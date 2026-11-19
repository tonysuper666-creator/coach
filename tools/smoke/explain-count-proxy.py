#!/usr/bin/env python3
"""Counting pass-through proxy for the friend-path smoke.

Sits between the real Windows client and the real isolated friend service and records every POST to the library explain
route: time, request kind (explain / lesson / ask ...) and the physical pages of the blocks it carries. It never stores
book text or credentials. The record is what proves *when* a whole-section request left the client.

  explain-count-proxy.py --listen-port 0 --upstream http://host:port --log counts.jsonl --port-file port.txt
"""
import argparse, json, sys, threading, time, urllib.request, urllib.error, re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

args = None
lock = threading.Lock()

def summarize(body: bytes) -> dict:
    try:
        data = json.loads(body.decode('utf-8'))
    except Exception:
        return {'kind': 'unparsed', 'pages': []}
    blocks = data.get('blocks') if isinstance(data, dict) else None
    pages = []
    for b in blocks or []:
        m = re.search(r':p(\d+):', str(b.get('id', '')))
        if m: pages.append(int(m.group(1)))
    return {'kind': data.get('kind', 'unknown') if isinstance(data, dict) else 'unknown', 'pages': sorted(set(pages)), 'blocks': len(blocks or [])}

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *a): pass
    def _forward(self):
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        if self.command == 'POST' and self.path.startswith('/v1/library/explain') and not self.path.endswith('/cancel'):
            rec = {'ts': time.time(), 'utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'path': self.path, **summarize(body)}
            with lock, open(args.log, 'a', encoding='utf-8') as f: f.write(json.dumps(rec) + '\n')
        req = urllib.request.Request(args.upstream + self.path, data=body if self.command != 'GET' else None, method=self.command)
        for k, v in self.headers.items():
            if k.lower() not in ('host', 'content-length', 'connection'): req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=180) as r: status, headers, data = r.status, r.getheaders(), r.read()
        except urllib.error.HTTPError as e: status, headers, data = e.code, e.headers.items(), e.read()
        except Exception: status, headers, data = 502, [], b'{}'
        self.send_response(status)
        for k, v in headers:
            if k.lower() not in ('transfer-encoding', 'connection', 'content-length'): self.send_header(k, v)
        self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
    do_GET = do_POST = do_DELETE = do_PUT = _forward

if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('--listen-port', type=int, default=0); p.add_argument('--upstream', required=True)
    p.add_argument('--log', required=True); p.add_argument('--port-file', required=True); args = p.parse_args()
    srv = ThreadingHTTPServer(('127.0.0.1', args.listen_port), Handler)
    open(args.port_file, 'w').write(str(srv.server_address[1])); srv.serve_forever()
