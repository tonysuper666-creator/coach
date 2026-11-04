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

# Importable guard code keeps fault injection confined to the child interpreter;
# no production switches or environment variables are added to the guard.
import importlib.util
import json
from types import SimpleNamespace
from unittest.mock import Mock, patch


def load_guard():
    spec = importlib.util.spec_from_file_location('service_child', GUARD)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class GuardFaultTest(unittest.TestCase):
    def exercise_timeout(self, continuous, kill_parent, stubborn, in_flight=False):
        with tempfile.TemporaryDirectory(prefix='coach-guard-timeout-') as work:
            root = Path(work)
            (root / 'runner.py').write_text('''import pathlib,runpy,signal,subprocess,sys
root=pathlib.Path(sys.argv[1])
continuous=sys.argv[2]=='True'
# Model a launcher that automatically reaps children; guard must reset this.
signal.signal(signal.SIGCHLD,signal.SIG_IGN)
original=subprocess.check_output
calls=0
def slow(command, **kwargs):
    global calls
    if command[0]=='/bin/ps':
        calls+=1
        (root/'ps-calls').write_text(str(calls))
        if continuous or calls==1:
            (root/'ps-started').touch()
            try:
                return original([sys.executable,'-c','import time; time.sleep(30)'], **kwargs)
            except subprocess.TimeoutExpired:
                (root/'ps-timeout').touch()
                raise
    return original(command, **kwargs)
subprocess.check_output=slow
sys.argv=sys.argv[3:]
runpy.run_path(sys.argv[0],run_name='__main__')
''')
            (root / 'cli.py').write_text('''import json,os,pathlib,signal,socket,subprocess,sys,time
root=pathlib.Path(sys.argv[1])
if sys.argv[2]=='True': signal.signal(signal.SIGTERM,signal.SIG_IGN)
listener=socket.socket()
listener.bind(('127.0.0.1',0))
listener.listen()
grandchild=subprocess.Popen([sys.executable,'-c','import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(120)'])
(root/'cli.json').write_text(json.dumps(dict(pid=os.getpid(),grandchild=grandchild.pid,port=listener.getsockname()[1])))
time.sleep(120)
''')
            (root / 'service.py').write_text('''import pathlib,signal,subprocess,sys,time
root=pathlib.Path(sys.argv[1])
if sys.argv[2]=='True': signal.signal(signal.SIGTERM,signal.SIG_IGN)
while not (root/'launch').exists(): time.sleep(.02)
subprocess.Popen([sys.executable,str(root/'cli.py'),str(root),sys.argv[2]],start_new_session=True)
time.sleep(120)
''')
            (root / 'sentinel.py').write_text('''import os,pathlib,signal,sys,time
path=pathlib.Path(sys.argv[1])
def record(sig,*_):
    with path.open('a') as out: out.write(str(sig)+'\\n')
for sig in (signal.SIGCONT,signal.SIGTERM): signal.signal(sig,record)
path.with_suffix('.ready').touch()
time.sleep(120)
''')
            command = [sys.executable, str(root / 'runner.py'), str(root), str(continuous),
                str(GUARD), str(root / 'service.pid'), sys.executable,
                str(root / 'service.py'), str(root), str(stubborn)]
            if kill_parent:
                (root / 'parent.py').write_text('''import pathlib,subprocess,sys,time
child=subprocess.Popen(sys.argv[2:])
pathlib.Path(sys.argv[1]).write_text(str(child.pid))
time.sleep(120)
''')
                command = [sys.executable, str(root / 'parent.py'), str(root / 'guard.pid'), *command]
            sentinel = subprocess.Popen([sys.executable, str(root / 'sentinel.py'), str(root / 'signals')],
                start_new_session=True)
            parent = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            owned = []
            try:
                wait_until(lambda: (root / 'signals.ready').exists() and (root / 'service.pid').exists()
                    and (root / 'ps-started').exists())
                service = int((root / 'service.pid').read_text())
                guard = int((root / 'guard.pid').read_text()) if kill_parent else parent.pid
                owned.extend((service, guard))
                # Usually finish a real 3s timeout, THEN create the detached CLI.
                # In-flight shutdown instead creates it while ps is blocked.
                # Both must discover it without a successful ps/cached snapshot.
                if not in_flight:
                    wait_until(lambda: (root / 'ps-timeout').exists())
                self.assertTrue(alive(service))
                self.assertTrue(alive(guard))
                (root / 'launch').touch()
                wait_until(lambda: (root / 'cli.json').exists() and (root / 'cli.json').stat().st_size > 0)
                cli = json.loads((root / 'cli.json').read_text())
                owned.extend((cli['pid'], cli['grandchild']))
                time.sleep(.7)
                if stubborn:
                    os.kill(cli['pid'], signal.SIGSTOP)
                started = time.monotonic()
                os.kill(parent.pid, signal.SIGKILL if kill_parent else signal.SIGTERM)
                parent.wait(timeout=8)
                wait_until(lambda: not any(alive(pid) for pid in owned))
                elapsed = time.monotonic() - started
                error = parent.communicate(timeout=5)[1].decode()
                self.assertLess(elapsed, 8, error)
                self.assertNotIn('Traceback', error)
                self.assertTrue((root / 'ps-timeout').exists(), 'Must exercise a real timeout')
                self.assertFalse((root / 'service.pid').exists(), error)
                self.assertEqual('1', (root / 'ps-calls').read_text(), 'Must latch to native queries')
                self.assertIsNone(sentinel.poll(), 'Unrelated process was killed')
                self.assertFalse((root / 'signals').exists(), 'Unrelated process received a signal')
                import socket
                with socket.socket() as replacement:
                    replacement.bind(('127.0.0.1', cli['port']))
                    replacement.listen()
                print(f'ps={"continuous" if continuous else "transient"} parent={"KILL" if kill_parent else "TERM"} '
                      f'cleanup={elapsed:.2f}s rebind=OK unrelated=alive', flush=True)
            finally:
                # Only fixture-owned PIDs; inspect before cleanup, never names.
                for pid in owned:
                    if alive(pid):
                        os.kill(pid, signal.SIGKILL)
                if parent.poll() is None:
                    parent.kill()
                parent.communicate(timeout=10)
                if sentinel.poll() is None:
                    sentinel.kill()
                sentinel.wait(timeout=5)

    def test_transient_ps_timeout_term(self):
        self.exercise_timeout(False, False, False)

    def test_continuous_ps_timeout_term_stopped_and_stubborn(self):
        self.exercise_timeout(True, False, True)

    def test_continuous_ps_timeout_parent_sigkill(self):
        self.exercise_timeout(True, True, True)

    def test_transient_ps_timeout_parent_sigkill(self):
        self.exercise_timeout(False, True, False)

    def test_shutdown_during_ps_timeout_fits_jvm_grace(self):
        self.exercise_timeout(True, False, True, in_flight=True)

    def test_same_second_pid_reuse_is_not_signalled(self):
        guard = load_guard()
        processes = Mock()
        # Same PID and PGID, same second, DIFFERENT microsecond identity.
        unrelated = guard.Process(42, 1, 42, (1000, 456), False)
        processes.snapshot.return_value = [unrelated]
        processes.get.return_value = unrelated
        groups = guard.Groups(SimpleNamespace(pid=99), processes)
        groups.groups = {42: {(42, (1000, 123))}}
        with patch.object(guard.os, 'killpg') as killpg:
            groups.signal(signal.SIGKILL)
        killpg.assert_not_called()

    def test_identity_changes_between_scan_and_signal(self):
        guard = load_guard()
        processes = Mock()
        processes.snapshot.return_value = [guard.Process(42, 1, 42, (1000, 123), False)]
        processes.get.return_value = guard.Process(42, 1, 42, (1000, 456), False)
        groups = guard.Groups(SimpleNamespace(pid=99), processes)
        groups.groups = {42: {(42, (1000, 123))}}
        with patch.object(guard.os, 'killpg') as killpg:
            groups.signal(signal.SIGKILL)
        killpg.assert_not_called()

    def test_inspection_failure_does_not_signal_cached_group(self):
        guard = load_guard()
        processes = Mock()
        processes.snapshot.side_effect = OSError('kernel query failed')
        groups = guard.Groups(SimpleNamespace(pid=99), processes)
        groups.groups = {42: {(42, (1000, 123))}}
        with patch.object(guard.os, 'killpg') as killpg:
            with self.assertRaises(OSError):
                groups.signal(signal.SIGKILL)
        killpg.assert_not_called()


if __name__ == '__main__':
    unittest.main()
