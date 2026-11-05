"""Build and preflight two disposable releases with real locked dependencies.

This stage-only check never starts launchd or runs a switch. It deletes its
private fixture on exit; it is not evidence of a successful switch/rollback.
"""
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import uuid

from . import ton1175_switch as switch, ton1175_verify as verify
from . import ton1193_isolated_controller as isolated
from .rehearse_ton1175_launchd import port

REPO = Path(__file__).resolve().parents[2]
OLD = '8d3608f'


def cmd(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    switch.require(not subprocess.check_output(['git', 'status', '--porcelain'], cwd=REPO).strip(),
                   'checkout must be clean')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    old = subprocess.check_output(['git', 'rev-parse', OLD], cwd=REPO, text=True).strip()
    # The old and current revisions may differ in the test harness only. Build
    # inputs must be identical before sharing a real build between the sides.
    cmd('git', 'diff', '--quiet', old, head, '--', 'server', 'tools/bridge',
        'tools/mathsvc', 'tools/dev/service-child.py', 'gradle', 'gradlew',
        'build.gradle.kts', 'settings.gradle.kts', cwd=REPO)
    java_home = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())
    java = java_home / 'bin/java'
    node = Path(shutil.which('node')).resolve()
    python = Path('/opt/homebrew/bin/python3.15')
    build_env = dict(os.environ, JAVA_HOME=str(java_home), PYTHONDONTWRITEBYTECODE='1')
    cmd(REPO / 'tools/dev/heavy', './gradlew', ':server:app:installDist', '--offline',
        cwd=REPO, env=build_env)
    ident = uuid.uuid4().hex
    root = Path('/tmp').resolve() / ('ton1193-' + ident)
    root.mkdir(mode=0o700)
    try:
        home = root / 'home'
        releases = home / 'Coach/service/releases'
        for rel in ('Library/LaunchAgents', 'Coach/service/releases', 'Coach/data',
                    'Coach/logs', 'Coach/run', 'Coach/bridge'):
            (home / rel).mkdir(mode=0o700, parents=True, exist_ok=True)
        for name, source_head in (('old', old), ('new', head)):
            release = releases / name
            release.mkdir(mode=0o700)
            shutil.copytree(REPO / 'server/app/build/install/app', release / 'app')
            for item in ('bridge', 'mathsvc'):
                shutil.copytree(REPO / 'tools' / item, release / 'tools' / item,
                    ignore=shutil.ignore_patterns('.venv', 'node_modules', '__pycache__', '*.pyc'))
            (release / 'tools/dev').mkdir(parents=True)
            shutil.copy2(REPO / 'tools/dev/service-child.py', release / 'tools/dev/service-child.py')
            cmd(python, '-m', 'venv', release / 'tools/bridge/.venv', env=build_env)
            cmd(release / 'tools/bridge/.venv/bin/python', '-m', 'pip', 'install', '--disable-pip-version-check',
                '-r', release / 'tools/bridge/requirements.txt', env=build_env)
            cmd('npm', 'ci', '--ignore-scripts', '--offline', cwd=release / 'tools/mathsvc', env=build_env)
            (release / 'source-head.txt').write_text(source_head + '\n')
            verify.dependencies(release, str(node))
        old_release, new_release = releases / 'old', releases / 'new'
        manifest = root / 'manifest.json'
        # attest invokes the global heavy lock and checks clean source, actual
        # dependencies, relocated tree and all external executable hashes.
        previous = os.environ.get('JAVA_HOME')
        os.environ['JAVA_HOME'] = str(java_home)
        try:
            verify.attest(REPO, new_release, str(node), str(java), manifest)
        finally:
            if previous is None:
                os.environ.pop('JAVA_HOME', None)
            else:
                os.environ['JAVA_HOME'] = previous
        service_port, bridge_port = port(), port()
        while service_port == bridge_port:
            bridge_port = port()
        label = 'local.coach.ton1193.test.' + ident
        logs = home / 'Coach/logs'
        for name in ('stdout.log', 'stderr.log'):
            switch.atomic(logs / name, b'')
        env_pins = {'BRIDGE_LOG_STDOUT': '1', 'BRIDGE_PORT': str(bridge_port),
                    'COACH_MANAGE_SERVICES': '1', 'COACH_PORT': str(service_port),
                    'COACH_TAIL_HOST': '127.0.0.1', 'PATH': '/usr/bin:/bin:/usr/sbin:/sbin'}
        plist_pins = {'ExitTimeOut': 20, 'KeepAlive': True, 'RunAtLoad': True,
                      'StandardErrorPath': str(logs / 'stderr.log'),
                      'StandardOutPath': str(logs / 'stdout.log'), 'ThrottleInterval': 5, 'Umask': 63}
        env = dict(env_pins, HOME=str(home), JAVA_HOME=str(java_home), COACH_NODE=str(node),
                   COACH_SERVICE_ROOT=str(old_release), COACH_PYTHON=str(old_release / 'tools/bridge/.venv/bin/python'),
                   COACH_MATHSVC=str(old_release / 'tools/mathsvc/src/server.mjs'),
                   COACH_DATA_DIR=str(home / 'Coach/data'), COACH_LOG_DIR=str(logs),
                   COACH_RUN_DIR=str(home / 'Coach/run'), COACH_BRIDGE_HOME=str(home / 'Coach/bridge'),
                   COACH_TRAFFIC='test', COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')
        plist = home / 'Library/LaunchAgents/service.plist'
        installation = home / 'Coach/service/installation.json'
        switch.atomic(plist, plistlib.dumps(dict(Label=label, ProgramArguments=[str(old_release / 'app/bin/app')],
                                                   WorkingDirectory=str(old_release), EnvironmentVariables=env,
                                                   **plist_pins)))
        switch.atomic(installation, json.dumps(dict(root=str(old_release), sourceHead=old,
                                                     dataDir=str(home / 'Coach/data'))).encode())
        manifest_hash = switch.file_hash(manifest)
        config = {'stateDir': str(root / 'stage'), 'adapterSnapshotDir': str(root / 'stage/snapshot'),
                  'adapterManifest': str(manifest), 'adapterManifestSha256': manifest_hash,
                  'plist': str(plist), 'installation': str(installation), 'release': str(new_release),
                  'expectedInstallationSha256': switch.file_hash(installation), 'expectedSourceHead': old,
                  'candidateHead': head, 'sourceHeadFileSha256': switch.file_hash(new_release / 'source-head.txt'),
                  'candidateTreeSha256': switch.tree_hash(new_release), 'adapterEnvPins': env_pins,
                  'adapterPlistPins': plist_pins,
                  'candidateVerifier': [sys.executable, '-m', 'tools.dev.ton1175_verify', 'candidate',
                                        '--manifest', str(manifest), '--sha256', manifest_hash],
                  'controller': [sys.executable, '-m', 'tools.dev.ton1193_isolated_controller',
                                 '--config', str(root / 'controller.json'), '--apply'],
                  # These hooks only cover identity so far: the verifier refuses
                  # until real lecture and exercise checks are implemented.
                  'acceptanceSnapshot': [sys.executable, '-m', 'tools.dev.ton1193_isolated_acceptance',
                                         '--config', str(root / 'controller.json'), 'snapshot'],
                  'acceptanceVerifier': [sys.executable, '-m', 'tools.dev.ton1193_isolated_acceptance',
                                         '--config', str(root / 'controller.json'), 'accept']}
        switch.atomic(root / 'controller.json', json.dumps(config).encode())
        switch.stage(config, True)
        (root / 'stage/snapshot').mkdir(mode=0o700)
        snapshot = isolated.run(root / 'controller.json', 'preflight', True)
        print(json.dumps({'head': head, 'oldHead': old, 'stage': 'staged',
                          'candidateRealDependencies': True, 'oldRealDependencies': True,
                          'snapshotSha256': snapshot, 'switchAttempted': False}), flush=True)
    finally:
        shutil.rmtree(root)
        switch.require(not root.exists(), 'private root survived cleanup')
        print(json.dumps({'stageRootRemoved': True, 'launchdUntouched': True}), flush=True)


if __name__ == '__main__':
    main()
