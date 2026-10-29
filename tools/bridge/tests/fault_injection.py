#!/usr/bin/env python3
"""桩 CLI 故障注入回归（ADR-0002(9)：不花额度）。

四项必测 + 一项排队：
  1. kill -9 中途          → truncated / cli_exit / error
  2. 挂起超时（出字前/后）  → failed·timeout / truncated·timeout，finish_reason=error
  3. 拒答兜底（开/关两种）  → 开：complete 且 served_by=兜底模型、fallback=true、physical_calls=2
                              关：failed / cli_exit、physical_calls=1（默认就是关）
  4. max_output_tokens      → truncated / max_output_tokens / length（不再报 stop）
  5. 4 个请求排队           → 全部 complete、同一时刻最多 1 个桩在跑、queue_ms 单列
另外验证：/health 登录状态与 true_stream 能力表、X-Request-Id 回显、X-Coach-Fallback 默认 off、
后台转派 Codex、额度紧张时后台 429。

每项断言都看最后一帧（x_coach）的 status / reason / finish_reason / served_by / physical_calls。
跑法：tools/bridge/.venv/bin/python tools/bridge/tests/fault_injection.py
"""
from __future__ import annotations

import http.client
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE_DIR = HERE.parent
PY = sys.executable

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    RESULTS.append((name, bool(cond), detail))
    print(("PASS  " if cond else "FAIL  ") + name + (f"  [{detail}]" if detail else ""), flush=True)


def stub_procs(stub_dir: Path) -> int:
    """桩是按 <stubdir>/claude 这个名字启动的（不是 stub_cli.py），按这个名字数。"""
    out = subprocess.run(["pgrep", "-f", str(stub_dir / "claude")], capture_output=True, text=True)
    return len([x for x in out.stdout.split() if x.strip()])


def abort_after(port: int, body: dict, delay: float) -> None:
    """发一个请求，delay 秒后直接断开（模拟客户端取消），一句话都不读。"""
    s = socket.create_connection(("127.0.0.1", port), timeout=30)
    payload = json.dumps(body, ensure_ascii=False).encode()
    head = ("POST /v1/chat/completions HTTP/1.0\r\nHost: 127.0.0.1\r\n"
            "Content-Type: application/json\r\n"
            f"Content-Length: {len(payload)}\r\nConnection: close\r\n\r\n")
    s.sendall(head.encode() + payload)
    time.sleep(delay)
    s.close()


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Bridge:
    def __init__(self, stub_dir: Path, home: Path, env_extra: dict | None = None):
        self.port = free_port()
        self.stub_dir = stub_dir
        self.home = home
        env = dict(os.environ)
        env.update({
            "BRIDGE_PORT": str(self.port),
            "COACH_BRIDGE_HOME": str(home),
            "BRIDGE_CHILD_PATH": str(stub_dir),
            "STUB_DIR": str(stub_dir),
            "BRIDGE_HEALTH_CACHE_SEC": "0",
            "BRIDGE_CLAUDE_CONCURRENCY": "1",
            "BRIDGE_CLAUDE_TIMEOUT": "3",
            "BRIDGE_STALL_TIMEOUT": "2",
            "BRIDGE_STREAM_TOTAL_TIMEOUT": "12",
            "BRIDGE_CODEX_TIMEOUT": "10",
        })
        env.update(env_extra or {})
        self.proc = subprocess.Popen([PY, str(BRIDGE_DIR / "subscription_bridge.py")],
                                     env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.log = home / "logs" / "bridge.log"
        for _ in range(100):
            try:
                st, _, _ = self.http("GET", "/health")
                if st == 200:
                    return
            except OSError:
                pass
            time.sleep(0.05)
        self.stop()
        out, err = self.proc.communicate(timeout=5) if self.proc.poll() is not None else (b"", b"")
        raise RuntimeError(f"bridge 没起来：{err.decode()[-800:] or out.decode()[-800:]}")

    def stop(self) -> None:
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def set_mode(self, m: str) -> None:
        (self.stub_dir / "mode").write_text(m, encoding="utf-8")

    def http(self, method: str, path: str, body: dict | None = None,
             headers: dict | None = None, timeout: float = 30):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        h = {"Content-Type": "application/json"}
        h.update(headers or {})
        payload = json.dumps(body).encode() if body is not None else None
        conn.request(method, path, body=payload, headers=h)
        resp = conn.getresponse()
        data = resp.read()
        hdrs = {k.lower(): v for k, v in resp.getheaders()}
        conn.close()
        return resp.status, hdrs, data

    def ask(self, text: str = "你好", model: str = "claude-sonnet-5-5", stream: bool = True,
            headers: dict | None = None) -> dict:
        """发一个请求，返回 {status, headers, x_coach, content, error, frames}。"""
        body = {"model": model, "stream": stream,
                "messages": [{"role": "user", "content": text}]}
        st, hdrs, data = self.http("POST", "/v1/chat/completions", body, headers)
        out = {"status": st, "headers": hdrs, "x_coach": None, "content": "", "error": None, "frames": []}
        if hdrs.get("content-type", "").startswith("text/event-stream"):
            for line in data.decode("utf-8", "replace").splitlines():
                if not line.startswith("data: "):
                    continue
                raw = line[6:].strip()
                if raw == "[DONE]":
                    continue
                try:
                    obj = json.loads(raw)
                except ValueError:
                    continue
                out["frames"].append(obj)
                if "x_coach" in obj:
                    out["x_coach"] = obj["x_coach"]
                for ch in obj.get("choices") or []:
                    out["content"] += (ch.get("delta") or {}).get("content") or ""
        else:
            try:
                obj = json.loads(data.decode("utf-8", "replace"))
            except ValueError:
                obj = {}
            out["error"] = obj.get("error")
            out["x_coach"] = obj.get("x_coach")
            for ch in obj.get("choices") or []:
                out["content"] += ((ch.get("message") or {}).get("content") or "")
        return out


def main() -> int:
    run_dir = Path(tempfile.mkdtemp(prefix="coach-bridge-fit-"))
    stub_dir = run_dir / "stubbin"
    stub_dir.mkdir()
    for name in ("claude", "codex"):
        (stub_dir / name).symlink_to(HERE / "stub_cli.py")
    os.chmod(HERE / "stub_cli.py", 0o755)
    (stub_dir / "mode").write_text("ok", encoding="utf-8")

    def overlap_max() -> int:
        pts = []
        f = stub_dir / "overlap.log"
        if not f.exists():
            return 0
        for ln in f.read_text().splitlines():
            kind, _pid, ts = ln.split()
            pts.append((float(ts), 1 if kind == "S" else -1))
        cur = mx = 0
        for _, d in sorted(pts):
            cur += d
            mx = max(mx, cur)
        return mx

    b = Bridge(stub_dir, run_dir / "home-a")
    try:
        # ---- 能力表 / 健康检查 ----
        st, _, data = b.http("GET", "/v1/models")
        models = {m["id"]: m for m in json.loads(data)["data"]}
        check("v1/models: Claude true_stream=true", models["claude-sonnet-5-5"]["true_stream"] is True)
        check("v1/models: Codex true_stream=false", models["gpt-6.1-sol"]["true_stream"] is False)
        check("v1/models: Grok 未注册", not any("grok" in k for k in models))
        st, _, data = b.http("GET", "/health")
        h = json.loads(data)
        check("/health: claude 已登录", h["upstreams"]["claude"]["logged_in"] is True,
              str(h["upstreams"]["claude"]["detail"])[:40])
        check("/health: codex 已登录", h["upstreams"]["codex"]["logged_in"] is True)
        check("/health: 带 true_stream 能力表", h["models"]["gpt-6.1-sol"]["true_stream"] is False)

        # ---- 正常流式：served_by 取 CLI 自报模型 ----
        b.set_mode("ok")
        r = b.ask("正常一问", headers={"X-Request-Id": "rid-ok-1"})
        oc = r["x_coach"]
        check("正常流式: status=complete", oc and oc["status"] == "complete", str(oc))
        check("served_by 取 CLI 自报模型", oc and oc["served_by"] == "claude-sonnet-5-5-stub", str(oc and oc["served_by"]))
        check("X-Request-Id 回显", r["headers"].get("x-request-id") == "rid-ok-1", r["headers"].get("x-request-id", ""))
        check("X-Coach-Fallback 默认 off", r["headers"].get("x-coach-fallback") == "off")
        check("终态帧在 [DONE] 之前（最后一帧是 x_coach）",
              bool(r["frames"]) and "x_coach" in r["frames"][-1])
        check("正常流式: physical_calls=1", oc and oc["physical_calls"] == 1)

        # ---- 1. kill -9 中途 ----
        b.set_mode("kill9")
        r = b.ask("kill9 中途")
        oc = r["x_coach"]
        check("kill9: status=truncated", oc and oc["status"] == "truncated", str(oc))
        check("kill9: reason=cli_exit", oc and oc["reason"] == "cli_exit", str(oc and oc["reason"]))
        check("kill9: finish_reason=error", oc and oc["finish_reason"] == "error", str(oc and oc["finish_reason"]))
        check("kill9: 已发正文非空", bool(r["content"]), f"{len(r['content'])} 字")
        check("kill9: physical_calls=1", oc and oc["physical_calls"] == 1)

        # ---- 2a. 挂起（出字前）→ failed / timeout ----
        b.set_mode("hang")
        r = b.ask("挂起不出字")
        oc = r["x_coach"]
        check("hang: 仍是 SSE 200 + 终态帧（客户端只认 x_coach）", r["status"] == 200 and r["frames"],
              str(r["status"]))
        check("hang: status=failed", oc and oc["status"] == "failed", str(oc))
        check("hang: reason=timeout", oc and oc["reason"] == "timeout", str(oc and oc["reason"]))
        check("hang: finish_reason=error", oc and oc["finish_reason"] == "error")

        # ---- 2c. 出字前取消：CLI 必须被杀掉，2 秒内无残留 ----
        b.set_mode("hang")
        before = stub_procs(stub_dir)
        abort_after(b.port, {"model": "claude-sonnet-5-5", "stream": True,
                             "messages": [{"role": "user", "content": "取消测试"}]}, delay=1.0)
        during = stub_procs(stub_dir)
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline and stub_procs(stub_dir) > before:
            time.sleep(0.05)
        check("取消（出字前）: 桩确实在跑过，之后 2 秒内回到基线", during > before and stub_procs(stub_dir) <= before,
              f"before={before} during={during} after={stub_procs(stub_dir)}")

        # ---- 2b. 挂起（出字后）→ truncated / timeout ----
        b.set_mode("hang_after")
        r = b.ask("出字后挂住")
        oc = r["x_coach"]
        check("hang_after: status=truncated", oc and oc["status"] == "truncated", str(oc))
        check("hang_after: reason=timeout", oc and oc["reason"] == "timeout", str(oc and oc["reason"]))
        check("hang_after: finish_reason=error", oc and oc["finish_reason"] == "error")

        # ---- 3a. 拒答 + 兜底关（默认）→ 不偷偷换模型 ----
        b.set_mode("refuse")
        r = b.ask("拒答，别兜底")
        oc = r["x_coach"]
        check("拒答/兜底关: 仍是 SSE 200 + 终态帧", r["status"] == 200, str(r["status"]))
        check("拒答/兜底关: status=failed", oc and oc["status"] == "failed", str(oc))
        check("拒答/兜底关: reason=cli_exit", oc and oc["reason"] == "cli_exit", str(oc and oc["reason"]))
        check("拒答/兜底关: physical_calls=1（没有第二次调用）", oc and oc["physical_calls"] == 1)
        check("拒答/兜底关: served_by 仍是 Claude（没有偷偷换模型）",
              oc and oc["served_by"] == "claude-sonnet-5-5-stub", str(oc and oc["served_by"]))

        # ---- 3b. 拒答 + 兜底开 → 报真名 ----
        r = b.ask("拒答，允许兜底", headers={"X-Coach-Fallback": "on"})
        oc = r["x_coach"]
        check("拒答/兜底开: status=complete", oc and oc["status"] == "complete", str(oc))
        check("拒答/兜底开: served_by=兜底模型", oc and oc["served_by"] == "gpt-6.1-sol", str(oc and oc["served_by"]))
        check("拒答/兜底开: fallback=true", oc and oc["fallback"] is True)
        check("拒答/兜底开: physical_calls=2", oc and oc["physical_calls"] == 2, str(oc and oc["physical_calls"]))
        check("拒答/兜底开: 正文来自兜底", "codex 桩" in r["content"], r["content"][:20])
        check("拒答/兜底开: 响应头说明兜底开着", r["headers"].get("x-coach-fallback") == "on")

        # ---- 4. max_output_tokens ----
        b.set_mode("maxtok")
        r = b.ask("截断测试")
        oc = r["x_coach"]
        check("maxtok: status=truncated", oc and oc["status"] == "truncated", str(oc))
        check("maxtok: reason=max_output_tokens", oc and oc["reason"] == "max_output_tokens", str(oc and oc["reason"]))
        check("maxtok: finish_reason=length（不再报 stop）", oc and oc["finish_reason"] == "length",
              str(oc and oc["finish_reason"]))

        # ---- Codex 非流式（非真流式后端）----
        b.set_mode("ok")
        r = b.ask("codex 非流式", model="gpt-6.1-sol", stream=False)
        oc = r["x_coach"]
        check("codex 非流式: status=complete", oc and oc["status"] == "complete", str(oc))
        check("codex 非流式: X-Served-Model 头", r["headers"].get("x-served-model") == "gpt-6.1-sol",
              str(r["headers"].get("x-served-model")))
        check("codex 非流式: 正文来自 Codex", "codex 桩" in r["content"], r["content"][:20])

        # ---- 后台转派：后台请求落到 Codex（期中保护）----
        r = b.ask("后台预生成", headers={"X-Coach-Background": "1", "X-Coach-Fallback": "off"})
        oc = r["x_coach"]
        check("后台转派: 实际由 Codex 作答", oc and oc["served_by"] == "gpt-6.1-sol", str(oc and oc["served_by"]))
        check("后台转派: 头里写明原请求模型", r["headers"].get("x-coach-routed-from") == "claude-sonnet-5-5",
              str(r["headers"].get("x-coach-routed-from")))
    finally:
        b.stop()

    # ---- 5. 4 个请求排队（单独一台桥，超时放宽）----
    b2 = Bridge(stub_dir, run_dir / "home-b",
                {"BRIDGE_CLAUDE_TIMEOUT": "30", "BRIDGE_STALL_TIMEOUT": "6",
                 "BRIDGE_STREAM_TOTAL_TIMEOUT": "60", "BRIDGE_BACKGROUND_BACKEND": ""})
    try:
        if (stub_dir / "overlap.log").exists():
            (stub_dir / "overlap.log").unlink()
        b2.set_mode("slow")
        out: list[dict] = [{} for _ in range(4)]

        def worker(i: int) -> None:
            out[i] = b2.ask(f"排队第 {i} 个", headers={"X-Request-Id": f"q{i}"})

        ts = [threading.Thread(target=worker, args=(i,)) for i in range(4)]
        t0 = time.monotonic()
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        wall = time.monotonic() - t0
        ocs = [o.get("x_coach") for o in out]
        check("排队: 4 个请求全部 complete", all(o and o["status"] == "complete" for o in ocs),
              str([o and o["status"] for o in ocs]))
        check("排队: 同一时刻最多 1 个 Claude（并发上限生效）", overlap_max() == 1, f"max overlap={overlap_max()}")
        qs = sorted((o["queue_ms"] for o in ocs if o), reverse=True)
        check("排队: 后到的请求记到排队时间", qs and qs[0] > 500, f"queue_ms={qs}")
        check("排队: 墙钟时间≈4×单个请求", wall > 4 * 3.0, f"{wall:.1f}s")
        check("排队: 首字计时不含排队（ttft 明显小于 queue+ttft）",
              all(o["ttft_ms"] is not None for o in ocs if o), str([o and o["ttft_ms"] for o in ocs]))
    finally:
        b2.stop()

    # ---- 额度紧张时暂停后台（rate_limit_event utilization 0.95）----
    b3 = Bridge(stub_dir, run_dir / "home-c", {"BRIDGE_BACKGROUND_BACKEND": ""})
    try:
        b3.set_mode("rate")
        r = b3.ask("前台把额度用到 0.95")
        check("rate: 前台的 rate_limit_event 被记录",
              json.loads(b3.http("GET", "/health")[2])["rate_limit"]["utilization"] == 0.95)
        r = b3.ask("后台请求应被拒", headers={"X-Coach-Background": "1"})
        check("rate: 后台请求 429", r["status"] == 429, str(r["status"]))
    finally:
        b3.stop()

    shutil.rmtree(run_dir, ignore_errors=True)

    failed = [n for n, ok, _ in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} 通过")
    if failed:
        print("失败：" + ", ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
