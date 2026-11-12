from copy import deepcopy
import unittest

from correlate_transport import correlate
from queue_readiness import analyze_queue_watch
import test_correlate_transport


def add_watch(client):
    client['readTid']=1
    client['queueWatch']=dict(mode='available',startedNs=1011,readyNs=1012,joinedNs=1550,observerTid=2,
        inputClass='invented-unit-stream',joined=True,forcedSocketClose=False,error=None,limitReached=False,
        queries=[dict(invokedNs=1100,returnedNs=1110,availableBytes=5)])


class QueueReadinessTest(unittest.TestCase):
    def records(self):
        client,server=test_correlate_transport.CorrelationTest().records();add_watch(client)
        return client,server

    def report(self,client,server):
        return analyze_queue_watch(correlate({'socketControl':[client]},[server]))

    def test_positive_query_return_during_read_is_only_same_process_evidence(self):
        client,server=self.records();report=self.report(client,server)
        self.assertTrue(report['complete'])
        self.assertEqual('positive_during_read',report['samples'][0]['classification'])
        self.assertEqual(410/1e6,report['samples'][0]['positiveObservationToReadMarkerMs'])
        self.assertFalse(report['kernelReceiveObserved']);self.assertFalse(report['applicationWakeCauseVerified'])

    def test_query_invocation_is_not_readability_time_and_zero_is_inconclusive(self):
        client,server=self.records()
        for queries in ([],[dict(invokedNs=1100,returnedNs=1110,availableBytes=0)],
                        [dict(invokedNs=1100,returnedNs=1530,availableBytes=5)]):
            client['queueWatch']['queries']=queries
            report=self.report(client,server)
            self.assertTrue(report['complete'])
            self.assertEqual('no_early_positive_observation',report['samples'][0]['classification'])
            self.assertIsNone(report['samples'][0]['positiveObservationToReadMarkerMs'])

    def test_positive_before_read_invocation_is_distinguished(self):
        client,server=self.records();client['readRequestedNs']=1200
        self.assertEqual('positive_before_read_call',self.report(client,server)['samples'][0]['classification'])

    def test_missing_failed_unjoined_or_wrong_thread_observer_is_rejected(self):
        client,server=self.records()
        for change in ({'joined':False},{'error':'IOException'},{'forcedSocketClose':True},{'observerTid':1}):
            bad=deepcopy(client);bad['queueWatch'].update(change)
            self.assertFalse(self.report(bad,server)['complete'])
        client.pop('queueWatch');self.assertFalse(self.report(client,server)['complete'])

    def test_reversed_or_prewrite_query_timestamps_are_rejected(self):
        client,server=self.records()
        for query in (dict(invokedNs=1110,returnedNs=1100,availableBytes=1),
                      dict(invokedNs=1000,returnedNs=1010,availableBytes=1),None):
            client['queueWatch']['queries']=[query]
            self.assertFalse(self.report(client,server)['complete'])


if __name__=='__main__': unittest.main()
