import http.server
import json
from pathlib import Path
import shutil
import threading
import time
import unittest
import uuid

from tools.dev import ton1193_isolated_acceptance as acceptance
from tools.dev.ton1175_switch import GateError, atomic


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


if __name__ == '__main__':
    unittest.main()
