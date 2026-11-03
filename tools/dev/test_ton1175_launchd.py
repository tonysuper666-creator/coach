"""Fail-closed adapter checks; positive lifecycle evidence is the real rehearsal."""
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from .ton1175_launchd import Adapter
from .ton1175_switch import GateError, atomic


class AdapterBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='ton1175-', dir='/tmp')).resolve()
        os.chmod(self.root, 0o700)
        self.addCleanup(shutil.rmtree, self.root)
        self.config = self.root / 'adapter.json'
        self.c = dict(root=str(self.root), label='local.coach.ton1175.test.' + 'a'*32,
                      domain=f'gui/{os.getuid()}', port=29123,
                      plist=str(self.root / 'service.plist'),
                      installation=str(self.root / 'installation.json'))
        self.save_config()
        env = {key: str(self.root / key.lower()) for key in (
            'HOME', 'COACH_DATA_DIR', 'COACH_TOKEN_FILE', 'COACH_LOG_DIR',
            'COACH_STATE_DIR', 'COACH_PACKS_DIR', 'COACH_ACTIVITY_SOCKET')}
        env.update(COACH_PORT='29123', COACH_TAIL_HOST='', COACH_MANAGE_SERVICES='0',
                   COACH_TRAFFIC='test', COACH_SOURCE_LOOKUP='false',
                   COACH_DEVICE_ID=self.c['label'], COACH_BRIDGE_URL='http://127.0.0.1:29124',
                   JAVA_OPTS=f'-Duser.home={env["HOME"]}')
        self.p = dict(Label=self.c['label'], RunAtLoad=True, KeepAlive=False,
            ProgramArguments=[str(self.root / 'old/app/bin/app')],
            WorkingDirectory=str(self.root / 'old'), EnvironmentVariables=env,
            StandardOutPath=str(self.root / 'out'), StandardErrorPath=str(self.root / 'err'))
        self.save_material()
        atomic(Path(self.c['installation']), json.dumps(dict(root=str(self.root / 'old'))).encode())

    def save_config(self):
        atomic(self.config, json.dumps(self.c).encode())

    def save_material(self):
        atomic(Path(self.c['plist']), plistlib.dumps(self.p))

    def test_valid_material_has_no_process_side_effects(self):
        with patch.object(Adapter, 'command', side_effect=AssertionError('process call')):
            Adapter(self.config).material()

    def test_production_label_and_domain_refused(self):
        for key, value in (('label', 'local.coach'), ('domain', 'system')):
            original = self.c[key]
            self.c[key] = value
            self.save_config()
            with self.assertRaises(GateError):
                Adapter(self.config)
            self.c[key] = original

    def test_reserved_ports_refused(self):
        for value in (8790, 8791, 18791, 3790):
            self.c['port'] = value
            self.save_config()
            with self.assertRaises(GateError):
                Adapter(self.config)

    def test_external_paths_and_symlinks_refused(self):
        outside = self.root / 'outside'
        outside.symlink_to('/Users')
        for value in ('/Users/shared-token', str(outside / 'token')):
            self.p['EnvironmentVariables']['COACH_TOKEN_FILE'] = value
            self.save_material()
            with self.assertRaises(GateError):
                Adapter(self.config).material()

    def test_managed_services_and_external_bridge_refused(self):
        env = self.p['EnvironmentVariables']
        for key, value in (('COACH_MANAGE_SERVICES', '1'),
                           ('COACH_BRIDGE_URL', 'http://127.0.0.1:8791'),
                           ('COACH_BRIDGE_URL', 'http://example.com:29124'),
                           ('JAVA_OPTS', '-Xmx128m')):
            previous = env[key]
            env[key] = value
            self.save_material()
            with self.assertRaises(GateError):
                Adapter(self.config).material()
            env[key] = previous

    def test_nonprivate_config_refused(self):
        os.chmod(self.config, 0o644)
        with self.assertRaises(GateError):
            Adapter(self.config)

    def test_daemon_pid_is_never_a_service_target(self):
        adapter = Adapter(self.config)
        with patch.object(adapter, 'command', return_value=subprocess.CompletedProcess(
                [], 0, json.dumps(dict(pid=12345)).encode())) as command:
            with self.assertRaises(GateError):
                adapter.identity(12345, launching=True)
            self.assertEqual(command.call_count, 1)
            self.assertEqual(command.call_args.args[0][:3], ['multica', 'daemon', 'status'])

    def test_changed_live_identity_refuses_bootout(self):
        adapter = Adapter(self.config)
        atomic(adapter.owner, json.dumps(dict(label=adapter.label, pid=12345, identity='old')).encode())
        with patch.object(adapter, 'job', return_value=12345), \
                patch.object(adapter, 'identity', return_value='different'), \
                patch.object(adapter, 'command', side_effect=AssertionError('unsafe bootout')):
            with self.assertRaises(GateError):
                adapter.stop()

    def test_loaded_job_path_must_match_bootstrap_intent(self):
        adapter = Adapter(self.config)
        atomic(adapter.intent, json.dumps(dict(target=adapter.target, plist=str(adapter.plist))).encode())
        response = subprocess.CompletedProcess([], 0, b'\tpath = /Users/service.plist\n\tpid = 12345\n')
        with patch.object(adapter, 'command', return_value=response):
            with self.assertRaises(GateError):
                adapter.job()

    def test_absent_job_stop_does_not_require_consistent_pointers(self):
        adapter = Adapter(self.config)
        Path(self.c['plist']).unlink()
        with patch.object(adapter, 'job', return_value=None):
            adapter.stop()


if __name__ == '__main__':
    unittest.main()
