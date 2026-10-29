#!/bin/zsh
# Coach 8791 订阅桥副本调用 Codex / Grok / Antigravity 时走这里：PATH 最前面的 bin/ 里放着指向本文件的同名链接。
# 真正的 CLI 在沙箱里运行（SBPL 见 sandbox/agent_sandbox.sb.in），读不到课程库、账本与私人目录，也改不了 Coach 的运行数据。
# 沙箱配置由 subscription_bridge.py 启动时渲染到 $COACH_BRIDGE_HOME/work/agent_sandbox.sb，并用 COACH_SANDBOX_PROFILE 指过来：
# 仓库里不留本机绝对路径（DESIGN §12.1）。
name=${0:t}
case $name in
  codex) real=$HOME/.local/bin/codex ;;
  agy)   real=$HOME/.local/bin/agy ;;
  grok)  real=$HOME/.grok/bin/grok ;;
  *) echo "sandboxed_agent: 不认识的命令 $name" >&2; exit 2 ;;
esac

profile=${COACH_SANDBOX_PROFILE:-$HOME/Coach/bridge/work/agent_sandbox.sb}
if [[ ! -r $profile ]]; then
  echo "sandboxed_agent: 找不到沙箱配置 $profile（先启动 subscription_bridge.py，它会渲染一份）" >&2
  exit 2
fi

# agy 的子 shell 不设区域时中文文件名会显示成问号。
export LANG=${LANG:-en_US.UTF-8}
exec /usr/bin/sandbox-exec -f "$profile" $real "$@"
