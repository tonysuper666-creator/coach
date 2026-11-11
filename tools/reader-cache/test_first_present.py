import copy
from pathlib import Path
import sqlite3
import unittest
from unittest.mock import patch
from first_present import analyze,config_bytes,digest
from present_context import parse_geometry,capture_context
import run_observed


def fixture():
    device=dict(avd='synthetic-fold',model='synthetic',abi='arm64-v8a')
    sdk=dict(api=35,fingerprintSha256='d'*64)
    geometry=dict(width=2208,height=1840,densityDpi=420,fontScale=1.0)
    context=dict(device=device,sdk=sdk,rendering=geometry)
    package='com.coach.readermeasurement.ton1463'
    run=dict(sourceSha='a'*40,runId='candidate',measurementPid=42,applicationPackage=package,
        materialSha256='c'*64,contextBefore=copy.deepcopy(context),contextAfter=copy.deepcopy(context),
        measurementContract=dict(context,rendering=dict(geometry,raster='144dpi/png/native',rendererSha256='e'*64),
            material='c'*64,origin='synthetic_contract',clock='BOOTTIME'),
        selectedActions=[dict(kind='input_next',page=2)],androidObserver=dict(mode='both'),
        perfettoConfig='first-present.pbtxt',perfettoConfigSha256=digest(config_bytes(package)))
    def event(kind,ordinal,elapsed,image):
        return dict(kind=kind,ordinal=ordinal,action=7,image=image,page=2,ns=ordinal*1000,
            elapsedRealtimeNanos=elapsed,markerBeforeElapsedRealtimeNanos=elapsed+1000,
            markerAfterElapsedRealtimeNanos=elapsed+3000,traceMarkerWritten=True)
    start=event('input_next',1,1_000_000_000,0)
    draw=event('target_window_draw_commands',3,1_050_000_000,11)
    trace=dict(complete=True,process=dict(pid=42),events=[start,
        dict(kind='lookup_hit',ordinal=2,action=7,page=2,image=11,ns=2000),draw])
    def marker(e,identifier):
        return dict(marker_id=identifier,marker=f"ReaderPage/{e['kind']}/{e['action']}/{e['image']}",
            marker_ns=e['elapsedRealtimeNanos']+1500,marker_pid=42,clock_id=identifier+100,
            clock_marker=f"ReaderClock/{e['ordinal']}/{e['elapsedRealtimeNanos']}",clock_ns=e['elapsedRealtimeNanos']+2000)
    input_row=marker(start,1)
    frame=marker(draw,2)
    frame.update(choreographer_id=100,choreographer_token=33,app_id=22,app_pid=42,app_ns=1_045_000_000,
        app_dur=20_000_000,surface_token=33,app_display_token=44,layer_name=package+'/ReaderActivity',
        app_present_type='On-time Present',display_id=44,display_ns=1_040_000_000,display_dur=50_000_000,
        display_token=44,display_surface_token=None,display_pid=400,display_process='/system/bin/surfaceflinger',
        display_present_type='On-time Present')
    return copy.deepcopy((run,trace,[input_row,frame]))


class FirstPresentTest(unittest.TestCase):
    def test_first_hardware_draw_recomputes_endpoint_and_keeps_acceptance_false(self):
        report=analyze(*fixture())
        self.assertTrue(report['complete'],report['errors'])
        self.assertEqual(90,report['samples'][0]['handlerToFirstPresentMs'])
        self.assertEqual(3,report['samples'][0]['firstDrawOrdinal'])
        for key in ('firstPresentLatencyVerified','physicalMemoryBudgetVerified','gpuReleaseVerified','matchedBaselineObserved'):
            self.assertFalse(report[key])
        self.assertIn('missing_baseline_arm',report['pairErrors'])

    def test_matched_synthetic_arms_validate_tuple_without_claiming_native_observation(self):
        run,trace,rows=fixture()
        baseline=copy.deepcopy(run);baseline.update(sourceSha='b'*40,runId='baseline')
        other=analyze(baseline,trace,rows)
        report=analyze(run,trace,rows,other)
        self.assertTrue(report['matchedTupleContract'],report['pairErrors'])
        self.assertFalse(report['matchedBaselineObserved'])

    def test_each_missing_signal_is_rejected(self):
        for removed in ('input','draw','input_marker','draw_marker','clock'):
            with self.subTest(removed=removed):
                run,trace,rows=fixture()
                if removed=='input':trace['events']=trace['events'][1:]
                if removed=='draw':trace['events']=trace['events'][:-1]
                if removed=='input_marker':rows=rows[1:]
                if removed=='draw_marker':rows=rows[:-1]
                if removed=='clock':del trace['events'][-1]['elapsedRealtimeNanos']
                self.assertFalse(analyze(run,trace,rows)['complete'])

    def test_wrong_clocks_and_precomputed_milliseconds_are_rejected(self):
        for wrong in ('domain','capture_config','event_value','bracket','precomputed','companion'):
            with self.subTest(wrong=wrong):
                run,trace,rows=fixture()
                if wrong=='domain':run['measurementContract']['clock']='MONOTONIC'
                if wrong=='capture_config':run['perfettoConfig']='frametimeline.pbtxt'
                if wrong=='event_value':trace['events'][-1]['elapsedRealtimeNanos']+=10**12
                if wrong=='bracket':rows[-1]['marker_ns']+=1_000_000
                if wrong=='precomputed':rows[-1]['handler_to_first_present_ms']=1
                if wrong=='companion':rows[-1]['clock_marker']='ReaderClock/99/1050000000'
                self.assertFalse(analyze(run,trace,rows)['complete'])

    def test_each_wrong_frame_boundary_is_rejected(self):
        changes=[('surface_token',34),('display_token',45),('app_pid',43),('marker_pid',43),
            ('layer_name','com.coach.readermeasurement.ton14630/Activity'),('app_dur',-1),('display_dur',0),
            ('display_surface_token',1),('display_process','app'),('app_present_type','Dropped Frame'),
            ('display_present_type','Dropped Frame'),('app_ns',1_060_000_000),('display_ns',0)]
        for field,value in changes:
            with self.subTest(field=field):
                run,trace,rows=fixture();rows[-1][field]=value
                self.assertFalse(analyze(run,trace,rows)['complete'])

    def test_ambiguous_join_cannot_be_deduplicated_to_a_pass(self):
        run,trace,rows=fixture();rows.append(copy.deepcopy(rows[-1]))
        self.assertIn('ambiguous_first_frame_join',analyze(run,trace,rows)['errors'])

    def test_missing_first_frame_cannot_be_replaced_by_a_later_redraw(self):
        run,trace,rows=fixture()
        later=copy.deepcopy(trace['events'][-1]);later['ordinal']=4
        trace['events'].append(later)
        rows[-1]['clock_marker']='ReaderClock/4/1050000000'
        self.assertIn('missing_first_marker',analyze(run,trace,rows)['errors'])

    def test_dropped_first_frame_stays_rejected_when_a_redraw_is_present(self):
        run,trace,rows=fixture()
        later=copy.deepcopy(trace['events'][-1]);later['ordinal']=4;trace['events'].append(later)
        good=copy.deepcopy(rows[-1]);good['clock_marker']='ReaderClock/4/1050000000';rows.append(good)
        rows[1]['display_present_type']='Dropped Frame'
        self.assertIn('dropped_or_unpresented_frame',analyze(run,trace,rows)['errors'])

    def test_display_after_another_input_cannot_bound_the_old_action(self):
        run,trace,rows=fixture()
        following=copy.deepcopy(trace['events'][0]);following.update(ordinal=4,action=8,page=3,elapsedRealtimeNanos=1_080_000_000)
        trace['events'].append(following)
        self.assertIn('display_outside_input_window',analyze(run,trace,rows)['errors'])

    def test_every_tuple_dimension_must_match_the_other_arm(self):
        for field in ('device','sdk','material','rendering','action'):
            with self.subTest(field=field):
                run,trace,rows=fixture();other=copy.deepcopy(run);other.update(sourceSha='b'*40,runId='baseline')
                other_trace=copy.deepcopy(trace)
                if field=='device':
                    for context in (other['measurementContract'],other['contextBefore'],other['contextAfter']):context['device']['avd']='other-fold'
                if field=='sdk':
                    for context in (other['measurementContract'],other['contextBefore'],other['contextAfter']):context['sdk']['api']=36
                if field=='material':other['measurementContract']['material']=other['materialSha256']='f'*64
                if field=='rendering':
                    for context in (other['measurementContract'],other['contextBefore'],other['contextAfter']):context['rendering']['width']=2100
                if field=='action':
                    other['selectedActions'][0]['page']=3
                    for event in other_trace['events']:event['page']=3
                baseline=analyze(other,other_trace,rows)
                self.assertTrue(baseline['complete'],baseline['errors'])
                report=analyze(run,trace,rows,baseline)
                self.assertFalse(report['matchedTupleContract'])
                self.assertFalse(report['matchedBaselineObserved'])

    def test_same_arm_missing_context_and_forged_flags_do_not_prove_acceptance(self):
        run,trace,rows=fixture()
        other=analyze(*fixture())
        self.assertIn('same_arm_reused',analyze(run,trace,rows,other)['pairErrors'])
        trace['firstPresentLatencyVerified']=True
        report=analyze(run,trace,rows)
        self.assertFalse(report['firstPresentLatencyVerified'])
        run['contextAfter']['rendering']['width']=1
        self.assertIn('context_changed_or_missing',analyze(run,trace,rows)['errors'])

    def test_native_origin_without_capture_hashes_and_quality_is_rejected(self):
        run,trace,rows=fixture();run['measurementContract']['origin']='android_capture'
        report=analyze(run,trace,rows)
        self.assertFalse(report['complete'])
        self.assertIn('missing_or_changed_rawTraceSha256',report['errors'])
        self.assertIn('missing_trace_quality',report['errors'])

    def test_cold_baseline_lookup_does_not_get_relabelled_as_a_cache_hit(self):
        run,trace,rows=fixture();trace['events'][1].update(kind='lookup_miss',image=0)
        report=analyze(run,trace,rows)
        self.assertTrue(report['complete'],report['errors'])
        self.assertEqual('lookup_miss',report['samples'][0]['cacheLookup'])

    def test_one_action_runner_refuses_missing_capture_or_another_platform(self):
        for platform,capture in (('desktop',True),('android',False)):
            args=['run_observed.py','--platform',platform,'--suite','first-present','--output','unused']
            if capture:args.append('--perfetto')
            with patch('sys.argv',args),patch('run_observed.sys.stderr'),patch('run_observed.Fixture') as service:
                with self.assertRaises(SystemExit) as raised:run_observed.main()
                self.assertEqual(2,raised.exception.code);service.assert_not_called()


class FirstPresentSqlTest(unittest.TestCase):
    def query(self,app_duration=20_000_000,duplicate_display=False):
        db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
        db.executescript('''
          CREATE TABLE slice(id,name,ts,track_id,parent_id);
          CREATE TABLE thread_track(id,utid);
          CREATE TABLE thread(utid,upid);
          CREATE TABLE process(upid,pid,name);
          CREATE TABLE ancestor_fixture(descendant_id,id,name);
          CREATE TABLE actual_frame_timeline_slice(id,upid,ts,dur,surface_frame_token,display_frame_token,layer_name,present_type);
          INSERT INTO thread_track VALUES(1,1);
          INSERT INTO thread VALUES(1,10);
          INSERT INTO process VALUES(10,42,'app'),(20,400,'/system/bin/surfaceflinger');
          INSERT INTO ancestor_fixture VALUES(2,100,'Choreographer#doFrame 33');
        ''')
        run,trace,_=fixture()
        for e,identifier in ((trace['events'][0],1),(trace['events'][-1],2)):
            db.execute('INSERT INTO slice VALUES(?,?,?,?,?)',(identifier,f"ReaderPage/{e['kind']}/7/{e['image']}",e['elapsedRealtimeNanos']+1500,1,None))
            db.execute('INSERT INTO slice VALUES(?,?,?,?,?)',(identifier+100,f"ReaderClock/{e['ordinal']}/{e['elapsedRealtimeNanos']}",e['elapsedRealtimeNanos']+2000,1,identifier))
        db.execute('INSERT INTO actual_frame_timeline_slice VALUES(?,?,?,?,?,?,?,?)',
            (22,10,1_045_000_000,app_duration,33,44,run['applicationPackage']+'/ReaderActivity','On-time Present'))
        db.execute('INSERT INTO actual_frame_timeline_slice VALUES(?,?,?,?,?,?,?,?)',
            (44,20,1_040_000_000,50_000_000,None,44,None,'On-time Present'))
        if duplicate_display:
            db.execute('INSERT INTO actual_frame_timeline_slice VALUES(?,?,?,?,?,?,?,?)',
                (45,20,1_040_000_000,50_000_000,None,44,None,'On-time Present'))
        sql=Path(__file__).with_name('first_present.sql').read_text().replace('{{PID}}','42')
        # Standard sqlite lacks Perfetto's ancestor TVF. Expand only that TVF to
        # the explicit ancestor relation; all real projection/LEFT JOIN predicates run unchanged.
        sql=sql.replace('ancestor_slice(m.id) c ON','ancestor_fixture c ON c.descendant_id=m.id AND')
        rows=[dict(row) for row in db.execute(sql)]
        db.close()
        return analyze(run,trace,rows),rows

    def test_production_sql_projection_and_clock_parent_join(self):
        report,rows=self.query()
        self.assertEqual(2,len(rows))
        self.assertTrue(report['complete'],report['errors'])
        self.assertEqual(90,report['samples'][0]['handlerToFirstPresentMs'])

    def test_unfinished_first_frame_is_exported_and_rejected(self):
        report,rows=self.query(app_duration=-1)
        self.assertEqual(-1,rows[-1]['app_dur'])
        self.assertFalse(report['complete'])

    def test_ambiguous_display_rows_are_not_hidden_by_having_or_min(self):
        report,rows=self.query(duplicate_display=True)
        self.assertEqual(3,len(rows))
        self.assertIn('ambiguous_first_frame_join',report['errors'])


class PresentContextTest(unittest.TestCase):
    def test_native_context_has_actual_sdk_abi_and_fingerprint_hash(self):
        answers={'ro.kernel.qemu':'1','ro.product.model':'sdk_gphone64_arm64','ro.product.cpu.abi':'arm64-v8a',
                 'ro.build.version.sdk':'35','ro.build.fingerprint':'synthetic-sdk-build','font_scale':'1.0'}
        def read(command,**_):
            if command[-3:]==['emu','avd','name']:return 'synthetic-fold\nOK\n'
            if command[-2:]==['wm','size']:return 'Physical size: 2208x1840'
            if command[-2:]==['wm','density']:return 'Physical density: 420'
            return answers[command[-1]]
        with patch('present_context.subprocess.check_output',read):
            context=capture_context(['adb','-s','emulator-unit'])
            self.assertEqual(35,context['sdk']['api'])
            self.assertEqual(digest(b'synthetic-sdk-build'),context['sdk']['fingerprintSha256'])
            answers['ro.build.version.sdk']='36'
            with self.assertRaisesRegex(ValueError,'non_contract_sdk_or_abi'):capture_context(['adb','-s','emulator-unit'])

    def test_effective_override_is_recorded_instead_of_assuming_physical_geometry(self):
        self.assertEqual(dict(width=1972,height=1248,densityDpi=320,fontScale=1.0),
            parse_geometry('Physical size: 2208x1840\nOverride size: 1972x1248','Physical density: 420\nOverride density: 320','1.0'))

    def test_capture_rejects_old_override_before_using_device_tuple(self):
        def read(command,**_):
            if command[-1]=='ro.kernel.qemu':return '1'
            if command[-3:]==['emu','avd','name']:return 'synthetic-fold\nOK\n'
            if command[-2:]==['wm','size']:return 'Physical size: 2208x1840\nOverride size: 1972x1248'
            if command[-2:]==['wm','density']:return 'Physical density: 420\nOverride density: 320'
            if command[-1]=='font_scale':return '1.0'
            raise AssertionError(command)
        with patch('present_context.subprocess.check_output',read):
            with self.assertRaisesRegex(ValueError,'non_contract_geometry'):capture_context(['adb','-s','emulator-unit'])


if __name__=='__main__':unittest.main()
