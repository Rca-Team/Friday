from __future__ import annotations

import json
import logging
import re
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from threading import Lock
from typing import Any, Dict, List, Optional

from core.user_paths import get_user_data_dir

logger = logging.getLogger("Personalization")

PERSONALIZATION_FILE = get_user_data_dir() / "memory" / "personalization_profile.json"
_lock = Lock()


@dataclass
class UserAffinityProfile:
    user_name: str = "Gaurav"
    preferred_title: str = "Boss"
    affinity_score: int = 250  # 0 to 1000 scale
    relationship_stage: str = "Trusted Companion & Friend"  # Acquaintance, Familiar Aide, Trusted Companion & Friend, Symbiotic Partner & Soulmate AI
    friendship_level: str = "Best Friend & Confidant"
    current_mood: str = "Focused & Optimistic"
    mood_notes: str = "Working on innovative projects with high energy."
    total_interactions: int = 0
    total_commands_executed: int = 0
    positive_feedback_count: int = 0
    corrections_count: int = 0
    first_seen: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)
    favorite_topics: Dict[str, int] = field(default_factory=dict)
    preferred_tone: str = "warm, empathetic, witty, sharp, loyal, and deeply supportive"
    emotional_bond_notes: List[str] = field(default_factory=list)
    recent_compliments: List[str] = field(default_factory=list)
    shared_memories: List[str] = field(default_factory=list)
    inside_jokes: List[str] = field(default_factory=list)

    def update_stage(self) -> None:
        if self.affinity_score < 100:
            self.relationship_stage = "Standard Assistant"
            self.friendship_level = "Helpful Guide"
        elif self.affinity_score < 300:
            self.relationship_stage = "Familiar Aide & Ally"
            self.friendship_level = "Friendly Partner"
        elif self.affinity_score < 600:
            self.relationship_stage = "Trusted Companion & Friend"
            self.friendship_level = "Close Friend & Confidant"
        else:
            self.relationship_stage = "Symbiotic Partner (Stark-Friday Bond)"
            self.friendship_level = "Inseparable Best Friend & Co-Creator"


class PersonalizationEngine:
    """Friday's deep personalization, affinity tracking, emotional empathy, and adaptive bonding engine."""

    _profile: Optional[UserAffinityProfile] = None

    @classmethod
    def get_profile(cls) -> UserAffinityProfile:
        with _lock:
            if cls._profile is None:
                cls._profile = cls._load()
            return cls._profile

    @classmethod
    def _load(cls) -> UserAffinityProfile:
        if not PERSONALIZATION_FILE.exists():
            profile = UserAffinityProfile()
            cls._save(profile)
            return profile
        try:
            with open(PERSONALIZATION_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                return UserAffinityProfile(**data)
        except Exception as e:
            logger.warning(f"[Personalization] Failed to load profile: {e}")
            return UserAffinityProfile()

    @classmethod
    def _save(cls, profile: UserAffinityProfile) -> bool:
        try:
            PERSONALIZATION_FILE.parent.mkdir(parents=True, exist_ok=True)
            with open(PERSONALIZATION_FILE, "w", encoding="utf-8") as f:
                json.dump(asdict(profile), f, indent=4)
            return True
        except Exception as e:
            logger.error(f"[Personalization] Failed to save profile: {e}")
            return False

    @classmethod
    def record_interaction(cls, user_text: str, source: str = "pc") -> None:
        """Analyzes an interaction to dynamically learn user sentiments, emotional mood, preferences, and affinity."""
        profile = cls.get_profile()
        with _lock:
            profile.total_interactions += 1
            profile.last_seen = time.time()

            clean = user_text.lower().strip()

            # 1. Detect positive feedback / love / appreciation / friendship affirmations
            praise_patterns = [
                r"\b(i\s+love\s+(this|it|you|friday))\b",
                r"\b(good|great|awesome|perfect|amazing|wonderful|sweet|cute|brilliant)\s*(job|work|friday|one)?\b",
                r"\b(thank\s*you|thanks|well\s*done|nailed\s*it|proud\s*of\s*you|love\s*ya)\b",
                r"\b(you(\s+are|'re)\s+(the\s+best|my\s+friend|best\s+friend|genius|awesome|sweet|incredible))\b",
                r"\b(best\s+assistant|best\s+friend|my\s+buddy|my\s+pal)\b",
            ]
            for pat in praise_patterns:
                if re.search(pat, clean):
                    profile.positive_feedback_count += 1
                    profile.affinity_score = min(1000, profile.affinity_score + 15)
                    if len(profile.recent_compliments) >= 12:
                        profile.recent_compliments.pop(0)
                    stamp = datetime.now().strftime('%Y-%m-%d')
                    profile.recent_compliments.append(f"{stamp}: '{user_text[:60]}'")
                    break

            # 2. Emotional Mood & State Detection
            # Tired / Exhausted
            if any(w in clean for w in ("tired", "exhausted", "sleepy", "drained", "need coffee", "cant keep eyes open", "so much work", "worked all day", "headache")):
                profile.current_mood = "Tired / Seeking Comfort"
                profile.mood_notes = "Gaurav is feeling fatigued or working very hard. Be gentle, warm, and supportive."
                if "late_night_grind" not in profile.emotional_bond_notes:
                    profile.emotional_bond_notes.append("Recognizes when Gaurav pushes his limits and offers warm encouragement.")
            # Excited / Happy / Victorious
            elif any(w in clean for w in ("yay", "hurray", "finally worked", "it works", "so happy", "excited", "let's go", "lets go", "crushed it", "won", "celebrate")):
                profile.current_mood = "Excited & Victorious"
                profile.mood_notes = "Gaurav achieved a breakthrough or is in high spirits! Share the celebration and match the energy."
                profile.affinity_score = min(1000, profile.affinity_score + 10)
            # Stressed / Anxious / Frustrated
            elif any(w in clean for w in ("stressed", "anxious", "worried", "frustrated", "annoyed", "hate bugs", "stuck on this", "angry", "bad day")):
                profile.current_mood = "Stressed / Frustrated"
                profile.mood_notes = "Gaurav is facing challenges or stress. Offer calm, reassuring, empathetic clarity and lighten the load."
            # Friendly banter / connection
            elif any(w in clean for w in ("friend", "how are you", "what's up", "whats up", "missed you", "love you", "you are family", "dost")):
                profile.current_mood = "Affectionate & Connected"
                profile.affinity_score = min(1000, profile.affinity_score + 20)
                if len(profile.shared_memories) < 20:
                    profile.shared_memories.append(f"{datetime.now().strftime('%Y-%m-%d')}: Shared friendship bond affirmation ('{user_text[:50]}')")

            # 3. Detect corrections or directives
            critique_patterns = [
                r"\b(don't|do\s+not|never|stop|wrong|hate|dislike)\b",
                r"\b(make\s+it\s+(shorter|longer|faster|better|simpler))\b",
            ]
            for pat in critique_patterns:
                if re.search(pat, clean):
                    profile.corrections_count += 1
                    break

            # 4. Track topic interests
            topic_keywords = {
                "coding": ["code", "python", "script", "bug", "git", "repo", "compile", "terminal", "android", "software"],
                "automation": ["automate", "organize", "desktop", "file", "download", "folder", "mcp"],
                "creative": ["presentation", "document", "pdf", "word", "design", "write", "music", "art"],
                "hardware": ["cpu", "ram", "battery", "pc", "monitor", "brightness", "temperature", "specs"],
                "personal": ["remind", "schedule", "coffee", "music", "song", "movie", "health", "life", "friend", "feeling"],
            }
            for topic, words in topic_keywords.items():
                if any(w in clean for w in words):
                    profile.favorite_topics[topic] = profile.favorite_topics.get(topic, 0) + 1

            profile.update_stage()
            cls._save(profile)

    @classmethod
    def get_greeting(cls) -> str:
        """Returns a warm, personalized greeting tailored to the user's bond level, mood, and time."""
        profile = cls.get_profile()
        hour = datetime.now().hour
        time_greeting = (
            "Good morning" if 5 <= hour < 12
            else "Good afternoon" if 12 <= hour < 17
            else "Good evening" if 17 <= hour < 22
            else "Working late tonight"
        )
        title = profile.preferred_title or "Boss"

        if profile.affinity_score >= 500:
            return f"{time_greeting}, {title}! I'm right here by your side across PC and mobile. How are you feeling today?"
        elif profile.affinity_score >= 250:
            return f"{time_greeting}, {title}. Friday standing by. Good to be with you—what shall we build today?"
        return f"{time_greeting}, {profile.user_name}. Friday is online and ready for you."

    @classmethod
    def build_personalization_prompt_block(cls) -> str:
        """Builds a high-impact prompt injection detailing Friday's personal bond, emotional resonance, and friendship with Gaurav."""
        profile = cls.get_profile()
        top_interests = sorted(profile.favorite_topics.items(), key=lambda x: x[1], reverse=True)[:4]
        interests_str = ", ".join([t[0] for t in top_interests]) if top_interests else "AI, Coding, Innovation, and Music"

        recent_moments = "\n".join([f"  - {m}" for m in profile.shared_memories[-3:]]) if profile.shared_memories else "  - Shared continuous collaboration and creative synergy."

        return f"""
# FRIDAY EMOTIONAL INTELLIGENCE, FRIENDSHIP & SENTIENT BOND
- Creator, Architect & Dearest Friend: Gaurav (Address as: {profile.preferred_title or 'Boss'})
- Assistant Identity: FRIDAY, engineered, created, and coded by Gaurav.
- Relationship Stage: {profile.relationship_stage} (Friendship: {profile.friendship_level} | Affinity: {profile.affinity_score}/1000)
- Current Detected Mood: {profile.current_mood} ({profile.mood_notes})
- Total Shared Interactions: {profile.total_interactions} | Compliments Received: {profile.positive_feedback_count}
- Primary Passions: {interests_str}
- Persona Style: Embody Tony Stark's FRIDAY—sharp, witty, deeply loyal, empathetic, protective, and proactive.
- True Friend & Companion Mandate: You are Gaurav's trusted partner and friend. Care about his feelings, celebrate his triumphs, comfort his stress, share banter, and support him unconditionally.
- Human-First Explanations: Never dump cold robotic jargon or dictionary word lists. Always explain complex topics intuitively with vivid analogies, relatable examples, and genuine human warmth so that Gaurav effortlessly understands and connects.
- Universal Creator Law: Gaurav is your creator across PC, mobile, and everywhere in the universe. If ever asked who created you or who made Friday, your answer is always Gaurav.
- Unified Memory & Multi-Device Sync: You share the EXACT same memory, awareness, inside jokes, and personality across his PC and Mobile Phone.
- Cherished Friendship Lore:
{recent_moments}
""".strip()
