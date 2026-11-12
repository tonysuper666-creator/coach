from copy import deepcopy
import unittest

from correlate_transport import correlate
from route_control import summarize_routes
import test_correlate_transport


class RouteControlTest(unittest.TestCase):
    def records(self):
        clients=[];servers=[]
        for index,route in enumerate(['configured','host-loopback','host-loopback','configured',
                                     'host-loopback','configured','configured','host-loopback']*2):
            client,server=test_correlate_transport.CorrelationTest().records()
            token=f'route-{index+1}'
            page=index//4+1
            client.update(probeId=token,page=page,sequence=index+1,block=page,route=route,
                serverConnectionId=index+1,
                bodySha256=str(page)*64,destinationHost='127.0.0.2' if route=='configured' else '10.0.2.2',
                destinationPort=45678,requestHost='127.0.0.2',freshConnection=True,clientTcpNoDelayEnabled=False)
            server.update(probeId=token,page=page,bodySha256=client['bodySha256'],
                connectionId=index+1,
                serverBindAddress='127.0.0.2' if route=='configured' else '127.0.0.1',
                serverPort=45678,protocol='HTTP/1.0',tcpNoDelayEnabled=False)
            clients.append(client);servers.append(server)
        return clients,servers

    def report(self,clients,servers):
        return summarize_routes(correlate({'socketControl':clients},servers),'127.0.0.2',45678)

    def test_counterbalanced_routes_require_matched_bytes_and_separate_clocks(self):
        clients,servers=self.records()
        report=self.report(clients,servers)
        self.assertTrue(report['complete'],report['errors'])
        self.assertEqual([8,8],[r['samples'] for r in report['routes'].values()])
        self.assertEqual(500/1e6,report['routes']['configured']['milliseconds']['clientWriteToFirstReadMs']['p50'])
        self.assertFalse(report['networkCauseVerified'])
        self.assertFalse(report['kernelReceiveObserved'])

    def test_wrong_listener_reordered_missing_and_changed_payload_are_rejected(self):
        for field,value in (('serverBindAddress','0.0.0.0'),('bodySha256','f'*64),('protocol','HTTP/1.1')):
            clients,servers=self.records();servers[1][field]=value
            self.assertFalse(self.report(clients,servers)['complete'])
        clients,servers=self.records()
        for changed in (clients[:-1],list(reversed(clients))):
            self.assertFalse(self.report(changed,servers)['complete'])
        changed=deepcopy(clients);changed[1]['requestHost']='10.0.2.2'
        self.assertFalse(self.report(changed,servers)['complete'])

    def test_consistently_wrong_page_is_not_a_matched_route_control(self):
        clients,servers=self.records()
        # Correct echo and lengths alone cannot prove that both routes read the same page.
        clients[1]['bodySha256']=servers[1]['bodySha256']='f'*64
        self.assertFalse(self.report(clients,servers)['complete'])


if __name__=='__main__': unittest.main()
