"""Async model transports for evaluation: Anthropic API, OpenAI Responses API, CodeBuddy CLI."""
from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import subprocess
import time
from typing import Any

import httpx

from config import MODEL_CATALOG

ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
OPENAI_URL = "https://api.openai.com/v1/responses"
DEFAULT_429_DELAYS = (1.5, 4.0, 10.0)


@dataclass
class CallResult:
    model: str
    status: str  # "ok" | "error"
    text: str
    ttft_ms: int | None
    elapsed_ms: int
    input_tokens: int
    output_tokens: int
    cached_tokens: int
    retries_429: int
    served_by: str | None = None
    error: str | None = None


def get_keychain_secret(service: str) -> str:
    env_map = {
        "coach-anthropic-api": "COACH_ANTHROPIC_API_KEY",
        "coach-openai-api": "COACH_OPENAI_API_KEY",
    }
    env_val = os.environ.get(env_map.get(service, ""), "").strip()
    if env_val:
        return env_val
    return subprocess.check_output(
        ["security", "find-generic-password", "-s", service, "-w"],
        text=True,
    ).strip()


def estimate_tokens(text: str) -> int:
    if not text:
        return 0
    words = len(re.findall(r"[\w'-]+|[\u4e00-\u9fff]", text))
    return max(1, int(words * 1.33), len(text) // 4)


def find_codebuddy_bin() -> str:
    for candidate in (
        os.environ.get("COACH_CODEBUDDY_BIN", ""),
        "/Users/zheng_li/.nvm/versions/node/v22.23.1/bin/codebuddy",
        "/opt/homebrew/bin/codebuddy",
        "/usr/local/bin/codebuddy",
    ):
        if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return "codebuddy"


def _parse_codebuddy_line(line: str) -> tuple[str, bool] | None:
    s = line.strip()
    if not s:
        return None
    if not s.startswith("{"):
        return s, True
    try:
        obj = json.loads(s)
    except Exception:
        return None
    t = obj.get("type")
    if t == "stream_event":
        ev = obj.get("event") or {}
        if ev.get("type") == "content_block_delta":
            delta = ev.get("delta") or {}
            if delta.get("type") == "text_delta" and delta.get("text"):
                return delta["text"], True
    elif t == "result":
        res = obj.get("result")
        if isinstance(res, str) and res.strip():
            return res, False
    return None


class EvalRunnerClients:
    def __init__(self, *, load_keys: bool = True) -> None:
        self.anthropic_key = get_keychain_secret("coach-anthropic-api") if load_keys else ""
        self.openai_key = get_keychain_secret("coach-openai-api") if load_keys else ""
        self.codebuddy_bin = find_codebuddy_bin()
        self.gates = {
            "anthropic": asyncio.Semaphore(3),
            "openai": asyncio.Semaphore(3),
            "codebuddy": asyncio.Semaphore(2),
        }

    async def call(
        self,
        model_name: str,
        system_prompt: str,
        user_prompt: str,
        *,
        image_path: str | None = None,
        max_tokens: int = 2048,
        timeout_sec: float = 150.0,
    ) -> CallResult:
        spec = MODEL_CATALOG[model_name]
        async with self.gates[spec.provider]:
            if spec.provider == "anthropic":
                return await self._call_anthropic(
                    spec.api_model, system_prompt, user_prompt, image_path=image_path, max_tokens=max_tokens, timeout_sec=timeout_sec
                )
            if spec.provider == "openai":
                return await self._call_openai(
                    spec.api_model, system_prompt, user_prompt, image_path=image_path, max_tokens=max_tokens, timeout_sec=timeout_sec
                )
            if image_path is not None:
                return CallResult(
                    model=model_name,
                    status="error",
                    text="",
                    ttft_ms=None,
                    elapsed_ms=0,
                    input_tokens=0,
                    output_tokens=0,
                    cached_tokens=0,
                    retries_429=0,
                    error="vision_unsupported",
                )
            return await self._call_codebuddy(spec.api_model, system_prompt, user_prompt, timeout_sec=timeout_sec)

    async def _call_anthropic(
        self,
        model: str,
        system_prompt: str,
        user_prompt: str,
        *,
        image_path: str | None,
        max_tokens: int,
        timeout_sec: float,
    ) -> CallResult:
        headers = {
            "x-api-key": self.anthropic_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        }
        content: Any = user_prompt
        if image_path:
            b64 = base64.b64encode(Path(image_path).read_bytes()).decode("ascii")
            content = [
                {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": b64}},
                {"type": "text", "text": user_prompt},
            ]
        payload = {
            "model": model,
            "system": system_prompt,
            "messages": [{"role": "user", "content": content}],
            "max_tokens": max_tokens,
            "stream": True,
        }
        retries_429 = 0
        started = time.monotonic()
        for attempt in range(len(DEFAULT_429_DELAYS) + 1):
            ttft_ms: int | None = None
            chunks: list[str] = []
            usage: dict[str, int] = {}
            served: str | None = None
            try:
                async with httpx.AsyncClient(timeout=httpx.Timeout(timeout_sec, connect=10.0), trust_env=False) as client:
                    async with client.stream("POST", ANTHROPIC_URL, headers=headers, json=payload) as resp:
                        if resp.status_code == 429:
                            retries_429 += 1
                            if attempt < len(DEFAULT_429_DELAYS):
                                ra = resp.headers.get("retry-after")
                                delay = float(ra) if ra and ra.replace(".", "", 1).isdigit() else DEFAULT_429_DELAYS[attempt]
                                await asyncio.sleep(min(delay, 30.0))
                                continue
                            return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error="http_429")
                        if resp.status_code != 200:
                            body = (await resp.aread()).decode("utf-8", errors="replace")[:250]
                            return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error=f"http_{resp.status_code}:{body}")
                        async for line in resp.aiter_lines():
                            if not line.startswith("data:"):
                                continue
                            raw = line[5:].strip()
                            if not raw or raw == "[DONE]":
                                continue
                            ev = json.loads(raw)
                            kind = ev.get("type")
                            if kind == "message_start":
                                msg = ev.get("message") or {}
                                served = msg.get("model")
                                for k, v in (msg.get("usage") or {}).items():
                                    if isinstance(v, int):
                                        usage[k] = v
                            elif kind == "content_block_delta":
                                delta = ev.get("delta") or {}
                                if delta.get("type") == "text_delta" and delta.get("text"):
                                    if ttft_ms is None:
                                        ttft_ms = max(1, int((time.monotonic() - started) * 1000))
                                    chunks.append(delta["text"])
                            elif kind == "message_delta":
                                for k, v in (ev.get("usage") or {}).items():
                                    if isinstance(v, int):
                                        usage[k] = v
                            elif kind == "error":
                                return CallResult(model, "error", "".join(chunks), ttft_ms, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, served, error=f"stream_error:{ev.get('error')}")
                elapsed_ms = max(1, int((time.monotonic() - started) * 1000))
                cached = int(usage.get("cache_read_input_tokens", 0))
                in_tok = int(usage.get("input_tokens", 0)) + cached
                out_tok = int(usage.get("output_tokens", 0))
                return CallResult(model, "ok", "".join(chunks), ttft_ms or elapsed_ms, elapsed_ms, in_tok, out_tok, cached, retries_429, served)
            except Exception as exc:
                if attempt < len(DEFAULT_429_DELAYS):
                    await asyncio.sleep(1.0)
                    continue
                return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error=f"{type(exc).__name__}:{exc}")
        return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error="exhausted")

    async def _call_openai(
        self,
        model: str,
        system_prompt: str,
        user_prompt: str,
        *,
        image_path: str | None,
        max_tokens: int,
        timeout_sec: float,
    ) -> CallResult:
        headers = {
            "Authorization": f"Bearer {self.openai_key}",
            "Content-Type": "application/json",
        }
        if image_path:
            b64 = base64.b64encode(Path(image_path).read_bytes()).decode("ascii")
            input_payload: Any = [
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": user_prompt},
                        {"type": "input_image", "image_url": f"data:image/png;base64,{b64}"},
                    ],
                }
            ]
        else:
            input_payload = user_prompt
        payload: dict[str, Any] = {
            "model": model,
            "instructions": system_prompt,
            "input": input_payload,
            "max_output_tokens": max_tokens,
            "stream": True,
            "store": False,
        }
        retries_429 = 0
        started = time.monotonic()
        for attempt in range(len(DEFAULT_429_DELAYS) + 1):
            ttft_ms: int | None = None
            chunks: list[str] = []
            usage: dict[str, Any] = {}
            served: str | None = None
            completed_text = ""
            try:
                async with httpx.AsyncClient(timeout=httpx.Timeout(timeout_sec, connect=10.0), trust_env=False) as client:
                    async with client.stream("POST", OPENAI_URL, headers=headers, json=payload) as resp:
                        if resp.status_code == 429:
                            retries_429 += 1
                            if attempt < len(DEFAULT_429_DELAYS):
                                ra = resp.headers.get("retry-after")
                                delay = float(ra) if ra and ra.replace(".", "", 1).isdigit() else DEFAULT_429_DELAYS[attempt]
                                await asyncio.sleep(min(delay, 30.0))
                                continue
                            return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error="http_429")
                        if resp.status_code != 200:
                            body = (await resp.aread()).decode("utf-8", errors="replace")[:250]
                            return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error=f"http_{resp.status_code}:{body}")
                        async for line in resp.aiter_lines():
                            if not line.startswith("data:"):
                                continue
                            raw = line[5:].strip()
                            if not raw or raw == "[DONE]":
                                continue
                            ev = json.loads(raw)
                            kind = ev.get("type")
                            if kind == "response.created":
                                served = (ev.get("response") or {}).get("model")
                            elif kind == "response.output_text.delta":
                                delta = ev.get("delta") or ""
                                if delta:
                                    if ttft_ms is None:
                                        ttft_ms = max(1, int((time.monotonic() - started) * 1000))
                                    chunks.append(delta)
                            elif kind in ("response.completed", "response.incomplete"):
                                res_obj = ev.get("response") or {}
                                served = res_obj.get("model") or served
                                usage = res_obj.get("usage") or {}
                                if not chunks:
                                    for item in res_obj.get("output") or []:
                                        for c in item.get("content") or []:
                                            if c.get("type") == "output_text" and c.get("text"):
                                                completed_text += c["text"]
                            elif kind in ("error", "response.failed"):
                                return CallResult(model, "error", "".join(chunks), ttft_ms, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, served, error=f"stream_error:{str(ev)[:200]}")
                elapsed_ms = max(1, int((time.monotonic() - started) * 1000))
                text_out = "".join(chunks) or completed_text
                in_tok = int(usage.get("input_tokens") or 0)
                out_tok = int(usage.get("output_tokens") or 0)
                cached = int((usage.get("input_tokens_details") or {}).get("cached_tokens") or 0)
                return CallResult(model, "ok", text_out, ttft_ms or elapsed_ms, elapsed_ms, in_tok, out_tok, cached, retries_429, served)
            except Exception as exc:
                if attempt < len(DEFAULT_429_DELAYS):
                    await asyncio.sleep(1.0)
                    continue
                return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error=f"{type(exc).__name__}:{exc}")
        return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, retries_429, error="exhausted")

    async def _call_codebuddy(
        self,
        model: str,
        system_prompt: str,
        user_prompt: str,
        *,
        timeout_sec: float,
    ) -> CallResult:
        started = time.monotonic()
        env = dict(os.environ, CODEBUDDY_CODE_DISABLE_BACKGROUND_TASKS="1")
        cmd = [
            self.codebuddy_bin,
            "-p",
            "--tools",
            "",
            "--no-session-persistence",
            "--output-format",
            "stream-json",
            "--include-partial-messages",
            "--system-prompt",
            system_prompt,
            "--model",
            model,
        ]
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
            )
            assert proc.stdin is not None and proc.stdout is not None and proc.stderr is not None
            proc.stdin.write(user_prompt.encode("utf-8"))
            await proc.stdin.drain()
            proc.stdin.close()

            ttft_ms: int | None = None
            streamed: list[str] = []
            fallback_result = ""

            async def _read_stdout() -> None:
                nonlocal ttft_ms, fallback_result
                while True:
                    raw_line = await proc.stdout.readline()  # type: ignore[union-attr]
                    if not raw_line:
                        break
                    parsed = _parse_codebuddy_line(raw_line.decode("utf-8", errors="replace"))
                    if parsed is None:
                        continue
                    txt, is_delta = parsed
                    if ttft_ms is None:
                        ttft_ms = max(1, int((time.monotonic() - started) * 1000))
                    if is_delta:
                        streamed.append(txt)
                    else:
                        fallback_result = txt

            await asyncio.wait_for(_read_stdout(), timeout=timeout_sec)
            rc = await asyncio.wait_for(proc.wait(), timeout=15.0)
            elapsed_ms = max(1, int((time.monotonic() - started) * 1000))
            if rc != 0:
                err = (await proc.stderr.read()).decode("utf-8", errors="replace")[:250]
                return CallResult(model, "error", "", ttft_ms, elapsed_ms, 0, 0, 0, 0, error=f"exit_{rc}:{err}")
            raw_out = ("".join(streamed) or fallback_result).strip()
            dsml = re.search(r'<｜｜DSML｜｜ parameter name="content"[^>]*>([\s\S]*?)</｜｜DSML｜｜ parameter>', raw_out)
            if dsml:
                raw_out = dsml.group(1).strip()
            if not raw_out:
                return CallResult(model, "error", "", ttft_ms, elapsed_ms, 0, 0, 0, 0, error="empty_output")
            return CallResult(
                model=model,
                status="ok",
                text=raw_out,
                ttft_ms=ttft_ms or elapsed_ms,
                elapsed_ms=elapsed_ms,
                input_tokens=estimate_tokens(system_prompt) + estimate_tokens(user_prompt),
                output_tokens=estimate_tokens(raw_out),
                cached_tokens=0,
                retries_429=0,
                served_by=model,
            )
        except asyncio.TimeoutError:
            return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, 0, error="timeout")
        except Exception as exc:
            return CallResult(model, "error", "", None, int((time.monotonic() - started) * 1000), 0, 0, 0, 0, error=f"{type(exc).__name__}:{exc}")
