#!/usr/bin/env python3
"""Run existing HTTP/WS Android checks against the installed LaunchAgent.

Build both debug APKs first. This owns and cleans up only its emulator; it
doesn't start an alternative server or change Tailscale configuration.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import time
from local_config import value

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(value('coach.sdkDir')).expanduser()
ADB = str(SDK / 'platform-tools/adb')
SERIAL = 'emulator-5570'
OUT = ROOT / '.service-evidence'


def adb(*args, **kwargs):
    return subprocess.run([ADB, '-s', SERIAL, *args], check=kwargs.pop('check', True), timeout=kwargs.pop('timeout', 30), **kwargs)


def main():
    existing = subprocess.run([ADB, '-s', SERIAL, 'get-state'], capture_output=True)
    if existing.returncode == 0:
        raise RuntimeError('Test emulator already in use; refusing to take ownership')
    OUT.mkdir(exist_ok=True)
    info = json.loads((Path.home() / 'Coach/service/installation.json').read_text())
    token = (Path(info['dataDir']) / 'device-token').read_text().strip()
    with (OUT / 'emulator.log').open('w') as log:
        emu = subprocess.Popen([str(SDK / 'emulator/emulator'), '-avd', 'sable-api35', '-port', '5570',
            '-no-snapshot-save', '-no-boot-anim', '-no-audio', '-no-window', '-gpu', 'host', '-cores', '2'],
            env=dict(os.environ, ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK)),
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if emu.poll() is not None:
                    raise RuntimeError('Emulator exited; inspect emulator.log')
                state = subprocess.run([ADB, '-s', SERIAL, 'shell', 'getprop', 'sys.boot_completed'],
                    capture_output=True, text=True, timeout=5)
                if state.stdout.strip() == '1':
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Emulator boot timed out')
            adb('install', '-r', str(ROOT / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'), timeout=90)
            adb('install', '-r', str(ROOT / 'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk'), timeout=90)
            result = adb('shell', 'am', 'instrument', '-w', '-e', 'coachToken', token,
                '-e', 'class', 'com.coach.android.NetworkSecuritySmoke',
                'com.coach.android.test/androidx.test.runner.AndroidJUnitRunner',
                capture_output=True, text=True, timeout=90, check=False)
            (OUT / 'android-network.txt').write_text(result.stdout)
            if result.returncode != 0 or 'OK (3 tests)' not in result.stdout:
                raise RuntimeError('Android HTTP/WS check failed; inspect android-network.txt (token was not printed)')
            print('Installed LaunchAgent via configured Tailscale IP: Android HTTP=200, WS=authenticated epoch; other cleartext blocked; 3 tests passed', flush=True)
        finally:
            if emu.poll() is None:
                subprocess.run([ADB, '-s', SERIAL, 'emu', 'kill'], capture_output=True, timeout=10)
                try:
                    emu.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(emu.pid, signal.SIGTERM)
                    emu.wait(timeout=10)


if __name__ == '__main__':
    main()
