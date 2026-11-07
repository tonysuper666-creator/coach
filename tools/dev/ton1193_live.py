"""Transaction-lock evidence for the unfinished production controller.

This is one gate, not permission to control launchd. The supervisor and
controller must run in separate processes from the transaction lock holder.
"""
import errno
import fcntl
import json
import os
from pathlib import Path
import stat

from .ton1175_switch import GateError, require
from .ton1193_transaction import _read_file
from .ton1193_trust import _open_path, directory_snapshot


def _contended(fd):
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as exc:
        if exc.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
            return
        raise GateError('transaction lock check failed') from exc
    fcntl.flock(fd, fcntl.LOCK_UN)
    raise GateError('transaction lock not held')


def _live_transaction(state_dir, phases):
    """Require a private journal in an allowed phase and a contended lock.

    Lock and journal are checked again before returning. The caller must repeat
    this immediately before service control; this observation does not transfer
    the lock or the coverage seal to the caller.
    """
    root = Path(state_dir)
    identity = directory_snapshot(root)
    parent = _open_path(str(root), directory=True)
    fd = -1
    try:
        directory = os.fstat(parent)
        require((directory.st_dev, directory.st_ino) == identity
                and stat.S_IMODE(directory.st_mode) == 0o700, 'unsafe transaction directory')
        try:
            fd = os.open('lock', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        except OSError as exc:
            raise GateError('transaction lock unavailable or symlink') from exc
        node = os.fstat(fd)
        require(stat.S_ISREG(node.st_mode) and node.st_uid == os.getuid()
                and node.st_nlink == 1 and stat.S_IMODE(node.st_mode) == 0o600,
                'unsafe transaction lock')
        journal, journal_id = _read_file(root / 'journal.json', identity, 0o600)
        try:
            phase = json.loads(journal)['phase']
        except (TypeError, ValueError, KeyError) as exc:
            raise GateError('invalid journal') from exc
        require(phase in phases, 'unsafe journal phase')
        _contended(fd)
        current = os.stat('lock', dir_fd=parent, follow_symlinks=False)
        require((node.st_dev, node.st_ino) == (current.st_dev, current.st_ino)
                and current.st_nlink == 1 and current.st_uid == os.getuid()
                and stat.S_IMODE(current.st_mode) == 0o600, 'transaction lock changed')
        require(_read_file(root / 'journal.json', identity, 0o600) == (journal, journal_id),
                'journal changed during lock check')
        require(directory_snapshot(root) == identity, 'transaction directory changed')
        _contended(fd)
        return phase
    finally:
        if fd >= 0:
            os.close(fd)
        os.close(parent)


def live_transaction(state_dir):
    """Service control is allowed only during switching or recovering."""
    return _live_transaction(state_dir, ('switching', 'recovering'))


def live_acceptance(state_dir):
    """Learning is allowed only after startup, while the switch holds its lock."""
    return _live_transaction(state_dir, ('validating',))
