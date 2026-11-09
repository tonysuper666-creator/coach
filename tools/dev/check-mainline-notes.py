#!/usr/bin/env python3
"""TON-1394 synthetic-only acceptance. Run via tools/dev/heavy --emulator.

Starts/owns exactly one coach_1128_c70_r emulator and an empty temporary server.
Uses the configured tailnet address (never adb reverse / localhost timings).
No production database, credentials, sources or subscription CLI are read.
Build installDist and isolated ton1394 APKs first; all owned processes are reaped.
"""
import argparse
import datetime
import http.server
import importlib.util
import json
import math
import os
from pathlib import Path
import shutil
import sqlite3
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.coach.android.ton1394'
AVD = 'coach_1128_c70_r'
FORBIDDEN = {8790, 8791, 18791, 3790}


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


fixture = module('mainline_fixture', ROOT / 'tools/dev/check-mainline-copy.py')
smoke = fixture.smoke


SUBMIT_P95_BUDGET_MS = 800
FAILURE_ARTIFACTS = (
    'mainline-explanation-failure.png',
    'mainline-explanation-failure-tree.txt',
    'mainline-explanation-failure-polls.json',
    'mainline-wait-fail.png',
    'mainline-wait-fail-tree.txt',
)


def p95_observation(p95):
    """Submit-latency budget is an observation in the report, never a functional failure (TON-1394)."""
    return dict(budgetMs=SUBMIT_P95_BUDGET_MS, withinBudget=None if p95 is None else p95 <= SUBMIT_P95_BUDGET_MS)


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--package', choices=[PACKAGE, 'com.coach.android.ton1419'], default=PACKAGE)
    parser.add_argument('--joint-reader', action='store_true')
    parser.add_argument('--probe-url', help='One authenticated HTTP probe; any HTTP failure exits nonzero')
    parser.add_argument('--emulator', type=Path, default=Path('/opt/homebrew/share/android-commandlinetools/emulator/emulator'))
    parser.add_argument('--fault-only', action='store_true')
    # A full pass outlives one foreground shell on this machine, so the same script can be run in two
    # segments over one head. Filtering only chooses which already-defined modes run; nothing is relaxed.
    parser.add_argument('--sizes', default='2448x1848,1972x1248')
    parser.add_argument('--modes', default='normal,resumeQuiz,resumeNotes,fault,offline')
    args = parser.parse_args()
    if args.probe_url:
        req = urllib.request.Request(args.probe_url, headers={'Authorization': 'Bearer ' + os.environ['COACH_SYNTHETIC_PROBE_TOKEN'], 'X-Coach-Device': 'desktop'})
        with urllib.request.urlopen(req, timeout=5) as response:
            assert response.status == 200
        return
    assert args.output, '--output is required for emulator acceptance'
    package = args.package
    sizes = [] if args.fault_only else [s for s in args.sizes.split(',') if s]
    modes = {m for m in args.modes.split(',') if m}
    partial = modes != {'normal', 'resumeQuiz', 'resumeNotes', 'fault', 'offline'} or len(sizes) < 2
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    os.umask(0o077)
    adb = shutil.which('adb') or str(Path.home() / 'android-sdk/platform-tools/adb')
    devices = subprocess.check_output([adb, 'devices'], text=True)
    assert not any(line.startswith('emulator-') for line in devices.splitlines()), 'Emulator lane must be empty'
    host = next(line.partition('=')[2].strip() for line in (Path.home() / 'Coach/config/local.properties').read_text().splitlines()
                if line.startswith('coach.tailHost='))
    assert host and host != '127.0.0.1'
    emulator = process = proxy = proxy_thread = bridge = bridge_thread = record = None
    serial = 'emulator-5580'

    def android(*cmd, check=True, timeout=60):
        try:
            result = subprocess.run([adb, '-s', serial, *map(str, cmd)], capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            # Instrumentation arguments contain the disposable token; never echo the full command.
            raise TimeoutError('adb operation timed out: ' + str(cmd[:2])) from None
        if check:
            assert result.returncode == 0, 'adb failed: ' + str(cmd[:3])
        return result

    try:
        with (out / 'emulator.log').open('wb') as log:
            emulator = subprocess.Popen([str(args.emulator), '-avd', AVD, '-port', '5580', '-no-window', '-no-audio',
                '-no-snapshot-save', '-no-boot-anim', '-gpu', 'swiftshader_indirect'], stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            assert emulator.poll() is None, 'Emulator exited'
            if android('shell', 'getprop', 'sys.boot_completed', check=False).stdout.strip() == b'1':
                break
            time.sleep(1)
        else:
            raise TimeoutError('Emulator boot')
        assert android('emu', 'avd', 'name').stdout.decode().splitlines()[0].strip() == AVD
        for key in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
            android('shell', 'settings', 'put', 'global', key, '0')
        android('shell', 'input', 'keyevent', '82')
        for apk in ('androidApp/build/outputs/apk/debug/androidApp-debug.apk',
                    'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk'):
            android('install', '-r', ROOT / apk)
        bridge = http.server.ThreadingHTTPServer(('127.0.0.1', 0), smoke.Bridge)
        assert bridge.server_address[1] not in FORBIDDEN
        bridge_thread = threading.Thread(target=bridge.serve_forever)
        bridge_thread.start()
        with tempfile.TemporaryDirectory(prefix='coach-ton1394-synthetic-') as temporary:
            base = Path(temporary)
            data = base / 'data'
            fixture.write_pack(base / 'mock/mcq-mainline')
            if args.joint_reader:
                module('joint_reader_fixture', ROOT / 'tools/dev/mainline-reader-fixture.py').write_library(base / 'library', fixture.COURSE)
                pack_file = base / 'mock/mcq-mainline/W01.json'
                pack = json.loads(pack_file.read_text())
                for item in pack['sections'][0]['items']:
                    item['source'] = '教材 p.1'
                    item['anchor'] = 'Synthetic pears grow in the orchard.'
                    item['explanation'] = item['explanation'].replace('Synthetic lecture 1, slide 4', '教材 p.1')
                pack_file.write_text(json.dumps(pack))
            notes = dict(schema_version=1, week=1, sections=[
                dict(section_id='W01-basics', title='Synthetic basics', body='Synthetic apples are sold out. Synthetic pears grow in the orchard.',
                     fact_ids=['F1', 'F2'], sources=['Synthetic lecture 1']),
                dict(section_id='W01-bridge', title='Synthetic bridge notes', body='**Synthetic pears** connect the orchard to the next topic.',
                     fact_ids=['F3'], sources=['Synthetic lecture 1'])])
            (base / 'mock/mcq-mainline/W01-notes.json').write_text(json.dumps(notes))
            emphasis = dict(schema_version=1, week=1, marks=[dict(fact_id='F1', recording_kind='lecture',
                recording_id='synthetic-L1', timestamp='98:00', summary='Synthetic pears were emphasized.', reviewed=True)])
            (base / 'mock/mcq-mainline/W01-emphasis.json').write_text(json.dumps(emphasis))
            port = smoke.free_port()
            assert port not in FORBIDDEN
            env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
            env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'), COACH_LOG_DIR=str(base / 'logs'),
                COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'), COACH_PACKS_DIR=str(base / 'packs'),
                COACH_MOCK_DIR=str(base / 'mock'), COACH_GENERATED_DIR=str(base / 'generated'), COACH_TAIL_HOST='', COACH_PORT=str(port),
                COACH_TRAFFIC='test', COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge.server_address[1]}')
            if args.joint_reader:
                env['COACH_READER_LIBRARY'] = str(base / 'library')
            token = ''

            def request(path):
                req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', headers={'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop'})
                with urllib.request.urlopen(req, timeout=5) as r:
                    return json.load(r)

            def start():
                nonlocal process, token
                with (base / 'server.log').open('ab') as log:
                    process = subprocess.Popen([str(smoke.BINARY)], env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    assert process.poll() is None, 'Synthetic candidate exited'
                    try:
                        token = (data / 'device-token').read_text().strip()
                        request('/v1/hello')
                        return
                    except OSError:
                        time.sleep(.2)
                raise TimeoutError('Candidate boot')

            start()
            stop(process)
            process = None
            now = int(time.time() * 1000)
            payload = {'course': {'id': fixture.COURSE, 'name': fixture.CODE + ' Synthetic orchard', 'tz': 'Europe/London'},
                       'knowledgePoints': [{'id': f'ton1358-kp-{k}', 'title': f'Synthetic orchard point {k}', 'week': 1} for k in 'abc']}
            event = dict(seq=1, id='ton1394-outline', courseId=fixture.COURSE, type='outline_revised', schema=1,
                         recordedAt=now, occurredAt=now, deviceId='desktop', actor='SYSTEM', effectIndex=0, idemKey='ton1394-outline', payload=payload)
            with sqlite3.connect(data / 'coach.sqlite') as db:
                assert db.execute('select count(*) from events').fetchone()[0] == 0
                db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                    (1, event['id'], fixture.COURSE, 'outline_revised', 1, now, now, 'desktop', 'SYSTEM', 0, event['idemKey'], json.dumps(payload), json.dumps(event)))
            # Each size must begin with unanswered synthetic questions, while its restart modes
            # share the resulting ledger. The first size's answers otherwise leak into the next.
            baseline = base / 'baseline-data'
            shutil.copytree(data, baseline)
            start()
            fault = False
            offline = False
            unavailable_until = 0
            failures = {'load': 0}
            negative_http = False
            attempts = {}
            diagnostics = []

            class TailProxy(http.server.BaseHTTPRequestHandler):
                protocol_version = 'HTTP/1.1'
                def log_message(self, *ignored):
                    pass
                def forward(self):
                    nonlocal unavailable_until
                    started = time.monotonic_ns()
                    received_utc_ms = time.time_ns() / 1_000_000
                    raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                    if time.monotonic() < unavailable_until:
                        self.close_connection = True
                        self.connection.shutdown(socket.SHUT_RDWR)
                        return
                    headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'content-length', 'connection')}
                    if negative_http and self.path == '/v1/hello':
                        status, body = 503, b'{"error":"Synthetic post-start HTTP failure"}'
                    elif fault and '/mainline/1?' in self.path and failures['load'] == 0:
                        failures['load'] += 1
                        status, body = 503, b'{"error":"Synthetic temporary outage"}'
                    else:
                        req = urllib.request.Request(f'http://127.0.0.1:{port}{self.path}', data=raw if self.command == 'POST' else None, headers=headers, method=self.command)
                        try:
                            with urllib.request.urlopen(req, timeout=15) as r:
                                status, body = r.status, r.read()
                        except urllib.error.HTTPError as e:
                            status, body = e.code, e.read()
                        if self.path.endswith('/mainline/1/answer') and status == 200:
                            sent = json.loads(raw)
                            history = attempts.setdefault(sent['submissionId'], [])
                            history.append((sent, json.loads(body)))
                            if fault and len(history) == 1:
                                if offline:
                                    unavailable_until = time.monotonic() + 3.5
                                    self.close_connection = True
                                    self.connection.shutdown(socket.SHUT_RDWR)
                                    return
                                status, body = 503, b'{"error":"Synthetic response lost after commit"}'
                    self.send_response(status)
                    diagnostic = dict(method=self.command, path=self.path, status=status,
                                      receivedUtcMs=received_utc_ms, responseBytes=len(body),
                                      tcpNoDelay=self.connection.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY),
                                      upstreamMs=(time.monotonic_ns() - started) / 1_000_000)
                    diagnostics.append(diagnostic)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    try:
                        self.wfile.write(body)
                        diagnostic['sentUtcMs'] = time.time_ns() / 1_000_000
                    except BrokenPipeError:
                        pass
                do_GET = do_POST = forward

            proxy = http.server.ThreadingHTTPServer((host, 0), TailProxy)
            assert proxy.server_address[1] not in FORBIDDEN
            proxy.daemon_threads = True
            proxy_thread = threading.Thread(target=proxy.serve_forever)
            proxy_thread.start()
            samples, runs, clock_brackets = [], [], []

            def record_clock(mode, phase):
                # Bound clock skew for cross-device diagnostics only; the gate uses Android monotonic time.
                before = time.time_ns() / 1_000_000
                device = int(android('shell', 'date', '+%s%3N').stdout.strip())
                after = time.time_ns() / 1_000_000
                clock_brackets.append(dict(mode=mode, phase=phase, hostBeforeMs=before, deviceMs=device,
                                           hostAfterMs=after, offsetLowMs=device - after, offsetHighMs=device - before))
                (out / 'clock-brackets.json').write_text(json.dumps(clock_brackets, indent=2))

            def instrument(mode, folder):
                folder.mkdir(parents=True, exist_ok=True)
                android('logcat', '-c', check=False)
                record_clock(mode, 'before')
                # A previous mode/size's cached image must never masquerade as this run's live failure.
                android('shell', 'run-as', package, 'rm', '-f',
                        *(f'cache/{name}' for name in FAILURE_ARTIFACTS), check=False)
                result = android('shell', 'am', 'instrument', '-w', '-e', 'class', 'com.coach.android.MainlineNotesSmoke',
                    '-e', 'coachPort', proxy.server_address[1], '-e', 'coachToken', token, '-e', 'mode', mode,
                    '-e', 'jointReader', '1' if args.joint_reader else '0',
                    f'{package}.test/androidx.test.runner.AndroidJUnitRunner', timeout=300, check=False)
                record_clock(mode, 'after')
                (folder / f'{mode}-instrument.txt').write_text((result.stdout + result.stderr).decode(errors='replace').replace(token, '<redacted>'))
                # App-side diagnostics for a UI failure: connection state transitions and mainline fetches.
                if result.returncode != 0 or b'OK (1 test)' not in result.stdout:
                    for name in FAILURE_ARTIFACTS:
                        captured = android('exec-out', 'run-as', package, 'cat', f'cache/{name}', check=False)
                        if captured.returncode == 0 and captured.stdout:
                            (folder / f'{mode}-{name}').write_bytes(captured.stdout)
                    pid = android('shell', 'pidof', package, check=False).stdout.decode().strip().split()
                    log = android('logcat', '-d', '-v', 'time', *(['--pid', pid[0]] if pid else []), check=False)
                    (folder / f'{mode}-logcat.txt').write_text(log.stdout.decode(errors='replace').replace(token, '<redacted>'))
                    shot = android('exec-out', 'screencap', '-p', check=False)
                    if shot.stdout.startswith(b'\x89PNG'):
                        (folder / f'{mode}-failure.png').write_bytes(shot.stdout)
                    android('shell', 'uiautomator', 'dump', '/sdcard/ton1394-fail.xml', check=False)
                    dump = android('shell', 'cat', '/sdcard/ton1394-fail.xml', check=False)
                    (folder / f'{mode}-failure-tree.xml').write_bytes(dump.stdout)
                    android('shell', 'rm', '-f', '/sdcard/ton1394-fail.xml', check=False)
                (out / 'http-diagnostics.json').write_text(json.dumps(diagnostics, indent=2))
                for name in ('entry', 'wait-fail', 'click-fail', 'two-click-first', 'warm-two-click-first', 'reading-route', 'personal-note', 'dark-reading', 'focus-reading', 'ai-no-evidence', 'weekly-map', 'weekly-map-open', 'reading-emphasis', 'question-emphasis', 'notes', 'filtered', 'setup', 'setup-bottom', 'detail', 'bridge', 'bridge-bottom', 'mastery',
                             'fault-waiting', 'resumed-quiz', 'reading-before-restart', 'resumed-notes',
                             'joint-highlight', 'joint-note', 'joint-page-two', 'joint-answer-return', 'joint-note-after-restart'):
                    image = android('exec-out', 'run-as', package, 'cat', f'cache/mainline-{name}.png', check=False)
                    if image.stdout.startswith(b'\x89PNG'):
                        (folder / f'{name}.png').write_bytes(image.stdout)
                assert result.returncode == 0 and b'OK (1 test)' in result.stdout, f'{mode} UI failed; see redacted instrumentation report'
                entry = {}
                if mode in ('normal', 'fault', 'offline'):
                    timing = json.loads(android('exec-out', 'run-as', package, 'cat', 'cache/mainline-timings.json').stdout)
                    (folder / 'timings.json').write_text(json.dumps(timing, indent=2))
                    samples.extend(timing['samples'])
                    entry = {key: timing[key] for key in ('initialColdClicks', 'warmClicks', 'notesRouteClicks')}
                runs.append(dict(mode=mode, passed=True, evidence=str(folder.relative_to(out)), **entry))
                print('UI ' + mode + ': PASS', flush=True)

            for index, size in enumerate(sizes):
                if index:
                    android('shell', 'am', 'force-stop', package)
                    stop(process)
                    process = None
                    shutil.rmtree(data)
                    shutil.copytree(baseline, data)
                    start()
                android('shell', 'wm', 'size', size)
                # Match the V7 1224dp inner / 986dp cover landscape geometry at these pixel sizes.
                android('shell', 'wm', 'density', '320')
                if 'normal' in modes:
                    android('shell', 'am', 'force-stop', package)
                    instrument('normal', out / size)
                if 'resumeQuiz' in modes:
                    android('shell', 'am', 'force-stop', package)
                    instrument('resumeQuiz', out / size)
                if 'resumeNotes' in modes:
                    android('shell', 'am', 'force-stop', package)
                    instrument('resumeNotes', out / size)
            fault = True
            android('shell', 'wm', 'size', '1972x1248')
            android('shell', 'wm', 'density', '320')
            if 'fault' in modes:
                android('shell', 'am', 'force-stop', package)
                record = subprocess.Popen([adb, '-s', serial, 'shell', 'screenrecord', '--size', '986x624', '--time-limit', '45',
                                           '/sdcard/ton1394-recovery.mp4'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                instrument('fault', out / 'recovery')
                record.wait(50)
                record = None
                android('pull', '/sdcard/ton1394-recovery.mp4', out / 'recovery/recovery.mp4')
                android('shell', 'rm', '/sdcard/ton1394-recovery.mp4')
            offline = True
            if 'offline' in modes:
                android('shell', 'am', 'force-stop', package)
                record = subprocess.Popen([adb, '-s', serial, 'shell', 'screenrecord', '--size', '986x624', '--time-limit', '45',
                                           '/sdcard/ton1394-offline.mp4'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                instrument('offline', out / 'offline')
                record.wait(50)
                record = None
                android('pull', '/sdcard/ton1394-offline.mp4', out / 'offline/offline.mp4')
                android('shell', 'rm', '/sdcard/ton1394-offline.mp4')
            retried = [values for values in attempts.values() if len(values) > 1]
            # Cross-mode accounting only exists after every mode has run; a filtered segment reports its own modes.
            if not partial:
                assert len(retried) == 6
                for values in retried:
                    assert len(values) == 2 and values[0] == values[1], 'Replay must preserve wire and stored outcome'
                coverage = request(f'/v1/courses/{fixture.COURSE}/mainline')['weeks'][0]['coverage']
                assert (coverage['answered'], coverage['mastered'], coverage['factsMastered']) == (3, 1, 1), coverage
                assert smoke.Bridge.calls == 0
            else:
                coverage = request(f'/v1/courses/{fixture.COURSE}/mainline')['weeks'][0]['coverage']
            normal = sorted(s['ms'] for s in samples if not s['fault'])
            p95 = normal[math.ceil(.95 * len(normal)) - 1] if normal else None
            partitions = ('locateMs', 'dispatchMs', 'awaitVisibleMs', 'verifyMs')
            for sample in samples:
                assert abs(sample['ms'] - sum(sample[key] for key in partitions)) < .000001
            partition_summary = {}
            for key in partitions:
                values = sorted(sample[key] for sample in samples if not sample['fault'])
                if values:
                    partition_summary[key] = dict(meanMs=sum(values) / len(values),
                                                  p95ms=values[math.ceil(.95 * len(values)) - 1])
            report = dict(syntheticOnly=True, productionRead=False, package=package, runs=runs, samples=samples,
                          transport='Android emulator -> configured Tailscale IPv4 -> isolated HTTP proxy -> candidate',
                          disconnection='Isolated proxy drops all HTTP connections for 3.5s after commit; emulator network settings unchanged',
                          p95ms=p95, p95Budget=p95_observation(p95), timingPartitions=partition_summary,
                          normalSamples=len(normal), faults=failures, immutableReplays=len(retried), coverage=coverage,
                          utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
            if args.joint_reader:
                # A fresh process running this very script sees a real post-start HTTP 503 and must fail.
                import sys
                request('/v1/hello')
                negative_http = True
                negative = subprocess.run([sys.executable, __file__, '--probe-url', f'http://{host}:{proxy.server_address[1]}/v1/hello'],
                    env=dict(os.environ, COACH_SYNTHETIC_PROBE_TOKEN=token), capture_output=True, text=True)
                (out / 'negative-http.txt').write_text((negative.stdout + negative.stderr).replace(token, '<redacted>'))
                assert negative.returncode != 0 and '503' in negative.stderr
                report['negativeHttp'] = dict(startedHealthy=True, status=503, scriptExit=negative.returncode)
                negative_http = False
                report['readerState'] = request(f'/v1/courses/{fixture.COURSE}/reader/joint-book/state')
                assert report['readerState']['notes'], 'PDF note must survive the app restarts'
                assert report['readerState']['progress'], 'PDF reading progress must be persisted'
                report['jointReader'] = True
            (out / 'report.json').write_text(json.dumps(report, indent=2))
            print(json.dumps({k: report[k] for k in ('normalSamples', 'p95ms', 'p95Budget', 'immutableReplays', 'coverage')}), flush=True)
            if report['p95Budget']['withinBudget'] is False:
                print('OBSERVATION: submit-to-first-visible-explanation p95 %.3fms exceeds the %dms budget (reported, not a functional failure)' % (p95, SUBMIT_P95_BUDGET_MS), flush=True)
    finally:
        stop(record)
        if proxy:
            proxy.shutdown()
            proxy.server_close()
            proxy_thread.join()
        stop(process)
        if bridge:
            bridge.shutdown()
            bridge.server_close()
            bridge_thread.join()
        if emulator:
            if emulator.poll() is None:
                android('uninstall', package + '.test', check=False)
                android('uninstall', package, check=False)
                android('shell', 'wm', 'size', 'reset', check=False)
                android('shell', 'wm', 'density', 'reset', check=False)
                android('emu', 'kill', check=False)
            stop(emulator)


if __name__ == '__main__':
    main()
