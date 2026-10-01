"""
core/conversational_filter.py - Advanced Conversational Awareness & Talking-to-Me Detection
Engineered for FRIDAY AI (Created by Gaurav)

Enables Friday to distinguish when Gaurav is speaking directly to the AI
versus speaking to someone else in the room, on a phone call, in games,
or ambient room noise.
"""

from __future__ import annotations

import re
import time
from core.voice_activation import is_supported_voice_text, wakeword_detected
from typing import Tuple, List, Set, Dict, Any


class ConversationalIntentClassifier:
    """Classifies whether spoken transcript is addressed to Friday or is ambient side-talk."""

    # Explicit Assistant Names & Address Cues
    ASSISTANT_CUES: Set[str] = {
        # Core Personas
        "friday", "jarvis", "f.r.i.d.a.y.", "j.a.r.v.i.s.", "frida", "fryday",
        # Utho Friday variations
        "utho friday", "friday utho", "uth jao friday", "friday uth jao", "uth ja friday",
        # Jarvis variations
        "utho jarvis", "jarvis utho", "uth jao jarvis", "jarvis uth jao", "uth ja jarvis",
        # Utho Dost variations
        "utho dost", "uth dost", "uth jao dost", "dost utho", "dost uth jao", "uth ja dost",
        "hey dost", "hi dost", "suno dost", "dost suno", "dost jagjao", "dost jag jao",
        # Jagjao / Wake Up variations
        "jagjao", "jag jao", "jaag jao", "jaago", "jag ja", "jagjaao", "jaagjao",
        "friday jagjao", "friday jag jao", "jarvis jagjao", "jarvis jag jao",
        "utho", "uth jao", "uth ja", "wake up", "wake friday", "wake jarvis",
        # English / Generic Greetings
        "hey friday", "hey jarvis", "hi friday", "hi jarvis", "hello friday", "hello jarvis",
        "ok friday", "ok jarvis", "okay friday", "okay jarvis",
        "ai", "assistant", "bot", "suno", "sun na",
        "are you there", "kya tum sun rahi ho", "kya tum sun rahe ho"
    }

    # Action Imperatives & Command Starters (Directed at Assistant)
    COMMAND_STARTERS: Tuple[str, ...] = (
        "what", "when", "where", "who", "why", "how",
        "open", "launch", "start", "close", "quit", "exit",
        "play", "pause", "resume", "stop",
        "search", "google", "browse", "find",
        "turn on", "turn off", "switch on", "switch off",
        "create", "generate", "build", "write", "make", "draft",
        "summarize", "explain", "calculate", "compute", "tell me",
        "show me", "check", "inspect", "diagnose", "scan",
        "remind", "set a timer", "set alarm", "schedule",
        "volume", "mute", "unmute", "brightness",
        "fix", "undo", "redo", "clear",
        "kya hai", "batao", "dikhao", "kholo", "band karo",
        "chalao", "rok do", "dhundo", "bana do", "samjhao"
    )

    # Valid follow-ups during an active dialogue window (10-15s after Friday spoke)
    DIALOGUE_FOLLOW_UPS: Tuple[str, ...] = (
        "yes", "yeah", "yup", "no", "nope", "sure", "okay", "ok",
        "cancel", "never mind", "undo", "go ahead", "continue",
        "what about", "and for", "make it", "change it", "more",
        "next", "previous", "again", "repeat", "louder", "softer",
        "do it", "use that", "send it", "open it", "save it", "fix it",
        "aur", "aur batao", "ye karo", "kar do", "theek hai", "haan",
        "nahi", "ruko", "aage", "piche"
    )

    # Side-talk Indicators: Talking to another person in the room or on phone
    SIDE_TALK_VOCATIVES: Set[str] = {
        "mummy", "mom", "maa", "papa", "dad", "pitaji",
        "bhai", "bhaiya", "bro", "yaar", "dude", "dost",
        "bhabhi", "didi", "sister", "aunty", "uncle",
        "baby", "janu", "darling", "son", "beta", "beti",
        "sirji", "bhaijaan"
    }

    SIDE_TALK_PHRASES: Tuple[str, ...] = (
        # Phone call & video call chatter
        "hello are you there", "can you hear me", " आवाज आ रही है",
        "phone pe hoon", "call pe hoon", "call aa raha hai", "hold on",
        "call back kar", "call back karo", "speaker pe daal",
        "i am on a call", "i'm on a call", "hang up", "call cut ho gaya",
        "wait for me", "just a second",

        # Gaming communication
        "revive karo", "revive me", "behind you", "enemy ahead",
        "rush karo", "headshot", "kill him", "nice shot", "good game", "gg",
        "pass the ball", "shoot", "defend", "cover me", "smoke karo",

        # Room / Social chatter
        "dinner ready", "khana lagao", "khana khalo", "chai banado",
        "paani laao", "gate kholo", "darwaza kholo", "bahar jao",
        "did you see that", "dekha tune", "usne bola", "unhone kaha",
        "she told me", "he told me", "they said", "what was he saying"
    )

    @classmethod
    def evaluate(cls, text: str, in_active_window: bool = False) -> Tuple[bool, str]:
        """
        Determines if the speech is addressed to Friday.
        Returns (is_addressed, reason).
        """
        if not text or not text.strip():
            return False, "EMPTY_INPUT"
        if not is_supported_voice_text(text):
            return False, "UNSUPPORTED_LANGUAGE"
        if wakeword_detected(text):
            return True, "WAKE_WORD"

        t = text.lower().strip()
        t_clean = re.sub(r"[^\w\s]", " ", t)
        words = [w for w in t_clean.split() if w]

        if not words:
            return False, "NO_WORDS"

        # 1. Explicit Addressing & Wake Commands (checked FIRST!)
        # Direct wake words: friday, utho friday, jarvis, utho dost, jagjao, etc.
        for cue in cls.ASSISTANT_CUES:
            if cue in t:
                return True, f"EXPLICIT_ADDRESS ('{cue}')"

        # 2. Check for explicit Side-Talk phrases (talking to family, phone call, games)
        for side_phrase in cls.SIDE_TALK_PHRASES:
            if side_phrase in t:
                # If it doesn't explicitly start with an assistant cue, suppress it!
                if not any(t.startswith(cue) for cue in ("friday", "jarvis", "hey friday", "hey jarvis", "utho", "jagjao", "dost")):
                    return False, f"SIDE_TALK_DETECTED ('{side_phrase}')"

        # 3. Check for third-person vocatives at start/middle ("Bhai sun na", "Mom dinner...")
        first_few_words = set(words[:3])
        interrupted_vocatives = first_few_words.intersection(cls.SIDE_TALK_VOCATIVES)
        if interrupted_vocatives and not any(w in words for w in ("friday", "jarvis", "dost")):
            return False, f"SIDE_VOCATIVE_DETECTED ({list(interrupted_vocatives)})"

        # 4. Command Imperative at start of utterance (e.g. "Open Chrome", "What is the time")
        for cmd in cls.COMMAND_STARTERS:
            if t.startswith(cmd) or (len(words) > 1 and f"{words[0]} {words[1]}".startswith(cmd)):
                return True, f"COMMAND_STARTER ('{cmd}')"

        # 5. Active Dialogue Window Handling (Follow-up conversation without repeating wake word)
        if in_active_window:
            # During active dialogue, check for conversational follow-ups
            for follow_up in cls.DIALOGUE_FOLLOW_UPS:
                if follow_up in t:
                    return True, f"DIALOGUE_FOLLOW_UP ('{follow_up}')"

            # If user asks a general question or gives brief instruction during active window
            if len(words) <= 12 and not interrupted_vocatives:
                return True, "ACTIVE_DIALOGUE_CONTINUITY"

        # 6. Default to ignoring non-addressed ambient chatter
        return False, "PASSIVE_BACKGROUND_CHATTER"


class ConversationalSessionTracker:
    """Tracks active conversational turn engagement and timeout window."""

    def __init__(self, dialogue_window_sec: float = 20.0):
        self.dialogue_window_sec = dialogue_window_sec
        self.last_interaction_time: float = 0.0
        self.is_session_active: bool = False

    def on_user_addressed(self) -> None:
        """Called when user successfully addresses Friday."""
        self.last_interaction_time = time.time()
        self.is_session_active = True

    def on_assistant_spoke(self) -> None:
        """Called when Friday completes speaking a response."""
        self.last_interaction_time = time.time()
        self.is_session_active = True

    def is_in_active_window(self) -> bool:
        """Checks if current moment is inside the active back-and-forth dialogue window."""
        if not self.is_session_active:
            return False
        elapsed = time.time() - self.last_interaction_time
        if elapsed <= self.dialogue_window_sec:
            return True
        self.is_session_active = False
        return False

    def close_dialogue(self) -> None:
        """Forces dialog window closed (e.g. when user says 'thanks', 'bye')."""
        self.is_session_active = False
        self.last_interaction_time = 0.0


class AdaptiveVoiceFilter:
    """
    Adaptive noise floor estimation and acoustic voice gate.
    Prevents background breathing, PC fans, and keyboard clicks from triggering the AI.
    """

    def __init__(self, alpha: float = 0.05, min_threshold: float = 22.0):
        self.alpha = alpha  # EMA smoothing factor
        self.noise_floor: float = 15.0
        self.min_threshold = min_threshold
        self.calibrated = False

    def update_noise_floor(self, lvl: float) -> None:
        """Tracks steady-state room ambient noise."""
        if not self.calibrated:
            self.noise_floor = max(8.0, lvl)
            self.calibrated = True
        else:
            # Only adapt downward or slowly upward if energy is low (not during speech bursts)
            if lvl < self.noise_floor * 2.0:
                self.noise_floor = (1.0 - self.alpha) * self.noise_floor + self.alpha * lvl

    def get_speech_threshold(self) -> float:
        """Calculates dynamic speech detection threshold based on ambient room noise."""
        return max(self.min_threshold, self.noise_floor * 2.2 + 8.0)

    def is_speech_burst(self, lvl: float) -> bool:
        """Checks if the audio frame level represents distinct vocal speech above room noise."""
        thresh = self.get_speech_threshold()
        if lvl > thresh:
            return True
        self.update_noise_floor(lvl)
        return False
