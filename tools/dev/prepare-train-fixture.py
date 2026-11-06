#!/usr/bin/env python3
"""Prepare a synthetic private v6 input using the actual old server binary.

No installed data, existing credentials, production ports or real tutors are used.
The resulting data/token directory is private and must never be uploaded.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import sqlite3
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('smoke', ROOT / 'tools/dev/check-candidate-smoke.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-distribution', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    base = args.out.resolve()
    assert not base.is_relative_to((Path.home() / 'Coach').resolve())
    base.mkdir(mode=0o700, exist_ok=False)
    data = base / 'data'; data.mkdir(mode=0o700)
    token = secrets.token_urlsafe(32)
    (data / 'device-token').write_text(token)
    port = smoke.free_port()
    assert port not in {8790, 8791, 18791, 3790}
    env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))
           and k not in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    for name in ('home', 'tmp', 'packs'):
        (base / name).mkdir(mode=0o700)
    env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'), COACH_DEVICE_ID='desktop',
               COACH_PORT=str(port), COACH_TAIL_HOST='', COACH_PACKS_DIR=str(base / 'packs'),
               COACH_MANAGE_SERVICES='0', COACH_SOURCE_LOOKUP='false', COACH_TRAFFIC='test',
               COACH_LOG_DIR=str(base / 'logs'), COACH_RUN_DIR=str(base / 'run'), COACH_STATE_DIR=str(base / 'state'),
               COACH_ACTIVITY_SOCKET=str(base / 'activity.sock'), COACH_BRIDGE_URL='http://127.0.0.1:1',
               JAVA_OPTS=f'-Duser.home={base}/home -Djava.io.tmpdir={base}/tmp')
    with (base / 'private-server.log').open('w') as log:
        process = subprocess.Popen([str(args.old_distribution.resolve() / 'bin/app')], env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                assert process.poll() is None, 'Old fixture binary exited; private log withheld'
                try:
                    request = urllib.request.Request(f'http://127.0.0.1:{port}/v1/hello', headers={'Authorization': 'Bearer ' + token, 'X-Coach-Device': 'desktop'})
                    with urllib.request.urlopen(request, timeout=2) as response:
                        assert response.status == 200
                        break
                except OSError:
                    time.sleep(.2)
            else:
                raise TimeoutError('Old fixture readiness')
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    with sqlite3.connect(data / 'coach.sqlite') as db:
        assert db.execute('pragma user_version').fetchone()[0] == 6
        payload = {'course': {'tz': 'Europe/London'}, 'ops': [{'op': 'add', 'kpId': 'original-kp', 'title': 'Synthetic original objective', 'conceptIds': ['synthetic']}]}
        event = dict(seq=1, id='original-event', courseId='synthetic-original', type='outline_revised', schema=1,
                     recordedAt=1, occurredAt=1, deviceId='desktop', actor='LEARNER', effectIndex=0, idemKey='original-event', payload=payload)
        db.execute('insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                   (1, event['id'], event['courseId'], event['type'], 1, 1, 1, 'desktop', 'LEARNER', 0, event['idemKey'], json.dumps(payload), json.dumps(event)))
        db.execute('insert or replace into devices(device_id,token_sha256,created_at) values(?,?,?)', ('paired-synthetic', hashlib.sha256(b'synthetic-paired-token').hexdigest(), 1))
        assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
    print(json.dumps(dict(fixture='synthetic private v6', schema=6, originalEvents=1, pairedCredential=True, oldTokenAuthenticated=True, childReaped=True, productionConnections=0)))


if __name__ == '__main__':
    main()
