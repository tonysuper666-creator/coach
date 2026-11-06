#!/usr/bin/env python3
"""Foreground emulator acceptance. Run under tools/dev/heavy, after preparing N/N+1 fixtures.

Private --artifacts directory contains N.apk, N+1.apk, test.apk, wrong-signer.apk.
Never use this harness on a phone: phone acceptance requires the testphone queue/lease.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
from local_config import value, tail_host

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.coach.android.ton1252'
PORT = 18852


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--prepare', action='store_true', help='build isolated N/N+1 and a synthetic wrong-signer APK first')
    args = parser.parse_args()
    out = args.artifacts.resolve()
    out.mkdir(parents=True, exist_ok=True)
    os.chmod(out, 0o700)
    sdk = Path(value('coach.sdkDir')).expanduser()
    if args.prepare:
        for code, name in [(216600001, 'N'), (216600002, 'N+1')]:
            subprocess.run([str(ROOT / 'gradlew'), ':androidApp:assembleDebug', ':androidApp:assembleDebugAndroidTest',
                ':server:app:installDist', '-PcoachApplicationId=' + PACKAGE, f'-Pcoach.port={PORT}',
                f'-PcoachVersionCode={code}', '-PcoachVersionName=0.1.' + name, '--console=plain'], cwd=ROOT, check=True)
            shutil.copyfile(ROOT / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk', out / (name + '.apk'))
        shutil.copyfile(ROOT / 'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk', out / 'test.apk')
        key = out / 'synthetic-signer.p12'
        key.unlink(missing_ok=True)
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storepass', 'synthetic-pass',
            '-keypass', 'synthetic-pass', '-alias', 'synthetic', '-dname', 'CN=Synthetic acceptance',
            '-keyalg', 'RSA', '-validity', '2'], check=True, capture_output=True)
        try:
            subprocess.run([str(sdk / 'build-tools/37.0.0/apksigner'), 'sign', '--ks', str(key),
                '--ks-pass', 'pass:synthetic-pass', '--out', str(out / 'wrong-signer.apk'), str(out / 'N+1.apk')], check=True)
        finally:
            key.unlink(missing_ok=True)
    serial = 'emulator-5572'
    adb_bin = str(sdk / 'platform-tools/adb')
    def adb(*arguments, check=True, timeout=45):
        return subprocess.run([adb_bin, '-s', serial, *map(str, arguments)], capture_output=True,
                              check=check, timeout=timeout)
    if adb('get-state', check=False).returncode == 0:
        raise RuntimeError('Acceptance emulator already exists; refusing to take ownership')
    spec = importlib.util.spec_from_file_location('publish_app', ROOT / 'tools/dev/publish-app.py')
    publisher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(publisher)
    updates = out / 'updates'
    updates.mkdir(exist_ok=True)
    # Each run starts a fresh isolated publication history.
    shutil.rmtree(updates / PACKAGE, ignore_errors=True)
    original = publisher.publish(out / 'N.apk', updates, sdk)
    assert publisher.publish(out / 'N.apk', updates, sdk) == original
    try:
        publisher.publish(out / 'wrong-signer.apk', updates, sdk)
        raise AssertionError('Publisher accepted a changed signing identity')
    except ValueError as error:
        assert 'signing identity' in str(error)
    valid = publisher.publish(out / 'N+1.apk', updates, sdk)
    assert (updates / (original['sha256'] + '.apk')).is_file()
    try:
        publisher.publish(out / 'N.apk', updates, sdk)
        raise AssertionError('Publisher accepted a downgrade')
    except ValueError as error:
        assert 'version must increase' in str(error)
    print('Publisher same-signing, monotonic version and pinned old APK: PASS', flush=True)
    wrong_dir = out / 'wrong-publication'
    shutil.rmtree(wrong_dir, ignore_errors=True)
    wrong = publisher.publish(out / 'wrong-signer.apk', wrong_dir, sdk)
    for name, metadata in [('valid', valid), ('wrong-signer', wrong)]:
        (out / (name + '.json')).write_text(json.dumps(metadata))
    shutil.copyfile(out / 'N+1.apk', out / 'valid.apk')
    shutil.copyfile(wrong_dir / (wrong['sha256'] + '.apk'), updates / (wrong['sha256'] + '.apk'))
    data = out / 'data'
    shutil.rmtree(data, ignore_errors=True)
    data.mkdir(mode=0o700)
    production = Path.home() / 'Coach/data'
    with sqlite3.connect((production / 'coach.sqlite').resolve().as_uri() + '?mode=ro', uri=True) as source:
        with sqlite3.connect(data / 'coach.sqlite') as target:
            source.backup(target)
    with sqlite3.connect(data / 'coach.sqlite') as db:
        assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
        db.execute("update jobs set state='cancelled',last_error='isolated_update_acceptance' where state in ('queued','running')")
    env = {k: v for k, v in os.environ.items() if not k.startswith(('COACH_', 'BRIDGE_'))}
    env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(data / 'device-token'), COACH_RUN_DIR=str(out / 'run'),
               COACH_LOG_DIR=str(out / 'logs'), COACH_PACKS_DIR=str(out / 'packs'), COACH_STATE_DIR=str(out / 'state'),
               COACH_PORT=str(PORT), COACH_TAIL_HOST=tail_host(), COACH_TRAFFIC='test',
               COACH_BRIDGE_URL='http://127.0.0.1:1', COACH_SOURCE_LOOKUP='false', COACH_APP_UPDATE_DIR=str(updates))
    server = emulator = None
    logs = []
    def log(name):
        handle = (out / name).open('wb'); logs.append(handle); return handle
    try:
        server = subprocess.Popen([str(ROOT / 'server/app/build/install/app/bin/app')], env=env,
                                  stdout=log('server.log'), stderr=subprocess.STDOUT)
        def api(path, body=None, token=None):
            headers = {}
            if token: headers.update({'X-Coach-Device': 'desktop', 'Authorization': 'Bearer ' + token})
            if body is not None: headers['Content-Type'] = 'application/json'
            req = urllib.request.Request(f'http://127.0.0.1:{PORT}' + path,
                data=json.dumps(body).encode() if body is not None else None, headers=headers)
            with urllib.request.urlopen(req, timeout=3) as response: return json.load(response)
        deadline = time.monotonic() + 45
        while True:
            try:
                token = (data / 'device-token').read_text().strip()
                api('/v1/hello', token=token); break
            except (OSError, ValueError):
                if server.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Isolated server not ready')
                time.sleep(.3)
        code = api('/v1/pairing-code', {}, token)['code']
        credentials = api('/v1/pair', {'code': code, 'name': 'Synthetic update acceptance'})
        emulator = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', 'sable-api35', '-port', '5572',
            '-read-only', '-no-snapshot-save', '-no-snapshot-load', '-no-window', '-no-audio', '-no-boot-anim',
            '-gpu', 'host', '-cores', '2', '-memory', '2048'], stdout=log('emulator.log'), stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 180
        while adb('shell', 'getprop', 'sys.boot_completed', check=False, timeout=5).stdout.strip() != b'1':
            if emulator.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Emulator boot failed')
            time.sleep(1)
        adb('uninstall', PACKAGE, check=False)
        adb('install', out / 'N.apk', timeout=90)
        adb('install', '-r', out / 'test.apk', timeout=90)
        fixture = f'/sdcard/Android/data/{PACKAGE}/files/update-fixtures'
        adb('shell', 'mkdir', '-p', fixture)
        for name in ['valid.apk', 'valid.json', 'wrong-signer.apk', 'wrong-signer.json']:
            adb('push', out / name, fixture + '/' + name)
        url = f'http://{tail_host()}:{PORT}'
        def instrument(method, extra=()):
            result = adb('shell', 'am', 'instrument', '-w', '-e', 'class',
                'com.coach.android.AppUpdateSmoke#' + method, *extra,
                PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=100)
            text = result.stdout.decode()
            # Only content-free test output is retained; credentials never enter output.
            (out / (method + '.txt')).write_text(text)
            if 'OK (1 test)' not in text: raise RuntimeError(method + ' failed; inspect private test output')
            print(method + ': PASS', flush=True)
        instrument('acceptsValidUpdateAndRejectsDigestAndSignerMismatch')
        instrument('seedUpgradeSentinel', ('-e', 'coachUrl', url, '-e', 'deviceId', credentials['deviceId'], '-e', 'token', credentials['token']))
        def launch():
            adb('shell', 'am', 'force-stop', PACKAGE)
            adb('shell', 'am', 'start', '-n', PACKAGE + '/com.coach.android.CoachActivity', '--es', 'coachUrl', url)
        def tree():
            adb('shell', 'uiautomator', 'dump', '/sdcard/ton1252.xml', check=False)
            return ET.fromstring(adb('shell', 'cat', '/sdcard/ton1252.xml').stdout)
        def find(label, timeout=40):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                nodes = list(tree().iter('node'))
                for node in nodes:
                    if label in (node.get('text', '') + node.get('content-desc', '')): return node
                time.sleep(.3)
            raise RuntimeError('UI did not show: ' + label)
        def tap(node):
            a, b, c, d = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', (a+c)//2, (b+d)//2)
        def install_button():
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                for node in tree().iter('node'):
                    if node.get('text', '').lower() in {'install', 'update', '安装', '更新'} and node.get('enabled') == 'true':
                        return node
                time.sleep(.3)
            raise RuntimeError('System installer confirmation not found')
        def screenshot(name): (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p').stdout)
        def release(metadata):
            adb('shell', 'input', 'keyevent', '3')
            temp = updates / PACKAGE / 'latest.tmp'
            temp.write_text(json.dumps(metadata)); temp.replace(updates / PACKAGE / 'latest.json')
            adb('shell', 'am', 'start', '-n', PACKAGE + '/com.coach.android.CoachActivity', '--es', 'coachUrl', url)
            find('Coach 有新版本')
        # Check startup and foreground before granting the installer permission.
        launch(); find('Coach 有新版本'); screenshot('01-update-offer')
        tap(find('稍后')); time.sleep(3)
        instrument('snapshotLearningReplica')
        before = adb('shell', 'dumpsys', 'package', PACKAGE).stdout.decode()
        (out / 'version-before.txt').write_text('\n'.join(line.strip() for line in before.splitlines() if 'versionCode=' in line or 'versionName=' in line))
        # Server fault injection is confined to the isolated publication directory.
        release(wrong)
        adb('emu', 'network', 'speed', '10000:10000')
        tap(find('下载并更新')); find('正在下载'); screenshot('02-download-progress')
        adb('emu', 'network', 'speed', 'full')
        find('签名与当前应用不一致'); screenshot('02-signature-rejected')
        print('Wrong signing identity rejected in app: PASS', flush=True)
        bad_hash = dict(valid, sha256='0' * 64)
        shutil.copyfile(out / 'N+1.apk', updates / ('0' * 64 + '.apk'))
        release(bad_hash); tap(find('下载并更新')); find('校验失败'); screenshot('03-digest-rejected')
        print('Wrong digest rejected in app: PASS', flush=True)
        release(valid); tap(find('下载并更新'))
        # Android's first-use permission is changed through the actual settings UI.
        tap(find('Allow from this source')); screenshot('04-install-permission')
        adb('shell', 'input', 'keyevent', '4')
        screenshot('05-system-installer'); tap(install_button())
        deadline = time.monotonic() + 90
        while True:
            after = adb('shell', 'dumpsys', 'package', PACKAGE).stdout.decode()
            if f'versionCode={valid["versionCode"]}' in after: break
            if time.monotonic() > deadline: raise RuntimeError('Installer did not upgrade package')
            time.sleep(.5)
        (out / 'version-after.txt').write_text('\n'.join(line.strip() for line in after.splitlines() if 'versionCode=' in line or 'versionName=' in line))
        instrument('upgradeRetainsKeystorePairingAndLearningDatabase')
        launch(); time.sleep(3); screenshot('06-after-upgrade')
        snapshot_output = (out / 'snapshotLearningReplica.txt').read_text()
        event_count = int(re.search(r'Learning replica snapshot: (\d+) events', snapshot_output).group(1))
        summary = {'versionBefore': 216600001, 'versionAfter': valid['versionCode'], 'signatureRejected': True,
                   'digestRejected': True, 'pairingRetained': True, 'learningReplicaRetained': True,
                   'learningEventCount': event_count, 'foregroundCheck': True,
                   'productionWrites': 0, 'realTutorCalls': 0, 'phoneUsed': False}
        (out / 'summary.json').write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary), flush=True)
    finally:
        if emulator is not None and emulator.poll() is None:
            adb('emu', 'kill', check=False, timeout=10)
            try: emulator.wait(timeout=20)
            except subprocess.TimeoutExpired: emulator.terminate(); emulator.wait(timeout=10)
        if server is not None and server.poll() is None:
            server.terminate()
            try: server.wait(timeout=15)
            except subprocess.TimeoutExpired: server.kill(); server.wait(timeout=5)
        for handle in logs: handle.close()


if __name__ == '__main__': main()
