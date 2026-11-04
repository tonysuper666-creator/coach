"""Controller orchestration tests. The launchd and image gates are simulated."""
import os
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from . import ton1193_controller as controller
from . import ton1175_switch as switch
from . import ton1193_receipt as receipt
from .ton1193_trust import directory_snapshot


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        switch.atomic(self.root / 'lock', b'')
        switch.set_journal(self.root, 'switching')
        self.private = self.root / 'adapter'
        self.private.mkdir(mode=0o700)
        pin = {'version': 1, 'privateId': directory_snapshot(self.private),
               'stateHash': 'a' * 64, 'snapshotHash': 'b' * 64, 'manifestHash': 'c' * 64}
        switch.atomic(self.private / 'pin.json', json.dumps(pin).encode())
        self.config = {'stateDir': str(self.root), 'plist': '/fixed/plist',
                       'adapterSnapshotDir': str(self.private)}

    def record_stop(self, pid=99999999):
        receipt.write(self.config, {'pid': pid, 'side': 'old',
                                    'identity': (1, '1 isolated process'),
                                    'image': '/isolated/java'})

    def hold_lock(self):
        code = ('import fcntl,sys; f=open(sys.argv[1],"rb"); '
                'fcntl.flock(f,fcntl.LOCK_EX); print("held",flush=True); sys.stdin.read()')
        proc = subprocess.Popen([sys.executable, '-c', code, str(self.root / 'lock')],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.addCleanup(self.close_locker, proc)
        self.assertEqual(proc.stdout.readline().strip(), 'held')

    def test_job_query_requires_exact_label_and_plist(self):
        with mock.patch.object(controller.started, '_command', return_value=subprocess.CompletedProcess([], 113, b'')) as call:
            self.assertIsNone(controller._job('/fixed/plist'))
            call.assert_called_once_with(['/bin/launchctl', 'print', controller._target()])
        for output in (b'path = /other\npid = 123\n', b'path = /fixed/plist\npid = 123\npid = 124\n'):
            with mock.patch.object(controller.started, '_command', return_value=subprocess.CompletedProcess([], 0, output)):
                with self.assertRaisesRegex(switch.GateError, 'identity changed'):
                    controller._job('/fixed/plist')
        with mock.patch.object(controller.started, '_command', return_value=subprocess.CompletedProcess([], 0, b'path = /fixed/plist\n')):
            self.assertEqual(controller._job('/fixed/plist'), 0)

    @staticmethod
    def close_locker(proc):
        if not proc.stdin.closed:
            proc.stdin.close()
        proc.wait(timeout=10)
        proc.stdout.close()

    def test_actual_lock_gates_stop_of_isolated_process(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        self.addCleanup(self.close_child, child)
        evidence = {'pid': child.pid, 'side': 'old', 'identity': (1, '1 isolated process'),
                    'image': '/isolated/java'}
        def job(_):
            return child.pid if child.poll() is None else None
        def bootout(args):
            self.assertEqual(args, ['bootout', controller._target()])
            child.terminate()
            child.wait(timeout=10)
        with mock.patch.object(controller.preflight, 'revalidate', return_value=('old', {})), \
             mock.patch.object(controller.started, 'check_stopping', return_value=evidence), \
             mock.patch.object(controller, '_job', side_effect=job), \
             mock.patch.object(controller, '_launchctl', side_effect=bootout) as operation:
            with self.assertRaisesRegex(switch.GateError, 'lock not held'):
                controller._stop(self.config, True)
            self.assertIsNone(child.poll())
            operation.assert_not_called()
            self.hold_lock()
            controller._stop(self.config, False)
            self.assertIsNone(child.poll())
            operation.assert_not_called()
            controller._stop(self.config, True)
            self.assertIsNotNone(child.poll())
            operation.assert_called_once()
            switch.set_journal(self.root, 'recovering')
            controller._stop(self.config, True)
            self.assertEqual(receipt.read(self.config)[0]['pid'], child.pid)
            print(f'isolated-child pid={child.pid} phase=switching reaped=yes; launchd=simulated')

    @staticmethod
    def close_child(child):
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=10)

    def test_stop_refuses_legacy_pid_change_or_missing_job(self):
        self.hold_lock()
        for job, gate, message in (([123], switch.GateError('legacy-protocol-absent'), 'legacy-protocol-absent'),
                                   ([123, 124], {'pid': 123}, 'PID changed'),
                                   ([None], {'pid': 123}, 'job absent outside recovery')):
            with self.subTest(message=message), \
                 mock.patch.object(controller.preflight, 'revalidate', return_value=('old', {})), \
                 mock.patch.object(controller, '_job', side_effect=job), \
                 mock.patch.object(controller.started, 'check_stopping', side_effect=gate if isinstance(gate, Exception) else None,
                                   return_value=gate), \
                 mock.patch.object(controller, '_launchctl') as operation:
                with self.assertRaisesRegex(switch.GateError, message):
                    controller._stop(self.config, True)
                operation.assert_not_called()

    def test_start_binds_after_bootstrap_and_refuses_occupied_label(self):
        self.hold_lock()
        before = ('new', {'plist': (1, 2)})
        self.record_stop()
        with mock.patch.object(controller.preflight, 'revalidate', return_value=before), \
             mock.patch.object(controller, '_exists', return_value=False), \
             mock.patch.object(controller, '_job', side_effect=[None, None, None, 321, 321]), \
             mock.patch.object(controller.started, 'check_started', return_value={'pid': 321}) as bind, \
             mock.patch.object(controller, '_launchctl') as operation:
            controller._start(self.config, True)
            operation.assert_called_once_with(['bootstrap', f'gui/{os.getuid()}', '/fixed/plist'])
            bind.assert_called_once_with(self.config, before)
            self.assertFalse((self.private / receipt.NAME).exists())
        with mock.patch.object(controller.preflight, 'revalidate', return_value=before), \
             mock.patch.object(controller, '_job', return_value=321), \
             mock.patch.object(controller, '_launchctl') as operation:
            with self.assertRaisesRegex(switch.GateError, 'occupied'):
                controller._start(self.config, True)
            operation.assert_not_called()

    def test_invalid_phase_prevents_service_control(self):
        self.hold_lock()
        switch.set_journal(self.root, 'staged')
        for action in (controller._stop, controller._start):
            with mock.patch.object(controller, '_launchctl') as operation:
                with self.assertRaisesRegex(switch.GateError, 'unsafe journal phase'):
                    action(self.config, True)
                operation.assert_not_called()

    def test_changed_identity_or_transaction_blocks_stop(self):
        self.hold_lock()
        with mock.patch.object(controller.preflight, 'revalidate', return_value=('old', {})), \
             mock.patch.object(controller, '_job', return_value=123), \
             mock.patch.object(controller.started, 'check_stopping', side_effect=[{'pid': 123}, {'pid': 123, 'image': 'changed'}]), \
             mock.patch.object(controller, '_launchctl') as operation:
            with self.assertRaisesRegex(switch.GateError, 'identity changed'):
                controller._stop(self.config, True)
            operation.assert_not_called()
        with mock.patch.object(controller.live, 'live_transaction', side_effect=['switching', 'recovering']), \
             mock.patch.object(controller.preflight, 'revalidate', return_value=('old', {})), \
             mock.patch.object(controller, '_job', return_value=123), \
             mock.patch.object(controller.started, 'check_stopping', return_value={'pid': 123}), \
             mock.patch.object(controller, '_launchctl') as operation:
            with self.assertRaisesRegex(switch.GateError, 'phase changed'):
                controller._stop(self.config, True)
            operation.assert_not_called()

    def test_start_propagates_post_bootstrap_binding_failure(self):
        self.hold_lock()
        self.record_stop()
        with mock.patch.object(controller.preflight, 'revalidate', return_value=('new', {})), \
             mock.patch.object(controller, '_exists', return_value=False), \
             mock.patch.object(controller, '_job', side_effect=[None, None, None, 321]), \
             mock.patch.object(controller.started, 'check_started', side_effect=switch.GateError('plist changed')), \
             mock.patch.object(controller, '_launchctl') as operation:
            with self.assertRaisesRegex(switch.GateError, 'plist changed'):
                controller._start(self.config, True)
            operation.assert_called_once()
            self.assertFalse((self.private / receipt.NAME).exists())

    def test_missing_job_requires_receipt_and_dead_unreused_pid(self):
        self.hold_lock()
        switch.set_journal(self.root, 'recovering')
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        self.addCleanup(self.close_child, child)
        with mock.patch.object(controller.preflight, 'revalidate', return_value=('old', {})), \
             mock.patch.object(controller, '_job', return_value=None), \
             mock.patch.object(controller, '_launchctl') as operation:
            with self.assertRaisesRegex(switch.GateError, 'control file unavailable'):
                controller._stop(self.config, True)
            self.record_stop(child.pid)
            with self.assertRaisesRegex(switch.GateError, 'still exists or was reused'):
                controller._stop(self.config, True)
            child.terminate()
            child.wait(timeout=10)
            controller._stop(self.config, True)
            operation.assert_not_called()
            print(f'recovery-proof pid={child.pid} phase=recovering exited=yes; launchd=simulated')

    def test_receipt_tampering_or_changed_pin_refuses_recovery(self):
        self.hold_lock()
        switch.set_journal(self.root, 'recovering')
        self.record_stop()
        with mock.patch.object(controller.preflight, 'revalidate', return_value=('old', {})), \
             mock.patch.object(controller, '_job', return_value=None), \
             mock.patch.object(controller, '_exists', return_value=False):
            pin = self.private / 'pin.json'
            old = pin.read_bytes()
            switch.atomic(pin, old + b' ')
            with self.assertRaisesRegex(switch.GateError, 'does not match snapshot'):
                controller._stop(self.config, True)
            switch.atomic(pin, old)
            path = self.private / receipt.NAME
            path.unlink()
            path.symlink_to(pin)
            with self.assertRaisesRegex(switch.GateError, 'control file unavailable'):
                controller._stop(self.config, True)
            path.unlink()
            os.link(pin, path)
            with self.assertRaisesRegex(switch.GateError, 'unsafe control file'):
                controller._stop(self.config, True)

    def test_dry_run_preserves_receipt_and_changed_snapshot_refuses(self):
        self.hold_lock()
        self.record_stop()
        with mock.patch.object(controller.preflight, 'revalidate', return_value=('new', {})), \
             mock.patch.object(controller, '_job', return_value=None), \
             mock.patch.object(controller, '_exists', return_value=False), \
             mock.patch.object(controller, '_launchctl') as operation:
            controller._start(self.config, False)
            self.assertTrue((self.private / receipt.NAME).exists())
            operation.assert_not_called()
        with mock.patch.object(controller.preflight, 'revalidate', side_effect=[('new', {}), ('old', {})]), \
             mock.patch.object(controller, '_job', return_value=None), \
             mock.patch.object(controller, '_exists', return_value=False):
            with self.assertRaisesRegex(switch.GateError, 'snapshot changed'):
                controller._start(self.config, True)


if __name__ == '__main__':
    unittest.main()
