"""Executable acceptance using ephemeral loopback TLS and a two-view rule double."""
import concurrent.futures
import contextlib
import http.client
import http.server
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import socketserver
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = Path(__file__).resolve()
spec = importlib.util.spec_from_file_location('window', HERE.with_name('anonymous_window.py'))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


class RuleDouble:
    def __init__(self, path):
        self.path = path

    def open_if_empty(self):
        with w.transaction(self.path) as db:
            w.require(db.execute("SELECT * FROM rule").fetchall() == [(None, 0)])
            db.execute("UPDATE rule SET owner='window'")

    def remove_if_owned(self):
        with w.transaction(self.path) as db:
            return db.execute("UPDATE rule SET owner=NULL WHERE owner='window'").rowcount == 1

    def views_empty(self):
        with w.transaction(self.path) as db:
            return db.execute("SELECT * FROM rule").fetchall() == [(None, 0)]


class ObserverServer(http.server.HTTPServer):
    def get_request(self):
        sock, addr = super().get_request()
        sock.settimeout(1)
        return sock, addr


@contextlib.contextmanager
def listening(server):
    with server:
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.01})
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join(2)
            w.require(not thread.is_alive())


def supervisor(path):
    with listening(ObserverServer(('127.0.0.1', 0), w.Observer)) as observer:
        observer.window = path
        def ready():
            with w.transaction(path) as db:
                db.execute("INSERT INTO observer VALUES (?,?)", (observer.server_port, os.getpid()))
        return w.watch(path, RuleDouble(path), ready)


class Edge(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(2)
        if self.server.eof:
            with self.request.makefile('rb') as stream:
                record = stream.read(5)
                w.require(len(record) == 5 and record[0] == 22)
                length = int.from_bytes(record[3:], 'big')
                w.require(0 < length <= 16384 and len(stream.read(length)) == length)
            self.request.shutdown(socket.SHUT_WR)
            return
        with self.server.ctx.wrap_socket(self.request, server_side=True) as conn, conn.makefile('rb') as stream:
            w.require(stream.readline(128) == b'GET /v1/public/status HTTP/1.1\r\n')
            headers = http.client.parse_headers(stream)
            w.require(not any(key in headers for key in ('Authorization','Cookie','X-Coach-Device')))
            nonce = headers[w.HEADER]
            if not self.server.omit:
                with contextlib.closing(http.client.HTTPConnection('127.0.0.1', self.server.observer, timeout=2)) as backend:
                    backend.request('GET', w.STATUS, headers={w.HEADER: nonce})
                    with backend.getresponse() as resp:
                        w.require(resp.status == 401 and not resp.read())
            conn.sendall(b'HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')


class Acceptance(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name).resolve()
        self.path = self.dir / 'window.db'
        w.initialize(self.path)
        with w.transaction(self.path) as db:
            db.execute("CREATE TABLE rule(owner TEXT, stale_view INTEGER)")
            db.execute("INSERT INTO rule VALUES(NULL,0)")
            db.execute("CREATE TABLE observer(port INTEGER,pid INTEGER)")
            db.execute("CREATE TABLE exit_receipt(code INTEGER)")
        self.watch = None

    def state(self, query):
        with w.transaction(self.path) as db:
            return db.execute(query).fetchall()

    def start_watch(self, orphan=False):
        cmd = [sys.executable,'-I','-B',str(HERE),'watch',str(self.path)]
        if orphan:
            cmd[4] = 'launch'
            # The launching process exits. The watchdog inherits no pipes.
            p = subprocess.run(cmd, capture_output=True, timeout=3)
            self.assertEqual((p.returncode,p.stderr), (0,b''))
        else:
            self.watch = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(self.end_watch)
        self.port,self.supervisor_pid = self.wait_for("SELECT port,pid FROM observer")[0]

    def wait_for(self, sql):
        until = time.monotonic() + 5
        while time.monotonic() < until:
            rows = self.state(sql)
            if rows:
                return rows
            time.sleep(.015)
        self.fail("fixture deadline")

    def end_watch(self):
        w.cancel(self.path)
        if self.watch and self.watch.poll() is None:
            try:
                self.watch.wait(3)
            except subprocess.TimeoutExpired:
                self.watch.kill()
                self.watch.wait()
                self.fail("supervisor failed to terminate")
        # 'exit' is written after listener teardown, including orphan mode.
        self.wait_for("SELECT * FROM exit_receipt")

    def listener_closed(self):
        with socket.socket() as sock:
            sock.settimeout(1)
            self.assertNotEqual(sock.connect_ex(('127.0.0.1', self.port)), 0)

    def test_h_before_e_and_concurrent_replay(self):
        self.start_watch()
        with self.assertRaises(w.Refused):
            w.grant(self.path,'E')
        def claim(_):
            try:
                w.grant(self.path,'H')
                return True
            except w.Refused:
                return False
        with concurrent.futures.ThreadPoolExecutor(6) as pool:
            self.assertEqual(sum(pool.map(claim,range(12))),1)
        with self.assertRaises(w.Refused):
            w.grant(self.path,'E')

    def test_duplicate_supervisor_preserves_active_window(self):
        self.start_watch()
        with self.assertRaises(w.Refused):
            w.watch(self.path, None, None)
        self.assertEqual(self.state("SELECT state FROM window"), [('open',)])
        self.assertEqual(self.state("SELECT * FROM rule"), [('window', 0)])

    def test_expired_supervisor_cannot_open_rule(self):
        with w.transaction(self.path) as db:
            db.execute("UPDATE window SET utc=?", (time.time() - 1,))
        with self.assertRaises(w.Refused):
            w.watch(self.path, None, None)
        self.assertEqual(self.state("SELECT * FROM rule"), [(None, 0)])

    def test_cancel_denies_queued_and_claimed(self):
        self.start_watch()
        nonce = w.grant(self.path,'H')
        w.cancel(self.path)
        with self.assertRaises(w.Refused):
            w.once(self.path,'H',nonce,'unused.invalid','127.0.0.1')
        with self.assertRaises(w.Refused):
            w.grant(self.path,'E')
        self.assertEqual(self.state('SELECT count(*) FROM attempts'),[(0,)])

    def test_orphan_expires_and_releases_listener(self):
        self.start_watch(orphan=True)
        nonce = w.grant(self.path,'H')
        with w.transaction(self.path) as db:
            # Advance the fixture to the last 30s, preserving a real clock tick.
            db.execute("UPDATE window SET stop=?", (time.monotonic()+30.35,))
        self.assertEqual(self.wait_for("SELECT code FROM exit_receipt"),[(0,)])
        self.assertEqual(self.state("SELECT state FROM window"),[('rule-cleared',)])
        self.assertEqual(self.state("SELECT * FROM rule"),[(None,0)])
        self.assertEqual(self.state("SELECT count(*) FROM slots WHERE digest IS NOT NULL"),[(0,)])
        self.listener_closed()
        with self.assertRaises(w.Refused):
            w.once(self.path,'H',nonce,'unused.invalid','127.0.0.1')
        with self.assertRaises(w.Refused):
            w.grant(self.path,'E')

        until = time.monotonic() + 2
        while time.monotonic() < until:
            try:
                os.kill(self.supervisor_pid,0)
            except ProcessLookupError:
                break
            time.sleep(.02)
        else:
            self.fail("detached supervisor still present")

    def test_expired_absolute_deadline_before_watchdog_tick(self):
        self.start_watch()
        nonce = w.grant(self.path,'H')
        with w.transaction(self.path) as db:
            db.execute("UPDATE window SET utc=?", (time.time()-1,))
        with self.assertRaises(w.Refused):
            w.once(self.path,'H',nonce,'unused.invalid','127.0.0.1')
        with self.assertRaises(w.Refused):
            w.grant(self.path,'E')

    def test_consumed_worker_claim_cannot_dial_again(self):
        self.start_watch()
        nonce = w.grant(self.path,'H')
        with w.transaction(self.path) as db:
            db.execute("INSERT INTO attempts VALUES ('H')")
        with self.assertRaises(w.Refused):
            w.once(self.path,'H',nonce,'unused.invalid','127.0.0.1')
        self.assertEqual(self.state('SELECT count(*) FROM attempts'),[(1,)])

    def test_hard_deadline_during_handshake(self):
        self.start_watch()
        stop = threading.Event()
        connected = threading.Event()

        class Stall(socketserver.BaseRequestHandler):
            def handle(self):
                connected.set()
                stop.wait(12)

        with listening(socketserver.TCPServer(('127.0.0.1',0), Stall)) as edge:
            started = time.monotonic()
            try:
                # Supply an existing trust bundle; the edge never sends a certificate.
                ca = ssl.get_default_verify_paths().cafile
                self.assertIsNotNone(ca)
                with self.assertRaises((w.Refused,subprocess.TimeoutExpired)):
                    w.run_slot(self.path,'H','deadline.invalid','127.0.0.1',edge.server_address[1],ca)
                self.assertTrue(connected.is_set())
                self.assertLess(time.monotonic()-started,11)
                with self.assertRaises(w.Refused):
                    w.grant(self.path,'E')
            finally:
                stop.set()

    def test_receipt_rejects_extra_fields_and_untyped_values(self):
        row = dict(role='H',stage='tls',category='tls-eof',family='IPv4',peer_match=True,
                   tls_verified=False,http_401=False,nonce_correlated=False,accepted=False)
        for addition in ({'stage':'fixture-leak.invalid'}, {'extra':'127.0.0.1'}, {'accepted':1}):
            with self.assertRaises(w.Refused):
                w.public_receipt(row | addition,'H')

    def alarm(self, change, expected):
        self.start_watch()
        with w.transaction(self.path) as db:
            db.execute(change)
        w.cancel(self.path)
        self.assertEqual(self.watch.wait(3),1)
        self.assertEqual(self.state('SELECT state FROM window'),[('alarm',)])
        self.assertEqual(self.state('SELECT * FROM rule'),[expected])
        self.listener_closed()

    def test_changed_owner_preserved(self):
        self.alarm("UPDATE rule SET owner='foreign'", ('foreign',0))

    def test_stale_second_view_alarms(self):
        self.alarm("UPDATE rule SET stale_view=1", (None,1))

    def test_unknown_nonce_stops_dispatch(self):
        self.start_watch()
        with contextlib.closing(http.client.HTTPConnection('127.0.0.1',self.port,timeout=2)) as c:
            c.request('GET',w.STATUS,headers={w.HEADER:secrets.token_hex(24)})
            self.assertEqual(c.getresponse().status,404)
        with self.assertRaises(w.Refused):
            w.grant(self.path,'H')

    def exercise(self, omit):
        self.start_watch()
        host = 'fixture-' + secrets.token_hex(8) + '.invalid'
        cert, key = self.dir/'cert', self.dir/'key'
        config = self.dir/'openssl.cnf'
        config.write_text('[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=ext\n'
                          '[dn]\nCN='+host+'\n[ext]\nsubjectAltName=DNS:'+host+'\n'
                          'basicConstraints=critical,CA:TRUE\nsubjectKeyIdentifier=hash\n')
        subprocess.run([shutil.which('openssl') or 'openssl','req','-x509','-newkey','rsa:2048',
                        '-nodes','-days','1','-config',str(config),'-keyout',str(key),'-out',str(cert)],
                       check=True,capture_output=True,timeout=10)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert,key)
        for role in (('H',) if omit else ('H','E')):
            with listening(socketserver.TCPServer(('127.0.0.1',0),Edge)) as edge:
                edge.ctx, edge.observer = ctx, self.port
                edge.omit, edge.eof = omit, role == 'E'
                row = w.run_slot(self.path,role,host,'127.0.0.1',edge.server_address[1],str(cert))
                self.assertTrue(all(value not in json.dumps(row) for value in (host,'127.0.0.1')))
                self.assertEqual(row['category'], 'http-401' if role=='H' else 'tls-eof')
                self.assertEqual(row['nonce_correlated'], role=='H' and not omit)
                self.assertEqual(row['accepted'], not omit)
                self.assertTrue(row['peer_match'])
                self.assertEqual(row['tls_verified'], role=='H')
        self.assertEqual(self.watch.wait(3),0)
        self.listener_closed()
        with self.assertRaises(w.Refused):
            w.grant(self.path,'E')
        self.assertEqual(self.state('SELECT count(*) FROM attempts'),[(1 if omit else 2,)])
        self.assertEqual(self.state('SELECT count(*) FROM slots WHERE digest IS NOT NULL'),[(0,)])

    def test_single_request_serial_401_then_eof(self):
        self.exercise(False)

    def test_missing_nonce_on_401_stops(self):
        self.exercise(True)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] in ('launch','watch'):
        path = Path(sys.argv[2])
        try:
            if sys.argv[1] == 'launch':
                subprocess.Popen([sys.executable,'-I','-B',str(HERE),'watch',str(path)],
                                 stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
                                 start_new_session=True)
                until = time.monotonic() + 2
                while time.monotonic() < until:
                    with w.transaction(path) as db:
                        if db.execute("SELECT count(*) FROM observer").fetchone() == (1,):
                            break
                    time.sleep(.01)
                else:
                    sys.exit(1)
            elif sys.argv[1] == 'watch':
                code = supervisor(path)
                with w.transaction(path) as db:
                    db.execute("INSERT INTO exit_receipt VALUES (?)",(code,))
                sys.exit(code)
        except Exception:
            sys.exit(1)
    else:
        unittest.main()
