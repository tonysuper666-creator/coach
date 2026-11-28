"""A no-fault control must reject stalled, unrelated or unhealthy observations."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'phone_control', Path(__file__).with_name('check-phone-reconnect.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def observations(run_id='this-run', port=29194):
    lines = [f'{run_id} READY port={port} elapsedMs=1000']
    for stamp in range(2000, 12001, 2000):
        for kind in ('MAIN_HEARTBEAT', 'SYNC_STATUS connection=online revision=2 active=true'):
            lines.append(f'{run_id} {kind} port={port} elapsedMs={stamp}')
    return '\n'.join(lines)


class HealthyControlTest(unittest.TestCase):
    def test_both_channels_must_advance_for_ten_seconds(self):
        self.assertEqual(dict(mainSpanMs=10000, ioSpanMs=10000),
                         runner.healthy_control(observations(), 'this-run', 29194))
        for missing in ('MAIN_HEARTBEAT', 'SYNC_STATUS'):
            log = '\n'.join(line for line in observations().splitlines() if missing not in line)
            self.assertIsNone(runner.healthy_control(log, 'this-run', 29194))

    def test_four_second_stall_does_not_pass(self):
        log = '\n'.join(line for line in observations().splitlines()
                        if int(line.rsplit('=', 1)[1]) <= 4000)
        self.assertIsNone(runner.healthy_control(log, 'this-run', 29194))

    def test_wrong_run_or_port_cannot_supply_control(self):
        for log in (observations('previous-run'), observations(port=29195)):
            self.assertIsNone(runner.healthy_control(log, 'this-run', 29194))

    def test_offline_or_exited_loop_fails_control(self):
        for log in (observations().replace('connection=online', 'connection=offline', 1),
                    observations().replace('active=true', 'active=false', 1)):
            self.assertIsNone(runner.healthy_control(log, 'this-run', 29194))

    def test_long_gap_and_reordered_clock_are_not_continuous(self):
        lines = observations().splitlines()
        gap = '\n'.join(line for line in lines if 'elapsedMs=4000' not in line
                        and 'elapsedMs=6000' not in line)
        backwards = observations().replace('elapsedMs=6000', 'elapsedMs=3000')
        for log in (gap, backwards):
            self.assertIsNone(runner.healthy_control(log, 'this-run', 29194))


if __name__ == '__main__':
    unittest.main()
