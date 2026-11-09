"""Clone and fingerprint a stopped data directory; not a transaction controller.

The caller must prove process and database-lock quiescence and journal the
result before using it.  This module does not stop services or restore data.
"""
import ctypes
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path

from .ton1175_verify import GateError, require
from .ton1193_trust import _open_path


def _manifest_fd(fd):
    entries = []
    root = os.fstat(fd)

    def visit(directory, prefix):
        for name in sorted(os.listdir(directory)):
            info = os.stat(name, dir_fd=directory, follow_symlinks=False)
            rel = prefix + name
            require(info.st_uid == os.getuid(), 'snapshot entry owner differs')
            require(info.st_dev == root.st_dev, 'snapshot contains another volume')
            if stat.S_ISDIR(info.st_mode):
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                try:
                    opened = os.fstat(child)
                    require((opened.st_dev, opened.st_ino) == (info.st_dev, info.st_ino),
                            'snapshot directory changed')
                    entries.append([rel, 'directory'])
                    visit(child, rel + '/')
                finally:
                    os.close(child)
            else:
                require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
                        'snapshot contains a link or special file')
                child = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
                try:
                    opened = os.fstat(child)
                    require((opened.st_dev, opened.st_ino) == (info.st_dev, info.st_ino),
                            'snapshot file changed')
                    sha = hashlib.sha256()
                    while True:
                        data = os.read(child, 1024 * 1024)
                        if not data:
                            break
                        sha.update(data)
                    after = os.fstat(child)
                    require((after.st_size, after.st_mtime_ns, after.st_ctime_ns) ==
                            (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns),
                            'snapshot file changed while reading')
                    entries.append([rel, 'file', opened.st_size, sha.hexdigest()])
                finally:
                    os.close(child)
    visit(fd, '')
    require(any(e[0] == 'coach.sqlite' and e[1] == 'file' for e in entries),
            'snapshot database missing')
    encoded = json.dumps(entries, ensure_ascii=True, separators=(',', ':')).encode()
    return hashlib.sha256(encoded).hexdigest()


def fingerprint(path):
    fd = _open_path(str(path), directory=True)
    try:
        return _manifest_fd(fd)
    finally:
        os.close(fd)


def _clone_fd(source_fd, parent_fd, destination):
    require(sys.platform == 'darwin', 'clonefile unavailable')
    libc = ctypes.CDLL(None, use_errno=True)
    try:
        clone = libc.fclonefileat
    except AttributeError as exc:
        raise GateError('clonefile unavailable') from exc
    clone.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    clone.restype = ctypes.c_int
    if clone(source_fd, parent_fd, os.fsencode(destination), 0) != 0:
        raise GateError('clonefile failed; no copy fallback')


def clone_snapshot(data_dir, nonce):
    """Create an exclusive CoW sibling and return its manifest digest.

    Failures never fall back to copying. A failed verification leaves any clone
    in place for inspection; this function never modifies the source.
    """
    path = Path(data_dir)
    require(path.is_absolute() and path.name == 'data' and re.fullmatch(r'[0-9a-f]{32,128}', nonce),
            'invalid snapshot target')
    parent_fd = _open_path(str(path.parent), directory=True)
    source_fd = None
    try:
        source_fd = os.open('data', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
        source = os.fstat(source_fd)
        parent = os.fstat(parent_fd)
        require(source.st_dev == parent.st_dev and source.st_uid == os.getuid(),
                'unsafe snapshot source')
        require(parent.st_uid == os.getuid() and not (parent.st_mode & 0o022)
                and not (source.st_mode & 0o022), 'unsafe snapshot directory permissions')
        before = _manifest_fd(source_fd)
        name = 'data.pre-' + nonce
        _clone_fd(source_fd, parent_fd, name)
        copy_fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
        try:
            require(os.fstat(copy_fd).st_dev == source.st_dev, 'snapshot crossed volume')
            cloned = _manifest_fd(copy_fd)
            require(before == cloned == _manifest_fd(source_fd), 'snapshot content changed')
            current = os.stat('data', dir_fd=parent_fd, follow_symlinks=False)
            require((current.st_dev, current.st_ino) == (source.st_dev, source.st_ino),
                    'snapshot source replaced')
            return cloned
        finally:
            os.close(copy_fd)
    finally:
        if source_fd is not None:
            os.close(source_fd)
        os.close(parent_fd)
