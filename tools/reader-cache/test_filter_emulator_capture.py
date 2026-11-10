import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

from filter_emulator_capture import filter_capture,ROOT
import test_route_control


class EmulatorCaptureTest(unittest.TestCase):
    def prepare(self):
        directory=ROOT/'build/reader-packet-filter-tests'/uuid.uuid4().hex
        directory.mkdir(parents=True)
        clients,servers=test_route_control.RouteControlTest().records()
        for index,(client,server) in enumerate(zip(clients,servers)):
            client['localPort']=40000+index
            client['probeId']=server['probeId']='unit-'+client['probeId']
        identity=dict(platform='android',suite='route',runId='unit',androidObserver=dict(mode='none'),
            routeEndpoints=dict(configured='127.0.0.2',hostLoopbackAlias='10.0.2.2',port=45678))
        association=dict(complete=True,samples=[dict(probeId=c['probeId'],client=c,server=s) for c,s in zip(clients,servers)])
        for name,data in (('run.json',identity),('transport-association.json',association),('route-comparison.json',dict(complete=True))):
            (directory/name).write_text(json.dumps(data))
        source=directory/'local-only.pcap';source.write_bytes(b'fake source, never a native capture')
        return directory,source,identity,association

    def fake(self,command,**kwargs):
        self.commands.append(command)
        self.assertIn('-r',command);self.assertNotIn('-i',command)
        self.assertEqual(30,kwargs['timeout'])
        if '-w' in command: Path(command[command.index('-w')+1]).write_bytes(b'fake filtered data for mock collector only')
        else: kwargs['stdout'].write(b'mock packet decode only')
        return SimpleNamespace(returncode=0)

    def test_offline_filter_limits_endpoints_and_does_not_claim_packet_correlation(self):
        directory,source,_,_=self.prepare();self.commands=[]
        with patch('filter_emulator_capture.subprocess.run',side_effect=self.fake): report=filter_capture(directory,source,'offline-tcpdump')
        self.assertTrue(report['offlineDecodeComplete']);self.assertEqual(16,len(report['requests']))
        self.assertIn('dst port 45678',self.commands[0][-1]);self.assertIn('src host 10.0.2.2',self.commands[0][-1])
        self.assertFalse(report['packetRequestAssociationVerified']);self.assertFalse(report['packetStreamCompletenessVerified'])
        self.assertFalse(report['kernelReceiveObserved']);self.assertTrue(source.exists())

    def test_failed_filter_or_decoder_keeps_failure_and_original(self):
        for fail_index in (0,1):
            directory,source,_,_=self.prepare();self.commands=[]
            def command(args,**kwargs):
                result=self.fake(args,**kwargs)
                if len(self.commands)-1==fail_index: result.returncode=1
                return result
            with patch('filter_emulator_capture.subprocess.run',side_effect=command): report=filter_capture(directory,source)
            self.assertFalse(report['offlineDecodeComplete']);self.assertTrue(report['errors']);self.assertTrue(source.exists())

    def test_declared_netsim_source_is_separate_from_origin_and_packet_verification(self):
        directory,source,_,_=self.prepare();self.commands=[]
        with patch('filter_emulator_capture.subprocess.run',side_effect=self.fake):
            report=filter_capture(directory,source,capture_source='netsim-wifi')
        self.assertTrue(report['offlineDecodeComplete'])
        self.assertEqual('netsim-wifi',report['declaredCaptureSource'])
        self.assertFalse(report['captureOriginVerified'])
        self.assertFalse(report['packetRequestAssociationVerified'])
        self.assertFalse(report['kernelReceiveObserved'])
        with patch('filter_emulator_capture.subprocess.run') as decoder:
            with self.assertRaisesRegex(ValueError,'Known capture source'):
                filter_capture(directory,source,capture_source='assume-any-interface')
            decoder.assert_not_called()

    def test_incomplete_association_or_foreign_port_rejected_before_decode(self):
        for kind in ('association','port','observer','token'):
            directory,source,identity,association=self.prepare()
            if kind=='association': association['complete']=False
            if kind=='port': identity['routeEndpoints']['port']=8790
            if kind=='observer': identity['androidObserver']['mode']='both'
            if kind=='token': association['samples'][0]['probeId']='previous-run-route-1'
            (directory/'run.json').write_text(json.dumps(identity))
            (directory/'transport-association.json').write_text(json.dumps(association))
            with patch('filter_emulator_capture.subprocess.run') as decoder:
                with self.assertRaises(ValueError): filter_capture(directory,source)
                decoder.assert_not_called()

    def test_changing_input_and_existing_outputs_are_not_overwritten_as_success(self):
        directory,source,_,_=self.prepare();self.commands=[]
        def changed(command,**kwargs):
            result=self.fake(command,**kwargs);source.write_bytes(b'capture still active');return result
        with patch('filter_emulator_capture.subprocess.run',side_effect=changed): report=filter_capture(directory,source)
        self.assertFalse(report['offlineDecodeComplete']);self.assertIn('source_changed_during_offline_filter',report['errors'])
        with patch('filter_emulator_capture.subprocess.run') as decoder:
            with self.assertRaisesRegex(ValueError,'Fresh'): filter_capture(directory,source)
            decoder.assert_not_called()


if __name__=='__main__': unittest.main()
