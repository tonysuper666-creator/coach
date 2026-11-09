import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import uuid
from unittest import mock

from tools.dev import ton1193_started as started
from tools.dev.ton1193_trust import file_snapshot
from tools.dev.ton1175_switch import GateError, atomic
from tools.dev.ton1175_launchd import Adapter
from tools.dev.rehearse_ton1175_launchd import port

root = Path(tempfile.mkdtemp(prefix='ton1175-', dir='/tmp')).resolve()
adapter = None
pid = None
try:
    os.chmod(root, 0o700)
    for item in ('home', 'data', 'logs', 'state', 'packs'):
        (root / item).mkdir(mode=0o700)
    release = root / 'candidate'
    shutil.copytree('server/app/build/install/app', release / 'app')
    java_home = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip())
    label = 'local.coach.ton1175.test.' + uuid.uuid4().hex
    service_port, bridge_port = port(), port()
    while bridge_port == service_port:
        bridge_port = port()
    env = dict(HOME=str(root / 'home'), JAVA_HOME=str(java_home),
        JAVA_OPTS=f'-Xmx128m -XX:ActiveProcessorCount=2 -Duser.home={root / "home"}',
        COACH_DATA_DIR=str(root / 'data'), COACH_TOKEN_FILE=str(root / 'data/device-token'),
        COACH_LOG_DIR=str(root / 'logs'), COACH_STATE_DIR=str(root / 'state'),
        COACH_PACKS_DIR=str(root / 'packs'), COACH_ACTIVITY_SOCKET=str(root / 'state/activity.sock'),
        COACH_TAIL_HOST='', COACH_MANAGE_SERVICES='0', COACH_TRAFFIC='test',
        COACH_SOURCE_LOOKUP='false', COACH_DEVICE_ID=label, COACH_PORT=str(service_port),
        COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')
    plist = root / 'service.plist'
    installation = root / 'installation.json'
    atomic(plist, plistlib.dumps(dict(Label=label, RunAtLoad=True, KeepAlive=False,
        ProgramArguments=[str(release / 'app/bin/app')], WorkingDirectory=str(release),
        EnvironmentVariables=env, StandardOutPath=str(root / 'stdout.log'),
        StandardErrorPath=str(root / 'stderr.log'))))
    atomic(installation, json.dumps({'root': str(release)}).encode())
    config = root / 'adapter.json'
    atomic(config, json.dumps(dict(root=str(root), label=label, domain=f'gui/{os.getuid()}',
        port=service_port, plist=str(plist), installation=str(installation))).encode())
    adapter = Adapter(config)
    adapter.start()
    pid = adapter.job()
    side = {'release': str(release),
            'java': file_snapshot(str((java_home / 'bin/java').resolve()), '/opt/homebrew/Cellar'),
            'node': None, 'python': [None, None, None], 'stub': None}
    sides = {'old': {'release': str(root / 'old')}, 'new': side}
    roots = {'cellar': '/opt/homebrew/Cellar', 'node': str(root)}
    with mock.patch.object(started, '_job', side_effect=lambda _: adapter.job()):
        evidence = started._stop_binding(pid, sides, roots, None, str(plist))
        try:
            started._stop_binding(pid, sides, roots, 'old', str(plist))
        except GateError as exc:
            assert str(exc) == 'stop process release differs from journal'
        else:
            raise AssertionError('wrong release accepted')
    print(json.dumps({'label': label, 'port': service_port, 'pid': pid,
                      'jvmImage': evidence['image'], 'children': len(evidence['children']),
                      'correctSide': 'passed', 'wrongSide': 'rejected'}), flush=True)
finally:
    if adapter is not None:
        adapter.stop()
        assert adapter.job() is None
        if pid:
            result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'command='], capture_output=True)
            assert result.returncode != 0 or str(root).encode() not in result.stdout
        print(json.dumps({'isolatedJobAbsent': True, 'originalPidGoneOrReused': True}), flush=True)
    shutil.rmtree(root)
    assert not root.exists()
    print(json.dumps({'privateRootRemoved': True}), flush=True)
