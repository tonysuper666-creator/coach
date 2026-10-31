"""Real OS process cleanup tests, including a descendant with its own session."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

GUARD = Path(__file__).with_name('service-child.py')


def wait_until(predicate):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.05)
    raise AssertionError('Process cleanup timed out')


def alive(pid):
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True)
    return bool(result.stdout.strip()) and not result.stdout.strip().startswith('Z')


class ChildGuardTest(unittest.TestCase):
    def exercise(self, kill_parent):
        with tempfile.TemporaryDirectory(prefix='coach-guard-') as work:
            root = Path(work)
            (root / 'service.py').write_text('''import pathlib,subprocess,sys,time
cli=subprocess.Popen([sys.executable,'-c','import time; time.sleep(120)'],start_new_session=True)
pathlib.Path(sys.argv[1]).write_text(str(cli.pid))
time.sleep(120)
''')
            command = [sys.executable, str(GUARD), str(root / 'service.pid'), sys.executable,
                str(root / 'service.py'), str(root / 'cli.pid')]
            if kill_parent:
                (root / 'parent.py').write_text('''import pathlib,subprocess,sys,time
guard=subprocess.Popen(sys.argv[2:])
pathlib.Path(sys.argv[1]).write_text(str(guard.pid))
time.sleep(120)
''')
                command = [sys.executable, str(root / 'parent.py'), str(root / 'guard.pid'), *command]
            parent = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            service = cli = guard = None
            try:
                wait_until(lambda: (root / 'cli.pid').exists() and (root / 'service.pid').exists())
                service = int((root / 'service.pid').read_text())
                cli = int((root / 'cli.pid').read_text())
                guard = int((root / 'guard.pid').read_text()) if kill_parent else parent.pid
                time.sleep(.7)  # Allow the guard to observe the separate CLI session.
                os.kill(parent.pid, signal.SIGKILL if kill_parent else signal.SIGTERM)
                parent.wait(timeout=10)
                wait_until(lambda: not any(alive(pid) for pid in (service, cli, guard)))
                error = parent.communicate(timeout=10)[1].decode()
                self.assertFalse((root / 'service.pid').exists(), error)
            finally:
                for pid in (service, cli, guard):
                    if pid and alive(pid):
                        os.kill(pid, signal.SIGKILL)
                if parent.poll() is None:
                    parent.kill()
                parent.communicate(timeout=10)

    def test_term_removes_service_and_separate_cli_group(self):
        self.exercise(False)

    def test_parent_sigkill_removes_service_and_separate_cli_group(self):
        self.exercise(True)


if __name__ == '__main__':
    unittest.main()
