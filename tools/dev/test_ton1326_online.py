"""Exercise the acceptance recorder against real local HTTP and upgraded sockets."""
import importlib.util
import json
from pathlib import Path
import socket
import socketserver
import subprocess
import tempfile
import threading
import unittest
import urllib.request
import venv

spec = importlib.util.spec_from_file_location("ton1326", Path(__file__).with_name("check-ton1326-online.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class Upstream(socketserver.BaseRequestHandler):
    def handle(self):
        incoming = bytearray()
        while not incoming.endswith(b"\r\n\r\n"):
            incoming += self.request.recv(1)
        self.server.requests.append(bytes(incoming))
        if b"Upgrade: websocket" in incoming:
            self.request.sendall(b"HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n")
            self.request.sendall(self.request.recv(4))
        else:
            self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}")


class RecorderTest(unittest.TestCase):
    def test_venv_interpreter_keeps_its_own_import_prefix(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "venv"
            venv.create(root, with_pip=False, symlinks=True)
            interpreter = check.input_file(root / "bin/python")
            prefix = subprocess.check_output([interpreter, "-c", "import sys; print(sys.prefix)"], text=True).strip()
            self.assertEqual(Path(prefix).resolve(), root.resolve())

    def test_paired_token_is_private_and_existing_credentials_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "paired-token"
            check.private_token(path, "private-fixture-token")
            self.assertEqual(path.read_text(), "private-fixture-token")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                check.private_token(path, "replacement")
            self.assertEqual(path.read_text(), "private-fixture-token")

    def test_http_and_websocket_pass_through_without_logging_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            upstream = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Upstream)
            upstream.requests = []
            probe = check.Probe(upstream.server_address[1], Path(temporary) / "http.jsonl")
            workers = [threading.Thread(target=server.serve_forever) for server in (upstream, probe)]
            for worker in workers:
                worker.start()
            try:
                url = f"http://127.0.0.1:{probe.server_address[1]}/course"
                request = urllib.request.Request(url, headers={"Authorization": "Bearer private-fixture-token"})
                with urllib.request.urlopen(request, timeout=3) as response:
                    self.assertEqual(json.load(response), {"ok": True})
                with socket.create_connection(probe.server_address, timeout=3) as client:
                    client.sendall(b"GET /v1/ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
                    header = bytearray()
                    while not header.endswith(b"\r\n\r\n"):
                        header += client.recv(1)
                    self.assertIn(b"101 Switching Protocols", header)
                    client.sendall(b"\x81\x02ok")
                    self.assertEqual(client.recv(4), b"\x81\x02ok")
                rows = probe.rows
                self.assertEqual([(r["path"], r["status"]) for r in rows], [("/course", 200), ("/v1/ws", 101)])
                self.assertIn(b"Authorization: Bearer private-fixture-token", upstream.requests[0])
                self.assertIn(b"Connection: close", upstream.requests[0])
                self.assertNotIn("private-fixture-token", probe.evidence.read_text())
            finally:
                for server in (probe, upstream):
                    server.shutdown()
                    server.server_close()
                for worker in workers:
                    worker.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
