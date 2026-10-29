"""Anonymous, daily aggregate usage metrics for the local backend."""

from __future__ import annotations

import argparse
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


FEATURE = "tryon"
MODES = {"mock", "real"}
PROVIDERS = {"mock", "aidge-cos", "unknown"}
MODELS = {"mock-static", "unknown"}
METRICS = {
    "task_created",
    "provider_submit_attempt",
    "provider_submit_accepted",
    "cache_reused",
    "succeeded",
    "failed",
    "cancelled",
}
SQLITE_TIMEOUT_SECONDS = 1.0


class UsageMetrics:
    """Persist only daily aggregates; never accept task, session, or image identifiers."""

    def __init__(self, db_path: str | Path, *, clock: Callable[[], float] = time.time,
                 initialize: bool = True):
        self.db_path = Path(db_path)
        self.clock = clock
        if initialize:
            self.db_path.parent.mkdir(parents=True, exist_ok=True)
            with sqlite3.connect(self.db_path, timeout=SQLITE_TIMEOUT_SECONDS) as connection:
                connection.execute("PRAGMA journal_mode=WAL")
                connection.execute(
                    """CREATE TABLE IF NOT EXISTS daily_usage (
                        day_utc TEXT NOT NULL,
                        feature TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        metric TEXT NOT NULL,
                        event_count INTEGER NOT NULL DEFAULT 0,
                        duration_ms_total INTEGER NOT NULL DEFAULT 0,
                        duration_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (day_utc, feature, provider, model, mode, metric)
                    )"""
                )

    def record(self, *, provider: str, model: str, mode: str, metric: str,
               duration_ms: int | None = None) -> None:
        if provider not in PROVIDERS or model not in MODELS or mode not in MODES or metric not in METRICS:
            raise ValueError("Unsupported usage metric dimension")
        if (mode, provider, model) not in {
            ("mock", "mock", "mock-static"),
            ("real", "aidge-cos", "unknown"),
            ("real", "unknown", "unknown"),
        }:
            raise ValueError("Inconsistent usage metric dimensions")
        if duration_ms is not None and (not isinstance(duration_ms, int) or duration_ms < 0):
            raise ValueError("duration_ms must be a non-negative integer")

        day_utc = datetime.fromtimestamp(self.clock(), timezone.utc).date().isoformat()
        duration_total = duration_ms or 0
        duration_count = int(duration_ms is not None)
        with sqlite3.connect(self.db_path, timeout=SQLITE_TIMEOUT_SECONDS) as connection:
            connection.execute(
                """INSERT INTO daily_usage
                   (day_utc, feature, provider, model, mode, metric, event_count,
                    duration_ms_total, duration_count)
                   VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                   ON CONFLICT(day_utc, feature, provider, model, mode, metric)
                   DO UPDATE SET event_count = event_count + 1,
                     duration_ms_total = duration_ms_total + excluded.duration_ms_total,
                     duration_count = duration_count + excluded.duration_count""",
                (day_utc, FEATURE, provider, model, mode, metric, duration_total, duration_count),
            )

    def read(self) -> list[dict]:
        if not self.db_path.is_file():
            return []
        uri = self.db_path.resolve().as_uri() + "?mode=ro"
        with sqlite3.connect(uri, uri=True, timeout=SQLITE_TIMEOUT_SECONDS) as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(
                """SELECT day_utc, feature, provider, model, mode, metric, event_count,
                          duration_ms_total, duration_count
                   FROM daily_usage ORDER BY day_utc, feature, provider, model, mode, metric"""
            ).fetchall()
        return [dict(row) for row in rows]


def render_report(rows: list[dict]) -> str:
    lines = ["匿名用量统计（按 UTC 日汇总）"]
    if not rows:
        lines.append("暂无记录。")
    else:
        lines.append("日期 | 功能 | 提供方 | 模型标识 | 模式 | 指标 | 次数 | 耗时合计(ms)")
        for row in rows:
            duration = str(row["duration_ms_total"]) if row["duration_count"] else "未知/不适用"
            lines.append(
                f"{row['day_utc']} | {row['feature']} | {row['provider']} | {row['model']} | "
                f"{row['mode']} | {row['metric']} | {row['event_count']} | {duration}"
            )
    lines.extend([
        "缓存复用：未实现／未计量。",
        "费用分类（没有费率／账单依据，未知不代表0元）：",
        "  模型调用：未知",
        "  COS存储：未知",
        "  COS外网流量：未知",
        "  请求费用：未知",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="只读输出本机匿名试衣用量汇总")
    parser.add_argument("--db", type=Path, default=Path("data/usage-metrics.sqlite3"))
    args = parser.parse_args()
    print(render_report(UsageMetrics(args.db, initialize=False).read()))


if __name__ == "__main__":
    main()
