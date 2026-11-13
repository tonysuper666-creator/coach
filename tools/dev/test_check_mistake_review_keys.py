"""Offline gate for check-mistake-review.py: pick a KP, then map SINGLE_CHOICE keys.

Synthetic rows only. The phone script is not imported: its module body builds
and talks to a device. These tests execute the real prepare_choice_keys source,
so the original pick and the key query run together.
"""
import json
from pathlib import Path
import sqlite3
import unittest

SOURCE = Path(__file__).with_name('check-mistake-review.py')
PREFIX, SEPARATOR, _REST = SOURCE.read_text(encoding='utf-8').partition(
    '# Phone acceptance starts below.')
if not SEPARATOR or 'def prepare_choice_keys(' not in PREFIX:
    raise RuntimeError('prepare_choice_keys must stay above the phone-acceptance marker')
_NAMESPACE = {}
exec(compile(PREFIX, str(SOURCE), 'exec'), _NAMESPACE)
prepare_choice_keys = _NAMESPACE['prepare_choice_keys']

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


if __name__ == '__main__':
    unittest.main()
