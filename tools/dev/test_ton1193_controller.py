"""Controller orchestration tests. The launchd and image gates are simulated."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from . import ton1193_controller as controller
from . import ton1175_switch as switch


class ControllerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        switch.atomic(self.root / 'lock', b'')
        switch.set_journal(self.root, 'switching')
        self.config = {'stateDir': str(self.root), 'plist': '/fixed/plist'}

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
        evidence = {'pid': child.pid, 'side': 'old'}
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
                                   ([None], {'pid': 123}, 'manual inspection')):
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
        with mock.patch.object(controller.preflight, 'revalidate', return_value=before), \
             mock.patch.object(controller, '_job', side_effect=[None, None, 321, 321]), \
             mock.patch.object(controller.started, 'check_started', return_value={'pid': 321}) as bind, \
             mock.patch.object(controller, '_launchctl') as operation:
            controller._start(self.config, True)
            operation.assert_called_once_with(['bootstrap', f'gui/{os.getuid()}', '/fixed/plist'])
            bind.assert_called_once_with(self.config, before)
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
        with mock.patch.object(controller.preflight, 'revalidate', return_value=('new', {})), \
             mock.patch.object(controller, '_job', side_effect=[None, None, 321]), \
             mock.patch.object(controller.started, 'check_started', side_effect=switch.GateError('plist changed')), \
             mock.patch.object(controller, '_launchctl') as operation:
            with self.assertRaisesRegex(switch.GateError, 'plist changed'):
                controller._start(self.config, True)
            operation.assert_called_once()


if __name__ == '__main__':
    unittest.main()
