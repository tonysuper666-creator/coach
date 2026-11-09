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

from websocket_probe import Closed, WebSocket

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BINARY = ROOT / "server/app/build/install/app/bin/app"
COURSE = "contract-course"
BASE = f"/v1/courses/{COURSE}"
FORBIDDEN = {8790, 8791, 18791, 3790}
SOURCES = [
    "server/app/src/main/kotlin/com/coach/server/Main.kt",
    "server/app/src/main/kotlin/com/coach/server/LedgerRoutes.kt",
    "server/app/src/main/kotlin/com/coach/server/Mainline.kt",
    "server/app/src/main/kotlin/com/coach/server/JournalService.kt",
    "server/store/src/main/kotlin/com/coach/store/LedgerStore.kt",
    "shared/protocol/src/commonMain/kotlin/com/coach/protocol/LedgerProtocol.kt",
    "shared/client/src/commonMain/kotlin/com/coach/client/HttpLedgerTransport.kt",
]


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
        self.env.update(
            JAVA_OPTS=f"-Duser.home={directory / 'home'} -Xmx512m",
            COACH_DATA_DIR=str(directory / "data"),
            COACH_TOKEN_FILE=str(directory / "data/device-token"), COACH_DEVICE_ID="contract-admin",
            COACH_GENERATED_DIR=str(directory / "generated"), COACH_MOCK_DIR=str(directory / "mock"),
            COACH_PACKS_DIR=str(directory / "packs"), COACH_LOG_DIR=str(directory / "logs"),
            COACH_STATE_DIR=str(directory / "state"), COACH_ACTIVITY_SOCKET=str(directory / "activity.sock"),
            COACH_TAIL_HOST="", COACH_PORT=str(self.port), COACH_TRAFFIC="test",
            COACH_BRIDGE_URL="http://127.0.0.1:1", COACH_SOURCE_LOOKUP="false")
        for name in ("home", "data", "generated", "packs", "logs", "state", "mock/mcq-mainline"):
            (directory / name).mkdir(parents=True, exist_ok=True)
        for name in ("W01.json", "W01-facts.md"):
            shutil.copyfile(HERE / "fixtures" / name, directory / "mock/mcq-mainline" / name)

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
        with (self.directory / "server.log").open("ab") as log:
            self.process = subprocess.Popen([str(BINARY)], cwd=ROOT, env=self.env,
                                            stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            require(self.process.poll() is None, "isolated server exited before readiness")
            try:
                self.request("/v1/hello", expected=401)
                token = (self.directory / "data/device-token").read_text().strip()
                self.admin = ("contract-admin", token)
                return
            except OSError:
                time.sleep(0.1)
        raise ContractFailure("isolated server readiness timed out")

    def stop(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
            self.process = None

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
                                status="unverified", owner="TON-1400",
                                reason="No verified merged reader/annotation adapter in this suite; TON-1412 remains incomplete.")],
                  unverified=["Swift codecs/UI", "real Android UI", "physical iPhone/Tailscale", "candidate PR117/118"])
    server = None
    code = 1
    try:
        # Always rebuild the actual checkout through the repository's resource gate.
        result = subprocess.run([str(ROOT / "tools/dev/heavy"), "./gradlew", ":server:app:installDist",
                                 "--max-workers=2", "--console=plain"], cwd=ROOT)
        report["buildExitCode"] = result.returncode
        require(result.returncode == 0, "installDist failed")
        report["serverArtifacts"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                     for p in sorted((BINARY.parent.parent / "lib").glob("*.jar"))}
        # Short, private path avoids macOS Unix-domain socket path limits.
        with tempfile.TemporaryDirectory(prefix="icc-", dir="/tmp") as temp:
            server = Server(Path(temp))
            try:
                Suite(server, report, args.inject_failure).run()
            finally:
                server.stop()
        code = 2 if args.scope == "full" else 0
        report["status"] = "incomplete" if code else "current-contracts-passed"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error) if isinstance(error, ContractFailure) else type(error).__name__
    except KeyboardInterrupt:
        code = 130
        report["status"] = "interrupted"
    finally:
        if server:
            server.stop()
        report["exitCode"] = code
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"RESULT {report['status']} exit={code}; reader/annotations remain unverified", flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
