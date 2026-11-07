import os
import subprocess
import unittest
from unittest import mock

from . import ton1193_started as started
from .ton1175_switch import GateError


class LaunchdBindingTest(unittest.TestCase):
    def setUp(self):
        self.plist = '/fixed/service.plist'
        self.side = {'release': '/fixed/release'}
        self.roots = {'cellar': '/cellar', 'node': '/node'}
        self.identity = (1, '1 start java /fixed/release/app/lib/a.jar com.coach.server.MainKt')

    def test_job_requires_exact_plist_and_unique_pid(self):
        result = subprocess.CompletedProcess([], 0, b'path = /fixed/service.plist\npid = 345\n')
        with mock.patch.object(started, '_command', return_value=result) as call:
            self.assertEqual(started._job(self.plist), 345)
            self.assertEqual(call.call_args.args[0], ['/bin/launchctl', 'print', f'gui/{os.getuid()}/local.coach.server'])
        result.stdout += b'pid = 346\n'
        with mock.patch.object(started, '_command', return_value=result):
            with self.assertRaisesRegex(GateError, 'launchd label or plist'):
                started._job(self.plist)
        result.stdout = b'path = /other\npid = 345\n'
        with mock.patch.object(started, '_command', return_value=result):
            with self.assertRaisesRegex(GateError, 'launchd label or plist'):
                started._job(self.plist)

    def test_process_and_launchd_identity_must_remain_equal(self):
        with mock.patch.object(started, '_job', side_effect=[345, 345]), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', return_value=self.identity), \
             mock.patch.object(started.process, 'bind', return_value={'identity': self.identity}) as bind:
            started._binding(345, self.side, self.roots, self.plist)
            bind.assert_called_once_with(345, self.side, self.roots)
        with mock.patch.object(started, '_job', side_effect=[345, 346]), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', return_value=self.identity), \
             mock.patch.object(started.process, 'bind', return_value={'identity': self.identity}):
            with self.assertRaisesRegex(GateError, 'identity changed'):
                started._binding(345, self.side, self.roots, self.plist)

    def test_daemon_pid_or_non_launchd_parent_refused(self):
        with mock.patch.object(started, '_job', return_value=345), \
             mock.patch.object(started, '_daemon_pid', return_value=345):
            with self.assertRaisesRegex(GateError, 'daemon PID'):
                started._binding(345, self.side, self.roots, self.plist)
        with mock.patch.object(started, '_job', return_value=345), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', return_value=(5, self.identity[1])):
            with self.assertRaisesRegex(GateError, 'unexpected launchd process'):
                started._binding(345, self.side, self.roots, self.plist)

    def test_process_reuse_is_rejected(self):
        with mock.patch.object(started, '_job', return_value=345), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', side_effect=[self.identity, (1, 'reused')]), \
             mock.patch.object(started.process, 'bind', return_value={'identity': self.identity}):
            with self.assertRaisesRegex(GateError, 'identity changed'):
                started._binding(345, self.side, self.roots, self.plist)

    def test_launcher_exec_must_reach_same_verified_jvm(self):
        shell = (1, '1 Sat Nov 7 01:49:14 2026 /bin/sh /fixed/release/app/bin/app')
        java = (1, '1 Sat Nov 7 01:49:14 2026 java /fixed/release/app/lib/a.jar com.coach.server.MainKt')
        with mock.patch.object(started, '_job', return_value=345), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', side_effect=[shell, java, java]), \
             mock.patch.object(started.process, '_image', return_value='/bin/sh'), \
             mock.patch.object(started.process, 'bind', return_value={'identity': java}) as bind, \
             mock.patch.object(started.time, 'sleep'):
            started._binding(345, self.side, self.roots, self.plist)
            bind.assert_called_once_with(345, self.side, self.roots)

        reused = (1, '1 Sat Nov 7 01:49:15 2026 java /fixed/release/app/lib/a.jar com.coach.server.MainKt')
        with mock.patch.object(started, '_job', return_value=345), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', side_effect=[shell, reused]), \
             mock.patch.object(started.process, '_image', return_value='/bin/sh'), \
             mock.patch.object(started.time, 'sleep'), \
             mock.patch.object(started.process, 'bind') as bind:
            with self.assertRaisesRegex(GateError, 'identity changed'):
                started._binding(345, self.side, self.roots, self.plist)
            bind.assert_not_called()

    def test_launcher_timeout_and_unknown_shell_fail_closed(self):
        shell = (1, '1 Sat Nov 7 01:49:14 2026 /bin/sh /fixed/release/app/bin/app')
        with mock.patch.object(started, '_job', return_value=345), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', return_value=shell), \
             mock.patch.object(started.time, 'monotonic', side_effect=[0, 21]), \
             mock.patch.object(started.process, 'bind') as bind:
            with self.assertRaisesRegex(GateError, 'launcher did not exec'):
                started._binding(345, self.side, self.roots, self.plist)
            bind.assert_not_called()
        other = (1, '1 Sat Nov 7 01:49:14 2026 /bin/sh /other/app')
        with mock.patch.object(started, '_job', return_value=345), \
             mock.patch.object(started, '_daemon_pid', return_value=777), \
             mock.patch.object(started.process, '_identity', return_value=other), \
             mock.patch.object(started.process, 'bind') as bind:
            with self.assertRaisesRegex(GateError, 'unexpected launchd process'):
                started._binding(345, self.side, self.roots, self.plist)
            bind.assert_not_called()

    def test_stop_selects_live_release_and_rejects_wrong_or_ambiguous_side(self):
        sides = {'old': self.side, 'new': {'release': '/fixed/candidate'}}
        with mock.patch.object(started.process, '_identity', return_value=self.identity), \
             mock.patch.object(started, '_binding', return_value={'identity': self.identity}) as bind:
            started._stop_binding(345, sides, self.roots, 'old', self.plist)
            bind.assert_called_once_with(345, self.side, self.roots, self.plist)
            started._stop_binding(345, sides, self.roots, None, self.plist)
            with self.assertRaisesRegex(GateError, 'differs from journal'):
                started._stop_binding(345, sides, self.roots, 'new', self.plist)
            with self.assertRaisesRegex(GateError, 'differs from journal'):
                started._stop_binding(345, {'old': self.side, 'new': self.side}, self.roots, None, self.plist)

    def test_stop_rejects_changed_identity_and_lookalike_classpath(self):
        sides = {'old': self.side, 'new': {'release': '/fixed/candidate'}}
        with mock.patch.object(started.process, '_identity', return_value=self.identity), \
             mock.patch.object(started, '_binding', return_value={'identity': (1, 'reused')}):
            with self.assertRaisesRegex(GateError, 'identity changed'):
                started._stop_binding(345, sides, self.roots, 'old', self.plist)
        fake = (1, 'java /fixed/release/app/library/a.jar com.coach.server.MainKt')
        with mock.patch.object(started.process, '_identity', return_value=fake):
            with self.assertRaisesRegex(GateError, 'differs from journal'):
                started._stop_binding(345, sides, self.roots, 'old', self.plist)

    def test_old_stop_requires_v1_but_recovery_new_does_not_check_old(self):
        sides = {'old': self.side, 'new': {'release': '/fixed/candidate'}}
        config = {'plist': self.plist, 'expectedSourceHead': 'a' * 40}
        with mock.patch.object(started, '_stop_binding', return_value={'side': 'old'}), \
             mock.patch.object(started, 'capability', return_value={'coverageProtocol': 'absent'}) as cap:
            with self.assertRaisesRegex(GateError, 'legacy-protocol-absent'):
                started._stopping_gate(345, sides, self.roots, 'old', config)
            with self.assertRaisesRegex(GateError, 'legacy-protocol-absent'):
                started._stopping_gate(345, sides, self.roots, None, config)
            self.assertEqual(cap.call_count, 2)
        with mock.patch.object(started, '_stop_binding', return_value={'side': 'new'}), \
             mock.patch.object(started, 'capability') as cap:
            self.assertEqual(started._stopping_gate(345, sides, self.roots, None, config)['side'], 'new')
            cap.assert_not_called()
        with mock.patch.object(started, '_stop_binding', side_effect=[{'side': 'old', 'pid': 345},
                                                                      {'side': 'old', 'pid': 346}]), \
             mock.patch.object(started, 'capability', return_value={'coverageProtocol': 'v1'}):
            with self.assertRaisesRegex(GateError, 'changed during capability'):
                started._stopping_gate(345, sides, self.roots, 'old', config)

    def test_public_binding_checks_live_transaction_before_and_after(self):
        config = {'stateDir': '/fixed/stage', 'plist': self.plist}
        for action in ('start', 'stop'):
            gate = started.check_started if action == 'start' else started.check_stopping
            helper = '_check_started' if action == 'start' else '_check_stopping'
            with self.subTest(action=action), \
                 mock.patch.object(started.preflight, '_production_roots', return_value=self.roots), \
                 mock.patch.object(started.live, 'live_transaction', side_effect=['switching', 'recovering']), \
                 mock.patch.object(started, '_job', return_value=345), \
                 mock.patch.object(started.preflight, helper, return_value={'pid': 345}):
                with self.assertRaisesRegex(GateError, 'transaction phase changed'):
                    gate(config, ('old', {}))
            with self.subTest(action=action, missing_lock=True), \
                 mock.patch.object(started.preflight, '_production_roots', return_value=self.roots), \
                 mock.patch.object(started.live, 'live_transaction', side_effect=GateError('transaction lock not held')), \
                 mock.patch.object(started, '_job') as job:
                with self.assertRaisesRegex(GateError, 'transaction lock not held'):
                    gate(config, ('old', {}))
                job.assert_not_called()


if __name__ == '__main__':
    unittest.main()
