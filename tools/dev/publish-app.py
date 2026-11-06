#!/usr/bin/env python3
"""Publish a locally signed APK to the private, authenticated Coach update directory."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def publish(apk, directory, sdk):
    tools = sdk / 'build-tools/37.0.0'
    def run(*args):
        return subprocess.check_output(list(map(str, args)), text=True, stderr=subprocess.PIPE)
    signatures = run(tools / 'apksigner', 'verify', '--print-certs', apk)
    certs = sorted(re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signatures))
    if not certs:
        raise ValueError('APK has no verified signers')
    badging = run(tools / 'aapt', 'dump', 'badging', apk)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match:
        raise ValueError('Cannot read APK version')
    package, code, name = match.groups()
    if not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+', package) or not 2 <= int(code) <= 2100000000:
        raise ValueError('Invalid Android application/version')
    if not 1 <= len(name) <= 100 or not 1 <= apk.stat().st_size <= 268435456:
        raise ValueError('Invalid update name or APK size')
    with apk.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    metadata = dict(packageName=package, versionCode=int(code), versionName=name, sha256=digest,
                    size=apk.stat().st_size, signerSha256=certs)
    target = directory / package
    target.mkdir(parents=True, exist_ok=True)
    with (directory / '.publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        latest = target / 'latest.json'
        if latest.exists():
            previous = json.loads(latest.read_text())
            if previous == metadata:
                return metadata
            if int(code) <= previous['versionCode']:
                raise ValueError('Update version must increase')
            if certs != previous['signerSha256']:
                raise ValueError('Update signing identity changed')
        # Content addresses remain available while another client finishes an older download.
        with tempfile.NamedTemporaryFile(dir=directory, delete=False) as temporary:
            temp_path = Path(temporary.name)
            try:
                with apk.open('rb') as source:
                    shutil.copyfileobj(source, temporary)
                temporary.flush(); os.fsync(temporary.fileno())
                with temp_path.open('rb') as copied:
                    if hashlib.file_digest(copied, 'sha256').hexdigest() != digest:
                        raise ValueError('APK changed while publishing')
                os.replace(temp_path, directory / (digest + '.apk'))
            finally:
                temp_path.unlink(missing_ok=True)
        with tempfile.NamedTemporaryFile(dir=target, mode='w', delete=False) as temporary:
            temp_path = Path(temporary.name)
            try:
                json.dump(metadata, temporary, ensure_ascii=False)
                temporary.flush(); os.fsync(temporary.fileno())
                os.replace(temp_path, latest)
            finally:
                temp_path.unlink(missing_ok=True)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--directory', type=Path, default=Path.home() / 'Coach/builds/updates')
    parser.add_argument('--sdk', type=Path)
    args = parser.parse_args()
    sdk = args.sdk
    if sdk is None:
        from local_config import value
        sdk = Path(value('coach.sdkDir')).expanduser()
    metadata = publish(args.apk.resolve(), args.directory.expanduser(), sdk)
    print(json.dumps({key: metadata[key] for key in ('packageName', 'versionCode', 'versionName', 'sha256', 'size')}))


if __name__ == '__main__':
    main()
