"""TON-740: the one-time raise on a temporary copy — worst-case back-charge, re-priced holds, measured allowance."""
import hashlib
import json
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import api_budget as ab  # noqa: E402
from api_budget import CreditLedger, Denied  # noqa: E402
import ton740_raise_coach_share as raise_share  # noqa: E402

NOW = 1795737600 + 3600  # 2026-11-27 01:00 UTC
M = 1_000_000


class RaiseCoachShareTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.state = (Path(tmp.name) / "state").resolve()
        self.state.mkdir()
        # The production shape before the raise: other writers 120 / 400, one Coach share at the 95% line.
        for provider, others, coach in (("anthropic", 120 * M, 70 * M), ("openai", 400 * M, 75 * M)):
            plan = {"version": 1, "provider": provider, "start_utc": 1793491200, "end_utc": 1798761600,
                    "grant_microusd": ab.micros(ab.MONTHLY_GRANTS[provider]), "other_writers_microusd": others,
                    "ledgers": [{"host": socket.gethostname(), "state": str(self.state),
                                 "limit_microusd": coach, "prior_settled_microusd": 0}]}
            (self.state / f"grant-allocation-{provider}.json").write_text(json.dumps(plan))
            (self.state / f"grant-window-{provider}.json").write_text(json.dumps(
                {"digest": hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest(), "end_utc": plan["end_utc"]}))
        # November spend recorded at the old rates, plus one OpenAI hold still open from the old table.
        self.ledger = CreditLedger(self.state, lambda: NOW)
        self.ledger.save({"version": 1, "months": {"2026-11": {
            "anthropic": {"spent_microusd": 36_930_400, "reserved_microusd": 0, "calls": 7400},
            "openai": {"spent_microusd": 12_974_038, "reserved_microusd": 1_000_000, "calls": 1154}}},
            "cycles": {"anthropic:2026-11": {"spent": 36_930_400, "reserved": {}, "limit": 149_790_000},
                       "openai:2026-11": {"spent": 12_974_038, "reserved": {"oldhold": 1_000_000}, "limit": 475_000_000}}})

    def test_back_charges_to_the_worst_case_reprices_holds_raises_and_runs_once(self):
        allowance = raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
        data = self.ledger.read()
        # Worst case: everything recorded at the old rates was gpt-6.2-astra → $43.25 in all.
        self.assertEqual(12_974_038 + 30_280_000, data["cycles"]["openai:2026-11"]["spent"])
        self.assertEqual(30_280_000, data["months"]["2026-11"]["openai"]["estimated_microusd"])
        self.assertGreaterEqual(data["cycles"]["openai:2026-11"]["spent"], 12_974_038 * 40 // 12)
        # The old-rate hold is re-priced at the worst new/old ratio (10/3), so its settlement cannot exceed it.
        self.assertEqual(3_333_334, data["cycles"]["openai:2026-11"]["reserved"]["oldhold"])
        self.assertEqual(3_333_334, data["months"]["2026-11"]["openai"]["reserved_microusd"])
        # Shares are the whole free grant minus other writers.
        for provider, coach in (("anthropic", 80 * M), ("openai", 100 * M)):
            plan = json.loads((self.state / f"grant-allocation-{provider}.json").read_text())
            self.assertEqual(coach, plan["ledgers"][0]["limit_microusd"])
        self.assertEqual({"anthropic": 80 * M - 36_930_400, "openai": 100 * M - 43_254_038 - 3_333_334}, allowance)
        # Measured on the copy: exactly the allowance passes, then the hold blocks (busy) …
        self.ledger.reserve("openai", "k", allowance["openai"])
        with self.assertRaisesRegex(Denied, "^reservations_busy$"):
            self.ledger.reserve("openai", "k", 1)
        # … and once everything settles at its bound, the free grant is used up at Coach's $100.
        data = self.ledger.read()
        for token, amount in list(data["cycles"]["openai:2026-11"]["reserved"].items()):
            self.ledger.settle("openai", ("openai:2026-11", "2026-11", token), amount)
        with self.assertRaisesRegex(Denied, "^free_grant_exhausted$"):
            self.ledger.reserve("openai", "k", 1)
        self.assertEqual(100 * M, self.ledger.read()["cycles"]["openai:2026-11"]["spent"])
        # A rerun neither back-charges nor re-prices again.
        before = self.ledger.read()
        raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
        self.assertEqual(before["cycles"], self.ledger.read()["cycles"])

    def test_spend_recorded_since_review_is_scaled_too(self):
        data = self.ledger.read()
        for key in (("cycles", "openai:2026-11", "spent"), ("months", "2026-11", "openai", "spent_microusd")):
            node = data
            for part in key[:-1]:
                node = node[part]
            node[key[-1]] = 20_000_000
        self.ledger.save(data)
        raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
        self.assertEqual(66_666_667, self.ledger.read()["cycles"]["openai:2026-11"]["spent"])

    def snapshot(self):
        return {p.name: p.read_bytes() for p in sorted(self.state.iterdir())
                if p.name != "api-spend.lock" and not p.name.endswith(".tmp")}

    def copy_of_state(self):
        import shutil
        probe = Path(tempfile.mkdtemp(dir=self.state.parent))
        for p in self.state.iterdir():
            if p.is_file():
                shutil.copy2(p, probe / p.name)
        for provider in ("anthropic", "openai"):   # the copy's own identity in its plan, pin re-derived as on disk
            plan_path, pin_path = probe / f"grant-allocation-{provider}.json", probe / f"grant-window-{provider}.json"
            plan, pin = json.loads(plan_path.read_text()), json.loads(pin_path.read_text())
            matched = pin["digest"] == hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest()
            plan["ledgers"][0]["state"] = str(probe.resolve())
            plan_path.write_text(json.dumps(plan))
            if matched:
                pin["digest"] = hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest()
            pin_path.write_text(json.dumps(pin))
        return probe.resolve()

    def writes_of_a_clean_run(self):
        names = []
        real = ab.atomic_json
        def record(path, value):
            names.append(Path(path).name)
            return real(path, value)
        with patch.object(ab, "atomic_json", record):
            raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
        return names

    def test_an_interrupted_run_rerun_ends_exactly_like_one_clean_run(self):
        # TON-740 review: fail each write once, rerun `apply`, and the result must match a clean run.
        pristine = self.snapshot()
        writes = self.writes_of_a_clean_run()
        reference = self.snapshot()
        self.assertEqual(["api-credit-ledger.json", "api-spend-2026-11.json",
                          "grant-window-anthropic.json", "grant-allocation-anthropic.json", "grant-window-anthropic.json",
                          "grant-window-openai.json", "grant-allocation-openai.json", "grant-window-openai.json"], writes)
        for failing, bridge_call_between in [(i, b) for i in range(len(writes)) for b in (False, True)]:
            with self.subTest(write=f"{failing}:{writes[failing]}", bridge_call_between=bridge_call_between):
                for name, content in pristine.items():
                    (self.state / name).write_bytes(content)
                for name in set(self.snapshot()) - set(pristine):
                    (self.state / name).unlink()
                real, calls = ab.atomic_json, []
                def flaky(path, value):
                    calls.append(path)
                    if len(calls) == failing + 1:
                        raise OSError("injected write failure")
                    return real(path, value)
                with patch.object(ab, "atomic_json", flaky), self.assertRaisesRegex(OSError, "injected"):
                    raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
                # Never a stall in between: both providers still admit calls on whatever plan is on disk
                # (checked on a throwaway copy unless the bridge really calls in between and re-pins).
                probe = self.state if bridge_call_between else self.copy_of_state()
                ledger = CreditLedger(probe, lambda: NOW)
                for provider in ("anthropic", "openai"):
                    ledger.allocation(provider, NOW, ledger.read())
                raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
                self.assertEqual(reference, self.snapshot())

    def test_a_lost_marker_cannot_double_the_back_charge(self):
        # The marker travels inside the same ledger write as the back-charge: there is no state with one and not the other.
        raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
        once = self.ledger.read()
        self.assertIn(raise_share.MARKER, once["migrations"])
        raise_share.run(self.state, apply=True, now=NOW, out=lambda *_: None)
        twice = self.ledger.read()
        self.assertEqual(once, twice)
        self.assertEqual(43_254_038, twice["cycles"]["openai:2026-11"]["spent"])
        self.assertEqual(3_333_334, twice["cycles"]["openai:2026-11"]["reserved"]["oldhold"])

    def test_check_changes_nothing(self):
        files = lambda: {p.name: p.read_bytes() for p in self.state.iterdir() if p.name != "api-spend.lock"}
        before = files()
        raise_share.run(self.state, apply=False, now=NOW, out=lambda *_: None)
        self.assertEqual(before, files())


if __name__ == "__main__":
    unittest.main()
