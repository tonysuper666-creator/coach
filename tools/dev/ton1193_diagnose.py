"""Read-only old-side production diagnosis; never a switch preflight.

The CLI has no configurable production paths or trusted-root overrides. It does
not read staged transaction state, run a candidate, or persist a snapshot.
"""
import json
import os
from pathlib import Path
import plistlib
import pwd
import stat
import subprocess
import sys
import warnings

from . import ton1175_verify as verify
from . import ton1193_preflight as preflight
from . import ton1193_snapshots as snapshots
from .ton1175_switch import GateError, require, tree_hash
from .ton1193_transaction import _read_file
from .ton1193_trust import _components, _open_path, capability, directory_snapshot


def _read_pointer(path):
    path = Path(path)
    parent = directory_snapshot(str(path.parent))
    node = os.stat(path, follow_symlinks=False)
    require(stat.S_ISREG(node.st_mode) and node.st_uid == os.getuid()
            and node.st_nlink == 1 and not node.st_mode & 0o022, 'unsafe diagnostic pointer')
    return _read_file(path, parent, stat.S_IMODE(node.st_mode))


def _diagnose(home, roots):
    """Test helper; only diagnose() supplies roots to the production CLI."""
    home = Path(home)
    plist_path = home / 'Library/LaunchAgents/local.coach.server.plist'
    installation_path = home / 'Coach/service/installation.json'
    plist_bytes, plist_id = _read_pointer(plist_path)
    install_bytes, install_id = _read_pointer(installation_path)
    plist = plistlib.loads(plist_bytes)
    info = json.loads(install_bytes)
    require(isinstance(info, dict) and isinstance(info.get('root'), str), 'invalid installation')
    release = info['root']
    _components(release)
    require(Path(release).parent == Path(roots['releases']), 'path-escape')
    require(type(info.get('port')) is int and isinstance(info.get('tailHost'), str),
            'invalid installation network settings')
    require(isinstance(plist, dict) and isinstance(plist.get('EnvironmentVariables'), dict),
            'invalid plist')
    env = plist['EnvironmentVariables']
    require(isinstance(env.get('COACH_NODE'), str), 'invalid node path')
    _components(env['COACH_NODE'])
    # These values are fixed by the checked-in installer, or independently
    # obtained from installation.json. Never copy PATH or flags from the plist.
    path = ':'.join(dict.fromkeys([str(Path(env['COACH_NODE']).parent), str(home / '.local/bin'),
                                  '/opt/homebrew/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin']))
    pins = {'BRIDGE_LOG_STDOUT': '1', 'BRIDGE_PORT': '8791', 'COACH_MANAGE_SERVICES': '1',
            'COACH_PORT': str(info['port']), 'COACH_TAIL_HOST': info['tailHost'], 'PATH': path}
    log = str(home / 'Coach/logs/bootstrap.log')
    config = {'adapterEnvPins': pins,
              'adapterPlistPins': {'ExitTimeOut': 20, 'KeepAlive': True, 'RunAtLoad': True,
                                   'StandardErrorPath': log, 'StandardOutPath': log,
                                   'ThrottleInterval': 5, 'Umask': 0o077}}
    with warnings.catch_warnings(record=True) as reported:
        warnings.simplefilter('always')
        preflight._whitelist(plist, config, roots)
    group_writable_path = bool(reported)
    require(plist['ProgramArguments'] == [release + '/app/bin/app']
            and plist['WorkingDirectory'] == release and env['COACH_SERVICE_ROOT'] == release
            and env['COACH_PYTHON'] == release + '/tools/bridge/.venv/bin/python'
            and env['COACH_MATHSVC'] == release + '/tools/mathsvc/src/server.mjs',
            'release descriptions disagree')
    for key, suffix in (('HOME', ''), ('COACH_DATA_DIR', '/Coach/data'),
                        ('COACH_LOG_DIR', '/Coach/logs'), ('COACH_RUN_DIR', '/Coach/run'),
                        ('COACH_BRIDGE_HOME', '/Coach/bridge')):
        require(env[key] == str(home) + suffix, 'service path differs')
        directory_snapshot(env[key])
    require(info.get('dataDir') == env['COACH_DATA_DIR'] and info.get('runDir') == env['COACH_RUN_DIR'],
            'installation service paths differ')
    directory_snapshot(release)
    for path in (release + '/app/bin/app', release + '/tools/mathsvc/src/server.mjs'):
        fd = _open_path(path)
        try:
            node = os.fstat(fd)
            require(stat.S_ISREG(node.st_mode) and node.st_uid == os.getuid()
                    and not node.st_mode & 0o022, 'unsafe release file')
        finally:
            os.close(fd)
    verify.release_links(release)
    tree = tree_hash(release)
    spec = {'release': release, 'java': env['JAVA_HOME'] + '/bin/java', 'node': env['COACH_NODE']}
    toolchains = snapshots._side(spec, {'cellar': roots['cellar'], 'node': roots['node']})
    evidence = capability(Path(__file__).resolve().parents[2], release, info.get('sourceHead'))
    require(tree_hash(release) == tree and snapshots._side(spec, {'cellar': roots['cellar'],
            'node': roots['node']}) == toolchains, 'diagnostic paths changed')
    require(_read_pointer(plist_path) == (plist_bytes, plist_id)
            and _read_pointer(installation_path) == (install_bytes, install_id), 'diagnostic pointers changed')
    return dict(evidence, pathGroupWritable=group_writable_path)


def diagnose():
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    return _diagnose(home, preflight._paths(home))


def main():
    if sys.argv[1:] != ['diagnose']:
        print('usage: python3 -m tools.dev.ton1193_diagnose diagnose', file=sys.stderr)
        return 64
    try:
        evidence = diagnose()
        result = {'scope': 'old-side-only; not switch preflight', 'plistKeys': 11,
                  'environmentKeys': 16, 'coverageProtocol': evidence['coverageProtocol'],
                  'pathGroupWritable': evidence['pathGroupWritable']}
        if evidence['coverageProtocol'] == 'absent':
            result['status'] = 'legacy-protocol-absent'
            print(json.dumps(result))
            return 1
        require(evidence['coverageProtocol'] == 'v1', 'unknown coverage protocol')
        result['status'] = 'old-side-diagnosis-only'
        print(json.dumps(result))
        return 0
    except GateError as exc:
        print('diagnose refused: ' + str(exc), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('diagnose refused: configuration unavailable or invalid', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
