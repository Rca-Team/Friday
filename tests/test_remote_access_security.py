import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock

from fastapi.testclient import TestClient
from friday_connect.gateway.server import FridayGateway
from friday_connect.gateway.protocol import build_message, ProtocolTypes
from friday_connect.gateway.tunnel import FridayTunnelManager


class RemoteAccessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.gateway = FridayGateway(Path(self.temp.name))
        self.client = TestClient(self.gateway.app)
        self.addCleanup(self.client.close)

    def test_remote_controls_and_pairing_cannot_be_accessed_anonymously(self):
        for path in ("/api/remote/files/browse", "/api/remote/pc/screenshot"):
            self.assertEqual(self.client.get(path).status_code, 401)
        self.assertEqual(self.client.post("/api/remote/command", json={"text": "hello"}).status_code, 401)
        self.assertEqual(self.client.get("/gateway/pair").status_code, 403)
        self.assertEqual(self.client.get("/gateway/logs").status_code, 403)

    def test_pair_authenticate_command_and_revoke(self):
        offer = self.gateway.create_pairing_offer()
        with self.client.websocket_connect("/ws") as ws:
            ws.send_json(build_message(ProtocolTypes.PAIR_REQUEST, {
                "pairing_token": offer["pairing_token"], "device_name": "Test phone", "platform": "android"}))
            paired = ws.receive_json()["payload"]
            headers = {"X-Friday-Device": paired["device"]["device_id"], "Authorization": "Bearer " + paired["device_secret"]}
            captured = []
            self.gateway.on_chat_message = lambda text, **kwargs: captured.append(text)
            response = self.client.post("/api/remote/command", headers=headers, json={"text": "test command"})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(captured, ["test command"])
            ws.send_json(build_message(ProtocolTypes.PAIR_REQUEST, {"pairing_token": offer["pairing_token"]}))
            self.assertEqual(ws.receive_json()["type"], ProtocolTypes.ERROR)
            self.gateway.device_manager.revoke(paired["device"]["device_id"])
            self.assertEqual(self.client.get("/api/remote/files/browse", headers=headers).status_code, 401)

    def test_websocket_chat_requires_authentication(self):
        with self.client.websocket_connect("/ws") as ws:
            ws.send_json(build_message(ProtocolTypes.CHAT_MESSAGE, {"text": "hello"}))
            self.assertEqual(ws.receive_json()["type"], ProtocolTypes.ERROR)

    def test_tunnel_start_does_not_deadlock_and_stale_url_is_hidden(self):
        manager = FridayTunnelManager(Path(self.temp.name))
        manager.ensure_cloudflared = Mock(return_value=False)
        worker = threading.Thread(target=manager.start_tunnel, args=(8765,), daemon=True)
        worker.start()
        worker.join(2)
        self.assertFalse(worker.is_alive(), "Tunnel startup deadlocked")
        manager._tunnel_url = "https://old.trycloudflare.com"
        manager._process = Mock()
        manager._process.poll.return_value = 1
        self.assertIsNone(manager.get_public_url())


if __name__ == "__main__":
    unittest.main()
