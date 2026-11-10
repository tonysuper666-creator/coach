#!/usr/bin/env python3
"""Opt-in real subscription CLI evidence using only a disposable synthetic course.

Run via tools/dev/heavy after :server:app:installDist. --bridge-python must be a
venv with tools/bridge/requirements.txt. Does not measure Android UI latency.
"""
import argparse
import datetime
import hashlib
import http.server
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[2]
spec = spec_from_file_location('notes_smoke', ROOT / 'tools/dev/check-mainline-notes.py')
notes_smoke = module_from_spec(spec)
spec.loader.exec_module(notes_smoke)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bridge-python', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False)
    rows = []
    children, handles = [], []
    with tempfile.TemporaryDirectory(prefix='coach-reader-synthetic-') as temporary:
        base = Path(temporary)
        env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))
               and k not in {'OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS'}}
        port, bridge_port = notes_smoke.smoke.free_port(), notes_smoke.smoke.free_port()
        assert port != bridge_port and not {port, bridge_port} & notes_smoke.FORBIDDEN
        data = base / 'data'
        fixture = notes_smoke.fixture
        fixture.write_pack(base / 'mock/mcq-mainline')
        quote = 'Synthetic pears grow in the orchard.'
        (base / 'mock/mcq-mainline/W01-notes.json').write_text(json.dumps(dict(schema_version=1, week=1,
            sections=[dict(section_id='W01-basics', title='Synthetic basics', body=quote,
                           fact_ids=['F1', 'F2', 'F3'], sources=['Synthetic lecture 1'])])))
        token = ''

        def launch(name, argv, variables):
            log = (base / (name + '.log')).open('ab')
            handles.append(log)
            child = subprocess.Popen(list(map(str, argv)), env=variables, stdout=log, stderr=subprocess.STDOUT)
            children.append(child)
            return child

        def request(path, body=None):
            req = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                data=None if body is None else json.dumps(body).encode(), headers={
                    'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop', 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=105) as response:
                return json.load(response)

        raw_answers = []

        class BridgeEvidence(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                # This private bridge receives only the synthetic source seeded below. Record
                # generated text, never request headers, auth material, or private source files.
                req = urllib.request.Request(f'http://127.0.0.1:{bridge_port}' + self.path,
                    data=self.rfile.read(int(self.headers['Content-Length'])),
                    headers={k: v for k, v in self.headers.items() if k.lower() not in {'host', 'connection'}})
                answer = []
                with urllib.request.urlopen(req, timeout=100) as response:
                    self.send_response(response.status)
                    self.send_header('Content-Type', response.headers['Content-Type'])
                    self.end_headers()
                    for line in response:
                        if line.startswith(b'data: ') and line.strip() != b'data: [DONE]':
                            frame = json.loads(line[6:])
                            for choice in frame.get('choices', []):
                                answer.append(choice.get('delta', {}).get('content') or '')
                        self.wfile.write(line)
                        self.wfile.flush()
                raw_answers.append(''.join(answer))

        proxy = http.server.ThreadingHTTPServer(('127.0.0.1', 0), BridgeEvidence)
        assert proxy.server_address[1] not in notes_smoke.FORBIDDEN
        proxy_thread = threading.Thread(target=proxy.serve_forever)
        proxy_thread.start()
        server_env = dict(env, COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'),
            COACH_LOG_DIR=str(base / 'logs'), COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'),
            COACH_PACKS_DIR=str(base / 'packs'), COACH_MOCK_DIR=str(base / 'mock'), COACH_GENERATED_DIR=str(base / 'generated'),
            COACH_TAIL_HOST='', COACH_PORT=str(port), COACH_TRAFFIC='test', COACH_BRIDGE_URL=f'http://127.0.0.1:{proxy.server_address[1]}')

        def start_server():
            nonlocal token
            child = launch('server', [notes_smoke.smoke.BINARY], server_env)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                assert child.poll() is None, 'Synthetic candidate exited'
                try:
                    token = (data / 'device-token').read_text().strip()
                    request('/v1/hello')
                    return child
                except OSError:
                    time.sleep(.2)
            raise TimeoutError('Synthetic candidate readiness')

        try:
            bridge = launch('bridge', [args.bridge_python.absolute(), ROOT / 'tools/bridge/subscription_bridge.py'],
                dict(env, BRIDGE_PORT=str(bridge_port), COACH_BRIDGE_HOME=str(base / 'bridge')))
            deadline = time.monotonic() + 20
            while True:
                assert bridge.poll() is None, 'Private bridge exited'
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{bridge_port}/health?scope=process', timeout=2) as response:
                        assert response.status == 200
                    break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Private bridge readiness') from None
                    time.sleep(.2)
            server = start_server()
            notes_smoke.stop(server)
            now = int(time.time() * 1000)
            payload = dict(course=dict(id=fixture.COURSE, name=fixture.CODE + ' Synthetic orchard', tz='Europe/London'),
                           knowledgePoints=[dict(id=f'ton1358-kp-{k}', title=f'Synthetic point {k}', week=1) for k in 'abc'])
            event = dict(seq=1, id='reader-live-outline', courseId=fixture.COURSE, type='outline_revised', schema=1,
                recordedAt=now, occurredAt=now, deviceId='desktop', actor='SYSTEM', effectIndex=0, idemKey='reader-live-outline', payload=payload)
            with sqlite3.connect(data / 'coach.sqlite') as db:
                assert db.execute('select count(*) from events').fetchone()[0] == 0
                db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                    (1, event['id'], fixture.COURSE, event['type'], 1, now, now, 'desktop', 'SYSTEM', 0, event['idemKey'], json.dumps(payload), json.dumps(event)))
                db.execute('insert into source_docs(doc_id,course_id,kind,title,tutor_visible,learner_visible) values(?,?,?,?,?,?)',
                    ('reader-synthetic', fixture.COURSE, 'textbook', 'Synthetic orchard', 1, 1))
                db.execute('insert into source_pages(doc_id,pdf_page,text,text_sha256) values(?,?,?,?)',
                    ('reader-synthetic', 4, quote, hashlib.sha256(quote.encode()).hexdigest()))
            server = start_server()
            route = f'/v1/courses/{fixture.COURSE}/mainline/1'
            week = request(route)
            assert week['notes'], week.get('notesError')
            section = week['notes'][0]
            for kind in ('explain', 'example'):
                started = time.monotonic()
                result = request(route + '/explain', dict(requestId=uuid.uuid4().hex, kind=kind,
                    sectionId=section['section_id'], version=section['content_version'], start=0, end=len(quote), quote=quote))
                passed = bool(result.get('sources')) and all(s['docId'] == 'reader-synthetic' and s['pdfPage'] == 4 for s in result['sources'])
                rows.append(dict(kind=kind, utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    roundTripMs=round((time.monotonic()-started)*1000, 3), passed=passed, response=result,
                    generatedText=raw_answers[-1] if raw_answers else None))
                (args.output / 'report.json').write_text(json.dumps(dict(syntheticOnly=True, productionRead=False,
                    channel='real server route -> isolated subscription CLI bridge; no Android UI timing', samples=rows), ensure_ascii=False, indent=2))
                print(f'{kind}: ' + ('PASS' if passed else 'FAIL'), flush=True)
            notes_smoke.stop(server)
            with sqlite3.connect(data / 'coach.sqlite') as db:
                calls = [dict(zip(('task', 'requestedModel', 'servedBy', 'ttftMs', 'durationMs', 'status', 'physicalCalls'), row))
                    for row in db.execute('select task,requested_model,served_by,ttft_ms,dur_ms,status,physical_calls from ai_calls')]
            (args.output / 'calls.json').write_text(json.dumps(calls, indent=2))
            assert len(calls) == 2 and all(call['task'] == 'READER_SELECTION' and call['physicalCalls'] >= 1 for call in calls)
            assert all(row['passed'] for row in rows), 'Live reader answer did not pass source citation gate'
        finally:
            for child in reversed(children):
                notes_smoke.stop(child)
            proxy.shutdown()
            proxy.server_close()
            proxy_thread.join()
            for handle in handles:
                handle.close()


if __name__ == '__main__':
    main()
