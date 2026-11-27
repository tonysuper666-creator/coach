"""Durable, single-request batches for background supply jobs.

The job key survives claims/restarts. Never resubmit an ambiguous POST. Files are
private application state (including cached answers), not diagnostic artifacts.
"""
from __future__ import annotations

from contextlib import contextmanager
import fcntl
import hashlib
import json
import math
import os
import re
import time

import httpx

from api_budget import Denied, atomic_json
from api_route import ApiFailure, cost, route, vendor

GUIDE_TASK = "literature_guide"
COMPARISON_TASK = "literature_comparison"
SAYINGS_TASK = "concept_sayings"
# Friends may submit these as Haiku Batch jobs; each result is namespaced and reserved against the friend's own grant.
FRIEND_BATCH_TASKS = frozenset({GUIDE_TASK, COMPARISON_TASK, SAYINGS_TASK})
FRIEND_BATCH_LABELS = {GUIDE_TASK: "文献导读", COMPARISON_TASK: "文献对比", SAYINGS_TASK: "各书说法"}


def friend_batch_task(task, friend):
    """Literature tasks are Batch-only for every caller; concept sayings only for friends, so owner jobs keep their old keys."""
    return task in FRIEND_BATCH_TASKS and (friend is not None or task != SAYINGS_TASK)

TASKS = frozenset({*FRIEND_BATCH_TASKS, "gen_mcq", "repair_mcq_sources", "blind_review",
                   "gen_constructed", "repair_constructed_sources", "review_constructed",
                   "BOOK_SECTION_BATCH", "BOOK_EXERCISE_HINTS", "BOOK_MATH_VERIFY", "BOOK_MATH_REPAIR",
                   "practice_grade", "practice_diagnose", "practice_variant", "course_mock_item",
                   "exam_point_drill", "chapter_open_question"})
ROOTS = {"anthropic": "https://api.anthropic.com/v1", "openai": "https://api.openai.com/v1"}


class BatchPending(Exception):
    pass


class BatchRouter:
    def __init__(self, router, clock=time.time):
        self.router, self.clock = router, clock
        self.directory = router.ledger.state / "api-batches"

    def path(self, key):
        if not isinstance(key, str) or not re.fullmatch(r"[a-f0-9]{64}", key):
            raise Denied("invalid_batch_key")
        return self.directory / (key + ".json")

    @contextmanager
    def locked(self, key):
        path = self.path(key)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd = os.open(path.with_suffix(".lock"), os.O_CREAT | os.O_RDWR, 0o600)
        try:
            try:
                # Never block this event loop on a lock held across another await.
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise BatchPending() from None
            yield path
        finally:
            os.close(fd)

    @staticmethod
    def read(path):
        if not path.exists():
            return None
        try:
            data = json.loads(path.read_text())
            if data["version"] != 1 or data["phase"] not in {"submitting", "pending", "fallback", "complete"}:
                raise ValueError()
            if not all(isinstance(data[k], str) for k in ("task", "model", "custom_id")):
                raise ValueError()
            if data["phase"] == "pending":
                if not all(type(data[k]) in (int, float) and math.isfinite(data[k]) for k in ("created", "next_poll")):
                    raise ValueError()
                if not isinstance(data["batch_id"], str) or not isinstance(data["reservation"], list) or len(data["reservation"]) != 3:
                    raise ValueError()
            if data["phase"] == "complete":
                result = data["result"]
                if (result["transport"] not in {"api", "subscription"} or
                        not all(isinstance(result[k], str) for k in ("text", "served_by")) or
                        not isinstance(result["usage"], dict)):
                    raise ValueError()
            return data
        except (ValueError, KeyError, TypeError):
            raise Denied("batch_state_unreadable") from None

    async def run(self, key, task, requested, system, prompt, max_tokens, attempt=0, *, friend=None, device=None):
        """Return a cached completion, defer, or request same-vendor CLI fallback.

        A key names one immutable job/task/format-attempt, not today's prompt.
        Recomputed avoid lists on resume must not create a second submission.
        """
        with self.locked(key) as path:
            state = self.read(path)
            model = route(task, requested, attempt)
            if task not in TASKS or not model:
                raise Denied("unsupported_batch_task")
            if state is not None:
                if state.get("task") != task or state.get("model") != model:
                    raise Denied("batch_identity_mismatch")
                if state["phase"] == "complete":
                    return state["result"]
                if state["phase"] in {"submitting", "fallback"}:
                    raise ApiFailure(state.get("reason", "batch_submit_unknown"))
            else:
                state = {"version": 1, "phase": "submitting", "task": task, "model": model,
                         "created": self.clock(), "custom_id": key,
                         "input_sha256": hashlib.sha256((system + "\0" + prompt).encode()).hexdigest()}
                # Persist intent before reserving/sending. A crash here fails closed.
                atomic_json(path, state)
                submitting_http = False
                try:
                    default_limit = 8192 if model == "claude-haiku-5-5" else 4000
                    limit = max_tokens if max_tokens is not None else default_limit
                    bound = len(system.encode()) + len(prompt.encode()) + 4096
                    if type(limit) is not int or not 1 <= limit <= 16000 or bound > 90000:
                        raise Denied("unsupported_batch_size")
                    provider = vendor(model)
                    maximum = cost(model, {"input_tokens": bound, "output_tokens": limit}, batch=True)
                    state["reservation"] = await self.router.reserve_waiting(provider, self.router.keys.get(provider, ""), maximum,
                                                                             friend=friend, device=device, tokens=bound + limit)
                    atomic_json(path, state)
                    async with self.router.client_factory() as client:
                        submitting_http = True
                        state["batch_id"] = await self.submit(client, provider, key, model, system, prompt, limit)
                        submitting_http = False
                    state["phase"] = "pending"
                    state["next_poll"] = self.clock() + 60
                    atomic_json(path, state)
                except httpx.HTTPError:
                    if submitting_http:
                        self.failed(path, state, "batch_submit_ambiguous")
                        raise ApiFailure("batch_submit_ambiguous") from None
                    self.failed(path, state, "batch_submit_unavailable")
                    raise ApiFailure("batch_submit_unavailable") from None
                except (ApiFailure, Denied, ValueError, KeyError, TypeError) as error:
                    if friend_batch_task(task, friend) and isinstance(error, Denied) and "reservation" not in state:
                        # No upstream submission. A later poll may proceed after the grant resets.
                        path.unlink()
                        raise
                    reason = "batch_submit_ambiguous" if str(error) == "batch_submit_ambiguous" else "batch_submit_unavailable"
                    self.failed(path, state, reason)
                    raise ApiFailure(reason) from None
                raise BatchPending()
            if self.clock() < state["next_poll"]:
                raise BatchPending()
            provider = vendor(model)
            try:
                async with self.router.client_factory() as client:
                    result = await self.poll(client, provider, state)
                if result is None:
                    state["next_poll"] = self.clock() + 60
                    atomic_json(path, state)
                    if self.clock() - state["created"] > 25 * 3600:
                        raise ApiFailure("batch_deadline")
                    raise BatchPending()
                # Idempotent across a crash between the ledger commit and cache write.
                result.pop("cost")
                state.update(phase="complete", result=result)
                atomic_json(path, state)
                return result
            except httpx.HTTPError:
                # Polling is read-only; a transient failure must not duplicate work.
                state["next_poll"] = self.clock() + 60
                atomic_json(path, state)
                if self.clock() - state["created"] > 25 * 3600:
                    self.failed(path, state, "batch_deadline")
                    raise ApiFailure("batch_deadline") from None
                raise BatchPending() from None
            except (ApiFailure, Denied, ValueError, KeyError, TypeError, AttributeError):
                self.failed(path, state, "batch_result_unavailable")
                raise ApiFailure("batch_result_unavailable") from None

    @staticmethod
    def failed(path, state, reason):
        state.update(phase="fallback", reason=reason)
        atomic_json(path, state)

    def cache_fallback(self, key, result):
        with self.locked(key) as path:
            state = self.read(path)
            if state is not None and state["phase"] in {"submitting", "fallback"}:
                state.update(phase="complete", result=result)
                atomic_json(path, state)

    def headers(self, provider):
        key = self.router.keys.get(provider, "")
        if not key:
            raise Denied("missing_key")
        return {"x-api-key": key, "anthropic-version": "2023-06-01"} if provider == "anthropic" else {"Authorization": "Bearer " + key}

    async def request(self, client, provider, method, path, **kwargs):
        response = await client.request(method, ROOTS[provider] + path, headers=self.headers(provider), **kwargs)
        if response.status_code != 200:
            # No upstream bodies, URLs or authentication material in exceptions.
            raise ApiFailure("batch_http_" + str(response.status_code))
        return response

    @staticmethod
    def identifier(value):
        if not isinstance(value, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,200}", value):
            raise ApiFailure("batch_invalid_id")
        return value

    async def submit(self, client, provider, key, model, system, prompt, limit):
        if provider == "anthropic":
            params = {"model": model, "system": system, "messages": [{"role": "user", "content": prompt}], "max_tokens": limit}
            response = await self.request(client, provider, "POST", "/messages/batches",
                                          json={"requests": [{"custom_id": key, "params": params}]})
        else:
            body = {"model": model, "instructions": system, "input": prompt, "max_output_tokens": limit, "store": False}
            row = {"custom_id": key, "method": "POST", "url": "/v1/responses", "body": body}
            upload = await self.request(client, provider, "POST", "/files", data={"purpose": "batch"},
                                        files={"file": ("coach.jsonl", (json.dumps(row) + "\n").encode(), "application/jsonl")})
            file_id = self.identifier(upload.json()["id"])
            response = await self.request(client, provider, "POST", "/batches",
                                          json={"input_file_id": file_id, "endpoint": "/v1/responses", "completion_window": "24h"})
        try:
            return self.identifier(response.json()["id"])
        except (ApiFailure, ValueError, KeyError, TypeError):
            # The create call was accepted (HTTP 200) but returned no usable id: a batch may exist upstream.
            raise ApiFailure("batch_submit_ambiguous") from None

    async def poll(self, client, provider, state):
        batch_id = self.identifier(state["batch_id"])
        prefix = "/messages/batches/" if provider == "anthropic" else "/batches/"
        status = (await self.request(client, provider, "GET", prefix + batch_id)).json()
        phase = status.get("processing_status" if provider == "anthropic" else "status")
        if phase in {"validating", "in_progress", "finalizing", "canceling", "cancelling"}:
            return None
        if phase != ("ended" if provider == "anthropic" else "completed"):
            raise ApiFailure("batch_terminal_failure")
        # Construct trusted-origin paths ourselves; never follow a results_url.
        result_path = prefix + batch_id + "/results" if provider == "anthropic" else "/files/" + self.identifier(status["output_file_id"]) + "/content"
        response = await self.request(client, provider, "GET", result_path)
        rows = [json.loads(line) for line in response.text.splitlines() if line.strip()]
        matches = [row for row in rows if row.get("custom_id") == state["custom_id"]]
        if len(matches) != 1:
            raise ApiFailure("batch_result_identity")
        row = matches[0]
        if provider == "anthropic":
            if row["result"]["type"] != "succeeded":
                raise ApiFailure("batch_item_failed")
            msg = row["result"]["message"]
            text = "".join(b["text"] for b in msg["content"] if b["type"] == "text")
            finish = msg.get("stop_reason") == "end_turn"
        else:
            if row.get("error") or row["response"]["status_code"] != 200:
                raise ApiFailure("batch_item_failed")
            msg = row["response"]["body"]
            text = "".join(b["text"] for item in msg["output"] if item["type"] == "message"
                           for b in item["content"] if b["type"] == "output_text")
            finish = msg.get("status") == "completed"
        served = msg.get("model")
        model = state["model"]
        if not isinstance(served, str) or not (served == model or served.startswith(model + "-")):
            raise ApiFailure("batch_model_mismatch")
        usage = dict(msg.get("usage") or {})
        if provider == "anthropic":
            if usage.get("cache_creation_input_tokens", 0):
                raise ApiFailure("unexpected_cache_write")
            usage["cached_tokens"] = usage.get("cache_read_input_tokens", 0)
            if type(usage.get("input_tokens")) is not int or type(usage["cached_tokens"]) is not int:
                raise ApiFailure("missing_usage")
            usage["input_tokens"] += usage["cached_tokens"]
        else:
            usage["cached_tokens"] = (usage.get("input_tokens_details") or {}).get("cached_tokens", 0)
        actual = cost(model, usage, batch=True)
        # Even an incomplete answer may have billable usage.
        self.router.ledger.settle(provider, tuple(state["reservation"]), actual, idempotent=True, usage=usage)
        if not finish or not text:
            raise ApiFailure("batch_incomplete")
        return {"text": text, "served_by": served, "transport": "api", "cost": actual,
                "usage": {"prompt_tokens": usage["input_tokens"], "completion_tokens": usage["output_tokens"],
                          "total_tokens": usage["input_tokens"] + usage["output_tokens"]}}
