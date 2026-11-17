"""Pointer transactions for the policy-free friend credential-domain plan.

The controller, acceptance and idle verifiers here are fixtures. These tests
do not constitute a real JVM, 900-second idle or production acceptance run.
"""
import json
import plistlib
import socket
import unittest

from tools.dev import ton1175_switch as switch
from tools.dev import test_ton1175_switch as pointer_fixture


class FriendDomainSwitchTest(unittest.TestCase):
    def setUp(self):
        self.f = pointer_fixture.SwitchTest(methodName='runTest')
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        # Canonical paths are required even on hosts whose temp root is a link.
        self.config_path = self.f.root.resolve() / 'friends.json'
        self.config_path.write_text(json.dumps(dict(paused=True, defaults=dict(daily_calls=1, daily_tokens=1000))))
        self.config_path.chmod(0o600)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            self.port = listener.getsockname()[1]
        old = plistlib.loads(self.f.old_plist)
        old['EnvironmentVariables'] = dict(HOME=str(self.f.root / 'home'), COACH_API_STATE_DIR='existing-state',
                                          COACH_TOKEN_FILE='original-owner-token', COACH_PORT='18791')
        self.f.old_plist = plistlib.dumps(old)
        self.f.plist.write_bytes(self.f.old_plist)
        self.f.config['friendAccess'] = dict(publicPort=self.port, config=str(self.config_path))
        self.save()

    def save(self):
        self.f.config_path.write_text(json.dumps(self.f.config))

    def stage(self):
        self.save()
        self.f.invoke('stage', True)

    def refused_before_stop(self):
        self.f.invoke('switch', True, False)
        self.f.assert_old()
        self.assertFalse((self.f.root / 'calls').exists())

    def test_switch_and_rollback_without_policy_preserve_config_and_new_writes(self):
        self.f.invoke('stage')
        self.assertFalse(self.f.ops.exists())
        self.stage()
        state = switch.load(self.f.ops / 'state.json')
        self.assertEqual(state['friendCredentialDomain'], 'coach-friend-v1')
        self.assertNotIn('friendGuardFiles', state)
        before = plistlib.loads(self.f.old_plist)
        after = plistlib.loads((self.f.ops / 'new.plist').read_bytes())
        expected = dict(before['EnvironmentVariables'], COACH_SERVICE_ROOT=str(self.f.candidate),
                        COACH_PYTHON=str(self.f.candidate / 'tools/bridge/.venv/bin/python'),
                        COACH_MATHSVC=str(self.f.candidate / 'tools/mathsvc/src/server.mjs'),
                        COACH_PUBLIC_PORT=str(self.port), COACH_FRIEND_CONFIG=str(self.config_path))
        self.assertEqual(after['EnvironmentVariables'], expected)
        for key, value in before.items():
            if key not in ('ProgramArguments', 'WorkingDirectory', 'EnvironmentVariables'):
                self.assertEqual(after[key], value)
        self.f.invoke('switch', True)
        self.assertEqual(self.f.running.read_text(), self.f.head)
        self.f.database.write_text('original and newly committed data')
        # Unpausing after acceptance must never prevent restoration of owner.
        self.config_path.write_text('{"paused":false}')
        self.f.invoke('rollback', True)
        self.f.assert_old()
        self.assertEqual(self.f.database.read_text(), 'original and newly committed data')
        self.assertEqual(self.f.running.read_text(), 'b' * 40)
        self.assertFalse(list(self.f.root.rglob('friend-start-*.json')))

    def test_candidate_start_failure_restores_owner_without_guard(self):
        self.stage()
        (self.f.root / 'fail-start').touch()
        result = self.f.invoke('switch', True, False)
        self.assertIn('old pointers restored', result.stderr)
        self.f.assert_old()
        self.assertEqual(self.f.running.read_text(), 'b' * 40)
        self.assertEqual(switch.load(self.f.ops / 'journal.json')['phase'], 'rolled_back')

    def test_acceptance_failure_restores_owner(self):
        self.stage()
        (self.f.root / 'reject-acceptance').touch()
        self.f.invoke('switch', True, False)
        self.f.assert_old()
        self.assertEqual(self.f.running.read_text(), 'b' * 40)

    def test_changed_friend_config_refuses_before_stop(self):
        self.stage()
        self.config_path.write_text('{"paused":false}')
        self.refused_before_stop()

    def test_changed_controller_refuses_before_stop(self):
        self.stage()
        self.f.controller.write_text('raise RuntimeError("unexpected controller")')
        self.refused_before_stop()

    def test_config_permissions_rechecked_before_stop(self):
        self.stage()
        self.config_path.chmod(0o644)
        self.refused_before_stop()

    def test_occupied_port_rechecked_before_stop(self):
        self.stage()
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', self.port))
            self.refused_before_stop()

    def test_recovery_does_not_need_friend_file_or_free_port(self):
        self.stage()
        (self.f.ops / 'journal.json').write_text('{"phase":"validating"}')
        self.f.plist.write_bytes((self.f.ops / 'new.plist').read_bytes())
        self.f.installation.write_bytes((self.f.ops / 'new.json').read_bytes())
        self.config_path.unlink()
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', self.port))
            self.f.invoke('recover', True)
        self.f.assert_old()
        self.assertEqual(self.f.running.read_text(), 'b' * 40)

    def test_unsafe_ports_and_existing_friend_environment_refused(self):
        for port in (18791, 8790, 8791, 3790, 19540, 0, 65536, True):
            with self.subTest(port=port):
                self.f.config['friendAccess']['publicPort'] = port
                with self.assertRaises(switch.GateError):
                    switch.stage(self.f.config, True)
                self.assertFalse(self.f.ops.exists())
        self.f.config['friendAccess']['publicPort'] = self.port
        for key in ('COACH_PUBLIC_PORT', 'COACH_FRIEND_CONFIG'):
            old = plistlib.loads(self.f.old_plist)
            old['EnvironmentVariables'][key] = 'already-configured'
            self.f.plist.write_bytes(plistlib.dumps(old))
            with self.assertRaises(switch.GateError):
                switch.stage(self.f.config, True)
            self.assertFalse(self.f.ops.exists())

    def test_unpaused_or_implicit_or_invalid_limits_refused(self):
        for value in (dict(paused=False, defaults=dict(daily_calls=1, daily_tokens=2)),
                      dict(paused=True), dict(paused=True, defaults=dict(daily_calls=True, daily_tokens=2)),
                      dict(paused=True, defaults=dict(daily_calls=1, daily_tokens=0))):
            with self.subTest(value=value):
                self.config_path.write_text(json.dumps(value))
                with self.assertRaises(switch.GateError):
                    switch.stage(self.f.config, True)
                self.assertFalse(self.f.ops.exists())

    def test_stale_idle_or_activity_still_refuses_before_stop(self):
        self.stage()
        self.f.write_idle(verifiedAtEpoch=self.f.now - 500)
        self.refused_before_stop()
        self.f.write_idle()
        (self.f.root / 'reject-logs').touch()
        self.refused_before_stop()

    def test_mode_marker_required_and_unknown_keys_not_ignored(self):
        self.f.config['friendAccess']['typo'] = True
        with self.assertRaises(switch.GateError):
            switch.stage(self.f.config, True)
        del self.f.config['friendAccess']['typo']
        self.stage()
        state = switch.load(self.f.ops / 'state.json')
        del state['friendCredentialDomain']
        (self.f.ops / 'state.json').write_text(json.dumps(state))
        self.refused_before_stop()


if __name__ == '__main__':
    unittest.main()
