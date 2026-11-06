#!/usr/bin/env python3
"""Isolated binary deployment/rollback on a readonly production SQLite backup.

No launchd, production controls, real bridge, mobile device or production writes.
The installed distribution is copied before use. All children finish in this call.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('candidate_smoke', ROOT / 'tools/dev/check-candidate-smoke.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
PROTECTED = {8790, 8791, 18791, 3790}


def require(value, message):
    if not value:
        raise RuntimeError(message)


def digest_events(db, maximum):
    digest = hashlib.sha256()
    for row in db.execute('select * from events where seq<=? order by seq', (maximum,)):
        digest.update(json.dumps(row, ensure_ascii=False, separators=(',', ':')).encode())
        digest.update(b'\n')
    return digest.digest()


def distribution_digest(app):
    digest = hashlib.sha256()
    for path in sorted(p for p in app.rglob('*') if p.is_file()):
        digest.update(str(path.relative_to(app)).encode())
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def seed(db, phase):
    for i in range(3):
        q = f'ton1254-{phase}-q{i}'
        options = [{'optionId': f'{q}-{j}', 'text': f'Synthetic option {j}'} for j in range(4)]
        db.execute('insert into questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) values(?,?,?,?,?,?,?,?)',
                   (q, f'ton1254-{phase}', 'SINGLE_CHOICE', f'Synthetic question {i}', json.dumps(options), 'COACH', q, 'VERIFIED'))
        db.execute('insert into answer_keys(question_id,key_json) values(?,?)', (q, json.dumps({'correctOptionId': f'{q}-0'})))
        db.execute('insert into question_kp(question_id,kp_id,is_primary) values(?,?,1)', (q, f'ton1254-{phase}-kp'))


def exercise(port, token, phase):
    ws = smoke.WS(port, 'desktop', token)
    try:
        common = {'threadId': f'ton1254-{phase}-thread', 'courseId': f'ton1254-{phase}'}
        ws.send(dict(common, t='tutor.open', kpId=f'ton1254-{phase}-kp', conceptIds=['synthetic'], title='Synthetic objective'))
        require(ws.await_type('tutor.state', 'msg.error')['t'] == 'tutor.state', 'synthetic tutor open failed')
        ws.send(dict(common, t='msg.ask', text='继续'))
        ws.await_type('msg.start')
        first = ws.await_type('msg.delta')
        require(first.get('text') or first.get('delta'), 'no synthetic first delta')
        require(ws.await_type('msg.end')['status'] == 'complete', 'synthetic lecture incomplete')
        deadline = time.monotonic() + 30
        while ws.await_type('tutor.state')['state'] != 'AWAIT':
            require(time.monotonic() < deadline, 'synthetic lecture did not settle')
        ws.send(dict(common, t='msg.ask', text='继续'))
        group = ws.await_type('tutor.cards')
        cards = group['cards']
        require(len(cards) == 3, 'synthetic quiz missing cards')
        answers = {c['attemptId']: c['letterMap'][c['questionId'] + '-0'] for c in cards}
        ws.send(dict(common, t='tutor.answer', groupId=group['groupId'], submissionId=f'ton1254-{phase}-submit', answers=answers))
        require(len(ws.await_type('tutor.feedback')['grades']) == 3, 'synthetic quiz missing grades')
    finally:
        ws.close()


def run(out, rollback_checkout=None, expected_rollback_head=None):
    os.umask(0o077)
    installation = Path.home() / 'Coach/service/installation.json'
    record = installation.read_bytes()
    installed = json.loads(record)
    production = Path(installed['dataDir']).resolve()
    candidate = ROOT / 'server/app/build/install/app'
    old = Path(installed['root']) / 'app'
    require((candidate / 'bin/app').is_file() and (old / 'bin/app').is_file(), 'missing built or installed distribution')
    rollback = old
    rollback_head = installed['sourceHead']
    if rollback_checkout is not None:
        rollback_checkout = rollback_checkout.resolve()
        rollback_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=rollback_checkout, text=True).strip()
        require(rollback_head == expected_rollback_head, 'rollback checkout differs from pinned head')
        require(not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=rollback_checkout, text=True).strip(),
                'rollback checkout has tracked changes')
        rollback = rollback_checkout / 'server/app/build/install/app'
        require((rollback / 'bin/app').is_file(), 'missing compatible rollback distribution')
    token_bytes = (production / 'device-token').read_bytes()
    token = token_bytes.decode().strip()
    bridge = smoke.http.server.ThreadingHTTPServer(('127.0.0.1', 0), smoke.Bridge)
    require(bridge.server_address[1] not in PROTECTED, 'protected bridge port')
    bridge_thread = threading.Thread(target=bridge.serve_forever)
    bridge_thread.start()
    process = None
    ports, pids, exits, startup_failures = [], [], [], []
    summary = {}
    try:
        with tempfile.TemporaryDirectory(prefix='coach-train-', dir='/tmp') as temporary:
            base = Path(temporary)
            data = base / 'data'
            data.mkdir(mode=0o700)
            (base / 'jvm-home').mkdir(mode=0o700)
            (base / 'jvm-tmp').mkdir(mode=0o700)
            with sqlite3.connect((production / 'coach.sqlite').as_uri() + '?mode=ro', uri=True) as source:
                with sqlite3.connect(data / 'coach.sqlite') as db:
                    source.backup(db)
            (data / 'device-token').write_bytes(token_bytes)
            (data / 'device-token').chmod(0o600)
            with sqlite3.connect(data / 'coach.sqlite') as db:
                require(db.execute('pragma integrity_check').fetchone()[0] == 'ok', 'invalid production snapshot')
                maximum = db.execute('select coalesce(max(seq),0) from events').fetchone()[0]
                original = digest_events(db, maximum)
                devices = dict(db.execute('select device_id,token_sha256 from devices'))
                # Cancel only copied work; no real content may be sent to any bridge.
                db.execute("update jobs set state='cancelled', last_error='isolated_release_train' where state in ('queued','running')")
                for phase in ('old', 'candidate', 'rollback', 'fault-recovery'):
                    seed(db, phase)
            for name, source in [('old', old), ('candidate', candidate), ('rollback', rollback)]:
                shutil.copytree(source, base / name)
            hashes = {name: distribution_digest(base / name) for name in ('old', 'candidate', 'rollback')}
            pointer = base / 'current'

            def select(name):
                next_link = base / 'next'
                next_link.symlink_to(name, target_is_directory=True)
                os.replace(next_link, pointer)

            def stop():
                nonlocal process
                if process is not None:
                    forced = False
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=20)
                        except subprocess.TimeoutExpired:
                            forced = True
                            process.kill()
                            process.wait(timeout=5)
                    exits.append({'exitCode': process.returncode, 'forcedKill': forced})
                    process = None

            def start():
                nonlocal process
                port = smoke.free_port()
                while port in PROTECTED or port in ports:
                    port = smoke.free_port()
                ports.append(port)
                env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))
                       and k not in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
                env.update(COACH_MANAGE_SERVICES='0', COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'),
                           COACH_LOG_DIR=str(base / 'logs'), COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'),
                           COACH_ACTIVITY_SOCKET=str(base / 'activity.sock'), COACH_PACKS_DIR=str(base / 'empty-packs'),
                           COACH_TAIL_HOST='', COACH_PORT=str(port), COACH_SOURCE_LOOKUP='false', COACH_TRAFFIC='test',
                           COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge.server_address[1]}', COACH_MATHSVC=str(base / 'absent-math.mjs'),
                           JAVA_OPTS=f'-Duser.home={base}/jvm-home -Djava.io.tmpdir={base}/jvm-tmp')
                with (base / 'server.log').open('ab') as log:
                    log_offset = log.tell()
                    process = subprocess.Popen([str(pointer / 'bin/app')], cwd=base, env=env, stdout=log, stderr=subprocess.STDOUT)
                pids.append(process.pid)
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        # Only exception classes and fixed diagnostic labels may leave the private copy.
                        with (base / 'server.log').open('rb') as log:
                            log.seek(log_offset)
                            text = log.read().decode(errors='replace')
                        classes = re.findall(r'(?:Caused by: |Exception in thread "[^"]+" )([\w.$]+)', text)
                        hints = [hint for hint in ('Could not find or load main class', 'UnsupportedClassVersionError',
                                                  'JAVA_HOME is set to an invalid directory', 'Permission denied',
                                                  'no such table', 'no such column', 'UNIQUE constraint failed',
                                                  'Database schema is newer than this server',
                                                  'Another Coach server owns this data directory') if hint in text]
                        failure = {'release': pointer.readlink().name, 'exitCode': process.returncode, 'classes': classes, 'hints': hints}
                        startup_failures.append(failure)
                        print(json.dumps({'startupFailure': failure}), flush=True)
                        raise RuntimeError('isolated release exited during startup')
                    req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/hello',
                                                 headers={'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop'})
                    try:
                        with urllib.request.urlopen(req, timeout=2) as response:
                            if response.status == 200:
                                return port
                    except OSError:
                        time.sleep(.2)
                raise RuntimeError('isolated original-token readiness failed')

            def unchanged():
                require((data / 'device-token').read_bytes() == token_bytes, 'copied token changed')
                with sqlite3.connect(data / 'coach.sqlite') as db:
                    require(db.execute('pragma integrity_check').fetchone()[0] == 'ok', 'copy integrity failed')
                    require(digest_events(db, maximum) == original, 'original ledger events changed')
                    current_devices = dict(db.execute('select device_id,token_sha256 from devices'))
                    require(all(current_devices.get(k) == v for k, v in devices.items()), 'paired device token hashes changed')

            try:
                timings, schema_versions = {}, {}
                for name, phase in [('old', 'old'), ('candidate', 'candidate')]:
                    print(json.dumps({'phase': phase, 'state': 'starting'}), flush=True)
                    began = time.monotonic()
                    select(name)
                    port = start()
                    exercise(port, token, phase)
                    timings[phase] = round(time.monotonic() - began, 3)
                    require(timings[phase] < 300, 'isolated acceptance exceeded 300 seconds')
                    stop()
                    unchanged()
                    with sqlite3.connect(data / 'coach.sqlite') as db:
                        schema_versions[phase] = db.execute('pragma user_version').fetchone()[0]
                began = time.monotonic()
                if rollback_checkout is not None:
                    # Demonstrate the installed binary's real incompatibility before using the compatible baseline.
                    select('old')
                    try:
                        start()
                    except RuntimeError:
                        require(startup_failures and startup_failures[-1]['release'] == 'old'
                                and startup_failures[-1]['exitCode'] == 1
                                and 'Database schema is newer than this server' in startup_failures[-1]['hints'],
                                'unexpected original rollback failure')
                    else:
                        raise RuntimeError('installed binary now accepts this schema; review rollback plan')
                    finally:
                        stop()
                    unchanged()
                select('rollback')
                port = start()
                exercise(port, token, 'rollback')
                timings['rollback'] = round(time.monotonic() - began, 3)
                require(timings['rollback'] < 300, 'isolated rollback exceeded 300 seconds')
                stop()
                unchanged()
                with sqlite3.connect(data / 'coach.sqlite') as db:
                    schema_versions['rollback'] = db.execute('pragma user_version').fetchone()[0]
                # A failed release must recover the same data, not restore an older snapshot.
                began = time.monotonic()
                broken = base / 'broken/bin'
                broken.mkdir(parents=True)
                (broken / 'app').write_text('#!/bin/sh\nexit 71\n')
                (broken / 'app').chmod(0o700)
                select('broken')
                try:
                    start()
                except RuntimeError:
                    require(process.poll() == 71, 'unexpected injected failure')
                else:
                    raise RuntimeError('fault injection unexpectedly ready')
                finally:
                    stop()
                select('rollback')
                port = start()
                exercise(port, token, 'fault-recovery')
                timings['fault-recovery'] = round(time.monotonic() - began, 3)
                require(timings['fault-recovery'] < 300, 'isolated fault recovery exceeded 300 seconds')
                stop()
                unchanged()
                with sqlite3.connect(data / 'coach.sqlite') as db:
                    kept = db.execute("select count(*) from events where course_id='ton1254-candidate'").fetchone()[0]
                    require(kept > 0, 'candidate writes lost on rollback')
                summary = dict(productionHead=installed['sourceHead'], candidateHead=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                               candidateSourceTrees={area: subprocess.check_output(['git', 'rev-parse', 'HEAD:' + area], cwd=ROOT, text=True).strip() for area in ('server', 'shared')},
                               rehearsalSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                               rollbackHead=rollback_head, originalRollbackRejected=rollback_checkout is not None,
                               startupFailures=startup_failures, schemaVersions=schema_versions,
                               distributionSha256=hashes, phases=['old', 'candidate'] + (['original-rollback-refused'] if rollback_checkout is not None else []) + ['rollback', 'failed-start', 'fault-recovery'],
                               originalTokenAcceptedStarts=4, syntheticLectureQuizPasses=4, candidateEventsRetained=kept,
                               originalEventsUnchanged=True, pairedTokenHashesUnchanged=True, copyIntegrity='ok',
                               startupAndLectureQuizSeconds=timings, databaseRestoredOnRollback=False,
                               productionConnections='readonly SQLite backup only', productionControlCalls=0,
                               protectedPortsUsed=False, realTutorCalls=0, launchdRehearsed=False)
            finally:
                stop()
    finally:
        bridge.shutdown()
        bridge_thread.join()
        bridge.server_close()
    require(installation.read_bytes() == record, 'installation changed concurrently; baseline needs review')
    require((production / 'device-token').read_bytes() == token_bytes, 'production token changed concurrently')
    for port in ports + [bridge.server_address[1]]:
        with socket.socket() as sock:
            require(sock.connect_ex(('127.0.0.1', port)) != 0, 'isolated listener survived cleanup')
    summary.update(childProcessesReaped=len(pids), childExits=exits, isolatedListenersRemaining=0, privateCopyRemoved=True)
    out.write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path, help='sanitized metadata report; no private data')
    parser.add_argument('--rollback-checkout', type=Path, help='clean checkout with a built compatible rollback distribution')
    parser.add_argument('--rollback-head', help='exact full commit of the compatible rollback checkout')
    args = parser.parse_args()
    if bool(args.rollback_checkout) != bool(args.rollback_head):
        parser.error('--rollback-checkout and --rollback-head must be provided together')
    run(args.out, args.rollback_checkout, args.rollback_head)
