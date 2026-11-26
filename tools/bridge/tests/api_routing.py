#!/usr/bin/env python3
"""Offline API contracts. MockTransport cannot contact a provider or subscription CLI."""
import asyncio
import base64
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import struct
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from api_budget import CreditLedger, Denied, atomic_json, micros
from grant_fixture import fund
from api_route import ApiRouter, ApiFailure, CONFIG, cost, image_token_bound, route, vendor
import httpx
import subscription_bridge as bridge

KEY = "synthetic-not-a-real-key"
NOW = 1794787200


def make_png_b64(width: int = 1600, height: int = 1200) -> str:
    """Build a minimal valid PNG header+IDAT+IEND with arbitrary verified dimensions."""
    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(b"\x00")) + chunk(b"IEND", b"")
    return base64.b64encode(raw).decode("ascii")


class RequestLogTests(unittest.TestCase):
    def test_records_actual_transport_and_model_without_prompt_or_key(self):
        for transport in ("api", "subscription"):
            with self.subTest(transport=transport), patch.dict("os.environ", {"BRIDGE_LOG_STDOUT": "1"}), patch("builtins.print") as output:
                bridge.write_log(transport=transport, served_by="claude-haiku-5-5",
                                 prompt="private passage", api_key=KEY)
            line = output.call_args.args[0]
            self.assertIn(f"transport={transport} ", line)
            self.assertIn("served=claude-haiku-5-5 ", line)
            self.assertNotIn("private passage", line)
            self.assertNotIn(KEY, line)


def frames(provider, terminal=True, usage=True, model=None):
    model = model or ("claude-sonnet-5-5" if provider == "anthropic" else "gpt-6.2-astra")
    if provider == "anthropic":
        events = [{"type": "message_start", "message": {"model": model, "usage": {"input_tokens": 100} if usage else {}}},
                  {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "synthetic answer"}},
                  {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 20} if usage else {}}]
        if terminal:
            events.append({"type": "message_stop"})
    else:
        events = [{"type": "response.created", "response": {"model": model}},
                  {"type": "response.output_text.delta", "delta": "synthetic answer"}]
        if terminal:
            events.append({"type": "response.completed", "response": {"model": model,
                "usage": {"input_tokens": 100, "output_tokens": 20, "input_tokens_details": {"cached_tokens": 10}} if usage else {}}})
    return "".join("data: " + json.dumps(e) + "\n\n" for e in events).encode()


class LedgerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.now = NOW
        fund(self.root)
        self.ledger = CreditLedger(self.root, lambda: self.now)

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_key_does_not_create_a_ledger(self):
        empty = self.root / "empty"
        with self.assertRaisesRegex(Denied, "missing_key"):
            CreditLedger(empty).reserve("anthropic", "", 1)
        self.assertFalse((empty / "api-credit-ledger.json").exists())

    def test_owner_authorized_key_needs_no_account_attestation(self):
        self.ledger.reserve("openai", KEY, 1)
        self.assertFalse((self.root / "api-credit-admission.json").exists())

    def test_invalid_reservations_fail_before_network(self):
        for amount in (0, -1, True, 1.5):
            with self.subTest(amount=amount), self.assertRaisesRegex(Denied, "invalid_reservation"):
                self.ledger.reserve("anthropic", KEY, amount)
        with self.assertRaisesRegex(Denied, "invalid_reservation"):
            self.ledger.reserve("unknown", KEY, 1)

    def test_95_percent_of_each_grant(self):
        for provider, limit in (("anthropic", 190), ("openai", 475)):
            self.ledger.reserve(provider, KEY, micros(limit))
            with self.assertRaisesRegex(Denied, "95_percent"):
                self.ledger.reserve(provider, KEY, 1)

    def test_guard_receipt_distinguishes_pending_from_accounted_usage(self):
        limit = micros(190)
        hold = self.ledger.reserve("anthropic", KEY, limit)
        receipt = self.root / "credit-guard-2026-11-anthropic.json"
        for reason, pending in (("pending_reservations", limit), ("insufficient_allocation", 0)):
            with self.assertRaisesRegex(Denied, "95_percent"):
                self.ledger.reserve("anthropic", KEY, 1)
            record = json.loads(receipt.read_text())
            self.assertEqual(reason, record["cause"])
            self.assertEqual(pending, record["reserved_microusd"])
            self.assertNotIn(KEY, receipt.read_text())
            if pending:
                self.ledger.settle("anthropic", hold, limit)

    def test_key_change_does_not_reset_provider_budget(self):
        self.ledger.reserve("anthropic", KEY, micros(190))
        with self.assertRaises(Denied):
            self.ledger.reserve("anthropic", "another-synthetic-key", 1)

    def test_restart_retains_unsettled_usage(self):
        self.ledger.reserve("anthropic", KEY, micros(190))
        restarted = CreditLedger(self.root, lambda: NOW)
        with self.assertRaises(Denied):
            restarted.reserve("anthropic", KEY, 1)
        self.assertEqual(micros(190), json.loads((self.root / "api-spend-2026-11.json").read_text())["anthropic"]["reserved_microusd"])

    def test_existing_anthropic_cycle_releases_only_retired_ceiling(self):
        settled = self.ledger.reserve("anthropic", KEY, micros(140))
        self.ledger.settle("anthropic", settled, micros(140))
        self.ledger.reserve("anthropic", KEY, micros(10))
        data = self.ledger.read()
        data["cycles"][settled[0]]["limit"] = micros(150)
        self.ledger.save(data)
        self.ledger.reserve("anthropic", KEY, micros(40))
        with self.assertRaisesRegex(Denied, "95_percent"):
            self.ledger.reserve("anthropic", KEY, 1)

    def test_settlement_and_price_change(self):
        reservation = self.ledger.reserve("anthropic", KEY, 1000)
        self.ledger.settle("anthropic", reservation, 20)
        data = json.loads((self.root / "api-spend-2026-11.json").read_text())["anthropic"]
        self.assertEqual({"spent_microusd": 20, "reserved_microusd": 0, "calls": 1}, data)
        reservation = self.ledger.reserve("anthropic", KEY, 1)
        self.ledger.settle("anthropic", reservation, 2)
        with self.assertRaises(Denied):
            self.ledger.reserve("anthropic", KEY, 1)

    def test_concurrent_reservations_cannot_overspend(self):
        def reserve(_):
            try:
                self.ledger.reserve("anthropic", KEY, micros(10))
                return True
            except Denied:
                return False
        with ThreadPoolExecutor(max_workers=12) as pool:
            self.assertEqual(19, sum(pool.map(reserve, range(30))))

    def test_corrupted_ledger_is_not_reset(self):
        (self.root / "api-credit-ledger.json").write_text("broken")
        with self.assertRaisesRegex(Denied, "ledger_unreadable"):
            self.ledger.reserve("anthropic", KEY, 1)

    def test_corrupted_nested_ledger_fails_closed(self):
        self.ledger.reserve("anthropic", KEY, 1)
        data = self.ledger.read()
        next(iter(data["cycles"].values()))["spent"] = -1
        atomic_json(self.root / "api-credit-ledger.json", data)
        with self.assertRaisesRegex(Denied, "ledger_unreadable"):
            self.ledger.reserve("anthropic", KEY, 1)

    def test_calendar_rollover_preserves_cycle_reservations(self):
        self.now = 1796083140  # 2026-11-30 23:59 UTC
        self.ledger.reserve("openai", KEY, micros(475))
        self.now += 120
        with self.assertRaisesRegex(Denied, "95_percent"):
            self.ledger.reserve("openai", KEY, 1)
        self.assertNotIn("2026-12", self.ledger.read()["months"])

    def test_settled_usage_resets_month_but_unknown_usage_carries_forward(self):
        self.now = 1796083140
        rollover = self.now + 60
        fund(self.root, end=rollover)
        r = self.ledger.reserve("anthropic", KEY, micros(189))
        self.ledger.settle("anthropic", r, micros(189))
        self.ledger.reserve("anthropic", KEY, micros(1))
        self.now += 120
        fund(self.root, start=rollover, prior_anthropic=micros(189))
        self.ledger.reserve("anthropic", KEY, micros(189))
        with self.assertRaises(Denied):
            self.ledger.reserve("anthropic", KEY, 1)

    def test_old_ledger_unsettled_calls_still_count(self):
        atomic_json(self.root / "api-credit-ledger.json", {"version": 1, "cycles": {
            "anthropic:old-cycle:old-key": {"spent": 0, "reserved": {"old": micros(190)}, "limit": micros(190)}
        }, "months": {}})
        with self.assertRaises(Denied):
            self.ledger.reserve("anthropic", KEY, 1)


class ApiTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.ledger = CreditLedger(self.root, lambda: NOW)
        fund(self.root)
        self.requests = []

    def tearDown(self):
        self.tmp.cleanup()

    def router(self, content=None, status=200, keys=None, handler=None, cross_vendor_fallback=False, **kwargs):
        def handle(request):
            self.requests.append(request)
            return handler(request) if handler else httpx.Response(status, content=content or b"error-body-must-never-be-read")
        return ApiRouter(self.ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(handle)),
                         keys=keys if keys is not None else {"anthropic": KEY, "openai": KEY},
                         cross_vendor_fallback=cross_vendor_fallback, **kwargs)

    async def collect(self, router, task="READER_SELECTION", model="claude-sonnet-5-5"):
        return [e async for e in router.events(task, model, "synthetic system", "synthetic prompt", 100)]

    async def test_missing_key_and_budget_limit_use_bridge_without_api_io(self):
        async def cli(req, model, effort):
            self.assertEqual("gpt-6.1-sol", model)
            req.physical_calls += 1
            return bridge.Outcome("complete", None, "stop", model, text="subscription answer")
        self.ledger.reserve("openai", KEY, micros(475))
        for keys, expected in (({}, "missing_key"), ({"openai": KEY}, "credit_limit_95_percent")):
            with self.subTest(reason=expected):
                req = bridge.build_req({"model": "gpt-6.1-sol", "max_tokens": 100,
                    "messages": [{"role": "user", "content": "synthetic"}]}, {"x-coach-task": "cross_review_grade"})
                with patch.object(bridge, "API_ROUTER", self.router(keys=keys)), patch.object(bridge, "dispatch_cli", cli), patch.object(bridge, "write_log"):
                    result = await bridge.dispatch(req, req.model, None)
                self.assertEqual("complete", result.status)
                self.assertEqual(expected, req.api_fallback)
                self.assertEqual(1, req.physical_calls)
        self.assertEqual([], self.requests)

    async def test_both_official_streaming_protocols_and_usage(self):
        for provider, task, requested, served in (
            ("anthropic", "READER_SELECTION", "claude-sonnet-5-5", "claude-sonnet-5-5"),
            ("openai", "cross_review_grade", "gpt-6.2-astra", "gpt-6.2-astra"),
        ):
            with self.subTest(provider=provider):
                events = await self.collect(self.router(frames(provider, model=served)), task, requested)
                self.assertEqual("complete", events[-1]["kind"])
                self.assertEqual("synthetic answer", events[-1]["text"])
                self.assertEqual(120, events[-1]["usage"]["total_tokens"])
                self.assertEqual(0, self.ledger.read()["months"]["2026-11"][provider]["reserved_microusd"])
        self.assertEqual("api.anthropic.com", self.requests[0].url.host)
        self.assertEqual("api.openai.com", self.requests[1].url.host)

    async def test_missing_terminal_keeps_received_usage_and_incomplete_response_still_fails(self):
        for provider, task, model in (("anthropic", "TEACH", "claude-sonnet-5-5"),
                                      ("openai", "blind_review", "gpt-6.2-astra")):
            with self.subTest(provider=provider):
                with self.assertRaisesRegex(ApiFailure, "api_no_terminal"):
                    await self.collect(self.router(frames(provider, terminal=False, model=model)), task, model)
                month = self.ledger.read()["months"]["2026-11"][provider]
                if provider == "anthropic":
                    # The final usage delta arrived before the missing stop frame.
                    self.assertEqual(cost(model, {"input_tokens": 100, "output_tokens": 20}), month["spent_microusd"])
                    self.assertEqual(0, month.get("estimated_microusd", 0))
                    self.assertEqual(0, month["reserved_microusd"])
                else:
                    # No usage was sent on this connection. The hold has a deadline.
                    self.assertGreater(month["reserved_microusd"], 0)
                    self.assertEqual(0, month["spent_microusd"])
                    self.assertEqual(1, len(self.ledger.read()["interrupted_streams"]))

                with self.assertRaises(ApiFailure):
                    await self.collect(self.router(frames(provider, usage=False, model=model)), task, model)
                updated = self.ledger.read()["months"]["2026-11"][provider]
                maximum = cost(model, {"input_tokens": 4096 + len("synthetic systemsynthetic prompt"), "output_tokens": 100})
                self.assertEqual(month["reserved_microusd"], updated["reserved_microusd"])
                self.assertEqual(maximum, updated["spent_microusd"] - month["spent_microusd"])
                self.assertEqual(maximum, updated["estimated_microusd"])

    async def test_http_errors_never_expose_body(self):
        for status in (401, 402, 429, 500):
            with self.assertRaisesRegex(ApiFailure, f"^api_http_{status}$"):
                await self.collect(self.router(status=status))

    async def test_transport_error_is_content_free(self):
        def fail(request):
            raise httpx.ReadError("sensitive error with " + KEY)
        with self.assertRaisesRegex(ApiFailure, "^api_ReadError$"):
            await self.collect(self.router(handler=fail))

    async def test_missing_key_never_contacts_network_and_existing_key_works(self):
        with self.assertRaisesRegex(Denied, "missing_key"):
            await self.collect(self.router(keys={}))
        self.assertEqual([], self.requests)
        events = await self.collect(self.router(frames("anthropic", model="claude-sonnet-5-5"), keys={"anthropic": KEY}))
        self.assertEqual("complete", events[-1]["kind"])
        self.assertEqual(1, len(self.requests))

    async def test_wrong_served_model_fails_closed(self):
        with self.assertRaisesRegex(ApiFailure, "api_model_mismatch"):
            await self.collect(self.router(frames("anthropic", model="claude-haiku-5-5")))

    async def test_cancellation_before_network_releases_reservation(self):
        events = self.router(frames("anthropic", model="claude-sonnet-5-5")).events("TEACH", "claude-sonnet-5-5", "s", "u", 100)
        self.assertEqual("attempt", (await anext(events))["kind"])
        await events.aclose()
        self.assertEqual({"spent_microusd": 0, "reserved_microusd": 0, "calls": 0}, self.ledger.read()["months"]["2026-11"]["anthropic"])
        self.assertEqual([], self.requests)

    async def test_read_timeout_and_disconnect_settle_upper_bound(self):
        for failure in (httpx.ReadTimeout, httpx.ReadError):
            with self.subTest(failure=failure):
                def fail(_):
                    raise failure("interrupted")
                with self.assertRaisesRegex(ApiFailure, failure.__name__):
                    await self.collect(self.router(handler=fail))
                month = self.ledger.read()["months"]["2026-11"]["anthropic"]
                self.assertEqual(0, month["reserved_microusd"])
                self.assertGreater(month["estimated_microusd"], 0)
                self.assertEqual(month["spent_microusd"], month["estimated_microusd"])

    async def test_close_after_first_delta_accounts_for_sent_request(self):
        events = self.router(frames("anthropic")).events("TEACH", "claude-sonnet-5-5", "s", "u", 100)
        async for event in events:
            if event["kind"] == "text":
                break
        await events.aclose()
        month = self.ledger.read()["months"]["2026-11"]["anthropic"]
        self.assertEqual(0, month["reserved_microusd"])
        self.assertEqual(cost("claude-sonnet-5-5", {"input_tokens": 100, "output_tokens": 100}), month["spent_microusd"])
        self.assertEqual(cost("claude-sonnet-5-5", {"input_tokens": 0, "output_tokens": 100}), month["estimated_microusd"])

    async def test_http_429_retries_within_same_reservation_and_recovers(self):
        slept = []
        async def fake_sleep(seconds):
            slept.append(seconds)
        attempts = 0
        def handler(request):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                return httpx.Response(429, headers={"Retry-After": "1.25"}, content=b"sensitive-upstream-body")
            return httpx.Response(200, content=frames("anthropic", model="claude-sonnet-5-5"))
        r = self.router(handler=handler, retry_429_delays=(0.5, 1.5), sleep=fake_sleep)
        events = await self.collect(r, "READER_SELECTION", "claude-sonnet-5-5")
        self.assertEqual("complete", events[-1]["kind"])
        self.assertEqual(2, len(self.requests))
        self.assertEqual([1.25], slept)
        month = self.ledger.read()["months"]["2026-11"]["anthropic"]
        self.assertEqual(0, month["reserved_microusd"])
        self.assertEqual(1, month["calls"])

    async def test_http_429_waits_full_10s_retry_after_within_budget_and_recovers_with_same_reservation(self):
        now = 0.0
        request_times = []
        slept = []
        def fake_clock():
            return now
        async def fake_sleep(seconds):
            nonlocal now
            slept.append(seconds)
            now += seconds
        def handler(request):
            request_times.append(now)
            if now < 10.0:
                return httpx.Response(429, headers={"Retry-After": str(int(10.0 - now))}, content=b"rate limited")
            return httpx.Response(200, content=frames("anthropic", model="claude-sonnet-5-5"))
        r = self.router(handler=handler, retry_429_delays=(0.5, 1.5), retry_429_budget=45.0, sleep=fake_sleep, clock=fake_clock)
        events = await self.collect(r, "READER_SELECTION", "claude-sonnet-5-5")
        self.assertEqual("complete", events[-1]["kind"])
        self.assertEqual([0.0, 10.0], request_times)
        self.assertEqual([10.0], slept)
        month = self.ledger.read()["months"]["2026-11"]["anthropic"]
        self.assertEqual(0, month["reserved_microusd"])
        self.assertEqual(1, month["calls"])

    async def test_http_429_over_budget_fails_fast_and_reports_retry_after_sec(self):
        now = 0.0
        slept = []
        async def fake_sleep(seconds):
            nonlocal now
            slept.append(seconds)
            now += seconds
        def handler(request):
            return httpx.Response(429, headers={"Retry-After": "60"}, content=b"rate limited sensitive body")
        r = self.router(handler=handler, retry_429_delays=(0.5, 1.5), retry_429_budget=45.0, sleep=fake_sleep, clock=lambda: now)
        with self.assertRaises(ApiFailure) as ctx:
            await self.collect(r, "READER_SELECTION", "claude-sonnet-5-5")
        self.assertEqual("api_http_429", str(ctx.exception))
        self.assertEqual(60, ctx.exception.retry_after_sec)
        self.assertEqual(1, len(self.requests))
        self.assertEqual([], slept)
        self.assertEqual({"spent_microusd": 0, "reserved_microusd": 0, "calls": 0}, self.ledger.read()["months"]["2026-11"]["anthropic"])
        # Bridge API_ONLY propagates retry_after_sec in x_coach without leaking any upstream body.
        req = bridge.build_req(
            {"model": "claude-sonnet-5-5", "max_tokens": 100, "messages": [{"role": "user", "content": "synthetic"}]},
            {"x-coach-task": "READER_SELECTION"},
        )
        with patch.object(bridge, "API_ONLY", True), patch.object(bridge, "API_ROUTER", r), patch.object(bridge, "write_log"):
            outcome = await bridge.dispatch(req, req.model, None)
        self.assertEqual("failed", outcome.status)
        self.assertEqual("api_http_429", outcome.reason)
        self.assertEqual(60, outcome.retry_after_sec)
        self.assertEqual(60, outcome.x_coach(req)["retry_after_sec"])

    async def test_bulk_concurrency_capped_and_foreground_qa_runs_in_reserved_slot(self):
        bulk_in_flight = 0
        bulk_peak = 0
        fg_ran_during_bulk = False
        release_bulk = asyncio.Event()
        bulk_started = asyncio.Event()

        class SlowTransport(httpx.AsyncBaseTransport):
            async def handle_async_request(self, request):
                nonlocal bulk_in_flight, bulk_peak, fg_ran_during_bulk
                body = json.loads(request.content.decode())
                user_msg = body["messages"][0]["content"]
                if user_msg.startswith("bulk:"):
                    bulk_in_flight += 1
                    bulk_peak = max(bulk_peak, bulk_in_flight)
                    if bulk_in_flight >= 2:
                        bulk_started.set()
                    try:
                        await release_bulk.wait()
                    finally:
                        bulk_in_flight -= 1
                else:
                    if bulk_in_flight > 0:
                        fg_ran_during_bulk = True
                return httpx.Response(200, content=frames("anthropic"))

        r = ApiRouter(
            self.ledger,
            lambda: httpx.AsyncClient(transport=SlowTransport()),
            keys={"anthropic": KEY},
            concurrency=4,
            bulk_concurrency=2,
            retry_429_delays=(),
        )
        bulk_tasks = [
            asyncio.create_task(self.collect_prompt(r, "COURSE_PLAN", f"bulk:{i}"))
            for i in range(5)
        ]
        await asyncio.wait_for(bulk_started.wait(), timeout=2.0)
        await asyncio.sleep(0.02)
        self.assertEqual(2, bulk_peak)
        # Foreground Q&A runs immediately in a reserved slot even while bulk slots are full.
        fg_events = await asyncio.wait_for(self.collect_prompt(r, "READER_SELECTION", "fg:qa"), timeout=2.0)
        self.assertEqual("complete", fg_events[-1]["kind"])
        self.assertTrue(fg_ran_during_bulk)
        release_bulk.set()
        results = await asyncio.wait_for(asyncio.gather(*bulk_tasks), timeout=2.0)
        self.assertEqual(5, len(results))
        self.assertEqual(2, bulk_peak)

    async def collect_prompt(self, router, task, prompt):
        return [e async for e in router.events(task, "claude-sonnet-5-5", "synthetic system", prompt, 100)]


class RoutingTests(unittest.IsolatedAsyncioTestCase):
    def test_rate_limited_grading_swap_keeps_cross_vendor_review(self):
        draft = route("grade_constructed", "gpt-6.1-sol")
        review = route("cross_review_grade", "claude-sonnet-5-5")
        self.assertEqual("gpt-6.2-astra", draft)
        self.assertEqual("claude-opus-5-5", review)
        self.assertNotEqual(vendor(draft), vendor(review))

    def test_task_roles_and_format_escalation(self):
        for task, group in CONFIG["tasks"].items():
            if group in ("review", "review_openai", "review_upgrade"):
                self.assertEqual(CONFIG["models"]["review"], route(task, "claude-sonnet-5-5"))
                self.assertEqual(CONFIG["models"]["review_openai"], route(task, "gpt-6.1-sol"))
                continue
            expected = CONFIG["models"][group]
            self.assertEqual(expected, route(task, "claude-sonnet-5-5"))
        self.assertEqual("gpt-6.2-sol", route("gen_mcq", "gpt-6.2-sol", 1))
        self.assertEqual("gpt-6.2-astra", route("gen_mcq", "gpt-6.2-sol", 2))
        self.assertEqual("claude-opus-5-5", route("cross_review_grade", "claude-sonnet-5-5"))
        self.assertEqual("gpt-6.2-astra", route("cross_review_grade", "gpt-6.1-sol"))
        self.assertIsNone(route("not-a-task", "claude-sonnet-5-5"))

    async def test_cross_vendor_fallback_on_429_and_not_on_review_or_partial(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fund(root)
            ledger = CreditLedger(root, lambda: NOW)
            seen_hosts = []
            def handler(request):
                seen_hosts.append(request.url.host)
                if request.url.host == "api.anthropic.com":
                    return httpx.Response(429, headers={"Retry-After": "60"}, content=b"rate-limited")
                return httpx.Response(200, content=frames("openai", model="gpt-6.2-sol"))
            r = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                          keys={"anthropic": KEY, "openai": KEY}, retry_429_delays=(), cross_vendor_fallback=True)
            events = [e async for e in r.events("READER_SELECTION", "claude-sonnet-5-5", "sys", "usr", 100)]
            self.assertEqual("complete", events[-1]["kind"])
            self.assertEqual("gpt-6.2-sol", events[-1]["served_by"])
            self.assertEqual(["api.anthropic.com", "api.openai.com"], seen_hosts)

            # Review tasks MUST NOT cross-vendor fall back even on 429
            seen_hosts.clear()
            r_review = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                                 keys={"anthropic": KEY, "openai": KEY}, retry_429_delays=(), cross_vendor_fallback=True)
            with self.assertRaises(ApiFailure):
                [e async for e in r_review.events("blind_review", "claude-sonnet-5-5", "sys", "usr", 100)]
            self.assertEqual(["api.anthropic.com"], seen_hosts)

    async def test_empty_text_delta_before_drop_still_allows_cross_vendor_fallback(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fund(root)
            ledger = CreditLedger(root, lambda: NOW)

            def empty_delta_then_drop(request):
                if request.url.host == "api.anthropic.com":
                    events = [
                        {"type": "message_start", "message": {"model": "claude-sonnet-5-5", "usage": {"input_tokens": 10}}},
                        {"type": "content_block_delta", "delta": {"type": "text_delta", "text": ""}},
                    ]
                    return httpx.Response(200, content="".join("data: " + json.dumps(e) + "\n\n" for e in events).encode())
                return httpx.Response(200, content=frames("openai", model="gpt-6.2-sol"))

            r = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(empty_delta_then_drop)),
                          keys={"anthropic": KEY, "openai": KEY}, retry_429_delays=(), cross_vendor_fallback=True)
            events = [e async for e in r.events("TEACH", "claude-sonnet-5-5", "sys", "usr", 100)]
            self.assertEqual("complete", events[-1]["kind"])
            self.assertEqual("gpt-6.2-sol", events[-1]["served_by"])

            def partial_nonempty_then_drop(request):
                events = [
                    {"type": "message_start", "message": {"model": "claude-sonnet-5-5", "usage": {"input_tokens": 10}}},
                    {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "partial"}},
                ]
                return httpx.Response(200, content="".join("data: " + json.dumps(e) + "\n\n" for e in events).encode())

            r_partial = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(partial_nonempty_then_drop)),
                                  keys={"anthropic": KEY, "openai": KEY}, retry_429_delays=(), cross_vendor_fallback=True)
            with self.assertRaisesRegex(ApiFailure, "api_no_terminal"):
                [e async for e in r_partial.events("TEACH", "claude-sonnet-5-5", "sys", "usr", 100)]

    def test_every_server_bridge_task_and_model_is_served(self):
        # TON-1795: 2.0 routes requested an unregistered model and unrouted tasks, so the
        # API-only bridge answered 404/503 and the chapter companion showed "暂时不可用".
        import re
        root = Path(__file__).resolve().parents[3]
        sources = [p.read_text(encoding="utf-8") for d in ("server/app/src/main/kotlin", "server/ai/src/main/kotlin")
                   for p in (root / d).rglob("*.kt")]
        tasks = {t for s in sources for t in re.findall(r'\btask\s*=\s*"([A-Za-z_]+)"', s)}
        tasks |= {t for s in sources for t in re.findall(r'"(BOOK_[A-Z_]+)"', s)}
        # Token-ledger / section labels only; these calls never reach the bridge.
        tasks -= {
            "course_align",
            "BOOK_MAP_BATCH", "BOOK_MAP_GRAPH",
        }
        self.assertTrue({"booklist_concept_align", "course_topic_map", "BOOK_SECTION_BATCH", "course_vision_ocr"} <= tasks)
        self.assertEqual(set(), tasks - set(CONFIG["tasks"]))
        constants = {m for s in sources for m in re.findall(r'const val [A-Z0-9_]+ = "((?:claude|gpt)-[a-z0-9.-]+)"', s)}
        self.assertIn("claude-haiku-5-5", constants)
        self.assertEqual(set(), constants - set(bridge.MODELS))

    def test_prices_and_incomplete_usage(self):
        self.assertEqual(600000, cost("claude-haiku-5-5", {"input_tokens": 1_000_000 // 10, "output_tokens": 1180000}))
        self.assertEqual(505, cost("gpt-6.1-terra", {"input_tokens": 100, "cached_tokens": 100, "output_tokens": 100}))
        with self.assertRaises(ApiFailure):
            cost("claude-haiku-5-5", {"output_tokens": 0})

    async def test_bridge_fallback_stays_with_requested_vendor_and_accounts_both_attempts(self):
        class Failing:
            async def events(self, *args):
                yield {"kind": "attempt", "model": "claude-haiku-5-5"}
                raise ApiFailure("api_http_429")
        async def cli(req, model, effort):
            self.assertEqual("claude-sonnet-5-5", model)
            req.physical_calls += 1
            return bridge.Outcome("complete", None, "stop", model, text="subscription answer")
        req = bridge.build_req({"model": "claude-sonnet-5-5", "messages": [{"role": "user", "content": "synthetic"}]},
                               {"x-coach-task": "gen_mcq"})
        with patch.object(bridge, "API_ROUTER", Failing()), patch.object(bridge, "dispatch_cli", cli), patch.object(bridge, "write_log") as log:
            result = await bridge.dispatch(req, req.model, None)
        self.assertEqual("complete", result.status)
        self.assertEqual("api_http_429", req.api_fallback)
        self.assertEqual(2, req.physical_calls)
        self.assertFalse(req.fallback)  # No cross-vendor substitution.
        self.assertNotIn(KEY, str(log.call_args_list))

    async def test_partial_api_resets_before_streaming_subscription(self):
        class Partial:
            async def events(self, *args):
                yield {"kind": "attempt", "model": "claude-haiku-5-5"}
                yield {"kind": "text", "text": "discard partial"}
                raise ApiFailure("api_no_terminal")
        async def cli(req, model, effort):
            req.physical_calls += 1
            yield {"kind": "text", "text": "replacement"}
            yield {"kind": "end", "outcome": bridge.Outcome("complete", None, "stop", model, text="replacement")}
        req = bridge.build_req({"model": "claude-sonnet-5-5", "stream": True, "messages": [{"role": "user", "content": "synthetic"}]},
                               {"x-coach-task": "TEACH", "x-coach-stream-reset": "v1"})
        with patch.object(bridge, "API_ROUTER", Partial()), patch.object(bridge, "claude_stream", cli), patch.object(bridge, "write_log"):
            events = [e async for e in bridge.routed_events(req, req.model, None)]
        self.assertEqual(["text", "reset", "text", "end"], [e["kind"] for e in events])
        self.assertEqual("replacement", events[-1]["outcome"].text)
        self.assertEqual(2, req.physical_calls)

    async def test_api_only_blocks_cli_and_reports_api_failure(self):
        req = bridge.build_req({"model": "claude-sonnet-5-5", "messages": [{"role": "user", "content": "sample"}]},
                               {"x-coach-task": "TEACH"})
        with patch.object(bridge, "API_ONLY", True), patch.object(bridge.API_ROUTER, "keys", {}),                 patch("asyncio.create_subprocess_exec") as launch:
            outcome = await bridge.dispatch(req, req.model, None)
            self.assertEqual(("failed", "missing_key", "api"), (outcome.status, outcome.reason, req.transport))
            with self.assertRaises(bridge.BridgeError) as error:
                await bridge.spawn(["claude"], Path("."), {})
            self.assertEqual("subscription_disabled", error.exception.reason)
            self.assertFalse((await bridge.cli_status(["codex"], {}, Path(".")))["enabled"])
            launch.assert_not_called()

    def test_api_keys_are_removed_from_all_cli_children(self):
        with patch.dict("os.environ", {"COACH_OPENAI_API_KEY": KEY, "COACH_ANTHROPIC_API_KEY": KEY, "OPENAI_API_KEY": KEY, "ANTHROPIC_API_KEY": KEY}):
            for backend in ("claude", "codex", "agy"):
                self.assertNotIn(KEY, bridge.child_env(backend).values())


class VisionRoutingTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.ledger = CreditLedger(self.root, lambda: NOW)
        fund(self.root)
        self.requests = []
        self.png_b64 = make_png_b64(1600, 1200)

    def tearDown(self):
        self.tmp.cleanup()

    def vision_req(self, png_b64=None, task="course_vision_ocr", model="claude-opus-5-5", max_tokens=2048):
        img_b64 = self.png_b64 if png_b64 is None else png_b64
        body = {
            "model": model,
            "max_tokens": max_tokens,
            "messages": [
                {"role": "system", "content": "transcribe faithfully"},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Transcribe board notes"},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{img_b64}"}},
                    ],
                },
            ],
        }
        return bridge.build_req(body, {"x-coach-task": task})

    def router(self, handler, keys=None, cross_vendor_fallback=True):
        def wrapped(request):
            self.requests.append(request)
            return handler(request)
        return ApiRouter(
            self.ledger,
            lambda: httpx.AsyncClient(transport=httpx.MockTransport(wrapped)),
            keys=keys if keys is not None else {"anthropic": KEY, "openai": KEY},
            retry_429_delays=(),
            cross_vendor_fallback=cross_vendor_fallback,
        )

    async def test_vision_reserves_image_bound_preserves_image_block_and_settles(self):
        reserved_during_call = []

        def handler(request):
            reserved_during_call.append(
                self.ledger.read()["months"]["2026-11"]["anthropic"]["reserved_microusd"]
            )
            payload = json.loads(request.content)
            self.assertEqual("claude-opus-5-5", payload["model"])
            blocks = payload["messages"][0]["content"]
            self.assertEqual("image", blocks[0]["type"])
            self.assertEqual("base64", blocks[0]["source"]["type"])
            self.assertEqual("image/png", blocks[0]["source"]["media_type"])
            self.assertEqual(self.png_b64, blocks[0]["source"]["data"])
            self.assertEqual({"type": "text", "text": "Transcribe board notes"}, blocks[1])
            self.assertNotIn("[image omitted]", json.dumps(payload))
            return httpx.Response(200, content=frames("anthropic", model="claude-opus-5-5"))

        req = self.vision_req()
        with patch.object(bridge, "API_ROUTER", self.router(handler)), patch.object(bridge, "write_log"):
            outcome = await bridge.dispatch(req, req.model, None)

        self.assertEqual("complete", outcome.status)
        self.assertEqual("claude-opus-5-5", outcome.served_by)
        self.assertEqual(1, len(self.requests))
        self.assertEqual("api.anthropic.com", self.requests[0].url.host)
        text_only_bound = len(req.spec_data.system.encode()) + len(req.spec_data.prompt.encode()) + 4096
        expected_bound = text_only_bound + image_token_bound(1600, 1200)
        expected_reserved = cost("claude-opus-5-5", {"input_tokens": expected_bound, "output_tokens": 2048})
        text_only_reserved = cost("claude-opus-5-5", {"input_tokens": text_only_bound, "output_tokens": 2048})
        self.assertEqual([expected_reserved], reserved_during_call)
        self.assertGreater(expected_reserved, text_only_reserved)
        month = self.ledger.read()["months"]["2026-11"]["anthropic"]
        self.assertEqual(0, month["reserved_microusd"])
        self.assertEqual(1, month["calls"])
        self.assertEqual(cost("claude-opus-5-5", {"input_tokens": 100, "output_tokens": 20}), month["spent_microusd"])

    async def test_vision_over_share_denies_with_zero_network_requests_and_no_cli_fallback(self):
        # Fill both Anthropic (95% = $190) and OpenAI (95% = $475) shares.
        self.ledger.reserve("anthropic", KEY, micros(190))
        self.ledger.reserve("openai", KEY, micros(475))

        def fail_if_called(request):
            raise AssertionError(f"Unexpected upstream HTTP call to {request.url}")

        async def fail_cli(*args, **kwargs):
            raise AssertionError("Vision must never fall back to subscription CLI")

        for keys, expected_reason in (
            ({"anthropic": KEY}, "credit_limit_95_percent"),
            ({"anthropic": KEY, "openai": KEY}, "credit_limit_95_percent"),
            ({"openai": KEY}, "credit_limit_95_percent"),
            ({}, "missing_key"),
        ):
            with self.subTest(keys=sorted(keys.keys())):
                req = self.vision_req()
                with patch.object(bridge, "API_ONLY", False), \
                        patch.object(bridge, "API_ROUTER", self.router(fail_if_called, keys=keys)), \
                        patch.object(bridge, "dispatch_cli", fail_cli), \
                        patch.object(bridge, "write_log"):
                    outcome = await bridge.dispatch(req, req.model, None)
                self.assertEqual("failed", outcome.status)
                self.assertEqual(expected_reason, outcome.reason)
                self.assertEqual("api", req.transport)
        self.assertEqual([], self.requests)

    async def test_vision_primary_failure_debits_anthropic_bound_and_falls_back_to_openai_with_guard(self):
        def handler(request):
            if request.url.host == "api.anthropic.com":
                return httpx.Response(500, content=b"upstream failure body")
            payload = json.loads(request.content)
            self.assertEqual("gpt-6.2-astra", payload["model"])
            content = payload["input"][0]["content"]
            self.assertEqual({"type": "input_text", "text": "Transcribe board notes"}, content[0])
            self.assertEqual("input_image", content[1]["type"])
            self.assertEqual(f"data:image/png;base64,{self.png_b64}", content[1]["image_url"])
            return httpx.Response(200, content=frames("openai", model="gpt-6.2-astra"))

        req = self.vision_req()
        with patch.object(bridge, "API_ROUTER", self.router(handler)), patch.object(bridge, "write_log"):
            outcome = await bridge.dispatch(req, req.model, None)

        self.assertEqual("complete", outcome.status)
        self.assertEqual("gpt-6.2-astra", outcome.served_by)
        self.assertTrue(req.fallback)
        self.assertEqual(["api.anthropic.com", "api.openai.com"], [r.url.host for r in self.requests])
        months = self.ledger.read()["months"]["2026-11"]
        # Failed Anthropic attempt consumes its conservative bound; OpenAI settled cleanly.
        self.assertEqual(0, months["anthropic"]["reserved_microusd"])
        self.assertGreater(months["anthropic"]["estimated_microusd"], 0)
        self.assertEqual(0, months["openai"]["reserved_microusd"])
        self.assertEqual(1, months["openai"]["calls"])
        self.assertGreater(months["openai"]["spent_microusd"], 0)

        # If OpenAI allocation is absent, Anthropic failure must NOT touch OpenAI network.
        (self.root / "grant-allocation-openai.json").unlink()
        self.requests.clear()
        req2 = self.vision_req()
        with patch.object(bridge, "API_ROUTER", self.router(handler)), patch.object(bridge, "write_log"):
            outcome2 = await bridge.dispatch(req2, req2.model, None)
        self.assertEqual("failed", outcome2.status)
        self.assertEqual("grant_allocation_unavailable", outcome2.reason)
        self.assertEqual(["api.anthropic.com"], [r.url.host for r in self.requests])

    async def test_vision_rejects_remote_url_corrupt_image_oversized_dimensions_and_multiturn_before_ledger(self):
        bad_bodies = [
            {
                "model": "claude-opus-5-5",
                "messages": [{"role": "user", "content": [
                    {"type": "text", "text": "hi"},
                    {"type": "image_url", "image_url": {"url": "https://example.com/secret.png"}},
                ]}],
            },
            {
                "model": "claude-opus-5-5",
                "messages": [{"role": "user", "content": [
                    {"type": "text", "text": "hi"},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo="}},
                ]}],
            },
            {
                "model": "claude-opus-5-5",
                "messages": [{"role": "user", "content": [
                    {"type": "text", "text": "hi"},
                    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{make_png_b64(8192, 8192)}"}},
                ]}],
            },
            {
                "model": "claude-opus-5-5",
                "messages": [
                    {"role": "user", "content": "first"},
                    {"role": "assistant", "content": "second"},
                    {"role": "user", "content": [
                        {"type": "text", "text": "third"},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{self.png_b64}"}},
                    ]},
                ],
            },
        ]
        for body in bad_bodies:
            with self.assertRaises(bridge.BridgeError) as ctx:
                bridge.build_req(body, {"x-coach-task": "course_vision_ocr"})
            self.assertEqual(400, ctx.exception.status)
            self.assertEqual("unsupported_image", ctx.exception.reason)
        self.assertEqual({}, self.ledger.read()["cycles"])
        self.assertEqual([], self.requests)

    async def test_vision_http_endpoint_enforces_share_and_returns_x_coach_without_network(self):
        self.ledger.reserve("anthropic", KEY, micros(190))

        def fail_if_called(request):
            raise AssertionError(f"Unexpected upstream HTTP call to {request.url}")

        payload = {
            "model": "claude-opus-5-5",
            "max_tokens": 2048,
            "stream": False,
            "messages": [
                {"role": "system", "content": "transcribe"},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Transcribe board"},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{self.png_b64}"}},
                    ],
                },
            ],
        }
        headers = {"X-Coach-Task": "course_vision_ocr", "X-Coach-Fallback": "off", "X-Coach-Model-Policy": "exact"}
        transport = httpx.ASGITransport(app=bridge.app)
        with patch.object(bridge, "USER_ONLY", False), \
                patch.object(bridge, "API_ONLY", False), \
                patch.object(bridge, "API_ROUTER", self.router(fail_if_called, keys={"anthropic": KEY})), \
                patch.object(bridge, "write_log"):
            async with httpx.AsyncClient(transport=transport, base_url="http://bridge.test") as client:
                resp = await client.post("/v1/chat/completions", json=payload, headers=headers)
        self.assertEqual(200, resp.status_code)
        body = resp.json()
        self.assertEqual("failed", body["x_coach"]["status"])
        self.assertEqual("credit_limit_95_percent", body["x_coach"]["reason"])
        self.assertEqual("api", body["x_coach"]["transport"])
        self.assertEqual(0, body["x_coach"]["physical_calls"])
        self.assertEqual([], self.requests)

    async def test_vision_primary_guard_denials_stop_without_openai_fallback_even_when_openai_has_headroom(self):
        # Regression for TON-1799 paid cross-review:
        # 1) Anthropic 95% full while OpenAI has headroom -> must stop with 0 upstream calls.
        # 2) Anthropic allocation missing while OpenAI has headroom -> must stop with 0 upstream calls.
        # 3) Anthropic returns 500 (retaining reservation) while OpenAI 95% full -> reports physical_calls=1, 0 OpenAI calls.
        payload = {
            "model": "claude-opus-5-5",
            "max_tokens": 2048,
            "stream": False,
            "messages": [
                {"role": "system", "content": "transcribe"},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Transcribe board"},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{self.png_b64}"}},
                    ],
                },
            ],
        }
        headers = {"X-Coach-Task": "course_vision_ocr", "X-Coach-Fallback": "off", "X-Coach-Model-Policy": "exact"}
        transport = httpx.ASGITransport(app=bridge.app)

        # Case 1: Only Anthropic 95% full; OpenAI has full headroom and key.
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fund(root)
            ledger = CreditLedger(root, lambda: NOW)
            ledger.reserve("anthropic", KEY, micros(190))
            seen = []

            def fail_if_called(request):
                seen.append(request.url.host)
                return httpx.Response(200, content=frames("openai", model="gpt-6.2-astra"))

            router = ApiRouter(
                ledger,
                lambda: httpx.AsyncClient(transport=httpx.MockTransport(fail_if_called)),
                keys={"anthropic": KEY, "openai": KEY},
                retry_429_delays=(),
                cross_vendor_fallback=True,
            )
            with patch.object(bridge, "USER_ONLY", False), patch.object(bridge, "API_ONLY", False), \
                    patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "write_log"):
                async with httpx.AsyncClient(transport=transport, base_url="http://bridge.test") as client:
                    resp = await client.post("/v1/chat/completions", json=payload, headers=headers)
            self.assertEqual(200, resp.status_code)
            xc = resp.json()["x_coach"]
            self.assertEqual("failed", xc["status"])
            self.assertEqual("credit_limit_95_percent", xc["reason"])
            self.assertEqual(0, xc["physical_calls"])
            self.assertEqual([], seen)

        # Case 2: Only Anthropic missing grant-allocation-anthropic.json; OpenAI has full headroom and key.
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fund(root)
            (root / "grant-allocation-anthropic.json").unlink()
            ledger = CreditLedger(root, lambda: NOW)
            seen = []

            def fail_if_called(request):
                seen.append(request.url.host)
                return httpx.Response(200, content=frames("openai", model="gpt-6.2-astra"))

            router = ApiRouter(
                ledger,
                lambda: httpx.AsyncClient(transport=httpx.MockTransport(fail_if_called)),
                keys={"anthropic": KEY, "openai": KEY},
                retry_429_delays=(),
                cross_vendor_fallback=True,
            )
            with patch.object(bridge, "USER_ONLY", False), patch.object(bridge, "API_ONLY", False), \
                    patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "write_log"):
                async with httpx.AsyncClient(transport=transport, base_url="http://bridge.test") as client:
                    resp = await client.post("/v1/chat/completions", json=payload, headers=headers)
            self.assertEqual(200, resp.status_code)
            xc = resp.json()["x_coach"]
            self.assertEqual("failed", xc["status"])
            self.assertEqual("grant_allocation_unavailable", xc["reason"])
            self.assertEqual(0, xc["physical_calls"])
            self.assertEqual([], seen)

        # Case 3: Anthropic receives 1 request and returns 500 (retaining reservation), while OpenAI is 95% full.
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fund(root)
            ledger = CreditLedger(root, lambda: NOW)
            ledger.reserve("openai", KEY, micros(475))
            seen = []

            def primary_500(request):
                seen.append(request.url.host)
                if request.url.host == "api.anthropic.com":
                    return httpx.Response(500, content=b"upstream error")
                raise AssertionError("OpenAI must not be contacted when over 95% share")

            router = ApiRouter(
                ledger,
                lambda: httpx.AsyncClient(transport=httpx.MockTransport(primary_500)),
                keys={"anthropic": KEY, "openai": KEY},
                retry_429_delays=(),
                cross_vendor_fallback=True,
            )
            with patch.object(bridge, "USER_ONLY", False), patch.object(bridge, "API_ONLY", False), \
                    patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "write_log"):
                async with httpx.AsyncClient(transport=transport, base_url="http://bridge.test") as client:
                    resp = await client.post("/v1/chat/completions", json=payload, headers=headers)
            self.assertEqual(200, resp.status_code)
            xc = resp.json()["x_coach"]
            self.assertEqual("failed", xc["status"])
            self.assertEqual("credit_limit_95_percent", xc["reason"])
            self.assertEqual(1, xc["physical_calls"])
            self.assertEqual(["api.anthropic.com"], seen)
            self.assertEqual(0, ledger.read()["months"]["2026-11"]["anthropic"]["reserved_microusd"])
            self.assertGreater(ledger.read()["months"]["2026-11"]["anthropic"]["estimated_microusd"], 0)

    async def test_minimal_effort_disables_anthropic_thinking_only_when_requested(self):
        seen_payloads = []

        def handle(request):
            body = json.loads(request.content)
            seen_payloads.append(body)
            return httpx.Response(200, content=frames("anthropic", model=body["model"]))

        router = self.router(handler=handle)
        events_min = await self.collect_with_effort(router, "READER_SELECTION", "claude-haiku-5-5", effort="minimal")
        self.assertEqual("complete", events_min[-1]["kind"])
        self.assertEqual({"type": "disabled"}, seen_payloads[0].get("thinking"))

        events_default = await self.collect_with_effort(router, "READER_SELECTION", "claude-haiku-5-5", effort=None)
        self.assertEqual("complete", events_default[-1]["kind"])
        self.assertNotIn("thinking", seen_payloads[1])

    async def collect_with_effort(self, router, task="READER_SELECTION", model="claude-haiku-5-5", effort=None):
        return [e async for e in router.events(task, model, "synthetic system", "synthetic prompt", 100, effort=effort)]


if __name__ == "__main__":
    unittest.main()
