"""Entirely invented data for tests and a shareable screenshot."""

import json
import sqlite3
from pathlib import Path
from contextlib import closing


def create_database(path):
    with closing(sqlite3.connect(path)) as db, db:
        db.executescript('''
        CREATE TABLE events(seq INTEGER PRIMARY KEY,course_id TEXT,type TEXT,payload TEXT);
        CREATE TABLE outline_kp(kp_id TEXT,course_id TEXT,ord INTEGER,title TEXT,anchors TEXT,retired INTEGER);
        CREATE TABLE questions(question_id TEXT,course_id TEXT,stem_md TEXT,options TEXT,key_state TEXT);
        CREATE TABLE answer_keys(question_id TEXT,key_json TEXT);
        CREATE TABLE pool_details(question_id TEXT,explanation TEXT,generator TEXT,reviewer TEXT);
        CREATE TABLE constructed_details(question_id TEXT,reference_answer TEXT);
        CREATE TABLE projection_cache(name TEXT,course_id TEXT,version INTEGER,last_seq INTEGER,blob TEXT);
        ''')
        def emit(typ, **payload):
            db.execute('INSERT INTO events(course_id,type,payload) VALUES(?,?,?)', ('synthetic', typ, json.dumps(payload)))
        points = [{'id': f'kp-{w}', 'title': f'【W{w}·示例】合成课程要点 {w}：$x^2 + y^2 = z^2$', 'anchors': [], 'week': w} for w in range(1, 12)]
        points[0]['title'] = '【W1·示例】价格变化与数量反应：$e = \\frac{\\Delta Q}{\\Delta P}$'
        points.append({'id': 'shared', 'title': '【W1·示例】【W2·示例】跨周知识点', 'anchors': []})
        emit('outline_revised', knowledgePoints=points, ops=[])
        for index, p in enumerate(points):
            db.execute('INSERT INTO outline_kp VALUES(?,?,?,?,?,0)', (p['id'], 'synthetic', index, p['title'], '[]'))
        options = [{'optionId': 'low', 'text': '数量上升'}, {'optionId': 'high', 'text': '数量下降'}]
        def pose(aid, kp, score=0, question=None):
            qid = question or aid + '-question'
            if not db.execute('SELECT 1 FROM questions WHERE question_id=?', (qid,)).fetchone():
                db.execute('INSERT INTO questions VALUES(?,?,?,?,?)', (qid, 'synthetic', '合成题：价格上升且需求曲线向下，数量如何变化？', json.dumps(options), 'VERIFIED'))
                db.execute('INSERT INTO answer_keys VALUES(?,?)', (qid, json.dumps({'correctOptionId': 'high'})))
            emit('question_posed', attemptId=aid, questionId=qid, kpIds=[kp], stem='合成题：价格上升且需求曲线向下，数量如何变化？', options=options, letterMap={'high': 'A', 'low': 'B'}, format='SINGLE_CHOICE', keyState='VERIFIED')
            emit('answer_submitted', attemptId=aid, answer='low' if score == 0 else 'high')
            emit('attempt_graded', attemptId=aid, score=score, max=1, status='EXACT', feedback='把价格变化与数量变化的方向混淆了。$\\Delta Q / \\Delta P < 0$。这是合成讲解。')
        pose('wrong-1', 'kp-1', question='repeat')
        db.execute('INSERT INTO pool_details VALUES(?,?,?,?)', ('repeat', '合成复核讲解：沿向下的需求曲线，价格与数量反向变化。', 'synthetic-generator', 'synthetic-reviewer'))
        pose('retry-1', 'kp-1', score=1, question='repeat')
        pose('revised', 'kp-2')
        emit('grade_reviewed', attemptId='revised', decision='REVISE', revised={'score': 1, 'max': 1, 'feedback': '改判正确'})
        pose('void', 'kp-3')
        emit('attempt_voided', attemptId='void')
        pose('shared-wrong', 'shared')
        emit('legacy_attempt', attemptId='legacy-wrong', questionId='legacy-q', kp_id='kp-4', kpIds=[], answer='low', keyState='UNREVIEWED',
             question={'prompt': '合成历史题', 'options': options, 'correctOptionId': 'high'},
             result={'final': True, 'is_correct': False, 'feedback': '历史合成讲解：B 是错的'},
             legacy_explanation_archive={'legacyLetterMap': {'low': 'B', 'high': 'A'}, 'explanation_references_letters': True, 'letter_map_complete': True})
        emit('legacy_attempt', attemptId='legacy-unresolved', kp_id='kp-4', answer='low', answer_unresolved=True, result={'final': True, 'is_correct': False})
        last = db.execute('SELECT MAX(seq) FROM events').fetchone()[0]
        projection = {'version': 4, 'lastSeq': last, 'mastery': {'kp-1': {'passed': True, 'passedBy': 'LEGACY', 'solid': False}, 'kp-2': {'passed': True, 'solid': True}}, 'unsupportedTypes': [], 'requiresServerSnapshot': False}
        db.execute('INSERT INTO projection_cache VALUES(?,?,?,?,?)', ('all', 'synthetic', 4, last, json.dumps(projection)))
    return Path(path)


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description='Create invented data only, for local export demos')
    parser.add_argument('database', type=Path)
    args = parser.parse_args()
    if args.database.exists():
        parser.error('refusing to overwrite an existing database')
    create_database(args.database)
