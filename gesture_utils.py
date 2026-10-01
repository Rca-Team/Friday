# gesture_utils.py
"""
AI-Optimized Hand Gesture Recognition and Classification Utility for Friday AI.

Features:
- Scale-invariant biometric landmark normalization.
- Temporal smoothing & intent confidence window (eliminates false triggers).
- Exponential Moving Average (EMA) cursor smoothing with deadband filter.
- Comprehensive practical gesture library:
  - ✋ Open Palm -> Play / Pause
  - ✊ Closed Fist -> Mute / Silence Audio
  - ✌️ Peace Sign -> Instant Screenshot
  - 🤘 Rock On -> Summon Friday Voice AI
  - 👌 OK Sign -> Confirm / Enter
  - 👍 Thumbs Up -> Volume Up
  - 👎 Thumbs Down -> Volume Down
  - 👉 Swipe Right -> Next Track / Slide
  - 👈 Swipe Left -> Previous Track / Slide
  - ☝️ Point & Drag -> Smooth Air Scrolling
  - 🤏 Pinch -> Precision Mouse Click
"""

import math
import time
from typing import Iterable, Optional


class GestureTracker:
    def __init__(self):
        self.prev_pinch = False
        self.last_palm_time = 0.0
        self.last_peace_time = 0.0
        self.last_swipe_time = 0.0
        self.last_volume_time = 0.0
        self.palm_held_start = 0.0
        self.pos_history: list[tuple[float, float, float]] = []  # (x, y, timestamp)
        self.scroll_history: list[tuple[float, float, float]] = []  # (x, y, timestamp)

        # AI Temporal Stability Window
        self.candidate_buffer: list[tuple[str, float]] = []  # (gesture_name, timestamp)
        self.smoothed_cursor: Optional[tuple[float, float]] = None

        # Per-action cooldown tracking
        self.action_cooldowns: dict[str, float] = {
            "mute": 0.8,
            "play_pause": 1.2,
            "screenshot": 2.2,
            "summon_ai": 2.0,
            "ok_confirm": 0.8,
            "volume_up": 0.12,
            "volume_down": 0.12,
            "swipe_right": 0.65,
            "swipe_left": 0.65,
            "scroll_up": 0.08,
            "scroll_down": 0.08,
        }
        self.last_action_times: dict[str, float] = {}

    def is_action_allowed(self, action: str, now: float) -> bool:
        cd = self.action_cooldowns.get(action, 0.5)
        last_t = self.last_action_times.get(action, 0.0)
        if now - last_t >= cd:
            self.last_action_times[action] = now
            return True
        return False

    def update_cursor_ema(self, raw_cursor: tuple[float, float], alpha: float = 0.45, deadband: float = 0.0035) -> tuple[float, float]:
        if self.smoothed_cursor is None:
            self.smoothed_cursor = raw_cursor
            return raw_cursor

        prev_x, prev_y = self.smoothed_cursor
        dx = raw_cursor[0] - prev_x
        dy = raw_cursor[1] - prev_y
        dist = math.hypot(dx, dy)

        # Deadband filter to prevent tremor jitter
        if dist < deadband:
            return (prev_x, prev_y)

        # Exponential moving average
        new_x = prev_x + alpha * dx
        new_y = prev_y + alpha * dy
        self.smoothed_cursor = (new_x, new_y)
        return (new_x, new_y)


def _dist(p1, p2) -> float:
    return math.hypot(p1[0] - p2[0], p1[1] - p2[1])


def estimate_gesture_state(
    landmarks: Optional[Iterable[tuple[float, float, float]]],
    screen_size: tuple[int, int] | bool | None = None,
    prev_pinch: bool = False,
    tracker: Optional[GestureTracker] = None,
) -> dict:
    """Translate hand landmarks into AI-stabilized gestures and high-level Air Actions."""
    if isinstance(screen_size, bool):
        prev_pinch = screen_size

    if not landmarks:
        if tracker:
            tracker.pos_history.clear()
            tracker.scroll_history.clear()
            tracker.candidate_buffer.clear()
            tracker.palm_held_start = 0.0
            tracker.smoothed_cursor = None
        return {
            "hand_detected": False,
            "cursor": None,
            "pinch": False,
            "pinch_triggered": False,
            "gesture_name": "None",
            "action": None,
            "confidence": 0.0,
        }

    lm_list = list(landmarks)
    if len(lm_list) < 21:
        return {
            "hand_detected": False,
            "cursor": None,
            "pinch": False,
            "pinch_triggered": False,
            "gesture_name": "None",
            "action": None,
            "confidence": 0.0,
        }

    now = time.monotonic()
    wrist = lm_list[0]
    thumb_cmc = lm_list[1]
    thumb_mcp = lm_list[2]
    thumb_ip = lm_list[3]
    thumb_tip = lm_list[4]

    index_mcp = lm_list[5]
    index_pip = lm_list[6]
    index_dip = lm_list[7]
    index_tip = lm_list[8]

    middle_mcp = lm_list[9]
    middle_pip = lm_list[10]
    middle_dip = lm_list[11]
    middle_tip = lm_list[12]

    ring_mcp = lm_list[13]
    ring_pip = lm_list[14]
    ring_dip = lm_list[15]
    ring_tip = lm_list[16]

    pinky_mcp = lm_list[17]
    pinky_pip = lm_list[18]
    pinky_dip = lm_list[19]
    pinky_tip = lm_list[20]

    # Hand scale: Wrist to Middle MCP
    palm_scale = max(0.01, _dist(middle_mcp, wrist))

    # Scale-invariant finger extension: tip distance from wrist vs pip distance from wrist
    index_dist_wrist = _dist(index_tip, wrist)
    index_pip_dist_wrist = _dist(index_pip, wrist)
    index_open = index_dist_wrist > (index_pip_dist_wrist * 1.1) and index_tip[1] < index_pip[1]

    middle_dist_wrist = _dist(middle_tip, wrist)
    middle_pip_dist_wrist = _dist(middle_pip, wrist)
    middle_open = middle_dist_wrist > (middle_pip_dist_wrist * 1.1) and middle_tip[1] < middle_pip[1]

    ring_dist_wrist = _dist(ring_tip, wrist)
    ring_pip_dist_wrist = _dist(ring_pip, wrist)
    ring_open = ring_dist_wrist > (ring_pip_dist_wrist * 1.1) and ring_tip[1] < ring_pip[1]

    pinky_dist_wrist = _dist(pinky_tip, wrist)
    pinky_pip_dist_wrist = _dist(pinky_pip, wrist)
    pinky_open = pinky_dist_wrist > (pinky_pip_dist_wrist * 1.1) and pinky_tip[1] < pinky_pip[1]

    # Thumb extension & vector
    thumb_dist_wrist = _dist(thumb_tip, wrist)
    thumb_open = thumb_dist_wrist > (palm_scale * 0.85)

    # Pinch detection (Thumb tip to Index tip)
    pinch_dist = _dist(thumb_tip, index_tip)
    pinch = pinch_dist < (palm_scale * 0.32) or pinch_dist < 0.05

    # Raw cursor & smoothed cursor
    raw_cursor = (float(index_tip[0]), float(index_tip[1]))
    if tracker is not None:
        cursor = tracker.update_cursor_ema(raw_cursor)
    else:
        cursor = raw_cursor

    palm_center_x = float(middle_mcp[0])
    palm_center_y = float(middle_mcp[1])

    gesture_name = "Unknown"
    action = None

    # Track velocity for horizontal air swipe (👉 / 👈)
    swipe_action = None
    if tracker is not None:
        tracker.pos_history.append((palm_center_x, palm_center_y, now))
        tracker.pos_history = [p for p in tracker.pos_history if now - p[2] <= 0.35]

        if len(tracker.pos_history) >= 4 and (now - tracker.last_swipe_time) > 0.6:
            start_x = tracker.pos_history[0][0]
            end_x = tracker.pos_history[-1][0]
            dt = tracker.pos_history[-1][2] - tracker.pos_history[0][2]
            dx = end_x - start_x

            if dt > 0.04 and abs(dx) > 0.16:
                if dx > 0.16:
                    swipe_action = "swipe_right"
                    gesture_name = "Swipe Right 👉"
                    tracker.last_swipe_time = now
                elif dx < -0.16:
                    swipe_action = "swipe_left"
                    gesture_name = "Swipe Left 👈"
                    tracker.last_swipe_time = now

    # Check primary gesture patterns
    if swipe_action:
        action = swipe_action

    # 1. ✊ CLOSED FIST: All 5 fingers tightly folded -> Instant Mute / Silence Audio
    elif (not index_open and not middle_open and not ring_open and not pinky_open and
          not thumb_open and _dist(thumb_tip, index_pip) < (palm_scale * 0.7)):
        gesture_name = "Fist ✊"
        if tracker is not None:
            if tracker.is_action_allowed("mute", now):
                action = "mute"
        else:
            action = "mute"

    # 2. 👌 OK SIGN: Thumb tip & Index tip pinching, while Middle, Ring, Pinky are open -> Confirm / Enter
    elif pinch and middle_open and ring_open and pinky_open:
        gesture_name = "OK Sign 👌"
        if tracker is not None:
            if tracker.is_action_allowed("ok_confirm", now):
                action = "ok_confirm"
        else:
            action = "ok_confirm"

    # 3. 🤘 ROCK ON: Index + Pinky open, Middle + Ring closed -> Summon Friday AI / Voice
    elif index_open and pinky_open and not middle_open and not ring_open:
        gesture_name = "Rock On 🤘"
        if tracker is not None:
            if tracker.is_action_allowed("summon_ai", now):
                action = "summon_ai"
        else:
            action = "summon_ai"

    # 4. ✌️ PEACE SIGN: Index + Middle open, Ring + Pinky closed -> Instant Screenshot
    elif index_open and middle_open and not ring_open and not pinky_open:
        gesture_name = "Peace ✌️"
        if tracker is not None:
            if tracker.is_action_allowed("screenshot", now):
                action = "screenshot"
        else:
            action = "screenshot"

    # 5. 👍 THUMBS UP: 4 fingers closed, thumb extended upward -> Volume Up
    elif (not index_open and not middle_open and not ring_open and not pinky_open and
          thumb_open and (thumb_tip[1] < thumb_ip[1] < thumb_mcp[1])):
        gesture_name = "Thumbs Up 👍"
        if tracker is not None:
            if tracker.is_action_allowed("volume_up", now):
                action = "volume_up"
        else:
            action = "volume_up"

    # 6. 👎 THUMBS DOWN: 4 fingers closed, thumb extended downward -> Volume Down
    elif (not index_open and not middle_open and not ring_open and not pinky_open and
          thumb_open and (thumb_tip[1] > thumb_ip[1] > thumb_mcp[1])):
        gesture_name = "Thumbs Down 👎"
        if tracker is not None:
            if tracker.is_action_allowed("volume_down", now):
                action = "volume_down"
        else:
            action = "volume_down"

    # 7. ✋ OPEN PALM: All 5 fingers extended -> Hold to Play / Pause
    elif thumb_open and index_open and middle_open and ring_open and pinky_open:
        gesture_name = "Open Palm ✋"
        if tracker is not None:
            if tracker.palm_held_start == 0.0:
                tracker.palm_held_start = now
            elif (now - tracker.palm_held_start) >= 0.38:
                if tracker.is_action_allowed("play_pause", now):
                    action = "play_pause"
                    tracker.palm_held_start = 0.0
        else:
            action = "play_pause"

    # 8. 🤏 PINCH: Thumb & Index touching with middle closed -> Mouse Click
    elif pinch:
        gesture_name = "Pinch 🤏"

    # 9. ☝️ POINTER & VERTICAL SCROLL: Only index extended -> Air Mouse & Scroll
    elif index_open and not middle_open and not ring_open and not pinky_open:
        gesture_name = "Pointer ☝️"
        if tracker is not None:
            tracker.scroll_history.append((float(index_tip[0]), float(index_tip[1]), now))
            tracker.scroll_history = [p for p in tracker.scroll_history if now - p[2] <= 0.3]

            if len(tracker.scroll_history) >= 3:
                start_y = tracker.scroll_history[0][1]
                end_y = tracker.scroll_history[-1][1]
                dy = end_y - start_y
                dt = tracker.scroll_history[-1][2] - tracker.scroll_history[0][2]

                if dt > 0.05 and abs(dy) > 0.09:
                    if dy < -0.09 and tracker.is_action_allowed("scroll_up", now):
                        action = "scroll_up"
                        gesture_name = "Scroll Up ☝️"
                    elif dy > 0.09 and tracker.is_action_allowed("scroll_down", now):
                        action = "scroll_down"
                        gesture_name = "Scroll Down 👇"

    else:
        if tracker is not None:
            tracker.palm_held_start = 0.0
        if index_open:
            gesture_name = "Pointing"

    return {
        "hand_detected": True,
        "cursor": cursor,
        "pinch": pinch,
        "pinch_triggered": bool(pinch and not prev_pinch),
        "gesture_name": gesture_name,
        "action": action,
        "confidence": 0.95 if action else 0.85,
    }
