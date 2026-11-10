"""Verify HTTP framing/identity controls without Poppler, a corpus or an emulator."""
from contextlib import contextmanager
import http.client
import json
import hashlib
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

from fixture_server import Fixture, PREFIX, TOKEN
from correlate_transport import correlate


@contextmanager
def fixture(**kwargs):
    state=Path('build/reader-fixture-tests')/uuid.uuid4().hex
    def library(path,*_):
        path.mkdir();(path/'synthetic.pdf').write_bytes(b'invented transport unit fixture')
    def render(args,**_):
        for page in range(1,25):
            Path(f'{args[-1]}-{page:02d}.png').write_bytes(b'\x89PNG\r\n\x1a\n'+bytes([page]))
    with patch('fixture_server.write_library',library),patch('fixture_server.subprocess.run',render):
        with Fixture(state,**kwargs) as service:
            yield service


class ReaderFixtureTransportTest(unittest.TestCase):
    def test_route_listeners_share_payload_identity_and_both_join_on_exit(self):
        with fixture(loopback_control=True,loopback_ephemeral=True) as service:
            ports=[server.server_port for server in service.servers]
            self.assertEqual(2,len(set(ports)))
            order=[ports[0],ports[1],ports[1],ports[0]]
            for index,port in enumerate(order):
                connection=http.client.HTTPConnection('127.0.0.1',port,timeout=3)
                try:
                    token=f'route-unit-{index}'
                    connection.request('GET',PREFIX+'/synthetic-baseline/pages/1/image?version=v1&readerProbe='+token,
                        headers={'Authorization':'Bearer '+TOKEN,'Host':f'127.0.0.1:{service.port}'})
                    response=connection.getresponse()
                    self.assertEqual(200,response.status)
                    self.assertEqual(token,response.getheader('X-Coach-Reader-Probe'))
                    self.assertEqual(b'\x89PNG\r\n\x1a\n\x01',response.read())
                finally: connection.close()
        self.assertEqual(['127.0.0.1']*4,[r['serverBindAddress'] for r in service.requests])
        self.assertEqual(order,[r['serverPort'] for r in service.requests])
        self.assertEqual(4,len({r['connectionId'] for r in service.requests}))
        for row in service.requests:
            self.assertEqual(hashlib.sha256(service.images[1]).hexdigest(),row['bodySha256'])
            self.assertLessEqual(row['acceptCalledNs'],row['socketAcceptedNs'])
            self.assertIsNotNone(row['bodyQueuedNs'])
        self.assertTrue(all(not thread.is_alive() for thread in service.threads))
        self.assertTrue(all(server.fileno()==-1 for server in service.servers))

    def test_explicit_modes_preserve_payload_and_record_server_write_boundaries(self):
        for mode in ('legacy','nodelay','keepalive'):
            with self.subTest(mode=mode),fixture(transport=mode) as service:
                connection=http.client.HTTPConnection('127.0.0.1',service.port,timeout=3)
                try:
                    connection.request('GET',PREFIX+'/synthetic-baseline/pages/1/image?version=v1',headers={'Authorization':'Bearer '+TOKEN})
                    response=connection.getresponse()
                    self.assertEqual(200,response.status)
                    self.assertEqual(b'\x89PNG\r\n\x1a\n\x01',response.read())
                    self.assertEqual(11 if mode=='keepalive' else 10,response.version)
                    connection.request('GET',PREFIX+'/synthetic-baseline/pages/2/image?version=v1',headers={'Authorization':'Bearer '+TOKEN})
                    response=connection.getresponse()
                    self.assertEqual(200,response.status)
                    self.assertEqual(b'\x89PNG\r\n\x1a\n\x02',response.read())
                finally: connection.close()
                # Server completion is joined by fixture exit before inspecting final write fields.
            row=service.requests[0]
            self.assertIs(mode!='legacy',row['tcpNoDelayEnabled'])
            self.assertLessEqual(row['startedNs'],row['headersQueuedNs'])
            self.assertLessEqual(row['headersQueuedNs'],row['bodyQueuedNs'])
            second=service.requests[1]
            self.assertEqual(mode=='keepalive',row['connectionId']==second['connectionId'])
            self.assertEqual(2 if mode=='keepalive' else 1,second['requestOnConnection'])

    def test_lifecycle_version_rejects_old_key_and_returns_different_pixels(self):
        with fixture(lifecycle=True) as service:
            def call(method,path,body=None):
                connection=http.client.HTTPConnection('127.0.0.1',service.port,timeout=3)
                try:
                    connection.request(method,path,body=body,headers={'Authorization':'Bearer '+TOKEN})
                    response=connection.getresponse();return response.status,response.read()
                finally: connection.close()
            self.assertEqual(200,call('POST','/test/version',b'v2')[0])
            self.assertEqual(409,call('GET',PREFIX+'/synthetic-baseline/pages/1/image?version=v1')[0])
            self.assertEqual(b'\x89PNG\r\n\x1a\n\x02',call('GET',PREFIX+'/synthetic-baseline/pages/1/image?version=v2')[1])
            self.assertEqual(b'\x89PNG\r\n\x1a\n\x03',call('GET',PREFIX+'/synthetic-other/pages/1/image?version=v1')[1])
            self.assertEqual('v2',json.loads(call('GET',PREFIX+'/synthetic-baseline/state')[1])['document']['version'])

    def test_repeated_page_requests_have_distinct_echoes_and_ordered_boundaries(self):
        clients=[]
        with fixture(transport='keepalive') as service:
            connection=http.client.HTTPConnection('127.0.0.1',service.port,timeout=3)
            try:
                for token in ('run-socket-1','run-socket-2'):
                    connection.request('GET',PREFIX+'/synthetic-baseline/pages/1/image?version=v1&readerProbe='+token,
                        headers={'Authorization':'Bearer '+TOKEN})
                    response=connection.getresponse()
                    self.assertEqual(200,response.status)
                    self.assertEqual(token,response.getheader('X-Coach-Reader-Probe'))
                    size=len(response.read());self.assertEqual(9,size)
                    clients.append(dict(probeId=token,responseEchoVerified=True,bytes=size,
                        serverConnectionId=int(response.getheader('X-Coach-Reader-Connection')),
                        serverRequestOrdinal=int(response.getheader('X-Coach-Reader-Ordinal'))))
            finally: connection.close()
        self.assertEqual(['run-socket-1','run-socket-2'],[r['probeId'] for r in service.requests])
        for row in service.requests:
            points=[row[k] for k in ('socketAcceptedNs','handlerStartedNs','acceptInitializedNs','requestWaitStartedNs','requestLineReadNs',
                'requestHeadersParsedNs','handlerNs','responseHeaderWriteStartedNs','headersQueuedNs','bodyQueuedNs')]
            self.assertEqual(sorted(points),points)
        association=correlate({'urlConnectionControl':clients},service.transfers)
        self.assertTrue(association['complete'])
        self.assertEqual(2,len(association['samples']))


if __name__=='__main__': unittest.main()
