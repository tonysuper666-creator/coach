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
    def drive(self,changed=False):
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
            if 'instrument' in command:kwargs['stdout'].write('OK (1 test)')
            elif 'exec-out' in command:kwargs['stdout'].write(json.dumps(trace).encode())
            elif 'pull' in command:Path(command[-1]).write_bytes(b'not a real trace; runner unit only')
            elif 'install' not in command:raise AssertionError(command)
            return SimpleNamespace(returncode=0)
        args=['runner','--platform','android','--suite','first-present','--perfetto',
              '--package',run['applicationPackage'],'--host','127.0.0.1','--serial','emulator-unit','--aapt2','aapt-unit',
              '--apk',str(app),'--test-apk',str(test),'--output',str(root/'out')]
        with patch('sys.argv',args),patch('run_observed.sys.platform','darwin'),patch('run_observed.Fixture',Service), \
             patch('run_observed.threading.Thread',Sampler),patch('run_observed.subprocess.Popen',Perfetto), \
             patch('run_observed.subprocess.check_output',check_output),patch('run_observed.subprocess.run',execute), \
             patch('run_observed.shutil.which',return_value=str(renderer)), \
             patch('run_observed.capture_context',side_effect=[copy.deepcopy(context),after]), \
             patch('run_observed.summarize',return_value=dict(complete=True)) as summary:
            if changed:
                with self.assertRaisesRegex(RuntimeError,'memory sampler failed'):run_observed.main()
            else:run_observed.main()
            summary.assert_called_once_with(trace,dict(input_next=1,input_citation=1))
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
        self.assertIs(identity['memorySamplerStarted'],True)
        self.assertIs(identity['memorySamplerJoined'],True)
        return identity

    def test_one_action_path_binds_context_config_and_the_correct_instrumentation(self):
        identity=self.drive()
        self.assertEqual([],identity['memorySamplerErrors'])
        self.assertEqual(identity['contextBefore'],identity['contextAfter'])

    def test_changed_context_fails_after_preserving_capture_and_run_receipt(self):
        identity=self.drive(changed=True)
        self.assertIn('measurement_context_changed',identity['memorySamplerErrors'])


if __name__=='__main__':unittest.main()
