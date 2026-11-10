"""Offline extraction of the owned route fixture from a stopped emulator's capture."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import subprocess

from fixture_server import FORBIDDEN

ROOT=Path(__file__).resolve().parents[2]


def filter_capture(directory,capture,tcpdump='/usr/sbin/tcpdump'):
    identity=json.loads((directory/'run.json').read_text())
    association=json.loads((directory/'transport-association.json').read_text())
    routes=json.loads((directory/'route-comparison.json').read_text())
    if identity.get('platform')!='android' or identity.get('suite')!='route' or \
            identity.get('androidObserver',{}).get('mode')!='none' or \
            not association.get('complete') or not routes.get('complete') or len(association.get('samples',[]))!=16:
        raise ValueError('A complete isolated Android route/observer none run is required')
    endpoint=identity['routeEndpoints'];port=endpoint['port']
    configured=str(ipaddress.IPv4Address(endpoint['configured']))
    alias=str(ipaddress.IPv4Address(endpoint['hostLoopbackAlias']))
    if type(port) is not int or not 1024<=port<=65535 or port in FORBIDDEN or \
            alias!='10.0.2.2' or configured in ('0.0.0.0','127.0.0.1',alias):
        raise ValueError('Exact isolated fixture endpoints required')
    requests=[]
    for row in association['samples']:
        client,server=row['client'],row['server']
        if client.get('destinationHost') not in (configured,alias) or client.get('destinationPort')!=port or \
                server.get('serverPort')!=port or not row['probeId'].startswith(identity['runId']+'-route-'):
            raise ValueError('Request does not match this run and fixture port')
        requests.append(dict(probeId=row['probeId'],route=client['route'],guestLocalPort=client['localPort'],
            destinationHost=client['destinationHost'],destinationPort=port,serverConnectionId=client['serverConnectionId']))
    outputs={name:directory/name for name in ('emulator-fixture.pcap','emulator-fixture.txt','emulator-filter.log','emulator-packet-filter.json')}
    if any(path.exists() for path in outputs.values()): raise ValueError('Fresh packet-filter outputs required')
    # The emulator captures its whole virtual Ethernet. Only these exact fixture
    # endpoints may enter shareable output; the unfiltered original stays local.
    addresses=f'(host {configured} or host {alias})'
    packet_filter=f'tcp and (((dst host {configured} or dst host {alias}) and dst port {port}) or ((src host {configured} or src host {alias}) and src port {port}))'
    original=hashlib.sha256(capture.read_bytes()).hexdigest()
    report=dict(captureBoundary='Emulator virtual Ethernet only; not guest kernel receipt',
        sourceSha256=original,runId=identity['runId'],fixturePort=port,filter=packet_filter,
        requestedEndpoints=addresses,requests=requests,offlineDecodeComplete=False,errors=[],
        packetRequestAssociationVerified=False,packetStreamCompletenessVerified=False,
        kernelReceiveObserved=False,networkCauseVerified=False,performanceTargetVerified=False)
    try:
        common=dict(stdin=subprocess.DEVNULL,timeout=30,env=dict(os.environ,LC_ALL='C'))
        with outputs['emulator-filter.log'].open('wb') as log:
            filtered=subprocess.run([tcpdump,'-nn','-r',str(capture),'-s','0','-w',str(outputs['emulator-fixture.pcap']),packet_filter],
                stdout=log,stderr=log,**common)
            report['filterExit']=filtered.returncode
            if filtered.returncode!=0: raise RuntimeError('offline_filter_failed')
            if not outputs['emulator-fixture.pcap'].exists() or outputs['emulator-fixture.pcap'].stat().st_size<=24:
                raise RuntimeError('no_filtered_packet_records')
            with outputs['emulator-fixture.txt'].open('wb') as text:
                decoded=subprocess.run([tcpdump,'-nn','-tt','-S','-vv','-A','-r',str(outputs['emulator-fixture.pcap']),packet_filter],
                    stdout=text,stderr=log,**common)
            report['decodeExit']=decoded.returncode
            if decoded.returncode!=0: raise RuntimeError('offline_decode_failed')
        if hashlib.sha256(capture.read_bytes()).hexdigest()!=original:
            raise RuntimeError('source_changed_during_offline_filter')
        report['filteredSha256']=hashlib.sha256(outputs['emulator-fixture.pcap'].read_bytes()).hexdigest()
        report['offlineDecodeComplete']=True
    except (OSError,subprocess.TimeoutExpired,RuntimeError) as error:
        report['errors'].append(str(error) if isinstance(error,RuntimeError) else type(error).__name__)
    outputs['emulator-packet-filter.json'].write_text(json.dumps(report,indent=2))
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--capture',type=Path,required=True)
    parser.add_argument('--emulator-stopped',action='store_true',required=True,
        help='Confirm the owned emulator exited and the input file is no longer being written')
    parser.add_argument('--tcpdump',default='/usr/sbin/tcpdump')
    args=parser.parse_args()
    directory=args.run.resolve();capture=args.capture.resolve()
    directory.relative_to(ROOT);capture.relative_to(ROOT)
    report=filter_capture(directory,capture,args.tcpdump)
    print(json.dumps(dict(offlineDecodeComplete=report['offlineDecodeComplete'],errors=report['errors'],
        packetRequestAssociationVerified=False)))
    return 0 if report['offlineDecodeComplete'] else 1


if __name__=='__main__': raise SystemExit(main())
