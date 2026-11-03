#!/usr/bin/env python3
"""Bounded foreground supervisor for TON-1175 switch/recover. Default: dry-run.

Trusted commands must not escape their command session. Service controllers
must detach the service into its own session and close inherited descriptors.
The supervisor is the cleanup owner: it must itself stay alive through recovery.
"""
import argparse
from dataclasses import dataclass
import fcntl
import json
import math
import os
from pathlib import Path
import selectors
import signal
import socket
import subprocess
import sys
import time

try:
    from .ton1175_switch import atomic, load, GateError, require
except ImportError:
    from ton1175_switch import atomic, load, GateError, require


@dataclass(frozen=True)
class Budgets:
    transaction: float = 600
    grace: float = 150
    recovery: float = 180
    acceptance: float = 300


def send(stream, value):
    data = memoryview((json.dumps(value, separators=(',', ':')) + '\n').encode())
    while data:
        data = data[stream.write(data):]


def remote_run(argv, timeout):
    """Synchronous worker-side RPC; cancellation drains before rollback RPCs."""
    require(timeout > 0, 'acceptance deadline expired')
    # dup() is deliberately non-inheritable. Commands never inherit this socket
    # (or the switcher's coverage seal), including during detached service start.
    with os.fdopen(os.dup(int(os.environ['TON1175_SUPERVISOR_FD'])), 'r+b', buffering=0) as stream:
        try:
            # A signal may arrive during registration or after a result was
            # consumed. Explicit cancellation acknowledgement handles both.
            previous = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGTERM, signal.SIGHUP})
            try:
                send(stream, dict(argv=argv, timeout=timeout))
            finally:
                signal.pthread_sigmask(signal.SIG_SETMASK, previous)
            reply = stream.readline(4096)
        except BaseException:
            send(stream, dict(cancel=True))
            while True:
                line = stream.readline(4096)
                if not line or json.loads(line).get('cancelled'):
                    break
            raise
    require(reply and json.loads(reply).get('ok') is True, 'supervised controller or verifier failed')


def acceptance_checkpoint():
    with os.fdopen(os.dup(int(os.environ['TON1175_SUPERVISOR_FD'])), 'wb', buffering=0) as stream:
        send(stream, dict(checkpoint='acceptance'))


def command_signals():
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    signal.signal(signal.SIGHUP, signal.SIG_DFL)


def guardian():
    """A live, unreaped group leader pins its PGID until parent cleanup.

    No user command starts until the supervisor has persisted the PID and sends
    the gate message. TERM affects the workload; the leader survives until KILL.
    An exited workload's descendants therefore cannot outlive the cleanup handle.
    """
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    request = json.loads(sys.stdin.buffer.readline())
    try:
        child = subprocess.Popen(request['argv'], stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 close_fds=True, preexec_fn=command_signals)
        code = child.wait()
    except (OSError, ValueError):
        code = 127
    print(json.dumps(dict(code=code)), flush=True)
    # Parent keeps stdin open. Also stay alive if EOF arrives unexpectedly: the
    # supervisor, rather than this process, owns group teardown and waitpid.
    while True:
        signal.pause()


class Supervisor:
    def __init__(self, config, directory, budgets):
        self.config = Path(config).resolve()
        self.directory = Path(directory).resolve()
        self.budgets = budgets
        self.worker = None
        self.job = None
        self.interrupted = False
        self.sequence = 0

    def event(self, event, **fields):
        self.sequence += 1
        # Never record executable argv, child output, config contents or tokens.
        row = dict(sequence=self.sequence, at=time.time(), event=event, **fields)
        with (self.directory / 'events.jsonl').open('ab', buffering=0) as output:
            os.chmod(output.name, 0o600)
            output.write((json.dumps(row) + '\n').encode())
            os.fsync(output.fileno())
        atomic(self.directory / 'owner.json', json.dumps(dict(
            supervisorPid=os.getpid(), workerPid=self.worker.pid if self.worker else None,
            commandPgid=self.job.pid if self.job else None,
            config=str(self.config), journal=str(self.ops / 'journal.json'),
            recover=[sys.executable, str(Path(__file__).resolve()), 'recover',
                     '--config', str(self.config), '--record-dir', 'NEW_PRIVATE_DIRECTORY', '--apply'],
            budgets=vars(self.budgets), last=row)).encode())

    def phase(self):
        return load(self.ops / 'journal.json')['phase']

    def cleanup_job(self):
        if self.job is None:
            return
        job = self.job
        # We have never polled/waited this guardian. Its PID cannot be reused.
        # It is a direct Python child, in a session we created, never the service
        # or Multica daemon. Do not discover targets with process-name searches.
        try:
            os.killpg(job.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        job.wait(timeout=5)
        job.stdin.close()
        job.stdout.close()
        self.job = None
        self.event('command_reaped', pgid=job.pid, guardianCode=job.returncode)

    def worker_run(self, action, apply, budget, graceful):
        """One worker and at most one brokered command, all owned in foreground."""
        parent, child = socket.socketpair()
        parent.set_inheritable(False)
        environment = dict(os.environ, TON1175_SUPERVISOR_FD=str(child.fileno()))
        script = Path(__file__).with_name('ton1175_switch.py')
        self.worker = subprocess.Popen([sys.executable, str(script), action,
                '--config', str(self.config)] + (['--apply'] if apply else []),
                env=environment, pass_fds=(child.fileno(),), start_new_session=True,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        child.close()
        worker = self.worker
        deadline = time.monotonic() + budget
        stopping = False
        timed_out = False
        job_deadline = 0
        buffer = b''
        acceptance_seen = False
        selector = selectors.DefaultSelector()
        selector.register(parent, selectors.EVENT_READ, 'request')
        try:
            self.event('worker_started', action=action, pid=worker.pid)
            while True:
                now = time.monotonic()
                if not stopping and (now >= deadline or self.interrupted):
                    timed_out = True
                    stopping = True
                    self.event('worker_terminate', pid=worker.pid)
                    # Popen.poll is not used before this signal. PID is still
                    # held as our unreaped child, even if it just exited.
                    worker.send_signal(signal.SIGTERM)
                    deadline = now + graceful
                if stopping and now >= deadline:
                    self.event('worker_kill', pid=worker.pid)
                    worker.kill()
                    worker.wait(timeout=5)
                    break
                # waitpid(WNOHANG) is safe now: no later signals are sent if it
                # returns an exit status. There are no children in worker's group.
                code = worker.poll()
                if code is not None:
                    break
                if self.job is not None and now >= job_deadline:
                    self.event('command_timeout', pgid=self.job.pid)
                    selector.unregister(self.job.stdout)
                    self.cleanup_job()
                    parent.sendall(b'{"ok":false}\n')
                for key, _ in selector.select(timeout=0.02):
                    if key.data == 'result':
                        if self.job is None or key.fileobj is not self.job.stdout:
                            continue  # Cancellation removed this ready handle.
                        line = self.job.stdout.readline(4096)
                        result = json.loads(line) if line else {}
                        self.event('command_finished', pgid=self.job.pid, code=result.get('code'))
                        selector.unregister(self.job.stdout)
                        self.cleanup_job()
                        parent.sendall((json.dumps(dict(ok=result.get('code') == 0))+'\n').encode())
                        continue
                    data = parent.recv(1024 * 1024)
                    if not data:
                        selector.unregister(parent)
                        continue
                    buffer += data
                    require(len(buffer) <= 1024 * 1024, 'oversized supervisor request')
                    while b'\n' in buffer:
                        raw, buffer = buffer.split(b'\n', 1)
                        request = json.loads(raw)
                        if request.get('checkpoint') == 'acceptance':
                            require(action == 'switch' and not acceptance_seen, 'invalid acceptance checkpoint')
                            acceptance_seen = True
                            if not stopping:
                                deadline = max(deadline, time.monotonic() + self.budgets.acceptance)
                            self.event('acceptance_budget', seconds=self.budgets.acceptance)
                            continue
                        if request.get('cancel'):
                            if self.job is not None:
                                selector.unregister(self.job.stdout)
                                self.cleanup_job()
                            parent.sendall(b'{"cancelled":true}\n')
                            continue
                        require(self.job is None, 'overlapping command request')
                        argv, timeout = request['argv'], float(request['timeout'])
                        require(isinstance(argv, list) and argv and all(isinstance(x, str) for x in argv)
                                and math.isfinite(timeout) and timeout > 0, 'invalid command request')
                        self.job = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_guardian'],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                start_new_session=True, bufsize=0)
                        # Creation/registration race: the guardian cannot launch
                        # argv until after this durable event and parent gate.
                        self.event('command_registered', pgid=self.job.pid)
                        send(self.job.stdin, dict(argv=argv))
                        job_deadline = time.monotonic() + timeout
                        selector.register(self.job.stdout, selectors.EVENT_READ, 'result')
        finally:
            try:
                self.cleanup_job()
            finally:
                if worker.poll() is None:
                    worker.kill()
                    worker.wait(timeout=5)
                parent.close()
                selector.close()
                code = worker.returncode
                self.worker = None
                self.event('worker_reaped', code=code, timedOut=timed_out)
        return code, timed_out

    def execute(self, action, apply):
        config = load(self.config)
        self.ops = Path(config['stateDir']).resolve()
        self.directory.mkdir(mode=0o700)  # New per invocation; never overwrite.
        os.chmod(self.directory, 0o700)
        # Serialize supervisors independently from the transaction's lock. The
        # worker must acquire the transaction lock itself; waiting there is timed.
        with (self.ops / 'supervisor.lock').open('a+b') as lock:
            os.chmod(lock.name, 0o600)
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise GateError('another supervisor owns this transaction') from error
            self.event('started', action=action, apply=apply)
            try:
                code, expired = self.worker_run(action, apply, self.budgets.transaction, self.budgets.grace)
            except (GateError, OSError, ValueError, KeyError, TypeError):
                code, expired = 1, False
                self.event('worker_transport_failed')
            phase = self.phase()
            # A worker can die without receiving TERM (SIGKILL/crash). Clean
            # every owned group first, then recover with a separate bounded worker.
            needs_recovery = apply and (code != 0 or expired) and phase in (
                'switching', 'validating', 'recovering', 'recovery_required', 'switched')
            recovered = True
            if needs_recovery:
                self.event('recovery_started', phase=phase)
                self.interrupted = False
                try:
                    result, _ = self.worker_run('recover', True, self.budgets.recovery, 0)
                except (GateError, OSError, ValueError, KeyError, TypeError):
                    result = 1
                recovered = result == 0 and self.phase() == 'rolled_back'
            outcome = 'recovery_required' if not recovered else 'failed' if code != 0 or expired else 'complete'
            self.event('finished', outcome=outcome, phase=self.phase())
            print('supervisor:', outcome, '; private cleanup/recover record:', self.directory)
            return 2 if not recovered else 124 if expired else 1 if code != 0 else 0


def main(argv=None, *, budgets=None):
    if argv is None and sys.argv[1:] == ['_guardian']:
        guardian()
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('switch', 'rollback', 'recover'))
    parser.add_argument('--config', required=True)
    parser.add_argument('--record-dir', required=True, help='new private directory for this invocation')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)
    runner = Supervisor(args.config, args.record_dir, budgets or Budgets())
    def interrupt(signum, frame):
        runner.interrupted = True
    previous = {sig: signal.signal(sig, interrupt) for sig in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)}
    try:
        return runner.execute(args.action, args.apply)
    except (GateError, OSError, ValueError, KeyError, TypeError):
        print('ERROR: supervisor failed; inspect private record and journal; recover required if incomplete', file=sys.stderr)
        return 2
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    sys.exit(main())
