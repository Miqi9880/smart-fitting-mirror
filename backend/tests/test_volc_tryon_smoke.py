from pathlib import Path

from backend.volc_tryon_smoke import REQ_KEY, build_submit_payload, load_dotenv, read_task_state, write_task_state


def test_load_dotenv_ignores_comments_and_quotes(tmp_path: Path):
    path = tmp_path / ".env"
    path.write_text("# private\nVOLC_ACCESS_KEY_ID='abc'\nVOLC_SECRET_ACCESS_KEY=def\n", encoding="utf-8")
    assert load_dotenv(path) == {"VOLC_ACCESS_KEY_ID": "abc", "VOLC_SECRET_ACCESS_KEY": "def"}


def test_base64_payload_uses_local_binary_order(tmp_path: Path):
    model = tmp_path / "model.png"
    garment = tmp_path / "garment.png"
    model.write_bytes(b"model")
    garment.write_bytes(b"garment")
    payload = build_submit_payload(model, garment)
    assert payload["req_key"] == REQ_KEY
    assert payload["req_image_store_type"] == 0
    assert payload["garment"] == {"data": [{"type": "upper"}]}
    assert payload["binary_data_base64"] == ["bW9kZWw=", "Z2FybWVudA=="]
    assert payload["inference_config"]["keep_lower"] is True


def test_task_state_round_trip_stores_no_credentials(tmp_path: Path):
    state_file = tmp_path / "private" / "last-task.json"
    state = {"task_id": "task-123", "submitted_at": "2026-10-22T00:00:00+00:00", "status": "submitted"}
    write_task_state(state_file, state)
    assert read_task_state(state_file) == state
