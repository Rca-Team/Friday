"""
memory/config_manager.py - Centralized configuration access for Friday AI.
Handles persistent app settings, audio device selection, push-to-talk,
and AI options. Backed by config/app_settings.json.
"""

from __future__ import annotations
import json
from pathlib import Path
from typing import Any, Dict
from core.user_paths import get_user_data_dir

BSE_DIR = Path(__file__).resolve().parent.parent
CONFIG_DIR = get_user_data_dir() / "config"
SETTINGS_FILE = CONFIG_DIR / "app_settings.json"


def _ensure_config() -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)


def load_settings() -> Dict[str, Any]:
    _ensure_config()
    if not SETTINGS_FILE.exists():
        return {}
    try:
        with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_settings(data: Dict[str, Any]) -> None:
    _ensure_config()
    current = load_settings()
    current.update(data)
    try:
        with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
            json.dump(current, f, indent=4)
    except Exception as e:
        print(f"[CONFIG] Error saving settings: {e}")


def get_setting(key: str, default: Any = None) -> Any:
    return load_settings().get(key, default)


def set_setting(key: str, value: Any) -> None:
    save_settings({key: value})


# --- Audio Devices
 

def get_input_device() -> str:
    return str(get_setting("input_device", "") or "")


def set_input_device(name: str) -> None:
    set_setting("input_device", name)


def get_output_device() -> str:
    return str(get_setting("output_device", "") or "")


def set_output_device(name: str) -> None:
    set_setting("output_device", name)


# --- Push-to-Talk

def get_push_to_talk_enabled() -> bool:
    return bool(get_setting("push_to_talk_enabled", False))


def set_push_to_talk_enabled(enabled: bool) -> None:
    set_setting("push_to_talk_enabled", bool(enabled))


# --- Wake Word & Briefing

def get_wake_word_enabled() -> bool:
    return bool(get_setting("wake_word_enabled", True))


def save_wake_word_enabled(enabled: bool) -> None:
    set_setting("wake_word_enabled", bool(enabled))


def get_brief_enabled() -> bool:
    return bool(get_setting("brief_enabled", True))


# --- Voice Persona (Friday vs Jarvis)

def get_voice_persona() -> str:
    """Returns 'friday' or 'jarvis'."""
    val = str(get_setting("voice_persona", "friday") or "friday").strip().lower()
    return "jarvis" if "jarvis" in val else "friday"


def set_voice_persona(persona: str) -> str:
    p = "jarvis" if str(persona).strip().lower() in ("jarvis", "paul bettany", "british male", "male") else "friday"
    set_setting("voice_persona", p)
    return p


def get_tts_voice_id() -> str:
    """Returns the Edge TTS ShortName for the active voice persona."""
    if get_voice_persona() == "jarvis":
        return "en-GB-RyanNeural"  # British male (Paul Bettany JARVIS style)
    return "en-IE-EmilyNeural"      # Irish female (Kerry Condon OG FRIDAY style)


def get_live_voice_name() -> str:
    """Returns Gemini Live prebuilt voice name for the active voice persona."""
    if get_voice_persona() == "jarvis":
        return "Fenrir"             # Deep distinguished male voice
    return "Aoede"                  # Sharp crisp female voice


# --- Smart Conversational Awareness Filter

def get_conversational_filter_enabled() -> bool:
    """Returns True if smart talking-to-me filter is enabled (default True)."""
    return bool(get_setting("conversational_filter_enabled", True))


def set_conversational_filter_enabled(enabled: bool) -> None:
    set_setting("conversational_filter_enabled", bool(enabled))


# --- Clipboard Proactive Reading Settings

def get_auto_clipboard_enabled() -> bool:
    """Returns True if proactive clipboard vocalization is enabled (default False)."""
    return bool(get_setting("auto_clipboard_enabled", False))


def set_auto_clipboard_enabled(enabled: bool) -> None:
    set_setting("auto_clipboard_enabled", bool(enabled))


# --- Tony Stark Visual Designs Matrix

STARK_DESIGNS = {
    "nanotech": {
        "id": "nanotech",
        "name": "Mark L Nanotech RT",
        "tagline": "Endgame Concentric Cyan Quantum Nano-Rings",
        "color_hex": "#00E5FF",
        "primary_rgb": (0, 229, 255),
        "secondary_rgb": (0, 255, 200),
        "description": "High-frequency electric cyan arc reactor with dual counter-spinning nano-rings and orbiting quantum particles."
    },
    "mark_3": {
        "id": "mark_3",
        "name": "Mark III Classic Armor",
        "tagline": "Iconic Hotrod Red & Stark Titanium Gold",
        "color_hex": "#D42B2B",
        "primary_rgb": (220, 38, 38),
        "secondary_rgb": (255, 193, 7),
        "description": "The quintessential Iron Man aesthetic with hotrod crimson armor rim, gold power arcs, and unibeam repulsor core."
    },
    "hulkbuster": {
        "id": "hulkbuster",
        "name": "Veronica Hulkbuster MK-XLIV",
        "tagline": "Industrial Magma Amber & Reinforced Hex-Armor",
        "color_hex": "#FF8C00",
        "primary_rgb": (255, 130, 0),
        "secondary_rgb": (255, 185, 0),
        "description": "Heavy hydraulic containment ring with mechanical armor teeth, orbital Veronica beacons, and deep magma core."
    },
    "edith": {
        "id": "edith",
        "name": "E.D.I.T.H. Tactical HUD",
        "tagline": "Even Dead I'm The Hero - AR Cobalt Hologram",
        "color_hex": "#007AFF",
        "primary_rgb": (0, 140, 255),
        "secondary_rgb": (0, 225, 255),
        "description": "Tactical augmented reality HUD with 360-degree radar sweep beam, target acquisition reticles, and perimeter telemetry ticks."
    },
    "stealth": {
        "id": "stealth",
        "name": "Mark II Supersonic Stealth",
        "tagline": "Polished Titanium Silver & Cold Fusion White",
        "color_hex": "#8E9CA8",
        "primary_rgb": (215, 225, 240),
        "secondary_rgb": (245, 250, 255),
        "description": "Unpainted aerodynamic chrome titanium finish with supersonic Mach telemetry compass marks and pure arc-white fusion glow."
    },
    "palladium": {
        "id": "palladium",
        "name": "Mark I Cave Palladium Core",
        "tagline": "10 Magnetic Copper Coils & Incandescent Glow",
        "color_hex": "#E59838",
        "primary_rgb": (255, 195, 75),
        "secondary_rgb": (215, 115, 45),
        "description": "Tony's original 2008 reactor built in the cave, featuring 10 authentic radial copper wire electromagnetic coils and warm palladium core."
    }
}


def get_stark_design() -> str:
    """Returns the active Tony Stark visual design key (default 'nanotech')."""
    d = str(get_setting("stark_design", "nanotech") or "nanotech").strip().lower()
    return d if d in STARK_DESIGNS else "nanotech"


def set_stark_design(design_key: str) -> str:
    """Sets active Tony Stark visual design key and returns the normalized key."""
    k = str(design_key or "").strip().lower()
    if any(x in k for x in ("nanotech", "mark 50", "mark l", "arc", "cyan", "blue", "infinity")):
        k = "nanotech"
    elif any(x in k for x in ("mark 3", "mark iii", "classic", "hotrod", "red", "gold", "iron man")):
        k = "mark_3"
    elif any(x in k for x in ("hulkbuster", "veronica", "amber", "orange", "heavy", "xliv")):
        k = "hulkbuster"
    elif any(x in k for x in ("edith", "glasses", "tactical", "hud", "radar", "target")):
        k = "edith"
    elif any(x in k for x in ("mark 2", "mark ii", "stealth", "silver", "chrome", "titanium", "white")):
        k = "stealth"
    elif any(x in k for x in ("mark 1", "mark i", "palladium", "cave", "copper", "coil", "heart")):
        k = "palladium"
    else:
        k = "nanotech"
        
    set_setting("stark_design", k)
    return k


def get_stark_theme_sync_enabled() -> bool:
    """Returns True if app theme color automatically syncs with Stark design (default True)."""
    return bool(get_setting("stark_theme_sync_enabled", True))


def set_stark_theme_sync_enabled(enabled: bool) -> None:
    set_setting("stark_theme_sync_enabled", bool(enabled))





