#!/usr/bin/env python3
"""Own one service's process groups, including CLI sessions; die with the JVM.

Ktor decides when to start/restart. This guard only forwards stdio and cleans up,
even when its parent is SIGKILLed (a JVM shutdown hook cannot cover that case).
"""
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def main():
    parent = os.getppid()
    pid_file = Path(sys.argv[1])
    stopping = False

    def stop(*_):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    child = subprocess.Popen(sys.argv[2:], start_new_session=True)
    pid_file.write_text(str(child.pid))
    groups = {}

    def collect_groups():
        rows = subprocess.check_output(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat=,lstart='], text=True, timeout=3)
        table = [row.split(maxsplit=4) for row in rows.splitlines()]
        table = [(int(pid), int(ppid), int(pgid), started) for pid, ppid, pgid, stat, started in table if not stat.startswith('Z')]
        # A long-lived bridge can launch thousands of CLI groups. Forget dead
        # groups and verify a live member's start time before signalling a PGID,
        # so PID/PGID reuse cannot target an unrelated process days later.
        live = {(pid, pgid, started) for pid, _, pgid, started in table}
        for group in list(groups):
            groups[group] = {(pid, started) for pid, started in groups[group] if (pid, group, started) in live}
            if not groups[group]:
                del groups[group]
        descendants = {child.pid}
        while True:
            found = {pid for pid, ppid, _, _ in table if ppid in descendants}
            if found <= descendants:
                break
            descendants |= found
        for pid, _, group, started in table:
            if pid in descendants or group in groups:
                groups.setdefault(group, set()).add((pid, started))

    def signal_groups(sig):
        collect_groups()
        for group in list(groups):
            try:
                os.killpg(group, sig)
            except ProcessLookupError:
                pass
            except PermissionError:
                # Darwin returns EPERM for a group containing only zombies.
                # Ignore that race only after proving it has no live members.
                collect_groups()
                if group in groups:
                    raise

    try:
        while not stopping and os.getppid() == parent and child.poll() is None:
            collect_groups()
            time.sleep(.5)
    finally:
        collect_groups()
        # Continue before TERM, so stopped services can perform cleanup too.
        signal_groups(signal.SIGCONT)
        signal_groups(signal.SIGTERM)
        deadline = time.monotonic() + 3
        while child.poll() is None and time.monotonic() < deadline:
            time.sleep(.05)
        signal_groups(signal.SIGKILL)
        child.wait()
        pid_file.unlink(missing_ok=True)
    return child.returncode if child.returncode and child.returncode > 0 else 0


if __name__ == '__main__':
    sys.exit(main())
