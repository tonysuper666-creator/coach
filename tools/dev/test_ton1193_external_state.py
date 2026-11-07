import datetime
from contextlib import closing
import json
import os
from pathlib import Path
import socket
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from . import ton1193_external_state as external
from .ton1175_switch import GateError


class ExternalStateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name).resolve()
        self.data = self.home / 'Coach/data'
        self.data.mkdir(parents=True)

    def write(self, rel, content='synthetic'):
        path = self.data.parent / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def snapshot(self):
        return external.inventory(self.data)

    def test_home_symlink_and_unrelated_sqlite_never_touched(self):
        (self.home / 'link').symlink_to('/nonexistent')
        with closing(sqlite3.connect(self.home / 'unrelated.sqlite')) as db, db:
            db.execute('CREATE TABLE unrelated(id)')
        with patch.object(sqlite3, 'connect', side_effect=AssertionError('opened SQLite')):
            self.assertEqual(self.snapshot(), {'other': {}, 'logs': {}, 'budget': None, 'key': None})
        self.assertFalse((self.home / 'unrelated.sqlite-shm').exists())

    def test_logs_allow_growth_creation_and_rotation_without_reading(self):
        path = self.write('logs/old.log')
        self.write('bridge/logs/bridge.log')
        before = self.snapshot()
        path.unlink()
        self.write('logs/new.log', 'growing')
        with patch.object(external.os, 'read', side_effect=AssertionError('read log')):
            external.compare(before, self.snapshot())

    def test_existing_log_cannot_shrink_without_rotation(self):
        path = self.write('logs/current.log', 'synthetic')
        before = self.snapshot()
        path.write_text('')
        with self.assertRaisesRegex(GateError, 'category=1'):
            external.compare(before, self.snapshot())

    def test_runtime_changes_and_sockets_are_opaque(self):
        before = self.snapshot()
        self.write('run/arbitrary')
        link = self.data.parent / 'bridge/work/link'
        link.parent.mkdir(parents=True)
        link.symlink_to('/nonexistent')
        path = self.data.parent / 'state/activity-test.sock'
        path.parent.mkdir()
        sock = socket.socket(socket.AF_UNIX)
        self.addCleanup(sock.close)
        sock.bind(str(path))
        external.compare(before, self.snapshot())

    def budget(self, **fields):
        value = dict(date=datetime.datetime.now().astimezone().date().isoformat(), claude_calls=2,
                     test_claude_calls=1)
        value.update(fields)
        return self.write('bridge/state/daily.json', json.dumps(value))

    def test_budget_increases_retained_and_only_integer_deltas_reported(self):
        path = self.budget()
        before = self.snapshot()
        self.budget(claude_calls=4, test_claude_calls=3)
        self.assertEqual(external.compare(before, self.snapshot()), {'claude_calls': 2, 'test_claude_calls': 2})
        self.assertEqual(json.loads(path.read_text())['claude_calls'], 4)

    def test_budget_rejects_decrease_bad_types_unknown_keys_tmp_and_deletion(self):
        path = self.budget()
        before = self.snapshot()
        for fields in ({'claude_calls': 1}, {'claude_calls': -1}, {'claude_calls': True},
                       {'extra': 1}, {'date': 'invalid'}, {'date': '2000-01-01'}):
            with self.subTest(fields=fields), self.assertRaisesRegex(GateError, 'category=3'):
                self.budget(**fields)
                external.compare(before, self.snapshot())
        self.budget()
        tmp = self.write('bridge/state/daily.tmp')
        with self.assertRaisesRegex(GateError, 'category=3'):
            self.snapshot()
        tmp.unlink()
        path.unlink()
        with self.assertRaisesRegex(GateError, 'category=3'):
            external.compare(before, self.snapshot())

    def test_budget_date_rollover_requires_local_today(self):
        yesterday = (datetime.datetime.now().astimezone().date() - datetime.timedelta(days=1)).isoformat()
        self.budget(date=yesterday, claude_calls=10)
        before = self.snapshot()
        self.budget(claude_calls=1)
        self.assertEqual(external.compare(before, self.snapshot())['claude_calls'], 1)

    def test_key_stat_only_never_read_and_change_rejected(self):
        path = self.write('bridge/state/production-key')
        with patch.object(external.os, 'read', side_effect=AssertionError('read key')):
            before = self.snapshot()
            external.compare(before, self.snapshot())
        path.unlink()
        self.write('bridge/state/production-key', 'changed')
        with self.assertRaisesRegex(GateError, 'category=4'):
            external.compare(before, self.snapshot())

    def test_other_files_exact_hash_and_set_including_sqlite_without_open(self):
        path = self.write('other.sqlite', 'SQLite format 3\0synthetic')
        self.write('other.json', '{}')
        with patch.object(sqlite3, 'connect', side_effect=AssertionError('opened SQLite')):
            before = self.snapshot()
            path.write_text('changed')
            with self.assertRaisesRegex(GateError, 'category=5'):
                external.compare(before, self.snapshot())
            path.unlink()
            with self.assertRaises(GateError):
                external.compare(before, self.snapshot())

    def test_symlinks_and_special_files_rejected_outside_runtime(self):
        for rel in ('logs/link', 'other-link', 'bridge/state/daily.json'):
            path = self.data.parent / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.symlink_to('/nonexistent')
            with self.assertRaisesRegex(GateError, 'EXTERNAL_STATE_CHANGED'):
                self.snapshot()
            path.unlink()
        path = self.data.parent / 'socket'
        sock = socket.socket(socket.AF_UNIX)
        self.addCleanup(sock.close)
        sock.bind(str(path))
        with self.assertRaisesRegex(GateError, 'category=5'):
            self.snapshot()

    def test_separately_bound_paths_excluded(self):
        before = self.snapshot()
        self.write('data/preexisting')
        self.write('data.pre-nonce/coach.sqlite')
        self.write('data.candidate-nonce/coach.sqlite-wal')
        self.write('service/releases/new/lib')
        self.write('service/installation.json')
        external.compare(before, self.snapshot())
