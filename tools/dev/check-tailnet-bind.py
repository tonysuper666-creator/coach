#!/usr/bin/env python3
"""Real Main regression: unavailable tailnet address must not stall loopback auth.

Build :server:app:installDist, then run this script through tools/dev/heavy.
Only installed build jars and an already cached matching Kotlin compiler are used.
The child uses a temporary home/ledger/token and a dynamic non-production port.
No Tailscale operation, managed child service, learner bridge or AI call is needed.
Only allowlisted metrics are retained; child output and response bodies are discarded.
"""
import argparse
import concurrent.futures
import datetime
import errno
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'tools/dev/probes/TailnetBindHeartbeat.kt'
WINDOW_SECONDS = 18
REQUEST_TIMEOUT = 2
TAIL_HOST = '192.0.2.253'  # RFC 5737; must be unassigned on this host.


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(java, output):
    jars = sorted((REPO / 'server/app/build/install/app/lib').glob('*.jar'))
    if not jars:
        raise RuntimeError('Build :server:app:installDist first')
    version = next(re.fullmatch(r'kotlin-stdlib-(.+)\.jar', p.name).group(1)
                   for p in jars if re.fullmatch(r'kotlin-stdlib-(.+)\.jar', p.name))
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    compiler = list(cache.glob(f'org.jetbrains.kotlin/kotlin-compiler-embeddable/{version}/*/*.jar'))
    if len(compiler) != 1:
        raise RuntimeError('Matching Kotlin compiler must already be cached; no downloads')
    compiler += list(cache.glob(f'org.jetbrains.kotlin/kotlin-daemon-embeddable/{version}/*/*.jar'))
    compiler += list(cache.glob('org.jetbrains/annotations/13.0/*/*.jar'))
    compiler += list(cache.glob('org.jetbrains.intellij.deps/trove4j/1.0.20200330/*/*.jar'))
    with socket.socket() as unavailable:
        try:
            unavailable.bind((TAIL_HOST, 0))
        except OSError as error:
            if error.errno != errno.EADDRNOTAVAIL:
                raise
        else:
            raise RuntimeError('Documentation address is assigned; refuse this experiment')
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    if port in (8790, 8791, 18791):
        raise RuntimeError('Refusing reserved port')

    report = {'startedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'windowSeconds': WINDOW_SECONDS, 'requestTimeoutSeconds': REQUEST_TIMEOUT,
              'probeSha256': sha(SOURCE), 'mainSha256': sha(REPO / 'server/app/src/main/kotlin/com/coach/server/Main.kt'),
              'jars': {p.name: sha(p) for p in jars}, 'aiCalls': 0}
    with tempfile.TemporaryDirectory(prefix='coach-tailnet-bind-') as temporary:
        root = Path(temporary)
        classes = root / 'classes'
        classes.mkdir()
        runtime = os.pathsep.join(map(str, jars))
        command = [java, '-Xmx512m', '-cp', os.pathsep.join(map(str, compiler + jars)),
                   'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                   '-jvm-target', '17', '-classpath', runtime, '-d', str(classes), str(SOURCE)]
        compilation = subprocess.run(command, capture_output=True, timeout=90)
        report['compileExit'] = compilation.returncode
        if compilation.returncode:
            output.write_text(json.dumps(report, indent=2) + '\n')
            raise RuntimeError('Heartbeat probe compilation failed; runtime was not started')
        token = secrets.token_hex(32)
        token_file = root / 'token'
        token_file.write_text(token)
        token_file.chmod(0o600)
        env = {k: v for k, v in os.environ.items()
               if not k.startswith('COACH_') and k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
        env.update(COACH_DATA_DIR=str(root / 'data'), COACH_TOKEN_FILE=str(token_file),
                   COACH_DEVICE_ID='bind-regression', COACH_PORT=str(port), COACH_TAIL_HOST=TAIL_HOST,
                   COACH_TRAFFIC='test', COACH_BRIDGE_URL='http://127.0.0.1:1',
                   COACH_MANAGE_SERVICES='0', COACH_SOURCE_LOOKUP='false',
                   COACH_ACTIVITY_SOCKET=str(root / 'activity.sock'))
        command = [java, '-Xmx256m', f'-Duser.home={root}', '-cp',
                   os.pathsep.join([str(classes), runtime]), 'com.coach.probes.TailnetBindHeartbeatKt']
        ready = threading.Event()
        stop = threading.Event()
        heartbeats = []
        failures = []
        started = time.monotonic()
        process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)

        def collect():
            for line in process.stdout:
                if line.startswith('COACH loopback ready http://127.0.0.1:'):
                    ready.set()
                elif line.startswith('TAILNET_HEARTBEAT ') and ready.is_set():
                    value = line.strip().split()[-1]
                    if value.isdecimal():
                        heartbeats.append((time.monotonic(), int(value)))
                elif line.startswith('COACH tailnet unavailable '):
                    # No raw exception messages/paths or other service output are retained.
                    failures.append({'atMs': round((time.monotonic() - started) * 1000),
                                     'bindException': 'BindException' in line})

        collector = threading.Thread(target=collect)
        collector.start()
        samples = []
        try:
            if not ready.wait(15):
                raise RuntimeError('Real Main did not announce loopback readiness')
            begin = time.monotonic()

            def sample(kind):
                while not stop.is_set() and time.monotonic() - begin < WINDOW_SECONDS:
                    at = time.monotonic()
                    row = {'kind': kind, 'atMs': round((at - begin) * 1000)}
                    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=REQUEST_TIMEOUT)
                    try:
                        credential = token if kind == 'authorized' else 'invalid-synthetic-credential'
                        connection.request('GET', '/v1/hello', headers={
                            'X-Coach-Device': 'bind-regression', 'Authorization': f'Bearer {credential}'})
                        response = connection.getresponse()
                        body = response.read()
                        row['status'] = response.status
                        row['bodyCorrect'] = (isinstance(json.loads(body).get('epoch'), str)
                                              if kind == 'authorized' and response.status == 200
                                              else body == b'Unauthorized')
                    except Exception as error:
                        row['exceptionType'] = type(error).__name__
                    finally:
                        connection.close()
                    row['elapsedMs'] = round((time.monotonic() - at) * 1000, 3)
                    samples.append(row)
                    stop.wait(0.02)

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                list(pool.map(sample, ['authorized', 'rejected']))
            measured_end = time.monotonic()
            measured_beats = [(at, nano) for at, nano in heartbeats if begin <= at <= measured_end]
            gaps = [(b[1] - a[1]) / 1_000_000 for a, b in zip(measured_beats, measured_beats[1:])]
            # Include window edges, so a heartbeat that never resumes also fails.
            gaps += [(measured_beats[0][0] - begin) * 1000,
                     (measured_end - measured_beats[-1][0]) * 1000] if measured_beats else [WINDOW_SECONDS * 1000]
            bad = [s for s in samples if s.get('status') != (200 if s['kind'] == 'authorized' else 401)
                   or not s.get('bodyCorrect')]
            report.update(samples=samples, failedRequests=len(bad), requestCount=len(samples),
                          maxResponseMs=max((s['elapsedMs'] for s in samples), default=None),
                          heartbeatCount=len(measured_beats), maxHeartbeatGapMs=round(max(gaps), 3),
                          bindingFailures=failures, serverAliveBeforeCleanup=process.poll() is None)
        finally:
            stop.set()
            forced = False
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    forced = True
                    process.kill()
                    process.wait()
            collector.join(timeout=5)
            process.stdout.close()
            report.update(serverExit=process.returncode, forcedKill=forced)
            with socket.socket() as check:
                check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                try:
                    check.bind(('127.0.0.1', port))
                    report['portReleased'] = True
                except OSError:
                    report['portReleased'] = False
            output.write_text(json.dumps(report, indent=2) + '\n')
    report['temporaryDataRemoved'] = not root.exists()
    passed = (report.get('failedRequests') == 0 and report.get('requestCount', 0) > 0
              and report.get('maxHeartbeatGapMs', float('inf')) < REQUEST_TIMEOUT * 1000
              and sum(f['bindException'] for f in failures) >= 2
              and report.get('serverAliveBeforeCleanup') and report['serverExit'] == 143
              and not report['forcedKill'] and report['portReleased'] and report['temporaryDataRemoved'])
    report['passed'] = bool(passed)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('jars', 'samples')}, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.java, args.output))
