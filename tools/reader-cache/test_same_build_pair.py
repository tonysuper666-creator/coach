import unittest

from first_present import analyze
from test_first_present import fixture


def controlled_arm(mode,run_id,raw_hash,csv_hash):
    run,trace,rows=fixture()
    run.update(runId=run_id,firstPresentCacheMode=mode,apkSha256='1'*64,testApkSha256='2'*64,
               rawTraceSha256=raw_hash*64,framesCsvSha256=csv_hash*64)
    trace.update(firstPresentCacheMode=mode,mode='three_page_window' if mode=='on' else 'visible_pages_only')
    if mode=='off':trace['events'][1].update(kind='lookup_miss',image=0)
    return run,trace,rows


class SameBuildPairTest(unittest.TestCase):
    def pair(self,hit=None,off=None):
        hit=hit or controlled_arm('on','hit','3','4')
        off=off or controlled_arm('off','off','5','6')
        return analyze(*hit,baseline=analyze(*off))

    def assert_closed_flags(self,report):
        for key in ('firstPresentLatencyVerified','matchedBaselineObserved','baselineAcceptanceEvaluated',
                    'physicalMemoryBudgetVerified','gpuReleaseVerified'):
            self.assertFalse(report[key])

    def test_same_source_and_signed_build_accept_only_distinct_opposite_controls(self):
        report=self.pair()
        self.assertTrue(report['matchedTupleContract'],report['pairErrors'])
        self.assertEqual([],report['pairErrors'])
        self.assertEqual(90,report['samples'][0]['handlerToFirstPresentMs'])
        self.assert_closed_flags(report)
        reverse=self.pair(controlled_arm('off','off','5','6'),controlled_arm('on','hit','3','4'))
        self.assertTrue(reverse['matchedTupleContract'])
        self.assert_closed_flags(reverse)

    def test_duplicate_run_raw_or_csv_is_never_a_new_arm(self):
        for field,value in (('runId','hit'),('rawTraceSha256','3'*64),('framesCsvSha256','4'*64)):
            with self.subTest(field=field):
                off=controlled_arm('off','off','5','6');off[0][field]=value
                report=self.pair(off=off)
                self.assertFalse(report['matchedTupleContract'])
                self.assertIn('same_arm_reused',report['pairErrors'])
                self.assert_closed_flags(report)

    def test_same_lookup_mode_missing_control_or_wait_does_not_unlock_same_sha(self):
        report=self.pair(off=controlled_arm('on','other-hit','5','6'))
        self.assertIn('same_arm_reused',report['pairErrors'])
        for mutation in ('missing_control','wait','wrong_lookup','wrong_trace_mode'):
            with self.subTest(mutation=mutation):
                off=controlled_arm('off','off','5','6')
                if mutation=='missing_control':off[0].pop('firstPresentCacheMode')
                if mutation=='wait':off[1]['events'][1]['kind']='lookup_wait'
                if mutation=='wrong_lookup':off[1]['events'][1].update(kind='lookup_hit',image=11)
                if mutation=='wrong_trace_mode':off[1]['mode']='three_page_window'
                report=self.pair(off=off)
                self.assertFalse(report['matchedTupleContract'])
                self.assert_closed_flags(report)

    def test_different_or_missing_apk_and_capture_hashes_do_not_unlock_same_source(self):
        for field in ('apkSha256','testApkSha256','rawTraceSha256','framesCsvSha256'):
            values=(None,'bad')+(('9'*64,) if field in ('apkSha256','testApkSha256') else ())
            for value in values:
                with self.subTest(field=field,value=value):
                    off=controlled_arm('off','off','5','6')
                    if value is None:off[0].pop(field)
                    else:off[0][field]=value
                    report=self.pair(off=off)
                    self.assertFalse(report['matchedTupleContract'])
                    self.assertIn('same_arm_reused',report['pairErrors'])

    def test_opposite_controls_still_require_matching_tuple_and_sampler(self):
        for field in ('material','device','sdk','rendering','sampler'):
            with self.subTest(field=field):
                off=controlled_arm('off','off','5','6')
                if field=='material':off[0]['measurementContract']['material']=off[0]['materialSha256']='f'*64
                elif field=='device':
                    off[0]['measurementContract']['device']['model']='other'
                    off[0]['contextBefore']['device']['model']=off[0]['contextAfter']['device']['model']='other'
                elif field=='sdk':
                    off[0]['measurementContract']['sdk']['api']=36
                    off[0]['contextBefore']['sdk']['api']=off[0]['contextAfter']['sdk']['api']=36
                elif field=='rendering':off[0]['measurementContract']['rendering']['raster']='different'
                else:off[0]['androidObserver']['mode']='none'
                report=self.pair(off=off)
                self.assertFalse(report['matchedTupleContract'])
                self.assert_closed_flags(report)

    def test_missing_marker_wrong_clock_or_prefilled_ms_still_close_same_build_pair(self):
        for mutation in ('missing_marker','clock','prefilled','frame'):
            with self.subTest(mutation=mutation):
                hit=controlled_arm('on','hit','3','4')
                if mutation=='missing_marker':hit[2].pop()
                if mutation=='clock':hit[1]['events'][-1]['elapsedRealtimeNanos']+=10**12
                if mutation=='prefilled':hit[2][-1]['handler_to_first_present_ms']=1
                if mutation=='frame':hit[2][-1]['display_token']=999
                report=self.pair(hit=hit)
                self.assertFalse(report['complete'])
                self.assertFalse(report['matchedTupleContract'])
                self.assert_closed_flags(report)


if __name__=='__main__':unittest.main()
