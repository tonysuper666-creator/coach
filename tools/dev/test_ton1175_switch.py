import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import plistlib
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from tools.dev import ton1175_switch as release

SCRIPT = Path(release.__file__).resolve()


class SwitchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old = self.root / 'old-release'
        self.old.mkdir()
        self.candidate = self.root / 'new-release'
        for name in ('app/bin/app', 'tools/bridge/.venv/bin/python', 'tools/mathsvc/src/server.mjs'):
            path = self.candidate / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture')
        (self.candidate / 'tools/mathsvc/node_modules').mkdir()
        self.head = 'a' * 40
        (self.candidate / 'source-head.txt').write_text(self.head + '\n')
        self.installation = self.root / 'installation.json'
        self.installation.write_text(json.dumps({'root': str(self.old), 'sourceHead': 'b' * 40}))
        self.plist = self.root / 'service.plist'
        self.plist.write_bytes(plistlib.dumps({'ProgramArguments': ['old'], 'WorkingDirectory': str(self.old),
                                               'EnvironmentVariables': {}}))
        self.old_record = self.installation.read_bytes()
        self.old_plist = self.plist.read_bytes()
        self.database = self.root / 'database'
        self.database.write_text('pre-switch write\n')
        self.running = self.root / 'running'
        self.running.write_text('old')
        self.controller = self.root / 'controller.py'
        self.controller.write_text('''import pathlib, sys\nr = pathlib.Path(sys.argv[1]); action = sys.argv[2]\nwith (r / "calls").open("a") as f: f.write(action + "\\n")\nif action == "stop":\n (r / "running").write_text("stopped")\n if (r / "request-on-stop").exists():\n  import datetime, json\n  with (r / "access.log").open("a") as f: f.write(json.dumps({"at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "route": "/v1/chat"}) + "\\n")\nelse:\n if (r / "fail-start").exists():\n  (r / "fail-start").unlink(); sys.exit(1)\n import json\n (r / "running").write_text(json.loads((r / "installation.json").read_text())["sourceHead"])\n''')
        self.verifier = self.root / 'verifier.py'
        self.verifier.write_text('import pathlib,sys\nsys.exit(1 if pathlib.Path(sys.argv[1]).exists() else 0)\n')
        self.snapshot = self.root / 'snapshot.py'
        self.snapshot.write_text('import os,sys\np=sys.argv[1]\nfd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)\nos.write(fd,b"{}")\nos.close(fd)\n')
        self.idle = self.root / 'idle.json'
        self.recording = self.root / 'recording.jfr'
        self.recording.write_bytes(b'fixture recording, checked by fixture verifier')
        self.log = self.root / 'access.log'
        self.log.write_text('')
        self.now = time.time()
        self.write_idle()
        self.ops = self.root / 'private-state'
        self.config = {'stateDir': str(self.ops), 'release': str(self.candidate), 'payload': str(self.candidate),
                       'installation': str(self.installation), 'plist': str(self.plist),
                       'expectedInstallationSha256': release.digest(self.old_record),
                       'expectedSourceHead': 'b' * 40, 'candidateHead': self.head,
                       'sourceHeadFileSha256': release.file_hash(self.candidate / 'source-head.txt'),
                       'candidateTreeSha256': release.tree_hash(self.candidate),
                       'controller': [sys.executable, str(self.controller), str(self.root)],
                       'idleEvidence': str(self.idle), 'accessLogs': [str(self.log)],
                       'candidateVerifier': [sys.executable, str(self.verifier), str(self.root / 'reject-candidate')],
                       'idleVerifier': [sys.executable, str(self.verifier), str(self.root / 'reject-idle')],
                       'logVerifier': [sys.executable, str(self.verifier), str(self.root / 'reject-logs')],
                       'acceptanceSnapshot': [sys.executable, str(self.snapshot)],
                       'acceptanceVerifier': [sys.executable, str(self.verifier), str(self.root / 'reject-acceptance')]}
        self.config_path = self.root / 'config.json'
        self.config_path.write_text(json.dumps(self.config))

    def write_idle(self, **changes):
        stamp = lambda seconds: dt.datetime.fromtimestamp(seconds, dt.timezone.utc).isoformat()
        evidence = {'windowStart': stamp(self.now - 915), 'windowEnd': stamp(self.now - 10),
                    'verifiedAtEpoch': self.now - 5, 'sourceHead': 'b' * 40,
                    'pid': 123, 'recording': str(self.recording), 'clientSocketReads': 0,
                    'latestClientRead': None}
        evidence.update(changes)
        self.idle.write_text(json.dumps(evidence))

    def invoke(self, action, apply=False, success=True):
        result = subprocess.run([sys.executable, str(SCRIPT), action, '--config', str(self.config_path)]
                                + (['--apply'] if apply else []), capture_output=True, text=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def stage(self):
        self.invoke('stage', True)

    def assert_old(self):
        self.assertEqual(self.installation.read_bytes(), self.old_record)
        self.assertEqual(self.plist.read_bytes(), self.old_plist)

    def test_dry_run_and_repeat_then_manual_rollback_preserves_writes(self):
        self.invoke('stage')
        self.assertFalse(self.ops.exists())
        self.stage()
        self.assertEqual(stat.S_IMODE(self.ops.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((self.ops / 'old.json').stat().st_mode), 0o600)
        self.invoke('stage', True)
        self.invoke('switch')
        self.assertFalse((self.root / 'calls').exists())
        self.assert_old()
        self.invoke('switch', True)
        self.assertEqual(json.loads(self.installation.read_text())['sourceHead'], self.head)
        self.assertEqual(self.running.read_text(), self.head)
        self.database.write_text(self.database.read_text() + 'after switch\n')
        calls = (self.root / 'calls').read_text()
        self.invoke('switch', True)
        self.assertEqual((self.root / 'calls').read_text(), calls)
        self.invoke('rollback')
        self.assertEqual(json.loads(self.installation.read_text())['sourceHead'], self.head)
        self.invoke('rollback', True)
        self.assert_old()
        self.assertEqual(self.running.read_text(), 'b' * 40)
        self.assertEqual(self.database.read_text(), 'pre-switch write\nafter switch\n')
        calls = (self.root / 'calls').read_text()
        self.invoke('rollback', True)
        self.assertEqual((self.root / 'calls').read_text(), calls)

    def test_start_failure_restores_old_and_retains_post_switch_data(self):
        self.stage()
        (self.root / 'fail-start').touch()
        result = self.invoke('switch', True, False)
        self.assertIn('old pointers restored', result.stderr)
        self.assert_old()
        self.assertEqual(self.running.read_text(), 'b' * 40)
        self.assertEqual(json.loads((self.ops / 'journal.json').read_text())['phase'], 'rolled_back')

    def test_refuses_mismatch_missing_stale_short_unverified_and_request(self):
        self.stage()
        for kind in ('pointer', 'plist', 'missing', 'stale', 'short', 'verifier', 'logs-verifier', 'request', 'release'):
            with self.subTest(kind=kind):
                self.installation.write_bytes(self.old_record)
                self.plist.write_bytes(self.old_plist)
                self.write_idle()
                self.log.write_text('')
                (self.root / 'reject-idle').unlink(missing_ok=True)
                (self.root / 'reject-logs').unlink(missing_ok=True)
                if kind == 'pointer':
                    self.installation.write_text('{}')
                elif kind == 'plist':
                    self.plist.write_bytes(b'external update')
                elif kind == 'missing':
                    self.idle.unlink()
                elif kind == 'stale':
                    self.write_idle(verifiedAtEpoch=self.now - 500)
                elif kind == 'short':
                    self.write_idle(windowStart=dt.datetime.fromtimestamp(self.now - 100, dt.timezone.utc).isoformat())
                elif kind == 'verifier':
                    (self.root / 'reject-idle').touch()
                elif kind == 'logs-verifier':
                    (self.root / 'reject-logs').touch()
                elif kind == 'request':
                    self.log.write_text(json.dumps({'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'route': '/v1/chat'}) + '\n')
                else:
                    (self.candidate / 'app/bin/app').write_text('changed')
                self.invoke('switch', True, False)
                self.assertEqual(self.running.read_text(), 'old')
                self.assertFalse((self.root / 'calls').exists())
        self.assertEqual(self.plist.read_bytes(), self.old_plist)

    def test_request_after_stop_aborts_and_restarts_old(self):
        self.stage()
        (self.root / 'request-on-stop').touch()
        self.invoke('switch', True, False)
        self.assert_old()
        self.assertEqual(self.running.read_text(), 'b' * 40)

    def test_failed_recovery_can_be_retried(self):
        self.stage()
        self.invoke('switch', True)
        (self.root / 'fail-start').touch()
        result = self.invoke('rollback', True, False)
        self.assertIn('recover --config CONFIG --apply', result.stderr)
        self.assertEqual(json.loads((self.ops / 'journal.json').read_text())['phase'], 'recovery_required')
        self.assert_old()
        self.invoke('recover', True)
        self.assert_old()
        self.assertEqual(self.running.read_text(), 'b' * 40)

    def test_partial_pointer_transaction_has_persistent_recovery(self):
        self.stage()
        (self.ops / 'journal.json').write_text(json.dumps({'phase': 'switching'}))
        self.plist.write_bytes((self.ops / 'new.plist').read_bytes())
        self.invoke('switch', True, False)
        self.invoke('recover', True)
        self.assert_old()
        self.assertEqual(self.running.read_text(), 'b' * 40)

    def test_stage_rejects_old_installation_change_and_missing_deps(self):
        self.installation.write_text('{}')
        self.invoke('stage', True, False)
        self.assertFalse(self.ops.exists())
        self.installation.write_bytes(self.old_record)
        (self.candidate / 'tools/mathsvc/src/server.mjs').unlink()
        self.invoke('stage', True, False)
        self.assertFalse(self.ops.exists())

    def test_prepare_copies_immutable_payload_only_after_apply(self):
        self.config['payload'] = str(self.candidate)
        self.config['release'] = str(self.root / 'prepared')
        self.config_path.write_text(json.dumps(self.config))
        self.invoke('prepare')
        self.assertFalse((self.root / 'prepared').exists())
        self.invoke('prepare', True)
        self.assertEqual(release.tree_hash(self.root / 'prepared'), self.config['candidateTreeSha256'])
        self.invoke('prepare', True)
        self.stage()

    def test_recovery_material_with_insecure_permissions_is_rejected(self):
        self.stage()
        (self.ops / 'old.json').chmod(0o644)
        self.invoke('switch', True, False)
        self.assertFalse((self.root / 'calls').exists())
        self.assert_old()

    def test_failed_second_pointer_write_automatically_restores(self):
        self.stage()
        real_atomic = release.atomic
        def fail_once(path, data, mode=0o600):
            if str(path) == str(self.installation) and json.loads(data)['sourceHead'] == self.head:
                raise OSError('fixture interrupted pointer replacement')
            return real_atomic(path, data, mode)
        with mock.patch.object(release, 'atomic', side_effect=fail_once):
            with self.assertRaises(release.GateError):
                release.operate(self.config, 'switch', True)
        self.assert_old()
        self.assertEqual(self.running.read_text(), 'b' * 40)

    def test_missing_acceptance_contract_cannot_stage(self):
        del self.config['acceptanceVerifier']
        self.config_path.write_text(json.dumps(self.config))
        self.invoke('stage', True, False)
        self.assertFalse(self.ops.exists())

    def test_acceptance_failure_rolls_back_and_preserves_new_write(self):
        self.verifier.write_text('''import pathlib,sys
marker=pathlib.Path(sys.argv[1])
if marker.name=='reject-acceptance':
 with (marker.parent/'database').open('a') as f: f.write('accepted new write\\n')
 sys.exit(1)
''')
        self.stage()
        self.invoke('switch', True, False)
        self.assert_old()
        self.assertEqual(self.running.read_text(), 'b' * 40)
        self.assertIn('accepted new write', self.database.read_text())
        self.assertEqual((self.root / 'calls').read_text().splitlines(), ['stop', 'start', 'stop', 'start'])

    def test_acceptance_timeout_kills_descendants_and_rolls_back(self):
        self.verifier.write_text('''import pathlib,subprocess,sys,time
marker=pathlib.Path(sys.argv[1])
if marker.name=='reject-acceptance':
 subprocess.Popen([sys.executable,'-c',"import pathlib,time;time.sleep(1);pathlib.Path("+repr(str(marker.parent/'orphan-write'))+").touch()"])
 time.sleep(10)
''')
        self.stage()
        real_run = release.run
        def short_timeout(argv, timeout=60):
            if str(self.root / 'reject-acceptance') in argv:
                self.assertGreater(timeout, 299)
                timeout = 0.1
            return real_run(argv, timeout=timeout)
        with mock.patch.object(release, 'run', side_effect=short_timeout):
            with self.assertRaises(release.GateError):
                release.operate(self.config, 'switch', True)
        self.assert_old()
        # Foreground wait checks a concrete late-write hazard, not external CI.
        time.sleep(1.1)
        self.assertFalse((self.root / 'orphan-write').exists())

    def test_sigterm_during_acceptance_rolls_back(self):
        self.verifier.write_text('''import pathlib,sys,time
marker=pathlib.Path(sys.argv[1])
if marker.name=='reject-acceptance':
 (marker.parent/'validating').touch()
 time.sleep(10)
''')
        self.stage()
        child = subprocess.Popen([sys.executable, str(SCRIPT), 'switch', '--config',
                                  str(self.config_path), '--apply'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while not (self.root / 'validating').exists() and child.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue((self.root / 'validating').exists())
            child.send_signal(signal.SIGTERM)
            _, errors = child.communicate(timeout=5)
            self.assertNotEqual(child.returncode, 0)
            self.assertIn(b'old pointers restored', errors)
            self.assert_old()
            self.assertEqual(self.running.read_text(), 'b' * 40)
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()

    def test_missing_baseline_refuses_before_stop(self):
        self.snapshot.write_text('pass\n')
        self.stage()
        self.invoke('switch', True, False)
        self.assert_old()
        self.assertFalse((self.root / 'calls').exists())

    def test_validating_phase_is_recoverable(self):
        self.stage()
        (self.ops / 'journal.json').write_text(json.dumps({'phase': 'validating'}))
        self.plist.write_bytes((self.ops / 'new.plist').read_bytes())
        self.installation.write_bytes((self.ops / 'new.json').read_bytes())
        self.invoke('recover', True)
        self.assert_old()


if __name__ == '__main__':
    unittest.main()
