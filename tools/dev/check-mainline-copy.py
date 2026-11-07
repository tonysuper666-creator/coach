#!/usr/bin/env python3
"""Weekly choice mainline against a disposable, readonly-backed production copy. No real AI calls.

Build :server:app:installDist and the ton1358 APKs through tools/dev/heavy first. The week pack is
synthetic and written to a temporary COACH_MOCK_DIR; the copied database, credentials and logs are
deleted. With --android the walk is driven on coach_1128_c70_r: pick a week, miss the setup question,
mark a right detail answer as a guess, answer the bridge question, and read the coverage panel.
--private only counts what the configured private packs parse to (week, questions, facts); it prints
no question text.
"""
import argparse
import datetime
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

COURSE = 'ton1358-synthetic-mainline'
CODE = 'TON1358SYN'
PACKAGE = 'com.coach.android.ton1358'
SCREENS = ['entry', 'weeks-before', 'explained', 'explained-bottom', 'guessed-top', 'guessed', 'bridge-top', 'bridge', 'weeks-after']


def item(i, tier, kp, fact):
    reasons = {0: 'Synthetic apples were sold out before the season started.', 2: 'Synthetic plums ripen after the period in the question.',
               3: 'Synthetic figs are imported, not grown in the orchard.'}
    lines = ['正确：Synthetic pears are the only fruit grown in the synthetic orchard.']
    lines += [f'错项{k}：{v}' for k, v in reasons.items()]
    lines += ['背景：The synthetic orchard story sets up the supply example used later in the week.', '出处：Synthetic lecture 1, slide 4']
    return dict(id=i, type='mcq', kp_week=1, kp=kp, key_state='VERIFIED', fact_ids=[fact], tier=tier, marks=1,
                stem=f'Synthetic orchard question {i}: which statement about the orchard holds?',
                options=['Synthetic apples are grown there', 'Synthetic pears are grown there', 'Synthetic plums ripen early', 'Synthetic figs are grown there'],
                answer=1, explanation='\n'.join(lines), source='Synthetic lecture 1')


def write_pack(root):
    root.mkdir(parents=True)
    pack = dict(id='mainline-W01', kind='pack', course=CODE, minutes=10, sections=[dict(
        title='Synthetic orchard week', instructions='One at a time.', choose=None,
        items=[item('B1', 'bridge', 'ton1358-kp-c', 'F3'), item('S1', 'setup', 'ton1358-kp-a', 'F1'), item('D1', 'detail', 'ton1358-kp-b', 'F2')])])
    (root / 'W01.json').write_text(json.dumps(pack))
    facts = [dict(fact_id=f'F{n}', fact=f'Synthetic orchard fact {n}.', type='detail', source='Synthetic lecture 1', kp=f'ton1358-kp-{k}')
             for n, k in ((1, 'a'), (2, 'b'), (3, 'c'))]
    (root / 'W01-facts.md').write_text('Synthetic facts.\n\n```jsonl\n' + '\n'.join(json.dumps(f) for f in facts) + '\n```\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', metavar='SERIAL', help='Run only on coach_1128_c70_r; invoke under tools/dev/heavy --emulator')
    parser.add_argument('--screenshots', type=Path, help='Export synthetic emulator screenshots here')
    parser.add_argument('--private', action='store_true', help='Also count what the configured private packs parse to')
    args = parser.parse_args()
    os.umask(0o077)
    assert smoke.BINARY.is_file(), 'Build installDist through tools/dev/heavy first'
    android_host = ''
    adb = None
    if args.android:
        assert args.screenshots, '--screenshots is required with --android'
        assert args.android.startswith('emulator-'), 'Physical devices are forbidden'
        sdk_roots = [Path(value) for key in ('ANDROID_SDK_ROOT', 'ANDROID_HOME') if (value := os.environ.get(key))]
        sdk_roots += [Path.home() / 'Library/Android/sdk', Path.home() / 'android-sdk']
        adb = next((str(sdk / 'platform-tools/adb') for sdk in sdk_roots if (sdk / 'platform-tools/adb').is_file()), shutil.which('adb'))
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
        with tempfile.TemporaryDirectory(prefix='coach-mainline-copy-') as temporary:
            base = Path(temporary)
            data = base / 'data'
            data.mkdir(mode=0o700)
            production = Path.home() / 'Coach/data'
            with sqlite3.connect((production / 'coach.sqlite').resolve().as_uri() + '?mode=ro', uri=True) as source:
                with sqlite3.connect(data / 'coach.sqlite') as copy:
                    source.backup(copy)
            shutil.copyfile(production / 'device-token', data / 'device-token')
            token = (data / 'device-token').read_text().strip()
            write_pack(base / 'mock' / 'mcq-mainline')
            now = int(time.time() * 1000)
            local = datetime.datetime.fromtimestamp(now / 1000, ZoneInfo('Europe/London'))
            date = local.date() - datetime.timedelta(days=int(local.hour < 4))
            day = (date - datetime.date(1970, 1, 1)).days
            with sqlite3.connect(data / 'coach.sqlite') as db:
                assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
                assert db.execute('select count(*) from events where course_id=?', (COURSE,)).fetchone()[0] == 0, 'Fixture ID collision'
                db.execute("update jobs set state='cancelled',last_error='isolated_mainline_rehearsal' where state in ('queued','running')")
                seq = db.execute('select coalesce(max(seq),0) from events').fetchone()[0]
                payload = {'course': {'id': COURSE, 'name': CODE + ' Synthetic orchard', 'tz': 'Europe/London'},
                           'knowledgePoints': [{'id': f'ton1358-kp-{k}', 'title': f'Synthetic orchard point {k}', 'week': 1} for k in 'abc']}
                event = dict(seq=seq + 1, id=f'{COURSE}-e1', courseId=COURSE, type='outline_revised', schema=1, recordedAt=now, occurredAt=now,
                             deviceId='desktop', actor='SYSTEM', effectIndex=0, idemKey=f'{COURSE}-e1', payload=payload)
                db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                           (event['seq'], event['id'], COURSE, 'outline_revised', 1, now, now, 'desktop', 'SYSTEM', 0, event['idemKey'], json.dumps(payload), json.dumps(event)))

            port = smoke.free_port()
            while port == 3790:
                port = smoke.free_port()
            env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
            env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'),
                       COACH_LOG_DIR=str(base / 'logs'), COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'),
                       COACH_PACKS_DIR=str(base / 'packs'), COACH_MOCK_DIR=str(base / 'mock'), COACH_TAIL_HOST=android_host,
                       COACH_PORT=str(port), COACH_TRAFFIC='test', COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge.server_address[1]}')

            def request(path, course=COURSE):
                req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/courses/{course}{path}', headers={
                    'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop'})
                with urllib.request.urlopen(req, timeout=5) as response:
                    return json.load(response)

            def start():
                nonlocal process
                with (base / 'server.log').open('ab') as log:
                    process = subprocess.Popen([str(smoke.BINARY)], env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    assert process.poll() is None, 'Candidate exited; private logs withheld'
                    try:
                        request('/mainline')
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

            def coverage():
                return request('/mainline')['weeks'][0]['coverage']

            def walk_by_protocol():
                for item_id, choice, guessed in (('S1', 0, False), ('D1', 1, True), ('B1', 1, False)):
                    body = json.dumps(dict(itemId=item_id, choice=choice, guessed=guessed, submissionId=f'protocol-{item_id}')).encode()
                    req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/courses/{COURSE}/mainline/1/answer', data=body, headers={
                        'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop', 'Content-Type': 'application/json'})
                    with urllib.request.urlopen(req, timeout=5) as response:
                        json.load(response)

            def android():
                # Never echo the command or instrument output: arguments carry a copy-only credential.
                result = subprocess.run([adb, '-s', args.android, 'shell', 'am', 'instrument', '-w',
                    '-e', 'class', 'com.coach.android.MainlineSmoke', '-e', 'coachPort', str(port), '-e', 'coachToken', token,
                    f'{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, timeout=240)
                passed = result.returncode == 0 and b'OK (1 test)' in result.stdout
                args.screenshots.mkdir(parents=True, exist_ok=True)
                (args.screenshots / 'instrument.txt').write_text((result.stdout + result.stderr).decode(errors='replace').replace(token, '<redacted>'))
                for screen in SCREENS:
                    name = f'mainline-{screen}.png'
                    shot = subprocess.run([adb, '-s', args.android, 'exec-out', 'run-as', PACKAGE, 'cat', f'cache/{name}'], capture_output=True)
                    if shot.returncode == 0 and shot.stdout.startswith(b'\x89PNG'):
                        (args.screenshots / name).write_bytes(shot.stdout)
                assert passed, 'Synthetic emulator mainline walk failed; see instrument.txt (credential redacted)'

            try:
                start()
                before = coverage()
                assert (before['facts'], before['factsWithQuestions'], before['factsMastered'], before['answered']) == (3, 3, 0, 0)
                week = request('/mainline/1')
                assert [i['tier'] for i in week['items']] == ['setup', 'detail', 'bridge']
                assert all('answer' not in i and 'explanation' not in i for i in week['items'])
                android() if args.android else walk_by_protocol()
                after = coverage()
                assert (after['answered'], after['mastered'], after['factsMastered']) == (3, 1, 1), after
                projection = request('/snapshot')['projection']
                revisit = projection.get('revisit', {})
                assert revisit.get('ton1358-kp-a') == day + 1, 'the missed setup question comes back tomorrow'
                assert revisit.get('ton1358-kp-b') == day + 1, 'the guessed detail question comes back tomorrow'
                assert 'ton1358-kp-c' not in revisit, 'the bridge question answered without guessing is not queued again'
                stop()
                with sqlite3.connect(data / 'coach.sqlite') as db:
                    posed = [json.loads(r[0]) for r in db.execute("select payload from events where course_id=? and type='question_posed'", (COURSE,))]
                    submitted = [json.loads(r[0]) for r in db.execute("select payload from events where course_id=? and type='answer_submitted'", (COURSE,))]
                    assert len(posed) == 3 and all(p['mainline'] and p['purpose'] == 'RECALL' for p in posed)
                    assert sorted(bool(s.get('guessed')) for s in submitted) == [False, False, True]
                    assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
                private = None
                if args.private:
                    # The configured private directory; only counts leave this process.
                    env.pop('COACH_MOCK_DIR')
                    start()
                    course = next(line.partition('=')[2].strip() for line in (Path.home() / 'Coach/config/local.properties').read_text().splitlines()
                                  if line.startswith('coach.course='))
                    home = request('/mainline', course)
                    private = dict(weeks=[dict(week=w['week'], questions=w['coverage']['questions'], facts=w['coverage']['facts'],
                                               factsWithQuestions=w['coverage']['factsWithQuestions'], keysPending=w['coverage']['keysPending'])
                                          for w in home['weeks']], invalidFiles=[b['file'] for b in home['invalid']])
                    for w in home['weeks']:
                        items = request(f"/mainline/{w['week']}", course)['items']
                        assert all('answer' not in i and 'explanation' not in i for i in items)
                        tiers = [i['tier'] for i in items]
                        assert tiers == sorted(tiers, key=['setup', 'detail', 'bridge'].index)
                    stop()
                print(json.dumps(dict(result='pass', snapshot='SQLite backup through readonly production connection',
                    productionWrites=0, protectedPortsUsed=False, realTutorCalls=0, fixture='synthetic only',
                    coverageBefore=dict(facts=before['facts'], withQuestions=before['factsWithQuestions'], mastered=before['factsMastered']),
                    coverageAfter=dict(facts=after['facts'], withQuestions=after['factsWithQuestions'], mastered=after['factsMastered'], answered=after['answered']),
                    revisitTomorrow=sorted(k for k, v in revisit.items() if v == day + 1), guessedAnswers=1,
                    emulator='coach_1128_c70_r' if args.android else 'protocol only', privatePacks=private), indent=2))
            finally:
                stop()
    finally:
        if args.android:
            subprocess.run([adb, '-s', args.android, 'shell', 'am', 'force-stop', PACKAGE], capture_output=True)
        bridge.shutdown()
        bridge_thread.join()
        bridge.server_close()


if __name__ == '__main__':
    main()
