#!/usr/bin/env python3
"""Private, daemon-supervised friend-path service. Never opens the production SQLite database."""
import argparse
import fcntl
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

REPO = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = Path.home() / "Coach/state/ton1649-friend-service"
FORBIDDEN_PORTS = {18791, 8790, 8791}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def private_json(path, value):
    pending = path.with_name(path.name + ".pending")
    fd = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.write("\n")
    os.replace(pending, path)


def isolated_root(path):
    root = path.expanduser().resolve()
    state = (Path.home() / "Coach/state").resolve()
    require(root.is_relative_to(state) and root != state, "root must be a new directory under ~/Coach/state")
    return root


def ports(port):
    require(1024 <= port <= 65526 and not ({port, port + 1} & FORBIDDEN_PORTS), "reserved/invalid port")
    return port, port + 1


def request(url, body=None, credential=None):
    headers = {"Content-Type": "application/json"}
    if credential:
        headers.update(Authorization="Bearer " + credential["token"], **{"X-Coach-Device": credential["deviceId"]})
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers)
    # Never follow redirects with a device credential or send tailnet traffic to a proxy.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(req, timeout=180) as res:
            return res.status, json.loads(res.read())
    except urllib.error.HTTPError as error:
        return error.code, None


def prepare(args):
    root = isolated_root(args.root)
    require(not root.exists(), "root already exists; stop/archive it before preparing another snapshot")
    source = args.source_data.expanduser().resolve()
    library = args.source_library.expanduser().resolve()
    require(source != (Path.home() / "Coach/data").resolve(), "use an authorized isolated ledger snapshot")
    require(not source.is_relative_to(root) and not library.is_relative_to(root), "input inside output root")
    docs = json.loads((library / "catalog.json").read_text())["documents"]
    matches = [d for d in docs if d["id"] == args.document]
    require(len(matches) == 1, "document must identify one existing catalog registration")
    doc = matches[0]
    pdf = (library / doc["file"]).resolve()
    require(pdf.is_relative_to(library) and pdf.is_file(), "PDF outside source library")
    require(hashlib.sha256(pdf.read_bytes()).hexdigest() == doc["sha256"], "PDF hash mismatch")
    binding = doc.get("explainSourceId")
    require(binding, "reuse requires an explicit existing explanation binding")
    with sqlite3.connect(f"{(source / 'coach.sqlite').as_uri()}?mode=ro", uri=True) as db:
        row = db.execute("select course_id,tutor_visible,learner_visible from source_docs where doc_id=?", (binding,)).fetchone()
        require(row == (doc["course"], 1, 1), "binding must match an existing visible course source")
        root.mkdir(mode=0o700)
        for name in ["data", "logs", "state", "run", "packs", "mock", "generated", "bridge", "api"]:
            (root / name).mkdir(mode=0o700)
        with sqlite3.connect(root / "data/coach.sqlite") as dest:
            db.backup(dest)
            # Credentials are instance-local. Learning, source and admission records stay intact.
            dest.execute("delete from devices")
            dest.execute("delete from pairing_code")
            if dest.execute("select 1 from sqlite_master where type='table' and name='friend_invites'").fetchone():
                dest.execute("delete from friend_invites")
        if (source / "blobs").exists():
            shutil.copytree(source / "blobs", root / "data/blobs")
    spec = importlib.util.spec_from_file_location("pdf_import", REPO / "tools/reader/import_pdf.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    imported = module.import_pdf(pdf, root / "library", document_id=doc["id"], course=doc["course"],
        title=doc["title"], kind=doc["kind"], offset=doc.get("printedPageOffset"),
        week=doc.get("week"), explain_source_id=binding)
    require(imported["sha256"] == doc["sha256"] and imported["pages"] == doc["pages"], "registration changed")
    runtime = args.runtime.expanduser().resolve()
    require((runtime / "bin/app").is_file(), "runtime must be an installDist directory")
    require(args.bridge_python.expanduser().is_file(), "bridge Python missing; install tools/bridge/requirements.txt first")
    host = str(ipaddress.ip_address(args.tail_host))
    require(ipaddress.ip_address(host) in ipaddress.ip_network("100.64.0.0/10"), "tail host must be a Tailscale IPv4 address")
    shutil.copytree(runtime, root / "runtime")
    config = dict(schema=1, root=str(root), sourceData=str(source), sourceLibrary=str(library),
        runtimeRevision=args.runtime_revision, bridgePython=str(args.bridge_python.expanduser().absolute()),
        javaHome=str(args.java_home.expanduser().resolve()), tailHost=host, document=imported,
        snapshotSha256=hashlib.sha256((root / "data/coach.sqlite").read_bytes()).hexdigest())
    private_json(root / "config.json", config)
    for current, dirs, files in os.walk(root):
        Path(current).chmod(0o700)
        for name in files:
            p = Path(current) / name
            if not p.is_symlink():
                p.chmod(0o700 if os.access(p, os.X_OK) else 0o600)
    print("Prepared private snapshot; source/learning/admission records preserved; devices reset.")


def load(root):
    root = isolated_root(root)
    config = json.loads((root / "config.json").read_text())
    require(config["root"] == str(root), "config root mismatch")
    require((root / "runtime/bin/app").is_file(), "isolated runtime missing")
    return root, config


def clean_environment():
    # No inherited production paths, service manager, API keys or Multica credentials.
    return {k: v for k, v in os.environ.items()
            if not k.startswith(("COACH_", "BRIDGE_", "MULTICA_", "ANTHROPIC_", "OPENAI_"))
            and k not in {"CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CODEX_THREAD_ID"}}


def friend_api_environment(root, config):
    if not config.get("friendApi", False):
        return {}
    require(config["friendApi"] is True, "invalid friend API mode")
    # Sharing the owner's existing credit ledger is intentional: a new empty
    # directory would reset the monthly cap. Book/SQLite/bridge state stay private.
    state = Path(config["apiStateDir"])
    require(state.is_absolute() and str(state.resolve()) == str(state) and
            state == (Path.home() / "Coach/state").resolve() and
            (state / "api-credit-ledger.json").is_file(), "existing owner credit ledger required")
    policy = root / "friends.json"
    metadata = policy.lstat()
    require(policy.is_file() and not policy.is_symlink() and metadata.st_uid == os.getuid() and
            metadata.st_mode & 0o777 == 0o600, "private friend policy required")
    settings = json.loads(policy.read_text())
    require(type(settings.get("paused")) is bool and isinstance(settings.get("defaults"), dict) and
            all(type(settings["defaults"].get(k)) is int and settings["defaults"][k] >= 0
                for k in ("daily_calls", "daily_tokens")), "explicit friend limits required")
    return {"COACH_API_STATE_DIR": str(state), "COACH_FRIEND_CONFIG": str(policy),
            "COACH_FRIEND_API_ONLY": "1"}


def serve(args):
    root, config = load(args.root)
    # Same private snapshot must never run in two environments/port blocks.
    instance_lock = (root / "instance.lock").open("a")
    fcntl.flock(instance_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    server_port, bridge_port = ports(args.port)
    # Bind checks fail before launching either child; actual bind still fails closed on races.
    for host, port in [("127.0.0.1", server_port), (config["tailHost"], server_port), ("127.0.0.1", bridge_port)]:
        with socket.socket() as sock:
            sock.bind((host, port))
    env = clean_environment()
    env.update(COACH_BRIDGE_HOME=str(root / "bridge"), COACH_API_STATE_DIR=str(root / "api"),
        BRIDGE_PORT=str(bridge_port), BRIDGE_BACKGROUND_BACKEND="", BRIDGE_AGY_ENABLED="0")
    api_env = friend_api_environment(root, config)
    env.update(api_env)
    server_env = env | {"JAVA_HOME": config["javaHome"], "COACH_PORT": str(server_port),
        "COACH_TAIL_HOST": config["tailHost"], "COACH_TRAFFIC": "test", "COACH_DEVICE_ID": "friend-service-admin",
        "COACH_TOKEN_FILE": str(root / "data/admin-token"), "COACH_DATA_DIR": str(root / "data"),
        "COACH_READER_LIBRARY": str(root / "library"), "COACH_BRIDGE_URL": f"http://127.0.0.1:{bridge_port}",
        "COACH_READER_MODEL": "gpt-6.1-sol"}
    if api_env:
        server_env["COACH_READER_MODEL"] = "claude-sonnet-5-5"
    for key, directory in [("LOG", "logs"), ("STATE", "state"), ("RUN", "run"), ("PACKS", "packs"), ("MOCK", "mock"), ("GENERATED", "generated")]:
        server_env[f"COACH_{key}_DIR"] = str(root / directory)
    children, logs = [], []
    def stopped(signum, frame):
        raise SystemExit(0)
    signal.signal(signal.SIGTERM, stopped)
    signal.signal(signal.SIGINT, stopped)
    try:
        for name, argv, child_env in [
            ("bridge", [config["bridgePython"], str(REPO / "tools/smoke/friend-service-bridge.py")], env),
            ("server", [str(root / "runtime/bin/app")], server_env)]:
            log = (root / f"logs/{name}.private.log").open("ab")
            logs.append(log)
            children.append(subprocess.Popen(argv, env=child_env, stdout=log, stderr=log, cwd=REPO))
        private_json(root / "running.json", dict(pid=os.getpid(), childPids=[p.pid for p in children],
            port=server_port, bridgePort=bridge_port, url=f"http://{config['tailHost']}:{server_port}", startedAt=int(time.time())))
        print(f"Isolated service starting on {server_port}; bridge on loopback {bridge_port}; friend API mode {bool(api_env)}", flush=True)
        while True:
            for child in children:
                require(child.poll() is None, "service child exited; inspect private logs")
            time.sleep(0.5)
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
        for child in children:
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        for log in logs:
            log.close()
        (root / "running.json").unlink(missing_ok=True)


def pair(args):
    root, _ = load(args.root)
    running = json.loads((root / "running.json").read_text())
    port, _ = ports(running["port"])
    admin = dict(deviceId="friend-service-admin", token=(root / "data/admin-token").read_text().strip())
    body = {}
    if args.friend_name_file:
        name = args.friend_name_file.read_text().strip()
        require(name and len(name) <= 80 and not any(ord(c) < 32 or ord(c) == 127 for c in name), "invalid private friend name")
        require(configured_friend_api(args.root), "friend API configuration required")
        body["friend"] = name
    status, code = request(f"http://127.0.0.1:{port}/v1/pairing-code", body, admin)
    require(status == 200, "pair-code request failed")
    handoff = dict(url=running["url"], role="friend" if body else "owner", **code)
    private_json(root / "pairing.json", handoff)
    if args.winbox:
        # Secrets go through stdin, never SSH arguments, shell history or public output.
        uploaded = subprocess.run(["scp", "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
            str(REPO / "tools/smoke/friend-service-winbox.ps1"),
            "winbox:D:/agent/logs/friend-service-winbox.ps1"], capture_output=True, timeout=30)
        require(uploaded.returncode == 0, "Windows helper upload failed")
        completed = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "winbox",
            "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File D:/agent/logs/friend-service-winbox.ps1"],
            input=json.dumps(handoff), text=True, capture_output=True, timeout=60)
        require(completed.returncode == 0, "Windows pairing failed; no credential printed")
        receipt = json.loads(completed.stdout)
        require(receipt.get("passed") is True, "Windows pairing did not pass")
        private_json(root / "winbox-result.json", receipt)
        print(json.dumps({k: v for k, v in receipt.items() if k not in {"url", "clientDir"}}))
    else:
        print("New single-use code written to private output; expires in five minutes. No credential printed.")


def configured_friend_api(root):
    root, config = load(root)
    return bool(friend_api_environment(root, config))


def probe(args):
    root, config = load(args.root)
    running = json.loads((root / "running.json").read_text())
    port, bridge_port = ports(running["port"])
    base = f"http://127.0.0.1:{port}"
    admin = dict(deviceId="friend-service-admin", token=(root / "data/admin-token").read_text().strip())
    selection = json.loads(args.selection_file.expanduser().read_text())
    block = selection["blocks"][0]
    match = re.fullmatch(r"([a-f0-9]{64}):p([0-9]+):[0-9]+", block["id"])
    require(match and match[1] == config["document"]["sha256"], "selection must belong to registered PDF hash")
    page = int(match[2])
    def normalized(text):
        return " ".join(text.split())
    doc = config["document"]
    text = subprocess.check_output(["pdftotext", "-f", str(page), "-l", str(page),
        "-enc", "UTF-8", str(root / "library" / doc["file"]), "-"], text=True)
    with sqlite3.connect(f"{(root / 'data/coach.sqlite').as_uri()}?mode=ro", uri=True) as db:
        saved = db.execute("select text from source_pages where doc_id=? and pdf_page=?",
                           (doc["explainSourceId"], page)).fetchone()
    require(saved and normalized(selection["selection"]) in normalized(saved[0])
        and normalized(selection["selection"]) in normalized(text), "selection must match both real PDF page and stored source")
    result = dict(pdfPages=doc["pages"], hashMatches=True, selectedPdfPage=page,
        selectedPrintedPage=page - doc.get("printedPageOffset", 0), pdfAndLedgerQuoteMatch=True,
        runtimeRevision=config["runtimeRevision"], kind=args.kind, cases=[])
    for name, url, credential, body, expected in [
        ("unauthenticated", base + "/v1/hello", None, None, 401),
        ("authenticated", base + "/v1/hello", admin, None, 200),
        ("isolated-bridge", f"http://127.0.0.1:{bridge_port}/health?scope=process", None, None, 200),
        ("unregistered-hash", base + "/v1/library/explain", admin,
            selection | {"requestId": uuid.uuid4().hex, "kind": "explain", "blocks": [block | {"id": "0" * 64 + f":p{page}:0"}]}, 409),
        ("wrong-page", base + "/v1/library/explain", admin,
            selection | {"requestId": uuid.uuid4().hex, "kind": "explain", "blocks": [block | {"id": doc["sha256"] + ":p2:0"}]}, 409),
        ("real-selection", base + "/v1/library/explain", admin,
            selection | {"requestId": uuid.uuid4().hex, "kind": args.kind}, 200)]:
        started = time.monotonic()
        status, body_result = request(url, body, credential)
        case = dict(name=name, http=status, elapsedMs=round((time.monotonic() - started) * 1000), passed=status == expected)
        if name == "real-selection":
            sources = (body_result or {}).get("sources", [])
            case.update(sourceCount=len(sources), textCharacters=len((body_result or {}).get("text", "")))
            case["passed"] = case["passed"] and bool(sources) and case["textCharacters"] > 0 and all(
                s.get("docId") == block["id"] and s.get("pdfPage") == page and s.get("anchor") for s in sources)
            private_json(root / f"{args.kind}.private.json", body_result)
        result["cases"].append(case)
        private_json(root / "probe-result.json", result)
        require(case["passed"], "live probe failed; inspect private result, do not claim completion")
    result["passed"] = True
    private_json(root / "probe-result.json", result)
    private_json(root / f"probe-{args.kind}-result.json", result)
    print(json.dumps(result, ensure_ascii=False))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare", help="copy an authorized isolated registration; never invent learned scope")
    for name in ["source-data", "source-library", "runtime", "bridge-python", "java-home"]:
        prep.add_argument("--" + name, type=Path, required=True)
    for name in ["document", "tail-host", "runtime-revision"]:
        prep.add_argument("--" + name, required=True)
    commands.add_parser("serve").add_argument("--port", type=int, default=int(os.environ.get("MULTICA_PORT", "0")))
    pair_parser = commands.add_parser("pair", help="write a fresh code, optionally pair winbox and store private credentials")
    pair_parser.add_argument("--winbox", action="store_true")
    pair_parser.add_argument("--friend-name-file", type=Path, help="private friend name; omitted preserves legacy owner pairing")
    probe_parser = commands.add_parser("probe", help="authorized real-source Q&A; output contains counts, never original text")
    probe_parser.add_argument("--selection-file", type=Path, required=True)
    probe_parser.add_argument("--kind", choices=("explain", "example"), default="explain")
    args = parser.parse_args()
    try:
        {"prepare": prepare, "serve": serve, "pair": pair, "probe": probe}[args.command](args)
    except (ValueError, OSError, sqlite3.Error, KeyError, subprocess.SubprocessError):
        # Exceptions can contain source paths/text or credentials; keep them local and out of daemon logs.
        parser.exit(1, "Friend service failed; verify private inputs/configuration and private child logs.\n")


if __name__ == "__main__":
    main()
