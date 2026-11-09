import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools.dev import ton1175_switch as switch
from tools.dev import ton1193_transaction as transaction
from tools.dev.ton1193_trust import directory_snapshot


class TransactionPointerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.ops = self.root / 'state'
        self.ops.mkdir(mode=0o700)
        self.plist, self.installation = self.root / 'service.plist', self.root / 'installation.json'
        self.config = {'stateDir': str(self.ops), 'plist': str(self.plist),
                       'installation': str(self.installation)}
        self.payloads = {'old.plist': b'old plist', 'new.plist': b'new plist',
                         'old.json': b'old installation', 'new.json': b'new installation'}
        self.state = {'configHash': switch.digest(json.dumps(self.config, sort_keys=True).encode()),
                      'files': {k: switch.digest(v) for k, v in self.payloads.items()},
                      'plistMode': 0o600, 'installationMode': 0o600}
        for name, data in self.payloads.items():
            switch.atomic(self.ops / name, data)
        switch.atomic(self.ops / 'state.json', json.dumps(self.state).encode())
        switch.atomic(self.plist, self.payloads['old.plist'])
        switch.atomic(self.installation, self.payloads['old.json'])
        switch.set_journal(self.ops, 'switching')
        self.state_identity = directory_snapshot(self.ops)
        self.parent_ids = {key: directory_snapshot(self.root) for key in ('plist', 'installation')}
        self.loaded, self.state_hash = transaction.load_bound_state(self.config, self.state_identity)

    def check(self, action):
        state, _ = transaction.load_bound_state(self.config, self.state_identity, self.state_hash)
        return transaction.check_pointers(self.config, state, self.state_identity, self.parent_ids, action)

    def set_new(self):
        switch.atomic(self.plist, self.payloads['new.plist'])
        switch.atomic(self.installation, self.payloads['new.json'])

    def test_switch_and_real_restore_allow_atomic_inode_replacement(self):
        self.assertEqual(self.check('stop')[0], 'old')
        original = self.plist.stat().st_ino
        self.set_new()
        self.assertNotEqual(original, self.plist.stat().st_ino)
        side, before = self.check('start')
        self.assertEqual(side, 'new')
        transaction.check_plist_after_start(self.config, self.loaded, self.parent_ids['plist'],
                                            side, before['plist'])
        calls = []

        def checked_command(config, action):
            calls.append((action, self.check(action)[0]))

        with mock.patch.object(switch, 'command', side_effect=checked_command):
            switch.restore(self.config, self.ops, self.state)
        self.assertEqual(calls, [('stop', None), ('start', 'old')])
        self.assertEqual(json.loads((self.ops / 'journal.json').read_text())['phase'], 'rolled_back')
        self.assertEqual(self.plist.read_bytes(), self.payloads['old.plist'])

    def test_unknown_mixed_links_and_startup_replacement_are_rejected(self):
        switch.atomic(self.plist, self.payloads['new.plist'])
        with self.assertRaises(switch.GateError):
            self.check('stop')
        with self.assertRaises(switch.GateError):
            self.check('start')
        switch.set_journal(self.ops, 'recovering')
        self.assertIsNone(self.check('stop')[0])
        switch.atomic(self.plist, b'unknown')
        with self.assertRaises(switch.GateError):
            self.check('stop')
        self.plist.unlink()
        self.plist.symlink_to(self.ops / 'old.plist')
        with self.assertRaises(switch.GateError):
            self.check('stop')
        self.plist.unlink()
        os.link(self.ops / 'old.plist', self.plist)
        with self.assertRaises(switch.GateError):
            self.check('stop')
        self.plist.unlink()
        switch.atomic(self.plist, self.payloads['old.plist'])
        side, identities = self.check('start')
        switch.atomic(self.plist, self.payloads['old.plist'])
        with self.assertRaises(switch.GateError):
            transaction.check_plist_after_start(self.config, self.loaded, self.parent_ids['plist'],
                                                side, identities['plist'])

    def test_stage_binding_tamper_and_other_phases_rejected(self):
        switch.set_journal(self.ops, 'staged')
        with self.assertRaises(switch.GateError):
            self.check('start')
        switch.atomic(self.ops / 'state.json', json.dumps(dict(self.state, extra=True)).encode())
        with self.assertRaises(switch.GateError):
            self.check('stop')

    def test_recovery_start_rejection_preserves_recovery_required(self):
        self.set_new()

        def reject_start(config, action):
            self.check(action)
            if action == 'start':
                raise switch.GateError('fixture old toolchain mismatch')

        with mock.patch.object(switch, 'command', side_effect=reject_start):
            with self.assertRaises(switch.GateError):
                switch.restore(self.config, self.ops, self.state)
        self.assertEqual(json.loads((self.ops / 'journal.json').read_text())['phase'], 'recovery_required')


if __name__ == '__main__':
    unittest.main()
