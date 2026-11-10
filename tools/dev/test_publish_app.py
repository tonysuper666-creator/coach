#!/usr/bin/env python3
"""Synthetic SDK regressions; no Gradle, Android SDK, server or device required.

COACH_PUBLISHER_UNDER_TEST selects an older publisher to demonstrate regressions.
The fake tools replace the original build output, never the private snapshot.
"""
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest


PUBLISHER = Path(os.environ.get('COACH_PUBLISHER_UNDER_TEST', Path(__file__).with_name('publish-app.py'))).resolve()
FAKE_TOOL = r'''
import json, os, stat, sys
from pathlib import Path
path = Path(sys.argv[-1])
data = json.loads(path.read_bytes())
tool = Path(sys.argv[0]).name
with open(os.environ['FAKE_AUDIT'], 'a') as audit:
    audit.write(json.dumps(dict(tool=tool, path=str(path), mode=stat.S_IMODE(path.stat().st_mode),
                               parentMode=stat.S_IMODE(path.parent.stat().st_mode))) + '\n')
if os.environ.get('FAKE_REPLACE_AFTER') == tool:
    replacement = Path(os.environ['FAKE_REPLACEMENT'])
    os.replace(replacement, os.environ['FAKE_ORIGINAL'])
if tool == 'apksigner':
    print(os.environ.get('FAKE_SIGNER_OUTPUT',
                         'Signer #1 certificate SHA-256 digest: ' + data['signer']))
    if not data.get('valid', True):
        sys.exit(2)
else:
    print("package: name='%s' versionCode='%d' versionName='%s'" %
          (data['package'], data['code'], data['name']))
'''

# build-tools 37.0.0 output captured from the normal v2-only debug APK;
# fingerprints are synthetic. DN, SHA-1 and MD5 must not supply the APK identity.
V2_CERTIFICATES = '''V2 Signer: certificate DN: C=US, O=Android, CN=Android Debug
V2 Signer: certificate SHA-256 digest: {signer}
V2 Signer: certificate SHA-1 digest: 1111111111111111111111111111111111111111
V2 Signer: certificate MD5 digest: 22222222222222222222222222222222
'''


class PublishAppTest(unittest.TestCase):
    def setUp(self):
        self.private = tempfile.TemporaryDirectory(prefix='coach-publish-test-')
        self.addCleanup(self.private.cleanup)
        self.root = Path(self.private.name)
        self.apk = self.root / 'build.apk'
        self.output = self.root / 'updates'
        self.sdk = self.root / 'sdk'
        tools = self.sdk / 'build-tools/37.0.0'
        tools.mkdir(parents=True)
        for name in ('aapt', 'apksigner'):
            tool = tools / name
            tool.write_text('#!' + sys.executable + '\n' + FAKE_TOOL)
            tool.chmod(0o700)
        self.audit = self.root / 'audit.jsonl'
        self.environment = dict(os.environ, FAKE_AUDIT=str(self.audit))
        self.environment.pop('FAKE_REPLACE_AFTER', None)
        self.original = self.payload()
        self.apk.write_bytes(self.original)

    @staticmethod
    def payload(code=2, signer='a' * 64, valid=True):
        return json.dumps(dict(package='com.coach.android.ton1252', code=code,
                               name='synthetic-' + str(code), signer=signer, valid=valid)).encode()

    def run_publish(self, **extra_env):
        return subprocess.run([sys.executable, str(PUBLISHER), str(self.apk), '--directory',
                               str(self.output), '--sdk', str(self.sdk)],
                              env=dict(self.environment, **extra_env), capture_output=True, text=True)

    def published(self):
        metadata = json.loads((self.output / 'com.coach.android.ton1252/latest.json').read_text())
        return metadata, (self.output / (metadata['sha256'] + '.apk')).read_bytes()

    def assert_coherent(self, metadata, content):
        data = json.loads(content)
        self.assertEqual(metadata['sha256'], hashlib.sha256(content).hexdigest())
        self.assertEqual(metadata['size'], len(content))
        self.assertEqual(metadata['packageName'], data['package'])
        self.assertEqual(metadata['versionCode'], data['code'])
        self.assertEqual(metadata['versionName'], data['name'])
        self.assertEqual(metadata['signerSha256'], [data['signer']])

    def test_source_replacement_during_sdk_checks_cannot_change_release(self):
        for phase in ('apksigner', 'aapt'):
            with self.subTest(replace_after=phase):
                self.output = self.root / ('updates-' + phase)
                self.apk.write_bytes(self.original)
                replacement = self.root / 'replacement.apk'
                replacement_bytes = self.payload(code=3, signer='b' * 64)
                replacement.write_bytes(replacement_bytes)
                result = self.run_publish(FAKE_REPLACE_AFTER=phase, FAKE_ORIGINAL=str(self.apk),
                                          FAKE_REPLACEMENT=str(replacement))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.apk.read_bytes(), replacement_bytes, 'race stimulus did not run')
                metadata, content = self.published()
                artifact = json.loads(content)
                print(f"replacement after {phase}: publisher exit={result.returncode}; "
                      f"metadata/artifact versions={metadata['versionCode']}/{artifact['code']}; "
                      f"signer matches={metadata['signerSha256'] == [artifact['signer']]}", flush=True)
                self.assert_coherent(metadata, content)
                self.assertEqual(content, self.original, 'unchecked source replacement was published')

    def test_private_readonly_snapshot_and_atomic_publication(self):
        result = self.run_publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata, content = self.published()
        self.assert_coherent(metadata, content)
        checks = [json.loads(line) for line in self.audit.read_text().splitlines()]
        self.assertEqual([item['tool'] for item in checks], ['apksigner', 'aapt'])
        self.assertEqual(checks[0]['path'], checks[1]['path'])
        self.assertNotEqual(checks[0]['path'], str(self.apk))
        for item in checks:
            self.assertEqual(item['parentMode'], 0o700)
            self.assertEqual(item['mode'], 0o400)
        self.assertFalse(Path(checks[0]['path']).exists(), 'private snapshot was not cleaned up')
        published = self.output / (metadata['sha256'] + '.apk')
        self.assertEqual(stat.S_IMODE(published.stat().st_mode), 0o400)
        self.assertEqual(json.loads(result.stdout)['sha256'], metadata['sha256'])

    def test_idempotent_release_and_increasing_version(self):
        self.assertEqual(self.run_publish().returncode, 0)
        before, _ = self.published()
        self.assertEqual(self.run_publish().returncode, 0)
        self.assertEqual(self.published()[0], before)
        self.apk.write_bytes(self.payload(code=3))
        result = self.run_publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.published()[0]['versionCode'], 3)
        self.assert_coherent(*self.published())

    def test_build_tools_37_v2_output_preserves_signing_identity(self):
        self.assertEqual(self.run_publish().returncode, 0)
        before = self.published()
        output = V2_CERTIFICATES.format(signer='a' * 64)
        for label in ('V2 Signer:', 'V1 Signer:', 'V3.0 Signer:'):
            with self.subTest(label=label):
                result = self.run_publish(FAKE_SIGNER_OUTPUT=output.replace('V2 Signer:', label))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.published(), before, 'output format changed an idempotent release')
        stamped = output + 'Source Stamp Signer: certificate SHA-256 digest: ' + 'b' * 64
        result = self.run_publish(FAKE_SIGNER_OUTPUT=stamped)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.published(), before, 'source stamp changed the APK signing identity')
        self.apk.write_bytes(self.payload(code=3))
        result = self.run_publish(FAKE_SIGNER_OUTPUT=output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_coherent(*self.published())
        before = self.published()
        for content, signatures, error in (
                (self.payload(code=4, signer='b' * 64),
                 V2_CERTIFICATES.format(signer='b' * 64).replace('V2 Signer:', 'V3.0 Signer:'),
                 'Update signing identity changed'),
                (self.payload(code=2), output, 'Update version must increase'),
                (self.payload(code=4, valid=False), output, 'CalledProcessError')):
            with self.subTest(error=error):
                self.apk.write_bytes(content)
                result = self.run_publish(FAKE_SIGNER_OUTPUT=signatures)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(error, result.stderr)
                self.assertEqual(self.published(), before)
                self.assertEqual(list(self.output.glob('.publish-*')), [])

    def test_source_stamp_and_other_digests_cannot_supply_apk_identity(self):
        output = V2_CERTIFICATES.format(signer='a' * 64)
        without_sha256 = '\n'.join(line for line in output.splitlines() if 'SHA-256' not in line)
        for signatures in (
                without_sha256,
                without_sha256 + '\nSource Stamp Signer: certificate SHA-256 digest: ' + 'b' * 64,
                'Source Stamp Signer #1 certificate SHA-256 digest: ' + 'b' * 64):
            with self.subTest(signatures=signatures):
                result = self.run_publish(FAKE_SIGNER_OUTPUT=signatures)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('APK has no verified signers', result.stderr)
                self.assertEqual(list(self.output.rglob('latest.json')), [])
                self.assertEqual(list(self.output.glob('*.apk')), [])
                self.assertEqual(list(self.output.glob('.publish-*')), [])

    def test_build_tools_37_preserves_all_signers_and_sorted_comparison(self):
        signers = ['a' * 64, 'b' * 64]
        legacy = '\n'.join('Signer #%d certificate SHA-256 digest: %s' % (index, signer)
                           for index, signer in enumerate(signers, 1))
        self.assertEqual(self.run_publish(FAKE_SIGNER_OUTPUT=legacy).returncode, 0)
        before = self.published()
        self.assertEqual(before[0]['signerSha256'], signers)
        modern = '\n'.join('V2 Signer #%d: certificate SHA-256 digest: %s' % (index, signer)
                           for index, signer in enumerate(reversed(signers), 1))
        result = self.run_publish(FAKE_SIGNER_OUTPUT=modern)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.published(), before)
        self.apk.write_bytes(self.payload(code=3))
        result = self.run_publish(FAKE_SIGNER_OUTPUT=modern.replace('b' * 64, 'c' * 64))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Update signing identity changed', result.stderr)
        self.assertEqual(self.published(), before)

    def test_changed_signer_and_nonincreasing_version_leave_release_unchanged(self):
        self.assertEqual(self.run_publish().returncode, 0)
        before = self.published()
        for payload in (self.payload(code=3, signer='b' * 64), self.payload(code=2, signer='b' * 64)):
            with self.subTest(payload=payload):
                self.apk.write_bytes(payload)
                self.assertNotEqual(self.run_publish().returncode, 0)
                self.assertEqual(self.published(), before)
                self.assertEqual(list(self.output.glob('.publish-*')), [])

    def test_invalid_signature_and_empty_file_publish_nothing(self):
        for content in (self.payload(valid=False), b''):
            with self.subTest(content=content):
                self.apk.write_bytes(content)
                self.assertNotEqual(self.run_publish().returncode, 0)
                self.assertEqual(list(self.output.rglob('latest.json')), [])
                self.assertEqual(list(self.output.glob('*.apk')), [])
                self.assertEqual(list(self.output.glob('.publish-*')), [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
