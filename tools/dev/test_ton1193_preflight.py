import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from tools.dev import ton1175_switch as switch
from tools.dev import ton1175_verify as verify
from tools.dev import ton1193_preflight as preflight


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name).resolve()
        self.cellar = self.home / 'cellar'
        self.node_root = self.home / 'nodes'
        self.releases = self.home / 'Coach/service/releases'
        self.ops = self.home / 'stage'
        self.private = self.ops / 'snapshot'
        for path in (self.cellar, self.node_root, self.releases, self.ops, self.private,
                     *(self.home / ('Coach/' + x) for x in ('data', 'logs', 'run', 'bridge'))):
            path.mkdir(parents=True, mode=0o700)
        self.roots = {'home': str(self.home), 'cellar': str(self.cellar),
                      'node': str(self.node_root), 'releases': str(self.releases)}
        java = self.cellar / 'jdk/bin/java'
        python = self.cellar / 'python/bin/python3.15'
        node = self.node_root / 'v22/bin/node'
        for path in (java, python, node):
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(Path(sys.executable).resolve(), path)
        self.targets = {'java': java, 'python': python, 'node': node}
        self.plist = self.home / 'service.plist'
        self.installation = self.home / 'installation.json'
        self.manifest = self.home / 'manifest.json'
        self.old_head, self.new_head = 'a' * 40, 'b' * 40
        self.sides = {}
        for name, head in (('old', self.old_head), ('new', self.new_head)):
            release = self.releases / name
            for relative in ('app/bin', 'tools/bridge/.venv/bin', 'tools/mathsvc/src'):
                (release / relative).mkdir(parents=True)
            app = release / 'app/bin/app'
            app.write_text('#!/bin/sh\nexit 0\n')
            app.chmod(0o700)
            (release / 'tools/mathsvc/src/server.mjs').write_text('// fixture\n')
            (release / 'tools/bridge/.venv/bin/python').symlink_to(python)
            env = {'COACH_SERVICE_ROOT': str(release), 'COACH_PYTHON': str(release / 'tools/bridge/.venv/bin/python'),
                   'COACH_MATHSVC': str(release / 'tools/mathsvc/src/server.mjs'),
                   'JAVA_HOME': str(java.parent.parent), 'COACH_NODE': str(node), 'HOME': str(self.home),
                   'COACH_DATA_DIR': str(self.home / 'Coach/data'),
                   'COACH_LOG_DIR': str(self.home / 'Coach/logs'),
                   'COACH_RUN_DIR': str(self.home / 'Coach/run'),
                   'COACH_BRIDGE_HOME': str(self.home / 'Coach/bridge')}
            self.sides[name] = {'release': release, 'info': {'root': str(release), 'sourceHead': head},
                                'plist': {'ProgramArguments': [str(app)], 'WorkingDirectory': str(release),
                                          'EnvironmentVariables': env}}
        external = {str(path): switch.file_hash(path) for path in self.targets.values()}
        proof = {'version': 1, 'sourceHead': self.new_head, 'root': str(self.sides['new']['release']),
                 'node': str(node), 'java': str(java), 'treeSha256': switch.tree_hash(self.sides['new']['release']),
                 'externalExecutables': external}
        switch.atomic(self.manifest, json.dumps(proof).encode())
        self.external = external
        self.config = {'stateDir': str(self.ops), 'plist': str(self.plist), 'installation': str(self.installation),
                       'release': str(self.sides['new']['release']), 'candidateHead': self.new_head,
                       'expectedSourceHead': self.old_head, 'adapterManifest': str(self.manifest),
                       'adapterManifestSha256': switch.file_hash(self.manifest), 'adapterSnapshotDir': str(self.private)}
        materials = {}
        for name in ('old', 'new'):
            materials[name + '.plist'] = plistlib.dumps(self.sides[name]['plist'])
            materials[name + '.json'] = json.dumps(self.sides[name]['info']).encode()
        self.state = {'version': 2, 'configHash': switch.digest(json.dumps(self.config, sort_keys=True).encode()),
                      'candidateTreeSha256': proof['treeSha256'],
                      'files': {name: switch.digest(data) for name, data in materials.items()},
                      'plistMode': 0o600, 'installationMode': 0o600}
        for name, data in materials.items():
            switch.atomic(self.ops / name, data)
        switch.atomic(self.ops / 'state.json', json.dumps(self.state).encode())
        switch.atomic(self.plist, materials['old.plist'])
        switch.atomic(self.installation, materials['old.json'])
        switch.set_journal(self.ops, 'staged')
        self.materials = materials
        # Keep candidate() itself real; dependency execution needs separately built MathJax.
        self.dependencies = mock.patch.object(verify, 'dependencies', return_value={
            str(python): external[str(python)], str(node): external[str(node)]})
        self.dependencies.start()
        self.addCleanup(self.dependencies.stop)

    def capture(self):
        return preflight._preflight(self.config, self.roots)

    def revalidate(self, action):
        return preflight._revalidate(self.config, action, self.roots)

    def test_stage_candidate_snapshot_and_persistent_pin_revalidate(self):
        with mock.patch.object(verify, 'candidate', wraps=verify.candidate) as call:
            pin = self.capture()
        call.assert_called_once_with(str(self.manifest), self.config['adapterManifestSha256'],
                                     self.config['release'], self.new_head, str(self.ops / 'new.plist'))
        stored = json.loads((self.private / 'pin.json').read_text())
        self.assertEqual(stored['snapshotHash'], pin)
        switch.set_journal(self.ops, 'switching')
        self.assertEqual(self.revalidate('stop')[0], 'old')
        result = subprocess.run([sys.executable, '-B', '-c',
                                 'import json,sys; from tools.dev.ton1193_preflight import _revalidate; '
                                 'print(_revalidate(json.loads(sys.argv[1]), "stop", json.loads(sys.argv[2]))[0])',
                                 json.dumps(self.config), json.dumps(self.roots)],
                                capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.strip(), 'old')
        switch.atomic(self.plist, self.materials['new.plist'])
        switch.atomic(self.installation, self.materials['new.json'])
        self.assertEqual(self.revalidate('start')[0], 'new')

    def test_modified_stage_or_current_pointer_rejected(self):
        switch.atomic(self.plist, self.materials['new.plist'])
        with self.assertRaisesRegex(switch.GateError, 'preflight pointer'):
            self.capture()
        switch.atomic(self.plist, self.materials['old.plist'])
        switch.atomic(self.ops / 'new.plist', b'changed')
        with self.assertRaises(switch.GateError):
            self.capture()

    def test_candidate_rejects_toolchain_changed_before_capture(self):
        with self.targets['python'].open('ab') as stream:
            stream.write(b'tamper')
        with self.assertRaisesRegex(switch.GateError, 'external interpreter changed'):
            self.capture()

    def test_pin_and_toolchain_changes_rejected_after_capture(self):
        self.capture()
        switch.set_journal(self.ops, 'switching')
        pin_path = self.private / 'pin.json'
        pin = json.loads(pin_path.read_text())
        pin['snapshotHash'] = '0' * 64
        switch.atomic(pin_path, json.dumps(pin).encode())
        with self.assertRaisesRegex(switch.GateError, 'private snapshot changed'):
            self.revalidate('stop')
        pin['snapshotHash'] = switch.file_hash(self.private / 'snapshot.json')
        switch.atomic(pin_path, json.dumps(pin).encode())
        with self.targets['java'].open('ab') as stream:
            stream.write(b'tamper')
        with self.assertRaisesRegex(switch.GateError, 'toolchain changed'):
            self.revalidate('stop')

    def test_manifest_pin_and_release_description_enforced(self):
        switch.atomic(self.manifest, b'{}')
        with self.assertRaisesRegex(switch.GateError, 'build evidence changed'):
            self.capture()

    def test_fixture_roots_cannot_enter_production_wrapper_or_revalidate(self):
        self.capture()
        switch.set_journal(self.ops, 'switching')
        with self.assertRaisesRegex(switch.GateError, 'production control paths are fixed'):
            preflight.revalidate(self.config, 'stop')
        with self.assertRaisesRegex(switch.GateError, 'snapshot trusted roots differ'):
            preflight._revalidate(self.config, 'stop', dict(self.roots, node=str(self.home)))

    def test_pointer_tamper_after_capture_rejected(self):
        self.capture()
        switch.set_journal(self.ops, 'switching')
        switch.atomic(self.installation, b'unknown')
        with self.assertRaisesRegex(switch.GateError, 'unknown pointer contents'):
            self.revalidate('stop')

    def test_inconsistent_staged_release_rejected_even_with_recomputed_stage_hash(self):
        bad = dict(self.sides['new']['plist'])
        bad['EnvironmentVariables'] = dict(bad['EnvironmentVariables'], COACH_PYTHON='/wrong/python')
        data = plistlib.dumps(bad)
        switch.atomic(self.ops / 'new.plist', data)
        self.state['files']['new.plist'] = switch.digest(data)
        switch.atomic(self.ops / 'state.json', json.dumps(self.state).encode())
        with self.assertRaisesRegex(switch.GateError, 'staged release descriptions disagree'):
            self.capture()


if __name__ == '__main__':
    unittest.main()
