"""Single anonymous status request and private, single-machine window controller.

The test adapter supplies the rule store. No command here operates Funnel.
"""
import contextlib
import hashlib
import http.client
import http.server
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

STATUS = "/v1/public/status"
HEADER = "X-Diagnostic-Nonce"
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("friend_public", ROOT / "friend-public.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class Refused(Exception):
    pass


def require(condition):
    if not condition:
        raise Refused()


@contextlib.contextmanager
def transaction(path):
    with contextlib.closing(sqlite3.connect(path.as_uri() + "?mode=rw", uri=True,
                                           isolation_level=None, timeout=1)) as db:
        db.execute("BEGIN IMMEDIATE")
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise


def initialize(path, duration=300):
    require(0 < duration <= 300)
    # The caller allocates a new private directory for every window.
    require(path.parent.stat().st_mode & 0o077 == 0)
    os.close(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600))
    with transaction(path) as db:
        db.execute("CREATE TABLE attempts(role TEXT PRIMARY KEY)")
        db.execute("CREATE TABLE window (state TEXT, stop REAL, utc REAL)")
        db.execute("CREATE TABLE slots (role TEXT PRIMARY KEY, state TEXT, digest TEXT, "
                   "seen INTEGER DEFAULT 0, until REAL DEFAULT 0)")
        db.execute("INSERT INTO window VALUES ('armed',?,?)",
                   (time.monotonic() + duration, time.time() + duration))
        db.executemany("INSERT INTO slots(role,state) VALUES (?, 'unused')", [('H',), ('E',)])


def remaining(db):
    state, stop, utc = db.execute("SELECT * FROM window").fetchone()
    return min(stop - time.monotonic(), utc - time.time()) if state == 'open' else 0


def grant(path, role):
    nonce = secrets.token_hex(24)
    digest = hashlib.sha256(nonce.encode()).hexdigest()
    with transaction(path) as db:
        require(role in ('H', 'E') and remaining(db) >= 40)
        if role == 'E':
            require(db.execute("SELECT state FROM slots WHERE role='H'").fetchone() == ('done',))
        require(db.execute("UPDATE slots SET state='running',digest=?,until=? "
                           "WHERE role=? AND state='unused'",
                           (digest, time.monotonic() + 10, role)).rowcount == 1)
    return nonce


def check_running(db, role, nonce):
    require(remaining(db) > 30)
    row = db.execute("SELECT digest,until FROM slots WHERE role=? AND state='running'",
                     (role,)).fetchone()
    require(row and row[1] > time.monotonic()
            and secrets.compare_digest(row[0], hashlib.sha256(nonce.encode()).hexdigest()))
    return row[1]


def cancel(path):
    with transaction(path) as db:
        db.execute("UPDATE window SET state='closing' WHERE state IN ('armed','starting','open')")
        db.execute("UPDATE slots SET state='revoked',digest=NULL WHERE state IN ('unused','running')")


def finish(path, role, nonce, category):
    with transaction(path) as db:
        check_running(db, role, nonce)
        seen, = db.execute("SELECT seen FROM slots WHERE role=?", (role,)).fetchone()
        good = (category == 'http-401' and seen == 1) or (category == 'tls-eof' and seen == 0)
        db.execute("UPDATE slots SET state=?,digest=NULL WHERE role=?",
                   ('done' if good else 'failed', role))
        if not good or role == 'E':
            db.execute("UPDATE window SET state='closing'")
    return bool(seen), good


class Observer(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        nonce = self.headers.get(HEADER, '')
        try:
            require(self.path == STATUS and not self.headers.get_all("Authorization")
                    and not self.headers.get_all("Cookie") and not self.headers.get_all("X-Coach-Device")
                    and self.headers.get_all(HEADER) == [nonce] and re.fullmatch("[a-f0-9]{48}", nonce))
            with transaction(self.server.window) as db:
                row = db.execute("SELECT role FROM slots WHERE state='running'").fetchone()
                require(row)
                check_running(db, row[0], nonce)
                require(db.execute("UPDATE slots SET seen=1 WHERE role=? AND seen=0",
                                   row).rowcount == 1)
            code = 401
        except (Refused, sqlite3.Error):
            cancel(self.server.window)
            code = 404
        self.send_response_only(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def send_error(self, *args, **kwargs):
        cancel(self.server.window)
        self.send_response_only(404)
        self.send_header("Content-Length", "0")
        self.end_headers()


def once(path, role, nonce, host, ip, port=443, fixture_ca=None):
    # Run in a dedicated worker process. A blocking resolver/read cannot
    # extend the slot's end-to-end deadline.
    with transaction(path) as db:
        deadline = check_running(db, role, nonce)
        require(db.execute("INSERT OR IGNORE INTO attempts VALUES (?)", (role,)).rowcount == 1)
    timer = threading.Timer(max(0, deadline - time.monotonic()), os._exit, args=(124,))
    timer.daemon = True
    timer.start()
    stage, category, matched, verified = 'configuration', 'refused', False, False
    connected = False
    try:
        require(re.fullmatch("[a-f0-9]{48}", nonce))
        require(not os.environ.get('SSLKEYLOGFILE'))
        if fixture_ca is None:
            require(port == 443 and re.fullmatch(r"[a-z0-9.-]+\.ts\.net", host))
            ips = [ipaddress.ip_address(item[4][0]) for item in
                   socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)]
            require(ips and all(i.is_global and not i.is_multicast and not i.is_reserved for i in ips)
                    and ipaddress.IPv4Address(ip) in ips)
        else:
            require(ip == '127.0.0.1' and host.endswith('.invalid'))
        ctx = ssl.create_default_context(cafile=fixture_ca)
        require(ctx.check_hostname and ctx.verify_mode == ssl.CERT_REQUIRED)
        attempted = False

        def dial(target, *args, **kwargs):
            nonlocal stage, matched, connected, attempted
            require(not attempted and target == (host, port))
            attempted = True
            with transaction(path) as db:
                check_running(db, role, nonce)
            stage = 'tcp'
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                sock.settimeout(max(0.001, deadline - time.monotonic()))
                sock.connect((ip, port))
                connected = True
                matched = sock.family == socket.AF_INET and sock.getpeername() == (ip, port)
                require(matched)
                stage = 'tls'
                return sock
            except BaseException:
                sock.close()
                raise

        class Pinned(http.client.HTTPSConnection):
            def connect(self):
                nonlocal stage, verified
                self._create_connection = dial
                super().connect()
                require(self.sock.getpeercert())
                verified = True
                stage = 'http'
                with transaction(path) as db:
                    check_running(db, role, nonce)

        class Handler(urllib.request.HTTPSHandler):
            def https_open(self, req):
                return self.do_open(Pinned, req, context=ctx)

        client = probe.Client("https://" + host + ":" + str(port))
        client.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), probe.NoRedirect(), Handler())
        client.opener.addheaders.append((HEADER, nonce))
        status, data = client.request(STATUS)
        category = 'http-401' if status == 401 and not data else 'unexpected'
    except (Refused, OSError, ValueError, http.client.HTTPException, urllib.error.URLError) as err:
        label = probe.failure_category(err)
        category = label if label in ('tls-eof', 'tls-certificate', 'timeout') else 'refused'
    finally:
        timer.cancel()
    return dict(role=role, family='IPv4' if connected else 'unknown', stage=stage, category=category,
                peer_match=matched, tls_verified=verified, http_401=category == 'http-401',
                nonce_correlated=False, accepted=False)


def watch(path, rules, ready, interval=0.05):
    """Own the lifecycle. The launcher exits independently after ready().

    Adapter operations are conditional on ownership. Readback checks both views.
    """
    opened = False
    clean = False
    # A stale or duplicate invocation has no authority to touch the adapter
    # or cancel the supervisor that already owns this window.
    with transaction(path) as db:
        require(db.execute("UPDATE window SET state='starting' WHERE state='armed' "
                           "AND stop>=? AND utc>=?",
                           (time.monotonic() + 40, time.time() + 40)).rowcount == 1)
    try:
        rules.open_if_empty()
        opened = True
        with transaction(path) as db:
            require(db.execute("UPDATE window SET state='open' WHERE state='starting' "
                               "AND stop>=? AND utc>=?",
                               (time.monotonic() + 40, time.time() + 40)).rowcount == 1)
        ready()
        while True:
            with transaction(path) as db:
                if remaining(db) <= 30:
                    break
            time.sleep(interval)
    finally:
        try:
            try:
                cancel(path)
            finally:
                if opened:
                    clean = rules.remove_if_owned() and rules.views_empty()
        finally:
            with transaction(path) as db:
                db.execute("UPDATE window SET state=?", ('rule-cleared' if clean else 'alarm',))
    return 0 if clean else 1


def public_receipt(row, role):
    """Copy only the exact typed schema at the worker/output boundary."""
    fields = {'peer_match', 'tls_verified', 'http_401', 'nonce_correlated', 'accepted'}
    require(type(row) is dict and set(row) == fields | {'role', 'family', 'stage', 'category'})
    require(all(type(row[key]) is bool for key in fields))
    require(role in ('H', 'E') and row['role'] == role)
    require(row['family'] in ('unknown', 'IPv4') and row['stage'] in ('configuration', 'tcp', 'tls', 'http'))
    require(row['category'] in ('tls-eof', 'tls-certificate', 'timeout', 'refused', 'http-401', 'unexpected'))
    return dict(row)


def run_slot(path, role, host, ip, port=443, fixture_ca=None):
    """Dispatch locally, validate a terminated worker, then unlock the next slot."""
    nonce = grant(path, role)
    try:
        with transaction(path) as db:
            deadline = check_running(db, role, nonce)
        payload = json.dumps([role, nonce, host, ip, port, fixture_ca]).encode()
        child = subprocess.run([sys.executable, "-I", "-B", __file__, "worker", str(path)],
                               input=payload, capture_output=True,
                               timeout=max(0, deadline - time.monotonic()))
        require(child.returncode == 0 and not child.stderr)
        row = public_receipt(json.loads(child.stdout), role)
        encoded = json.dumps(row).encode()
        require(child.stdout in (encoded + b"\n", encoded + b"\r\n"))
        require(not row['accepted'] and not row['nonce_correlated'])
        if row['category'] in ('http-401', 'tls-eof'):
            http = row['category'] == 'http-401'
            require(row['family'] == 'IPv4' and row['peer_match']
                    and row['stage'] == ('http' if http else 'tls')
                    and row['tls_verified'] == row['http_401'] == http)
        row['nonce_correlated'], row['accepted'] = finish(path, role, nonce, row['category'])
        return public_receipt(row, role)
    except BaseException:
        cancel(path)
        raise


if __name__ == '__main__':
    try:
        require(len(sys.argv) == 3 and sys.argv[1] == "worker")
        role, nonce, host, ip, port, ca = json.load(sys.stdin)
        receipt = once(Path(sys.argv[2]), role, nonce, host, ip, port, ca)
        print(json.dumps(public_receipt(receipt, role)))
    except Exception:
        sys.exit(1)
