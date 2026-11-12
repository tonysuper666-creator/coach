import copy
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

import run_observed
from first_present import config_bytes,digest
from test_first_present import fixture


class FirstPresentRunnerTest(unittest.TestCase):
    def drive(self,changed=False,failed=False,stale=False,missing=False,cache_mode=None):
        root=run_observed.ROOT/'build/first-present-runner-test'/uuid.uuid4().hex
        root.mkdir(parents=True)
        app=root/'app.apk';app.write_bytes(b'isolated fake apk')
        test=root/'test.apk';test.write_bytes(b'isolated fake test apk')
        renderer=root/'pdftoppm';renderer.write_bytes(b'fixture renderer')
        run,trace,_=fixture()
        context=run['contextBefore']
        after=copy.deepcopy(context)
        if changed:after['rendering']['width']=2100
        commands=[];captures=[]
        class Input(io.BytesIO):
            def close(self):self.config=self.getvalue();super().close()
        class Perfetto:
            def __init__(self,*args,**kwargs):self.stdin=Input();captures.append(self)
            def wait(self,**_):return 0
            def kill(self):raise AssertionError('unexpected kill')
        class Sampler:
            def __init__(self,**kwargs):pass
            def start(self):pass
            def join(self):pass
        class Service:
            port=42000;material_sha256='c'*64;requests=[];transfers=[]
            def __init__(self,*args,**kwargs):pass
            def __enter__(self):return self
            def __exit__(self,*args):pass
        def check_output(command,**kwargs):
            if command[:2]==['git','rev-parse']:return 'a'*40
            if command[:2]==['git','status']:return ''
            if command[-1]=='ro.kernel.qemu':return '1'
            if command[0]=='aapt-unit':return "package: name='"+run['applicationPackage']+('.test' if command[-1]==str(test) else '')+"'"
            raise AssertionError(command)
        def execute(command,**kwargs):
            commands.append(command)
            if 'instrument' in command:
                trace.update(runId='stale' if stale else command[command.index('readerRunId')+1],applicationPackage=run['applicationPackage'])
                selected_mode=command[command.index('readerNeighbourCache')+1]
                trace.update(firstPresentCacheMode=selected_mode,mode='three_page_window' if selected_mode=='on' else 'visible_pages_only')
                kwargs['stdout'].write('FAILURES!!!\nTests run: 1, Failures: 1' if failed else 'OK (1 test)')
            elif 'exec-out' in command:
                kwargs['stdout'].write(b'' if missing else json.dumps(trace).encode())
            elif 'pull' in command:Path(command[-1]).write_bytes(b'not a real trace; runner unit only')
            elif 'install' not in command:raise AssertionError(command)
            return SimpleNamespace(returncode=0)
        args=['runner','--platform','android','--suite','first-present','--perfetto',
              '--package',run['applicationPackage'],'--host','127.0.0.1','--serial','emulator-unit','--aapt2','aapt-unit',
              '--apk',str(app),'--test-apk',str(test),'--output',str(root/'out')]
        if cache_mode is not None:args+=['--first-present-cache',cache_mode]
        with patch('sys.argv',args),patch('run_observed.sys.platform','darwin'),patch('run_observed.Fixture',Service), \
             patch('run_observed.threading.Thread',Sampler),patch('run_observed.subprocess.Popen',Perfetto), \
             patch('run_observed.subprocess.check_output',check_output),patch('run_observed.subprocess.run',execute), \
             patch('run_observed.shutil.which',return_value=str(renderer)), \
             patch('run_observed.capture_context',side_effect=[copy.deepcopy(context),after]), \
             patch('run_observed.summarize',return_value=dict(complete=True)) as summary:
            if failed:
                with self.assertRaisesRegex(RuntimeError,'Android instrumentation failed'):run_observed.main()
            elif changed:
                with self.assertRaisesRegex(RuntimeError,'memory sampler failed'):run_observed.main()
            else:run_observed.main()
            if failed:summary.assert_not_called()
            else:summary.assert_called_once_with(trace,dict(input_next=1,input_citation=1))
        identity=json.loads((root/'out/run.json').read_text())
        self.assertEqual('first-present.pbtxt',identity['perfettoConfig'])
        self.assertEqual(config_bytes(run['applicationPackage']),captures[0].stdin.config)
        self.assertEqual(digest(config_bytes(run['applicationPackage'])),identity['perfettoConfigSha256'])
        self.assertEqual([dict(kind='input_next',page=2)],identity['selectedActions'])
        self.assertEqual('c'*64,identity['measurementContract']['material'])
        self.assertEqual(digest(renderer.read_bytes()),identity['measurementContract']['rendering']['rendererSha256'])
        native=[command for command in commands if 'instrument' in command]
        self.assertEqual(1,len(native))
        self.assertIn('com.coach.android.ReaderFirstPresentSmoke',native[0])
        self.assertEqual(run['applicationPackage']+'.test/androidx.test.runner.AndroidJUnitRunner',native[0][-1])
        self.assertEqual(1,native[0].count('readerNeighbourCache'))
        self.assertEqual(cache_mode or 'off',native[0][native[0].index('readerNeighbourCache')+1])
        self.assertEqual(cache_mode or 'off',identity['firstPresentCacheMode'])
        self.assertIs(identity['memorySamplerStarted'],True)
        self.assertIs(identity['memorySamplerJoined'],True)
        self.assertEqual(not failed,identity['instrumentationPassed'])
        return identity

    def test_one_action_path_binds_context_config_and_the_correct_instrumentation(self):
        identity=self.drive()
        self.assertEqual([],identity['memorySamplerErrors'])
        self.assertEqual(identity['contextBefore'],identity['contextAfter'])

    def test_changed_context_fails_after_preserving_capture_and_run_receipt(self):
        identity=self.drive(changed=True)
        self.assertIn('measurement_context_changed',identity['memorySamplerErrors'])

    def test_adb_zero_with_failed_test_keeps_run_bound_pid_but_no_summary(self):
        identity=self.drive(failed=True)
        self.assertEqual(0,identity['commandExit'])
        self.assertEqual(42,identity['measurementPid'])
        self.assertEqual('run_bound_trace_snapshot',identity['measurementPidSource'])
        self.assertFalse(identity['instrumentationPassed'])
        self.assertEqual(0,identity['perfettoExit'])

    def test_stale_failed_snapshot_pid_is_never_reused(self):
        identity=self.drive(failed=True,stale=True)
        self.assertIsNone(identity['measurementPid'])
        self.assertEqual('snapshot_run_or_package_mismatch',identity['measurementPidError'])

    def test_missing_failed_snapshot_preserves_missing_pid_and_cleanup_receipts(self):
        identity=self.drive(failed=True,missing=True)
        self.assertIsNone(identity['measurementPid'])
        self.assertTrue(identity['measurementPidError'])
        self.assertEqual(0,identity['perfettoExit'])

    def test_on_control_uses_same_one_test_and_the_explicit_instrumentation_argument(self):
        identity=self.drive(cache_mode='on')
        self.assertEqual('on',identity['firstPresentCacheMode'])

    def test_explicit_off_control_uses_the_same_entry_and_capture_contract(self):
        identity=self.drive(cache_mode='off')
        self.assertEqual('off',identity['firstPresentCacheMode'])

    def test_control_is_rejected_for_other_suites_or_invalid_values_before_execution(self):
        for suite,mode in (('behavior','on'),('first-present','invalid')):
            with self.subTest(suite=suite,mode=mode):
                args=['runner','--platform','android','--suite',suite,'--perfetto',
                      '--first-present-cache',mode,'--output','unused']
                with patch('sys.argv',args),patch('run_observed.sys.stderr'),patch('run_observed.subprocess.run') as native:
                    with self.assertRaises(SystemExit) as raised:run_observed.main()
                    self.assertEqual(2,raised.exception.code);native.assert_not_called()


if __name__=='__main__':unittest.main()
