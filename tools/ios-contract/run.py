#!/usr/bin/env python3
"""Real HTTP/WS contract checks against a disposable Coach server. See README.md."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import ctypes
from ctypes import wintypes

from websocket_probe import Closed, WebSocket

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
COURSE = "contract-course"
BASE = f"/v1/courses/{COURSE}"
READER = f"{BASE}/reader"
FORBIDDEN = {8790, 8791, 18791, 3790}
TEXT_ID = "orchard-text"
READ_ID = "orchard-read"
NOTE_TEXT = "合成锚定批注"
SOURCES = [
    "server/app/src/main/kotlin/com/coach/server/Main.kt",
    "server/app/src/main/kotlin/com/coach/server/LedgerRoutes.kt",
    "server/app/src/main/kotlin/com/coach/server/Mainline.kt",
    "server/app/src/main/kotlin/com/coach/server/JournalService.kt",
    "server/app/src/main/kotlin/com/coach/server/ReaderRoutes.kt",
    "server/app/src/main/kotlin/com/coach/server/ReaderLibrary.kt",
    "server/store/src/main/kotlin/com/coach/store/LedgerStore.kt",
    "shared/protocol/src/commonMain/kotlin/com/coach/protocol/LedgerProtocol.kt",
    "shared/client/src/commonMain/kotlin/com/coach/client/HttpLedgerTransport.kt",
    "shared/client/src/commonMain/kotlin/com/coach/client/ReaderHttp.kt",
]


def server_binary():
    directory = ROOT / "server/app/build/install/app/bin"
    return directory / ("app.bat" if os.name == "nt" else "app")


def invented_pdf(pages=2, label="Synthetic pears"):
    """Minimal PDF matching the server's invented fixture. Poppler extracts its text."""
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", "",
               "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    kids = []
    for index in range(pages):
        page_id = len(objects) + 1
        kids.append(page_id)
        stream = f"BT /F1 18 Tf 30 300 Td ({label} PDF {index + 1}) Tj ET"
        objects.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] "
                       f"/Resources << /Font << /F1 3 0 R >> >> /Contents {page_id + 1} 0 R >>")
        objects.append(f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream")
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(f'{kid} 0 R' for kid in kids)}] /Count {pages} >>"
    pdf = "%PDF-1.4\n"
    offsets = [0]
    for index, obj in enumerate(objects):
        offsets.append(len(pdf))
        pdf += f"{index + 1} 0 obj\n{obj}\nendobj\n"
    xref = len(pdf)
    pdf += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n"
    pdf += "".join(f"{offset:010d} 00000 n \n" for offset in offsets[1:])
    pdf += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    return pdf.encode("ascii")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def poppler_dir():
    found = shutil.which("pdftotext")
    if found:
        return str(Path(found).parent)
    if os.name == "nt":
        root = Path(r"D:\agent\tools\poppler")
        matches = sorted(root.glob("**/pdftotext.exe")) if root.is_dir() else []
        if matches:
            return str(matches[-1].parent)
    return None


def build_server():
    """Mac keeps tools/dev/heavy. Windows takes the same two-slot queue, then low-priority gradlew.bat."""
    if os.name != "nt":
        return subprocess.run([str(ROOT / "tools/dev/heavy"), "./gradlew", ":server:app:installDist",
                               "--max-workers=2", "--console=plain"], cwd=ROOT).returncode
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.LPCWSTR]
    kernel.CreateMutexW.restype = wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.WaitForSingleObject.restype = wintypes.DWORD
    kernel.ReleaseMutex.argtypes = [wintypes.HANDLE]
    kernel.ReleaseMutex.restype = wintypes.BOOL
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.SetPriorityClass.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.SetPriorityClass(kernel.GetCurrentProcess(), 0x00000040)  # IDLE_PRIORITY_CLASS
    owned = None
    deadline = time.monotonic() + int(os.environ.get("COACH_WINBOX_QUEUE_SECONDS", "900"))
    try:
        while owned is None:
            for index in (0, 1):
                handle = kernel.CreateMutexW(None, False, f"Global\\CoachHeavySlot{index}")
                if not handle:
                    continue
                result = kernel.WaitForSingleObject(handle, 0)
                if result in (0, 0x80):
                    owned = handle
                    break
                kernel.CloseHandle(handle)
            if owned is not None:
                break
            if time.monotonic() >= deadline:
                raise ContractFailure("winbox gradle slot was not free before the queue timeout")
            print("WAITING winbox gradle slot", flush=True)
            time.sleep(2)
        env = os.environ.copy()
        gradle_home = Path(r"D:\agent\gradle-home")
        if gradle_home.is_dir():
            env["GRADLE_USER_HOME"] = str(gradle_home)
        command = [str(ROOT / "gradlew.bat"), "--priority=low", "--max-workers=2", "--console=plain",
                   "--no-daemon", "--no-configuration-cache", "--build-cache", ":server:app:installDist"]
        return subprocess.run(command, cwd=ROOT, env=env).returncode
    finally:
        if owned is not None:
            kernel.ReleaseMutex(owned)
            kernel.CloseHandle(owned)


class ContractFailure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ContractFailure(message)


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def wire(device, command_id, kind, payload):
    # Preserve these exact UTF-8 bytes for retry; Unicode deliberately exercises hashing.
    body = json.dumps(dict(type=kind, courseId=COURSE, clientTime=1800000000000,
                           payload=payload), ensure_ascii=False, separators=(",", ":"))
    return dict(deviceId=device, commandId=command_id, payloadBytes=body,
                payloadSha256=hashlib.sha256(body.encode()).hexdigest())


class Server:
    def __init__(self, directory):
        self.directory = directory
        self.process = None
        while True:
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                self.port = sock.getsockname()[1]
            if self.port not in FORBIDDEN:
                break
        # Clear inherited app/bridge/JVM injection, and isolate Java user.home as well.
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("COACH_", "BRIDGE_")) and
                    k not in {"JAVA_OPTS", "APP_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        home = (directory / "home").resolve().as_posix()
        self.library = directory / "reader"
        self.env.update(
            JAVA_OPTS=f"-Duser.home={home} -Xmx512m",
            COACH_DATA_DIR=(directory / "data").resolve().as_posix(),
            COACH_TOKEN_FILE=(directory / "data/device-token").resolve().as_posix(),
            COACH_DEVICE_ID="contract-admin",
            COACH_GENERATED_DIR=(directory / "generated").resolve().as_posix(),
            COACH_MOCK_DIR=(directory / "mock").resolve().as_posix(),
            COACH_PACKS_DIR=(directory / "packs").resolve().as_posix(),
            COACH_LOG_DIR=(directory / "logs").resolve().as_posix(),
            COACH_STATE_DIR=(directory / "state").resolve().as_posix(),
            COACH_ACTIVITY_SOCKET=(directory / "activity.sock").resolve().as_posix(),
            COACH_READER_LIBRARY=self.library.resolve().as_posix(),
            COACH_TAIL_HOST="", COACH_PORT=str(self.port), COACH_TRAFFIC="test",
            COACH_BRIDGE_URL="http://127.0.0.1:1", COACH_SOURCE_LOOKUP="false")
        poppler = poppler_dir()
        if poppler:
            self.env["PATH"] = poppler + os.pathsep + self.env.get("PATH", "")
        for name in ("home", "data", "generated", "packs", "logs", "state", "mock/mcq-mainline", "reader"):
            (directory / name).mkdir(parents=True, exist_ok=True)
        for name in ("W01.json", "W01-facts.md"):
            shutil.copyfile(HERE / "fixtures" / name, directory / "mock/mcq-mainline" / name)
        self.text_pdf = invented_pdf()
        self.read_pdf = invented_pdf(label="Synthetic reading")
        self.write_catalog()

    def write_catalog(self):
        def entry(doc_id, pdf, filename, kind, course=COURSE, offset=0):
            item = dict(id=doc_id, course=course, title="Invented " + kind, file=filename,
                        sha256=sha256(pdf), pages=2, kind=kind)
            if kind == "textbook":
                item["printedPageOffset"] = offset
            return item
        (self.library / "text.pdf").write_bytes(self.text_pdf)
        (self.library / "read.pdf").write_bytes(self.read_pdf)
        other = invented_pdf(label="Other course")
        (self.library / "other.pdf").write_bytes(other)
        catalog = dict(schema=1, documents=[
            entry(TEXT_ID, self.text_pdf, "text.pdf", "textbook"),
            entry(READ_ID, self.read_pdf, "read.pdf", "reading"),
            entry("other-course-text", other, "other.pdf", "textbook", course="other-course"),
        ])
        (self.library / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")

    def request(self, path, credential=None, method="GET", body=None, expected=200):
        headers = {}
        if credential:
            headers.update({"X-Coach-Device": credential[0], "Authorization": "Bearer " + credential[1]})
        if body is not None:
            body = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            raw = response.read(4 * 1024 * 1024 + 1)
            require(len(raw) <= 4 * 1024 * 1024, "oversized HTTP response")
            require(response.status == expected,
                    f"{method} {path.split('?')[0]} expected HTTP {expected}, received {response.status}")
            self.last_headers = dict((k.lower(), v) for k, v in response.getheaders())
            if not raw:
                return None
            try:
                return json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                require(expected >= 400, "successful HTTP response was not JSON")
                return None
        finally:
            connection.close()

    def start(self):
        # Windows app.bat expands every jar into one command and exceeds the 8191-character limit
        # under a long checkout path. Java's own classpath wildcard stays one argument. macOS keeps app.
        if os.name == "nt":
            java_home = self.env.get("JAVA_HOME") or os.environ.get("JAVA_HOME")
            java = str(Path(java_home) / "bin/java.exe") if java_home else "java.exe"
            home = (self.directory / "home").resolve().as_posix()
            lib = ROOT / "server/app/build/install/app/lib"
            command = [java, f"-Duser.home={home}", "-Xmx512m", "-cp", str(lib / "*"), "com.coach.server.MainKt"]
        else:
            command = [str(server_binary())]
        with (self.directory / "server.log").open("ab") as log:
            self.process = subprocess.Popen(command, cwd=ROOT, env=self.env,
                                            stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise ContractFailure("isolated server exited before readiness: " + self.startup_excerpt())
            try:
                self.request("/v1/hello", expected=401)
                token = (self.directory / "data/device-token").read_text().strip()
                self.admin = ("contract-admin", token)
                return
            except OSError:
                time.sleep(0.1)
        raise ContractFailure("isolated server readiness timed out: " + self.startup_excerpt())

    def startup_excerpt(self):
        log = self.directory / "server.log"
        if not log.exists():
            return "no server log"
        lines = []
        for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
            lowered = line.lower()
            if any(word in lowered for word in ("token", "bearer", "password", "secret", "authorization")):
                continue
            text = line.strip()
            if text:
                lines.append(text[:180])
        picked = [line for line in lines if line.startswith("Exception") or "Main.kt" in line or "posix" in line.lower()]
        return " | ".join((picked or lines)[:3]) or "no server log"

    def stop(self):
        process = self.process
        if process is None:
            return
        self.process = None
        if process.poll() is None:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                if os.name != "nt":
                    process.kill()
                process.wait(timeout=5)

    def pair(self, name):
        code = self.request("/v1/pairing-code", self.admin, "POST")
        require(self.last_headers.get("cache-control") == "no-store", "pairing code must not be cached")
        result = self.request("/v1/pair", method="POST", body=dict(code=code["code"], name=name))
        require(self.last_headers.get("cache-control") == "no-store", "credential must not be cached")
        require(len(result["token"]) >= 32, "invalid issued token length")
        self.request("/v1/pair", method="POST", body=dict(code=code["code"], name=name), expected=401)
        return result["deviceId"], result["token"]


class Suite:
    def __init__(self, server, report, inject_failure):
        self.server, self.report, self.inject_failure = server, report, inject_failure

    def check(self, name, function):
        start = time.monotonic()
        item = dict(name=name, status="running")
        self.report["checks"].append(item)
        try:
            function()
            item["status"] = "passed"
        except Exception as error:
            item["status"] = "failed"
            # Only controlled assertion text is safe to publish; never raw HTTP/server logs.
            item["error"] = str(error) if isinstance(error, ContractFailure) else type(error).__name__
            raise
        finally:
            item["seconds"] = round(time.monotonic() - start, 3)
            print(f"{item['status'].upper()} {name}", flush=True)

    def run(self):
        s = self.server
        self.check("isolated server readiness", s.start)

        def auth():
            s.request("/v1/hello", expected=401)
            s.request("/v1/hello", (s.admin[0], "x" * 43),
                      expected=200 if self.inject_failure else 401)
            s.request("/v1/hello?access_token=synthetic", s.admin, expected=401)
            s.request("/v1/pair", method="POST", body=b"{", expected=401)
            self.phone = s.pair("Synthetic iOS")
            self.other = s.pair("Synthetic existing HTTP client")
            s.request("/v1/hello", (self.phone[0], self.other[1]), expected=401)
            s.request("/v1/devices", self.phone, expected=403)
            s.request("/v1/pairing-code", self.phone, "POST", expected=403)
            s.request("/v1/devices/contract-admin", s.admin, "DELETE", expected=403)
            devices = s.request("/v1/devices", s.admin)
            require({self.phone[0], self.other[0]} <= {d["deviceId"] for d in devices}, "paired devices absent")
            WebSocket(s.port, (self.phone[0], "x" * 43), expected_status=401)
            self.hello = s.request("/v1/hello", self.phone)
            require(all(k in self.hello for k in ("epoch", "lastSeq", "backedUpThroughSeq", "lastSeqBefore")), "hello fields absent")
            require(self.hello.get("protocol", 1) == 1, "unsupported protocol version")
        self.check("HTTP/WS auth, single-use pairing and admin boundaries", auth)

        def commands():
            self.note = wire(self.phone[0], "note-original", "save_note", dict(noteId="note-1", text="合成笔记 — orchard"))
            first = s.request("/v1/commands", self.phone, "POST", self.note)
            require(first["status"] == "OK" and first["eventSeqs"], "write was not committed")
            self.note_seqs = first["eventSeqs"]
            replay = s.request("/v1/commands", self.phone, "POST", self.note)
            require(replay["status"] == "DUPLICATE" and replay["eventSeqs"] == self.note_seqs, "retry duplicated write")
            changed = wire(self.phone[0], "note-original", "save_note", dict(noteId="note-1", text="changed"))
            rejected = s.request("/v1/commands", self.phone, "POST", changed)
            require(rejected["status"] == "REJECTED" and rejected["error"] == "PAYLOAD_MISMATCH", "changed retry accepted")
            bad_hash = dict(self.note, commandId="bad-hash", payloadSha256="0" * 64)
            require(s.request("/v1/commands", self.phone, "POST", bad_hash)["status"] == "REJECTED", "bad hash accepted")
            unknown = wire(self.phone[0], "unknown", "synthetic_unknown_command", {})
            rejected = s.request("/v1/commands", self.phone, "POST", unknown)
            require(rejected["status"] == "REJECTED" and rejected["error"] == "UNSUPPORTED_COMMAND", "unknown command accepted")
            s.request("/v1/commands", self.other, "POST", self.note, expected=403)
            s.request("/v1/commands", self.phone, "POST", b"{", expected=400)
            snapshot = s.request(BASE + "/snapshot", self.other)
            event = next(e for e in snapshot["events"] if e["seq"] == self.note_seqs[0])
            require(event["payload"]["text"] == "合成笔记 — orchard", "second client lost Unicode note")
        self.check("immutable command bytes, idempotency and second-client readback", commands)

        def conflict():
            first = wire(self.phone[0], "journal-first", "edit_journal", dict(day=20833, expectedRev=0, content="first writer"))
            require(s.request("/v1/commands", self.phone, "POST", first)["status"] == "OK", "initial journal write failed")
            self.stale = wire(self.other[0], "journal-stale", "edit_journal", dict(day=20833, expectedRev=0, content="stale writer"))
            lost = s.request("/v1/commands", self.other, "POST", self.stale, expected=409)
            require(lost["status"] == "CONFLICT" and lost["error"] == "REV_MISMATCH" and
                    lost["result"] == dict(currentRev=1, content="first writer"), "conflict winner missing")
            batch = s.request("/v1/commands", self.other, "POST", [self.stale])
            require(batch[0]["status"] == "CONFLICT", "batch HTTP 200 concealed conflict")
            current = s.request(BASE + "/journal/20833", self.other)
            require(current["rev"] == 1 and current["manual"] == "first writer", "stale writer overwrote journal")
        self.check("optimistic journal revision conflict, single and batch receipt semantics", conflict)

        def practice():
            empty = s.request("/v1/courses/empty-course/mainline", self.phone)
            require(empty["weeks"] == [], "empty course was not empty")
            pack = s.request("/offline-pack?courseId=empty-course&size=3", self.phone)
            require(pack["groups"] == [] and pack["keys"] == [], "empty pack fabricated questions")
            s.request("/offline-pack?courseId=empty-course&size=31", self.phone, expected=400)
            s.request(BASE + "/mainline/99", self.phone, expected=404)
            legacy = s.request(BASE + "/mainline/1", self.other)
            modern = s.request(BASE + "/mainline/1?layout=shuffled-v1", self.phone)
            require(len(legacy["items"]) == len(modern["items"]) == 2, "synthetic questions not loaded")
            require(legacy["items"][0]["options"][0] == "Pears", "legacy option order changed")
            item = modern["items"][0]
            require("layout" in item and "answer" not in item and "explanation" not in item, "question/key boundary changed")
            self.answer = dict(itemId=item["id"], choice=item["options"].index("Pears"), layout=item["layout"], submissionId="synthetic-answer")
            self.answered = s.request(BASE + "/mainline/1/answer", self.phone, "POST", self.answer)
            require(self.answered["status"] == "mastered", "correct verified answer not mastered")
            retried = dict(self.answer, choice=(self.answer["choice"] + 1) % 4, guessed=True)
            require(s.request(BASE + "/mainline/1/answer", self.other, "POST", retried) == self.answered, "answer retry changed committed result")
            other_id = next(i["id"] for i in modern["items"] if i["id"] != item["id"])
            s.request(BASE + "/mainline/1/answer", self.other, "POST", dict(self.answer, itemId=other_id), expected=409)
            s.request(BASE + "/mainline/1/answer", self.phone, "POST", b"{", expected=400)
            require(next(i for i in s.request(BASE + "/mainline/1", self.other)["items"] if i["id"] == item["id"])["status"] == "mastered", "second client missed practice state")
        self.check("empty questions, legacy/shuffled clients, answer retry and conflict", practice)

        def websocket():
            cursor = s.request("/v1/hello", self.other)["lastSeq"]
            with WebSocket(s.port, self.phone) as ws:
                ws.until(lambda m: m.get("epoch") == self.hello["epoch"] and "lastSeq" in m)
                ws.send(dict(t="hello", epoch=self.hello["epoch"], ledgerAfter={COURSE: cursor}))
                new = wire(self.other[0], "ws-live", "save_note", dict(noteId="live", text="synthetic live"))
                receipt = s.request("/v1/commands", self.other, "POST", new)
                self.live_seq = receipt["eventSeqs"][0]
                frame = ws.until(lambda m: any(e["seq"] == self.live_seq for e in m.get("events", [])))
                require(frame["t"] == "ledger" and frame["course"] == COURSE, "live WS envelope incorrect")
            offline = wire(self.other[0], "ws-offline", "save_note", dict(noteId="offline", text="synthetic offline"))
            self.offline_seq = s.request("/v1/commands", self.other, "POST", offline)["eventSeqs"][0]
            with WebSocket(s.port, self.phone) as ws:
                ws.send(dict(t="hello", epoch=self.hello["epoch"], ledgerAfter={COURSE: self.live_seq}))
                frame = ws.until(lambda m: any(e["seq"] == self.offline_seq for e in m.get("events", [])))
                require(all(e["seq"] > self.live_seq for e in frame["events"]), "reconnect ignored cursor")
            with WebSocket(s.port, self.phone) as ws:
                ws.send(dict(t="hello", epoch="synthetic-old-epoch", ledgerAfter={COURSE: 999999999}))
                ws.until(lambda m: any(e["seq"] == self.note_seqs[0] for e in m.get("events", [])))
            # Existing Android transport does not send streaming hello: it consumes HTTP hints.
            with WebSocket(s.port, self.other) as ws:
                hint = ws.until(lambda m: m.get("course") == COURSE and "httpCatchUp" in m)
                page = s.request(hint["httpCatchUp"]["url"], self.other)
                require(any(e["seq"] == self.offline_seq for e in page["events"]), "legacy HTTP hint lost history")
            collected, after = [], 0
            while True:
                page = s.request(BASE + f"/events?after={after}&limit=2", self.other)
                require(page["epoch"] == self.hello["epoch"], "HTTP pagination epoch changed")
                collected.extend(e["seq"] for e in page["events"])
                if not page.get("hasMore", False):
                    break
                require(page["throughSeq"] > after, "HTTP pagination stalled")
                after = page["throughSeq"]
            snapshot = s.request(BASE + "/snapshot", self.other)
            require(collected == [e["seq"] for e in snapshot["events"]], "paged events lost/duplicated history")
        self.check("WS live data, reconnect, epoch reset, legacy hints and HTTP pagination", websocket)

        def errors():
            before = s.request(BASE + "/journal/20833", self.other)
            # The only bridge is loopback port 1, deliberately unavailable; no subscription CLI.
            s.request(BASE + "/journal/20833/summary", self.other, "POST", expected=502)
            after = s.request(BASE + "/journal/20833", self.other)
            require(after["manual"] == before["manual"] and after.get("summary") is None,
                    "upstream failure fabricated summary or overwrote manual text")
        self.check("real upstream failure returns 502 without fabricated state", errors)

        def restart():
            before = s.request(BASE + "/snapshot", self.other)
            s.stop()
            try:
                s.request("/v1/hello", self.other)
            except OSError:
                pass
            else:
                raise ContractFailure("stopped server unexpectedly reachable")
            s.start()
            after = s.request(BASE + "/snapshot", self.other)
            require(after["events"] == before["events"], "restart changed persisted events")
            require(after["projection"] == before["projection"], "restart changed persisted projection")
            require(after["hello"]["epoch"] == before["hello"]["epoch"], "normal restart changed epoch")
            require(s.request("/v1/commands", self.phone, "POST", self.note)["status"] == "DUPLICATE", "restart lost command receipt")
            require(s.request(BASE + "/mainline/1/answer", self.other, "POST", self.answer) == self.answered, "restart lost answer receipt")
            s.request("/v1/commands", self.other, "POST", self.stale, expected=409)
            with WebSocket(s.port, self.phone) as ws:
                ws.send(dict(t="hello", epoch=self.hello["epoch"], ledgerAfter={COURSE: self.live_seq}))
                ws.until(lambda m: any(e["seq"] == self.offline_seq for e in m.get("events", [])))
        self.check("service outage, persisted restart, retry receipts and WS catch-up", restart)

        def reader():
            self.report["missing"] = [item for item in self.report["missing"]
                                      if item.get("capability") != "reader and anchored annotations"]
            require(poppler_dir(), "local pdftotext is absent; page text and anchored notes were not sent")
            s.request(READER, expected=401)
            catalog = s.request(READER, self.phone)
            require(s.last_headers.get("cache-control") == "no-store", "reader catalog was cacheable")
            documents = {item["id"]: item for item in catalog["documents"]}
            require(set(documents) == {TEXT_ID, READ_ID}, "reader catalog mixed courses or dropped a document")
            require(catalog.get("privateAi") == "disabled_no_local_inference", "reader catalog changed private-ai flag")
            require(documents[TEXT_ID]["kind"] == "textbook" and documents[READ_ID]["kind"] == "reading",
                    "reader catalog kinds changed")
            text_version = documents[TEXT_ID]["version"]
            read_version = documents[READ_ID]["version"]
            require(text_version != read_version, "two synthetic documents hashed to one version")
            page = s.request(f"{READER}/{TEXT_ID}/pages/1", self.phone)
            require(page["documentId"] == TEXT_ID and page["version"] == text_version and page["pdfPage"] == 1,
                    "reader page identity changed")
            text = page.get("text")
            require(isinstance(text, str) and len(text) >= 1 and isinstance(page.get("textSha256"), str)
                    and len(page["textSha256"]) == 64, "reader page text was not returned")
            end = min(15, len(text))
            quote = text[:end]
            study = s.request(f"{READER}/{TEXT_ID}/pages/1/study", self.other)
            question_ids = {item["id"] for item in study["questions"]}
            fact_ids = {item["id"] for item in study["facts"]}
            require(question_ids == {"orchard-1", "orchard-2"} and fact_ids == {"F1"},
                    "study page did not return the synthetic questions and fact")
            require(any(item["id"] == self.answer["itemId"] and item["status"] == "mastered" for item in study["questions"]),
                    "study page lost the mastered synthetic answer")
            other_page = s.request(f"{READER}/{TEXT_ID}/pages/2/study", self.phone)
            require(other_page["questions"] == [] and other_page["facts"] == [], "study results leaked onto another page")
            other_doc = s.request(f"{READER}/{READ_ID}/pages/1/study", self.phone)
            require(other_doc["questions"] == [] and other_doc["documentId"] == READ_ID,
                    "study results leaked onto another document")
            s.request(f"{READER}/missing-doc/state", self.phone, expected=404)

            def state(doc_id, credential):
                body = s.request(f"{READER}/{doc_id}/state", credential)
                require(body["document"]["id"] == doc_id, "reader state returned another document")
                return body

            require(state(TEXT_ID, self.phone)["progress"] is None and state(TEXT_ID, self.phone)["notes"] == [],
                    "fresh reader state was not empty")
            progress = dict(version=text_version, pdfPage=1, baseRevision=0, mutationId="progress-one")
            saved = s.request(f"{READER}/{TEXT_ID}/progress", self.phone, "POST", progress)
            require(saved.get("replayed") is False and isinstance(saved.get("revision"), int), "progress write was not committed")
            seen = state(TEXT_ID, self.other)
            require(seen["progress"]["pdfPage"] == 1 and seen["progress"]["version"] == text_version
                    and seen["progress"]["stale"] is False, "second client lost reading progress")
            s.request(f"{READER}/{TEXT_ID}/progress", self.other, "POST",
                      dict(progress, mutationId="progress-stale"), expected=409)
            require(state(TEXT_ID, self.phone)["progress"]["revision"] == saved["revision"],
                    "stale progress write replaced the saved page")
            note = dict(id="anchor-one", version=text_version, pdfPage=1, textSha256=page["textSha256"],
                        start=0, end=end, quote=quote, note=NOTE_TEXT, deleted=False,
                        baseRevision=0, mutationId="anchor-one")
            # ReaderRoutes keys idempotency by course, device and mutation. The same original
            # bytes must replay on that device. Another device still on baseRevision 0 conflicts.
            note_bytes = json.dumps(note, ensure_ascii=False).encode()
            anchored = s.request(f"{READER}/{TEXT_ID}/notes", self.phone, "POST", note_bytes)
            require(anchored.get("replayed") is False, "anchored note was not committed")
            replay = s.request(f"{READER}/{TEXT_ID}/notes", self.phone, "POST", note_bytes)
            require(replay.get("replayed") is True and replay.get("revision") == anchored["revision"],
                    "anchored note retry was not idempotent")
            s.request(f"{READER}/{TEXT_ID}/notes", self.other, "POST",
                      dict(note, note="different synthetic body", mutationId="anchor-stale"), expected=409)

            def find_note(body, note_id, expected_quote, expected_version):
                notes = [item for item in body["notes"] if item.get("id") == note_id]
                require(len(notes) == 1, "anchored note was dropped or duplicated")
                item = notes[0]
                require(item["quote"] == expected_quote and item["pdfPage"] == 1
                        and item["version"] == expected_version,
                        "anchored note moved to another page or version")
                return item

            kept = find_note(state(TEXT_ID, self.other), "anchor-one", quote, text_version)
            require(kept["revision"] == anchored["revision"] and kept["note"] == NOTE_TEXT and kept["stale"] is False,
                    "409 replaced the anchored note")
            require(state(READ_ID, self.other)["notes"] == [] and state(READ_ID, self.other)["progress"] is None,
                    "textbook annotation leaked into the other document")
            read_page = s.request(f"{READER}/{READ_ID}/pages/1", self.phone)
            read_text = read_page["text"]
            read_end = min(15, len(read_text))
            read_quote = read_text[:read_end]
            read_note = dict(id="anchor-read", version=read_version, pdfPage=1, textSha256=read_page["textSha256"],
                             start=0, end=read_end, quote=read_quote, note="reading-margin",
                             deleted=False, baseRevision=0, mutationId="anchor-read")
            read_saved = s.request(f"{READER}/{READ_ID}/notes", self.other, "POST", read_note)
            require(read_saved.get("replayed") is False, "second document note was not committed")

            cursor = s.request("/v1/hello", self.other)["lastSeq"]
            moved = dict(version=text_version, pdfPage=2, baseRevision=saved["revision"], mutationId="progress-reconnect")
            with WebSocket(s.port, self.phone) as ws:
                ws.until(lambda message: message.get("epoch") == self.hello["epoch"] and "lastSeq" in message)
            resumed = s.request(f"{READER}/{TEXT_ID}/progress", self.phone, "POST", moved)
            require(resumed.get("replayed") is False, "progress write after disconnect was not committed")
            with WebSocket(s.port, self.phone) as ws:
                ws.send(dict(t="hello", epoch=self.hello["epoch"], ledgerAfter={COURSE: cursor}))
                ws.until(lambda message: any(event.get("seq") == resumed["revision"] and event.get("type") == "reader_progress_saved"
                                             for event in message.get("events", [])))
            before = state(TEXT_ID, self.other)
            read_before = state(READ_ID, self.phone)
            s.stop()
            try:
                s.request(f"{READER}/{TEXT_ID}/state", self.other)
            except OSError:
                pass
            else:
                raise ContractFailure("stopped reader unexpectedly reachable")
            s.start()
            after = state(TEXT_ID, self.phone)
            require(after["progress"]["pdfPage"] == 2 and after["progress"]["revision"] == before["progress"]["revision"],
                    "restart lost reading progress")
            require(find_note(after, "anchor-one", quote, text_version)["note"] == NOTE_TEXT
                    and find_note(state(READ_ID, self.other), "anchor-read", read_quote, read_version)["note"] == "reading-margin",
                    "restart lost an anchored note or mixed the documents")
            require(find_note(read_before, "anchor-read", read_quote, read_version)["revision"]
                    == find_note(state(READ_ID, self.phone), "anchor-read", read_quote, read_version)["revision"],
                    "restart changed the other document")

            s.stop()
            s.text_pdf = invented_pdf(label="Synthetic pears revised")
            s.write_catalog()
            s.start()
            revised = state(TEXT_ID, self.other)
            retained = find_note(revised, "anchor-one", quote, text_version)
            require(retained["stale"] is True and retained["note"] == NOTE_TEXT and retained["version"] == text_version,
                    "new document version dropped or rewrote the old anchored note")
            require(revised["progress"]["stale"] is True and revised["progress"]["pdfPage"] == 2,
                    "new document version dropped the old reading progress")
            require(find_note(state(READ_ID, self.phone), "anchor-read", read_quote, read_version)["stale"] is False,
                    "version change marked another document stale")
            s.request(f"{READER}/{TEXT_ID}/notes", self.phone, "POST", note, expected=409)
            require(find_note(state(TEXT_ID, self.other), "anchor-one", quote, text_version)["revision"] == retained["revision"]
                    and find_note(state(TEXT_ID, self.phone), "anchor-one", quote, text_version)["note"] == NOTE_TEXT,
                    "rejected old version replaced the retained note")
            fresh = s.request(f"{READER}/{TEXT_ID}/pages/1", self.other)
            require(fresh["version"] != text_version, "restart kept the old document version")
            fresh_end = min(15, len(fresh["text"]))
            second = dict(id="anchor-two", version=fresh["version"], pdfPage=1, textSha256=fresh["textSha256"],
                          start=0, end=fresh_end, quote=fresh["text"][:fresh_end], note="second-version-margin",
                          deleted=False, baseRevision=0, mutationId="anchor-two")
            created = s.request(f"{READER}/{TEXT_ID}/notes", self.phone, "POST", second)
            require(created.get("replayed") is False, "note on the new version was not committed")
            final = state(TEXT_ID, self.other)
            fresh_note = find_note(final, "anchor-two", fresh["text"][:fresh_end], fresh["version"])
            require(find_note(final, "anchor-one", quote, text_version)["stale"] is True and fresh_note["stale"] is False,
                    "new version note replaced or hid the retained annotation")
            read_final = state(READ_ID, self.phone)
            require(len(read_final["notes"]) == 1
                    and find_note(read_final, "anchor-read", read_quote, read_version)["stale"] is False,
                    "new version leaked into the other document")
        if self.report["scope"] == "full" and not self.inject_failure:
            self.check("reader catalog, page, study, anchored notes, conflict, reconnect and restart", reader)

        def revoke():
            with WebSocket(s.port, self.phone) as ws:
                ws.until(lambda m: "lastSeq" in m)
                s.request("/v1/devices/" + self.phone[0], s.admin, "DELETE", expected=204)
                try:
                    ws.until(lambda _: False, timeout=6)
                except Closed as close:
                    require(close.code == 1008, "revoked WS used wrong close code")
                else:
                    raise ContractFailure("established revoked WS remained open")
            s.request("/v1/hello", self.phone, expected=401)
            s.request("/v1/commands", self.phone, "POST", self.note, expected=401)
            WebSocket(s.port, self.phone, expected_status=401)
            s.stop()
            s.start()
            s.request("/v1/hello", self.phone, expected=401)
            s.request("/v1/hello", self.other)
        self.check("revocation closes established WS and survives restart", revoke)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("full", "current"), default="full")
    parser.add_argument("--report", type=Path, default=HERE / ".evidence/report.json")
    parser.add_argument("--inject-failure", action="store_true", help="negative control: expect an invalid token to succeed")
    args = parser.parse_args()
    report = dict(schemaVersion=1, status="running", scope=args.scope, sourceCommit=git("rev-parse", "HEAD"),
                  sourceDirty=bool(git("status", "--porcelain", "--", "server", "shared")),
                  sourceFiles={p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in SOURCES},
                  toolFiles={p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in (HERE / "run.py", HERE / "websocket_probe.py")},
                  negativeControl=args.inject_failure, checks=[],
                  missing=[dict(capability="reader and anchored annotations",
                                status="unverified", owner="TON-1412",
                                reason="Full scope has not finished the merged reader/annotation checks.")],
                  unverified=["Swift codecs/UI", "real Android UI", "physical iPhone/Tailscale", "TestFlight"])
    server = None
    code = 1
    try:
        # Always rebuild the actual checkout. Unix keeps heavy; Windows uses the existing slot queue.
        result_code = build_server()
        report["buildExitCode"] = result_code
        require(result_code == 0, "installDist failed")
        binary = server_binary()
        report["serverArtifacts"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                     for p in sorted((binary.parent.parent / "lib").glob("*.jar"))}
        # Short, private path avoids macOS Unix-domain socket path limits.
        temp_parent = "/tmp" if os.name != "nt" else None
        with tempfile.TemporaryDirectory(prefix="icc-", dir=temp_parent) as temp:
            server = Server(Path(temp))
            try:
                Suite(server, report, args.inject_failure).run()
            finally:
                server.stop()
        if report["missing"] and args.scope == "full":
            code = 2
            report["status"] = "incomplete"
        else:
            code = 0
            report["status"] = "current-contracts-passed" if args.scope == "current" else "reader-contracts-passed"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error) if isinstance(error, ContractFailure) else type(error).__name__
    except KeyboardInterrupt:
        code = 130
        report["status"] = "interrupted"
    finally:
        if server:
            server.stop()
        report["counts"] = dict(passed=sum(item["status"] == "passed" for item in report["checks"]),
                                failed=sum(item["status"] == "failed" for item in report["checks"]),
                                missing=len(report["missing"]))
        report["exitCode"] = code
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"RESULT {report['status']} exit={code} passed={report['counts']['passed']} "
          f"failed={report['counts']['failed']} missing={report['counts']['missing']}", flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
