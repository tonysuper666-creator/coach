import contextlib
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from tools.dev import friend_revoke_offline as tool


class OfflineRevokeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        # Characters requiring URI escaping must not select a different DB.
        self.path = self.root / 'private ?# database.sqlite'
        self.binding_path = self.root / 'binding.json'
        self.make_database()
        info = self.path.stat()
        self.binding = {"database": str(self.path), "device": info.st_dev,
                        "inode": info.st_ino, "epoch": "fixture-epoch"}
        self.binding_path.write_text(json.dumps(self.binding))

    def make_database(self, friend_code=True, wal=False):
        with contextlib.closing(sqlite3.connect(self.path)) as db, db:
            if wal:
                db.execute('PRAGMA journal_mode=WAL')
            # The credential/meta definitions match V001__ledger.sql, with
            # additive friend columns and pairing_code from LedgerStore.init.
            db.executescript('''
                CREATE TABLE ledger_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                    epoch TEXT NOT NULL,backed_up_through_seq INTEGER NOT NULL DEFAULT 0,
                    last_seq_before INTEGER NOT NULL DEFAULT 0,last_backup_at INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE devices(device_id TEXT PRIMARY KEY,name TEXT,token_sha256 TEXT NOT NULL,
                    created_at INTEGER,revoked_at INTEGER);
                ALTER TABLE devices ADD COLUMN friend_name TEXT;
                ALTER TABLE devices ADD COLUMN last_used_at INTEGER;
                CREATE TABLE pairing_code(singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                    code_sha256 TEXT NOT NULL,expires_at INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0);
                ALTER TABLE pairing_code ADD COLUMN friend_name TEXT;
                CREATE TABLE retained_writes(id INTEGER PRIMARY KEY, content TEXT);
                INSERT INTO retained_writes VALUES(1,'post-switch write');
                INSERT INTO ledger_meta(singleton,epoch) VALUES(1,'fixture-epoch');
            ''')
            for identity, friend, revoked in [('owner', None, None), ('owner-old', None, 42),
                                               ('friend-a', 'a', None), ('friend-b', 'b', None),
                                               ('friend-old', 'a', 23), ('friend-empty', '', None)]:
                token = hashlib.sha256((identity + '-private-token').encode()).hexdigest()
                db.execute('INSERT INTO devices VALUES(?,?,?,?,?,?,?)',
                           (identity, 'private name', token, 11, revoked, friend, 17))
            db.execute('INSERT INTO pairing_code VALUES(1,?,9999999999999,0,?)',
                       ('private-code-hash', 'a' if friend_code else None))

    def rows(self, table):
        with contextlib.closing(sqlite3.connect(self.path)) as db, db:
            return db.execute(f'SELECT * FROM {table} ORDER BY 1').fetchall()

    def invoke(self, apply=False, binding=None):
        if binding is not None:
            self.binding_path.write_text(json.dumps(binding))
        return subprocess.run([sys.executable, tool.__file__, '--binding', str(self.binding_path)]
                              + (['--apply'] if apply else []), capture_output=True, text=True)

    def assert_failed(self, result):
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, '{"failed":1}\n')
        self.assertEqual(result.stderr, '')

    def assert_success(self, result, counts):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, '')
        self.assertEqual(json.loads(result.stdout), counts)
        self.assertTrue(all(type(value) is int for value in json.loads(result.stdout).values()))

    def test_default_opens_readonly_and_does_not_change_database(self):
        before = self.path.read_bytes()
        self.assert_success(self.invoke(), {"active_friends": 3, "pending_friend_codes": 1,
                                            "revoked": 0, "invalidated_codes": 0})
        self.assertEqual(self.path.read_bytes(), before)
        real_connect = sqlite3.connect
        with mock.patch.object(tool.sqlite3, 'connect', wraps=real_connect) as connect:
            tool.revoke(self.binding)
        self.assertEqual(connect.call_count, 1)
        self.assertTrue(connect.call_args.args[0].endswith('?mode=ro'))

    def test_apply_revokes_all_friends_preserves_owner_and_new_writes_and_is_idempotent(self):
        for wal in (False, True):
            with self.subTest(wal=wal):
                with contextlib.closing(sqlite3.connect(self.path)) as db, db:
                    db.execute('PRAGMA journal_mode=' + ('WAL' if wal else 'DELETE'))
                    db.execute('UPDATE devices SET revoked_at=NULL WHERE device_id IN '
                               "('friend-a','friend-b','friend-empty')")
                    db.execute("INSERT OR REPLACE INTO pairing_code VALUES(1,'private-code-hash',9999999999999,0,'a')")
                before = self.rows('devices')
                retained = self.rows('retained_writes')
                meta = self.rows('ledger_meta')
                budget = self.root / 'api-credit-ledger.json'
                budget.write_text('{"cycles":{"owner":{"spent":90,"reserved":25,"limit":1000}}}')
                self.assert_success(self.invoke(True), {"active_friends": 0, "pending_friend_codes": 0,
                                                        "revoked": 3, "invalidated_codes": 1})
                after = self.rows('devices')
                for old, new in zip(before, after):
                    self.assertEqual(old[:4] + old[5:], new[:4] + new[5:])
                    if old[5] is not None and old[4] is None:
                        self.assertGreater(new[4], 0)
                    else:
                        self.assertEqual(old, new)
                self.assertEqual(self.rows('ledger_meta'), meta)
                self.assertEqual(self.rows('retained_writes'), retained)
                self.assertEqual(budget.read_text(), '{"cycles":{"owner":{"spent":90,"reserved":25,"limit":1000}}}')
                self.assert_success(self.invoke(True), {"active_friends": 0, "pending_friend_codes": 0,
                                                        "revoked": 0, "invalidated_codes": 0})
                self.assertEqual(self.rows('devices'), after)
                # This is the old authorize/pair SQL, not an actual old binary.
                with contextlib.closing(sqlite3.connect(self.path)) as db, db:
                    self.assertEqual(db.execute('SELECT device_id FROM devices WHERE revoked_at IS NULL').fetchall(), [('owner',)])
                    self.assertEqual(db.execute('SELECT * FROM pairing_code WHERE singleton=1').fetchall(), [])

    def test_owner_pending_code_is_unchanged(self):
        with contextlib.closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE pairing_code SET friend_name=NULL,attempts=2')
        before = self.rows('pairing_code')
        self.assert_success(self.invoke(True), {"active_friends": 0, "pending_friend_codes": 0,
                                                "revoked": 3, "invalidated_codes": 0})
        self.assertEqual(self.rows('pairing_code'), before)

    def test_expired_and_exhausted_friend_code_is_removed(self):
        with contextlib.closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE pairing_code SET expires_at=0,attempts=5')
        self.assert_success(self.invoke(True), {"active_friends": 0, "pending_friend_codes": 0,
                                                "revoked": 3, "invalidated_codes": 1})

    def test_wrong_identity_and_missing_database_do_not_write_or_create(self):
        before = self.path.read_bytes()
        for field, value in [('device', self.binding['device'] + 1),
                             ('inode', self.binding['inode'] + 1), ('epoch', 'other'),
                             ('database', str(self.root / 'does-not-exist.sqlite'))]:
            with self.subTest(field=field):
                self.assert_failed(self.invoke(True, {**self.binding, field: value}))
                self.assertEqual(self.path.read_bytes(), before)
        self.assertFalse((self.root / 'does-not-exist.sqlite').exists())

    def test_symlink_and_replaced_database_are_rejected(self):
        link = self.root / 'link.sqlite'
        link.symlink_to(self.path)
        self.assert_failed(self.invoke(True, {**self.binding, 'database': str(link)}))
        original = self.path.with_suffix('.old')
        self.path.rename(original)
        self.path.write_bytes(original.read_bytes())
        before = self.path.read_bytes()
        self.assert_failed(self.invoke(True, self.binding))
        self.assertEqual(self.path.read_bytes(), before)

    def test_unmigrated_schema_and_unexpected_triggers_fail_closed(self):
        before = self.rows('devices')
        with contextlib.closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TRIGGER surprise AFTER UPDATE ON devices BEGIN '
                       "DELETE FROM retained_writes; END")
        self.assert_failed(self.invoke(True))
        self.assertEqual(self.rows('devices'), before)
        self.assertEqual(len(self.rows('retained_writes')), 1)
        with contextlib.closing(sqlite3.connect(self.path)) as db, db:
            db.execute('DROP TRIGGER surprise')
            db.execute('ALTER TABLE pairing_code RENAME COLUMN friend_name TO unknown_column')
        self.assert_failed(self.invoke(True))
        self.assertEqual(self.rows('devices'), before)

    def test_writer_lock_conflict_fails_without_mutation_in_wal_and_delete_modes(self):
        for wal in (False, True):
            with self.subTest(wal=wal), contextlib.closing(sqlite3.connect(self.path)) as db:
                db.execute('PRAGMA journal_mode=' + ('WAL' if wal else 'DELETE'))
                before = self.rows('devices')
                db.execute('BEGIN IMMEDIATE')
                self.assert_failed(self.invoke(True))
                db.rollback()
                self.assertEqual(self.rows('devices'), before)

    def test_second_statement_failure_rolls_back_device_revocations(self):
        before = self.rows('devices')
        codes = self.rows('pairing_code')
        # A real SQLite FK error occurs at DELETE, after UPDATE has run.
        with contextlib.closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE code_dependency(code INTEGER REFERENCES pairing_code(singleton) ON DELETE RESTRICT)')
            db.execute('INSERT INTO code_dependency VALUES(1)')
        self.assert_failed(self.invoke(True))
        self.assertEqual(self.rows('devices'), before)
        self.assertEqual(self.rows('pairing_code'), codes)

    def test_wal_contains_latest_committed_friend(self):
        with contextlib.closing(sqlite3.connect(self.path)) as writer:
            writer.execute('PRAGMA journal_mode=WAL')
            writer.execute('PRAGMA wal_autocheckpoint=0')
            writer.execute("INSERT INTO devices VALUES('wal-friend','private','hash',12,NULL,'c',NULL)")
            writer.commit()
            self.assertTrue(Path(str(self.path) + '-wal').exists())
            self.assert_success(self.invoke(True), {"active_friends": 0, "pending_friend_codes": 0,
                                                    "revoked": 4, "invalidated_codes": 1})

    def test_identity_is_rechecked_between_preflight_and_write_open(self):
        before = self.rows('devices')
        real_connect = tool.connect

        def replace_epoch(binding, writable=False):
            if writable:
                with contextlib.closing(sqlite3.connect(self.path)) as db, db:
                    db.execute("UPDATE ledger_meta SET epoch='replacement'")
            return real_connect(binding, writable)

        with mock.patch.object(tool, 'connect', side_effect=replace_epoch):
            with self.assertRaises(tool.GateError):
                tool.revoke(self.binding, True)
        self.assertEqual(self.rows('devices'), before)

    def test_fresh_read_failure_after_commit_is_reported_as_failure(self):
        real_connect = tool.connect
        calls = 0

        def connect(binding, writable=False):
            nonlocal calls
            calls += 1
            if calls == 3:
                raise OSError('private path must not be printed')
            return real_connect(binding, writable)

        output = io.StringIO()
        with mock.patch.object(tool, 'connect', side_effect=connect), contextlib.redirect_stdout(output):
            self.assertEqual(tool.main(['--binding', str(self.binding_path), '--apply']), 1)
        self.assertEqual(output.getvalue(), '{"failed":1}\n')
        self.assertTrue(all(row[4] is not None for row in self.rows('devices') if row[5] is not None))

    def test_invalid_arguments_binding_and_corrupt_database_print_only_failure_count(self):
        for args in (['--binding', 'private-missing.json'], ['--secret-private-value'],
                     ['--binding', str(self.binding_path), '--apply=private-value']):
            result = subprocess.run([sys.executable, tool.__file__, *args], capture_output=True, text=True)
            self.assert_failed(result)
        for binding in ({}, [], {**self.binding, 'inode': True}, {**self.binding, 'epoch': ''}):
            self.assert_failed(self.invoke(True, binding))
        self.binding_path.write_text(json.dumps(self.binding))
        self.path.write_bytes(b'corrupt private contents')
        self.assert_failed(self.invoke(True))


if __name__ == '__main__':
    unittest.main()
