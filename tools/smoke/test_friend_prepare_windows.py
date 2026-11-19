import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_windows", Path(__file__).with_name("friend-prepare-windows.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareTest(unittest.TestCase):
    owner = {"deviceId": "desktop", "token": "owner-fixture"}

    def clients(self, fail=None):
        events = []
        b = {"deviceId": "friend-b", "token": "b" * 43, "role": "friend"}
        class Client:
            def request(self, path, method="GET", auth=None, body=None):
                events.append((path, body))
                if path == "/v1/friends/invite":
                    return 200, json.dumps({"code": "123456"})
                if path == "/v1/pair":
                    return (401, "{}") if fail == "pair" else (200, json.dumps(b | ({"role": "owner"} if fail == "role" else {})))
                return (401 if fail == "status" else 200), "{}"
        return Client(), Client(), events

    def test_b_is_redeemed_and_authorized_before_a_is_signed(self):
        private, public, events = self.clients()
        result = prepare.prepare(private, public, self.owner)
        self.assertEqual([e[0] for e in events], ["/v1/friends/invite", "/v1/pair", "/v1/public/status", "/v1/friends/invite", "/v1/public/status"])
        self.assertEqual(events[0][1], {"friend": prepare.B})
        self.assertEqual(events[3][1], {"friend": prepare.A})
        self.assertEqual(result["credentialB"]["role"], "friend")

    def test_failed_b_never_signs_a(self):
        for failure in ("pair", "status", "role"):
            private, public, events = self.clients(failure)
            with self.subTest(failure=failure), self.assertRaises(ValueError):
                prepare.prepare(private, public, self.owner)
            self.assertNotIn(("/v1/friends/invite", {"friend": prepare.A}), events)

    def test_only_isolated_loopback_endpoints(self):
        self.assertEqual(prepare.local_endpoint("http://127.0.0.1:41242"), "http://127.0.0.1:41242")
        for endpoint in ("http://127.0.0.1:18791", "https://node.example.ts.net", "http://localhost:41242",
                         "http://127.0.0.1:8791", "http://user@127.0.0.1:41242", "http://127.0.0.1:41242/path"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                prepare.local_endpoint(endpoint)

    def test_private_output_and_no_secret_output(self):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            root.chmod(0o700)
            output = root / "fixture.json"
            private, public, _ = self.clients()
            stdout = io.StringIO()
            with patch.dict(os.environ, {"COACH_PREPARE_OWNER_URL": "http://127.0.0.1:41242",
                    "COACH_PREPARE_PUBLIC_URL": "http://127.0.0.1:41243", "COACH_PREPARE_OWNER": json.dumps(self.owner)}), \
                    patch.object(prepare.sys, "argv", ["prepare", "--output", str(output)]), \
                    patch.object(prepare.protocol, "Client", side_effect=[private, public]), contextlib.redirect_stdout(stdout):
                self.assertEqual(prepare.main(), 0)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(output.read_text())["credentialB"]["token"], "b" * 43)
            self.assertNotIn("123456", stdout.getvalue())
            self.assertNotIn("b" * 43, stdout.getvalue())
            self.assertNotIn("127.0.0.1", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
