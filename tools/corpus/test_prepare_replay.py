"""Behavior tests use invented conversations and a temporary WAL database only."""
import copy
import importlib.util
import json
from pathlib import Path
import os
import sqlite3
import tempfile
import time
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("replay", HERE / "prepare-replay.py")
replay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)


class ReplayTest(unittest.TestCase):
    def test_coach_copy_preserves_user_text_cutoff_and_does_not_invent_missing_speech(self):
        import argparse
        with tempfile.TemporaryDirectory() as temp:
            home = Path(temp).resolve()
            source = home / 'Coach/corpus/replay/source/snapshot'
            source.mkdir(parents=True)
            path = source / 'coach.snapshot.sqlite'
            cutoff = 1793455140000
            with sqlite3.connect(path) as db:
                db.execute('CREATE TABLE threads(thread_id TEXT,course_id TEXT)')
                db.execute('CREATE TABLE messages(thread_id TEXT,mseq INTEGER,message_id TEXT,role TEXT,content_md TEXT,created_at INTEGER)')
                db.execute('CREATE TABLE events(seq INTEGER,event_json TEXT,occurred_at INTEGER,course_id TEXT,payload TEXT,type TEXT)')
                db.execute("INSERT INTO threads VALUES ('thread','ECNM10119')")
                db.executemany('INSERT INTO messages VALUES (?,?,?,?,?,?)',[
                    ('thread',1,'old','user','Before cutoff',cutoff-1),
                    ('thread',2,'assistant','assistant','Invented flower lesson',cutoff),
                    ('thread',3,'new','user','继续',cutoff),
                    ('thread',4,'reply','assistant','Invented card',cutoff+1)])
            args=argparse.Namespace(output=home/'out',coach_copy=path,since='2026-10-31T13:59:00Z',course='ECNM10119')
            with patch.object(replay,'HOME',home):
                replay.export_coach(args,'synthetic Coach copy test')
                rows=[json.loads(line) for line in (args.output/'coach-replay-input.jsonl').read_text().splitlines()]
                self.assertEqual(['继续'],[r['user']['content'] for r in rows])
                self.assertEqual(cutoff,rows[0]['user']['coach_row']['created_at'])
                self.assertEqual('Invented card',rows[0]['assistant_after'][0]['content'])
                self.assertTrue(rows[0]['id'].startswith('coach:'))
                with sqlite3.connect(path) as db:
                    db.execute("UPDATE threads SET course_id='opaque-course'")
                    outline={'course': {'id': 'opaque-course', 'name': 'ECNM10119 Synthetic Health Economics'}}
                    event={'type': 'outline_revised', 'payload': outline}
                    db.execute('INSERT INTO events VALUES (?,?,?,?,?,?)',
                               (1,json.dumps(event),cutoff-1000,'opaque-course',json.dumps(outline),'outline_revised'))
                    ops={'ops': []}
                    db.execute('INSERT INTO events VALUES (?,?,?,?,?,?)',
                               (2,json.dumps({'type':'outline_revised','payload':ops}),cutoff+1,
                                'opaque-course',json.dumps(ops),'outline_revised'))
                replay.export_coach(args,'synthetic opaque course ID test')
                rows=[json.loads(line) for line in (args.output/'coach-replay-input.jsonl').read_text().splitlines()]
                self.assertEqual(['继续'],[r['user']['content'] for r in rows])
                with sqlite3.connect(path) as db:
                    db.execute("DELETE FROM messages WHERE role='user'")
                replay.export_coach(args,'synthetic Coach copy test')
                summary=json.loads((args.output/'coach-summary.json').read_text())
                self.assertEqual(0,summary['user_utterances'])
                self.assertTrue(summary['missing_user_text'])

    def setUp(self):
        self.fixture = [json.loads(line) for line in (HERE / "fixtures/replay-synthetic.jsonl").read_text().splitlines()]

    def test_synthetic_intents_and_all_eight_assertion_families(self):
        _, rows = replay.prepare(self.fixture, "ECNM10119", set(), 0)
        by_text = {r["user"]["content"]: r for r in rows}
        expected = {"继续": "continue", "出题": "request_question", "一次三道题": "request_batch",
                    "A": "answer", "不想写自述，只做选择题": "mcq_only", "学了多少？": "progress",
                    "为什么要先讲解？": "question", "我选最后那个吧": "unknown"}
        for text, kind in expected.items():
            self.assertEqual(by_text[text]["intent"], kind)
        self.assertEqual(by_text["继续"]["assistant_before"][0]["content"], "合成讲解：月光花在夜间开放。")
        self.assertEqual(by_text["继续"]["assistant_after"][0]["content"], "[合成题卡] 哪种花在夜间开放？")
        for family in replay.ASSERTIONS.values():
            self.assertTrue(set(family) <= {a for row in rows for a in row["assertion_candidates"]})

    def test_course_override_cutoff_and_context_preserved(self):
        item = copy.deepcopy(self.fixture[0])
        msgs = item["messages"]
        msgs[1]["metadata_json"] = json.dumps({"request_snapshot": {"knowledgeBases": ["OTHER101"]}})
        msgs[3]["metadata_json"] = json.dumps({"request_snapshot": {"knowledgeBases": ["ECNM10119-X"]}})
        selected, rows = replay.prepare([item], "ECNM10119", set(), msgs[3]["created_at"])
        self.assertEqual([r["user"]["content"] for r in rows], [msgs[3]["content"], msgs[5]["content"]])
        self.assertEqual(selected[0]["messages"], msgs)
        self.assertEqual(rows[0]["assistant_before"], [msgs[2]])
        _, all_rows = replay.prepare([item], "ECNM10119", set(), 0)
        self.assertNotIn(msgs[1]["id"], [r["user"]["id"] for r in all_rows])
        self.assertFalse(replay.course_match({"knowledgeBases": ["ECNM101190"]}, "ECNM10119", set())[1])

    def test_structured_answer_and_ambiguous_text(self):
        msg = {"content": "三角形", "metadata_json": json.dumps({"request_snapshot": {"masteryAnswer": {"optionId": "shape-3"}}})}
        self.assertEqual(replay.intent(msg)[0], "answer")
        self.assertEqual(replay.intent({"content": "继续解释这个", "metadata_json": "{}"})[0], "unknown")

    def test_export_bytes_repeat_and_absent_corpus_is_not_synthetic(self):
        import argparse
        with tempfile.TemporaryDirectory() as temp:
            args = argparse.Namespace(output=Path(temp).resolve(), synthetic=HERE / "fixtures/replay-synthetic.jsonl",
                                      since="2026-10-23T00:00:00+01:00", course="ECNM10119", snapshot=False)
            replay.export(args, "synthetic test")
            before = {p.name: p.read_bytes() for p in Path(temp).iterdir()}
            replay.export(args, "synthetic test")
            self.assertEqual(before, {p.name: p.read_bytes() for p in Path(temp).iterdir()})
            args.synthetic = None
            with self.assertRaisesRegex(ValueError, "缺语料，未运行"):
                replay.export(args, "real test")

    def test_snapshot_keeps_wal_rows_and_never_connects_live_db(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            source = root / "DeepTutor"
            user = source / "data/user"
            (user / "runtime").mkdir(parents=True)
            log = user / "runtime/bridge.log"
            log.write_text("2020-01-01T00:00:00+00:00 idle\n")
            os.utime(log, (time.time() - 3600, time.time() - 3600))
            dbpath = user / "chat_history.db"
            writer = sqlite3.connect(dbpath)
            try:
                writer.execute("PRAGMA journal_mode=WAL")
                writer.execute("CREATE TABLE facts(value TEXT)")
                writer.execute("INSERT INTO facts VALUES ('invented WAL fact')")
                writer.commit()
                before = replay.inventory(source / "data")
                actual_connect = sqlite3.connect
                opened = []

                def connect(path, *args, **kwargs):
                    opened.append(str(path))
                    self.assertNotIn(str(source), str(path))
                    return actual_connect(path, *args, **kwargs)

                with patch.object(replay.sqlite3, "connect", side_effect=connect):
                    target = replay.snapshot(source, root / "output", "synthetic snapshot test")
                self.assertEqual(before, replay.inventory(source / "data"))
                self.assertTrue(opened)
                # SQLite may create SHM while reading the copied WAL; only source must stay unchanged.
                with actual_connect(target / "chat.snapshot.db") as db:
                    self.assertEqual(db.execute("SELECT value FROM facts").fetchone()[0], "invented WAL fact")
            finally:
                writer.close()

    def test_snapshot_discards_if_source_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            source = root / "DeepTutor"
            (source / "data/user").mkdir(parents=True)
            (source / "data/user/chat_history.db").write_bytes(b"synthetic")
            with patch.object(replay, "idle"), patch.object(replay, "inventory", side_effect=[{}, {"changed": [1, 2]}]):
                with self.assertRaisesRegex(ValueError, "源数据在拷贝期间变化"):
                    replay.snapshot(source, root / "out", "test")
            self.assertEqual(list((root / "out/source").iterdir()), [])


if __name__ == "__main__":
    unittest.main()
