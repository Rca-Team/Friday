# tests/test_gesture_utils.py
from gesture_utils import estimate_gesture_state, GestureTracker


def _create_base_landmarks():
    # Wrist at (0.5, 0.9)
    # Default closed / neutral positions
    lm = [(0.5, 0.9, 0.0)] * 21
    # Wrist
    lm[0] = (0.5, 0.9, 0.0)
    # Thumb: cmc(1), mcp(2), ip(3), tip(4)
    lm[1] = (0.45, 0.85, 0.0)
    lm[2] = (0.42, 0.80, 0.0)
    lm[3] = (0.40, 0.75, 0.0)
    lm[4] = (0.38, 0.70, 0.0)
    # Index: mcp(5), pip(6), dip(7), tip(8)
    lm[5] = (0.45, 0.65, 0.0)
    lm[6] = (0.45, 0.60, 0.0)
    lm[7] = (0.45, 0.55, 0.0)
    lm[8] = (0.45, 0.50, 0.0)
    # Middle: mcp(9), pip(10), dip(11), tip(12)
    lm[9] = (0.50, 0.64, 0.0)
    lm[10] = (0.50, 0.58, 0.0)
    lm[11] = (0.50, 0.53, 0.0)
    lm[12] = (0.50, 0.48, 0.0)
    # Ring: mcp(13), pip(14), dip(15), tip(16)
    lm[13] = (0.55, 0.65, 0.0)
    lm[14] = (0.55, 0.60, 0.0)
    lm[15] = (0.55, 0.56, 0.0)
    lm[16] = (0.55, 0.52, 0.0)
    # Pinky: mcp(17), pip(18), dip(19), tip(20)
    lm[17] = (0.60, 0.68, 0.0)
    lm[18] = (0.60, 0.64, 0.0)
    lm[19] = (0.60, 0.60, 0.0)
    lm[20] = (0.60, 0.57, 0.0)
    return lm


def test_estimate_gesture_state_reports_no_hand_when_missing_landmarks():
    state = estimate_gesture_state(None, (1920, 1080))
    assert state["hand_detected"] is False
    assert state["cursor"] is None


def test_estimate_gesture_state_detects_pinch_trigger():
    landmarks = [(0.2, 0.2, 0.0)] * 21
    landmarks[4] = (0.2, 0.2, 0.0)
    landmarks[8] = (0.2, 0.2, 0.0)
    state = estimate_gesture_state(landmarks, (1920, 1080), False)
    assert state["pinch"] is True
    assert state["pinch_triggered"] is True


def test_estimate_gesture_state_detects_rock_on_summon_ai():
    lm = _create_base_landmarks()
    # Index open (tip high y < pip)
    lm[6] = (0.45, 0.60, 0.0)
    lm[8] = (0.45, 0.30, 0.0)
    # Pinky open (tip high y < pip)
    lm[18] = (0.60, 0.64, 0.0)
    lm[20] = (0.60, 0.35, 0.0)
    # Middle closed (tip low y >= pip)
    lm[10] = (0.50, 0.58, 0.0)
    lm[12] = (0.50, 0.62, 0.0)
    # Ring closed (tip low y >= pip)
    lm[14] = (0.55, 0.60, 0.0)
    lm[16] = (0.55, 0.64, 0.0)

    tracker = GestureTracker()
    state = estimate_gesture_state(lm, tracker=tracker)
    assert "Rock On" in state["gesture_name"]
    assert state["action"] == "summon_ai"


def test_estimate_gesture_state_detects_peace_screenshot():
    lm = _create_base_landmarks()
    # Index open
    lm[6] = (0.45, 0.60, 0.0)
    lm[8] = (0.45, 0.30, 0.0)
    # Middle open
    lm[10] = (0.50, 0.58, 0.0)
    lm[12] = (0.50, 0.30, 0.0)
    # Ring closed
    lm[14] = (0.55, 0.60, 0.0)
    lm[16] = (0.55, 0.64, 0.0)
    # Pinky closed
    lm[18] = (0.60, 0.64, 0.0)
    lm[20] = (0.60, 0.68, 0.0)

    tracker = GestureTracker()
    state = estimate_gesture_state(lm, tracker=tracker)
    assert "Peace" in state["gesture_name"]
    assert state["action"] == "screenshot"


def test_gesture_tracker_ema_and_deadband():
    tracker = GestureTracker()
    p1 = tracker.update_cursor_ema((0.5, 0.5))
    assert p1 == (0.5, 0.5)

    # Micro movement within deadband (0.001 delta < 0.0035 deadband)
    p2 = tracker.update_cursor_ema((0.5005, 0.5005))
    assert p2 == (0.5, 0.5)

    # Larger movement updates with smoothing
    p3 = tracker.update_cursor_ema((0.6, 0.6), alpha=0.5)
    assert p3[0] > 0.5 and p3[0] < 0.6
