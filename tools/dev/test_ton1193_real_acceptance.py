import json
import contextlib
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest import mock

from . import check_ton1193_real_acceptance as driver
from .ton1175_switch import GateError


class LiveConfigurationTest(unittest.TestCase):
    def test_controller_diagnostics_rejects_unclosed_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'controller-recovering-start.json'
            value = {'phase': 'recovering', 'action': 'start', 'step': 'RECEIPT', 'reason': 'IO_ERROR'}
            path.write_text(json.dumps(value))
            self.assertEqual(driver.controller_diagnostics(root), [value])
            value['path'] = '/private/secret'
            path.write_text(json.dumps(value))
            with self.assertRaisesRegex(GateError, 'invalid controller diagnostic'):
                driver.controller_diagnostics(root)

    def test_binds_exact_lifetime_log_and_real_verifiers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / 'home/Coach/logs'
            logs.mkdir(parents=True)
            log = logs / 'activity-1234-2026-11-06.log'
            log.write_text('{}\n')
            plist = root / 'service.plist'
            plist.write_bytes(plistlib.dumps({'EnvironmentVariables': {'JAVA_HOME': '/java'}}))
            path = root / 'controller.json'
            config = {'plist': str(plist)}
            with mock.patch.object(driver.probe, 'control_diagnostic', return_value={'lifetime': '1234'}):
                diagnostic, java = driver.configure(path, config, {'home': str(root / 'home')}, 123, {})
            stored = json.loads(path.read_text())
            self.assertEqual(stored['accessLogs'], [str(log)])
            self.assertEqual(stored['idleEvidence'], str(root / 'idle.json'))
            self.assertEqual(stored['idleVerifier'][2:4], ['tools.dev.ton1175_verify', 'idle'])
            self.assertEqual(stored['logVerifier'][2:], ['tools.dev.ton1175_verify', 'logs'])
            self.assertEqual(str(java), '/java')
            self.assertEqual(diagnostic['lifetime'], '1234')

    def test_ambiguous_or_linked_lifetime_log_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / 'home/Coach/logs'
            logs.mkdir(parents=True)
            log = logs / 'activity-1234-2026-11-06.log'
            log.write_text('{}\n')
            with mock.patch.object(driver.probe, 'control_diagnostic', return_value={'lifetime': '1234'}):
                (logs / 'activity-1234-2026-11-07.log').write_text('{}\n')
                with self.assertRaises(GateError):
                    driver.configure(root / 'controller.json', {}, {'home': str(root / 'home')}, 123, {})
                (logs / 'activity-1234-2026-11-07.log').unlink()
                log.unlink()
                log.symlink_to(root / 'anything')
                with self.assertRaises(GateError):
                    driver.configure(root / 'controller.json', {}, {'home': str(root / 'home')}, 123, {})


class IdleRecordingTest(unittest.TestCase):
    def test_inspector_diagnostic_retains_only_fixed_line_or_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence = root / 'idle.json'
            evidence.write_text(json.dumps({'recording': str(root / 'recording.jfr'),
                'windowStart': 'start', 'windowEnd': 'end', 'pid': 123}))
            config = {'idleEvidence': str(evidence), 'installation': 'installation'}
            result = mock.Mock(returncode=1, stderr=b'JFR evidence rejected; inspector line 144\n')
            with mock.patch.object(driver.verify, 'idle', side_effect=GateError('dependency or evidence command failed')), \
                 mock.patch.object(driver.verify, 'staged_bridge_port', return_value=contextlib.nullcontext(41500)), \
                 mock.patch.object(driver.subprocess, 'run', return_value=result) as run:
                report = {}
                driver.diagnose_idle_failure(config, root, Path('/java'), report)
                self.assertEqual(report['inspectorFailureLine'], 144)
                self.assertEqual(run.call_args.args[0][-1], '41500')
                result.stderr = b'private data 144'
                driver.diagnose_idle_failure(config, root, Path('/java'), report)
                self.assertIsNone(report['inspectorFailureLine'])
                result.returncode = 0
                result.stdout = json.dumps({'pid': 123, 'socketReads': 2,
                    'attributedHealthReads': 2, 'clientReads': 0, 'private': 'secret'}).encode()
                report = {}
                driver.diagnose_idle_failure(config, root, Path('/java'), report)
                self.assertEqual(report['fullSocketReads'], {'pid': 123, 'socketReads': 2,
                    'attributedHealthReads': 2, 'clientReads': 0})

    def test_diagnostic_keeps_only_allowlisted_errors(self):
        config = {'idleEvidence': 'idle', 'installation': 'installation'}
        report = {}
        with mock.patch.object(driver.verify, 'idle', side_effect=GateError('JFR recording changed')):
            driver.diagnose_idle_failure(config, Path('/fixture'), Path('/java'), report)
        self.assertEqual(report['idleFailureDiagnostic'], 'JFR recording changed')
        with mock.patch.object(driver.verify, 'idle', side_effect=GateError('private content')):
            driver.diagnose_idle_failure(config, Path('/fixture'), Path('/java'), report)
        self.assertEqual(report['idleFailureDiagnostic'], 'other gate rejection')

    def test_positive_socket_read_fails_before_writing_idle_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report = {}

            def execute(argv, **kwargs):
                if 'JFR.dump' in argv:
                    Path(next(v.split('=', 1)[1] for v in argv if v.startswith('filename='))).touch()
                if 'jdk.CPULoad' in argv:
                    return json.dumps({'recording': {'events': [{'values': {
                        'startTime': '1970-01-01T00:16:41Z'}}]}}).encode()
                if any(x.endswith('Ton1175Jfr.java') for x in argv):
                    return json.dumps({'pid': 123, 'socketReads': 1,
                                       'attributedHealthReads': 0, 'clientReads': 1}).encode()
                return b'identity'

            control = mock.MagicMock()
            control.__enter__.return_value.ask.return_value = {}
            with mock.patch.object(driver.subprocess, 'check_output', side_effect=execute), \
                 mock.patch.object(driver.coverage, 'Control', return_value=control), \
                 mock.patch.object(driver.coverage, 'healthy'), \
                 mock.patch.object(driver.verify, 'staged_bridge_port', return_value=contextlib.nullcontext(41500)), \
                 mock.patch.object(driver.time, 'sleep'), \
                 mock.patch.object(driver.time, 'time', return_value=1000), \
                 mock.patch.object(driver.time, 'monotonic', return_value=10):
                with self.assertRaisesRegex(GateError, 'SocketRead'):
                    driver.recording(root, root / 'java', 123, 'activity.sock',
                        {'installation': str(root / 'installation.json'), 'idleEvidence': str(root / 'idle.json')}, report)
            self.assertEqual(report['earlySocketReads']['clientReads'], 1)
            self.assertIn('<setting name="stackTrace">true</setting>', (root / 'idle.jfc').read_text())
            self.assertFalse((root / 'idle.json').exists())


if __name__ == '__main__':
    unittest.main()
