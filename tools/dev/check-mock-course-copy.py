#!/usr/bin/env python3
"""TON-1362 foreground acceptance using the existing installDist entry point.

private-copy: readonly SQLite backup and aggregate private paper counts only.
emulator: fresh synthetic data, owned read-only AVD, screenshots, and package cleanup.
Build first through tools/dev/heavy; run emulator mode under heavy --emulator.
Private data, credentials, server/emulator logs are temporary and never exported.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from local_config import value, tail_host

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / 'server/app/build/install/app/bin/app'
COURSE = 'ton1362-synthetic-course'
PACKAGE = 'com.coach.android.ton1362'
PORT = 19362
SERIAL = 'emulator-5624'


def wait_until(check, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.5)
    raise TimeoutError('Isolated acceptance readiness timed out')


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def seed(database, course, name):
    now = int(time.time() * 1000)
    payload = {'course': {'id': course, 'name': name, 'tz': 'Europe/London'},
               'knowledgePoints': [{'id': 'ton1362-orchard', 'title': 'Synthetic orchard', 'week': 1}]}
    with sqlite3.connect(database) as db:
        seq = db.execute('select coalesce(max(seq),0)+1 from events').fetchone()[0]
        event = dict(seq=seq, id=course+'-outline', courseId=course, type='outline_revised', schema=1,
                     recordedAt=now, occurredAt=now, deviceId='ton1362-isolated', actor='SYSTEM',
                     effectIndex=0, idemKey=course+'-outline', payload=payload)
        db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                   (seq, event['id'], course, event['type'], 1, now, now, event['deviceId'], 'SYSTEM', 0,
                    event['idemKey'], json.dumps(payload), json.dumps(event)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['private-copy', 'emulator'])
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    args.evidence.mkdir(parents=True, exist_ok=True)
    assert BINARY.is_file(), 'Build :server:app:installDist through heavy first'
    server = emulator = None
    adb = None
    installed = []
    with tempfile.TemporaryDirectory(prefix='coach-ton1362-') as temporary:
        base = Path(temporary)
        data = base / 'data'
        data.mkdir()
        token = secrets.token_urlsafe(32)
        (base / 'token').write_text(token)
        private = args.mode == 'private-copy'
        host = '' if private else tail_host()
        mock_dir = Path.home() / 'Coach/materials/generated/ECNM10119' if private else base / 'papers'
        port = PORT
        if private:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
        assert port not in {18791, 8790, 8791, 3790}
        env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
        env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(base / 'token'), COACH_DEVICE_ID='ton1362-isolated',
                   COACH_PORT=str(port), COACH_TAIL_HOST=host, COACH_TRAFFIC='test', COACH_BRIDGE_URL='http://127.0.0.1:1',
                   COACH_LOG_DIR=str(base / 'logs'), COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'),
                   COACH_PACKS_DIR=str(base / 'packs'), COACH_GENERATED_DIR=str(base / 'generated'),
                   COACH_MOCK_DIR=str(mock_dir), COACH_SOURCE_LOOKUP='false')

        def request(course):
            req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/courses/{course}/mocks',
                headers={'Authorization': 'Bearer '+token, 'X-Coach-Device': 'ton1362-isolated'})
            with urllib.request.urlopen(req, timeout=5) as response:
                assert response.status == 200
                return json.load(response)

        def start():
            nonlocal server
            with (base / 'server.log').open('ab') as log:
                server = subprocess.Popen([str(BINARY)], env=env, stdout=log, stderr=subprocess.STDOUT)

            def ready():
                assert server.poll() is None, 'Isolated server exited (private logs withheld)'
                try:
                    request('ton1362-readiness')
                    return True
                except OSError:
                    return False
            wait_until(ready, 45)

        try:
            if private:
                source_path = Path.home() / 'Coach/data/coach.sqlite'
                with sqlite3.connect(source_path.resolve().as_uri()+'?mode=ro', uri=True) as source:
                    with sqlite3.connect(data / 'coach.sqlite') as copy:
                        source.backup(copy)
                with sqlite3.connect(data / 'coach.sqlite') as db:
                    assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
                    db.execute("update jobs set state='cancelled',last_error='ton1362_isolated_copy' where state in ('queued','running')")
                    if db.execute("select 1 from sqlite_master where type='table' and name='mock_sessions'").fetchone():
                        db.execute('delete from mock_sessions')
                    db.execute('update devices set revoked_at=?', (int(time.time()*1000),))
                    names = {}
                    for course, payload in db.execute("select course_id,payload from events where type='outline_revised' order by seq"):
                        name = json.loads(payload).get('course', {}).get('name', '')
                        if name.strip():
                            names[course] = name.strip().split(' ')[0]
                courses = [course for course, code in names.items() if code.casefold() == 'ecnm10119']
                assert len(courses) == 1, 'Expected exactly one matching ledger course'
                counts = {'pack': 0, 'paper': 0}
                for file in mock_dir.glob('**/*.json'):
                    raw = json.loads(file.read_text())
                    if str(raw.get('course', '')).casefold() == 'ecnm10119' and raw.get('kind') in counts:
                        counts[raw['kind']] += 1
                assert counts['paper'] > 0, 'No private paper files found'
                start()
                actual = len(request(courses[0])['papers'])
                other = request('ton1362-other-course')
                assert actual == counts['paper'], 'Aggregate private paper count mismatch'
                assert len(other['papers']) == 0 and other['drill']['total'] == 0, 'Cross-course leak'
                report = dict(source='readonly SQLite backup', files=counts, apiPapers=actual, otherCoursePapers=0,
                              otherCourseDrillItems=0, httpStatus=200, bridge='disabled', port=port)
            else:
                mock_dir.mkdir()
                # Reuse the existing synthetic JVM fixture; no private corpus in this mode.
                test = (ROOT / 'server/app/src/test/kotlin/com/coach/server/MockExamTest.kt').read_text()
                paper = json.loads(re.search(r'private val PAPER = """\n(.*?)\n"""', test, re.S).group(1))
                paper.update(id='ton1362-paper', title='TON-1362 Synthetic whole paper', course='c1')
                (mock_dir / 'paper.json').write_text(json.dumps(paper))
                (mock_dir / 'figs').mkdir()
                (mock_dir / 'figs/x.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>')
                start()
                stop(server)
                seed(data / 'coach.sqlite', COURSE, 'C1 Synthetic orchard')
                start()
                assert len(request(COURSE)['papers']) == 1
                assert len(request('ton1362-other-course')['papers']) == 0
                sdk = Path(value('coach.sdkDir')).expanduser()
                adb = sdk / 'platform-tools/adb'

                def command(*parts, timeout=60):
                    result = subprocess.run([str(adb), '-s', SERIAL, *map(str, parts)], capture_output=True, timeout=timeout)
                    assert result.returncode == 0, 'Isolated emulator command failed (output withheld)'
                    return result.stdout

                for p in [5624, 5625]:
                    with socket.socket() as probe:
                        assert probe.connect_ex(('127.0.0.1', p)) != 0, 'Emulator port already owned'
                with (base / 'emulator.log').open('wb') as log:
                    emulator = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', 'coach_1128_c70_r', '-port', '5624',
                        '-read-only', '-no-snapshot-save', '-no-boot-anim', '-no-audio', '-no-window', '-gpu', 'host', '-cores', '2'],
                        env=dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk)), stdout=log, stderr=subprocess.STDOUT)

                def booted():
                    assert emulator.poll() is None, 'Owned emulator exited'
                    r = subprocess.run([str(adb), '-s', SERIAL, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, timeout=5)
                    return r.stdout.strip() == b'1'
                wait_until(booted, 180)
                stable = {'route': None, 'since': 0.0}

                def reachable():
                    probe = subprocess.run([str(adb), '-s', SERIAL, 'shell',
                        f'ip route get {host}; nc -z -w 2 {host} {port} && echo NC_OK'],
                        capture_output=True, timeout=15).stdout.decode()
                    match = re.search(r'dev (\S+).* src (\S+)', probe)
                    route = match.groups() if match and 'NC_OK' in probe else None
                    if route != stable['route']:
                        stable.update(route=route, since=time.monotonic())
                    return route is not None and time.monotonic()-stable['since'] >= 6
                wait_until(reachable, 90)
                for apk, package in [('androidApp/build/outputs/apk/debug/androidApp-debug.apk', PACKAGE),
                                     ('androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk', PACKAGE+'.test')]:
                    command('install', '-r', ROOT / apk)
                    installed.append(package)
                output = command('shell', 'am', 'instrument', '-w', '-e', 'class', 'com.coach.android.MockCourseAliasSmoke',
                    '-e', 'coachPort', port, '-e', 'coachDevice', 'ton1362-isolated', '-e', 'coachToken', token,
                    PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner', timeout=150)
                (args.evidence / 'instrumentation.txt').write_bytes(output.replace(token.encode(), b'<redacted>'))
                assert b'OK (1 test)' in output, 'Synthetic UI failed; see sanitized instrumentation evidence'
                for name in ['course', 'library', 'paper']:
                    png = command('exec-out', 'run-as', PACKAGE, 'cat', f'cache/ton1362-{name}.png')
                    assert png.startswith(b'\x89PNG'), 'Missing synthetic screenshot'
                    (args.evidence / f'{name}.png').write_bytes(png)
                report = dict(syntheticPapers=1, otherCoursePapers=0, instrumentationTests=1,
                              screenshots=3, package=PACKAGE, serial=SERIAL, port=port)
        finally:
            try:
                if emulator is not None:
                    try:
                        # Only the read-only emulator started above; never enumerate or address phones.
                        if emulator.poll() is None and adb is not None:
                            for package in reversed(installed):
                                result = subprocess.run([str(adb), '-s', SERIAL, 'uninstall', package], capture_output=True, timeout=30)
                                assert result.returncode == 0, 'Package cleanup failed: '+package
                            subprocess.run([str(adb), '-s', SERIAL, 'emu', 'kill'], capture_output=True, timeout=15)
                            emulator.wait(timeout=15)
                    finally:
                        stop(emulator)
            finally:
                stop(server)
    report['temporaryDataRemoved'] = True
    report['ownedProcessesStopped'] = True
    (args.evidence / (args.mode+'.json')).write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
