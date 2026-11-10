"""Real synthetic loopback I/O and fail-closed boundary regressions; no native UI."""
import copy
import http.client
import json
from pathlib import Path
import socket
import time
import unittest
from unittest.mock import patch
import uuid

from correlate_transport import correlate
from host_socket_boundary import SocketBoundaryRelay, analyze_boundary, headers, digest
from test_fixture_transport import fixture
import host_probe


def collect():
    run_id=uuid.uuid4().hex
    probe=run_id+'-boundary-1'
    with fixture(single_boundary=True,host_clock=True) as service:
        with SocketBoundaryRelay('127.0.0.1',service.port,probe,10_000_000) as relay:
            def call(target):
                with socket.socket() as client:
                    client.settimeout(3)
                    start=time.monotonic_ns()
                    client.connect(('127.0.0.1',relay.port))
                    connected=time.monotonic_ns()
                    local=client.getsockname()[1]
                    request=(f'GET {target} HTTP/1.1\r\nHost: 127.0.0.1:{relay.port}\r\n'
                             'Authorization: Bearer synthetic-baseline-token\r\nConnection: close\r\n\r\n').encode('ascii')
                    # Fragment headers to exercise multiple real reads, not a mock recv.
                    client.sendall(request[:17]);client.sendall(request[17:])
                    written=time.monotonic_ns();read=time.monotonic_ns()
                    first=client.recv(1);first_time=time.monotonic_ns();response=bytearray(first)
                    while True:
                        part=client.recv(4096)
                        if not part: break
                        response.extend(part)
                    end=time.monotonic_ns()
                    return bytes(response),dict(startNs=start,connectedNs=connected,requestWrittenNs=written,
                        readRequestedNs=read,firstByteNs=first_time,headersNs=end,endNs=end,localPort=local)
            health,_=call('/health')
            assert headers(health)[0].startswith('HTTP/1.0 200 ')
            response,client=call('/v1/courses/READER_BASELINE/reader/synthetic-baseline/pages/1/image?version=v1&readerProbe='+probe)
            assert relay.done.wait(3)
        report=relay.report()
        _,fields,body=headers(response)
        assert body==service.images[1]
        client.update(probeId=probe,responseEchoVerified=fields['x-coach-reader-probe']==probe,
            serverConnectionId=int(fields['x-coach-reader-connection']),serverRequestOrdinal=1,
            bytes=len(body),bodySha256=digest(body),route='configured',page=1,
            destinationHost='127.0.0.1',destinationPort=relay.port,requestHost='127.0.0.1',
            clientTcpNoDelayEnabled=False,freshConnection=True,block=1,sequence=1)
    association=correlate({'socketControl':[client]},service.transfers)
    assert association['complete'],association
    return run_id,association,report


def analyze(run_id,association,report):
    return analyze_boundary(association,report,run_id,'127.0.0.1',report['listenerPort'],report['backendPort'])


class HostSocketBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.run_id,cls.association,cls.report=collect()

    def test_real_single_nonce_retains_bytes_tuple_clocks_and_joined_cleanup(self):
        result=analyze(self.run_id,self.association,self.report)
        self.assertTrue(result['complete'],result['errors'])
        self.assertEqual(1,result['matchedNonces'])
        self.assertTrue(result['recording']['observedWithinBudget'])
        for key in ('netsimdSocketReturnObserved','packetHostAligned','guestHostAligned',
                    'captureClockSourceVerified','installedSourceMappingVerified','clockErrorBoundValidated',
                    'networkCauseVerified','uiPerformanceVerified','physicalMemoryBudgetVerified'):
            self.assertIs(False,result[key])
        for port in (self.report['listenerPort'],self.report['backendPort']):
            with socket.socket() as sock:
                self.assertNotEqual(0,sock.connect_ex(('127.0.0.1',port)))

    def test_missing_duplicate_or_truncated_io_is_never_complete(self):
        for mutation in ('missing','duplicate','dropped','short_write','missing_eof'):
            report=copy.deepcopy(self.report)
            row=next(r for r in report['records'] if r['purpose']=='probe')
            if mutation=='missing': row['events'].pop(1)
            if mutation=='duplicate': row['events'].insert(1,copy.deepcopy(row['events'][0]))
            if mutation=='dropped': row['droppedEvents']=1
            if mutation=='short_write': next(e for e in row['events'] if e['operation']=='response_send')['bytes']-=1
            if mutation=='missing_eof': row['events'].pop()
            with self.subTest(mutation=mutation):
                self.assertFalse(analyze(self.run_id,self.association,report)['complete'])

    def test_wrong_nonce_tuple_payload_or_same_host_clock_is_rejected(self):
        for mutation in ('nonce','port','peer','payload','clock'):
            association=copy.deepcopy(self.association);report=copy.deepcopy(self.report)
            if mutation=='nonce': association['samples'][0]['probeId']+='-other'
            if mutation=='port': report['backendPort']+=1
            if mutation=='peer': association['samples'][0]['client']['localPort']+=1
            if mutation=='payload': association['samples'][0]['client']['bodySha256']='0'*64
            if mutation=='clock': report['hostClock']['pid']+=1
            with self.subTest(mutation=mutation):
                self.assertFalse(analyze(self.run_id,association,report)['complete'])

    def test_cross_domain_port_difference_is_diagnosed_without_waiving_strict_failure(self):
        association=copy.deepcopy(self.association)
        client=association['samples'][0]['client']
        client['localPort']=1 if client['localPort']!=1 else 2
        result=analyze(self.run_id,association,self.report)
        self.assertFalse(result['complete'])
        self.assertEqual(['nonce_or_socket_tuple_mismatch'],result['errors'])
        failed=[name for name,value in result['identityChecks'].items() if not value]
        self.assertEqual(['crossDomainPortNumbersEqual'],failed)
        self.assertEqual(client['localPort'],result['portObservationDomains']['clientReported']['localPort'])
        self.assertFalse(result['crossDomainPortMappingVerified'])
        # Preserve every input port; no guessed NAT mapping is written back.
        self.assertNotEqual(client['localPort'],result['portObservationDomains']['hostRelayIncoming']['peerPort'])

    def test_port_equality_does_not_prove_mapping_and_host_mismatch_is_still_fatal(self):
        valid=analyze(self.run_id,self.association,self.report)
        self.assertTrue(valid['identityChecks']['crossDomainPortNumbersEqual'])
        self.assertFalse(valid['crossDomainPortMappingVerified'])
        association=copy.deepcopy(self.association)
        association['samples'][0]['server']['clientPort']+=1
        result=analyze(self.run_id,association,self.report)
        self.assertFalse(result['identityChecks']['hostRelayFixturePeerPort'])
        self.assertFalse(result['complete'])
        self.assertIn('nonce_or_socket_tuple_mismatch',result['errors'])

    def test_large_recording_span_and_error_or_forced_cleanup_fail(self):
        for mutation in ('budget','error','close','unjoined','backwards'):
            report=copy.deepcopy(self.report)
            row=next(r for r in report['records'] if r['purpose']=='probe')
            if mutation=='budget': report['recordingBudgetNs']=1
            if mutation=='error': row['events'][0]['error']='InterruptedError'
            if mutation=='close': report['forcedClose']=True
            if mutation=='unjoined': report['joined']=False
            if mutation=='backwards': row['events'][0]['returnNs']=0
            with self.subTest(mutation=mutation):
                self.assertFalse(analyze(self.run_id,self.association,report)['complete'])

    def test_exact_nonce_gate_rejects_another_request_before_fixture_forwarding(self):
        with fixture(single_boundary=True,host_clock=True) as service:
            with SocketBoundaryRelay('127.0.0.1',service.port,'gate-boundary-1') as relay:
                with socket.socket() as client:
                    client.settimeout(3);client.connect(('127.0.0.1',relay.port))
                    client.sendall((f'GET /private HTTP/1.1\r\nHost: 127.0.0.1:{relay.port}\r\n\r\n').encode())
                    self.assertEqual(b'',client.recv(1))
            self.assertFalse(service.requests)
            self.assertTrue(relay.report()['records'][0]['errors'])

    def test_partial_socket_sends_preserve_all_bytes_and_failure_is_recorded(self):
        relay=SocketBoundaryRelay('127.0.0.1',45678,'partial-boundary-1')
        row=dict(events=[],droppedEvents=0)
        class PartialSocket:
            def __init__(self): self.data=bytearray()
            def send(self,data):
                count=min(3,len(data));self.data.extend(data[:count]);return count
        sink=PartialSocket()
        try:
            relay._send(sink,b'unchanged synthetic bytes',row,'request_send','fixture-facing')
            self.assertEqual(b'unchanged synthetic bytes',bytes(sink.data))
            self.assertGreater(len(row['events']),1)
            self.assertEqual(len(sink.data),sum(e['bytes'] for e in row['events']))
            with patch.object(sink,'send',side_effect=OSError('synthetic failed write')):
                with self.assertRaises(OSError):
                    relay._send(sink,b'error',row,'request_send','fixture-facing')
            self.assertEqual('OSError',row['events'][-1]['error'])
        finally:
            relay.listener.close()

    def test_loopback_evidence_roundtrips_through_offline_validator(self):
        from host_socket_boundary import main
        out=Path('build/reader-single-boundary-check')/self.run_id
        out.mkdir(parents=True,exist_ok=True)
        identity=dict(singleSocketBoundaryRequested=True,runId=self.run_id,
            platform='synthetic-host-check',suite='single-boundary',nativeExecuted=False,
            routeEndpoints=dict(configured='127.0.0.1',port=self.report['listenerPort'],backendPort=self.report['backendPort']))
        for name,data in (('run.json',identity),('transport-association.json',self.association),
                          ('host-socket-events.json',self.report)):
            (out/name).write_text(json.dumps(data,indent=2),encoding='utf-8')
        with patch('sys.argv',['host_socket_boundary.py','--run-dir',str(out)]):
            self.assertEqual(0,main())
        self.assertTrue(json.loads((out/'host-socket-boundary.json').read_text())['complete'])


class HostProbeTest(unittest.TestCase):
    def test_existing_rights_failure_is_retained_without_sudo_or_attachment(self):
        from types import SimpleNamespace
        with patch('host_probe.sys.platform','darwin'),patch('host_probe.shutil.which',return_value='/usr/sbin/dtrace'), \
                patch('host_probe.subprocess.run',return_value=SimpleNamespace(returncode=1,stdout='',stderr='permission denied')) as command:
            report=host_probe.preflight()
        self.assertEqual('activation_rejected',report['status'])
        self.assertEqual('permission denied',report['stderr'])
        self.assertNotIn('sudo',report['command']);self.assertFalse(report['targetAttached'])
        self.assertFalse(report['netsimdFdNonceCollectorAvailable'])
        self.assertEqual(5,command.call_args.kwargs['timeout'])

    def test_activation_alone_does_not_claim_netsimd_nonce_collection(self):
        from types import SimpleNamespace
        with patch('host_probe.sys.platform','darwin'),patch('host_probe.shutil.which',return_value='/usr/sbin/dtrace'), \
                patch('host_probe.subprocess.run',return_value=SimpleNamespace(returncode=0,stdout='reader-boundary-ready\n',stderr='')):
            report=host_probe.preflight()
        self.assertTrue(report['activationVerified'])
        self.assertFalse(report['trafficCollected']);self.assertFalse(report['netsimdFdNonceCollectorAvailable'])


class SingleBoundaryRunnerTest(unittest.TestCase):
    def run_bridge(self,old_instrumentation=False):
        import run_observed
        from fixture_server import Fixture
        from types import SimpleNamespace
        root=run_observed.ROOT/'build/reader-boundary-runner-tests'/uuid.uuid4().hex
        root.mkdir(parents=True)
        app=root/'app.apk';app.write_bytes(b'synthetic isolated APK stand-in, never installed')
        test=root/'test.apk';test.write_bytes(b'synthetic test APK stand-in, never installed')
        out=root/'out';commands=[];device={}
        def make_fixture(state,bind,**options):
            def library(path,*_):
                path.mkdir();(path/'synthetic.pdf').write_bytes(b'synthetic boundary runner')
            def render(args,**_):
                for page in range(1,25):
                    Path(f'{args[-1]}-{page:02d}.png').write_bytes(b'\x89PNG\r\n\x1a\n'+bytes([page]))
            with patch('fixture_server.write_library',library),patch('fixture_server.subprocess.run',render):
                return Fixture(state,bind,**options)
        def output(command,**_):
            if 'getprop' in command: return '1'
            if command[0]=='aapt-unit':
                package=run_observed.PACKAGE+('.test' if command[-1]==str(test) else '')
                return f"package: name='{package}'"
            if command[:2]==['git','rev-parse']: return 'synthetic-source-head'
            if command[:2]==['git','status']: return ' M synthetic'
            raise AssertionError(command)
        def run(command,**kwargs):
            commands.append(command)
            if 'install' in command:
                return SimpleNamespace(returncode=0)
            if 'instrument' in command:
                port=int(command[command.index('readerPort')+1]);run_id=command[command.index('readerRunId')+1]
                def call(target):
                    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=3)
                    start=time.monotonic_ns();connection.connect();connected=time.monotonic_ns()
                    connection.sock.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,0)
                    local=connection.sock.getsockname()[1]
                    try:
                        connection.request('GET',target,headers={'Authorization':'Bearer synthetic-baseline-token'})
                        written=time.monotonic_ns();read=time.monotonic_ns()
                        response=connection.getresponse();head=time.monotonic_ns();body=response.read();end=time.monotonic_ns()
                        return dict(startNs=start,connectedNs=connected,requestWrittenNs=written,readRequestedNs=read,
                            firstByteNs=head,headersNs=head,endNs=end,localPort=local,bytes=len(body),bodySha256=digest(body),
                            responseEchoVerified=response.getheader('X-Coach-Reader-Probe')==run_id+'-boundary-1',
                            serverConnectionId=response.getheader('X-Coach-Reader-Connection'),serverRequestOrdinal=1)
                    finally: connection.close()
                call('/health')
                client=call('/v1/courses/READER_BASELINE/reader/synthetic-baseline/pages/1/image?version=v1&readerProbe='+run_id+'-boundary-1')
                client.update(probeId=run_id+'-boundary-1',route='configured',page=1,destinationHost='127.0.0.1',
                    destinationPort=port,requestHost='127.0.0.1',clientTcpNoDelayEnabled=False,freshConnection=True)
                client['serverConnectionId']=int(client['serverConnectionId'])
                device.update(socketControl=[client],boundaryControl='old' if old_instrumentation else 'single-relay')
                kwargs['stdout'].write('OK (1 test)')
            elif 'exec-out' in command:
                data=device if command[-1]=='cache/reader-transport.json' else dict(process=dict(pid=1234))
                kwargs['stdout'].write(json.dumps(data).encode())
            else:
                raise AssertionError(command)
            return SimpleNamespace(returncode=0)
        args=['run_observed.py','--platform','android','--suite','route','--host','127.0.0.1',
              '--transport-observer','none','--single-socket-boundary','--serial','emulator-5554',
              '--aapt2','aapt-unit','--apk',str(app),'--test-apk',str(test),'--output',str(out)]
        with patch('sys.argv',args),patch('run_observed.sys.platform','darwin'), \
                patch('run_observed.Fixture',side_effect=make_fixture), \
                patch('run_observed.host_probe_preflight',return_value=dict(status='synthetic-mock-no-Mac-permission-claim')), \
                patch('run_observed.subprocess.check_output',side_effect=output),patch('run_observed.subprocess.run',side_effect=run):
            if old_instrumentation:
                with self.assertRaisesRegex(RuntimeError,'rebuild the isolated test APK'):
                    run_observed.main()
            else:
                run_observed.main()
        report=json.loads((out/'host-socket-events.json').read_text())
        self.assertTrue(report['joined']);self.assertFalse(report['forcedClose'])
        self.assertEqual(1,sum(row.get('purpose')=='probe' for row in report['records']))
        self.assertFalse((out/'memory.jsonl').exists());self.assertFalse((out/'route-comparison.json').exists())
        instrument=next(command for command in commands if 'instrument' in command)
        self.assertEqual('single-relay',instrument[instrument.index('readerSocketBoundary')+1])
        if not old_instrumentation:
            self.assertTrue(json.loads((out/'host-socket-boundary.json').read_text())['complete'])
        return out

    def test_single_probe_bridge_forwards_mode_and_joins_real_host_sockets(self):
        self.run_bridge()

    def test_stale_instrumentation_fails_without_losing_host_records_or_cleanup(self):
        self.run_bridge(old_instrumentation=True)

    def test_single_boundary_cannot_disable_default_ui_memory_or_join_queue_sampling(self):
        import run_observed
        for extra in (['--suite','performance'],['--suite','route'],
                      ['--suite','route','--transport-observer','none','--socket-queue-watch']):
            args=['run_observed.py','--platform','android','--single-socket-boundary','--output','unused',*extra]
            with self.subTest(extra=extra),patch('sys.argv',args),patch('run_observed.sys.stderr'),patch('run_observed.Fixture') as service:
                with self.assertRaises(SystemExit) as raised: run_observed.main()
                self.assertEqual(2,raised.exception.code);service.assert_not_called()


if __name__=='__main__':
    unittest.main()
