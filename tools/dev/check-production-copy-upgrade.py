#!/usr/bin/env python3
"""Exercise a candidate against a private SQLite backup, never the live service."""
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

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('candidate_smoke', ROOT / 'tools/dev/check-candidate-smoke.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
PROD = Path.home() / 'Coach/data'
INSTALLATION = Path.home() / 'Coach/service/installation.json'
OLD_HEAD = '5ef99895d9fad277bfbdbb97b64eda6a551242e9'
CANDIDATE_HEAD = '6e5a704cc526072a336ddae6056e7459bbdb9f62'
CANDIDATE_PATHS = ['server', 'shared', 'build-logic', 'build.gradle.kts', 'settings.gradle.kts', 'gradle']


def main():
    os.umask(0o077)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    for revisions in ([CANDIDATE_HEAD, 'HEAD'], []):
        result = subprocess.run(['git', 'diff', '--quiet', *revisions, '--', *CANDIDATE_PATHS], cwd=ROOT)
        if result.returncode:
            raise RuntimeError('Candidate build sources differ from the independently reviewed 6e5a704')
    if subprocess.run(['git', 'diff', '--cached', '--quiet', '--', *CANDIDATE_PATHS], cwd=ROOT).returncode:
        raise RuntimeError('Candidate build sources have staged changes')
    if json.loads(INSTALLATION.read_text())['sourceHead'] != OLD_HEAD:
        raise RuntimeError('Production source head changed; review the new baseline')
    if not smoke.BINARY.is_file():
        raise RuntimeError('Build the candidate using tools/dev/heavy first')
    bridge = smoke.http.server.ThreadingHTTPServer(('127.0.0.1', 0), smoke.Bridge)
    bridge_port = bridge.server_address[1]
    if bridge_port in smoke.FORBIDDEN | {3790}:
        bridge.server_close()
        raise RuntimeError('Refusing protected bridge port')
    bridge_thread = threading.Thread(target=bridge.serve_forever)
    bridge_thread.start()
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix='coach-upgrade-copy-') as temporary:
            base = Path(temporary)
            data = base / 'data'
            data.mkdir(mode=0o700)
            with sqlite3.connect((PROD / 'coach.sqlite').resolve().as_uri() + '?mode=ro', uri=True) as source:
                with sqlite3.connect(data / 'coach.sqlite') as copy:
                    source.backup(copy)
            shutil.copyfile(PROD / 'device-token', data / 'device-token')
            os.chmod(data / 'device-token', 0o600)
            with sqlite3.connect(data / 'coach.sqlite') as db:
                if db.execute('pragma integrity_check').fetchone()[0] != 'ok':
                    raise RuntimeError('Backup integrity check failed')
                legacy = db.execute("select thread_id, course_id, anchor_id from threads where kind='KP' and thread_id like 'qa:%'").fetchall()
                if len(legacy) != 3:
                    raise RuntimeError('Legacy thread count changed; review current data')
                contexts = []
                for thread_id, course_id, kp_id in legacy:
                    row = db.execute("select payload from events where type='kp_opened' and thread_id=? order by seq limit 1", (thread_id,)).fetchone()
                    if row is None:
                        raise RuntimeError('Existing legacy thread has no opening event')
                    payload = json.loads(row[0])
                    contexts.append(dict(threadId=thread_id, courseId=course_id, kpId=kp_id,
                                         conceptIds=payload.get('conceptIds', []), title=payload.get('title', '')))
                db.execute("update jobs set state='cancelled',last_error='isolated_rehearsal' where state in ('queued','running')")
                for i in range(3):
                    q = f'ton1175-copy-q{i}'
                    options = [{'optionId':f'{q}-{j}','text':f'Synthetic option {i}-{j}'} for j in range(4)]
                    db.execute('insert into questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) values(?,?,?,?,?,?,?,?)',
                        (q,'ton1175-copy-course','SINGLE_CHOICE',f'Synthetic question {i}',json.dumps(options),'COACH',f'ton1175-copy-fingerprint-{i}','VERIFIED'))
                    db.execute('insert into answer_keys(question_id,key_json) values(?,?)',(q,json.dumps({'correctOptionId':f'{q}-0'})))
                    db.execute('insert into question_kp(question_id,kp_id,is_primary) values(?,?,1)',(q,'ton1175-copy-kp'))
            port = smoke.free_port()
            while port == 3790:
                port = smoke.free_port()
            env = {k:v for k,v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
            env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data/'device-token'),
                COACH_LOG_DIR=str(base/'logs'), COACH_STATE_DIR=str(base/'state'), COACH_RUN_DIR=str(base/'run'),
                COACH_PACKS_DIR=str(base/'packs'), COACH_TAIL_HOST='', COACH_PORT=str(port),
                COACH_TRAFFIC='test', COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')
            token = (data/'device-token').read_text().strip()
            log = (base/'server.log').open('wb')
            try:
                process = subprocess.Popen([str(smoke.BINARY)], env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 40
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Candidate exited during startup')
                    req = urllib.request.Request(f'http://127.0.0.1:{port}/v1/hello',
                        headers={'Authorization':'Bearer '+token,'X-Coach-Device':'desktop'})
                    try:
                        with urllib.request.urlopen(req, timeout=2) as response:
                            if response.status == 200:
                                break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise RuntimeError('Original desktop token was not accepted by isolated candidate')
                        time.sleep(.2)
                for context in contexts:
                    ws = smoke.WS(port,'desktop',token)
                    try:
                        ws.send(dict(context,t='tutor.open'))
                        state = ws.await_type('tutor.state','msg.error')
                        if state.get('t') != 'tutor.state' or state.get('threadId') != context['threadId']:
                            raise RuntimeError('An existing legacy thread could not be opened')
                    finally:
                        ws.close()
                ws = smoke.WS(port,'desktop',token)
                try:
                    common = {'threadId':'ton1175-copy-thread','courseId':'ton1175-copy-course'}
                    ws.send(dict(common,t='tutor.open',kpId='ton1175-copy-kp',conceptIds=['synthetic'],title='Synthetic objective'))
                    if ws.await_type('tutor.state','msg.error')['t'] != 'tutor.state':
                        raise RuntimeError('Synthetic tutor could not be opened')
                    ws.send(dict(common,t='msg.ask',text='继续'))
                    ws.await_type('msg.start')
                    first = ws.await_type('msg.delta')
                    if not (first.get('text') or first.get('delta')) or ws.await_type('msg.end').get('status') != 'complete':
                        raise RuntimeError('Synthetic lecture failed')
                    while ws.await_type('tutor.state').get('state') != 'AWAIT':
                        pass
                    ws.send(dict(common,t='msg.ask',text='继续'))
                    group = ws.await_type('tutor.cards')
                    cards = group['cards']
                    if len(cards) != 3:
                        raise RuntimeError('Synthetic quiz did not provide three cards')
                    answers = {card['attemptId']:card['letterMap'][card['questionId']+'-0'] for card in cards}
                    ws.send(dict(common,t='tutor.answer',groupId=group['groupId'],submissionId='ton1175-copy-submit',answers=answers))
                    feedback = ws.await_type('tutor.feedback')
                    if len(feedback['grades']) != 3:
                        raise RuntimeError('Synthetic quiz did not produce three grades')
                finally:
                    ws.close()
                with sqlite3.connect(data/'coach.sqlite') as db:
                    if db.execute('pragma integrity_check').fetchone()[0] != 'ok':
                        raise RuntimeError('Rehearsal copy failed integrity check')
                print(json.dumps({'candidateHead':CANDIDATE_HEAD,'harnessHead':head,'productionHead':OLD_HEAD,'productionWrites':0,
                    'snapshot':'sqlite backup from readonly production connection','originalDesktopHello':200,
                    'legacyThreadsOpened':len(contexts),'lecture':'synthetic bridge complete',
                    'quizGrades':3,'protectedPortsUsed':False,'realTutorCalls':0},indent=2))
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                log.close()
    finally:
        bridge.shutdown()
        bridge_thread.join()
        bridge.server_close()


if __name__ == '__main__':
    main()
