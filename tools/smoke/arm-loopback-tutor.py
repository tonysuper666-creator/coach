"""Fixed replies for the installed ARM UI smoke, limited to the repository PDF."""
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import secrets
import sys


class Tutor(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.path != "/v1/library/explain":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer " + self.server.token:
            self.send_error(401)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 131072:
                raise ValueError("body size")
            req = json.loads(self.rfile.read(size))
            blocks = req["blocks"]
            if not blocks or not all(self.server.block.fullmatch(b["id"]) for b in blocks):
                raise ValueError("unexpected book")
            kind = req["kind"]
            chosen = blocks[0] if kind == "lesson" else next(
                b for b in blocks if b["text"].strip() == req["selection"].strip())
            if kind == "ask" and "选项A" in req["question"]:
                kind = "practice"
                text = ("题干：Which method checks recall? 选项A：Recall before checking "
                        "选项B：Copy without reading 选项C：Skip every test 选项D：Count pages "
                        "答案：A 解析：Practice feedback. 先尝试回忆，再对照原文。")
            elif kind == "ask" and req["question"] == "What does this passage mean?":
                text = "Passage answer. 可以用自己的话解释，再和原文核对。"
            elif kind == "lesson":
                text = "Lesson overview. 先阅读原文，再用回忆检查理解。"
            elif kind == "explain":
                text = "Block explanation. 阅读后先独立回答，再对照原文。"
            else:
                raise ValueError("unexpected question")
            block_id = chosen["id"]
            page = int(self.server.block.fullmatch(block_id).group(1))
            source = {"docId": block_id, "anchor": "S1", "pdfPage": page}
            body = json.dumps({"text": text + "【S1】", "sources": [source]}, ensure_ascii=False).encode()
        except (KeyError, ValueError, TypeError, StopIteration):
            self.send_error(400)
            return
        with self.server.events.open("a", encoding="utf-8") as log:
            log.write(json.dumps({"kind": kind, "source": source, "blocks": len(blocks)}) + "\n")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    private, fixture, events = map(Path, sys.argv[1:])
    with ThreadingHTTPServer(("127.0.0.1", 0), Tutor) as server:
        digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
        server.block = re.compile(digest + r":p([1-3]):[0-9]+")
        server.token = secrets.token_hex(24)
        server.events = events
        (private / "token").write_text(server.token, encoding="ascii")
        ready = private / "port.tmp"
        ready.write_text(str(server.server_port), encoding="ascii")
        ready.replace(private / "port")
        server.serve_forever()
