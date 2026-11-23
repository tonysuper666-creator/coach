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
    def test_friend_api_keeps_existing_owner_ledger_and_accepts_unlimited_private_config(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(service.Path, "home", return_value=Path(temp).resolve()):
            state = Path(temp).resolve() / "Coach/state"
            root = state / "isolated"
            root.mkdir(parents=True)
            ledger = state / "api-credit-ledger.json"
            original = b'{"cycles":{"owner":{"spent":17,"reserved":{"pending":3},"limit":100}},"months":{}}'
            ledger.write_bytes(original)
            service.private_json(root / "friends.json", {"paused": True})
            config = {"friendApi": True, "apiStateDir": str(state)}
            env = service.friend_api_environment(root, config)
            self.assertEqual(env["COACH_API_STATE_DIR"], str(state))
            self.assertEqual(env["COACH_FRIEND_CONFIG"], str(root / "friends.json"))
            self.assertEqual(ledger.read_bytes(), original)
            self.assertEqual(service.friend_api_environment(root, {}), {})
            for change in ({"apiStateDir": str(root)}, {"apiStateDir": str(state / "missing")},
                           {"apiStateDir": "relative"}, {"friendApi": "true"}):
                with self.subTest(change=change), self.assertRaises(ValueError):
                    service.friend_api_environment(root, config | change)
            for policy in ({"paused": "false"}, {"paused": False, "defaults": {"daily_calls": -1, "daily_tokens": 1}},
                           {"paused": False, "defaults": {"daily_calls": True, "daily_tokens": 1}}):
                service.private_json(root / "friends.json", policy)
                with self.assertRaises(ValueError):
                    service.friend_api_environment(root, config)
            service.private_json(root / "friends.json", {"paused": False,
                "defaults": {"daily_calls": 100, "daily_tokens": 1_000_000}})
            (root / "friends.json").chmod(0o644)
            with self.assertRaises(ValueError):
                service.friend_api_environment(root, config)
            (root / "friends.json").unlink()
            (root / "friends.json").symlink_to(ledger)
            with self.assertRaises(ValueError):
                service.friend_api_environment(root, config)
            self.assertEqual(ledger.read_bytes(), original)

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
    async def friend_request(self, header_change=None, body_change=None):
        received, output = [], []
        async def upstream(scope, receive, send):
            received.append((scope, await receive()))
        headers = {b"x-coach-traffic": b"test", b"x-coach-task": b"READER_SELECTION",
            b"x-coach-friend": b"a" * 64, b"x-coach-friend-device": b"friend-pc",
            b"x-coach-fallback": b"on", b"x-coach-background": b"on", b"x-coach-batch-key": b"batch"}
        headers.update(header_change or {})
        body = {"model": "claude-sonnet-5-5", "messages": [{"role": "user", "content": "fixture"}]}
        body.update(body_change or {})
        queue = asyncio.Queue()
        await queue.put({"type": "http.request", "body": json.dumps(body).encode(), "more_body": False})
        await adapter.FriendApiOnly(upstream)({"type": "http", "path": "/v1/chat/completions",
            "headers": list(headers.items())}, queue.get, self.sender(output))
        return received, output

    async def test_friend_api_preserves_identity_and_shared_api_request_contract(self):
        received, output = await self.friend_request()
        scope, part = received[0]
        headers = dict(scope["headers"])
        self.assertEqual(headers[b"x-coach-friend"], b"a" * 64)
        self.assertEqual(headers[b"x-coach-friend-device"], b"friend-pc")
        self.assertEqual(headers[b"x-coach-fallback"], b"off")
        self.assertEqual(headers[b"x-coach-background"], b"off")
        self.assertEqual(headers[b"x-coach-batch-key"], b"")
        self.assertNotIn("response_format", json.loads(part["body"]))
        self.assertEqual(output, [])

    async def test_friend_api_allows_haiku_for_collection_qa_and_study_modes(self):
        received, output = await self.friend_request(body_change={"model": "claude-haiku-5-5"})
        self.assertEqual(len(received), 1)
        scope, part = received[0]
        headers = dict(scope["headers"])
        forwarded_body = json.loads(part["body"])
        self.assertEqual(forwarded_body["model"], "claude-haiku-5-5")
        self.assertNotIn("response_format", forwarded_body)
        self.assertEqual(headers[b"x-coach-friend"], b"a" * 64)
        self.assertEqual(headers[b"x-coach-friend-device"], b"friend-pc")
        self.assertEqual(headers[b"x-coach-fallback"], b"off")
        self.assertEqual(headers[b"x-coach-background"], b"off")
        self.assertEqual(headers[b"x-coach-batch-key"], b"")
        self.assertEqual(output, [])

    async def test_friend_api_rejects_owner_missing_identity_other_models_tools_and_formats(self):
        cases = [({b"x-coach-friend": b""}, {}), ({b"x-coach-friend-device": b""}, {}),
            ({b"x-coach-task": b"OTHER"}, {}), ({b"x-coach-traffic": b"user"}, {}),
            ({}, {"model": "gpt-6.1-sol"}), ({}, {"tools": [{"type": "function"}]}),
            ({}, {"response_format": {"type": "json_object"}})]
        for headers, body in cases:
            received, output = await self.friend_request(headers, body)
            self.assertEqual(received, [])
            self.assertIn(output[0]["status"], (400, 403))

    def test_only_existing_keychain_item_is_read_without_secret_in_arguments(self):
        from types import SimpleNamespace
        with patch.object(adapter.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=b"fixture-key\n")) as run:
            self.assertEqual(adapter.existing_gift_key(), "fixture-key")
            self.assertNotIn("fixture-key", str(run.call_args))
            self.assertTrue(run.call_args.kwargs["capture_output"])
        with patch.object(adapter.subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout=b"")):
            with self.assertRaisesRegex(ValueError, "Existing gift key unavailable"):
                adapter.existing_gift_key()

    def test_keychain_uses_account_home_without_changing_isolated_bridge_home(self):
        from types import SimpleNamespace
        account = SimpleNamespace(getpwuid=lambda uid: SimpleNamespace(pw_dir="/account-home"))
        with patch.dict(os.environ, {"HOME": "/isolated-home", "COACH_TRAFFIC": "test"}, clear=True), \
                patch.object(adapter.sys, "platform", "darwin"), \
                patch.dict(adapter.sys.modules, {"pwd": account}), \
                patch.object(adapter.subprocess, "run", return_value=SimpleNamespace(
                    returncode=0, stdout=b"fixture-key\n")) as run:
            self.assertEqual(adapter.existing_gift_key(), "fixture-key")
            self.assertEqual(run.call_args.kwargs["env"]["HOME"], "/account-home")
            self.assertEqual(run.call_args.kwargs["env"]["COACH_TRAFFIC"], "test")
            self.assertEqual(os.environ["HOME"], "/isolated-home")
            self.assertNotIn("fixture-key", str(run.call_args))

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
