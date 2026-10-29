#!/usr/bin/env python3
"""Foreground Android replay measurement. Builds first, owns/stops only its emulator."""
import os
import signal
import subprocess
import time
from pathlib import Path
from local_config import value

root = Path(__file__).resolve().parents[2]
sdk = Path(value('coach.sdkDir')).expanduser()
adb = str(sdk / 'platform-tools/adb')
serial = 'emulator-5576'
output = Path(os.environ.get('COACH_LEDGER_EVIDENCE', root / '.ledger-evidence'))
output.mkdir(parents=True, exist_ok=True)

def run(args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, timeout=kwargs.pop('timeout', 60), **kwargs)

run([root/'tools/dev/heavy', root/'gradlew', '-p', root, ':androidApp:assembleDebug', ':androidApp:assembleDebugAndroidTest', '--max-workers=2', '--console=plain'], timeout=600)
if subprocess.run([adb, '-s', serial, 'get-state'], capture_output=True).returncode == 0:
    raise RuntimeError('benchmark emulator port is occupied')
log = open(output/'emulator.log', 'w')
process = subprocess.Popen([str(root/'tools/dev/heavy'), str(sdk/'emulator/emulator'), '-avd', 'sable-api35', '-port', '5576', '-no-snapshot-save', '-no-boot-anim', '-no-audio', '-no-window', '-gpu', 'host', '-cores', '2'], env=dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk)), stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
try:
    for _ in range(240):
        if process.poll() is not None:
            raise RuntimeError('emulator exited')
        if subprocess.run([adb, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True).stdout.strip() == '1':
            break
        time.sleep(1)
    else:
        raise RuntimeError('emulator boot timeout')
    for apk in ['androidApp/build/outputs/apk/debug/androidApp-debug.apk', 'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk']:
        run([adb, '-s', serial, 'install', '-r', root/apk])
    results=[]
    for phase in ['prepare', 'cache']:
        run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.coach.android'])
        result = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class', 'com.coach.android.LedgerReplayBenchmark#coldReplay', '-e', 'coachReplayPhase', phase, 'com.coach.android.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, timeout=180)
        results.append(result.stdout+result.stderr)
        print(result.stdout, flush=True)
        if 'OK (1 test)' not in result.stdout:
            raise RuntimeError('instrumentation failed: '+phase)
    (output/'android-instrumentation.txt').write_text("\n".join(results))
    report=run([adb, '-s', serial, 'logcat', '-d', '-s', 'CoachReplay:I', '*:S'], capture_output=True, text=True).stdout
    (output/'android-replay.txt').write_text(report)
    print(report, flush=True)
    if 'events=50000' not in report or 'cachedFreshProcess' not in report:
        raise RuntimeError('missing replay measurement')
finally:
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
    log.close()
