#!/usr/bin/env python3
"""Run both review smoke suites on an owned, disposable emulator under heavy --emulator."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-data-dir', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    sdk = Path(os.environ['ANDROID_SDK_ROOT'])
    adb = str(sdk / 'platform-tools/adb')
    args.out.mkdir(mode=0o700, parents=True, exist_ok=True)
    occupied = subprocess.check_output([adb, 'devices'], text=True)
    port = next(p for p in range(5588, 5680, 2) if f'emulator-{p}' not in occupied and available(p) and available(p + 1))
    serial = f'emulator-{port}'
    with (args.out / 'emulator-private.log').open('w') as log:
        process = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', 'coach_1128_c70_r',
            '-port', str(port), '-read-only', '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'host', '-cores', '2'],
            stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                assert process.poll() is None, 'Owned emulator exited; see private emulator log'
                ready = subprocess.run([adb, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, timeout=10)
                if ready.stdout.strip() == b'1':
                    break
                time.sleep(1)
            else:
                raise TimeoutError('Owned emulator boot')
            for name in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
                subprocess.run([adb, '-s', serial, 'shell', 'settings', 'put', 'global', name, '0'], check=True, capture_output=True)
            for name, flags in [('queue', []), ('mistakes', ['--mistakes'])]:
                command = [sys.executable, str(ROOT / 'tools/dev/check-review-copy.py'), '--private-data-dir', str(args.private_data_dir.resolve()),
                           '--android', serial, '--screenshots', str(args.out.resolve() / name)] + flags
                with (args.out / (name + '-summary.log')).open('w') as output:
                    result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
                print(json.dumps(dict(suite=name, exitCode=result.returncode)), flush=True)
                assert result.returncode == 0, 'Synthetic emulator suite failed: ' + name
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            print(json.dumps(dict(ownedEmulatorReaped=True, exitCode=process.returncode)), flush=True)


def available(port):
    try:
        with socket.socket() as connection:
            connection.bind(('127.0.0.1', port))
        return True
    except OSError:
        return False


if __name__ == '__main__':
    main()
