"""TON-740: each month is Coach's whole grant, and the bridge never waits for the timer to get there.

Real reservations and settlements on a real ledger directory, calls through the bridge's HTTP endpoint
with a synthetic upstream. Production on 11-27 has one Coach ledger and a Nov 1 -> Jan 1 plan.
"""
import asyncio
import json
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from grant_fixture import fund, use_up  # noqa: E402
import api_budget as ab  # noqa: E402
from api_budget import CreditLedger, Denied, micros  # noqa: E402
from api_route import ApiRouter  # noqa: E402
import monthly_grant_plan as monthly  # noqa: E402
import subscription_bridge as bridge  # noqa: E402

KEY = "synthetic-not-a-real-key"
NOV_1, DEC_1, JAN_1 = 1793491200, 1796083200, 1798761600     # 00:00:00 UTC
JAN_2 = JAN_1 + 86_400
GRANT = {"anthropic": micros(200), "openai": micros(500)}
CALL = {"anthropic": ("READER_SELECTION", "claude-sonnet-5-5"), "openai": ("cross_review_grade", "gpt-6.2-astra")}


def frames(provider, model):
    if provider == "anthropic":
        events = [{"type": "message_start", "message": {"model": model, "usage": {"input_tokens": 100}}},
                  {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "synthetic answer"}},
                  {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 20}},
                  {"type": "message_stop"}]
    else:
        events = [{"type": "response.created", "response": {"model": model}},
                  {"type": "response.output_text.delta", "delta": "synthetic answer"},
                  {"type": "response.completed", "response": {"model": model,
                   "usage": {"input_tokens": 100, "output_tokens": 20, "input_tokens_details": {"cached_tokens": 0}}}}]
    return "".join("data: " + json.dumps(e) + "\n\n" for e in events).encode()


def month(now):
    return ab.datetime.fromtimestamp(now, ab.timezone.utc).strftime("%Y-%m")


class MonthlyGrantPlanTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name).resolve()
        self.state = self.root / "state"
        fund(self.state)                                   # Nov 1 -> Jan 1, Coach alone, whole grant
        for provider in GRANT:                             # pinned the way production pinned it
            CreditLedger(self.state, lambda: NOV_1 + 60).allocation(provider, NOV_1 + 60, CreditLedger(self.state).read())

    def ledger(self, now):
        return CreditLedger(self.state, lambda: now)

    def plan(self, provider):
        return json.loads((self.state / f"grant-allocation-{provider}.json").read_text())

    def snapshot(self):
        return {p.name: p.read_bytes() for p in sorted(self.state.iterdir()) if p.name != "api-spend.lock"}

    def call(self, provider, now):
        """One call through the bridge's HTTP endpoint at `now`: (HTTP status, x_coach, upstream requests)."""
        task, model = CALL[provider]
        seen = []

        def upstream(request):
            seen.append(request.url.host)
            return httpx.Response(200, content=frames(provider, model))

        router = ApiRouter(self.ledger(now), lambda: httpx.AsyncClient(transport=httpx.MockTransport(upstream)),
                           keys={"anthropic": KEY, "openai": KEY}, retry_429_delays=(), cross_vendor_fallback=False)
        body = {"model": model, "max_tokens": 100, "messages": [{"role": "user", "content": "synthetic"}]}

        async def post():
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=bridge.app), base_url="http://bridge.test") as client:
                return await client.post("/v1/chat/completions", json=body, headers={"X-Coach-Task": task})

        with patch.object(bridge, "USER_ONLY", False), patch.object(bridge, "API_ONLY", True), \
                patch.object(bridge, "API_ROUTER", router), patch.object(bridge, "write_log"):
            response = asyncio.run(post())
        return response.status_code, response.json().get("x_coach", {}), seen

    def assert_served_and_recorded(self, provider, now):
        before = self.ledger(now).read()["months"].get(month(now), {}).get(provider, {"calls": 0, "spent_microusd": 0})
        status, x_coach, seen = self.call(provider, now)
        self.assertEqual((200, "complete", "api", 1), (status, x_coach.get("status"), x_coach.get("transport"), len(seen)))
        after = self.ledger(now).read()["months"][month(now)][provider]
        self.assertEqual(before["calls"] + 1, after["calls"])            # the month's ledger records this one call
        self.assertGreater(after["spent_microusd"], before["spent_microusd"])
        self.assertEqual(0, after["reserved_microusd"])
        self.assertEqual(ab.month_plan(provider, now, self.state), self.plan(provider))

    def use_up_month(self, provider, now):
        ledger = self.ledger(now)
        data = ledger.read()
        spent = data["cycles"].get(f"{provider}:{month(now)}", {}).get("spent", 0)
        use_up(ledger, provider, GRANT[provider] - spent, key=KEY)
        status, x_coach, seen = self.call(provider, now)
        self.assertEqual((200, "failed", "free_grant_exhausted", []), (status, x_coach.get("status"), x_coach.get("reason"), seen))

    def test_november_used_up_then_december_first_second(self):
        for provider in GRANT:
            self.use_up_month(provider, DEC_1 - 1)                  # 11-30 23:59:59: November is gone
            self.assert_served_and_recorded(provider, DEC_1)        # 12-01 00:00:00, no timer has run
            share, used, period, holds = self.ledger(DEC_1).allocation(provider, DEC_1, self.ledger(DEC_1).read())
            self.assertEqual((GRANT[provider], 0, f"{provider}-{DEC_1}-{JAN_1}"), (share, holds, period))
            self.assertEqual(self.ledger(DEC_1).read()["cycles"][f"{provider}:2026-12"]["spent"], used)
            self.assertEqual(GRANT[provider], self.ledger(DEC_1).read()["cycles"][f"{provider}:2026-11"]["spent"])

    def test_timer_first_write_fails(self):
        for provider in GRANT:
            self.use_up_month(provider, DEC_1 - 1)
        pristine = self.snapshot()
        for target in ("grant-allocation-", "grant-window-"):
            with self.subTest(failing=target):
                for p in list(self.state.iterdir()):
                    p.unlink()
                for name, content in pristine.items():
                    (self.state / name).write_bytes(content)
                real, failed = ab.atomic_json, []

                def flaky(path, value):
                    if Path(path).name.startswith(target) and not failed:
                        failed.append(path)
                        raise OSError("injected write failure")
                    return real(path, value)

                with patch.object(ab, "atomic_json", flaky), self.assertRaisesRegex(Denied, "grant_allocation_unavailable"):
                    monthly.run(self.state, apply=True, now=DEC_1 + 5, out=lambda *_: None)
                self.assertEqual(1, len(failed))
                for provider in GRANT:
                    self.assert_served_and_recorded(provider, DEC_1 + 6)
                # The timer's next run finds nothing to change and reports December's own spend.
                settled = self.snapshot()
                report = monthly.run(self.state, apply=True, now=DEC_1 + 600, out=lambda *_: None)
                self.assertEqual(settled, self.snapshot())
                for provider in GRANT:
                    spent = self.ledger(DEC_1).read()["cycles"][f"{provider}:2026-12"]["spent"]
                    self.assertEqual(GRANT[provider] - spent, report[provider])

    def test_timer_never_runs_through_january_2(self):
        for provider in GRANT:
            self.use_up_month(provider, DEC_1 - 1)
            self.assert_served_and_recorded(provider, DEC_1)
            self.use_up_month(provider, JAN_1 - 1)                  # December used up as well
            self.assert_served_and_recorded(provider, JAN_1)
            self.assert_served_and_recorded(provider, JAN_2)        # the day the old one-day overlap ran out
            data = self.ledger(JAN_2).read()
            self.assertEqual(2, data["months"]["2027-01"][provider]["calls"])
            self.assertEqual((GRANT[provider], GRANT[provider]),
                             (data["cycles"][f"{provider}:2026-11"]["spent"], data["cycles"][f"{provider}:2026-12"]["spent"]))

    def test_timer_and_bridge_write_at_once(self):
        for provider in GRANT:
            self.use_up_month(provider, DEC_1 - 1)
        pristine = self.snapshot()
        results = {}
        for order in ("timer first", "bridge first", "together", "together", "together", "together"):
            for p in list(self.state.iterdir()):
                p.unlink()
            for name, content in pristine.items():
                (self.state / name).write_bytes(content)
            timer = lambda: monthly.run(self.state, apply=True, now=DEC_1, out=lambda *_: None)  # noqa: E731
            if order == "timer first":
                timer()
            if order == "together":
                start = threading.Barrier(2)
                worker = threading.Thread(target=lambda: (start.wait(), timer()))
                worker.start()
                start.wait()
            for provider in GRANT:
                self.assert_served_and_recorded(provider, DEC_1)
            if order == "together":
                worker.join()
            if order == "bridge first":
                timer()
            plans = {n: c for n, c in self.snapshot().items() if n.startswith(("grant-allocation-", "grant-window-"))}
            results.setdefault("files", plans)
            self.assertEqual(results["files"], plans, order)       # byte-identical whoever wrote first
            self.assertEqual([], [p.name for p in self.state.glob("*.tmp")])
            data = self.ledger(DEC_1).read()
            for provider in GRANT:
                self.assertEqual(1, data["months"]["2026-12"][provider]["calls"])

    def test_late_november_settlement_stays_in_november(self):
        provider = "openai"
        ledger = self.ledger(DEC_1 - 10)
        use_up(ledger, provider, GRANT[provider] - micros(5), key=KEY)
        late = ledger.reserve(provider, KEY, micros(5))               # 11-30 23:59:50, still streaming at midnight
        ledger.defer_interrupted(late, 1000, 1000)
        self.assert_served_and_recorded(provider, DEC_1)             # December does not wait for November's hold
        dec = self.ledger(DEC_1 + 60)
        december = dec.read()["cycles"][f"{provider}:2026-12"]["spent"]
        whole = dec.reserve(provider, KEY, GRANT[provider] - december)  # all of December's own grant, hold or not
        with self.assertRaisesRegex(Denied, "^reservations_busy$"):
            dec.reserve(provider, KEY, 1)
        dec = self.ledger(DEC_1 + 3600)
        dec.expire_interrupted()                                     # the $5 bound is charged as an estimate …
        data = dec.read()
        self.assertEqual((GRANT[provider], micros(5)), (data["months"]["2026-11"][provider]["spent_microusd"],
                                                         data["months"]["2026-11"][provider]["estimated_microusd"]))
        self.assertNotIn("estimated_microusd", data["months"]["2026-12"][provider])   # … to November only
        share, used, _, holds = dec.allocation(provider, DEC_1 + 3600, data)
        self.assertEqual((GRANT[provider], december, GRANT[provider] - december), (share, used, holds))
        dec.settle(provider, whole, GRANT[provider] - december)
        with self.assertRaisesRegex(Denied, "^free_grant_exhausted$"):
            dec.reserve(provider, KEY, 1)

    def test_december_spend_before_the_timer_counts_in_december(self):
        self.assert_served_and_recorded("anthropic", DEC_1 + 1)     # the bridge writes December's plan itself
        spent = self.ledger(DEC_1).read()["cycles"]["anthropic:2026-12"]["spent"]
        report = monthly.run(self.state, apply=True, now=DEC_1 + 5, out=lambda *_: None)
        self.assertEqual(GRANT["anthropic"] - spent, report["anthropic"])
        self.assertEqual(GRANT["openai"], report["openai"])

    def test_shared_or_missing_plans_are_never_rewritten(self):
        # Several ledgers: each deriving a whole-grant plan would add up to more than the grant.
        shared = self.plan("anthropic")
        shared["ledgers"].append(dict(shared["ledgers"][0], state=str(self.root / "elsewhere"), limit_microusd=0))
        (self.state / "grant-allocation-anthropic.json").write_text(json.dumps(shared))
        (self.state / "grant-window-anthropic.json").unlink()
        (self.state / "grant-allocation-openai.json").unlink()
        before = self.snapshot()
        lines = []
        monthly.run(self.state, apply=False, now=DEC_1, out=lines.append)
        self.assertEqual(["anthropic：计划分给了几个账本，不会自动改写，到期后拒绝", "openai：没有可读的计划，拒绝调用"], lines)
        self.ledger(DEC_1).reserve("anthropic", KEY, 1)            # the shared plan runs to its own end …
        with self.assertRaisesRegex(Denied, "grant_allocation_unavailable"):
            self.ledger(JAN_1).reserve("anthropic", KEY, 1)        # … and then denies
        with self.assertRaisesRegex(Denied, "grant_allocation_unavailable"):
            self.ledger(DEC_1).reserve("openai", KEY, 1)
        self.assertEqual(json.loads(before["grant-allocation-anthropic.json"]), self.plan("anthropic"))
        self.assertFalse((self.state / "grant-allocation-openai.json").exists())

    def test_check_reports_and_changes_nothing(self):
        before, lines = self.snapshot(), []
        monthly.run(self.state, apply=False, now=DEC_1, out=lines.append)
        monthly.run(self.state, apply=False, now=NOV_1 + 3600, out=lines.append)
        self.assertEqual(before, self.snapshot())
        self.assertEqual(["anthropic：本月计划还没写，apply 或桥的第一笔预留会当场写入",
                          "openai：本月计划还没写，apply 或桥的第一笔预留会当场写入",
                          "anthropic：本月用的是另写的计划，照原样保留",
                          "openai：本月用的是另写的计划，照原样保留"], lines)
        monthly.run(self.state, apply=True, now=DEC_1, out=lambda *_: None)
        lines.clear()
        monthly.run(self.state, apply=False, now=DEC_1 + 60, out=lines.append)
        self.assertEqual(["anthropic：本月整份赠额的计划已写好", "openai：本月整份赠额的计划已写好"], lines)


if __name__ == "__main__":
    unittest.main()
