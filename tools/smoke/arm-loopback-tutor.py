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

    def _authorized(self):
        if self.headers.get("Authorization") != "Bearer " + self.server.token:
            self.send_error(401)
            return False
        return True

    def _read_json(self, limit):
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= limit:
            raise ValueError("body size")
        return json.loads(self.rfile.read(size))

    def _source_for(self, block):
        block_id = block["id"]
        matched = self.server.block.fullmatch(block_id)
        if not matched:
            raise ValueError("unexpected book")
        page, offset = map(int, matched.groups())
        with closing(sqlite3.connect(self.server.database, uri=True)) as db:
            row = db.execute("SELECT text FROM pages WHERE book=? AND page=? AND ocr=1",
                             (self.server.digest, page)).fetchone()
        if not row or not row[0].startswith(block["text"], offset):
            raise ValueError("source differs from imported page")
        paragraph = 1 + len(re.findall(r"\r?\n[ \t]*\r?\n", row[0][:offset]))
        source = {"docId": block_id, "anchor": "S1", "pdfPage": page,
                  "label": f"第 {page} 页 · 第 {paragraph} 段"}
        return page, offset, block_id, source

    def _reply(self, body, events):
        encoded = json.dumps(body, ensure_ascii=False).encode()
        with self.server.events.open("a", encoding="utf-8") as log:
            for event in events:
                log.write(json.dumps(event, ensure_ascii=False) + "\n")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _fixture_blocks(self, node, found):
        if isinstance(node, dict):
            block_id = node.get("id")
            text = node.get("text")
            if isinstance(block_id, str) and isinstance(text, str) and self.server.block.fullmatch(block_id):
                found.append(node)
            for value in node.values():
                self._fixture_blocks(value, found)
        elif isinstance(node, list):
            for value in node:
                self._fixture_blocks(value, found)

    def _companion(self, req):
        found = []
        self._fixture_blocks(req.get("sections", []), found)
        if not found:
            raise ValueError("unexpected book")
        page, offset, block_id, source = self._source_for(found[0])
        quote = squash(found[0]["text"])[:40]
        section_id = ""
        sections = req.get("sections") or []
        if sections and isinstance(sections[0], dict):
            section_id = sections[0].get("sectionId") or ""
        anchor = {"pdfPage": page, "blockId": block_id, "startUtf16": offset,
                  "endUtf16": offset + len(found[0]["text"]), "quoteAnchor": quote}
        lesson = "Lesson overview. 先阅读原文，再用回忆检查理解。"
        pack = {
            "version": 1,
            "bookId": req.get("bookId") or self.server.digest,
            "chapterId": req.get("chapterId") or "arm-smoke",
            "chapterTitle": req.get("chapterTitle") or "第 1 章",
            "coreQuestion": lesson,
            "keyPoints": [{
                "id": "arm-kp-1", "sectionId": section_id, "summary": lesson, "anchor": anchor,
            }],
            "pretest": [{
                "id": "arm-pretest-1",
                "sectionId": section_id,
                "stem": "Which method checks recall?",
                "options": ["Recall before checking", "Copy without reading", "Skip every test", "Count pages"],
                "correctOption": "Recall before checking",
                "explanation": "Practice feedback. 先尝试回忆，再对照原文。",
                "revealHint": "先回忆再对照",
                "kind": "MCQ",
                "anchor": anchor,
            }],
        }
        # 2.0 delivers the lesson and the pretest in this one response. The pretest
        # is graded on the device, so the request that carries it is the practice step.
        self._reply(pack, [
            {"kind": "lesson", "source": source, "blocks": len(found)},
            {"kind": "practice", "source": source, "blocks": len(found)},
        ])

    def do_POST(self):
        if self.path not in ("/v1/library/explain", "/v1/library/companion/chapter"):
            self.send_error(404)
            return
        if not self._authorized():
            return
        try:
            if self.path == "/v1/library/companion/chapter":
                self._companion(self._read_json(524288))
                return
            req = self._read_json(524288)
            blocks = req["blocks"]
            if not blocks or not all(self.server.block.fullmatch(b["id"]) for b in blocks):
                raise ValueError("unexpected book")
            kind = req["kind"]
            selection = squash(req.get("selection", ""))
            if kind in ("lesson", "guide"):
                chosen = blocks[0]
            else:
                chosen = next(b for b in blocks if selection and selection in squash(b["text"]))
            logged = kind
            if kind == "ask" and "选项A" in req.get("question", ""):
                logged = "practice"
                text = ("题干：Which method checks recall? 选项A：Recall before checking "
                        "选项B：Copy without reading 选项C：Skip every test 选项D：Count pages "
                        "答案：A 解析：Practice feedback. 先尝试回忆，再对照原文。")
            elif kind == "ask" and req.get("question") == "What does this passage mean?":
                text = "Passage answer. 可以用自己的话解释，再和原文核对。"
            elif kind == "lesson":
                text = "Lesson overview. 先阅读原文，再用回忆检查理解。"
            elif kind == "guide":
                text = "Section guide. 这一节可以从原文读起。"
            elif kind == "explain":
                text = "Block explanation. 阅读后先独立回答，再对照原文。"
            else:
                raise ValueError("unexpected question")
            _page, _offset, _block_id, source = self._source_for(chosen)
            body = {"text": text + "【S1】", "sources": [source]}
            events = [] if kind == "guide" else [
                {"kind": logged, "source": source, "blocks": len(blocks)}
            ]
            self._reply(body, events)
        except (KeyError, ValueError, TypeError, StopIteration, sqlite3.Error, json.JSONDecodeError):
            self.send_error(400)
            return


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
