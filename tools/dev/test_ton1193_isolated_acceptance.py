import http.server
import json
from pathlib import Path
import shutil
import sqlite3
import threading
import time
import unittest
from unittest import mock
import uuid

from tools.dev import ton1193_isolated_acceptance as acceptance
from tools.dev.ton1175_switch import GateError, atomic, digest


class Hello(http.server.BaseHTTPRequestHandler):
    epoch = 'isolated-epoch'
    sequence = 7

    def do_GET(self):
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
        self.root = Path('/tmp').resolve() / ('ton1193-' + uuid.uuid4().hex)
        self.root.mkdir(mode=0o700)
        self.addCleanup(shutil.rmtree, self.root)
        home = self.root / 'home'
        data = home / 'Coach/data'
        data.mkdir(parents=True, mode=0o700)
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
        with mock.patch.object(acceptance, 'time', clock):
            with self.assertRaisesRegex(GateError, 'lecture and exercise'):
                acceptance.run(str(self.path), 'accept', self.baseline, now, now + 300)

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
        with acceptance.ledger({'home': str(self.root / 'home')}) as db:
            self.assertFalse(acceptance.learning_evidence(db, before, started, started + 300))
        self.db.execute('DELETE FROM events WHERE seq>7')
        self.db.execute('DELETE FROM messages')
        self.db.commit()
        self.learning(started, answer_device='other')
        with acceptance.ledger({'home': str(self.root / 'home')}) as db:
            self.assertFalse(acceptance.learning_evidence(db, before, started, started + 300))
        self.db.execute("UPDATE events SET device_id='desktop', thread_id='other' WHERE type='answer_submitted'")
        self.db.commit()
        with acceptance.ledger({'home': str(self.root / 'home')}) as db:
            self.assertFalse(acceptance.learning_evidence(db, before, started, started + 300))

    def test_linked_ledger_is_rejected(self):
        path = self.root / 'home/Coach/data/coach.sqlite'
        self.db.close()
        path.rename(path.with_suffix('.saved'))
        path.symlink_to(path.with_suffix('.saved'))
        with self.assertRaises(OSError):
            acceptance.run(str(self.path), 'snapshot', self.baseline)


if __name__ == '__main__':
    unittest.main()
