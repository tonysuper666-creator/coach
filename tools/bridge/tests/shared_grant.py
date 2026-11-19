"""Two independent ledger directories, real flock, synthetic identities and funds."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import httpx

from api_routing import KEY, ApiRouter, CreditLedger, Denied, atomic_json, frames
from friend_spend_watch import publish_grant_decisions, pull_grant_guards


class SharedGrantTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.states = [self.root / "private", self.root / "friend"]
        self.now = int(datetime(2026, 11, 30, 23, 59, tzinfo=timezone.utc).timestamp())
        self.plan = {"version": 1, "provider": "anthropic", "grant_microusd": 200_000_000,
                     "start_utc": self.now - 86400,
                     "end_utc": self.now + 86400, "other_writers_microusd": 25_000_000,
                     "ledgers": []}
        for state, share in zip(self.states, (15_210_000, 149_790_000)):
            self.plan["ledgers"].append({"host": socket.gethostname(), "state": str(state.resolve()),
                                         "limit_microusd": share, "prior_settled_microusd": 0})
            atomic_json(state / "api-credit-ledger.json", {"version": 1, "cycles": {}, "months": {}})
        for state in self.states:
            self.install(state)

    def install(self, state):
        atomic_json(state / "grant-allocation-anthropic.json", self.plan)

    def ledger(self, site):
        return CreditLedger(self.states[site], lambda: self.now)

    def test_two_sites_compete_for_last_micros_and_one_receiver_deduplicates(self):
        barrier = threading.Barrier(4)
        def claim(site):
            barrier.wait()
            try:
                return self.ledger(site).reserve("anthropic", KEY, 1)
            except Denied:
                return None
        for site, size in enumerate((15_209_999, 149_789_999)):
            self.ledger(site).reserve("anthropic", KEY, size)
        with ThreadPoolExecutor(max_workers=4) as pool:
            accepted = list(pool.map(claim, (0, 1, 0, 1)))
        self.assertEqual(2, len([r for r in accepted if r]))
        pending = sum(sum(self.ledger(i).read()["cycles"]["anthropic:2026-11"]["reserved"].values())
                      for i in (0, 1))
        self.assertEqual(190_000_000, pending + self.plan["other_writers_microusd"])
        for site in (0, 1):
            with self.assertRaisesRegex(Denied, "95_percent"):
                self.ledger(site).reserve("anthropic", KEY, 1)

        # Both machines deliver the same durable notice after the receiver restarts.
        inbox = self.root / "inbox"
        inbox.mkdir()
        notice = next(self.states[0].glob("grant-guard-*.json")).name
        self.assertTrue(pull_grant_guards("peer", inbox, run=lambda *a, **kw: SimpleNamespace(stdout=notice)))
        def offline(*args, **kwargs):
            raise TimeoutError()
        self.assertFalse(pull_grant_guards("peer", inbox, run=offline))
        calls = []
        metadata = {"ask": json.dumps({"q": "another decision"})}
        def receive(args, **kwargs):
            self.assertEqual("TON-1691", args[4])
            if args[3] == "list":
                return SimpleNamespace(stdout=json.dumps(metadata))
            metadata[args[6]] = args[-1]
            calls.append(args[6])
        def publish():
            return publish_grant_decisions(self.states[1], inbox, run=receive)
        with patch.dict(os.environ, {"MULTICA_CLI_PATH": "synthetic"}):
            self.assertEqual(1, publish()["pending"])
            metadata.clear()
            with ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(lambda _: publish(), range(2)))
            metadata.clear()  # Simulate the user answering before the next poll.
            self.assertEqual(0, publish()["pending"])
        self.assertEqual(["ask", "ask_since"], calls)

    def test_rollover_offline_peer_and_late_idempotent_settlement(self):
        old = self.ledger(0).reserve("anthropic", KEY, 15_210_000)
        self.states[1].rename(self.root / "offline")
        self.now += 120
        with self.assertRaisesRegex(Denied, "95_percent"):
            self.ledger(0).reserve("anthropic", KEY, 1)
        for _ in range(2):
            self.ledger(0).settle("anthropic", old, 15_209_999, idempotent=True)
        self.ledger(0).reserve("anthropic", KEY, 1)
        with self.assertRaisesRegex(Denied, "95_percent"):
            self.ledger(0).reserve("anthropic", KEY, 1)
        self.now = self.plan["end_utc"]
        with self.assertRaisesRegex(Denied, "allocation_unavailable"):
            self.ledger(0).reserve("anthropic", KEY, 1)

    def test_lock_zero_and_migrated_settled_and_pending_preserved(self):
        data = self.ledger(1).read()
        data["cycles"] = {"anthropic:2026-10": {"spent": 0, "reserved": {"a": 9}, "limit": 150_000_000},
                          "anthropic:2026-11": {"spent": 321, "reserved": {}, "limit": 149_790_000}}
        self.ledger(1).save(data)
        self.ledger(1).reserve("anthropic", KEY, 149_790_000 - 321 - 9, friend="a"*64, device="pc", tokens=1)
        before = self.ledger(1).read()
        self.assertEqual(1, len(before["friends"]["a"*64]["2026-11-30"]["reserved"]))
        self.assertEqual(data["cycles"]["anthropic:2026-10"], before["cycles"]["anthropic:2026-10"])
        before["cycles"]["anthropic:2026-11"]["limit"] = 0
        self.ledger(1).save(before)
        self.now += 120
        with self.assertRaisesRegex(Denied, "95_percent"):
            self.ledger(1).reserve("anthropic", KEY, 1)
        self.assertEqual(before, self.ledger(1).read())

    def test_next_verified_window_excludes_only_prior_settlement(self):
        old = self.ledger(0).reserve("anthropic", KEY, 123)
        self.ledger(0).settle("anthropic", old, 120)
        pending = self.ledger(0).reserve("anthropic", KEY, 17)
        self.now = self.plan["end_utc"]
        self.plan.update(start_utc=self.now, end_utc=self.now + 86400)
        self.plan["ledgers"][0]["prior_settled_microusd"] = 120
        self.install(self.states[0])
        self.ledger(0).reserve("anthropic", KEY, 15_210_000 - 17)
        self.ledger(0).settle("anthropic", pending, 16)
        self.ledger(0).reserve("anthropic", KEY, 1)
        with self.assertRaisesRegex(Denied, "95_percent"):
            self.ledger(0).reserve("anthropic", KEY, 1)

    def test_adjusted_legacy_ceiling_survives_mid_grant_calendar_rollover(self):
        self.plan["ledgers"][0]["limit_microusd"] -= 210_000
        self.plan["ledgers"][1]["limit_microusd"] += 210_000
        self.install(self.states[1])
        data = self.ledger(1).read()
        data["cycles"]["anthropic:2026-11"] = {"spent": 149_790_000, "limit": 149_790_000, "reserved": {}}
        self.ledger(1).save(data)
        self.now += 120
        with self.assertRaisesRegex(Denied, "95_percent"):
            self.ledger(1).reserve("anthropic", KEY, 1)

    def test_wrong_identity_overallocated_reissued_and_missing_ledger(self):
        state = self.states[0]
        self.plan["other_writers_microusd"] += 1
        self.install(state)
        with self.assertRaisesRegex(Denied, "allocation_unavailable"):
            self.ledger(0).reserve("anthropic", KEY, 1)
        self.plan["other_writers_microusd"] -= 1
        self.install(state)
        self.ledger(0).reserve("anthropic", KEY, 1)
        self.plan["ledgers"][0]["limit_microusd"] -= 1
        self.install(state)
        with self.assertRaisesRegex(Denied, "allocation_unavailable"):
            self.ledger(0).reserve("anthropic", KEY, 1)
        self.install(self.root)
        with self.assertRaisesRegex(Denied, "allocation_unavailable"):
            CreditLedger(self.root, lambda: self.now).reserve("anthropic", KEY, 1)
        (self.states[1] / "api-credit-ledger.json").unlink()
        with self.assertRaisesRegex(Denied, "allocation_unavailable"):
            self.ledger(1).reserve("anthropic", KEY, 1)

    def test_default_router_denies_unallocated_provider_before_transport(self):
        with patch.dict(os.environ, {"COACH_API_STATE_DIR": str(self.states[1])}):
            router = ApiRouter(keys={"openai": KEY}, client_factory=lambda: self.fail("unexpected transport"))
        with self.assertRaisesRegex(Denied, "allocation_unavailable"):
            asyncio.run(anext(router.events("blind_review", "gpt-6.1-sol", "", "", None)))

    def test_stream_uses_allocated_ledger_before_every_network_attempt(self):
        state = self.states[1]
        requests = []
        def transport(request):
            requests.append(request)
            return httpx.Response(200, content=frames("anthropic"))
        with patch.dict(os.environ, {"COACH_API_STATE_DIR": str(state)}):
            router = ApiRouter(keys={"anthropic": KEY},
                               client_factory=lambda: httpx.AsyncClient(transport=httpx.MockTransport(transport)))
        router.ledger.clock = lambda: self.now
        async def stream():
            return [event async for event in router.events("TEACH", "claude-sonnet-5-5", "", "", 1)]
        self.assertEqual("complete", asyncio.run(stream())[-1]["kind"])
        # Reserve the remaining share in another worker. A retry cannot open a client.
        spent = self.ledger(1).read()["cycles"]["anthropic:2026-11"]["spent"]
        self.ledger(1).reserve("anthropic", KEY, 149_790_000 - spent)
        with self.assertRaisesRegex(Denied, "95_percent"):
            asyncio.run(stream())
        self.assertEqual(1, len(requests))
