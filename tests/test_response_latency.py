"""Exercise response routing without hardware, credentials or network calls."""
import ast
import logging
import time
import types
import unittest
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[1]


def load_class(path, name, namespace):
    tree = ast.parse((ROOT / path).read_text(encoding="utf-8"))
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == name)
    exec(compile(ast.Module(body=[cls], type_ignores=[]), path, "exec"), namespace)
    return namespace[name]


class ResponseLatencyTests(unittest.TestCase):
    def make_provider(self, status):
        response = types.SimpleNamespace(status_code=status, json=lambda: {
            "choices": [{"message": {"content": "Ready"}}],
        })
        post = Mock(return_value=response)
        namespace = {
            "Optional": __import__("typing").Optional,
            "_load_api_key": lambda: "test-key",
            "DEFAULT_MAX_TOKENS": 4096, "DEFAULT_TEMPERATURE": .7,
            "REQUEST_TIMEOUT": 20, "MAX_RETRIES_PER_MODEL": 1,
            "RETRY_DELAY": 2, "RATE_LIMIT_COOLDOWN": 60,
            "ACCESS_DENIED_COOLDOWN": 86400,
            "_rate_limited": {}, "_access_denied": {},
            "_last_pool_exhausted_at": 0,
            "API_URL": "https://example.invalid",
            "time": time, "logger": logging.getLogger("test"),
            "requests": types.SimpleNamespace(post=post, exceptions=types.SimpleNamespace(Timeout=TimeoutError)),
        }
        cls = load_class("or_client.py", "OpenRouterClient", namespace)
        return cls(), post

    def test_stalled_provider_is_not_retried(self):
        provider, post = self.make_provider(502)
        self.assertIsNone(provider._call("model", []))
        self.assertEqual(post.call_count, 1)
        self.assertEqual(post.call_args.kwargs["timeout"], (5.0, 20))

    def test_retired_model_is_skipped_on_next_request(self):
        provider, post = self.make_provider(404)
        self.assertIsNone(provider._call("retired", []))
        self.assertTrue(provider._is_access_denied("retired"))

    def test_smaller_remaining_budget_reaches_http_client(self):
        provider, post = self.make_provider(200)
        self.assertEqual(provider._call("model", [], request_timeout=3), "Ready")
        self.assertEqual(post.call_args.kwargs["timeout"], (3, 3))


if __name__ == "__main__":
    unittest.main()
