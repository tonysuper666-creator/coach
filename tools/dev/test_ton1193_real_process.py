from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import socket
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock

from tools.dev import check_ton1193_real_process as probe
from tools.dev.ton1175_switch import GateError


def process(pid, ppid, started=1):
    return SimpleNamespace(pid=pid, ppid=ppid, started=started, zombie=False)


class RealProcessRegressionTest(unittest.TestCase):
    def test_restored_readiness_reports_only_closed_step_and_reason(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, 'snapshot.json').write_text(json.dumps({'sides': {'old': {}}}))
            config = {'adapterSnapshotDir': root}
            diagnostic = {}
            with mock.patch.object(probe.time, 'monotonic', side_effect=[0, 1, 61]), \
                 mock.patch.object(probe.time, 'sleep'), \
                 mock.patch.object(probe, 'job', return_value=123), \
                 mock.patch.object(probe, 'collect'), \
                 mock.patch.object(probe.process, '_identity', return_value=(1, '/isolated/old/app/lib/x')), \
                 mock.patch.object(probe.acceptance, 'observe', side_effect=GateError('secret path and token')):
                with self.assertRaisesRegex(GateError, 'did not become ready'):
                    probe.wait_ready(config, {}, 'label', Path('/isolated/old'), mock.Mock(), {}, diagnostic)
            self.assertEqual(diagnostic, {'step': 'HELLO', 'reason': 'GATE_REJECTED'})
            self.assertNotIn('secret', json.dumps(diagnostic))

    def test_collection_finds_children_started_after_first_snapshot(self):
        kernel = mock.Mock()
        kernel.snapshot.return_value = [process(10, 1), process(11, 10)]
        owned = {}
        probe.collect(kernel, 10, owned)
        kernel.snapshot.return_value.append(process(12, 11))
        probe.collect(kernel, 10, owned)
        self.assertEqual(set(owned), {10, 11, 12})

    def test_reused_root_pid_is_rejected(self):
        kernel = mock.Mock()
        kernel.snapshot.return_value = [process(10, 1, started=2)]
        with self.assertRaisesRegex(GateError, 'PID reused'):
            probe.collect(kernel, 10, {10: process(10, 1, started=1)})

    def test_cleanup_rejects_a_real_surviving_listener(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            config = {'adapterEnvPins': {'COACH_PORT': str(port), 'BRIDGE_PORT': str(port)}}
            with mock.patch.object(probe, 'job', return_value=None):
                with self.assertRaises(OSError):
                    probe.stop(config, 'isolated-label', mock.Mock(), {})
        with mock.patch.object(probe, 'job', return_value=None):
            probe.stop(config, 'isolated-label', mock.Mock(), {})


    def test_cleanup_allows_http_time_wait_after_service_has_exited(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            with socket.create_connection(('127.0.0.1', port)) as client:
                peer, _ = listener.accept()
                peer.shutdown(socket.SHUT_WR)
                peer.close()
                self.assertEqual(client.recv(1), b'')
        config = {'adapterEnvPins': {'COACH_PORT': str(port), 'BRIDGE_PORT': str(port)}}
        with mock.patch.object(probe, 'job', return_value=None):
            probe.stop(config, 'isolated-label', mock.Mock(), {})


class ActivityControlTest(unittest.TestCase):
    def setUp(self):
        self.root = probe.isolated.new_root()
        self.addCleanup(shutil.rmtree, self.root)
        self.home = self.root / 'h'
        self.path = probe.isolated.activity_socket(self.home)
        probe.isolated.private_directories(self.path.parent, self.root)
        probe.isolated.private_directories(self.home / 'Coach/logs', self.root)
        self.pid = os.getpid()
        birth = probe.processes().get(self.pid)
        seconds, micros = birth.started
        self.first = dict(protocol=1, pid=self.pid, lifetime='00000000-0000-0000-0000-000000000000',
                          jvmStart=datetime.fromtimestamp(seconds + micros / 1e6, timezone.utc).isoformat(),
                          failures=0, closed=False)
        (self.home / 'Coach/logs/activity-test.log').write_text(json.dumps(self.first) + '\n')
        self.owned = {self.pid: birth}
        self.config = {'plist': str(self.home / 'Library/LaunchAgents/service.plist'),
                       'adapterEnvPins': {'COACH_PORT': '0', 'BRIDGE_PORT': '0'}}

    def reply(self, value):
        server = socket.socket(socket.AF_UNIX)
        server.settimeout(5)
        server.bind(str(self.path)); server.listen(1)
        self.path.chmod(0o600)
        errors = []
        def respond():
            try:
                peer, _ = server.accept()
                with peer, peer.makefile('rwb', buffering=0) as stream:
                    self.assertEqual(json.loads(stream.readline()), {'command': 'snapshot'})
                    stream.write((json.dumps(value) + '\n').encode())
            except BaseException as exc:
                errors.append(exc)
        worker = threading.Thread(target=respond)
        worker.start()
        try:
            return probe.control_diagnostic({'home': str(self.home)}, self.pid, self.owned)
        finally:
            worker.join(6)
            server.close()
            self.path.unlink()
            self.assertFalse(worker.is_alive())
            if errors:
                raise errors[0]

    def test_snapshot_requires_real_exchange_and_exact_lifetime(self):
        result = self.reply(self.first)
        self.assertEqual(result['snapshot'], self.first)
        for key, bad in (('protocol', 2), ('pid', self.pid + 1), ('lifetime', 'other'),
                         ('jvmStart', 'other'), ('failures', 1), ('closed', True)):
            with self.subTest(key=key), self.assertRaises(GateError):
                self.reply(dict(self.first, **{key: bad}))

    def test_missing_control_and_forged_birth_fail_closed(self):
        with self.assertRaises(OSError):
            probe.control_diagnostic({'home': str(self.home)}, self.pid, self.owned)
        self.owned[self.pid] = process(self.pid, 1, started=(1, 0))
        with self.assertRaisesRegex(GateError, 'birth mismatch'):
            probe.control_diagnostic({'home': str(self.home)}, self.pid, self.owned)

    def test_cleanup_checks_exact_socket_even_when_ready_never_returned(self):
        kernel = mock.Mock()
        kernel.get.return_value = None
        with socket.socket(socket.AF_UNIX) as occupied:
            occupied.bind(str(self.path)); occupied.listen(1)
            with mock.patch.object(probe, 'job', return_value=None):
                with self.assertRaisesRegex(GateError, 'socket survived'):
                    probe.stop(self.config, 'private-label', kernel, self.owned, {})
            self.assertTrue(self.path.exists())
            # Another owner remains connectable; cleanup never unlinks it.
            with socket.socket(socket.AF_UNIX) as client:
                client.connect(str(self.path))
                peer, _ = occupied.accept()
                peer.close()
        self.path.unlink()
        sockets = {}
        with mock.patch.object(probe, 'job', return_value=None):
            probe.stop(self.config, 'private-label', kernel, self.owned, sockets)
        self.assertEqual(list(sockets), [str(self.path)])
        self.assertFalse(os.path.lexists(self.path))



if __name__ == '__main__':
    unittest.main()
