#!/bin/zsh
# Coach 8791 订阅桥副本调用 Codex / Grok / Antigravity 时走这里：PATH 最前面的 var/bin 里放着指向本文件的同名链接。
# 真正的 CLI 在 sandbox/agent_sandbox.sb 里运行，读不到课程库、账本与私人目录，也改不了 Coach 的运行数据。
# 来源：~/DeepTutor/tools/sandboxed_agent.sh，按 ADR-0002(1) 复制进 Coach 并改为自带路径。
name=${0:t}
case $name in
  codex) real=$HOME/.local/bin/codex ;;
  agy)   real=$HOME/.local/bin/agy ;;
  grok)  real=$HOME/.grok/bin/grok ;;
  *) echo "sandboxed_agent: 不认识的命令 $name" >&2; exit 2 ;;
esac

# agy 的子 shell 不设区域时中文文件名会显示成问号。
export LANG=${LANG:-en_US.UTF-8}
exec /usr/bin/sandbox-exec -f "${0:A:h}/agent_sandbox.sb" $real "$@"
