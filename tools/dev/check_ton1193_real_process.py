"""Real managed startup/HTTP/cleanup probes for two disposable releases.

This deliberately does NOT call a release relocation a gated switch. No idle
proof, learning, rollback or production readiness is inferred from these probes.
Build preparation uses heavy separately; only the short process exercise owns
a heavy lane. Failure injection is after candidate HTTP/seed verification.
"""
import argparse
from datetime import datetime
import http.client
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import re
import signal
import socket
import subprocess
import sys
import time

from . import check_ton1193_real_stage as stage
from . import activity_coverage as coverage
from . import ton1193_isolated_acceptance as acceptance
from . import ton1193_isolated_controller as isolated
from . import ton1193_isolated_seed as seed
from . import ton1193_process as process
from .ton1175_switch import GateError, atomic, require


class InjectedFailure(Exception):
    pass


def processes():
    spec = importlib.util.spec_from_file_location('ton1193_service_child',
                                                stage.REPO / 'tools/dev/service-child.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.Processes()


def command(*argv):
    return subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, timeout=30)


def job(config, label):
    result = command('/bin/launchctl', 'print', f'gui/{os.getuid()}/{label}')
    if result.returncode == 113:
        return None
    require(result.returncode == 0, 'cannot inspect isolated launchd label')
    paths = re.findall(rb'^\s*path = (.+?)\s*$', result.stdout, re.M)
    pids = re.findall(rb'^\s*pid = ([0-9]+)\s*$', result.stdout, re.M)
    require(paths == [os.fsencode(config['plist'])] and len(pids) <= 1,
            'isolated launchd label identity changed')
    return int(pids[0]) if pids else 0


def collect(kernel, pid, owned):
    table = {p.pid: p for p in kernel.snapshot(native=True)}
    root = table.get(pid)
    if root is None:
        return
    previous = owned.get(pid)
    require(previous is None or previous.started == root.started, 'isolated PID reused')
    pending, visited = [pid], set()
    while pending:
        parent = pending.pop()
        visited.add(parent)
        owned[parent] = table[parent]
        pending.extend(p.pid for p in table.values() if p.ppid == parent and p.pid not in visited)


def request(config, roots, path, valid=True):
    token = (Path(roots['home']) / 'Coach/data/device-token').read_text().strip()
    conn = http.client.HTTPConnection('127.0.0.1', int(config['adapterEnvPins']['COACH_PORT']), timeout=5)
    try:
        conn.request('GET', path, headers={'X-Coach-Device': 'desktop',
                     'Authorization': 'Bearer ' + (token if valid else 'invalid-isolated-token-000000000000000')})
        response = conn.getresponse()
        body = response.read(1024 * 1024 + 1)
        require(len(body) <= 1024 * 1024, 'oversized isolated HTTP response')
        return response.status, body
    finally:
        conn.close()


def activity_paths(roots, owned):
    """Find exact lifetime endpoints, including a JVM that failed before ready.

    Never unlink here: a surviving or occupied endpoint must fail cleanup.
    The log's JVM start instant is independently bound to the kernel birth.
    """
    home = Path(roots['home'])
    paths = {}
    for log in (home / 'Coach/logs').glob('activity-*.log'):
        require(not log.is_symlink(), 'activity journal is a symlink')
        with log.open() as stream:
            first = json.loads(stream.readline())
        birth = owned.get(first['pid'])
        if birth is None:
            continue
        seconds, micros = birth.started
        require(abs(datetime.fromisoformat(first['jvmStart'].replace('Z', '+00:00')).timestamp()
                    - (seconds + micros / 1e6)) < .001, 'activity JVM birth mismatch')
        path = isolated.activity_socket(home, first['lifetime'])
        paths[str(path)] = first
    return paths


def control_diagnostic(roots, pid, owned):
    for name, first in activity_paths(roots, owned).items():
        if first['pid'] != pid:
            continue
        with coverage.Control(name) as control:
            snapshot = control.ask('snapshot')
        coverage.same_lifetime(first, snapshot)
        require(snapshot['protocol'] == 1 and snapshot['failures'] == 0
                and not snapshot['closed'], 'activity control unhealthy')
        return dict(socketPath=name, socketPathBytes=len(os.fsencode(name)), socketExists=True,
                    lifetime=first['lifetime'], snapshot=snapshot)
    raise GateError('activity lifetime journal missing')


def wait_ready(config, roots, label, release, kernel, owned, diagnostic=None):
    snapshot = json.loads((Path(config['adapterSnapshotDir']) / 'snapshot.json').read_text())
    side = snapshot['sides'][release.name]
    deadline = time.monotonic() + 60
    diagnostic = diagnostic if diagnostic is not None else {}
    diagnostic.update(jobAbsent=0, jobNoPid=0, jobPid=0,
                      lastFailureStep='NONE', lastFailureReason='NONE')
    while time.monotonic() < deadline:
        diagnostic.update(step='JOB', reason='NOT_READY')
        pid = job(config, label)
        counter = 'jobAbsent' if pid is None else 'jobPid' if pid else 'jobNoPid'
        diagnostic[counter] += 1
        if pid:
            collect(kernel, pid, owned)
            identity = process._identity(pid)
            diagnostic['step'] = 'JVM'
            if identity[0] == 1 and str(release / 'app/lib') + '/' in identity[1]:
                try:
                    diagnostic['step'] = 'HELLO'
                    observed = acceptance.observe(config, roots)
                    diagnostic['step'] = 'LEDGER'
                    with acceptance.ledger(roots) as db:
                        acceptance.ledger_identity(db, observed)
                    services = {}
                    for name in ('bridge', 'mathsvc'):
                        diagnostic['step'] = 'BRIDGE_STATE' if name == 'bridge' else 'MATHSVC_STATE'
                        state = json.loads((Path(roots['home']) / f'Coach/run/{name}.json').read_text())
                        require(state['ownerPid'] == pid and state['healthy'] is True
                                and 0 <= time.time() * 1000 - state['checkedAt'] < 15000,
                                'managed child not healthy')
                        guard, child = kernel.get(state['guardPid']), kernel.get(state['pid'])
                        require(guard is not None and guard.ppid == pid and child is not None
                                and child.ppid == guard.pid, 'managed child ancestry changed')
                        expected = (side['python'][2][0], side['stub'][0]) if name == 'bridge' else (side['node'][0],)
                        require(process._image(child.pid) in expected, 'managed child executable mismatch')
                        services[name] = {'guardPid': guard.pid, 'pid': child.pid,
                                          'image': process._image(child.pid), 'healthy': True}
                    # Health state alone is insufficient: query the actual bridge PID.
                    diagnostic['step'] = 'BRIDGE_HTTP'
                    conn = http.client.HTTPConnection('127.0.0.1', int(config['adapterEnvPins']['BRIDGE_PORT']), timeout=5)
                    try:
                        conn.request('GET', '/health?scope=process')
                        response = conn.getresponse()
                        health = json.loads(response.read(65536))
                        require(response.status == 200 and health['ok'] is True
                                and health['pid'] == services['bridge']['pid'], 'bridge HTTP identity mismatch')
                    finally:
                        conn.close()
                    collect(kernel, pid, owned)
                    diagnostic['step'] = 'ACTIVITY_CONTROL'
                    require(job(config, label) == pid, 'isolated JVM restarted')
                    return dict(pid=pid, image=process._image(pid), services=services,
                                identity=observed, httpHello=200, activityControl=control_diagnostic(roots, pid, owned))
                except (OSError, ValueError, KeyError, GateError, http.client.HTTPException) as exc:
                    diagnostic['reason'] = ('IO_ERROR' if isinstance(exc, OSError) else
                                            'VALUE_ERROR' if isinstance(exc, ValueError) else
                                            'MISSING_FIELD' if isinstance(exc, KeyError) else
                                            'GATE_REJECTED' if isinstance(exc, GateError) else
                                            'HTTP_ERROR')
                    diagnostic.update(lastFailureStep=diagnostic['step'],
                                      lastFailureReason=diagnostic['reason'])
        time.sleep(.2)
    raise GateError('real managed services/HTTP identity did not become ready')


def stop(config, label, kernel, owned, sockets=None):
    pid = job(config, label)
    if pid is not None:
        if pid:
            collect(kernel, pid, owned)
        result = command('/bin/launchctl', 'bootout', f'gui/{os.getuid()}/{label}')
        require(result.returncode == 0, 'isolated bootout failed')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        live = [p for p in owned.values()
                if (now := kernel.get(p.pid)) is not None and now.started == p.started and not now.zombie]
        if job(config, label) is None and not live:
            break
        time.sleep(.2)
    else:
        raise GateError('isolated label or owned descendants survived cleanup')
    if sockets is not None:
        home = Path(config['plist']).parents[2]
        sockets.update(activity_paths({'home': str(home)}, owned))
        require(all(not os.path.lexists(path) for path in sockets),
                'owned activity socket survived cleanup')
    for name in ('COACH_PORT', 'BRIDGE_PORT'):
        with socket.socket() as probe:
            # Completed HTTP connections leave TIME_WAIT tuples; a new listener
            # must be possible without mistaking these for a surviving service.
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', int(config['adapterEnvPins'][name])))
            probe.listen(1)


def worker(config_path, report_path):
    config, roots, label = isolated.fixture(config_path)
    root = Path(config_path).parent
    marker = root / 'retain-on-cleanup-failure'
    require(not marker.exists() and job(config, label) is None, 'isolated label occupied')
    kernel, owned, sockets = processes(), {}, {}
    original_plist = Path(config['plist']).read_bytes()
    original_install = Path(config['installation']).read_bytes()
    cases = []
    report = dict(head=config['candidateHead'], nonce=isolated.fixture_nonce(root),
                  label=label, ports=config['adapterEnvPins'], cases=cases,
                  environmentKeys=sorted(plistlib.loads(original_plist)['EnvironmentVariables']),
                  gatedSwitchAttempted=False, productionTouched=False,
                  unverified=['900s idle/JFR gate', 'gated switch', 'subscription lecture and three graded answers',
                              'transaction rollback', 'device UI', 'production'])
    atomic(marker, b'cleanup not yet proved\n')
    try:
        for inject in (False, True):
            case = dict(name='failure-after-candidate-ready' if inject else 'managed-two-release-startup',
                        releases=[], injected=False)
            cases.append(case)
            try:
                for side in ('old', 'new'):
                    # Startup relocation probe only. Never edit stage pointers,
                    # receipts or journal to impersonate a transaction.
                    plist = plistlib.loads(original_plist)
                    release = Path(roots['releases']) / side
                    env = plist['EnvironmentVariables']
                    env.update(COACH_SERVICE_ROOT=str(release),
                               COACH_PYTHON=str(release / 'tools/bridge/.venv/bin/python'),
                               COACH_MATHSVC=str(release / 'tools/mathsvc/src/server.mjs'))
                    plist.update(ProgramArguments=[str(release / 'app/bin/app')], WorkingDirectory=str(release))
                    atomic(Path(config['plist']), plistlib.dumps(plist))
                    install = json.loads(original_install)
                    install.update(root=str(release), sourceHead=(release / 'source-head.txt').read_text().strip())
                    atomic(Path(config['installation']), json.dumps(install).encode())
                    require(job(config, label) is None, 'isolated label unexpectedly occupied')
                    isolated.activity_socket(env['HOME'])
                    require(command('/bin/launchctl', 'bootstrap', f'gui/{os.getuid()}', config['plist']).returncode == 0,
                            'isolated bootstrap failed')
                    observed = wait_ready(config, roots, label, release, kernel, owned)
                    observed['side'] = side
                    case['releases'].append(observed)
                    status, _ = request(config, roots, '/v1/hello', valid=False)
                    require(status == 401, 'invalid private token was accepted')
                    observed['invalidTokenStatus'] = status
                    if side == 'old':
                        if not inject:
                            seed.seed(config_path)
                        with acceptance.ledger(roots) as db:
                            count = db.execute("SELECT count(*) FROM questions WHERE question_id IN ('q0','q1','q2')").fetchone()[0]
                        require(count == 3, 'synthetic seed missing in live migrated ledger')
                    else:
                        before = case['releases'][0]['identity']
                        require(all(observed['identity'][key] == before[key]
                                    for key in ('epoch', 'tokenSha256', 'credentialSha256')),
                                'isolated release identity changed')
                        status, raw = request(config, roots, '/offline-pack?size=3&courseId=course-1')
                        require(status == 200, 'real service did not read synthetic questions')
                        pack = json.loads(raw)
                        ids = [e['payload']['questionId'] for group in pack['groups'] for e in group]
                        require(sorted(ids) == ['q0', 'q1', 'q2'] and pack['epoch'] == before['epoch'],
                                'real service synthetic question set differs')
                        case['realHttpSeedRead'] = dict(status=status, questionIds=sorted(ids), count=len(ids))
                        if inject:
                            case['injected'] = True
                            raise InjectedFailure('candidate ready')
                    stop(config, label, kernel, owned, sockets)
                case['outcome'] = 'passed'
            except InjectedFailure:
                case['outcome'] = 'injected-failure-caught'
            finally:
                stop(config, label, kernel, owned, sockets)
                atomic(Path(config['plist']), original_plist)
                atomic(Path(config['installation']), original_install)
                case['cleanup'] = dict(labelAbsent=True, ownedIdentitiesAbsent=True, portsFree=True,
                                       ownedSocketsAbsent=True, privatePointersRestored=True,
                                       journal=json.loads((root / 'stage/journal.json').read_text()))
    except BaseException as exc:
        report['unexpectedFailureType'] = type(exc).__name__
        raise
    finally:
        # Even an unexpected startup/HTTP exception must take the same cleanup.
        try:
            stop(config, label, kernel, owned, sockets)
            atomic(Path(config['plist']), original_plist)
            atomic(Path(config['installation']), original_install)
            report['cleanupVerified'] = True
        except BaseException as exc:
            report['cleanupVerified'] = False
            report['cleanupFailureType'] = type(exc).__name__
            raise
        finally:
            report['ownedProcesses'] = [dict(pid=p.pid, ppid=p.ppid, started=p.started) for p in owned.values()]
            report['activitySockets'] = sorted(sockets)
            atomic(Path(report_path), json.dumps(report, indent=2, sort_keys=True).encode())
        marker.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='allow disposable test-label startup')
    parser.add_argument('--report', required=True)
    parser.add_argument('--config', help=argparse.SUPPRESS)
    args = parser.parse_args()
    require(args.apply, 'explicit --apply required')
    report = Path(args.report).absolute()
    require(report.parent.resolve() == stage.REPO.parent and not report.parent.is_symlink()
            and re.fullmatch(r'ton1193-[A-Za-z0-9_-]+\.json', report.name),
            'report must be a ton1193 JSON file in this workspace')
    require(not report.exists(), 'report already exists')
    def interrupted(signum, frame):
        raise InterruptedError('isolated process probe interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    if args.config:
        worker(Path(args.config), report)
    else:
        def exercise(root, config, metadata):
            stage.cmd(stage.REPO / 'tools/dev/heavy', sys.executable, '-B', '-m',
                      'tools.dev.check_ton1193_real_process', '--apply', '--config',
                      root / 'controller.json', '--report', report, cwd=stage.REPO)
        stage.main(exercise)
        evidence = json.loads(report.read_text())
        evidence['fixtureRemoved'] = True
        atomic(report, json.dumps(evidence, indent=2, sort_keys=True).encode())
        print(json.dumps({'report': report.name, 'cases': len(evidence['cases']),
                          'cleanupVerified': True, 'gatedSwitchAttempted': False}), flush=True)


if __name__ == '__main__':
    main()
