import os
import shutil
import sys
from functools import lru_cache
from pathlib import Path

def migrate_missing(source: Path, destination: Path) -> None:
    """Copy user data once, keeping every existing destination file."""
    if not source.is_dir() or source.resolve() == destination.resolve():
        return
    for folder in ("config", "memory"):
        for item in (source / folder).rglob("*"):
            if not item.is_file() or item.suffix in {".py", ".pyc", ".pyo"} or "__pycache__" in item.parts:
                continue
            target = destination / item.relative_to(source)
            if not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item, target)

@lru_cache(maxsize=1)
def get_user_data_dir() -> Path:
    app_data = os.getenv('LOCALAPPDATA', os.path.expanduser('~'))
    friday_dir = Path(app_data) / 'FridayAI'
    brahma_dir = Path(app_data) / 'BrahmaAI'

    friday_dir.mkdir(parents=True, exist_ok=True)
    marker = friday_dir / ".migration-1.0.1"
    if not marker.exists():
        # The running source checkout has the latest audio settings. Never ship it.
        if not getattr(sys, "frozen", False):
            migrate_missing(Path(__file__).resolve().parent.parent, friday_dir)
        migrate_missing(brahma_dir, friday_dir)
        marker.touch()
    return friday_dir
