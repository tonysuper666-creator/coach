#!/usr/bin/env python3
"""起一台 8791 桥副本 → 跑 live 探针 → 关掉。只用于 M0 验收，不是常驻服务。

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
import time
import urllib.request
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--stages", default="claude")
    ap.add_argument("--claude-n", type=int, default=20)
    ap.add_argument("--codex-n", type=int, default=5)
    ap.add_argument("--cancel-n", type=int, default=2)
    ap.add_argument("--port", type=int, default=8791)
    ap.add_argument("--out", default="/tmp/coach-bridge-live.jsonl")
    ap.add_argument("--bridge-home", default=str(Path.home() / "Coach" / "bridge"))
    ap.add_argument("--effort", default=None, help="claude 阶段的 X-Coach-Effort")
    args = ap.parse_args()

    env = dict(os.environ)
    env.setdefault("COACH_BRIDGE_HOME", args.bridge_home)
    env["BRIDGE_PORT"] = str(args.port)
    bridge = subprocess.Popen([PY, str(BRIDGE_DIR / "subscription_bridge.py")], env=env)
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


if __name__ == "__main__":
    sys.exit(main())
