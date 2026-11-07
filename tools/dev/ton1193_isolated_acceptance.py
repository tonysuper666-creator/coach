"""Read-only acceptance evidence for the disposable TON-1193 fixture only."""
import contextlib
import argparse
import errno
import http.client
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys
import time

from . import ton1193_isolated_controller as isolated
from . import ton1193_live as live
from .ton1175_switch import GateError, atomic, digest, require
from .ton1193_transaction import _read_file
from .ton1193_trust import directory_snapshot


@contextlib.contextmanager
def ledger(roots):
    data = Path(roots['home']) / 'Coach/data'
    directory_snapshot(str(data))
    path = data / 'coach.sqlite'
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        st = os.fstat(fd)
        require(stat.S_ISREG(st.st_mode) and st.st_uid == os.getuid() and st.st_nlink == 1,
                'invalid isolated ledger')
        db = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=1)
        try:
            now = os.stat(path, follow_symlinks=False)
            require((now.st_dev, now.st_ino) == (st.st_dev, st.st_ino), 'isolated ledger replaced')
            db.execute('PRAGMA query_only=ON')
            db.execute('BEGIN')
            yield db
            now = os.stat(path, follow_symlinks=False)
            require((now.st_dev, now.st_ino) == (st.st_dev, st.st_ino), 'isolated ledger replaced')
        finally:
            db.close()
    finally:
        os.close(fd)


def ledger_identity(db, observed):
    row = db.execute("SELECT epoch FROM ledger_meta WHERE singleton=1").fetchone()
    device = db.execute("SELECT token_sha256 FROM devices WHERE device_id='desktop' AND revoked_at IS NULL").fetchone()
    require(row is not None and row[0] == observed['epoch'] and device is not None
            and device[0] == observed['credentialSha256'], 'isolated ledger identity changed')
    return db.execute('SELECT coalesce(max(seq),0) FROM events').fetchone()[0]


def learning_evidence(db, before, started, deadline):
    """Read only counts and identifiers from new synthetic lesson events."""
    start_ms, end_ms = int(started * 1000), int(min(deadline, time.time()) * 1000)
    lecture = db.execute('''SELECT count(*) FROM events e JOIN messages m
        ON m.message_id=json_extract(e.payload,'$.msgId') AND m.thread_id=e.thread_id
        WHERE e.seq>? AND e.recorded_at BETWEEN ? AND ? AND e.course_id='course-1'
        AND e.thread_id='thread-1' AND e.type='explanation_delivered'
        AND m.status='complete' AND coalesce(m.role,'assistant')='assistant'
        AND length(trim(m.content_md))>0 AND m.created_at BETWEEN ? AND ?''',
        (before['seq'], start_ms, end_ms, start_ms, end_ms)).fetchone()[0] > 0
    groups = db.execute('''WITH fresh AS (SELECT * FROM events WHERE seq>?
        AND recorded_at BETWEEN ? AND ? AND course_id='course-1' AND thread_id='thread-1')
        SELECT json_extract(p.payload,'$.groupId'), count(DISTINCT json_extract(p.payload,'$.attemptId'))
        FROM fresh p JOIN fresh a ON a.type='answer_submitted' AND a.device_id='desktop'
          AND json_extract(a.payload,'$.attemptId')=json_extract(p.payload,'$.attemptId') AND a.seq>p.seq
        JOIN fresh g ON g.type='attempt_graded'
          AND json_extract(g.payload,'$.attemptId')=json_extract(p.payload,'$.attemptId') AND g.seq>a.seq
        WHERE p.type='question_posed' AND json_extract(p.payload,'$.groupId')<>''
          AND json_extract(p.payload,'$.attemptId')<>''
        GROUP BY json_extract(p.payload,'$.groupId')''',
        (before['seq'], start_ms, end_ms)).fetchall()
    return lecture and any(count >= 3 for _, count in groups)


def token_identity(roots):
    data = Path(roots['home']) / 'Coach/data'
    identity = directory_snapshot(str(data))
    token, _ = _read_file(data / 'device-token', identity, 0o600)
    credential = token.decode('ascii').strip()
    require(bool(credential) and '\n' not in credential and '\r' not in credential,
            'invalid isolated token')
    return credential, digest(token), digest(credential.encode())


def observe(config, roots):
    credential, token_hash, credential_hash = token_identity(roots)
    connection = http.client.HTTPConnection('127.0.0.1', int(config['adapterEnvPins']['COACH_PORT']), timeout=5)
    try:
        connection.request('GET', '/v1/hello', headers={
            'X-Coach-Device': 'desktop', 'Authorization': 'Bearer ' + credential})
        response = connection.getresponse()
        body = response.read(65537)
        require(response.status == 200 and len(body) <= 65536, 'isolated authenticated hello failed')
        hello = json.loads(body)
        require(isinstance(hello['epoch'], str) and bool(hello['epoch'])
                and type(hello['lastSeq']) is int and hello['lastSeq'] >= 0,
                'invalid isolated hello')
        return {'epoch': hello['epoch'], 'lastSeq': hello['lastSeq'], 'tokenSha256': token_hash,
                'credentialSha256': credential_hash}
    finally:
        connection.close()


def observe_when_ready(config, roots, deadline):
    """Allow the isolated JVM time to bind, without accepting a failed hello."""
    stop = time.monotonic() + min(60, max(0, deadline - time.time()))
    while True:
        try:
            result = observe(config, roots)
            require(time.time() < deadline and time.monotonic() < stop,
                    'isolated acceptance readiness timed out')
            return result
        except ConnectionRefusedError as exc:
            if exc.errno != errno.ECONNREFUSED or time.monotonic() >= stop:
                raise
            require(live.live_acceptance(config['stateDir']) == 'validating',
                    'isolated acceptance transaction ended')
            time.sleep(min(0.25, max(0, stop - time.monotonic())))


def run(config_path, action, baseline, started=None, deadline=None):
    config, roots, _ = isolated.fixture(config_path)
    require(baseline == str(Path(config['stateDir']) / 'acceptance-baseline.json'),
            'baseline outside isolated stage')
    state_id = directory_snapshot(config['stateDir'])
    if action == 'snapshot':
        # The idle window has already closed.  An HTTP hello here is ingress,
        # so use the private ledger and original token just as the production
        # snapshot does; actual authentication remains in the post-start check.
        _, token_hash, credential_hash = token_identity(roots)
        with ledger(roots) as db:
            epoch = db.execute('SELECT epoch FROM ledger_meta WHERE singleton=1').fetchone()
            require(epoch is not None and isinstance(epoch[0], str) and bool(epoch[0]),
                    'invalid isolated ledger epoch')
            before = {'epoch': epoch[0], 'tokenSha256': token_hash, 'credentialSha256': credential_hash}
            before['seq'] = ledger_identity(db, before)
            before['lastSeq'] = before['seq']
        before['createdAt'] = time.time()
        atomic(Path(baseline), json.dumps(before, sort_keys=True).encode())
        return
    raw, _ = _read_file(baseline, state_id, 0o600)
    before = json.loads(raw)
    now = time.time()
    require(started is not None and deadline is not None and deadline - started == 300
            and before['createdAt'] <= started <= now < deadline
            and started - before['createdAt'] <= 120, 'invalid acceptance deadline or baseline')
    monotonic_deadline = time.monotonic() + deadline - now
    first = True
    while time.monotonic() < monotonic_deadline:
        current = observe_when_ready(config, roots, deadline) if first else observe(config, roots)
        first = False
        require(current['epoch'] == before['epoch'] and current['tokenSha256'] == before['tokenSha256']
                and current['credentialSha256'] == before['credentialSha256']
                and current['lastSeq'] >= before['lastSeq'], 'isolated identity or ledger changed')
        with ledger(roots) as db:
            seq = ledger_identity(db, current)
            require(seq >= before['seq'] and seq >= current['lastSeq'], 'isolated ledger regressed')
            complete = learning_evidence(db, before, started, deadline)
        if complete:
            final = observe(config, roots)
            require(final['epoch'] == before['epoch'] and final['tokenSha256'] == before['tokenSha256']
                    and final['credentialSha256'] == before['credentialSha256']
                    and final['lastSeq'] >= current['lastSeq'], 'isolated identity or ledger changed')
            require(time.monotonic() < monotonic_deadline, 'acceptance deadline expired')
            return
        time.sleep(min(1, max(0, monotonic_deadline - time.monotonic())))
    raise GateError('isolated lecture and exercise evidence timed out')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('action', choices=('snapshot', 'accept'))
    parser.add_argument('baseline')
    parser.add_argument('started', nargs='?', type=float)
    parser.add_argument('deadline', nargs='?', type=float)
    args = parser.parse_args()
    try:
        run(args.config, args.action, args.baseline, args.started, args.deadline)
        return 0
    except (GateError, OSError, ValueError, KeyError, TypeError, sqlite3.Error, http.client.HTTPException):
        print('isolated acceptance refused', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
