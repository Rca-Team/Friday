"""Local, persistent project context for Friday's developer wingman."""
from __future__ import annotations

import json
import re
import threading
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from core.user_paths import get_user_data_dir


PROJECTS_FILE = get_user_data_dir() / "memory" / "project_knowledge_graph.json"
_lock = threading.RLock()


@dataclass
class ProjectProfile:
    project_name: str
    domain: str = "CODING"
    active_stack: list[str] = field(default_factory=list)
    git_repo_path: str = ""
    hardware_bom: list[str] = field(default_factory=list)
    key_milestones: list[str] = field(default_factory=list)
    architecture_notes: list[str] = field(default_factory=list)
    recent_decisions: list[str] = field(default_factory=list)


class ProjectKnowledgeGraph:
    """Stores concise, user-visible engineering facts rather than raw conversations."""

    def _load(self) -> dict[str, ProjectProfile]:
        if not PROJECTS_FILE.exists():
            return {}
        try:
            raw = json.loads(PROJECTS_FILE.read_text(encoding="utf-8"))
            return {name: ProjectProfile(**data) for name, data in raw.items()}
        except (OSError, json.JSONDecodeError, TypeError):
            return {}

    def _save(self, projects: dict[str, ProjectProfile]) -> None:
        PROJECTS_FILE.parent.mkdir(parents=True, exist_ok=True)
        PROJECTS_FILE.write_text(json.dumps({k: asdict(v) for k, v in projects.items()}, indent=2), encoding="utf-8")

    def upsert(self, project_name: str, **updates: Any) -> ProjectProfile:
        name = " ".join((project_name or "").split())[:80]
        if not name:
            raise ValueError("A project name is required.")
        with _lock:
            projects = self._load()
            profile = projects.get(name, ProjectProfile(project_name=name))
            for key, value in updates.items():
                if hasattr(profile, key) and value is not None:
                    setattr(profile, key, value)
            projects[name] = profile
            self._save(projects)
            return profile

    def get(self, project_name: str) -> ProjectProfile | None:
        with _lock:
            return self._load().get(project_name)

    def list(self) -> list[ProjectProfile]:
        with _lock:
            return list(self._load().values())

    def learn_from_text(self, text: str, project_name: str = "") -> ProjectProfile | None:
        """Extract conservative stack and hardware facts from an explicit project update."""
        clean = " ".join((text or "").split())
        if len(clean) < 8:
            return None
        name = project_name.strip() or self._infer_project_name(clean)
        if not name:
            return None
        profile = self.get(name) or ProjectProfile(project_name=name, domain=self._infer_domain(clean))
        stack = list(dict.fromkeys(profile.active_stack + self._matches(clean, ["Python", "C++", "ROS2", "Arduino", "ESP32", "KiCad", "SolidWorks", "PlatformIO", "React", "FastAPI"])))
        bom = list(dict.fromkeys(profile.hardware_bom + self._matches(clean, ["PCA9685", "DRV8833", "MPU6050", "Raspberry Pi", "servo", "stepper motor"])))
        decisions = (profile.recent_decisions + [clean[:300]])[-20:]
        return self.upsert(name, domain=profile.domain or self._infer_domain(clean), active_stack=stack, hardware_bom=bom, recent_decisions=decisions)

    @staticmethod
    def _matches(text: str, choices: list[str]) -> list[str]:
        found = []
        for item in choices:
            if item == "C++":
                if "c++" in text.lower():
                    found.append(item)
            elif re.search(r"\b" + re.escape(item) + r"\b", text, re.I):
                found.append(item)
        return found

    @staticmethod
    def _infer_domain(text: str) -> str:
        low = text.lower()
        if any(word in low for word in ("robot", "servo", "sensor", "kinematic", "pcb", "esp32", "arduino")):
            return "ROBOTICS"
        if any(word in low for word in ("research", "paper", "datasheet", "study")):
            return "RESEARCH"
        if any(word in low for word in ("cad", "solidworks", "kicad", "gerber")):
            return "CAD"
        return "CODING"

    @staticmethod
    def _infer_project_name(text: str) -> str:
        match = re.search(r"(?:project|working on|building)\s+(?:my\s+)?([\w .-]{3,50})", text, re.I)
        return match.group(1).strip(" .") if match else ""


graph = ProjectKnowledgeGraph()
