"""TON-740: the ledger is the only spend stop, so it must never price a call below the provider's bill.

The floors are the providers' standard list prices checked on 2026-11-27
(https://platform.claude.com/docs/en/about-claude/pricing,
https://developers.openai.com/api/docs/models/<model>). A model added to routing without a
price, or a price edited below its floor, fails here before it can under-count spend.
"""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from api_route import CONFIG  # noqa: E402

# USD per million tokens: input, output, cached input.
FLOORS = {
    "claude-haiku-5-5": (0.10, 0.50, 0.01),
    "claude-sonnet-5-5": (2.00, 10.00, 0.10),
    "claude-opus-5-5": (4.00, 20.00, 0.20),
    "gpt-6.1-terra": (1.00, 5.00, 0.05),
    "gpt-6.1-sol": (2.00, 10.00, 0.10),
    # GPT-6.2 input may include automatic cache writes, billed above the plain input rate.
    "gpt-6.2-sol": (2.50, 10.00, 0.10),
    "gpt-6.2-astra": (10.00, 40.00, 0.40),
}


def routed_models():
    models = set(CONFIG["models"].values()) | set(CONFIG.get("fallbacks", {}).values())
    return {m for m in models if m.startswith(("claude-", "gpt-"))}


class PriceFloorTests(unittest.TestCase):
    def test_every_routed_model_has_a_price_at_or_above_its_list_floor(self):
        prices = CONFIG["prices_usd_per_million"]
        for model in sorted(routed_models() | set(prices)):
            with self.subTest(model=model):
                self.assertIn(model, FLOORS, "add this model's list price to FLOORS before routing it")
                self.assertIn(model, prices, "a routed model without a price would be charged nothing")
                floor_in, floor_out, floor_cached = FLOORS[model]
                price = prices[model]
                self.assertGreaterEqual(price["input"], floor_in)
                self.assertGreaterEqual(price["output"], floor_out)
                self.assertGreaterEqual(price["cached"], floor_cached)

    def test_review_example_is_priced_at_the_standard_rate(self):
        # 10k input + 1k output on GPT-6.2 Astra: $0.120 at list price (was $0.042 locally).
        from api_route import cost
        self.assertGreaterEqual(cost("gpt-6.2-astra", {"input_tokens": 10_000, "output_tokens": 1_000}), 120_000)


if __name__ == "__main__":
    unittest.main()
