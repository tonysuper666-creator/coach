#!/usr/bin/env python3
"""Non-production candidate smoke: isolated files/ports, synthetic bridge, no mobile/CLI."""
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import struct
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / 'server/app/build/install/app/bin/app'
FORBIDDEN = {8790, 8791, 18791}


def free_port():
    while True:
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            port = s.getsockname()[1]
        if port not in FORBIDDEN:
            return port


class Bridge(http.server.BaseHTTPRequestHandler):
    calls = 0
    def log_message(self, *args): pass
    def do_GET(self):
        body = b'{"ok":true,"rate_limit":{"status":"allowed","utilization":0.1}}'
        self.send_response(200); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        if self.path != '/v1/chat/completions':
            self.send_error(404); return
        self.rfile.read(int(self.headers['Content-Length']))
        Bridge.calls += 1
        self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
        items = [
            {'model':'claude-sonnet-5-5','choices':[{'index':0,'delta':{'content':'Synthetic lesson body.\n'}}]},
            {'model':'claude-sonnet-5-5','choices':[], 'x_coach':{'status':'complete','reason':None,'finish_reason':'stop','served_by':'claude-sonnet-5-5','fallback':False,'physical_calls':1,'request_id':'rid-synthetic-smoke','traffic':'test','source':'unknown','queue_ms':2,'ttft_ms':50,'elapsed_ms':80}},
        ]
        for item in items:
            self.wfile.write(('data: '+json.dumps(item)+'\n\n').encode()); self.wfile.flush(); time.sleep(.05)
        self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()


class WS:
    def __init__(self, port, device, token):
        self.sock = socket.create_connection(('127.0.0.1', port), 5)
        self.sock.settimeout(15)
        self.pending = []
        key = base64.b64encode(os.urandom(16)).decode()
        request = (f'GET /v1/ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {token}\r\nX-Coach-Device: {device}\r\n\r\n')
        self.sock.sendall(request.encode())
        # Read one byte at a time so the first frame is never consumed by handshake.
        response = b''
        while not response.endswith(b'\r\n\r\n'):
            response += self.sock.recv(1)
            if len(response) > 16384: raise RuntimeError('oversized handshake')
        assert response.startswith(b'HTTP/1.1 101 '), response.splitlines()[0]
        expected = base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
        headers = dict(line.split(b':', 1) for line in response.split(b'\r\n')[1:] if b':' in line)
        assert any(name.lower() == b'sec-websocket-accept' and value.strip() == expected for name, value in headers.items())
    def read(self, n):
        result = b''
        while len(result) < n:
            part = self.sock.recv(n-len(result))
            if not part: raise EOFError('websocket closed')
            result += part
        return result
    def send(self, value):
        data = json.dumps(value, ensure_ascii=False).encode(); mask = os.urandom(4)
        size = len(data)
        header = bytes([0x81, 0x80 | size]) if size < 126 else bytes([0x81, 0x80 | 126])+struct.pack('!H', size)
        self.sock.sendall(header+mask+bytes(x ^ mask[i % 4] for i,x in enumerate(data)))
    def await_type(self, *types):
        for index, frame in enumerate(self.pending):
            if frame.get('t') in types:
                return self.pending.pop(index)
        end = time.monotonic()+20
        while time.monotonic() < end:
            h = self.read(2); opcode = h[0] & 15; size = h[1] & 127
            if size == 126: size = struct.unpack('!H',self.read(2))[0]
            elif size == 127: size = struct.unpack('!Q',self.read(8))[0]
            payload = self.read(size)
            if opcode == 8: raise RuntimeError('websocket closed before '+str(types))
            if opcode != 1: continue
            frame = json.loads(payload)
            if frame.get('t') in types: return frame
            self.pending.append(frame)
        raise TimeoutError(str(types))
    def close(self): self.sock.close()


def main():
    assert BINARY.is_file(), 'run installDist first'
    bridge = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Bridge)
    bridge_port = bridge.server_address[1]
    assert bridge_port not in FORBIDDEN
    thread = threading.Thread(target=bridge.serve_forever); thread.start()
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix='coach-candidate-') as tmp:
            base = Path(tmp); data = base/'data'; data.mkdir()
            port = free_port()
            env = {k:v for k,v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
            env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data/'device-token'),
                       COACH_LOG_DIR=str(base/'logs'), COACH_STATE_DIR=str(base/'state'),
                       COACH_PACKS_DIR=str(base/'packs'), COACH_TAIL_HOST='', COACH_PORT=str(port),
                       COACH_TRAFFIC='test', COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')
            logfile = base/'server.log'
            def start():
                nonlocal process
                out = logfile.open('ab')
                process = subprocess.Popen([str(BINARY)], env=env, stdout=out, stderr=subprocess.STDOUT)
                out.close()
                deadline = time.monotonic()+40
                while time.monotonic() < deadline:
                    if process.poll() is not None: raise RuntimeError('candidate exited: '+logfile.read_text()[-3000:])
                    try:
                        code, _ = request('/v1/hello')
                        if code == 401: return
                    except OSError: pass
                    time.sleep(.2)
                raise TimeoutError('candidate readiness')
            def stop():
                nonlocal process
                if process:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
                    process = None
            def request(path, method='GET', body=None, device=None, token=None):
                headers = {}
                if device: headers['X-Coach-Device'] = device
                if token: headers['Authorization'] = 'Bearer '+token
                if body is not None: headers['Content-Type'] = 'application/json'
                req = urllib.request.Request(f'http://127.0.0.1:{port}'+path,
                    data=json.dumps(body).encode() if body is not None else (b'' if method == 'POST' else None), headers=headers, method=method)
                try:
                    with urllib.request.urlopen(req,timeout=3) as response: return response.status, response.read()
                except urllib.error.HTTPError as e: return e.code, e.read()
            try:
                start()
                admin = (data/'device-token').read_text().strip()
                assert (data/'device-token').stat().st_mode & 0o777 == 0o600
                assert re.fullmatch(r'[A-Za-z0-9_-]{43}', admin)
                status, content = request('/v1/pairing-code','POST',device='desktop',token=admin); assert status == 200
                code = json.loads(content)['code']
                status, content = request('/v1/pair','POST',{'code':code,'name':'synthetic'}); assert status == 200
                credentials = json.loads(content); device, token = credentials['deviceId'], credentials['token']
                assert re.fullmatch(r'[A-Za-z0-9_-]{43}', token)
                assert request('/v1/pair','POST',{'code':code,'name':'replay'})[0] == 401
                assert request('/v1/hello',device=device,token=token)[0] == 200
                assert request('/v1/hello',device=device,token='wrong')[0] == 401
                stop()
                with sqlite3.connect(data/'coach.sqlite') as db:
                    digest = db.execute('SELECT token_sha256 FROM devices WHERE device_id=?',(device,)).fetchone()[0]
                    assert digest == hashlib.sha256(token.encode()).hexdigest() and token != digest
                    for i in range(3):
                        q = f'q{i}'; options = [{'optionId':f'{q}-{j}','text':f'Synthetic option {i}-{j}'} for j in range(4)]
                        db.execute('INSERT INTO questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state) VALUES(?,?,?,?,?,?,?,?)', (q,'course-1','SINGLE_CHOICE',f'Synthetic question {i}',json.dumps(options),'COACH',f'synthetic-fingerprint-{i}','VERIFIED'))
                        db.execute('INSERT INTO answer_keys(question_id,key_json) VALUES(?,?)',(q,json.dumps({'correctOptionId':f'{q}-0'})))
                        db.execute('INSERT INTO question_kp(question_id,kp_id,is_primary) VALUES(?,?,1)',(q,'kp-1'))
                start()
                assert (data/'device-token').read_text().strip() == admin
                assert request('/v1/hello',device=device,token=token)[0] == 200
                ws = WS(port,device,token)
                try:
                    common = {'threadId':'thread-1','courseId':'course-1'}
                    ws.send(dict(common,t='tutor.open',kpId='kp-1',conceptIds=['cost'],title='Synthetic objective'))
                    ws.await_type('tutor.state')
                    ws.send(dict(common,t='msg.ask',text='继续'))
                    ws.await_type('msg.start')
                    first = ws.await_type('msg.delta'); assert first.get('text') or first.get('delta')
                    end = ws.await_type('msg.end'); assert end.get('status') == 'complete'
                    while ws.await_type('tutor.state').get('state') != 'AWAIT': pass
                    ws.send(dict(common,t='msg.ask',text='继续'))
                    group = ws.await_type('tutor.cards'); cards = group['cards']; assert len(cards) == 3
                    answers = {c['attemptId']:c['letterMap'][c['questionId']+'-0'] for c in cards}
                    ws.send(dict(common,t='tutor.answer',groupId=group['groupId'],submissionId='synthetic-submit',answers=answers))
                    ws.await_type('tutor.feedback')
                finally: ws.close()
                deadline = time.monotonic()+5
                while 'COACH first_word ' not in logfile.read_text() and time.monotonic()<deadline: time.sleep(.1)
                traces = [json.loads(line.split('COACH first_word ',1)[1]) for line in logfile.read_text().splitlines() if 'COACH first_word ' in line]
                assert any(t.get('msg') == end['msg'] and t.get('rid') == 'rid-synthetic-smoke' and t.get('raw_first_ms') is not None and t.get('visible_first_ms') is not None for t in traces)
                assert any('COACH msg.end ' in line and 'rid=rid-synthetic-smoke' in line and 'traffic=test' in line for line in logfile.read_text().splitlines())
                report = subprocess.run(['python3',str(ROOT/'tools/dev/first_word_report.py'),'--log',str(logfile),'--source-label','synthetic'],capture_output=True,text=True,check=True)
                result = json.loads(report.stdout)
                assert result['attempts']['primary'] >= 1 and result['stages']['primary']['bridge_first_raw_ms']['n'] >= 1
                print(json.dumps({'result':'pass','checkout_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                    'isolation':'ephemeral loopback ports and temporary data; synthetic bridge',
                    'pairing':'single-use; unauthenticated and bad token rejected; 43-char base64url token; SHA-256 persisted; survives restart',
                    'flow':'lesson first delta and complete; 3 cards submitted and feedback received',
                    'bridge_calls':Bridge.calls,'same_request_rid':'rid-synthetic-smoke',
                    'trace':next(t for t in traces if t.get('rid')=='rid-synthetic-smoke'),
                    'report_primary_attempts':result['attempts']['primary'],
                    'report_bridge_first_raw':result['stages']['primary']['bridge_first_raw_ms'],
                    'report_visible_after_raw':result['stages']['primary']['visible_after_raw_ms'],
                    'source_verified':result['input']['source_verified']},ensure_ascii=False,indent=2))
            finally: stop()
    finally:
        bridge.shutdown(); thread.join(); bridge.server_close()

if __name__ == '__main__': main()
