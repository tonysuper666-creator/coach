import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock

from tools.dev import ton1175_switch as switch
from tools.dev import ton1193_snapshots as snapshots


class PersistentSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.ops = self.root / 'state'
        self.ops.mkdir(mode=0o700)
        self.private = self.root / 'private'
        self.private.mkdir(mode=0o700)
        self.cellar = self.root / 'cellar'
        self.node_root = self.root / 'node'
        self.cellar.mkdir()
        self.node_root.mkdir()
        self.config = {'stateDir': str(self.ops), 'plist': str(self.root / 'service.plist'),
                       'installation': str(self.root / 'installation.json')}
        self.payloads = {'old.plist': b'old plist', 'new.plist': b'new plist',
                         'old.json': b'old installation', 'new.json': b'new installation'}
        self.state = {'configHash': switch.digest(json.dumps(self.config, sort_keys=True).encode()),
                      'files': {k: switch.digest(v) for k, v in self.payloads.items()},
                      'plistMode': 0o600, 'installationMode': 0o600}
        for name, value in self.payloads.items():
            switch.atomic(self.ops / name, value)
        switch.atomic(self.ops / 'state.json', json.dumps(self.state).encode())
        switch.atomic(Path(self.config['plist']), self.payloads['old.plist'])
        switch.atomic(Path(self.config['installation']), self.payloads['old.json'])
        switch.set_journal(self.ops, 'switching')
        self.sides = {}
        for side in ('old', 'new'):
            release = self.root / (side + '-release')
            bin_dir = release / 'tools/bridge/.venv/bin'
            bin_dir.mkdir(parents=True)
            framework = self.cellar / side / 'Frameworks/Python.framework/Versions/3.15'
            (framework / 'bin').mkdir(parents=True)
            python = framework / 'bin/python3.15'
            shutil.copy2(Path(sys.executable).resolve(), python)
            stub = framework / 'Resources/Python.app/Contents/MacOS/Python'
            stub.parent.mkdir(parents=True)
            shutil.copy2(Path(sys.executable).resolve(), stub)
            (bin_dir / 'python3.15').symlink_to(python)
            (bin_dir / 'python').symlink_to('python3.15')
            java = self.cellar / side / 'java'
            node = self.node_root / side / 'node'
            node.parent.mkdir(parents=True)
            shutil.copy2(Path(sys.executable).resolve(), java)
            shutil.copy2(Path(sys.executable).resolve(), node)
            self.sides[side] = {'release': str(release), 'java': str(java), 'node': str(node)}
        self.roots = {'cellar': str(self.cellar), 'node': str(self.node_root)}
        self.private_id, self.pin = snapshots.capture(self.config, str(self.private), [str(self.root)],
                                                       self.sides, self.roots)

    def check(self, action):
        return snapshots.revalidate(self.config, str(self.private), self.private_id, self.pin, action)

    def set_new(self):
        switch.atomic(Path(self.config['plist']), self.payloads['new.plist'])
        switch.atomic(Path(self.config['installation']), self.payloads['new.json'])

    def test_both_sides_persist_and_match_real_files_across_atomic_writes(self):
        self.assertEqual(self.check('stop')[0], 'old')
        stored = json.loads((self.private / 'snapshot.json').read_text())
        self.assertEqual(set(stored['sides']), {'old', 'new'})
        for side in ('old', 'new'):
            self.assertEqual(len(stored['sides'][side]['python'][2][3]), 64)
            self.assertIsNotNone(stored['sides'][side]['stub'])
        self.set_new()
        self.assertEqual(self.check('start')[0], 'new')
        calls = []
        with mock.patch.object(switch, 'command', side_effect=lambda c, action: calls.append(self.check(action)[0])):
            switch.restore(self.config, self.ops, self.state)
        self.assertEqual(calls, [None, 'old'])
        self.assertEqual(json.loads((self.ops / 'journal.json').read_text())['phase'], 'rolled_back')

    def test_real_old_interpreter_tampering_rejects_recovery_start(self):
        self.set_new()
        calls = []
        def run_command(config, action):
            calls.append(action)
            result = self.check(action)
            if action == 'stop':
                target = Path(self.sides['old']['release']) / 'tools/bridge/.venv/bin/python'
                with target.open('ab') as stream:
                    stream.write(b'tampered')
            return result
        with mock.patch.object(switch, 'command', side_effect=run_command):
            with self.assertRaisesRegex(switch.GateError, 'old toolchain changed'):
                switch.restore(self.config, self.ops, self.state)
        self.assertEqual(calls, ['stop', 'start'])
        self.assertEqual(json.loads((self.ops / 'journal.json').read_text())['phase'], 'recovery_required')

    def test_recovery_rejects_replaced_data_directory_even_with_identical_content(self):
        data = self.root / 'data'
        data.mkdir()
        (data / 'coach.sqlite').write_bytes(b'isolated fixture')
        saved = self.root / 'data.pre-fixture'
        shutil.copytree(data, saved)
        private = self.root / 'private-data'
        private.mkdir(mode=0o700)
        identity, pin = snapshots.capture(self.config, str(private), [str(self.root), str(data)],
                                          self.sides, self.roots)
        os.rename(data, self.root / 'data.candidate-fixture')
        os.rename(saved, data)
        switch.set_journal(self.ops, 'recovering')
        with self.assertRaisesRegex(switch.GateError, 'stable directory changed'):
            snapshots.revalidate(self.config, str(private), identity, pin, 'start')

    def test_toolchain_replacement_leaf_stub_and_snapshot_tampering_rejected(self):
        node = Path(self.sides['new']['node'])
        replacement = node.with_name('replacement')
        shutil.copy2(node, replacement)
        os.replace(replacement, node)
        with self.assertRaisesRegex(switch.GateError, 'new toolchain changed'):
            self.check('stop')
        # A saved snapshot cannot be silently replaced even by a valid private file.
        data = json.loads((self.private / 'snapshot.json').read_text())
        data['sides']['new']['node'][2] = node.stat().st_ino
        switch.atomic(self.private / 'snapshot.json', json.dumps(data).encode())
        with self.assertRaisesRegex(switch.GateError, 'private snapshot changed'):
            self.check('stop')

    def test_changed_old_leaf_and_stub_rejected(self):
        leaf = Path(self.sides['old']['release']) / 'tools/bridge/.venv/bin/python'
        leaf.unlink()
        leaf.symlink_to('python3.15')
        with self.assertRaisesRegex(switch.GateError, 'old toolchain changed'):
            self.check('stop')

    def test_changed_stub_rejected(self):
        data = json.loads((self.private / 'snapshot.json').read_text())
        stub = Path(data['sides']['old']['stub'][0])
        with stub.open('ab') as stream:
            stream.write(b'tampered')
        with self.assertRaisesRegex(switch.GateError, 'old toolchain changed'):
            self.check('stop')

    def test_state_mutation_and_private_permissions_rejected(self):
        with self.assertRaises(switch.GateError):
            snapshots.capture(self.config, str(self.private), [str(self.root)], self.sides, self.roots)
        os.chmod(self.private, 0o755)
        with self.assertRaises(switch.GateError):
            self.check('stop')
        os.chmod(self.private, 0o700)
        os.chmod(self.private / 'snapshot.json', 0o644)
        with self.assertRaises(switch.GateError):
            self.check('stop')
        os.chmod(self.private / 'snapshot.json', 0o600)
        switch.atomic(self.ops / 'old.plist', b'changed')
        with self.assertRaises(switch.GateError):
            self.check('stop')

    def test_snapshot_symlink_and_hardlink_rejected(self):
        path = self.private / 'snapshot.json'
        copy = self.root / 'snapshot-copy'
        shutil.copy2(path, copy)
        path.unlink()
        path.symlink_to(copy)
        with self.assertRaises(switch.GateError):
            self.check('stop')
        path.unlink()
        os.link(copy, path)
        with self.assertRaises(switch.GateError):
            self.check('stop')


if __name__ == '__main__':
    unittest.main()
