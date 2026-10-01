"""Safe task classification and local productivity suggestions."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class SecondaryTask:
    title: str
    goal: str
    category: str
    needs_visible_desktop: bool = False


def suggest_secondary_tasks(activity: str) -> list[SecondaryTask]:
    low = (activity or "").lower()
    tasks: list[SecondaryTask] = []
    if any(term in low for term in ("robot", "esp32", "arduino", "servo", "sensor", "pca9685", "kinematic")):
        tasks += [
            SecondaryTask("Find verified pinout and voltage limits", "Research the relevant component datasheet, pinout, voltage range, and wiring cautions. Return cited findings.", "hardware_research"),
            SecondaryTask("Prepare a bench-test checklist", "Create a short test checklist for the current hardware component, including power, ground, logic-level, and smoke-test checks.", "hardware_research"),
        ]
    if any(term in low for term in ("code", "python", "c++", "bug", "test", "api", "repo", "compile")):
        tasks += [
            SecondaryTask("Run focused verification", "Identify and run the smallest relevant test or build check. Report failures with file locations and a concise next action.", "development"),
            SecondaryTask("Research the blocking API or library", "Find official documentation for the API or library being used and summarize the exact integration details needed.", "research"),
        ]
    if any(term in low for term in ("study", "research", "paper", "thesis")):
        tasks.append(SecondaryTask("Create a research brief", "Gather authoritative sources for the active research topic and return a dated, linked summary with open questions.", "research"))
    return tasks[:3]


def classify_task(goal: str) -> str:
    low = (goal or "").lower()
    if any(word in low for word in ("api key", "token", "secret", "credential")):
        return "credential_retrieval"
    if any(word in low for word in ("pinout", "datasheet", "register", "wiring")):
        return "hardware_research"
    return "development" if any(word in low for word in ("test", "build", "code")) else "research"
