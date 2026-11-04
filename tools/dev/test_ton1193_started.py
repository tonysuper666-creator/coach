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


if __name__ == '__main__':
    unittest.main()
