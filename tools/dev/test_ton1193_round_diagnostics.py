"""Ensure rejection diagnostics survive without copying recording data."""
import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools.dev import check_ton1193_real_acceptance as driver
from tools.dev.ton1175_switch import GateError


class DiagnosticTest(unittest.TestCase):
    def test_inspector_rejection_retains_only_closed_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence = root / 'idle.json'
            evidence.write_text(json.dumps({'recording': 'private', 'windowStart': 'start',
                                            'windowEnd': 'end', 'pid': 123}))
            data = {'socketReads': 11, 'attributedHealthReads': 10, 'clientReads': 1,
                    'healthRounds': 3, 'maxReadsPerRound': 4, 'maxBytesPerRound': 900,
                    'minRoundGapMillis': 10000, 'rejectReason': 'ROUND_READ_LIMIT',
                    'address': 'private', 'stack': 'secret'}
            result = mock.Mock(returncode=1, stdout=json.dumps(data).encode(),
                               stderr=b'JFR evidence rejected; inspector line 1\n')
            config = {'idleEvidence': str(evidence), 'installation': 'installation'}
            with mock.patch.object(driver.verify, 'idle', side_effect=GateError('dependency or evidence command failed')), \
                 mock.patch.object(driver.verify, 'staged_bridge_port', return_value=contextlib.nullcontext(41500)), \
                 mock.patch.object(driver.subprocess, 'run', return_value=result):
                report = {}
                driver.diagnose_idle_failure(config, root, Path('/java'), report)
            self.assertEqual(report['fullSocketReads']['socketReads'], 11)
            self.assertEqual(report['fullSocketReads']['rejectReason'], 'ROUND_READ_LIMIT')
            self.assertNotIn('private', json.dumps(report))
            self.assertNotIn('secret', json.dumps(report))
            self.assertEqual(set(report['fullSocketReads']), set(driver.COUNTS) | {'rejectReason'})

    def test_unrecognized_schema_is_not_copied(self):
        counts = dict.fromkeys(driver.COUNTS, 0)
        counts['rejectReason'] = 'untrusted text'
        with self.assertRaises(ValueError):
            driver.safe_counts(counts)
        counts['rejectReason'] = 'NONE'
        counts['clientReads'] = True
        with self.assertRaises(ValueError):
            driver.safe_counts(counts)


if __name__ == '__main__':
    unittest.main()
