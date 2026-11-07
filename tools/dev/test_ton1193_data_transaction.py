"""Real APFS/SQLite/lock tests; launchd absence is an explicit fixture seam."""
import fcntl
from contextlib import closing
import json
import os
from pathlib import Path
import plistlib
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from . import ton1175_switch as switch
from . import ton1193_data_transaction as data
from . import ton1193_receipt as receipt
from .ton1193_trust import directory_snapshot


class Crash(BaseException):
    pass


class DataTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.active = self.root / 'h/Coach/data'
        self.active.mkdir(parents=True, mode=0o700)
        self.ops = self.root / 'stage'
        self.ops.mkdir(mode=0o700)
        self.private = self.ops / 'snapshot'
        self.private.mkdir(mode=0o700)
        self.c = {'stateDir': str(self.ops), 'adapterSnapshotDir': str(self.private)}
        with closing(sqlite3.connect(self.active / 'coach.sqlite')) as db, db:
            db.executescript('''PRAGMA user_version=6;
                CREATE TABLE events(seq INTEGER PRIMARY KEY);
                INSERT INTO events VALUES(42);
                CREATE TABLE pairing_code(singleton INTEGER,code_sha256 TEXT,expires_at INTEGER,attempts INTEGER);
                INSERT INTO pairing_code VALUES(1,'synthetic',123,0);''')
        (self.active / 'server.lock').touch(mode=0o600)
        plist = plistlib.dumps({'Label': 'local.coach.ton1193.test',
                               'EnvironmentVariables': {'COACH_DATA_DIR': str(self.active)}})
        files = {name: plist if name.endswith('plist') else b'{}'
                 for name in ('old.plist', 'new.plist', 'old.json', 'new.json')}
        for name, raw in files.items():
            switch.atomic(self.ops / name, raw)
        state = json.dumps({'configHash': switch.digest(json.dumps(self.c, sort_keys=True).encode()),
                            'files': {name: switch.digest(raw) for name, raw in files.items()}}).encode()
        switch.atomic(self.ops / 'state.json', state)
        snap = json.dumps({'version': 1, 'stateHash': switch.digest(state),
                           'stable': {str(self.active): list(directory_snapshot(self.active))}}).encode()
        switch.atomic(self.private / 'snapshot.json', snap)
        switch.atomic(self.private / 'pin.json', json.dumps({
            'version': 1, 'privateId': directory_snapshot(self.private),
            'stateHash': switch.digest(state), 'snapshotHash': switch.digest(snap)}).encode())
        switch.atomic(self.ops / 'lock', b'')
        switch.set_journal(self.ops, 'switching')
        child = subprocess.Popen([sys.executable, '-c', 'pass'])
        child.wait()
        receipt.write(self.c, {'pid': child.pid, 'side': 'old', 'identity': [1, 'fixture'], 'image': sys.executable})
        self.lock = open(self.ops / 'lock', 'rb')
        self.addCleanup(self.lock.close)
        fcntl.flock(self.lock, fcntl.LOCK_EX)
        self.absent = patch.object(data, '_label_absent')
        self.absent.start()
        self.addCleanup(self.absent.stop)

    def cleanup(self):
        for root, dirs, files in os.walk(self.temp.name):
            os.chmod(root, 0o700)
            for name in files:
                os.chmod(Path(root) / name, 0o600)
        self.temp.cleanup()

    def capture(self):
        data.capture(self.c, 'closed-fixture-seal')
        value, journal = data.load(self.c)
        self.assertEqual(journal['phase'], 'snapshotted')
        return value

    def migrate(self, changed_pairing=False):
        with closing(sqlite3.connect(self.active / 'coach.sqlite')) as db, db:
            db.executescript('PRAGMA user_version=7; CREATE TABLE mock_sessions(id INTEGER); INSERT INTO mock_sessions VALUES(1); INSERT INTO events VALUES(43);')
            if changed_pairing:
                db.execute("UPDATE pairing_code SET code_sha256='changed'")
        switch.set_journal(self.ops, 'recovering')

    def assert_restored(self, value, changed=False):
        current, journal = data.load(self.c)
        self.assertEqual(data.counts(self.active), value['before'])
        self.assertEqual(journal['dataState'], 'restored')
        self.assertEqual(journal['candidateCounts']['userVersion'], 7)
        self.assertEqual(journal['candidateCounts']['mockSessions'], 1)
        self.assertEqual(journal['candidateEventDelta'], 1)
        self.assertEqual(journal['merge'], 'NOMERGE')
        self.assertEqual(journal['pairing'], 'PAIRING_CHANGED' if changed else 'PAIRING_UNCHANGED')
        quarantine = data._path(current, 'candidate')
        self.assertEqual(data.clone.fingerprint(quarantine), journal['candidateManifest'])
        self.assertEqual(quarantine.stat().st_mode & 0o222, 0)
        stable = {str(self.active): value['dataId']}
        self.assertEqual(data.stable_paths(self.c, stable)[str(self.active)], value['snapshotId'])

    def test_restore_keeps_candidate_writes_and_pairing_changed_never_merges(self):
        value = self.capture()
        self.migrate(changed_pairing=True)
        data.restore(self.c)
        self.assert_restored(value, changed=True)
        data.restore(self.c)

    def test_each_rename_crash_resumes_only_bound_layout(self):
        for cut, after in ((1, False), (1, True), (2, False), (2, True)):
            with self.subTest(rename=cut, after=after):
                # A distinct staged transaction for each crash point.
                fixture = DataTransactionTest('test_digest_mismatch_refuses_before_renames')
                fixture.setUp()
                try:
                    value = fixture.capture()
                    fixture.migrate()
                    rename, calls = data._rename, []
                    def crash(*args):
                        calls.append(args)
                        if len(calls) == cut and not after:
                            raise Crash()
                        rename(*args)
                        if len(calls) == cut and after:
                            raise Crash()
                    with patch.object(data, '_rename', side_effect=crash):
                        with self.assertRaises(Crash):
                            data.restore(fixture.c)
                    switch.set_journal(fixture.ops, 'recovery_required')
                    switch.set_journal(fixture.ops, 'recovering')
                    data.restore(fixture.c)
                    fixture.assert_restored(value)
                finally:
                    fixture.doCleanups()

    def test_digest_mismatch_refuses_before_renames(self):
        value = self.capture()
        self.migrate()
        with open(data._path(value, 'pre') / 'coach.sqlite', 'ab') as stream:
            stream.write(b'corrupt')
        with self.assertRaisesRegex(switch.GateError, 'manifest changed'):
            data.restore(self.c)
        self.assertTrue(self.active.exists())
        self.assertFalse(data._path(value, 'candidate').exists())

    def test_live_pid_and_kernel_lock_refuse_rename(self):
        value = self.capture()
        self.migrate()
        with patch.object(data.os, 'kill', return_value=None):
            with self.assertRaisesRegex(switch.GateError, 'process still exists'):
                data.restore(self.c)
        script = 'import fcntl,sys; f=open(sys.argv[1],"r+"); fcntl.lockf(f,fcntl.LOCK_EX); print("locked",flush=True); sys.stdin.read()'
        child = subprocess.Popen([sys.executable, '-c', script, str(self.active / 'server.lock')],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(child.stdout.readline().strip(), 'locked')
            with self.assertRaisesRegex(switch.GateError, 'lock still held'):
                data.restore(self.c)
            self.assertFalse(data._path(value, 'candidate').exists())
        finally:
            child.communicate('release')

    def test_occupied_label_refuses_mutation(self):
        value = self.capture()
        self.migrate()
        with patch.object(data, '_label_absent', side_effect=switch.GateError('label occupied')):
            with self.assertRaisesRegex(switch.GateError, 'label occupied'):
                data.restore(self.c)
        self.assertFalse(data._path(value, 'candidate').exists())

    def test_commit_retains_snapshot_and_prohibits_rollback(self):
        value = self.capture()
        self.migrate()
        switch.set_journal(self.ops, 'validating')
        data.commit(self.c)
        switch.set_journal(self.ops, 'recovering')
        with self.assertRaisesRegex(switch.GateError, 'forward repair'):
            data.restore(self.c)
        self.assertEqual(data.clone.fingerprint(data._path(value, 'pre')), value['manifest'])

    def test_unbound_identity_and_descriptor_changes_rejected(self):
        value = self.capture()
        old = self.active.with_name('unknown')
        os.rename(self.active, old)
        self.active.mkdir()
        with self.assertRaises(switch.GateError):
            data.restore(self.c)
        raw = json.loads((self.private / data.NAME).read_text())
        raw['snapshotId'] = directory_snapshot(self.active)
        switch.atomic(self.private / data.NAME, json.dumps(raw).encode())
        with self.assertRaisesRegex(switch.GateError, 'descriptor changed'):
            data.load(self.c)

    def test_new_schema_outside_data_stops_restore(self):
        value = self.capture()
        self.migrate()
        with closing(sqlite3.connect(self.active.parent / 'outside.sqlite')) as db, db:
            db.execute('CREATE TABLE unauthorized(id INTEGER)')
        with self.assertRaisesRegex(switch.GateError, 'EXTERNAL_STATE_CHANGED category=5'):
            data.restore(self.c)
        self.assertFalse(data._path(value, 'candidate').exists())

    def test_capture_merges_existing_journal_fields(self):
        switch.atomic(self.ops / 'journal.json', json.dumps({'phase': 'switching', 'proof': 'keep'}).encode())
        self.capture()
        self.assertEqual(data._journal(self.c)['proof'], 'keep')

    def test_legacy_descriptor_cannot_resume_under_new_inventory(self):
        value = self.capture()
        value['version'] = 1
        raw = data._json(value)
        switch.atomic(self.private / data.NAME, raw)
        journal = data._journal(self.c)
        journal['dataSnapshotHash'] = switch.digest(raw)
        switch.atomic(self.ops / 'journal.json', data._json(journal))
        with self.assertRaisesRegex(switch.GateError, 'invalid data snapshot binding'):
            data.load(self.c)

    def test_bound_usage_inventory_is_used_at_capture_and_restore(self):
        from .test_ton1193_usage_observation import SOURCE
        from . import ton1193_usage_schema as schema
        for name in ('old-source', 'new-source'):
            path = self.root / name / schema.SOURCE
            path.parent.mkdir(parents=True)
            path.write_text(SOURCE)
        proof = schema.attest(self.root / 'old-source', self.root / 'new-source', 'old', 'new')
        diagnostics = self.active.parent / 'state/diagnostics'
        diagnostics.mkdir(parents=True, mode=0o700)
        switch.atomic(diagnostics / 'usage.sqlite', b'synthetic diagnostic')
        import datetime
        summary = diagnostics.parent / ('usage-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d') + '.md')
        switch.atomic(summary, b'old summary')
        with patch.object(data.usage_schema, 'bound', return_value=proof) as bound:
            value = self.capture()
            self.migrate()
            switch.atomic(summary, b'new summary')
            data.restore(self.c)
            self.assertEqual(bound.call_count, 2)
        self.assert_restored(value)
        self.assertEqual(summary.read_bytes(), b'new summary')
        self.assertEqual(data._journal(self.c)['externalChecks']['restore']['usageDelta']['changedFiles'], 1)

    def test_pending_candidate_wal_is_quarantined_without_blocking_restore(self):
        value = self.capture()
        self.migrate()
        # Kill a real WAL writer: leave a committed, non-checkpointed WAL/shm.
        script = '''import os,sqlite3,sys
db=sqlite3.connect(sys.argv[1]); db.execute('PRAGMA journal_mode=WAL')
db.execute('INSERT INTO events VALUES(44)'); db.commit(); os._exit(0)
'''
        subprocess.run([sys.executable, '-c', script, str(self.active / 'coach.sqlite')], check=True)
        manifest = data.clone.fingerprint(self.active)
        wal = (self.active / 'coach.sqlite-wal').read_bytes()
        self.assertTrue(wal)
        data.restore(self.c)
        _, journal = data.load(self.c)
        self.assertEqual(journal['candidateCounts'], 'WAL_PENDING')
        self.assertEqual(data.counts(self.active), value['before'])
        self.assertEqual(journal['candidateManifest'], manifest)
        quarantine = data._path(value, 'candidate')
        self.assertEqual((quarantine / 'coach.sqlite-wal').read_bytes(), wal)
        self.assertTrue((quarantine / 'coach.sqlite-shm').exists())

    def test_regressed_candidate_is_still_restored(self):
        value = self.capture()
        self.migrate()
        with closing(sqlite3.connect(self.active / 'coach.sqlite')) as db, db:
            db.execute('DELETE FROM events')
        data.restore(self.c)
        self.assertEqual(data._journal(self.c)['candidateCounts'], 'CANDIDATE_REGRESSED')
        self.assertEqual(data.counts(self.active), value['before'])

    def test_external_violation_prevents_commit(self):
        self.capture()
        self.migrate()
        switch.set_journal(self.ops, 'validating')
        (self.active.parent / 'outside.json').write_text('{}')
        with self.assertRaisesRegex(switch.GateError, 'EXTERNAL_STATE_CHANGED category=5'):
            data.commit(self.c)
        self.assertEqual(data._journal(self.c)['dataState'], 'captured')


if __name__ == '__main__':
    unittest.main()
