#!/usr/bin/env python3
"""live 探针：真实 claude / codex CLI + 真实教材页，走 8791 的桥副本。

阶段（分开跑，每段都远小于 10 分钟）：
  claude       流式追问 n=20，量「客户端看到正文首字」的 p50/p90/max（不含 role/进度帧）
  codex        Codex 非流式 n=5
  codex-stream 对 Codex 发 stream=true，记 delta 到达分布（证明它不真流式）
  cancel       流到一半断开，2 秒内 `pgrep -f "claude -p"` 回到基线
每段开跑前只读看 ~/DeepTutor/data/user/runtime/bridge.log 最后一行的活动时间，10 分钟内有活动就推迟（他在复习）。
结果逐条 append 到 --out 指定的 JSONL，便于汇总。

跑法：
  tools/bridge/.venv/bin/python tools/bridge/tests/live_probe.py --stage claude -n 20 --out /tmp/live.jsonl
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE_DIR = HERE.parent
PY = sys.executable
# 真实语料不进仓库（DESIGN §12.1）：默认从 ~/Coach/corpus/bridge-8791/ 读教材页，可用 COACH_CORPUS_DIR 覆盖。
CORPUS = Path(os.environ.get("COACH_CORPUS_DIR", str(Path.home() / "Coach" / "corpus" / "bridge-8791"))).expanduser()
DEEPTUTOR_LOG = Path.home() / "DeepTutor" / "data" / "user" / "runtime" / "bridge.log"
PACK_LIMIT = 40_000          # 备课包上限（ADR-0002 §4：≤40K 字）
PROGRESSIVE = ("system", "message_start", "content_block_start", "ping", "rate_limit_event",
               "message_delta", "message_stop", "content_block_stop", "thinking")


def build_pack(limit: int = PACK_LIMIT) -> str:
    """按真实备课包的取法拼：先用本知识点所在章节（逆向选择 08→09），不够再用其它章。"""
    files = sorted(CORPUS.glob("*.md"))
    if not files:
        raise SystemExit(f"语料目录 {CORPUS} 是空的：把教材页放进去，或设 COACH_CORPUS_DIR")
    preferred = [f for f in files if "Adverse selection" in f.name]
    ordered = preferred + [f for f in files if f not in preferred]
    corpus = ""
    for f in ordered:
        corpus += f.read_text(encoding="utf-8") + "\n\n"
        if len(corpus) >= limit:
            break
    return corpus[:limit]


def recent_deeptutor_activity(minutes: int = 10) -> str | None:
    """只读：看 DeepTutor 的 bridge.log 最后一行时间。返回说明文字表示需要推迟。"""
    if not DEEPTUTOR_LOG.exists():
        return None
    try:
        lines = [ln for ln in DEEPTUTOR_LOG.read_text(encoding="utf-8").splitlines() if ln.strip()]
    except OSError:
        return None
    if not lines:
        return None
    last = lines[-1]
    m = re.match(r"(\S+)", last)
    if not m:
        return None
    try:
        ts = datetime.fromisoformat(m.group(1))
    except ValueError:
        return None
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    age = (datetime.now(timezone.utc) - ts).total_seconds() / 60
    if age < minutes:
        return f"DeepTutor 的 8790 在 {age:.1f} 分钟前刚有请求（{m.group(1)}），按约束推迟"
    return None


SKIP_QUIET = [False]       # --skip-quiet-check：只跳过 DeepTutor 占用检查，黑名单与额度检查照旧


class Abort(Exception):
    """触发期中保护：整批中止（不是重试）。"""


BLACKOUT_START = datetime(2026, 11, 2, 0, 0)     # 11-01 18:00 起
BLACKOUT_END = datetime(2026, 11, 2, 15, 0)       # 到 11-02 15:00 不跑任何 Claude live


def in_blackout(now: datetime | None = None) -> bool:
    now = now or datetime.now()
    return BLACKOUT_START <= now < BLACKOUT_END


def preflight(port: int, claude: bool, skip_quiet: bool = False) -> None:
    """每个请求前都跑：黑名单时段、DeepTutor 是否在忙、额度是否有余量。任一不满足就中止整批。"""
    if claude and in_blackout():
        raise Abort(f"{BLACKOUT_START:%m-%d %H:%M} 至 {BLACKOUT_END:%m-%d %H:%M} 不跑 Claude live")
    if not skip_quiet:
        busy = recent_deeptutor_activity(10)
        if busy:
            raise Abort(busy)
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=5) as r:
            h = json.loads(r.read().decode("utf-8"))
    except OSError:
        return
    rl = h.get("rate_limit") or {}
    if rl.get("all_paused"):
        raise Abort(f"rate_limit utilization={rl.get('utilization')} ≥ {rl.get('pause_at')}，全部调用暂停")
    daily = h.get("daily") or {}
    cap = daily.get("claude_cap") or 0
    if claude and cap > 0 and (daily.get("remaining") or 0) <= 0:
        raise Abort(f"今日 Claude 物理调用已用完（{daily.get('claude_calls')}/{cap}）")


def claude_procs() -> int:
    out = subprocess.run(["pgrep", "-f", "claude -p"], capture_output=True, text=True)
    return len([x for x in out.stdout.split() if x.strip()])


class SSE:
    """HTTP/1.0 + 裸 socket：不做 chunked 解码，按行读，时间戳就是到达时间。"""

    def __init__(self, port: int, body: dict, headers: dict | None = None, timeout: float = 240):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        payload = json.dumps(body, ensure_ascii=False).encode()
        h = {"Host": "127.0.0.1", "Content-Type": "application/json", "X-Coach-Traffic": "test",
             "Content-Length": str(len(payload)), "Connection": "close"}
        h.update(headers or {})
        head = "POST /v1/chat/completions HTTP/1.0\r\n" + "".join(f"{k}: {v}\r\n" for k, v in h.items()) + "\r\n"
        self.sock.sendall(head.encode() + payload)
        self.f = self.sock.makefile("rb")
        self.status = 0
        self.resp_headers: dict[str, str] = {}
        line = self.f.readline().decode("latin-1").strip()
        parts = line.split()
        self.status = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
        while True:
            ln = self.f.readline().decode("latin-1").strip()
            if not ln:
                break
            if ":" in ln:
                k, v = ln.split(":", 1)
                self.resp_headers[k.strip().lower()] = v.strip()

    def events(self):
        """yield (到达时刻, obj)。畸形行与 [DONE] 跳过。"""
        while True:
            ln = self.f.readline()
            if not ln:
                return
            txt = ln.decode("utf-8", "replace")
            if not txt.startswith("data: "):
                continue
            raw = txt[6:].strip()
            if raw == "[DONE]":
                return
            try:
                yield time.monotonic(), json.loads(raw)
            except ValueError:
                continue

    def close(self) -> None:
        try:
            self.f.close()
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


def run_claude(port: int, pack: str, n: int, out: Path, effort: str | None = None) -> None:
    rows = []
    for i in range(n):
        preflight(port, claude=True, skip_quiet=SKIP_QUIET[0])   # 每个请求前都查，不是在批开始前查一次
        nonce = os.urandom(6).hex()          # 每个请求冷缓存，与核查员 d3 探针同口径
        system = (f"[session {nonce}]\n你是健康经济学导师。下面是本知识点的备课材料（教材原文页）。"
                  f"只依据材料回答，用中文，120–180 字。\n\n{pack}")
        body = {"model": "claude-sonnet-5-5", "stream": True,
                "messages": [{"role": "system", "content": system},
                             {"role": "user", "content": "用两三句话说清逆向选择为什么会让保险市场出现死亡螺旋，并给一处页码引用。"}]}
        rid = f"live-claude-{i:02d}-{nonce}"
        t0 = time.monotonic()
        hdrs = {"X-Request-Id": rid}
        if effort:
            hdrs["X-Coach-Effort"] = effort
        s = SSE(port, body, hdrs)
        ttft = None
        first_kind = None
        text = ""
        xc = None
        try:
            for t, obj in s.events():
                if "x_coach" in obj:
                    xc = obj["x_coach"]
                for ch in obj.get("choices") or []:
                    d = ch.get("delta") or {}
                    if d.get("content"):
                        if ttft is None:
                            ttft = (t - t0) * 1000
                            first_kind = "content"
                        text += d["content"]
        finally:
            total = (time.monotonic() - t0) * 1000
            s.close()
        row = {"stage": "claude", "i": i, "rid": rid, "http": s.status, "effort": effort,
               "ttft_ms": round(ttft, 1) if ttft else None,
               "total_ms": round(total, 1), "out_chars": len(text),
               "status": (xc or {}).get("status"), "reason": (xc or {}).get("reason"),
               "served_by": (xc or {}).get("served_by"),
               "queue_ms": (xc or {}).get("queue_ms"),
               "bridge_ttft_ms": (xc or {}).get("ttft_ms"),
               "physical_calls": (xc or {}).get("physical_calls"),
               "finish_reason": (xc or {}).get("finish_reason"),
               "pack_chars": len(system), "head": text[:40]}
        rows.append(row)
        append(out, row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    summarize(rows, "claude 流式追问首字（客户端 SSE 层）")


def run_codex(port: int, pack: str, n: int, out: Path, stream: bool = False) -> None:
    rows = []
    for i in range(n):
        preflight(port, claude=False, skip_quiet=SKIP_QUIET[0])
        nonce = os.urandom(6).hex()
        system = f"[session {nonce}]\n你是健康经济学导师。材料：\n\n{pack}"
        body = {"model": "gpt-6.1-sol", "stream": stream,
                "messages": [{"role": "system", "content": system},
                             {"role": "user", "content": "用两三句话说清逆向选择对保险市场的影响。"}]}
        rid = f"live-codex-{i:02d}-{nonce}"
        t0 = time.monotonic()
        s = SSE(port, body, {"X-Request-Id": rid})
        arrivals: list[float] = []
        text = ""
        xc = None
        try:
            if stream:
                for t, obj in s.events():
                    if "x_coach" in obj:
                        xc = obj["x_coach"]
                    for ch in obj.get("choices") or []:
                        d = ch.get("delta") or {}
                        if d.get("content"):
                            arrivals.append(round((t - t0) * 1000, 1))
                            text += d["content"]
            else:
                raw = s.f.read().decode("utf-8", "replace")
                try:
                    obj = json.loads(raw)
                except ValueError:
                    obj = {}
                xc = obj.get("x_coach")
                for ch in obj.get("choices") or []:
                    text += ((ch.get("message") or {}).get("content") or "")
        finally:
            total = (time.monotonic() - t0) * 1000
            s.close()
        row = {"stage": "codex-stream" if stream else "codex", "i": i, "rid": rid, "http": s.status,
               "total_ms": round(total, 1), "out_chars": len(text),
               "status": (xc or {}).get("status"), "served_by": (xc or {}).get("served_by"),
               "physical_calls": (xc or {}).get("physical_calls"),
               "delta_count": len(arrivals),
               "delta_first_ms": arrivals[0] if arrivals else None,
               "delta_last_ms": arrivals[-1] if arrivals else None,
               "delta_span_ms": round(arrivals[-1] - arrivals[0], 1) if len(arrivals) > 1 else 0,
               "pack_chars": len(system), "head": text[:40]}
        rows.append(row)
        append(out, row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    if stream:
        spans = [r["delta_span_ms"] for r in rows]
        print(f"\ncodex stream=true：{rows[0]['delta_count']} 个 chunk 在 {spans} ms 内全部到达 → 不真流式", flush=True)
    summarize(rows, "codex 非流式" if not stream else "codex 流式（假流式）")


def run_cancel(port: int, pack: str, n: int, out: Path) -> None:
    for i in range(n):
        preflight(port, claude=True, skip_quiet=SKIP_QUIET[0])
        base = claude_procs()
        nonce = os.urandom(6).hex()
        system = f"[session {nonce}]\n你是健康经济学导师。材料：\n\n{pack}"
        body = {"model": "claude-sonnet-5-5", "stream": True,
                "messages": [{"role": "system", "content": system},
                             {"role": "user", "content": "用 800 字详细讲逆向选择的机制、形式化条件和一个数值例子。"}]}
        rid = f"live-cancel-{i:02d}-{nonce}"
        s = SSE(port, body, {"X-Request-Id": rid})
        got = False
        for _t, obj in s.events():
            for ch in obj.get("choices") or []:
                if (ch.get("delta") or {}).get("content"):
                    got = True
                    break
            if got:
                break
        during = claude_procs()
        s.close()                      # 流到一半，客户端直接断开
        t_cut = time.monotonic()
        after = during
        waited = 0.0
        while time.monotonic() - t_cut < 2.0:
            after = claude_procs()
            if after <= base:
                break
            time.sleep(0.05)
            waited = time.monotonic() - t_cut
        row = {"stage": "cancel", "i": i, "rid": rid, "got_text_before_cut": got,
               "procs_baseline": base, "procs_during": during, "procs_after": after,
               "cleanup_ms": round(waited * 1000, 1), "ok_within_2s": after <= base}
        append(out, row)
        print(json.dumps(row, ensure_ascii=False), flush=True)


def append(path: Path, row: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def pct(vals: list[float], p: float) -> float:
    if not vals:
        return 0.0
    vs = sorted(vals)
    k = min(len(vs) - 1, max(0, int(round((p / 100) * (len(vs) - 1)))))
    return round(vs[k], 1)


def summarize(rows: list[dict], title: str) -> None:
    vals = [r["ttft_ms"] for r in rows if r.get("ttft_ms")]
    if vals:
        print(f"\n{title}：n={len(vals)} p50={pct(vals, 50)}ms p90={pct(vals, 90)}ms "
              f"max={round(max(vals), 1)}ms min={round(min(vals), 1)}ms "
              f"| 桥内首字（去掉排队）p50={pct([r['bridge_ttft_ms'] for r in rows if r.get('bridge_ttft_ms')], 50)}ms "
              f"queue={[r.get('queue_ms') for r in rows]}", flush=True)


def report(path: Path) -> None:
    """把 --out 的 JSONL 汇总成一份可贴进交付评论的数字表。"""
    rows = [json.loads(ln) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()] \
        if path.exists() else []
    # 同一次跑里 i 从 0 递增；i 回绕就是新的一批（换个备课包/强度重跑），分开报
    batches: list[list[dict]] = []
    for r in rows:
        if r["stage"] != "claude" or not r.get("ttft_ms"):
            continue
        if not batches or r["i"] <= batches[-1][-1]["i"]:
            batches.append([])
        batches[-1].append(r)
    for bi, sub in enumerate(batches, 1):
        vals = [r["ttft_ms"] for r in sub]
        print(f"Claude 流式追问 批{bi}（备课包 {sub[0]['pack_chars']} 字，effort={sub[0].get('effort') or '默认'}，冷缓存）："
              f"n={len(vals)} p50={pct(vals,50)}ms p90={pct(vals,90)}ms max={max(vals):.0f}ms min={min(vals):.0f}ms "
              f"queue_max={max(r.get('queue_ms') or 0 for r in sub)}ms")
    codex = [r for r in rows if r["stage"] == "codex"]
    if codex:
        tot = [r["total_ms"] for r in codex]
        print(f"Codex 非流式：n={len(tot)} p50={pct(tot,50)}ms p90={pct(tot,90)}ms "
              f"max={max(tot):.0f}ms，状态={sorted({r['status'] for r in codex})}")
    cstream = [r for r in rows if r["stage"] == "codex-stream"]
    if cstream:
        print(f"Codex stream=true（假流式）：n={len(cstream)} 每次 chunk 数={sorted({r['delta_count'] for r in cstream})} "
              f"首末块间隔={[r['delta_span_ms'] for r in cstream]}ms")
    canc = [r for r in rows if r["stage"] == "cancel"]
    if canc:
        print(f"取消：{len(canc)} 次全部在 2 秒内回到基线={[r['ok_within_2s'] for r in canc]}，"
              f"清理耗时={[r['cleanup_ms'] for r in canc]}ms")
    bridges = [r for r in rows if r["stage"] == "bridge"]
    for ev in ("start", "stop"):
        ts = [r["at"][11:19] for r in bridges if r["event"] == ev]
        if ts:
            print(f"8791 {'起' if ev == 'start' else '停'}：{', '.join(ts)}")
    calls = sum((r.get("physical_calls") or 0) for r in rows)
    aborted = [r for r in rows if r.get("event") == "aborted"]
    print(f"本文件物理调用合计：{calls}" + (f"；中止批次 {len(aborted)} 次（期中保护）" if aborted else ""))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=True,
                    choices=["claude", "codex", "codex-stream", "cancel", "report"])
    ap.add_argument("-n", type=int, default=20)
    ap.add_argument("--port", type=int, default=8791)
    ap.add_argument("--out", default="/tmp/coach-bridge-live.jsonl")
    ap.add_argument("--pack", type=int, default=PACK_LIMIT)
    ap.add_argument("--skip-quiet-check", action="store_true")
    ap.add_argument("--effort", default=None, help="按请求推理强度（X-Coach-Effort），如 low/medium")
    args = ap.parse_args()
    out = Path(args.out)

    if args.stage == "report":
        report(out)
        return 0

    SKIP_QUIET[0] = args.skip_quiet_check

    pack = build_pack(args.pack)
    print(f"备课包 {len(pack)} 字（来自 {CORPUS}）", flush=True)
    try:
        if args.stage == "claude":
            run_claude(args.port, pack, args.n, out, args.effort)
        elif args.stage == "codex":
            run_codex(args.port, pack, args.n, out, stream=False)
        elif args.stage == "codex-stream":
            run_codex(args.port, pack, args.n, out, stream=True)
        else:
            run_cancel(args.port, pack, args.n, out)
    except Abort as e:
        append(out, {"stage": args.stage, "event": "aborted", "reason": str(e), "at": time.time()})
        print(f"中止整批：{e}", flush=True)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
