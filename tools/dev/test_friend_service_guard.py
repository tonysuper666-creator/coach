import fcntl
import importlib.util
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import unittest
from unittest import mock

from tools.dev import friend_service_guard as guard
from tools.dev import ton1175_switch as switch
from tools.dev import test_friend_revoke_offline as database_fixture
from tools.dev import test_ton1175_switch as pointer_fixture


class FriendStartTest(unittest.TestCase):
    def setUp(self):
        self.db = database_fixture.OfflineRevokeTest(methodName='runTest')
        self.db.setUp()
        self.addCleanup(self.db.doCleanups)
        self.root = self.db.root
        self.path = self.root / 'friend-start-policy.json'
        self.blocked = self.root / 'friend-start-blocked.json'
        self.policy = {'version': 1, 'binding': self.db.binding,
                       'capableHeads': ['a' * 40], 'publicPort': 27443,
                       'tailscale': sys.executable}
        self.write_policy()
        self.calls = []
        self.closed = mock.patch.object(guard, 'close_entry', side_effect=lambda p: self.calls.append('close')).start()
        self.addCleanup(mock.patch.stopall)

    def write_policy(self):
        self.path.write_text(json.dumps(self.policy))
        self.path.chmod(0o600)

    def locked(self):
        with (self.root / 'friend-operation.lock').open('rb') as lock:
            with self.assertRaises(BlockingIOError):
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def stop(self):
        self.locked()
        self.calls.append('stop')

    def launch(self):
        self.locked()
        self.calls.append('start')

    def start(self, head='b' * 40, launch=None):
        guard.start(self.path, head, self.db.path, self.stop, launch or self.launch)

    def test_legacy_stop_revoke_start_under_one_lock_keeps_owner_and_budget(self):
        owners = [row for row in self.db.rows('devices') if row[5] is None]
        budget = self.root / 'api-credit-ledger.json'
        budget.write_text('{"cycles":{"nonzero":{"spent":17,"reserved":{"x":9},"limit":123}}}')
        before = budget.read_bytes()
        real_revoke = guard.offline.revoke
        def revoke(*args, **kwargs):
            self.locked()
            self.assertEqual(self.calls, ['close', 'stop'])
            self.calls.append('revoke')
            return real_revoke(*args, **kwargs)
        with mock.patch.object(guard.offline, 'revoke', side_effect=revoke):
            self.start()
        self.assertEqual(self.calls, ['close', 'stop', 'revoke', 'start'])
        self.assertFalse(self.blocked.exists())
        self.assertEqual(owners, [row for row in self.db.rows('devices') if row[5] is None])
        self.assertTrue(all(row[4] is not None for row in self.db.rows('devices') if row[5] is not None))
        self.assertEqual(self.db.rows('pairing_code'), [])
        self.assertEqual(self.db.rows('retained_writes'), [(1, 'post-switch write')])
        self.assertEqual(budget.read_bytes(), before)

    def test_capable_start_preserves_friends(self):
        before = self.db.rows('devices')
        self.start('a' * 40)
        self.assertEqual(self.calls, ['start'])
        self.assertEqual(self.db.rows('devices'), before)

    def test_post_commit_readback_failure_latches_even_for_capable_or_retry(self):
        real_revoke = guard.offline.revoke
        def committed_then_failed(*args, **kwargs):
            real_revoke(*args, **kwargs)
            raise OSError('private database details must not be surfaced')
        with mock.patch.object(guard.offline, 'revoke', side_effect=committed_then_failed) as revoke:
            with self.assertRaises(OSError):
                self.start()
            self.assertTrue(self.blocked.exists())
            self.assertEqual(json.loads(self.blocked.read_text()), {'blocked': 1})
            for head in ('a' * 40, 'b' * 40):
                with self.assertRaises(guard.GateError):
                    self.start(head)
            self.assertEqual(revoke.call_count, 1)
        self.assertEqual(self.calls, ['close', 'stop'])

    def test_failed_close_still_stops_and_never_revokes_or_starts(self):
        self.closed.side_effect = OSError('close failed')
        with mock.patch.object(guard.offline, 'revoke') as revoke:
            with self.assertRaises(OSError):
                self.start()
            revoke.assert_not_called()
        self.assertEqual(self.calls, ['stop'])
        self.assertTrue(self.blocked.exists())

    def test_stop_failure_cannot_use_sqlite_lock_as_shutdown_proof(self):
        with mock.patch.object(guard.offline, 'revoke') as revoke:
            with self.assertRaises(RuntimeError):
                guard.start(self.path, 'b' * 40, self.db.path,
                            mock.Mock(side_effect=RuntimeError('still running')), self.launch)
            revoke.assert_not_called()
        self.assertNotIn('start', self.calls)
        self.assertTrue(self.blocked.exists())

    def test_database_replaced_after_policy_check_stays_stopped(self):
        def replaced():
            self.stop()
            self.db.path.rename(self.root / 'original.sqlite')
            self.db.path.write_bytes(b'wrong database')
        with self.assertRaises(guard.offline.GateError):
            guard.start(self.path, 'b' * 40, self.db.path, replaced, self.launch)
        self.assertEqual(self.calls, ['close', 'stop'])
        self.assertTrue(self.blocked.exists())

    def test_lock_conflict_refuses_without_touching_other_operation(self):
        with guard.operation(self.path):
            with self.assertRaises(BlockingIOError):
                self.start()
        self.assertEqual(self.calls, [])

    def test_failed_legacy_start_stops_again_and_latches(self):
        def fail():
            self.launch()
            raise RuntimeError('not ready')
        with self.assertRaises(RuntimeError):
            self.start(launch=fail)
        self.assertEqual(self.calls, ['close', 'stop', 'start', 'stop'])
        self.assertTrue(self.blocked.exists())

    def test_missing_insecure_or_wrong_database_policy_refuses(self):
        self.path.chmod(0o644)
        with self.assertRaises(guard.GateError):
            self.start()
        self.write_policy()
        with self.assertRaises(guard.GateError):
            guard.start(self.path, 'b' * 40, self.root / 'binding.json', self.stop, self.launch)
        self.path.unlink()
        with self.assertRaises(FileNotFoundError):
            self.start()
        self.assertEqual(self.calls, [])

    def test_durable_controller_start_and_install_cannot_bypass(self):
        spec = importlib.util.spec_from_file_location('coach_service', Path(guard.__file__).with_name('coach-service.py'))
        controller = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(controller)
        record = self.root / 'installation.json'
        record.write_text(json.dumps({'sourceHead': 'b' * 40, 'dataDir': str(self.root)}))
        plist = self.root / 'service.plist'
        plist.write_bytes(b'fixture')
        with mock.patch.multiple(controller, BASE=self.root, RECORD=record, PLIST=plist), \
                mock.patch.dict(sys.modules, friend_service_guard=guard), \
                mock.patch.object(controller, 'stop', self.stop), \
                mock.patch.object(controller, 'run') as run, \
                mock.patch.object(controller, 'loaded', return_value=False), \
                mock.patch.object(controller, 'ready'):
            # A frozen old stage calls the same durable control.py start.
            self.blocked.write_text('{"blocked":1}')
            with self.assertRaises(guard.GateError):
                controller.start()
            run.assert_not_called()
            with self.assertRaises(RuntimeError):
                controller.install()


class FunnelCloseTest(unittest.TestCase):
    def test_exact_rule_removed_and_other_port_preserved(self):
        key = 'node.example.ts.net:443'
        before = {'Web': {key: {'Handlers': {'/': {'Proxy': 'http://127.0.0.1:27443'}}},
                          'node.example.ts.net:8443': {'Handlers': {'/': {'Text': 'retained'}}}},
                  'TCP': {'443': {'HTTPS': True}, '8443': {'HTTPS': True}},
                  'AllowFunnel': {key: True}}
        after = {'Web': {'node.example.ts.net:8443': before['Web']['node.example.ts.net:8443']},
                 'TCP': {'8443': {'HTTPS': True}}}
        with mock.patch.object(guard, 'captured', side_effect=[json.dumps(before), b'', json.dumps(after)]) as run:
            guard.close_entry({'tailscale': '/fixture/tailscale', 'publicPort': 27443})
        self.assertEqual(run.call_args_list[1].args[0], ['/fixture/tailscale', 'funnel', '--https=443', 'off'])

    def test_owner_port_or_remaining_rule_fails_closed(self):
        key = 'node.example.ts.net:443'
        for port in (18791, 27443):
            before = {'Web': {key: {'Handlers': {'/': {'Proxy': f'http://127.0.0.1:{port}'}}}}}
            with mock.patch.object(guard, 'captured', return_value=json.dumps(before)) as run:
                with self.assertRaises(guard.GateError):
                    guard.close_entry({'tailscale': '/fixture/tailscale', 'publicPort': 27443})
            self.assertEqual(run.call_count, 1 if port == 18791 else 3)


class ControllerStopTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('coach_stop', Path(guard.__file__).with_name('coach-service.py'))
        self.controller = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.controller)
        self.db = database_fixture.OfflineRevokeTest(methodName='runTest')
        self.db.setUp()
        self.addCleanup(self.db.doCleanups)
        self.record = self.db.root / 'installation.json'
        self.record.write_text(json.dumps({'root': str(self.db.root / 'release'), 'runDir': str(self.db.root)}))

    def test_stop_tracks_descendants_when_health_files_are_absent(self):
        observed = []
        def command(argv, **kwargs):
            observed.append(argv)
            if argv[:2] == ['launchctl', 'print']:
                return subprocess.CompletedProcess(argv, 0, 'pid = 101', '')
            if argv == ['/bin/ps', '-axo', 'pid=,ppid=']:
                return subprocess.CompletedProcess(argv, 0, '101 1\n102 101\n103 102\n', '')
            if '-p' in argv:
                count = sum(a == argv for a in observed)
                return subprocess.CompletedProcess(argv, 0 if count == 1 else 1, 'start identity', '')
            return subprocess.CompletedProcess(argv, 0, '', '')
        with mock.patch.object(self.controller, 'RECORD', self.record), \
                mock.patch.object(self.controller.subprocess, 'run', side_effect=command), \
                mock.patch.object(self.controller, 'loaded', return_value=False):
            self.controller.stop()
        for pid in (101, 102, 103):
            self.assertEqual(observed.count(['/bin/ps', '-p', str(pid), '-o', 'lstart=']), 2)

    def test_orphan_release_process_refuses_even_after_launchd_and_tracked_pids_exit(self):
        def command(argv, **kwargs):
            if argv == ['/bin/ps', '-axo', 'command=']:
                output = str(self.db.root / 'release/app/bin/app')
            else:
                output = ''
            return subprocess.CompletedProcess(argv, 0, output, '')
        with mock.patch.object(self.controller, 'RECORD', self.record), \
                mock.patch.object(self.controller.subprocess, 'run', side_effect=command), \
                mock.patch.object(self.controller, 'loaded', return_value=False):
            with self.assertRaisesRegex(RuntimeError, 'remains disabled'):
                self.controller.stop()


class FriendStageTest(unittest.TestCase):
    def setUp(self):
        self.f = pointer_fixture.SwitchTest(methodName='runTest')
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        self.db = database_fixture.OfflineRevokeTest(methodName='runTest')
        self.db.setUp()
        self.addCleanup(self.db.doCleanups)
        self.data = self.db.path.parent
        self.db.path.rename(self.data / 'coach.sqlite')
        self.db.binding['database'] = str(self.data / 'coach.sqlite')
        self.base = self.f.root.resolve() / 'durable'
        self.base.mkdir()
        for src, target in [('coach-service.py', 'control.py'), ('friend_service_guard.py', 'friend_service_guard.py'),
                            ('friend_revoke_offline.py', 'friend_revoke_offline.py')]:
            shutil.copyfile(Path(guard.__file__).with_name(src), self.base / target)
        self.policy = self.base / 'friend-start-policy.json'
        self.config = self.f.root.resolve() / 'friends.json'
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            self.port = listener.getsockname()[1]
        self.policy.write_text(json.dumps({'version': 1, 'binding': self.db.binding, 'capableHeads': [self.f.head],
                                         'tailscale': sys.executable, 'publicPort': self.port}))
        self.policy.chmod(0o600)
        self.config.write_text(json.dumps({'paused': True, 'defaults': {'daily_calls': 1, 'daily_tokens': 10000}}))
        self.config.chmod(0o600)
        record = json.loads(self.f.old_record)
        record['dataDir'] = str(self.data)
        self.f.installation.write_text(json.dumps(record))
        self.f.config.update(expectedInstallationSha256=switch.file_hash(self.f.installation),
                             controller=[sys.executable, str(self.base / 'control.py')],
                             friendAccess={'publicPort': self.port, 'config': str(self.config), 'policy': str(self.policy)})

    def test_staging_adds_only_two_variables_and_keeps_state_home(self):
        import plistlib
        before = plistlib.loads(self.f.old_plist)
        before['EnvironmentVariables'] = {'HOME': str(self.f.root), 'COACH_API_STATE_DIR': str(self.f.root / 'existing')}
        self.f.plist.write_bytes(plistlib.dumps(before))
        switch.stage(self.f.config, True)
        after = plistlib.loads((self.f.ops / 'new.plist').read_bytes())['EnvironmentVariables']
        self.assertEqual(after['HOME'], before['EnvironmentVariables']['HOME'])
        self.assertEqual(after['COACH_API_STATE_DIR'], before['EnvironmentVariables']['COACH_API_STATE_DIR'])
        self.assertEqual(after['COACH_PUBLIC_PORT'], str(self.port))
        self.assertEqual(after['COACH_FRIEND_CONFIG'], str(self.config))
        self.assertEqual(set(after) - set(before['EnvironmentVariables']),
                         {'COACH_SERVICE_ROOT', 'COACH_PYTHON', 'COACH_MATHSVC', 'COACH_PUBLIC_PORT', 'COACH_FRIEND_CONFIG'})
        self.policy.write_text('{}')
        with self.assertRaises(switch.GateError):
            switch.operate(self.f.config, 'recover', True)

    def test_unarmed_controller_unpaused_limits_or_occupied_port_cannot_stage(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', self.port))
            with self.assertRaises(switch.GateError):
                switch.stage(self.f.config, True)
        self.config.write_text('{"paused":false}')
        with self.assertRaises(switch.GateError):
            switch.stage(self.f.config, True)
        self.config.write_text('{"paused":true,"defaults":{"daily_calls":1,"daily_tokens":10000}}')
        (self.base / 'control.py').write_text('unguarded old controller')
        with self.assertRaises(switch.GateError):
            switch.stage(self.f.config, True)
        self.assertFalse(self.f.ops.exists())


if __name__ == '__main__':
    unittest.main()
