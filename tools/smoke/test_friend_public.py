"""Offline security checks for the public probe; never contacts a Funnel."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import ssl
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock, patch

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
                patch.object(probe, "public_route"), \
                patch.object(probe.Client, "request", side_effect=RuntimeError(" ".join(secrets))), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["surface"]), 1)
        for secret in (*secrets, "node.example.ts.net", "123456"):
            self.assertNotIn(secret, output.getvalue())
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net", "COACH_FRIEND_CODE": "123456"}, clear=True), \
                patch.object(probe, "public_route"), \
                patch.object(probe.Client, "request", return_value=(500, b"private-material")), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["surface"]), 1)
        self.assertNotIn("private-material", output.getvalue())

    def test_transport_failures_report_fixed_stage_and_category_only(self):
        private = "private-host private-token private-material ::warning::injection"
        cases = (
            (ssl.SSLEOFError(8, private), "tls-eof"),
            (ssl.SSLCertVerificationError(1, private), "tls-certificate"),
            (ssl.SSLError(1, private), "tls-other"),
            (socket.gaierror(-2, private), "dns"),
            (TimeoutError(private), "timeout"),
            (ConnectionRefusedError(private), "connection-refused"),
            (ConnectionResetError(private), "connection-reset"),
            (RuntimeError(private), "unexpected"),
        )
        for cause, expected in cases:
            for error in (cause, probe.urllib.error.URLError(cause)):
                with self.subTest(category=expected, wrapped=error is not cause):
                    output = io.StringIO()
                    with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net"}, clear=True), \
                            patch.object(probe, "public_route"), \
                            patch.object(probe.urllib.request.OpenerDirector, "open", side_effect=error), \
                            contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                        self.assertEqual(probe.main(["transport"]), 1)
                    self.assertEqual(output.getvalue(), "FAIL stage=anonymous-status category=" + expected
                                     + "; private diagnostic values suppressed\n")

    def test_transport_mode_needs_no_code_or_credentials(self):
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net"}, clear=True), \
                patch.object(probe, "public_route"), patch.object(probe, "closed_ports"), \
                patch.object(probe.Client, "request", return_value=(401, b"")) as request, \
                contextlib.redirect_stdout(output):
            self.assertEqual(probe.main(["transport"]), 0)
        request.assert_called_once_with("/v1/public/status")
        self.assertIn("PASS anonymous_https=401", output.getvalue())

    def test_tailnet_or_local_dns_cannot_reach_pairing(self):
        for addresses in (("100.64.1.2",), ("127.0.0.1",), ("::1",), ("8.8.8.8", "100.64.1.2"), ()):
            resolved = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (value, 443)) for value in addresses]
            with self.subTest(addresses=addresses):
                output = io.StringIO()
                with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net", "COACH_FRIEND_CODE": "123456"}, clear=True), \
                        patch.object(socket, "getaddrinfo", return_value=resolved), \
                        patch.object(probe.Client, "request") as request, \
                        contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                    self.assertEqual(probe.main(["surface"]), 1)
                request.assert_not_called()
                self.assertIn("FAIL stage=public-dns check=public DNS required", output.getvalue())
                self.assertNotIn("node.example.ts.net", output.getvalue())
        resolved = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443)),
                    (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2606:4700:4700::1111", 443, 0, 0))]
        output = io.StringIO()
        with patch.object(socket, "getaddrinfo", return_value=resolved), contextlib.redirect_stdout(output):
            probe.public_route("unused")
        self.assertEqual(output.getvalue(), "PASS dns=public\n")

    def test_failure_after_anonymous_success_identifies_pairing_stage(self):
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net", "COACH_FRIEND_CODE": "123456"}, clear=True), \
                patch.object(probe, "public_route"), \
                patch.object(probe.Client, "request", side_effect=[(401, b""), TimeoutError("private-code")]), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["surface"]), 1)
        self.assertIn("PASS anonymous_https=401\nFAIL stage=pairing category=timeout", output.getvalue())
        for private in ("private-code", "123456", "node.example.ts.net"):
            self.assertNotIn(private, output.getvalue())

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

    @staticmethod
    def edge_redirect(location="https://node.example.ts.net/v1/public/status", *, status=302,
                      body=None, extra=""):
        body = (('<a href="' + location + '">Found</a>.\n\n').encode() if body is None else body)
        wire = (f"HTTP/1.1 {status} Found\r\nContent-Type: text/html; charset=utf-8\r\n"
                f"Location: {location}\r\nContent-Length: {len(body)}\r\n{extra}\r\n").encode() + body
        connection = Mock()
        connection.makefile.return_value = io.BytesIO(wire)
        return connection

    def test_shared_edge_accepts_only_exact_https_redirect(self):
        connection = self.edge_redirect()
        output = io.StringIO()
        with patch.object(socket, "create_connection", side_effect=[connection, TimeoutError(), TimeoutError(), TimeoutError()]), \
                contextlib.redirect_stdout(output):
            probe.closed_ports("node.example.ts.net")
        connection.close.assert_called_once()
        connection.sendall.assert_called_once_with(
            b"GET /v1/public/status HTTP/1.1\r\nHost: node.example.ts.net\r\nConnection: close\r\n\r\n")
        self.assertIn("PASS alternate_routes=4", output.getvalue())
        self.assertNotIn("node.example.ts.net", output.getvalue())

    def test_http_application_or_other_redirect_cannot_pass(self):
        cases = (
            {"status": 200}, {"status": 401},
            {"location": "https://other.example.ts.net/v1/public/status"},
            {"location": "https://node.example.ts.net:18791/v1/public/status"},
            {"location": "https://node.example.ts.net/v1/devices"},
            {"location": "http://node.example.ts.net/v1/public/status"},
            {"body": b"private-material"},
            {"extra": "Set-Cookie: private-token\r\n"},
            {"extra": "Location: https://other.example.ts.net/\r\n"},
        )
        for options in cases:
            with self.subTest(options=options):
                connection = self.edge_redirect(**options)
                with patch.object(socket, "create_connection", return_value=connection), \
                        self.assertRaisesRegex(probe.CheckFailed, "unexpected alternate listener port=80"):
                    probe.closed_ports("node.example.ts.net")
                connection.close.assert_called_once()

    def test_shared_tls_edge_must_reject_handshake_on_both_alternate_ports(self):
        context = Mock()
        context.wrap_socket.side_effect = ssl.SSLEOFError(8, "private-host")
        connections = [Mock(), Mock()]
        with patch.object(socket, "create_connection", side_effect=[TimeoutError(), *connections, TimeoutError()]), \
                patch.object(ssl, "create_default_context", return_value=context), \
                contextlib.redirect_stdout(io.StringIO()):
            probe.closed_ports("node.example.ts.net")
        self.assertEqual(context.wrap_socket.call_count, 2)
        for connection in connections:
            connection.close.assert_called_once()
            connection.sendall.assert_not_called()
        self.assertEqual(context.wrap_socket.call_args.kwargs, {"server_hostname": "node.example.ts.net"})

    def test_alternate_tls_success_certificate_error_or_timeout_cannot_pass(self):
        for port in (8443, 10000):
            for error in (None, ssl.SSLCertVerificationError(1, "private-host"),
                          ssl.SSLError(1, "private-host"), TimeoutError("private-host")):
                with self.subTest(port=port, error=type(error).__name__):
                    context = Mock()
                    context.wrap_socket.side_effect = error
                    if error is None:
                        context.wrap_socket.return_value.__enter__ = Mock()
                        context.wrap_socket.return_value.__exit__ = Mock(return_value=False)
                    connection = Mock()
                    outcomes = [TimeoutError()] * ((80, 8443, 10000, 18791).index(port)) + [connection]
                    with patch.object(socket, "create_connection", side_effect=outcomes), \
                            patch.object(ssl, "create_default_context", return_value=context), \
                            self.assertRaises(probe.CheckFailed):
                        probe.closed_ports("node.example.ts.net")
                    connection.close.assert_called_once()

    def test_owner_port_tcp_success_always_fails(self):
        connection = Mock()
        with patch.object(socket, "create_connection", side_effect=[TimeoutError(), TimeoutError(), TimeoutError(), connection]), \
                self.assertRaisesRegex(probe.CheckFailed, "unexpected alternate listener port=18791"):
            probe.closed_ports("node.example.ts.net")
        connection.close.assert_called_once()
        connection.sendall.assert_not_called()

    def test_stage_trace_records_tcp_failure(self):
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net"}, clear=True), \
                patch.object(probe, "public_route"), \
                patch.object(socket, "create_connection", side_effect=TimeoutError("private-host")), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["transport", "--stages"]), 1)
        lines = output.getvalue().splitlines()
        self.assertEqual(lines, [
            "STAGE_TRACE tcp=timeout server_hello=unknown cert_verify=unknown http=unknown",
            "FAIL stage=anonymous-status category=timeout; private diagnostic values suppressed",
        ])

    def test_stage_trace_records_eof_before_server_hello(self):
        sock = Mock()
        sock.recv.return_value = b""
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net"}, clear=True), \
                patch.object(probe, "public_route"), \
                patch.object(socket, "create_connection", return_value=sock), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["transport", "--stages"]), 1)
        sock.close.assert_called_once()
        lines = output.getvalue().splitlines()
        self.assertEqual(lines, [
            "STAGE_TRACE tcp=ok server_hello=none cert_verify=unknown http=unknown",
            "FAIL stage=anonymous-status category=tls-eof; private diagnostic values suppressed",
        ])

    def test_stage_trace_records_server_hello_and_cert_failure_with_full_verify(self):
        # TLS 1.2/1.3 Handshake record (type 22) containing ServerHello (msg type 2, len 4).
        server_hello_record = bytes([22, 3, 3, 0, 8, 2, 0, 0, 4, 3, 3, 0, 0])
        sock = Mock()
        sock.recv.side_effect = [server_hello_record, b""]
        ctx = ssl.create_default_context()
        self.assertTrue(ctx.check_hostname)
        self.assertEqual(ctx.verify_mode, ssl.CERT_REQUIRED)
        ssl_obj = Mock()
        ssl_obj.do_handshake.side_effect = [
            ssl.SSLWantReadError(),
            ssl.SSLCertVerificationError(1, "private-host"),
        ]
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net"}, clear=True), \
                patch.object(probe, "public_route"), \
                patch.object(socket, "create_connection", return_value=sock), \
                patch.object(ssl, "create_default_context", return_value=ctx), \
                patch.object(ctx, "wrap_bio", return_value=ssl_obj) as wrap_bio, \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["transport", "--stages"]), 1)
        wrap_bio.assert_called_once()
        self.assertEqual(wrap_bio.call_args.kwargs, {"server_hostname": "node.example.ts.net"})
        lines = output.getvalue().splitlines()
        self.assertEqual(lines, [
            "STAGE_TRACE tcp=ok server_hello=ok cert_verify=fail http=unknown",
            "FAIL stage=anonymous-status category=tls-certificate; private diagnostic values suppressed",
        ])
        self.assertNotIn("private-host", output.getvalue())
        self.assertNotIn("node.example.ts.net", output.getvalue())

    def test_stage_trace_records_full_tls_and_http_401_on_one_connection(self):
        sock = Mock()
        sock.recv.return_value = b""
        ctx = ssl.create_default_context()
        ssl_obj = Mock()
        ssl_obj.do_handshake.return_value = None
        ssl_obj.read.side_effect = [b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n", b""]
        output = io.StringIO()
        with patch.dict(os.environ, {"COACH_FRIEND_URL": "https://node.example.ts.net"}, clear=True), \
                patch.object(probe, "public_route"), \
                patch.object(probe, "closed_ports"), \
                patch.object(socket, "create_connection", return_value=sock) as connect, \
                patch.object(ssl, "create_default_context", return_value=ctx), \
                patch.object(ctx, "wrap_bio", return_value=ssl_obj), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(probe.main(["transport", "--stages"]), 0)
        connect.assert_called_once_with(("node.example.ts.net", 443), timeout=10)
        sock.close.assert_called_once()
        self.assertEqual(output.getvalue().splitlines(), [
            "STAGE_TRACE tcp=ok server_hello=ok cert_verify=ok http=http-401",
            "PASS anonymous_https=401",
            "SCOPE protocol-only; native GUI, real explanation, quota ledger and cleanup require separate verification",
        ])


if __name__ == "__main__":
    unittest.main()
