"""Shared opt-in tee logging and percentage progress for repository Python scripts."""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path
from typing import TextIO


class _Tee:
    def __init__(self, primary: TextIO, log: TextIO) -> None:
        self.primary = primary
        self.log = log

    def write(self, value: str) -> int:
        self.primary.write(value)
        self.log.write(value)
        self.flush()
        return len(value)

    def flush(self) -> None:
        self.primary.flush()
        self.log.flush()

    def isatty(self) -> bool:
        return self.primary.isatty()


def configure_logging() -> None:
    log_value = os.environ.get("LEAN4ANDROID_LOG_FILE")
    if not log_value or os.environ.get("LEAN4ANDROID_LOG_ACTIVE") == "1":
        return
    path = Path(log_value).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    log = path.open("a", encoding="utf-8", buffering=1)
    sys.stdout = _Tee(sys.stdout, log)  # type: ignore[assignment]
    sys.stderr = _Tee(sys.stderr, log)  # type: ignore[assignment]
    os.environ["LEAN4ANDROID_LOG_ACTIVE"] = "1"
    print(f"[{time.strftime('%Y-%m-%dT%H:%M:%S%z')}] Logging combined stdout/stderr to {path}")


class Progress:
    def __init__(self, label: str, total_items: int, total_bytes: int = 0) -> None:
        self.label = label
        self.total_items = total_items
        self.total_bytes = total_bytes
        self.started = time.monotonic()
        self.last_report = self.started
        self.interval = max(1, int(os.environ.get("LEAN4ANDROID_PROGRESS_INTERVAL_SECONDS", "30")))
        print(f"Starting {label}: {total_items} items, {total_bytes} bytes")

    def update(self, completed_items: int, completed_bytes: int, *, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self.last_report < self.interval:
            return
        denominator = self.total_bytes if self.total_bytes else self.total_items
        numerator = completed_bytes if self.total_bytes else completed_items
        percent = 100 if denominator == 0 else min(100, numerator * 100 // denominator)
        elapsed = int(now - self.started)
        print(
            f"Progress {self.label}: {percent}% "
            f"({completed_items}/{self.total_items} items, {completed_bytes}/{self.total_bytes} bytes, {elapsed}s)"
        )
        self.last_report = now

    def complete(self, completed_items: int, completed_bytes: int) -> None:
        self.update(completed_items, completed_bytes, force=True)
        print(f"Completed {self.label} in {int(time.monotonic() - self.started)}s")
