#!/usr/bin/env python3
"""Exercise the runner with fake simctl/xcodebuild; no simulator is touched."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RUNNER = Path(__file__).with_name('run-ui-tests.sh')
MOCK = '''#!/usr/bin/env python3
import json, os, pathlib, sys, time
p = pathlib.Path(os.environ['FIXTURE_DIR'])
args = sys.argv[1:]
with (p/'calls').open('a') as f: f.write(pathlib.Path(sys.argv[0]).name + ' ' + ' '.join(args) + '\\n')
scenario = os.environ['SCENARIO']
if pathlib.Path(sys.argv[0]).name == 'xcodebuild':
    if scenario == 'test_timeout': time.sleep(30)
    sys.exit(65 if scenario == 'test_failure' else 0)
if args[:4] == ['simctl', 'list', 'devices', 'available']:
    print(json.dumps({'devices': {'fixture': [{'udid': 'fixture-device', 'state': (p/'state').read_text()}]}}))
elif args[:2] == ['simctl', 'boot']:
    (p/'state').write_text('Booting' if scenario == 'duo_not_booted' else 'Booted')
elif args[:2] == ['simctl', 'io']:
    if scenario == 'duo_screenshot_fail': sys.exit(42)
    pathlib.Path(args[-1]).write_bytes(b'fixture screenshot')
elif args[:2] == ['simctl', 'bootstatus']:
    if scenario == 'boot_timeout': time.sleep(30)
    if scenario == 'boot_exit': sys.exit(47)
    if scenario == 'migration': print('Status=3, isTerminal=YES, Data Migration Failed')
    elif scenario == 'terminal': print('Status=3, isTerminal=YES')
    else: print('Status=4294967295, isTerminal=YES, Finished')
elif args[:2] == ['simctl', 'shutdown']:
    (p/'state').write_text('Shutdown')
'''

class RunnerTests(unittest.TestCase):
    def exercise(self, scenario, expected, initial='Shutdown', duo=False):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            for name in ('xcrun', 'xcodebuild'):
                binary = base/name
                binary.write_text(MOCK)
                binary.chmod(0o755)
            (base/'state').write_text(initial)
            env = dict(os.environ, PATH=str(base)+os.pathsep+os.environ['PATH'],
                       FIXTURE_DIR=temp, SCENARIO=scenario, READER_UI_LOG_DIR=str(base/'logs'),
                       READER_UI_TIMEOUT_SECONDS='1' if scenario.endswith('timeout') else '10')
            if duo: env['READER_UI_DUO_READY'] = '1'
            if duo: env['READER_UI_DUO_BOOT_WAIT_SECONDS'] = '0.3'
            result = subprocess.run(['bash', str(RUNNER), 'fixture-device'], env=env, capture_output=True, text=True, timeout=18)
            self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
            calls = (base/'calls').read_text()
            self.assertEqual('simctl shutdown' in calls, initial == 'Shutdown', calls)
            self.assertTrue(list((base/'logs').glob('*/run.log')))
            return calls

    def test_success_and_finished_sentinel(self):
        self.assertIn('xcodebuild ', self.exercise('success', 0))
    def test_preexisting_booted_device_is_left_running(self):
        self.exercise('success', 0, initial='Booted')
    def test_migration_failure_with_zero_boot_exit(self):
        self.assertNotIn('xcodebuild ', self.exercise('migration', 1))
    def test_abnormal_terminal_status(self):
        self.assertNotIn('xcodebuild ', self.exercise('terminal', 1))
    def test_boot_failure_code(self):
        self.assertNotIn('xcodebuild ', self.exercise('boot_exit', 47))
    def test_xcodebuild_failure_code(self):
        self.exercise('test_failure', 65)
    def test_test_timeout(self):
        self.exercise('test_timeout', 124)
    def test_boot_timeout(self):
        self.assertNotIn('xcodebuild ', self.exercise('boot_timeout', 124))
    def test_duo_ready_uses_primary_screenshot_not_bootstatus(self):
        calls = self.exercise('success', 0, duo=True)
        self.assertIn('screenshot --display=1', calls)
        self.assertNotIn('bootstatus', calls)
        self.assertIn('xcodebuild ', calls)
    def test_duo_requires_booted_state(self):
        self.assertNotIn('xcodebuild ', self.exercise('duo_not_booted', 1, duo=True))
    def test_duo_screenshot_failure_propagates(self):
        self.assertNotIn('xcodebuild ', self.exercise('duo_screenshot_fail', 42, duo=True))

if __name__ == '__main__':
    unittest.main()
