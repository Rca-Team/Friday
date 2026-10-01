import tempfile
import unittest
from pathlib import Path


class TestWingmanIntelligence(unittest.TestCase):
    def test_robotics_activity_generates_useful_secondary_tasks(self):
        from intelligence.secondary_tasks import suggest_secondary_tasks

        tasks = suggest_secondary_tasks("I am debugging ESP32 inverse kinematics and a PCA9685 servo board")
        self.assertTrue(any(task.category == "hardware_research" for task in tasks))
        self.assertTrue(any("verification" in task.title.lower() for task in tasks))

    def test_project_graph_learns_stack_and_hardware(self):
        import memory.project_knowledge_graph as knowledge

        original = knowledge.PROJECTS_FILE
        with tempfile.TemporaryDirectory() as folder:
            knowledge.PROJECTS_FILE = Path(folder) / "projects.json"
            graph = knowledge.ProjectKnowledgeGraph()
            profile = graph.learn_from_text(
                "I am working on Quadruped Robot with ESP32, ROS2, C++ and PCA9685 servo control",
                "Quadruped Robot",
            )
            self.assertEqual(profile.domain, "ROBOTICS")
            self.assertIn("ESP32", profile.active_stack)
            self.assertIn("PCA9685", profile.hardware_bom)
        knowledge.PROJECTS_FILE = original

    def test_chronicle_redacts_secret_values(self):
        import memory.task_chronicle as history

        original = history.CHRONICLE_FILE
        with tempfile.TemporaryDirectory() as folder:
            history.CHRONICLE_FILE = Path(folder) / "chronicle.jsonl"
            entry = history.TaskChronicle().record("task-1", "Find api_key=super-secret-value", "completed")
            self.assertIn("[REDACTED]", entry["goal"])
            self.assertNotIn("super-secret-value", history.CHRONICLE_FILE.read_text(encoding="utf-8"))
        history.CHRONICLE_FILE = original


if __name__ == "__main__":
    unittest.main()
