import json
import time
import unittest
from unittest import mock

from tools.dev import ton1193_isolated_learning as learning
from tools.dev.ton1175_switch import GateError, digest


class LearningDriverTest(unittest.TestCase):
    def setUp(self):
        self.now = time.time()
        self.started = self.now - 1
        self.deadline = self.started + 300
        self.baseline = '/private/stage/acceptance-baseline.json'
        self.token = b'isolated-token\n'
        self.before = {'createdAt': self.started - 1, 'epoch': 'epoch', 'lastSeq': 3,
                       'tokenSha256': digest(self.token), 'credentialSha256': digest(b'isolated-token')}
        self.config = {'stateDir': '/private/stage', 'adapterEnvPins': {'COACH_PORT': '23456'}}
        self.roots = {'home': '/private/home'}
        self.stack = mock.patch.multiple(learning, directory_snapshot=mock.DEFAULT, _read_file=mock.DEFAULT)
        self.mocks = self.stack.start()
        self.addCleanup(self.stack.stop)
        self.mocks['_read_file'].side_effect = lambda path, *_: (
            (json.dumps(self.before).encode(), None) if str(path) == self.baseline else (self.token, None))
        self.fixture = mock.patch.object(learning.isolated, 'fixture', return_value=(self.config, self.roots, 'label'))
        self.fixture.start()
        self.addCleanup(self.fixture.stop)
        self.live = mock.patch.object(learning.live, 'live_acceptance', return_value='validating')
        self.live_mock = self.live.start()
        self.addCleanup(self.live.stop)
        self.observe = mock.patch.object(learning.acceptance, 'observe', return_value=dict(self.before))
        self.observe.start()
        self.addCleanup(self.observe.stop)
        self.accept = mock.patch.object(learning.acceptance, 'run')
        self.accept_mock = self.accept.start()
        self.addCleanup(self.accept.stop)
        self.websocket = mock.patch.object(learning, 'WebSocket')
        self.ws_class = self.websocket.start()
        self.addCleanup(self.websocket.stop)
        self.ws = self.ws_class.return_value
        self.ws.await_type.side_effect = [
            {'state': 'OPEN'}, {}, {'text': 'lesson'}, {'status': 'complete'}, {'state': 'AWAIT'},
            {'groupId': 'g', 'cards': [{'attemptId': str(i), 'questionId': f'q{i}',
                                       'letterMap': {f'q{i}-0': 'A'}} for i in range(3)]}, {}]

    def run_driver(self):
        learning.run('/private/controller.json', self.baseline, self.started, self.deadline)

    def test_drives_three_answers_then_checks_ledger(self):
        self.run_driver()
        self.assertEqual(self.ws_class.call_args.args[:2], (23456, 'isolated-token'))
        self.assertEqual(self.ws.send.call_args_list[-1].args[0]['answers'], {'0': 'A', '1': 'A', '2': 'A'})
        self.ws.close.assert_called_once()
        self.accept_mock.assert_called_once_with('/private/controller.json', 'accept', self.baseline,
                                                 self.started, self.deadline)

    def test_changed_token_or_inactive_switch_refuses_before_connect(self):
        self.token = b'replaced'
        with self.assertRaisesRegex(GateError, 'token changed'):
            self.run_driver()
        self.ws_class.assert_not_called()
        self.token = b'isolated-token\n'
        self.live_mock.return_value = 'recovering'
        with self.assertRaisesRegex(GateError, 'switch is not validating'):
            self.run_driver()
        self.ws_class.assert_not_called()

    def test_incomplete_lecture_closes_socket_and_never_accepts(self):
        self.ws.await_type.side_effect = [{'state': 'OPEN'}, {}, {'text': 'x'}, {'status': 'error'}]
        with self.assertRaisesRegex(GateError, 'lecture incomplete'):
            self.run_driver()
        self.ws.close.assert_called_once()
        self.accept_mock.assert_not_called()


if __name__ == '__main__':
    unittest.main()
