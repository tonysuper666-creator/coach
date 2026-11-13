import unittest
from correlate_transport import correlate


class CorrelationTest(unittest.TestCase):
    def records(self):
        client=dict(probeId='nonce-socket-1',responseEchoVerified=True,bytes=9,serverConnectionId=3,serverRequestOrdinal=1,
            startNs=1000,connectedNs=1010,requestWrittenNs=1020,readRequestedNs=1021,firstByteNs=1520,headersNs=1530,endNs=1540)
        server=dict(probeId='nonce-socket-1',bytes=9,status=200,connectionId=3,requestOnConnection=1,
            socketAcceptedNs=999_999_980,handlerStartedNs=999_999_990,
            acceptInitializedNs=1_000_000_000,requestWaitStartedNs=1_000_000_001,requestLineReadNs=1_000_000_011,
            requestHeadersParsedNs=1_000_000_021,handlerNs=1_000_000_022,responseHeaderWriteStartedNs=1_000_000_023,
            headersQueuedNs=1_000_000_031,bodyQueuedNs=1_000_000_041)
        return client,server

    def test_unique_echo_maps_request_and_response_without_clock_subtraction(self):
        client,server=self.records()
        report=correlate({'socketControl':[client]},[server])
        self.assertTrue(report['complete'])
        self.assertEqual(500/1e6,report['samples'][0]['intervals']['clientWriteToFirstReadMs'])
        self.assertEqual(10/1e6,report['samples'][0]['intervals']['serverHeadersToBodyQueuedMs'])
        self.assertFalse(report['networkCauseVerified'])

    def test_duplicates_missing_echo_wrong_connection_and_partial_writes_are_rejected(self):
        client,server=self.records()
        for rows,changed in (([server,server],client),([server],dict(client,responseEchoVerified=False)),
            ([server],dict(client,serverConnectionId=99)),([dict(server,bodyQueuedNs=None)],client)):
            with self.subTest(rows=len(rows),client=changed):
                self.assertFalse(correlate({'socketControl':[changed]},rows)['complete'])

    def test_order_or_payload_mismatch_is_not_correlated(self):
        client,server=self.records()
        for changed in (dict(server,requestHeadersParsedNs=0),dict(server,bytes=10),dict(server,status=409)):
            self.assertFalse(correlate({'socketControl':[client]},[changed])['complete'])


if __name__=='__main__': unittest.main()
