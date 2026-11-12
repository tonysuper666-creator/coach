"""Validate per-read scheduling intervals exported from the three read_*.sql queries."""
import argparse
import csv
import json
from pathlib import Path


def integer(row,key):
    value=row.get(key)
    return None if value in (None,'','[NULL]') else int(value)


def summarize_scheduling(association,pid,slices,states,quality):
    errors=list(association['errors']);result=[];expected=set()
    if not association['complete']: errors.append('incomplete_request_association')
    bad_quality=[row for row in quality if row.get('severity') in ('error','data_loss') and integer(row,'value')!=0]
    if bad_quality: errors.append('trace_quality_errors_or_data_loss')
    for sample in association['samples']:
        client=sample['client'];label=client.get('readTraceLabel');local=[]
        expected_label='ReaderSocket/first-read/'+sample['probeId'];expected.add(expected_label)
        candidates=[row for row in slices if row.get('label')==expected_label]
        output=dict(probeId=sample['probeId'],route=client.get('route'),errors=local,
                    applicationReadIntervalNs=client['firstByteNs']-client['readRequestedNs'])
        result.append(output)
        if label!=expected_label or len(candidates)!=1:
            local.append('missing_or_ambiguous_read_marker');continue
        marker=candidates[0];start=integer(marker,'ts');duration=integer(marker,'dur')
        if integer(marker,'pid')!=pid or integer(marker,'tid')!=client.get('readTid') or start is None or duration is None or duration<=0:
            local.append('invalid_marker_identity_or_duration');continue
        end=start+duration;segments=[];totals={}
        for state in states:
            if integer(state,'slice_id')!=integer(marker,'slice_id'): continue
            ts=integer(state,'ts');dur=integer(state,'dur');name=state.get('state')
            if ts is None or dur is None or dur<=0 or name in (None,'','[NULL]') or \
                    integer(state,'pid')!=pid or integer(state,'tid')!=client['readTid'] or integer(state,'utid')!=integer(marker,'utid'):
                local.append('incomplete_or_wrong_thread_state');continue
            left=max(start,ts);right=min(end,ts+dur)
            if right<=left:
                local.append('state_does_not_overlap_read');continue
            segments.append(dict(ts=left,end=right,state=name,raw=state))
        cursor=start
        for segment in sorted(segments,key=lambda row:(row['ts'],row['end'])):
            if segment['ts']>cursor: local.append('state_coverage_gap')
            if segment['ts']<cursor: local.append('state_coverage_overlap')
            cursor=max(cursor,segment['end'])
            totals[segment['state']]=totals.get(segment['state'],0)+segment['end']-segment['ts']
        if cursor!=end: local.append('state_coverage_incomplete')
        output.update(marker=marker,traceBracketNs=duration,stateDurationNs=totals,segments=segments,
            runningNs=totals.get('Running',0),runnableNs=totals.get('R',0)+totals.get('R+',0))
    extras={row.get('label') for row in slices}-expected
    if extras: errors.append('unmatched_read_markers')
    return dict(complete=bool(result) and not errors and all(not row['errors'] for row in result),
        samples=result,errors=errors,quality=quality,
        interpretation='Runnable time can show CPU dispatch waiting. Sleeping states do not identify network receipt or a specific blocked operation.',
        kernelReceiveObserved=False,wakeSourceVerified=False,networkCauseVerified=False,performanceTargetVerified=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--slices',type=Path,required=True)
    parser.add_argument('--states',type=Path,required=True)
    parser.add_argument('--quality',type=Path,required=True)
    args=parser.parse_args();directory=args.run.resolve()
    directory.relative_to(Path(__file__).resolve().parents[2])
    identity=json.loads((directory/'run.json').read_text())
    association=json.loads((directory/'transport-association.json').read_text())
    def read(path):
        with path.open(newline='',encoding='utf-8-sig') as source:
            reader=csv.DictReader(source)
            if not reader.fieldnames: raise ValueError('CSV header required')
            return list(reader)
    report=summarize_scheduling(association,identity['measurementPid'],read(args.slices),read(args.states),read(args.quality))
    (directory/'read-scheduling.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(dict(complete=report['complete'],samples=len(report['samples']),errors=report['errors'])))
    return 0 if report['complete'] else 1


if __name__=='__main__': raise SystemExit(main())
