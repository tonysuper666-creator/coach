import os
import subprocess
import unittest
from unittest import mock

from . import ton1193_process as process
from .ton1175_switch import GateError
from .ton1193_trust import file_snapshot


class ProcessBindingTests(unittest.TestCase):
    def setUp(self):
        self.roots = {'cellar': '/', 'node': '/'}
        self.java = file_snapshot(process._image(os.getpid()), '/')
        self.side = {'java': self.java, 'node': self.java,
                     'python': [None, None, self.java], 'stub': None}

    def sleep_child(self):
        child = subprocess.Popen(['/bin/sleep', '30'], stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def cleanup():
            if child.poll() is None:
                child.terminate()
            child.wait(timeout=10)
        self.addCleanup(cleanup)
        return child

    def test_live_proc_images_accept_pinned_child(self):
        child = self.sleep_child()
        image = process._image(child.pid)
        self.side['node'] = file_snapshot(image, '/')
        result = process.bind(os.getpid(), self.side, self.roots)
        self.assertEqual(result['image'], self.java[0])
        self.assertEqual(result['children'][child.pid][1], image)

    def test_live_unexpected_child_image_is_rejected(self):
        self.sleep_child()
        with self.assertRaisesRegex(GateError, 'unexpected process image'):
            process.bind(os.getpid(), self.side, self.roots)

    def test_live_changed_executable_snapshot_is_rejected(self):
        self.side['java'] = (*self.java[:3], '0' * 64)
        with self.assertRaisesRegex(GateError, 'process executable changed'):
            process.bind(os.getpid(), self.side, self.roots)

    def test_reused_root_identity_is_rejected(self):
        with mock.patch.object(process, '_identity', side_effect=[(1, 'old'), (1, 'new')]), \
             mock.patch.object(process, '_children', return_value=[]):
            with self.assertRaisesRegex(GateError, 'root process identity changed'):
                process.bind(os.getpid(), self.side, self.roots)

    def test_changing_child_set_is_rejected(self):
        with mock.patch.object(process, '_children', side_effect=[[], [12345]]):
            with self.assertRaisesRegex(GateError, 'child process set changed'):
                process.bind(os.getpid(), self.side, self.roots)

    def test_python_framework_stub_is_allowed(self):
        self.side['stub'] = self.java
        self.side['node'] = ('/not/node', 0, 0, '')
        self.side['python'] = [None, None, ('/not/python', 0, 0, '')]
        with mock.patch.object(process, '_identity', side_effect=[(1, 'root'), (123, 'child'),
                                                                 (123, 'child'), (123, 'child'),
                                                                 (1, 'root')]), \
             mock.patch.object(process, '_children', return_value=[456]), \
             mock.patch.object(process, '_image', return_value=self.java[0]):
            result = process.bind(123, self.side, self.roots)
        self.assertIn(456, result['children'])

    def test_reparented_child_is_rejected(self):
        with mock.patch.object(process, '_identity', side_effect=[(1, 'root'), (999, 'child')]), \
             mock.patch.object(process, '_children', return_value=[456]), \
             mock.patch.object(process, '_image', return_value=self.java[0]):
            with self.assertRaisesRegex(GateError, 'child process parent changed'):
                process.bind(123, self.side, self.roots)


if __name__ == '__main__':
    unittest.main()
