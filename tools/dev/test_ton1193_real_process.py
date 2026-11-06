import socket
from types import SimpleNamespace
import unittest
from unittest import mock

from tools.dev import check_ton1193_real_process as probe
from tools.dev.ton1175_switch import GateError


def process(pid, ppid, started=1):
    return SimpleNamespace(pid=pid, ppid=ppid, started=started, zombie=False)


class RealProcessRegressionTest(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
