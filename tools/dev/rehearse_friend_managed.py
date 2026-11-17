#!/usr/bin/env python3
"""Foreground macOS managed-service rehearsal. No production or model requests.

The private config contains release, manifest, manifestSha256. The candidate
must already have a clean-checkout attestation. Only the controller's LABEL
constant is namespaced in a private copy; readiness/start/stop run unchanged in
separate processes. HOME, all data and three listeners are isolated. A security
shim blocks run.sh's Keychain reads. This does not prove tailnet, GUI, API costs,
pointer rollback, or production readiness.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import traceback
import uuid

try:
    from . import ton1175_verify as verify
    from .ton1175_switch import GateError, atomic, file_hash, load, require, tree_hash
except ImportError:
    import ton1175_verify as verify
    from ton1175_switch import GateError, atomic, file_hash, load, require, tree_hash


def private_json(path):
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), 'private regular config required')
    st = path.stat()
    require(st.st_uid == os.getuid() and st.st_mode & 0o777 == 0o600, 'private config permissions')
    return load(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--receipt', required=True)
    args = parser.parse_args()
    receipt_path = Path(args.receipt).resolve()
    require(not receipt_path.exists(), 'receipt must be new')
    c = private_json(args.config)
    require(set(c) == {'release', 'manifest', 'manifestSha256'}, 'config schema')
    require(file_hash(c['manifest']) == c['manifestSha256'], 'manifest binding')
    proof = load(c['manifest'])
    release = Path(c['release']).resolve(strict=True)
    require(str(release) == proof['root'], 'no candidate relocation')
    controller_source = Path(__file__).with_name('coach-service.py').read_text()
    marker = "LABEL = 'local.coach.server'"
    require(controller_source.count(marker) == 1, 'controller namespace substitution')
    protocol_spec = importlib.util.spec_from_file_location('friend_public', Path(__file__).parents[1] / 'smoke/friend-public.py')
    protocol = importlib.util.module_from_spec(protocol_spec)
    protocol_spec.loader.exec_module(protocol)
    root = Path(tempfile.mkdtemp(prefix='coach-managed-', dir='/tmp')).resolve()
    root.chmod(0o700)
    label = 'local.coach.ton1175.test.' + uuid.uuid4().hex
    target = f'gui/{os.getuid()}/{label}'
    receipt = dict(candidateHead=proof['sourceHead'], result='FAIL', controllerLabelOnlySubstitution=True,
                   productionTouched=False, modelRequests=0, tailnetValidated=False, nativeOrExternal=False)
    stage = 'prepare'
    env = None
    plist = None
    control = root / 'home/Coach/service/control.py'
    old_umask = os.umask(0o077)
    try:
        for name in ('home/Coach/service', 'home/Library/LaunchAgents', 'data', 'logs', 'state',
                     'packs', 'run', 'library', 'generated', 'mock', 'bin', 'bridge'):
            (root / name).mkdir(parents=True, exist_ok=True, mode=0o700)
        atomic(root / 'library/catalog.json', b'{"schema":1,"documents":[]}\n')
        atomic(root / 'friend-config.json', b'{"paused":true,"defaults":{"daily_calls":1,"daily_tokens":256}}\n')
        atomic(root / 'bin/security', b'#!/bin/sh\nexit 1\n')
        (root / 'bin/security').chmod(0o700)
        atomic(control, controller_source.replace(marker, f'LABEL = {label!r}').encode())
        ports = []
        while len(ports) < 3:
            with socket.socket() as s:
                s.bind(('127.0.0.1', 0))
                port = s.getsockname()[1]
            if port not in (8790, 8791, 18791, 3790, 19540) and port not in ports:
                ports.append(port)
        private, public, bridge = ports
        java_home = Path(proof['java']).parent.parent
        env = dict(HOME=str(root / 'home'), PATH=f'{root / "bin"}:{Path(proof["node"]).parent}:/usr/bin:/bin:/usr/sbin:/sbin',
                   JAVA_HOME=str(java_home), JAVA_OPTS=f'-Xmx192m -XX:ActiveProcessorCount=2 -Duser.home={root / "home"}',
                   PYTHONDONTWRITEBYTECODE='1', COACH_MANAGE_SERVICES='1', COACH_TRAFFIC='test',
                   COACH_SERVICE_ROOT=str(release), COACH_PYTHON=str(release / 'tools/bridge/.venv/bin/python'),
                   COACH_NODE=proof['node'], COACH_MATHSVC=str(release / 'tools/mathsvc/src/server.mjs'),
                   COACH_DATA_DIR=str(root / 'data'), COACH_TOKEN_FILE=str(root / 'data/device-token'),
                   COACH_RUN_DIR=str(root / 'run'), COACH_LOG_DIR=str(root / 'logs'), COACH_STATE_DIR=str(root / 'state'),
                   COACH_API_STATE_DIR=str(root / 'state'), COACH_BRIDGE_HOME=str(root / 'bridge'),
                   COACH_FRIEND_CONFIG=str(root / 'friend-config.json'),
                   COACH_PACKS_DIR=str(root / 'packs'), COACH_GENERATED_DIR=str(root / 'generated'),
                   COACH_MOCK_DIR=str(root / 'mock'), COACH_READER_LIBRARY=str(root / 'library'),
                   COACH_ACTIVITY_SOCKET=str(root / 'state/activity.sock'), COACH_SOURCE_LOOKUP='false',
                   COACH_TAIL_HOST='', COACH_DEVICE_ID='desktop', COACH_PORT=str(private), COACH_PUBLIC_PORT=str(public),
                   BRIDGE_PORT=str(bridge), COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge}', BRIDGE_LOG_STDOUT='1')
        plist = root / f'home/Library/LaunchAgents/{label}.plist'
        atomic(plist, plistlib.dumps(dict(Label=label, RunAtLoad=True, KeepAlive=False,
               ProgramArguments=[str(release / 'app/bin/app')], WorkingDirectory=str(release),
               EnvironmentVariables=env, StandardOutPath=str(root / 'stdout.log'), StandardErrorPath=str(root / 'stderr.log'))))
        atomic(control.with_name('installation.json'), (json.dumps(dict(root=str(release), sourceHead=proof['sourceHead'],
               dataDir=str(root / 'data'), runDir=str(root / 'run'), port=private, tailHost='127.0.0.1')) + '\n').encode())
        verify.candidate(c['manifest'], c['manifestSha256'], str(release), proof['sourceHead'], str(plist))
        before = tree_hash(release)

        def cli(command):
            with (root / 'controller.log').open('ab') as log:
                subprocess.run([sys.executable, str(control), command], env=env, stdout=log,
                               stderr=subprocess.STDOUT, timeout=90, check=True)

        def owner_pid():
            out = subprocess.check_output(['/bin/launchctl', 'print', target], stderr=subprocess.DEVNULL)
            return int(re.search(rb'^\s*pid = (\d+)', out, re.M)[1])

        def states():
            result = {name: load(root / f'run/{name}.json') for name in ('bridge', 'mathsvc')}
            require(all(s['healthy'] and s['pid'] > 0 and s['ownerPid'] == owner_pid()
                        and 0 <= time.time() * 1000 - s['checkedAt'] < 15000 for s in result.values()), 'owned fresh health')
            return result

        stage = 'start'
        cli('start')
        initial = states()
        pid = owner_pid()
        stage = 'idempotent-start-and-pair'
        cli('start')
        cli('pair-code')
        cli('status')
        require(owner_pid() == pid, 'start/pair must not restart ready service')
        require(all(states()[name]['pid'] == initial[name]['pid'] for name in initial), 'same children after start/pair')
        receipt.update(controllerCommands=4, managedBridge=True, managedMath=True, idempotentStart=True, ownerPairCode=True)

        stage = 'protocol'
        owner_client = protocol.Client(f'http://127.0.0.1:{private}')
        public_client = protocol.Client(f'http://127.0.0.1:{public}')
        owner = dict(deviceId='desktop', token=(root / 'data/device-token').read_text().strip())
        require(owner_client.request('/v1/hello', auth=owner)[0] == 200, 'owner authorization')
        friends = []
        for name in ('isolated managed A', 'isolated managed B'):
            status, body = owner_client.request('/v1/pairing-code', 'POST', owner, dict(friend=name))
            require(status == 200, 'issue friend code')
            status, body = public_client.request('/v1/pair', 'POST', body=dict(code=json.loads(body)['code'], name='managed fixture'))
            require(status == 200, 'pair friend')
            friends.append(json.loads(body))
        friend = friends[0]
        require(public_client.request('/v1/public/status')[0] == 401, 'anonymous denied')
        require(public_client.request('/v1/public/status', auth=friend)[0] == 200, 'friend authorized')
        for path in protocol.DENIED:
            method = 'POST' if any(p in path for p in ('commands', 'pairing-code', 'prewarm')) else 'GET'
            require(public_client.request(path, method, friend) == (404, b''), 'private path empty 404')
        for method, path in protocol.WRONG_METHODS:
            require(public_client.request(path, method, friend) == (404, b''), 'wrong method empty 404')
        receipt.update(privatePaths=len(protocol.DENIED), wrongMethods=len(protocol.WRONG_METHODS))
        require(owner_client.request('/v1/friends/revoke', 'POST', owner, dict(friend='isolated managed A'))[0] == 204, 'revoke A')
        require(public_client.request('/v1/public/status', auth=friends[0])[0] == 401, 'revoked A denied')
        require(public_client.request('/v1/public/status', auth=friends[1])[0] == 200, 'B unaffected')
        receipt['revokedA401OtherB200'] = True

        stage = 'child-restart'
        # Only signal the child recorded by this JVM; recheck its parent guard.
        child = states()['bridge']
        parent = subprocess.check_output(['/bin/ps', '-p', str(child['pid']), '-o', 'ppid='], text=True).strip()
        require(int(parent) == child['guardPid'], 'bridge child identity')
        os.kill(child['pid'], signal.SIGTERM)
        deadline = time.monotonic() + 45
        while True:
            try:
                current = states()
                if current['bridge']['pid'] != child['pid']:
                    break
            except (OSError, ValueError, GateError):
                pass
            require(time.monotonic() < deadline, 'child recovery deadline')
            time.sleep(.25)
        require(owner_pid() == pid, 'child restart preserves JVM')
        require(owner_client.request('/v1/hello', auth=owner)[0] == 200, 'owner survives child restart')
        receipt['bridgeRecoveredWithoutJvmRestart'] = True
        stage = 'stop'
        cli('stop')
        require(subprocess.run(['/bin/launchctl', 'print', target], capture_output=True).returncode != 0, 'job removed')
        require(not any((root / f'run/{n}.json').exists() for n in ('bridge', 'mathsvc')), 'health files cleaned')
        for port in ports:
            with socket.socket() as s:
                s.bind(('127.0.0.1', port))
        require(tree_hash(release) == before, 'immutable candidate')
        receipt.update(result='PASS', stopVerified=True, listenersReleased=3, candidateUnchanged=True)
    except Exception:
        atomic(root / 'failure.log', traceback.format_exc().encode())
        receipt['failedStage'] = stage
    finally:
        # Never return with a run-owned job alive, even when readiness failed.
        if env is not None and plist is not None:
            with (root / 'cleanup.log').open('ab') as log:
                cleanup = subprocess.run([sys.executable, str(control), 'stop'], env=env,
                                         stdout=log, stderr=subprocess.STDOUT, timeout=90)
            if cleanup.returncode != 0:
                receipt.update(result='FAIL', failedStage='cleanup')
            else:
                plist.unlink(missing_ok=True)
                subprocess.run(['/bin/launchctl', 'enable', target], check=True, capture_output=True)
        atomic(receipt_path, (json.dumps(receipt, sort_keys=True) + '\n').encode())
        os.umask(old_umask)
        if receipt['result'] == 'PASS':
            shutil.rmtree(root)
        else:
            # Private location, never printed or published.
            atomic(receipt_path.with_suffix('.diagnostics'), str(root).encode())
    print('MANAGED_REHEARSAL=' + receipt['result'])
    return 0 if receipt['result'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
