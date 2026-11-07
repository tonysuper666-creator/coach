import datetime
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from . import ton1193_external_state as external
from . import ton1193_usage_schema as schema
from .ton1175_switch import GateError, digest


SOURCE = '''s.execute("PRAGMA journal_mode=WAL")
s.execute("CREATE TABLE IF NOT EXISTS usage(device TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(device,id))")
'''


class UsageObservationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.data = self.root / 'h/Coach/data'
        self.data.mkdir(parents=True)
        self.day = datetime.date(2026, 11, 7)
        clock_patch = patch.object(external, 'utc_today', return_value=self.day)
        self.clock = clock_patch.start()
        self.addCleanup(clock_patch.stop)
        for side in ('old', 'new'):
            source = self.root / side / schema.SOURCE
            source.parent.mkdir(parents=True)
            source.write_text(SOURCE)
        self.proof = schema.attest(self.root / 'old', self.root / 'new', 'old-head', 'new-head')
        diagnostics = self.data.parent / 'state/diagnostics'
        diagnostics.mkdir(mode=0o700, parents=True)
        self.write('state/diagnostics/usage.sqlite')

    def write(self, rel, content=b'synthetic'):
        path = self.data.parent / rel
        path.parent.mkdir(exist_ok=True, parents=True)
        path.write_bytes(content)
        path.chmod(0o600)
        return path

    def snapshot(self):
        return external.inventory(self.data, self.proof)

    def test_today_replacement_and_utc_rollover_are_retained(self):
        path = self.write('state/usage-20261107.md')
        before = self.snapshot()
        replacement = self.root / 'replacement'
        replacement.write_bytes(b'new summary')
        replacement.chmod(0o600)
        replacement.replace(path)
        self.assertNotEqual(before['usage']['files']['state/usage-20261107.md'][1], path.stat().st_ino)
        self.clock.return_value = self.day + datetime.timedelta(days=1)
        self.write('state/usage-20261108.md')
        after = self.snapshot()
        external.compare(before, after)
        self.assertEqual(external.compare_usage(before, after), {'changedFiles': 2, 'sizeDelta': 11})
        self.assertEqual(path.read_bytes(), b'new summary')

    def test_historical_or_future_changes_and_any_deletion_refuse(self):
        for date in ('20261106', '20261109', '20261107'):
            with self.subTest(date=date):
                path = self.write(f'state/usage-{date}.md')
                before = self.snapshot()
                if date != '20261107':
                    path.write_bytes(b'changed')
                    with self.assertRaisesRegex(GateError, 'category=6'):
                        external.compare(before, self.snapshot())
                path.unlink()
                with self.assertRaisesRegex(GateError, 'category=6'):
                    external.compare(before, self.snapshot())

    def test_historical_inode_replacement_refuses_even_identical_bytes(self):
        path = self.write('state/usage-20261106.md')
        before = self.snapshot()
        replacement = self.root / 'replacement'
        replacement.write_bytes(path.read_bytes())
        replacement.chmod(0o600)
        replacement.replace(path)
        with self.assertRaisesRegex(GateError, 'category=6'):
            external.compare(before, self.snapshot())

    def test_tmp_refuses_even_empty(self):
        self.write('state/usage-123456.tmp', b'')
        with self.assertRaisesRegex(GateError, 'category=6'):
            self.snapshot()

    def test_invalid_dates_and_size_limits_refuse(self):
        for name in ('usage-20260230.md', 'usage-2026117.md', 'usage-yesterday.md'):
            path = self.write('state/' + name)
            with self.subTest(name=name), self.assertRaisesRegex(GateError, 'category=6'):
                self.snapshot()
            path.unlink()
        for rel, limit in (('state/usage-20261107.md', 1024**2),
                           ('state/diagnostics/usage.sqlite', 64 * 1024**2),
                           ('state/diagnostics/usage.sqlite-wal', 64 * 1024**2),
                           ('state/diagnostics/usage.sqlite-shm', 64 * 1024**2)):
            path = self.write(rel)
            with path.open('r+b') as stream:
                stream.truncate(limit)
            self.snapshot()
            with path.open('r+b') as stream:
                stream.truncate(limit + 1)
            with self.subTest(rel=rel), self.assertRaisesRegex(GateError, 'category=6'):
                self.snapshot()
            path.unlink()

    def test_unknown_diagnostic_entry_symlink_hardlink_and_subdirectory_refuse(self):
        path = self.write('state/diagnostics/unknown')
        with self.assertRaisesRegex(GateError, 'category=6'):
            self.snapshot()
        path.unlink()
        path.symlink_to('/nonexistent')
        with self.assertRaisesRegex(GateError, 'category=6'):
            self.snapshot()
        path.unlink()
        os.link(self.data.parent / 'state/diagnostics/usage.sqlite', path)
        with self.assertRaisesRegex(GateError, 'category=6'):
            self.snapshot()
        path.unlink()
        path.mkdir()
        with self.assertRaisesRegex(GateError, 'category=6'):
            self.snapshot()

    def test_permissions_and_owner_refuse(self):
        path = self.data.parent / 'state/diagnostics'
        path.chmod(0o750)
        with self.assertRaisesRegex(GateError, 'category=6'):
            self.snapshot()
        path.chmod(0o700)
        file = self.write('state/usage-20261107.md')
        file.chmod(0o620)
        with self.assertRaisesRegex(GateError, 'category=6'):
            self.snapshot()
        file.chmod(0o600)
        with patch.object(external.os, 'getuid', return_value=os.getuid() + 1):
            with self.assertRaisesRegex(GateError, 'category=6'):
                self.snapshot()

    def test_database_inode_must_stay_and_wal_shm_can_appear_disappear(self):
        before = self.snapshot()
        wal = self.write('state/diagnostics/usage.sqlite-wal')
        shm = self.write('state/diagnostics/usage.sqlite-shm')
        with_wal = self.snapshot()
        external.compare(before, with_wal)
        wal.unlink()
        shm.unlink()
        external.compare(with_wal, self.snapshot())
        replacement = self.root / 'replacement'
        replacement.write_bytes(b'synthetic')
        replacement.chmod(0o600)
        replacement.replace(self.data.parent / 'state/diagnostics/usage.sqlite')
        with self.assertRaisesRegex(GateError, 'category=6'):
            external.compare(before, self.snapshot())

    def test_different_or_missing_source_proof_falls_back_to_strict_hashes(self):
        for source in (SOURCE.replace('device TEXT', 'device BLOB'), '', None):
            with self.subTest(source=source):
                path = self.root / 'new' / schema.SOURCE
                if source is None:
                    path.unlink()
                else:
                    path.write_text(source)
                proof = schema.attest(self.root / 'old', self.root / 'new', 'old-head', 'new-head')
                self.assertFalse(schema.enabled(proof))
                md = self.write('state/usage-20261107.md', b'before')
                before = external.inventory(self.data, proof)
                self.assertNotIn('usage', before)
                md.write_bytes(b'after')
                with self.assertRaisesRegex(GateError, 'category=5'):
                    external.compare(before, external.inventory(self.data, proof))

    def test_stat_only_never_reads_payload_or_opens_sqlite(self):
        self.write('state/usage-20261107.md')
        self.write('state/diagnostics/usage.sqlite-wal')
        original_open = os.open
        def open_directory_only(path, flags, *args, **kwargs):
            self.assertNotIn('usage', os.fspath(path), 'opened usage payload')
            return original_open(path, flags, *args, **kwargs)
        with patch.object(external.os, 'read', side_effect=AssertionError('read usage payload')), \
                patch.object(external.os, 'open', side_effect=open_directory_only), \
                patch.object(sqlite3, 'connect', side_effect=AssertionError('opened SQLite')):
            before = self.snapshot()
            external.compare(before, self.snapshot())

    def test_manifest_hash_and_both_source_heads_bind_proof(self):
        manifest = self.root / 'manifest.json'
        raw = json.dumps({'usageObservationSchema': self.proof}).encode()
        manifest.write_bytes(raw)
        manifest.chmod(0o600)
        config = dict(adapterManifest=str(manifest), adapterManifestSha256=digest(raw),
                      expectedSourceHead='old-head', candidateHead='new-head')
        self.assertEqual(schema.bound(config), self.proof)
        self.assertIsNone(schema.bound({}))
        config['candidateHead'] = 'different'
        with self.assertRaisesRegex(GateError, 'revisions differ'):
            schema.bound(config)
        config['candidateHead'] = 'new-head'
        manifest.write_bytes(raw + b' ')
        with self.assertRaisesRegex(GateError, 'manifest changed'):
            schema.bound(config)

    def test_initializers_require_exact_literal_lines(self):
        self.assertIsNotNone(schema.extract(SOURCE.encode()))
        for changed in (SOURCE + SOURCE, SOURCE.replace('s.execute', '// s.execute'),
                        SOURCE.replace('WAL', '" + dynamic + "')):
            self.assertIsNone(schema.extract(changed.encode()))
        path = self.root / 'new' / schema.SOURCE
        path.write_text(SOURCE.replace('journal_mode=WAL', 'journal_mode=DELETE'))
        self.assertFalse(schema.enabled(schema.attest(self.root / 'old', self.root / 'new', 'a', 'b')))

    def test_clock_rollback_refuses(self):
        before = self.snapshot()
        self.clock.return_value = self.day - datetime.timedelta(days=1)
        with self.assertRaisesRegex(GateError, 'category=6'):
            external.compare(before, self.snapshot())
