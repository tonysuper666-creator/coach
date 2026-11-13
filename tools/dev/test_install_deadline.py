"""Local subprocess regression tests. Never import the phone/build module body."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest


SOURCE = Path(__file__).with_name('check-mistake-review.py')
PREFIX, MARKER, _ = SOURCE.read_text().partition('# Phone acceptance starts below.')
assert MARKER and 'tools/dev/heavy' not in PREFIX
NAMESPACE = {}
exec(compile(PREFIX, str(SOURCE), 'exec'), NAMESPACE)
run_install = NAMESPACE['_run_install']


class InstallDeadlineTest(unittest.TestCase):
    def test_shared_deadline_includes_startup(self):
        started = time.monotonic()
        output = run_install([sys.executable, '-c',
                              'import os; print(os.environ["COACH_INSTALL_DEADLINE"])'], 2)
        self.assertAlmostEqual(float(output), started + 2, delta=0.05)

    def test_nonzero_preserves_raw_streams(self):
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            run_install([sys.executable, '-c',
                         'import os; os.write(1,b"Success\\n"); os.write(2,b"\\xff"); exit(3)'], 2)
        self.assertEqual(raised.exception.returncode, 3)
        self.assertEqual(raised.exception.stdout, b'Success\n')
        self.assertEqual(raised.exception.stderr, b'\xff')

    def test_timeout_reaps_term_ignoring_helper_and_preserves_large_output(self):
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / 'pid'
            code = ('import os,signal,time; '
                    'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                    f'open({str(pid_file)!r},"w").write(str(os.getpid())); '
                    'os.write(1,b"Success\\n"); os.write(2,b"E"*200000); time.sleep(30)')
            previous = {number: signal.getsignal(number) for number in (signal.SIGTERM, signal.SIGINT)}
            started = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                run_install([sys.executable, '-c', code], 1)
            self.assertLess(time.monotonic() - started, 1)
            self.assertEqual(raised.exception.stdout, b'Success\n')
            self.assertEqual(raised.exception.stderr, b'E' * 200000)
            with self.assertRaises(ProcessLookupError):
                os.kill(int(pid_file.read_text()), 0)
            for number, handler in previous.items():
                self.assertEqual(signal.getsignal(number), handler)

    def test_default_entry_retains_timeout_files_and_180_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / 'install.sh'
            helper.write_text('#!/bin/sh\nprintf "Success\\n"\nprintf "raw-error" >&2\nexec sleep 30\n')
            helper.chmod(0o755)
            previous_path = os.environ.get('COACH_TESTPHONE_DIR')
            install = NAMESPACE['install_with_confirm']
            previous_defaults = install.__defaults__
            calls = []

            def scaled_runner(args, timeout):
                calls.append(timeout)
                return run_install(args, 1)

            os.environ['COACH_TESTPHONE_DIR'] = directory
            NAMESPACE['OUT'] = root
            install.__defaults__ = (scaled_runner,)
            try:
                with self.assertRaises(subprocess.TimeoutExpired):
                    NAMESPACE['install_with_confirm'](root / 'fixture.apk')
                self.assertEqual(calls, [180])
                self.assertEqual((root / 'install-timeout.stdout').read_bytes(), b'Success\n')
                self.assertEqual((root / 'install-timeout.stderr').read_bytes(), b'raw-error')
            finally:
                install.__defaults__ = previous_defaults
                NAMESPACE.pop('OUT', None)
                if previous_path is None:
                    os.environ.pop('COACH_TESTPHONE_DIR', None)
                else:
                    os.environ['COACH_TESTPHONE_DIR'] = previous_path

    def test_success_and_missing_executable(self):
        self.assertEqual(run_install([sys.executable, '-c', 'print("Success")'], 1), 'Success\n')
        with self.assertRaises(FileNotFoundError):
            run_install(['/ton1548-nonexistent-helper'], 1)


if __name__ == '__main__':
    unittest.main()
