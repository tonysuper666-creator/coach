"""Synthetic reader HTTP fixture. Owned processes use it as a context manager and close it before exit."""

import hashlib
import http.server
import json
from pathlib import Path
import subprocess
import socket
import threading
import time
from urllib.parse import urlsplit, parse_qs

from synthetic_library import write_library

TOKEN = "synthetic-baseline-token"
COURSE = "READER_BASELINE"
PREFIX = f"/v1/courses/{COURSE}/reader"
FORBIDDEN = {8790, 8791, 18791, 3790}


class Fixture:
    def __init__(self, state: Path, bind="127.0.0.1", port=0, renderer="pdftoppm", transport="legacy", lifecycle=False):
        if port in FORBIDDEN:
            raise ValueError("production port is forbidden")
        state.mkdir(parents=True, exist_ok=False)
        library = state / "library"
        write_library(library, COURSE)
        # All inputs were just created here; never enumerate or read another library.
        subprocess.run([renderer, "-r", "144", "-png", str(library / "synthetic.pdf"), str(state / "page")], check=True)
        self.images = {page: (state / f"page-{page:02d}.png").read_bytes() for page in range(1, 25)}
        self.material_sha256 = hashlib.sha256((library / "synthetic.pdf").read_bytes()).hexdigest()
        self.page = 1
        self.revision = 0
        self.requests = []
        self.transfers = []
        self.version = "v1"
        if transport not in ("legacy", "nodelay", "keepalive"):
            raise ValueError("unknown synthetic transport")
        self.lock = threading.Lock()
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1" if transport == "keepalive" else "HTTP/1.0"
            disable_nagle_algorithm = transport != "legacy"

            def log_message(self, *_):
                pass  # no request URLs, headers, credentials, or material in logs

            def send(self, status, body, content_type="application/json", row=None):
                data = body if isinstance(body, bytes) else json.dumps(body).encode()
                row = {} if row is None else row
                row.update(status=status, bytes=len(data), handlerNs=time.monotonic_ns(),
                           protocol=self.protocol_version, tcpNoDelay=self.connection.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY))
                with fixture.lock:
                    fixture.transfers.append(row)
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                row["headersQueuedNs"] = time.monotonic_ns()
                try:
                    self.wfile.write(data)
                    row["bodyQueuedNs"] = time.monotonic_ns()
                except ConnectionError as error:
                    row["writeError"] = type(error).__name__

            def do_GET(self):
                parsed = urlsplit(self.path)
                if parsed.path == "/health":
                    self.send(200, dict(syntheticOnly=True, pages=24, width=1200, height=1600,
                                        materialSha256=fixture.material_sha256, hostMonotonicNs=time.monotonic_ns(), transport=transport))
                    return
                if self.headers.get("Authorization") != f"Bearer {TOKEN}":
                    self.send(401, {})
                    return
                path = parsed.path.rstrip("/")
                if not (path == PREFIX or path.startswith(PREFIX + "/")):
                    self.send(404, {})
                elif path.endswith("/image"):
                    try:
                        page = int(path.split("/")[-2])
                        document = path.split("/")[-4]
                        if document not in (["synthetic-baseline", "synthetic-other"] if lifecycle else ["synthetic-baseline"]):
                            raise ValueError("document")
                        expected_version = fixture.version if document == "synthetic-baseline" else "v1"
                        offset = (1 if expected_version == "v2" else 0) if document == "synthetic-baseline" else 2
                        image = fixture.images[(page - 1 + offset) % 24 + 1]
                    except (ValueError, KeyError):
                        self.send(404, {})
                        return
                    if parse_qs(parsed.query).get("version") != [expected_version]:
                        self.send(409, {})
                        return
                    with fixture.lock:
                        row=dict(page=page, document=document, version=expected_version, startedNs=time.monotonic_ns())
                        fixture.requests.append(row)
                    self.send(200, image, "image/png", row=row)
                elif path.endswith("/resolve"):
                    if parse_qs(parsed.query).get("source") == ["Synthetic p.1"]:
                        self.send(200, dict(documentId="synthetic-baseline", version=fixture.version, pdfPage=1))
                    else:
                        self.send(422, {})
                elif path.endswith("/state"):
                    with fixture.lock:
                        version = "v1" if path.endswith("/synthetic-other/state") else fixture.version
                        body = dict(document=dict(version=version), progress=dict(version=version, pdfPage=fixture.page,
                                    revision=fixture.revision), notes=[])
                    self.send(200, body)
                elif path.endswith("/study"):
                    self.send(200, dict(facts=[], questions=[], unlocated=0))
                elif path == PREFIX:
                    documents = [dict(id="synthetic-baseline", title="Synthetic baseline", version=fixture.version, pages=24)]
                    if lifecycle:
                        documents.append(dict(id="synthetic-other", title="Synthetic variant", version="v1", pages=24))
                    self.send(200, dict(documents=documents))
                else:
                    self.send(404, {})

            def do_POST(self):
                if lifecycle and self.path == "/test/version" and self.headers.get("Authorization") == f"Bearer {TOKEN}":
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 < length <= 128:
                        self.send(400, {}); return
                    body = self.rfile.read(length)
                    if body != b"v2":
                        self.send(400, {}); return
                    with fixture.lock:
                        fixture.version = "v2"; fixture.page = 1; fixture.revision = 0
                    self.send(200, dict(version="v2")); return
                if self.headers.get("Authorization") != f"Bearer {TOKEN}" or self.path != PREFIX + "/synthetic-baseline/progress":
                    self.send(403, {})
                    return
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    self.send(400, {})
                    return
                try:
                    body = json.loads(self.rfile.read(length))
                    page = body["pdfPage"]
                    if not isinstance(page, int) or not 1 <= page <= 24:
                        raise ValueError("page")
                except (KeyError, ValueError, TypeError):
                    self.send(400, {})
                    return
                with fixture.lock:
                    if body.get("version") != fixture.version or body.get("baseRevision") != fixture.revision:
                        status, response = 409, {}
                    else:
                        fixture.page = page
                        fixture.revision += 1
                        status, response = 200, dict(revision=fixture.revision)
                self.send(status, response)

        self.server = http.server.ThreadingHTTPServer((bind, port), Handler)
        if self.server.server_port in FORBIDDEN:
            self.server.server_close()
            raise ValueError("production port selected")
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs=dict(poll_interval=.1))

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
