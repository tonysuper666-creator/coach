"""Task routing and first-party text API transports; injectable HTTP for offline tests."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from decimal import Decimal, ROUND_CEILING
from email.utils import parsedate_to_datetime
import json
import math
import os
from pathlib import Path
import time

import httpx

from api_budget import CreditLedger, Denied

CONFIG = json.loads(Path(__file__).with_name("model_routes.json").read_text())
ENDPOINTS = {"anthropic": "https://api.anthropic.com/v1/messages", "openai": "https://api.openai.com/v1/responses"}
INTERACTIVE_TASKS = frozenset({
    "TEACH", "READER_SELECTION", "SOURCE_LOOKUP", "PREPARE_TEACH",
    "COLLECTION_QA", "STUDY_MODE_STREAM", "practice_grade", "practice_diagnose",
})
REVIEW_GROUPS = frozenset({"review", "review_openai", "review_upgrade"})
DEFAULT_429_DELAYS = (0.5, 1.5)
DEFAULT_429_BUDGET_SEC = 45.0


def _env_int(name: str, default: int, minimum: int = 1) -> int:
    try:
        return max(minimum, int(os.environ.get(name, str(default))))
    except ValueError:
        return max(minimum, default)


def _env_float(name: str, default: float, minimum: float = 0.0) -> float:
    try:
        v = float(os.environ.get(name, str(default)))
    except ValueError:
        return max(minimum, default)
    if not math.isfinite(v) or v < minimum:
        return max(minimum, default)
    return v


def _env_delays(name: str, default: tuple[float, ...]) -> tuple[float, ...]:
    raw = os.environ.get(name)
    if raw is None:
        return default
    parts = [p.strip() for p in raw.split(",") if p.strip()]
    if not parts:
        return ()
    out = []
    for p in parts:
        try:
            v = float(p)
        except ValueError:
            return default
        if not math.isfinite(v) or v < 0:
            return default
        out.append(v)
    return tuple(out)


def _parse_retry_after_seconds(raw: str | None, wall_clock=time.time) -> float | None:
    if raw is None:
        return None
    text = raw.strip()
    if not text:
        return None
    try:
        parsed = float(text)
    except ValueError:
        try:
            dt = parsedate_to_datetime(text)
        except (TypeError, ValueError, IndexError, OverflowError):
            return None
        if dt.tzinfo is None:
            return None
        return max(0.0, dt.timestamp() - wall_clock())
    if not math.isfinite(parsed) or parsed < 0:
        return None
    return parsed


class ApiGate:
    """Foreground-prioritized API concurrency gate so bulk synthesis never starves interactive Q&A."""

    def __init__(self, limit: int, bulk_limit: int, clock=time.monotonic):
        self.limit = max(2, limit)
        self.bulk_limit = min(self.limit - 1, max(1, bulk_limit))
        self._clock = clock
        self.active = 0
        self.bulk_active = 0
        self.fg_waiting = 0
        self.bulk_cooldown_until = 0.0
        self._cv: asyncio.Condition | None = None
        self._cv_loop: asyncio.AbstractEventLoop | None = None

    def _cond(self) -> asyncio.Condition:
        loop = asyncio.get_running_loop()
        if self._cv is None or self._cv_loop is not loop:
            self._cv = asyncio.Condition()
            self._cv_loop = loop
        return self._cv

    def note_rate_limit(self, seconds: float) -> None:
        if seconds > 0:
            self.bulk_cooldown_until = max(self.bulk_cooldown_until, self._clock() + seconds)

    async def acquire(self, *, bulk: bool) -> None:
        cv = self._cond()
        async with cv:
            if not bulk:
                self.fg_waiting += 1
            try:
                while True:
                    if bulk:
                        remaining = self.bulk_cooldown_until - self._clock()
                        if remaining > 0:
                            try:
                                await asyncio.wait_for(cv.wait(), timeout=remaining)
                            except asyncio.TimeoutError:
                                pass
                            continue
                        if self.active < self.limit and self.bulk_active < self.bulk_limit and self.fg_waiting == 0:
                            self.active += 1
                            self.bulk_active += 1
                            return
                    else:
                        if self.active < self.limit:
                            self.active += 1
                            return
                    await cv.wait()
            finally:
                if not bulk:
                    self.fg_waiting -= 1
                    cv.notify_all()

    async def release(self, *, bulk: bool) -> None:
        cv = self._cond()
        async with cv:
            self.active = max(0, self.active - 1)
            if bulk:
                self.bulk_active = max(0, self.bulk_active - 1)
            cv.notify_all()

    @asynccontextmanager
    async def slot(self, *, bulk: bool):
        await self.acquire(bulk=bulk)
        try:
            yield
        finally:
            await self.release(bulk=bulk)


def vendor(model: str) -> str:
    return "anthropic" if model.startswith("claude-") else "openai" if model.startswith("gpt-") else "unknown"


def route(task: str, requested: str, format_attempt: int = 0) -> str | None:
    group = CONFIG["tasks"].get(task)
    if group is None or vendor(requested) == "unknown":
        return None
    if group in REVIEW_GROUPS:
        # Independent cross-vendor review: honor whichever vendor the caller chose to inspect the draft.
        if vendor(requested) == "anthropic":
            return CONFIG["models"]["review"]
        return CONFIG["models"]["review_upgrade"] if format_attempt == 2 else CONFIG["models"].get("review_openai", CONFIG["models"]["review"])
    model = CONFIG["models"][group]
    if format_attempt == 2:
        if vendor(model) == "anthropic" and model == "claude-haiku-5-5":
            model = CONFIG["models"]["planning"]
        elif vendor(model) == "openai" and model == "gpt-6.2-sol":
            model = CONFIG["models"].get("review_upgrade", "gpt-6.2-astra")
    return model


def friend_model(requested: str) -> str:
    batch_model = CONFIG["models"].get("friend_batch", "claude-haiku-5-5")
    if requested == batch_model:
        return batch_model
    return CONFIG["models"].get("friend", CONFIG["models"]["teaching"])


def friend_fallback_model(primary_model: str) -> str | None:
    fb = CONFIG.get("fallbacks", {}).get("friend")
    if not fb or fb == primary_model or fb not in CONFIG.get("prices_usd_per_million", {}):
        return None
    if vendor(fb) != "anthropic":
        return None
    return fb


def fallback_model(task: str, primary_model: str) -> str | None:
    group = CONFIG["tasks"].get(task)
    if group is None or group in REVIEW_GROUPS or group == "friend":
        return None
    fb = CONFIG.get("fallbacks", {}).get(group)
    if not fb or fb not in CONFIG.get("prices_usd_per_million", {}):
        return None
    if vendor(fb) == vendor(primary_model) or vendor(fb) == "unknown":
        return None
    return fb


class ApiFailure(Exception):
    def __init__(self, reason: str, *, retry_after_sec: int | None = None):
        super().__init__(reason)
        self.retry_after_sec = retry_after_sec


def cost(model: str, usage: dict, batch: bool = False) -> int:
    """Integer microdollars; unknown or incomplete usage is never treated as zero."""
    p = CONFIG["prices_usd_per_million"][model]
    required = ("input_tokens", "output_tokens")
    if any(type(usage.get(k)) is not int or usage[k] < 0 for k in required):
        raise ApiFailure("missing_usage")
    cached = usage.get("cached_tokens", 0)
    if type(cached) is not int or not 0 <= cached <= usage["input_tokens"]:
        raise ApiFailure("invalid_usage")
    # Requests are plain text, no explicit cache writes, tools, regions or Fast mode.
    # Larger/unsupported prompts go to the subscription transport before sending.
    if usage["input_tokens"] > 100_000:
        raise ApiFailure("unsupported_context_price")
    result = ((usage["input_tokens"] - cached) * Decimal(str(p["input"])) +
              cached * Decimal(str(p["cached"])) + usage["output_tokens"] * Decimal(str(p["output"])))
    if batch:
        result /= 2
    return int(result.to_integral_value(rounding=ROUND_CEILING))


class ApiRouter:
    def __init__(
        self,
        ledger=None,
        client_factory=None,
        keys=None,
        *,
        concurrency: int | None = None,
        bulk_concurrency: int | None = None,
        retry_429_delays: tuple[float, ...] | None = None,
        retry_429_budget: float | None = None,
        sleep=None,
        clock=time.monotonic,
        wall_clock=time.time,
        cross_vendor_fallback: bool = True,
    ):
        self.cross_vendor_fallback = cross_vendor_fallback
        self.ledger = ledger or CreditLedger(Path(os.environ.get("COACH_API_STATE_DIR", str(Path.home() / "Coach/state"))).expanduser())
        self.client_factory = client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(20, connect=5), trust_env=False, follow_redirects=False))
        self.keys = keys if keys is not None else {
            "anthropic": os.environ.get("COACH_ANTHROPIC_API_KEY", ""),
            "openai": os.environ.get("COACH_OPENAI_API_KEY", "")}
        limit = concurrency if concurrency is not None else _env_int("BRIDGE_API_CONCURRENCY", 4, minimum=2)
        bulk_limit = bulk_concurrency if bulk_concurrency is not None else _env_int("BRIDGE_API_BULK_CONCURRENCY", 2, minimum=1)
        self._clock = clock
        self._wall_clock = wall_clock
        self._gates = {
            "anthropic": ApiGate(limit, bulk_limit, clock=clock),
            "openai": ApiGate(limit, bulk_limit, clock=clock),
        }
        self.retry_429_delays = (
            tuple(retry_429_delays) if retry_429_delays is not None else _env_delays("BRIDGE_API_429_DELAYS", DEFAULT_429_DELAYS)
        )
        self.retry_429_budget = (
            max(0.0, float(retry_429_budget))
            if retry_429_budget is not None
            else _env_float("BRIDGE_API_429_BUDGET_SEC", DEFAULT_429_BUDGET_SEC, minimum=0.0)
        )
        self._sleep = sleep or asyncio.sleep

    def _gate(self, provider: str) -> ApiGate:
        gate = self._gates.get(provider)
        if gate is None:
            gate = ApiGate(4, 2, clock=self._clock)
            self._gates[provider] = gate
        return gate

    def fallback_target(self, task: str, primary_model: str) -> str | None:
        if not self.cross_vendor_fallback:
            return None
        cand = fallback_model(task, primary_model)
        if cand and self.keys.get(vendor(cand), ""):
            return cand
        return None

    async def fallback_events(self, task: str, primary_model: str, system: str, prompt: str, max_tokens: int | None):
        fb = self.fallback_target(task, primary_model)
        if fb is None:
            raise Denied("no_cross_vendor_fallback")
        async for event in self._stream_model(task, fb, system, prompt, max_tokens, friend=None, device=None):
            if event.get("kind") == "complete":
                event = dict(event, fallback=True)
            yield event

    async def events(self, task: str, requested: str, system: str, prompt: str, max_tokens: int | None, format_attempt=0, friend=None, device=None):
        if friend is not None and (task != "READER_SELECTION" or vendor(requested) != "anthropic"):
            raise Denied("invalid_friend_route")
        model = friend_model(requested) if friend is not None else route(task, requested, format_attempt)
        if model is None:
            raise Denied("unrouted_task")
        fb = None
        if friend is None:
            fb = self.fallback_target(task, model)
        else:
            cand = friend_fallback_model(model)
            if cand and self.keys.get("anthropic", ""):
                fb = cand
        yielded_text = False
        try:
            async for event in self._stream_model(task, model, system, prompt, max_tokens, friend=friend, device=device):
                if event.get("kind") == "text" and event.get("text"):
                    yielded_text = True
                yield event
            return
        except Denied:
            if friend is not None or yielded_text or fb is None:
                raise
        except ApiFailure:
            if yielded_text or fb is None:
                raise
        async for event in self._stream_model(task, fb, system, prompt, max_tokens, friend=friend, device=device):
            if event.get("kind") == "complete":
                event = dict(event, fallback=True)
            yield event

    async def _stream_model(self, task: str, model: str, system: str, prompt: str, max_tokens: int | None, *, friend=None, device=None):
        provider = vendor(model)
        key = self.keys.get(provider, "")
        output_limit = max_tokens if max_tokens is not None else 4000
        if type(output_limit) is not int or not 1 <= output_limit <= 16000:
            raise Denied("unsupported_output_limit")
        # UTF-8 bytes bound text tokens conservatively; reserve overhead too. Avoid
        # long-context pricing entirely. Reservation precedes every network attempt.
        input_bound = len(system.encode()) + len(prompt.encode()) + 4096
        if input_bound > 90_000:
            raise Denied("unsupported_context_price")
        maximum = cost(model, {"input_tokens": input_bound, "output_tokens": output_limit})
        bulk = friend is None and task not in INTERACTIVE_TASKS
        gate = self._gate(provider)
        async with gate.slot(bulk=bulk):
            reservation = self.ledger.reserve(provider, key, maximum, friend=friend, device=device, tokens=input_bound + output_limit)
            # Past this point a timeout, cancellation, HTTP error or missing terminal
            # usage retains the full reservation, including across process restarts.
            yield {"kind": "attempt", "model": model}
            if provider == "anthropic":
                headers = {"x-api-key": key, "anthropic-version": "2023-06-01"}
                payload = {"model": model, "system": system, "messages": [{"role": "user", "content": prompt}],
                           "max_tokens": output_limit, "stream": True}
            else:
                headers = {"Authorization": "Bearer " + key}
                payload = {"model": model, "instructions": system, "input": prompt, "max_output_tokens": output_limit,
                           "stream": True, "store": False, "service_tier": "default"}
            text, usage, served, finish, ended = [], {}, None, None, False
            started = self._clock()
            try:
                async with asyncio.timeout(175):
                    for attempt in range(len(self.retry_429_delays) + 1):
                        retry_delay = None
                        async with self.client_factory() as client:
                            async with client.stream("POST", ENDPOINTS[provider], headers=headers, json=payload) as response:
                                if response.status_code == 429:
                                    # Never read an error message containing a key, source text or prompt.
                                    parsed_ra = _parse_retry_after_seconds(response.headers.get("retry-after"), self._wall_clock)
                                    elapsed = max(0.0, self._clock() - started)
                                    remaining_budget = max(0.0, self.retry_429_budget - elapsed)
                                    if parsed_ra is not None:
                                        retry_sec = max(1, math.ceil(parsed_ra))
                                        if attempt < len(self.retry_429_delays) and parsed_ra <= remaining_budget:
                                            gate.note_rate_limit(parsed_ra)
                                            retry_delay = parsed_ra
                                        else:
                                            gate.note_rate_limit(min(parsed_ra, self.retry_429_budget))
                                            raise ApiFailure("api_http_429", retry_after_sec=retry_sec)
                                    else:
                                        if attempt < len(self.retry_429_delays):
                                            base_delay = self.retry_429_delays[attempt]
                                            if base_delay <= remaining_budget:
                                                gate.note_rate_limit(base_delay)
                                                retry_delay = base_delay
                                            else:
                                                raise ApiFailure("api_http_429")
                                        else:
                                            raise ApiFailure("api_http_429")
                                elif response.status_code != 200:
                                    # Never read an error message containing a key, source text or prompt.
                                    raise ApiFailure(f"api_http_{response.status_code}")
                                else:
                                    async for line in response.aiter_lines():
                                        if not line.startswith("data:"):
                                            continue
                                        raw = line[5:].strip()
                                        if raw == "[DONE]":
                                            break
                                        event = json.loads(raw)
                                        kind = event.get("type")
                                        if provider == "anthropic":
                                            if kind == "message_start":
                                                msg = event["message"]
                                                served = msg.get("model")
                                                usage.update(msg.get("usage", {}))
                                                yield {"kind": "meta", "served_by": served or model}
                                            elif kind == "content_block_delta" and event.get("delta", {}).get("type") == "text_delta":
                                                delta = event["delta"]["text"]
                                                if delta:
                                                    text.append(delta)
                                                    yield {"kind": "text", "text": delta}
                                            elif kind == "message_delta":
                                                usage.update(event.get("usage", {}))
                                                finish = event.get("delta", {}).get("stop_reason")
                                            elif kind == "message_stop":
                                                ended = True
                                                break
                                            elif kind == "error":
                                                raise ApiFailure("api_stream_error")
                                        else:
                                            if kind == "response.created":
                                                served = event["response"].get("model")
                                                yield {"kind": "meta", "served_by": served or model}
                                            elif kind == "response.output_text.delta":
                                                delta = event["delta"]
                                                if delta:
                                                    text.append(delta)
                                                    yield {"kind": "text", "text": delta}
                                            elif kind in ("response.completed", "response.incomplete"):
                                                result = event["response"]
                                                served = result.get("model")
                                                usage = result.get("usage") or {}
                                                usage["cached_tokens"] = (usage.get("input_tokens_details") or {}).get("cached_tokens", 0)
                                                finish = "end_turn" if kind == "response.completed" else "max_tokens"
                                                ended = True
                                                break
                                            elif kind in ("error", "response.failed"):
                                                raise ApiFailure("api_stream_error")
                        if retry_delay is None:
                            break
                        if retry_delay > 0:
                            await self._sleep(retry_delay)
                if not ended:
                    raise ApiFailure("api_no_terminal")
                if not isinstance(served, str) or not (served == model or served.startswith(model + "-")):
                    raise ApiFailure("api_model_mismatch")
                if provider == "anthropic":
                    # No cache_control is sent; any unexpected write usage closes this route.
                    if usage.get("cache_creation_input_tokens", 0):
                        raise ApiFailure("unexpected_cache_write")
                    if type(usage.get("input_tokens")) is not int or usage["input_tokens"] < 0:
                        raise ApiFailure("missing_usage")
                    usage["cached_tokens"] = usage.get("cache_read_input_tokens", 0)
                    usage["input_tokens"] = usage.get("input_tokens", -1) + usage["cached_tokens"]
                actual = cost(model, usage)
                self.ledger.settle(provider, reservation, actual, usage=usage)
                if finish != "end_turn" or not text:
                    raise ApiFailure("api_incomplete")
                yield {"kind": "complete", "text": "".join(text), "served_by": served,
                       "usage": {"prompt_tokens": usage["input_tokens"], "completion_tokens": usage["output_tokens"],
                                 "total_tokens": usage["input_tokens"] + usage["output_tokens"]}}
            except (httpx.HTTPError, TimeoutError, ValueError, KeyError, TypeError, AttributeError, OSError) as error:
                raise ApiFailure("api_" + type(error).__name__) from None
