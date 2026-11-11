"""Cross-language protocol tests. Gradle supplies the test-only JVM classpath.

Real loopback HTTP/WS and Unix sockets, isolated data and filesystem faults;
only the 901-second clock interval is injected. No production bridge/phone.
"""
import http.client
import datetime as dt
import json
import os
from pathlib import Path
import select
import signal
import socket
import subprocess
import tempfile
import time
import unittest
import sys
from unittest import mock

from .activity_coverage import Control, chain, healthy, stop_guard, verify
from .ton1175_switch import GateError


def startup_checkpoint(control, timeout=5):
    """Wait for bootstrap/ResponseSent to drain before starting the idle proof.

    This is test setup only: observation and stop gates still fail immediately
    on activity. Log loss, closure and control errors must never be retried.
    """
    deadline = time.monotonic()+timeout
    while True:
        point = control.ask('snapshot')
        if point['failures'] != 0 or point['closed'] or (point['active'] == 0 and point['workQuiet']):
            healthy(point)
            return point
        remaining = deadline-time.monotonic()
        if remaining <= 0:
            raise AssertionError(f'startup coverage did not settle: {point}')
        time.sleep(min(.01, remaining))


class StartupCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.ready = dict(failures=0, active=0, workQuiet=True, closed=False)

    def test_waits_for_both_request_completion_and_detached_work(self):
        control = mock.Mock()
        control.ask.side_effect = [dict(self.ready, active=1), dict(self.ready, workQuiet=False), self.ready]
        with mock.patch.object(time, 'sleep'), mock.patch.object(time, 'monotonic', return_value=0):
            self.assertIs(self.ready, startup_checkpoint(control))
        self.assertEqual([mock.call('snapshot')]*3, control.ask.call_args_list)

    def test_ready_checkpoint_does_not_sleep(self):
        control = mock.Mock()
        control.ask.return_value = self.ready
        with mock.patch.object(time, 'sleep') as sleep:
            self.assertIs(self.ready, startup_checkpoint(control))
        sleep.assert_not_called()

    def test_sticky_log_loss_and_closure_fail_without_retry(self):
        for fault in (dict(failures=1), dict(failures=1, active=1), dict(closed=True)):
            with self.subTest(fault=fault):
                control = mock.Mock()
                control.ask.side_effect = [dict(self.ready, **fault), self.ready]
                with mock.patch.object(time, 'sleep') as sleep, self.assertRaises(GateError):
                    startup_checkpoint(control)
                control.ask.assert_called_once_with('snapshot')
                sleep.assert_not_called()

    def test_control_errors_are_not_retried(self):
        control = mock.Mock()
        error = GateError('coverage unavailable')
        control.ask.side_effect = error
        with self.assertRaises(GateError) as raised:
            startup_checkpoint(control)
        self.assertIs(error, raised.exception)
        control.ask.assert_called_once_with('snapshot')

    def test_busy_checkpoint_times_out_with_last_state(self):
        for busy in (dict(active=1), dict(workQuiet=False)):
            with self.subTest(busy=busy):
                control = mock.Mock()
                control.ask.return_value = dict(self.ready, **busy)
                with mock.patch.object(time, 'monotonic', side_effect=[10, 10, 15]), \
                        mock.patch.object(time, 'sleep'), \
                        self.assertRaisesRegex(AssertionError, 'startup coverage did not settle:') as raised:
                    startup_checkpoint(control, timeout=5)
                self.assertIn(str(control.ask.return_value), str(raised.exception))
                self.assertEqual(2, control.ask.call_count)


class Service:
    def __init__(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cc-', dir='/tmp')
        self.root = Path(self.temp.name)
        self.error = (self.root / 'stderr').open('w')
        self.child = subprocess.Popen([
            os.environ['COACH_COVERAGE_JAVA'], '-cp', os.environ['COACH_COVERAGE_CLASSPATH'],
            'com.coach.server.ActivityCoverageProtocolTestKt', str(self.root)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.error, text=True, bufsize=1)
        try:
            self.port = self.read()['port']
        except BaseException:
            self.close()
            raise
        self.socket = str(self.root / 'control.sock')

    def read(self):
        # JVM/library startup can print diagnostic lines. Only consume closed JSON objects.
        deadline = time.monotonic()+20
        while time.monotonic() < deadline:
            if not select.select([self.child.stdout], [], [], max(0, deadline-time.monotonic()))[0]:
                break
            line = self.child.stdout.readline()
            if not line:
                break
            if line.startswith('{'):
                return json.loads(line)
        raise AssertionError('isolated JVM did not reply: '+(self.root / 'stderr').read_text()[-1500:])

    def command(self, command, **kw):
        self.child.stdin.write(json.dumps(dict(command=command, **kw))+'\n')
        self.child.stdin.flush()
        if command in ('stop', 'crash'):
            self.child.wait(timeout=10)
            return
        return self.read()

    def snapshot(self):
        with Control(self.socket) as c:
            return c.ask('snapshot')

    def request(self):
        c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        try:
            c.request('GET', '/v1/hello', headers={
                'X-Coach-Device': 'fixture', 'Authorization': 'Bearer fixture-token-never-production-32-characters'})
            r = c.getresponse(); r.read()
            return r.status
        finally:
            c.close()

    def ws(self):
        s = socket.create_connection(('127.0.0.1', self.port), timeout=5)
        s.sendall(('GET /v1/ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n'
                   'Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n'
                   'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n'
                   'X-Coach-Device: fixture\r\n'
                   'Authorization: Bearer fixture-token-never-production-32-characters\r\n\r\n').encode())
        reply = b''
        while b'\r\n\r\n' not in reply:
            reply += s.recv(4096)
        if b'101 Switching Protocols' not in reply:
            s.close()
            raise AssertionError(reply)
        return s

    def settle(self):
        deadline = time.monotonic()+5
        while True:
            v = self.snapshot()
            if v['active'] == 0:
                return v
            if time.monotonic() > deadline:
                raise AssertionError(v)
            time.sleep(.01)

    def proof(self):
        start = self.snapshot()
        end = self.command('advance', ns=901_000_000_000)
        self.evidence = self.root / 'idle.json'
        self.evidence.write_text(json.dumps(dict(pid=start['pid'], windowStart=start['at'], windowEnd=end['at'],
            coverage=dict(protocol=1, socket=self.socket, start=start, end=end))))
        return self.evidence

    def logs(self):
        return [str(p) for p in sorted((self.root / 'logs').glob('activity-*.log'))]

    def close(self):
        try:
            if self.child.poll() is None:
                self.command('stop')
        finally:
            if self.child.poll() is None:
                self.child.kill(); self.child.wait()
            self.child.stdin.close();self.child.stdout.close();self.error.close();self.temp.cleanup()


@unittest.skipUnless(os.environ.get('COACH_COVERAGE_CLASSPATH'), 'run through :server:app:test for the real JVM fixture')
class ActivityCoverageTests(unittest.TestCase):
    def setUp(self):
        self.service = Service()
        self.addCleanup(self.service.close)

    def transaction(self, evidence=None, logs=None):
        # Reuse the existing atomic-pointer/controller fixture, but exercise the
        # real new log verifier and seal in the real switch transaction.
        from .test_ton1175_switch import SwitchTest
        f = SwitchTest(methodName='runTest')
        f.setUp();self.addCleanup(f.doCleanups)
        proof = json.loads(f.idle.read_text())
        proof.update(json.loads(Path(evidence or self.service.evidence).read_text()))
        proof['verifiedAtEpoch'] = time.time()
        f.idle.write_text(json.dumps(proof))
        f.config.update(idleEvidence=str(f.idle), accessLogs=logs or self.service.logs(),
                        logVerifier=[sys.executable, str(Path(__file__).with_name('ton1175_verify.py')), 'logs'])
        f.config_path.write_text(json.dumps(f.config))
        f.stage()
        return f

    def refuse_and_available(self, evidence=None, logs=None):
        s = self.service
        evidence, logs = evidence or s.evidence, logs or s.logs()
        with self.assertRaises((GateError, OSError, ValueError)):
            verify(evidence, logs)
        with self.assertRaises((GateError, OSError, ValueError)):
            with stop_guard(evidence, logs):
                self.fail('unsafe stop was permitted')
        # A reconnect also proves the previous failed guard has released its seal.
        s.snapshot()
        f = self.transaction(evidence, logs)
        f.invoke('switch', True, success=False)
        f.assert_old()
        self.assertFalse((f.root / 'calls').exists(), 'unsafe proof must fail before controller stop')
        self.assertEqual(200, s.request())

    def supervised_seal_fault(self, fault):
        from .test_ton1175_supervise import SupervisorTests
        s = self.service
        s.proof()
        f = self.transaction()
        # Hold immediately before a controller stop. The real JVM stays alive
        # outside command groups, so HTTP tests seal release, not a replacement
        # fixture's claimed state. No production service is addressed.
        f.controller.write_text('''import pathlib,sys,time
r=pathlib.Path(sys.argv[1]);action=sys.argv[2]
if action=='stop' and not (r/'once').exists():
 (r/'once').touch();(r/'fault-ready').touch();time.sleep(90)
if action=='start': (r/'running').write_text('b'*40)
''')
        helper = SupervisorTests(methodName='runTest')
        helper.f, helper.record = f, f.root / 'supervision'
        self.addCleanup(helper.doCleanups)
        child = helper.launch()
        helper.wait_for(lambda: (f.root / 'fault-ready').exists(), child)
        self.assertEqual(503, s.request(), 'sealed real JVM must reject HTTP')
        owner = json.loads((helper.record / 'owner.json').read_text())
        os.kill(owner['workerPid'], fault)
        helper.finish(child, 1 if fault == signal.SIGKILL else 124)
        helper.assert_recovered()
        self.assertIsNone(s.child.poll(), 'command cleanup must not kill the real service')
        self.assertIsNone(s.snapshot().get('seal'))
        self.assertEqual(200, s.request(), 'EOF after forced worker exit must release the seal')

    def test_supervised_worker_kill_releases_real_seal(self):
        self.supervised_seal_fault(signal.SIGKILL)

    def test_supervised_deadline_kill_releases_real_seal(self):
        self.supervised_seal_fault(signal.SIGSTOP)

    def test_complete_http_ws_rotation_and_closed_positive(self):
        s = self.service
        self.assertEqual(200, s.request())
        ws = s.ws();ws.close();s.settle()
        s.command('rotate')
        evidence = s.proof()
        self.assertEqual(2, len(s.logs()))
        verify(evidence, s.logs())
        with stop_guard(evidence, s.logs()) as seal:
            s.command('stop')
            result = verify(evidence, s.logs(), stopped=True, seal=seal)
            self.assertEqual('closed', result['event'])
            self.assertEqual(0, result['active'])
        all_logs = ''.join(Path(p).read_text() for p in s.logs())
        self.assertNotIn('fixture-token', all_logs)
        self.assertNotIn('/v1/', all_logs)

    def test_dormant_established_websocket_refuses(self):
        s = self.service
        ws = s.ws()
        try:
            s.proof()
            self.assertGreater(s.snapshot()['active'], 0)
            self.refuse_and_available()
        finally:
            ws.close()
        s.settle()

    def test_disk_write_failure_is_sticky_and_service_survives(self):
        s = self.service
        s.command('fail');self.assertEqual(200, s.request());s.settle()
        self.assertGreater(s.snapshot()['failures'], 0)
        s.proof();self.refuse_and_available()

    def test_access_writer_failure_also_refuses(self):
        s = self.service
        # ServiceLog cannot create a regular access file over this directory.
        import datetime
        (s.root / 'logs' / ('access-'+datetime.datetime.now(datetime.timezone.utc).date().isoformat()+'.log')).mkdir()
        self.assertEqual(200, s.request());s.settle()
        self.assertGreater(s.snapshot()['failures'], 0)
        s.proof();self.refuse_and_available()

    def test_missing_middle_row_refuses_and_releases_fence(self):
        s = self.service
        self.assertEqual(200, s.request());s.settle();s.proof()
        path = Path(s.logs()[0]);lines = path.read_bytes().splitlines(keepends=True)
        path.write_bytes(b''.join(lines[:1]+lines[2:]))
        self.refuse_and_available()

    def test_tail_truncation_refuses(self):
        s = self.service
        self.assertEqual(200, s.request());s.settle();s.proof()
        path = Path(s.logs()[0]);lines = path.read_bytes().splitlines(keepends=True)
        path.write_bytes(b''.join(lines[:-1]))
        self.refuse_and_available()

    def test_missing_rotation_file_refuses(self):
        s = self.service
        s.command('rotate');s.proof()
        self.refuse_and_available(logs=s.logs()[1:])

    def test_rotation_broken_hash_refuses(self):
        s = self.service
        s.command('rotate');s.proof()
        path = Path(s.logs()[1]);lines = path.read_text().splitlines()
        row = json.loads(lines[0]);row['prev'] = 'f'*64
        path.write_text(json.dumps(row)+'\n'+'\n'.join(lines[1:])+'\n')
        self.refuse_and_available()

    def test_process_identity_change_refuses(self):
        s = self.service
        s.proof()
        other = Service();self.addCleanup(other.close)
        proof = json.loads(s.evidence.read_text());proof['coverage']['socket'] = other.socket
        s.evidence.write_text(json.dumps(proof))
        self.refuse_and_available()

    def test_request_after_observation_refuses(self):
        s = self.service
        s.proof();self.assertEqual(200, s.request());s.settle()
        self.refuse_and_available()

    def test_detached_work_refuses(self):
        s = self.service
        s.proof();s.command('quiet', value=False)
        self.refuse_and_available()

    def test_fence_blocks_new_http_then_eof_restores_service(self):
        s = self.service
        s.proof()
        with stop_guard(s.evidence, s.logs()):
            self.assertEqual(503, s.request())
        self.assertEqual(200, s.request())

    def test_request_during_fence_invalidates_closed_barrier(self):
        s = self.service
        s.proof()
        with stop_guard(s.evidence, s.logs()) as seal:
            self.assertEqual(503, s.request())
            s.command('stop')
            with self.assertRaises(GateError):
                verify(s.evidence, s.logs(), stopped=True, seal=seal)

    def test_crash_cannot_supply_shutdown_barrier(self):
        s = self.service
        s.proof()
        with stop_guard(s.evidence, s.logs()) as seal:
            s.command('crash')
            with self.assertRaises(GateError):
                verify(s.evidence, s.logs(), stopped=True, seal=seal)

    def test_legacy_claim_still_refuses(self):
        s = self.service
        s.proof()
        proof = json.loads(s.evidence.read_text());proof['coverage'] = True
        s.evidence.write_text(json.dumps(proof))
        self.refuse_and_available()

    def test_request_between_preflight_and_seal_never_stops_old(self):
        s = self.service
        s.proof()
        f = self.transaction()
        original = f.snapshot.read_text()
        f.snapshot.write_text("import http.client\n"+
            f"c=http.client.HTTPConnection('127.0.0.1',{s.port});c.request('GET','/v1/hello');c.getresponse().read();c.close()\n"+original)
        f.invoke('switch', True, success=False)
        f.assert_old()
        self.assertFalse((f.root / 'calls').exists())
        self.assertEqual('staged', json.loads((f.ops / 'journal.json').read_text())['phase'])
        self.assertEqual(200, s.request())

    def test_complete_closed_barrier_through_switch_transaction(self):
        from . import ton1175_switch as release
        s = self.service
        s.proof()
        f = self.transaction()
        actions = []
        def controller(config, action):
            actions.append(action)
            if action == 'stop':
                s.command('stop')
        # The actual journal/CLI verification and atomic pointer replacement run;
        # candidate, JFR and post-switch learning gates remain controlled fixtures.
        with mock.patch.object(release, 'command', side_effect=controller):
            release.operate(f.config, 'switch', True)
        self.assertEqual(['stop'], actions)
        self.assertEqual('switched', json.loads((f.ops / 'journal.json').read_text())['phase'])
        self.assertEqual(f.head, json.loads(f.installation.read_text())['sourceHead'])

    def test_inflight_http_body_refuses(self):
        s = self.service
        pending = socket.create_connection(('127.0.0.1', s.port), timeout=5)
        try:
            pending.sendall(b'POST /v1/usage HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n'
                            b'X-Coach-Device: fixture\r\nAuthorization: Bearer fixture-token-never-production-32-characters\r\n\r\n[')
            deadline = time.monotonic()+5
            while s.snapshot()['active'] == 0:
                self.assertLess(time.monotonic(), deadline)
                time.sleep(.01)
            s.proof();self.refuse_and_available()
        finally:
            pending.close()

    def test_checkpoint_collection_cli(self):
        s = self.service
        script = str(Path(__file__).with_name('activity_coverage.py'))
        start = s.root / 'start.json'
        subprocess.run([sys.executable, script, 'start', '--socket', s.socket, '--output', str(start)], check=True)
        self.assertEqual(0o600, start.stat().st_mode & 0o777)
        s.proof()
        covered = s.root / 'covered.json'
        subprocess.run([sys.executable, script, 'finish', '--start', str(start), '--idle', str(s.evidence),
                        '--output', str(covered)], check=True)
        self.assertEqual(0o600, covered.stat().st_mode & 0o777)
        verify(covered, s.logs())


@unittest.skipUnless(os.environ.get('COACH_COVERAGE_BINARY'), 'Gradle supplies the installed service binary')
class MainLifecycleTests(unittest.TestCase):
    def test_real_main_graceful_shutdown_carries_held_seal(self):
        self.shutdown_with_held_seal()

    def test_real_main_waits_for_completion_after_401(self):
        self.shutdown_with_held_seal(block_access=True)

    def shutdown_with_held_seal(self, *, block_access=False):
        import hashlib
        with tempfile.TemporaryDirectory(prefix='cm-', dir='/tmp') as tmp:
            root = Path(tmp)
            with socket.socket() as reserve:
                reserve.bind(('127.0.0.1', 0));port = reserve.getsockname()[1]
            env = {k:v for k,v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
            env.update(COACH_DATA_DIR=str(root / 'data'), COACH_LOG_DIR=str(root / 'logs'),
                       COACH_STATE_DIR=str(root / 'state'), COACH_PACKS_DIR=str(root / 'packs'),
                       COACH_ACTIVITY_SOCKET=str(root / 'control.sock'), COACH_TOKEN_FILE=str(root / 'device-token'),
                       COACH_TAIL_HOST='', COACH_PORT=str(port), COACH_TRAFFIC='test', COACH_BRIDGE_URL='http://127.0.0.1:1')
            with (root / 'output').open('wb') as output:
                process = subprocess.Popen([os.environ['COACH_COVERAGE_BINARY']], env=env,
                    stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
                access_reader = None
                try:
                    deadline = time.monotonic()+20
                    if block_access:
                        # The initial summary reads access logs. Let it finish before
                        # installing a FIFO that blocks ResponseSent's first write.
                        while not list((root / 'state').glob('usage-*.md')):
                            self.assertIsNone(process.poll(), (root / 'output').read_text()[-1500:])
                            self.assertLess(time.monotonic(), deadline, 'initial usage summary did not finish')
                            time.sleep(.01)
                        day = dt.datetime.now(dt.timezone.utc).date()
                        access = root / 'logs' / f'access-{day}.log'
                        os.mkfifo(access, 0o600)
                    while True:
                        self.assertIsNone(process.poll(), (root / 'output').read_text()[-1500:])
                        try:
                            c = http.client.HTTPConnection('127.0.0.1', port, timeout=1)
                            c.request('GET', '/v1/hello');response = c.getresponse();response.read();c.close()
                            self.assertEqual(401, response.status)
                            break
                        except OSError:
                            self.assertLess(time.monotonic(), deadline)
                            time.sleep(.05)
                    with Control(str(root / 'control.sock')) as control:
                        if block_access:
                            busy = control.ask('snapshot')
                            self.assertGreater(busy['active'], 0)
                            self.assertEqual(0, busy['failures'])
                            with self.assertRaises(GateError):
                                healthy(busy)
                            sleep = time.sleep

                            def release_access(delay):
                                nonlocal access_reader
                                # Release only once the checkpoint has observed the
                                # outstanding request; no scheduler/timing assumption.
                                if access_reader is None:
                                    access_reader = os.open(access, os.O_RDONLY | os.O_NONBLOCK)
                                sleep(delay)

                            with mock.patch.object(time, 'sleep', side_effect=release_access):
                                point = startup_checkpoint(control)
                            self.assertIsNotNone(access_reader, 'checkpoint did not wait for completion')
                        else:
                            point = startup_checkpoint(control)
                        seal = control.ask('seal', expected=point)['seal']
                        process.terminate();process.wait(timeout=15)
                    logs = [str(p) for p in (root / 'logs').glob('activity-*.log')]
                    rows = [raw for p in logs for raw in Path(p).read_bytes().splitlines()]
                    last_raw = max(rows, key=lambda raw: json.loads(raw)['seq'])
                    last = json.loads(last_raw)
                    self.assertEqual('closed', last['event'])
                    self.assertEqual(seal, last['seal'])
                    self.assertEqual(0, last['active']);self.assertEqual(0, last['failures'])
                    chain(logs, dict(last, tail=hashlib.sha256(last_raw).hexdigest()), (point,))
                finally:
                    if process.poll() is None:
                        process.kill();process.wait()
                    if access_reader is not None:
                        os.close(access_reader)
