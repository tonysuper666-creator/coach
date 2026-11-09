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
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--emulator', type=Path, default=Path('/opt/homebrew/share/android-commandlinetools/emulator/emulator'))
    parser.add_argument('--fault-only', action='store_true')
    args = parser.parse_args()
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
            start()
            fault = False
            offline = False
            unavailable_until = 0
            failures = {'load': 0}
            attempts = {}
            diagnostics = []

            class TailProxy(http.server.BaseHTTPRequestHandler):
                protocol_version = 'HTTP/1.1'
                def log_message(self, *ignored):
                    pass
                def forward(self):
                    nonlocal unavailable_until
                    raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                    if time.monotonic() < unavailable_until:
                        self.close_connection = True
                        self.connection.shutdown(socket.SHUT_RDWR)
                        return
                    headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'content-length', 'connection')}
                    if fault and '/mainline/1?' in self.path and failures['load'] == 0:
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
                    diagnostics.append(dict(method=self.command, path=self.path, status=status))
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    try:
                        self.wfile.write(body)
                    except BrokenPipeError:
                        pass
                do_GET = do_POST = forward

            proxy = http.server.ThreadingHTTPServer((host, 0), TailProxy)
            assert proxy.server_address[1] not in FORBIDDEN
            proxy.daemon_threads = True
            proxy_thread = threading.Thread(target=proxy.serve_forever)
            proxy_thread.start()
            samples, runs = [], []

            def instrument(mode, folder):
                folder.mkdir(parents=True, exist_ok=True)
                result = android('shell', 'am', 'instrument', '-w', '-e', 'class', 'com.coach.android.MainlineNotesSmoke',
                    '-e', 'coachPort', proxy.server_address[1], '-e', 'coachToken', token, '-e', 'mode', mode,
                    f'{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner', timeout=300, check=False)
                (folder / f'{mode}-instrument.txt').write_text((result.stdout + result.stderr).decode(errors='replace').replace(token, '<redacted>'))
                (out / 'http-diagnostics.json').write_text(json.dumps(diagnostics, indent=2))
                for name in ('entry', 'two-click-first', 'warm-two-click-first', 'reading-route', 'personal-note', 'dark-reading', 'focus-reading', 'ai-no-evidence', 'weekly-map', 'reading-emphasis', 'question-emphasis', 'notes', 'filtered', 'setup', 'setup-bottom', 'detail', 'bridge', 'bridge-bottom', 'mastery',
                             'fault-waiting', 'resumed-quiz', 'reading-before-restart', 'resumed-notes'):
                    image = android('exec-out', 'run-as', PACKAGE, 'cat', f'cache/mainline-{name}.png', check=False)
                    if image.stdout.startswith(b'\x89PNG'):
                        (folder / f'{name}.png').write_bytes(image.stdout)
                assert result.returncode == 0 and b'OK (1 test)' in result.stdout, f'{mode} UI failed; see redacted instrumentation report'
                if mode in ('normal', 'fault', 'offline'):
                    timing = json.loads(android('exec-out', 'run-as', PACKAGE, 'cat', 'cache/mainline-timings.json').stdout)
                    (folder / 'timings.json').write_text(json.dumps(timing, indent=2))
                    samples.extend(timing['samples'])
                runs.append(dict(mode=mode, passed=True))
                print('UI ' + mode + ': PASS', flush=True)

            for size in ([] if args.fault_only else ['2448x1848', '1972x1248']):
                android('shell', 'wm', 'size', size)
                android('shell', 'wm', 'density', '240')
                android('shell', 'am', 'force-stop', PACKAGE)
                instrument('normal', out / size)
                android('shell', 'am', 'force-stop', PACKAGE)
                instrument('resumeQuiz', out / size)
                android('shell', 'am', 'force-stop', PACKAGE)
                instrument('resumeNotes', out / size)
            fault = True
            android('shell', 'wm', 'size', '1972x1248')
            android('shell', 'wm', 'density', '240')
            android('shell', 'am', 'force-stop', PACKAGE)
            record = subprocess.Popen([adb, '-s', serial, 'shell', 'screenrecord', '--size', '986x624', '--time-limit', '45',
                                       '/sdcard/ton1394-recovery.mp4'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            instrument('fault', out / 'recovery')
            record.wait(50)
            record = None
            android('pull', '/sdcard/ton1394-recovery.mp4', out / 'recovery/recovery.mp4')
            android('shell', 'rm', '/sdcard/ton1394-recovery.mp4')
            offline = True
            android('shell', 'am', 'force-stop', PACKAGE)
            record = subprocess.Popen([adb, '-s', serial, 'shell', 'screenrecord', '--size', '986x624', '--time-limit', '45',
                                       '/sdcard/ton1394-offline.mp4'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            instrument('offline', out / 'offline')
            record.wait(50)
            record = None
            android('pull', '/sdcard/ton1394-offline.mp4', out / 'offline/offline.mp4')
            android('shell', 'rm', '/sdcard/ton1394-offline.mp4')
            retried = [values for values in attempts.values() if len(values) > 1]
            assert len(retried) == 6
            for values in retried:
                assert len(values) == 2 and values[0] == values[1], 'Replay must preserve wire and stored outcome'
            coverage = request(f'/v1/courses/{fixture.COURSE}/mainline')['weeks'][0]['coverage']
            assert (coverage['answered'], coverage['mastered'], coverage['factsMastered']) == (3, 1, 1), coverage
            assert smoke.Bridge.calls == 0
            normal = sorted(s['ms'] for s in samples if not s['fault'])
            p95 = normal[math.ceil(.95 * len(normal)) - 1] if normal else None
            report = dict(syntheticOnly=True, productionRead=False, package=PACKAGE, runs=runs, samples=samples,
                          transport='Android emulator -> configured Tailscale IPv4 -> isolated HTTP proxy -> candidate',
                          disconnection='Isolated proxy drops all HTTP connections for 3.5s after commit; emulator network settings unchanged',
                          p95ms=p95, normalSamples=len(normal), faults=failures, immutableReplays=len(retried), coverage=coverage,
                          utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
            (out / 'report.json').write_text(json.dumps(report, indent=2))
            print(json.dumps({k: report[k] for k in ('normalSamples', 'p95ms', 'immutableReplays', 'coverage')}), flush=True)
            assert p95 is None or p95 <= 800, 'Tailnet submit-to-first-visible-explanation p95 exceeds 800ms'
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
                android('uninstall', PACKAGE + '.test', check=False)
                android('uninstall', PACKAGE, check=False)
                android('shell', 'wm', 'size', 'reset', check=False)
                android('shell', 'wm', 'density', 'reset', check=False)
                android('emu', 'kill', check=False)
            stop(emulator)


if __name__ == '__main__':
    main()
