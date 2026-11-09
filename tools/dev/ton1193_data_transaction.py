"""Journal-bound V6 snapshot and V7 quarantine for the TON-1193 adapter.

Only the transaction lock holder calls mutations. The immutable snapshot
descriptor is bound to the preflight pin; journal checkpoints make either
rename replayable without accepting an arbitrary replacement directory.
Committed switches require forward repair. Retained data is never deleted.
"""
import ctypes
import fcntl
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import sqlite3
import stat
import subprocess
import sys

from . import ton1193_data_snapshot as clone, ton1193_receipt as receipt
from . import ton1193_external_state as external
from . import ton1193_usage_schema as usage_schema
from .ton1175_switch import atomic, digest, require, GateError
from .ton1193_live import _live_transaction
from .ton1193_transaction import _read_file, load_bound_state
from .ton1193_trust import _open_path, directory_snapshot

NAME = 'data-snapshot.json'
STATES = {'captured', 'quarantining', 'quarantined', 'restoring', 'restored', 'committed'}


def _json(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def _context(c):
    private = Path(c['adapterSnapshotDir'])
    require(private.parent == Path(c['stateDir']), 'data snapshot outside stage')
    identity = directory_snapshot(private)
    raw, _ = _read_file(private / 'pin.json', identity, 0o600)
    pin = json.loads(raw)
    require(pin['version'] == 1 and tuple(pin['privateId']) == identity, 'snapshot pin changed')
    state_id = directory_snapshot(c['stateDir'])
    load_bound_state(c, state_id, pin['stateHash'])
    raw_snapshot, _ = _read_file(private / 'snapshot.json', identity, 0o600)
    require(digest(raw_snapshot) == pin['snapshotHash'], 'private snapshot changed')
    snap = json.loads(raw_snapshot)
    require(snap['stateHash'] == pin['stateHash'], 'snapshot state changed')
    old, _ = _read_file(Path(c['stateDir']) / 'old.plist', state_id, 0o600)
    data = Path(plistlib.loads(old)['EnvironmentVariables']['COACH_DATA_DIR'])
    require(data.name == 'data' and str(data) in snap['stable'], 'data path not pinned')
    return private, identity, digest(raw), snap, data


def _journal(c):
    raw, _ = _read_file(Path(c['stateDir']) / 'journal.json', directory_snapshot(c['stateDir']), 0o600)
    return json.loads(raw)


def load(c):
    private, identity, pin_hash, snap, data = _context(c)
    journal = _journal(c)
    raw, _ = _read_file(private / NAME, identity, 0o600)
    require(digest(raw) == journal['dataSnapshotHash'], 'data snapshot descriptor changed')
    value = json.loads(raw)
    require(value['version'] == 2 and value['pinHash'] == pin_hash
            and value['dataPath'] == str(data) and value['dataId'] == snap['stable'][str(data)]
            and re.fullmatch(r'[0-9a-f]{32}', value['nonce'])
            and journal['dataState'] in STATES, 'invalid data snapshot binding')
    require(directory_snapshot(data.parent) == tuple(value['parentId']), 'data parent changed')
    return value, journal


def _checkpoint(c, state, **extra):
    value, journal = load(c)
    require(state in STATES, 'invalid data checkpoint')
    journal.update(dataState=state, **extra)
    atomic(Path(c['stateDir']) / 'journal.json', _json(journal))


def _path(value, kind):
    return Path(value['dataPath']).with_name('data.' + kind + '-' + value['nonce'])


def lock_free(path):
    """Darwin F_GETLK, without acquiring or releasing a Java FileChannel lock."""
    require(sys.platform == 'darwin', 'unsupported kernel lock query')
    class Flock(ctypes.Structure):
        _fields_ = [('start', ctypes.c_int64), ('length', ctypes.c_int64),
                    ('pid', ctypes.c_int32), ('kind', ctypes.c_short), ('whence', ctypes.c_short)]
    require(ctypes.sizeof(Flock) == 24, 'unsupported Darwin flock ABI')
    fd = _open_path(str(Path(path) / 'server.lock'))
    try:
        node = os.fstat(fd)
        require(stat.S_ISREG(node.st_mode) and node.st_nlink == 1 and node.st_uid == os.getuid(),
                'unsafe database lock')
        query = Flock(0, 0, 0, fcntl.F_WRLCK, os.SEEK_SET)
        held = Flock.from_buffer_copy(fcntl.fcntl(fd, fcntl.F_GETLK, bytes(query)))
        require(held.kind == fcntl.F_UNLCK, 'database lock still held')
        current = os.stat(Path(path) / 'server.lock', follow_symlinks=False)
        require((current.st_dev, current.st_ino) == (node.st_dev, node.st_ino), 'database lock changed')
    finally:
        os.close(fd)


def _quiescent(c, paths):
    # The controller's exclusive receipt binds the stopped process to the pin.
    # Do not signal any process here. A reused PID is a safe refusal.
    proof = receipt.read(c)
    try:
        os.kill(proof[0]['pid'], 0)
    except ProcessLookupError:
        pass
    else:
        raise GateError('stopped process still exists')
    _live_transaction(c['stateDir'], ('switching', 'snapshotted', 'recovering'))
    _label_absent(c)
    for path in paths:
        lock_free(path)
    _label_absent(c)
    require(receipt.read(c) == proof, 'stop intent changed')


def _label_absent(c):
    # Read only the label from the already hash-bound old plist. This also
    # works while data is absent between renames, when start preflight refuses.
    _context(c)
    raw, _ = _read_file(Path(c['stateDir']) / 'old.plist', directory_snapshot(c['stateDir']), 0o600)
    label = plistlib.loads(raw)['Label']
    require(isinstance(label, str) and re.fullmatch(r'[A-Za-z0-9_.-]+', label), 'invalid pinned label')
    result = subprocess.run(['/bin/launchctl', 'print', f'gui/{os.getuid()}/{label}'],
                            stdin=subprocess.DEVNULL, capture_output=True, timeout=10)
    require(result.returncode == 113, 'launchd label still present')


def counts(path, *, lower_bound=False):
    """Stopped, immutable SQLite view; reject uncheckpointed WAL instead of losing it."""
    path = Path(path)
    wal = path / 'coach.sqlite-wal'
    require(lower_bound or not wal.exists() or wal.stat().st_size == 0, 'database WAL not checkpointed')
    fd = _open_path(str(path / 'coach.sqlite'))
    try:
        node = os.fstat(fd)
        require(stat.S_ISREG(node.st_mode) and node.st_nlink == 1 and node.st_uid == os.getuid(),
                'unsafe snapshot database')
        db = sqlite3.connect((path / 'coach.sqlite').as_uri() + '?mode=ro&immutable=1', uri=True)
        try:
            version = db.execute('PRAGMA user_version').fetchone()[0]
            seq = db.execute('SELECT coalesce(max(seq),0) FROM events').fetchone()[0]
            table = db.execute("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='mock_sessions'").fetchone()[0]
            sessions = db.execute('SELECT count(*) FROM mock_sessions').fetchone()[0] if table else 0
            pairing = db.execute('SELECT singleton,code_sha256,expires_at,attempts FROM pairing_code ORDER BY singleton').fetchall()
        finally:
            db.close()
        current = os.stat(path / 'coach.sqlite', follow_symlinks=False)
        require((current.st_dev, current.st_ino) == (node.st_dev, node.st_ino), 'snapshot database changed')
        pairing_hash = digest(_json(pairing))
        return dict(userVersion=version, lastSeq=seq, mockSessions=sessions, pairingHash=pairing_hash)
    finally:
        os.close(fd)


def candidate_observation(path, before):
    wal = Path(path) / 'coach.sqlite-wal'
    pending = wal.exists() and wal.stat().st_size > 0
    try:
        observed = counts(path, lower_bound=True)
    except (GateError, OSError, sqlite3.Error):
        return dict(candidateCounts='WAL_PENDING' if pending else 'CANDIDATE_UNREADABLE',
                    candidateLowerBounds=None)
    if pending:
        return dict(candidateCounts='WAL_PENDING', candidateLowerBounds=observed)
    if observed['lastSeq'] < before['lastSeq']:
        return dict(candidateCounts='CANDIDATE_REGRESSED', candidateLowerBounds=observed)
    return dict(candidateCounts=observed)


def capture(c, seal):
    private, identity, pin_hash, snap, data = _context(c)
    require(_journal(c)['phase'] == 'switching' and seal, 'sealed switching phase required')
    require(not os.path.lexists(private / NAME), 'data snapshot already exists; use new stage')
    require(directory_snapshot(data) == tuple(snap['stable'][str(data)]), 'stable directory changed')
    _quiescent(c, [data])
    before = counts(data)
    require(before['userVersion'] == 6, 'snapshot requires schema V6')
    nonce = secrets.token_hex(16)
    manifest = clone.clone_snapshot(data, nonce)
    saved = data.with_name('data.pre-' + nonce)
    require(counts(saved) == before, 'snapshot ledger differs')
    _quiescent(c, [data, saved])
    value = dict(version=2, pinHash=pin_hash, nonce=nonce, sealHash=digest(seal.encode()),
                 dataPath=str(data), dataId=directory_snapshot(data), parentId=directory_snapshot(data.parent),
                 snapshotId=directory_snapshot(saved), manifest=manifest, before=before,
                 outsideState=external.inventory(data, usage_schema.bound(c)))
    # Exclusive descriptor then journal publication. A crash before publication
    # never authorizes an unbound clone; it requires inspection and a new stage.
    from .ton1193_preflight import _exclusive_file
    raw = _json(value)
    _exclusive_file(str(private), NAME, raw)
    journal = _journal(c)
    journal.update(phase='snapshotted', dataSnapshotHash=digest(raw), dataState='captured',
                   externalChecks={'capture': {'passed': True}})
    atomic(Path(c['stateDir']) / 'journal.json', _json(journal))


def stable_paths(c, stable):
    """Return expected inode map, changing only the journal-bound restored data."""
    journal = _journal(c)
    if 'dataSnapshotHash' not in journal:
        return stable
    value, journal = load(c)
    require(stable[value['dataPath']] == value['dataId'], 'original data identity differs')
    if journal['dataState'] == 'restored':
        require(journal['phase'] in ('recovering', 'rolled_back', 'recovery_required'),
                'restored data outside recovery')
        return dict(stable, **{value['dataPath']: value['snapshotId']})
    require(journal['dataState'] in ('captured', 'committed'), 'data restoration incomplete')
    return stable


def restoring(c):
    journal = _journal(c)
    return journal.get('dataState') in ('quarantining', 'quarantined', 'restoring')


def _readonly(path):
    for directory, dirs, files in os.walk(path):
        for name in files:
            os.chmod(Path(directory) / name, 0o400, follow_symlinks=False)
        os.chmod(directory, 0o500, follow_symlinks=False)


def _rename(value, source, target):
    """Same-parent, no-follow rename, with durable intent already in journal."""
    parent = Path(value['dataPath']).parent
    fd = _open_path(str(parent), directory=True)
    try:
        node = os.fstat(fd)
        require((node.st_dev, node.st_ino) == tuple(value['parentId']), 'data parent changed')
        require(not os.path.lexists(parent / target), 'data rename target occupied')
        os.rename(source, target, src_dir_fd=fd, dst_dir_fd=fd)
        os.fsync(fd)
    finally:
        os.close(fd)


def restore(c):
    value, journal = load(c)
    require(journal['phase'] == 'recovering' and journal['dataState'] != 'committed',
            'committed data requires forward repair')
    if journal['dataState'] == 'restored':
        require(directory_snapshot(value['dataPath']) == tuple(value['snapshotId']), 'restored data changed')
        require(clone.fingerprint(_path(value, 'candidate')) == journal['candidateManifest'],
                'quarantine manifest changed')
        return
    data, saved, candidate = Path(value['dataPath']), _path(value, 'pre'), _path(value, 'candidate')
    # Filesystem layout, never a phase alone, determines whether a rename
    # happened before the crash. Only the two captured inodes are admissible.
    active = os.path.lexists(data)
    quarantined = os.path.lexists(candidate)
    saved_exists = os.path.lexists(saved)
    require(saved_exists or (active and quarantined and journal['dataState'] == 'restoring'),
            'snapshot missing outside restore checkpoint')
    if saved_exists:
        require(directory_snapshot(saved) == tuple(value['snapshotId'])
                and clone.fingerprint(saved) == value['manifest'], 'snapshot manifest changed')
    if quarantined:
        require(journal['dataState'] in ('quarantining', 'quarantined', 'restoring')
                and directory_snapshot(candidate) == tuple(value['dataId']), 'unknown quarantine identity')
    else:
        require(active and saved_exists and journal['dataState'] in ('captured', 'quarantining'),
                'invalid data restore layout')
        require(directory_snapshot(data) == tuple(value['dataId']), 'candidate directory changed')
    paths = ([data] if active else []) + ([saved] if saved_exists else []) + ([candidate] if quarantined else [])
    _quiescent(c, paths)
    outside = external.inventory(data, usage_schema.bound(c))
    delta = external.compare(value['outsideState'], outside)
    checks = dict(journal['externalChecks'], restore=dict(passed=True, budgetDelta=delta,
                  usageDelta=external.compare_usage(value['outsideState'], outside)))
    _checkpoint(c, journal['dataState'], externalChecks=checks)
    if not quarantined:
        observation = candidate_observation(data, value['before'])
        _checkpoint(c, 'quarantining', candidateManifest=clone.fingerprint(data), **observation)
        _rename(value, data.name, candidate.name)
    value, journal = load(c)
    require(clone.fingerprint(candidate) == journal['candidateManifest'], 'quarantine manifest changed')
    _readonly(candidate)
    _checkpoint(c, 'quarantined') if saved_exists else None
    if saved_exists:
        require(not os.path.lexists(data), 'data path occupied during restore')
        require(clone.fingerprint(saved) == value['manifest'], 'snapshot manifest changed')
        _checkpoint(c, 'restoring')
        _rename(value, saved.name, data.name)
    require(directory_snapshot(data) == tuple(value['snapshotId'])
            and clone.fingerprint(data) == value['manifest'] and counts(data) == value['before'],
            'restored snapshot differs')
    candidate_counts = journal['candidateCounts']
    pairing = 'PAIRING_UNVERIFIED'
    if isinstance(candidate_counts, dict):
        pairing = 'PAIRING_UNCHANGED' if candidate_counts['pairingHash'] == value['before']['pairingHash'] else 'PAIRING_CHANGED'
    observed = candidate_counts if isinstance(candidate_counts, dict) else journal.get('candidateLowerBounds')
    _checkpoint(c, 'restored', restoredCounts=counts(data), pairing=pairing, merge='NOMERGE',
                candidateEventDelta=observed['lastSeq'] - value['before']['lastSeq'] if observed else None)


def commit(c):
    value, journal = load(c)
    require(journal['phase'] == 'validating' and journal['dataState'] == 'captured', 'invalid data commit')
    require(directory_snapshot(value['dataPath']) == tuple(value['dataId'])
            and directory_snapshot(_path(value, 'pre')) == tuple(value['snapshotId'])
            and clone.fingerprint(_path(value, 'pre')) == value['manifest'], 'retained snapshot changed')
    outside = external.inventory(value['dataPath'], usage_schema.bound(c))
    delta = external.compare(value['outsideState'], outside)
    checks = dict(journal['externalChecks'], commit=dict(passed=True, budgetDelta=delta,
                  usageDelta=external.compare_usage(value['outsideState'], outside)))
    _checkpoint(c, 'committed', externalChecks=checks)
