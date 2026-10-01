"""Turns a saved project profile into a Gamma-ready deck outline and prompt."""
from __future__ import annotations

from memory.project_knowledge_graph import ProjectProfile


def build_gamma_prompt(project: ProjectProfile, audience: str = "college submission") -> str:
    stack = ", ".join(project.active_stack) or "to be confirmed"
    bom = ", ".join(project.hardware_bom) or "to be confirmed"
    milestones = "; ".join(project.key_milestones[-6:]) or "current implementation progress"
    return (
        f"Create a polished 10-slide technical presentation for {audience}. Project: {project.project_name}. "
        f"Domain: {project.domain}. Stack: {stack}. Hardware: {bom}. Milestones: {milestones}. "
        "Include: problem, objectives, architecture diagram, implementation, hardware or software workflow, "
        "validation, challenges, results, future work, and conclusion. Use concise technical language, modern dark visual styling, and editable diagrams."
    )


def build_slide_outline(project: ProjectProfile) -> list[str]:
    return ["Title and project objective", "Problem and success criteria", "System architecture", "Technology stack", "Hardware design / BOM", "Implementation workflow", "Milestones and evidence", "Testing and validation", "Challenges and next steps", "Conclusion"]
