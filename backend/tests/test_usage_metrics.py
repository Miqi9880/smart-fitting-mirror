from datetime import datetime, timezone

import pytest

from backend.service import Photo, Store
from backend.usage_metrics import UsageMetrics, render_report


def test_metrics_roll_up_by_utc_day_with_whitelisted_dimensions(tmp_path):
    midnight = datetime(2026, 10, 29, tzinfo=timezone.utc).timestamp()
    metrics = UsageMetrics(tmp_path / "usage.sqlite3", clock=lambda: midnight + 3600)
    metrics.record(provider="mock", model="mock-static", mode="mock", metric="task_created")
    metrics.record(provider="mock", model="mock-static", mode="mock", metric="task_created")
    metrics.record(provider="aidge-cos", model="unknown", mode="real", metric="succeeded", duration_ms=2350)

    rows = metrics.read()
    mock = next(row for row in rows if row["provider"] == "mock")
    real = next(row for row in rows if row["provider"] == "aidge-cos")
    assert mock["day_utc"] == "2026-10-29" and mock["event_count"] == 2
    assert real["model"] == "unknown" and real["duration_ms_total"] == 2350
    assert all(not ({"session_id", "job_id", "upload_id", "task_id"} & row.keys()) for row in rows)


def test_read_only_report_does_not_create_a_missing_database(tmp_path):
    path = tmp_path / "absent" / "usage.sqlite3"
    metrics = UsageMetrics(path, initialize=False)

    assert metrics.read() == []
    assert not path.exists()
    assert "暂无记录" in render_report([])


def test_metrics_reject_uncontrolled_dimensions_and_negative_duration(tmp_path):
    metrics = UsageMetrics(tmp_path / "usage.sqlite3")

    with pytest.raises(ValueError, match="Unsupported usage metric dimension"):
        metrics.record(provider="job_123", model="unknown", mode="real", metric="task_created")
    with pytest.raises(ValueError, match="Inconsistent usage metric dimensions"):
        metrics.record(provider="mock", model="unknown", mode="real", metric="task_created")
    with pytest.raises(ValueError, match="duration_ms"):
        metrics.record(provider="mock", model="mock-static", mode="mock", metric="succeeded", duration_ms=-1)


def test_mock_failure_and_cancel_are_recorded_once_without_poll_counts(tmp_path):
    now = [0.0]
    metrics = UsageMetrics(tmp_path / "usage.sqlite3", clock=lambda: 1_798_000_000)
    store = Store(mode="failure", clock=lambda: now[0], monotonic=lambda: now[0],
                  catalog_profile="regression", metrics=metrics)
    session = store.create_session("local")
    auth = "Bearer " + session["session_token"]
    upload_id = store.save_photo(auth, Photo(b"local synthetic bytes", 2, 2, 0))["upload_id"]

    created, status = store.create_job(auth, upload_id, "upper_blue", "f69581ee-b5fb-4b10-ae26-151c48915da1")
    assert status == 202
    now[0] = 2.5
    assert store.get_job(auth, created["job_id"])["status"] == "failed"
    assert store.get_job(auth, created["job_id"])["status"] == "failed"

    cancelled_upload = store.save_photo(auth, Photo(b"local synthetic bytes", 2, 2, 0))["upload_id"]
    cancelled, status = store.create_job(auth, cancelled_upload, "upper_blue", "c1aee24a-2882-48f5-8f50-69a56eb819ee")
    assert status == 202
    store.get_job(auth, cancelled["job_id"], cancel=True)
    store.get_job(auth, cancelled["job_id"])

    rows = metrics.read()
    counts = {row["metric"]: row["event_count"] for row in rows}
    assert counts == {"task_created": 2, "cancelled": 1, "failed": 1}
    failure = next(row for row in rows if row["metric"] == "failed")
    assert failure["duration_ms_total"] == 2500 and failure["duration_count"] == 1
