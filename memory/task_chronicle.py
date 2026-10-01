"""Dated, searchable task outcome history. Secret payloads are intentionally redacted."""
from __future__ import annotations

import json
import re
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from core.user_paths import get_user_data_dir


CHRONICLE_FILE = get_user_data_dir() / "memory" / "task_chronicle.jsonl"
_lock = threading.RLock()
_SECRET = re.compile(r"(?i)(api[_ -]?key|token|secret|password)\s*[:=]\s*[^\s,;]+")


def _redact(value: Any) -> str:
    return _SECRET.sub(lambda match: match.group(1) + "=[REDACTED]", str(value or ""))[:2000]


class TaskChronicle:
    def record(self, task_id: str, goal: str, status: str, result: Any = "", error: str = "", project: str = "") -> dict:
        entry = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "task_id": task_id,
            "project": project[:80],
            "category": self._category(goal),
            "goal": _redact(goal),
            "status": status,
            "summary": _redact(error or result),
        }
        CHRONICLE_FILE.parent.mkdir(parents=True, exist_ok=True)
        with _lock, CHRONICLE_FILE.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
        return entry

    def search(self, query: str = "", limit: int = 20) -> list[dict]:
        if not CHRONICLE_FILE.exists():
            return []
        needle = query.lower().strip()
        with _lock:
            rows = []
            for line in CHRONICLE_FILE.read_text(encoding="utf-8").splitlines():
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not needle or needle in json.dumps(entry).lower():
                    rows.append(entry)
            return list(reversed(rows[-max(1, min(limit, 100)):]))

    @staticmethod
    def _category(goal: str) -> str:
        low = (goal or "").lower()
        if any(word in low for word in ("pinout", "datasheet", "sensor", "register", "wiring")):
            return "hardware_research"
        if any(word in low for word in ("api key", "token", "secret", "credential")):
            return "credential_retrieval"
        if any(word in low for word in ("test", "build", "code", "bug")):
            return "development"
        return "research"


chronicle = TaskChronicle()
