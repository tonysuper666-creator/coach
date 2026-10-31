"""Shared guard for the real-device scripts: read the server port out of the INSTALLED package and refuse to run against production."""
import os, re, subprocess, sys, tempfile
from pathlib import Path

PRODUCTION_PORT = 18791

def installed_port(adb, serial, package):
    """Port baked into the package that is installed on the phone (manifest meta-data `coach.port`), or None when it cannot be read."""
    path = subprocess.run([adb, '-s', serial, 'shell', 'pm', 'path', package], capture_output=True, text=True, timeout=30).stdout.strip().splitlines()
    if not path: return None
    with tempfile.TemporaryDirectory() as tmp:
        local = Path(tmp) / 'base.apk'
        subprocess.run([adb, '-s', serial, 'pull', path[0].replace('package:', ''), str(local)], capture_output=True, timeout=120)
        sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
        aapt2 = sorted(sdk.glob('build-tools/*/aapt2'))
        if not aapt2 or not local.exists(): return None
        dump = subprocess.run([str(aapt2[-1]), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(local)], capture_output=True, text=True, timeout=60).stdout
    m = re.search(r'name\(0x01010003\)="coach\.port"[^\n]*\n\s*A: [^\n]*:value\(0x01010024\)=\"?(\d+)', dump)
    return int(m.group(1)) if m else None

def require_test_port(adb, serial, package):
    port = installed_port(adb, serial, package)
    if port is None: sys.exit(f'{package}: cannot read the server port from the installed package; refusing to run')
    if port == PRODUCTION_PORT: sys.exit(f'{package} on the phone is built for production port {PRODUCTION_PORT}; refusing to run (install a -Pcoach.port=<test port> build)')
    print(f'installed {package} COACH_PORT={port}', flush=True)
    return port
