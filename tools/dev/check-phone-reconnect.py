#!/usr/bin/env python3
"""Foreground OPPO acceptance. Requires an existing TON-1094 guardian lease.

Pauses only an owned, isolated JVM; never sleeps the Mac or restarts Tailscale.
Build both APKs with -PcoachApplicationId=com.coach.android.ton1094
-Pcoach.port=<port>, and :server:app:installDist through tools/dev/heavy first.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request
import uuid

from local_config import value, tail_host

ROOT = Path(__file__).resolve().parents[2]
PHONE = Path.home() / 'Coach/testphone'
PACKAGE = 'com.coach.android.ton1094'
FORBIDDEN = {8790, 8791, 18791}


def installation_diagnostic(output):
    """Retain known installer outcomes, never device text or exception messages."""
    known = {'INSTALL_FAILED_USER_RESTRICTED', 'INSTALL_FAILED_UPDATE_INCOMPATIBLE',
             'INSTALL_FAILED_VERSION_DOWNGRADE', 'INSTALL_FAILED_INSUFFICIENT_STORAGE',
             'INSTALL_FAILED_INVALID_APK', 'INSTALL_FAILED_TEST_ONLY',
             'INSTALL_FAILED_ALREADY_EXISTS', 'INSTALL_PARSE_FAILED_NO_CERTIFICATES',
             'INSTALL_FAILED_ABORTED', 'INSTALL_FAILED_INTERNAL_ERROR'}
    codes = set(re.findall(r'Failure \[([A-Z_]+)(?=[:\]])', output)) & known
    return dict(packageManagerCodes=sorted(codes),
                passwordPrompt='手机要求输入账号密码，必须人来点' in output.splitlines(),
                transportFailure=bool(re.search(
                    r'device offline|no devices/emulators found|device [^\n]+ not found|failed to connect',
                    output)))


def package_present(output, package):
    """Empty successful pm output means absent; unexpected output stays unknown."""
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines:
        return False
    if all(line in ('package:'+PACKAGE, 'package:'+PACKAGE+'.test') for line in lines):
        return 'package:'+package in lines
    raise ValueError('Unknown package query result')


def process_flags(dump):
    """Keep only scheduling flags from our process record, never arbitrary dump text."""
    flags = {}
    for record in re.split(r'(?=^\s*\*APP\*)', dump, flags=re.MULTILINE):
        header = next((line for line in record.splitlines() if '*APP*' in line), '')
        if not re.search(r'\d+:'+re.escape(PACKAGE)+r'/', header):
            continue
        for key, val in re.findall(
                r'\b(isFrozen|frozen|pendingFreeze|curProcState|setProcState|curAdj|setAdj|cached|empty|hasForegroundActivities)\s*[=:]\s*(true|false|-?\d+)\b', record):
            flags[key] = val
    return flags


def kernel_state(status):
    """Return only a recognized Linux task-state letter, never /proc contents."""
    states = re.findall(r'^State:\s+([RSDTtXZPI])(?:\s|$)', status, re.MULTILINE)
    return states[0] if len(states) == 1 else None


def wakefulness(dump):
    """Exclude all power-dump data except the current wakefulness enum."""
    states = re.findall(r'^\s*mWakefulness=(Awake|Asleep|Dozing|Dreaming)\s*$',
                        dump, re.MULTILINE)
    return states[0] if len(states) == 1 else None


def healthy_control(log, run_id, port):
    """Require both phone clocks to advance with the isolated server still running."""
    pattern = (re.escape(run_id)+r' (READY|MAIN_HEARTBEAT|SYNC_STATUS connection=\w+ revision=\d+ active=\w+)'
               +r' port='+str(port)+r' elapsedMs=(\d+)\s*$')
    samples = [match.groups() for line in log.splitlines()
               if (match := re.search(pattern, line))]
    ready = [int(stamp) for kind, stamp in samples if kind == 'READY']
    if len(ready) != 1:
        return None
    streams = {'main': [], 'io': []}
    for kind, stamp in samples:
        stamp = int(stamp)
        if stamp < ready[0]:
            continue
        if kind == 'MAIN_HEARTBEAT':
            streams['main'].append(stamp)
        elif kind.startswith('SYNC_STATUS'):
            if not re.fullmatch(r'SYNC_STATUS connection=online revision=\d+ active=true', kind):
                return None
            streams['io'].append(stamp)
    result = {}
    for channel, stamps in streams.items():
        if len(stamps) < 2 or stamps[-1]-stamps[0] < 10_000:
            return None
        if any(not 0 < current-previous <= 4_000
               for previous, current in zip(stamps, stamps[1:])):
            return None
        result[channel+'SpanMs'] = stamps[-1]-stamps[0]
    return result


def heartbeat_record(raw, run_id, channel):
    """Reject unrelated runs and every field outside the synthetic counter schema."""
    if not re.fullmatch(r'[0-9a-f]{32}', run_id) or channel not in ('main', 'io'):
        raise ValueError('Invalid heartbeat identity')
    record = json.loads(raw)
    keys = {'runId', 'channel', 'count', 'elapsedMs'}
    if channel == 'io':
        keys.update(('connection', 'active'))
    if not isinstance(record, dict) or set(record) != keys:
        raise ValueError('Invalid heartbeat fields')
    if record['runId'] != run_id or record['channel'] != channel:
        raise ValueError('Unrelated heartbeat')
    for key, minimum in (('count', 1), ('elapsedMs', 0)):
        if type(record[key]) is not int or not minimum <= record[key] <= 2**63-1:
            raise ValueError('Invalid heartbeat counter')
    if channel == 'io' and (record['connection'] not in ('online', 'offline')
                            or type(record['active']) is not bool):
        raise ValueError('Invalid heartbeat status')
    return record


def lease_valid():
    fields = dict(line.split('=', 1) for line in (PHONE/'lease.d/info').read_text().splitlines())
    if fields.get('card') != 'TON-1094' or int(fields['until']) <= time.time():
        raise RuntimeError('A current guardian grant to TON-1094 is required')


def wait_for(check, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(.25)
    raise TimeoutError('Isolated acceptance phase timed out')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    if args.port in FORBIDDEN or not 1024 <= args.port <= 65535:
        parser.error('A non-production unprivileged port is required')
    if args.serial.startswith('emulator-'):
        parser.error('This acceptance requires the leased OPPO')
    out = args.evidence.resolve()
    out.mkdir(mode=0o700, parents=True, exist_ok=False)
    data = out/'private-data'
    data.mkdir(mode=0o700)
    adb = str(Path(value('coach.sdkDir')).expanduser()/'platform-tools/adb')
    device_env = dict(os.environ, COACH_TESTPHONE=args.serial, COACH_IDLE_MIN='10')
    env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
    env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data/'device-token'),
               COACH_LOG_DIR=str(data/'logs'), COACH_STATE_DIR=str(data/'state'),
               COACH_PACKS_DIR=str(data/'packs'), COACH_TAIL_HOST=tail_host(),
               COACH_PORT=str(args.port), COACH_MANAGE_SERVICES='0',
               COACH_TRAFFIC='test', COACH_BRIDGE_URL='http://127.0.0.1:1',
               COACH_SOURCE_LOOKUP='false')
    server = instrument = None
    paused = False
    touched_phone = False
    token = ''
    run_id = uuid.uuid4().hex
    summary = dict(runId=run_id, startedAt=datetime.now(timezone.utc).isoformat(),
                   head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                   scope='real OPPO; isolated JVM SIGSTOP/CONT; not OS sleep or Tailscale restart',
                   package=PACKAGE, port=args.port, productionWrites=0,
                   faultInjected=False, result='failed')

    def run(command, timeout=30, **kwargs):
        result = subprocess.run(list(map(str, command)), capture_output=True, text=True,
                                timeout=timeout, **kwargs)
        if result.returncode:
            # Never expose command arguments (the instrumentation token) or arbitrary device output.
            raise RuntimeError('Acceptance command failed: '+str(command[0]))
        return result.stdout

    def device(*command, **kwargs):
        return run([adb, '-s', args.serial, *command], **kwargs)

    def hello():
        if server.poll() is not None:
            raise RuntimeError('Isolated server exited')
        try:
            req = urllib.request.Request(f'http://127.0.0.1:{args.port}/v1/hello',
                headers={'Authorization': 'Bearer '+token, 'X-Coach-Device': 'desktop'})
            with urllib.request.urlopen(req, timeout=1) as response:
                return json.load(response)
        except (OSError, ValueError):
            return None

    def report():
        log = device('logcat', '-d', '-s', 'CoachReconnect:I', '*:S')
        checkpoints = '\n'.join(line for line in log.splitlines() if run_id in line)
        if checkpoints:
            (out/'checkpoints.txt').write_text(checkpoints+'\n')
        if instrument.poll() is not None:
            raise RuntimeError('Instrumentation exited before its checkpoint')
        return checkpoints

    def process_snapshot(phase):
        lease_valid()
        snapshot = dict(phase=phase, at=datetime.now(timezone.utc).isoformat())
        try:
            pid = device('shell', 'pidof', PACKAGE, timeout=5).strip()
            snapshot['present'] = bool(pid)
            # Never interpolate unvalidated device output into a remote command.
            if re.fullmatch(r'[1-9][0-9]*', pid):
                snapshot['kernelState'] = kernel_state(
                    device('shell', 'cat', '/proc/'+pid+'/status', timeout=5))
        except Exception as error:
            snapshot['processCaptureError'] = type(error).__name__
        try:
            snapshot['flags'] = process_flags(device('shell', 'dumpsys', 'activity', 'processes', PACKAGE, timeout=5))
        except Exception as error:
            snapshot['captureError'] = type(error).__name__
        try:
            snapshot['wakefulness'] = wakefulness(device('shell', 'dumpsys', 'power', timeout=5))
        except Exception as error:
            snapshot['powerCaptureError'] = type(error).__name__
        summary.setdefault('phoneProcessSnapshots', []).append(snapshot)

    def heartbeat_snapshot(phase):
        lease_valid()
        snapshot = dict(phase=phase, at=datetime.now(timezone.utc).isoformat())
        for channel in ('main', 'io'):
            try:
                raw = device('shell', 'run-as', PACKAGE, 'cat',
                             f'cache/ton1094-{run_id}-{channel}.json', timeout=5)
                snapshot[channel] = heartbeat_record(raw, run_id, channel)
            except Exception as error:
                snapshot[channel+'CaptureError'] = type(error).__name__
        summary.setdefault('phoneHeartbeatSnapshots', []).append(snapshot)

    try:
        summary['phase'] = 'authorization'
        lease_valid()
        run(['bash', PHONE/'lease.sh', 'renew', 'TON-1094', '30'])
        run(['bash', PHONE/'probe.sh'], env=device_env)
        brand = device('shell', 'getprop', 'ro.product.brand').strip()
        if brand.lower() != 'oppo':
            raise RuntimeError('The granted test device must be OPPO')
        summary['deviceBrand'] = brand
        summary['deviceModel'] = device('shell', 'getprop', 'ro.product.model').strip()
        summary['phase'] = 'isolated server readiness'
        # Reserve checks only; a bind race causes server readiness to fail, never takeover.
        for host in ('127.0.0.1', tail_host()):
            with socket.socket() as sock:
                sock.bind((host, args.port))
        with (data/'server.log').open('w') as log:
            server = subprocess.Popen([ROOT/'server/app/build/install/app/bin/app'],
                env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        wait_for(lambda: (data/'device-token').is_file() or server.poll() is not None, 30)
        token = (data/'device-token').read_text().strip()
        initial = wait_for(hello, 40)
        summary['phase'] = 'card APK installation'
        touched_phone = True
        for role, apk in (('main', 'debug/androidApp-debug.apk'),
                          ('test', 'androidTest/debug/androidApp-debug-androidTest.apk')):
            summary['installingPackage'] = role
            command = ['bash', PHONE/'install.sh', ROOT/'androidApp/build/outputs/apk'/apk]
            result = subprocess.run(list(map(str, command)), timeout=240, env=device_env,
                                    capture_output=True, text=True)
            if result.returncode:
                summary['installationFailure'] = dict(
                    package=role, exitCode=result.returncode,
                    **installation_diagnostic(result.stdout+'\n'+result.stderr))
                raise RuntimeError('Card APK installation failed')
        summary.pop('installingPackage', None)
        def instrument_args(test):
            return [adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w',
                    '-e', 'class', 'com.coach.android.'+test,
                    '-e', 'coachToken', token, '-e', 'coachRunId', run_id,
                    PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
        summary['phase'] = 'network security gate'
        network = run(instrument_args('NetworkSecuritySmoke'), timeout=90)
        (out/'network.txt').write_text(network.replace(token, '[redacted]'))
        if 'OK (3 tests)' not in network:
            raise RuntimeError('Network/security smoke did not pass all 3 tests')
        summary['networkTests'] = 3
        with (out/'reconnect.txt').open('w') as log:
            instrument = subprocess.Popen(instrument_args('LedgerReconnectSmoke'),
                stdout=log, stderr=subprocess.STDOUT)
        summary['phase'] = 'phone online checkpoint'
        wait_for(lambda: (s if f'READY port={args.port} ' in (s := report()) else None), 40)
        # A no-fault control separates a phone/test/logging stall from a reaction
        # to JVM pause. It cannot pass or extend the original 30-second OFFLINE gate.
        summary['phase'] = 'phone heartbeat control with isolated JVM running'
        control_started = time.monotonic()
        heartbeat_snapshot('ready before no-fault control')
        summary['healthyControl'] = wait_for(
            lambda: healthy_control(report(), run_id, args.port),
            max(0, 15-(time.monotonic()-control_started)))
        process_snapshot('ready before JVM pause')
        lease_valid()
        start = time.monotonic()
        os.killpg(server.pid, signal.SIGSTOP)
        paused = True
        summary['faultInjected'] = True
        summary['pauseStartedAt'] = datetime.now(timezone.utc).isoformat()
        summary['pausedProcessState'] = run(['ps', '-o', 'stat=', '-p', server.pid]).strip()
        summary['phase'] = 'phone offline checkpoint during isolated JVM pause'
        wait_for(lambda: 'OFFLINE' in report(), 30)
        # Match the historical controlled JVM pause without claiming actual system sleep.
        while time.monotonic() - start < 32:
            time.sleep(.25)
        os.killpg(server.pid, signal.SIGCONT)
        paused = False
        resumed = time.monotonic()
        summary['phase'] = 'phone reconnection after JVM resume'
        instrument.wait(timeout=65)
        result = (out/'reconnect.txt').read_text().replace(token, '[redacted]')
        (out/'reconnect.txt').write_text(result)
        log = device('logcat', '-d', '-s', 'CoachReconnect:I', '*:S')
        log = '\n'.join(line for line in log.splitlines() if run_id in line)+'\n'
        (out/'checkpoints.txt').write_text(log)
        if 'OK (1 test)' not in result or 'RECONNECTED epochUnchanged=true' not in log:
            raise RuntimeError('Real-device reconnection assertion failed')
        assert hello()['epoch'] == initial['epoch']
        summary.update(result='passed', phase='completed', reconnectTests=1, controlledPauseSeconds=round(resumed-start, 3),
                       completionAfterResumeSeconds=round(time.monotonic()-resumed, 3),
                       checkpointElapsedMs={key: int(re.search(key+r'[^\n]*elapsedMs=(\d+)', log)[1])
                                            for key in ('READY', 'OFFLINE', 'RECONNECTED')})
    except Exception as error:
        summary['failureType'] = type(error).__name__
        if instrument is not None:
            # Preserve the last safe diagnostics even when instrumentation has exited.
            try:
                lease_valid()
                report()
            except Exception:
                pass
            try:
                process_snapshot('failure before cleanup')
            except Exception:
                pass
            try:
                heartbeat_snapshot('failure before cleanup')
            except Exception:
                pass
        raise
    finally:
        cleanup_errors = []

        def cleanup_step(label, action):
            try:
                action()
            except Exception:
                # Keep trying the remaining cleanup without exposing private output.
                cleanup_errors.append(label)

        def stop_server():
            if server and server.poll() is None:
                if paused:
                    os.killpg(server.pid, signal.SIGCONT)
                    summary['controlledPauseSeconds'] = round(time.monotonic()-start, 3)
                os.killpg(server.pid, signal.SIGTERM)
                try:
                    server.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(server.pid, signal.SIGKILL)
                    server.wait()

        def stop_instrument():
            if instrument and instrument.poll() is None:
                instrument.terminate()
                try:
                    instrument.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    instrument.kill()
                    instrument.wait()

        def cleanup_phone():
            if not touched_phone:
                return
            lease_valid()
            # Remove only this run's diagnostic files, including AtomicFile sidecars.
            files = [f'cache/ton1094-{run_id}-{channel}.json'+suffix
                     for channel in ('main', 'io') for suffix in ('', '.new', '.bak')]
            files += ['databases/synthetic-reconnect.sqlite'+suffix
                      for suffix in ('', '-wal', '-shm', '-journal')]
            def clean_main():
                present = package_present(device('shell', 'pm', 'list', 'packages',
                                                  '--user', '0', PACKAGE), PACKAGE)
                if present:
                    device('shell', 'am', 'force-stop', PACKAGE)
                    device('shell', 'run-as', PACKAGE, 'rm', '-f', *files)

            def clean_test():
                test_package = PACKAGE+'.test'
                # An install failure may leave no test package at all. Confirm
                # absence rather than treating adb's uninstall error as success.
                present = package_present(device('shell', 'pm', 'list', 'packages',
                                                  '--user', '0', test_package), test_package)
                summary['testPackagePresentBeforeCleanup'] = present
                if present:
                    device('uninstall', test_package)
                summary['testPackageRemoved'] = not package_present(
                    device('shell', 'pm', 'list', 'packages', '--user', '0', test_package),
                    test_package)
                if not summary['testPackageRemoved']:
                    raise RuntimeError('Test package cleanup failed')

            cleanup_step('phone synthetic files', clean_main)
            cleanup_step('test package removal', clean_test)

        def count_calls():
            # Read only our temporary synthetic SQLite after JVM shutdown.
            if (data/'coach.sqlite').is_file():
                with sqlite3.connect(data/'coach.sqlite') as db:
                    summary['aiCalls'] = db.execute('SELECT coalesce(sum(physical_calls),0) FROM ai_calls').fetchone()[0]

        cleanup_step('isolated JVM', stop_server)
        cleanup_step('instrumentation process', stop_instrument)
        cleanup_step('test package', cleanup_phone)
        cleanup_step('AI call count', count_calls)
        cleanup_step('private data', lambda: shutil.rmtree(data))
        cleanup_step('lease release', lambda: run(['bash', PHONE/'lease.sh', 'release', 'TON-1094']))
        if cleanup_errors:
            summary.update(result='failed', cleanupErrors=cleanup_errors)
        summary['finishedAt'] = datetime.now(timezone.utc).isoformat()
        (out/'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
        print(json.dumps(summary), flush=True)
        if cleanup_errors:
            raise RuntimeError('Acceptance cleanup incomplete: '+', '.join(cleanup_errors))


if __name__ == '__main__':
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    main()
