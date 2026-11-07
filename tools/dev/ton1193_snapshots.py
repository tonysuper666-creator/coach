"""Private transaction snapshots for a future adapter; this is not a controller.

Callers must validate both side descriptions, trusted roots, and the candidate
manifest before capture, and pin the returned digest independently. No CLI or
production defaults are provided by this module.
"""
import json
import os
from pathlib import Path
import stat

from .ton1175_switch import GateError, digest, require
from .ton1193_transaction import _read_file, check_pointers, load_bound_state
from .ton1193_trust import _open_path, directory_snapshot, file_snapshot, python_leaf_snapshot


def _side(spec, roots):
    release = spec['release']
    python = release + '/tools/bridge/.venv/bin/python'
    parent_fd = _open_path(str(Path(python).parent), directory=True)
    try:
        link = os.readlink('python', dir_fd=parent_fd)
    finally:
        os.close(parent_fd)
    python_info = python_leaf_snapshot(release, python, link, roots['cellar'])
    target = Path(python_info[2][0])
    stub = target.parent.parent / 'Resources/Python.app/Contents/MacOS/Python'
    stub_info = file_snapshot(stub, roots['cellar']) if os.path.lexists(stub) else None
    return {'release': release, 'python': python_info,
            'java': file_snapshot(spec['java'], roots['cellar']),
            'node': file_snapshot(spec['node'], roots['node']), 'stub': stub_info}


def _stable(paths):
    return {path: directory_snapshot(path) for path in sorted(set(paths))}


def _json(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def capture(config, private_dir, stable_paths, sides, roots):
    """Persist two snapshots exclusively; return directory identity and digest.

    The caller must retain both return values in trusted transaction context.
    Preflight must separately bind the supplied descriptors to staged plists,
    enforce fixed trusted roots and run the existing candidate verifier.
    """
    require(set(sides) == {'old', 'new'}, 'both release sides required')
    state_id = directory_snapshot(config['stateDir'])
    _, state_hash = load_bound_state(config, state_id)
    private_id = directory_snapshot(private_dir)
    fd = _open_path(private_dir, directory=True)
    try:
        info = os.fstat(fd)
        require((info.st_dev, info.st_ino) == private_id and stat.S_IMODE(info.st_mode) == 0o700,
                'snapshot directory is not private')
        all_paths = list(stable_paths) + [config['stateDir'], private_dir,
                    str(Path(config['plist']).parent), str(Path(config['installation']).parent)]
        for side in sides.values():
            all_paths.extend((side['release'], side['release'] + '/tools/bridge/.venv/bin'))
        stable = _stable(all_paths)
        values = {side: _side(spec, roots) for side, spec in sides.items()}
        require(_stable(all_paths) == stable, 'stable directory changed during capture')
        load_bound_state(config, state_id, state_hash)
        # Round-trip normalizes tuples to lists for exact comparisons after reload.
        data = _json({'version': 1, 'stateHash': state_hash, 'stateId': state_id,
                      'stable': stable, 'sides': values, 'specs': sides, 'roots': roots})
        try:
            output = os.open('snapshot.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=fd)
        except OSError as exc:
            raise GateError('snapshot already exists or cannot be created safely') from exc
        try:
            os.fchmod(output, 0o600)
            with os.fdopen(output, 'wb') as stream:
                output = -1
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.fsync(fd)
        finally:
            if output >= 0:
                os.close(output)
        require(directory_snapshot(private_dir) == private_id, 'snapshot directory changed')
        return private_id, digest(data)
    finally:
        os.close(fd)


def revalidate(config, private_dir, private_id, snapshot_hash, action):
    """Recheck pinned snapshot, stage, journal, pointers and live toolchains.

    Stop checks both sides; start checks the side selected by the journal. The
    caller still needs launchd, process-image and post-start plist checks.
    """
    directory = _open_path(private_dir, directory=True)
    try:
        info = os.fstat(directory)
        require((info.st_dev, info.st_ino) == tuple(private_id)
                and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700,
                'snapshot directory changed or is not private')
    finally:
        os.close(directory)
    data, _ = _read_file(Path(private_dir) / 'snapshot.json', private_id, 0o600)
    require(digest(data) == snapshot_hash, 'private snapshot changed')
    try:
        snap = json.loads(data)
        require(snap['version'] == 1, 'unknown snapshot version')
        state, _ = load_bound_state(config, snap['stateId'], snap['stateHash'])
        stable = snap['stable']
        if 'adapterSnapshotDir' in config:
            from .ton1193_data_transaction import stable_paths
            stable = stable_paths(config, stable)
        require(_json(_stable(stable)) == _json(stable), 'stable directory changed')
        parents = {key: snap['stable'][str(Path(config[key]).parent)] for key in ('plist', 'installation')}
        expected, inodes = check_pointers(config, state, snap['stateId'], parents, action)
        for side in ('old', 'new') if action == 'stop' else (expected,):
            require(_json(_side(snap['specs'][side], snap['roots'])) == _json(snap['sides'][side]),
                    side + ' toolchain changed')
        require(_json(_stable(stable)) == _json(stable), 'stable directory changed')
        if 'adapterSnapshotDir' in config:
            require(stable_paths(config, snap['stable']) == stable, 'data checkpoint changed')
        require(check_pointers(config, state, snap['stateId'], parents, action) == (expected, inodes),
                'transaction changed during revalidation')
        return expected, inodes
    except (KeyError, TypeError, ValueError, OSError) as exc:
        raise GateError('invalid snapshot or unavailable toolchain') from exc
