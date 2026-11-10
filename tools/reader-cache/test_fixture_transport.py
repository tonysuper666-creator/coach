"""Verify HTTP framing/identity controls without Poppler, a corpus or an emulator."""
from contextlib import contextmanager
import http.client
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

from fixture_server import Fixture, PREFIX, TOKEN


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


if __name__=='__main__': unittest.main()
