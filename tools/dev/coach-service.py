#!/usr/bin/env python3
"""Install/control the user LaunchAgent, with a durable copy outside task worktrees."""
import argparse
import datetime
import ipaddress
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import time
import urllib.request

LABEL = 'local.coach.server'
DOMAIN = f'gui/{os.getuid()}'
HOME = Path.home()
BASE = HOME / 'Coach/service'
PLIST = HOME / f'Library/LaunchAgents/{LABEL}.plist'
RECORD = BASE / 'installation.json'


def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def loaded():
    return subprocess.run(['launchctl', 'print', f'{DOMAIN}/{LABEL}'], capture_output=True).returncode == 0


def stop():
    # bootout can return before the JVM and its children have exited. Capture
    # identities first: Ktor removes its child health files before its own exit.
    tracked = {}
    def remember(pid):
        result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'lstart='], capture_output=True, text=True)
        if result.returncode == 0:
            tracked[pid] = result.stdout.strip()
    job = subprocess.run(['launchctl', 'print', f'{DOMAIN}/{LABEL}'], capture_output=True, text=True)
    match = re.search(r'^\s*pid = (\d+)', job.stdout, re.MULTILINE)
    if match:
        remember(int(match.group(1)))
    if RECORD.exists():
        info = json.loads(RECORD.read_text())
        for name in ('bridge', 'mathsvc'):
            try:
                state = json.loads((Path(info['runDir']) / f'{name}.json').read_text())
                for key in ('ownerPid', 'guardPid', 'pid'):
                    if state.get(key):
                        remember(state[key])
            except FileNotFoundError:
                pass
    run('launchctl', 'disable', f'{DOMAIN}/{LABEL}')
    if loaded():
        run('launchctl', 'bootout', f'{DOMAIN}/{LABEL}')
    # JVM shutdown/parent-death guard cleanup is bounded; bootout may return first.
    deadline = time.monotonic() + 30
    while True:
        alive = False
        for pid, started in tracked.items():
            result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'lstart='], capture_output=True, text=True)
            alive |= result.returncode == 0 and result.stdout.strip() == started
        if not alive and not loaded():
            break
        if time.monotonic() >= deadline:
            raise RuntimeError('Stop timed out; inspect service logs. No unrelated processes were signalled.')
        time.sleep(.1)
    print(f'{LABEL}: stopped and disabled until start (data retained)')


def ready(info):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            states = [json.loads((Path(info['runDir']) / f'{name}.json').read_text()) for name in ('bridge', 'mathsvc')]
            assert all(s['healthy'] and time.time() * 1000 - s['checkedAt'] < 15000 for s in states)
            token = (Path(info['dataDir']) / 'device-token').read_text().strip()
            for host in ('127.0.0.1', info['tailHost']):
                request = urllib.request.Request(f'http://{host}:{info["port"]}/v1/hello',
                    headers={'Authorization': f'Bearer {token}', 'X-Coach-Device': 'desktop'})
                with urllib.request.urlopen(request, timeout=2) as response:
                    assert json.load(response)['epoch']
            print(f'{LABEL}: ready; authenticated loopback + tailnet; bridge + mathsvc healthy')
            return
        except (OSError, ValueError, AssertionError):
            time.sleep(.2)
    raise RuntimeError('Not ready within 30s; inspect ~/Coach/logs/server-YYYY-MM-DD.log and bootstrap.log')


def start():
    info = json.loads(RECORD.read_text())
    run('launchctl', 'enable', f'{DOMAIN}/{LABEL}')
    if not loaded():
        run('launchctl', 'bootstrap', DOMAIN, PLIST)
    ready(info)


def install():
    root = Path(__file__).resolve().parents[2]
    binary = root / 'server/app/build/install/app'
    if not (binary / 'bin/app').is_file():
        raise RuntimeError('Build :server:app:installDist before install')
    settings = HOME / 'Coach/config/local.properties'
    config = dict(line.split('=', 1) for line in settings.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))
    tail = os.environ.get('COACH_TAIL_HOST', config.get('coach.tailHost', '')).strip()
    assert ipaddress.ip_address(tail) in ipaddress.ip_network('100.64.0.0/10'), 'Set the actual Tailscale IPv4 in local.properties'
    node = shutil.which('node')
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    assert node and (java / 'bin/java').is_file(), 'Node and JDK 17 are required'
    if (PLIST.exists() or loaded()) and not RECORD.exists():
        raise RuntimeError('An unmanaged local.coach.server already exists; refusing to replace it')
    BASE.mkdir(parents=True, exist_ok=True)
    release = BASE / 'releases' / datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    release.mkdir(parents=True)
    # No checkout, data, credentials, corpus or logs are copied into the service code.
    shutil.copytree(binary, release / 'app', symlinks=True)
    for name in ('bridge', 'mathsvc'):
        shutil.copytree(root / f'tools/{name}', release / f'tools/{name}', symlinks=True,
            ignore=shutil.ignore_patterns('.venv', 'node_modules', '__pycache__'))
    (release / 'tools/dev').mkdir()
    shutil.copy2(root / 'tools/dev/service-child.py', release / 'tools/dev/service-child.py')
    python = release / 'tools/bridge/.venv/bin/python'
    run(sys.executable, '-m', 'venv', python.parent.parent)
    run(python, '-m', 'pip', 'install', '--disable-pip-version-check', '-r', release / 'tools/bridge/requirements.txt')
    run(shutil.which('npm'), 'ci', '--omit=dev', '--prefix', release / 'tools/mathsvc')
    logs = HOME / 'Coach/logs'
    logs.mkdir(parents=True, exist_ok=True)
    info = {'root': str(release), 'dataDir': str(HOME / 'Coach/data'), 'runDir': str(HOME / 'Coach/run'),
        'tailHost': tail, 'port': 18791,
        'sourceHead': subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()}
    environment = {
        'HOME': str(HOME), 'PATH': ':'.join(dict.fromkeys([str(Path(node).parent), str(HOME / '.local/bin'), '/opt/homebrew/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin'])),
        'JAVA_HOME': str(java), 'COACH_MANAGE_SERVICES': '1', 'COACH_SERVICE_ROOT': str(release),
        'COACH_PYTHON': str(python), 'COACH_NODE': node, 'COACH_TAIL_HOST': tail, 'COACH_PORT': str(info['port']),
        'COACH_DATA_DIR': info['dataDir'], 'COACH_RUN_DIR': info['runDir'], 'COACH_LOG_DIR': str(logs),
        'COACH_BRIDGE_HOME': str(HOME / 'Coach/bridge'), 'BRIDGE_PORT': '8791',
        'BRIDGE_LOG_STDOUT': '1',
        'BRIDGE_DAILY_CLAUDE_CAP': '60', 'BRIDGE_RATE_PAUSE_AT': '0.5',
    }
    plist = {'Label': LABEL, 'ProgramArguments': [str(release / 'app/bin/app')], 'WorkingDirectory': str(release),
        'EnvironmentVariables': environment, 'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 5,
        'ExitTimeOut': 20, 'StandardOutPath': str(logs / 'bootstrap.log'), 'StandardErrorPath': str(logs / 'bootstrap.log'), 'Umask': 0o077}
    old_plist = PLIST.read_bytes() if PLIST.exists() else None
    old_record = RECORD.read_bytes() if RECORD.exists() else None
    if loaded():
        stop()
    PLIST.parent.mkdir(parents=True, exist_ok=True)
    PLIST.write_bytes(plistlib.dumps(plist))
    RECORD.write_text(json.dumps(info, indent=2))
    os.chmod(RECORD, 0o600)
    shutil.copy2(__file__, BASE / 'control.py')
    try:
        start()
    except BaseException:
        stop()
        if old_plist and old_record:
            PLIST.write_bytes(old_plist)
            RECORD.write_bytes(old_record)
            start()
            print('Previous installation restored', file=sys.stderr)
        else:
            PLIST.unlink(missing_ok=True)
            RECORD.unlink(missing_ok=True)
        raise
    print('Stop: python3 "$HOME/Coach/service/control.py" stop')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['install', 'start', 'stop', 'status', 'uninstall'])
    command = parser.parse_args().command
    if command == 'install':
        install()
    elif command == 'start':
        start()
    elif command == 'stop':
        stop()
    elif command == 'uninstall':
        stop()
        PLIST.unlink(missing_ok=True)
        print('LaunchAgent removed; data, backups, logs and releases retained')
    else:
        print(f'{LABEL}: {"loaded" if loaded() else "stopped"}')
        if loaded():
            ready(json.loads(RECORD.read_text()))


if __name__ == '__main__':
    main()
