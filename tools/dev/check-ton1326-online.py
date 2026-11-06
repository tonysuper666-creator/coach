#!/usr/bin/env python3
"""Real DMG/default-course acceptance, using synthetic data and a private CLI bridge.

Build with reviewed tools/dev/heavy first; see check-ton1326-online.md. This is
foreground work, never a production probe. No credentials or original material
are copied. Screenshots use the packaged application's existing VerifyHooks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import socket
import socketserver
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[2]
FORBIDDEN = {8790, 8791, 18791}
OVERRIDE = "course_132600000002"
CONFIGURED = "course_132600000003"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def private_token(path, token):
    with open(path, "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as out:
        out.write(token)


def input_file(path):
    # Preserve a virtualenv interpreter's final symlink and hence sys.prefix.
    path = path.absolute()
    if not path.is_file():
        raise ValueError("missing file")
    return path


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    if port in FORBIDDEN:
        return free_port()
    return port


def wait_for(check, seconds, label):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.5)
    raise TimeoutError(label)


class Probe(socketserver.ThreadingTCPServer):
    """Transparent HTTP/WS forwarding; record paths/statuses, never headers/body.

    HTTP requests ask the real server to close after its response, so every next
    request is observed too. Upgraded WebSockets are forwarded byte for byte.
    """
    daemon_threads = True

    def __init__(self, target, evidence):
        self.target, self.evidence = target, evidence
        self.phase = "setup"
        self.rows, self.sockets = [], set()
        self.guard = threading.Lock()
        super().__init__(("127.0.0.1", 0), Forward)
        assert self.server_address[1] not in FORBIDDEN

    def record(self, method, path, status):
        row = dict(phase=self.phase, method=method, path=path, status=status)
        with self.guard:
            self.rows.append(row)
            with self.evidence.open("a") as out:
                out.write(json.dumps(row) + "\n")


class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        peer = None
        try:
            self.request.settimeout(10)
            header = bytearray()
            while not header.endswith(b"\r\n\r\n"):
                byte = self.request.recv(1)
                if not byte:
                    return
                header += byte
                if len(header) > 32768:
                    raise ValueError("oversize header")
            lines = bytes(header).split(b"\r\n")
            method, path, _ = lines[0].decode().split(" ", 2)
            websocket = any(line.lower() == b"upgrade: websocket" for line in lines)
            if not websocket:
                lines = [line for line in lines[:-2] if not line.lower().startswith(b"connection:")]
                header = b"\r\n".join(lines + [b"Connection: close", b"", b""])
            peer = socket.create_connection(("127.0.0.1", self.server.target), timeout=10)
            with self.server.guard:
                self.server.sockets.update((peer, self.request))
            peer.sendall(header)
            response = bytearray()
            recorded = False
            while True:
                ready, _, _ = select.select([peer, self.request], [], [], 1)
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    if source is peer:
                        if not recorded:
                            response += data
                            if b"\r\n" in response:
                                status = int(response.split(b"\r\n", 1)[0].split()[1])
                                self.server.record(method, path, status)
                                recorded = True
                        self.request.sendall(data)
                    else:
                        peer.sendall(data)
        except (OSError, ValueError):
            return
        finally:
            with self.server.guard:
                self.server.sockets.discard(peer)
                self.server.sockets.discard(self.request)
            if peer:
                peer.close()


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.evidence = args.evidence_dir.resolve()
        self.evidence.mkdir(mode=0o700)  # refuse overwrite of an earlier run
        self.work = Path(tempfile.mkdtemp(prefix="coach-ton1326-", dir="/private/tmp"))
        os.chmod(self.work, 0o700)
        self.processes, self.mounts, self.handles = [], [], []
        self.secrets = []
        self.result = {"head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
                       "synthetic_only": True, "phases": [], "cleanup": []}
        # Remove inherited service/measurement switches and pay-per-call credentials.
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("COACH_", "BRIDGE_"))
                    and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"}}
        self.env["JAVA_TOOL_OPTIONS"] = f"-Duser.home={self.work / 'home'}"
        for name in ("home", "data", "state", "logs", "empty-packs", "mock", "client-packs"):
            (self.work / name).mkdir()
        self.probe = None

    def run(self, argv, **kwargs):
        return subprocess.run(list(map(str, argv)), check=True, capture_output=True,
                              text=True, timeout=60, **kwargs).stdout

    def start(self, name, argv, env):
        log = (self.work / f"{name}.log").open("w")
        self.handles.append(log)
        child = subprocess.Popen(list(map(str, argv)), env=env, cwd=self.work,
                                 stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        self.processes.append((name, child))
        return child

    def stop(self, name, child):
        # Only this Popen-owned process, never executable-name matching or shared locks.
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=10)
        row = dict(name=name, pid=child.pid, exit=child.returncode)
        if row not in self.result["cleanup"]:
            self.result["cleanup"].append(row)

    def mount(self, image):
        mount = self.work / f"mount-{len(self.mounts)}"
        mount.mkdir()
        self.run(["hdiutil", "attach", image, "-readonly", "-nobrowse", "-mountpoint", mount, "-plist"])
        self.mounts.append(mount)
        apps = list(mount.glob("*.app"))
        assert len(apps) == 1
        cfg = apps[0] / "Contents/app/Coach.cfg"
        course = re.findall(r"^java-options=-Dcoach.course=([A-Za-z0-9_-]+)$", cfg.read_text(), re.M)
        assert len(course) == 1, "DMG must contain exactly one explicit packaged course"
        self.result.setdefault("images", []).append(dict(name=image.name, sha256=digest(image),
                                                         cfg_sha256=digest(cfg), course=course[0]))
        return apps[0] / "Contents/MacOS/Coach", course[0], cfg

    def seed(self, courses):
        payloads = []
        for course, count in courses:
            ops = [dict(op="add", kpId=f"{course}_K{i:02}",
                        title=f"【W1·隔离合成】加法知识点 {i:02}", conceptIds=[f"{course}_C{i:02}"])
                   for i in range(1, count + 1)]
            payloads.append(dict(course=course, payload=dict(ops=ops)))
        fixture = self.work / "seed.json"
        fixture.write_text(json.dumps(payloads, ensure_ascii=False))
        source = self.work / "Seed.java"
        source.write_text('''import java.nio.file.*;
import java.util.*;
import com.coach.store.LedgerStore;
import kotlinx.serialization.json.*;
class Seed {
  public static void main(String[] args) throws Exception {
    try (LedgerStore store = new LedgerStore(Path.of(args[0]), () -> System.currentTimeMillis())) {
      JsonArray rows = (JsonArray) Json.Default.parseToJsonElement(Files.readString(Path.of(args[1])));
      for (JsonElement raw : rows) {
        JsonObject row = (JsonObject) raw;
        String course = ((JsonPrimitive) row.get("course")).getContent();
        store.append(course, "outline_revised", (JsonObject) row.get("payload"),
          UUID.randomUUID().toString(), "seed", "MIGRATION", null, 0,
          System.currentTimeMillis(), null);
      }
    }
  }
}
''')
        self.run(["java", "--class-path", REPO / "server/app/build/install/app/lib/*", source,
                  self.work / "data", fixture], env=self.env)

    def request(self, path, body=None, device=None, token=None):
        headers = {}
        if token:
            headers.update(Authorization=f"Bearer {token}", **{"X-Coach-Device": device})
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.url + path, data=data, headers=headers)
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)

    def phase(self, name, binary, course, token_file, device, override=None, lecture=False):
        self.probe.phase = name
        client = self.work / ("client-configured" if name.startswith("configured") else f"client-{name}")
        client.mkdir(exist_ok=True)
        shots = self.evidence / name
        shots.mkdir()
        captures = ",".join(f"frame-{at:03}@{at}" for at in (range(10, 201, 10) if lecture else [8]))
        env = dict(self.env, COACH_URL=self.url, COACH_DEVICE_ID=device,
                   COACH_TOKEN_FILE=str(token_file), COACH_CLIENT_DIR=str(client),
                   COACH_PACK_DIR=str(self.work / "client-packs"),
                   JAVA_TOOL_OPTIONS=self.env["JAVA_TOOL_OPTIONS"] +
                   f" -Dcoach.captures={captures} -Dcoach.captureDir={shots} -Dcoach.autoExitSeconds={210 if lecture else 12}")
        if override is not None:
            env["COACH_COURSE"] = override
        if lecture:
            env.update(COACH_OPEN_KP=f"{course}_K01", COACH_AUTOSTART="1")
            (self.work / "client-packs" / f"{course}_K01.md").write_text(
                "# 隔离合成教材：整数加法\n将两个计数合并就是加法。例如两个苹果加三个苹果共有五个苹果。"
                "这是TON1326的原创隔离材料。请用中文简短讲解，不调用工具。\n")
        child = self.start(name, [binary], env)
        row = dict(name=name, course=course, env_course=override, pid=child.pid)
        if lecture:
            def completed():
                if child.poll() is not None:
                    raise RuntimeError(f"{name}: desktop exited before a real lecture")
                with sqlite3.connect(self.work / "data/coach.sqlite") as db:
                    messages = db.execute("SELECT message_id,status,content_md,model FROM messages WHERE role='assistant'").fetchall()
                if any(status in ("failed", "cancelled", "truncated") for _, status, _, _ in messages):
                    raise RuntimeError("Real tutor returned a non-complete terminal message; see isolated logs")
                return next((m for m in messages if m[1] == "complete" and m[2]), None)
            wait_for(completed, 195, "Real subscription lecture timed out")
            # Wait for the next application-surface capture after terminal text arrives.
            before = set(shots.glob("*.png"))
            wait_for(lambda: set(shots.glob("*.png")) - before, 12, "Final lecture screenshot absent")
            with sqlite3.connect(self.work / "data/coach.sqlite") as db:
                msg = db.execute("SELECT message_id,status,content_md,model FROM messages WHERE role='assistant' AND status='complete'").fetchall()
                events = db.execute("SELECT seq,type,event_json FROM events WHERE course_id=? ORDER BY seq", (course,)).fetchall()
                calls = db.execute("SELECT task,requested_model,served_by,status,out_chars FROM ai_calls").fetchall()
            assert any(e[1] == "explanation_delivered" for e in events), "No durable explanation event"
            assert any(c[0] == "TEACH" and c[3] == "complete" and c[4] > 0 for c in calls), "No successful real TEACH call"
            row.update(messages=[dict(id=m[0], status=m[1], model=m[3], text=m[2]) for m in msg],
                       ledger=[dict(seq=e[0], type=e[1]) for e in events], ai_calls=calls)
            self.stop(name, child)
        else:
            assert child.wait(timeout=25) == 0, f"{name}: desktop failed"
            self.stop(name, child)
        assert list(shots.glob("*.png")), f"{name}: no packaged-app screenshot"
        if name != "cold-unpaired":
            with sqlite3.connect(client / "ledger.sqlite") as db:
                outlines = db.execute("SELECT course_id,event_json FROM replica WHERE course_id=?", (course,)).fetchall()
                row["replica_seq"] = db.execute("SELECT max(seq) FROM replica WHERE course_id=?", (course,)).fetchone()[0]
            points = [op for _, raw in outlines for op in json.loads(raw).get("payload", {}).get("ops", [])]
            row["kp_count"] = len(points)
            assert len(points) == (49 if course == self.default else 3)
            assert any(r["phase"] == name and r["status"] == 200 and
                       r["path"].startswith(f"/v1/courses/{course}/") for r in self.probe.rows), "No actual desktop course HTTP 200"
            assert any(r["phase"] == name and r["status"] == 101 and r["path"] == "/v1/ws" for r in self.probe.rows)
        else:
            assert any(r["phase"] == name and r["status"] == 401 for r in self.probe.rows)
        self.result["phases"].append(row)
        print(f"{name}: pass course={course} kp={row.get('kp_count', 0)}", flush=True)

    def execute(self):
        binary, self.default, default_cfg = self.mount(self.args.dmg)
        configured_binary, configured, cfg = self.mount(self.args.configured_dmg)
        assert re.fullmatch(r"course_[a-f0-9]{12}", self.default), "Expected production-shaped packaged ID"
        assert configured == CONFIGURED and self.default not in {OVERRIDE, CONFIGURED}
        self.seed([(self.default, 49), (OVERRIDE, 3), (CONFIGURED, 3)])
        bridge_port, server_port = free_port(), free_port()
        assert bridge_port != server_port
        bridge_env = dict(self.env, BRIDGE_PORT=str(bridge_port), COACH_BRIDGE_HOME=str(self.work / "bridge"))
        self.bridge = self.start("bridge", [self.args.bridge_python, REPO / "tools/bridge/subscription_bridge.py"], bridge_env)
        def bridge_ready():
            if self.bridge.poll() is not None:
                raise RuntimeError("Isolated subscription bridge exited")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{bridge_port}/health?scope=process", timeout=1) as response:
                    self.result["bridge_health"] = json.load(response)
                    return response.status == 200
            except (urllib.error.URLError, ConnectionError):
                return False
        wait_for(bridge_ready, 20, "Bridge process not ready")
        admin_token = self.work / "admin-token"
        env = dict(self.env, COACH_DATA_DIR=str(self.work / "data"), COACH_TOKEN_FILE=str(admin_token),
                   COACH_DEVICE_ID="desktop", COACH_PORT=str(server_port), COACH_TAIL_HOST="",
                   COACH_BRIDGE_URL=f"http://127.0.0.1:{bridge_port}", COACH_TRAFFIC="test",
                   COACH_STATE_DIR=str(self.work / "state"), COACH_LOG_DIR=str(self.work / "logs"),
                   COACH_ACTIVITY_SOCKET=str(self.work / "activity.sock"),
                   COACH_PACKS_DIR=str(self.work / "empty-packs"), COACH_MOCK_DIR=str(self.work / "mock"))
        self.server = self.start("server", [REPO / "server/app/build/install/app/bin/app"], env)
        self.probe = Probe(server_port, self.evidence / "http.jsonl")
        self.proxy_thread = threading.Thread(target=self.probe.serve_forever)
        self.proxy_thread.start()
        self.url = f"http://127.0.0.1:{self.probe.server_address[1]}"
        self.result["ports"] = dict(server=server_port, bridge=bridge_port, probe=self.probe.server_address[1])
        def server_ready():
            if self.server.poll() is not None:
                raise RuntimeError("Isolated server exited")
            try:
                self.request("/v1/hello")
            except urllib.error.HTTPError as error:
                return error.code == 401
            except (urllib.error.URLError, ConnectionError):
                return False
            return False
        wait_for(server_ready, 30, "Server not ready")
        self.secrets.append(admin_token.read_text().strip())
        self.phase("cold-unpaired", binary, self.default, self.work / "not-yet-paired", "unpaired")
        self.probe.phase = "pairing"
        status, code = self.request("/v1/pairing-code", {}, "desktop", self.secrets[0])
        assert status == 200
        self.secrets.append(code["code"])
        status, paired = self.request("/v1/pair", dict(code=code["code"], name="TON1326 isolated Mac"))
        assert status == 200
        self.secrets.append(paired["token"])
        token_file = self.work / "paired-token"
        private_token(token_file, paired["token"])
        assert token_file.stat().st_mode & 0o777 == 0o600
        assert paired["token"] != self.secrets[0]
        self.result["pairing"] = dict(status=status, device=paired["deviceId"], token_mode="0600",
                                      token_sha256=digest(token_file), independent_from_admin=True)
        for course, count in [(self.default, 49), (OVERRIDE, 3), (CONFIGURED, 3)]:
            status, snap = self.request(f"/v1/courses/{course}/snapshot", device=paired["deviceId"], token=paired["token"])
            assert status == 200 and len(snap["events"][0]["payload"]["ops"]) == count
        self.phase("default-home", binary, self.default, token_file, paired["deviceId"])
        self.phase("explicit-override", binary, OVERRIDE, token_file, paired["deviceId"], override=OVERRIDE)
        before_cfg = digest(cfg)
        self.phase("configured-new", configured_binary, CONFIGURED, token_file, paired["deviceId"])
        self.phase("configured-reopen", configured_binary, CONFIGURED, token_file, paired["deviceId"])
        assert digest(cfg) == before_cfg
        self.result["persisted_packaged_configuration"] = True
        self.phase("default-lecture", binary, self.default, token_file, paired["deviceId"], lecture=True)
        bridge_log = self.work / "bridge/logs/bridge.log"
        assert bridge_log.exists() and "traffic=test" in bridge_log.read_text(), "Missing actual test-traffic bridge evidence"
        self.result["passed"] = True

    def cleanup(self):
        # The bridge starts CLI children in separate sessions. Remember only our
        # descendants before stopping their parents, and verify their birth time
        # before signalling a still-live PID (including on timeout/failure).
        snapshot = subprocess.check_output(["ps", "-axo", "pid=,ppid=,lstart="], text=True)
        processes = {}
        for line in snapshot.splitlines():
            pid, parent, born = line.strip().split(None, 2)
            processes[int(pid)] = (int(parent), born)
        owned = {child.pid for _, child in self.processes if child.poll() is None}
        descendants = set()
        while True:
            added = {pid for pid, (parent, _) in processes.items()
                     if parent in owned | descendants and pid not in owned | descendants}
            if not added:
                break
            descendants.update(added)
        for name, child in reversed(self.processes):
            self.stop(name, child)
        for pid in descendants:
            born = subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
            if born and born == processes[pid][1]:
                try:
                    os.kill(pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        deadline = time.monotonic() + 5
        remaining = set(descendants)
        while remaining and time.monotonic() < deadline:
            remaining = {pid for pid in remaining if subprocess.run(
                ["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True).stdout.strip() == processes[pid][1]}
            if remaining:
                time.sleep(0.2)
        for pid in remaining:
            born = subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
            if born == processes[pid][1]:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        if remaining:
            time.sleep(0.3)
            remaining = {pid for pid in remaining if subprocess.run(
                ["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True).stdout.strip() == processes[pid][1]}
        self.result["cleanup_descendants_remaining"] = sorted(remaining)
        if remaining:
            self.result["passed"] = False
        if self.probe:
            self.probe.shutdown()
            with self.probe.guard:
                for sock in list(self.probe.sockets):
                    try:
                        sock.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
            self.probe.server_close()
            self.proxy_thread.join(timeout=5)
        for mount in reversed(self.mounts):
            result = subprocess.run(["hdiutil", "detach", str(mount)], capture_output=True, text=True, timeout=30)
            self.result["cleanup"].append(dict(name=mount.name, exit=result.returncode))
            if result.returncode:
                self.result["passed"] = False
        for handle in self.handles:
            handle.close()
        # Export only logs, with all known run-owned credentials redacted.
        for path in [*self.work.glob("*.log"), self.work / "bridge/logs/bridge.log"]:
            if path.exists():
                text = path.read_text(errors="replace")
                for secret in self.secrets:
                    text = text.replace(secret, "[REDACTED]")
                name = "bridge-calls.log" if path.parent.name == "logs" else path.name
                (self.evidence / name).write_text(text)
        (self.evidence / "result.json").write_text(json.dumps(self.result, ensure_ascii=False, indent=2) + "\n")
        if all(item["exit"] == 0 for item in self.result["cleanup"] if item["name"].startswith("mount-")):
            shutil.rmtree(self.work)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dmg", required=True, type=Path)
    parser.add_argument("--configured-dmg", required=True, type=Path)
    parser.add_argument("--bridge-python", required=True, type=Path)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    args = parser.parse_args()
    for key in ("dmg", "configured_dmg", "bridge_python"):
        try:
            setattr(args, key, input_file(getattr(args, key)))
        except ValueError:
            parser.error(f"{key}: missing file")
    acceptance = Acceptance(args)
    try:
        acceptance.execute()
    except Exception as error:
        acceptance.result.update(passed=False, failure=f"{type(error).__name__}: {error}")
        raise
    finally:
        acceptance.cleanup()
    if not acceptance.result.get("passed"):
        raise SystemExit(1)
    print("PASS: real isolated DMG/HTTP/WS/subscription lecture; processes collected", flush=True)


if __name__ == "__main__":
    main()
