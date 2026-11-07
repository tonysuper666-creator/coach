"""Run one real isolated 900-second transaction; never targets production.

Each invocation builds a disposable two-release fixture. The optional failure
case deliberately fails acceptance only after the candidate has started.
"""
import argparse
import datetime as dt
import fcntl
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
from . import ton1193_data_transaction as data_transaction
from . import ton1193_isolated_acceptance as acceptance
from . import ton1193_isolated_learning as learning
from . import ton1193_synthetic_cli as synthetic


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


COUNTS = ('socketReads', 'attributedHealthReads', 'clientReads', 'healthRounds',
          'maxReadsPerRound', 'maxBytesPerRound', 'minRoundGapMillis', 'maxIntraRoundGapMicros')
REASONS = {'NONE', 'CLIENT_READ', 'ROUND_THREADS', 'ROUND_READ_LIMIT', 'ROUND_BYTES_LIMIT',
           'ROUND_GAP', 'ROUND_SPAN', 'ROUND_COUNT', 'EVIDENCE_INVALID'}


def seal_diagnostic(config):
    """Compare a post-failure snapshot; a concurrent change can obscure the cause."""
    try:
        proof = switch.load(config['idleEvidence'])
        cov, end = coverage.observed(proof)
        with coverage.Control(cov['socket']) as client:
            current = client.ask('snapshot')
        for key in ('closed', 'workQuiet'):
            if type(current[key]) is not bool:
                return 'SNAPSHOT_INVALID'
        for key in ('failures', 'active', 'ingress', 'rejected'):
            if type(current[key]) is not int or current[key] < 0:
                return 'SNAPSHOT_INVALID'
        if current['closed']: return 'CLOSED'
        if current.get('seal'): return 'ALREADY_SEALED'
        if current['failures']: return 'WRITE_FAILURES'
        if current['active']: return 'ACTIVE'
        if not current['workQuiet']: return 'WORK_NOT_QUIET'
        if any(current[k] != end[k] for k in ('lifetime', 'pid', 'jvmStart')): return 'LIFETIME_CHANGED'
        if current['ingress'] != end['ingress']: return 'INGRESS_CHANGED'
        if current['rejected'] != end['rejected']: return 'REJECTED_CHANGED'
        return 'NO_VISIBLE_CAUSE'
    except (OSError, ValueError, KeyError, TypeError, switch.GateError):
        return 'SNAPSHOT_UNAVAILABLE'


def safe_counts(data):
    """Retain only the inspector's fixed, non-sensitive diagnostic schema."""
    if (not isinstance(data, dict) or any(type(data.get(k)) is not int or data[k] < 0 for k in COUNTS)
            or data.get('rejectReason') not in REASONS):
        raise ValueError('invalid inspector diagnostic')
    return {**{k: data[k] for k in COUNTS}, 'rejectReason': data['rejectReason']}


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
        if reason in ('dependency or evidence command failed', 'JFR contains client socket activity'):
            # The verifier deliberately suppresses child output. Re-run its
            # exact inspector and retain only counts or its fixed line marker.
            try:
                proof = json.loads(Path(config['idleEvidence']).read_text())
                config_hash = switch.digest(json.dumps(config, sort_keys=True).encode())
                inspector = Path(verify.__file__).with_name('Ton1175Jfr.java')
                with verify.staged_bridge_port(root / 'controller.json', config['installation'], config_hash) as port:
                    result = subprocess.run([str(java / 'bin/java'), str(inspector), proof['recording'],
                        proof['windowStart'], proof['windowEnd'], str(proof['pid']), str(port)],
                        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        timeout=60, check=False)
                try:
                    measured = json.loads(result.stdout)
                    if 'rejectReason' in measured:
                        report['fullSocketReads'] = safe_counts(measured)
                    elif result.returncode == 0:
                        report['fullSocketReads'] = {k: measured[k] for k in
                            ('pid', 'socketReads', 'attributedHealthReads', 'clientReads')}
                except (ValueError, TypeError, KeyError):
                    pass
                if result.returncode != 0:
                    marker = re.fullmatch(rb'JFR evidence rejected; inspector line ([0-9]+)\s*', result.stderr)
                    report['inspectorFailureLine'] = int(marker.group(1)) if marker else None
            except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError, switch.GateError):
                report['inspectorDiagnostic'] = 'unavailable'
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
        try:
            measured = json.loads(run(java / 'bin/java', inspector, sample, stamp(wall_start), stamp(sample_end),
                                      pid, port))
        except subprocess.CalledProcessError as error:
            try:
                report['earlySocketReads'] = safe_counts(json.loads(error.output.splitlines()[0]))
            except (ValueError, TypeError, KeyError):
                pass
            raise switch.GateError('early JFR inspector rejected evidence') from error
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
    with verify.staged_bridge_port(root / 'controller.json', config['installation'], config_hash) as port:
        measured = json.loads(run(java / 'bin/java', inspector, target, stamp(wall_start), stamp(end), pid, port))
        report['fullSocketReads'] = safe_counts(measured)
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
        cli_proof = synthetic.verify(config)
        report.update(cli='SYNTHETIC', syntheticCliSha256=cli_proof['sha256'])
        switch.require(probe.command('/bin/launchctl', 'bootstrap', f'gui/{os.getuid()}', config['plist']).returncode == 0,
                       'isolated bootstrap failed')
        pid = wait_old(config, roots, label, kernel, owned)
        report['syntheticPreflight'] = synthetic.smoke(config)
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
                if str(exc) == 'coverage unavailable':
                    report['sealDiagnostic'] = seal_diagnostic(config)
                if not inject or journal['phase'] != 'rolled_back':
                    raise
                diagnostic = root / 'injected-diagnostic.json'
                if diagnostic.exists():
                    report['injectedDiagnostic'] = json.loads(diagnostic.read_text())
                    raise switch.GateError('injected verifier refused before authenticated failure')
                switch.require((root / 'injected-acceptance.json').is_file(),
                               'injected verifier did not run')
                injected = json.loads((root / 'injected-acceptance.json').read_text())
                switch.require(all(injected[k] == old['identity'][k] for k in
                                   ('epoch', 'tokenSha256', 'credentialSha256')),
                               'candidate did not retain isolated token')
                report['injectedAuthenticated'] = True
                report['restoredReadiness'] = {}
                restored = probe.wait_ready(config, roots, label, Path(roots['releases']) / 'old', kernel, owned,
                                            diagnostic=report['restoredReadiness'])
                switch.require(all(restored['identity'][k] == old['identity'][k] for k in
                                   ('epoch', 'tokenSha256', 'credentialSha256')),
                               'rollback changed isolated token')
                report['restored'] = restored
                value, journal = data_transaction.load(config)
                switch.require(restored['identity']['lastSeq'] == value['before']['lastSeq'],
                               'restored startup high-water differs from sealed snapshot')
                report['restoredStartupLastSeqMatchesSeal'] = True
                with open(root / 'stage/lock', 'rb') as recovery_lock:
                    fcntl.flock(recovery_lock, fcntl.LOCK_EX)
                    baseline = str(root / 'stage/acceptance-baseline.json')
                    acceptance.run(config_path, 'snapshot', baseline)
                    started = time.time()
                    report['restoredLearning'] = learning.run(config_path, baseline, started, started + 300, recovery=True)
                report['injectedFailure'] = str(exc)
                report['unverified'].remove('injected failure rollback')
        finally:
            done.set()
            thread.join()
            candidate_learning = root / 'synthetic-learning-candidate.json'
            if candidate_learning.exists():
                report['candidateLearning'] = json.loads(candidate_learning.read_bytes())
            report['controllerDiagnostics'] = controller_diagnostics(root)
            report['journal'] = json.loads((root / 'stage/journal.json').read_text())
            if 'dataSnapshotHash' in report['journal']:
                value, journal = data_transaction.load(config)
                report['snapshot'] = dict(manifest=value['manifest'], before=value['before'],
                                           state=journal['dataState'], retained=True)
                if journal['dataState'] == 'committed':
                    report['snapshot']['retainedDigestMatches'] = (
                        data_transaction.clone.fingerprint(data_transaction._path(value, 'pre')) == value['manifest'])
                if journal['dataState'] == 'restored':
                    report['quarantineDigestMatches'] = (
                        data_transaction.clone.fingerprint(data_transaction._path(value, 'candidate')) == journal['candidateManifest'])
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
            if report.get('snapshot') and report.get('cleanup'):
                report['stoppedData'] = data_transaction.counts(Path(roots['home']) / 'Coach/data')
            report['dataEvidenceRetained'] = bool(list((Path(roots['home']) / 'Coach').glob('data.pre-*'))
                                                  + list((Path(roots['home']) / 'Coach').glob('data.candidate-*')))
            switch.atomic(report_path, json.dumps(report, indent=2, sort_keys=True).encode())
        marker.unlink()


def controller_diagnostics(root):
    """Copy only the isolated controller's closed diagnostic vocabulary."""
    steps = {'CONTROLLER', 'TRANSACTION', 'REVALIDATE', 'STOPPED', 'RECEIPT', 'JOB', 'LEARNING',
             'LAUNCHCTL', 'START_IDENTITY', 'STOP_IDENTITY'}
    reasons = set(isolated.REASONS.values()) | {'GATE_REJECTED', 'IO_ERROR', 'INVALID_DATA'}
    result = []
    for phase in ('switching', 'recovering', 'unknown'):
        for action in ('stop', 'start'):
            path = root / ('controller-' + phase + '-' + action + '.json')
            if not path.exists():
                continue
            value = json.loads(path.read_text())
            switch.require(isinstance(value, dict) and set(value) == {'phase', 'action', 'step', 'reason'}
                           and value['phase'] == phase and value['action'] == action
                           and value['step'] in steps and value['reason'] in reasons,
                           'invalid controller diagnostic')
            result.append(value)
    return result


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
        data['fixtureRemoved'] = not data['dataEvidenceRetained']
        switch.atomic(report, json.dumps(data, indent=2, sort_keys=True).encode())


if __name__ == '__main__':
    main()
