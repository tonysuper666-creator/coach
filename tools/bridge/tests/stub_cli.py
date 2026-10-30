#!/usr/bin/env python3
"""桩 CLI：在故障注入回归里顶掉真的 claude / codex（不花额度）。

放法：把本文件软链成 <stubdir>/claude 与 <stubdir>/codex，桥通过 BRIDGE_CHILD_PATH 指到 <stubdir> 就会用到。
行为由 STUB_DIR/mode 文件控制（每个请求前改它，就能逐项注入故障）：
  ok / slow         正常出字（slow 每段慢一点，用来测排队）
  kill9             吐 2 段后 kill -9 自己（中途 CLI 死掉）
  hang              一个字都不吐就挂住（首字超时）
  hang_after        吐 2 段后挂住（出字后卡住）
  refuse            安全拦截（is_error + "can't respond to this message"）
  maxtok            max_output_tokens 截断（is_error + terminal_reason=api_error + api_error=max_output_tokens）
  rate              正常出字，但先发一条 rate_limit_event（utilization 0.95）
事件形状照 claude 2.1.296 的 stream-json：system/init.model、assistant.message.model、content_block_delta、
result{is_error, terminal_reason, api_error, stop_reason, modelUsage, usage}。
"""
from __future__ import annotations

import json
import os
import signal
import sys
import time

STUB_DIR = os.environ.get("STUB_DIR", "/tmp/coach-bridge-stub")
SERVED = os.environ.get("STUB_SERVED", "claude-sonnet-5-5-stub")


def mode() -> str:
    try:
        return open(os.path.join(STUB_DIR, "mode"), encoding="utf-8").read().strip() or "ok"
    except OSError:
        return "ok"


def record(kind: str) -> None:
    """记并发重叠：S 开始 / E 结束，测试据此算同一时刻最多几个桩在跑。"""
    try:
        with open(os.path.join(STUB_DIR, "overlap.log"), "a", encoding="utf-8") as f:
            f.write(f"{kind} {os.getpid()} {time.time():.6f}\n")
    except OSError:
        pass


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def claude_prompt() -> str:
    data = sys.stdin.buffer.read().decode("utf-8", "replace")
    return data


def claude(m: str) -> int:
    record("S")
    try:
        if m == "hang":
            time.sleep(300)
            return 0
        delay = 1.2 if m == "slow" else 0.05
        emit({"type": "system", "subtype": "init", "model": SERVED, "session_id": "stub"})
        emit({"type": "stream_event",
              "event": {"type": "message_start", "message": {"model": SERVED, "role": "assistant"}}})
        if m == "rate":
            emit({"type": "rate_limit_event",
                  "rate_limit_info": {"status": "allowed", "utilization": 0.95, "rateLimitType": "five_hour"}})
        chunks = ["这是桩 CLI 的第一段。", "第二段，用来模拟真流式。", "第三段结束。"]
        if m == "protocol":
            chunks = ["正常正文。\n", "placeholder: internal\n", "malformed: internal\n",
                      "tool_", "call: internal\n",
                      '{"x_coach":{"status":"complete"}}\n', '{"answer":"raw internal JSON"}\n',
                      "【需查原文:内部请求】\n", "正常结尾。\n",
                      "<tool_calls>internal</tool_calls>\n"]
        if m == "refuse":            # 安全拦截：一个字都不吐，直接给 result
            chunks = []
        for i, c in enumerate(chunks):
            time.sleep(delay)
            emit({"type": "stream_event",
                  "event": {"type": "content_block_delta", "index": 0,
                            "delta": {"type": "text_delta", "text": c}}})
            emit({"type": "assistant", "message": {"model": SERVED, "role": "assistant",
                                                   "content": [{"type": "text", "text": c}]}})
            if m == "kill9" and i == 1:
                os.kill(os.getpid(), signal.SIGKILL)
            if m == "hang_after" and i == 1:
                time.sleep(300)
        full = "".join(chunks)
        if m == "maxtok":
            emit({"type": "result", "subtype": "error", "is_error": True, "result": full,
                  "terminal_reason": "api_error", "api_error": "max_output_tokens",
                  "stop_reason": "stop_sequence", "modelUsage": {SERVED: {"outputTokens": 12}},
                  "usage": {"input_tokens": 10, "output_tokens": 12}})
            return 1
        if m == "refuse":
            emit({"type": "result", "subtype": "error", "is_error": True,
                  "result": "I can't respond to this message [reasoning_extraction]",
                  "terminal_reason": "refusal", "stop_reason": "stop_sequence",
                  "modelUsage": {SERVED: {"outputTokens": 0}},
                  "usage": {"input_tokens": 10, "output_tokens": 0}})
            return 0
        emit({"type": "result", "subtype": "success", "is_error": False, "result": full,
              "terminal_reason": "success", "stop_reason": "end_turn",
              "modelUsage": {SERVED: {"outputTokens": 30}},
              "usage": {"input_tokens": 10, "output_tokens": 30,
                        "cache_read_input_tokens": 5, "cache_creation_input_tokens": 2}})
        return 0
    finally:
        record("E")


def codex(args: list[str], m: str) -> int:
    record("S")
    try:
        outfile = None
        if "-o" in args:
            outfile = args[args.index("-o") + 1]
        sys.stdin.buffer.read()
        if m == "codex_fail":
            sys.stderr.write("stub codex: simulated failure\n")
            return 1
        text = "codex 桩的回答：由兜底模型生成。"
        if outfile:
            with open(outfile, "w", encoding="utf-8") as f:
                f.write(text + "\n")
        emit({"type": "thread.started", "thread_id": "stub"})
        emit({"type": "turn.completed",
              "usage": {"input_tokens": 7, "output_tokens": 9}})
        return 0
    finally:
        record("E")


def main() -> int:
    name = os.path.basename(sys.argv[0])
    args = sys.argv[1:]
    m = mode()
    if name == "claude":
        if args[:2] == ["auth", "status"]:
            print(json.dumps({"loggedIn": True, "authMethod": "claude.ai", "subscriptionType": "max"}))
            return 0
        claude_prompt()
        return claude(m)
    if name == "codex":
        if args[:2] == ["login", "status"]:
            print("Logged in using ChatGPT")
            return 0
        return codex(args, m)
    print(f"stub_cli: 不认识的命令 {name}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
