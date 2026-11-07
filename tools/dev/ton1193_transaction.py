"""Read-only transaction pointer checks for the unfinished production adapter.

The caller must also verify the stable directories, release/toolchain snapshots,
process identity, and startup binding. This module does not operate launchd.
"""
import hashlib
import json
import os
from pathlib import Path
import stat

from .ton1175_switch import GateError, digest, require
from .ton1193_trust import _open_path, directory_snapshot

MAX_CONTROL_BYTES = 1024 * 1024


def _read_file(path, parent_identity, mode):
    """Open the control file relative to its verified no-follow parent."""
    path = Path(path)
    parent_fd = _open_path(str(path.parent), directory=True)
    file_fd = -1
    try:
        parent = os.fstat(parent_fd)
        require((parent.st_dev, parent.st_ino) == tuple(parent_identity)
                and parent.st_uid == os.getuid() and not parent.st_mode & 0o022,
                'control parent changed or unsafe')
        try:
            file_fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent_fd)
        except OSError as exc:
            raise GateError('control file unavailable or symlink') from exc
        before = os.fstat(file_fd)
        require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid()
                and before.st_nlink == 1 and stat.S_IMODE(before.st_mode) == mode,
                'unsafe control file')
        chunks, size = [], 0
        while True:
            block = os.read(file_fd, min(65536, MAX_CONTROL_BYTES + 1 - size))
            if not block:
                break
            size += len(block)
            require(size <= MAX_CONTROL_BYTES, 'control file too large')
            chunks.append(block)
        after = os.fstat(file_fd)
        require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns,
                 before.st_mode, before.st_uid, before.st_nlink)
                == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns,
                    after.st_mode, after.st_uid, after.st_nlink),
                'control file changed during read')
        require(directory_snapshot(path.parent) == tuple(parent_identity), 'control parent changed')
        return b''.join(chunks), (before.st_dev, before.st_ino)
    finally:
        if file_fd >= 0:
            os.close(file_fd)
        os.close(parent_fd)


def load_bound_state(config, state_identity, state_sha256=None):
    """Apply load_state's config/material hashes using private no-follow reads."""
    root = Path(config['stateDir'])
    require(directory_snapshot(root) == tuple(state_identity), 'state directory changed')
    node = os.stat(root, follow_symlinks=False)
    require(stat.S_IMODE(node.st_mode) == 0o700, 'private state missing')
    data, state_inode = _read_file(root / 'state.json', state_identity, 0o600)
    if state_sha256 is not None:
        require(digest(data) == state_sha256, 'transaction state changed')
    try:
        state = json.loads(data)
        require(state['configHash'] == digest(json.dumps(config, sort_keys=True).encode()),
                'configuration changed')
        for name in ('old.plist', 'new.plist', 'old.json', 'new.json'):
            value, _ = _read_file(root / name, state_identity, 0o600)
            require(digest(value) == state['files'][name], 'recovery material missing or changed')
        again, inode = _read_file(root / 'state.json', state_identity, 0o600)
        require((again, inode) == (data, state_inode), 'transaction state changed during read')
        return state, digest(data)
    except (ValueError, KeyError, TypeError) as exc:
        raise GateError('invalid transaction state') from exc


def check_pointers(config, state, state_identity, parent_identities, action):
    """Check journal phase and both pointer files; return expected side and inodes.

    The returned identities are for a second plist check after launchctl reads it.
    Stop during recovery may observe either known side for each pointer.
    """
    require(action in ('stop', 'start'), 'unknown controller action')
    journal_path = Path(config['stateDir']) / 'journal.json'
    journal, journal_inode = _read_file(journal_path, state_identity, 0o600)
    try:
        phase = json.loads(journal)['phase']
    except (ValueError, KeyError, TypeError) as exc:
        raise GateError('invalid journal') from exc
    require(phase in ('switching', 'recovering'), 'unsafe journal phase')
    sides, identities, contents = [], {}, {}
    for key, extension, mode_key in (('plist', 'plist', 'plistMode'),
                                     ('installation', 'json', 'installationMode')):
        data, identity = _read_file(config[key], parent_identities[key], state[mode_key])
        actual = hashlib.sha256(data).hexdigest()
        old, new = state['files']['old.' + extension], state['files']['new.' + extension]
        require(old != new and actual in (old, new), 'unknown pointer contents')
        sides.append('old' if actual == old else 'new')
        identities[key] = identity
        contents[key] = data
    if phase == 'switching':
        expected = 'old' if action == 'stop' else 'new'
        require(sides == [expected, expected], 'switching pointer mismatch')
    elif action == 'start':
        require(sides == ['old', 'old'], 'recovery pointer mismatch')
        expected = 'old'
    else:
        expected = None
    for key, mode_key in (('plist', 'plistMode'), ('installation', 'installationMode')):
        again = _read_file(config[key], parent_identities[key], state[mode_key])
        require(again == (contents[key], identities[key]), 'pointer changed during check')
    require(_read_file(journal_path, state_identity, 0o600) == (journal, journal_inode),
            'journal changed during check')
    return expected, identities


def check_plist_after_start(config, state, parent_identity, expected, before_identity):
    require(expected in ('old', 'new'), 'unknown expected side')
    data, identity = _read_file(config['plist'], parent_identity, state['plistMode'])
    require(identity == tuple(before_identity) and digest(data) == state['files'][expected + '.plist'],
            'plist changed during startup')
