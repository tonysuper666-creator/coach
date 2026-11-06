import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

import import_weak_packs as importer


FIXTURE = Path(__file__).resolve().parent / "fixtures" / "pack-synthetic-weak.json"
SCHEMA = """
CREATE TABLE outline_kp(kp_id TEXT PRIMARY KEY, course_id TEXT, title TEXT, retired INTEGER);
CREATE TABLE questions(question_id TEXT PRIMARY KEY, course_id TEXT NOT NULL, format TEXT NOT NULL,
    stem_md TEXT NOT NULL, options TEXT, origin TEXT NOT NULL, fingerprint TEXT NOT NULL,
    key_state TEXT NOT NULL, created_seq INTEGER);
CREATE TABLE question_kp(question_id TEXT, kp_id TEXT, is_primary INTEGER, PRIMARY KEY(question_id, kp_id));
CREATE TABLE answer_keys(question_id TEXT PRIMARY KEY, key_json TEXT NOT NULL, reviewed_by TEXT, reviewed_at INTEGER);
CREATE TABLE pool_details(question_id TEXT PRIMARY KEY REFERENCES questions(question_id),
    pack_hash TEXT NOT NULL, explanation TEXT NOT NULL, source_quote TEXT NOT NULL,
    generator TEXT NOT NULL, reviewer TEXT NOT NULL);
CREATE TABLE constructed_details(question_id TEXT PRIMARY KEY REFERENCES questions(question_id),
    pack_hash TEXT NOT NULL, reference_answer TEXT NOT NULL, rubric TEXT NOT NULL,
    rubric_fingerprint TEXT NOT NULL, generator TEXT NOT NULL, reviewer TEXT NOT NULL);
CREATE TABLE events(seq INTEGER PRIMARY KEY, course_id TEXT, type TEXT, payload TEXT);
"""


class WeakPackImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / "data"
        self.data.mkdir()
        self.db = self.data / "coach.sqlite"
        with sqlite3.connect(self.db) as db:
            db.executescript(SCHEMA)
            db.execute("INSERT INTO outline_kp VALUES('kp-synthetic','course','Synthetic point',0)")
            db.execute("INSERT INTO events(course_id,type,payload) VALUES('course','note','keep me')")

    def run_import(self, *extra):
        return subprocess.run(
            [sys.executable, str(Path(importer.__file__)), "--pack", str(FIXTURE),
             "--target", str(self.data), "--course", "course", *extra],
            capture_output=True, text=True)

    def test_apply_is_idempotent_and_keeps_other_rows(self):
        dry = self.run_import()
        self.assertEqual(dry.returncode, 0, dry.stderr)
        self.assertEqual(json.loads(dry.stdout)["inserted"], 8)
        with sqlite3.connect(self.db) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM questions").fetchone()[0], 0)
        applied = self.run_import("--apply", "--backup", str(self.root / "backup.sqlite"))
        self.assertEqual(applied.returncode, 0, applied.stderr)
        report = json.loads(applied.stdout)
        self.assertEqual(report["inserted"], 8)
        self.assertEqual(report["mcq"], 7)
        self.assertEqual(report["analytical"], 1)
        with sqlite3.connect(self.db) as db:
            self.assertEqual(db.execute("SELECT payload FROM events").fetchone()[0], "keep me")
            mcq = db.execute(
                """SELECT q.question_id FROM questions q JOIN question_kp k USING(question_id)
                   WHERE q.format='SINGLE_CHOICE' AND q.key_state='VERIFIED' AND k.kp_id='kp-synthetic'"""
            ).fetchall()
            self.assertGreaterEqual(len(mcq), 3)
            key = json.loads(db.execute(
                "SELECT key_json FROM answer_keys WHERE question_id='pack-synthetic-weak-S1'").fetchone()[0])
            options = json.loads(db.execute(
                "SELECT options FROM questions WHERE question_id='pack-synthetic-weak-S1'").fetchone()[0])
            chosen = next(option["text"] for option in options if option["optionId"] == key["correctOptionId"])
            self.assertEqual(chosen, "green token")
            stem = db.execute(
                "SELECT stem_md FROM questions WHERE question_id='pack-synthetic-weak-S8'").fetchone()[0]
            self.assertNotIn("7", stem)
        again = self.run_import("--apply", "--backup", str(self.root / "backup-again.sqlite"))
        self.assertEqual(again.returncode, 0, again.stderr)
        self.assertEqual(json.loads(again.stdout)["inserted"], 0)

    def test_bad_answer_writes_nothing(self):
        broken = json.loads(FIXTURE.read_text())
        broken["sections"][0]["items"][0]["answer"] = 9
        path = self.root / "broken.json"
        path.write_text(json.dumps(broken))
        result = subprocess.run(
            [sys.executable, str(Path(importer.__file__)), "--pack", str(path),
             "--target", str(self.data), "--course", "course", "--apply"],
            capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        with sqlite3.connect(self.db) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM questions").fetchone()[0], 0)

    def test_overlap_and_production_guard(self):
        exam = self.root / "exam"
        exam.mkdir()
        sentence = "alpha bravo charlie delta echo foxtrot golf hotel"
        (exam / "01-Dec2024真题详解.md").write_text(sentence)
        (exam / "02-Dec2025真题详解.md").write_text("nothing else here")
        pack = {"id": "p", "sections": [{"items": [{"stem": sentence}]}]}
        self.assertEqual(len(importer.overlap_hits([pack], exam)), 1)
        self.assertEqual(importer.overlap_hits([{"id": "p", "sections": [{"items": [{"stem": "alpha only"}]}]}], exam), [])
        with self.assertRaises(SystemExit):
            importer.refuse_production(importer.production_dir())

    def test_eight_word_run_breaks_on_digits(self):
        text = "alpha bravo charlie delta echo foxtrot golf 9 hotel india"
        self.assertFalse(any(gram.startswith("alpha bravo") for gram in importer.eightgrams(text)))


if __name__ == "__main__":
    unittest.main()
