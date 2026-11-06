#!/usr/bin/env python3
"""Synthetic three-mistake replay. Run only inside `tools/dev/heavy --emulator`.

Required: a running coach_1128_c70_r AVD, cached Gradle dependencies, JDK 17,
and Android SDK (ANDROID_SDK_ROOT or ANDROID_HOME; alternatively adb on PATH).
JAVA_HOME may select the JDK; ANDROID_SERIAL is not used. No COACH_REVIEW_*
variables, production database, pairing, or external bridge are used.
Example: tools/dev/heavy --emulator python3 tools/dev/check-mistake-review.py \
    --emulator --serial emulator-5554 --out /isolated/evidence/ton1146
The output directory must be new and outside this checkout. It contains only
synthetic screenshots, redacted instrumentation output, trace, metrics and seqs.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.coach.android.ton1146'
COURSE = 'ton1146-synthetic'
KP = 'ton1146-synthetic-kp'
TEST = 'com.coach.android.MistakeReviewSmoke'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--serial', required=True, help='Explicit emulator-N serial; physical devices are rejected')
    parser.add_argument('--out', required=True, type=Path, help='New evidence directory outside the checkout')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('only an emulator-N serial is accepted')
    out = args.out.resolve()
    if out == ROOT or ROOT in out.parents or out.exists():
        parser.error('--out must be new and outside the checkout')
    # The emulator lane must be held by the immediate heavy wrapper; a build
    # lane or an independently selected lock does not serialize device access.
    try:
        owner = (Path.home() / 'dev/.coach-heavy.lock/owner').read_text()
    except OSError:
        raise RuntimeError('run inside tools/dev/heavy --emulator') from None
    if not re.match(r'^PID='+str(os.getppid())+r' command=', owner):
        raise RuntimeError('run inside tools/dev/heavy --emulator')
    os.umask(0o077)
    sdk_roots = [Path(os.environ[k]) for k in ('ANDROID_SDK_ROOT', 'ANDROID_HOME') if os.environ.get(k)]
    sdk_roots += [Path.home() / 'Library/Android/sdk', Path.home() / 'android-sdk']
    adb = next((str(p / 'platform-tools/adb') for p in sdk_roots if (p / 'platform-tools/adb').is_file()), shutil.which('adb'))
    if not adb:
        raise RuntimeError('Android SDK adb not found')
    def android(*cmd, **kwargs):
        return subprocess.run([adb, '-s', args.serial, *cmd], check=True, **kwargs)
    avd = android('emu', 'avd', 'name', capture_output=True, text=True, timeout=10).stdout.splitlines()[0].strip()
    if avd != 'coach_1128_c70_r':
        raise RuntimeError('unexpected emulator AVD')
    out.mkdir(parents=True, mode=0o700)
    subprocess.run(['./gradlew', ':server:app:installDist', ':androidApp:assembleDebug',
                    ':androidApp:assembleDebugAndroidTest', '-PcoachApplicationId='+PACKAGE,
                    '-Pcoach.course='+COURSE, '-Pcoach.tailHost=10.0.2.2', '-Pcoach.port=18888'], cwd=ROOT, check=True)
    spec = importlib.util.spec_from_file_location('candidate_smoke', ROOT/'tools/dev/check-candidate-smoke.py')
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    bridge = smoke.http.server.ThreadingHTTPServer(('127.0.0.1', 0), smoke.Bridge)
    if bridge.server_address[1] in smoke.FORBIDDEN | {3790}:
        bridge.server_close()
        raise RuntimeError('unsafe bridge port')
    thread = threading.Thread(target=bridge.serve_forever)
    thread.start()
    process = None
    installed_test = False
    try:
        with tempfile.TemporaryDirectory(prefix='coach-ton1146-') as tmp:
            base = Path(tmp)
            data = base/'data'
            data.mkdir()
            port = smoke.free_port()
            while port == 3790:
                port = smoke.free_port()
            env = {k:v for k,v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
            env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data/'device-token'),
                       COACH_LOG_DIR=str(base/'logs'), COACH_STATE_DIR=str(base/'state'),
                       COACH_RUN_DIR=str(base/'run'), COACH_PACKS_DIR=str(base/'packs'),
                       COACH_TAIL_HOST='', COACH_PORT=str(port), COACH_TRAFFIC='test',
                       COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge.server_address[1]}')
            def stop():
                nonlocal process
                if process is not None:
                    if process.poll() is None:
                        process.terminate()
                        try: process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
                    process = None
            def start():
                nonlocal process
                with (base/'server.log').open('ab') as log:
                    process = subprocess.Popen([str(smoke.BINARY)], env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic()+40
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('isolated server exited; private log withheld')
                    try:
                        urllib.request.urlopen(f'http://127.0.0.1:{port}/v1/hello', timeout=2)
                    except urllib.error.HTTPError as e:
                        if e.code == 401: return
                    except OSError: pass
                    time.sleep(.2)
                raise TimeoutError('isolated server readiness')
            try:
                start()
                token = (data/'device-token').read_text().strip()
                if not re.fullmatch(r'[A-Za-z0-9_-]{43}', token):
                    raise RuntimeError('invalid fresh test credential')
                stop()
                keys = {}
                now = int(time.time()*1000)
                with sqlite3.connect(data/'coach.sqlite') as db:
                    payload = {'course':{'tz':'Europe/London'}, 'ops':[{'kpId':KP,'title':'Synthetic objective','conceptIds':['synthetic']}]}
                    event = dict(seq=1,id=COURSE+'-outline',courseId=COURSE,type='outline_revised',schema=1,
                                 recordedAt=now,occurredAt=now,deviceId='desktop',actor='SYSTEM',effectIndex=0,
                                 idemKey=COURSE+'-outline',payload=payload)
                    db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                               (1,event['id'],COURSE,event['type'],1,now,now,'desktop','SYSTEM',0,event['idemKey'],json.dumps(payload),json.dumps(event)))
                    for i in range(6):
                        q = f'{COURSE}-q{i+1}'
                        options = [{'optionId':f'{q}-{j}','text':f'Synthetic option {i+1}-{j}'} for j in range(4)]
                        db.execute('insert into questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) values(?,?,?,?,?,?,?,?)',
                                   (q,COURSE,'SINGLE_CHOICE',f'Synthetic question {i+1}',json.dumps(options),'COACH',hashlib.sha256(q.encode()).hexdigest(),'VERIFIED'))
                        db.execute('insert into answer_keys(question_id,key_json) values(?,?)',(q,json.dumps({'correctOptionId':f'{q}-0'})))
                        db.execute('insert into question_kp(question_id,kp_id,is_primary) values(?,?,1)',(q,KP))
                        keys[q] = f'{q}-0'
                start()
                apks = ['androidApp/build/outputs/apk/debug/androidApp-debug.apk',
                        'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk']
                aapt = next((str(p) for root in sdk_roots for p in sorted((root/'build-tools').glob('*/aapt'), reverse=True)), None)
                if not aapt: raise RuntimeError('Android SDK aapt not found')
                for index, apk in enumerate(apks):
                    info = subprocess.check_output([aapt,'dump','badging',str(ROOT/apk)], text=True)
                    expected = PACKAGE + ('.test' if index else '')
                    if not re.search(r"^package: name='"+re.escape(expected)+r"'", info, re.MULTILINE):
                        raise RuntimeError('unexpected APK package')
                    if index: installed_test = True
                    android('install','-r',str(ROOT/apk),capture_output=True,timeout=120)
                android('shell','run-as',PACKAGE,'tee','cache/review-keys.json',
                        input=json.dumps(keys).encode(),capture_output=True,timeout=10)
                def capture(name):
                    result = subprocess.run([adb,'-s',args.serial,'exec-out','run-as',PACKAGE,'cat','cache/'+name],capture_output=True,timeout=10)
                    valid = result.stdout.startswith(b'\x89PNG\r\n\x1a\n') if name.endswith('.png') else not result.stdout.startswith((b'cat:', b'run-as:'))
                    if result.returncode == 0 and result.stdout and valid:
                        (out/name).write_bytes(result.stdout)
                def instrument(method, phase):
                    cmd = [adb,'-s',args.serial,'shell','am','instrument','-w','-e','class',TEST+'#'+method,
                           '-e','coachPort',str(port),'-e','coachToken',token,'-e','coachDevice','desktop',
                           '-e','coachKp',KP,'-e','coachPhase',phase,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
                    try:
                        result = subprocess.run(cmd,capture_output=True,timeout=420)
                        output = (result.stdout+result.stderr).decode(errors='replace').replace(token,'<redacted>')
                        (out/('instrument-'+phase+'.txt')).write_text(output)
                        if result.returncode or 'OK (1 test)' not in output:
                            raise RuntimeError('instrumentation failed: '+phase)
                    finally:
                        for name in ('review-trace.txt','review-metrics.json','review-wrong-list.png','review-retry.png',
                                     'review-cleared.png','review-restart-cleared.png','review-fail.png','review-fail.txt'):
                            capture(name)
                instrument('wrongAnswersRetryAndClear','prepare3')
                instrument('wrongAnswersRetryAndClear','resume3')
                stop()
                with sqlite3.connect(data/'coach.sqlite') as db:
                    rows = db.execute('select seq,type,event_json from events where course_id=? order by seq',(COURSE,)).fetchall()
                seqs = [{'seq':seq,'type':kind,'thread':json.loads(raw).get('threadId'),
                         'questionId':json.loads(raw).get('payload',{}).get('questionId'),
                         'attemptId':json.loads(raw).get('payload',{}).get('attemptId')}
                        for seq,kind,raw in rows]
                retry = [x for x in seqs if x['type']=='question_posed' and x['thread']=='mistakes:'+KP]
                if len(retry) != 3: raise AssertionError('retry ledger does not contain three posed questions')
                attempts = {x['attemptId'] for x in retry}
                if None in attempts or len(attempts) != 3: raise AssertionError('invalid retry attempt IDs')
                for kind in ('answer_submitted','attempt_graded'):
                    found = [x for x in seqs if x['type']==kind and x['attemptId'] in attempts]
                    if len(found) != 3: raise AssertionError('retry ledger incomplete: '+kind)
                (out/'ledger.json').write_text(json.dumps(seqs,indent=2))
                start()
                req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/courses/{COURSE}/events?after=0&limit=500',
                    headers={'Authorization':'Bearer '+token,'X-Coach-Device':'desktop'})
                with urllib.request.urlopen(req,timeout=5) as response:
                    restored = json.load(response)
                if restored['throughSeq'] < seqs[-1]['seq']:
                    raise AssertionError('ledger sequence not restored after server restart')
                android('shell','am','force-stop',PACKAGE,capture_output=True,timeout=10)
                instrument('clearedMistakesSurviveServerRestart','restart3')
                print('Synthetic replay passed; evidence:',out)
            finally:
                stop()
    finally:
        if installed_test:
            subprocess.run([adb,'-s',args.serial,'uninstall',PACKAGE+'.test'],capture_output=True,timeout=30)
        bridge.shutdown()
        thread.join(timeout=10)
        bridge.server_close()


if __name__ == '__main__':
    main()
