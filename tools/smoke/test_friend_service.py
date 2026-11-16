"""Exercise production isolation and the real adapter's request boundary."""
import asyncio
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


service = module("friend_service", "friend-service.py")
adapter = module("friend_bridge", "friend-service-bridge.py")


class ServiceIsolationTest(unittest.TestCase):
    def test_production_and_adjacent_bridge_ports_refused(self):
        for port in (0, 8790, 8791, 8789, 18790, 18791, 65535):
            with self.subTest(port=port), self.assertRaises(ValueError):
                service.ports(port)
        self.assertEqual(service.ports(60010), (60010, 60011))

    def test_production_paths_and_inherited_secrets_cannot_configure_children(self):
        with self.assertRaises(ValueError):
            service.isolated_root(Path.home() / "Coach/data")
        with self.assertRaises(ValueError):
            service.isolated_root(Path.home() / "Coach/state")
        with patch.dict(os.environ, {"COACH_MANAGE_SERVICES": "1", "COACH_DATA_DIR": "production",
            "BRIDGE_PORT": "8791", "MULTICA_TOKEN": "secret", "OPENAI_API_KEY": "secret"}, clear=True):
            self.assertEqual(service.clean_environment(), {})

    def test_private_output_does_not_follow_existing_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            original = root / "original"
            original.write_text("keep")
            output = root / "pairing.json"
            output.symlink_to(original)
            service.private_json(output, {"code": "private"})
            self.assertEqual(original.read_text(), "keep")
            self.assertFalse(output.is_symlink())
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)


class BridgeBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def call_adapter(self, traffic=b"test", payload=None):
        received, output = [], []
        async def upstream(scope, receive, send):
            received.append((scope, await receive()))
        request = {"model": "gpt-6.1-sol", "messages": [{"role": "user", "content": "test selection"}]}
        data = json.dumps(request).encode() if payload is None else payload
        queue = asyncio.Queue()
        await queue.put({"type": "http.request", "body": data, "more_body": False})
        await adapter.SubscriptionOnly(upstream)({"type": "http", "path": "/v1/chat/completions",
            "headers": [(b"x-coach-traffic", traffic), (b"x-coach-task", b"READER_SELECTION")]}, queue.get, self.sender(output))
        return received, output

    @staticmethod
    def sender(output):
        async def send(value):
            output.append(value)
        return send

    async def test_real_model_is_explicit_and_no_fallback_or_batch(self):
        received, output = await self.call_adapter()
        scope, part = received[0]
        self.assertEqual(json.loads(part["body"])["model"], "gpt-6.1-sol")
        self.assertEqual(json.loads(part["body"])["response_format"], {"type": "json_object"})
        headers = dict(scope["headers"])
        self.assertEqual(headers[b"x-coach-model-policy"], b"exact")
        self.assertEqual(headers[b"x-coach-fallback"], b"off")
        self.assertEqual(headers[b"x-coach-batch-key"], b"")
        self.assertEqual(output, [])

    async def test_user_traffic_oversized_and_invalid_requests_never_reach_model(self):
        for traffic, payload, expected in [(b"user", None, 403), (b"test", b"x" * 512001, 403),
                                            (b"test", b"[]", 400), (b"test", b'{"model":"claude-sonnet-5-5"}', 400)]:
            received, output = await self.call_adapter(traffic, payload)
            self.assertEqual(received, [])
            self.assertEqual(output[0]["status"], expected)


if __name__ == "__main__":
    unittest.main()
