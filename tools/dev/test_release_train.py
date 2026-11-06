"""Restore must refuse real writes, including a same-watermark event edit or speech-only write."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('train', Path(__file__).with_name('rehearse-release-train.py'))
train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train)


class MigrationRestoreTest(unittest.TestCase):
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
                create table source_docs(doc_id text primary key);
                insert into events values(1,'original');
                insert into messages values('t',1,'original speech');
                insert into devices values('phone','original hash');
                pragma user_version=6;""")
            with train.database(self.backup) as before:
                db.backup(before)
            db.execute('alter table source_docs add column learner_visible integer not null default 1')
            db.execute('pragma user_version=7')

    def fingerprint(self):
        with train.database(self.current) as db:
            return db.execute('pragma user_version').fetchone()[0], train.durable_fingerprint(db)

    def restore(self):
        train.restore_before_v7(self.data, self.backup, server_stopped=True)

    def test_zero_write_window_restores_actual_v6_schema(self):
        with train.database(self.current) as db:
            self.assertIn('learner_visible', [row[1] for row in db.execute('pragma table_info(source_docs)')])
        self.restore()
        self.assertEqual(self.fingerprint()[0], 6)
        with train.database(self.current) as db:
            self.assertEqual(db.execute('pragma integrity_check').fetchone()[0], 'ok')
            self.assertEqual(db.execute('select token_sha256 from devices').fetchone()[0], 'original hash')
            self.assertNotIn('learner_visible', [row[1] for row in db.execute('pragma table_info(source_docs)')])

    def test_new_event_same_seq_edit_speech_and_credential_changes_refuse_without_loss(self):
        mutations = ["insert into events values(2,'candidate')", "update events set payload='edited' where seq=1",
                     "insert into messages values('t',2,'candidate speech')", "update devices set token_sha256='new hash'"]
        for sql in mutations:
            with self.subTest(sql=sql):
                with train.database(self.current) as db:
                    db.execute(sql)
                    db.commit()
                before = self.fingerprint()
                with self.assertRaisesRegex(RuntimeError, 'durable writes'):
                    self.restore()
                self.assertEqual(self.fingerprint(), before)
                with train.database(self.backup) as backup, train.database(self.current) as db:
                    backup.backup(db)
                    db.execute('pragma user_version=7')

    def test_running_server_is_never_restored(self):
        before = self.fingerprint()
        with self.assertRaisesRegex(RuntimeError, 'server is running'):
            train.restore_before_v7(self.data, self.backup, server_stopped=False)
        self.assertEqual(self.fingerprint(), before)

    def test_future_table_binary_updates_deletes_and_visibility_are_protected(self):
        mutations = [
            "create table future_table(id integer, content blob); insert into future_table values(1,x'00ff')",
            "delete from devices",
            "insert into source_docs values('public',0)",
            "update messages set content='changed without a new event'",
        ]
        for sql in mutations:
            with self.subTest(sql=sql):
                with train.database(self.current) as db:
                    db.executescript(sql)
                before = self.fingerprint()
                with self.assertRaisesRegex(RuntimeError, 'durable writes'):
                    self.restore()
                self.assertEqual(self.fingerprint(), before)
                with train.database(self.backup) as backup, train.database(self.current) as db:
                    backup.backup(db)
                    db.execute('alter table source_docs add column learner_visible integer not null default 1')
                    db.execute('pragma user_version=7')

    def test_only_rebuildable_projection_cache_is_disposable(self):
        with train.database(self.current) as db:
            db.executescript("create table projection_cache(course text, payload blob); insert into projection_cache values('C',x'00ff')")
        self.restore()
        self.assertEqual(self.fingerprint()[0], 6)

    def test_wrong_backup_schema_is_refused(self):
        with train.database(self.backup) as db:
            db.execute('pragma user_version=5')
        before = self.fingerprint()
        with self.assertRaisesRegex(RuntimeError, 'not v6'):
            self.restore()
        self.assertEqual(self.fingerprint(), before)


if __name__ == '__main__':
    unittest.main()
