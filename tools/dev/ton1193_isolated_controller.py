"""Run the draft controller against a disposable launchd fixture, never production.

This is a test-process adapter: only the private roots and random label are
substituted. Transaction, snapshot, capability, process and launchd checks use
the real implementation. It does not create a fixture or prove a switch.
"""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import plistlib
import pwd
import re
import stat
import sys
from unittest.mock import patch

from . import ton1193_controller as controller, ton1193_preflight as preflight
from . import ton1193_started as started, ton1193_live as live
from .ton1175_launchd import FORBIDDEN_PORTS
from .ton1175_switch import GateError, require
from .ton1193_transaction import _read_file
from .ton1193_trust import directory_snapshot


def fixture(path):
    path = Path(path)
    root = path.parent
    # A random root and matching label prevent this helper from targeting an
    # existing launchd job or any production pointer. No caller supplies roots.
    match = re.fullmatch(r'ton1193-([0-9a-f]{32})', root.name)
    require(path.is_absolute() and path.name == 'controller.json' and match
            and root.parent == Path('/tmp').resolve(), 'invalid isolated fixture path')
    identity = directory_snapshot(str(root))
    require(stat.S_IMODE(root.stat().st_mode) == 0o700, 'fixture root must be private')
    data, _ = _read_file(path, identity, 0o600)
    config = json.loads(data)
    require(isinstance(config, dict), 'invalid fixture config')
    home = root / 'home'
    roots = preflight._paths(home)
    # Toolchain roots remain the same trusted host locations as production.
    roots['node'] = str(Path(pwd.getpwuid(os.getuid()).pw_dir) / '.nvm/versions/node')
    expected = {'plist': home / 'Library/LaunchAgents/service.plist',
                'installation': home / 'Coach/service/installation.json',
                'stateDir': root / 'stage', 'adapterSnapshotDir': root / 'stage/snapshot',
                'adapterManifest': root / 'manifest.json'}
    for key, value in expected.items():
        require(config[key] == str(value), 'fixture path mismatch: ' + key)
    require(Path(config['release']).parent == Path(roots['releases']), 'fixture release outside root')
    pins = config['adapterEnvPins']
    require(pins['COACH_TAIL_HOST'] in ('', '127.0.0.1')
            and pins['COACH_MANAGE_SERVICES'] == '1', 'unsafe fixture environment')
    ports = [int(pins['COACH_PORT']), int(pins['BRIDGE_PORT'])]
    require(len(set(ports)) == 2 and all(1024 <= p <= 65535 and p not in FORBIDDEN_PORTS
                                         for p in ports), 'unsafe fixture ports')
    label = 'local.coach.ton1193.test.' + match.group(1)
    return config, roots, label


def run(path, action, apply=False):
    config, roots, label = fixture(path)
    target = f'gui/{os.getuid()}/{label}'
    original_whitelist = preflight._whitelist
    original_launchctl = controller._launchctl

    def whitelist(plist, c, r):
        require(plist.get('Label') == label, 'fixture label mismatch')
        # Only the label is substituted for the fixed-label whitelist; all
        # other values and all transaction bytes remain the real fixture data.
        original_whitelist(dict(plist, Label='local.coach.server'), c, r)

    def job(plist):
        pid = controller._job(plist)
        require(pid is not None and pid > 1, 'isolated job has no PID')
        return pid

    def checked_launchctl(argv):
        allowed = (['bootout', target], ['bootstrap', f'gui/{os.getuid()}', config['plist']])
        require(argv in allowed, 'isolated launchctl target mismatch')
        if argv[0] == 'bootstrap':
            parent = directory_snapshot(str(Path(config['plist']).parent))
            data, _ = _read_file(config['plist'], parent, 0o600)
            require(plistlib.loads(data)['Label'] == label, 'fixture plist label changed')
        original_launchctl(argv)

    def check_started(c, before):
        phase = live.live_transaction(c['stateDir'])
        pid = job(c['plist'])
        result = preflight._check_started(c, before, roots, pid,
            lambda p, side, trusted: started._binding(p, side, trusted, c['plist']))
        require(live.live_transaction(c['stateDir']) == phase, 'transaction phase changed')
        return result

    def check_stopping(c, before):
        phase = live.live_transaction(c['stateDir'])
        pid = job(c['plist'])
        result = preflight._check_stopping(c, before, roots, pid,
            lambda p, sides, trusted, expected: started._stopping_gate(p, sides, trusted, expected, c))
        require(live.live_transaction(c['stateDir']) == phase, 'transaction phase changed')
        return result

    with ExitStack() as stack:
        stack.enter_context(patch.object(preflight, '_whitelist', whitelist))
        stack.enter_context(patch.object(controller, '_target', lambda: target))
        stack.enter_context(patch.object(controller, '_launchctl', checked_launchctl))
        stack.enter_context(patch.object(preflight, 'revalidate',
                                         lambda c, a: preflight._revalidate(c, a, roots)))
        stack.enter_context(patch.object(started, '_job', job))
        stack.enter_context(patch.object(started, 'check_started', check_started))
        stack.enter_context(patch.object(started, 'check_stopping', check_stopping))
        if action == 'preflight':
            require(apply, 'preflight writes isolated snapshot; --apply required')
            return preflight._preflight(config, roots)
        return (controller._stop if action == 'stop' else controller._start)(config, apply)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('action', choices=('preflight', 'stop', 'start'))
    args = parser.parse_args()
    try:
        result = run(args.config, args.action, args.apply)
        print(json.dumps({'action': args.action, 'applied': args.apply, 'result': result}))
        return 0
    except (GateError, OSError, ValueError, KeyError, TypeError) as exc:
        print('isolated controller refused: ' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
