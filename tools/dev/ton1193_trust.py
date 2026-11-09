"""Read-only building blocks for the TON-1193 production adapter.

This module is not a service controller or a switch entry point.  The caller
must separately establish the attested release and persist/recheck snapshots at
the transaction boundaries; these helpers do not authorize any operation.
"""
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess
import zipfile

from .ton1175_switch import GateError, require

PROTOCOL_COMMIT = '95b11b8c3fef51d8c8d4f5cd08be6a8468d0ae80'
PROTOCOL_CLASS = 'com/coach/server/ActivityCoverage.class'


def _components(path):
    text = os.fspath(path)
    require(isinstance(text, str) and text.startswith('/') and not text.startswith('//')
            and os.path.normpath(text) == text, 'path-escape')
    return text.split('/')[1:]


def _open_path(path, directory=False):
    """Open every component relative to an already-open, no-follow parent."""
    parts = _components(path)
    require(parts and parts != [''], 'path-escape')
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for index, part in enumerate(parts):
            flags = os.O_RDONLY | os.O_NOFOLLOW
            if index < len(parts) - 1 or directory:
                flags |= os.O_DIRECTORY
            child = os.open(part, flags, dir_fd=fd)
            os.close(fd)
            fd = child
        result, fd = fd, -1
        return result
    except OSError as exc:
        raise GateError('service-path-symlink or inaccessible path') from exc
    finally:
        if fd >= 0:
            os.close(fd)


def directory_snapshot(path):
    fd = _open_path(path, directory=True)
    try:
        node = os.fstat(fd)
        require(stat.S_ISDIR(node.st_mode) and node.st_uid == os.getuid()
                and not node.st_mode & 0o022, 'unsafe directory ownership or permissions')
        return (node.st_dev, node.st_ino)
    finally:
        os.close(fd)


def file_snapshot(path, trusted_root):
    """Bind the resolved, non-symlink regular file and hash one open fd."""
    resolved = Path(path).resolve(strict=True)
    root = Path(trusted_root).resolve(strict=True)
    require(root in resolved.parents, 'toolchain-root')
    fd = _open_path(str(resolved))
    try:
        node = os.fstat(fd)
        require(stat.S_ISREG(node.st_mode), 'toolchain target not a regular file')
        h = hashlib.sha256()
        while True:
            block = os.read(fd, 1024 * 1024)
            if not block:
                break
            h.update(block)
        after = os.fstat(fd)
        require((node.st_dev, node.st_ino, node.st_size, node.st_mtime_ns, node.st_ctime_ns)
                == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns),
                'toolchain file changed while hashing')
        return (str(resolved), node.st_dev, node.st_ino, h.hexdigest())
    finally:
        os.close(fd)


def python_leaf_snapshot(release, value, expected_text, cellar):
    """Snapshot the only allowed venv leaf after the caller verifies tree_hash."""
    release = os.fspath(release)
    _components(release)
    expected = release + '/tools/bridge/.venv/bin/python'
    require(value == expected, 'path-escape')
    parent = os.path.dirname(expected)
    fd = _open_path(parent, directory=True)
    try:
        directory = os.fstat(fd)
        require(directory.st_uid == os.getuid() and not directory.st_mode & 0o022,
                'unsafe directory ownership or permissions')
        before = os.stat('python', dir_fd=fd, follow_symlinks=False)
        require(stat.S_ISLNK(before.st_mode), 'venv python leaf is not a symlink')
        text = os.readlink('python', dir_fd=fd)
        after = os.stat('python', dir_fd=fd, follow_symlinks=False)
        require((before.st_dev, before.st_ino) == (after.st_dev, after.st_ino)
                and text == expected_text, 'venv python link changed')
        target = file_snapshot(expected, cellar)
        final = os.stat('python', dir_fd=fd, follow_symlinks=False)
        require((before.st_dev, before.st_ino) == (final.st_dev, final.st_ino)
                and os.readlink('python', dir_fd=fd) == text, 'venv python link changed')
        return ((directory.st_dev, directory.st_ino),
                (before.st_dev, before.st_ino, text), target)
    finally:
        os.close(fd)


def capability(checkout, release, head):
    """Combine the source ancestry and complete installed JAR class scan."""
    require(isinstance(head, str) and re.fullmatch(r'[0-9a-f]{40}', head), 'invalid installation head')
    result = subprocess.run(['git', '-C', str(checkout), 'merge-base', '--is-ancestor',
                             PROTOCOL_COMMIT, head], stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    require(result.returncode in (0, 1), 'cannot establish installation ancestry')
    jar_dir = Path(release) / 'app/lib'
    require(jar_dir.is_dir() and not jar_dir.is_symlink(), 'installed JAR directory missing')
    jars = sorted(jar_dir.glob('*.jar'))
    require(jars, 'installed JARs missing')
    found = False
    for jar in jars:
        fd = _open_path(str(jar))
        try:
            require(stat.S_ISREG(os.fstat(fd).st_mode), 'invalid installed JAR')
            with os.fdopen(fd, 'rb') as stream:
                fd = -1
                with zipfile.ZipFile(stream) as archive:
                    found |= PROTOCOL_CLASS in archive.namelist()
        except (OSError, zipfile.BadZipFile) as exc:
            raise GateError('unreadable installed JAR') from exc
        finally:
            if fd >= 0:
                os.close(fd)
    require((result.returncode == 0) == found, 'installation protocol evidence inconsistent')
    return {'coverageProtocol': 'v1' if found else 'absent'}
