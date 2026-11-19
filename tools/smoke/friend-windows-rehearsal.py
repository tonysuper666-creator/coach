#!/usr/bin/env python3
"""Real managed JVM/bridge listeners with an offline provider and synthetic data.

Run with the bridge venv after :server:app:installDist. --work must not exist.
No Keychain, provider network, real materials, GUI or production state is used.
Private diagnostics stay in --work; stdout contains counts and booleans only.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[2]


def load(path):
    spec = importlib.util.spec_from_file_location(path.stem.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require(condition):
    if not condition:
        raise ValueError("rehearsal rejected")


def install_provider_fixture():
    """Called by private sitecustomize ONLY in the selected wrapper process."""
    sys.path.insert(0, str(REPO / "tools/bridge"))
    import httpx
    import subscription_bridge as bridge
    from api_route import ApiRouter

    def provider(request):
        require(request.url.host == "api.anthropic.com")
        require(json.loads(request.content)["model"] == "claude-haiku-5-5")
        counter = Path(os.environ["FRIEND_FIXTURE_COUNTER"])
        counter.write_text(str(int(counter.read_text()) + 1))
        text = json.dumps({"paragraphs": [{"text": "这是用于验收的自写样例说明。", "anchors": ["S1"]}]}, ensure_ascii=False)
        events = [{"type": "message_start", "message": {"model": "claude-haiku-5-5", "usage": {"input_tokens": 100}}},
                  {"type": "content_block_delta", "delta": {"type": "text_delta", "text": text}},
                  {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 20}},
                  {"type": "message_stop"}]
        return httpx.Response(200, content="".join("data: " + json.dumps(e) + "\n\n" for e in events).encode())

    bridge.API_ROUTER = ApiRouter(client_factory=lambda: httpx.AsyncClient(transport=httpx.MockTransport(provider)),
                                  keys={"anthropic": "offline-fixture-only"})

    async def forbidden(*args, **kwargs):
        raise ValueError("CLI forbidden in offline friend rehearsal")
    bridge.dispatch_cli = forbidden


def unused_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        value = sock.getsockname()[1]
    require(value not in {18791, 8790, 8791, 3790, 19540})
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--node", required=True, type=Path)
    parser.add_argument("--math-dependencies", required=True, type=Path)
    args = parser.parse_args()
    root = args.work.resolve()
    root.mkdir(mode=0o700)  # Never adopt an existing HOME, ledger or service.
    private, public, bridge_port = unused_port(), unused_port(), unused_port()
    require(len({private, public, bridge_port}) == 3)
    prepare = load(Path(__file__).with_name("friend-prepare-windows.py"))
    stage, child = "setup", None
    result = 1
    try:
        for name in ("home", "state", "shim", "hooks", "service/tools/mathsvc"):
            (root / name).mkdir(parents=True, exist_ok=True, mode=0o700)
        tools = root / "service/tools"
        for name in ("bridge", "smoke", "dev"):
            (tools / name).symlink_to(REPO / "tools" / name, target_is_directory=True)
        (tools / "mathsvc/src").symlink_to(REPO / "tools/mathsvc/src", target_is_directory=True)
        (tools / "mathsvc/node_modules").symlink_to(args.math_dependencies.resolve(), target_is_directory=True)
        # Fixture key is emitted by a private shim; never read the user's key.
        shim = root / "shim/security"
        shim.write_text("#!/bin/sh\nprintf '%s' 'offline-fixture-only'\n")
        shim.chmod(0o700)
        hook = "import runpy, sys\nif sys.argv[0].endswith('/friend-service-bridge.py'):\n"
        hook += "    runpy.run_path(" + repr(str(Path(__file__).resolve())) + ")[\"install_provider_fixture\"]()\n"
        (root / "hooks/sitecustomize.py").write_text(hook)
        counter = root / "provider-count"
        counter.write_text("0")
        friend_b = hashlib.sha256(prepare.B.encode()).hexdigest()
        config = root / "friends.json"
        config.write_text(json.dumps({"paused": False}))
        config.chmod(0o600)
        month = datetime.now(timezone.utc).strftime("%Y-%m")
        cycle_id = "anthropic:" + month
        ledger_file = root / "state/api-credit-ledger.json"
        now = int(time.time())
        (ledger_file.parent / "grant-allocation-anthropic.json").write_text(json.dumps({
            "version": 1, "provider": "anthropic", "grant_microusd": 2_000_000,
            "start_utc": now - 1, "end_utc": now + 86400, "other_writers_microusd": 0,
            "ledgers": [{"host": socket.gethostname(), "state": str(ledger_file.parent),
                         "limit_microusd": 1_000_000, "prior_settled_microusd": 0}]}))
        ledger_file.write_text(json.dumps({"version": 1,
            "cycles": {cycle_id: {"spent": 17, "limit": 1_000_000, "reserved": {"owner-pending": 3}}},
            "months": {month: {"anthropic": {"spent_microusd": 17, "reserved_microusd": 3, "calls": 1}}}}))
        env = {k: v for k, v in os.environ.items() if not k.startswith(("COACH_", "BRIDGE_", "ANTHROPIC_", "OPENAI_"))
               and k not in {"JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "PYTHONPATH"}}
        env.update(HOME=str(root / "home"), PATH=str(root / "shim") + os.pathsep + os.environ["PATH"],
            JAVA_OPTS=f"-Duser.home={root / 'home'}", PYTHONPATH=str(root / "hooks"),
            COACH_SERVICE_ROOT=str(root / "service"), COACH_PYTHON=sys.executable, COACH_NODE=str(args.node.resolve()),
            COACH_DATA_DIR=str(root / "data"), COACH_LOG_DIR=str(root / "logs"), COACH_STATE_DIR=str(root / "state"),
            COACH_RUN_DIR=str(root / "run"), COACH_API_STATE_DIR=str(root / "state"),
            COACH_FRIEND_CONFIG=str(config), COACH_BRIDGE_HOME=str(root / "bridge-home"),
            COACH_PORT=str(private), COACH_PUBLIC_PORT=str(public), BRIDGE_PORT=str(bridge_port),
            COACH_BRIDGE_URL=f"http://127.0.0.1:{bridge_port}", COACH_TRAFFIC="test", COACH_FRIEND_API_ONLY="1",
            COACH_MANAGE_SERVICES="1", COACH_TAIL_HOST="", FRIEND_FIXTURE_COUNTER=str(counter))
        stage = "managed-start"
        with (root / "jvm.log").open("w") as log:
            child = subprocess.Popen([str(REPO / "server/app/build/install/app/bin/app")], env=env, stdout=log, stderr=log)
        private_client = prepare.protocol.Client(f"http://127.0.0.1:{private}")
        public_client = prepare.protocol.Client(f"http://127.0.0.1:{public}")
        deadline = time.monotonic() + 40
        while True:
            require(child.poll() is None and time.monotonic() < deadline)
            try:
                state = json.loads((root / "run/bridge.json").read_text())
                require(state["healthy"] and state["ownerPid"] == child.pid)
                require(public_client.request("/v1/public/status")[0] == 401)
                break
            except (OSError, ValueError):
                time.sleep(.1)
        stage = "bridge-boundary"
        base_body = {"model": "claude-sonnet-5-5", "messages": [{"role": "user", "content": "synthetic fixture"}]}
        base_headers = {"Content-Type": "application/json", "X-Coach-Traffic": "test", "X-Coach-Task": "READER_SELECTION",
                        "X-Coach-Friend": friend_b, "X-Coach-Friend-Device": "fixture-device"}
        cases = [(base_body, base_headers | {"X-Coach-Traffic": "user"}, 403),
                 (base_body, {k: v for k, v in base_headers.items() if k != "X-Coach-Friend"}, 400),
                 (base_body, base_headers | {"X-Coach-Task": "QUESTION"}, 400),
                 (base_body | {"model": "gpt-6.1-sol"}, base_headers, 400),
                 (base_body | {"tools": [{"type": "function"}]}, base_headers, 400),
                 (base_body | {"response_format": {"type": "json_object"}}, base_headers, 400)]
        before = ledger_file.read_bytes()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), prepare.protocol.NoRedirect())
        for body, headers, expected in cases:
            request = urllib.request.Request(f"http://127.0.0.1:{bridge_port}/v1/chat/completions",
                                             data=json.dumps(body).encode(), headers=headers)
            try:
                with opener.open(request, timeout=10) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
                error.close()
            require(status == expected)
        require(counter.read_text() == "0" and ledger_file.read_bytes() == before)
        stage = "prepare-b-then-a"
        owner = {"deviceId": "desktop", "token": (root / "data/device-token").read_text().strip()}
        prepared = prepare.prepare(private_client, public_client, owner)
        status, body = public_client.request("/v1/pair", "POST", body={"code": prepared["codeA"], "name": "Synthetic Windows stand-in"})
        require(status == 200)
        a, b = json.loads(body), prepared["credentialB"]
        require(public_client.request("/v1/public/status", auth=b)[0] == 200)
        stage = "explain-and-usage"
        book = hashlib.sha256(b"Self-authored acceptance book").hexdigest()
        text = "A synthetic orbit is a path around a synthetic star."
        payload = {"requestId": "ab12", "kind": "explain", "selection": text, "blocks": [{"id": book + ":p1:0", "text": text}],
            "localSource": {"schema": 1, "book": book, "format": "txt", "title": "Self-authored sample", "consented": True,
                "pages": [{"number": 1, "length": len(text), "ocr": False, "sha256": hashlib.sha256(text.encode()).hexdigest()}]}}
        for identity in (a, b, b):
            status, body = public_client.request("/v1/library/explain", "POST", identity, payload)
            require(status == 200 and "自写样例说明" in json.loads(body)["text"])
        stage = "revoke-a"
        require(private_client.request("/v1/friends/revoke", "POST", owner, {"friend": prepare.A})[0] == 204)
        require(public_client.request("/v1/public/status", auth=a)[0] == 401)
        require(public_client.request("/v1/public/status", auth=b)[0] == 200)
        ledger = json.loads(ledger_file.read_text())
        daily = next(iter(ledger["friends"][friend_b].values()))
        require((daily["calls"], daily["input_tokens"], daily["output_tokens"], len(daily["reserved"])) == (2, 200, 40, 0))
        require(daily["spent_microusd"] > 0 and daily["devices"][b["deviceId"]]["spent_microusd"] == daily["spent_microusd"])
        require(daily["devices"][b["deviceId"]]["calls"] == 2 and counter.read_text() == "3")
        require(ledger["cycles"][cycle_id]["reserved"] == {"owner-pending": 3})
        require(ledger["cycles"][cycle_id]["limit"] == 1_000_000 and ledger["cycles"][cycle_id]["spent"] > 17)
        require(ledger["months"][month]["anthropic"]["calls"] == 4)
        result = 0
    except Exception:
        (root / "failure.log").write_text(traceback.format_exc())
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=20)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=10)
        # Guard subprocesses must release all three listeners before success.
        deadline = time.monotonic() + 12
        while True:
            occupied = []
            for value in (private, public, bridge_port):
                with socket.socket() as sock:
                    sock.settimeout(.2)
                    occupied.append(sock.connect_ex(("127.0.0.1", value)) == 0)
            if not any(occupied):
                break
            if time.monotonic() >= deadline:
                result, stage = 1, "cleanup"
                break
            time.sleep(.1)
    if result == 0:
        print("FRIEND_WINDOWS_REHEARSAL PASS managedWrapper=1 boundaryDenials=6 providerNetwork=0 keychainReads=0")
        print("bPrepairedOnMac=1 aPair=200 bStatus=200 aRevoked=401 bPreserved=200 bFirst=200 bSecond=200")
        print("bCalls=2 input=200 output=40 costRecorded=1 pending=0 ownerPendingPreserved=1 ownerCapUnchanged=1 listenersReleased=3")
    else:
        print("FRIEND_WINDOWS_REHEARSAL FAIL stage=" + stage)
    return result


if __name__ == "__main__":
    sys.exit(main())
