"""Foreground-owned capture of one synthetic fixture TCP port. Never uses sudo."""
import os
import ipaddress
from pathlib import Path
import re
import signal
import subprocess
import time
from fixture_server import FORBIDDEN


def clock_bridge():
    before=time.monotonic_ns()
    wall=time.time_ns()
    return dict(hostMonotonicBeforeNs=before,hostEpochNs=wall,hostMonotonicAfterNs=time.monotonic_ns())


class PacketCapture:
    def __init__(self,out,interface,host,port):
        if not 1024<=port<=65535 or port in FORBIDDEN: raise ValueError('fixture port required')
        if interface is not None and re.fullmatch(r'[A-Za-z0-9_.:,\-]{1,64}',interface) is None:
            raise ValueError('one capture interface required')
        self.host=str(ipaddress.IPv4Address(host))
        self.out=Path(out);self.interface=interface;self.port=port
        self.filter=f'tcp and ((dst host {self.host} and dst port {port}) or (src host {self.host} and src port {port}))'
        self.process=None;self.log=None
        self.report=dict(requested=interface is not None,ready=False,observed=False,fixturePort=port,
                         filter=self.filter,fixtureHost=self.host,interface=interface)

    def start(self):
        if self.interface is None:return
        self.report['clockBefore']=clock_bridge()
        self.log=(self.out/'wire-capture.log').open('wb')
        command=['/usr/sbin/tcpdump','-i',self.interface,'-nn','-s','0','-U','-w',str(self.out/'wire.pcap'),
                 self.filter]
        try:
            self.process=subprocess.Popen(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,
                stderr=self.log,env=dict(os.environ,LC_ALL='C'))
            self.report['pid']=self.process.pid
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                text=(self.out/'wire-capture.log').read_text(errors='replace')
                if 'listening on' in text:
                    self.report['ready']=True;return
                if self.process.poll() is not None:break
                time.sleep(.05)
            self.report['error']='capture_not_ready; inspect wire-capture.log'
        except OSError as error:self.report['error']=type(error).__name__

    def stop(self):
        if self.process is not None:
            try:
                if self.process.poll() is None:self.process.send_signal(signal.SIGINT)
                try:self.report['exit']=self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill();self.report['exit']=self.process.wait()
                    self.report['error']='capture_stop_timeout'
            finally:
                if self.log is not None:self.log.close()
        elif self.log is not None:self.log.close()
        if self.interface is None:return
        self.report['clockAfter']=clock_bridge()
        text=(self.out/'wire-capture.log').read_text(errors='replace')
        count=re.search(r'(\d+) packets captured',text)
        drops=re.search(r'(\d+) packets dropped by kernel',text)
        self.report['packetsCaptured']=int(count.group(1)) if count else None
        self.report['kernelDrops']=int(drops.group(1)) if drops else None
        self.report['packetStreamCompletenessVerified']=False
        self.report['observed']=self.report['ready'] and bool(count and int(count.group(1))>0) and self.report.get('exit') in (0,-signal.SIGINT)
        if self.report['observed']:
            # Offline decode is also bounded and contains only the owned fixture port.
            with (self.out/'wire-text.log').open('wb') as decoded:
                try:
                    result=subprocess.run(['/usr/sbin/tcpdump','-nn','-tt','-S','-A','-r',str(self.out/'wire.pcap'),
                        self.filter],stdout=decoded,stderr=subprocess.PIPE,timeout=10)
                    self.report['decodeExit']=result.returncode
                except (OSError,subprocess.TimeoutExpired) as error:self.report['decodeError']=type(error).__name__
