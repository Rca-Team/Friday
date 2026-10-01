"""Pure, testable wake matching and hands-free turn authorization."""
import time
import unicodedata

_FOREIGN_LANGUAGE_CUES = {
    "hola", "bonjour", "salut", "hallo", "ciao", "ola",
    "gracias", "merci", "danke", "por", "favor", "como", "estas",
}


def is_supported_voice_text(text: str) -> bool:
    """Accept English and Romanized Hinglish only; reject Hindi script and other languages."""
    value = (text or "").strip()
    if not value:
        return False
    if not all(ord(char) < 128 for char in value):
        return False
    tokens = set(normalize(value).split())
    return not bool(tokens.intersection(_FOREIGN_LANGUAGE_CUES))


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text or "").casefold()
    return " ".join("".join(c if c.isalnum() or unicodedata.category(c).startswith("M") else " " for c in text).split())


def wakeword_detected(text: str) -> bool:
    if not is_supported_voice_text(text):
        return False
    tokens = normalize(text).split()
    if not tokens:
        return False
    names = {
        "friday", "fryday", "frida", "firday", "fraiday", "fraidey", "fridey", "fraidy",
        "freeday", "phriday", "phride", "frieday", "fridy",
        "jarvis", "jarwis", "zarvis", "zaarvis", "jarves", "jarviss",
    }
    if names.intersection(tokens) or any(left + right in names for left, right in zip(tokens, tokens[1:])):
        return True
    prefixes = ("utho", "uth", "hey", "hi", "suno", "wake", "ok")
    for token in tokens:
        for p in prefixes:
            if token.startswith(p) and token[len(p):] in names:
                return True
    phrases = (
        "utho dost", "uth jao dost", "dost utho", "hey dost", "sun dost", "suno dost",
        "jag jao", "jagjao", "jaag jao", "jaagjao", "jaago", "jago",
        "uth jao", "uthjao", "utho", "uth ja", "uthja", "uto", "utto", "ootho", "uthho",
        "wake up", "wakeup",
        "suno friday", "friday suno", "hey friday", "hi friday", "ok friday", "hello friday",
        "suno jarvis", "jarvis suno", "hey jarvis", "hi jarvis", "ok jarvis", "hello jarvis",
    )
    value = " " + " ".join(tokens) + " "
    return any(" " + phrase + " " in value for phrase in phrases)



class VoiceActivation:
    def __init__(self, clock=time.monotonic, window=20.0):
        self.clock, self.window = clock, window
        self.active_until = 0.0
        self.fragments = ""
        self.allowed = False
        self.woke = False
        self.last_fragment = 0.0

    def activate(self):
        self.active_until = self.clock() + self.window
        self.allowed = True

    def close(self):
        self.end_turn()
        self.active_until = 0.0

    def accept(self, text, *, push_to_talk=False, held=False, wake_enabled=True):
        now = self.clock()
        if not is_supported_voice_text(text):
            self.fragments = ""
            self.woke = False
            return False, False
        if now - self.last_fragment > 3.0:
            self.fragments = ""
        self.last_fragment = now
        self.fragments = (self.fragments + " " + text)[-512:]
        wake = wake_enabled and wakeword_detected(self.fragments)
        # PTT is an explicit alternative, never silently bypassed by a wake word.
        accepted = held if push_to_talk else (wake or now < self.active_until or not wake_enabled)
        newly_woke = bool(wake and accepted and not self.woke)
        if accepted:
            self.activate()
        self.woke = self.woke or newly_woke
        return self.allowed, newly_woke

    def end_turn(self):
        if self.allowed:
            self.active_until = self.clock() + self.window
        self.fragments = ""
        self.allowed = False
        self.woke = False


def microphone_allowed(*, muted, wake_listening, ptt_enabled, ptt_held):
    return (not ptt_enabled or ptt_held) and (not muted or wake_listening)
