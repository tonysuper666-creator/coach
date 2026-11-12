#!/usr/bin/env python3
"""OPPO acceptance on a copied production ledger, optionally plus reviewed inventory. No model calls.
Build first with -PcoachApplicationId=com.coach.ton1146 (or COACH_REVIEW_PACKAGE). Required environment:
COACH_REVIEW_SNAPSHOT, COACH_REVIEW_OUT, COACH_REVIEW_SERIAL,
COACH_REVIEW_BRIDGE_PYTHON (an existing environment with FastAPI/uvicorn).
Optional: COACH_REVIEW_INVENTORY. When unset, the snapshot copy already carries the reviewed pool,
so the import step is skipped and summary.json records inventoryImport="skipped".
All child processes are collected and stopped before this foreground command exits.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import re
import selectors
import signal
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.request


def prepare_choice_keys(db, course):
    """Pick one KP, then map only that KP's SINGLE_CHOICE answer keys.

    The pick uses the same count as tools/pool/import_reviewed.py inventory():
    active KP, VERIFIED SINGLE_CHOICE, claude-generated, gpt-reviewed, never
    posed, and not offline-reserved. CALCULATION rows are left out of the map.
    A missing or damaged SINGLE_CHOICE key raises SystemExit here, before the
    caller reaches adb.
    """
    picked = db.execute("""select k.kp_id from questions q join question_kp k using(question_id)
        join pool_details d using(question_id) join answer_keys a using(question_id)
        join outline_kp o on o.course_id=q.course_id and o.kp_id=k.kp_id and o.retired=0
        where q.course_id=? and q.format='SINGLE_CHOICE' and q.key_state='VERIFIED'
        and d.reviewer=a.reviewed_by and d.reviewer like 'gpt%' and d.generator like 'claude%'
        and not exists(select 1 from events e where e.course_id=q.course_id and e.type='question_posed'
            and json_extract(e.payload,'$.questionId')=q.question_id)
        and not exists(select 1 from offline_reservations r where r.question_id=q.question_id
            and r.expires_at>cast(strftime('%s','now') as integer)*1000)
        group by k.kp_id having count(*)>=6 order by k.kp_id limit 1""", (course,)).fetchone()
    if picked is None:
        raise SystemExit(f'No active KP in {course} has six unseen reviewed questions in this snapshot copy')
    kp = picked[0]
    keys = {}
    for question_id, key_json in db.execute(
            """select q.question_id, a.key_json from answer_keys a
               join question_kp k using(question_id)
               join questions q using(question_id)
               where k.kp_id=? and q.format='SINGLE_CHOICE'""", (kp,)):
        try:
            option = json.loads(key_json)['correctOptionId']
        except (json.JSONDecodeError, KeyError, TypeError) as error:
            raise SystemExit(
                f'SINGLE_CHOICE answer key for {question_id} is missing or damaged ({type(error).__name__})'
            ) from error
        if not isinstance(option, str) or not option.strip():
            raise SystemExit(f'SINGLE_CHOICE answer key for {question_id} is missing or damaged (correctOptionId)')
        keys[question_id] = option
    return kp, keys


def run(args, timeout=90, binary=False, **kwargs):
    return subprocess.check_output(list(map(str, args)), timeout=timeout,
                                   text=not binary, stderr=subprocess.PIPE, **kwargs)


def testphone_install_sh():
    # tools/dev/formfactor-sim.sh: $COACH_TESTPHONE_DIR/install.sh, else ~/Coach/testphone/install.sh.
    directory = os.environ.get('COACH_TESTPHONE_DIR')
    root = Path(directory) if directory else Path.home() / 'Coach' / 'testphone'
    return root / 'install.sh'


def _timeout_bytes(value):
    """Raw bytes, or a faithful encoding of captured text. None is an empty file."""
    if value is None:
        return b''
    if isinstance(value, bytes):
        return value
    if isinstance(value, str):
        return value.encode('utf-8', errors='surrogateescape')
    raise TypeError(type(value).__name__)


def _install_timeout_dir():
    found = globals().get('OUT')
    if found is not None:
        return Path(found)
    raw = os.environ.get('COACH_REVIEW_OUT')
    return Path(raw) if raw else None


def _save_install_timeout(exc):
    """Write this TimeoutExpired's stdout and stderr into the current output directory.

    A failed save must not replace the install exception. The hint is one fixed
    sentence and contains no path and no captured output.
    """
    directory = _install_timeout_dir()
    hint = 'install timeout output was not saved\n'
    saved = directory is not None
    if saved:
        for name, value in (
                ('install-timeout.stdout', getattr(exc, 'stdout', None)),
                ('install-timeout.stderr', getattr(exc, 'stderr', None))):
            try:
                (directory / name).write_bytes(_timeout_bytes(value))
            except Exception:
                saved = False
    if saved:
        return
    try:
        if directory is not None:
            (directory / 'install-timeout-save-error.txt').write_text(hint, encoding='ascii')
            return
    except Exception:
        pass
    try:
        sys.stderr.write(hint)
    except Exception:
        pass


def _run_install(args, timeout):
    """Share one monotonic deadline with install.sh, including startup and exit.

    The helper owns and reaps its directly launched clients on TERM/INT. Only
    signal this Popen handle here, never an adb server or another installation.
    Files avoid waiting for EOF on descriptors inherited by a daemon.
    """
    deadline = time.monotonic() + timeout
    reserve = min(0.5, timeout / 8)
    env = dict(os.environ, COACH_INSTALL_DEADLINE=str(deadline))
    command = list(map(str, args))
    process = None
    cancelled = 0

    def cancel(number, frame):
        nonlocal cancelled
        cancelled = number

    previous = {}
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        try:
            for number in (signal.SIGTERM, signal.SIGINT):
                previous[number] = signal.signal(number, cancel)
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=stdout,
                                       stderr=stderr, env=env, start_new_session=True)
            while (process.poll() is None and not cancelled
                   and time.monotonic() < deadline - reserve):
                time.sleep(min(0.02, max(0, deadline - reserve - time.monotonic())))
            timed_out = process.poll() is None
        finally:
            # Repeated cancellation cannot interrupt the bounded reap.
            for number in previous:
                signal.signal(number, signal.SIG_IGN)
            try:
                if process is not None and process.poll() is None:
                    try:
                        process.terminate()
                    except ProcessLookupError:
                        pass
                    # Cancellation may arrive well before the work deadline.
                    # Let the helper finish its bounded client reap before KILL.
                    end = min(deadline - reserve / 4,
                              time.monotonic() + min(3, timeout / 2))
                    while process.poll() is None and time.monotonic() < end:
                        time.sleep(min(0.01, max(0, end - time.monotonic())))
                    if process.poll() is None:
                        try:
                            process.kill()
                        except ProcessLookupError:
                            pass
                    while process.poll() is None and time.monotonic() < deadline:
                        time.sleep(min(0.01, max(0, deadline - time.monotonic())))
            finally:
                for number, handler in previous.items():
                    signal.signal(number, handler)
        stdout.seek(0)
        stderr.seek(0)
        output, error = stdout.read(), stderr.read()
    if cancelled:
        raise SystemExit(128 + cancelled)
    if timed_out:
        raise subprocess.TimeoutExpired(command, timeout, output=output, stderr=error)
    if process.returncode:
        raise subprocess.CalledProcessError(process.returncode, command, output=output, stderr=error)
    return output.decode('utf-8')


def install_with_confirm(apk, run_command=_run_install):
    # Proven call is `install.sh <apk>`. formfactor-sim.sh requires a stdout line starting with Success.
    # The helper shares this 180s budget. Other run() calls keep the 90s timeout.
    # TimeoutExpired keeps its captured output, then the same exception is raised.
    # Success is checked only after the process exits.
    try:
        output = run_command([testphone_install_sh(), apk], timeout=180)
    except subprocess.TimeoutExpired as exc:
        _save_install_timeout(exc)
        raise
    if not any(line.startswith('Success') for line in output.splitlines()):
        raise RuntimeError('install.sh did not report Success')
    return output


# Phone acceptance starts below. Importing this module runs that path.
from local_config import value

ROOT = Path(__file__).resolve().parents[2]
# Refresh server/client gates after any main rebase; the phone must exercise this checkout.
subprocess.run([ROOT/'tools/dev/heavy', ROOT/'gradlew', ':server:app:test', ':server:app:installDist',
                ':shared:client:jvmTest', '--max-workers=2'], check=True,
               env=dict(os.environ, JAVA_HOME='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
OUT = Path(os.environ['COACH_REVIEW_OUT']).resolve()
OUT.mkdir(parents=True, exist_ok=False, mode=0o700)
SNAPSHOT = Path(os.environ['COACH_REVIEW_SNAPSHOT']).resolve()
INVENTORY = Path(os.environ['COACH_REVIEW_INVENTORY']).resolve() if os.environ.get('COACH_REVIEW_INVENTORY') else None
SERIAL = os.environ['COACH_REVIEW_SERIAL']
ADB = Path(value('coach.sdkDir')).expanduser() / 'platform-tools/adb'
HOST = value('coach.tailHost')
COURSE = value('coach.course')
PACKAGE = os.environ.get('COACH_REVIEW_PACKAGE', 'com.coach.ton1146')
assert PACKAGE != 'com.coach.android'
DATA = OUT / 'data'
DATA.mkdir(mode=0o700)
TOKEN = secrets.token_urlsafe(32)
DEVICE = 'review-acceptance'
children, logs = [], []


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


PORT, BRIDGE = port(), port()
assert not {PORT, BRIDGE} & {8790, 8791, 18791, 3790}
assert PORT != BRIDGE
with sqlite3.connect(SNAPSHOT.as_uri() + '?mode=ro', uri=True) as source, sqlite3.connect(DATA / 'coach.sqlite') as target:
    source.backup(target)
if INVENTORY:
    run(['python3', ROOT/'tools/pool/import_reviewed.py', '--source', INVENTORY,
         '--target', DATA, '--course', COURSE, '--packs', Path.home()/'Coach/corpus/packs',
         '--apply', '--backup', OUT/'before-import.sqlite', '--report', OUT/'inventory.json'])
with sqlite3.connect(DATA / 'coach.sqlite') as db:
    # Only our stopped, disposable copy is changed. Prevent copied pending jobs from calling AI.
    db.execute("delete from jobs where state in ('queued','running')")
    db.execute('insert or replace into devices(device_id,token_sha256,created_at) values(?,?,?)',
               (DEVICE, hashlib.sha256(TOKEN.encode()).hexdigest(), int(time.time()*1000)))
    # Fail before any adb command touches the phone.
    kp, keys = prepare_choice_keys(db, COURSE)
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


def stream_instrument(command, report, phase, timeout=420, heartbeat=10):
    """Stream partial output even if the runner hangs before the test starts."""
    start = time.monotonic()
    last_step = 'waiting for instrumentation startup'
    output = bytearray()
    pending = b''
    process = subprocess.Popen(list(map(str, command)), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    next_heartbeat = start
    try:
        with report.open('wb') as log:
            while True:
                now = time.monotonic()
                if now >= next_heartbeat:
                    print(f'{time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())} OPPO {phase} '
                          f'elapsed={now-start:.1f}s last={last_step}', flush=True)
                    next_heartbeat = now + heartbeat
                if now - start >= timeout:
                    raise TimeoutError(f'OPPO {phase} timed out after {timeout}s; last={last_step}; report={report}')
                for key, _ in selector.select(min(next_heartbeat-now, timeout-(now-start), 1)):
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    log.write(chunk); log.flush(); output.extend(chunk)
                    pending += chunk
                    lines = pending.split(b'\n'); pending = lines.pop()
                    for line in lines:
                        decoded = line.decode('utf-8', errors='replace').strip()
                        if 'COACH_STEP ' in decoded:
                            last_step = decoded.split('COACH_STEP ', 1)[1]
                        print(f'{time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())} OPPO {phase} {decoded}', flush=True)
                if process.poll() is not None and not selector.get_map():
                    if pending:
                        print(pending.decode('utf-8', errors='replace'), flush=True)
                    if process.returncode != 0:
                        raise RuntimeError(f'OPPO {phase} instrumentation exited {process.returncode}; last={last_step}; report={report}')
                    return output.decode('utf-8', errors='replace')
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        process.stdout.close()


def instrument(method, phase):
    run([ADB, '-s', SERIAL, 'shell', 'am', 'force-stop', PACKAGE+'.test'])
    run([ADB, '-s', SERIAL, 'shell', 'am', 'force-stop', PACKAGE])
    output = stream_instrument([ADB, '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-e', 'class',
        'com.coach.android.MistakeReviewSmoke#'+method, '-e', 'coachPhase', phase,
        '-e', 'coachPort', PORT, '-e', 'coachDevice', DEVICE, '-e', 'coachToken', TOKEN,
        '-e', 'coachKp', kp, PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'], OUT/(phase+'.txt'), phase)
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
        try: installed = run([ADB,'-s',SERIAL,'shell','pm','path',package]).strip().removeprefix('package:')
        except subprocess.CalledProcessError: installed = ''  # first install of a per-card package
        same = False
        if installed:
            digest = run([ADB,'-s',SERIAL,'shell','sha256sum',installed]).split()[0]
            same = digest == hashlib.sha256((ROOT/apk).read_bytes()).hexdigest()
        if not same: install_with_confirm(ROOT/apk)
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
    summary.update(kp=kp,inventoryImport='applied' if INVENTORY else 'skipped',serverPort=PORT,bridgePort=BRIDGE,productionTouched=False,
                   snapshotSha256=hashlib.sha256(SNAPSHOT.read_bytes()).hexdigest(),newAiJobs=0,
                   layoutSizesDp=[[360,780],[673,841],[800,1280],[1280,800]],layoutMethod="OPPO display-size simulation; original wm settings restored")
    (OUT/'summary.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary), flush=True)
finally:
    # A killed adb client can leave the runner on the device; stop it before collecting evidence.
    try: subprocess.run([ADB, '-s', SERIAL, 'shell', 'am', 'force-stop', PACKAGE+'.test'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    except (OSError, subprocess.TimeoutExpired): pass
    # Failure evidence written by the instrumented test, kept next to the other artifacts.
    try: snaps = [n for n in run([ADB, '-s', SERIAL, 'shell', 'run-as', PACKAGE, 'ls', 'cache']).split() if n.startswith('review-snap-')]
    except Exception: snaps = []
    for name in ['review-fail.png', 'review-fail.txt', 'review-trace.txt'] + snaps:
        try: (OUT/name).write_bytes(run([ADB, '-s', SERIAL, 'exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name], binary=True))
        except Exception: pass
    for p in reversed(children): stop(p)
    for log in logs: log.close()
    # Keep the production package untouched; only remove this test package's private keys.
    subprocess.run([ADB, '-s', SERIAL, 'shell', 'am', 'force-stop', PACKAGE], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run([ADB, '-s', SERIAL, 'shell', 'run-as', PACKAGE, 'rm', '-f', 'cache/review-keys.json'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try: subprocess.run([ADB, '-s', SERIAL, 'uninstall', PACKAGE+'.test'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    except (OSError, subprocess.TimeoutExpired): pass
