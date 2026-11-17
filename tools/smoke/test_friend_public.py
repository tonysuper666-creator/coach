"""Offline security checks for the public probe; never contacts a Funnel."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("friend_public", Path(__file__).with_name("friend-public.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

AUTH = {"deviceId": "test-device", "token": "test-bearer", "role": "friend"}


class ProbeTests(unittest.TestCase):
    def test_rejects_unsafe_endpoints_before_transport(self):
        for value in ("http://node.example.ts.net", "https://example.org", "https://node.example.ts.net:18791",
                      "https://user:secret@node.example.ts.net", "https://node.example.ts.net/v1",
                      "https://node.example.ts.net?secret=value", "https://node.example.ts.net#secret",
                      "\nhttps://node.example.ts.net"):
            with self.subTest(value=value), self.assertRaises(probe.CheckFailed):
                probe.endpoint(value)

    def test_owner_or_header_injection_credentials_rejected(self):
        for change in ({"role": "owner"}, {"token": "bearer\r\nInjected: yes"}, {"deviceId": ""}):
            with self.subTest(change=change), self.assertRaises(probe.CheckFailed):
                probe.credential(json.dumps(dict(AUTH, **change)))

    def test_failure_does_not_log_transport_or_body_secrets(self):
        secrets = ("private-address", "private-code", "private-token", "private-material")
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net", "COACH_FRIEND_CODE": "123456"}, clear=True), \
                patch.object(probe.Client, "request", side_effect=RuntimeError(" ".join(secrets))), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["surface"]), 1)
        for secret in (*secrets, "node.example.ts.net", "123456"):
            self.assertNotIn(secret, output.getvalue())
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net", "COACH_FRIEND_CODE": "123456"}, clear=True), \
                patch.object(probe.Client, "request", return_value=(500, b"private-material")), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["surface"]), 1)
        self.assertNotIn("private-material", output.getvalue())

    def test_masks_json_components_and_escapes_workflow_commands(self):
        output = io.StringIO()
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), contextlib.redirect_stdout(output):
            probe.credential(json.dumps(AUTH))
            probe.mask("value%\r\n::warning::injection")
        self.assertEqual(output.getvalue().splitlines(), [
            "::add-mask::test-device", "::add-mask::test-bearer",
            "::add-mask::value%25%0D%0A::warning::injection"])

    def test_redirect_never_forwards_credentials(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append((self.path, self.headers.get("Authorization")))
                self.send_response(302)
                self.send_header("Location", "/stolen")
                self.end_headers()

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            client = probe.Client("http://127.0.0.1:" + str(server.server_port))
            self.assertEqual(client.request("/status", auth=AUTH), (302, b""))
            self.assertEqual(requests, [("/status", "Bearer test-bearer")])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
            self.assertFalse(thread.is_alive())

    def test_oversized_response_is_rejected(self):
        response = io.BytesIO(b"x" * (probe.MAX_BODY + 1))
        response.code = 200
        client = probe.Client("https://node.example.ts.net")
        with patch.object(client.opener, "open", return_value=response), self.assertRaises(probe.CheckFailed):
            client.request("/v1/public/status")

    def test_private_response_body_cannot_pass_as_404(self):
        class Server:
            def request(self, path, method="GET", auth=None, body=None):
                if path == "/v1/pair":
                    if "name" in body:
                        return 200, json.dumps(AUTH).encode()
                    return 401, b""
                if path == "/v1/public/status":
                    return (200, b'{"online":true}') if auth else (401, b"")
                return 404, b"private-material"
        with self.assertRaisesRegex(probe.CheckFailed, "private path boundary"):
            probe.surface(Server(), "123456")

    def test_revocation_requires_active_control(self):
        other = dict(AUTH, deviceId="control-device", token="control-token")
        with patch.object(probe.Client, "request", return_value=(401, b"")), \
                self.assertRaisesRegex(probe.CheckFailed, "other friend remains online"):
            probe.revocation(probe.Client("unused"), AUTH, other)

    def test_ports_fail_on_open_or_inconclusive_dns(self):
        with patch.object(socket, "create_connection", side_effect=socket.gaierror("private-host")), \
                self.assertRaisesRegex(probe.CheckFailed, "inconclusive"):
            probe.closed_ports("unused")
        with patch.object(socket, "create_connection") as connect, \
                self.assertRaisesRegex(probe.CheckFailed, "unexpected alternate listener"):
            probe.closed_ports("unused")
        connect.return_value.close.assert_called_once()
        output = io.StringIO()
        with patch.object(socket, "create_connection", side_effect=TimeoutError) as connect, contextlib.redirect_stdout(output):
            probe.closed_ports("unused")
        self.assertEqual(connect.call_count, 4)


if __name__ == "__main__":
    unittest.main()
