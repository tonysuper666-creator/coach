#!/usr/bin/env python3
"""Review practice against an explicit private database copy. No real AI calls.

Build :server:app:installDist through tools/dev/heavy first. Only synthetic fixture facts
are inspected/reported; the copied database, credentials and server logs are deleted.
This checks the service protocol, not the separately required emulator interaction.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.request
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('candidate_smoke', ROOT / 'tools/dev/check-candidate-smoke.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', metavar='SERIAL', help='Run only on coach_1128_c70_r; invoke under tools/dev/heavy --emulator')
    parser.add_argument('--screenshots', type=Path, help='Export synthetic emulator screenshots here')
    parser.add_argument('--mistakes', action='store_true', help='Exercise wrong answers, offline replay and independent review retry on the emulator')
    parser.add_argument('--private-data-dir', required=True, type=Path, help='prepared private database/token copies outside the installed Coach tree')
    args = parser.parse_args()
    assert not args.mistakes or args.android, '--mistakes requires the isolated emulator'
    os.umask(0o077)
    source_data = args.private_data_dir.resolve()
    assert not source_data.is_relative_to((Path.home() / 'Coach').resolve()), 'Refusing installed Coach tree'
    assert all((source_data / name).is_file() and not (source_data / name).is_symlink() for name in ('coach.sqlite', 'device-token'))
    assert smoke.BINARY.is_file(), 'Build installDist through tools/dev/heavy first'
    android_host = ''
    adb = None
    if args.android:
        assert args.screenshots, '--screenshots is required with --android'
        assert args.android.startswith('emulator-'), 'Physical devices are forbidden'
        sdk_roots = [Path(value) for key in ('ANDROID_SDK_ROOT', 'ANDROID_HOME')
                     if (value := os.environ.get(key))]
        sdk_roots += [Path.home() / 'Library/Android/sdk', Path.home() / 'android-sdk']
        adb = next((str(sdk / 'platform-tools/adb') for sdk in sdk_roots
                    if (sdk / 'platform-tools/adb').is_file()), shutil.which('adb'))
        assert adb, 'Set ANDROID_SDK_ROOT/ANDROID_HOME or put adb on PATH'
        avd = subprocess.check_output([adb, '-s', args.android, 'emu', 'avd', 'name'], text=True).splitlines()[0].strip()
        assert avd == 'coach_1128_c70_r', 'Wrong emulator'
        config = (Path.home() / 'Coach/config/local.properties').read_text().splitlines()
        android_host = next(line.partition('=')[2].strip() for line in config if line.startswith('coach.tailHost='))
        for apk in ['androidApp/build/outputs/apk/debug/androidApp-debug.apk',
                    'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk']:
            result = subprocess.run([adb, '-s', args.android, 'install', '-r', str(ROOT / apk)], capture_output=True)
            assert result.returncode == 0, 'Isolated APK install failed'
    bridge = smoke.http.server.ThreadingHTTPServer(('127.0.0.1', 0), smoke.Bridge)
    assert bridge.server_address[1] not in smoke.FORBIDDEN | {3790}
    bridge_thread = threading.Thread(target=bridge.serve_forever)
    bridge_thread.start()
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix='coach-review-copy-') as temporary:
            base = Path(temporary)
            data = base / 'data'
            data.mkdir(mode=0o700)
            with sqlite3.connect((source_data / 'coach.sqlite').as_uri() + '?mode=ro', uri=True) as source:
                with sqlite3.connect(data / 'coach.sqlite') as copy:
                    source.backup(copy)
            shutil.copyfile(source_data / 'device-token', data / 'device-token')
            token = (data / 'device-token').read_text().strip()
            now = int(time.time() * 1000)
            local = datetime.datetime.fromtimestamp(now / 1000, ZoneInfo('Europe/London'))
            date = local.date() - datetime.timedelta(days=int(local.hour < 4))
            day = (date - datetime.date(1970, 1, 1)).days
            course, kp = 'ton1291-synthetic-review', 'ton1291-synthetic-kp'
            thread_id = 'review:' + course + ':' + kp
            with sqlite3.connect(data / 'coach.sqlite') as db:
                assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
                assert db.execute('select count(*) from events where course_id=?', (course,)).fetchone()[0] == 0, 'Fixture ID collision'
                # A rehearsal must not resume copied production jobs.
                db.execute("update jobs set state='cancelled',last_error='isolated_review_rehearsal' where state in ('queued','running')")
                seq = db.execute('select coalesce(max(seq),0) from events').fetchone()[0]
                facts = [
                    ('outline_revised', {'course': {'tz': 'Europe/London'}, 'ops': [{'kpId': kp, 'title': 'Synthetic review objective', 'conceptIds': ['synthetic']}]}),
                    ('legacy_passed', {'kpId': kp}),
                    ('legacy_schedule', {'kpId': kp, 'dueDay': day + 12, 'interval': 15}),
                    ('exam_set', {'examDay': day + 7}),
                ]
                if args.mistakes:
                    facts = [facts[0], ('explanation_delivered', {'kpId': kp, 'conceptIds': ['synthetic']})]
                for i, (kind, payload) in enumerate(facts, 1):
                    event = dict(seq=seq+i, id=f'{course}-e{i}', courseId=course, type=kind,
                                 schema=1, recordedAt=now, occurredAt=now, deviceId='desktop',
                                 actor='SYSTEM', effectIndex=0, idemKey=f'{course}-e{i}', payload=payload)
                    db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                               (event['seq'], event['id'], course, kind, 1, now, now, 'desktop', 'SYSTEM', 0, event['idemKey'], json.dumps(payload), json.dumps(event)))
                keys = {}
                for i in range(6 if args.mistakes else 3):
                    q = f'{course}-q{i}'
                    keys[q] = f'{q}-0'
                    options = [{'optionId': f'{q}-{j}', 'text': f'Synthetic option {i}-{j}'} for j in range(4)]
                    db.execute('insert into questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) values(?,?,?,?,?,?,?,?)',
                               (q, course, 'SINGLE_CHOICE', f'Synthetic review question {i}', json.dumps(options), 'COACH', hashlib.sha256(q.encode()).hexdigest(), 'VERIFIED'))
                    db.execute('insert into answer_keys(question_id,key_json) values(?,?)', (q, json.dumps({'correctOptionId': f'{q}-0'})))
                    db.execute('insert into question_kp(question_id,kp_id,is_primary) values(?,?,1)', (q, kp))

            port = smoke.free_port()
            while port == 3790:
                port = smoke.free_port()
            for name in ('home', 'tmp', 'packs'):
                (base / name).mkdir(mode=0o700)
            env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))
                   and k not in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
            env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'),
                       COACH_LOG_DIR=str(base / 'logs'), COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'),
                       COACH_PACKS_DIR=str(base / 'packs'), COACH_TAIL_HOST=android_host, COACH_PORT=str(port),
                       COACH_TRAFFIC='test', COACH_MANAGE_SERVICES='0', COACH_SOURCE_LOOKUP='false',
                       COACH_ACTIVITY_SOCKET=str(base / 'activity.sock'),
                       JAVA_OPTS=f'-Duser.home={base}/home -Djava.io.tmpdir={base}/tmp',
                       COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge.server_address[1]}')

            def request(path):
                req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', headers={
                    'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop'})
                with urllib.request.urlopen(req, timeout=3) as response:
                    return json.load(response)

            def start():
                nonlocal process
                with (base / 'server.log').open('ab') as log:
                    process = subprocess.Popen([str(smoke.BINARY)], env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    assert process.poll() is None, 'Candidate exited; private logs withheld'
                    try:
                        request('/v1/hello')
                        return
                    except OSError:
                        time.sleep(.2)
                raise TimeoutError('Candidate readiness')

            def stop():
                nonlocal process
                if process is not None:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
                    process = None

            def android(phase):
                if not args.android:
                    return
                # Never echo the command or instrument output: arguments carry a copy-only credential.
                result = subprocess.run([adb, '-s', args.android, 'shell', 'am', 'instrument', '-w',
                    '-e', 'class', 'com.coach.android.ReviewQueueSmoke',
                    '-e', 'coachPort', str(port), '-e', 'coachToken', token, '-e', 'coachPhase', phase,
                    'com.coach.android.ton1291.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, timeout=150)
                passed = result.returncode == 0 and b'OK (1 test)' in result.stdout
                args.screenshots.mkdir(parents=True, exist_ok=True)
                (args.screenshots / f'instrument-{phase}.txt').write_text(
                    (result.stdout + result.stderr).decode(errors='replace').replace(token, '<redacted>'))
                for screen in (['today', 'queue', 'practice'] if phase == 'before' else ['today', 'queue']):
                    name = f'review-{phase}-{screen}.png'
                    shot = subprocess.run([adb, '-s', args.android, 'exec-out', 'run-as', 'com.coach.android.ton1291', 'cat', f'cache/{name}'], capture_output=True)
                    if shot.returncode == 0 and shot.stdout.startswith(b'\x89PNG'):
                        (args.screenshots / name).write_bytes(shot.stdout)
                assert passed, 'Synthetic emulator review flow failed; instrument output withheld to protect credentials'

            def mistake_android(method, phase):
                result = subprocess.run([adb, '-s', args.android, 'shell', 'am', 'instrument', '-w',
                    '-e', 'class', 'com.coach.android.MistakeReviewSmoke#' + method,
                    '-e', 'coachPort', str(port), '-e', 'coachToken', token, '-e', 'coachDevice', 'desktop',
                    '-e', 'coachKp', kp, '-e', 'coachPhase', phase,
                    'com.coach.android.ton1291.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, timeout=240)
                args.screenshots.mkdir(parents=True, exist_ok=True)
                (args.screenshots / f'mistake-{phase}.txt').write_text(
                    (result.stdout + result.stderr).decode(errors='replace').replace(token, '<redacted>'))
                for name in ('review-metrics.json', 'review-fail.txt', 'review-trace.txt',
                             'review-wrong-list.png', 'review-offline.png', 'review-retry.png', 'review-cleared.png', 'review-fail.png'):
                    artifact = subprocess.run([adb, '-s', args.android, 'exec-out', 'run-as', 'com.coach.android.ton1291', 'cat', 'cache/' + name], capture_output=True)
                    if artifact.returncode == 0:
                        (args.screenshots / name).write_bytes(artifact.stdout.replace(token.encode(), b'<redacted>'))
                assert result.returncode == 0 and b'OK (1 test)' in result.stdout, 'Synthetic mistake emulator flow failed; see sanitized phase output'

            def force_stop():
                subprocess.run([adb, '-s', args.android, 'shell', 'am', 'force-stop', 'com.coach.android.ton1291'], check=True, capture_output=True)

            try:
                start()
                if args.mistakes:
                    subprocess.run([adb, '-s', args.android, 'shell', 'run-as', 'com.coach.android.ton1291', 'mkdir', '-p', 'cache'], check=True, capture_output=True)
                    subprocess.run([adb, '-s', args.android, 'shell', "run-as com.coach.android.ton1291 sh -c 'cat > cache/review-keys.json'"],
                                   input=json.dumps(keys).encode(), check=True, capture_output=True)
                    mistake_android('wrongAnswersRetryAndClear', 'prepare')
                    stop(); force_stop()
                    mistake_android('cachedMistakesSurviveRestartWithoutMac', 'offline')
                    force_stop(); start()
                    mistake_android('wrongAnswersRetryAndClear', 'resume')
                    stop()
                    with sqlite3.connect(data / 'coach.sqlite') as db:
                        posed = [(row[0], json.loads(row[1])) for row in db.execute("select thread_id,payload from events where course_id=? and type='question_posed'", (course,))]
                        learning = [p for t, p in posed if t == 'kp:' + kp]
                        retry = [p for t, p in posed if t == thread_id]
                        assert len(learning) == len(retry) == 3
                        assert all(p['purpose'] == 'RECALL' and p['review'] for p in retry)
                        assert not {p['questionId'] for p in learning} & {p['questionId'] for p in retry}
                        assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
                    print(json.dumps(dict(result='pass', fixture='synthetic private copy', emulator='coach_1128_c70_r',
                        phases=['prepare', 'offline', 'resume'], learningCards=3, independentRecallCards=3,
                        protectedPortsUsed=False, productionConnections=0, realTutorCalls=0)))
                    return
                before = request(f'/v1/courses/{course}/snapshot')['projection']
                review = before['review'][kp]
                assert review['dueDay'] == day and review['intervalDays'] == 3
                assert review['baseDueDay'] == day + 12 and review['baseIntervalDays'] == 15
                due_before = sum(r['dueDay'] <= day and r.get('lastReviewDay') != day for r in before['review'].values())
                assert due_before == 1
                android('before')
                common = {'threadId': thread_id, 'courseId': course}
                opening = dict(common, t='tutor.open', kpId=kp, conceptIds=['synthetic'], title='Synthetic review objective', review=True)
                ws = smoke.WS(port, 'desktop', token)
                try:
                    ws.send(opening)
                    assert ws.await_type('tutor.state', 'msg.error')['t'] == 'tutor.state'
                    ws.send(dict(common, t='tutor.next'))
                    group = ws.await_type('tutor.cards', 'msg.start', 'msg.error')
                    assert group['t'] == 'tutor.cards' and len(group['cards']) == 3
                finally:
                    ws.close()
                # Recover the same pending group after a real service restart.
                stop()
                start()
                ws = smoke.WS(port, 'desktop', token)
                try:
                    ws.send(opening)
                    assert ws.await_type('tutor.state', 'msg.error')['t'] == 'tutor.state'
                    ws.send(dict(common, t='tutor.next'))
                    resumed = ws.await_type('tutor.cards', 'msg.start', 'msg.error')
                    assert resumed['t'] == 'tutor.cards' and resumed['groupId'] == group['groupId']
                    assert [c['attemptId'] for c in resumed['cards']] == [c['attemptId'] for c in group['cards']]
                    answers = {c['attemptId']: c['letterMap'][c['questionId'] + '-0'] for c in group['cards']}
                    ws.send(dict(common, t='tutor.answer', groupId=group['groupId'], submissionId='synthetic-review-submit', answers=answers))
                    feedback = ws.await_type('tutor.feedback', 'msg.error')
                    assert feedback['t'] == 'tutor.feedback' and len(feedback['grades']) == 3
                finally:
                    ws.close()
                after = request(f'/v1/courses/{course}/snapshot')['projection']
                assert after['review'][kp]['lastReviewDay'] == day
                assert after['review'][kp]['lastResult'] == 'PASS'
                assert after['position']['current'] == before['position']['current']
                due_after = sum(r['dueDay'] <= day and r.get('lastReviewDay') != day for r in after['review'].values())
                assert due_after == 0
                android('after')
                stop()
                with sqlite3.connect(data / 'coach.sqlite') as db:
                    posed = [json.loads(row[0]) for row in db.execute("select payload from events where course_id=? and type='question_posed'", (course,))]
                    assert len(posed) == 3 and all(p['review'] and p['purpose'] == 'RECALL' for p in posed)
                    assert db.execute("select count(*) from events where course_id=? and type='explanation_delivered'", (course,)).fetchone()[0] == 0
                    assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
                print(json.dumps(dict(result='pass', snapshot='explicit private SQLite copy',
                    productionWrites=0, protectedPortsUsed=False, realTutorCalls=0,
                    compressedInterval=3, ledgerDueCountBefore=due_before, ledgerDueCountAfter=due_after,
                    reviewCards=3, gradedCards=3, samePendingGroupAfterRestart=True,
                    continuePositionUnchanged=True, fixture='synthetic only',
                    emulator='coach_1128_c70_r' if args.android else 'not exercised by this protocol check',
                    androidAnswerSubmission='protocol after UI entry' if args.android else None), indent=2))
            finally:
                stop()
    finally:
        if args.android:
            subprocess.run([adb, '-s', args.android, 'shell', 'am', 'force-stop', 'com.coach.android.ton1291'], capture_output=True)
        bridge.shutdown()
        bridge_thread.join()
        bridge.server_close()


if __name__ == '__main__':
    main()
