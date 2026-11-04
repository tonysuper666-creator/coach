"""Real launchd/JVM smoke: an unlocked transaction must not stop the test job.

This checks only the fail-closed entry, not a staged switch or rollback. The
test JVM deliberately runs unmanaged and does not exercise candidate services.
"""
import json
import os
from pathlib import Path
import plistlib
import shutil
import socket
import subprocess
import sys
import time
import uuid

from .rehearse_ton1175_launchd import port
from .ton1175_switch import atomic, require


def command(*args):
    return subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True, timeout=30)


def main():
    name = uuid.uuid4().hex
    root = Path('/tmp').resolve() / ('ton1193-' + name)
    root.mkdir(mode=0o700)
    label = 'local.coach.ton1193.test.' + name
    target = f'gui/{os.getuid()}/{label}'
    plist = root / 'home/Library/LaunchAgents/service.plist'
    pid = None
    child_pids = []
    service_port = None
    clean = False
    try:
        for relative in ('home/Library/LaunchAgents', 'home/Coach/service/releases/old',
                         'home/Coach/data', 'home/Coach/logs', 'home/Coach/run',
                         'home/Coach/bridge', 'stage'):
            (root / relative).mkdir(mode=0o700, parents=True, exist_ok=True)
        release = root / 'home/Coach/service/releases/old'
        shutil.copytree('server/app/build/install/app', release / 'app')
        java = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
        service_port, bridge_port = port(), port()
        while service_port == bridge_port:
            bridge_port = port()
        home = root / 'home'
        env = dict(HOME=str(home), JAVA_HOME=java,
            JAVA_OPTS=f'-Xmx128m -XX:ActiveProcessorCount=2 -Duser.home={home}',
            COACH_DATA_DIR=str(home / 'Coach/data'), COACH_TOKEN_FILE=str(home / 'Coach/data/token'),
            COACH_LOG_DIR=str(home / 'Coach/logs'), COACH_STATE_DIR=str(home / 'Coach/run'),
            COACH_PACKS_DIR=str(home / 'Coach/data/packs'),
            COACH_ACTIVITY_SOCKET=str(home / 'Coach/run/activity.sock'),
            COACH_TAIL_HOST='', COACH_MANAGE_SERVICES='0', COACH_TRAFFIC='test',
            COACH_SOURCE_LOOKUP='false', COACH_DEVICE_ID=label, COACH_PORT=str(service_port),
            COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')
        atomic(plist, plistlib.dumps(dict(Label=label, RunAtLoad=True, KeepAlive=False,
            ProgramArguments=[str(release / 'app/bin/app')], WorkingDirectory=str(release),
            EnvironmentVariables=env, StandardOutPath=str(root / 'stdout'),
            StandardErrorPath=str(root / 'stderr'))))
        config = {'plist': str(plist), 'installation': str(home / 'Coach/service/installation.json'),
                  'stateDir': str(root / 'stage'), 'adapterSnapshotDir': str(root / 'stage/snapshot'),
                  'adapterManifest': str(root / 'manifest.json'),
                  'release': str(home / 'Coach/service/releases/new'),
                  'adapterEnvPins': {'COACH_TAIL_HOST': '', 'COACH_MANAGE_SERVICES': '1',
                                     'COACH_PORT': str(service_port), 'BRIDGE_PORT': str(bridge_port)}}
        atomic(root / 'controller.json', json.dumps(config).encode())
        atomic(root / 'stage/lock', b'')
        atomic(root / 'stage/journal.json', b'{"phase":"switching"}')
        result = command('/bin/launchctl', 'bootstrap', f'gui/{os.getuid()}', str(plist))
        require(result.returncode == 0, 'isolated launchd bootstrap failed')
        from . import ton1193_controller as controller, ton1193_started as started
        from unittest.mock import patch
        deadline = time.monotonic() + 25
        with patch.object(controller, '_target', lambda: target):
            while time.monotonic() < deadline:
                pid = controller._job(str(plist))
                if pid:
                    identity = command('/bin/ps', '-p', str(pid), '-o', 'command=')
                    if b'com.coach.server.MainKt' in identity.stdout:
                        break
                time.sleep(.1)
            else:
                raise RuntimeError('isolated JVM did not start')
            image = started.process._image(pid)
            child_pids = started.process._children(pid)
            refusal = command(sys.executable, '-m', 'tools.dev.ton1193_isolated_controller',
                              '--config', str(root / 'controller.json'), '--apply', 'stop')
            require(refusal.returncode != 0 and b'transaction lock not held' in refusal.stderr,
                    'unlocked stop was not refused')
            require(controller._job(str(plist)) == pid, 'refusal changed isolated job')
            print(json.dumps({'label': label, 'pid': pid, 'image': image, 'port': service_port,
                              'journal': 'switching', 'stopExit': refusal.returncode,
                              'reason': 'transaction lock not held', 'jobPreserved': True,
                              'children': child_pids}), flush=True)
    finally:
        # The unique label is checked against its private plist before removal.
        from . import ton1193_controller as controller
        from unittest.mock import patch
        with patch.object(controller, '_target', lambda: target):
            job = controller._job(str(plist))
            if job is not None:
                result = command('/bin/launchctl', 'bootout', target)
                require(result.returncode == 0, 'isolated label cleanup failed')
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline and controller._job(str(plist)) is not None:
                    time.sleep(.1)
            require(controller._job(str(plist)) is None, 'isolated label remains')
        if pid:
            require(command('/bin/ps', '-p', str(pid), '-o', 'command=').returncode != 0,
                    'isolated PID remains or was reused')
        for child in child_pids:
            require(command('/bin/ps', '-p', str(child), '-o', 'command=').returncode != 0,
                    'isolated child PID remains or was reused')
        if service_port:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', service_port))
        shutil.rmtree(root)
        clean = not root.exists()
        print(json.dumps({'labelAbsent': True, 'pidAbsent': True, 'childrenAbsent': True,
                          'portFree': True, 'rootRemoved': clean}), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
