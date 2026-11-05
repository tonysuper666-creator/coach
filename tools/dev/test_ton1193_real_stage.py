import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from . import check_ton1193_real_stage as stage
from .ton1175_switch import GateError


class PythonLockTest(unittest.TestCase):
    def test_direct_requirements_must_match_and_duplicates_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock, direct = Path(tmp) / 'lock', Path(tmp) / 'requirements'
            lock.write_text('some-package==1.2\nother==3\n')
            direct.write_text('some_package==1.2\n')
            self.assertEqual(stage.dependency_preflight(lock, direct)['some-package'], '1.2')
            direct.write_text('some_package==1.3\n')
            with self.assertRaises(GateError):
                stage.dependency_preflight(lock, direct)
            lock.write_text('some-package==1.2\nsome_package==1.2\n')
            with self.assertRaises(GateError):
                stage.pins(lock)
            lock.write_text('some-package>=1.2\n')
            with self.assertRaises(GateError):
                stage.pins(lock)

    def test_installed_closure_must_be_exact_and_pip_check_must_pass(self):
        locked = {'fastapi': '1', 'typing-extensions': '2'}
        with mock.patch.object(stage, 'cmd') as check, \
             mock.patch.object(stage.subprocess, 'check_output', return_value=json.dumps(locked)):
            stage.verify_python('/fixture/python', locked)
            check.assert_called_once_with('/fixture/python', '-m', 'pip', 'check')
        for actual in ({'fastapi': '1'}, dict(locked, unexpected='3'), dict(locked, fastapi='9')):
            with self.subTest(actual=actual), mock.patch.object(stage, 'cmd'), \
                 mock.patch.object(stage.subprocess, 'check_output', return_value=json.dumps(actual)):
                with self.assertRaises(GateError):
                    stage.verify_python('/fixture/python', locked)
        with mock.patch.object(stage, 'cmd', side_effect=subprocess.CalledProcessError(1, 'pip check')), \
             mock.patch.object(stage.subprocess, 'check_output') as inspect:
            with self.assertRaises(subprocess.CalledProcessError):
                stage.verify_python('/fixture/python', locked)
            inspect.assert_not_called()


if __name__ == '__main__':
    unittest.main()
