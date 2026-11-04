"""Read-only production launchd startup identity gate, not a controller.

No service mutation is exposed. The production target and paths are fixed. A
controller must retain pre-start revalidation and call this before readiness.
"""
import json
import os
from pathlib import Path
import re
import subprocess

from . import ton1193_preflight as preflight
from . import ton1193_process as process
from .ton1175_switch import GateError, require


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
    require(identity[0] == 1 and str(Path(side['release']) / 'app/lib') in identity[1]
            and 'com.coach.server.MainKt' in identity[1], 'unexpected launchd process identity')
    evidence = process.bind(pid, side, roots)
    require(evidence['identity'] == identity and _job(plist) == pid
            and _daemon_pid() != pid and process._identity(pid) == identity,
            'launchd process identity changed')
    return evidence


def check_started(config, before):
    """Check the fixed production label after startup; never changes launchd."""
    roots = preflight._production_roots(config)
    pid = _job(config['plist'])
    return preflight._check_started(config, before, roots, pid,
                                   lambda p, side, trusted: _binding(p, side, trusted, config['plist']))
