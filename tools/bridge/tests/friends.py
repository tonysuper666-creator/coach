"""Offline friend budgets/routing. Only MockTransport supplies provider replies."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch, AsyncMock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from api_budget import CreditLedger, Denied, INTERRUPTED_USAGE_GRACE_SECONDS, atomic_json, micros
from api_route import ApiFailure, ApiRouter, CONFIG, cost
from api_routing import frames, KEY, NOW, make_png_b64
from grant_fixture import fund
from friend_spend_watch import publish
import httpx
import subscription_bridge as bridge

A, B = "a" * 64, "b" * 64


class FriendBudgetTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.config = self.root / "friends.json"
        self.env = patch.dict(os.environ, {"COACH_FRIEND_CONFIG": str(self.config)})
        self.env.start()
        self.now = NOW
        fund(self.root)
        self.ledger = CreditLedger(self.root, lambda: self.now)

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def limit(self, calls=1, tokens=1_000_000):
        atomic_json(self.config, {"defaults": {"daily_calls": calls, "daily_tokens": tokens}})

    def reserve(self, friend=B, device="pc", tokens=200):
        return self.ledger.reserve("anthropic", KEY, 100, friend=friend, device=device, tokens=tokens)

    def test_default_usage_continues_past_old_limits_and_is_recorded_per_person(self):
        first = self.reserve(tokens=1_000_100)
        self.ledger.settle("anthropic", first, 20, usage={"input_tokens": 1_000_000, "output_tokens": 20})
        data = self.ledger.read()
        daily = next(iter(data["friends"][B].values()))
        daily["calls"] = daily["devices"]["pc"]["calls"] = 100
        self.ledger.save(data)
        atomic_json(self.config, {"defaults": {}})
        second = self.reserve(device="second-pc")
        self.ledger.settle("anthropic", second, 30, usage={"input_tokens": 100, "output_tokens": 15})
        daily = next(iter(self.ledger.read()["friends"][B].values()))
        self.assertEqual((101, 1_000_100, 35, 50),
                         tuple(daily[key] for key in ("calls", "input_tokens", "output_tokens", "spent_microusd")))
        self.assertEqual({"pc": 100, "second-pc": 1}, {key: value["calls"] for key, value in daily["devices"].items()})

    def test_concurrent_requests_for_last_slot_admit_exactly_one_and_no_partial_month_write(self):
        self.limit()
        def reserve(_):
            try:
                return self.reserve()
            except Denied as e:
                self.assertEqual("daily_quota", str(e))
                return None
        with ThreadPoolExecutor(max_workers=12) as pool:
            admitted = [r for r in pool.map(reserve, range(24)) if r]
        self.assertEqual(1, len(admitted))
        data = self.ledger.read()
        self.assertEqual(1, len(data["cycles"]["anthropic:2026-11"]["reserved"]))
        self.ledger.settle("anthropic", admitted[0], 20, usage={"input_tokens": 100, "output_tokens": 20})
        day = data["friends"][B].keys().__iter__().__next__()
        daily = self.ledger.read()["friends"][B][day]
        self.assertEqual((1, 100, 20, 20), tuple(daily[k] for k in ("calls", "input_tokens", "output_tokens", "spent_microusd")))
        self.assertEqual(1, daily["devices"]["pc"]["calls"])
        with self.assertRaisesRegex(Denied, "daily_quota"):
            self.reserve(device="second-pc")
        # A different friend and the next UTC day get independent allowances.
        self.reserve(A)
        self.now += 86400
        self.reserve()

    def test_token_pending_restart_and_friend_override(self):
        self.limit(calls=100, tokens=300)
        self.reserve(tokens=200)
        restarted = CreditLedger(self.root, lambda: NOW)
        with self.assertRaisesRegex(Denied, "daily_quota"):
            restarted.reserve("anthropic", KEY, 100, friend=B, device="pc", tokens=101)
        atomic_json(self.config, {"defaults": {"daily_tokens": 300}, "friends": {B: {"daily_tokens": 400}}})
        self.reserve(tokens=200)

    def test_month_limit_is_shared_with_owner_and_no_daily_reservation_is_created_on_denial(self):
        self.ledger.reserve("anthropic", KEY, micros(190))
        with self.assertRaisesRegex(Denied, "credit_limit_95_percent"):
            self.reserve()
        self.assertNotIn("friends", self.ledger.read())

    def test_invalid_config_and_global_pause_fail_closed(self):
        for config, reason in (({"defaults": {"daily_calls": -1}}, "friend_config_unreadable"),
                               ({"paused": True}, "monthly_quota")):
            atomic_json(self.config, config)
            with self.assertRaisesRegex(Denied, reason):
                self.reserve()
        self.config.write_text("broken")
        with self.assertRaisesRegex(Denied, "friend_config_unreadable"):
            self.reserve()

    def test_spend_warning_publishes_once_and_contains_no_private_text(self):
        self.ledger.reserve("anthropic", KEY, micros(185))
        calls = []
        def run(args, **kw):
            calls.append(args)
            return SimpleNamespace(stdout="{}")
        with patch.dict(os.environ, {"MULTICA_CLI_PATH": "/synthetic/multica"}):
            quiet = publish(self.root, run=run, now=datetime.fromtimestamp(NOW, timezone.utc))
            self.assertEqual("ok", quiet["status"])
            with self.assertRaisesRegex(Denied, "95_percent"):
                self.ledger.reserve("anthropic", KEY, micros(6))
            first = publish(self.root, run=run, now=datetime.fromtimestamp(NOW, timezone.utc))
            publish(self.root, run=run, now=datetime.fromtimestamp(NOW + 60, timezone.utc))
        self.assertEqual("warn", first["status"])
        asks = [c for c in calls if "--key" in c and c[c.index("--key") + 1] == "ask"]
        self.assertEqual(1, len(asks))
        self.assertIn("赠额 95% 保护线", json.loads(asks[0][-1])["q"])
        self.assertTrue(all(KEY not in str(c) for c in calls))

    def test_warning_does_not_replace_an_existing_question_or_fail_on_a_broken_marker(self):
        self.ledger.reserve("anthropic", KEY, micros(190))
        (self.root / "friend-spend-watch.json").write_text("broken")
        calls = []
        def run(args, **kw):
            calls.append(args)
            return SimpleNamespace(stdout=json.dumps({"ask": json.dumps({"q": "another decision"})}))
        with patch.dict(os.environ, {"MULTICA_CLI_PATH": "/synthetic/multica"}):
            publish(self.root, run=run, now=datetime.fromtimestamp(NOW, timezone.utc))
        self.assertFalse(any("--key" in c and c[c.index("--key") + 1] == "ask" for c in calls))
        self.assertNotIn("asked_guard_month", json.loads((self.root / "friend-spend-watch.json").read_text()))


class FriendApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_api_only_all_failures_map_without_cli_and_success_uses_haiku_even_on_repair(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/friends.json"}):
            fund(Path(temp))
            ledger = CreditLedger(Path(temp), lambda: NOW)
            seen = []
            def response(request):
                seen.append(request)
                return httpx.Response(200, content=frames("anthropic"))
            def router(keys):
                return ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(response)), keys=keys)
            def req():
                return bridge.build_req({"model": "claude-sonnet-5-5", "max_tokens": 100,
                    "messages": [{"role": "user", "content": "synthetic passage"}]},
                    {"x-coach-task": "READER_SELECTION", "x-coach-friend": B, "x-coach-friend-device": "pc"})
            cli = AsyncMock(side_effect=AssertionError("Friend must never enter subscription CLI"))
            with patch.object(bridge, "dispatch_cli", cli), patch.object(bridge, "write_log"):
                for keys, expected in (({}, "unavailable"),):
                    request = req()
                    with patch.object(bridge, "API_ROUTER", router(keys)):
                        result = await bridge.dispatch(request, request.model, None)
                    self.assertEqual(expected, result.reason)
                    self.assertFalse(request.fallback_enabled)
                request = req()
                request.format_attempt = 2
                with patch.object(bridge, "API_ROUTER", router({"anthropic": KEY})):
                    result = await bridge.dispatch(request, request.model, None)
                self.assertEqual("complete", result.status)
                self.assertEqual(CONFIG["models"]["friend"], json.loads(seen[0].content)["model"])
                self.assertEqual(1, request.physical_calls)
                atomic_json(Path(temp) / "friends.json", {"defaults": {"daily_calls": 1}})
                request = req()
                with patch.object(bridge, "API_ROUTER", router({"anthropic": KEY})):
                    result = await bridge.dispatch(request, request.model, None)
                self.assertEqual("daily_quota", result.reason)
                self.assertEqual(1, len(seen))
                cli.assert_not_called()

    async def test_friend_batch_uses_haiku_while_interactive_uses_sonnet_with_haiku_fallback(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/friends.json"}):
            fund(Path(temp))
            ledger = CreditLedger(Path(temp), lambda: NOW)
            seen = []
            fail_sonnet = False

            def response(request):
                body = json.loads(request.content)
                seen.append(body["model"])
                if fail_sonnet and body["model"] == CONFIG["models"]["friend"]:
                    return httpx.Response(500)
                return httpx.Response(200, content=frames("anthropic", model=body["model"]))

            router = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(response)), keys={"anthropic": KEY})
            cli = AsyncMock(side_effect=AssertionError("Friend must never enter subscription CLI"))
            with patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "dispatch_cli", cli), patch.object(bridge, "write_log"):
                # 1) Friend chapter batch requests Haiku and must actually serve Haiku (not overridden to Sonnet).
                batch_req = bridge.build_req(
                    {"model": "claude-haiku-5-5", "max_tokens": 200, "messages": [{"role": "user", "content": "batch chunk"}]},
                    {"x-coach-task": "READER_SELECTION", "x-coach-friend": B, "x-coach-friend-device": "pc"},
                )
                batch_res = await bridge.dispatch(batch_req, batch_req.model, None)
                self.assertEqual("complete", batch_res.status)
                self.assertEqual("claude-haiku-5-5", batch_res.served_by)
                self.assertFalse(batch_req.fallback)
                self.assertEqual(["claude-haiku-5-5"], seen)

                # 2) Friend interactive requests Sonnet primary; when Sonnet fails with HTTP 500 before text, fall back to Haiku on friend ledger.
                fail_sonnet = True
                interactive_req = bridge.build_req(
                    {"model": "claude-sonnet-5-5", "max_tokens": 200, "messages": [{"role": "user", "content": "interactive explain"}]},
                    {"x-coach-task": "READER_SELECTION", "x-coach-friend": B, "x-coach-friend-device": "pc"},
                )
                fallback_res = await bridge.dispatch(interactive_req, interactive_req.model, None)
                self.assertEqual("complete", fallback_res.status)
                self.assertEqual("claude-haiku-5-5", fallback_res.served_by)
                self.assertTrue(interactive_req.fallback)
                self.assertEqual(["claude-haiku-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"], seen)

                # Two successful calls plus one conservatively accounted failed physical attempt.
                day = next(iter(ledger.read()["friends"][B].values()))
                self.assertEqual(3, day["calls"])
                self.assertEqual(3, day["devices"]["pc"]["calls"])
                self.assertEqual({}, day["reserved"])

                # 3) Friend quota denial must NOT fall back to Haiku.
                atomic_json(Path(temp) / "friends.json", {"defaults": {"daily_calls": 2}})
                denied_req = bridge.build_req(
                    {"model": "claude-sonnet-5-5", "max_tokens": 200, "messages": [{"role": "user", "content": "over quota"}]},
                    {"x-coach-task": "READER_SELECTION", "x-coach-friend": B, "x-coach-friend-device": "pc"},
                )
                denied_res = await bridge.dispatch(denied_req, denied_req.model, None)
                self.assertEqual("daily_quota", denied_res.reason)
                self.assertEqual(3, len(seen))
                cli.assert_not_called()

    async def disconnect_after_text(self, ledger, receipts):
        """Cancel the production SSE body while it is yielding text to the downstream client."""
        model = "claude-haiku-5-5"
        frames = [{"type": "message_start", "message": {"model": model, "usage": receipts}},
                  {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "synthetic answer"}}]
        body = "".join("data: " + json.dumps(frame) + "\n\n" for frame in frames)
        router = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(200, content=body))), keys={"anthropic": KEY})
        req = bridge.build_req({"model": model, "stream": True, "max_tokens": 100,
                                "messages": [{"role": "user", "content": "synthetic question"}]},
                               {"x-coach-task": "READER_SELECTION", "x-coach-friend": A, "x-coach-friend-device": "pc"})
        with patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "write_log") as audit:
            events = bridge.routed_events(req, model, None)
            response = bridge.live_stream(req, "synthetic", NOW, [], events, True)
            stream = response.body_iterator
            async for chunk in stream:
                if "synthetic answer" in chunk:
                    break
            else:
                self.fail("missing SSE text frame")
            with self.assertRaises(asyncio.CancelledError):
                await stream.athrow(asyncio.CancelledError())
            self.assertEqual("client_gone", audit.call_args.kwargs["reason"])

    async def test_client_gone_bills_reported_tokens_and_bounds_only_unconfirmed_output(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/missing"}):
            root = Path(temp)
            fund(root)
            ledger = CreditLedger(root, lambda: NOW)
            await self.disconnect_after_text(ledger, {"input_tokens": 90, "cache_read_input_tokens": 10, "output_tokens": 7})
            data = ledger.read()
            summary = data["months"]["2026-11"]["anthropic"]
            observed = cost("claude-haiku-5-5", {"input_tokens": 100, "cached_tokens": 10, "output_tokens": 7})
            bounded = cost("claude-haiku-5-5", {"input_tokens": 100, "cached_tokens": 10, "output_tokens": 100})
            self.assertEqual(observed, summary["spent_microusd"] - summary["estimated_microusd"])
            self.assertEqual(bounded, summary["spent_microusd"])
            self.assertEqual({}, data["cycles"]["anthropic:2026-11"]["reserved"])
            daily = next(iter(data["friends"][A].values()))
            self.assertEqual({}, daily["reserved"])
            self.assertEqual(bounded - observed, daily["estimated_microusd"])
            self.assertEqual(bounded - observed, daily["devices"]["pc"]["estimated_microusd"])
            self.assertEqual((1, 100, 100, bounded), tuple(daily[k] for k in ("calls", "input_tokens", "output_tokens", "spent_microusd")))

    async def test_client_gone_without_usage_expires_only_its_own_hold_after_restart(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/missing"}):
            root = Path(temp)
            fund(root)
            now = NOW
            ledger = CreditLedger(root, lambda: now)
            legacy = ledger.reserve("anthropic", KEY, 73)
            await self.disconnect_after_text(ledger, {})
            before = ledger.read()
            token, marker = next(iter(before["interrupted_streams"].items()))
            self.assertEqual(NOW + INTERRUPTED_USAGE_GRACE_SECONDS, marker["expires_at"])
            bound = before["cycles"][legacy[0]]["reserved"][token]
            now = marker["expires_at"] - 1
            restarted = CreditLedger(root, lambda: now)
            self.assertEqual(0, restarted.expire_interrupted())
            now += 1
            # The first admission after restart also sweeps while holding the ledger lock.
            fresh = restarted.reserve("anthropic", KEY, 19)
            self.assertEqual(0, restarted.expire_interrupted())
            data = restarted.read()
            self.assertEqual({}, data["interrupted_streams"])
            self.assertEqual({legacy[2]: 73, fresh[2]: 19}, data["cycles"][legacy[0]]["reserved"])
            summary = data["months"]["2026-11"]["anthropic"]
            self.assertEqual((92, bound, bound, 1), tuple(summary[k] for k in ("reserved_microusd", "spent_microusd", "estimated_microusd", "calls")))
            daily = next(iter(data["friends"][A].values()))
            self.assertEqual({}, daily["reserved"])
            self.assertEqual(bound, daily["devices"]["pc"]["spent_microusd"])
            self.assertEqual(1, daily["calls"])

    async def test_billing_and_overload_rejections_release_friend_reservation(self):
        for status in (402, 529):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temp, patch.dict(
                os.environ, {"COACH_FRIEND_CONFIG": temp + "/friends.json"}
            ):
                root = Path(temp)
                fund(root)
                ledger = CreditLedger(root, lambda: NOW)
                router = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(
                    lambda _: httpx.Response(status))), keys={"anthropic": KEY})
                with self.assertRaisesRegex(ApiFailure, f"^api_http_{status}$"):
                    [event async for event in router.events(
                        "READER_SELECTION", "claude-haiku-5-5", "s", "u", 100, friend=A, device="pc")]
                data = ledger.read()
                month = data["months"]["2026-11"]["anthropic"]
                self.assertEqual({"spent_microusd": 0, "reserved_microusd": 0, "calls": 0}, month)
                daily = next(iter(data["friends"][A].values()))
                self.assertEqual(({}, 0, 0), (daily["reserved"], daily["calls"], daily["spent_microusd"]))

    async def test_rate_limit_does_not_consume_last_friend_slot(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/friends.json"}):
            root = Path(temp)
            fund(root)
            atomic_json(root / "friends.json", {"defaults": {"daily_calls": 1}})
            ledger = CreditLedger(root, lambda: NOW)
            statuses = iter([429, 200])

            def response(_):
                return httpx.Response(next(statuses), content=frames("anthropic", model="claude-haiku-5-5"))

            router = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(response)),
                               keys={"anthropic": KEY}, retry_429_delays=())
            def request():
                return router.events("READER_SELECTION", "claude-haiku-5-5", "s", "u", 100, friend=A, device="pc")
            with self.assertRaisesRegex(ApiFailure, "^api_http_429$"):
                [event async for event in request()]
            result = [event async for event in request()]
            self.assertEqual("complete", result[-1]["kind"])
            daily = next(iter(ledger.read()["friends"][A].values()))
            self.assertEqual({}, daily["reserved"])
            self.assertEqual(1, daily["calls"])
            with self.assertRaisesRegex(Denied, "daily_quota"):
                [event async for event in request()]

    async def test_ambiguous_http_failure_debits_bound_and_unsent_cancel_releases(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/missing"}):
            fund(Path(temp))
            ledger = CreditLedger(Path(temp), lambda: NOW)
            router = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(500))), keys={"anthropic": KEY})
            request = bridge.build_req({"model": "claude-haiku-5-5", "messages": [{"role": "user", "content": "synthetic"}]},
                {"x-coach-task": "READER_SELECTION", "x-coach-friend": A, "x-coach-friend-device": "pc"})
            with patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "dispatch_cli", AsyncMock(side_effect=AssertionError("CLI"))):
                result = await bridge.dispatch(request, request.model, None)
            self.assertEqual("unavailable", result.reason)
            events = router.events("READER_SELECTION", "claude-haiku-5-5", "s", "u", 100, friend=A, device="pc")
            self.assertEqual("attempt", (await anext(events))["kind"])
            await events.aclose()
            data = ledger.read()
            self.assertEqual({}, data["cycles"]["anthropic:2026-11"]["reserved"])
            month = data["months"]["2026-11"]["anthropic"]
            self.assertEqual(1, month["calls"])
            self.assertEqual(month["spent_microusd"], month["estimated_microusd"])
            self.assertGreater(month["estimated_microusd"], 0)
            daily = next(iter(data["friends"][A].values()))
            self.assertEqual({}, daily["reserved"])
            self.assertEqual(1, daily["calls"])
            self.assertEqual(month["estimated_microusd"], daily["spent_microusd"])

    async def test_friend_vision_stays_anthropic_only_and_obeys_shared_guard(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/friends.json"}):
            fund(Path(temp))
            ledger = CreditLedger(Path(temp), lambda: NOW)
            png_b64 = make_png_b64(800, 600)
            seen = []

            def response(request):
                seen.append(request.url.host)
                payload = json.loads(request.content)
                self.assertEqual("image", payload["messages"][0]["content"][0]["type"])
                return httpx.Response(200, content=frames("anthropic", model=payload["model"]))

            router = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(response)),
                               keys={"anthropic": KEY, "openai": KEY})
            cli = AsyncMock(side_effect=AssertionError("Friend vision must never enter subscription CLI"))
            req_body = {
                "model": "claude-sonnet-5-5",
                "max_tokens": 2048,
                "messages": [{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Transcribe page"},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{png_b64}"}},
                    ],
                }],
            }
            headers = {"x-coach-task": "READER_SELECTION", "x-coach-friend": B, "x-coach-friend-device": "pc"}
            with patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "dispatch_cli", cli), patch.object(bridge, "write_log"):
                ok_req = bridge.build_req(req_body, headers)
                ok_res = await bridge.dispatch(ok_req, ok_req.model, None)
                self.assertEqual("complete", ok_res.status)
                self.assertEqual(["api.anthropic.com"], seen)
                day = next(iter(ledger.read()["friends"][B].values()))
                self.assertEqual(1, day["calls"])

                # Exhaust remaining Anthropic share; friend vision must be denied with zero network calls and no OpenAI fallback.
                spent = ledger.read()["cycles"]["anthropic:2026-11"]["spent"]
                ledger.reserve("anthropic", KEY, micros(190) - spent)
                denied_req = bridge.build_req(req_body, headers)
                denied_res = await bridge.dispatch(denied_req, denied_req.model, None)
                self.assertEqual("failed", denied_res.status)
                self.assertEqual("monthly_quota", denied_res.reason)
                self.assertEqual(["api.anthropic.com"], seen)
                cli.assert_not_called()

    async def test_friend_audit_has_separate_private_file_without_prompt_or_key(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_API_STATE_DIR": temp, "BRIDGE_LOG_STDOUT": "1"}):
            with patch("builtins.print") as output:
                bridge.write_log(friend=A, device="pc", status="failed", physical_calls=1, prompt="secret-source", note=KEY)
            output.assert_not_called()
            path = Path(temp) / "friends" / A / "bridge.log"
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertNotIn(KEY, path.read_text())
            self.assertNotIn("secret-source", path.read_text())

    async def test_friend_minimal_effort_header_disables_thinking_in_upstream_payload(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"COACH_FRIEND_CONFIG": temp + "/friends.json"}):
            fund(Path(temp))
            ledger = CreditLedger(Path(temp), lambda: NOW)
            seen_payloads = []

            def response(request):
                body = json.loads(request.content)
                seen_payloads.append(body)
                return httpx.Response(200, content=frames("anthropic", model=body["model"]))

            router = ApiRouter(ledger, lambda: httpx.AsyncClient(transport=httpx.MockTransport(response)), keys={"anthropic": KEY})
            cli = AsyncMock(side_effect=AssertionError("Friend must never enter subscription CLI"))
            with patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "dispatch_cli", cli), patch.object(bridge, "write_log"):
                min_req = bridge.build_req(
                    {"model": "claude-haiku-5-5", "max_tokens": 200, "messages": [{"role": "user", "content": "batch chunk"}]},
                    {"x-coach-task": "READER_SELECTION", "x-coach-friend": B, "x-coach-friend-device": "pc", "x-coach-effort": "minimal"},
                )
                min_res = await bridge.dispatch(min_req, min_req.model, None)
                self.assertEqual("complete", min_res.status)
                self.assertEqual({"type": "disabled"}, seen_payloads[0].get("thinking"))

                std_req = bridge.build_req(
                    {"model": "claude-haiku-5-5", "max_tokens": 200, "messages": [{"role": "user", "content": "math audit"}]},
                    {"x-coach-task": "READER_SELECTION", "x-coach-friend": B, "x-coach-friend-device": "pc"},
                )
                std_res = await bridge.dispatch(std_req, std_req.model, None)
                self.assertEqual("complete", std_res.status)
                self.assertNotIn("thinking", seen_payloads[1])
                cli.assert_not_called()


if __name__ == "__main__":
    unittest.main()
