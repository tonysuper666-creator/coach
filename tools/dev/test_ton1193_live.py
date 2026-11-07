import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tools.dev import ton1175_switch as switch
from tools.dev import ton1193_live as live


class LiveTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        switch.atomic(self.root / 'lock', b'')
        switch.set_journal(self.root, 'switching')

    def hold_lock(self):
        code = ('import fcntl,sys; f=open(sys.argv[1],"rb"); '
                'fcntl.flock(f,fcntl.LOCK_EX); print("held",flush=True); sys.stdin.read()')
        child = subprocess.Popen([sys.executable, '-c', code, str(self.root / 'lock')],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.addCleanup(self.stop_child, child)
        self.assertEqual(child.stdout.readline().strip(), 'held')
        return child

    @staticmethod
    def stop_child(child):
        if child.stdin and not child.stdin.closed:
            child.stdin.close()
        child.wait(timeout=10)
        child.stdout.close()

    def test_requires_contended_lock_and_valid_phase(self):
        with self.assertRaisesRegex(switch.GateError, 'lock not held'):
            live.live_transaction(self.root)
        child = self.hold_lock()
        self.assertEqual(live.live_transaction(self.root), 'switching')
        switch.set_journal(self.root, 'recovering')
        self.assertEqual(live.live_transaction(self.root), 'recovering')
        switch.set_journal(self.root, 'staged')
        with self.assertRaisesRegex(switch.GateError, 'unsafe journal phase'):
            live.live_transaction(self.root)
        switch.set_journal(self.root, 'switching')
        self.stop_child(child)
        with self.assertRaisesRegex(switch.GateError, 'lock not held'):
            live.live_transaction(self.root)

    def test_rejects_symlink_and_hard_link_lock(self):
        target = self.root / 'other'
        switch.atomic(target, b'')
        (self.root / 'lock').unlink()
        (self.root / 'lock').symlink_to(target)
        with self.assertRaisesRegex(switch.GateError, 'unavailable or symlink'):
            live.live_transaction(self.root)
        (self.root / 'lock').unlink()
        os.link(target, self.root / 'lock')
        with self.assertRaisesRegex(switch.GateError, 'unsafe transaction lock'):
            live.live_transaction(self.root)

    def test_acceptance_and_service_control_have_disjoint_phases(self):
        child = self.hold_lock()
        for phase in ('staged', 'switching', 'recovering', 'switched', 'rolled_back'):
            switch.set_journal(self.root, phase)
            with self.assertRaisesRegex(switch.GateError, 'unsafe journal phase'):
                live.live_acceptance(self.root)
        switch.set_journal(self.root, 'validating')
        self.assertEqual(live.live_acceptance(self.root), 'validating')
        with self.assertRaisesRegex(switch.GateError, 'unsafe journal phase'):
            live.live_transaction(self.root)
        self.stop_child(child)
        with self.assertRaisesRegex(switch.GateError, 'lock not held'):
            live.live_acceptance(self.root)

    def test_rejects_symlink_journal(self):
        target = self.root / 'other'
        switch.atomic(target, b'{"phase":"switching"}')
        (self.root / 'journal.json').unlink()
        (self.root / 'journal.json').symlink_to(target)
        with self.assertRaisesRegex(switch.GateError, 'unavailable or symlink'):
            live.live_transaction(self.root)


if __name__ == '__main__':
    unittest.main()
