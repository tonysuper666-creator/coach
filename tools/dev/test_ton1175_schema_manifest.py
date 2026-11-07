import contextlib
import datetime
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from . import ton1175_verify as verify
from . import ton1193_external_state as external
from . import ton1193_usage_schema as schema
from . import ton1193_data_transaction as data
from . import ton1193_receipt as receipt
from . import test_ton1193_data_transaction as transaction_tests
from . import ton1175_switch as switch
from .ton1175_switch import GateError, digest

SOURCE = '''s.execute("PRAGMA journal_mode=WAL")
s.execute("CREATE TABLE IF NOT EXISTS usage(device TEXT NOT NULL)")
'''


class SchemaManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.git('init', '-q')
        self.write('.gitignore', 'server/app/build/\n')
        self.write(schema.SOURCE, SOURCE)
        heavy = self.write('tools/dev/heavy', '#!/bin/sh\nexit 0\n')
        heavy.chmod(0o755)
        self.commit('old')
        self.old = self.git('rev-parse', 'HEAD')
        self.write('other', 'candidate')
        self.commit('candidate')
        self.head = self.git('rev-parse', 'HEAD')
        built = self.write('server/app/build/install/app/bin/app', '#!/bin/sh\n')
        built.chmod(0o755)
        self.release = self.root / 'release'
        (self.release / 'app/bin').mkdir(parents=True)
        app = self.release / 'app/bin/app'
        app.write_bytes(built.read_bytes())
        app.chmod(0o755)
        (self.release / 'source-head.txt').write_text(self.head + '\n')
        self.java = self.root / 'java'
        self.java.write_text('#!/bin/sh\necho "openjdk 17.0.0"\n')
        self.java.chmod(0o755)
        self.manifest = self.root / 'manifest.json'

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True).strip()

    def write(self, name, text):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def commit(self, message):
        self.git('add', '-A')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '-qm', message)

    def make_manifest(self):
        # Only dependency installation is replaced; Git, build command, source-head,
        # tree hashes, executable check, Java check, file output and binding are real.
        with patch.object(verify, 'dependencies', return_value={}), contextlib.redirect_stdout(io.StringIO()):
            verify.attest(self.repo, self.release, str(self.java), str(self.java), self.manifest,
                          usage_old_head=self.old)
        raw = self.manifest.read_bytes()
        config = dict(adapterManifest=str(self.manifest), adapterManifestSha256=digest(raw),
                      expectedSourceHead=self.old, candidateHead=self.head)
        return json.loads(raw), config

    def test_build_manifest_is_bound_and_tampering_or_head_change_refuses(self):
        manifest, config = self.make_manifest()
        proof = schema.bound(config)
        self.assertEqual(manifest['sourceHead'], self.head)
        self.assertTrue(schema.enabled(proof))
        self.assertEqual(proof['old']['head'], self.old)
        self.assertEqual(proof['new']['head'], self.head)
        self.assertEqual(proof['old']['sourceSha256'], digest(SOURCE.encode()))
        config['candidateHead'] = '0' * 40
        with self.assertRaisesRegex(GateError, 'revisions differ'):
            schema.bound(config)
        config['candidateHead'] = self.head
        self.manifest.write_bytes(self.manifest.read_bytes() + b' ')
        with self.assertRaisesRegex(GateError, 'manifest changed'):
            schema.bound(config)

    def test_changed_or_missing_git_source_remains_category_five(self):
        self.write(schema.SOURCE, SOURCE.replace('TEXT', 'BLOB'))
        self.commit('different schema')
        different = self.git('rev-parse', 'HEAD')
        (self.repo / schema.SOURCE).unlink()
        self.commit('missing schema')
        missing = self.git('rev-parse', 'HEAD')
        data = self.root / 'home/Coach/data'
        data.mkdir(parents=True)
        path = data.parent / 'state/usage-20261107.md'
        path.parent.mkdir()
        for candidate in (different, missing):
            with self.subTest(candidate=candidate):
                proof = schema.attest_revisions(self.repo, self.old, candidate)
                self.assertFalse(schema.enabled(proof))
                path.write_bytes(b'before')
                before = external.inventory(data, proof)
                path.write_bytes(b'after')
                with self.assertRaisesRegex(GateError, 'category=5'):
                    external.compare(before, external.inventory(data, proof))
        with self.assertRaisesRegex(GateError, 'full source commit'):
            schema.attest_revisions(self.repo, self.old[:7], self.head)
        with self.assertRaisesRegex(GateError, 'source commit missing'):
            schema.attest_revisions(self.repo, '0' * 40, self.head)

    def test_nontemporary_write_refused_before_build(self):
        with patch.object(verify, 'captured', side_effect=AssertionError('must refuse before command')):
            with self.assertRaisesRegex(GateError, 'defaults to temporary'):
                verify.attest(self.repo, '/Users/production/release', 'node', 'java',
                              '/Users/production/manifest', usage_old_head=self.old)

    def test_generated_manifest_binds_capture_restore_and_commit(self):
        _, config = self.make_manifest()
        for action in ('restore', 'commit', 'tamper'):
            with self.subTest(action=action):
                fixture = transaction_tests.DataTransactionTest('test_commit_retains_snapshot_and_prohibits_rollback')
                fixture.setUp()
                try:
                    fixture.c.update(config)
                    state = json.loads((fixture.ops / 'state.json').read_bytes())
                    state['configHash'] = digest(json.dumps(fixture.c, sort_keys=True).encode())
                    raw_state = json.dumps(state).encode()
                    switch.atomic(fixture.ops / 'state.json', raw_state)
                    snap = json.loads((fixture.private / 'snapshot.json').read_bytes())
                    snap['stateHash'] = digest(raw_state)
                    raw_snap = json.dumps(snap).encode()
                    switch.atomic(fixture.private / 'snapshot.json', raw_snap)
                    pin = json.loads((fixture.private / 'pin.json').read_bytes())
                    pin.update(stateHash=digest(raw_state), snapshotHash=digest(raw_snap))
                    switch.atomic(fixture.private / 'pin.json', json.dumps(pin).encode())
                    (fixture.private / receipt.NAME).unlink()
                    child = subprocess.Popen([os.sys.executable, '-c', 'pass'])
                    child.wait()
                    receipt.write(fixture.c, {'pid': child.pid, 'side': 'old',
                                             'identity': [1, 'fixture'], 'image': os.sys.executable})
                    diagnostics = fixture.active.parent / 'state/diagnostics'
                    diagnostics.mkdir(parents=True, mode=0o700)
                    switch.atomic(diagnostics / 'usage.sqlite', b'synthetic')
                    today = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d')
                    summary = diagnostics.parent / ('usage-' + today + '.md')
                    switch.atomic(summary, b'before')
                    value = fixture.capture()
                    fixture.migrate()
                    switch.atomic(summary, b'after')
                    if action == 'tamper':
                        self.manifest.write_bytes(self.manifest.read_bytes() + b' ')
                        with self.assertRaisesRegex(GateError, 'manifest changed'):
                            data.restore(fixture.c)
                        self.assertFalse(data._path(value, 'candidate').exists())
                    elif action == 'restore':
                        data.restore(fixture.c)
                        fixture.assert_restored(value)
                        self.assertEqual(summary.read_bytes(), b'after')
                        self.assertEqual(data._journal(fixture.c)['externalChecks']['restore']
                                         ['usageDelta']['changedFiles'], 1)
                    else:
                        switch.set_journal(fixture.ops, 'validating')
                        data.commit(fixture.c)
                        self.assertEqual(data.load(fixture.c)[1]['dataState'], 'committed')
                finally:
                    fixture.doCleanups()


if __name__ == '__main__':
    unittest.main()
