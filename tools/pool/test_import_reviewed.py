import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

import import_reviewed as importer


class ReviewedImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.target = self.root / 'target'
        self.target.mkdir()
        self.packs = self.root / 'packs'
        (self.packs / 'course').mkdir(parents=True)
        self.quote = 'A reviewed quotation from the original source.'
        (self.packs / 'course/kp.md').write_text(self.quote)
        schema = (Path(__file__).resolve().parents[2] / 'server/store/src/main/resources/migrations/V001__ledger.sql').read_text()
        self.source = self.root / 'source.sqlite'
        for path in (self.source, self.target / 'coach.sqlite'):
            with sqlite3.connect(path) as db:
                db.executescript(schema)
                db.executescript('CREATE TABLE pool_details(question_id TEXT PRIMARY KEY REFERENCES questions(question_id),pack_hash TEXT,explanation TEXT,source_quote TEXT,generator TEXT,reviewer TEXT); CREATE TABLE offline_reservations(question_id TEXT PRIMARY KEY,pack_id TEXT,expires_at INTEGER);')
                db.execute("INSERT INTO outline_kp(kp_id,course_id,title,retired) VALUES('kp','course','Title',0)")
        with sqlite3.connect(self.target / 'coach.sqlite') as db:
            db.execute("INSERT INTO devices VALUES('learner','Phone','unchanged-token-hash',1,NULL)")
            db.execute("INSERT INTO messages(thread_id,mseq,message_id,status,content_md) VALUES('thread',1,'message','complete','Learner history')")
        with sqlite3.connect(self.source) as db:
            rows = []
            for i in range(6):
                options = [dict(optionId=str(j), text=f'Option {j}') for j in range(4)]
                stem = f'Which answer is correct for example number {i}?'
                rows.append(dict(question_id=f'q{i}', course_id='course', format='SINGLE_CHOICE',
                    stem_md=stem, options=json.dumps(options), origin='COACH', fingerprint=importer.fingerprint(stem,options),
                    key_state='VERIFIED', created_seq=None, kp_id='kp', is_primary=1,
                    key_json=json.dumps(dict(correctOptionId='0')), reviewed_by='gpt-6', reviewed_at=1,
                    pack_hash=hashlib.sha256(('Title\0'+self.quote).encode()).hexdigest(), explanation='Explanation',
                    source_quote=self.quote, generator='claude-opus', reviewer='gpt-6'))
            importer.insert(db, rows)

    def run_import(self, apply=False, suffix=''):
        args = [sys.executable, str(Path(importer.__file__)), '--source', str(self.source),
                '--target', str(self.target), '--course', 'course', '--packs', str(self.packs),
                '--report', str(self.root / f'report{suffix}.json')]
        if apply:
            args += ['--apply', '--backup', str(self.root / f'backup{suffix}.sqlite')]
        return subprocess.run(args, capture_output=True, text=True)

    def test_import_preserves_learning_history_and_is_idempotent(self):
        self.assertEqual(self.run_import().returncode, 0)
        with sqlite3.connect(self.target / 'coach.sqlite') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM questions').fetchone()[0], 0)
        result = self.run_import(apply=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        with sqlite3.connect(self.target / 'coach.sqlite') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM questions').fetchone()[0], 6)
            self.assertEqual(db.execute('SELECT content_md FROM messages').fetchone()[0], 'Learner history')
            self.assertEqual(db.execute('SELECT token_sha256 FROM devices').fetchone()[0], 'unchanged-token-hash')
        with sqlite3.connect(self.root / 'backup.sqlite') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM questions').fetchone()[0], 0)
        result = self.run_import(apply=True, suffix='-again')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['inserted'], 0)

    def test_invalid_answer_cannot_partially_import_other_questions(self):
        with sqlite3.connect(self.source) as db:
            db.execute('UPDATE answer_keys SET key_json=? WHERE question_id=?', (json.dumps(dict(correctOptionId='missing')), 'q5'))
        self.assertNotEqual(self.run_import(apply=True).returncode, 0)
        with sqlite3.connect(self.target / 'coach.sqlite') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM questions').fetchone()[0], 0)

    def test_changed_source_requires_review_again(self):
        (self.packs / 'course/kp.md').write_text('A revised source that no longer supports these questions.')
        result = self.run_import(apply=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Source pack changed', result.stderr)
        with sqlite3.connect(self.target / 'coach.sqlite') as db:
            self.assertEqual(db.execute('SELECT count(*) FROM questions').fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
