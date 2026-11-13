import unittest
from unittest.mock import patch

from host_clock import offset_bounds,sample


class HostClockTest(unittest.TestCase):
    def test_epoch_read_is_bracketed_with_conservative_offset_bounds(self):
        with patch('host_clock.time.monotonic_ns',side_effect=[100,108]),patch('host_clock.time.time_ns',return_value=1000):
            witness=sample()
        self.assertEqual(dict(hostMonotonicBeforeNs=100,hostEpochNs=1000,hostMonotonicAfterNs=108),witness)
        self.assertEqual(dict(minimumNs=892,maximumNs=900,widthNs=8),offset_bounds(witness))

    def test_invalid_brackets_never_supply_clock_bounds(self):
        for before,epoch,after in ((109,1000,108),(True,1000,108),(100,None,108),(100,1000,'108')):
            with self.assertRaises(ValueError):
                offset_bounds(dict(hostMonotonicBeforeNs=before,hostEpochNs=epoch,hostMonotonicAfterNs=after))
