"""Reviewer probe (not committed): post-v7 durable writes outside events/messages/devices."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('train', Path(__file__).with_name('rehearse-release-train.py'))
train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train)


class UncoveredDurableWriteTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.data = Path(self.temporary.name)
        self.current = self.data / 'coach.sqlite'
        self.backup = self.data / 'before.sqlite'
        with train.database(self.current) as db:
            db.executescript("""create table events(seq integer primary key, payload text);
                create table messages(thread_id text,mseq integer,content text);
                create table devices(device_id text,token_sha256 text);
                create table source_docs(doc_id text primary key, tutor_visible integer);
                create table questions(question_id text primary key, stem text);
                create table answer_keys(question_id text primary key, key_json text);
                create table offline_packs(pack_id text primary key, device_id text);
                create table threads(thread_id text primary key, anchor_id text);
                insert into events values(1,'original');
                insert into messages values('t',1,'original speech');
                insert into devices values('phone','original hash');
                pragma user_version=6;""")
            with train.database(self.backup) as before:
                db.backup(before)
            db.execute('alter table source_docs add column learner_visible integer not null default 1')
            db.execute('pragma user_version=7')

    def test_non_event_durable_writes_must_refuse_restore(self):
        mutations = [
            "insert into questions values('q-new','reviewed pool question')",
            "insert into answer_keys values('q-new','{\"correctOptionId\":\"o1\"}')",
            "insert into source_docs values('doc-new',1,1)",
            "insert into offline_packs values('pack-new','phone')",
            "insert into threads values('thread-new','kp')",
        ]
        for sql in mutations:
            with self.subTest(sql=sql):
                self.setUp()
                with train.database(self.current) as db:
                    db.execute(sql)
                with self.assertRaisesRegex(RuntimeError, 'durable writes',
                                            msg='restore silently discarded a post-migration durable row'):
                    train.restore_before_v7(self.data, self.backup, server_stopped=True)
                with train.database(self.current) as db:
                    print(sql.split()[2], 'after restore user_version=', db.execute('pragma user_version').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
