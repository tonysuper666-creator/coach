from pathlib import Path
import unittest
from unittest.mock import patch
import uuid
from packet_capture import PacketCapture


class CaptureContractTest(unittest.TestCase):
    def test_only_owned_port_filter_is_built_and_child_is_joined(self):
        out=Path('build/reader-packet-tests')/uuid.uuid4().hex;out.mkdir(parents=True)
        calls=[]
        class Child:
            pid=12345;code=None
            def poll(self):return self.code
            def send_signal(self,_):self.code=0;calls.append('stopped')
            def wait(self,timeout=None):calls.append('joined');return self.code
        def launch(command,**options):
            calls.append(command)
            options['stderr'].write(b'listening on lo0\n20 packets captured\n');options['stderr'].flush()
            return Child()
        with patch('packet_capture.subprocess.Popen',launch),patch('packet_capture.subprocess.run') as decode:
            decode.return_value.returncode=0
            capture=PacketCapture(out,'lo0','127.0.0.1',23456);capture.start();capture.stop()
        self.assertEqual('tcp and ((dst host 127.0.0.1 and dst port 23456) or (src host 127.0.0.1 and src port 23456))',calls[0][-1])
        self.assertNotIn('sudo',calls[0]);self.assertEqual(['stopped','joined'],calls[1:])
        self.assertTrue(capture.report['observed'])

    def test_permission_or_missing_command_cannot_claim_capture(self):
        out=Path('build/reader-packet-tests')/uuid.uuid4().hex;out.mkdir(parents=True)
        with patch('packet_capture.subprocess.Popen',side_effect=PermissionError()):
            capture=PacketCapture(out,'lo0','127.0.0.1',23456);capture.start();capture.stop()
        self.assertFalse(capture.report['observed'])
        self.assertEqual('PermissionError',capture.report['error'])

    def test_child_bpf_denial_is_reaped_and_remains_unobserved(self):
        out=Path('build/reader-packet-tests')/uuid.uuid4().hex;out.mkdir(parents=True)
        class Denied:
            pid=12345
            def poll(self):return 1
            def wait(self,timeout=None):return 1
        def launch(command,**options):
            options['stderr'].write(b'bpf: permission denied\n');options['stderr'].flush()
            return Denied()
        with patch('packet_capture.subprocess.Popen',launch):
            capture=PacketCapture(out,'lo0','127.0.0.1',23456);capture.start();capture.stop()
        self.assertEqual(1,capture.report['exit'])
        self.assertFalse(capture.report['observed'])

    def test_invalid_filter_inputs_are_rejected_before_process_creation(self):
        for interface,port in (('lo0 -w elsewhere',23456),('lo0',8790),('lo0',0)):
            with self.assertRaises(ValueError):PacketCapture(Path('.'),interface,'127.0.0.1',port)


if __name__=='__main__':unittest.main()
