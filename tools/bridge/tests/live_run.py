#!/usr/bin/env python3
"""起一台独立端口的桥副本 → 跑 live 探针 → 关掉。只用于 M0 验收，不是常驻服务。

例：
  tools/bridge/.venv/bin/python tools/bridge/tests/live_run.py --stages claude --claude-n 20
  tools/bridge/.venv/bin/python tools/bridge/tests/live_run.py --stages codex,codex-stream --codex-n 5
  tools/bridge/.venv/bin/python tools/bridge/tests/live_run.py --stages cancel --cancel-n 2

每个阶段开跑前由 live_probe 自己只读检查 ~/DeepTutor/data/user/runtime/bridge.log：
10 分钟内有活动就推迟（返回码 3），本脚本原样透出。
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import json
import time
import urllib.request
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE_DIR = HERE.parent
PY = sys.executable


def healthy(port: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return r.status == 200
    except OSError:
        return False


def main() -> int:
    import socket
    import tempfile
    ap = argparse.ArgumentParser()
    ap.add_argument("--stages", default="claude")
    ap.add_argument("--claude-n", type=int, default=20)
    ap.add_argument("--codex-n", type=int, default=5)
    ap.add_argument("--cancel-n", type=int, default=2)
    ap.add_argument("--port", type=int, help="独立测试桥端口，默认系统分配")
    ap.add_argument("--out", default="/tmp/coach-bridge-live.jsonl")
    ap.add_argument("--bridge-home", help="独立测试目录，默认新建临时目录")
    ap.add_argument("--effort", default=None, help="claude 阶段的 X-Coach-Effort")
    args = ap.parse_args()

    if args.port in (8790, 8791):
        ap.error("测试禁止使用 8790/8791")
    if args.port is None:
        with socket.socket() as address:
            address.bind(("127.0.0.1", 0))
            args.port = address.getsockname()[1]
    args.bridge_home = args.bridge_home or tempfile.mkdtemp(prefix="coach-live-bridge-")
    production_home = Path.home() / "Coach" / "bridge"
    if Path(args.bridge_home).expanduser().resolve().is_relative_to(production_home.resolve()):
        ap.error("测试必须使用生产桥目录之外的 --bridge-home")
    env = dict(os.environ)
    env["COACH_BRIDGE_HOME"] = args.bridge_home
    env["BRIDGE_PORT"] = str(args.port)
    out_path = Path(args.out)
    started = datetime.now().astimezone()
    bridge = subprocess.Popen([PY, str(BRIDGE_DIR / "subscription_bridge.py")], env=env)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "a", encoding="utf-8") as f:      # 起停时间要能进交付报告
        f.write(json.dumps({"stage": "bridge", "event": "start", "at": started.isoformat(timespec="seconds"),
                            "pid": bridge.pid, "port": args.port}, ensure_ascii=False) + "\n")
    print(f"测试桥 起 {started:%H:%M:%S}（pid {bridge.pid}，端口 {args.port}）", flush=True)
    try:
        for _ in range(150):
            if healthy(args.port):
                break
            if bridge.poll() is not None:
                print("桥没起来", flush=True)
                return 2
            time.sleep(0.1)
        else:
            print("桥 15 秒内没就绪", flush=True)
            return 2

        rc_all = 0
        for stage in [s.strip() for s in args.stages.split(",") if s.strip()]:
            n = {"claude": args.claude_n, "codex": args.codex_n,
                 "codex-stream": 3, "cancel": args.cancel_n}[stage]
            cmd = [PY, str(HERE / "live_probe.py"), "--stage", stage, "-n", str(n),
                   "--port", str(args.port), "--out", args.out]
            if args.effort and stage == "claude":
                cmd += ["--effort", args.effort]
            print(f"\n===== {stage} n={n} =====", flush=True)
            rc = subprocess.run(cmd).returncode
            if rc == 3:
                print("推迟：DeepTutor 刚有请求，本阶段未跑", flush=True)
                rc_all = 3
                continue
            rc_all = rc_all or rc
        return rc_all
    finally:
        bridge.terminate()
        try:
            bridge.wait(timeout=10)
        except subprocess.TimeoutExpired:
            bridge.kill()
        stopped = datetime.now().astimezone()
        with open(out_path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"stage": "bridge", "event": "stop", "at": stopped.isoformat(timespec="seconds"),
                                "pid": bridge.pid, "port": args.port}, ensure_ascii=False) + "\n")
        print(f"测试桥 停 {stopped:%H:%M:%S}（pid {bridge.pid}）", flush=True)


if __name__ == "__main__":
    sys.exit(main())
