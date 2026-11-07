"""Read-only process-image checks for the unfinished TON-1193 adapter.

The caller must supply the journal-selected, independently pinned snapshot and
revalidate it and the launchd identity before and after this check. This module
is not a controller and does not authorize a switch or make a process ready.
"""
import ctypes
import os
import subprocess

from .ton1175_switch import GateError, require
from .ton1193_trust import file_snapshot


def _libproc():
    try:
        lib = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
        lib.proc_pidpath.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32]
        lib.proc_pidpath.restype = ctypes.c_int
        lib.proc_listchildpids.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
        lib.proc_listchildpids.restype = ctypes.c_int
        return lib
    except OSError as exc:
        raise GateError('libproc unavailable') from exc


def _image(pid):
    buf = ctypes.create_string_buffer(4096)
    count = _libproc().proc_pidpath(pid, buf, len(buf))
    require(0 < count < len(buf), 'process image unavailable')
    try:
        path = os.fsdecode(buf.value)
        require(path.startswith('/'), 'invalid process image')
        return path
    except (ValueError, UnicodeError) as exc:
        raise GateError('invalid process image') from exc


def _children(pid):
    capacity = 4096
    result = (ctypes.c_int * capacity)()
    count = _libproc().proc_listchildpids(pid, result, ctypes.sizeof(result))
    require(0 <= count < capacity, 'child process list unavailable or truncated')
    children = list(result[:count])
    require(all(child > 1 for child in children) and len(children) == len(set(children)),
            'invalid child process list')
    return sorted(children)


def _identity(pid):
    try:
        result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'ppid=', '-o', 'lstart=',
                                 '-o', 'command='], stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=10, check=False)
        text = os.fsdecode(result.stdout).strip()
        require(result.returncode == 0 and bool(text), 'process identity unavailable')
        parent, rest = text.split(None, 1)
        require(parent.isdecimal() and bool(rest), 'invalid process identity')
        return int(parent), text
    except (OSError, subprocess.TimeoutExpired, ValueError) as exc:
        raise GateError('process identity unavailable') from exc


def _verify_image(pid, choices):
    path = _image(pid)
    matches = [(expected, root) for expected, root in choices if expected and expected[0] == path]
    require(matches, 'unexpected process image')
    try:
        require(any(tuple(file_snapshot(path, root)) == tuple(expected)
                    for expected, root in matches), 'process executable changed')
    except (OSError, ValueError, TypeError) as exc:
        raise GateError('process executable unavailable') from exc
    return path


def bind(pid, side, roots):
    """Check a JVM and its direct children against an already verified snapshot.

    Reject unknown images, executable replacement, PID identity changes and a
    changing child set. The returned identities are evidence, not a launchd
    identity or a substitute for subsequent transaction/pointer revalidation.
    """
    require(type(pid) is int and pid > 1, 'invalid process PID')
    try:
        java = [(side['java'], roots['cellar'])]
        children_allowed = [(side['node'], roots['node']),
                            (side['python'][2], roots['cellar']),
                            (side['stub'], roots['cellar'])]
        root_identity = _identity(pid)
        root_image = _verify_image(pid, java)
        children = _children(pid)
        observed = {}
        for child in children:
            identity = _identity(child)
            require(identity[0] == pid, 'child process parent changed')
            image = _verify_image(child, children_allowed)
            require(_identity(child) == identity and _image(child) == image,
                    'child process identity changed')
            observed[child] = (identity, image)
        require(_children(pid) == children, 'child process set changed')
        for child, (identity, image) in observed.items():
            require(_identity(child) == identity and _verify_image(child, children_allowed) == image,
                    'child process identity changed')
        require(_identity(pid) == root_identity and _verify_image(pid, java) == root_image,
                'root process identity changed')
        require(_children(pid) == children, 'child process set changed')
        return {'pid': pid, 'identity': root_identity, 'image': root_image,
                'children': observed}
    except (KeyError, IndexError, TypeError) as exc:
        raise GateError('invalid process snapshot') from exc
