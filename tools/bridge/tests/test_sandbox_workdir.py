"""The sandbox must allow each isolated bridge's work, while keeping its data private."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform == 'darwin', 'macOS SBPL')
class SandboxWorkdirTest(unittest.TestCase):
    def test_custom_bridge_work_is_writable_and_sibling_data_is_unreadable(self):
        with tempfile.TemporaryDirectory(prefix='coach-sandbox-', dir=Path.home()) as folder:
            root = Path(folder)
            bridge_home = root / 'bridge "isolated"'
            env = os.environ.copy()
            env.update(COACH_BRIDGE_HOME=str(bridge_home), BRIDGE_PORT='18850')
            source = Path(__file__).resolve().parents[1] / 'subscription_bridge.py'
            subprocess.run([sys.executable, '-c',
                'import runpy,sys; runpy.run_path(sys.argv[1],run_name="sandbox_test")', str(source)],
                env=env, check=True, capture_output=True)
            work = bridge_home / 'work'
            secret = root / 'coach.sqlite'
            secret.write_text('PRIVATE_LEDGER_SENTINEL')
            profile = work / 'agent_sandbox.sb'
            def sandbox(*args):
                return subprocess.run(['/usr/bin/sandbox-exec', '-f', str(profile), *args],
                    cwd=work, capture_output=True, text=True)
            output = work / 'reply.txt'
            write = sandbox('/bin/sh', '-c', 'printf reply > "$1"', 'sh', str(output))
            self.assertEqual(0, write.returncode, write.stderr)
            self.assertEqual('reply', output.read_text())
            denied = sandbox('/bin/cat', str(secret))
            self.assertNotEqual(0, denied.returncode)
            self.assertNotIn('PRIVATE_LEDGER_SENTINEL', denied.stdout)


if __name__ == '__main__':
    unittest.main()
