"""Task routing and first-party text API transports; injectable HTTP for offline tests."""
from __future__ import annotations

import asyncio
from decimal import Decimal, ROUND_CEILING
import json
import os
from pathlib import Path

import httpx

from api_budget import CreditLedger, Denied

CONFIG = json.loads(Path(__file__).with_name("model_routes.json").read_text())
ENDPOINTS = {"anthropic": "https://api.anthropic.com/v1/messages", "openai": "https://api.openai.com/v1/responses"}


def vendor(model: str) -> str:
    return "anthropic" if model.startswith("claude-") else "openai" if model.startswith("gpt-") else "unknown"


def route(task: str, requested: str, format_attempt: int = 0) -> str | None:
    group = CONFIG["tasks"].get(task)
    if group is None or vendor(requested) == "unknown":
        return None
    model = CONFIG["models"][group]
    # Older persisted grades may have an OpenAI draft. Honour their independently
    # chosen Anthropic reviewer instead of silently reviewing with OpenAI again.
    if vendor(model) != vendor(requested):
        model = CONFIG["models"]["draft" if vendor(requested) == "anthropic" else "review"]
    if format_attempt == 2 and vendor(model) == "anthropic" and group != "planning":
        model = CONFIG["models"]["planning"]
    elif format_attempt == 2 and vendor(model) == "openai":
        model = CONFIG["models"]["review_upgrade"]
    return model


class ApiFailure(Exception):
    pass


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
    def __init__(self, ledger=None, client_factory=None, keys=None):
        self.ledger = ledger or CreditLedger(Path(os.environ.get("COACH_API_STATE_DIR", str(Path.home() / "Coach/state"))).expanduser())
        self.client_factory = client_factory or (lambda: httpx.AsyncClient(timeout=httpx.Timeout(20, connect=5), trust_env=False, follow_redirects=False))
        self.keys = keys if keys is not None else {
            "anthropic": os.environ.get("COACH_ANTHROPIC_API_KEY", ""),
            "openai": os.environ.get("COACH_OPENAI_API_KEY", "")}

    async def events(self, task: str, requested: str, system: str, prompt: str, max_tokens: int | None, format_attempt=0):
        model = route(task, requested, format_attempt)
        if model is None:
            raise Denied("unrouted_task")
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
        reservation = self.ledger.reserve(provider, key, maximum)
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
        try:
            async with asyncio.timeout(175):
                async with self.client_factory() as client:
                    async with client.stream("POST", ENDPOINTS[provider], headers=headers, json=payload) as response:
                        if response.status_code != 200:
                            # Never read an error message containing a key, source text or prompt.
                            raise ApiFailure(f"api_http_{response.status_code}")
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
                                    text.append(event["delta"])
                                    yield {"kind": "text", "text": event["delta"]}
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
            self.ledger.settle(provider, reservation, actual)
            if finish != "end_turn" or not text:
                raise ApiFailure("api_incomplete")
            yield {"kind": "complete", "text": "".join(text), "served_by": served,
                   "usage": {"prompt_tokens": usage["input_tokens"], "completion_tokens": usage["output_tokens"],
                             "total_tokens": usage["input_tokens"] + usage["output_tokens"]}}
        except (httpx.HTTPError, TimeoutError, ValueError, KeyError, TypeError, AttributeError, OSError) as error:
            raise ApiFailure("api_" + type(error).__name__) from None
