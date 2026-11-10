import datetime as dt
import http.server
import json
import os
from pathlib import Path
import plistlib
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

from tools.dev import ton1175_verify as verify
from tools.dev import test_ton1460_idle as idle_fixtures


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.db_path = self.root / 'ledger.sqlite'
        self.db = sqlite3.connect(self.db_path)
        self.addCleanup(self.db.close)
        self.db.executescript('''CREATE TABLE ledger_meta(singleton INTEGER,epoch TEXT);
            INSERT INTO ledger_meta VALUES(1,'fixture-epoch');
            CREATE TABLE devices(device_id TEXT,token_sha256 TEXT,revoked_at INTEGER);
            CREATE TABLE events(seq INTEGER PRIMARY KEY,course_id TEXT,thread_id TEXT,type TEXT,
                recorded_at INTEGER,device_id TEXT,payload TEXT);
            CREATE TABLE messages(message_id TEXT,thread_id TEXT,status TEXT,role TEXT,content_md TEXT,created_at INTEGER);
        ''')
        self.secret = 'private-fixture-token-never-print-' * 2
        self.token = self.root / 'token'
        self.token.write_text(self.secret)
        self.log = self.root / 'access.log'
        self.log.write_bytes(b'')
        self.db.executemany('INSERT INTO devices VALUES(?,?,NULL)',
                            [('desktop', verify.digest(self.secret.encode())), ('original-phone', 'phone-hash')])
        self.db.commit()
        self.config = {'database': str(self.db_path), 'desktopTokenFile': str(self.token),
                       'desktopDevice': 'desktop', 'phoneDevice': 'original-phone',
                       'course': 'course', 'thread': 'thread', 'accessLogs': [str(self.log)],
                       'helloUrl': 'http://127.0.0.1:1/v1/hello'}
        self.config_path = self.root / 'acceptance.json'
        self.baseline = self.root / 'baseline.json'
        self.save_config()
        verify.snapshot(self.config_path, self.baseline)
        self.started = time.time()
        self.deadline = self.started + 5

    def save_config(self):
        self.config_path.write_text(json.dumps(self.config))

    def event(self, kind, payload, device='desktop', thread='thread'):
        self.db.execute('INSERT INTO events(course_id,thread_id,type,recorded_at,device_id,payload) VALUES(?,?,?,?,?,?)',
                        ('course', thread, kind, int((self.started+0.01)*1000), device, json.dumps(payload)))

    def evidence(self):
        return verify.positive_evidence(self.config, verify.load(self.baseline), self.started, self.deadline)

    def learning(self, count=3):
        self.db.execute('INSERT INTO messages VALUES(?,?,?,?,?,?)',
                        ('lecture-message', 'thread', 'complete', 'assistant', 'private fixture body',
                         int((self.started+0.01)*1000)))
        self.event('explanation_delivered', {'msgId': 'lecture-message'})
        for i in range(count):
            payload = {'attemptId': str(i), 'groupId': 'new-group'}
            self.event('question_posed', payload)
            self.event('answer_submitted', payload)
            self.event('attempt_graded', payload, device='server')
        self.db.commit()

    def phone(self, device='original-phone', status=200, when=None):
        when = self.started + 0.01 if when is None else when
        with self.log.open('a') as stream:
            stream.write(json.dumps({'at': dt.datetime.fromtimestamp(when, dt.timezone.utc).isoformat(),
                                     'device': device, 'status': status, 'route': '/v1/hello', 'method': 'GET'})+'\n')

    def test_original_phone_complete_lecture_and_correlated_three_attempts(self):
        self.learning()
        self.assertFalse(self.evidence())
        self.phone('synthetic-new-phone')
        self.phone(status=401)
        self.phone(when=self.started-10)
        self.assertFalse(self.evidence())
        self.phone()
        self.assertTrue(self.evidence())
        self.assertEqual(self.baseline.stat().st_mode & 0o777, 0o600)
        # Read-only checking must not change the ledger or the original credential.
        before = verify.file_hash(self.db_path)
        self.assertTrue(self.evidence())
        self.assertEqual(verify.file_hash(self.db_path), before)
        self.assertNotIn(self.secret, self.baseline.read_text())

    def test_no_substitution_of_messages_two_answers_or_other_groups(self):
        self.learning(count=2)
        self.phone()
        self.assertFalse(self.evidence())
        self.event('question_posed', {'attemptId':'2', 'groupId':'other-group'})
        self.event('answer_submitted', {'attemptId':'2'})
        self.event('attempt_graded', {'attemptId':'2'})
        self.db.commit()
        self.assertFalse(self.evidence())
        self.db.execute("UPDATE events SET payload=? WHERE type='question_posed' AND json_extract(payload,'$.attemptId')='2'",
                        (json.dumps({'attemptId':'2', 'groupId':'new-group'}),))
        self.db.execute("DELETE FROM events WHERE type='explanation_delivered'")
        self.db.commit()
        self.assertFalse(self.evidence())

    def test_token_epoch_rotation_and_log_replacement_fail_closed(self):
        self.learning()
        self.phone()
        for change in ('phone', 'desktop', 'epoch', 'rotation', 'truncated'):
            with self.subTest(change=change):
                if change == 'phone':
                    self.db.execute("UPDATE devices SET token_sha256='repaired-token' WHERE device_id='original-phone'")
                elif change == 'desktop':
                    self.token.write_text('replacement-secret')
                elif change == 'epoch':
                    self.db.execute("UPDATE ledger_meta SET epoch='new-epoch'")
                elif change == 'rotation':
                    self.log.rename(self.root / 'rotated.log')
                    self.log.write_text('')
                else:
                    # Bind a nonempty prefix, then prove truncation is rejected.
                    verify.snapshot(self.config_path, self.baseline)
                    self.log.write_text('')
                self.db.commit()
                with self.assertRaises(verify.GateError):
                    self.evidence()
                self.db.execute("UPDATE devices SET token_sha256='phone-hash' WHERE device_id='original-phone'")
                self.db.execute("UPDATE ledger_meta SET epoch='fixture-epoch'")
                self.db.commit()
                self.token.write_text(self.secret)
                if (self.root / 'rotated.log').exists():
                    self.log.unlink()
                    (self.root / 'rotated.log').rename(self.log)

    def test_current_access_sink_always_refuses_negative_coverage_claim(self):
        legacy = self.root / 'legacy-coverage.json'
        legacy.write_text('{"coverage":true,"dropped":0}')
        for text in ('', '{"coverage":true,"dropped":0}\n'):
            self.log.write_text(text)
            with self.assertRaisesRegex(verify.GateError, 'lossless coverage'):
                verify.logs(legacy, [str(self.log)])

    def test_original_desktop_hello_against_isolated_http_server(self):
        owner = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                valid = self.headers.get('Authorization') == 'Bearer '+owner.secret
                valid = valid and self.headers.get('X-Coach-Device') == 'desktop' and self.path == '/v1/hello'
                self.send_response(200 if valid else 401)
                self.end_headers()
                self.wfile.write(b'{"epoch":"fixture-epoch"}')
            def log_message(self, *args):
                pass
        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            self.config['helloUrl'] = f'http://127.0.0.1:{server.server_port}/v1/hello'
            self.save_config()
            verify.snapshot(self.config_path, self.baseline)
            self.started = time.time()
            self.deadline = self.started+2
            self.learning()
            self.phone()
            verify.accept(self.config_path, self.baseline, self.started, self.deadline)
            self.token.write_text('wrong-credential')
            with self.assertRaises(verify.GateError):
                verify.accept(self.config_path, self.baseline, self.started, self.deadline)
            # Wire the real snapshot/accept CLI into a pointer transaction.
            # Only candidate/idle/coverage and service control are fixtures.
            from tools.dev import test_ton1175_switch as transaction_tests
            transaction = transaction_tests.SwitchTest()
            transaction.setUp()
            try:
                self.token.write_text(self.secret)
                transaction.config['acceptanceSnapshot'] = [sys.executable, verify.__file__, 'snapshot',
                                                            '--config', str(self.config_path)]
                transaction.config['acceptanceVerifier'] = [sys.executable, verify.__file__, 'accept',
                                                            '--config', str(self.config_path)]
                transaction.config_path.write_text(json.dumps(transaction.config))
                # Candidate startup loses the original credential and writes a
                # new ledger row. Real HTTP authentication must trigger rollback.
                with transaction.controller.open('a') as script:
                    script.write('\nif action == "start" and (r / "running").read_text() == '+repr(transaction.head)+':\n'
                                 ' import sqlite3\n'
                                 ' pathlib.Path('+repr(str(self.token))+').write_text("lost-credential")\n'
                                 ' with sqlite3.connect('+repr(str(self.db_path))+') as db:\n'
                                 '  db.execute("INSERT INTO events(type,payload) VALUES(?,?)", '
                                 '("fixture-post-switch-write", "{}"))\n')
                transaction.stage()
                transaction.invoke('switch', True, False)
                transaction.assert_old()
                self.assertEqual(transaction.running.read_text(), 'b'*40)
                self.assertEqual(verify.load(transaction.ops/'journal.json')['phase'], 'rolled_back')
                self.assertEqual(self.db.execute("SELECT count(*) FROM events WHERE type='fixture-post-switch-write'")
                                 .fetchone()[0], 1)
            finally:
                transaction.doCleanups()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_missing_phone_times_out_without_inventing_traffic(self):
        with mock.patch.object(verify, 'hello'):
            with self.assertRaisesRegex(verify.GateError, 'timed out'):
                verify.accept(self.config_path, self.baseline, self.started, time.time()+0.05)

    def test_forbidden_hello_destination_never_receives_credentials(self):
        self.config['helloUrl'] = 'http://example.invalid/v1/hello'
        with self.assertRaises(verify.GateError), mock.patch.object(verify.http.client, 'HTTPConnection') as network:
            verify.hello(self.config, 'fixture-epoch', 1)
        network.assert_not_called()

    def test_cli_failure_redacts_database_and_token_exception_data(self):
        self.config['database'] = str(self.root / self.secret)
        self.save_config()
        result = subprocess.run([sys.executable, verify.__file__, 'snapshot', '--config', str(self.config_path),
                                 str(self.baseline)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(self.secret, result.stdout+result.stderr)


class CandidateTest(unittest.TestCase):
    def test_actual_venv_pinned_import_and_actual_math_render(self):
        # A tiny local dependency with a dist-info record exercises the real
        # subprocess/import/version/origin checks without pip or network access.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            venv = root / 'tools/bridge/.venv'
            subprocess.run([sys.executable, '-m', 'venv', '--without-pip', str(venv)], check=True)
            site = next((venv / 'lib').glob('python*/site-packages'))
            (site / 'gate_fixture.py').write_text('VALUE=1\n')
            meta = site / 'gate_fixture-1.0.dist-info'
            meta.mkdir()
            (meta / 'METADATA').write_text('Metadata-Version: 2.1\nName: gate_fixture\nVersion: 1.0\n')
            requirements = root / 'tools/bridge/requirements.txt'
            requirements.write_text('gate_fixture==1.0\n')
            # Use the repository's real MathJax renderer with a disposable copy
            # of cached npm dependencies; no production files or services.
            math = Path(__file__).parents[1] / 'mathsvc'
            require_node = shutil.which('node')
            self.assertIsNotNone(require_node, 'Node 22 is required for this gate')
            self.assertTrue((math / 'node_modules/mathjax-full').is_dir(), 'run npm ci in tools/mathsvc first')
            shutil.copytree(math / 'src', root / 'tools/mathsvc/src')
            (root / 'tools/mathsvc/node_modules').symlink_to(math / 'node_modules', target_is_directory=True)
            before = verify.tree_hash(root)
            external = verify.dependencies(root, require_node)
            self.assertEqual(verify.tree_hash(root), before, 'verification mutated final release')
            self.assertIn(str((venv / 'bin/python').resolve()), external)
            requirements.write_text('gate_fixture==9.9\n')
            with self.assertRaises(verify.GateError):
                verify.dependencies(root, require_node)
            requirements.write_text('gate_fixture==1.0\n')
            (root / 'tools/mathsvc/node_modules').unlink()
            with self.assertRaises(verify.GateError):
                verify.dependencies(root, require_node)

    def test_manifest_hash_and_runtime_cannot_be_substituted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / 'manifest'
            manifest.write_text('{}')
            with self.assertRaises(verify.GateError):
                verify.candidate(manifest, 'wrong-review-hash', root, 'a'*40, root/'plist')
            release = root/'release'
            (release/'app/bin').mkdir(parents=True)
            app = release/'app/bin/app'
            app.write_text('fixture')
            app.chmod(0o700)
            plist = root/'plist'
            plist.write_bytes(plistlib.dumps({'EnvironmentVariables':
                {'COACH_NODE': '/foreign/node', 'JAVA_HOME': '/foreign/jdk'}}))
            proof = {'version':1, 'sourceHead':'a'*40, 'root':str(release.resolve()),
                     'treeSha256':verify.tree_hash(release), 'externalExecutables':{},
                     'node':'/reviewed/node', 'java':'/reviewed/jdk/bin/java'}
            manifest.write_text(json.dumps(proof))
            with self.assertRaisesRegex(verify.GateError, 'service runtime'):
                verify.candidate(manifest, verify.file_hash(manifest), release, 'a'*40, plist)
            (release/'external-library').symlink_to(root/'manifest')
            with self.assertRaisesRegex(verify.GateError, 'unbound external release symlink'):
                verify.release_links(release)
            with self.assertRaisesRegex(verify.GateError, 'differs from attested'):
                verify.candidate(manifest, verify.file_hash(manifest), release, 'a'*40, plist)


class IdleTest(unittest.TestCase):
    """Exercise the real idle orchestrator with synthetic process/JFR evidence.

    These tests retain the module's real platform dependencies. The independent
    test_ton1460_idle suite covers the pure policy on Windows without fcntl.
    """
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.start = 1794268800
        self.now = self.start + 910
        self.pid, self.bridge_pid, self.guard_pid = 1802, 300, 301
        self.info = dict(root=self.root.as_posix(), runDir=(self.root / 'run').as_posix(),
                         dataDir=str(self.root / 'data'), sourceHead='a' * 40)
        Path(self.info['runDir']).mkdir()
        self.state = dict(pid=self.bridge_pid, guardPid=self.guard_pid, ownerPid=self.pid,
                          healthy=True, checkedAt=self.now * 1000)
        self.state_path = Path(self.info['runDir']) / 'bridge.json'
        self.state_path.write_text(json.dumps(self.state))
        self.ps = idle_fixtures.process(self.start - 2000,
            'java -cp ' + str(self.root / 'app/lib') + ' com.coach.server.MainKt')
        self.bridge = idle_fixtures.process(self.start - 1000,
            'python ' + self.info['root'] + '/tools/bridge/subscription_bridge.py')
        self.guard = idle_fixtures.process(self.start - 1001,
            'python ' + self.info['root'] + '/tools/dev/service-child.py '
            + self.info['runDir'] + '/bridge-1802.pid ' + self.info['root'] + '/tools/bridge/run.sh')
        binding = verify.idle_checks.bridge_identity(self.state, self.pid, self.bridge, self.guard,
            self.guard_pid, self.pid, self.info['root'], self.info['runDir'], self.start, self.now)
        self.installation = self.root / 'installation.json'
        self.installation.write_text(json.dumps(self.info))
        self.recording = self.root / 'idle.jfr'
        self.recording.write_bytes(b'synthetic recording hash, inspector output is mocked')
        self.proof = dict(pid=self.pid, sourceHead=self.info['sourceHead'],
            installationSha256=verify.file_hash(self.installation), recording=str(self.recording),
            recordingSha256=verify.file_hash(self.recording), processIdentitySha256=verify.digest(self.ps),
            bridgeIdentitySha256=verify.digest(binding), windowStart=idle_fixtures.stamp(self.start),
            windowEnd=idle_fixtures.stamp(self.start + 900), clientSocketReads=0)
        self.evidence = self.root / 'idle.json'
        self.measured = dict(idle_fixtures.health_recording(self.start), pid=self.pid,
                             jvmStart=idle_fixtures.stamp(self.start - 1999.875))
        self.lock_owner = self.pid
        self.listener_owner = self.bridge_pid
        self.after_inspect = lambda: None
        self.captured = mock.Mock(side_effect=self.capture)
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(verify, 'captured', self.captured).start()
        mock.patch.object(verify.time, 'time', return_value=self.now).start()
        # Always exercise Darwin's owner check as an independent boundary;
        # the existing JfrTest below checks its real kernel implementation.
        mock.patch.object(verify.sys, 'platform', 'darwin').start()
        self.kernel_lock = mock.patch.object(verify, 'darwin_lock_owner', side_effect=self.check_lock).start()

    def check_lock(self, path, pid):
        self.assertEqual(path, Path(self.info['dataDir']) / 'server.lock')
        verify.require(int(pid) == self.lock_owner, 'recorded JVM does not exclusively own whole-file server lock')

    def capture(self, argv, **kwargs):
        if argv[0] == '/bin/ps':
            pid = int(argv[2])
            if argv[-1] == 'ppid=':
                return str({self.bridge_pid: self.guard_pid, self.guard_pid: self.pid}[pid]).encode()
            self.assertEqual(kwargs['env']['TZ'], 'UTC')
            self.assertEqual(kwargs['env']['LC_ALL'], 'C')
            return {self.pid: self.ps, self.bridge_pid: self.bridge, self.guard_pid: self.guard}[pid]
        if argv[0] == 'fixture-lsof':
            return f'p{self.listener_owner}\nn127.0.0.1:8791\n'.encode()
        if argv[0] == 'fixture-java':
            self.after_inspect()
            return json.dumps(self.measured).encode()
        raise AssertionError('unexpected command; VM.uptime must never be used')

    def run_idle(self):
        self.evidence.write_text(json.dumps(self.proof))
        verify.idle(self.evidence, self.installation, 'fixture-java', 'forbidden-jcmd', 'fixture-lsof')

    def test_full_health_window_and_sleep_bind_same_lifetime_without_uptime(self):
        self.run_idle()
        self.assertEqual(self.kernel_lock.call_count, 2)
        self.assertFalse(any(call.args[0][0] == 'forbidden-jcmd' for call in self.captured.call_args_list))

    def test_client_read_cannot_be_hidden_by_callers_zero_count(self):
        self.measured['reads'][40]['healthOrigin'] = False
        with self.assertRaisesRegex(verify.GateError, 'non-health'):
            self.run_idle()

    def test_rehearsal_idle_hook_accepts_strict_zero_reads_without_managed_bridge(self):
        from tools.dev import rehearse_ton1175_launchd as rehearsal
        self.measured.update(socketReads=0, reads=[])
        self.info.pop('runDir')
        self.installation.write_text(json.dumps(self.info))
        self.proof['installationSha256'] = verify.file_hash(self.installation)
        self.proof.pop('bridgeIdentitySha256')
        self.evidence.write_text(json.dumps(self.proof))
        (self.root / 'rehearsal.json').write_text(json.dumps(dict(
            case='normal', java='fixture-java', jcmd='forbidden-jcmd')))
        with mock.patch.object(rehearsal, 'Adapter'):
            rehearsal.hook('idle', self.root, [self.evidence, self.installation])
        self.assertEqual(self.kernel_lock.call_count, 2)
        self.assertTrue(all(call.args[0][0] in ('/bin/ps', 'fixture-java')
                            for call in self.captured.call_args_list))

    def test_positive_reads_still_require_observed_bridge_binding(self):
        self.proof.pop('bridgeIdentitySha256')
        with self.assertRaises(KeyError):
            self.run_idle()

    def test_new_lifetime_handles_fork_boundary_but_rejects_five_seconds(self):
        self.measured['jvmStart'] = idle_fixtures.stamp(self.start - 1998.93)
        self.run_idle()
        self.measured['jvmStart'] = idle_fixtures.stamp(self.start - 1995)
        with self.assertRaisesRegex(verify.GateError, 'different JVM lifetime'):
            self.run_idle()

    def test_short_stale_future_window_still_rejected(self):
        for start, end in ((self.start, self.start + 899), (self.start - 131, self.start + 769),
                           (self.start, self.now + 1)):
            self.proof.update(windowStart=idle_fixtures.stamp(start), windowEnd=idle_fixtures.stamp(end))
            with self.subTest(start=start, end=end), self.assertRaisesRegex(verify.GateError, 'short, stale or future'):
                self.run_idle()
        self.captured.assert_not_called()

    def test_pid_reuse_wrong_jfr_pid_and_different_lifetime_rejected(self):
        for field, value in (('pid', self.pid + 1),
                             ('jvmStart', idle_fixtures.stamp(self.start - 13313))):
            old = self.measured[field]
            self.measured[field] = value
            with self.subTest(field=field), self.assertRaises(verify.GateError):
                self.run_idle()
            self.measured[field] = old
        self.ps = self.ps.replace(b'java', b'foreign-java', 1)
        with self.assertRaisesRegex(verify.GateError, 'process identity changed'):
            self.run_idle()

    def test_foreign_lock_listener_and_missing_bridge_attestation_rejected(self):
        self.lock_owner += 1
        with self.assertRaisesRegex(verify.GateError, 'whole-file server lock'):
            self.run_idle()
        self.lock_owner = self.pid
        self.listener_owner += 1
        with self.assertRaisesRegex(verify.GateError, 'health listener'):
            self.run_idle()
        self.listener_owner = self.bridge_pid
        self.proof['bridgeIdentitySha256'] = 'unbound'
        with self.assertRaisesRegex(verify.GateError, 'bridge identity changed'):
            self.run_idle()

    def test_recording_and_installation_hashes_checked_before_and_after(self):
        for path in (self.recording, self.installation):
            original = path.read_bytes()
            path.write_bytes(original + b'\n')
            with self.subTest(path=path.name, phase='before'), self.assertRaises(verify.GateError):
                self.run_idle()
            path.write_bytes(original)
            self.after_inspect = lambda: path.write_bytes(original + b'\n')
            with self.subTest(path=path.name, phase='after'), self.assertRaisesRegex(verify.GateError, 'identity changed'):
                self.run_idle()
            path.write_bytes(original)
            self.after_inspect = lambda: None

    def test_process_or_bridge_replacement_during_inspection_rejected(self):
        def replace_process():
            self.ps += b' changed'
        self.after_inspect = replace_process
        old = self.ps
        with self.assertRaisesRegex(verify.GateError, 'process identity changed'):
            self.run_idle()
        self.ps = old
        def replace_bridge():
            self.bridge += b' changed'
        self.after_inspect = replace_bridge
        with self.assertRaises(verify.GateError):
            self.run_idle()


class JfrTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == 'darwin', 'Darwin kernel ABI')
    def test_kernel_query_binds_actual_java_whole_file_lock_and_rejects_other_owners(self):
        java_home = os.environ.get('JAVA_HOME')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'LockOwner.java'
            source.write_text('''import java.nio.channels.*;import java.nio.file.*;
class LockOwner { public static void main(String[] args)throws Exception {
 try(FileChannel c=FileChannel.open(Path.of(args[0]),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
     FileLock l=c.tryLock()) {System.out.println("ready");System.out.flush();Thread.sleep(30000);}
}}''')
            path = root / 'server.lock'
            child = subprocess.Popen([java, str(source), str(path)], stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True)
            try:
                self.assertEqual(child.stdout.readline().strip(), 'ready')
                verify.darwin_lock_owner(path, child.pid)
                with self.assertRaisesRegex(verify.GateError, 'whole-file'):
                    verify.darwin_lock_owner(path, os.getpid())
                link = root / 'link'
                link.symlink_to(path)
                with self.assertRaises(OSError):
                    verify.darwin_lock_owner(link, child.pid)
            finally:
                child.terminate()
                child.wait(timeout=10)
                child.stdout.close()
            with self.assertRaisesRegex(verify.GateError, 'whole-file'):
                verify.darwin_lock_owner(path, child.pid)

    @unittest.skipUnless(sys.platform == 'darwin', 'Darwin kernel ABI')
    def test_kernel_query_rejects_partial_or_shared_locks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'server.lock'
            for mode in ('partial', 'shared'):
                with self.subTest(mode=mode):
                    child = subprocess.Popen([sys.executable, '-u', '-c',
                        'import fcntl,sys,time\n'
                        'f=open(sys.argv[1],"w+")\n'
                        'fcntl.lockf(f, fcntl.LOCK_SH if sys.argv[2]=="shared" else fcntl.LOCK_EX, '
                        '0 if sys.argv[2]=="shared" else 1)\n'
                        'print("ready",flush=True);time.sleep(30)', str(path), mode],
                        stdout=subprocess.PIPE, text=True)
                    try:
                        self.assertEqual(child.stdout.readline().strip(), 'ready')
                        with self.assertRaisesRegex(verify.GateError, 'whole-file'):
                            verify.darwin_lock_owner(path, child.pid)
                    finally:
                        child.terminate()
                        child.wait(timeout=10)
                        child.stdout.close()

    def test_real_recording_detects_socket_read_wrong_pid_and_short_window(self):
        java_home = os.environ.get('JAVA_HOME')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        self.assertTrue(java and javac, 'JDK 17 is required for the real JFR fixture')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            classes = root / 'app/lib'
            classes.mkdir(parents=True)
            source = root / 'MainKt.java'
            source.write_text('''package com.coach.server;
import jdk.jfr.*;import java.time.*;import java.nio.file.*;import java.net.*;
public class MainKt {
 public static void main(String[] args)throws Exception {
  try(Recording r=new Recording()) {
   r.enable("jdk.JVMInformation").withPeriod(Duration.ofMillis(20));
   r.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(20));
   r.enable("jdk.ActiveSetting");r.enable("jdk.DataLoss");
   r.enable("jdk.SocketRead").withThreshold(Duration.ZERO).withStackTrace();
   r.start();Thread.sleep(1500);Instant start=Instant.now();
   Thread.sleep(150);Instant quietEnd=Instant.now();
   try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
       Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());
       Socket accepted=server.accept()) {
    client.getOutputStream().write(1);accepted.getInputStream().read();
   }
   Instant busyEnd=Instant.now();Thread.sleep(1500);r.stop();
   r.dump(Path.of(System.getenv("GATE_JFR_OUTPUT")));
   System.out.printf("{\\"pid\\":%d,\\"start\\":\\"%s\\",\\"quietEnd\\":\\"%s\\",\\"busyEnd\\":\\"%s\\"}%n",
      ProcessHandle.current().pid(),start,quietEnd,busyEnd);
  }
 }
}''')
            subprocess.run([javac, '-d', str(classes), str(source)], capture_output=True, check=True)
            recording = root / 'actual.jfr'
            result = subprocess.run([java, '-cp', str(classes), 'com.coach.server.MainKt'],
                                    capture_output=True, check=True, text=True,
                                    env=dict(os.environ, GATE_JFR_OUTPUT=str(recording)))
            proof = json.loads(result.stdout)
            inspector = Path(verify.__file__).with_name('Ton1175Jfr.java')
            def inspect(end, pid=None):
                return subprocess.run([java, str(inspector), str(recording), proof['start'], end,
                                       str(pid or proof['pid'])], capture_output=True, text=True)
            quiet = inspect(proof['quietEnd'])
            self.assertEqual(quiet.returncode, 0, quiet.stderr)
            self.assertEqual(json.loads(quiet.stdout)['socketReads'], 0)
            busy = inspect(proof['busyEnd'])
            self.assertEqual(busy.returncode, 0, busy.stderr)
            self.assertGreater(json.loads(busy.stdout)['socketReads'], 0)
            self.assertNotEqual(inspect(proof['busyEnd'], proof['pid']+1).returncode, 0)
            # The Python entrypoint independently rejects a sub-15-minute window
            # even when the real JFR parser above can inspect it successfully.
            installation = root / 'installation.json'
            installation.write_text(json.dumps({'root':str(root), 'sourceHead':'a'*40}))
            evidence = root / 'idle.json'
            evidence.write_text(json.dumps({'pid':proof['pid'], 'sourceHead':'a'*40,
                'installationSha256':verify.file_hash(installation),
                'windowStart':proof['start'], 'windowEnd':proof['busyEnd']}))
            with self.assertRaisesRegex(verify.GateError, 'short, stale or future'):
                verify.idle(evidence, installation, java, 'unused-jcmd', 'unused-lsof')


if __name__ == '__main__':
    unittest.main()
