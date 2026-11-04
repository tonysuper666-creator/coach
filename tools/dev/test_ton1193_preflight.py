import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

from tools.dev import ton1175_switch as switch
from tools.dev import ton1175_verify as verify
from tools.dev import ton1193_preflight as preflight
from tools.dev import ton1193_trust as trust


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
        log = self.home / 'Coach/logs/bootstrap.log'
        log.write_text('fixture')
        log.chmod(0o600)
        self.env_pins = {'BRIDGE_LOG_STDOUT': '1', 'BRIDGE_PORT': '19001',
                         'COACH_MANAGE_SERVICES': '1', 'COACH_PORT': '19002',
                         'COACH_TAIL_HOST': '127.0.0.1', 'PATH': str(self.home)}
        self.plist_pins = {'ExitTimeOut': 20, 'KeepAlive': True, 'RunAtLoad': True,
                           'StandardErrorPath': str(log), 'StandardOutPath': str(log),
                           'ThrottleInterval': 5, 'Umask': 63}
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
            env.update(self.env_pins)
            self.sides[name] = {'release': release, 'info': {'root': str(release), 'sourceHead': head},
                                'plist': {'ProgramArguments': [str(app)], 'WorkingDirectory': str(release),
                                          'EnvironmentVariables': env, 'Label': 'local.coach.server',
                                          **self.plist_pins}}
        external = {str(path): switch.file_hash(path) for path in self.targets.values()}
        proof = {'version': 1, 'sourceHead': self.new_head, 'root': str(self.sides['new']['release']),
                 'node': str(node), 'java': str(java), 'treeSha256': switch.tree_hash(self.sides['new']['release']),
                 'externalExecutables': external}
        switch.atomic(self.manifest, json.dumps(proof).encode())
        self.external = external
        self.config = {'stateDir': str(self.ops), 'plist': str(self.plist), 'installation': str(self.installation),
                       'release': str(self.sides['new']['release']), 'candidateHead': self.new_head,
                       'expectedSourceHead': self.old_head, 'adapterManifest': str(self.manifest),
                       'adapterManifestSha256': switch.file_hash(self.manifest), 'adapterSnapshotDir': str(self.private),
                       'adapterEnvPins': self.env_pins, 'adapterPlistPins': self.plist_pins}
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
        self.protocol = mock.patch.object(preflight, 'capability', return_value={'coverageProtocol': 'v1'})
        self.protocol.start()
        self.addCleanup(self.protocol.stop)

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

    def test_post_start_binding_uses_journal_side_and_before_inodes(self):
        self.capture()
        for phase, side in (('switching', 'new'), ('recovering', 'old')):
            with self.subTest(phase=phase):
                switch.set_journal(self.ops, phase)
                switch.atomic(self.plist, self.materials[side + '.plist'])
                switch.atomic(self.installation, self.materials[side + '.json'])
                before = self.revalidate('start')
                binding = mock.Mock(return_value={'pid': 42})
                result = preflight._check_started(self.config, before, self.roots, 42, binding)
                self.assertEqual(result, {'pid': 42})
                self.assertEqual(binding.call_args.args[1]['release'], str(self.sides[side]['release']))
                self.assertEqual(binding.call_args.args[2], {'cellar': str(self.cellar), 'node': str(self.node_root)})

    def test_post_start_pointer_inode_replacement_rejected_even_with_same_contents(self):
        self.capture()
        switch.set_journal(self.ops, 'switching')
        switch.atomic(self.plist, self.materials['new.plist'])
        switch.atomic(self.installation, self.materials['new.json'])
        before = self.revalidate('start')
        def replace(*args):
            switch.atomic(self.plist, self.materials['new.plist'])
            return {}
        with self.assertRaisesRegex(switch.GateError, 'pointer identity changed'):
            preflight._check_started(self.config, before, self.roots, 42, replace)

    def test_post_start_private_snapshot_replacement_rejected(self):
        self.capture()
        switch.set_journal(self.ops, 'switching')
        switch.atomic(self.plist, self.materials['new.plist'])
        switch.atomic(self.installation, self.materials['new.json'])
        before = self.revalidate('start')
        def replace(*args):
            path = self.private / 'snapshot.json'
            switch.atomic(path, path.read_bytes())
            return {}
        with self.assertRaisesRegex(switch.GateError, 'private snapshot changed during process binding'):
            preflight._check_started(self.config, before, self.roots, 42, replace)

    def test_stop_binding_follows_phase_and_pinned_sides(self):
        self.capture()
        for phase, expected in (('switching', 'old'), ('recovering', None)):
            with self.subTest(phase=phase):
                switch.set_journal(self.ops, phase)
                if phase == 'recovering':
                    switch.atomic(self.plist, self.materials['new.plist'])
                before = self.revalidate('stop')
                binding = mock.Mock(return_value={'pid': 42})
                self.assertEqual(preflight._check_stopping(self.config, before, self.roots, 42, binding),
                                 {'pid': 42})
                _, sides, roots, selected = binding.call_args.args
                self.assertEqual(selected, expected)
                self.assertEqual(sides['old']['release'], str(self.sides['old']['release']))
                self.assertEqual(sides['new']['release'], str(self.sides['new']['release']))
                self.assertEqual(roots, {'cellar': str(self.cellar), 'node': str(self.node_root)})

    def test_stop_binding_rejects_pointer_or_pin_replacement(self):
        self.capture()
        switch.set_journal(self.ops, 'switching')
        before = self.revalidate('stop')
        def replace_pointer(*args):
            switch.atomic(self.plist, self.materials['old.plist'])
            return {}
        with self.assertRaisesRegex(switch.GateError, 'pointer identity changed'):
            preflight._check_stopping(self.config, before, self.roots, 42, replace_pointer)
        before = self.revalidate('stop')
        def replace_pin(*args):
            path = self.private / 'pin.json'
            switch.atomic(path, path.read_bytes())
            return {}
        with self.assertRaisesRegex(switch.GateError, 'private snapshot changed during stop binding'):
            preflight._check_stopping(self.config, before, self.roots, 42, replace_pin)

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

    def test_exact_plist_and_environment_keys_and_values(self):
        import copy
        good = self.sides['new']['plist']
        for where, key, operation in (('plist', 'Unexpected', 'add'), ('plist', 'RunAtLoad', 'remove'),
                                      ('env', 'UNEXPECTED', 'add'), ('env', 'COACH_PORT', 'remove'),
                                      ('env', 'COACH_PORT', 'change'), ('plist', 'KeepAlive', 'change'),
                                      ('plist', 'Label', 'change')):
            with self.subTest(where=where, key=key, operation=operation):
                bad = copy.deepcopy(good)
                target = bad if where == 'plist' else bad['EnvironmentVariables']
                if operation == 'remove':
                    del target[key]
                elif operation == 'add':
                    target[key] = 'unexpected'
                else:
                    target[key] = False if key == 'KeepAlive' else 'wrong'
                with self.assertRaises(switch.GateError):
                    preflight._whitelist(bad, self.config, self.roots)

        for key in preflight.ENV_PINS:
            with self.subTest(pin=key):
                bad = copy.deepcopy(good)
                bad['EnvironmentVariables'][key] += '-tampered'
                with self.assertRaisesRegex(switch.GateError, 'environment pins differ'):
                    preflight._whitelist(bad, self.config, self.roots)

    def test_preflight_rejects_rehashed_staged_extra_environment(self):
        bad = dict(self.sides['new']['plist'])
        bad['EnvironmentVariables'] = dict(bad['EnvironmentVariables'], INJECTED='1')
        data = plistlib.dumps(bad)
        switch.atomic(self.ops / 'new.plist', data)
        self.state['files']['new.plist'] = switch.digest(data)
        switch.atomic(self.ops / 'state.json', json.dumps(self.state).encode())
        with mock.patch.object(preflight, 'capability') as cap:
            with self.assertRaisesRegex(switch.GateError, 'environment keys'):
                self.capture()
        cap.assert_not_called()

    def test_path_symlink_and_world_write_rejected_group_write_reported(self):
        path = self.home / 'pathdir'
        path.mkdir(mode=0o700)
        self.env_pins['PATH'] = str(path)
        plist = self.sides['new']['plist']
        plist['EnvironmentVariables']['PATH'] = str(path)
        path.chmod(0o770)
        with self.assertWarnsRegex(UserWarning, 'group-writable'):
            preflight._whitelist(plist, self.config, self.roots)
        path.chmod(0o707)
        with self.assertRaisesRegex(switch.GateError, 'unsafe PATH'):
            preflight._whitelist(plist, self.config, self.roots)
        link = self.home / 'pathlink'
        link.symlink_to(path)
        self.env_pins['PATH'] = str(link)
        plist['EnvironmentVariables']['PATH'] = str(link)
        with self.assertRaises(switch.GateError):
            preflight._whitelist(plist, self.config, self.roots)

    def test_absent_protocol_rejects_before_candidate_or_snapshot(self):
        with mock.patch.object(preflight, 'capability', return_value={'coverageProtocol': 'absent'}) as cap, \
             mock.patch.object(verify, 'candidate') as candidate, \
             mock.patch.object(preflight.snapshots, 'capture') as capture:
            with self.assertRaisesRegex(switch.GateError, 'legacy-protocol-absent'):
                self.capture()
        cap.assert_called_once_with(Path(preflight.__file__).resolve().parents[2],
                                    str(self.sides['old']['release']), self.old_head)
        candidate.assert_not_called()
        capture.assert_not_called()
        self.assertEqual(list(self.private.iterdir()), [])

    def test_real_old_ancestry_and_jar_refuse_before_any_snapshot(self):
        checkout = Path(preflight.__file__).resolve().parents[2]
        old = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', '5ef9989'], text=True).strip()
        lib = self.sides['old']['release'] / 'app/lib'
        lib.mkdir()
        with zipfile.ZipFile(lib / 'fixture.jar', 'w') as jar:
            jar.writestr('fixture.class', b'synthetic')
        self.config['expectedSourceHead'] = old
        data = json.dumps({'root': str(self.sides['old']['release']), 'sourceHead': old}).encode()
        switch.atomic(self.ops / 'old.json', data)
        switch.atomic(self.installation, data)
        self.state['files']['old.json'] = switch.digest(data)
        self.state['configHash'] = switch.digest(json.dumps(self.config, sort_keys=True).encode())
        switch.atomic(self.ops / 'state.json', json.dumps(self.state).encode())
        with mock.patch.object(preflight, 'capability', wraps=trust.capability) as cap, \
             mock.patch.object(verify, 'candidate') as candidate:
            with self.assertRaisesRegex(switch.GateError, 'legacy-protocol-absent'):
                self.capture()
        cap.assert_called_once()
        candidate.assert_not_called()
        self.assertEqual(list(self.private.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
