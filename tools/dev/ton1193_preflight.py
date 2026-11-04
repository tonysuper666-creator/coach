"""Staged snapshot preflight for the unfinished production adapter.

No service control or command-line entry point is exposed. This partial check
does not authorize a switch: capability, whitelist and process gates remain
mandatory. The private fixture
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

from . import ton1175_verify as verify
from . import ton1193_snapshots as snapshots
from .ton1175_switch import GateError, digest, require, tree_hash
from .ton1193_transaction import _read_file, load_bound_state
from .ton1193_trust import _components, _open_path, directory_snapshot


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
