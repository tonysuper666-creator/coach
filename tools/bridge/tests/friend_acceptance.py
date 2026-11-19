#!/usr/bin/env python3
"""One-command isolated Mac acceptance; real Coach/bridge, offline provider SSE, no billable traffic.

Run with the bridge venv: python tools/bridge/tests/friend_acceptance.py
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[3]
BRIDGE = REPO / "tools/bridge"
FORBIDDEN = {18791, 8790, 8791, 3790, 19540}


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        value = sock.getsockname()[1]
    assert value not in FORBIDDEN
    return value


def bridge_child(port):
    sys.path.insert(0, str(BRIDGE))
    import httpx
    import uvicorn
    import subscription_bridge as bridge
    from api_route import ApiRouter
    def provider(request):
        assert request.url.host == "api.anthropic.com"
        assert json.loads(request.content)["model"] == "claude-haiku-5-5"
        text = json.dumps({"paragraphs": [{"text": "这是用于验收的自写样例说明。", "anchors": ["S1"]}]}, ensure_ascii=False)
        events = [{"type": "message_start", "message": {"model": "claude-haiku-5-5", "usage": {"input_tokens": 100}}},
            {"type": "content_block_delta", "delta": {"type": "text_delta", "text": text}},
            {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 20}},
            {"type": "message_stop"}]
        return httpx.Response(200, content="".join("data: " + json.dumps(e) + "\n\n" for e in events).encode())
    bridge.API_ROUTER = ApiRouter(client_factory=lambda: httpx.AsyncClient(transport=httpx.MockTransport(provider)),
                                  keys={"anthropic": "synthetic-acceptance-only"})
    async def forbidden(*args, **kwargs):
        raise AssertionError("Subscription CLI is forbidden in friend acceptance")
    bridge.dispatch_cli = forbidden
    uvicorn.run(bridge.app, host="127.0.0.1", port=port, log_level="warning", access_log=False)


def main():
    subprocess.run([str(REPO / "tools/dev/heavy"), "./gradlew", ":server:app:installDist", "--console=plain", "-q"], cwd=REPO, check=True)
    app = REPO / "server/app/build/install/app/bin/app"
    private, public, bridge_port = port(), port(), port()
    assert len({private, public, bridge_port}) == 3
    with tempfile.TemporaryDirectory(prefix="coach-friend-acceptance-") as temp:
        root = Path(temp).resolve()
        env = {k: v for k, v in os.environ.items() if not k.startswith("COACH_")}
        env.update(COACH_DATA_DIR=str(root / "data"), COACH_LOG_DIR=str(root / "logs"), COACH_STATE_DIR=str(root / "state"),
            COACH_API_STATE_DIR=str(root / "api-state"), COACH_FRIEND_CONFIG=str(root / "friends.json"),
            COACH_BRIDGE_HOME=str(root / "bridge"), BRIDGE_PORT=str(bridge_port), BRIDGE_USER_ONLY="0",
            COACH_BRIDGE_URL=f"http://127.0.0.1:{bridge_port}", COACH_TRAFFIC="test", COACH_PORT=str(private),
            COACH_PUBLIC_PORT=str(public), COACH_TAIL_HOST="", JAVA_OPTS=f"-Duser.home={root / 'home'}")
        processes = []
        log = (root / "acceptance.log").open("w")
        try:
            processes.append(subprocess.Popen([sys.executable, __file__, "--bridge", str(bridge_port)], env=env, stdout=log, stderr=log))
            processes.append(subprocess.Popen([str(app)], env=env, stdout=log, stderr=log))
            def request(path, method="GET", credentials=None, body=None, internal=False):
                data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
                req = urllib.request.Request(f"http://127.0.0.1:{private if internal else public}" + path, data=data, method=method)
                if credentials:
                    req.add_header("X-Coach-Device", credentials["deviceId"])
                    req.add_header("Authorization", "Bearer " + credentials["token"])
                try:
                    with urllib.request.urlopen(req, timeout=20) as response:
                        return response.status, response.read().decode()
                except urllib.error.HTTPError as error:
                    return error.code, error.read().decode()
            deadline = time.monotonic() + 25
            while True:
                try:
                    assert request("/v1/public/status")[0] == 401
                    with socket.create_connection(("127.0.0.1", bridge_port), timeout=1):
                        pass
                    break
                except (OSError, AssertionError):
                    assert all(p.poll() is None for p in processes), "Synthetic child failed to start"
                    assert time.monotonic() < deadline, "Synthetic readiness deadline"
                    time.sleep(.1)
            admin = {"deviceId": "desktop", "token": (root / "data/device-token").read_text().strip()}
            def command(*args):
                return subprocess.check_output([str(app), *args], env=env, text=True)
            def pair(name):
                code = re.search(r"\b(\d{6})\b", command("pair-code", "--friend", name)).group(1)
                status, body = request("/v1/pair", "POST", body={"code": code, "name": name + " PC"})
                assert status == 200
                result = json.loads(body)
                assert result["role"] == "friend"
                return result
            a, b = pair("Synthetic A"), pair("Synthetic B")
            book = hashlib.sha256(b"Self-authored acceptance book").hexdigest()
            text = "A synthetic orbit is a path around a synthetic star."
            payload = {"requestId": "ab12", "kind": "explain", "selection": text,
                "blocks": [{"id": book + ":p1:0", "text": text}],
                "localSource": {"schema": 1, "book": book, "format": "txt", "title": "Self-authored sample", "consented": True,
                    "pages": [{"number": 1, "length": len(text), "ocr": False, "sha256": hashlib.sha256(text.encode()).hexdigest()}]}}
            status, body = request("/v1/library/explain", "POST", a, payload)
            assert status == 200 and "自写样例说明" in json.loads(body)["text"], "Synthetic explanation did not pass real source/response validation"
            denied = ["/v1/ws", "/v1/hello", "/v1/commands", "/v1/devices", "/v1/pairing-code", "/v1/usage",
                "/v1/courses/x/snapshot", "/v1/courses/x/today", "/v1/courses/x/sources", "/v1/courses/x/pool",
                "/v1/courses/x/essays", "/v1/prewarm", "/v1/reader", "/v1/library", "/v1/health", "/health",
                "/v1/%64evices", "/v1/%70airing-code", "/v1/library/explain/", "/v1/public/status/../devices"]
            for path in denied:
                assert request(path, credentials=a) == (404, ""), path
            command("revoke-friend", "Synthetic A")
            assert request("/v1/public/status", credentials=a)[0] == 401
            assert request("/v1/public/status", credentials=b)[0] == 200
            b_id = hashlib.sha256(b"Synthetic B").hexdigest()
            for _ in range(2):
                status, body = request("/v1/library/explain", "POST", b, payload)
                assert status == 200 and "自写样例说明" in json.loads(body)["text"]
            status, body = request("/v1/public/status", credentials=b)
            usage = json.loads(body)
            assert status == 200 and usage["calls"] == 2 and not {"callsRemaining", "tokensRemaining"} & usage.keys()
            (root / "friends.json").write_text(json.dumps({"friends": {b_id: {"daily_calls": 2}}}))
            status, body = request("/v1/library/explain", "POST", b, payload)
            assert status == 429 and json.loads(body) == {"error": "讲解服务暂时连不上，读书照常。", "code": "daily_quota"}
            ledger = json.loads((root / "api-state/api-credit-ledger.json").read_text())
            daily = next(iter(ledger["friends"][b_id].values()))
            assert (daily["calls"], daily["input_tokens"], daily["output_tokens"], len(daily["reserved"])) == (2, 200, 40, 0)
            assert daily["spent_microusd"] > 0 and daily["devices"][b["deviceId"]]["spent_microusd"] == daily["spent_microusd"]
            assert daily["devices"][b["deviceId"]]["calls"] == 2
            status, body = request("/v1/public/status", credentials=b)
            assert status == 200 and json.loads(body)["callsRemaining"] == 0
            devices = json.loads(command("devices"))
            assert any(d["deviceId"] == b["deviceId"] and d["today"]["calls"] == 2 for d in devices)
            # The owner's SQL call ledger remains empty.
            db = next((root / "data").glob("*.sqlite"))
            with sqlite3.connect(db) as connection:
                assert connection.execute("SELECT COUNT(*) FROM ai_calls").fetchone()[0] == 0
            print("FRIEND_ACCEPTANCE PASS: real isolated listeners + actual bridge + offline Anthropic SSE")
            print("pair A/B=friend; own sample explanation=200; 20 forbidden paths=empty404; revoke A=401/B=200")
            print("B default=2 successful calls with no remainder; explicit limit=429; ledger calls=2 input=200 output=40 cost recorded; owner AI calls=0")
            print("production=untouched; provider network=0; subscription CLI=0")
        finally:
            # Every process is a directly owned child; never terminate by executable name.
            protected = None
            if os.environ.get("MULTICA_CLI_PATH"):
                result = subprocess.run([os.environ["MULTICA_CLI_PATH"], "daemon", "status", "--output", "json"], capture_output=True, text=True)
                if result.returncode == 0:
                    protected = json.loads(result.stdout).get("pid")
            for child in reversed(processes):
                assert child.pid != protected
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
            log.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--bridge", type=int)
    args = parser.parse_args()
    bridge_child(args.bridge) if args.bridge else main()
