import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

BRIDGE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BRIDGE))
from api_budget import CreditLedger, atomic_json


class EraseFriendUsageTest(unittest.TestCase):
    def test_retry_preserves_pending_cost_and_other_account(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "friends.json"
            x, y = "a" * 64, "b" * 64
            totals = dict(calls=1, input_tokens=5, output_tokens=3, spent_microusd=20)
            daily = dict(totals, reserved={}, devices={"pc": totals})
            pending = {"test-hold": dict(tokens=10, device="pc", microusd=30)}
            usage = {x: {"day": dict(daily, reserved=pending)}, y: {"day": daily}}
            summary = {"spent_microusd": 40, "reserved_microusd": 30, "calls": 2}
            ledger = CreditLedger(root)
            ledger.save(dict(version=1, cycles={"anthropic:month": dict(spent=40, limit=100, reserved={"test-hold": 30})},
                             months={"month": {"anthropic": summary}}, friends=usage))
            atomic_json(config, {"defaults": {}, "friends": {x: {"daily_calls": 2}, y: {"daily_calls": 7}}})
            command = [sys.executable, str(BRIDGE / "erase_friend_usage.py"), str(root), str(config), x]
            self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
            self.assertEqual(usage, ledger.read()["friends"])
            ledger.settle("anthropic", ("anthropic:month", "month", "test-hold"), 12, usage={"input_tokens": 4, "output_tokens": 2})
            settled = ledger.read()
            for _ in range(2):
                subprocess.run(command, capture_output=True, check=True)
            final = ledger.read()
            self.assertEqual({y: usage[y]}, final["friends"])
            self.assertEqual(settled["months"], final["months"])
            self.assertEqual(settled["cycles"], final["cycles"])
            self.assertEqual({y: {"daily_calls": 7}}, json.loads(config.read_text())["friends"])


if __name__ == "__main__":
    unittest.main()
