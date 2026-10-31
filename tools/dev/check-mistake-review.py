#!/usr/bin/env python3
"""OPPO acceptance on a copied production ledger plus reviewed inventory. No model calls.
Build first with -PcoachApplicationId=com.coach.reviewtest. Required environment:
COACH_REVIEW_SNAPSHOT, COACH_REVIEW_INVENTORY, COACH_REVIEW_OUT, COACH_REVIEW_SERIAL,
COACH_REVIEW_BRIDGE_PYTHON (an existing environment with FastAPI/uvicorn).
All child processes are collected and stopped before this foreground command exits.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import re
import socket
import sqlite3
import subprocess
import time
import urllib.request
from local_config import value

ROOT = Path(__file__).resolve().parents[2]
# Refresh server/client gates after any main rebase; the phone must exercise this checkout.
subprocess.run([ROOT/'tools/dev/heavy', ROOT/'gradlew', ':server:app:test', ':server:app:installDist',
                ':shared:client:jvmTest', '--max-workers=2'], check=True,
               env=dict(os.environ, JAVA_HOME='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
OUT = Path(os.environ['COACH_REVIEW_OUT']).resolve()
OUT.mkdir(parents=True, exist_ok=False, mode=0o700)
SNAPSHOT = Path(os.environ['COACH_REVIEW_SNAPSHOT']).resolve()
INVENTORY = Path(os.environ['COACH_REVIEW_INVENTORY']).resolve()
SERIAL = os.environ['COACH_REVIEW_SERIAL']
ADB = Path(value('coach.sdkDir')).expanduser() / 'platform-tools/adb'
HOST = value('coach.tailHost')
COURSE = value('coach.course')
PACKAGE = 'com.coach.reviewtest'
DATA = OUT / 'data'
DATA.mkdir(mode=0o700)
TOKEN = secrets.token_urlsafe(32)
DEVICE = 'review-acceptance'
children, logs = [], []


def run(args, timeout=90, binary=False, **kwargs):
    return subprocess.check_output(list(map(str, args)), timeout=timeout,
                                   text=not binary, stderr=subprocess.PIPE, **kwargs)


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


PORT, BRIDGE = port(), port()
assert not {PORT, BRIDGE} & {8790, 8791, 18791, 3790}
assert PORT != BRIDGE
with sqlite3.connect(SNAPSHOT.as_uri() + '?mode=ro', uri=True) as source, sqlite3.connect(DATA / 'coach.sqlite') as target:
    source.backup(target)
run(['python3', ROOT/'tools/pool/import_reviewed.py', '--source', INVENTORY,
     '--target', DATA, '--course', COURSE, '--packs', Path.home()/'Coach/corpus/packs',
     '--apply', '--backup', OUT/'before-import.sqlite', '--report', OUT/'inventory.json'])
with sqlite3.connect(DATA / 'coach.sqlite') as db:
    # Only our stopped, disposable copy is changed. Prevent copied pending jobs from calling AI.
    db.execute("delete from jobs where state in ('queued','running')")
    db.execute('insert or replace into devices(device_id,token_sha256,created_at) values(?,?,?)',
               (DEVICE, hashlib.sha256(TOKEN.encode()).hexdigest(), int(time.time()*1000)))
    kp = db.execute("""select kp_id from questions q join question_kp using(question_id)
        where q.course_id=? and key_state='VERIFIED' and format='SINGLE_CHOICE'
        and not exists(select 1 from events e where e.type='question_posed' and json_extract(e.payload,'$.questionId')=q.question_id)
        group by kp_id having count(*)>=6 order by kp_id limit 1""", (COURSE,)).fetchone()[0]
    keys = {row[0]: json.loads(row[1])['correctOptionId'] for row in db.execute(
        'select question_id,key_json from answer_keys join question_kp using(question_id) where kp_id=?', (kp,))}
    jobs_before = db.execute('select state,count(*) from jobs group by state').fetchall()
(DATA/'device-token').write_text(TOKEN)
(DATA/'device-token').chmod(0o600)
env = dict(os.environ, COACH_DATA_DIR=str(DATA), COACH_PORT=str(PORT), COACH_TAIL_HOST=HOST,
           COACH_DEVICE_ID=DEVICE, COACH_BRIDGE_URL=f'http://127.0.0.1:{BRIDGE}',
           COACH_PACKS_DIR=str(OUT/'no-generation-packs'), BRIDGE_PORT=str(BRIDGE),
           COACH_BRIDGE_HOME=str(OUT/'bridge'), JAVA_HOME='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')
env.pop('COACH_MANAGE_SERVICES', None)


def spawn(name, command):
    log = (OUT/(name+'.log')).open('a'); logs.append(log)
    p = subprocess.Popen(list(map(str, command)), cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    children.append(p)
    return p


def start_server():
    p = spawn('server', [ROOT/'server/app/build/install/app/bin/app'])
    end = time.monotonic()+30
    while time.monotonic() < end:
        if p.poll() is not None:
            raise RuntimeError('Test server exited; inspect server.log')
        try:
            req = urllib.request.Request(f'http://127.0.0.1:{PORT}/v1/hello', headers={
                'Authorization': 'Bearer '+TOKEN, 'X-Coach-Device': DEVICE})
            with urllib.request.urlopen(req, timeout=1) as r:
                if json.load(r)['epoch']: return p
        except OSError: time.sleep(.2)
    raise TimeoutError('Test server readiness')


def stop(p):
    if p.poll() is None:
        p.terminate()
        try: p.wait(timeout=20)
        except subprocess.TimeoutExpired: p.kill(); p.wait()


def instrument(method, phase):
    run([ADB, '-s', SERIAL, 'shell', 'am', 'force-stop', PACKAGE])
    output = run([ADB, '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.coach.android.MistakeReviewSmoke#'+method, '-e', 'coachPhase', phase,
        '-e', 'coachPort', PORT, '-e', 'coachDevice', DEVICE, '-e', 'coachToken', TOKEN,
        '-e', 'coachKp', kp, PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'], timeout=180)
    (OUT/(phase+'.txt')).write_text(output)
    if 'OK (1 test)' not in output: raise RuntimeError('Phone replay failed in '+phase+'; inspect phase report')
    print('OPPO '+phase+' passed', flush=True)


try:
    # Refuse to take over a phone already running another task's instrumentation.
    state = run([ADB, '-s', SERIAL, 'shell', 'dumpsys', 'activity'])
    if 'Active instrumentation' in state or 'ActiveInstrumentation{' in state:
        raise RuntimeError('OPPO is already under instrumentation; retry after its owner finishes')
    for apk in ['androidApp/build/outputs/apk/debug/androidApp-debug.apk',
                'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk']:
        package = PACKAGE+'.test' if 'androidTest' in apk else PACKAGE
        installed = run([ADB,'-s',SERIAL,'shell','pm','path',package]).strip().removeprefix('package:')
        same = False
        if installed:
            digest = run([ADB,'-s',SERIAL,'shell','sha256sum',installed]).split()[0]
            same = digest == hashlib.sha256((ROOT/apk).read_bytes()).hexdigest()
        if not same: run([ADB, '-s', SERIAL, 'install', '-r', ROOT/apk])
    run([ADB, '-s', SERIAL, 'shell', 'run-as', PACKAGE, 'mkdir', '-p', 'cache'])
    run([ADB, '-s', SERIAL, 'shell', f"run-as {PACKAGE} sh -c 'cat > cache/review-keys.json'"], input=json.dumps(keys))
    bridge = spawn('bridge', [os.environ['COACH_REVIEW_BRIDGE_PYTHON'], ROOT/'tools/bridge/subscription_bridge.py'])
    deadline = time.monotonic()+15
    while True:
        if bridge.poll() is not None: raise RuntimeError('Own bridge copy exited')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{BRIDGE}/health',timeout=1) as response:
                assert response.status == 200
            break
        except OSError:
            if time.monotonic()>deadline: raise TimeoutError('Own bridge readiness')
            time.sleep(.2)
    server = start_server()
    instrument('wrongAnswersRetryAndClear', 'prepare')
    stop(server)
    instrument('cachedMistakesSurviveRestartWithoutMac', 'offline')
    sizes = run([ADB,'-s',SERIAL,'shell','wm','size'])
    densities = run([ADB,'-s',SERIAL,'shell','wm','density'])
    old_size = re.search(r'Override size: ([0-9]+x[0-9]+)',sizes)
    old_density = re.search(r'Override density: ([0-9]+)',densities)
    try:
        for shape, width, height in [('outer',360,780),('inner',673,841),('pad-portrait',800,1280),('pad-landscape',1280,800)]:
            run([ADB,'-s',SERIAL,'shell','wm','size',f'{width}x{height}'])
            run([ADB,'-s',SERIAL,'shell','wm','density','160'])
            instrument('cachedMistakesSurviveRestartWithoutMac', shape)
            (OUT/('layout-'+shape+'.png')).write_bytes(run([ADB,'-s',SERIAL,'exec-out','run-as',PACKAGE,'cat','cache/review-offline.png'],binary=True))
    finally:
        run([ADB,'-s',SERIAL,'shell','wm','size',old_size.group(1) if old_size else 'reset'])
        run([ADB,'-s',SERIAL,'shell','wm','density',old_density.group(1) if old_density else 'reset'])
    server = start_server()
    instrument('wrongAnswersRetryAndClear', 'resume')
    stop(server)
    for name in ['review-metrics.json', 'review-wrong-list.png', 'review-retry.png', 'review-cleared.png', 'review-offline.png']:
        (OUT/name).write_bytes(run([ADB, '-s', SERIAL, 'exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name], binary=True))
    with sqlite3.connect(DATA/'coach.sqlite') as db:
        jobs_after = db.execute('select state,count(*) from jobs group by state').fetchall()
        assert jobs_before == jobs_after
    summary = json.loads((OUT/'review-metrics.json').read_text())
    summary.update(kp=kp,serverPort=PORT,bridgePort=BRIDGE,productionTouched=False,
                   snapshotSha256=hashlib.sha256(SNAPSHOT.read_bytes()).hexdigest(),newAiJobs=0,
                   layoutSizesDp=[[360,780],[673,841],[800,1280],[1280,800]],layoutMethod="OPPO display-size simulation; original wm settings restored")
    (OUT/'summary.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary), flush=True)
finally:
    for p in reversed(children): stop(p)
    for log in logs: log.close()
    # Keep the production package untouched; only remove this test package's private keys.
    subprocess.run([ADB, '-s', SERIAL, 'shell', 'am', 'force-stop', PACKAGE], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run([ADB, '-s', SERIAL, 'shell', 'run-as', PACKAGE, 'rm', '-f', 'cache/review-keys.json'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
