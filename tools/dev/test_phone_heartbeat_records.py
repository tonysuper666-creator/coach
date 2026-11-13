"""Only this synthetic run's typed counters may leave app-private storage."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'phone_heartbeat', Path(__file__).with_name('check-phone-reconnect.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
RUN_ID = 'a'*32


class HeartbeatRecordsTest(unittest.TestCase):
    def record(self, channel='main', **changes):
        record = dict(runId=RUN_ID, channel=channel, count=7, elapsedMs=14000)
        if channel == 'io':
            record.update(connection='online', active=True)
        record.update(changes)
        return json.dumps(record)

    def test_main_and_io_counters_survive_without_logcat(self):
        for channel in ('main', 'io'):
            raw = self.record(channel)
            self.assertEqual(json.loads(raw), runner.heartbeat_record(raw, RUN_ID, channel))

    def test_other_run_channel_and_unexpected_private_fields_are_rejected(self):
        for raw in (self.record(runId='b'*32), self.record(channel='io'),
                    self.record(token='private-token'), self.record(message='learning-content'),
                    '[]', '{partial'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                runner.heartbeat_record(raw, RUN_ID, 'main')

    def test_malformed_counters_and_status_cannot_be_evidence(self):
        for changes in (dict(count=True), dict(count=0), dict(elapsedMs=-1),
                        dict(count='7'), dict(elapsedMs=2**63), dict(active=1),
                        dict(connection='private-content')):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                runner.heartbeat_record(self.record('io', **changes), RUN_ID, 'io')


if __name__ == '__main__':
    unittest.main()
