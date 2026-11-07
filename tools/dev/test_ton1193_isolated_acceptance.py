import http.server
import errno
import json
from pathlib import Path
import shutil
import sqlite3
import threading
import time
import unittest
from unittest import mock

from tools.dev import ton1193_isolated_acceptance as acceptance
from tools.dev import ton1193_isolated_controller as isolated
from tools.dev import ton1193_isolated_injected_failure as injected
from tools.dev.ton1175_switch import GateError, atomic, digest


class Hello(http.server.BaseHTTPRequestHandler):
    epoch = 'isolated-epoch'
    sequence = 7
    requests = 0

    def do_GET(self):
        type(self).requests += 1
        allowed = (self.path == '/v1/hello' and self.headers.get('X-Coach-Device') == 'desktop'
                   and self.headers.get('Authorization') == 'Bearer isolated-test-token')
        body = json.dumps({'epoch': self.epoch, 'lastSeq': self.sequence}).encode()
        self.send_response(200 if allowed else 401)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        Hello.requests = 0
        self.root = isolated.new_root()
        self.addCleanup(shutil.rmtree, self.root)
        home = self.root / 'h'
        data = home / 'Coach/data'
        isolated.private_directories(data, self.root)
        self.token = data / 'device-token'
        atomic(self.token, b'isolated-test-token')
        self.db = sqlite3.connect(data / 'coach.sqlite')
        self.db.executescript('''CREATE TABLE ledger_meta(singleton INTEGER,epoch TEXT);
            INSERT INTO ledger_meta VALUES(1,'isolated-epoch');
            CREATE TABLE devices(device_id TEXT,token_sha256 TEXT,revoked_at INTEGER);
            CREATE TABLE events(seq INTEGER PRIMARY KEY,course_id TEXT,thread_id TEXT,type TEXT,
                recorded_at INTEGER,device_id TEXT,payload TEXT);
            CREATE TABLE messages(message_id TEXT,thread_id TEXT,status TEXT,role TEXT,content_md TEXT,created_at INTEGER);
            INSERT INTO events(seq) VALUES(7);
        ''')
        self.db.execute('INSERT INTO devices VALUES(?,?,NULL)', ('desktop', digest(b'isolated-test-token')))
        self.db.commit()
        self.addCleanup(self.db.close)
        (self.root / 'stage').mkdir(mode=0o700)
        self.baseline = str(self.root / 'stage/acceptance-baseline.json')
        self.server = http.server.HTTPServer(('127.0.0.1', 0), Hello)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.path = self.root / 'controller.json'
        self.config = {'plist': str(home / 'Library/LaunchAgents/service.plist'),
                       'installation': str(home / 'Coach/service/installation.json'),
                       'stateDir': str(self.root / 'stage'),
                       'adapterSnapshotDir': str(self.root / 'stage/snapshot'),
                       'adapterManifest': str(self.root / 'manifest.json'),
                       'release': str(home / 'Coach/service/releases/candidate'),
                       'adapterEnvPins': {'COACH_TAIL_HOST': '', 'COACH_MANAGE_SERVICES': '1',
                                          'COACH_PORT': str(self.server.server_port), 'BRIDGE_PORT': '23102'}}
        if self.server.server_port == 23102:
            self.config['adapterEnvPins']['BRIDGE_PORT'] = '23103'
        atomic(self.path, json.dumps(self.config).encode())

    def stop_server(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def test_authenticated_baseline_does_not_accept_without_exercises(self):
        acceptance.run(str(self.path), 'snapshot', self.baseline)
        baseline = json.loads(Path(self.baseline).read_text())
        self.assertEqual((baseline['epoch'], baseline['lastSeq']), ('isolated-epoch', 7))
        self.assertNotIn('isolated-test-token', Path(self.baseline).read_text())
        now = time.time()
        clock = mock.Mock()
        clock.time.return_value = now
        clock.monotonic.side_effect = [0, 0, 301, 301]
        config, roots, _ = isolated.fixture(str(self.path))
        observed = acceptance.observe(config, roots)
        with mock.patch.object(acceptance, 'time', clock), \
             mock.patch.object(acceptance, 'observe_when_ready', return_value=observed):
            with self.assertRaisesRegex(GateError, 'lecture and exercise'):
                acceptance.run(str(self.path), 'accept', self.baseline, now, now + 300)

    def test_snapshot_after_hello_does_not_generate_ingress(self):
        config, roots, _ = isolated.fixture(str(self.path))
        acceptance.observe(config, roots)
        self.assertEqual(Hello.requests, 1)
        # Any HTTP request here invalidates the idle coverage captured immediately before snapshot.
        with mock.patch.object(acceptance.http.client, 'HTTPConnection',
                               side_effect=AssertionError('snapshot created HTTP ingress')):
            acceptance.run(str(self.path), 'snapshot', self.baseline)
        before = json.loads(Path(self.baseline).read_text())
        self.assertEqual((before['epoch'], before['lastSeq'], before['seq']), ('isolated-epoch', 7, 7))
        self.assertEqual(Hello.requests, 1)

    def test_snapshot_rejects_token_missing_from_ledger(self):
        atomic(self.token, b'different-token')
        with self.assertRaisesRegex(GateError, 'ledger identity'):
            acceptance.run(str(self.path), 'snapshot', self.baseline)
        self.assertFalse(Path(self.baseline).exists())

    def test_injected_failure_records_only_closed_diagnostic(self):
        now = time.time()
        with mock.patch.object(injected.live, 'live_acceptance', return_value='validating'), \
             mock.patch.object(acceptance, 'observe_when_ready', side_effect=ConnectionRefusedError(errno.ECONNREFUSED, 'private detail')):
            self.assertFalse(injected.verify(str(self.path), self.baseline, now, now + 300))
        diagnostic = (self.root / 'injected-diagnostic.json').read_text()
        self.assertEqual(json.loads(diagnostic), {'step': 'HELLO', 'reason': 'CONNECTION_REFUSED'})
        self.assertNotIn('private detail', diagnostic)
        self.assertFalse((self.root / 'injected-acceptance.json').exists())

    def test_injected_failure_records_authenticated_evidence(self):
        now = time.time()
        with mock.patch.object(injected.live, 'live_acceptance', return_value='validating'):
            self.assertTrue(injected.verify(str(self.path), self.baseline, now, now + 300))
        self.assertEqual(Hello.requests, 1)
        self.assertEqual(json.loads((self.root / 'injected-acceptance.json').read_text())['epoch'], 'isolated-epoch')
        self.assertFalse((self.root / 'injected-diagnostic.json').exists())

    def test_startup_refusal_retries_under_live_transaction_then_authenticates(self):
        config, roots, _ = isolated.fixture(str(self.path))
        real_observe = acceptance.observe
        with mock.patch.object(acceptance, 'observe', side_effect=[
                ConnectionRefusedError(errno.ECONNREFUSED, 'private'),
                ConnectionRefusedError(errno.ECONNREFUSED, 'private'),
                real_observe(config, roots)]) as observe, \
             mock.patch.object(acceptance.live, 'live_acceptance', return_value='validating') as live, \
             mock.patch.object(acceptance.time, 'sleep'):
            result = acceptance.observe_when_ready(config, roots, time.time() + 10)
        self.assertEqual(result['epoch'], 'isolated-epoch')
        self.assertEqual(observe.call_count, 3)
        self.assertEqual(live.call_count, 2)

    def test_startup_wait_rejects_expired_or_ended_transaction(self):
        config, roots, _ = isolated.fixture(str(self.path))
        refusal = ConnectionRefusedError(errno.ECONNREFUSED, 'private')
        with mock.patch.object(acceptance, 'observe', side_effect=refusal):
            with self.assertRaises(ConnectionRefusedError):
                acceptance.observe_when_ready(config, roots, time.time() - 1)
            with mock.patch.object(acceptance.live, 'live_acceptance', return_value='recovering'):
                with self.assertRaisesRegex(GateError, 'transaction ended'):
                    acceptance.observe_when_ready(config, roots, time.time() + 10)

    def test_changed_token_and_outside_baseline_are_refused(self):
        acceptance.run(str(self.path), 'snapshot', self.baseline)
        atomic(self.token, b'changed')
        now = time.time()
        with self.assertRaises(GateError):
            acceptance.run(str(self.path), 'accept', self.baseline, now, now + 300)
        with self.assertRaisesRegex(GateError, 'baseline outside'):
            acceptance.run(str(self.path), 'snapshot', str(self.root / 'other'))

    def test_identity_or_ledger_regression_is_refused(self):
        acceptance.run(str(self.path), 'snapshot', self.baseline)
        baseline = json.loads(Path(self.baseline).read_text())
        now = time.time()
        for key, value in (('epoch', 'other'), ('lastSeq', 8), ('tokenSha256', '0' * 64)):
            changed = dict(baseline, **{key: value})
            atomic(Path(self.baseline), json.dumps(changed).encode())
            with self.subTest(key=key), self.assertRaisesRegex(GateError, 'identity or ledger'):
                acceptance.run(str(self.path), 'accept', self.baseline, now, now + 300)

    def learning(self, started, count=3, answer_device='desktop', answer_thread='thread-1'):
        at = int(started * 1000)
        self.db.execute('INSERT INTO messages VALUES(?,?,?,?,?,?)',
                        ('m1', 'thread-1', 'complete', 'assistant', 'synthetic lesson', at))
        def event(kind, payload, device='desktop', thread='thread-1'):
            self.db.execute('INSERT INTO events(course_id,thread_id,type,recorded_at,device_id,payload) VALUES(?,?,?,?,?,?)',
                            ('course-1', thread, kind, at, device, json.dumps(payload)))
        event('explanation_delivered', {'msgId': 'm1'})
        for n in range(count):
            payload = {'attemptId': str(n), 'groupId': 'group'}
            event('question_posed', payload)
            event('answer_submitted', payload, answer_device, answer_thread)
            event('attempt_graded', payload, 'server')
        self.db.commit()

    def test_complete_ledger_evidence_and_original_token_are_required(self):
        acceptance.run(str(self.path), 'snapshot', self.baseline)
        started = time.time()
        self.learning(started)
        acceptance.run(str(self.path), 'accept', self.baseline, started, started + 300)
        self.db.execute("UPDATE devices SET token_sha256='replaced' WHERE device_id='desktop'")
        self.db.commit()
        with self.assertRaisesRegex(GateError, 'ledger identity'):
            acceptance.run(str(self.path), 'accept', self.baseline, started, started + 300)

    def test_partial_wrong_device_and_cross_thread_evidence_do_not_pass(self):
        acceptance.run(str(self.path), 'snapshot', self.baseline)
        before = json.loads(Path(self.baseline).read_text())
        started = time.time()
        self.learning(started, count=2)
        with acceptance.ledger({'home': str(self.root / 'h')}) as db:
            self.assertFalse(acceptance.learning_evidence(db, before, started, started + 300))
        self.db.execute('DELETE FROM events WHERE seq>7')
        self.db.execute('DELETE FROM messages')
        self.db.commit()
        self.learning(started, answer_device='other')
        with acceptance.ledger({'home': str(self.root / 'h')}) as db:
            self.assertFalse(acceptance.learning_evidence(db, before, started, started + 300))
        self.db.execute("UPDATE events SET device_id='desktop', thread_id='other' WHERE type='answer_submitted'")
        self.db.commit()
        with acceptance.ledger({'home': str(self.root / 'h')}) as db:
            self.assertFalse(acceptance.learning_evidence(db, before, started, started + 300))

    def test_linked_ledger_is_rejected(self):
        path = self.root / 'h/Coach/data/coach.sqlite'
        self.db.close()
        path.rename(path.with_suffix('.saved'))
        path.symlink_to(path.with_suffix('.saved'))
        with self.assertRaises(OSError):
            acceptance.run(str(self.path), 'snapshot', self.baseline)


if __name__ == '__main__':
    unittest.main()
