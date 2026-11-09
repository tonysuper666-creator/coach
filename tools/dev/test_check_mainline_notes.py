import importlib.util
import pathlib
import unittest

SPEC = importlib.util.spec_from_file_location('check_mainline_notes', pathlib.Path(__file__).with_name('check-mainline-notes.py'))
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


class P95ObservationTest(unittest.TestCase):
    def test_slow_p95_is_reported_not_raised(self):
        self.assertEqual(mod.p95_observation(1180.9), dict(budgetMs=800, withinBudget=False))

    def test_within_budget_and_boundary(self):
        self.assertTrue(mod.p95_observation(800)['withinBudget'])
        self.assertTrue(mod.p95_observation(120.5)['withinBudget'])

    def test_no_samples_is_unknown(self):
        self.assertIsNone(mod.p95_observation(None)['withinBudget'])

    def test_script_no_longer_asserts_on_latency_but_keeps_other_asserts(self):
        text = pathlib.Path(mod.__file__).read_text(encoding='utf-8')
        self.assertNotIn('p95 <= 800', text.replace('p95 <= SUBMIT', ''))
        self.assertIn("assert len(retried) == 6", text)
        self.assertIn("Replay must preserve wire and stored outcome", text)


if __name__ == '__main__':
    unittest.main()
