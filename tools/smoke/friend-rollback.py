#!/usr/bin/env python3
"""Real JVM rollback rehearsal; isolated data only, no bridge or paid requests.

--config is a private JSON file containing oldApp, candidateApp (bin/app paths),
oldHead, candidateHead and tailscale. It never contains credentials or URLs.
Funnel must already have an empty configuration; this script never enables it.
This does not exercise LaunchAgent pointer transactions or native clients.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dev'))
import friend_service_guard as guard

spec = importlib.util.spec_from_file_location('friend_public', Path(__file__).with_name('friend-public.py'))
protocol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protocol)


def require(value):
    if not value:
        raise RuntimeError('rehearsal check failed')


def port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def manifest(binary):
    root = binary.parent.parent
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob('*')) if p.is_file()}


def rehearse(config):
    require(set(config) == {'oldApp', 'candidateApp', 'oldHead', 'candidateHead', 'tailscale'})
    for key in ('oldHead', 'candidateHead'):
        require(re.fullmatch('[0-9a-f]{40}', config[key]) is not None)
    require(config['oldHead'] != config['candidateHead'])
    old, candidate = [Path(config[key]).resolve(strict=True) for key in ('oldApp', 'candidateApp')]
    before = {key: manifest(binary) for key, binary in [('old', old), ('candidate', candidate)]}
    # Use the real installed CLI, without touching any existing rule.
    require(json.loads(guard.captured([config['tailscale'], 'funnel', 'status', '--json'])) == {})
    with tempfile.TemporaryDirectory(prefix='coach-friend-rollback-') as directory:
        root = Path(directory).resolve()
        root.chmod(0o700)
        private, public = port(), port()
        require(private != public and not {private, public} & {18791, 8790, 8791, 3790, 19540})
        data = root / 'data'
        data.mkdir(mode=0o700)
        library = root / 'library'
        library.mkdir(mode=0o700)
        (library / 'catalog.json').write_text('{"schema":1,"documents":[]}')
        env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG') if key in os.environ}
        env.update(HOME=str(root), JAVA_OPTS=f'-Duser.home={root}', COACH_MANAGE_SERVICES='0',
                   COACH_DATA_DIR=str(data), COACH_PORT=str(private), COACH_TAIL_HOST='',
                   COACH_GENERATED_DIR=str(root / 'generated'), COACH_MOCK_DIR=str(root / 'mock'),
                   COACH_READER_LIBRARY=str(root / 'library'), COACH_API_STATE_DIR=str(root / 'state'),
                   COACH_STATE_DIR=str(root / 'state'), COACH_LOG_DIR=str(root / 'logs'),
                   COACH_BRIDGE_URL='http://127.0.0.1:1')
        owner_client, friend_client = protocol.Client(f'http://127.0.0.1:{private}'), protocol.Client(f'http://127.0.0.1:{public}')
        process = None
        log = (root / 'jvm.log').open('wb')
        def stop():
            nonlocal process
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                    raise RuntimeError('owned JVM did not stop cleanly')
                process = None
            with socket.socket() as listener:
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                listener.bind(('127.0.0.1', private))
            with sqlite3.connect(data / 'coach.sqlite', timeout=0) as db:
                db.execute('BEGIN EXCLUSIVE')
                db.rollback()

        def launch(binary, expose=False):
            nonlocal process
            current = dict(env)
            if expose:
                current['COACH_PUBLIC_PORT'] = str(public)
            process = subprocess.Popen([str(binary)], env=current, stdin=subprocess.DEVNULL, stdout=log, stderr=log)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    log.flush()
                    # main() emits only FAIL. Diagnostics stay available to a
                    # private caller, never as public probe output.
                    raise RuntimeError((root / 'jvm.log').read_text())
                try:
                    token = (data / 'device-token').read_text().strip()
                    credential = {'deviceId': 'desktop', 'token': token}
                    status, body = owner_client.request('/v1/hello', auth=credential)
                    if status == 200:
                        return credential, json.loads(body)
                except OSError:
                    pass
                time.sleep(.1)
            raise RuntimeError('owned JVM not ready')

        try:
            owner, initial = launch(old)
            stop()
            same_owner, migrated = launch(candidate, expose=True)
            require(owner == same_owner and initial['epoch'] == migrated['epoch'])
            payload = json.dumps({'type': 'save_note', 'courseId': 'rehearsal',
                'clientTime': time.time_ns() // 1_000_000,
                'payload': {'text': 'Self-written rollback rehearsal note.'}})
            status, body = owner_client.request('/v1/commands', 'POST', owner, {
                'deviceId': 'desktop', 'commandId': 'post-migration-note',
                'payloadBytes': payload, 'payloadSha256': hashlib.sha256(payload.encode()).hexdigest()})
            require(status == 200)
            status, body = owner_client.request('/v1/pairing-code', 'POST', owner, {'friend': 'rehearsal A'})
            require(status == 200)
            status, body = friend_client.request('/v1/pair', 'POST', body={'code': json.loads(body)['code'], 'name': 'isolated PC'})
            require(status == 200)
            friend = json.loads(body)
            require(friend['role'] == 'friend')
            # Actual listener checks, including the method contract not covered
            # by the earlier mocked probe tests.
            checks = 0
            for path in protocol.DENIED:
                method = 'POST' if any(part in path for part in ('commands', 'pairing-code', 'prewarm')) else 'GET'
                require(friend_client.request(path, method, friend) == (404, b''))
                checks += 1
            for method, path in protocol.WRONG_METHODS:
                require(friend_client.request(path, method, friend) == (404, b''))
                checks += 1
            require(friend_client.request('/v1/public/status')[0] == 401)
            require(friend_client.request('/v1/public/status', auth=friend)[0] == 200)
            require(owner_client.request('/v1/hello', auth=friend)[0] == 404)
            status, body = owner_client.request('/v1/pairing-code', 'POST', owner, {'friend': 'pending rehearsal'})
            require(status == 200)
            pending = json.loads(body)['code']
            # Retain real ledger bytes and event prefix across rollback; never
            # restore an earlier SQLite file or a budget snapshot.
            database = data / 'coach.sqlite'
            with sqlite3.connect(database) as db:
                epoch = db.execute('SELECT epoch FROM ledger_meta WHERE singleton=1').fetchone()[0]
                events = db.execute('SELECT * FROM events ORDER BY seq').fetchall()
                require(events)
            budget = root / 'state' / 'api-credit-ledger.json'
            budget.parent.mkdir(exist_ok=True)
            budget.write_text('{"cycles":{"rehearsal":{"spent":17,"limit":123,"reserved":{"pending":9}}},"months":{"rehearsal":17}}')
            budget_bytes = budget.read_bytes()
            identity = database.stat()
            policy = root / 'friend-start-policy.json'
            policy.write_text(json.dumps({'version': 1, 'binding': {'database': str(database), 'device': identity.st_dev,
                'inode': identity.st_ino, 'epoch': epoch}, 'capableHeads': [config['candidateHead']],
                'tailscale': config['tailscale'], 'publicPort': public}))
            policy.chmod(0o600)
            restored = []
            guard.start(policy, config['oldHead'], database, stop, lambda: restored.append(launch(old)))
            require(restored[0][0] == owner and restored[0][1]['epoch'] == initial['epoch'])
            require(owner_client.request('/v1/hello', auth=friend)[0] == 401)
            require(owner_client.request('/v1/pair', 'POST', body={'code': pending, 'name': 'never enrolled'})[0] == 401)
            stop()
            with sqlite3.connect(database) as db:
                require(db.execute('SELECT * FROM events ORDER BY seq').fetchall() == events)
                require(db.execute('SELECT count(*) FROM devices WHERE friend_name IS NOT NULL AND revoked_at IS NULL').fetchone()[0] == 0)
                require(db.execute('SELECT count(*) FROM pairing_code WHERE friend_name IS NOT NULL').fetchone()[0] == 0)
            require(budget.read_bytes() == budget_bytes)
            require(before == {key: manifest(binary) for key, binary in [('old', old), ('candidate', candidate)]})
            return {'forbidden_paths': checks - len(protocol.WRONG_METHODS), 'wrong_methods': len(protocol.WRONG_METHODS),
                    'revoked_tokens_rejected': 1, 'pending_codes_rejected': 1, 'owner_preserved': 1,
                    'events_preserved': 1, 'budget_bytes_preserved': 1, 'artifacts_unchanged': 1}
        finally:
            try:
                stop()
            finally:
                log.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    try:
        result = rehearse(json.loads(guard.private_file(args.config).read_text()))
    except Exception:
        print('FRIEND_ROLLBACK=FAIL')
        return 1
    print('FRIEND_ROLLBACK=' + json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
