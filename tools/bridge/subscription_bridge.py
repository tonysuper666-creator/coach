#!/usr/bin/env python3
"""Coach 订阅桥副本（8791）。

来源：~/DeepTutor/tools/subscription_bridge.py（8790，DeepTutor 在用，一个字都不动）。
本文件是 Coach 仓库里的副本，按 ADR-0002 决定 1 的九项改写：

(1) 路径迁出：所有运行文件在 COACH_BRIDGE_HOME（默认 ~/Coach/bridge）：日志、CLI 的 cwd、临时文件。
    不再引用 ~/DeepTutor；沙箱包装与 .sb 复制在 sandbox/ 并重审过白名单；用自己的 venv。
(2) 显式终态：[DONE] 前最后一帧带 x_coach={status, reason, finish_reason, served_by, fallback,
    physical_calls, request_id}；中途失败不再发 finish_reason=stop。
(3) served_by：取 CLI 自报的实际模型（system/init.model、assistant.message.model、result.modelUsage），
    写进回包 model、x_coach，非流式再加 X-Served-Model 头。
(4) X-Coach-Fallback: off|on —— 默认 off（Coach 不开隐式兜底，兜底由 Coach 显式发起并计入预算）。
(5) 回显 X-Request-Id 并写日志；按请求设推理强度（X-Coach-Effort / reasoning_effort）。
(6) 超时从拿到信号量之后起算；排队时长（queue_ms）单独记录。
(7) 并发：Claude 上限可配（BRIDGE_CLAUDE_CONCURRENCY，期中前默认 1），后台最多 1；
    rate_limit_event 仅作可观察信号，不以本地阈值拦截。
(8) /v1/models 标 true_stream；Codex 为 false（exec --json 没有 delta）；agy 默认关；Grok 未注册。
(9) 桩 CLI 故障注入回归见 tests/fault_injection.py，live 探针见 tests/live_probe.py。

日志只记长度、耗时与终态，不记提示词与答案（与 8790 一致）。
启动：tools/bridge/run.sh（前台，端口 8791，不装 LaunchAgent）。
"""
from __future__ import annotations

import asyncio
import errno
import json
import os
import re
import secrets
import signal
import tempfile
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, AsyncIterator

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse

# ---------- 路径与配置（全部在 Coach 目录里） ----------

HOST = "127.0.0.1"
PORT = int(os.environ.get("BRIDGE_PORT", "8791"))
BRIDGE_HOME = Path(os.environ.get("COACH_BRIDGE_HOME", str(Path.home() / "Coach" / "bridge"))).expanduser()
WORK_DIR = BRIDGE_HOME / "work"
TMP_DIR = WORK_DIR / "tmp"
CLAUDE_CWD = WORK_DIR / "claude-cwd"        # claude 的工作目录：空目录，它没有文件可看
AGY_CWD = WORK_DIR / "agy-cwd"
CODEX_CWD = WORK_DIR / "codex-cwd"
LOG_FILE = BRIDGE_HOME / "logs" / "bridge.log"
BRIDGE_DIR = Path(__file__).resolve().parent
SANDBOX_BIN = BRIDGE_DIR / "bin"           # 里面是指向 sandbox/sandboxed_agent.sh 的 codex/agy/grok 链接

TIMEOUT = 600                               # 单次子进程上限（秒），最后一道保险
CLAUDE_TIMEOUT = float(os.environ.get("BRIDGE_CLAUDE_TIMEOUT", "90"))    # 出字前的首字上限
STREAM_TOTAL_TIMEOUT = float(os.environ.get("BRIDGE_STREAM_TOTAL_TIMEOUT", "180"))  # 出字后的总上限
STALL_TIMEOUT = float(os.environ.get("BRIDGE_STALL_TIMEOUT", "30"))      # 已有文字后多久没有新事件算卡住
CLAUDE_EFFORT = os.environ.get("BRIDGE_CLAUDE_EFFORT", "medium")         # 默认推理强度（可按请求覆盖）
CODEX_EFFORT = os.environ.get("BRIDGE_CODEX_EFFORT", "high")
AGY_TIMEOUT = float(os.environ.get("BRIDGE_AGY_TIMEOUT", "120"))
CODEX_TIMEOUT = float(os.environ.get("BRIDGE_CODEX_TIMEOUT", "150"))
SYS_FILE_MIN = 200_000                      # system 提示超过这个字节数改走文件，免得顶到命令行上限
DEFAULT_SYSTEM = "You are a helpful assistant."
NO_TOOLS_NOTE = ("Answer directly in plain text. Do not use any built-in tools "
                 "(shell, files, browser, web search); you have no access to the user's machine.")

# 子进程 PATH：Coach 自己的沙箱包装目录在前，然后是 CLI 本体。
# BRIDGE_CHILD_PATH 供故障注入回归使用（把桩 CLI 放进去，顶掉真 CLI）。
_DEFAULT_CHILD_PATH = ":".join([str(SANDBOX_BIN), str(Path.home() / ".local/bin"),
                                str(Path.home() / ".nvm/versions/node/v22.23.1/bin"), "/opt/homebrew/bin"])
CHILD_PATH = os.environ.get("BRIDGE_CHILD_PATH", _DEFAULT_CHILD_PATH)

# 并发：期中前（≤2026-11-02 14:25）Claude 副本上限 1；之后按 ADR 调 2。
CLAUDE_CONCURRENCY = max(1, int(os.environ.get("BRIDGE_CLAUDE_CONCURRENCY", "1")))
CODEX_CONCURRENCY = max(1, int(os.environ.get("BRIDGE_CODEX_CONCURRENCY", "2")))
AGY_CONCURRENCY = max(1, int(os.environ.get("BRIDGE_AGY_CONCURRENCY", "2")))
BACKGROUND_SLOTS = max(1, int(os.environ.get("BRIDGE_BACKGROUND_SLOTS", "1")))
# 后台生成全部走 Codex（期中保护）。设为空串可关掉这条转派。
BACKGROUND_BACKEND = os.environ.get("BRIDGE_BACKGROUND_BACKEND", "codex")
BACKGROUND_MODEL = os.environ.get("BRIDGE_BACKGROUND_MODEL", "gpt-6.1-sol")
AGY_ENABLED = os.environ.get("BRIDGE_AGY_ENABLED", "0") == "1"     # agy 默认关（条款风险）
HEALTH_CACHE_SEC = float(os.environ.get("BRIDGE_HEALTH_CACHE_SEC", "60"))
# Test-only budget (TON-1128); unmarked learner traffic is always counted but never capped.
STATE_DIR = BRIDGE_HOME / "state"
DAILY_FILE = STATE_DIR / "daily.json"
SANDBOX_TEMPLATE = BRIDGE_DIR / "sandbox" / "agent_sandbox.sb.in"
SANDBOX_PROFILE = WORK_DIR / "agent_sandbox.sb"

REFUSAL_FALLBACK = "gpt-6.1-sol"            # 仅当 X-Coach-Fallback: on 时才会被用到


@dataclass(frozen=True)
class ModelSpec:
    id: str
    backend: str          # claude / codex / agy
    true_stream: bool     # 是否真流式（CLI 边写边给 delta）


def build_registry() -> dict[str, ModelSpec]:
    reg = {
        "claude-sonnet-5-5": ModelSpec("claude-sonnet-5-5", "claude", True),
        "gpt-6.1-sol": ModelSpec("gpt-6.1-sol", "codex", False),
        "gpt-6-sol": ModelSpec("gpt-6-sol", "codex", False),
    }
    if AGY_ENABLED:
        for mid in ("gemini-4-argon-high", "gemini-4-argon-medium"):
            reg[mid] = ModelSpec(mid, "agy", False)
    # Grok：未 live 实测前不注册为导师（ADR-0002(8)）。
    return reg


MODELS = build_registry()

for _d in (WORK_DIR, TMP_DIR, CLAUDE_CWD, AGY_CWD, CODEX_CWD, LOG_FILE.parent, STATE_DIR):
    try:
        _d.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass

EFFORT_VALUES = {"minimal", "low", "medium", "high", "xhigh", "max"}
CLAUDE_EFFORT_VALUES = {"low", "medium", "high", "xhigh", "max"}


def render_sandbox_profile() -> Path:
    """把 sandbox/agent_sandbox.sb.in 里的 @@HOME@@ 换成真实主目录，渲染到 bridge 的 work 目录。

    仓库里不留本机绝对路径（DESIGN §12.1）；沙箱包装用 COACH_SANDBOX_PROFILE 指过来。
    """
    try:
        tpl = SANDBOX_TEMPLATE.read_text(encoding="utf-8")
    except OSError:
        return SANDBOX_PROFILE
    body = tpl.replace("@@HOME@@", str(Path.home()))
    try:
        SANDBOX_PROFILE.parent.mkdir(parents=True, exist_ok=True)
        if not SANDBOX_PROFILE.exists() or SANDBOX_PROFILE.read_text(encoding="utf-8") != body:
            tmp = SANDBOX_PROFILE.with_suffix(".tmp")
            tmp.write_text(body, encoding="utf-8")
            tmp.replace(SANDBOX_PROFILE)
    except OSError:
        pass
    return SANDBOX_PROFILE


render_sandbox_profile()      # 定义之后立刻渲染一份，供 codex/agy 的沙箱包装用


def _today() -> str:
    return datetime.now().astimezone().date().isoformat()


def read_daily() -> dict:
    try:
        data = json.loads(DAILY_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    if not isinstance(data, dict) or data.get("date") != _today():
        data = {"date": _today(), "claude_calls": 0}
    return data


def daily_used() -> int:
    return int(read_daily().get("test_claude_calls") or 0)


def reserve_claude_call(test_traffic: bool = False) -> None:
    """Count every physical call; only explicitly marked tests consume the test budget."""
    data = read_daily()
    used = int(data.get("test_claude_calls") or 0)
    data["claude_calls"] = int(data.get("claude_calls") or 0) + 1
    if test_traffic:
        data["test_claude_calls"] = used + 1
    # A failed accounting write must not silently permit uncounted test calls.
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = DAILY_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    tmp.replace(DAILY_FILE)


# ---------- 错误与终态 ----------

class BridgeError(Exception):
    def __init__(self, status: int, message: str, etype: str = "api_error",
                 code: str | None = None, param: str | None = None,
                 reason: str | None = None, started: bool = False):
        super().__init__(message)
        self.status, self.message, self.etype = status, message, etype
        self.code, self.param = code, param
        self.reason = reason or ("timeout" if code == "timeout" else "cli_exit")
        self.started = started     # 是否已经向客户端发过正文（决定 truncated 还是 failed）


class Refused(BridgeError):
    """Claude Code 的安全拦截拒绝回答这条请求。"""


class ClientGone(Exception):
    """调用方在等待期间断开了连接。"""


def err_response(e: BridgeError, *, x_coach: dict | None = None, headers: dict | None = None) -> JSONResponse:
    body: dict[str, Any] = {"error": {"message": e.message, "type": e.etype,
                                      "param": e.param, "code": e.code}}
    if x_coach:
        body["x_coach"] = x_coach
    return JSONResponse(body, status_code=e.status, headers=headers or {})


# ---------- 日志 ----------

def write_log(**kw: Any) -> None:
    """一行一个请求：时间 rid 请求模型 实际模型 流式 后台 强度 输入/输出字符 排队 首字 总时长 终态 物理调用。"""
    line = (f"{datetime.now().astimezone().isoformat(timespec='seconds')} "
            f"rid={kw.get('request_id') or '-'} model={str(kw.get('model'))[:48]} "
            f"served={str(kw.get('served_by') or '-')[:48]} stream={int(bool(kw.get('stream')))} "
            f"traffic={kw.get('traffic', 'user')} bg={int(bool(kw.get('background')))} effort={kw.get('effort') or '-'} "
            f"in={kw.get('in_chars', 0)} out={kw.get('out_chars', 0)} "
            f"queue={kw.get('queue_ms', 0)}ms ttft={kw.get('ttft_ms') if kw.get('ttft_ms') is not None else '-'}ms "
            f"total={kw.get('total_ms', 0)}ms status={kw.get('status')} reason={kw.get('reason') or '-'} "
            f"finish={kw.get('finish_reason') or '-'} calls={kw.get('physical_calls', 0)} "
            f"fallback={int(bool(kw.get('fallback')))}"
            + (f" note={kw['note']}" if kw.get("note") else "")
            + "\n")
    try:
        if os.environ.get("BRIDGE_LOG_STDOUT") == "1":
            # Managed Ktor drains stdout into its daily, 14-day retained bridge log.
            print(line, end="", flush=True)
            return
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line)
    except OSError:
        pass


# ---------- 消息 → 提示词 ----------

def text_of(content: Any) -> str:
    """content 可能是字符串或分段列表；文本段保留，图片等换成占位。"""
    if content is None:
        return ""
    if isinstance(content, str):
        s = content
    elif isinstance(content, list):
        parts = []
        for p in content:
            if isinstance(p, str):
                parts.append(p)
            elif isinstance(p, dict):
                t = p.get("type")
                if t in ("text", "input_text", "output_text") or (t is None and "text" in p):
                    parts.append(str(p.get("text", "")))
                elif t in ("image_url", "image", "input_image"):
                    parts.append("[image omitted]")
                else:
                    parts.append(f"[{t or 'part'} omitted]")
        s = "\n".join(parts)
    else:
        s = str(content)
    return s.replace("\x00", "")  # argv 里不能有 NUL


def args_str(a: Any) -> str:
    if isinstance(a, str):
        return a
    return json.dumps(a if a is not None else {}, ensure_ascii=False)


def render_transcript(msgs: list[dict]) -> str:
    out = ["Below is the conversation so far. Write ONLY the next assistant message "
           "(no role label, no quoting of the transcript).", ""]
    names = {c.get("id"): (c.get("function") or {}).get("name")  # tool 消息通常不带 name，靠 id 回查
             for m in msgs for c in (m.get("tool_calls") or []) if isinstance(c, dict)}
    for m in msgs:
        role = m.get("role")
        content = text_of(m.get("content"))
        if role == "assistant":
            calls = m.get("tool_calls") or []
            if content.strip():
                out.append(f"[assistant]\n{content}\n")
            if calls:
                items = []
                for c in calls:
                    fn = c.get("function") or {}
                    try:
                        args = json.loads(args_str(fn.get("arguments")) or "{}")
                    except ValueError:
                        args = args_str(fn.get("arguments"))
                    items.append({"name": fn.get("name"), "arguments": args})
                ids = ", ".join(str(c.get("id")) for c in calls)
                out.append("[assistant]\n<tool_calls>" + json.dumps(items, ensure_ascii=False)
                           + f"</tool_calls>\n(tool call ids, in order: {ids})\n")
        elif role in ("tool", "function"):
            tid = m.get("tool_call_id")
            head = f"[tool result id={tid} name={m.get('name') or names.get(tid)}]"
            out.append(f"{head}\n{content}\n")
        else:
            out.append(f"[{role or 'user'}]\n{content}\n")
    out.append("Now write the next assistant message.")
    return "\n".join(out)


def tool_specs(tools: list) -> list[dict]:
    specs = []
    for t in tools:
        if not isinstance(t, dict):
            continue
        fn = t.get("function") if isinstance(t.get("function"), dict) else t
        if fn.get("name"):
            specs.append({"name": fn["name"], "description": fn.get("description", ""),
                          "parameters": fn.get("parameters") or {"type": "object", "properties": {}}})
    return specs


def tools_instruction(specs: list[dict], choice: Any) -> str:
    lines = ["# Tools",
             "You may call the tools listed below. Each entry has a name, a description and a JSON Schema for its parameters.",
             "<tools>"]
    lines += [json.dumps(s, ensure_ascii=False) for s in specs]
    lines += ["</tools>", "",
              "# Tool-call protocol (strict)",
              "- To call tools, your ENTIRE reply must be exactly one block and nothing else:",
              '  <tool_calls>[{"name": "<tool name>", "arguments": {<JSON object matching the parameters schema>}}]</tool_calls>',
              "- The block holds a JSON array. Put several objects in it to call several tools at once.",
              "  No text before or after it, no markdown fences, no explanation.",
              "- If no tool is needed, answer normally in plain text and do NOT output a <tool_calls> block.",
              "- Never use any other tool-call format (no <tool_call>/<tool_name> XML tags, no function-call syntax),",
              "  and never append a tool-call block to a plain-text answer.",
              '- Tool results come back in the conversation as "[tool result ...]" entries. '
              "Once you have what you need, give the final answer in plain text.",
              "- Use only the tool names listed above; never invent tools or parameters."]
    if choice == "required":
        lines.append("- You MUST call at least one tool in this reply.")
    elif isinstance(choice, dict) and (choice.get("function") or {}).get("name"):
        lines.append(f'- You MUST call the tool "{choice["function"]["name"]}" in this reply.')
    return "\n".join(lines)


def json_instruction(rf: Any, with_tools: bool) -> str:
    if not isinstance(rf, dict) or rf.get("type") not in ("json_object", "json_schema"):
        return ""
    s = ("Output exactly one valid JSON object and nothing else: no markdown fences, "
         "no commentary before or after it.")
    if with_tools:
        s += " (This does not apply when you are calling a tool as described above.)"
    schema = (rf.get("json_schema") or {}).get("schema") if rf.get("type") == "json_schema" else None
    if schema:
        s += "\nThe object must conform to this JSON Schema:\n" + json.dumps(schema, ensure_ascii=False)
    return s


class Spec:
    system: str
    prompt: str
    tool_names: set[str]
    json_mode: bool


def build_spec(body: dict) -> Spec:
    msgs = body.get("messages")
    if not isinstance(msgs, list) or not msgs:
        raise BridgeError(400, "messages 必须是非空数组", "invalid_request_error", param="messages")
    msgs = [m for m in msgs if isinstance(m, dict)]
    sys_parts = [text_of(m.get("content")) for m in msgs if m.get("role") in ("system", "developer")]
    rest = [m for m in msgs if m.get("role") not in ("system", "developer")]
    if not rest:
        raise BridgeError(400, "messages 里没有 user 消息", "invalid_request_error", param="messages")

    system = "\n\n".join(p for p in sys_parts if p.strip()) or DEFAULT_SYSTEM
    choice = body.get("tool_choice")
    specs = tool_specs(body["tools"]) if isinstance(body.get("tools"), list) and choice != "none" else []
    if specs:
        system += "\n\n" + tools_instruction(specs, choice)
    jin = json_instruction(body.get("response_format"), bool(specs))
    if jin:
        system += "\n\n" + jin

    if len(rest) == 1 and rest[0].get("role") == "user":
        prompt = text_of(rest[0].get("content"))
    else:
        prompt = render_transcript(rest)
    if not prompt.strip():
        raise BridgeError(400, "提示词为空", "invalid_request_error", param="messages")

    s = Spec()
    s.system, s.prompt = system, prompt
    s.tool_names = {x["name"] for x in specs}
    s.json_mode = bool(jin)
    return s


# ---------- 并发闸门 ----------

class Gate:
    """带优先级的并发闸门：前台可以插到后台前面（期中保护：他的复习不能被 Coach 的后台任务排队）。"""

    def __init__(self, limit: int):
        self.limit = limit
        self.active = 0
        self.fg_waiting = 0
        self._cv = asyncio.Condition()

    async def acquire(self, background: bool = False) -> None:
        async with self._cv:
            if not background:
                self.fg_waiting += 1
            try:
                while True:
                    if self.active < self.limit and (not background or self.fg_waiting == 0):
                        self.active += 1
                        return
                    await self._cv.wait()
            finally:
                if not background:
                    self.fg_waiting -= 1
                    self._cv.notify_all()

    async def release(self) -> None:
        async with self._cv:
            self.active -= 1
            self._cv.notify_all()

    def snapshot(self) -> dict:
        return {"limit": self.limit, "active": self.active, "foreground_waiting": self.fg_waiting}


GATES = {"claude": Gate(CLAUDE_CONCURRENCY), "codex": Gate(CODEX_CONCURRENCY), "agy": Gate(AGY_CONCURRENCY)}
BG_SLOTS = asyncio.Semaphore(BACKGROUND_SLOTS)
BG_ACTIVE = 0        # /health 用的当前后台请求数


@dataclass
class RateLimit:
    utilization: float | None = None
    status: str | None = None
    resets_at: Any = None
    at: float | None = None

    def snapshot(self) -> dict:
        return {"utilization": self.utilization, "status": self.status,
                "resets_at": self.resets_at,
                "at": datetime.fromtimestamp(self.at).astimezone().isoformat(timespec="seconds") if self.at else None,
                "background_paused": False, "all_paused": False, "pause_at": None}


RATE_LIMIT = RateLimit()


def update_rate_limit(ev: dict) -> None:
    """从 claude 的 rate_limit_event 里取 utilization / status（字段名在 CLI 里可能嵌一层，递归找）。"""
    found: dict[str, Any] = {}

    def walk(node: Any, depth: int = 0) -> None:
        if depth > 4 or not isinstance(node, dict):
            return
        if "utilization" in node and "utilization" not in found:
            found["utilization"] = node.get("utilization")
        if "status" in node and isinstance(node.get("status"), str) and "status" not in found:
            found["status"] = node.get("status")
        if ("resets_at" in node or "resetsAt" in node) and "resets_at" not in found:
            found["resets_at"] = node.get("resets_at", node.get("resetsAt"))
        for v in node.values():
            walk(v, depth + 1)

    walk(ev)
    if "utilization" in found:
        try:
            RATE_LIMIT.utilization = float(found["utilization"])
        except (TypeError, ValueError):
            pass
    if "status" in found:
        RATE_LIMIT.status = str(found["status"])
    if "resets_at" in found:
        RATE_LIMIT.resets_at = found["resets_at"]
    RATE_LIMIT.at = time.time()


# ---------- 请求上下文 ----------

@dataclass
class ReqCtx:
    request_id: str
    model: str
    spec: ModelSpec
    body: dict
    spec_data: Spec
    stream: bool
    background: bool
    fallback_enabled: bool
    effort: str | None
    t_req: float
    in_chars: int
    exact_model: bool = False
    test_traffic: bool = False
    # 运行中更新
    served_by: str | None = None
    t_acq: float | None = None
    ttft_ms: float | None = None
    physical_calls: int = 0
    fallback: bool = False
    routed_from: str | None = None   # 后台转派时的原请求模型
    out_chars: int = 0

    def queue_ms(self) -> float:
        if self.t_acq is None:
            return 0.0
        return round((self.t_acq - self.t_req) * 1000, 1)

    def total_ms(self) -> float:
        return round((time.monotonic() - self.t_req) * 1000, 1)


@dataclass
class Outcome:
    status: str                     # complete / truncated / failed
    reason: str | None              # timeout / cli_exit / max_output_tokens / client_gone
    finish_reason: str              # stop / length / error / tool_calls
    served_by: str
    text: str = ""
    usage: dict = field(default_factory=lambda: {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0})
    started: bool = False
    detail: str = ""

    def x_coach(self, req: ReqCtx) -> dict:
        return {"status": self.status, "reason": self.reason, "finish_reason": self.finish_reason,
                "served_by": self.served_by, "fallback": req.fallback,
                "physical_calls": req.physical_calls, "request_id": req.request_id,
                "requested_model": req.model, "queue_ms": req.queue_ms(),
                "ttft_ms": req.ttft_ms, "elapsed_ms": req.total_ms()}


# ---------- 子进程 ----------

CHILDREN: set[asyncio.subprocess.Process] = set()


def child_env(backend: str) -> dict[str, str]:
    env = dict(os.environ)
    env["PATH"] = CHILD_PATH + ":" + env.get("PATH", "")
    env["LANG"] = "en_US.UTF-8"
    env["TMPDIR"] = str(TMP_DIR)
    env["COACH_SANDBOX_PROFILE"] = str(SANDBOX_PROFILE)
    if backend == "claude":
        # 只走订阅登录：从别处（如 Claude Code 桌面端）继承来的 ANTHROPIC_* / CLAUDE_* 会改线路或改模式，一律去掉。
        for k in list(env):
            if k.startswith(("ANTHROPIC", "CLAUDE")) and k != "CLAUDE_CODE_OAUTH_TOKEN":
                del env[k]
    return env


def kill_group(proc: asyncio.subprocess.Process) -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)  # start_new_session 之后 pgid == pid，连孙进程一起杀
    except (ProcessLookupError, PermissionError):
        pass


async def spawn(argv: list[str], cwd: Path, env: dict[str, str],
                stdin_pipe: bool = False) -> asyncio.subprocess.Process:
    try:
        proc = await asyncio.create_subprocess_exec(
            *argv, cwd=str(cwd), env=env,
            stdin=asyncio.subprocess.PIPE if stdin_pipe else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            start_new_session=True, limit=32 * 1024 * 1024)
    except OSError as e:
        if e.errno == errno.E2BIG:
            raise BridgeError(413, "提示词太长，超出该后端命令行长度上限（约 1 MB）",
                              "invalid_request_error", "context_length_exceeded")
        raise BridgeError(502, f"无法启动 CLI：{e.strerror}")
    CHILDREN.add(proc)
    return proc


async def reap(proc: asyncio.subprocess.Process) -> int:
    """杀掉整个进程组并等到它真的退出（取消后 2 秒内不留残留进程）。"""
    kill_group(proc)
    try:
        rc = await asyncio.wait_for(proc.wait(), 2.0)
    except asyncio.TimeoutError:
        rc = -9
    CHILDREN.discard(proc)
    return rc


async def run_cli(argv: list[str], cwd: Path, env: dict[str, str], stdin_data: bytes | None = None,
                  timeout: float = TIMEOUT) -> tuple[int, str, str]:
    proc = await spawn(argv, cwd, env, stdin_pipe=stdin_data is not None)
    try:
        out, err = await asyncio.wait_for(proc.communicate(stdin_data), timeout)
    except asyncio.TimeoutError:
        await reap(proc)
        raise BridgeError(504, f"CLI 超过 {timeout:.0f} 秒未返回，已终止", "timeout", "timeout",
                          reason="timeout")
    except BaseException:  # 含取消（调用方断开）：杀掉再往上抛
        await reap(proc)
        raise
    CHILDREN.discard(proc)
    return proc.returncode, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


def tail(s: str, n: int = 600) -> str:
    s = s.strip()
    return s[-n:] if len(s) > n else s


# ---------- 终态判定 ----------

def usage_of(result: dict) -> dict:
    u = result.get("usage") or {}
    pt = sum(int(u.get(k) or 0) for k in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"))
    ct = int(u.get("output_tokens") or 0)
    return {"prompt_tokens": pt, "completion_tokens": ct, "total_tokens": pt + ct,
            "cache_read": int(u.get("cache_read_input_tokens") or 0),
            "cache_create": int(u.get("cache_creation_input_tokens") or 0)}


def served_from_result(result: dict | None, fallback: str) -> str:
    """取 CLI 自报的实际模型：result.modelUsage / result.model。"""
    if not isinstance(result, dict):
        return fallback
    mu = result.get("modelUsage")
    if isinstance(mu, dict) and mu:
        keys = list(mu.keys())
        # 多个模型时取 token 最多的那个
        try:
            keys.sort(key=lambda k: int((mu.get(k) or {}).get("outputTokens") or 0), reverse=True)
        except (AttributeError, TypeError, ValueError):
            pass
        return str(keys[0])
    if isinstance(result.get("model"), str) and result["model"]:
        return str(result["model"])
    return fallback


def official_rate_limit(event: dict) -> bool:
    """Only upstream error/rejection events trigger recovery; utilization never does."""
    kind = event.get("type")
    if kind == "rate_limit_event":
        info = event.get("rate_limit_info") or {}
        return str(info.get("status") or "").lower() in {"rejected", "rate_limited"}
    if kind != "error" and not event.get("is_error"):
        return False
    return bool(re.search(r"\b429\b|rate.?limit|usage.?limit|quota.exceeded|too many requests", json.dumps(event).lower()))


def judge_result(result: dict | None, rc: int, started: bool, served: str, text_hint: str = "") -> Outcome:
    """按 is_error / terminal_reason / api_error 判定终态；不再看 stop_reason=="max_tokens"。

    真实 max_output_tokens 的终态是 rc=1 + is_error=true + terminal_reason=api_error +
    api_error=max_output_tokens（核查员实测），所以必须**先看 is_error 再看 rc**，否则会被当成普通退出失败。
    """
    raw_text = str((result or {}).get("result") or "").strip()
    usage = usage_of(result or {})
    if not isinstance(result, dict):
        return Outcome("truncated" if started else "failed", "cli_exit", "error", served,
                       (text_hint or "").strip(), usage, started,
                       detail=f"exit {rc}: no result event")
    terminal = str(result.get("terminal_reason") or "")
    api_error = str(result.get("api_error") or "")
    # 已经发出去的文字以流里的为准；CLI 的 result.result 在错误分支里往往是错误说明，不能顶替正文
    text = (text_hint or raw_text).strip() if started and text_hint else raw_text
    if result.get("is_error"):
        if official_rate_limit(result):
            return Outcome("truncated" if started else "failed", "rate_limited", "error", served, text, usage, started,
                           detail="official upstream rate limit")
        if "max_output_tokens" in api_error or "max_output_tokens" in terminal:
            return Outcome("truncated" if (started or text) else "failed", "max_output_tokens",
                           "length", served, text, usage, started or bool(text),
                           detail="max_output_tokens")
        if "timeout" in terminal or "timeout" in api_error:
            return Outcome("truncated" if started else "failed", "timeout", "error", served, text, usage, started,
                           detail=terminal or api_error)
        return Outcome("truncated" if started else "failed", "cli_exit", "error", served, text, usage, started,
                       detail=f"{terminal or 'is_error'}:{api_error or '-'}")
    if rc != 0:
        return Outcome("truncated" if started else "failed", "cli_exit", "error", served, text, usage, started,
                       detail=f"exit {rc}")
    if result.get("stop_reason") == "max_tokens":
        return Outcome("truncated", "max_output_tokens", "length", served, text, usage, True)
    return Outcome("complete", None, "stop", served, text, usage, started)


# ---------- 后端：Claude Code ----------

def claude_argv(model: str, system: str, effort: str | None, stream: bool) -> tuple[list[str], str | None]:
    argv = ["claude", "-p", "--model", model]
    eff = (effort or "").strip()
    if eff:
        argv += ["--effort", eff]
    sysfile = None
    if len(system.encode()) > SYS_FILE_MIN:
        fd, sysfile = tempfile.mkstemp(prefix="coach-bridge-sys-", suffix=".txt", dir=str(TMP_DIR))
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(system)
        argv += ["--system-prompt-file", sysfile]
    else:
        argv += ["--system-prompt", system]
    argv += ["--tools", "", "--strict-mcp-config", "--setting-sources", "", "--no-session-persistence"]
    if stream:
        argv += ["--output-format", "stream-json", "--include-partial-messages", "--verbose"]
    else:
        argv += ["--output-format", "json"]
    return argv, sysfile


async def call_claude(req: ReqCtx, model: str, effort: str | None) -> Outcome:
    """非流式 Claude：--output-format json。"""
    async with slot(req, "claude") as t_acq:
        req.t_acq = t_acq
        reserve_claude_call(req.test_traffic)
        req.physical_calls += 1
        argv, sysfile = claude_argv(model, req.spec_data.system, effort, stream=False)
        budget = CLAUDE_TIMEOUT + STREAM_TOTAL_TIMEOUT   # 非流式：整段等回来
        try:
            rc, out, err = await run_cli(argv, CLAUDE_CWD, child_env("claude"),
                                         req.spec_data.prompt.encode("utf-8"), timeout=budget)
        finally:
            if sysfile:
                try:
                    os.unlink(sysfile)
                except OSError:
                    pass
    data = parse_claude_json(out)
    served = served_from_result(data, model)
    oc = judge_result(data, rc, False, served)
    if oc.status != "complete":
        detail = str((data or {}).get("result") or "")
        if "can't respond to this message" in detail:
            raise Refused(502, f"claude CLI 拒绝回答（exit {rc}）：{tail(detail, 200)}", reason="cli_exit")
        raise BridgeError(504 if oc.reason == "timeout" else 502,
                          f"claude CLI 未正常完成（exit {rc}，{oc.reason}）：{tail(detail or err, 200)}",
                          reason=oc.reason)
    return oc


def parse_claude_json(out: str) -> dict | None:
    data: Any = None
    try:
        data = json.loads(out.strip())
    except ValueError:
        for line in reversed(out.strip().splitlines()):
            try:
                data = json.loads(line)
                break
            except ValueError:
                continue
    if isinstance(data, list):
        data = next((x for x in reversed(data) if isinstance(x, dict) and x.get("type") == "result"), None)
    return data if isinstance(data, dict) else None


# 工具调用标记：正文可以先发，遇到 "<tool_call" 开头的部分扣住不发。
TC_START = "<tool_call"


def stream_safe_end(s: str) -> int:
    i = s.find(TC_START)
    if i != -1:
        return i
    for k in range(len(TC_START) - 1, 0, -1):
        if s.endswith(TC_START[:k]):
            return len(s) - k
    return len(s)


async def claude_stream(req: ReqCtx, model: str, effort: str | None) -> AsyncIterator[dict]:
    """真流式：yield {"kind":"meta"|"text"|"end"}。

    计时：（6）超时全部从拿到信号量之后起算，排队时长由 req.t_acq - req.t_req 单独记。
    终态：（2）出字后失败=truncated，从未出字=failed；reason 取 timeout/cli_exit/max_output_tokens/client_gone。
    """
    argv, sysfile = claude_argv(model, req.spec_data.system, effort, stream=True)
    async with slot(req, "claude") as t_acq:
        req.t_acq = t_acq
        reserve_claude_call(req.test_traffic)
        proc = await spawn(argv, CLAUDE_CWD, child_env("claude"), stdin_pipe=True)
        req.physical_calls += 1

        async def feed() -> None:
            try:
                proc.stdin.write(req.spec_data.prompt.encode("utf-8"))
                await proc.stdin.drain()
                proc.stdin.close()
            except (BrokenPipeError, ConnectionResetError, RuntimeError):
                pass

        feeder = asyncio.ensure_future(feed())
        errtask = asyncio.ensure_future(proc.stderr.read())
        s, sent, started = "", 0, False
        result: dict | None = None
        served = model
        failure: Outcome | None = None
        try:
            while True:
                now = time.monotonic()
                if started:
                    budget = STREAM_TOTAL_TIMEOUT - (now - t_acq)
                    budget = min(budget, STALL_TIMEOUT)
                else:
                    budget = CLAUDE_TIMEOUT - (now - t_acq)
                if budget <= 0:
                    raise asyncio.TimeoutError
                line = await asyncio.wait_for(proc.stdout.readline(), budget)
                if not line:
                    break
                try:
                    ev = json.loads(line)
                except ValueError:
                    continue
                kind = ev.get("type")
                if official_rate_limit(ev):
                    if kind == "rate_limit_event":
                        update_rate_limit(ev)
                    await reap(proc)
                    failure = Outcome("truncated" if started else "failed", "rate_limited", "error", served, s, {}, started,
                                      detail="official upstream rate limit")
                    break
                if kind == "system" and isinstance(ev.get("model"), str) and ev["model"]:
                    served = ev["model"]                      # system/init.model
                    yield {"kind": "meta", "served_by": served}
                elif kind == "assistant":
                    m = (ev.get("message") or {})
                    if isinstance(m, dict) and isinstance(m.get("model"), str) and m["model"]:
                        served = m["model"]
                elif kind == "rate_limit_event":
                    update_rate_limit(ev)
                elif kind == "result":
                    result = ev
                elif kind == "stream_event":
                    e = ev.get("event") or {}
                    d = e.get("delta") or {}
                    if e.get("type") == "content_block_delta" and d.get("type") == "text_delta" and d.get("text"):
                        s += d["text"]
                        view = s.lstrip()
                        end = stream_safe_end(view)
                        if end > sent:
                            if not started:
                                started = True
                                req.ttft_ms = round((time.monotonic() - t_acq) * 1000, 1)
                            yield {"kind": "text", "text": view[sent:end]}
                            sent = end
            rc = await proc.wait()
        except asyncio.TimeoutError:
            started_now = started
            await reap(proc)
            if started_now:
                failure = Outcome("truncated", "timeout", "error", served, s, {}, True, detail="stream timeout/stall")
            else:
                failure = Outcome("failed", "timeout", "error", served, "", {}, False, detail="first token timeout")
            rc = -9
        except asyncio.CancelledError:
            # 客户端断开：杀整个进程组（2 秒内不留残留）
            await reap(proc)
            raise
        finally:
            CHILDREN.discard(proc)
            feeder.cancel()
            errtask.cancel()
            if sysfile:
                try:
                    os.unlink(sysfile)
                except OSError:
                    pass

        if failure is not None:
            yield {"kind": "end", "outcome": failure}
            return

        served = served_from_result(result, served)
        oc = judge_result(result, rc, started, served, text_hint=s)
        if oc.status == "complete":
            # 流里已发出的文字和最终结果对得上才补发剩余部分；对不上就不补，免得重复。
            raw = oc.text
            i = raw.find(TC_START)
            before = raw[:i] if i != -1 else raw
            if len(before) > sent and raw.startswith(s.lstrip()[:sent]):
                yield {"kind": "text", "text": before[sent:]}
                req.out_chars = len(before)
            else:
                req.out_chars = max(sent, 0)
            oc.text = raw
        else:
            oc.text = s
            detail = str((result or {}).get("result") or "")
            if "can't respond to this message" in detail:
                oc.reason = oc.reason or "cli_exit"
                oc.detail = f"refused:{','.join(re.findall(r'\[(\w+)\]', detail)) or '?'}"
        yield {"kind": "end", "outcome": oc}


# ---------- 后端：Antigravity (agy，默认关) ----------

async def call_agy(req: ReqCtx, model: str, effort: str | None) -> Outcome:
    # agy 不读 stdin，只能把提示词放进 argv；开头不能是 "-"，否则会被当成参数。
    text = f"{req.spec_data.system}\n\n{NO_TOOLS_NOTE}\n\n{req.spec_data.prompt}"
    if text.startswith("-"):
        text = " " + text
    argv = ["agy", "-p", text, "--output-format", "stream-json", "--model", model]
    if effort:
        argv += ["--reasoning-effort", effort]
    async with slot(req, "agy") as t_acq:
        req.t_acq = t_acq
        rc, out, err = await run_cli(argv, AGY_CWD, child_env("agy"), timeout=AGY_TIMEOUT)
    result, deltas = None, []
    for line in out.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("event") == "result":
            result = ev.get("result") or {}
        elif ev.get("event") == "step_update":
            su = ev.get("step_update") or {}
            if su.get("step_type") == "agent_response" and su.get("text_delta"):
                deltas.append(su["text_delta"])
    if rc != 0 or (result is not None and (result.get("status") != "SUCCESS" or result.get("error"))):
        raise BridgeError(502, f"agy CLI 失败（exit {rc}）：{tail(str((result or {}).get('error') or '') + ' ' + err, 300)}",
                          reason="cli_exit")
    if result is None and not deltas:
        raise BridgeError(502, f"agy CLI 没有返回 result 事件 | stderr: {tail(err)}", reason="cli_exit")
    answer = str((result or {}).get("response") or "".join(deltas)).strip()
    u = (result or {}).get("usage") or {}
    pt, ct = int(u.get("input_tokens") or 0), int(u.get("output_tokens") or 0)
    return Outcome("complete", None, "stop", served_from_result(result, model), answer,
                   {"prompt_tokens": pt, "completion_tokens": ct, "total_tokens": pt + ct})


# ---------- 后端：Codex ----------

async def call_codex(req: ReqCtx, model: str, effort: str | None) -> Outcome:
    """codex exec --json 没有 token 级 delta（核查员实测），所以 true_stream=false：
    桥把整段结果切块发，界面显示「等待中」，不模拟打字。"""
    TMP_DIR.mkdir(parents=True, exist_ok=True)
    fd, outfile = tempfile.mkstemp(prefix="coach-bridge-codex-", suffix=".txt", dir=str(TMP_DIR))
    os.close(fd)
    argv = ["codex", "exec", "-m", model,
            "-c", f"model_reasoning_effort={effort or CODEX_EFFORT}",
            "--ephemeral", "--skip-git-repo-check", "-s", "read-only", "-o", outfile, "-"]
    text = f"{req.spec_data.system}\n\n{NO_TOOLS_NOTE}\n\n{req.spec_data.prompt}"
    try:
        async with slot(req, "codex") as t_acq:
            req.t_acq = t_acq
            try:
                rc, out, err = await run_cli(argv, CODEX_CWD, child_env("codex"),
                                             text.encode("utf-8"), timeout=CODEX_TIMEOUT)
            except BridgeError as e:
                if e.reason == "timeout":
                    raise BridgeError(504, e.message, "timeout", "timeout", reason="timeout")
                raise
        try:
            answer = Path(outfile).read_text(encoding="utf-8").strip()
        except OSError:
            answer = ""
    finally:
        try:
            os.unlink(outfile)
        except OSError:
            pass
    usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    for line in out.splitlines():                       # turn.completed 里带真实 usage
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if isinstance(ev, dict) and ev.get("type") == "turn.completed":
            u = ev.get("usage") or {}
            pt = int(u.get("input_tokens") or u.get("prompt_tokens") or 0)
            ct = int(u.get("output_tokens") or u.get("completion_tokens") or 0)
            usage = {"prompt_tokens": pt, "completion_tokens": ct, "total_tokens": pt + ct}
    if rc != 0 or not answer:
        raise BridgeError(502, f"codex CLI 失败（exit {rc}）：{tail(err, 300)}", reason="cli_exit")
    return Outcome("complete", None, "stop", model, answer, usage)


# ---------- 并发槽 ----------

@asynccontextmanager
async def slot(req: ReqCtx, backend: str):
    """拿到槽之后把 t_acq 交出去；后台请求额外占一个后台槽，额度紧张时直接拒。"""
    global BG_ACTIVE
    if req.background:
        await BG_SLOTS.acquire()
        BG_ACTIVE += 1
    try:
        await GATES[backend].acquire(req.background)
        try:
            yield time.monotonic()
        finally:
            await GATES[backend].release()
    finally:
        if req.background:
            BG_ACTIVE -= 1
            BG_SLOTS.release()


# ---------- 输出后处理 ----------

TC_RE = re.compile(r"<tool_calls>\s*(.*?)\s*(?:</tool_calls>|\Z)", re.S)
FENCE_RE = re.compile(r"^```[\w-]*[ \t]*\n?(.*?)\n?```\s*$", re.S)
XML_CALL_RE = re.compile(r"<tool_call>(.*?)(?:</tool_call>|\Z)", re.S)
XML_NAME_RE = re.compile(r"<(?:tool_name|name)>\s*([\w.:-]+)\s*</(?:tool_name|name)>")
XML_ARGS_RE = re.compile(r"<(arguments|parameters|args)>\s*(.*?)\s*</\1>", re.S)


def strip_tool_markup(text: str) -> str:
    return TC_RE.sub("", XML_CALL_RE.sub("", text)).strip()


def _xml_calls(body: str) -> list:
    data = []
    for chunk in XML_CALL_RE.findall(body) or [body]:
        nm = XML_NAME_RE.search(chunk)
        if not nm:
            continue
        am = XML_ARGS_RE.search(chunk)
        args: Any = {}
        if am:
            try:
                args = json.loads(am.group(2))
            except ValueError:
                args = {}
        data.append({"name": nm.group(1), "arguments": args})
    return data


def parse_tool_calls(text: str, names: set[str]) -> list[dict] | None:
    m = TC_RE.search(text) or XML_CALL_RE.search(text)
    if not m:
        return None
    body = m.group(1).strip() if m.re is TC_RE else m.group(0)
    fm = FENCE_RE.match(body)
    if fm:
        body = fm.group(1).strip()
    start = min((i for i in (body.find("["), body.find("{")) if i != -1), default=-1)
    try:
        if start == -1 or body.lstrip().startswith("<"):
            raise ValueError
        data, _ = json.JSONDecoder().raw_decode(body[start:])
    except ValueError:
        data = _xml_calls(body)
    if isinstance(data, dict):
        data = data.get("tool_calls") if isinstance(data.get("tool_calls"), list) else [data]
    if not isinstance(data, list):
        return None
    calls = []
    for it in data:
        if not isinstance(it, dict):
            continue
        fn = it.get("function") if isinstance(it.get("function"), dict) else it
        name = fn.get("name")
        if not isinstance(name, str) or (names and name not in names):
            continue
        args = fn.get("arguments", fn.get("parameters", {}))
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except ValueError:
                pass
        calls.append({"id": "call_" + secrets.token_hex(12), "type": "function",
                      "function": {"name": name, "arguments": args_str(args)}})
    return calls or None


def clean_json(text: str) -> str:
    s = text.strip()
    fm = FENCE_RE.match(s)
    if fm:
        s = fm.group(1).strip()
    try:
        json.loads(s)
        return s
    except ValueError:
        pass
    i, j = s.find("{"), s.rfind("}")
    if 0 <= i < j:
        try:
            json.loads(s[i:j + 1])
            return s[i:j + 1]
        except ValueError:
            pass
    return s


# ---------- HTTP ----------

@asynccontextmanager
async def lifespan(_: FastAPI):
    for d in (WORK_DIR, TMP_DIR, CLAUDE_CWD, AGY_CWD, CODEX_CWD, LOG_FILE.parent, STATE_DIR):
        d.mkdir(parents=True, exist_ok=True)
    render_sandbox_profile()
    try:
        yield
    finally:
        for proc in list(CHILDREN):        # 退出时不留下 CLi 子进程
            kill_group(proc)


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


def model_obj(spec: ModelSpec) -> dict:
    return {"id": spec.id, "object": "model", "created": 1700000000, "owned_by": "coach-bridge",
            "true_stream": spec.true_stream, "backend": spec.backend}


# ---------- /health：每家 CLI 的登录状态 + true_stream 能力表 ----------

_HEALTH_CACHE: dict[str, Any] = {"at": 0.0, "data": {}}


async def cli_status(argv: list[str], env: dict[str, str], cwd: Path) -> dict:
    """只调 CLI 自带的状态命令，不读凭证文件。

    cwd 必须给：codex 在沙箱里读不到自己的工作目录时会报 Operation not permitted。
    """
    cwd.mkdir(parents=True, exist_ok=True)
    try:
        proc = await asyncio.create_subprocess_exec(
            *argv, cwd=str(cwd), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            env=env, start_new_session=True)
    except OSError as e:
        return {"logged_in": None, "detail": f"无法运行 {argv[0]}：{e.strerror}"}
    try:
        out, err = await asyncio.wait_for(proc.communicate(), 10.0)
    except asyncio.TimeoutError:
        try:
            kill_group(proc)
        except ProcessLookupError:
            pass
        return {"logged_in": None, "detail": "状态命令 10 秒未返回"}
    txt = (out or b"").decode("utf-8", "replace").strip()
    errs = (err or b"").decode("utf-8", "replace").strip()
    combined = (txt + "\n" + errs).strip()      # codex 把 "Logged in using ChatGPT" 打在 stderr
    if proc.returncode != 0:
        return {"logged_in": False, "detail": tail(combined, 200)}
    try:
        data = json.loads(txt)
        if isinstance(data, dict) and "loggedIn" in data:
            return {"logged_in": bool(data.get("loggedIn")),
                    "detail": f"{data.get('authMethod') or '?'} · {data.get('subscriptionType') or '?'}",
                    "account": data.get("email")}
    except ValueError:
        pass
    low = combined.lower()
    logged_in = ("logged in" in low) or ("true" == low) or low.startswith("ok")
    return {"logged_in": logged_in, "detail": tail(combined, 200)}


async def health_payload() -> dict:
    now = time.monotonic()
    if HEALTH_CACHE_SEC > 0 and _HEALTH_CACHE["data"] and now - _HEALTH_CACHE["at"] < HEALTH_CACHE_SEC:
        cached = _HEALTH_CACHE["data"]
    else:
        probes = {
            "claude": (["claude", "auth", "status"], CLAUDE_CWD),
            "codex": (["codex", "login", "status"], CODEX_CWD),
        }
        results: dict[str, Any] = {}
        for name, (argv, cwd) in probes.items():
            st = await cli_status(argv, child_env(name), cwd)
            st["command"] = " ".join(argv)
            results[name] = st
        if not AGY_ENABLED:
            results["agy"] = {"logged_in": None, "enabled": False, "detail": "默认关（BRIDGE_AGY_ENABLED=0）"}
        cached = results
        _HEALTH_CACHE.update(at=now, data=cached)
    upstreams = {k: {"enabled": True, **v} for k, v in cached.items()}
    return {
        "ok": True,
        "service": "coach-bridge",
        "port": PORT,
        "bridge_home": str(BRIDGE_HOME),
        "upstreams": upstreams,
        "models": {m.id: {"backend": m.backend, "true_stream": m.true_stream} for m in MODELS.values()},
        "concurrency": {name: gate.snapshot() for name, gate in GATES.items()}
                       | {"background": {"limit": BACKGROUND_SLOTS, "active": BG_ACTIVE}},
        "background_backend": BACKGROUND_BACKEND or None,
        "daily": {"date": _today(), "scope": "test", "total_claude_calls": int(read_daily().get("claude_calls") or 0),
                  "claude_calls": daily_used(), "claude_cap": None, "remaining": None},
        "rate_limit": RATE_LIMIT.snapshot(),
        "fallback_default": "off",
    }


@app.get("/health")
async def health(request: Request):
    if request.query_params.get("scope") == "process":
        # Liveness must not wait for CLI auth probes (up to 20s), or depend on
        # subscription availability. Full /health continues to report upstreams.
        return {"ok": True, "service": "coach-bridge", "port": PORT, "pid": os.getpid()}
    payload = await health_payload()
    payload["request_id"] = request.headers.get("x-request-id") or "-"
    return payload


@app.get("/v1/models")
@app.get("/models")
async def list_models():
    return {"object": "list", "data": [model_obj(m) for m in MODELS.values()]}


@app.get("/v1/models/{mid}")
@app.get("/models/{mid}")
async def get_model(mid: str):
    spec = MODELS.get(mid)
    if spec is None:
        return err_response(BridgeError(404, f"The model `{mid}` does not exist", "invalid_request_error",
                                        "model_not_found", "model"))
    return model_obj(spec)


# ---------- 请求解析 ----------

def norm_effort(v: str | None) -> str | None:
    if not v:
        return None
    v = v.strip().lower()
    if not re.fullmatch(r"[a-z]{3,8}", v) or v not in EFFORT_VALUES:
        return None
    return v


def build_req(body: dict, headers: Any) -> ReqCtx:
    t_req = time.monotonic()
    model = str(body.get("model"))
    mspec = MODELS.get(model)
    if mspec is None:
        raise BridgeError(404, f"The model `{model}` does not exist", "invalid_request_error",
                          "model_not_found", "model")
    spec_data = build_spec(body)
    rid = (headers.get("x-request-id") or "").strip()[:128] or secrets.token_hex(8)
    fallback_hdr = (headers.get("x-coach-fallback") or "off").strip().lower()
    fallback_enabled = fallback_hdr in ("on", "1", "true", "yes")
    background = (headers.get("x-coach-background") or "").strip().lower() in ("1", "true", "yes") \
        or (headers.get("x-coach-priority") or "").strip().lower() == "background"
    effort = norm_effort(headers.get("x-coach-effort") or body.get("reasoning_effort"))
    return ReqCtx(request_id=rid, model=model, spec=mspec, body=body, spec_data=spec_data,
                  stream=bool(body.get("stream")), background=background,
                  fallback_enabled=fallback_enabled, effort=effort, t_req=t_req,
                  exact_model=(headers.get("x-coach-model-policy") or "").lower() == "exact",
                  test_traffic=(headers.get("x-coach-traffic") or "").strip().lower() == "test",
                  in_chars=len(spec_data.system) + len(spec_data.prompt))


def effort_for(req: ReqCtx, backend: str) -> str | None:
    e = req.effort
    if backend == "claude":
        if e is None:
            return CLAUDE_EFFORT or None
        return e if e in CLAUDE_EFFORT_VALUES else CLAUDE_EFFORT or None
    if backend == "codex":
        return e or CODEX_EFFORT
    return e


async def dispatch(req: ReqCtx, model: str, effort: str | None) -> Outcome:
    """按模型调后端；这里的失败都以 BridgeError 抛出，由外层决定兜底与否。"""
    spec = MODELS[model]
    req.physical_calls += 1 if spec.backend != "claude" else 0   # claude_stream 自己计数
    if spec.backend == "claude":
        return await call_claude(req, model, effort)
    if spec.backend == "codex":
        return await call_codex(req, model, effort)
    if spec.backend == "agy":
        return await call_agy(req, model, effort)
    raise BridgeError(500, f"未知后端 {spec.backend}")


# ---------- SSE ----------

def sse(obj: Any) -> str:
    return f"data: {json.dumps(obj, ensure_ascii=False)}\n\n"


def chunk_obj(cid: str, created: int, model: str, delta: dict, fin: str | None = None) -> dict:
    return {"id": cid, "object": "chat.completion.chunk", "created": created, "model": model,
            "choices": [{"index": 0, "delta": delta, "finish_reason": fin}]}


def terminal_frames(req: ReqCtx, cid: str, created: int, oc: Outcome, want_usage: bool,
                    usage: dict) -> list[str]:
    """终态帧在 [DONE] 之前：x_coach 是唯一可信的结束信号（收不到=按截断处理）。"""
    served = oc.served_by or req.model
    frames = [sse(chunk_obj(cid, created, served, {}, oc.finish_reason))]
    if want_usage:
        frames.append(sse({"id": cid, "object": "chat.completion.chunk", "created": created,
                           "model": served, "choices": [], "usage": usage}))
    frames.append(sse({"id": cid, "object": "chat.completion.chunk", "created": created,
                       "model": served, "choices": [], "x_coach": oc.x_coach(req)}))
    frames.append("data: [DONE]\n\n")
    return frames


def log_req(req: ReqCtx, oc: Outcome) -> None:
    write_log(request_id=req.request_id, model=req.model, served_by=oc.served_by or req.served_by,
              stream=req.stream, background=req.background, effort=req.effort, traffic="test" if req.test_traffic else "user",
              in_chars=req.in_chars, out_chars=req.out_chars, queue_ms=req.queue_ms(),
              ttft_ms=req.ttft_ms, total_ms=req.total_ms(), status=oc.status, reason=oc.reason,
              finish_reason=oc.finish_reason, physical_calls=req.physical_calls,
              fallback=req.fallback, note=oc.detail or None)


def buffered_stream(req: ReqCtx, cid: str, created: int, oc: Outcome, content: str | None,
                    calls: list[dict] | None, want_usage: bool) -> StreamingResponse:
    """非真流式后端（Codex / agy / 兜底结果 / 无正文的整段结果）：切块发，界面显示等待、不模拟打字。"""
    served = oc.served_by or req.model

    async def gen():
        final = oc
        try:
            yield sse(chunk_obj(cid, created, served,
                                {"role": "assistant", "content": None if calls and not content else ""}))
            for i in range(0, len(content or ""), 60):
                yield sse(chunk_obj(cid, created, served, {"content": content[i:i + 60]}))
            if calls:
                yield sse(chunk_obj(cid, created, served,
                                    {"tool_calls": [{"index": i, **c} for i, c in enumerate(calls)]}))
            for f in terminal_frames(req, cid, created, oc, want_usage, oc.usage):
                yield f
        except asyncio.CancelledError:
            final = Outcome("truncated" if content else "failed", "client_gone", "error",
                            served, started=bool(content))
            raise
        finally:
            log_req(req, final)

    headers = base_headers(req)
    headers["X-Served-Model"] = served
    return StreamingResponse(gen(), media_type="text/event-stream", headers=headers)


def live_stream(req: ReqCtx, cid: str, created: int, pending: list[dict],
                events: AsyncIterator[dict], want_usage: bool) -> StreamingResponse:
    """Claude 真流式：边收边转 SSE，最后补终态帧。

    pending 是回包前已经取到的事件（meta / 第一段正文），按原顺序先发出去。
    """
    served0 = req.served_by or req.model

    async def gen():
        nonlocal served0
        sent_text = False
        oc: Outcome | None = None
        queue = list(pending)
        try:
            # 先发 role 帧（此时还不知道是不是纯工具调用，content 留空字符串）
            yield sse(chunk_obj(cid, created, served0, {"role": "assistant", "content": ""}))
            while True:
                ev = queue.pop(0) if queue else None
                if ev is None:
                    try:
                        ev = await events.__anext__()
                    except StopAsyncIteration:
                        break
                kind = ev.get("kind")
                if kind == "meta":
                    served0 = ev["served_by"]
                    req.served_by = served0
                elif kind == "text":
                    sent_text = True
                    req.out_chars += len(ev["text"])
                    yield sse(chunk_obj(cid, created, served0, {"content": ev["text"]}))
                elif kind == "end":
                    oc = ev["outcome"]
                    break
        except asyncio.CancelledError:
            # 客户端断开：关掉内层生成器（它会杀进程组），记 client_gone
            req.served_by = served0
            log_req(req, Outcome("truncated" if sent_text else "failed", "client_gone", "error",
                                 served0, started=sent_text))
            try:
                await events.aclose()
            except Exception:
                pass
            raise
        except Exception as e:      # 内部异常也要给出显式终态，不能让客户端只看到断流
            oc = Outcome("truncated" if sent_text else "failed", "cli_exit", "error", served0,
                         started=sent_text, detail=f"internal:{type(e).__name__}")
        finally:
            try:
                await events.aclose()
            except Exception:
                pass

        if oc is None:       # 生成器莫名结束：按截断处理
            oc = Outcome("truncated" if sent_text else "failed", "cli_exit", "error", served0,
                         started=sent_text, detail="no terminal event")
        oc.served_by = oc.served_by or served0
        req.served_by = oc.served_by

        content, calls, _finish = shape_output(req, oc)
        if calls:
            yield sse(chunk_obj(cid, created, oc.served_by,
                                {"tool_calls": [{"index": i, **c} for i, c in enumerate(calls)]}))
            oc.finish_reason = "tool_calls"
        elif oc.status != "complete" and not sent_text and content:
            # 一个字都没发出去却拿到了整段内容（极少）：补发，状态仍按 CLI 判定
            yield sse(chunk_obj(cid, created, oc.served_by, {"content": content}))
        for f in terminal_frames(req, cid, created, oc, want_usage, oc.usage):
            yield f
        log_req(req, oc)

    headers = base_headers(req)
    if req.served_by:
        headers["X-Served-Model"] = req.served_by
    return StreamingResponse(gen(), media_type="text/event-stream", headers=headers)


def base_headers(req: ReqCtx) -> dict[str, str]:
    h = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no",
         "X-Request-Id": req.request_id,
         "X-Coach-Fallback": "on" if req.fallback_enabled else "off"}
    if req.routed_from:
        h["X-Coach-Routed-From"] = req.routed_from
    return h


def shape_output(req: ReqCtx, oc: Outcome) -> tuple[str | None, list[dict] | None, str]:
    """工具调用解析与 JSON 清理（与 8790 同规则）。返回 (content, calls, finish_reason)。"""
    raw = oc.text or ""
    if req.spec_data.tool_names and raw:
        calls = parse_tool_calls(raw, req.spec_data.tool_names)
        text = strip_tool_markup(raw)
        return (text or None) if calls else text, calls, oc.finish_reason
    if req.spec_data.json_mode and oc.status == "complete":
        return clean_json(raw), None, oc.finish_reason
    return raw, None, oc.finish_reason


async def wait_disconnect(request: Request, coro, timeout_s: float = TIMEOUT):
    """等后端结果；调用方中途断开就取消任务（取消会杀掉子进程组）。"""
    task = asyncio.ensure_future(coro)
    try:
        deadline = time.monotonic() + timeout_s
        while True:
            done, _ = await asyncio.wait({task}, timeout=1.0)
            if done:
                return task.result()
            if time.monotonic() > deadline:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                raise BridgeError(504, "请求等待超时", "timeout", "timeout", reason="timeout")
            if await request.is_disconnected():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                raise ClientGone()
    except asyncio.CancelledError:
        task.cancel()
        raise


async def fallback_call(req: ReqCtx, first_error: BridgeError | None) -> Outcome:
    """只有 X-Coach-Fallback: on 才会走到这里：物理调用数照实累加，served_by 报真名。"""
    req.fallback = True
    req.routed_from = req.model
    model = REFUSAL_FALLBACK
    for attempt in (1, 2):          # 兜底模型偶尔卡住：超时重试一次（与 8790 相同）
        try:
            return await dispatch(req, model, effort_for(req, MODELS[model].backend))
        except BridgeError as e:
            if e.reason != "timeout" or attempt == 2:
                raise
    raise BridgeError(502, "兜底失败", reason="cli_exit")


def exam_protection_active(now: float | None = None) -> bool:
    current = time.time() if now is None else now
    return (datetime(2026, 11, 2, tzinfo=timezone.utc).timestamp() <= current
            < datetime(2026, 11, 2, 15, tzinfo=timezone.utc).timestamp())


@app.post("/v1/chat/completions")
@app.post("/chat/completions")
async def chat(request: Request):
    req: ReqCtx | None = None
    try:
        try:
            body = await request.json()
        except Exception:
            raise BridgeError(400, "请求体不是合法 JSON", "invalid_request_error")
        if not isinstance(body, dict):
            raise BridgeError(400, "请求体必须是 JSON 对象", "invalid_request_error")
        req = build_req(body, request.headers)
        if exam_protection_active():
            raise BridgeError(503, "考前保护：11 月 2 日 00:00–15:00 GMT 暂停模型调用", reason="exam_protection")

        # 期中保护：后台生成全部走 Codex（ADR-0002 裁决）
        target = req.model
        if req.background and not req.exact_model and BACKGROUND_BACKEND and req.spec.backend != BACKGROUND_BACKEND:
            if BACKGROUND_MODEL in MODELS and MODELS[BACKGROUND_MODEL].backend == BACKGROUND_BACKEND:
                target = BACKGROUND_MODEL
                req.routed_from = req.model

        want_usage = bool(isinstance(body.get("stream_options"), dict)
                          and body["stream_options"].get("include_usage"))
        cid, created = "chatcmpl-" + secrets.token_hex(12), int(time.time())

        # ---- 真流式（Claude 流式且非 JSON 模式）----
        if req.stream and MODELS[target].true_stream and not req.spec_data.json_mode:
            effort = effort_for(req, "claude")
            events = claude_stream(req, target, effort)
            # 先攒到「第一段正文」或「终态」再回包：这样出字前的失败/拒答仍能兜底或给 502，
            # 已经出字之后就只能截断（文字收不回来）。
            pending: list[dict] = []
            end_ev: dict | None = None
            try:
                while True:
                    ev = await wait_disconnect(request, events.__anext__())
                    if ev.get("kind") == "end":
                        end_ev = ev
                        break
                    pending.append(ev)
                    if ev.get("kind") == "text":
                        break
            except StopAsyncIteration:
                end_ev = None
            except ClientGone:
                await events.aclose()
                raise
            except BridgeError as e:
                await events.aclose()
                if e.status >= 500:      # 上游失败：流式请求也走终态帧，保持「每个流都以 x_coach 结束」
                    oc = Outcome("failed", e.reason, "error", req.served_by or target, detail=e.message)
                    return buffered_stream(req, cid, created, oc, None, None, want_usage)
                raise
            for ev in pending:
                if ev.get("kind") == "meta":
                    req.served_by = ev["served_by"]

            if end_ev is not None:
                # 没有正文：拒答、挂起超时、空回复、纯工具调用都走这里
                oc = end_ev["outcome"]
                await events.aclose()
                req.served_by = oc.served_by
                if oc.status != "complete" and req.fallback_enabled and oc.reason in ("cli_exit", "timeout"):
                    fb = await fallback_call(req, None)
                    content, calls, _ = shape_output(req, fb)
                    req.out_chars = len(fb.text)
                    return buffered_stream(req, cid, created, fb, content, calls, want_usage)
                if oc.status == "failed":
                    # 流式请求也给终态帧：Coach 只认 x_coach，HTTP 层面保持 200
                    return buffered_stream(req, cid, created, oc, None, None, want_usage)
                content, calls, _ = shape_output(req, oc)
                req.out_chars = len(oc.text)
                return buffered_stream(req, cid, created, oc, content, calls, want_usage)
            return live_stream(req, cid, created, pending, events, want_usage)

        # ---- 非流式 / 非真流式后端 ----
        try:
            oc = await wait_disconnect(request, dispatch(req, target, effort_for(req, MODELS[target].backend)))
        except BridgeError as e:
            if req.fallback_enabled and e.reason in ("cli_exit", "timeout"):
                oc = await fallback_call(req, e)
            elif req.stream and e.status >= 500:
                oc = Outcome("failed", e.reason, "error", req.served_by or target, detail=e.message)
                return buffered_stream(req, cid, created, oc, None, None, want_usage)
            else:
                raise

        req.served_by = oc.served_by
        content, calls, _finish = shape_output(req, oc)
        if calls:
            oc.finish_reason = "tool_calls"
        req.out_chars = len(oc.text)
        log_req(req, oc)

        headers = base_headers(req)
        headers["X-Served-Model"] = oc.served_by

        if req.stream:      # 后端本身不真流式：桥切块发
            return buffered_stream(req, cid, created, oc, content, calls, want_usage)

        msg: dict[str, Any] = {"role": "assistant", "content": content}
        if calls:
            msg["tool_calls"] = calls
        return JSONResponse({"id": cid, "object": "chat.completion", "created": created,
                             "model": oc.served_by,
                             "choices": [{"index": 0, "message": msg, "finish_reason": oc.finish_reason}],
                             "usage": oc.usage,
                             "x_coach": oc.x_coach(req)}, headers=headers)

    except BridgeError as e:
        rid = req.request_id if req else (request.headers.get("x-request-id") or secrets.token_hex(8))
        headers = {"X-Request-Id": rid, "X-Coach-Fallback": "on" if (req and req.fallback_enabled) else "off"}
        oc = Outcome("failed", e.reason, "error",
                     (req.served_by or req.model) if req else "?", detail=e.message)
        if req is not None:
            log_req(req, oc)
        return err_response(e, x_coach=oc.x_coach(req) if req else None, headers=headers)
    except ClientGone:
        return Response(status_code=499, headers={"X-Request-Id": req.request_id if req else "-"})
    except Exception as e:  # 兜底：不把异常细节里的内容写进日志
        rid = req.request_id if req else (request.headers.get("x-request-id") or "-")
        if req:
            write_log(request_id=req.request_id, model=req.model, served_by=req.served_by,
                      stream=req.stream, background=req.background, effort=req.effort, traffic="test" if req.test_traffic else "user",
                      in_chars=req.in_chars, out_chars=0, queue_ms=req.queue_ms(),
                      ttft_ms=req.ttft_ms, total_ms=req.total_ms(), status="failed",
                      reason="cli_exit", finish_reason="error",
                      physical_calls=req.physical_calls, fallback=req.fallback,
                      note=f"internal:{type(e).__name__}")
        return err_response(BridgeError(500, f"bridge 内部错误：{type(e).__name__}"),
                            headers={"X-Request-Id": rid})


if __name__ == "__main__":
    for d in (WORK_DIR, TMP_DIR, CLAUDE_CWD, AGY_CWD, CODEX_CWD, LOG_FILE.parent, STATE_DIR):
        d.mkdir(parents=True, exist_ok=True)
    render_sandbox_profile()
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning", access_log=False)
