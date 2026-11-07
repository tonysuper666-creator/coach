"""Read-only production launchd startup identity gate, not a controller.

No service mutation is exposed. The production target and paths are fixed. A
controller must retain pre-start revalidation and call this before readiness.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time

from . import ton1193_preflight as preflight
from . import ton1193_process as process
from . import ton1193_live as live
from .ton1175_switch import GateError, require
from .ton1193_trust import capability


def _command(argv):
    try:
        return subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GateError('launchd identity query failed') from exc


def _job(plist):
    target = f'gui/{os.getuid()}/local.coach.server'
    result = _command(['/bin/launchctl', 'print', target])
    require(result.returncode == 0, 'production launchd job unavailable')
    paths = re.findall(rb'^\s*path = (.+?)\s*$', result.stdout, re.M)
    pids = re.findall(rb'^\s*pid = ([0-9]+)\s*$', result.stdout, re.M)
    require(len(paths) == len(pids) == 1 and os.fsdecode(paths[0]) == plist,
            'launchd label or plist identity changed')
    pid = int(pids[0])
    require(pid > 1, 'unsafe launchd PID')
    return pid


def _daemon_pid():
    result = _command(['multica', 'daemon', 'status', '--output', 'json'])
    require(result.returncode == 0, 'Multica daemon identity unavailable')
    try:
        pid = json.loads(result.stdout)['pid']
        require(type(pid) is int and pid > 1, 'invalid Multica daemon identity')
        return pid
    except (KeyError, TypeError, ValueError) as exc:
        raise GateError('invalid Multica daemon identity') from exc


def _binding(pid, side, roots, plist):
    require(_job(plist) == pid and _daemon_pid() != pid, 'launchd or daemon PID changed')
    identity = process._identity(pid)
    # launchd exposes the PID before the release's shell launcher execs Java.
    # Waiting never authorizes the launcher: the normal JVM/image binding below
    # remains mandatory, and a different job, process birth, or command fails.
    launcher = '/bin/sh ' + str(Path(side['release']) / 'app/bin/app')
    fields = identity[1].split(None, 6)
    if identity[0] == 1 and len(fields) == 7 and fields[6] == launcher:
        birth = fields[:6]
        deadline = time.monotonic() + 20
        while True:
            require(time.monotonic() < deadline, 'launcher did not exec JVM')
            require(_job(plist) == pid and _daemon_pid() != pid,
                    'launchd or daemon PID changed')
            fields = identity[1].split(None, 6)
            require(identity[0] == 1 and len(fields) == 7 and fields[:6] == birth,
                    'launchd process identity changed')
            if fields[6] != launcher:
                break
            if process._image(pid) != '/bin/sh':
                time.sleep(0.05)
                identity = process._identity(pid)
                continue
            time.sleep(0.05)
            identity = process._identity(pid)
    require(identity[0] == 1 and str(Path(side['release']) / 'app/lib') + '/' in identity[1]
            and 'com.coach.server.MainKt' in identity[1], 'unexpected launchd process identity')
    evidence = process.bind(pid, side, roots)
    require(evidence['identity'] == identity and _job(plist) == pid
            and _daemon_pid() != pid and process._identity(pid) == identity,
            'launchd process identity changed')
    return evidence


def check_started(config, before):
    """Check the fixed production label after startup; never changes launchd."""
    roots = preflight._production_roots(config)
    phase = live.live_transaction(config['stateDir'])
    pid = _job(config['plist'])
    result = preflight._check_started(config, before, roots, pid,
                                     lambda p, side, trusted: _binding(p, side, trusted, config['plist']))
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
    return result


def _stop_binding(pid, sides, roots, expected, plist):
    identity = process._identity(pid)
    matches = [name for name in ('old', 'new')
               if str(Path(sides[name]['release']) / 'app/lib') + '/' in identity[1]]
    require(len(matches) == 1 and (expected is None or matches[0] == expected),
            'stop process release differs from journal')
    result = _binding(pid, sides[matches[0]], roots, plist)
    require(result['identity'] == identity, 'stop process identity changed')
    return dict(result, side=matches[0])


def _stopping_gate(pid, sides, roots, expected, config):
    result = _stop_binding(pid, sides, roots, expected, config['plist'])
    if result['side'] == 'old':
        evidence = capability(Path(__file__).resolve().parents[2], sides['old']['release'],
                              config['expectedSourceHead'])
        require(evidence == {'coverageProtocol': 'v1'}, 'legacy-protocol-absent')
        require(_stop_binding(pid, sides, roots, expected, config['plist']) == result,
                'stop process changed during capability check')
    return result


def check_stopping(config, before):
    """Read-only stop identity check; a held admission seal remains mandatory."""
    roots = preflight._production_roots(config)
    phase = live.live_transaction(config['stateDir'])
    pid = _job(config['plist'])
    result = preflight._check_stopping(config, before, roots, pid,
                                      lambda p, sides, trusted, expected:
                                      _stopping_gate(p, sides, trusted, expected, config))
    require(live.live_transaction(config['stateDir']) == phase, 'transaction phase changed')
    return result
