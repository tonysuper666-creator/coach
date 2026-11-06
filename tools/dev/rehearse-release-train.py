#!/usr/bin/env python3
"""Isolated binary deployment/rollback on an explicitly supplied private SQLite copy.

No launchd, production controls, real bridge, mobile device or production writes.
The explicitly supplied old distribution is copied before use. All children finish in this call.
"""
import argparse
from contextlib import closing, contextmanager
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
import unicodedata
import urllib.request
import urllib.parse

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('candidate_smoke', ROOT / 'tools/dev/check-candidate-smoke.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
PROTECTED = {8790, 8791, 18791, 3790}
INSTALLED_ROLLBACK = 'c0c5bb82510694a481351d60733b8c98d6162bb8'


@contextmanager
def database(path, **options):
    with closing(sqlite3.connect(path, **options)) as connection:
        with connection:
            yield connection


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


def durable_fingerprint(db):
    """Protect every durable table, including future tables and binary values.

    Only projection_cache is disposable. Normalize the known v6 -> v7 document
    visibility migration on the backup; never ignore the new column on v7.
    Empty tables may be created by startup, but any inserted row changes this hash.
    """
    maximum = db.execute('select coalesce(max(seq),0) from events').fetchone()[0]
    digest = hashlib.sha256()
    tables = db.execute("select name from sqlite_master where type='table' and name!='projection_cache' order by name").fetchall()
    for (table,) in tables:
        quoted = '"' + table.replace('"', '""') + '"'
        columns = [row[1] for row in db.execute(f'pragma table_info({quoted})')]
        rows = []
        for row in db.execute(f'select * from {quoted}'):
            values = dict(zip(columns, row))
            if table == 'source_docs' and 'learner_visible' not in values:
                hidden = any(any(word in unicodedata.normalize('NFKC', str(values.get(field) or '')).lower()
                                 for word in ('answer', '答案', 'sealed')) for field in ('doc_id', 'title', 'kind'))
                values['learner_visible'] = int(values.get('tutor_visible', 1) != 0 and not hidden)
            encoded = [(key, ['blob', value.hex()] if isinstance(value, bytes) else ['value', value])
                       for key, value in sorted(values.items())]
            rows.append(json.dumps(encoded, ensure_ascii=False, separators=(',', ':')).encode())
        for row in sorted(rows):
            digest.update(json.dumps(table).encode() + b'\0' + row + b'\n')
    return maximum, digest.digest()


def restore_before_v7(data, backup, *, server_stopped):
    """Private rehearsal only; a post-migration write must make restore fail closed."""
    require(server_stopped, 'restore refused: server is running')
    current = data / 'coach.sqlite'
    with database(current) as db, database(backup.as_uri() + '?mode=ro', uri=True) as before:
        require(before.execute('pragma integrity_check').fetchone()[0] == 'ok', 'invalid migration backup')
        require(before.execute('pragma user_version').fetchone()[0] == 6, 'migration backup is not v6')
        require(db.execute('pragma user_version').fetchone()[0] == 7, 'restore source is not v7')
        require(durable_fingerprint(db) == durable_fingerprint(before), 'restore refused: durable writes after migration')
        db.execute('pragma wal_checkpoint(TRUNCATE)')
        before.backup(db)


def frame(ws, kind):
    result = ws.await_type(kind, 'msg.error')
    require(result['t'] == kind, 'isolated tutor returned an error')
    return result


def request(port, token, course, endpoint):
    path = '/v1/courses/' + urllib.parse.quote(course, safe='') + '/' + endpoint
    req = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                                 headers={'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop'})
    with urllib.request.urlopen(req, timeout=15) as response:
        require(response.status == 200, 'course readback failed')
        return json.load(response)


def client_probe(name, app, payload):
    probe = subprocess.run(['java', '-cp', str(ROOT / 'server/app/build/classes/kotlin/test') + os.pathsep + str(app / 'lib/*'),
                            'com.coach.server.' + name + 'Kt'], input=json.dumps(payload),
                           text=True, capture_output=True, timeout=45)
    require(probe.returncode == 0, 'client course/mistake/routing probe failed')
    return json.loads(probe.stdout)


def fingerprint(path):
    with database(path.as_uri() + '?mode=ro', uri=True) as db:
        return durable_fingerprint(db)


def readback(port, token, courses, client_app, data, expected=None, *, review_supported=False):
    """Use real endpoints and the selected distribution's real client projector.

    Course content stays in memory/stdin. Nothing from it is printed or saved.
    Only pre-existing KP threads are opened; no lesson or real-data write is sent.
    """
    calls = smoke.Bridge.calls
    opened = mistakes = wrong_cards = rejected = candidate_misroutes = legacy_misroutes = 0
    for course in courses:
        snapshot = request(port, token, course, 'snapshot')
        require(isinstance(snapshot.get('projection'), dict), 'missing course projection')
        today = request(port, token, course, 'today')
        require(isinstance(today, dict), 'missing today progress')
        events, after = [], 0
        while True:
            page = request(port, token, course, f'events?after={after}&limit=500')
            events.extend(page['events'])
            if not page.get('hasMore', False):
                break
            require(page['throughSeq'] > after, 'event paging did not advance')
            after = page['throughSeq']
        require(events == snapshot['events'], 'course event/snapshot disagreement')
        # The probe class comes from this checkout; all client/domain/protocol
        # libraries come from the binary actually selected for rollback.
        candidate_app = ROOT / 'server/app/build/install/app'
        payload = dict(events=events, **{k: v for k, v in (expected or {}).items() if k != 'events'})
        probes = {name: client_probe(name, app, dict(payload, expectLegacyMisroute=not review_supported))
                  for name, app in [('ReleaseTrainReadback', client_app), ('ReleaseTrainMistakeReadback', candidate_app)]}
        candidate_route = client_probe('ReleaseTrainReadback', candidate_app, dict(payload, expectLegacyMisroute=False))
        candidate_misroutes += candidate_route['continueLearningMisroutes']
        legacy_misroutes += probes['ReleaseTrainReadback']['continueLearningMisroutes']
        mistakes += probes['ReleaseTrainMistakeReadback']['mistakes']
        wrong_cards += probes['ReleaseTrainReadback']['wrongCards']
        contexts = {}
        for event in events:
            if event['type'] == 'kp_opened' and event['payload'].get('conceptIds'):
                contexts[event['threadId']] = event['payload']
        require(contexts or expected is None, 'candidate tutor context lost')
        for thread, context in contexts.items():
            ws = smoke.WS(port, 'desktop', token)
            try:
                before = fingerprint(data / 'coach.sqlite')
                ws.send(dict(t='tutor.open', courseId=course, threadId=thread, kpId=context['kpId'],
                             conceptIds=context['conceptIds'], title=context.get('title', ''),
                             review=context.get('via') == 'REVIEW'))
                if context.get('via') == 'REVIEW' and not review_supported:
                    result = ws.await_type('msg.error', 'tutor.state')
                    require(result['t'] == 'msg.error', 'rollback unexpectedly accepted a review context')
                    require(fingerprint(data / 'coach.sqlite') == before, 'rejected review open wrote durable data')
                    rejected += 1
                else:
                    frame(ws, 'tutor.state')
                    frame(ws, 'msg.history')
                    opened += 1
            finally:
                ws.close()
        if expected:
            by_id = {e['id']: e for e in events}
            require(all(by_id.get(e['id']) == e for e in expected['events']), 'candidate event payload changed')
            pending = {e['payload']['attemptId'] for e in events if e['type'] == 'question_posed'} - {
                e['payload']['attemptId'] for e in events if e['type'] in ('answer_submitted', 'attempt_voided')}
            require(pending == set(expected['retryAttempts']), 'candidate retry group lost')
    require(smoke.Bridge.calls == calls, 'readback unexpectedly called bridge')
    if expected:
        require(opened >= 1 and (review_supported or rejected >= 1), 'missing learning/review contract checks')
        require(candidate_misroutes == 0 and legacy_misroutes == (0 if review_supported else 1), 'client routing contract failed')
    return dict(courses=len(courses), tutorOpens=opened, rejectedReviewOpens=rejected, rejectedReviewWrites=0,
                candidateContinueLearningMisroutes=candidate_misroutes, selectedClientContinueLearningMisroutes=legacy_misroutes,
                trainClientMistakes=mistakes, rollbackClientWrongCards=wrong_cards, bridgeCalls=0)


def seed(db, phase):
    for i in range(6 if phase == 'candidate' else 3):
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
        frame(ws, 'tutor.state')
        ws.send(dict(common, t='msg.ask', text='继续'))
        frame(ws, 'msg.start')
        first = frame(ws, 'msg.delta')
        require(first.get('text') or first.get('delta'), 'no synthetic first delta')
        require(frame(ws, 'msg.end')['status'] == 'complete', 'synthetic lecture incomplete')
        deadline = time.monotonic() + 30
        while frame(ws, 'tutor.state')['state'] != 'AWAIT':
            require(time.monotonic() < deadline, 'synthetic lecture did not settle')
        ws.send(dict(common, t='msg.ask', text='继续'))
        group = frame(ws, 'tutor.cards')
        cards = group['cards']
        require(len(cards) == 3, 'synthetic quiz missing cards')
        answers = {c['attemptId']: c['letterMap'][c['questionId'] + '-0'] for c in cards}
        if phase == 'candidate':
            wrong = cards[-1]
            answers[wrong['attemptId']] = wrong['letterMap'][wrong['questionId'] + '-1']
        ws.send(dict(common, t='tutor.answer', groupId=group['groupId'], submissionId=f'ton1254-{phase}-submit', answers=answers))
        feedback = frame(ws, 'tutor.feedback')['grades']
        require(len(feedback) == 3, 'synthetic quiz missing grades')
        if phase == 'candidate':
            require(sum(g['score'] < g['max'] for g in feedback) == 1, 'candidate mistake was not recorded')
            common['threadId'] = f'review:ton1254-{phase}:ton1254-{phase}-kp'
            ws.send(dict(common, t='tutor.open', kpId=f'ton1254-{phase}-kp', conceptIds=['synthetic'],
                         title='Synthetic objective', review=True))
            frame(ws, 'tutor.state')
            ws.send(dict(common, t='tutor.retryMistakes'))
            retry = frame(ws, 'tutor.cards')['cards']
            require(len(retry) == 3 and not ({c['questionId'] for c in retry} & {c['questionId'] for c in cards}),
                    'retry did not select three new questions')
            return dict(wrongAttempt=wrong['attemptId'], retryAttempts=[c['attemptId'] for c in retry],
                        routingKp=f'ton1254-{phase}-kp', learningThread=f'ton1254-{phase}-thread', reviewThread=common['threadId'],
                        events=request(port, token, common['courseId'], 'snapshot')['events'])
    finally:
        ws.close()


def private_input(path):
    path = path.resolve()
    require(not path.is_relative_to((Path.home() / 'Coach').resolve()), 'private input must be outside the installed Coach tree')
    for name in ('coach.sqlite', 'device-token'):
        require((path / name).is_file() and not (path / name).is_symlink(), 'private input must contain regular database/token copies')
    return path


def installed_rollback(descriptor, expected_head):
    require(descriptor is not None, 'rollback requires an explicit private installation descriptor')
    require(descriptor.is_file() and not descriptor.is_symlink(), 'installation descriptor must be a regular private input')
    installed = json.loads(descriptor.read_text())
    require(installed.get('sourceHead') == expected_head, 'rollback head differs from the installed production version')
    require(expected_head == INSTALLED_ROLLBACK, 'no reviewed downgrade contract for this installed version')
    return expected_head


def run(out, private_data_dir, old_distribution, old_head, rollback_checkout=None, expected_rollback_head=None, installation=None):
    os.umask(0o077)
    source_data = private_input(private_data_dir)
    candidate = ROOT / 'server/app/build/install/app'
    old = old_distribution.resolve()
    require((candidate / 'bin/app').is_file() and (old / 'bin/app').is_file(), 'missing built or installed distribution')
    rollback = old
    rollback_head = old_head
    if rollback_checkout is not None:
        installed_rollback(installation, expected_rollback_head)
        rollback_checkout = rollback_checkout.resolve()
        rollback_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=rollback_checkout, text=True).strip()
        require(rollback_head == expected_rollback_head, 'rollback checkout differs from pinned head')
        require(not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=rollback_checkout, text=True).strip(),
                'rollback checkout has tracked changes')
        rollback = rollback_checkout / 'server/app/build/install/app'
        require((rollback / 'bin/app').is_file(), 'missing compatible rollback distribution')
    token_bytes = (source_data / 'device-token').read_bytes()
    token = token_bytes.decode().strip()
    bridge = smoke.http.server.ThreadingHTTPServer(('127.0.0.1', 0), smoke.Bridge)
    require(bridge.server_address[1] not in PROTECTED, 'protected bridge port')
    bridge_thread = threading.Thread(target=bridge.serve_forever)
    bridge_thread.start()
    process = None
    authenticated_starts = 0
    ports, pids, exits, startup_failures = [], [], [], []
    summary = {}
    try:
        with tempfile.TemporaryDirectory(prefix='coach-train-', dir='/tmp') as temporary:
            base = Path(temporary)
            data = base / 'data'
            data.mkdir(mode=0o700)
            (base / 'jvm-home').mkdir(mode=0o700)
            (base / 'jvm-tmp').mkdir(mode=0o700)
            with database((source_data / 'coach.sqlite').as_uri() + '?mode=ro', uri=True) as source:
                with database(data / 'coach.sqlite') as db:
                    source.backup(db)
            (data / 'device-token').write_bytes(token_bytes)
            (data / 'device-token').chmod(0o600)
            with database(data / 'coach.sqlite') as db:
                require(db.execute('pragma integrity_check').fetchone()[0] == 'ok', 'invalid private snapshot')
                maximum = db.execute('select coalesce(max(seq),0) from events').fetchone()[0]
                original = digest_events(db, maximum)
                devices = dict(db.execute('select device_id,token_sha256 from devices'))
                real_courses = [row[0] for row in db.execute("select distinct course_id from events where course_id!='__system__'")]
                require(real_courses, 'private copy has no course to read back')
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
                nonlocal process, authenticated_starts
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
                                authenticated_starts += 1
                                return port
                    except OSError:
                        time.sleep(.2)
                raise RuntimeError('isolated original-token readiness failed')

            def unchanged():
                require((data / 'device-token').read_bytes() == token_bytes, 'copied token changed')
                with database(data / 'coach.sqlite') as db:
                    require(db.execute('pragma integrity_check').fetchone()[0] == 'ok', 'copy integrity failed')
                    require(digest_events(db, maximum) == original, 'original ledger events changed')
                    current_devices = dict(db.execute('select device_id,token_sha256 from devices'))
                    require(all(current_devices.get(k) == v for k, v in devices.items()), 'paired device token hashes changed')

            def migration_window():
                nonlocal data
                require(rollback_checkout is not None, 'migration rehearsal needs the pinned baseline')
                require(process is None, 'migration copy requires stopped server')
                train_data = data
                stage = base / 'migration-data'
                stage.mkdir(mode=0o700)
                with database(train_data / 'coach.sqlite') as source, database(stage / 'coach.sqlite') as target:
                    source.backup(target)
                (stage / 'device-token').write_bytes(token_bytes)
                (stage / 'device-token').chmod(0o600)
                calls = smoke.Bridge.calls
                try:
                    data = stage
                    select('rollback')
                    start()
                    stop()
                    backups = list((stage / 'backups').glob('pre-V007-*/coach.sqlite'))
                    require(len(backups) == 1, 'missing unique automatic pre-v7 backup')
                    restore_before_v7(stage, backups[0], server_stopped=process is None)
                    select('old')
                    start()  # The actual old binary must accept the restored v6 copy.
                    stop()
                    unchanged()
                    select('rollback')
                    port = start()
                    ws = smoke.WS(port, 'desktop', token)
                    try:
                        ws.send(dict(t='tutor.open', courseId='ton1254-migration-write', threadId='ton1254-migration-write-thread',
                                     kpId='ton1254-migration-write-kp', conceptIds=['synthetic'], title='Synthetic migration write'))
                        frame(ws, 'tutor.state')
                    finally:
                        ws.close()
                    stop()
                    with database(stage / 'coach.sqlite') as db:
                        written = durable_fingerprint(db)
                    try:
                        restore_before_v7(stage, backups[0], server_stopped=process is None)
                    except RuntimeError as error:
                        require(str(error) == 'restore refused: durable writes after migration', 'unexpected migration refusal')
                    else:
                        raise RuntimeError('migration restore discarded new writes')
                    with database(stage / 'coach.sqlite') as db:
                        require(db.execute('pragma user_version').fetchone()[0] == 7 and durable_fingerprint(db) == written,
                                'refused migration restore changed the database')
                    unchanged()
                    require(smoke.Bridge.calls == calls, 'migration check called bridge')
                    return dict(noWriteRestore='old binary authenticated on restored v6', withWriteRestore='refused; v7 writes retained',
                                checks=['event max sequence', 'all durable table rows', 'device credentials'], bridgeCalls=0)
                finally:
                    stop()
                    data = train_data

            try:
                timings, schema_versions, readbacks = {}, {}, {}
                candidate_state = None
                migration = None
                for name, phase in [('old', 'old'), ('candidate', 'candidate')]:
                    print(json.dumps({'phase': phase, 'state': 'starting'}), flush=True)
                    began = time.monotonic()
                    select(name)
                    port = start()
                    result = exercise(port, token, phase)
                    if phase == 'candidate':
                        candidate_state = result
                    timings[phase] = round(time.monotonic() - began, 3)
                    require(timings[phase] < 300, 'isolated acceptance exceeded 300 seconds')
                    stop()
                    unchanged()
                    with database(data / 'coach.sqlite') as db:
                        schema_versions[phase] = db.execute('pragma user_version').fetchone()[0]
                    if phase == 'old' and rollback_checkout is not None:
                        migration = migration_window()
                began = time.monotonic()
                if rollback_checkout is not None:
                    # Keep the historical v6 binary incompatibility diagnostic as an additional check.
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
                readbacks['rollback'] = dict(candidate=readback(port, token, ['ton1254-candidate'], base / 'rollback', data, candidate_state),
                                             original=readback(port, token, real_courses, base / 'rollback', data))
                mocks = client_probe('ReleaseTrainMockReadback', base / 'candidate',
                                     dict(baseUrl=f'http://127.0.0.1:{port}', token=token, course='ton1254-candidate'))
                require(mocks == dict(status=404, clientError=True, completed=True), 'missing old-server mocks error')
                exercise(port, token, 'rollback')
                timings['rollback'] = round(time.monotonic() - began, 3)
                require(timings['rollback'] < 300, 'isolated rollback exceeded 300 seconds')
                stop()
                unchanged()
                with database(data / 'coach.sqlite') as db:
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
                readbacks['fault-recovery'] = dict(candidate=readback(port, token, ['ton1254-candidate'], base / 'rollback', data, candidate_state),
                                                   original=readback(port, token, real_courses, base / 'rollback', data))
                exercise(port, token, 'fault-recovery')
                timings['fault-recovery'] = round(time.monotonic() - began, 3)
                require(timings['fault-recovery'] < 300, 'isolated fault recovery exceeded 300 seconds')
                stop()
                unchanged()
                # Resume on this exact database: review data is retained during the server-only downgrade.
                began = time.monotonic()
                select('candidate')
                port = start()
                readbacks['forward-roll'] = dict(
                    candidate=readback(port, token, ['ton1254-candidate'], base / 'candidate', data, candidate_state, review_supported=True),
                    original=readback(port, token, real_courses, base / 'candidate', data, review_supported=True))
                calls = smoke.Bridge.calls
                ws = smoke.WS(port, 'desktop', token)
                try:
                    common = dict(courseId='ton1254-candidate', threadId=candidate_state['reviewThread'])
                    ws.send(dict(common, t='tutor.open', kpId=candidate_state['routingKp'], conceptIds=['synthetic'],
                                 title='Synthetic objective', review=True))
                    frame(ws, 'tutor.state')
                    frame(ws, 'msg.history')
                    ws.send(dict(common, t='tutor.retryMistakes'))
                    cards = frame(ws, 'tutor.cards')['cards']
                    require({c['attemptId'] for c in cards} == set(candidate_state['retryAttempts']), 'forward roll lost pending review cards')
                finally:
                    ws.close()
                require(smoke.Bridge.calls == calls, 'forward review unexpectedly called bridge')
                stop()
                unchanged()
                timings['forward-roll'] = round(time.monotonic() - began, 3)
                require(timings['forward-roll'] < 300, 'isolated forward roll exceeded 300 seconds')
                with database(data / 'coach.sqlite') as db:
                    kept = db.execute("select count(*) from events where course_id='ton1254-candidate'").fetchone()[0]
                    require(kept >= 26, 'candidate writes lost on rollback/forward roll')
                    review_cards = [json.loads(row[0]) for row in db.execute(
                        "select payload from events where course_id='ton1254-candidate' and thread_id=? and type='question_posed'",
                        (candidate_state['reviewThread'],))]
                    require(len(review_cards) == 3 and all(c.get('purpose') == 'RECALL' and c.get('review') is True for c in review_cards),
                            'forward review cards are not RECALL review attempts')
                    schema_versions['forward-roll'] = db.execute('pragma user_version').fetchone()[0]
                summary = dict(oldHead=old_head, candidateHead=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                               candidateSourceTrees={area: subprocess.check_output(['git', 'rev-parse', 'HEAD:' + area], cwd=ROOT, text=True).strip() for area in ('server', 'shared')},
                               rehearsalSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                               rollbackHead=rollback_head, rollbackTargetFromInstallation=True, rollbackScope='server only; keep candidate client',
                               oldApkReinstallAllowed=False, historicalV6BinaryRejected=rollback_checkout is not None,
                               originalRollbackRejected=rollback_checkout is not None, mocksOnRollback=mocks,
                               forwardReviewRecallCards=len(review_cards), forwardRollSameDatabase=True,
                               firstMigration=migration, courseReadbacks=readbacks, candidateWrongAnswers=1, candidateRetryNewCards=3,
                               startupFailures=startup_failures, schemaVersions=schema_versions,
                               distributionSha256=hashes, phases=['old', 'candidate'] + (['historical-v6-rollback-refused'] if rollback_checkout is not None else []) + ['rollback', 'failed-start', 'fault-recovery', 'forward-roll'],
                               originalTokenAcceptedStarts=authenticated_starts, syntheticLectureQuizPasses=4, candidateEventsRetained=kept,
                               originalEventsUnchanged=True, pairedTokenHashesUnchanged=True, copyIntegrity='ok',
                               startupAndLectureQuizSeconds=timings, databaseRestoredOnRollback=False,
                               inputKind='explicit private copy', productionConnections=0, productionControlCalls=0,
                               protectedPortsUsed=False, realTutorCalls=0, launchdRehearsed=False)
            finally:
                stop()
    finally:
        bridge.shutdown()
        bridge_thread.join()
        bridge.server_close()
    require((source_data / 'device-token').read_bytes() == token_bytes, 'private input token changed concurrently')
    for port in ports + [bridge.server_address[1]]:
        with socket.socket() as sock:
            require(sock.connect_ex(('127.0.0.1', port)) != 0, 'isolated listener survived cleanup')
    summary.update(childProcessesReaped=len(pids), childExits=exits, isolatedListenersRemaining=0, privateCopyRemoved=True)
    out.write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path, help='sanitized metadata report; no private data')
    parser.add_argument('--private-data-dir', required=True, type=Path, help='previously prepared private database/token copies; never the live dataDir')
    parser.add_argument('--old-distribution', required=True, type=Path, help='built pre-v7 app distribution')
    parser.add_argument('--old-head', required=True, help='source commit of the old distribution')
    parser.add_argument('--rollback-checkout', type=Path, help='clean checkout with a built compatible rollback distribution')
    parser.add_argument('--rollback-head', help='exact full commit of the compatible rollback checkout')
    parser.add_argument('--rollback-installation', required=True, type=Path, help='explicit private copy of the pre-switch installation descriptor')
    args = parser.parse_args()
    if not args.rollback_checkout or not args.rollback_head:
        parser.error('--rollback-checkout and --rollback-head are required for the installed downgrade contract')
    run(args.out, args.private_data_dir, args.old_distribution, args.old_head, args.rollback_checkout, args.rollback_head, args.rollback_installation)
