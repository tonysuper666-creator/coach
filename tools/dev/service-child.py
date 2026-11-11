#!/usr/bin/env python3
"""Own one service's process groups, including CLI sessions; die with the JVM.

Ktor decides when to start/restart. This guard only forwards stdio and cleans up,
even when its parent is SIGKILLed (a JVM shutdown hook cannot cover that case).
"""
import ctypes
from dataclasses import dataclass
import errno
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


@dataclass(frozen=True)
class Process:
    pid: int
    ppid: int
    pgid: int
    started: object
    zombie: bool


class BsdInfo(ctypes.Structure):
    # macOS SDK sys/proc_info.h: struct proc_bsdinfo (PROC_PIDTBSDINFO).
    # https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/proc_info.h
    _fields_ = [(name, ctypes.c_uint32) for name in (
        'flags', 'status', 'xstatus', 'pid', 'ppid', 'uid', 'gid', 'ruid',
        'rgid', 'svuid', 'svgid', 'reserved')]
    _fields_ += [('comm', ctypes.c_char * 16), ('name', ctypes.c_char * 32)]
    _fields_ += [(name, ctypes.c_uint32) for name in (
        'nfiles', 'pgid', 'jobc', 'tdev', 'tpgid', 'nice')]
    _fields_ += [('start_sec', ctypes.c_uint64), ('start_usec', ctypes.c_uint64)]


class Processes:
    """Fresh kernel identities without spawning another potentially stuck ps."""
    def __init__(self):
        if sys.platform == 'darwin':
            self.lib = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
            self.lib.proc_listallpids.argtypes = [ctypes.c_void_p, ctypes.c_int]
            self.lib.proc_listallpids.restype = ctypes.c_int
            self.lib.proc_pidinfo.argtypes = [ctypes.c_int, ctypes.c_int,
                ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int]
            self.lib.proc_pidinfo.restype = ctypes.c_int
        elif not sys.platform.startswith('linux'):
            # Refuse before starting any workload without a safe cleanup backend.
            raise RuntimeError('Service guard requires macOS libproc or Linux procfs')
        self.native_only = False

    def pids(self):
        if sys.platform != 'darwin':
            return [int(path.name) for path in Path('/proc').iterdir() if path.name.isdigit()]
        count = self.lib.proc_listallpids(None, 0)
        if count <= 0:
            raise OSError(ctypes.get_errno(), 'Cannot enumerate processes')
        # Allow for concurrent forks; retry boundedly if the buffer filled.
        for _ in range(3):
            buffer = (ctypes.c_int * (count + 256))()
            size = self.lib.proc_listallpids(buffer, ctypes.sizeof(buffer))
            if size <= 0:
                raise OSError(ctypes.get_errno(), 'Cannot enumerate processes')
            if size < len(buffer):
                return [pid for pid in buffer[:size] if pid > 0]
            count = len(buffer) * 2
        raise RuntimeError('Process list kept growing during enumeration')

    def get(self, pid):
        if sys.platform == 'darwin':
            info = BsdInfo()
            size = self.lib.proc_pidinfo(pid, 3, 0, ctypes.byref(info), ctypes.sizeof(info))
            if size == 0 and ctypes.get_errno() in (errno.ESRCH, errno.ENOENT):
                return None
            if size != ctypes.sizeof(info):
                raise OSError(ctypes.get_errno(), 'Cannot inspect process identity')
            return Process(info.pid, info.ppid, info.pgid,
                (info.start_sec, info.start_usec), info.status == 5)
        try:
            # comm may contain spaces and parentheses. Fields after its final ')'
            # start at stat field 3; starttime is field 22.
            fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
            return Process(pid, int(fields[1]), int(fields[2]), int(fields[19]), fields[0] == 'Z')
        except (FileNotFoundError, ProcessLookupError):
            return None

    def snapshot(self, native=False):
        if native or self.native_only:
            pids = self.pids()
        else:
            try:
                pids = map(int, subprocess.check_output(
                    ['/bin/ps', '-axo', 'pid='], text=True, timeout=3).split())
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError, OSError):
                # One timeout costs at most 3s; never repeat that cost in finally
                # or in later scans. The JVM allows only 8s for guard shutdown.
                self.native_only = True
                print('COACH service guard: ps unavailable; using kernel process queries', file=sys.stderr)
                pids = self.pids()
        rows = []
        for pid in pids:
            try:
                row = self.get(pid)
            except PermissionError:
                continue  # Uninspectable processes never become signal targets.
            if row is not None and not row.zombie:
                rows.append(row)
        return rows


class Groups:
    def __init__(self, child, processes):
        self.child = child
        self.processes = processes
        self.groups = {}

    def collect(self, native=False):
        table = self.processes.snapshot(native)
        # Root remains our unreaped direct child through the LAST signal. Its
        # PID/initial PGID cannot be recycled, even after the workload exits.
        live = {(row.pid, row.pgid, row.started) for row in table}
        for group in list(self.groups):
            self.groups[group] = {(pid, started) for pid, started in self.groups[group]
                if (pid, group, started) in live}
            if not self.groups[group]:
                del self.groups[group]
        descendants = {self.child.pid}
        while True:
            found = {row.pid for row in table if row.ppid in descendants}
            if found <= descendants:
                break
            descendants |= found
        for row in table:
            if row.pid in descendants or row.pgid in self.groups:
                self.groups.setdefault(row.pgid, set()).add((row.pid, row.started))

    def signal(self, sig):
        self.collect(native=True)
        for group, members in list(self.groups.items()):
            # Never signal a cached PGID on faith. Recheck a live member's full
            # kernel start identity immediately before each group signal.
            for pid, started in members:
                row = self.processes.get(pid)
                if row and not row.zombie and (row.pgid, row.started) == (group, started):
                    try:
                        os.killpg(group, sig)
                    except ProcessLookupError:
                        pass
                    except PermissionError:
                        # Darwin may report EPERM if the last member just died.
                        if any((current := self.processes.get(member)) and not current.zombie
                               and (current.pgid, current.started) == (group, birth)
                               for member, birth in members):
                            raise
                    break


def main():
    parent = os.getppid()
    pid_file = Path(sys.argv[1])
    processes = Processes()
    stopping = False

    def stop(*_):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    # Do not inherit SIG_IGN/automatic child reaping from a launcher: the root
    # must remain waitable until every group signal has finished.
    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    child = subprocess.Popen(sys.argv[2:], start_new_session=True)
    groups = Groups(child, processes)

    def running():
        # Popen.poll() would reap an exited child and release our pinned PID.
        row = processes.get(child.pid)
        return row is not None and not row.zombie

    try:
        pid_file.write_text(str(child.pid))
        while not stopping and os.getppid() == parent and running():
            groups.collect()
            time.sleep(.5)
    finally:
        try:
            # Continue before TERM, so stopped services can perform cleanup too.
            for sig in (signal.SIGCONT, signal.SIGTERM):
                groups.signal(sig)
            deadline = time.monotonic() + 3
            while running() and time.monotonic() < deadline:
                time.sleep(.05)
        finally:
            try:
                groups.signal(signal.SIGKILL)
            finally:
                # Safe even if inspection itself failed: we have NEVER reaped
                # this session leader, so its initial group ID is still pinned.
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except PermissionError:
                    # A pinned zombie-only group returns EPERM on Darwin.
                    if any(row.pgid == child.pid for row in processes.snapshot(native=True)):
                        raise
                child.wait(timeout=1)
                pid_file.unlink(missing_ok=True)
    return child.returncode if child.returncode and child.returncode > 0 else 0


if __name__ == '__main__':
    sys.exit(main())
