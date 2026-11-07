import contextlib
import io
import json
import os
import plistlib
import unittest
from unittest import mock

from tools.dev import ton1193_diagnose as diagnosis
from tools.dev import ton1193_preflight as preflight
from tools.dev.ton1175_switch import GateError
from tools.dev import test_ton1193_preflight as fixtures


class DiagnoseTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.PreflightTest('runTest')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.f = fixture
        self.plist = fixture.home / 'Library/LaunchAgents/local.coach.server.plist'
        self.installation = fixture.home / 'Coach/service/installation.json'
        self.plist.parent.mkdir(parents=True)
        (fixture.home / '.local/bin').mkdir(parents=True)
        self.plist_data = fixture.sides['old']['plist']
        env = self.plist_data['EnvironmentVariables']
        env['BRIDGE_PORT'] = '8791'
        env['PATH'] = ':'.join(dict.fromkeys([str(fixture.targets['node'].parent),
            str(fixture.home / '.local/bin'), '/opt/homebrew/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin']))
        self.info = dict(fixture.sides['old']['info'], port=19002, tailHost='127.0.0.1',
                         dataDir=env['COACH_DATA_DIR'], runDir=env['COACH_RUN_DIR'])
        self.write_pointers()

    def write_pointers(self):
        self.plist.write_bytes(plistlib.dumps(self.plist_data))
        self.installation.write_text(json.dumps(self.info))
        self.plist.chmod(0o600)
        self.installation.chmod(0o600)

    def call(self):
        return diagnosis._diagnose(self.f.home, self.f.roots)

    def test_old_side_only_does_not_stage_or_run_candidate(self):
        with mock.patch.object(diagnosis, 'capability', return_value={'coverageProtocol': 'absent'}) as cap, \
             mock.patch.object(preflight, '_stage', side_effect=AssertionError('stage was read')), \
             mock.patch.object(diagnosis.verify, 'candidate', side_effect=AssertionError('candidate was run')), \
             mock.patch.object(diagnosis.snapshots, 'capture', side_effect=AssertionError('snapshot persisted')):
            self.assertEqual(self.call()['coverageProtocol'], 'absent')
        cap.assert_called_once()
        self.assertEqual(list(self.f.private.iterdir()), [])

    def test_unknown_plist_or_environment_key_rejected(self):
        for location in ('plist', 'env'):
            with self.subTest(location=location):
                target = self.plist_data if location == 'plist' else self.plist_data['EnvironmentVariables']
                target['INJECTED'] = 'unexpected'
                self.write_pointers()
                with self.assertRaises(GateError):
                    self.call()
                del target['INJECTED']

    def test_pointer_links_and_changed_toolchain_rejected(self):
        self.plist.unlink()
        self.plist.symlink_to(self.f.plist)
        with self.assertRaises(GateError):
            self.call()
        self.plist.unlink()
        self.write_pointers()
        hardlink = self.plist.parent / 'second-link'
        os.link(self.plist, hardlink)
        with self.assertRaises(GateError):
            self.call()
        hardlink.unlink()
        def tamper(*args):
            with self.f.targets['python'].open('ab') as stream:
                stream.write(b'tamper')
            return {'coverageProtocol': 'absent'}
        with mock.patch.object(diagnosis, 'capability', side_effect=tamper):
            with self.assertRaisesRegex(GateError, 'diagnostic paths changed'):
                self.call()

    def test_cli_absent_is_nonzero_and_output_redacted(self):
        output = io.StringIO()
        with mock.patch.object(diagnosis, 'diagnose', return_value={'coverageProtocol': 'absent', 'pathGroupWritable': True}), \
             mock.patch.object(diagnosis.sys, 'argv', ['diagnose', 'diagnose']), \
             contextlib.redirect_stdout(output):
            self.assertEqual(diagnosis.main(), 1)
        result = json.loads(output.getvalue())
        self.assertEqual(result['status'], 'legacy-protocol-absent')
        self.assertEqual(result['scope'], 'old-side-only; not switch preflight')
        self.assertNotIn(str(self.f.home), output.getvalue())


if __name__ == '__main__':
    unittest.main()
