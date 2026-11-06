#!/usr/bin/env python3
"""Independent read-only SQL verification of reproduce-formal.sh outputs; no live DB access."""
import argparse
import hashlib
import json
import pathlib
import sqlite3


def connect(path):
    db = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
    assert db.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
    assert db.execute('PRAGMA foreign_key_check').fetchall() == []
    return db


def logical(db):
    tables = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").fetchall()
    return {name: sorted(db.execute('SELECT * FROM "' + name.replace('"', '""') + '"').fetchall(), key=repr)
            for (name,) in tables}


def supply(db, course, clock):
    result = {}
    for (kp,) in db.execute('SELECT kp_id FROM outline_kp WHERE course_id=? ORDER BY kp_id', (course,)):
        pool = db.execute('''SELECT count(*) FROM questions q JOIN question_kp k USING(question_id)
            WHERE q.course_id=? AND k.kp_id=? AND q.format='SINGLE_CHOICE' AND q.key_state='VERIFIED'
            AND NOT EXISTS (SELECT 1 FROM offline_reservations r WHERE r.question_id=q.question_id AND r.expires_at>?)
            AND NOT EXISTS (SELECT 1 FROM events e WHERE e.course_id=q.course_id AND e.type='question_posed'
                AND json_extract(e.payload,'$.questionId')=q.question_id)''', (course, kp, clock)).fetchone()[0]
        constructed = db.execute('''SELECT count(*) FROM questions q JOIN constructed_details d USING(question_id)
            JOIN question_kp k USING(question_id) WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED' ''', (course, kp)).fetchone()[0]
        result[kp] = dict(poolAvailable=pool, constructedSupply=constructed)
    return result


def verify(root, proposal_path):
    report = json.loads((root/'backup/result.json').read_text())
    manifest = json.loads((root/'backup/backup.json').read_text())
    proposal = json.loads(proposal_path.read_text())
    course = manifest['course_id']
    before = connect(root/'backup/coach.sqlite')
    after = connect(root/'data/coach.sqlite')
    rolled = connect(root/'rollback-copy/coach.sqlite')
    preflight = connect(root/'backup/restored/coach.sqlite')
    assert logical(before) == logical(rolled), 'Rollback changed raw rows'
    assert logical(preflight) == logical(after), 'Actual write differs from restored-backup preflight'
    assert (root/'backup/coach.sqlite').read_bytes() == (root/'rollback-copy/coach.sqlite').read_bytes()
    native_events = before.execute('SELECT * FROM events ORDER BY seq').fetchall()
    assert after.execute('SELECT * FROM events ORDER BY seq LIMIT ?', (len(native_events),)).fetchall() == native_events
    for table in ('questions', 'question_kp', 'answer_keys', 'pool_details', 'constructed_details'):
        rows = set(before.execute(f'SELECT * FROM {table}').fetchall())
        assert rows <= set(after.execute(f'SELECT * FROM {table}').fetchall()), 'Lost native ' + table
    expected = {q['id']: set(q['kp_ids']) for q in proposal['questions']}
    migrated = after.execute("SELECT source_id,payload_json FROM migration_records WHERE kind='legacy_question'").fetchall()
    assert {q for q, _ in migrated} == set(expected)
    edges = associated = 0
    for q, payload in migrated:
        assert after.execute('SELECT key_state FROM questions WHERE question_id=?', (q,)).fetchone() == ('UNREVIEWED',)
        for table in ('answer_keys', 'pool_details', 'constructed_details'):
            assert after.execute(f'SELECT count(*) FROM {table} WHERE question_id=?', (q,)).fetchone()[0] == 0
        actual = {k for (k,) in after.execute('SELECT kp_id FROM question_kp WHERE question_id=?', (q,))}
        assert actual == expected[q] == set(json.loads(payload)['kp_ids'])
        for kp in actual:
            assert after.execute('SELECT retired FROM outline_kp WHERE course_id=? AND kp_id=?', (course, kp)).fetchone() == (0,)
        associated += bool(actual)
        edges += len(actual)
    same_supply = supply(before, course, report['supply_clock'])
    assert same_supply == supply(after, course, report['supply_clock']) == report['supply']
    for db in (before, after, rolled, preflight):
        for sha, size, path in db.execute('SELECT sha256,size,path FROM blobs'):
            file = root/'backup/blobs'/path
            assert file.stat().st_size == size and hashlib.sha256(file.read_bytes()).hexdigest() == sha
    ambiguous = []
    for source_id, payload, source in after.execute("SELECT source_id,payload_json,source_json FROM migration_records WHERE kind='legacy_attempt'"):
        payload, source = json.loads(payload), json.loads(source)
        assert payload['window_eligible'] is False
        if payload['answer_unresolved']:
            assert payload['answer'] is None and payload['unresolved_answer_text'] == source['user_answer']
            ambiguous.append(source_id)
    unknown = [q for q in expected if not expected[q]]
    projection = json.loads((root/'backup/after-state.json').read_text())['projection']
    review_bytes = json.dumps(projection['review'], ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    result = dict(native_events_preserved=len(native_events), questions=len(migrated), association_edges=edges,
                  associated_questions=associated, unassociated_questions=len(migrated)-associated,
                  all_unreviewed=True, grading_details=0, supply_kps_unchanged=len(same_supply),
                  raw_rollback_rows_equal=True, actual_equals_preflight=True, ambiguous_answers_preserved=len(ambiguous),
                  unassociated_positions=[int(q.rsplit(':', 1)[1]) + 1 for q in unknown],
                  passed_kps=sum(v['passed'] for v in projection['mastery'].values()),
                  active_kps=after.execute('SELECT count(*) FROM outline_kp WHERE course_id=? AND retired=0', (course,)).fetchone()[0],
                  review_kps=len(projection['review']), review_state_sha256=hashlib.sha256(review_bytes).hexdigest(),
                  rollback_database_sha256=hashlib.sha256((root/'rollback-copy/coach.sqlite').read_bytes()).hexdigest())
    for db in (before, after, rolled, preflight):
        db.close()
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=pathlib.Path, required=True)
    parser.add_argument('--proposal', type=pathlib.Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.root, args.proposal), indent=2))
