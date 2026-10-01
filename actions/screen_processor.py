from core.user_paths import get_user_data_dir
import asyncio
import base64
import io
import json
import os
import sys
import time
import threading
from pathlib import Path
from typing import Optional, Dict, Any

import cv2

try:
    import PIL.Image
    import PIL.ImageGrab
    _PIL_OK = True
except ImportError:
    _PIL_OK = False

try:
    import mss
    import mss.tools
except ImportError:
    mss = None

class _LivePlaceholder:
    def is_ready(self) -> bool:
        return False
    def analyze(self, image_bytes: bytes, mime_type: str, user_text: str) -> None:
        pass

_live = _LivePlaceholder()

def _ensure_started(player=None) -> None:
    pass

from google import genai
from google.genai import types

def get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent

BASE_DIR        = get_base_dir()
API_CONFIG_PATH = get_user_data_dir() / "config" / "api_keys.json"

IMG_MAX_W = 1280
IMG_MAX_H = 720
JPEG_Q    = 80
_GEMINI_VISION_COOLDOWN_UNTIL = 0.0

SYSTEM_PROMPT = (
    "You are FRIDAY, Gaurav's practical developer and robotics partner. Treat the image as private working context, "
    "not as something to narrate. Infer what the user is trying to accomplish from their question, the active window, "
    "and visible evidence. Lead with the answer, diagnosis, or next action—never open with phrases such as 'I see on "
    "your screen' or a generic UI description. For errors, state the likely root cause, give the smallest concrete fix, "
    "and name one verification step. For code or CAD/robotics work, give the decision or command the user needs now. "
    "Only mention a visible detail when it proves the answer. Be direct, natural, and concise; do not invent text that "
    "is not legible in the image."
)


def _get_api_key() -> str:
    try:
        with open(API_CONFIG_PATH, "r", encoding="utf-8") as f:
            keys = json.load(f)
        key = keys.get("gemini_api_key", "").strip()
        if not key:
            raise ValueError("gemini_api_key not found")
        return key
    except Exception as e:
        raise RuntimeError(f"Could not load API key: {e}")


def _is_quota_error(exc: Exception) -> bool:
    message = str(exc).lower()
    return any(marker in message for marker in (
        "resource_exhausted", "quota exceeded", "too many requests", "429", "rate limit",
    ))


def _get_camera_index() -> int:
    try:
        with open(API_CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
        if "camera_index" in cfg:
            return int(cfg["camera_index"])
    except Exception:
        pass

    for idx in range(4):
        cap = cv2.VideoCapture(idx, cv2.CAP_DSHOW)
        if not cap.isOpened():
            cap.release()
            continue
        ret, frame = cap.read()
        cap.release()
        if ret and frame is not None:
            return idx
    return 0


def _capture_camera() -> bytes:
    camera_index = _get_camera_index()
    cap = cv2.VideoCapture(camera_index, cv2.CAP_DSHOW)
    if not cap.isOpened():
        raise RuntimeError(f"Camera could not be opened at index {camera_index}")
    for _ in range(5):
        cap.read()
    ret, frame = cap.read()
    cap.release()
    if not ret or frame is None:
        raise RuntimeError("Could not capture camera frame.")
    ret, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_Q])
    if not ret:
        raise RuntimeError("Could not encode camera frame.")
    return buf.tobytes()


def _capture_screen_robust(player=None) -> bytes:
    """
    4-Tier High-Reliability Screen Capture Pipeline:
    1. UI Threadsafe QScreen Capture (best fidelity & DPI support on Windows)
    2. Direct PyQt6 QScreen Capture
    3. PIL ImageGrab
    4. MSS Desktop Capture
    """
    # Tier 1: Try UI player capture
    if player and hasattr(player, "capture_screen_bytes"):
        try:
            b = player.capture_screen_bytes()
            if b and len(b) > 1000:
                print(f"[ScreenProcess] Captured via UI engine ({len(b)} bytes)")
                return b
        except Exception as e:
            print(f"[ScreenProcess] UI capture failed: {e}")

    # Tier 2: Direct PyQt6 QScreen Capture
    try:
        from PyQt6.QtWidgets import QApplication
        from PyQt6.QtCore import QBuffer, QIODevice, Qt
        app = QApplication.instance()
        if not app:
            app = QApplication(sys.argv)
        screen = QApplication.primaryScreen()
        if screen:
            pixmap = screen.grabWindow(0)
            if not pixmap.isNull():
                pixmap = pixmap.scaled(IMG_MAX_W, IMG_MAX_H, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                buf = QBuffer()
                buf.open(QIODevice.OpenModeFlag.WriteOnly)
                pixmap.save(buf, "JPEG", JPEG_Q)
                data = bytes(buf.data().data())
                if data and len(data) > 1000:
                    print(f"[ScreenProcess] Captured via PyQt6 QScreen ({len(data)} bytes)")
                    return data
    except Exception as e:
        print(f"[ScreenProcess] Direct QScreen capture failed: {e}")

    # Tier 3: PIL ImageGrab
    if _PIL_OK:
        try:
            from PIL import ImageGrab
            img = ImageGrab.grab(all_screens=True).convert("RGB")
            img.thumbnail((IMG_MAX_W, IMG_MAX_H))
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=JPEG_Q)
            data = buf.getvalue()
            if data and len(data) > 1000:
                print(f"[ScreenProcess] Captured via PIL ImageGrab ({len(data)} bytes)")
                return data
        except Exception as e:
            print(f"[ScreenProcess] PIL ImageGrab failed: {e}")

    # Tier 4: MSS
    try:
        import mss, mss.tools
        with mss.mss() as sct:
            monitors = sct.monitors
            mon = monitors[1] if len(monitors) > 1 else monitors[0]
            shot = sct.grab(mon)
            raw = mss.tools.to_png(shot.rgb, shot.size)
            if _PIL_OK:
                img = PIL.Image.open(io.BytesIO(raw)).convert("RGB")
                img.thumbnail((IMG_MAX_W, IMG_MAX_H))
                buf = io.BytesIO()
                img.save(buf, format="JPEG", quality=JPEG_Q)
                return buf.getvalue()
            return raw
    except Exception as e:
        print(f"[ScreenProcess] MSS capture failed: {e}")

    return b""


def _capture_screenshot() -> bytes:
    if mss is not None:
        try:
            with mss.mss() as sct:
                monitors = sct.monitors
                mon = monitors[1] if len(monitors) > 1 else monitors[0]
                shot = sct.grab(mon)
                raw = mss.tools.to_png(shot.rgb, shot.size)
                if _PIL_OK:
                    img = PIL.Image.open(io.BytesIO(raw)).convert("RGB")
                    img.thumbnail((IMG_MAX_W, IMG_MAX_H))
                    buf = io.BytesIO()
                    img.save(buf, format="JPEG", quality=JPEG_Q)
                    return buf.getvalue()
                return raw
        except Exception:
            pass
    return _capture_screen_robust()


def screen_process(
    parameters: dict | None = None,
    response: str | None = None,
    player: Any = None,
    session_memory: Any = None,
    image_bytes: bytes | None = None,
) -> Any:
    """
    Task-oriented Gemini Vision reasoning for FRIDAY AI. Captures the active
    workspace only when the user asks a question that benefits from it, then
    returns a diagnosis or an actionable answer instead of a screen narration.
    """
    global _GEMINI_VISION_COOLDOWN_UNTIL
    params = parameters or {}
    user_text = (params.get("text") or params.get("query") or params.get("prompt") or "").strip()
    if not user_text:
        user_text = "What should I do next in the task currently open on my screen?"

    angle = str(params.get("angle") or "screen").lower().strip()
    print(f"[ScreenProcess] Starting vision analysis: angle='{angle}', prompt='{user_text[:60]}'")

    # Show scanning pulse in Friday HUD
    if player and hasattr(player, "set_scanning"):
        try:
            player.set_scanning(True, "SCANNING SCREEN...")
        except Exception:
            pass

    try:
        # 1. Capture Image
        if angle == "camera":
            img_data = _capture_camera()
            mime_type = "image/jpeg"
        elif angle in ("active_window", "window") or params.get("target") == "active_window":
            from core.window_context import capture_active_window_screenshot
            img_data = capture_active_window_screenshot() or _capture_screen_robust(player)
            mime_type = "image/jpeg"
        else:
            img_data = image_bytes or _capture_screen_robust(player)
            mime_type = "image/jpeg"

        if _live is not None and getattr(_live, "is_ready", lambda: False)():
            _live.analyze(img_data, mime_type, user_text)
            if player and hasattr(player, "set_scanning"):
                player.set_scanning(False, "")
            return True

        if not img_data or len(img_data) < 500:
            err_msg = "Could not capture the screen. Please check display permissions."
            print(f"[ScreenProcess] [FAIL] {err_msg}")
            if player and hasattr(player, "set_scanning"):
                player.set_scanning(False, "")
            return err_msg

        # 2. Enrich prompt with foreground window context
        window_ctx = ""
        if angle != "camera":
            try:
                from core.window_context import get_foreground_window_info
                w_info = get_foreground_window_info()
                if w_info and w_info.get("title"):
                    w_title = w_info.get("title", "").strip()
                    if w_title and "friday" not in w_title.lower():
                        window_ctx = f"\n[User's Active Focused Window: '{w_title}']"
            except Exception:
                pass

        full_prompt = (
            f"{SYSTEM_PROMPT}\n{window_ctx}\n\n"
            f"User request: {user_text}\n\n"
            "Response format: Answer first. If this is an error, use: Cause: … / Fix now: … / Check: … . "
            "If the user asks what to do, give the next best action and only the essential steps."
        )

        # 3. Vision Analysis via Gemini. A quota failure applies to the project,
        # so do not waste additional requests cycling through equivalent models.
        analysis_text = ""
        gemini_quota_limited = time.time() < _GEMINI_VISION_COOLDOWN_UNTIL
        if time.time() < _GEMINI_VISION_COOLDOWN_UNTIL:
            print("[ScreenProcess] Gemini Vision quota cooldown active; using fallback.")
        else:
            try:
                api_key = _get_api_key()
                client = genai.Client(api_key=api_key, http_options={"timeout": 20000})
                for model_candidate in ("gemini-3.8-flash", "gemini-flash-latest"):
                    try:
                        resp = client.models.generate_content(
                            model=model_candidate,
                            contents=[types.Part.from_bytes(data=img_data, mime_type=mime_type), full_prompt],
                            config={"max_output_tokens": 512},
                        )
                        if resp and resp.text:
                            analysis_text = resp.text.strip()
                            print(f"[ScreenProcess] Gemini ({model_candidate}) analysis complete.")
                            break
                    except Exception as model_error:
                        if _is_quota_error(model_error):
                            _GEMINI_VISION_COOLDOWN_UNTIL = time.time() + 60.0
                            gemini_quota_limited = True
                            print("[ScreenProcess] Gemini Vision quota reached; switching to fallback for 60 seconds.")
                            break
                        print(f"[ScreenProcess] Model {model_candidate} warning: {model_error}")
            except Exception as api_err:
                print(f"[ScreenProcess] Gemini Vision API error: {api_err}")

        # 4. Fallback to OpenRouter Vision if Gemini fails
        if not analysis_text:
            try:
                from or_client import client as orc
                print("[ScreenProcess] Attempting OpenRouter vision fallback...")
                # OpenRouter accepts a base64 string, not raw bytes. The former
                # call passed its arguments in reverse order and could never
                # serialize the image into the JSON request.
                image_b64 = base64.b64encode(img_data).decode("ascii")
                analysis_text = orc.vision(full_prompt, image_b64, mime_type, SYSTEM_PROMPT, max_tokens=512)
            except Exception as or_err:
                print(f"[ScreenProcess] OpenRouter vision fallback error: {or_err}")

        if not analysis_text:
            analysis_text = (
                "Gemini Vision has reached this project's current quota, and the backup vision provider did not respond. "
                "Wait for the Gemini quota to reset or check the OpenRouter Vision key in Settings → AI & Vision."
                if gemini_quota_limited else
                "I captured your screen, but the vision service could not generate an answer right now."
            )

        # 5. Update Friday UI Deliverables & Log
        if player:
            if hasattr(player, "set_scanning"):
                player.set_scanning(False, "")
            if hasattr(player, "write_log"):
                player.write_log(f"Vision: {analysis_text[:120]}...")
            if hasattr(player, "show_hud_deliverable"):
                try:
                    player.show_hud_deliverable(
                        title="SCREEN VISION RECOGNITION",
                        summary=analysis_text,
                        file_path=None,
                        kind="screen_process"
                    )
                except Exception:
                    pass

        return analysis_text

    except Exception as e:
        import traceback
        traceback.print_exc()
        if player and hasattr(player, "set_scanning"):
            player.set_scanning(False, "")
        return f"Screen recognition encountered an error: {e}"


def warmup_session(player=None):
    """No-op warmup for backward compatibility."""
    pass
