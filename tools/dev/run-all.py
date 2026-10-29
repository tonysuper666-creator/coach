#!/usr/bin/env python3
"""M0 foreground owner for Ktor, the Coach bridge copy and JSONL mathsvc."""
import json
import os
import select
import socket
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[2]
run_dir = Path(os.environ.get('COACH_RUN_DIR', Path.home()/'Coach/run'))
log_dir = Path(os.environ.get('COACH_LOG_DIR', Path.home()/'Coach/logs'))
manifest = run_dir/'run-all.json'

def start_time(pid):
    return subprocess.check_output(['ps', '-p', str(pid), '-o', 'lstart='], text=True).strip()

if sys.argv[1:] == ['stop']:
    record = json.loads(manifest.read_text())
    if start_time(record['pid']) != record['started']:
        raise SystemExit('Recorded supervisor PID has been reused; refusing to signal it')
    os.kill(record['pid'], signal.SIGTERM)
    raise SystemExit(0)
if sys.argv[1:]:
    raise SystemExit('usage: tools/dev/run-all.sh [stop]')
run_dir.mkdir(parents=True, exist_ok=True)
log_dir.mkdir(parents=True, exist_ok=True)
# Exclusive creation prevents overwriting someone else’s cleanup handle.
handle = os.open(manifest, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
children = []
logs = []
record = {'pid': os.getpid(), 'started': start_time(os.getpid()), 'children': []}

def save():
    manifest.write_text(json.dumps(record, indent=2))

def spawn(name, command, **kwargs):
    log = open(log_dir/(name+'.log'), 'a')
    logs.append(log)
    process = subprocess.Popen(['nice', '-n', '15', *map(str, command)], cwd=root, start_new_session=True, stdout=kwargs.pop('stdout', log), stderr=log, **kwargs)
    children.append(process)
    record['children'].append({'name': name, 'pid': process.pid, 'started': start_time(process.pid)})
    save()
    return process

def stop(signum, frame):
    raise KeyboardInterrupt

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
os.close(handle)
save()
try:
    ports = [int(os.environ.get('COACH_PORT','18791')), int(os.environ.get('BRIDGE_PORT','8791'))]
    if len(set(ports)) != 2:
        raise RuntimeError('Server and bridge ports must differ')
    for port in ports:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1',port))
    binary = root/'server/app/build/install/app/bin/app'
    if not binary.is_file():
        raise RuntimeError('Build :server:app:installDist first')
    app = spawn('server', [binary])
    bridge = spawn('bridge', [root/'tools/bridge/run.sh'])
    math = spawn('mathsvc', ['node', root/'tools/mathsvc/src/server.mjs'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    math.stdin.write('{"id":"startup","type":"ping"}\n')
    math.stdin.flush()
    if not select.select([math.stdout], [], [], 15)[0]:
        raise RuntimeError('mathsvc startup timed out')
    pong = json.loads(math.stdout.readline())
    if pong.get('type') != 'pong':
        raise RuntimeError('mathsvc health check failed')
    data = Path(os.environ.get('COACH_DATA_DIR', Path.home()/'Coach/data'))
    token_file = Path(os.environ.get('COACH_TOKEN_FILE', data/'device-token'))
    for _ in range(100):
        if any(p.poll() is not None for p in children):
            raise RuntimeError('A child exited during startup; see logs')
        try:
            token = token_file.read_text().strip()
            request = urllib.request.Request('http://127.0.0.1:'+os.environ.get('COACH_PORT','18791')+'/v1/hello', headers={'Authorization':'Bearer '+token,'X-Coach-Device':os.environ.get('COACH_DEVICE_ID','desktop')})
            with urllib.request.urlopen(request, timeout=1) as response:
                assert json.load(response)['epoch']
            with urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('BRIDGE_PORT','8791')+'/health', timeout=1) as response:
                assert response.status == 200
            break
        except (OSError, ValueError):
            time.sleep(.2)
    else:
        raise RuntimeError('Server/bridge readiness timed out')
    print('Coach ready: server + bridge + mathsvc; cleanup handle: '+str(manifest), flush=True)
    while True:
        if any(p.poll() is not None for p in children):
            raise RuntimeError('A child exited; stopping this group, see logs')
        # Keep the JSONL service observable without sending any AI requests.
        math.stdin.write('{"id":"health","type":"ping"}\n');math.stdin.flush()
        if not select.select([math.stdout], [], [], 5)[0]:
            raise RuntimeError('mathsvc health timeout')
        if json.loads(math.stdout.readline()).get('type') != 'pong':
            raise RuntimeError('mathsvc health failed')
        time.sleep(10)
except KeyboardInterrupt:
    pass
finally:
    for process in reversed(children):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
    for process in children:
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
    for log in logs:
        log.close()
    manifest.unlink(missing_ok=True)
