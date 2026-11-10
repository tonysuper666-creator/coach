#!/usr/bin/env python3
"""Opt-in wall-clock reader fault replay through the real HTTP/bridge/CLI chain (synthetic CLI).

Run via tools/dev/heavy after :server:app:installDist. --bridge-python must be a
venv with tools/bridge/requirements.txt. No paid CLI, private material, or production writes.
"""
import argparse
import datetime
import hashlib
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
            with urllib.request.urlopen(req, timeout=145) as response:
                return json.load(response)

        stub = base / 'stub'
        stub.mkdir()
        (stub / 'claude').symlink_to(ROOT / 'tools/bridge/tests/reader_timeout_cli.py')
        server_env = dict(env, COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'),
            COACH_LOG_DIR=str(base / 'logs'), COACH_STATE_DIR=str(base / 'state'), COACH_RUN_DIR=str(base / 'run'),
            COACH_PACKS_DIR=str(base / 'packs'), COACH_MOCK_DIR=str(base / 'mock'), COACH_GENERATED_DIR=str(base / 'generated'),
            COACH_TAIL_HOST='', COACH_PORT=str(port), COACH_TRAFFIC='test', COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')

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
                dict(env, BRIDGE_PORT=str(bridge_port), COACH_BRIDGE_HOME=str(base / 'bridge'),
                     BRIDGE_CHILD_PATH=str(stub), READER_STUB_DIR=str(stub), BRIDGE_CLAUDE_TIMEOUT='150',
                     BRIDGE_STALL_TIMEOUT='150', BRIDGE_STREAM_TOTAL_TIMEOUT='180'))
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
            notes_smoke.stop(server)
            import concurrent.futures
            import socket
            for mode in ('first', 'gap', 'total', 'cancel', 'disconnect', 'long'):
                for name in ('pids', 'times'):
                    (stub / name).unlink(missing_ok=True)
                (stub / 'mode').write_text(mode)
                limits = dict(first=3000, gap=2000, total=135000 if mode == 'long' else 10000)
                if mode == 'total':
                    limits['total'] = 3000
                server_env.update(COACH_READER_FIRST_CONTENT_TIMEOUT_MS=str(limits['first']),
                    COACH_READER_CONTENT_GAP_TIMEOUT_MS=str(limits['gap']), COACH_READER_TOTAL_TIMEOUT_MS=str(limits['total']))
                server = start_server()
                request_id = uuid.uuid4().hex
                body = dict(requestId=request_id, kind='explain', sectionId=section['section_id'],
                    version=section['content_version'], start=0, end=len(quote), quote=quote)
                started = time.monotonic()
                def await_started():
                    deadline = time.monotonic()+8
                    while not (stub / 'pids').exists():
                        assert time.monotonic() < deadline, 'CLI did not start'
                        time.sleep(.02)
                if mode == 'disconnect':
                    payload = json.dumps(body).encode()
                    connection = socket.create_connection(('127.0.0.1', port), timeout=10)
                    headers = (f'POST {route}/explain HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\n'
                        f'X-Coach-Device: desktop\r\nContent-Type: application/json\r\nContent-Length: {len(payload)}\r\n\r\n')
                    connection.sendall(headers.encode()+payload)
                    await_started()
                    time.sleep(.4)
                    connection.close()
                    result = {}
                elif mode == 'cancel':
                    with concurrent.futures.ThreadPoolExecutor() as pool:
                        pending = pool.submit(request, route+'/explain', body)
                        await_started()
                        time.sleep(.4)
                        request(route+'/explain/cancel', dict(requestId=request_id))
                        result = pending.result(timeout=5)
                else:
                    result = request(route+'/explain', body)
                elapsed = (time.monotonic()-started)*1000
                await_started()
                pids = json.loads((stub / 'pids').read_text())
                def alive(pid):
                    # Zombies have exited; reject every still-running process, including grandchildren.
                    check = subprocess.run(['ps', '-o', 'stat=', '-p', str(pid)], capture_output=True, text=True)
                    return check.returncode == 0 and not check.stdout.strip().startswith('Z')
                deadline = time.monotonic()+5
                while any(alive(pid) for pid in pids) and time.monotonic()<deadline:
                    time.sleep(.05)
                assert not any(alive(pid) for pid in pids), f'{mode}: CLI process leaked'
                if mode in ('cancel', 'disconnect'):
                    assert elapsed < 1800, f'{mode}: cancellation waited for timeout'
                times = [float(t) for t in (stub / 'times').read_text().splitlines()] if (stub / 'times').exists() else []
                notes_smoke.stop(server)
                receipts = [line.split('COACH reader.end ',1)[1] for line in (base/'server.log').read_text().splitlines() if 'COACH reader.end ' in line]
                receipt = receipts[-1]
                expected = dict(first='timeout_first', gap='timeout_gap', total='timeout_total',
                    cancel='cancelled', disconnect='disconnected', long='complete')[mode]
                assert f'status={expected} ' in receipt, (mode, receipt)
                if mode == 'long':
                    assert result.get('sources') and len(times)==121 and times[-1]-times[0]>=120, 'Not genuine 120s body streaming'
                elif mode in ('first','gap','total'):
                    assert not result.get('sources') and elapsed < limits['total']+2000
                elif mode == 'cancel':
                    assert result.get('text') == '追问已取消。'
                # The source and reading notes must survive every failure, cancellation and success.
                with sqlite3.connect(data/'coach.sqlite') as db:
                    assert db.execute('select text from source_pages where doc_id=?', ('reader-synthetic',)).fetchone()[0] == quote
                    assert db.execute('select count(*) from events').fetchone()[0] == 1
                rows.append(dict(mode=mode, passed=True, thresholdsMs=limits, roundTripMs=round(elapsed,3),
                    firstBodyMs=round(times[0]*1000,3) if times else None,
                    lastBodyMs=round(times[-1]*1000,3) if times else None,
                    bodySpanMs=round((times[-1]-times[0])*1000,3) if times else 0, bodyEvents=len(times),
                    cliProcessesRemaining=0, userMessage=result.get('text') if mode != 'long' else None,
                    receipt=receipt, originalPreserved=True))
                (args.output/'report.json').write_text(json.dumps(dict(syntheticOnly=True,
                    productionRead=False, path='HTTP -> ReaderExplanation -> BridgeClient -> subscription_bridge -> synthetic CLI',
                    utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), samples=rows), ensure_ascii=False, indent=2))
                print(f'{mode}: PASS elapsed={elapsed:.0f}ms bodyEvents={len(times)} CLI remaining=0', flush=True)
        finally:
            # Keep only timing/error metadata, even when an assertion fails.
            log_path = base / 'server.log'
            if log_path.exists():
                metadata = [line for line in log_path.read_text(errors='replace').splitlines()
                            if 'COACH reader.end ' in line or 'Exception' in line or 'Error:' in line
                            or line.lstrip().startswith(('at ', 'Caused by:'))]
                (args.output / 'diagnostics.log').write_text('\n'.join(metadata)+'\n')
            for child in reversed(children):
                notes_smoke.stop(child)
            for handle in handles:
                handle.close()


if __name__ == '__main__':
    main()
