"""Fixed replies for the installed ARM UI smoke, limited to the repository PDF."""
import hashlib
import json
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import secrets
import sqlite3
import sys


# Same rule as the real server's containsReaderSelection: whitespace is squashed, then the
# selection must be a slice of one supplied block. 1.1.1 sends the whole section, so the
# selection (a dragged line) is a substring of one of those blocks, never equal to it.
def squash(value):
    return re.sub(r"\s+", " ", value).strip()


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
            selection = squash(req.get("selection", ""))
            chosen = blocks[0] if kind == "lesson" else next(
                b for b in blocks if selection and selection in squash(b["text"]))
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
            page, offset = map(int, self.server.block.fullmatch(block_id).groups())
            with closing(sqlite3.connect(self.server.database, uri=True)) as db:
                row = db.execute("SELECT text FROM pages WHERE book=? AND page=? AND ocr=1",
                                 (self.server.digest, page)).fetchone()
            if not row or not row[0].startswith(chosen["text"], offset):
                raise ValueError("source differs from imported page")
            paragraph = 1 + len(re.findall(r"\r?\n[ \t]*\r?\n", row[0][:offset]))
            source = {"docId": block_id, "anchor": "S1", "pdfPage": page,
                      "label": f"第 {page} 页 · 第 {paragraph} 段"}
            body = json.dumps({"text": text + "【S1】", "sources": [source]}, ensure_ascii=False).encode()
        except (KeyError, ValueError, TypeError, StopIteration, sqlite3.Error):
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
    private, fixture, events, database = map(Path, sys.argv[1:])
    with ThreadingHTTPServer(("127.0.0.1", 0), Tutor) as server:
        digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
        server.digest = digest
        server.database = database.resolve().as_uri() + "?mode=ro"
        server.block = re.compile(digest + r":p([1-3]):([0-9]+)")
        server.token = secrets.token_hex(24)
        server.events = events
        (private / "token").write_text(server.token, encoding="ascii")
        ready = private / "port.tmp"
        ready.write_text(str(server.server_port), encoding="ascii")
        ready.replace(private / "port")
        server.serve_forever()
