"""Synthetic install timeout gate for check-mistake-review.py.

The phone script is not imported: its module body builds and talks to a device.
These tests execute the real helpers above the phone-acceptance marker.
Children are local processes. The product call still requests 180 seconds;
the stand-in waits 2 seconds so TimeoutExpired can be observed without a device.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest

SOURCE = Path(__file__).with_name('check-mistake-review.py')
PREFIX, SEPARATOR, _REST = SOURCE.read_text(encoding='utf-8').partition(
    '# Phone acceptance starts below.')
if (not SEPARATOR or 'def install_with_confirm(' not in PREFIX
        or 'tools/dev/heavy' in PREFIX):
    raise RuntimeError('install_with_confirm must stay above the phone-acceptance marker')
_NAMESPACE = {}
exec(compile(PREFIX, str(SOURCE), 'exec'), _NAMESPACE)
install_with_confirm = _NAMESPACE['install_with_confirm']

CHILD_BUDGET = 2
CHILD = textwrap.dedent(
    """\
    import sys, time
    mode = sys.argv[1]
    if mode == 'silent':
        time.sleep(30)
    elif mode == 'success-then-timeout':
        sys.stdout.write('Success\\n')
        sys.stdout.flush()
        sys.stderr.write('stderr-marker\\n')
        sys.stderr.flush()
        time.sleep(30)
    elif mode == 'large-stderr':
        sys.stderr.write('E' * 200000)
        sys.stderr.flush()
        time.sleep(30)
    elif mode == 'immediate-success':
        sys.stdout.write('Success\\n')
        sys.stdout.flush()
    elif mode == 'nonzero':
        sys.stdout.write('Success\\n')
        sys.stdout.flush()
        sys.stderr.write('fail-marker\\n')
        sys.stderr.flush()
        raise SystemExit(3)
    elif mode == 'no-success':
        sys.stdout.write('installed\\n')
        sys.stdout.flush()
    else:
        raise SystemExit('unknown mode')
    """
)
RECEIPTS = []


def _sha256(data):
    return hashlib.sha256(data).hexdigest()


def _receipt(scenario, **fields):
    row = {'scenario': scenario}
    row.update(fields)
    RECEIPTS.append(row)


class InstallTimeoutSaveTest(unittest.TestCase):
    def setUp(self):
        self._previous_out = os.environ.get('COACH_REVIEW_OUT')
        os.environ.pop('COACH_REVIEW_OUT', None)
        _NAMESPACE.pop('OUT', None)
        self._root = tempfile.TemporaryDirectory()
        self.directory = Path(self._root.name)
        self.output = self.directory / 'out'
        self.output.mkdir()
        self.child = self.directory / 'child.py'
        self.child.write_text(CHILD, encoding='ascii', newline='\n')
        self.apk = self.directory / 'card.apk'
        self.apk.write_bytes(b'synthetic-apk')
        _NAMESPACE['OUT'] = self.output
        self.assertIn(180, install_with_confirm.__code__.co_consts)

    def tearDown(self):
        _NAMESPACE.pop('OUT', None)
        if self._previous_out is None:
            os.environ.pop('COACH_REVIEW_OUT', None)
        else:
            os.environ['COACH_REVIEW_OUT'] = self._previous_out
        self._root.cleanup()

    def _run(self, mode, recorded):
        def run_command(args, timeout=90, **kwargs):
            recorded.append((list(map(str, args)), timeout))
            if timeout != 180:
                raise AssertionError(f'product budget was {timeout}, expected 180')
            command = [sys.executable, str(self.child), mode]
            try:
                return subprocess.check_output(
                    command, timeout=CHILD_BUDGET, text=True, stderr=subprocess.PIPE)
            except subprocess.TimeoutExpired as exc:
                recorded.append(exc)
                raise
        return run_command

    def _files(self):
        stdout = self.output / 'install-timeout.stdout'
        stderr = self.output / 'install-timeout.stderr'
        return stdout, stderr

    def _assert_saved(self, exc):
        stdout, stderr = self._files()
        self.assertTrue(stdout.is_file())
        self.assertTrue(stderr.is_file())
        stdout_bytes = stdout.read_bytes()
        stderr_bytes = stderr.read_bytes()
        self.assertEqual(stdout_bytes, _NAMESPACE['_timeout_bytes'](exc.stdout))
        self.assertEqual(stderr_bytes, _NAMESPACE['_timeout_bytes'](exc.stderr))
        self.assertFalse((self.output / 'install-timeout-save-error.txt').exists())
        return stdout_bytes, stderr_bytes

    def _assert_absent(self):
        stdout, stderr = self._files()
        self.assertFalse(stdout.exists())
        self.assertFalse(stderr.exists())
        self.assertFalse((self.output / 'install-timeout-save-error.txt').exists())

    def test_success_then_timeout_is_saved_and_reraised(self):
        recorded = []
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            install_with_confirm(self.apk, self._run('success-then-timeout', recorded))
        elapsed = time.monotonic() - started
        exc = recorded[-1]
        self.assertIs(caught.exception, exc)
        self.assertIsInstance(exc, subprocess.TimeoutExpired)
        self.assertGreaterEqual(elapsed, 1)
        self.assertLess(elapsed, 20)
        self.assertEqual(recorded[0][1], 180)
        stdout_bytes, stderr_bytes = self._assert_saved(exc)
        self.assertEqual(stdout_bytes, b'Success\n')
        self.assertEqual(stderr_bytes, b'stderr-marker\n')
        self.assertNotIn('Success', str(exc))
        _receipt(
            'success-then-timeout', result='TimeoutExpired', same_exception=True,
            call_timeout=recorded[0][1], child_budget=CHILD_BUDGET, elapsed_s=round(elapsed, 2),
            stdout_sha256=_sha256(stdout_bytes), stdout_bytes=len(stdout_bytes),
            stderr_sha256=_sha256(stderr_bytes), stderr_bytes=len(stderr_bytes))

    def test_silent_timeout_writes_empty_files(self):
        recorded = []
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            install_with_confirm(self.apk, self._run('silent', recorded))
        elapsed = time.monotonic() - started
        exc = recorded[-1]
        self.assertIs(caught.exception, exc)
        self.assertLess(elapsed, 20)
        stdout_bytes, stderr_bytes = self._assert_saved(exc)
        self.assertEqual(stdout_bytes, b'')
        self.assertEqual(stderr_bytes, b'')
        _receipt(
            'silent-timeout', result='TimeoutExpired', same_exception=True,
            call_timeout=180, child_budget=CHILD_BUDGET, elapsed_s=round(elapsed, 2),
            stdout_sha256=_sha256(stdout_bytes), stdout_bytes=0,
            stderr_sha256=_sha256(stderr_bytes), stderr_bytes=0)

    def test_large_stderr_is_saved_whole(self):
        recorded = []
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            install_with_confirm(self.apk, self._run('large-stderr', recorded))
        elapsed = time.monotonic() - started
        exc = recorded[-1]
        self.assertIs(caught.exception, exc)
        self.assertLess(elapsed, 20)
        stdout_bytes, stderr_bytes = self._assert_saved(exc)
        self.assertEqual(stderr_bytes, b'E' * 200000)
        self.assertNotIn(b'Success', stdout_bytes)
        _receipt(
            'large-stderr', result='TimeoutExpired', same_exception=True,
            call_timeout=180, child_budget=CHILD_BUDGET, elapsed_s=round(elapsed, 2),
            stdout_sha256=_sha256(stdout_bytes), stdout_bytes=len(stdout_bytes),
            stderr_sha256=_sha256(stderr_bytes), stderr_bytes=len(stderr_bytes))

    def test_immediate_success_checks_success_and_writes_nothing(self):
        recorded = []
        output = install_with_confirm(self.apk, self._run('immediate-success', recorded))
        self.assertTrue(any(line.startswith('Success') for line in output.splitlines()))
        self.assertEqual(recorded[0][1], 180)
        self.assertEqual(len(recorded), 1)
        self._assert_absent()
        _receipt('immediate-success', result='returned', call_timeout=180, timeout_files=0)

    def test_nonzero_exit_keeps_returncode_and_writes_nothing(self):
        recorded = []
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            install_with_confirm(self.apk, self._run('nonzero', recorded))
        self.assertEqual(caught.exception.returncode, 3)
        self.assertEqual(recorded[0][1], 180)
        self._assert_absent()
        _receipt('nonzero-failure', result='CalledProcessError', returncode=3, call_timeout=180, timeout_files=0)

    def test_zero_exit_without_success_still_fails(self):
        recorded = []
        with self.assertRaises(RuntimeError) as caught:
            install_with_confirm(self.apk, self._run('no-success', recorded))
        self.assertIn('did not report Success', str(caught.exception))
        self._assert_absent()
        _receipt('no-success-line', result='RuntimeError', call_timeout=180, timeout_files=0)

    def test_none_is_empty_and_text_is_utf8(self):
        def run_command(args, timeout=90, **kwargs):
            self.assertEqual(timeout, 180)
            exc = subprocess.TimeoutExpired(list(map(str, args)), timeout)
            exc.stdout = None
            exc.stderr = '标记\n'
            raise exc

        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            install_with_confirm(self.apk, run_command)
        self.assertIsNone(caught.exception.stdout)
        stdout_bytes, stderr_bytes = self._assert_saved(caught.exception)
        self.assertEqual(stdout_bytes, b'')
        self.assertEqual(stderr_bytes, '标记\n'.encode('utf-8'))
        _receipt(
            'none-and-text', result='TimeoutExpired',
            stdout_sha256=_sha256(stdout_bytes), stdout_bytes=0,
            stderr_sha256=_sha256(stderr_bytes), stderr_bytes=len(stderr_bytes))

    def test_raw_bytes_are_stored_unchanged(self):
        payload = b'Success\n\xff'

        def run_command(args, timeout=90, **kwargs):
            self.assertEqual(timeout, 180)
            exc = subprocess.TimeoutExpired(list(map(str, args)), timeout)
            exc.stdout = payload
            exc.stderr = None
            raise exc

        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            install_with_confirm(self.apk, run_command)
        self.assertIs(caught.exception.stdout, payload)
        stdout_bytes, stderr_bytes = self._assert_saved(caught.exception)
        self.assertEqual(stdout_bytes, payload)
        self.assertEqual(stderr_bytes, b'')
        _receipt(
            'raw-bytes', result='TimeoutExpired', same_exception=True,
            stdout_sha256=_sha256(stdout_bytes), stdout_bytes=len(stdout_bytes),
            stderr_sha256=_sha256(stderr_bytes), stderr_bytes=0)

    def test_save_failure_keeps_the_original_exception(self):
        blocked = self.directory / 'blocked-out'
        blocked.write_bytes(b'not-a-directory')
        _NAMESPACE['OUT'] = blocked
        recorded = []
        hint = io.StringIO()
        previous = sys.stderr
        sys.stderr = hint
        try:
            with self.assertRaises(subprocess.TimeoutExpired) as caught:
                install_with_confirm(self.apk, self._run('silent', recorded))
        finally:
            sys.stderr = previous
        self.assertIs(caught.exception, recorded[-1])
        self.assertIn('install timeout output was not saved', hint.getvalue())
        self.assertNotIn(str(self.directory), hint.getvalue())
        self.assertFalse(list(self.directory.glob('install-timeout*')))
        _receipt('save-failure', result='TimeoutExpired', same_exception=True, hint='fixed-sentence')

    def test_partial_save_keeps_written_file_and_original_exception(self):
        (self.output / 'install-timeout.stderr').mkdir()

        def run_command(args, timeout=90, **kwargs):
            self.assertEqual(timeout, 180)
            exc = subprocess.TimeoutExpired(list(map(str, args)), timeout)
            exc.stdout = b'partial-stdout'
            exc.stderr = b'partial-stderr'
            raise exc

        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            install_with_confirm(self.apk, run_command)
        self.assertEqual((self.output / 'install-timeout.stdout').read_bytes(), b'partial-stdout')
        self.assertTrue((self.output / 'install-timeout.stderr').is_dir())
        hint = (self.output / 'install-timeout-save-error.txt').read_text(encoding='ascii')
        self.assertEqual(hint, 'install timeout output was not saved\n')
        self.assertIsInstance(caught.exception, subprocess.TimeoutExpired)
        _receipt('partial-save', result='TimeoutExpired', hint_file=True, stdout_bytes=14)

    def test_output_env_is_used_only_when_out_is_unset(self):
        _NAMESPACE.pop('OUT', None)
        env_out = self.directory / 'env-out'
        env_out.mkdir()
        os.environ['COACH_REVIEW_OUT'] = str(env_out)

        def run_command(args, timeout=90, **kwargs):
            exc = subprocess.TimeoutExpired(['install.sh', 'card.apk'], timeout)
            exc.stdout = b''
            exc.stderr = b''
            raise exc

        with self.assertRaises(subprocess.TimeoutExpired):
            install_with_confirm(self.apk, run_command)
        self.assertEqual((env_out / 'install-timeout.stdout').read_bytes(), b'')
        self.assertEqual((env_out / 'install-timeout.stderr').read_bytes(), b'')
        self.assertFalse((self.output / 'install-timeout.stdout').exists())


def tearDownModule():
    print('SYNTHETIC_RECEIPTS')
    for row in RECEIPTS:
        print(json.dumps(row, sort_keys=True, ensure_ascii=True))


if __name__ == '__main__':
    unittest.main()
