#!/usr/bin/env python3
"""路线 1 真实模型跑题工具（TON-1976 §6 / TON-1977 #5）。

用法：
  python3 tools/book-stance/run.py --entry ask --stance forced --corpus ~/Coach/materials/public-corpus --out tools/book-stance/out/ask
  python3 tools/book-stance/run.py --entry explain --stance forced --corpus ~/Coach/materials/public-corpus --out tools/book-stance/out/explain
  python3 tools/book-stance/run.py --entry ask --stance forced --only K1-A,X1,X2 --corpus ~/Coach/materials/public-corpus --out tools/book-stance/out/sample

说明：
- 走正式 TextbookLibrary.importBook 导入管线，并与客户端使用同一套 ExplainWire/ImportedBookWire 请求构造；
- 默认自动在本机拉起当前分支的隔离 Mac 开发服务 + 订阅桥跑真实模型（也可通过 --server-url / --token 连接已启动的开发服务）；
- 输出逐题 JSON 与逐句核对报告到 --out（tools/book-stance/out/ 已在 .gitignore 中，不进 Git/评论/附件）。
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
QUESTIONS_PATH = Path(__file__).resolve().parent / "questions.json"
OUT_CACHE_DEFAULT = Path(__file__).resolve().parent / "out" / "_cache"
FORBIDDEN_PORTS = {8791, 18791, 18792, 18995}


def free_port() -> int:
    while True:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        if port not in FORBIDDEN_PORTS:
            return port


def ensure_bridge_python(explicit: Path | None) -> Path:
    if explicit and explicit.is_file():
        return explicit
    venv_py = ROOT / "tools/bridge/.venv/bin/python"
    if venv_py.is_file():
        return venv_py
    print("[run] creating tools/bridge/.venv ...", file=sys.stderr, flush=True)
    subprocess.run([sys.executable, "-m", "venv", str(ROOT / "tools/bridge/.venv")], check=True)
    subprocess.run(
        [str(venv_py), "-m", "pip", "install", "-q", "-r", str(ROOT / "tools/bridge/requirements.txt")],
        check=True,
    )
    return venv_py


def ensure_server_binary() -> Path:
    binary = ROOT / "server/app/build/install/app/bin/app"
    print("[run] building :server:app:installDist ...", file=sys.stderr, flush=True)
    subprocess.run(["./gradlew", ":server:app:installDist", "--quiet"], cwd=ROOT, check=True)
    if not binary.is_file():
        raise FileNotFoundError(f"Missing server binary: {binary}")
    return binary


def prepare_corpus_requests(
    corpus_dir: Path,
    cache_dir: Path,
    entry: str,
    stance_mode: str,
    only: str | None,
    set_name: str | None,
    forced_config: Path | None = None,
    no_seed_forced: bool = False,
) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    gradle_args = [
        "./gradlew",
        ":desktopApp:prepareBookStanceCorpus",
        "--quiet",
        f"-Pstance.corpus={corpus_dir}",
        f"-Pstance.questions={QUESTIONS_PATH}",
        f"-Pstance.cache={cache_dir}",
        f"-Pstance.entry={entry}",
        f"-Pstance.mode={stance_mode}",
    ]
    if only:
        gradle_args.append(f"-Pstance.only={only}")
    if set_name:
        gradle_args.append(f"-Pstance.set={set_name}")
    if forced_config is not None:
        gradle_args.append(f"-Pstance.forcedConfig={forced_config}")
    if no_seed_forced:
        gradle_args.append("-Pstance.noSeedForced=true")
    print(f"[run] preparing corpus & client wire payloads ({entry}) ...", file=sys.stderr, flush=True)
    subprocess.run(gradle_args, cwd=ROOT, check=True)
    prepared = cache_dir / f"prepared-{entry}.json"
    if not prepared.is_file():
        raise FileNotFoundError(f"Prepared manifest missing: {prepared}")
    return prepared


def stop_proc(proc: subprocess.Popen[Any] | None) -> None:
    if proc is None or proc.poll() is not None:
        return
    try:
        proc.terminate()
        proc.wait(timeout=8)
    except Exception:
        try:
            proc.kill()
            proc.wait(timeout=4)
        except Exception:
            pass


def post_explain(server_url: str, token: str, device_id: str, payload: dict[str, Any], timeout: int = 120) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{server_url.rstrip('/')}/v1/library/explain",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "X-Coach-Device": device_id,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_explain_with_retry(server_url: str, token: str, device_id: str, payload: dict[str, Any], retries: int = 2) -> dict[str, Any]:
    last_err: Exception | None = None
    expect_stance = "stance" in payload and payload["stance"] is not None
    for attempt in range(retries + 1):
        req_payload = dict(payload)
        if attempt > 0:
            req_payload["requestId"] = uuid.uuid4().hex
        try:
            res = post_explain(server_url, token, device_id, req_payload)
            # Only retry on missing `stance` when the request itself actually asked for a stance response
            if expect_stance and "stance" not in res and attempt < retries:
                time.sleep(1.5)
                continue
            return res
        except Exception as exc:
            last_err = exc
            if attempt < retries:
                time.sleep(2.0)
    raise RuntimeError(f"request failed after {retries + 1} tries: {last_err}")


def write_verification_report(out_dir: Path, run_meta: dict[str, Any], results: list[dict[str, Any]], pages: dict[str, str]) -> None:
    lines: list[str] = []
    lines.append(f"# Book Stance Verification Report ({run_meta['entry']} / {run_meta['expectedStanceSource']})")
    lines.append("")
    total_book_sents = 0
    total_dropped = 0
    total_oor = 0
    for r in results:
        resp = r.get("response") or {}
        gate = resp.get("gate") or {}
        total_dropped += int(gate.get("droppedSentences", 0))
        total_oor += int(gate.get("outOfRange", 0))
        for lyr in resp.get("layers") or []:
            if lyr.get("layer") == "book":
                total_book_sents += len(lyr.get("sentences") or [])
    lines.append(
        f"- Total items: {len(results)} | Book sentences: {total_book_sents} | "
        f"Dropped sentences: {total_dropped} | Out-of-range: {total_oor}"
    )
    lines.append("")
    for r in results:
        qid = r["id"]
        meta = r["meta"]
        resp = r.get("response") or {}
        gate = resp.get("gate") or {}
        lines.append(f"## {qid} · {r['bookKey']} (p.{r['selectionPage']})")
        if meta.get("question"):
            lines.append(f"- Question: {meta['question']}")
        lines.append(
            f"- Expected stance: `{meta.get('stance')}` | Actual stance: `{resp.get('stance')}` | "
            f"Guards: `{meta.get('guards')}` | stanceSource: `{r.get('stanceSource')}` | "
            f"modelCalled: `{resp.get('modelCalled')}` | dropped: `{gate.get('droppedSentences', 0)}` | "
            f"outOfRange: `{gate.get('outOfRange', 0)}`"
        )
        if r.get("initialResponse"):
            ir = r["initialResponse"]
            lines.append(
                f"- Initial card (`{r.get('initialStance')}` → actual `{ir.get('stance')}`): "
                f"noBook=`{ir.get('noBookNotice')}` layers=`{len(ir.get('layers') or [])}` "
                f"outOfRange=`{(ir.get('gate') or {}).get('outOfRange', 0)}`"
            )
            if ir.get("text"):
                lines.append(f"  - Initial summary: {ir['text'].replace(chr(10), ' ')}")
        if resp.get("boundary"):
            b = resp["boundary"]
            lines.append(f"- **BOUNDARY**: {b.get('message')} [{b.get('actionLabel')}]")
        if resp.get("systemNotice"):
            lines.append(f"- System notice: {resp['systemNotice']}")
        if resp.get("noBookNotice"):
            lines.append(f"- No-book notice: {resp['noBookNotice']}")
        for lyr in resp.get("layers") or []:
            kind = lyr.get("layer")
            label = lyr.get("label")
            if kind == "book":
                lines.append(f"- **[{label}]**")
                for s_idx, sent in enumerate(lyr.get("sentences") or [], 1):
                    lines.append(f"  {s_idx}. 回答句：{sent.get('text')}")
                    for cite in sent.get("citations") or []:
                        lines.append(
                            f"     - 原句 (p.{cite.get('pdfPage')} [{cite.get('startUtf16')}:{cite.get('endUtf16')}]): "
                            f"{cite.get('quote')}"
                        )
            else:
                lines.append(f"- **[{label}]** {lyr.get('text')}")
        if r.get("error"):
            lines.append(f"- **ERROR**: {r['error']}")
        lines.append("")
    (out_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--entry", choices=["ask", "explain", "collection"], required=True)
    parser.add_argument("--stance", choices=["forced", "auto"], default="forced")
    parser.add_argument("--set", dest="set_name", choices=["locked"], default=None)
    parser.add_argument("--only", default=None, help="Comma-separated question IDs (e.g. K1-A,X1,X2)")
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=OUT_CACHE_DEFAULT)
    parser.add_argument("--server-url", default=os.environ.get("COACH_DEV_SERVER_URL"))
    parser.add_argument("--token", default=os.environ.get("COACH_DEV_TOKEN"))
    parser.add_argument("--device-id", default="desktop")
    parser.add_argument("--bridge-python", type=Path, default=None)
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--forced-config", type=Path, default=None, help="Existing book-stance-forced.json to test without auto-seeding")
    parser.add_argument("--no-seed-forced", action="store_true", help="Do not auto-write book-stance-forced.json; resolve from existing library state only")
    args = parser.parse_args()

    if args.entry == "collection":
        print("collection entry is implemented in stage 2 (TON-1978).", file=sys.stderr)
        return 2
    if args.set_name == "locked":
        print("--set locked is implemented in stage 2 (TON-1979).", file=sys.stderr)
        return 2
    if args.stance == "auto":
        print("--stance auto is implemented in stage 2 (TON-1979); stage 1 uses --stance forced.", file=sys.stderr)
        return 2

    corpus_dir = args.corpus.expanduser().resolve()
    out_dir = args.out.resolve()
    cache_dir = args.cache.resolve()
    forced_config = args.forced_config.expanduser().resolve() if args.forced_config else None

    prepared_path = prepare_corpus_requests(
        corpus_dir,
        cache_dir,
        args.entry,
        args.stance,
        args.only,
        args.set_name,
        forced_config=forced_config,
        no_seed_forced=args.no_seed_forced,
    )
    prepared = json.loads(prepared_path.read_text(encoding="utf-8"))
    items = prepared["items"]
    pages = prepared["pages"]

    # 保留已有输出目录中的既有文件，仅按需创建目录并覆盖本次产物，绝不递归删除 out_dir。
    (out_dir / "answers").mkdir(parents=True, exist_ok=True)
    (out_dir / "pages.json").write_text(json.dumps(pages, ensure_ascii=False), encoding="utf-8")

    # Copy active book-stance-forced.json from the library directory if present
    forced_file = cache_dir / "library" / "book-stance-forced.json"
    if forced_file.is_file():
        shutil.copy2(forced_file, out_dir / "book-stance-forced.json")

    expected_source = "locked" if args.set_name == "locked" else args.stance
    run_meta = {
        "entry": args.entry,
        "stance": args.stance,
        "set": args.set_name,
        "expectedStanceSource": expected_source,
        "itemCount": len(items),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (out_dir / "run_meta.json").write_text(json.dumps(run_meta, ensure_ascii=False, indent=2), encoding="utf-8")

    children: list[subprocess.Popen[Any]] = []
    handles: list[Any] = []
    tmp_ctx: tempfile.TemporaryDirectory[str] | None = None

    try:
        server_url = args.server_url
        token = args.token or ""
        if not server_url:
            bridge_py = ensure_bridge_python(args.bridge_python)
            server_bin = ensure_server_binary()
            tmp_ctx = tempfile.TemporaryDirectory(prefix="coach-stance-run-")
            base = Path(tmp_ctx.name)
            port = free_port()
            bridge_port = free_port()
            while bridge_port == port:
                bridge_port = free_port()

            clean_env = {
                k: v
                for k, v in os.environ.items()
                if not k.startswith(("COACH_", "BRIDGE_"))
                and k not in {"OPENAI_API_KEY", "ANTHROPIC_API_KEY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS"}
            }
            # Read keychain API keys on macOS just like tools/bridge/run.sh (never logged)
            if sys.platform == "darwin":
                for env_key, svc in [
                    ("COACH_ANTHROPIC_API_KEY", "coach-anthropic-api"),
                    ("COACH_OPENAI_API_KEY", "coach-openai-api"),
                ]:
                    res = subprocess.run(
                        ["security", "find-generic-password", "-s", svc, "-w"],
                        capture_output=True,
                        text=True,
                    )
                    if res.returncode == 0 and res.stdout.strip():
                        clean_env[env_key] = res.stdout.strip()

            bridge_log = (out_dir / "bridge.log").open("ab")
            handles.append(bridge_log)
            bridge_env = dict(
                clean_env,
                BRIDGE_PORT=str(bridge_port),
                COACH_BRIDGE_HOME=str(base / "bridge"),
                BRIDGE_CLAUDE_CONCURRENCY="3",
            )
            bridge = subprocess.Popen(
                [str(bridge_py), str(ROOT / "tools/bridge/subscription_bridge.py")],
                env=bridge_env,
                stdout=bridge_log,
                stderr=subprocess.STDOUT,
            )
            children.append(bridge)
            deadline = time.monotonic() + 25
            while True:
                if bridge.poll() is not None:
                    raise RuntimeError(f"Isolated bridge exited early (see {out_dir / 'bridge.log'})")
                try:
                    with urllib.request.urlopen(f"http://127.0.0.1:{bridge_port}/health?scope=process", timeout=2):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Bridge failed to become healthy")
                    time.sleep(0.25)

            data_dir = base / "data"
            lib_dir = base / "library"
            lib_dir.mkdir(parents=True, exist_ok=True)
            (lib_dir / "catalog.json").write_text('{"schema":1,"documents":[]}\n', encoding="utf-8")
            server_log = (out_dir / "server.log").open("ab")
            handles.append(server_log)
            server_env = dict(
                clean_env,
                COACH_DATA_DIR=str(data_dir),
                COACH_TOKEN_FILE=str(data_dir / "device-token"),
                COACH_LOG_DIR=str(base / "logs"),
                COACH_STATE_DIR=str(base / "state"),
                COACH_RUN_DIR=str(base / "run"),
                COACH_PACKS_DIR=str(base / "packs"),
                COACH_MOCK_DIR=str(base / "mock"),
                COACH_GENERATED_DIR=str(base / "generated"),
                COACH_TAIL_HOST="",
                COACH_PORT=str(port),
                COACH_TRAFFIC="test",
                COACH_READER_LIBRARY=str(lib_dir),
                COACH_BRIDGE_URL=f"http://127.0.0.1:{bridge_port}",
            )
            server = subprocess.Popen(
                [str(server_bin)],
                env=server_env,
                stdout=server_log,
                stderr=subprocess.STDOUT,
            )
            children.append(server)
            server_url = f"http://127.0.0.1:{port}"
            deadline = time.monotonic() + 45
            while True:
                if server.poll() is not None:
                    raise RuntimeError(f"Isolated server exited early (see {out_dir / 'server.log'})")
                try:
                    token_file = data_dir / "device-token"
                    if token_file.is_file():
                        token = token_file.read_text(encoding="utf-8").strip()
                        req = urllib.request.Request(
                            f"{server_url}/v1/hello",
                            headers={"Authorization": f"Bearer {token}", "X-Coach-Device": args.device_id},
                        )
                        with urllib.request.urlopen(req, timeout=2):
                            break
                except OSError:
                    pass
                if time.monotonic() >= deadline:
                    raise TimeoutError("Server failed to become healthy")
                time.sleep(0.25)
            print(f"[run] isolated server ready on {server_url}", file=sys.stderr, flush=True)

        def execute_item(item: dict[str, Any]) -> dict[str, Any]:
            started = time.monotonic()
            record: dict[str, Any] = {
                "id": item["id"],
                "entry": item["entry"],
                "bookKey": item["bookKey"],
                "bookHash": item["bookHash"],
                "bookTitle": item["bookTitle"],
                "bookPages": item["bookPages"],
                "locator": item["locator"],
                "selectionPage": item["selectionPage"],
                "selectionBlockId": item["selectionBlockId"],
                "readThroughPage": item.get("readThroughPage"),
                "effectiveReadThroughPage": item.get("effectiveReadThroughPage"),
                "stanceSource": item.get("stanceSource", "undetermined"),
                "resolvedDefaultStance": item.get("resolvedDefaultStance"),
                "resolvedGuards": item.get("resolvedGuards"),
                "meta": item["meta"],
            }
            if "initialStance" in item:
                record["initialStance"] = item["initialStance"]
            try:
                if "initialRequest" in item:
                    record["initialResponse"] = post_explain_with_retry(
                        server_url, token, args.device_id, item["initialRequest"]
                    )
                record["response"] = post_explain_with_retry(
                    server_url, token, args.device_id, item["request"]
                )
            except Exception as exc:
                record["error"] = str(exc)
            record["roundTripMs"] = round((time.monotonic() - started) * 1000)
            (out_dir / "answers" / f"{item['id']}.json").write_text(
                json.dumps(record, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            return record

        results_by_id: dict[str, dict[str, Any]] = {}
        workers = max(1, min(args.concurrency, len(items)))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futs = {pool.submit(execute_item, it): it["id"] for it in items}
            done_count = 0
            for fut in as_completed(futs):
                rec = fut.result()
                results_by_id[rec["id"]] = rec
                done_count += 1
                resp = rec.get("response") or {}
                gate = resp.get("gate") or {}
                b_sents = sum(
                    len(l.get("sentences") or [])
                    for l in (resp.get("layers") or [])
                    if l.get("layer") == "book"
                )
                print(
                    f"[{done_count}/{len(items)}] {rec['id']} stance={resp.get('stance')} "
                    f"bookSents={b_sents} boundary={resp.get('boundary') is not None} "
                    f"dropped={gate.get('droppedSentences', 0)} ms={rec['roundTripMs']}"
                    + (f" ERROR={rec['error']}" if rec.get("error") else ""),
                    flush=True,
                )

        ordered_results = [results_by_id[it["id"]] for it in items]
        write_verification_report(out_dir, run_meta, ordered_results, pages)
        print(f"[run] completed {len(ordered_results)} items -> {out_dir}", flush=True)
        return 0
    finally:
        for child in reversed(children):
            stop_proc(child)
        for h in handles:
            try:
                h.close()
            except Exception:
                pass
        if tmp_ctx is not None:
            tmp_ctx.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
