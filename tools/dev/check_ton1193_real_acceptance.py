"""Run one real isolated 900-second transaction; never targets production.

Each invocation builds a disposable two-release fixture. The optional failure
case deliberately fails acceptance only after the candidate has started.
"""
import argparse
import datetime as dt
import http.client
import json
import os
from pathlib import Path
import plistlib
import re
import signal
import sqlite3
import subprocess
import sys
import threading
import time

from . import activity_coverage as coverage
from . import check_ton1193_real_process as probe
from . import check_ton1193_real_stage as stage
from . import ton1175_switch as switch
from . import ton1175_verify as verify
from . import ton1193_isolated_controller as isolated
from . import ton1193_isolated_seed as seed


def configure(config_path, config, roots, pid, owned):
    """Bind the actual lifetime log before stage snapshots the entire config."""
    diagnostic = probe.control_diagnostic(roots, pid, owned)
    logs = list((Path(roots['home']) / 'Coach/logs').glob(
        'activity-' + diagnostic['lifetime'] + '-*.log'))
    switch.require(len(logs) == 1 and not logs[0].is_symlink(), 'ambiguous live activity log')
    # JAVA_HOME is a plist path, not a pinned non-path env variable.
    java = Path(plistlib.loads(Path(config['plist']).read_bytes())['EnvironmentVariables']['JAVA_HOME'])
    config.update(idleEvidence=str(Path(config_path).parent / 'idle.json'),
                  accessLogs=[str(logs[0])],
                  idleVerifier=[sys.executable, '-m', 'tools.dev.ton1175_verify', 'idle',
                                '--java', str(java / 'bin/java'), '--jcmd', str(java / 'bin/jcmd'),
                                '--lsof', '/usr/sbin/lsof', '--stage-config', str(config_path)],
                  logVerifier=[sys.executable, '-m', 'tools.dev.ton1175_verify', 'logs'])
    switch.atomic(config_path, json.dumps(config).encode())
    return diagnostic, java


def wait_old(config, roots, label, kernel, owned):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        pid = probe.job(config, label)
        if pid:
            probe.collect(kernel, pid, owned)
            try:
                probe.control_diagnostic(roots, pid, owned)
                seed.seed(Path(roots['home']).parent / 'controller.json')
                switch.require(probe.job(config, label) == pid, 'old JVM changed during seed')
                return pid
            except (OSError, ValueError, KeyError, sqlite3.Error, http.client.HTTPException, switch.GateError):
                pass
        time.sleep(.25)
    raise switch.GateError('old isolated JVM did not become ready for seed')


def diagnose_idle_failure(config, root, java, report):
    """Record only closed-schema gate failures from the isolated fixture."""
    args = (config['idleEvidence'], config['installation'], str(java / 'bin/java'),
            str(java / 'bin/jcmd'), '/usr/sbin/lsof', str(root / 'controller.json'),
            switch.digest(json.dumps(config, sort_keys=True).encode()))
    allowed = {
        'JFR installation mismatch', 'JFR window is short, stale or future',
        'JFR recording changed', 'recorded JVM process identity changed',
        'live JVM is not the installed server', 'recorded JVM does not exclusively own server lock',
        'dependency or evidence command failed', 'JFR contains client socket activity',
        'recording belongs to a different JVM lifetime', 'identity changed during verification',
        'stage config and transaction digest required together',
    }
    try:
        verify.idle(*args)
        report['idleFailureDiagnostic'] = 'verifier passed on diagnostic retry'
    except switch.GateError as error:
        reason = str(error)
        report['idleFailureDiagnostic'] = reason if reason in allowed else 'other gate rejection'
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        report['idleFailureDiagnostic'] = 'evidence unreadable or dependency unavailable'


def recording(root, java, pid, control_path, config, report):
    settings = '<configuration version="2.0" label="TON1193 isolated">'
    for event in ('SocketRead', 'ActiveSetting', 'DataLoss', 'CPULoad', 'JVMInformation'):
        settings += f'<event name="jdk.{event}"><setting name="enabled">true</setting>'
        if event == 'SocketRead':
            settings += '<setting name="threshold">0 ns</setting><setting name="stackTrace">true</setting>'
        if event == 'CPULoad':
            settings += '<setting name="period">1 s</setting>'
        settings += '</event>'
    settings += '</configuration>'
    switch.atomic(root / 'idle.jfc', settings.encode())
    jcmd = java / 'bin/jcmd'
    def run(*argv):
        return subprocess.check_output([str(x) for x in argv], stderr=subprocess.STDOUT, timeout=30)
    def heartbeat(after=None):
        path = root / 'heartbeat.jfr'
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            run(jcmd, pid, 'JFR.dump', 'name=ton1193', f'filename={path}')
            os.chmod(path, 0o600)
            data = json.loads(run(java / 'bin/jfr', 'print', '--json', '--events', 'jdk.CPULoad', path))
            beats = [dt.datetime.fromisoformat(e['values']['startTime'].replace('Z', '+00:00')).timestamp()
                     for e in data['recording']['events']]
            if beats and (after is None or max(beats) >= after):
                return path
            time.sleep(.25)
        raise switch.GateError('JFR heartbeat unavailable')
    run(jcmd, pid, 'JFR.start', 'name=ton1193', f'settings={root / "idle.jfc"}')
    heartbeat()
    with coverage.Control(control_path) as control:
        start = control.ask('snapshot')
    coverage.healthy(start)
    time.sleep(2)
    wall_start, mono_start = time.time(), time.monotonic()
    identity = switch.digest(run('/bin/ps', '-p', pid, '-o', 'lstart=', '-o', 'command=').strip())
    # Short-window sampling uses the same inspector and trusted stage pin as
    # the full gate; it is only an early failure check, never an idle proof.
    time.sleep(22)
    sample_end = time.time()
    sample = heartbeat(sample_end)
    stamp = lambda value: dt.datetime.fromtimestamp(value, dt.timezone.utc).isoformat()
    inspector = Path(verify.__file__).with_name('Ton1175Jfr.java')
    config_hash = switch.digest(json.dumps(config, sort_keys=True).encode())
    with verify.staged_bridge_port(root / 'controller.json', config['installation'], config_hash) as port:
        measured = json.loads(run(java / 'bin/java', inspector, sample, stamp(wall_start), stamp(sample_end),
                                  pid, port))
        report['earlySocketReads'] = dict(durationSeconds=time.monotonic()-mono_start, **measured)
        switch.require(measured['pid'] == pid and measured['clientReads'] == 0
                       and measured['socketReads'] == measured['attributedHealthReads'],
                       'real JFR recorded client SocketRead during idle window')
    # Foreground wait; no requests to the server while measuring the window.
    while time.monotonic() - mono_start < 903:
        time.sleep(min(30, 903 - (time.monotonic() - mono_start)))
    end = time.time()
    time.sleep(2)
    with coverage.Control(control_path) as control:
        finish = control.ask('snapshot')
    target = root / 'idle.jfr'
    os.replace(heartbeat(end), target)
    proof = dict(windowStart=stamp(wall_start), windowEnd=stamp(end), verifiedAtEpoch=time.time(),
                 sourceHead=config['expectedSourceHead'], pid=pid, recording=str(target),
                 recordingSha256=switch.file_hash(target), processIdentitySha256=identity,
                 installationSha256=switch.file_hash(config['installation']), clientSocketReads=0,
                 latestClientRead=None, coverage=dict(protocol=1, socket=control_path, start=start, end=finish))
    coverage.observed(proof)
    switch.atomic(config['idleEvidence'], json.dumps(proof).encode())
    try:
        switch.check_idle(config, config['expectedSourceHead'])
    except switch.GateError:
        diagnose_idle_failure(config, root, java, report)
        raise
    report['idle'] = dict(pid=pid, durationSeconds=end-wall_start, recordingSha256=proof['recordingSha256'],
                          lifetime=start['lifetime'], realJfrAndActivityVerified=True)


def worker(config_path, report_path, inject):
    config, roots, label = isolated.fixture(config_path)
    root = Path(config_path).parent
    marker = root / 'retain-on-cleanup-failure'
    switch.require(not marker.exists() and probe.job(config, label) is None, 'isolated label occupied')
    kernel, owned, sockets = probe.processes(), {}, {}
    report = dict(head=config['candidateHead'], oldHead=config['expectedSourceHead'], label=label,
                  fixtureNonce=isolated.fixture_nonce(root), productionTouched=False,
                  unverified=['successful switch', 'injected failure rollback', 'device', 'production'])
    switch.atomic(marker, b'cleanup not yet proved\n')
    try:
        switch.require(probe.command('/bin/launchctl', 'bootstrap', f'gui/{os.getuid()}', config['plist']).returncode == 0,
                       'isolated bootstrap failed')
        pid = wait_old(config, roots, label, kernel, owned)
        diagnostic, java = configure(config_path, config, roots, pid, owned)
        if inject:
            config['acceptanceVerifier'] = [sys.executable, '-m',
                'tools.dev.ton1193_isolated_injected_failure', '--config', str(config_path)]
            switch.atomic(config_path, json.dumps(config).encode())
        switch.stage(config, True)
        (root / 'stage/snapshot').mkdir(mode=0o700)
        report['snapshotSha256'] = isolated.run(config_path, 'preflight', True)
        old = probe.wait_ready(config, roots, label, Path(roots['releases']) / 'old', kernel, owned)
        switch.require(old['pid'] == pid, 'old JVM changed before idle')
        report['old'] = old
        recording(root, java, pid, diagnostic['socketPath'], config, report)
        # The ordinary transaction performs its own checks again and invokes the
        # real learning verifier with the original isolated credential.
        done = threading.Event()
        phases, seen, monitor_errors = [], set(), []
        def monitor():
            while not done.wait(.1):
                try:
                    current = probe.job(config, label)
                    if current:
                        probe.collect(kernel, current, owned)
                    phase = json.loads((root / 'stage/journal.json').read_text())['phase']
                    if phase not in seen:
                        seen.add(phase)
                        phases.append(phase)
                except (OSError, ValueError, KeyError, switch.GateError) as exc:
                    monitor_errors.append(str(exc))
        thread = threading.Thread(target=monitor)
        thread.start()
        try:
            try:
                switch.operate(config, 'switch', True)
                switch.require(not inject, 'injected verifier unexpectedly passed')
                report['unverified'].remove('successful switch')
            except switch.GateError as exc:
                journal = json.loads((root / 'stage/journal.json').read_text())
                if not inject or journal['phase'] != 'rolled_back':
                    raise
                injected = json.loads((root / 'injected-acceptance.json').read_text())
                switch.require(all(injected[k] == old['identity'][k] for k in
                                   ('epoch', 'tokenSha256', 'credentialSha256')),
                               'candidate did not retain isolated token')
                restored = probe.wait_ready(config, roots, label, Path(roots['releases']) / 'old', kernel, owned)
                switch.require(all(restored['identity'][k] == old['identity'][k] for k in
                                   ('epoch', 'tokenSha256', 'credentialSha256')),
                               'rollback changed isolated token')
                report['restored'] = restored
                report['injectedFailure'] = str(exc)
                report['unverified'].remove('injected failure rollback')
        finally:
            done.set()
            thread.join()
            report['journal'] = json.loads((root / 'stage/journal.json').read_text())
            report['observedPhases'] = phases
            report['monitorErrors'] = monitor_errors
            rows = [json.loads(line) for line in Path(config['accessLogs'][0]).read_text().splitlines()]
            report['sealRows'] = [dict(event=r.get('event'), seal=r.get('seal')) for r in rows
                                  if r.get('event') in ('seal', 'closed')]
        switch.require(not monitor_errors, 'transaction process monitor failed')
    except BaseException as exc:
        report['failure'] = str(exc)
        raise
    finally:
        try:
            probe.stop(config, label, kernel, owned, sockets)
            report['cleanup'] = dict(labelAbsent=True, ownedIdentitiesAbsent=True, portsFree=True,
                                     ownedSocketsAbsent=True)
        except BaseException as exc:
            report['cleanupFailure'] = str(exc)
            raise
        finally:
            report['ownedProcesses'] = [dict(pid=p.pid, ppid=p.ppid, started=p.started) for p in owned.values()]
            switch.atomic(report_path, json.dumps(report, indent=2, sort_keys=True).encode())
        marker.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--report', required=True)
    parser.add_argument('--config', help=argparse.SUPPRESS)
    parser.add_argument('--inject-failure', action='store_true')
    args = parser.parse_args()
    switch.require(args.apply, 'explicit --apply required')
    report = Path(args.report).absolute()
    switch.require(report.parent.resolve() == stage.REPO.parent and not report.parent.is_symlink()
                   and re.fullmatch(r'ton1193-[A-Za-z0-9_-]+\.json', report.name)
                   and not report.exists(), 'invalid or existing report')
    def interrupted(signum, frame):
        raise InterruptedError('isolated transaction interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    if args.config:
        worker(Path(args.config), report, args.inject_failure)
    else:
        def exercise(root, config, metadata):
            argv = [stage.REPO / 'tools/dev/heavy', sys.executable, '-B', '-m',
                      'tools.dev.check_ton1193_real_acceptance', '--apply', '--config',
                      root / 'controller.json', '--report', report]
            if args.inject_failure:
                argv.append('--inject-failure')
            stage.cmd(*argv, cwd=stage.REPO)
        stage.main(unstaged_exercise=exercise)
        data = json.loads(report.read_text())
        data['fixtureRemoved'] = True
        switch.atomic(report, json.dumps(data, indent=2, sort_keys=True).encode())


if __name__ == '__main__':
    main()
