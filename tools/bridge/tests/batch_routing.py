#!/usr/bin/env python3
"""Offline Batch contracts; synthetic inputs and MockTransport only."""
import asyncio
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import httpx
from api_batch import BatchRouter, BatchPending
from api_budget import CreditLedger, atomic_json, micros, Denied
from grant_fixture import fund
from api_route import ApiRouter, ApiFailure, cost
import subscription_bridge as bridge

KEY = "synthetic-not-a-real-key"
NOW = 1794787200


class BatchTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.now = NOW
        fund(self.root)
        self.ledger = CreditLedger(self.root, lambda: self.now)
        self.calls = []
        self.provider = "anthropic"
        self.phase = "ended"
        self.failure = None
        self.result_change = lambda row: row
        self.router = self.make_router()

    def tearDown(self):
        self.tmp.cleanup()

    def make_router(self, keys=None):
        api = ApiRouter(self.ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(self.handle)),
                        keys=keys if keys is not None else {"anthropic": KEY, "openai": KEY})
        return BatchRouter(api, lambda: self.now)

    def key(self, name="draft"):
        return hashlib.sha256(name.encode()).hexdigest()

    def handle(self, request):
        self.calls.append(request)
        if self.failure:
            return self.failure(request)
        if request.method == "POST":
            return httpx.Response(200, json={"id": "file_input" if request.url.path == "/v1/files" else "batch_one"})
        if request.url.path.endswith(("/results", "/content")):
            usage = {"input_tokens": 100, "output_tokens": 20}
            if self.provider == "anthropic":
                msg = {"model": "claude-opus-5-5", "content": [{"type": "text", "text": "synthetic answer"}],
                       "stop_reason": "end_turn", "usage": usage}
                row = {"custom_id": self.key(), "result": {"type": "succeeded", "message": msg}}
            else:
                msg = {"model": "gpt-6.2-sol", "status": "completed", "usage": usage,
                       "output": [{"type": "message", "content": [{"type": "output_text", "text": "synthetic answer"}]}]}
                row = {"custom_id": self.key(), "response": {"status_code": 200, "body": msg}, "error": None}
            # An unrelated row comes first: identity, rather than line order, is authoritative.
            unrelated = dict(row, custom_id="unrelated")
            return httpx.Response(200, text=json.dumps(unrelated) + "\n" + json.dumps(self.result_change(row)) + "\n")
        return httpx.Response(200, json={"processing_status": self.phase, "status": self.phase,
                                       "output_file_id": "file_output", "results_url": "https://untrusted.invalid/private"})

    async def run_batch(self, router=None):
        task, model = ("BOOK_MATH_VERIFY", "claude-opus-5-5") if self.provider == "anthropic" else ("gen_mcq", "gpt-6.2-sol")
        return await (router or self.router).run(self.key(), task, model, "synthetic system", "synthetic prompt", 100)

    async def submit(self):
        with self.assertRaises(BatchPending):
            await self.run_batch()
        state = self.router.read(self.router.path(self.key()))
        self.assertEqual("pending", state["phase"])
        self.assertGreater(self.summary()["reserved_microusd"], 0)
        return state

    def summary(self):
        return self.ledger.read()["months"]["2026-11"][self.provider]

    async def test_both_official_batch_contracts_restart_and_exactly_once_settlement(self):
        for provider in ("anthropic", "openai"):
            with self.subTest(provider=provider):
                # Independent durable jobs/providers, in the same ledger.
                self.provider = provider
                if provider == "openai":
                    self.router.path(self.key()).unlink()
                self.calls.clear()
                self.phase = "ended" if provider == "anthropic" else "completed"
                state = await self.submit()
                posts = len(self.calls)
                self.assertEqual(1 if provider == "anthropic" else 2, posts)
                first = self.calls[0]
                self.assertEqual("api.anthropic.com" if provider == "anthropic" else "api.openai.com", first.url.host)
                if provider == "anthropic":
                    payload = json.loads(first.content)["requests"][0]
                    self.assertEqual(self.key(), payload["custom_id"])
                    self.assertEqual("claude-opus-5-5", payload["params"]["model"])
                    self.assertNotIn("stream", payload["params"])
                else:
                    self.assertIn(b'name="purpose"\r\n\r\nbatch', first.content)
                    self.assertIn(b'"url": "/v1/responses"', first.content)
                    self.assertIn(b'"model": "gpt-6.2-sol"', first.content)
                    self.assertIn(b'"store": false', first.content)
                    self.assertEqual({"input_file_id": "file_input", "endpoint": "/v1/responses", "completion_window": "24h"},
                                     json.loads(self.calls[1].content))
                restarted = self.make_router()
                with self.assertRaises(BatchPending):
                    await self.run_batch(restarted)
                self.assertEqual(posts, len(self.calls), "poll cadence persists across restart")
                self.now += 61
                result = await self.run_batch(restarted)
                self.assertEqual("synthetic answer", result["text"])
                self.assertEqual("api", result["transport"])
                self.assertEqual(120, result["usage"]["total_tokens"])
                model = "claude-opus-5-5" if provider == "anthropic" else "gpt-6.2-sol"
                self.assertEqual(cost(model, {"input_tokens": 100, "output_tokens": 20}, batch=True), self.summary()["spent_microusd"])
                self.assertEqual(0, self.summary()["reserved_microusd"])
                self.assertEqual(1, self.summary()["calls"])
                before = len(self.calls)
                self.assertEqual(result, await self.run_batch(restarted))
                self.assertEqual(before, len(self.calls))
                self.assertTrue(all(r.url.host != "untrusted.invalid" for r in self.calls))
                self.assertEqual(0o600, restarted.path(self.key()).stat().st_mode & 0o777)
                self.assertNotIn(KEY, restarted.path(self.key()).read_text())
                self.assertNotIn("synthetic prompt", restarted.path(self.key()).read_text())
                self.assertEqual("batch_one", state["batch_id"])

    async def test_concurrent_claim_is_deferred_without_second_post(self):
        entered, release = asyncio.Event(), asyncio.Event()
        async def slow(*args):
            entered.set()
            await release.wait()
            return "batch_one"
        with patch.object(self.router, "submit", slow):
            running = asyncio.create_task(self.run_batch())
            await entered.wait()
            with self.assertRaises(BatchPending):
                await self.run_batch(self.make_router())
            release.set()
            with self.assertRaises(BatchPending):
                await running
        self.assertEqual(1, len(self.ledger.read()["cycles"]["anthropic:2026-11"]["reserved"]))

    async def test_ambiguous_post_and_cancel_keep_reservation_and_never_resubmit(self):
        for cancelled in (False, True):
            with self.subTest(cancelled=cancelled):
                self.router.path(self.key()).unlink(missing_ok=True)
                def fail(request):
                    if cancelled:
                        raise asyncio.CancelledError()
                    raise httpx.ReadError("upstream message with " + KEY)
                self.failure = fail
                with self.assertRaises(asyncio.CancelledError if cancelled else ApiFailure):
                    await self.run_batch()
                before = len(self.calls)
                with self.assertRaises(ApiFailure):
                    await self.run_batch(self.make_router())
                self.assertEqual(before, len(self.calls))
                self.assertGreater(self.summary()["reserved_microusd"], 0)
                self.assertNotIn(KEY, self.router.path(self.key()).read_text())

    async def test_poll_timeout_only_retries_get_and_deadline_falls_back(self):
        await self.submit()
        self.now += 61
        self.failure = lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("private error"))
        with self.assertRaises(BatchPending):
            await self.run_batch()
        self.assertEqual(["POST", "GET"], [r.method for r in self.calls])
        self.now += 26 * 3600
        with self.assertRaisesRegex(ApiFailure, "batch_deadline"):
            await self.run_batch()
        self.assertEqual(1, sum(r.method == "POST" for r in self.calls))
        self.assertGreater(self.summary()["reserved_microusd"], 0)

    async def test_pending_status_and_terminal_error_do_not_release_unknown_usage(self):
        await self.submit()
        self.phase = "in_progress"
        self.now += 61
        with self.assertRaises(BatchPending):
            await self.run_batch()
        self.phase = "expired"
        self.now += 61
        with self.assertRaises(ApiFailure):
            await self.run_batch()
        self.assertGreater(self.summary()["reserved_microusd"], 0)
        self.assertEqual("fallback", self.router.read(self.router.path(self.key()))["phase"])

    async def test_bad_identity_model_usage_or_item_error_cannot_be_accepted(self):
        mutations = [lambda r: dict(r, custom_id="wrong"),
                     lambda r: dict(r, result={"type": "errored"}),
                     lambda r: dict(r, result=dict(r["result"], message=dict(r["result"]["message"], model="claude-haiku-5-5"))),
                     lambda r: dict(r, result=dict(r["result"], message=dict(r["result"]["message"], usage={}))) ]
        for mutate in mutations:
            self.router.path(self.key()).unlink(missing_ok=True)
            await self.submit()
            self.now += 61
            self.result_change = mutate
            with self.assertRaises(ApiFailure):
                await self.run_batch()
            self.assertEqual(0, self.summary()["spent_microusd"])
            self.assertGreater(self.summary()["reserved_microusd"], 0)

    async def test_crash_after_settlement_replays_without_double_counting(self):
        await self.submit()
        self.now += 61
        real_atomic = atomic_json
        def fail_cache(path, state):
            if state.get("phase") == "complete":
                raise OSError("synthetic disk failure")
            real_atomic(path, state)
        with patch("api_batch.atomic_json", fail_cache), self.assertRaises(OSError):
            await self.run_batch()
        self.assertEqual(1, self.summary()["calls"])
        result = await self.run_batch(self.make_router())
        self.assertEqual("api", result["transport"])
        self.assertEqual(1, self.summary()["calls"])
        self.assertEqual(0, self.summary()["reserved_microusd"])

    async def test_missing_key_or_cap_uses_cached_same_vendor_subscription_fallback(self):
        async def cli(req, model, effort):
            self.assertEqual("claude-opus-5-5", model)
            req.physical_calls += 1
            return bridge.Outcome("complete", None, "stop", model, text="synthetic CLI answer", usage={})
        for capped in (False, True):
            self.router.path(self.key()).unlink(missing_ok=True)
            if capped:
                self.ledger.reserve("anthropic", KEY, micros(190))
            router = self.make_router(keys=None if capped else {})
            def req():
                return bridge.build_req({"model": "claude-opus-5-5", "max_tokens": 100,
                    "messages": [{"role": "user", "content": "synthetic"}]},
                    {"x-coach-task": "BOOK_MATH_VERIFY", "x-coach-background": "1", "x-coach-batch-key": self.key()})
            with patch.object(bridge, "API_BATCH", router), patch.object(bridge, "dispatch_cli", cli) as fallback, patch.object(bridge, "write_log"):
                first = req()
                result = await bridge.dispatch_batch(first, first.model, None)
                self.assertEqual("complete", result.status)
                self.assertEqual("subscription", first.transport)
                # Cached results preserve same vendor and original physical-call metadata.
                with patch.object(bridge, "dispatch_cli", side_effect=AssertionError("duplicate fallback")):
                    second = req()
                    self.assertEqual(result.text, (await bridge.dispatch_batch(second, second.model, None)).text)
                self.assertEqual(1, second.physical_calls)
            self.assertEqual([], self.calls)

    async def test_corrupted_state_or_changed_identity_never_submits(self):
        path = self.router.path(self.key())
        path.parent.mkdir()
        path.write_text("broken")
        with self.assertRaisesRegex(Denied, "batch_state_unreadable"):
            await self.run_batch()
        self.assertEqual([], self.calls)
        path.unlink()
        await self.submit()
        with self.assertRaisesRegex(Denied, "batch_identity_mismatch"):
            await self.router.run(self.key(), "BOOK_MATH_REPAIR", "claude-opus-5-5", "s", "p", 100)
        self.assertEqual(1, len(self.calls))

    async def test_http_pending_completion_and_traffic_gates(self):
        headers = {"X-Coach-Task": "BOOK_MATH_VERIFY", "X-Coach-Background": "1", "X-Coach-Batch-Key": self.key(),
                   "X-Coach-Traffic": "test"}
        body = {"model": "claude-opus-5-5", "max_tokens": 100,
                "messages": [{"role": "user", "content": "synthetic"}]}
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=bridge.app), base_url="http://test") as client:
            with patch.object(bridge, "API_BATCH", self.router), patch.object(bridge, "USER_ONLY", True), patch.object(bridge, "write_log"):
                response = await client.post("/v1/chat/completions", headers=headers, json=body)
                self.assertEqual(403, response.status_code)
                self.assertEqual([], self.calls)
            with patch.object(bridge, "API_BATCH", self.router), patch.object(bridge, "USER_ONLY", False), patch.object(bridge, "write_log"):
                response = await client.post("/v1/chat/completions", headers=headers, json=body)
                self.assertEqual(202, response.status_code)
                self.assertEqual("60", response.headers["Retry-After"])
                self.assertEqual("pending", response.json()["x_coach_batch"])
                self.now += 61
                response = await client.post("/v1/chat/completions", headers=headers, json=body)
                self.assertEqual(200, response.status_code)
                self.assertEqual("api", response.json()["x_coach"]["transport"])
                self.assertEqual("claude-opus-5-5", response.json()["model"])
                async def synchronous(req, model, effort):
                    return bridge.Outcome("complete", None, "stop", model, text="synthetic immediate answer")
                # A header alone cannot make an interactive call wait for Batch.
                foreground = dict(headers)
                del foreground["X-Coach-Background"]
                with patch.object(bridge, "dispatch", synchronous), patch.object(bridge, "dispatch_batch", side_effect=AssertionError("interactive batch")):
                    response = await client.post("/v1/chat/completions", headers=foreground, json=body)
                    self.assertEqual(200, response.status_code)
                    self.assertEqual("synthetic immediate answer", response.json()["choices"][0]["message"]["content"])

    async def test_guide_submits_haiku_batch_and_never_falls_through_to_cli(self):
        from api_batch import GUIDE_TASK
        body = {"model": "claude-haiku-5-5", "messages": [{"role": "user", "content": "synthetic"}]}
        headers = {"x-coach-task": GUIDE_TASK, "x-coach-background": "1", "x-coach-batch-key": self.key()}
        with self.assertRaises(bridge.BridgeError):
            bridge.build_req(dict(body, stream=True), headers)
        request = bridge.build_req(body, headers)
        with patch.object(bridge, "API_BATCH", self.router):
            with self.assertRaises(BatchPending):
                await bridge.dispatch_batch(request, request.model, None)
        self.assertEqual("/v1/messages/batches", self.calls[0].url.path)
        self.assertEqual(body["model"], json.loads(self.calls[0].content)["requests"][0]["params"]["model"])

        friend = bridge.build_req(body, dict(headers, **{"x-coach-friend": "a" * 64, "x-coach-friend-device": "pc"}))
        denied = AsyncMock(side_effect=Denied("credit_limit_95_percent"))
        with patch.object(bridge.API_BATCH, "run", denied), patch.object(bridge, "write_log"), \
                patch.object(bridge, "dispatch_cli", side_effect=AssertionError("guide fell through to CLI")):
            result = await bridge.dispatch_batch(friend, friend.model, None)
        self.assertEqual(("failed", "credit_limit_95_percent"), (result.status, result.reason))
        self.assertEqual("a" * 64, denied.await_args.kwargs["friend"])
        self.assertEqual("pc", denied.await_args.kwargs["device"])

    async def test_key_removed_while_pending_caches_subscription_and_preserves_reserve(self):
        await self.submit()
        self.now += 61
        self.router.router.keys.clear()
        with self.assertRaises(ApiFailure):
            await self.run_batch()
        self.router.cache_fallback(self.key(), {"text": "synthetic", "served_by": "claude-opus-5-5",
                                              "transport": "subscription", "usage": {}})
        result = await self.run_batch()
        self.assertEqual("subscription", result["transport"])
        self.assertGreater(self.summary()["reserved_microusd"], 0)
        self.assertEqual(1, len(self.calls))


    async def test_api_only_pre_submit_failure_falls_back_cross_vendor_and_ambiguous_does_not(self):
        from api_routing import frames as stream_frames
        req_mcq = bridge.build_req(
            {"model": "gpt-6.2-sol", "max_tokens": 100, "messages": [{"role": "user", "content": "synthetic"}]},
            {"x-coach-task": "gen_mcq", "x-coach-background": "1", "x-coach-batch-key": self.key("pre-fail")},
        )
        seen_models = []

        def handle_backup(request):
            seen_models.append(json.loads(request.content)["model"])
            return httpx.Response(200, content=stream_frames("anthropic", model="claude-opus-5-5"))

        api_router = ApiRouter(self.ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(handle_backup)),
                               keys={"openai": "", "anthropic": KEY}, cross_vendor_fallback=True)
        batch_router = BatchRouter(api_router, lambda: self.now)
        with patch.object(bridge, "API_ONLY", True), patch.object(bridge, "API_ROUTER", api_router), \
                patch.object(bridge, "API_BATCH", batch_router), patch.object(bridge, "write_log"):
            result = await bridge.dispatch_batch(req_mcq, req_mcq.model, None)
        self.assertEqual(("complete", "claude-opus-5-5", ["claude-opus-5-5"], True),
                         (result.status, result.served_by, seen_models, req_mcq.fallback))

        # Ambiguous network failure during submit must NOT replay onto cross-vendor backup.
        self.provider = "openai"
        self.failure = lambda request: (_ for _ in ()).throw(httpx.ReadError("ambiguous")) if request.url.path == "/v1/files" else httpx.Response(200, json={})
        seen_models.clear()
        req_amb = bridge.build_req(
            {"model": "gpt-6.2-sol", "max_tokens": 100, "messages": [{"role": "user", "content": "synthetic"}]},
            {"x-coach-task": "gen_mcq", "x-coach-background": "1", "x-coach-batch-key": self.key("ambig")},
        )
        api_amb = ApiRouter(self.ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(self.handle)),
                            keys={"openai": KEY, "anthropic": KEY}, cross_vendor_fallback=True)
        batch_amb = BatchRouter(api_amb, lambda: self.now)
        with patch.object(bridge, "API_ONLY", True), patch.object(bridge, "API_ROUTER", api_amb), \
                patch.object(bridge, "API_BATCH", batch_amb), patch.object(bridge, "write_log"):
            failed = await bridge.dispatch_batch(req_amb, req_amb.model, None)
        self.assertEqual(("failed", "batch_submit_ambiguous"), (failed.status, failed.reason))

    def mcq_request(self, name):
        return bridge.build_req(
            {"model": "gpt-6.2-sol", "max_tokens": 100, "messages": [{"role": "user", "content": "synthetic"}]},
            {"x-coach-task": "gen_mcq", "x-coach-background": "1", "x-coach-batch-key": self.key(name)},
        )

    async def test_accepted_receipt_without_id_is_ambiguous_and_never_calls_backup(self):
        # HTTP 200 on batch creation without a usable id: a batch may exist upstream, so no second paid call.
        self.provider = "openai"
        paths = []

        def handle(request):
            paths.append(request.url.path)
            if request.url.path == "/v1/files":
                return httpx.Response(200, json={"id": "file_input"})
            if request.url.path == "/v1/batches":
                return httpx.Response(200, json={"status": "in_progress"})
            return httpx.Response(500)

        api = ApiRouter(self.ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(handle)),
                        keys={"openai": KEY, "anthropic": KEY}, cross_vendor_fallback=True)
        jobs = BatchRouter(api, lambda: self.now)
        cli = AsyncMock(side_effect=AssertionError("subscription CLI after ambiguous submit"))
        for api_only in (True, False):
            paths.clear()
            name = "receipt-api-only" if api_only else "receipt-default"
            req = self.mcq_request(name)
            with patch.object(bridge, "API_ONLY", api_only), patch.object(bridge, "API_ROUTER", api),                     patch.object(bridge, "API_BATCH", jobs), patch.object(bridge, "dispatch_cli", cli),                     patch.object(bridge, "write_log"):
                first = await bridge.dispatch_batch(req, req.model, None)
                again = await bridge.dispatch_batch(self.mcq_request(name), req.model, None)
            self.assertEqual(("failed", "batch_submit_ambiguous"), (first.status, first.reason))
            self.assertEqual(("failed", "batch_submit_ambiguous"), (again.status, again.reason))
            self.assertEqual(["/v1/files", "/v1/batches"], paths)
            self.assertEqual("fallback", jobs.read(jobs.path(self.key(name)))["phase"])
        cli.assert_not_called()

    async def test_cached_cross_vendor_fallback_keeps_wire_marker(self):
        from api_routing import frames as stream_frames
        paths = []

        def handle(request):
            paths.append(request.url.path)
            return httpx.Response(200, content=stream_frames("anthropic", model="claude-opus-5-5"))

        api = ApiRouter(self.ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(handle)),
                        keys={"openai": "", "anthropic": KEY}, cross_vendor_fallback=True)
        jobs = BatchRouter(api, lambda: self.now)
        first, cached = self.mcq_request("cached"), self.mcq_request("cached")
        with patch.object(bridge, "API_ONLY", True), patch.object(bridge, "API_ROUTER", api),                 patch.object(bridge, "API_BATCH", jobs), patch.object(bridge, "write_log"):
            a = await bridge.dispatch_batch(first, first.model, None)
            b = await bridge.dispatch_batch(cached, cached.model, None)
        self.assertEqual(["/v1/messages"], paths)
        self.assertEqual(("claude-opus-5-5", True), (a.x_coach(first)["served_by"], a.x_coach(first)["fallback"]))
        self.assertEqual(("claude-opus-5-5", True), (b.x_coach(cached)["served_by"], b.x_coach(cached)["fallback"]))

        # Caches written before the flag existed: a cross-vendor served_by still marks the reply as fallback.
        path = jobs.path(self.key("cached"))
        state = json.loads(path.read_text())
        state["result"].pop("fallback")
        atomic_json(path, state)
        legacy = self.mcq_request("cached")
        with patch.object(bridge, "API_ONLY", True), patch.object(bridge, "API_ROUTER", api),                 patch.object(bridge, "API_BATCH", jobs), patch.object(bridge, "write_log"):
            c = await bridge.dispatch_batch(legacy, legacy.model, None)
        self.assertTrue(c.x_coach(legacy)["fallback"])
        self.assertEqual(["/v1/messages"], paths)


if __name__ == "__main__":
    unittest.main()
