"""Regression for the production comparison population; no real logs or material."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('reader_stats', Path(__file__).with_name('reader-timeout-stats.py'))
stats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stats)


class ReaderStatsTest(unittest.TestCase):
    def test_window_denominator_and_test_exclusion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'server-synthetic.log').write_text(''.join('COACH reader.end '+line+'\n' for line in [
                'at_ms=100 status=complete traffic=user',
                'at_ms=101 status=timeout_first traffic=user',
                'at_ms=102 status=cancelled traffic=user',
                'at_ms=104 status=format traffic=user',
                'at_ms=105 status=disconnected traffic=user',
                'at_ms=103 status=timeout_gap traffic=test',
                'at_ms=200 status=timeout_total traffic=user',
                'status=timeout_gap traffic=user']))
            result = stats.summarize(root, 100, 200)
            self.assertEqual((5, 1, 1/5, 1), (result['requests'], result['timeouts'],
                result['timeoutRate'], result['excludedSynthetic']))
            self.assertEqual(1, result['statuses']['format'])
            self.assertEqual(1, result['statuses']['cancelled'])
            self.assertEqual(1, result['statuses']['disconnected'])
            self.assertTrue(result['comparable'])

    def test_absent_instrumentation_is_not_a_zero_failure_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            result = stats.summarize(Path(directory), 100, 200)
            self.assertFalse(result['comparable'])
            self.assertIsNone(result['timeoutRate'])
            self.assertIsNotNone(result['limitation'])


if __name__ == '__main__':
    unittest.main()
