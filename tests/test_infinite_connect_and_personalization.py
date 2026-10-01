import json
import os
import sys
import unittest
from pathlib import Path

# Add project root to sys.path
BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from memory.personalization_engine import PersonalizationEngine, UserAffinityProfile
from friday_connect.gateway.server import FridayGateway, FridayGatewayConfig
from fastapi.testclient import TestClient


class TestInfiniteConnectAndPersonalization(unittest.TestCase):

    def test_01_personalization_engine_basics(self):
        profile = PersonalizationEngine.get_profile()
        self.assertIsNotNone(profile)
        self.assertEqual(profile.user_name, "Gaurav")
        self.assertIn("Boss", profile.preferred_title)

        # Record interaction with positive feedback
        initial_score = profile.affinity_score
        PersonalizationEngine.record_interaction("Friday I love this, great job on building this feature!")
        updated_profile = PersonalizationEngine.get_profile()
        self.assertGreaterEqual(updated_profile.affinity_score, initial_score)
        self.assertGreater(updated_profile.positive_feedback_count, 0)

        # Check prompt injection contains personalized bond
        prompt_block = PersonalizationEngine.build_personalization_prompt_block()
        self.assertIn("Gaurav", prompt_block)
        self.assertIn("Boss", prompt_block)
        self.assertIn("Tony Stark's FRIDAY", prompt_block)
        self.assertIn("same memory", prompt_block)

    def _create_paired_device_headers(self, gateway):
        record, secret = gateway.device_manager.create_from_pairing(
            name="Test Phone",
            platform="android",
            os_version="14",
            agent_version="1.0.0",
            ip="127.0.0.1",
            capabilities=["camera", "screen_capture", "remote_control"],
            permissions=["camera", "notifications"],
        )
        return {
            "X-Friday-Device": record.device_id,
            "Authorization": f"Bearer {secret}"
        }

    def test_02_remote_gateway_file_browsing(self):
        gateway = FridayGateway(BASE_DIR)
        headers = self._create_paired_device_headers(gateway)
        client = TestClient(gateway.app)

        # Test root drives / quick access folders browse
        resp = client.get("/api/remote/files/browse", headers=headers)
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertTrue(data.get("ok"))
        entries = data.get("entries", [])
        self.assertGreater(len(entries), 0)

        # Test specific workspace folder browsing
        resp_dir = client.get(f"/api/remote/files/browse?path={str(BASE_DIR)}", headers=headers)
        self.assertEqual(resp_dir.status_code, 200)
        dir_data = resp_dir.json()
        self.assertTrue(dir_data.get("ok"))
        names = [e["name"] for e in dir_data.get("entries", [])]
        self.assertIn("main.py", names)

    def test_03_remote_folder_zip_download(self):
        gateway = FridayGateway(BASE_DIR)
        headers = self._create_paired_device_headers(gateway)
        client = TestClient(gateway.app)

        # Download assets folder as zip stream
        assets_dir = str(BASE_DIR / "assets")
        resp = client.get(f"/api/remote/files/download?path={assets_dir}", headers=headers)
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.headers.get("content-type"), "application/zip")
        self.assertIn("attachment", resp.headers.get("content-disposition", ""))
        self.assertGreater(len(resp.content), 100)

        # Download single file
        main_py = str(BASE_DIR / "version.txt")
        resp_file = client.get(f"/api/remote/files/download?path={main_py}", headers=headers)
        self.assertEqual(resp_file.status_code, 200)
        self.assertGreater(len(resp_file.content), 0)

    def test_04_remote_command_execution(self):
        gateway = FridayGateway(BASE_DIR)
        headers = self._create_paired_device_headers(gateway)
        captured = []

        def mock_on_chat(text, device_id=""):
            captured.append((text, device_id))
            return f"Executed: {text}"

        gateway.on_chat_message = mock_on_chat
        client = TestClient(gateway.app)

        resp = client.post("/api/remote/command", json={"text": "Open my project on PC"}, headers=headers)
        self.assertEqual(resp.status_code, 200)
        res_json = resp.json()
        self.assertTrue(res_json.get("ok"))
        self.assertEqual(len(captured), 1)
        self.assertEqual(captured[0][0], "Open my project on PC")

    def test_05_pairing_offer_includes_tunnel(self):
        gateway = FridayGateway(BASE_DIR)
        offer = gateway.create_pairing_offer()
        self.assertIn("pairing_code", offer)
        self.assertIn("pairing_token", offer)
        self.assertIn("tunnel_url", offer)


if __name__ == "__main__":
    unittest.main()
