import copy
import unittest
from backing_lifetimes import analyze


class BackingLifetimesTest(unittest.TestCase):
    def fixture(self):
        rows=[]
        for image in range(1,6):
            retired=image<=2
            rows.append(dict(image=image,storage='decoded',width=1200,height=1600,bytes=7680000,
                sourceSession=1,sourcePage=image,prefetch=image!=1,published=not retired and image!=5,retired=retired,
                releaseDisposition='release_deferred_renderer' if retired else None,compositionOwners=int(image==4),
                lease=None if image==5 else dict(cacheOwned=not retired,composedUses=int(image==4),valueCleared=retired,everDrawn=True)))
        peak=dict(ns=10,event='allocation_decoded',decoded=5,scratch=0,bytes=38400000,decodeInFlight=1,compositionOwners=1,backings=rows)
        return dict(complete=True,allocations=copy.deepcopy(rows),peakWeakLiveDecodedBackings=5,peakWeakReachableBackingBytes=38400000,
            peakWeakLiveDecodedSnapshot=peak,peakWeakReachableBytesSnapshot=copy.deepcopy(peak),
            events=[dict(ns=10,kind='allocation_decoded',image=5,weakReachableBackingBytes=38400000,weakLiveDecodedBackings=5)])

    def test_two_retired_backings_remain_in_peak_bytes_and_never_verify_physical_budget(self):
        report=analyze(self.fixture())
        self.assertTrue(report['complete'],report['errors'])
        self.assertFalse(report['physicalMemoryBudgetVerified'])
        self.assertFalse(report['gpuReleaseVerified'])
        for peak in report['peaks']:
            self.assertEqual(38400000,peak['bytes'])
            self.assertEqual(23040000,peak['logicalDecodedBytes'])
            self.assertEqual(2,peak['retiredWeakBackings'])

    def test_missing_peak_identity_is_rejected_instead_of_using_final_gc_state(self):
        trace=self.fixture()
        del trace['peakWeakLiveDecodedSnapshot']
        report=analyze(trace)
        self.assertFalse(report['complete'])
        self.assertIn('peakWeakLiveDecodedSnapshot:missing_peak_identity_snapshot',report['errors'])

    def test_duplicate_identity_and_disguised_peak_are_rejected(self):
        trace=self.fixture()
        trace['peakWeakLiveDecodedSnapshot']['backings'][1]=copy.deepcopy(trace['peakWeakLiveDecodedSnapshot']['backings'][0])
        trace['peakWeakLiveDecodedBackings']=3
        report=analyze(trace)
        self.assertFalse(report['complete'])
        self.assertIn('peakWeakLiveDecodedSnapshot:duplicate_backing_identity',report['errors'])
        self.assertIn('peakWeakLiveDecodedSnapshot:high_water_mismatch',report['errors'])

    def test_cleared_lease_cannot_keep_a_composed_reference(self):
        trace=self.fixture()
        trace['peakWeakLiveDecodedSnapshot']['backings'][0]['lease']['composedUses']=1
        report=analyze(trace)
        self.assertFalse(report['complete'])
        self.assertIn('peakWeakLiveDecodedSnapshot:inconsistent_lease_ownership',report['errors'])

    def test_eviction_before_lease_retire_is_reported_as_pending_not_misread_as_cleared(self):
        trace=self.fixture()
        for field in ('peakWeakLiveDecodedSnapshot','peakWeakReachableBytesSnapshot'):
            row=trace[field]['backings'][0]
            row['lease'].update(cacheOwned=True,valueCleared=False)
            row['releaseDisposition']=None
        report=analyze(trace)
        self.assertTrue(report['complete'],report['errors'])
        for peak in report['peaks']:
            self.assertEqual(1,peak['pendingLeaseRetirement'])
            self.assertEqual(4,peak['logicalDecodedIncludingHandoff'])
            self.assertEqual(38400000,peak['bytes'])


if __name__ == '__main__':unittest.main()
