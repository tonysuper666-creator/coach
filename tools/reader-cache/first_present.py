"""Audit the first hardware draw, its clock pair and an optional matched arm.

This slice reports endpoint observations, never the 20-page latency or physical
memory acceptance. A later redraw cannot replace an unlinked first draw.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re

PACKAGES=('com.coach.readermeasurement','com.coach.readermeasurement.ton1463')
PRESENT={'On-time Present','Late Present','Early Present'}
TP_SHA256='8488edbefadfc4be152cda1d42d9bfcede8b47638295eb85eb3bad8cd148fdf4'
TUPLE=('device','sdk','material','rendering','action')


def digest(data):return hashlib.sha256(data).hexdigest()


def config_bytes(package):
    if package not in PACKAGES:raise ValueError('non_measurement_package')
    return Path(__file__).with_name('first-present.pbtxt').read_bytes().replace(PACKAGES[0].encode(),package.encode())


def integer(value):return type(value) is int and value>=0


def validate_contract(run):
    errors=[]
    contract=run.get('measurementContract',{})
    if not isinstance(contract,dict):return ['missing_measurement_contract']
    package=run.get('applicationPackage')
    if package not in PACKAGES:errors.append('non_measurement_package')
    if not re.fullmatch('[0-9a-f]{40}',run.get('sourceSha','')):errors.append('missing_source_sha')
    if not run.get('runId') or not integer(run.get('measurementPid')):errors.append('missing_run_identity')
    if contract.get('origin') not in ('android_capture','synthetic_contract'):errors.append('missing_evidence_origin')
    for field in TUPLE[:-1]:
        if not contract.get(field):errors.append('missing_tuple_'+field)
    device=contract.get('device') or {}
    sdk=contract.get('sdk') or {}
    rendering=contract.get('rendering') or {}
    if not all(isinstance(value,dict) for value in (device,sdk,rendering)):return errors+['invalid_tuple_structure']
    if not all(isinstance(device.get(key),str) and device[key] for key in ('avd','model','abi')):errors.append('incomplete_device_tuple')
    if not integer(sdk.get('api')) or not re.fullmatch('[0-9a-f]{64}',sdk.get('fingerprintSha256','')):errors.append('incomplete_sdk_tuple')
    if contract.get('material')!=run.get('materialSha256') or not re.fullmatch('[0-9a-f]{64}',str(contract.get('material',''))):errors.append('material_hash_mismatch')
    if not all(rendering.get(key) for key in ('width','height','densityDpi','fontScale','raster','rendererSha256')):errors.append('incomplete_rendering_tuple')
    if not all(integer(rendering.get(key)) and rendering[key]>0 for key in ('width','height','densityDpi')) or not isinstance(rendering.get('fontScale'),(int,float)) or not math.isfinite(rendering['fontScale']) or rendering['fontScale']<=0:
        errors.append('invalid_rendering_geometry')
    if not re.fullmatch('[0-9a-f]{64}',str(rendering.get('rendererSha256',''))):errors.append('missing_renderer_identity')
    if run.get('contextBefore')!=run.get('contextAfter') or not run.get('contextBefore'):errors.append('context_changed_or_missing')
    elif any(contract.get(key)!=run['contextBefore'].get(key) for key in ('device','sdk')) or any(
            rendering.get(key)!=run['contextBefore'].get('rendering',{}).get(key) for key in ('width','height','densityDpi','fontScale')):
        errors.append('tuple_not_bound_to_captured_context')
    if contract.get('clock')!='BOOTTIME':errors.append('wrong_clock_domain')
    if package in PACKAGES and (run.get('perfettoConfig')!='first-present.pbtxt' or
            run.get('perfettoConfigSha256')!=digest(config_bytes(package))):errors.append('wrong_clock_capture_config')
    if run.get('androidObserver',{}).get('mode')!='both':errors.append('missing_default_ui_sampler')
    if contract.get('origin')=='android_capture':
        if run.get('sourceDirty') is not False:errors.append('dirty_native_source')
        for field in ('apkSha256','testApkSha256'):
            if not re.fullmatch('[0-9a-f]{64}',run.get(field,'')):errors.append('missing_'+field)
        if run.get('commandExit')!=0 or run.get('perfettoExit')!=0 or run.get('memorySamplerErrors') or run.get('instrumentationPassed') is not True:
            errors.append('native_capture_incomplete')
        for name in ('rawTraceSha256','framesCsvSha256'):
            if not re.fullmatch('[0-9a-f]{64}',run.get(name,'')) or run.get(name)!=run.get('_'+name):errors.append('missing_or_changed_'+name)
        query=Path(__file__).with_name('first_present.sql').read_text().replace('{{PID}}',str(run.get('measurementPid')))
        if run.get('frameQuerySha256')!=digest(query.encode()):errors.append('wrong_frame_query')
        if run.get('traceProcessorBinarySha256')!=TP_SHA256:errors.append('unverified_trace_processor')
        if 'traceQualityIssues' not in run:errors.append('missing_trace_quality')
        elif any(item.get('severity') in ('error','data_loss') and int(item.get('value',0)) for item in run['traceQualityIssues']):
            errors.append('trace_import_error_or_loss')
    return errors


def clock_row(event,rows,pid):
    elapsed=event.get('elapsedRealtimeNanos')
    before=event.get('markerBeforeElapsedRealtimeNanos')
    after=event.get('markerAfterElapsedRealtimeNanos')
    if not all(integer(value) for value in (elapsed,before,after,event.get('ordinal'))) or not elapsed<=before<=after:
        raise ValueError('missing_or_wrong_event_clock')
    if event.get('traceMarkerWritten') is not True:raise ValueError('marker_not_written')
    clock=f"ReaderClock/{event['ordinal']}/{elapsed}"
    matching=[row for row in rows if row.get('clock_marker')==clock]
    identities={(row.get('marker_id'),row.get('clock_id'),row.get('marker_ns'),row.get('clock_ns')) for row in matching}
    if not matching:raise ValueError('missing_first_marker')
    if len(identities)!=1:raise ValueError('ambiguous_marker_clock')
    marker=f"ReaderPage/{event['kind']}/{event['action']}/{event['image']}"
    for row in matching:
        if row.get('marker')!=marker or row.get('marker_pid')!=pid:raise ValueError('marker_identity_mismatch')
        if not all(integer(row.get(key)) for key in ('marker_id','clock_id','marker_ns','clock_ns')):
            raise ValueError('missing_trace_clock')
        if not before<=row['marker_ns']<=row['clock_ns']<=after:raise ValueError('trace_clock_outside_local_bracket')
        if 'handler_to_first_present_ms' in row:raise ValueError('precomputed_latency_not_accepted')
    return matching


def same_build_opposite_controls(run,samples,other,other_samples):
    """A source SHA may repeat only for independently captured opposite controls."""
    mode=run.get('firstPresentCacheMode')
    opposite={'on':'off','off':'on'}
    if mode not in opposite or other.get('firstPresentCacheMode')!=opposite[mode]:return False
    for field in ('apkSha256','testApkSha256'):
        value=run.get(field)
        if not isinstance(value,str) or not re.fullmatch('[0-9a-f]{64}',value) or other.get(field)!=value:return False
    for field in ('rawTraceSha256','framesCsvSha256'):
        left,right=run.get(field),other.get(field)
        if not all(isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value) for value in (left,right)) or left==right:return False
    left={json.dumps(row['selector'],sort_keys=True):row['cacheLookup'] for row in samples}
    right={json.dumps(row['selector'],sort_keys=True):row['cacheLookup'] for row in other_samples}
    return bool(left) and left.keys()==right.keys() and all({left[key],right[key]}=={'lookup_hit','lookup_miss'} for key in left)


def analyze(run,trace,rows,baseline=None):
    errors=validate_contract(run)
    samples=[]
    events=trace.get('events',[])
    cache_mode=run.get('firstPresentCacheMode')
    if cache_mode is not None:
        if cache_mode not in ('on','off'):errors.append('invalid_first_present_cache_mode')
        elif trace.get('firstPresentCacheMode')!=cache_mode or trace.get('mode')!=('three_page_window' if cache_mode=='on' else 'visible_pages_only'):
            errors.append('cache_mode_trace_mismatch')
    if trace.get('complete') is not True or trace.get('droppedEvents',0):errors.append('incomplete_events')
    ordinals=[e.get('ordinal') for e in events]
    if not all(integer(value) for value in ordinals) or len(set(ordinals))!=len(ordinals) or ordinals!=sorted(ordinals):
        errors.append('ambiguous_event_order')
    if trace.get('process',{}).get('pid')!=run.get('measurementPid'):errors.append('process_identity_mismatch')
    selected=run.get('selectedActions',[])
    if not selected or len({json.dumps(item,sort_keys=True) for item in selected})!=len(selected):errors.append('missing_or_duplicate_selected_actions')
    if any(set(item)!= {'kind','page'} or item.get('kind') not in ('input_next','input_previous','input_citation','input_map','input_restore')
           or not integer(item.get('page')) or item['page']==0 for item in selected):errors.append('invalid_action_tuple')
    for selector in selected:
        try:
            inputs=[e for e in events if e.get('kind')==selector.get('kind') and e.get('page')==selector.get('page')]
            if len(inputs)!=1:raise ValueError('missing_or_ambiguous_input')
            start=inputs[0]
            following=[e for e in events if e.get('kind','').startswith('input_') and e['ordinal']>start['ordinal']]
            next_input=following[0] if following else None
            draws=[e for e in events if e.get('kind')=='target_window_draw_commands' and e.get('action')==start['action']
                   and e['ordinal']>start['ordinal'] and (next_input is None or e['ordinal']<next_input['ordinal'])]
            if not draws:raise ValueError('missing_first_hardware_draw')
            draw=draws[0]
            if draw['page']!=start['page'] or not integer(draw.get('image')) or draw['image']==0:raise ValueError('wrong_target_image')
            lookups=[e for e in events if e.get('kind') in ('lookup_hit','lookup_miss','lookup_wait') and e.get('action')==start['action']
                     and start['ordinal']<e['ordinal']<=draw['ordinal']]
            if len(lookups)!=1 or (lookups[0].get('image')!=draw['image'] and
                    (lookups[0]['kind']=='lookup_hit' or lookups[0].get('image')!=0)):
                raise ValueError('missing_or_wrong_lookup_image')
            if cache_mode in ('on','off') and lookups[0]['kind']!=('lookup_hit' if cache_mode=='on' else 'lookup_miss'):
                raise ValueError('lookup_cache_mode_mismatch')
            clock_row(start,rows,run['measurementPid'])
            matched=clock_row(draw,rows,run['measurementPid'])
            if len(matched)!=1:raise ValueError('ambiguous_first_frame_join')
            frame=matched[0]
            needed=('app_id','app_pid','app_ns','choreographer_id','choreographer_token','surface_token',
                    'app_display_token','display_id','display_ns','display_token','display_pid')
            # A signed -1 duration is present-but-unfinished, not an absent endpoint.
            if not all(integer(frame.get(key)) for key in needed) or not all(
                    type(frame.get(key)) is int for key in ('app_dur','display_dur')):
                raise ValueError('missing_frame_endpoint')
            if frame['app_dur']<=0 or frame['display_dur']<=0:raise ValueError('unfinished_frame')
            if frame['app_pid']!=run['measurementPid'] or frame['surface_token']!=frame['choreographer_token'] or frame['app_display_token']!=frame['display_token']:
                raise ValueError('wrong_frame_token_or_process')
            if frame.get('display_surface_token') is not None:raise ValueError('not_a_display_frame')
            if frame.get('app_present_type') not in PRESENT or frame.get('display_present_type') not in PRESENT:raise ValueError('dropped_or_unpresented_frame')
            if 'surfaceflinger' not in str(frame.get('display_process','')).lower():raise ValueError('missing_surfaceflinger_identity')
            package=run['applicationPackage']
            if not re.search(r'(?<![A-Za-z0-9_.])'+re.escape(package)+r'(?=$|[/ #}])',str(frame.get('layer_name',''))):raise ValueError('wrong_app_layer')
            if not frame['app_ns']<=frame['marker_ns']<=frame['app_ns']+frame['app_dur']:raise ValueError('marker_outside_app_frame')
            end=frame['display_ns']+frame['display_dur']
            if not start['elapsedRealtimeNanos']<=draw['elapsedRealtimeNanos']<=frame['marker_ns']<=end:raise ValueError('wrong_clock_or_frame_order')
            if next_input is not None and (not integer(next_input.get('elapsedRealtimeNanos')) or end>=next_input['elapsedRealtimeNanos']):
                raise ValueError('display_outside_input_window')
            samples.append(dict(action=start['action'],selector=selector,image=draw['image'],firstDrawOrdinal=draw['ordinal'],
                markerId=frame['marker_id'],displayFrameId=frame['display_id'],inputElapsedRealtimeNanos=start['elapsedRealtimeNanos'],
                firstDrawElapsedRealtimeNanos=draw['elapsedRealtimeNanos'],displayEndNs=end,
                handlerToFirstPresentMs=(end-start['elapsedRealtimeNanos'])/1e6,
                cacheLookup=lookups[0]['kind'],tuple=dict(run['measurementContract'],action=selector)))
        except (ValueError,KeyError,TypeError) as error:errors.append(str(error))
    pair_errors=[]
    if baseline is None:pair_errors.append('missing_baseline_arm')
    elif not baseline.get('complete') or not baseline.get('samples'):pair_errors.append('incomplete_baseline_arm')
    else:
        other=baseline['run']
        if other['runId']==run['runId'] or (other['sourceSha']==run['sourceSha'] and
                not same_build_opposite_controls(run,samples,other,baseline['samples'])):
            pair_errors.append('same_arm_reused')
        if run['measurementContract'].get('origin')=='android_capture' and (
                other.get('rawTraceSha256')==run.get('rawTraceSha256') or other.get('framesCsvSha256')==run.get('framesCsvSha256')):
            pair_errors.append('same_capture_reused')
        if {json.dumps(item,sort_keys=True) for item in other['selectedActions']}!={json.dumps(item,sort_keys=True) for item in selected}:
            pair_errors.append('action_tuple_mismatch')
        for field in TUPLE[:-1]:
            if other['measurementContract'].get(field)!=run['measurementContract'].get(field):pair_errors.append('tuple_mismatch_'+field)
        for field in ('applicationPackage','perfettoConfigSha256'):
            if other.get(field)!=run.get(field):pair_errors.append('provenance_mismatch_'+field)
        if other.get('androidObserver',{}).get('mode')!=run.get('androidObserver',{}).get('mode'):pair_errors.append('sampler_mismatch')
    complete=not errors and len(samples)==len(selected) and bool(samples)
    pair_complete=complete and not pair_errors
    return dict(complete=complete,errors=errors,samples=samples,run=run,
        matchedTupleContract=pair_complete,matchedBaselineObserved=False,pairErrors=pair_errors,
        scope='first recorded hardware draw and ended display-frame association; not full latency acceptance',
        firstPresentLatencyVerified=False,physicalMemoryBudgetVerified=False,gpuReleaseVerified=False,
        baselineAcceptanceEvaluated=False,independentNativeVerificationRequired=True)


def load(directory):
    directory=Path(directory)
    run=json.loads((directory/'run.json').read_text())
    run['_framesCsvSha256']=digest((directory/'first-present.csv').read_bytes())
    raw=directory/'frames.perfetto-trace'
    run['_rawTraceSha256']=digest(raw.read_bytes()) if raw.exists() else None
    trace=json.loads((directory/'trace.json').read_text())
    rows=[]
    with (directory/'first-present.csv').open(newline='') as stream:
        for row in csv.DictReader(stream):
            rows.append({key:(None if value in ('','[NULL]','NULL') else int(value) if re.fullmatch('-?[0-9]+',value) else value)
                         for key,value in row.items()})
    return run,trace,rows


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--baseline',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    baseline=analyze(*load(args.baseline)) if args.baseline else None
    result=analyze(*load(args.directory),baseline=baseline)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    raise SystemExit(0 if result['complete'] and (not args.baseline or result['matchedTupleContract']) else 1)
