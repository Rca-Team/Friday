# actions/unlock_device.py

import json
from pathlib import Path
from actions.friday_connect import connect_execute

def _get_settings() -> dict:
    try:
        from ui import APP_SETTINGS_FILE
        settings_file = APP_SETTINGS_FILE
    except ImportError:
        # Fallback if import fails
        settings_file = Path(__file__).resolve().parent.parent / "config" / "app_settings.json"
        
    if settings_file.exists():
        try:
            with open(settings_file, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}

def unlock_device(parameters: dict, response=None, player=None, session_memory=None) -> str:
    return json.dumps({
        "success": False,
        "error_code": "UNLOCK_REQUIRES_DEVICE_AUTH",
        "error": "Android requires the phone owner to unlock directly with its configured PIN, fingerprint, or face authentication.",
    })
