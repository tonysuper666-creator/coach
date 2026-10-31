#!/bin/zsh
# 启动 Coach 的订阅桥副本（8791）。前台运行，Ctrl-C 停；M0 不装 LaunchAgent（DESIGN §4.5）。
#   tools/bridge/run.sh                 # 默认 COACH_BRIDGE_HOME=~/Coach/bridge
#   COACH_BRIDGE_HOME=/tmp/bridge-x tools/bridge/run.sh   # 换目录（回归测试用）
set -euo pipefail

HERE=${0:A:h}
PY="$HERE/.venv/bin/python"
if [[ ! -x $PY ]]; then
  print -u2 "缺少 $PY；先跑：python3 -m venv $HERE/.venv && $HERE/.venv/bin/python -m pip install -r $HERE/requirements.txt"
  exit 1
fi

export COACH_BRIDGE_HOME=${COACH_BRIDGE_HOME:-$HOME/Coach/bridge}
export BRIDGE_PORT=${BRIDGE_PORT:-8791}
# 期中前（≤2026-11-02 14:25）Claude 副本并发 1、后台全部走 Codex（ADR-0002 裁决）；期中后按需改这两项。
export BRIDGE_CLAUDE_CONCURRENCY=${BRIDGE_CLAUDE_CONCURRENCY:-1}
export BRIDGE_BACKGROUND_BACKEND=${BRIDGE_BACKGROUND_BACKEND:-codex}
export BRIDGE_AGY_ENABLED=${BRIDGE_AGY_ENABLED:-0}

mkdir -p "$COACH_BRIDGE_HOME/logs"
print "coach-bridge $BRIDGE_PORT → COACH_BRIDGE_HOME=$COACH_BRIDGE_HOME  log=$COACH_BRIDGE_HOME/logs/bridge.log"
exec "$PY" "$HERE/subscription_bridge.py"
