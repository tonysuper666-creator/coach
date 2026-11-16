#!/usr/bin/env python3
"""Real subscription check of ONLY SavedBookBindingTest's self-authored fixture exports.

Build :server:app:installDist and run SavedBookBindingTest on Windows first.
Never pass a user library. Uses disposable server/bridge state and random loopback ports.
"""
import argparse
from contextlib import closing
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

from import_local_book import import_local_book

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('notes_smoke', ROOT / 'tools/dev/check-mainline-notes.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bridge-python', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--automatic', action='store_true', help='Exercise first-request registration without catalog or course grants')
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False)
    fixtures = ROOT / 'desktopApp/build/screens/saved-book-binding'
    samples = json.loads((fixtures / 'samples.json').read_text())
    assert {s['format'] for s in samples} == {'txt', 'docx', 'pptx', 'png'}, 'Run the fixture export on Windows'
    # Verify every input against the committed self-authored fixtures; do not accept private libraries.
    names = {'txt': 'formats/gbk.txt', 'docx': 'formats/lesson.docx', 'pptx': 'formats/lesson.pptx',
             'png': 'ocr/01-clean-serif.png'}
    resources = ROOT / 'desktopApp/src/test/resources/books'
    for sample in samples:
        assert sample['book'] == hashlib.sha256((resources / names[sample['format']]).read_bytes()).hexdigest()
    children, handles, rows = [], [], []
    with tempfile.TemporaryDirectory(prefix='coach-saved-book-live-') as temporary:
        base = Path(temporary)
        env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_')) and
               k not in {'OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS'}}
        port, bridge_port = smoke.smoke.free_port(), smoke.smoke.free_port()
        assert port != bridge_port and not {port, bridge_port} & smoke.FORBIDDEN
        data, library = base / 'data', base / 'library'
        course = smoke.fixture.COURSE
        smoke.fixture.write_pack(base / 'mock/mcq-mainline')
        bindings = [] if args.automatic else [import_local_book(fixtures / 'library', sample['book'], library,
            document_id='synthetic-' + sample['format'], course=course,
            explain_source_id='synthetic-' + sample['format']) for sample in samples]
        server_env = dict(env, COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'),
            COACH_LOG_DIR=str(base / 'logs'), COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'),
            COACH_PACKS_DIR=str(base / 'packs'), COACH_MOCK_DIR=str(base / 'mock'), COACH_GENERATED_DIR=str(base / 'generated'),
            COACH_TAIL_HOST='', COACH_PORT=str(port), COACH_TRAFFIC='test', COACH_READER_LIBRARY=str(library),
            COACH_READER_MODEL='gpt-6.1-sol', COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')
        token = ''

        def launch(name, argv, variables):
            handle = (args.output / (name + '.log')).open('ab')
            handles.append(handle)
            child = subprocess.Popen(list(map(str, argv)), env=variables, stdout=handle, stderr=subprocess.STDOUT)
            children.append(child)
            return child

        def request(path, body=None):
            req = urllib.request.Request(f'http://127.0.0.1:{port}' + path,
                data=None if body is None else json.dumps(body).encode(), headers={
                    'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop', 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=110) as response:
                return json.load(response)

        def start_server():
            nonlocal token
            child = launch('server', [smoke.smoke.BINARY], server_env)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                assert child.poll() is None, 'Isolated server exited'
                try:
                    token = (data / 'device-token').read_text().strip()
                    request('/v1/hello')
                    return child
                except OSError:
                    time.sleep(.2)
            raise TimeoutError('Isolated server readiness')

        try:
            bridge = launch('bridge', [args.bridge_python.absolute(), ROOT / 'tools/bridge/subscription_bridge.py'],
                dict(env, BRIDGE_PORT=str(bridge_port), COACH_BRIDGE_HOME=str(base / 'bridge')))
            deadline = time.monotonic() + 20
            while True:
                assert bridge.poll() is None, 'Isolated bridge exited'
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{bridge_port}/health?scope=process', timeout=2):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Isolated bridge readiness') from None
                    time.sleep(.2)
            server = start_server()
            if args.automatic:
                smoke.stop(server)
                # The ordinary server creates this private directory. No source/outline seeding.
                server = start_server()
            else:
                smoke.stop(server)
            now = int(time.time() * 1000)
            points = [dict(id='saved-' + s['format'], title='Synthetic ' + s['format'], week=1) for s in samples]
            payload = dict(course=dict(id=course, name='Self-authored saved books', tz='Europe/London'), knowledgePoints=points)
            events = [('outline_revised', payload), ('kp_opened', dict(kpId=points[-1]['id']))]
            with closing(sqlite3.connect(data / 'coach.sqlite')) as db, db:
                assert db.execute('select count(*) from events').fetchone()[0] == 0
                for seq, (kind, payload) in enumerate([] if args.automatic else events, 1):
                    event = dict(seq=seq, id=f'saved-live-{seq}', courseId=course, type=kind, schema=1,
                        recordedAt=now, occurredAt=now, deviceId='desktop', actor='SYSTEM' if seq == 1 else 'LEARNER',
                        effectIndex=0, idemKey=f'saved-live-{seq}', payload=payload)
                    db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                        (seq, event['id'], course, kind, 1, now, now, 'desktop', event['actor'], 0, event['idemKey'], json.dumps(payload), json.dumps(event)))
                for i, (sample, binding) in enumerate(zip(samples, bindings)):
                    source = binding['explainSourceId']
                    db.execute('insert into source_docs(doc_id,course_id,kind,title,tutor_visible,learner_visible) values(?,?,?,?,1,1)',
                        (source, course, 'textbook', 'Self-authored ' + sample['format']))
                    snapshot = json.loads((library / binding['pagesFile']).read_bytes())
                    for page in snapshot['pages']:
                        db.execute('insert into source_pages(doc_id,pdf_page,text,text_sha256) values(?,?,?,?)',
                            (source, page['number'], page['text'], hashlib.sha256(page['text'].encode()).hexdigest()))
                    db.execute('insert into outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired) values(?,?,?,?,?,?,?,?,?,0)',
                        (points[i]['id'], course, 'synthetic', i, points[i]['title'], 'concept', '[]', '[]',
                         json.dumps([dict(docId=source, pdfPage=sample['page'])])))
            if not args.automatic:
                server = start_server()
            for sample in samples:
                body = dict(requestId=uuid.uuid4().hex, kind='explain', selection=sample['text'],
                            blocks=[dict(id=sample['id'], text=sample['text'])])
                if args.automatic:
                    with closing(sqlite3.connect((fixtures / 'library/library.sqlite').as_uri() + '?mode=ro', uri=True)) as saved:
                        title = saved.execute('select title from books where id=?', (sample['book'],)).fetchone()[0]
                        text, ocr = saved.execute('select text,ocr from pages where book=? and page=?',
                                                 (sample['book'], sample['page'])).fetchone()
                    assert text.encode('utf-16-le')[sample['start'] * 2:sample['end'] * 2].decode('utf-16-le') == sample['text']
                    body['localSource'] = dict(schema=1, book=sample['book'], format=sample['format'], title=title,
                        consented=True, pages=[dict(number=sample['page'], length=len(text.encode('utf-16-le')) // 2,
                            ocr=bool(ocr), sha256=hashlib.sha256(text.encode()).hexdigest())])
                started = time.monotonic()
                result = request('/v1/library/explain', body)
                passed = bool(result.get('sources')) and all(s['docId'] == sample['id'] and s['pdfPage'] == sample['page'] for s in result['sources'])
                rows.append(dict(format=sample['format'], ocr=sample['ocr'], book=sample['book'], page=sample['page'],
                    block=sample['id'], start=sample['start'], end=sample['end'], passed=passed,
                    roundTripMs=round((time.monotonic() - started) * 1000), response=result))
                (args.output / 'report.json').write_text(json.dumps(dict(syntheticOnly=True, isolatedServer=True,
                    operatorRegisteredExistingSource=not args.automatic, automaticRegistration=args.automatic,
                    nativeWindow=False, samples=rows), ensure_ascii=False, indent=2))
                print(sample['format'] + ': ' + ('PASS' if passed else 'FAIL'), flush=True)
            smoke.stop(server)
            with closing(sqlite3.connect(data / 'coach.sqlite')) as db:
                if args.automatic:
                    assert db.execute('select count(*) from events').fetchone()[0] == 0
                    assert db.execute('select count(*) from source_docs').fetchone()[0] == 0
                    assert db.execute('select count(*) from outline_kp').fetchone()[0] == 0
                calls = [dict(zip(('task', 'requestedModel', 'servedBy', 'status', 'physicalCalls', 'durationMs'), row))
                         for row in db.execute('select task,requested_model,served_by,status,physical_calls,dur_ms from ai_calls')]
            (args.output / 'calls.json').write_text(json.dumps(calls, indent=2))
            if args.automatic:
                with closing(sqlite3.connect(data / 'personal-imports/sources.sqlite')) as db:
                    assert db.execute('select count(distinct book) from blocks where device=?', ('desktop',)).fetchone()[0] == len(samples)
            assert len(calls) >= len(samples) and all(c['task'] == 'READER_SELECTION' and c['physicalCalls'] >= 1 for c in calls)
            assert all(s['passed'] for s in rows), 'Real explanation did not pass the citation gate'
        finally:
            for child in reversed(children):
                smoke.stop(child)
            for handle in handles:
                handle.close()


if __name__ == '__main__':
    main()
