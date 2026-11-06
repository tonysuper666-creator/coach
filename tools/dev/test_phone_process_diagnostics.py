"""Scheduling evidence must exclude unrelated processes and arbitrary dump fields."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'phone_reconnect', Path(__file__).with_name('check-phone-reconnect.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ProcessDiagnosticsTest(unittest.TestCase):
    def test_only_target_process_scheduling_flags_survive(self):
        dump = '''ACTIVITY MANAGER RUNNING PROCESSES
  *APP* UID 123 ProcessRecord{abc 45:com.coach.android.ton1094/u0a123}
    cached=true empty=false curProcState=19 setProcState=19
    frozen=true pendingFreeze=false
    token=private-token learning=private-content
  *APP* UID 124 ProcessRecord{def 46:unrelated.app/u0a124}
    frozen=false curProcState=2
'''
        self.assertEqual(dict(cached='true', empty='false', curProcState='19',
                              setProcState='19', frozen='true', pendingFreeze='false'),
                         runner.process_flags(dump))

    def test_missing_record_or_unsupported_fields_are_unknown(self):
        self.assertEqual({}, runner.process_flags('Permission denied'))
        self.assertEqual({}, runner.process_flags(
            '*APP* UID 123 ProcessRecord{abc 45:com.coach.android.ton1094.other/u0a123}\n frozen=true'))


if __name__ == '__main__':
    unittest.main()
