"""Offline gate for check-mistake-review.py: pick a KP, then map SINGLE_CHOICE keys.

Synthetic rows only. The phone script is not imported: its module body builds
and talks to a device. These tests execute the real prepare_choice_keys source,
so the original pick and the key query run together.
"""
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).with_name('check-mistake-review.py')
PREFIX, SEPARATOR, _REST = SOURCE.read_text(encoding='utf-8').partition(
    '# Phone acceptance starts below.')
if not SEPARATOR or 'def prepare_choice_keys(' not in PREFIX or 'def install_with_confirm(' not in PREFIX:
    raise RuntimeError('prepare_choice_keys and install_with_confirm must stay above the phone-acceptance marker')
_NAMESPACE = {}
exec(compile(PREFIX, str(SOURCE), 'exec'), _NAMESPACE)
prepare_choice_keys = _NAMESPACE['prepare_choice_keys']
run = _NAMESPACE['run']
testphone_install_sh = _NAMESPACE['testphone_install_sh']
install_with_confirm = _NAMESPACE['install_with_confirm']

COURSE = 'synthetic-course'
SCHEMA = """
CREATE TABLE questions(question_id TEXT PRIMARY KEY, course_id TEXT, format TEXT, key_state TEXT);
CREATE TABLE question_kp(question_id TEXT, kp_id TEXT);
CREATE TABLE pool_details(question_id TEXT, generator TEXT, reviewer TEXT);
CREATE TABLE answer_keys(question_id TEXT, key_json TEXT, reviewed_by TEXT);
CREATE TABLE outline_kp(kp_id TEXT, course_id TEXT, retired INTEGER);
CREATE TABLE events(course_id TEXT, type TEXT, payload TEXT);
CREATE TABLE offline_reservations(question_id TEXT, expires_at INTEGER);
"""
CALC_KEY = json.dumps({
    'numeric_final': '1', 'reference_answer': '1', 'rubric': 'synthetic', 'stem': 'synthetic'})


def connect():
    db = sqlite3.connect(':memory:')
    db.executescript(SCHEMA)
    return db


def add_kp(db, kp_id, retired=0):
    db.execute('INSERT INTO outline_kp VALUES(?,?,?)', (kp_id, COURSE, retired))


def add_question(db, kp_id, question_id, fmt='SINGLE_CHOICE', key_json=None, posed=False):
    if key_json is None:
        key_json = json.dumps({'correctOptionId': f'opt-{question_id}'})
    db.execute('INSERT INTO questions VALUES(?,?,?,?)', (question_id, COURSE, fmt, 'VERIFIED'))
    db.execute('INSERT INTO question_kp VALUES(?,?)', (question_id, kp_id))
    db.execute('INSERT INTO pool_details VALUES(?,?,?)', (question_id, 'claude-opus', 'gpt-6'))
    db.execute('INSERT INTO answer_keys VALUES(?,?,?)', (question_id, key_json, 'gpt-6'))
    if posed:
        db.execute('INSERT INTO events VALUES(?,?,?)',
                   (COURSE, 'question_posed', json.dumps({'questionId': question_id})))


def add_choices(db, kp_id, count, prefix):
    for i in range(count):
        add_question(db, kp_id, f'{prefix}-{i}')


class ChoiceKeyGateTest(unittest.TestCase):
    def test_mixed_kp_maps_only_its_single_choice_keys(self):
        db = connect()
        add_kp(db, 'kp-m')
        add_kp(db, 'kp-z')
        add_choices(db, 'kp-m', 6, 'sc-m')
        add_question(db, 'kp-m', 'calc-m-0', fmt='CALCULATION', key_json=CALC_KEY)
        add_question(db, 'kp-m', 'calc-m-1', fmt='CALCULATION', key_json=CALC_KEY)
        add_question(db, 'kp-m', 'calc-m-marked', fmt='CALCULATION',
                     key_json=json.dumps({'correctOptionId': 'not-a-choice', 'numeric_final': '1'}))
        add_choices(db, 'kp-z', 6, 'sc-z')

        kp, keys = prepare_choice_keys(db, COURSE)

        self.assertEqual(kp, 'kp-m')
        self.assertEqual(set(keys), {f'sc-m-{i}' for i in range(6)})
        self.assertEqual(keys['sc-m-0'], 'opt-sc-m-0')
        self.assertNotIn('calc-m-0', keys)
        self.assertNotIn('calc-m-1', keys)
        self.assertNotIn('calc-m-marked', keys)
        self.assertNotIn('not-a-choice', keys.values())
        self.assertFalse(any(question_id.startswith('sc-z-') for question_id in keys))

    def test_pure_single_choice_maps_every_selected_question(self):
        db = connect()
        add_kp(db, 'kp-m')
        add_choices(db, 'kp-m', 6, 'sc-m')

        kp, keys = prepare_choice_keys(db, COURSE)

        self.assertEqual(kp, 'kp-m')
        self.assertEqual(keys, {f'sc-m-{i}': f'opt-sc-m-{i}' for i in range(6)})

    def test_unqualified_kp_exits_without_building_keys(self):
        cases = {
            'five choices': lambda db: add_choices(db, 'kp-m', 5, 'sc-m'),
            'one of six already posed': lambda db: (
                add_choices(db, 'kp-m', 5, 'sc-m'),
                add_question(db, 'kp-m', 'sc-m-posed', posed=True)),
            'calculation rows do not qualify': lambda db: [
                add_question(db, 'kp-m', f'calc-m-{i}', fmt='CALCULATION', key_json=CALC_KEY)
                for i in range(6)],
        }
        for name, fill in cases.items():
            with self.subTest(name=name):
                db = connect()
                add_kp(db, 'kp-m')
                fill(db)
                with self.assertRaises(SystemExit) as caught:
                    prepare_choice_keys(db, COURSE)
                self.assertIn('No active KP in synthetic-course has six unseen reviewed questions',
                              str(caught.exception))

    def test_damaged_single_choice_key_exits_by_name(self):
        damaged = {
            'missing field': json.dumps({'reference_answer': 'synthetic'}),
            'not json': '{',
            'blank option': json.dumps({'correctOptionId': '  '}),
        }
        for name, key_json in damaged.items():
            with self.subTest(name=name):
                db = connect()
                add_kp(db, 'kp-m')
                add_choices(db, 'kp-m', 5, 'sc-m')
                add_question(db, 'kp-m', 'sc-m-bad', key_json=key_json)
                add_question(db, 'kp-m', 'calc-m-0', fmt='CALCULATION', key_json=CALC_KEY)
                with self.assertRaises(SystemExit) as caught:
                    prepare_choice_keys(db, COURSE)
                message = str(caught.exception)
                self.assertIn('sc-m-bad', message)
                self.assertIn('missing or damaged', message)
                self.assertNotIn('calc-m-0', message)


class InstallHelperGateTest(unittest.TestCase):
    """The install call must execute install.sh and propagate its result.

    Stubs are real child processes. The phone-acceptance body below the marker
    is not executed.
    """

    def setUp(self):
        self._previous_dir = os.environ.get('COACH_TESTPHONE_DIR')

    def tearDown(self):
        if self._previous_dir is None:
            os.environ.pop('COACH_TESTPHONE_DIR', None)
        else:
            os.environ['COACH_TESTPHONE_DIR'] = self._previous_dir

    def test_script_path_matches_formfactor_directory(self):
        os.environ['COACH_TESTPHONE_DIR'] = r'C:\synthetic\testphone'
        self.assertEqual(testphone_install_sh(), Path(r'C:\synthetic\testphone') / 'install.sh')
        os.environ.pop('COACH_TESTPHONE_DIR')
        self.assertEqual(testphone_install_sh(), Path.home() / 'Coach' / 'testphone' / 'install.sh')

    def _recording_run(self, recorded):
        def recording_run(args, timeout=90, **kwargs):
            recorded.append((list(map(str, args)), timeout))
            return run(args, timeout=timeout, **kwargs)
        return recording_run

    def _with_stub(self, message, code):
        """Real child process. Windows keeps the cmd stub; POSIX runs a shell stub.

        The product call is install.sh <apk>. These stubs only stand in for that
        process so the success line, the exit code, and the 180s budget can run
        on the machine that re-runs the test.
        """
        recorded = []
        original = _NAMESPACE['testphone_install_sh']
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            if os.name == 'nt':
                stub = directory / 'install-stub.cmd'
                stub.write_text(
                    '@echo off\n'
                    '>"%~dp0saw.txt" echo %~1\n'
                    f'echo {message}\n'
                    f'exit /b {int(code)}\n',
                    encoding='ascii', newline='\r\n')
            else:
                stub = directory / 'install-stub.sh'
                stub.write_text(
                    '#!/bin/sh\n'
                    'printf "%s\\n" "$1" > "$(dirname "$0")/saw.txt"\n'
                    f'printf "%s\\n" "{message}"\n'
                    f'exit {int(code)}\n',
                    encoding='ascii', newline='\n')
                stub.chmod(0o755)
            apk = directory / 'card.apk'
            apk.write_bytes(b'not-a-real-apk')
            _NAMESPACE['testphone_install_sh'] = lambda: stub
            try:
                outcome = None
                error = None
                try:
                    outcome = install_with_confirm(apk, self._recording_run(recorded))
                except (subprocess.CalledProcessError, RuntimeError) as caught:
                    error = caught
                saw = (directory / 'saw.txt').read_text(encoding='ascii')
                return recorded, outcome, error, saw.strip(), str(apk)
            finally:
                _NAMESPACE['testphone_install_sh'] = original

    def test_success_passes_only_the_apk_and_requires_success_line(self):
        recorded, outcome, error, saw, apk = self._with_stub('Success', 0)
        self.assertIsNone(error)
        self.assertTrue(any(line.startswith('Success') for line in outcome.splitlines()))
        self.assertEqual(recorded, [([recorded[0][0][0], apk], 180)])
        self.assertEqual(Path(recorded[0][0][0]).name, 'install-stub.cmd' if os.name == 'nt' else 'install-stub.sh')
        self.assertEqual(saw, apk)
        self.assertNotIn('adb', recorded[0][0])

    def test_nonzero_exit_propagates_even_if_output_says_success(self):
        recorded, outcome, error, saw, apk = self._with_stub('Success', 3)
        self.assertIsNone(outcome)
        self.assertIsInstance(error, subprocess.CalledProcessError)
        self.assertEqual(error.returncode, 3)
        self.assertEqual(recorded[0][1], 180)
        self.assertEqual(saw, apk)

    def test_zero_exit_without_success_line_fails(self):
        _recorded, outcome, error, saw, apk = self._with_stub('installed', 0)
        self.assertIsNone(outcome)
        self.assertIsInstance(error, RuntimeError)
        self.assertIn('did not report Success', str(error))
        self.assertEqual(saw, apk)


if __name__ == '__main__':
    unittest.main()
