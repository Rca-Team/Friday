import base64
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
from friday_connect.gateway.server import FridayGateway
from friday_connect.gateway.protocol import ProtocolTypes, build_message
from friday_connect.gateway.mobile_api import desktop_inventory

class MobileAssistantAPITests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.gateway = FridayGateway(Path(self.temp.name)); self.client = TestClient(self.gateway.app); self.addCleanup(self.client.close)
        self.headers = []
        for name in ('Phone A', 'Phone B'):
            offer = self.gateway.create_pairing_offer()
            with self.client.websocket_connect('/ws') as ws:
                ws.send_json(build_message(ProtocolTypes.PAIR_REQUEST, {'pairing_token': offer['pairing_token'], 'device_name': name, 'platform': 'android'}))
                paired = ws.receive_json()['payload']
            self.headers.append({'X-Friday-Device': paired['device']['device_id'], 'Authorization': 'Bearer ' + paired['device_secret']})

    def wait_task(self, task_id):
        for _ in range(100):
            task = self.client.get('/api/remote/tasks/' + task_id, headers=self.headers[0]).json()
            if task['status'] not in ('queued', 'running'): return task
            time.sleep(.01)
        self.fail('Task did not finish')

    def test_new_routes_require_authentication_and_revocation(self):
        self.assertEqual(self.client.get('/api/remote/capabilities').status_code, 401)
        self.assertEqual(self.client.post('/api/remote/tasks', json={}).status_code, 401)
        self.assertEqual(self.client.post('/api/remote/attachments', json={}).status_code, 401)
        response = self.client.get('/api/remote/capabilities', headers=self.headers[0]); self.assertEqual(response.status_code, 200)
        expected = desktop_inventory(Path(__file__).resolve().parents[1] / 'main.py')
        self.assertEqual(response.json()['tools'], expected)
        self.gateway.device_manager.revoke(self.headers[0]['X-Friday-Device'])
        self.assertEqual(self.client.get('/api/remote/capabilities', headers=self.headers[0]).status_code, 401)

    def test_duplicate_task_never_dispatches_twice_and_is_device_scoped(self):
        calls = []
        self.gateway.on_chat_message = lambda text, device_id: calls.append((text, device_id))
        task_id = str(uuid.uuid4()); body = {'request_id': task_id, 'text': 'Open notepad on PC'}
        self.assertEqual(self.client.post('/api/remote/tasks', json=body, headers=self.headers[0]).status_code, 200)
        task = self.wait_task(task_id); self.assertEqual(task['status'], 'dispatched')
        self.client.post('/api/remote/tasks', json=body, headers=self.headers[0]); self.assertEqual(len(calls), 1)
        self.assertEqual(self.client.get('/api/remote/tasks/' + task_id, headers=self.headers[1]).status_code, 404)
        cancelled = self.client.post('/api/remote/tasks/' + task_id + '/cancel', json={}, headers=self.headers[0]).json()
        self.assertFalse(cancelled['cancelled']); self.assertIn('Check the PC', cancelled['message'])

    def test_queued_cancel_prevents_dispatch(self):
        task_id = str(uuid.uuid4()); calls = []; self.gateway.on_chat_message = lambda *a, **kw: calls.append(a)
        with patch.object(self.gateway.mobile_api, 'dispatch'):
            self.client.post('/api/remote/tasks', json={'request_id': task_id, 'text': 'hello'}, headers=self.headers[0])
        result = self.client.post('/api/remote/tasks/' + task_id + '/cancel', json={}, headers=self.headers[0]).json()
        self.assertTrue(result['cancelled'])
        self.gateway.mobile_api.dispatch(self.headers[0]['X-Friday-Device'], task_id, 'hello')
        self.assertEqual(calls, [])

    def test_gateway_restart_marks_uncertain_tasks_without_replay(self):
        task_id = str(uuid.uuid4())
        with patch.object(self.gateway.mobile_api, 'dispatch'):
            self.client.post('/api/remote/tasks', json={'request_id': task_id, 'text': 'hello'}, headers=self.headers[0])
        from friday_connect.gateway.mobile_api import MobileAPI
        restarted = MobileAPI(self.gateway)
        self.assertEqual(restarted.task(self.headers[0]['X-Friday-Device'], task_id)['status'], 'unknown')

    def test_attachment_validation_transfer_and_ownership(self):
        body = {'name': 'notes.txt', 'mime': 'text/plain', 'data': base64.b64encode(b'hello').decode()}
        response = self.client.post('/api/remote/attachments', json=body, headers=self.headers[0]); self.assertEqual(response.status_code, 200)
        attachment_id = response.json()['id']; path = '/api/remote/attachments/' + attachment_id
        self.assertEqual(self.client.get(path, headers=self.headers[0]).content, b'hello')
        self.assertEqual(self.client.get(path, headers=self.headers[1]).status_code, 404)
        self.assertEqual(self.client.delete(path, headers=self.headers[1]).status_code, 404)
        body['name'] = '../escape.txt'; self.assertEqual(self.client.post('/api/remote/attachments', json=body, headers=self.headers[0]).status_code, 400)
        body['name'] = 'notes.txt'; body['data'] = 'bad-base64'; self.assertEqual(self.client.post('/api/remote/attachments', json=body, headers=self.headers[0]).status_code, 400)
        self.assertEqual(self.client.delete(path, headers=self.headers[0]).status_code, 200)
        self.assertEqual(self.client.get(path, headers=self.headers[0]).status_code, 404)

    def test_foreign_attachment_cannot_be_used_in_task(self):
        body = {'name': 'notes.txt', 'mime': 'text/plain', 'data': base64.b64encode(b'hello').decode()}
        aid = self.client.post('/api/remote/attachments', json=body, headers=self.headers[0]).json()['id']
        self.assertEqual(self.client.post('/api/remote/tasks', json={'request_id': str(uuid.uuid4()), 'text': 'inspect', 'attachment_ids': [aid]}, headers=self.headers[1]).status_code, 400)

if __name__ == '__main__': unittest.main()
