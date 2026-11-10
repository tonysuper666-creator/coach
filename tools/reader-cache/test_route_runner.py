"""Runner format integration with a fake device; never starts a native app."""
import json
import io
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

import run_observed
import test_route_control
from test_queue_readiness import add_watch


class RouteRunnerTest(unittest.TestCase):
    def test_socket_only_native_report_needs_no_cio_samples_and_uses_route_argument(self):
        self.run_case(False,execute_cycle=True)

    def test_disabled_sampler_starts_no_thread_and_is_explicitly_recorded(self):
        self.run_case(True)

    def test_partial_observers_have_separate_commands_files_and_metadata(self):
        for mode in ('stage','meminfo','none'):
            with self.subTest(mode=mode): self.run_case(False,mode,execute_cycle=True)

    def test_observer_exception_keeps_failed_span_and_fails_runner(self):
        self.run_case(False,execute_cycle=True,fail_stage=True)

    def test_stale_or_wrong_process_stage_fails_after_retaining_associations(self):
        for changed in ({'runId':'previous'},{'pid':99},{'stage':'transport_complete'}):
            with self.subTest(changed=changed): self.run_case(False,execute_cycle=True,stage_overrides=changed)

    def test_queue_observation_is_forwarded_and_retains_request_associations(self):
        self.run_case(False,'none',watch_queue=True)

    def test_scheduling_trace_is_forwarded_collected_and_joined(self):
        self.run_case(False,'none',schedule_trace=True)

    def test_scheduling_trace_does_not_mix_other_observers_or_frame_capture(self):
        for extra in (['--transport-observer','both'],['--transport-observer','none','--socket-queue-watch'],
                      ['--transport-observer','none','--perfetto']):
            args=['run_observed.py','--platform','android','--suite','route','--socket-scheduling-trace','--output','unused',*extra]
            with self.subTest(extra=extra),patch('sys.argv',args),patch('run_observed.sys.stderr'):
                with self.assertRaises(SystemExit) as raised: run_observed.main()
                self.assertEqual(2,raised.exception.code)

    def test_queue_observer_requires_route_and_no_repeated_adb_observer(self):
        for suite,mode in (('route','both'),('route','stage'),('transport','none'),('performance','none')):
            args=['run_observed.py','--platform','android','--suite',suite,'--transport-observer',mode,
                '--socket-queue-watch','--output','unused']
            with self.subTest(suite=suite,mode=mode),patch('sys.argv',args),patch('run_observed.sys.stderr'):
                with self.assertRaises(SystemExit) as raised: run_observed.main()
                self.assertEqual(2,raised.exception.code)

    def test_sampler_cannot_be_disabled_for_memory_or_ui_acceptance(self):
        for suite in ('performance','behavior','lifecycle'):
            for selection in (['--no-transport-memory-sampler'], *[['--transport-observer',mode] for mode in ('both','stage','meminfo','none')]):
                args=['run_observed.py','--platform','android','--suite',suite,'--output','unused',*selection]
                with self.subTest(suite=suite,selection=selection),patch('sys.argv',args),patch('run_observed.sys.stderr'),patch('run_observed.Fixture') as service:
                    with self.assertRaises(SystemExit) as raised: run_observed.main()
                    self.assertEqual(2,raised.exception.code)
                    service.assert_not_called()

    def test_conflicting_old_and_new_observer_flags_are_rejected(self):
        args=['run_observed.py','--platform','android','--suite','route','--output','unused',
            '--transport-observer','stage','--no-transport-memory-sampler']
        with patch('sys.argv',args),patch('run_observed.sys.stderr'):
            with self.assertRaises(SystemExit) as raised: run_observed.main()
            self.assertEqual(2,raised.exception.code)

    def run_case(self,disabled,mode=None,execute_cycle=False,fail_stage=False,stage_overrides=None,watch_queue=False,schedule_trace=False):
        selected=mode or ('none' if disabled else 'both')
        enabled=selected!='none'
        clients,servers=test_route_control.RouteControlTest().records()
        if watch_queue:
            for client in clients: add_watch(client)
        if schedule_trace:
            for client in clients: client.update(readTraceLabel='ReaderSocket/first-read/'+client['probeId'],readTid=1)
        root=run_observed.ROOT/'build/reader-runner-tests'/uuid.uuid4().hex
        root.mkdir(parents=True)
        apk=root/'app.apk';apk.write_bytes(b'invented package')
        test_apk=root/'test.apk';test_apk.write_bytes(b'invented test package')
        out=root/'out'
        commands=[]
        sampler_events=[]
        captures=[]

        class FakePerfetto:
            def __init__(self,*args,**kwargs): self.stdin=io.BytesIO();self.waited=False;captures.append(self)
            def wait(self,**_): self.waited=True;return 0
            def kill(self): raise AssertionError('unexpected capture termination')

        class FakeFixture:
            def __init__(self,*_,**options):
                self.port=45678;self.material_sha256='a'*64
                self.requests=servers;self.transfers=servers
                if options.get('loopback_control') is not True: raise AssertionError('missing second listener')
            def __enter__(self): return self
            def __exit__(self,*_): pass

        class NoSampler:
            def __init__(self,**kwargs):
                sampler_events.append('constructed');self.target=kwargs['target']
            def start(self):
                sampler_events.append('started')
                if execute_cycle: self.target()
            def join(self): sampler_events.append('joined')

        class OneCycleStop:
            def __init__(self): self.stopped=False
            def is_set(self): return self.stopped
            def set(self): self.stopped=True
            def wait(self,*_): self.stopped=True

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
            if 'pull' in command:
                Path(command[-1]).write_bytes(b'invented scheduling trace fixture')
                return SimpleNamespace(returncode=0)
            if command[-1]=='cache/reader-stage.json':
                if fail_stage: raise OSError('unit observer failure')
                data=dict(runId='unit-run',suite='route',pid=1234,stage='transport_running')
                data.update(stage_overrides or {})
                return SimpleNamespace(returncode=0,stdout=json.dumps(data))
            if 'dumpsys' in command:
                return SimpleNamespace(returncode=0,stdout='unit meminfo')
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
        if disabled: args.append('--no-transport-memory-sampler')
        if mode is not None: args.extend(['--transport-observer',mode])
        if watch_queue: args.append('--socket-queue-watch')
        if schedule_trace: args.append('--socket-scheduling-trace')
        with patch('sys.argv',args),patch('run_observed.sys.platform','darwin'), \
                patch('run_observed.Fixture',FakeFixture),patch('run_observed.threading.Thread',NoSampler), \
                patch('run_observed.threading.Event',OneCycleStop),patch('run_observed.uuid.uuid4',return_value=SimpleNamespace(hex='unit-run')), \
                patch('run_observed.subprocess.Popen',FakePerfetto), \
                patch('run_observed.subprocess.check_output',check_output),patch('run_observed.subprocess.run',run):
            if fail_stage:
                with self.assertRaisesRegex(RuntimeError,'memory sampler failed'): run_observed.main()
            elif stage_overrides:
                with self.assertRaisesRegex(RuntimeError,'active stage was not observed'): run_observed.main()
            else:
                run_observed.main()
        self.assertTrue(json.loads((out/'route-comparison.json').read_text())['complete'])
        self.assertEqual('route',json.loads((out/'summary.json').read_text())['suite'])
        instrument=next(command for command in commands if 'instrument' in command)
        self.assertEqual('host-loopback',instrument[instrument.index('readerRouteControl')+1])
        if watch_queue:
            self.assertEqual('available',instrument[instrument.index('readerQueueWatch')+1])
            self.assertTrue(json.loads((out/'queue-watch.json').read_text())['complete'])
        if schedule_trace:
            self.assertEqual('enabled',instrument[instrument.index('readerSocketSchedule')+1])
            self.assertTrue((out/'socket-scheduling.perfetto-trace').exists())
            self.assertFalse((out/'frames.perfetto-trace').exists())
            self.assertTrue(captures[0].stdin.closed);self.assertTrue(captures[0].waited)
        self.assertFalse(json.loads((out/'run.json').read_text())['packetCapture']['requested'])
        identity=json.loads((out/'run.json').read_text())
        for key in ('memorySamplerEnabled','memorySamplerStarted','memorySamplerJoined'):
            self.assertIs(enabled,identity[key])
        self.assertEqual(['constructed','started','joined'] if enabled else [],sampler_events)
        observer=identity['androidObserver']
        self.assertEqual(selected,observer['mode'])
        stage_count=int(execute_cycle and selected in ('both','stage'))
        meminfo_count=int(execute_cycle and selected in ('both','meminfo') and not fail_stage)
        self.assertEqual(dict(stage=stage_count,meminfo=meminfo_count),observer['commandCounts'])
        self.assertEqual(stage_count,sum(command[-1]=='cache/reader-stage.json' for command in commands))
        self.assertEqual(meminfo_count,sum('dumpsys' in command for command in commands))
        if enabled and execute_cycle:
            samples=out/observer['samplesFile']
            row=json.loads(samples.read_text().strip())
            self.assertEqual(selected,row['observerMode'])
            self.assertEqual(bool(meminfo_count),'meminfo' in row)
            if fail_stage: self.assertEqual('OSError',row['operations']['stage']['error'])
        self.assertEqual(['OSError'] if fail_stage else [],identity['memorySamplerErrors'])
        if selected in ('stage','none'): self.assertFalse((out/'memory.jsonl').exists())
        if not fail_stage:
            audit=json.loads((out/'stage-audit.json').read_text())
            self.assertEqual(selected in ('both','stage'),audit['requested'])
            self.assertEqual(selected in ('both','stage') and not stage_overrides,audit['verified'])


if __name__=='__main__': unittest.main()
