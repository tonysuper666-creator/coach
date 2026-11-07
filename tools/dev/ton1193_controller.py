"""Fixed-target production controller; invocation defaults to a read-only dry run.

The switch owns the coverage seal and checks its closed nonce.  This controller
only accepts an independently held transaction lock and the pinned stage.
INCOMPLETE: real isolated launchd switch and recovery remain unverified. Do not
use this draft for a production switch.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from . import ton1193_live as live, ton1193_preflight as preflight, ton1193_started as started, ton1193_receipt as receipt
from .ton1175_switch import GateError, require
from .ton1193_transaction import _read_file
from .ton1193_trust import directory_snapshot


def _target():
    return f'gui/{os.getuid()}/local.coach.server'


def _job(plist):
    result = started._command(['/bin/launchctl', 'print', _target()])
    if result.returncode == 113:
        return None
    require(result.returncode == 0, 'cannot inspect production launchd job')
    paths = re.findall(rb'^\s*path = (.+?)\s*$', result.stdout, re.M)
    pids = re.findall(rb'^\s*pid = ([0-9]+)\s*$', result.stdout, re.M)
    require(len(paths) == 1 and len(pids) <= 1 and os.fsdecode(paths[0]) == plist,
            'launchd label or plist identity changed')
    if not pids:
        return 0
    pid = int(pids[0])
    require(pid > 1, 'unsafe launchd PID')
    return pid


def _launchctl(args):
    result = started._command(['/bin/launchctl'] + args)
    require(result.returncode == 0, 'launchctl command failed')


def _exists(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except OSError as exc:
        raise GateError('cannot verify stopped PID') from exc


def _stopped(config, phase, before, action):
    """Require an absent label and an unreused, transaction-bound stopped PID."""
    require(_job(config['plist']) is None, 'production label occupied')
    proof = receipt.read(config)
    require(not _exists(proof[0]['pid']), 'recorded PID still exists or was reused')
    require(preflight.revalidate(config, action) == before, 'transaction snapshot changed')
    require(receipt.read(config) == proof, 'stop intent changed')
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
    require(_job(config['plist']) is None and not _exists(proof[0]['pid']),
            'stopped job or PID appeared')
    return proof


def _stop(config, apply):
    phase = live.live_transaction(config['stateDir'])
    before = preflight.revalidate(config, 'stop')
    job = _job(config['plist'])
    if job is None:
        require(phase == 'recovering', 'job absent outside recovery')
        _stopped(config, phase, before, 'stop')
        return
    require(job > 1, 'job without PID; recovery requires manual inspection')
    evidence = started.check_stopping(config, before)
    pid = evidence['pid']
    require(_job(config['plist']) == pid, 'launchd PID changed before stop')
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
    require(started.check_stopping(config, before) == evidence, 'stop identity changed')
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
    if not apply:
        return
    receipt.write(config, evidence)
    require(started.check_stopping(config, before) == evidence, 'stop identity changed after intent')
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
    _launchctl(['bootout', _target()])
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if _job(config['plist']) is None and not _exists(pid):
            require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
            return
        time.sleep(.1)
    raise GateError('stopped job or PID remains; manual inspection required')


def _start(config, apply):
    phase = live.live_transaction(config['stateDir'])
    before = preflight.revalidate(config, 'start')
    proof = _stopped(config, phase, before, 'start')
    if not apply:
        return
    receipt.consume(config, proof)
    require(preflight.revalidate(config, 'start') == before, 'start snapshot changed')
    require(_job(config['plist']) is None and not _exists(proof[0]['pid']), 'stopped job or PID appeared')
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
    _launchctl(['bootstrap', f'gui/{os.getuid()}', config['plist']])
    # A loaded label with no PID is not treated as safely absent or ready.
    # Any identity or plist mismatch fails closed and the switch owns recovery.
    deadline = time.monotonic() + 20
    while not _job(config['plist']):
        require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
        require(time.monotonic() < deadline, 'started job PID unavailable')
        time.sleep(.1)
    evidence = started.check_started(config, before)
    require(_job(config['plist']) == evidence['pid'], 'launchd PID changed after start')
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')


def _config(path):
    path = Path(path)
    require(path.is_absolute(), 'absolute controller config required')
    parent = directory_snapshot(path.parent)
    data, _ = _read_file(path, parent, 0o600)
    config = json.loads(data)
    require(isinstance(config, dict), 'invalid controller config')
    preflight._production_roots(config)
    return config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('action', choices=('stop', 'start'))
    args = parser.parse_args()
    try:
        config = _config(args.config)
        (_stop if args.action == 'stop' else _start)(config, args.apply)
        print('controller gates passed' + ('' if args.apply else '; dry-run only'))
        return 0
    except (GateError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        print('controller refused: ' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
