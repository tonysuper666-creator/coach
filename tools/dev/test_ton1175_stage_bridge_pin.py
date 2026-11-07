import json
import os
from pathlib import Path
import plistlib
import tempfile
import unittest

from tools.dev import ton1175_switch as switch
from tools.dev import ton1175_verify as verify


class StagedBridgePinTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=Path.cwd())
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.state_dir = self.root / 'state'
        self.state_dir.mkdir(mode=0o700)
        self.plist, self.installation = self.root / 'service.plist', self.root / 'installation.json'
        self.config_file = self.root / 'controller.json'
        self.config = {'stateDir': str(self.state_dir), 'plist': str(self.plist),
                       'installation': str(self.installation), 'adapterEnvPins': {
                           'BRIDGE_PORT': '41500', 'COACH_MANAGE_SERVICES': '1'},
                       'expectedSourceHead': verify.HEALTH_LOOP_OLD_HEAD}
        self.config_hash = switch.digest(json.dumps(self.config, sort_keys=True).encode())
        old_plist = plistlib.dumps({'EnvironmentVariables': self.config['adapterEnvPins']})
        files = {'old.plist': old_plist, 'new.plist': b'new plist',
                 'old.json': json.dumps({'sourceHead': verify.HEALTH_LOOP_OLD_HEAD}).encode(),
                 'new.json': b'new installation'}
        state = {'configHash': self.config_hash, 'plistMode': 0o600, 'installationMode': 0o600,
                 'files': {name: switch.digest(data) for name, data in files.items()}}
        for name, data in files.items():
            switch.atomic(self.state_dir / name, data)
        switch.atomic(self.state_dir / 'state.json', json.dumps(state).encode())
        switch.atomic(self.config_file, json.dumps(self.config).encode())
        switch.atomic(self.plist, old_plist)
        switch.atomic(self.installation, files['old.json'])

    def pin(self, expected=None):
        return verify.staged_bridge_port(self.config_file, str(self.installation),
                                         expected if expected is not None else self.config_hash)

    def test_bound_pin_and_wrong_transaction_digest(self):
        with self.pin() as port:
            self.assertEqual(port, 41500)
        with self.assertRaises(switch.GateError):
            with self.pin('0' * 64):
                pass

    def test_rejects_changed_stage_and_pointer(self):
        switch.atomic(self.state_dir / 'new.json', b'tampered')
        with self.assertRaises(switch.GateError):
            with self.pin():
                pass
        switch.atomic(self.state_dir / 'new.json', b'new installation')
        with self.assertRaises(switch.GateError):
            with self.pin():
                switch.atomic(self.plist, self.plist.read_bytes())

    def test_rejects_symlink_and_hardlink_pointer(self):
        self.plist.unlink()
        self.plist.symlink_to(self.state_dir / 'old.plist')
        with self.assertRaises(switch.GateError):
            with self.pin():
                pass
        self.plist.unlink()
        os.link(self.state_dir / 'old.plist', self.plist)
        with self.assertRaises(switch.GateError):
            with self.pin():
                pass

    def test_rejects_unreviewed_source_even_when_stage_and_pointer_match(self):
        data = json.dumps({'sourceHead': '0' * 40}).encode()
        state = json.loads((self.state_dir / 'state.json').read_bytes())
        state['files']['old.json'] = switch.digest(data)
        switch.atomic(self.state_dir / 'old.json', data)
        switch.atomic(self.state_dir / 'state.json', json.dumps(state).encode())
        switch.atomic(self.installation, data)
        with self.assertRaisesRegex(switch.GateError, 'unreviewed managed health loop source'):
            with self.pin():
                pass


if __name__ == '__main__':
    unittest.main()
