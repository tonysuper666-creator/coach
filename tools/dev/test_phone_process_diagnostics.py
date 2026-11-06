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

    def test_kernel_state_excludes_identifiers_and_arbitrary_fields(self):
        self.assertEqual('T', runner.kernel_state(
            'Name:\tprivate-name\nState:\tT (stopped)\nUid:\t123\nToken:\tprivate-token\n'))
        self.assertEqual('S', runner.kernel_state('State:\tS (sleeping)\n'))

    def test_unknown_or_ambiguous_kernel_state_is_not_evidence(self):
        for status in ('Permission denied', 'State: private-content',
                       'State: S\nState: T\n', 'State: Token'):
            with self.subTest(status=status):
                self.assertIsNone(runner.kernel_state(status))

    def test_only_current_wakefulness_survives(self):
        self.assertEqual('Awake', runner.wakefulness(
            'private-content\n mWakefulness=Awake\n mLastWakefulness=Asleep\n'))
        for dump in ('Permission denied', 'mWakefulness=private-content',
                     'mWakefulness=Awake\nmWakefulness=Asleep',
                     'mWakefulness=Awake private-token'):
            with self.subTest(dump=dump):
                self.assertIsNone(runner.wakefulness(dump))


if __name__ == '__main__':
    unittest.main()
