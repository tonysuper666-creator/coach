"""Private stop intent bound to the already-pinned transaction snapshot.

The controller writes this only after binding the actual launchd process. It
must consume the record before any subsequent bootstrap; a missing or uncertain
record never proves that an unobserved process has stopped.
"""
import json
import os
from pathlib import Path

from .ton1175_switch import digest, require
from .ton1193_preflight import _exclusive_file
from .ton1193_transaction import _read_file
from .ton1193_trust import _open_path, directory_snapshot

NAME = 'stop-intent.json'


def _pin(config):
    private = Path(config['adapterSnapshotDir'])
    require(private.parent == Path(config['stateDir']), 'snapshot directory outside stage')
    identity = directory_snapshot(private)
    data, inode = _read_file(private / 'pin.json', identity, 0o600)
    pin = json.loads(data)
    require(pin['version'] == 1 and tuple(pin['privateId']) == identity, 'snapshot pin changed')
    return private, identity, data, inode


def _value(config, evidence):
    private, identity, pin, inode = _pin(config)
    pid = evidence['pid']
    require(type(pid) is int and pid > 1 and evidence['side'] in ('old', 'new')
            and isinstance(evidence['identity'], (list, tuple)) and len(evidence['identity']) == 2
            and evidence['identity'][0] == 1 and isinstance(evidence['identity'][1], str)
            and isinstance(evidence['image'], str) and evidence['image'].startswith('/'),
            'invalid stopped process identity')
    value = {'version': 1, 'pinHash': digest(pin), 'pid': pid, 'side': evidence['side'],
             'identity': list(evidence['identity']), 'image': evidence['image']}
    require(_read_file(private / 'pin.json', identity, 0o600) == (pin, inode), 'snapshot pin changed')
    return value


def write(config, evidence):
    private, identity, pin, inode = _pin(config)
    value = _value(config, evidence)
    _exclusive_file(str(private), NAME, json.dumps(value, sort_keys=True).encode())
    require(directory_snapshot(private) == identity
            and _read_file(private / 'pin.json', identity, 0o600) == (pin, inode), 'snapshot pin changed')
    require(read(config)[0] == value, 'stop intent changed')


def read(config):
    private, identity, pin, inode = _pin(config)
    data, receipt_inode = _read_file(private / NAME, identity, 0o600)
    value = json.loads(data)
    require(set(value) == {'version', 'pinHash', 'pid', 'side', 'identity', 'image'}
            and value['version'] == 1 and value['pinHash'] == digest(pin), 'stop intent does not match snapshot')
    require(_value(config, value) == value, 'invalid stop intent')
    require(_read_file(private / NAME, identity, 0o600) == (data, receipt_inode)
            and _read_file(private / 'pin.json', identity, 0o600) == (pin, inode), 'stop intent changed')
    return value, receipt_inode


def consume(config, expected):
    """Durably invalidate before bootstrap; an uncertain result forbids start."""
    value, inode = read(config)
    require((value, inode) == expected, 'stop intent changed before start')
    private, identity, pin, pin_inode = _pin(config)
    fd = _open_path(str(private), directory=True)
    try:
        node = os.fstat(fd)
        require((node.st_dev, node.st_ino) == identity, 'snapshot directory changed')
        node = os.stat(NAME, dir_fd=fd, follow_symlinks=False)
        require((node.st_dev, node.st_ino) == inode, 'stop intent replaced')
        os.unlink(NAME, dir_fd=fd)
        os.fsync(fd)
    finally:
        os.close(fd)
    require(directory_snapshot(private) == identity
            and _read_file(private / 'pin.json', identity, 0o600) == (pin, pin_inode), 'snapshot pin changed')
