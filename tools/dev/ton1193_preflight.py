"""Staged snapshot preflight for the unfinished production adapter.

No service control or command-line entry point is exposed. This partial check
does not authorize a switch: launchd and process gates remain mandatory. The private fixture
helper accepts roots only for isolated tests; the production entry fixes them.
The pin is protected by the same uid/private-directory trust boundary as stage,
not against an attacker able to rewrite all files as the service owner.
"""
import json
import os
from pathlib import Path
import plistlib
import pwd
import re
import stat
import warnings

from . import ton1175_verify as verify
from . import ton1193_snapshots as snapshots
from .ton1175_switch import GateError, digest, require, tree_hash
from .ton1193_transaction import _read_file, load_bound_state
from .ton1193_trust import _components, _open_path, directory_snapshot, capability

PLIST_KEYS = frozenset(('EnvironmentVariables', 'ExitTimeOut', 'KeepAlive', 'Label',
                        'ProgramArguments', 'RunAtLoad', 'StandardErrorPath',
                        'StandardOutPath', 'ThrottleInterval', 'Umask', 'WorkingDirectory'))
ENV_PINS = frozenset(('BRIDGE_LOG_STDOUT', 'BRIDGE_PORT', 'COACH_MANAGE_SERVICES',
                      'COACH_PORT', 'COACH_TAIL_HOST', 'PATH'))
ENV_KEYS = ENV_PINS | frozenset(('COACH_BRIDGE_HOME', 'COACH_DATA_DIR', 'COACH_LOG_DIR',
                                 'COACH_MATHSVC', 'COACH_NODE', 'COACH_PYTHON', 'COACH_RUN_DIR',
                                 'COACH_SERVICE_ROOT', 'HOME', 'JAVA_HOME'))
PLIST_PINS = {'ExitTimeOut': int, 'KeepAlive': bool, 'RunAtLoad': bool,
              'StandardErrorPath': str, 'StandardOutPath': str, 'ThrottleInterval': int,
              'Umask': int}


def _whitelist(plist, config, roots):
    require(isinstance(plist, dict) and set(plist) == PLIST_KEYS, 'plist keys differ from whitelist')
    env = plist['EnvironmentVariables']
    require(isinstance(env, dict) and set(env) == ENV_KEYS
            and all(isinstance(value, str) for value in env.values()), 'environment keys or types differ from whitelist')
    pins = config['adapterEnvPins']
    require(isinstance(pins, dict) and set(pins) == ENV_PINS
            and all(isinstance(value, str) for value in pins.values())
            and all(env[key] == pins[key] for key in ENV_PINS), 'environment pins differ')
    plist_pins = config['adapterPlistPins']
    require(isinstance(plist_pins, dict) and set(plist_pins) == set(PLIST_PINS), 'plist pins differ')
    require(plist['Label'] == 'local.coach.server', 'production label differs')
    for key, kind in PLIST_PINS.items():
        require(type(plist[key]) is kind and type(plist_pins[key]) is kind
                and plist[key] == plist_pins[key], 'plist pins differ')
    log_root = Path(roots['home']) / 'Coach/logs'
    for key in ('StandardOutPath', 'StandardErrorPath'):
        path = plist[key]
        _components(path)
        require(Path(path).parent == log_root, 'log path outside fixed root')
        fd = _open_path(path)
        try:
            node = os.fstat(fd)
            require(stat.S_ISREG(node.st_mode) and node.st_uid == os.getuid()
                    and not node.st_mode & 0o022, 'unsafe log file')
        finally:
            os.close(fd)
    group_writable = False
    for path in env['PATH'].split(':'):
        fd = _open_path(path, directory=True)
        try:
            node = os.fstat(fd)
            require(stat.S_ISDIR(node.st_mode) and node.st_uid in (0, os.getuid())
                    and not node.st_mode & 0o002, 'unsafe PATH directory')
            group_writable |= bool(node.st_mode & 0o020)
        finally:
            os.close(fd)
    if group_writable:
        warnings.warn('PATH includes a group-writable directory', stacklevel=2)


def _paths(home):
    return {'home': str(home), 'releases': str(home / 'Coach/service/releases'),
            'cellar': '/opt/homebrew/Cellar', 'node': str(home / '.nvm/versions/node')}


def _stage(config, roots):
    state_dir = config['stateDir']
    state_id = directory_snapshot(state_dir)
    state, state_hash = load_bound_state(config, state_id)
    require(state.get('version') == 2, 'stage version lacks acceptance contract')
    materials = {}
    for name in ('old.plist', 'new.plist', 'old.json', 'new.json'):
        data, _ = _read_file(Path(state_dir) / name, state_id, 0o600)
        require(digest(data) == state['files'][name], 'stage material changed')
        materials[name] = data
    journal, _ = _read_file(Path(state_dir) / 'journal.json', state_id, 0o600)
    require(json.loads(journal)['phase'] in ('staged', 'rolled_back'), 'unsafe preflight phase')
    for key, extension, mode in (('plist', 'plist', 'plistMode'),
                                  ('installation', 'json', 'installationMode')):
        parent_id = directory_snapshot(str(Path(config[key]).parent))
        data, _ = _read_file(config[key], parent_id, state[mode])
        require(digest(data) == state['files']['old.' + extension], 'preflight pointer is not old')
    load_bound_state(config, state_id, state_hash)
    sides = {}
    stable = [roots['home']]
    for name in ('old', 'new'):
        info = json.loads(materials[name + '.json'])
        plist = plistlib.loads(materials[name + '.plist'])
        _whitelist(plist, config, roots)
        env = plist['EnvironmentVariables']
        release = info['root']
        _components(release)
        require(Path(release).parent == Path(roots['releases']), 'release outside fixed root')
        require(plist['ProgramArguments'] == [release + '/app/bin/app']
                and plist['WorkingDirectory'] == release
                and env['COACH_SERVICE_ROOT'] == release
                and env['COACH_PYTHON'] == release + '/tools/bridge/.venv/bin/python'
                and env['COACH_MATHSVC'] == release + '/tools/mathsvc/src/server.mjs',
                'staged release descriptions disagree')
        expected_head = config['expectedSourceHead'] if name == 'old' else config['candidateHead']
        require(info['sourceHead'] == expected_head, 'staged source head differs')
        for key, suffix in (('HOME', ''), ('COACH_DATA_DIR', '/Coach/data'),
                            ('COACH_LOG_DIR', '/Coach/logs'), ('COACH_RUN_DIR', '/Coach/run'),
                            ('COACH_BRIDGE_HOME', '/Coach/bridge')):
            require(env[key] == roots['home'] + suffix, 'staged service path differs')
            stable.append(env[key])
        for path in (release, release + '/app/bin/app', release + '/tools/mathsvc/src/server.mjs'):
            _components(path)
            fd = _open_path(path, directory=path == release)
            os.close(fd)
        verify.release_links(release)
        sides[name] = {'release': release, 'java': env['JAVA_HOME'] + '/bin/java',
                       'node': env['COACH_NODE']}
    require(sides['new']['release'] == config['release'] and sides['old']['release'] != config['release'],
            'staged release does not match configuration')
    return state, state_hash, state_id, sides, stable


def _exclusive_file(directory, name, data):
    fd = _open_path(directory, directory=True)
    try:
        node = os.fstat(fd)
        require(node.st_uid == os.getuid() and stat.S_IMODE(node.st_mode) == 0o700,
                'snapshot directory is not private')
        try:
            out = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                          0o600, dir_fd=fd)
        except OSError as exc:
            raise GateError('snapshot pin already exists or unavailable') from exc
        try:
            os.fchmod(out, 0o600)
            with os.fdopen(out, 'wb') as stream:
                out = -1
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.fsync(fd)
        finally:
            if out >= 0:
                os.close(out)
    finally:
        os.close(fd)


def _preflight(config, roots):
    """Fixture-only seam: roots are always fixed by the production wrapper."""
    require(Path(config['adapterSnapshotDir']).parent == Path(config['stateDir']),
            'private snapshot directory must be inside stage')
    state, state_hash, state_id, sides, stable = _stage(config, roots)
    evidence = capability(Path(__file__).resolve().parents[2], sides['old']['release'],
                          config['expectedSourceHead'])
    require(evidence == {'coverageProtocol': 'v1'}, 'legacy-protocol-absent')
    manifest_path = config['adapterManifest']
    manifest_hash = config['adapterManifestSha256']
    require(re.fullmatch(r'[0-9a-f]{64}', manifest_hash) is not None, 'invalid manifest pin')
    manifest_parent = directory_snapshot(str(Path(manifest_path).parent))
    manifest_bytes, manifest_inode = _read_file(manifest_path, manifest_parent, 0o600)
    require(digest(manifest_bytes) == manifest_hash, 'build evidence changed')
    # The existing verifier deliberately runs the real dependency checks.
    verify.candidate(manifest_path, manifest_hash, sides['new']['release'],
                     config['candidateHead'], str(Path(config['stateDir']) / 'new.plist'))
    require(tree_hash(config['release']) == state['candidateTreeSha256'], 'candidate tree differs from stage')
    private_dir = config['adapterSnapshotDir']
    private_id, snapshot_hash = snapshots.capture(config, private_dir, stable, sides,
                                                   {'cellar': roots['cellar'], 'node': roots['node']})
    snap_bytes, _ = _read_file(Path(private_dir) / 'snapshot.json', private_id, 0o600)
    require(digest(snap_bytes) == snapshot_hash, 'snapshot changed during capture')
    snap = json.loads(snap_bytes)
    require(_read_file(manifest_path, manifest_parent, 0o600) == (manifest_bytes, manifest_inode),
            'build evidence changed during preflight')
    proof = json.loads(manifest_bytes)
    new = snap['sides']['new']
    require(new['java'][0] == proof['java'] and new['node'][0] == proof['node'],
            'candidate toolchain path differs from manifest')
    for item in (new['python'][2], new['java'], new['node']):
        require(proof['externalExecutables'].get(item[0]) == item[3],
                'candidate toolchain hash differs from manifest')
    require(tree_hash(config['release']) == proof['treeSha256'] == state['candidateTreeSha256'],
            'candidate tree changed during preflight')
    again, again_hash, _, again_sides, again_stable = _stage(config, roots)
    require((again, again_hash, again_sides, again_stable) == (state, state_hash, sides, stable),
            'stage changed during preflight')
    pin = {'version': 1, 'stateHash': state_hash, 'snapshotHash': snapshot_hash,
           'privateId': private_id, 'manifestHash': manifest_hash}
    _exclusive_file(private_dir, 'pin.json', json.dumps(pin, sort_keys=True).encode())
    return snapshot_hash


def _production_roots(config):
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    require(config['plist'] == str(home / 'Library/LaunchAgents/local.coach.server.plist')
            and config['installation'] == str(home / 'Coach/service/installation.json'),
            'production control paths are fixed')
    return _paths(home)


def preflight(config):
    """Capture private evidence; does not launch, stop, or configure a service."""
    return _preflight(config, _production_roots(config))


def _revalidate(config, action, roots):
    """Fixture-only seam; production always supplies its fixed roots."""
    private_dir = config['adapterSnapshotDir']
    require(Path(private_dir).parent == Path(config['stateDir']), 'private snapshot directory outside stage')
    private_id = directory_snapshot(private_dir)
    data, _ = _read_file(Path(private_dir) / 'pin.json', private_id, 0o600)
    try:
        pin = json.loads(data)
        require(pin['version'] == 1 and tuple(pin['privateId']) == private_id,
                'snapshot pin invalid')
        state_id = directory_snapshot(config['stateDir'])
        load_bound_state(config, state_id, pin['stateHash'])
        require(pin['manifestHash'] == config['adapterManifestSha256'], 'manifest pin changed')
        snapshot, _ = _read_file(Path(private_dir) / 'snapshot.json', private_id, 0o600)
        require(digest(snapshot) == pin['snapshotHash'], 'private snapshot changed')
        snap = json.loads(snapshot)
        require(snap['roots'] == {'cellar': roots['cellar'], 'node': roots['node']}
                and all(Path(side['release']).parent == Path(roots['releases'])
                        for side in snap['specs'].values()), 'snapshot trusted roots differ')
        return snapshots.revalidate(config, private_dir, private_id, pin['snapshotHash'], action)
    except (KeyError, TypeError, ValueError) as exc:
        raise GateError('invalid private snapshot pin') from exc


def revalidate(config, action):
    """Load the durable private pin and enforce the journal's expected side."""
    return _revalidate(config, action, _production_roots(config))


def _check_started(config, before, roots, pid, binding):
    """Bind startup to the pinned journal side and the pre-launch pointers.

    `binding` must independently validate launchd ownership and process images;
    its arguments are the PID, the already pinned side, and the fixed roots.
    This is only a gate; it does not start or stop a service.
    """
    current = _revalidate(config, 'start', roots)
    require(current == before, 'transaction or pointer identity changed during startup')
    private_dir = config['adapterSnapshotDir']
    private_id = directory_snapshot(private_dir)
    pin_bytes, pin_inode = _read_file(Path(private_dir) / 'pin.json', private_id, 0o600)
    try:
        pin = json.loads(pin_bytes)
        snapshot_bytes, snapshot_inode = _read_file(Path(private_dir) / 'snapshot.json', private_id, 0o600)
        require(digest(snapshot_bytes) == pin['snapshotHash'], 'private snapshot changed')
        snapshot = json.loads(snapshot_bytes)
        require(snapshot['roots'] == {'cellar': roots['cellar'], 'node': roots['node']},
                'snapshot trusted roots differ')
        expected = current[0]
        require(expected in ('old', 'new'), 'unknown journal side')
        result = binding(pid, snapshot['sides'][expected], snapshot['roots'])
        require(_revalidate(config, 'start', roots) == before,
                'transaction or pointer identity changed during process binding')
        require(_read_file(Path(private_dir) / 'pin.json', private_id, 0o600) == (pin_bytes, pin_inode)
                and _read_file(Path(private_dir) / 'snapshot.json', private_id, 0o600)
                == (snapshot_bytes, snapshot_inode), 'private snapshot changed during process binding')
        return result
    except (KeyError, TypeError, ValueError) as exc:
        raise GateError('invalid startup snapshot') from exc
