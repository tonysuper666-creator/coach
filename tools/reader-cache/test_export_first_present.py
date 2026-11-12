import json
import csv
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import export_first_present as exporter
from first_present import digest
from test_first_present import fixture


class ExportFirstPresentTest(unittest.TestCase):
    def test_two_offline_exports_bind_hashes_and_preserve_false_acceptance(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);binary=root/'cached';binary.write_bytes(b'unit binary')
            run,trace,rows=fixture()
            (root/'run.json').write_text(json.dumps(run));(root/'trace.json').write_text(json.dumps(trace))
            (root/'frames.perfetto-trace').write_bytes(b'raw fixture only; no real TP in this unit test')
            commands=[]
            def query(command,**kwargs):
                commands.append(command)
                self.assertIn('--no-agent',command);self.assertIn('--format',command)
                if Path(command[-2]).name=='first-present.sql':
                    keys=list(dict.fromkeys(key for row in rows for key in row))
                    writer=csv.DictWriter(kwargs['stdout'],fieldnames=keys);writer.writeheader();writer.writerows(rows)
                else:kwargs['stdout'].write('name,value,severity,source,idx\nftrace_setup_errors,36,info,analysis,0\n')
                return SimpleNamespace(returncode=0)
            with patch('export_first_present.TP_SHA256',digest(binary.read_bytes())),patch('export_first_present.subprocess.run',query):
                report=exporter.export(root,binary)
            self.assertEqual(2,len(commands))
            self.assertTrue(report['complete'],report['errors'])
            self.assertFalse(report['firstPresentLatencyVerified']);self.assertFalse(report['matchedBaselineObserved'])
            updated=json.loads((root/'run.json').read_text())
            self.assertEqual(digest((root/'frames.perfetto-trace').read_bytes()),updated['rawTraceSha256'])
            self.assertEqual(digest((root/'first-present.csv').read_bytes()),updated['framesCsvSha256'])
            self.assertEqual('36',updated['traceQualityIssues'][0]['value'])

    def test_missing_or_wrong_binary_is_not_run_or_downloaded(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);binary=root/'wrong';binary.write_bytes(b'not the cached binary')
            with patch('export_first_present.subprocess.run') as run:
                with self.assertRaisesRegex(ValueError,'missing_or_wrong_cached'):exporter.export(root,binary)
                run.assert_not_called()

    def test_failed_query_keeps_raw_trace_exit_receipt_and_csv(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);binary=root/'cached';binary.write_bytes(b'unit binary')
            run,_,_=fixture();(root/'run.json').write_text(json.dumps(run))
            raw=root/'frames.perfetto-trace';raw.write_bytes(b'raw unit trace')
            with patch('export_first_present.TP_SHA256',digest(binary.read_bytes())), \
                 patch('export_first_present.subprocess.run',return_value=SimpleNamespace(returncode=7)):
                with self.assertRaisesRegex(RuntimeError,'query_failed'):exporter.export(root,binary)
            self.assertEqual(7,json.loads((root/'first-present-query-receipts.json').read_text())[0]['exit'])
            self.assertEqual(b'raw unit trace',raw.read_bytes())
            self.assertTrue((root/'first-present.csv').exists())
            self.assertFalse((root/'first-present-report.json').exists())

    def test_existing_export_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);binary=root/'cached';binary.write_bytes(b'unit binary')
            run,_,_=fixture();(root/'run.json').write_text(json.dumps(run))
            (root/'first-present.csv').write_text('previous evidence')
            with patch('export_first_present.TP_SHA256',digest(binary.read_bytes())),patch('export_first_present.subprocess.run') as command:
                with self.assertRaisesRegex(ValueError,'refusing_to_overwrite'):exporter.export(root,binary)
                command.assert_not_called()
            self.assertEqual('previous evidence',(root/'first-present.csv').read_text())

    def test_missing_pid_reports_exact_preflight_gap_without_querying(self):
        for pid in (None,False,0):
            with self.subTest(pid=pid),tempfile.TemporaryDirectory() as name:
                root=Path(name);binary=root/'cached';binary.write_bytes(b'unit binary')
                run,_,_=fixture()
                if pid is None:run.pop('measurementPid')
                else:run['measurementPid']=pid
                (root/'run.json').write_text(json.dumps(run))
                raw=root/'frames.perfetto-trace';raw.write_bytes(b'failed capture retained')
                with patch('export_first_present.TP_SHA256',digest(binary.read_bytes())),patch('export_first_present.subprocess.run') as query:
                    report=exporter.export(root,binary)
                    query.assert_not_called()
                self.assertFalse(report['complete'])
                self.assertIn('missing_measurement_pid' if pid is None else 'invalid_measurement_pid',report['errors'])
                self.assertEqual(0,report['queriesExecuted'])
                self.assertEqual(report,json.loads((root/'first-present-report.json').read_text()))
                self.assertEqual(b'failed capture retained',raw.read_bytes())
                self.assertFalse((root/'first-present.csv').exists())

    def test_failed_native_test_cannot_be_accepted_even_when_pid_was_preserved(self):
        with tempfile.TemporaryDirectory() as name:
            root=Path(name);binary=root/'cached';binary.write_bytes(b'unit binary')
            run,_,_=fixture();run['measurementContract']['origin']='android_capture'
            run.update(commandExit=0,instrumentationPassed=False)
            (root/'run.json').write_text(json.dumps(run))
            with patch('export_first_present.TP_SHA256',digest(binary.read_bytes())),patch('export_first_present.subprocess.run') as query:
                report=exporter.export(root,binary)
                query.assert_not_called()
                with self.assertRaisesRegex(ValueError,'refusing_to_overwrite'):exporter.export(root,binary)
            self.assertEqual(42,report['measurementPid'])
            self.assertEqual(['native_test_failed_or_missing_pass_receipt'],report['errors'])
            self.assertEqual(0,report['queriesExecuted'])
            for key in ('firstPresentLatencyVerified','matchedTupleContract','matchedBaselineObserved','physicalMemoryBudgetVerified','gpuReleaseVerified'):
                self.assertFalse(report[key])


if __name__=='__main__':unittest.main()
