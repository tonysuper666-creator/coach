"""Runner format integration with a fake device; never starts a native app."""
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

import run_observed
import test_route_control


class RouteRunnerTest(unittest.TestCase):
    def test_socket_only_native_report_needs_no_cio_samples_and_uses_route_argument(self):
        clients,servers=test_route_control.RouteControlTest().records()
        root=run_observed.ROOT/'build/reader-runner-tests'/uuid.uuid4().hex
        root.mkdir(parents=True)
        apk=root/'app.apk';apk.write_bytes(b'invented package')
        test_apk=root/'test.apk';test_apk.write_bytes(b'invented test package')
        out=root/'out'
        commands=[]

        class FakeFixture:
            def __init__(self,*_,**options):
                self.port=45678;self.material_sha256='a'*64
                self.requests=servers;self.transfers=servers
                if options.get('loopback_control') is not True: raise AssertionError('missing second listener')
            def __enter__(self): return self
            def __exit__(self,*_): pass

        class NoSampler:
            def __init__(self,**_): pass
            def start(self): pass
            def join(self): pass

        def check_output(command,**_):
            if 'getprop' in command: return '1'
            if command[0]=='aapt-unit':
                package=run_observed.PACKAGE+('.test' if command[-1]==str(test_apk) else '')
                return f"package: name='{package}'"
            if command[:2]==['git','rev-parse']: return 'test-sha'
            if command[:2]==['git','status']: return ''
            raise AssertionError(command)

        def run(command,**kwargs):
            commands.append(command)
            if 'instrument' in command:
                kwargs['stdout'].write('OK (1 test)')
            elif 'exec-out' in command:
                report={'socketControl':clients} if command[-1]=='cache/reader-transport.json' else {'process':{'pid':1234}}
                kwargs['stdout'].write(json.dumps(report).encode())
            elif 'install' not in command:
                raise AssertionError(command)
            return SimpleNamespace(returncode=0)

        args=['run_observed.py','--platform','android','--suite','route','--host','127.0.0.2',
            '--serial','emulator-5554','--aapt2','aapt-unit','--apk',str(apk),'--test-apk',str(test_apk),'--output',str(out)]
        with patch('sys.argv',args),patch('run_observed.sys.platform','darwin'), \
                patch('run_observed.Fixture',FakeFixture),patch('run_observed.threading.Thread',NoSampler), \
                patch('run_observed.subprocess.check_output',check_output),patch('run_observed.subprocess.run',run):
            run_observed.main()
        self.assertTrue(json.loads((out/'route-comparison.json').read_text())['complete'])
        self.assertEqual('route',json.loads((out/'summary.json').read_text())['suite'])
        instrument=next(command for command in commands if 'instrument' in command)
        self.assertEqual('host-loopback',instrument[instrument.index('readerRouteControl')+1])
        self.assertFalse(json.loads((out/'run.json').read_text())['packetCapture']['requested'])


if __name__=='__main__': unittest.main()
