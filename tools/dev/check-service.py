#!/usr/bin/env python3
"""Exercise the installed real Coach LaunchAgent, never 8790 or DeepTutor.

Disrupts only Coach: kill each child, hang mathsvc, kill the JVM, stop/start.
Uses authenticated reads and ping health checks; makes zero AI requests.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.request

BASE = Path.home() / 'Coach/service'
INFO = json.loads((BASE / 'installation.json').read_text())
RUN = Path(INFO['runDir'])
TOKEN = (Path(INFO['dataDir']) / 'device-token').read_text().strip()
RESULTS = []


def states():
    return {name: json.loads((RUN / f'{name}.json').read_text()) for name in ('bridge', 'mathsvc')}


def hello():
    for host in ('127.0.0.1', INFO['tailHost']):
        request = urllib.request.Request(f'http://{host}:{INFO["port"]}/v1/hello',
            headers={'Authorization': f'Bearer {TOKEN}', 'X-Coach-Device': 'desktop'})
        with urllib.request.urlopen(request, timeout=2) as response:
            assert json.load(response)['epoch']


def wait_ready(predicate=lambda _: True, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            current = states()
            assert all(s['healthy'] and s['pid'] > 0 and time.time() * 1000 - s['checkedAt'] < 15000 for s in current.values())
            assert len({s['ownerPid'] for s in current.values()}) == 1
            assert all(INFO['root'] in (identity(s['ownerPid']) or '') for s in current.values())
            assert predicate(current)
            hello()
            return current
        except (OSError, ValueError, AssertionError):
            time.sleep(.1)
    raise RuntimeError('Coach did not recover in time; see dated service logs')


def identity(pid):
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'lstart=,command='], capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else None


def disrupt(name, sig, key='pid'):
    before = wait_ready()
    pid = before[name][key]
    command = identity(pid)
    assert command and INFO['root'] in command, 'Refuse to signal a process outside this installation'
    tracked = {s[k]: identity(s[k]) for s in before.values() for k in ('pid', 'guardPid', 'ownerPid')}
    start = time.monotonic()
    os.kill(pid, sig)
    try:
        after = wait_ready(lambda s: s[name][key] != pid)
        if key == 'pid':
            assert after[name]['ownerPid'] == before[name]['ownerPid'], 'Child failure restarted the JVM'
        else:
            assert all(identity(old) != old_identity for old, old_identity in tracked.items()), 'Orphaned old service process'
        elapsed = round(time.monotonic() - start, 3)
        result = {'service': name if key == 'pid' else 'server', 'signal': signal.Signals(sig).name,
            'recoveredSeconds': elapsed, 'authenticatedLoopbackAndTailnet': True}
        RESULTS.append(result)
        print(json.dumps(result), flush=True)
    finally:
        if sig == signal.SIGSTOP and identity(pid) == command:
            os.kill(pid, signal.SIGCONT)


def main():
    disrupt('bridge', signal.SIGKILL)
    disrupt('mathsvc', signal.SIGKILL)
    disrupt('mathsvc', signal.SIGSTOP)
    disrupt('bridge', signal.SIGKILL, key='ownerPid')
    before = wait_ready()
    tracked = {s[k]: identity(s[k]) for s in before.values() for k in ('pid', 'guardPid', 'ownerPid')}
    subprocess.run(['python3', str(BASE / 'control.py'), 'stop'], check=True)
    try:
        remaining = [pid for pid, previous in tracked.items() if identity(pid) == previous]
        assert not remaining, f'Stop left service processes alive: {remaining}'
        for host in ('127.0.0.1', INFO['tailHost']):
            try:
                urllib.request.urlopen(f'http://{host}:{INFO["port"]}/v1/hello', timeout=1)
            except OSError:
                continue
            raise AssertionError('Listener remained open after stop')
        RESULTS.append({'stopRemovedAllOwnedProcesses': True, 'listenersClosed': True})
    finally:
        start = time.monotonic()
        subprocess.run(['python3', str(BASE / 'control.py'), 'start'], check=True)
        wait_ready()
        RESULTS.append({'startReadySeconds': round(time.monotonic() - start, 3)})
    out = Path('.service-evidence')
    out.mkdir(exist_ok=True)
    (out / 'service.json').write_text(json.dumps(RESULTS, indent=2))
    print('Real LaunchAgent checks passed; ai_calls=0; system sleep and Tailscale restart not exercised', flush=True)


if __name__ == '__main__':
    main()
