"""Overlay returned syscalls with read-thread sleep, retaining unknown boundary calls."""
import argparse
import csv
import json
from pathlib import Path

from read_scheduling import integer


def summarize_syscalls(scheduling,syscalls,quality):
    errors=[];results=[]
    if not scheduling['complete']: errors.append('incomplete_scheduling_evidence')
    expected={integer(read['marker'],'slice_id') for read in scheduling['samples'] if read.get('marker')}
    if any(integer(call,'read_slice_id') not in expected for call in syscalls):
        errors.append('unmatched_syscall_read_marker')
    if any(row.get('severity') in ('error','data_loss') and integer(row,'value')!=0 for row in quality):
        errors.append('trace_quality_errors_or_data_loss')
    for read in scheduling['samples']:
        marker=read.get('marker');local=[]
        output=dict(probeId=read['probeId'],route=read.get('route'),errors=local,calls=[],boundaryCalls=[])
        results.append(output)
        if not marker or read.get('errors'):
            local.append('invalid_read_marker_or_states');continue
        start=integer(marker,'ts');end=start+integer(marker,'dur');seen=set();ranges=[];sleep_covered=0
        for call in syscalls:
            if integer(call,'read_slice_id')!=integer(marker,'slice_id'): continue
            if call.get('read_label')!=marker['label'] or integer(call,'pid')!=integer(marker,'pid') or \
                    integer(call,'tid')!=integer(marker,'tid') or integer(call,'utid')!=integer(marker,'utid'):
                local.append('wrong_syscall_identity');continue
            identity=integer(call,'syscall_slice_id');ts=integer(call,'ts');dur=integer(call,'dur');name=call.get('name')
            if identity is None or identity in seen or ts is None or dur is None or not isinstance(name,str) or not name.startswith('sys_'):
                local.append('duplicate_or_invalid_syscall');continue
            seen.add(identity)
            # ATrace writes can be truncated/replicated by the importer. Preserve them;
            # they cannot identify socket send/receive or a completed blocking call.
            if name=='sys_write' or dur<=0 or ts<start or ts+dur>end:
                output['boundaryCalls'].append(call)
                if name!='sys_write': local.append('unfinished_or_cross_boundary_syscall')
                continue
            if integer(call,'ret') is None:
                local.append('missing_syscall_exit_value');output['boundaryCalls'].append(call);continue
            states={}
            for segment in read['segments']:
                overlap=max(0,min(ts+dur,segment['end'])-max(ts,segment['ts']))
                if overlap: states[segment['state']]=states.get(segment['state'],0)+overlap
            sleep=states.get('S',0)+states.get('D',0);sleep_covered+=sleep
            output['calls'].append(dict(raw=call,stateDurationNs=states,sleepSDNs=sleep))
            ranges.append((ts,ts+dur))
        if not output['calls']: local.append('no_returned_non_marker_syscalls')
        previous=None
        for left,right in sorted(ranges):
            if previous is not None and left<previous: local.append('overlapping_completed_syscalls')
            previous=max(right,previous or right)
        total_sleep=read.get('stateDurationNs',{}).get('S',0)+read.get('stateDurationNs',{}).get('D',0)
        output.update(sleepSDNs=total_sleep,returnedCallSleepSDNs=sleep_covered,
            unattributedSleepSDNs=max(0,total_sleep-sleep_covered),sleepAttributionComplete=sleep_covered==total_sleep and not local)
    return dict(complete=bool(results) and not errors and all(not row['errors'] for row in results),samples=results,errors=errors,quality=quality,
        interpretation='Complete means returned syscall records validated; inspect residual sleep and boundary calls separately. FD identity and receipt are unverified.',
        socketFdVerified=False,kernelReceiveObserved=False,wakeSourceVerified=False,networkCauseVerified=False,performanceTargetVerified=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True)
    parser.add_argument('--syscalls',type=Path,required=True)
    parser.add_argument('--quality',type=Path,required=True)
    args=parser.parse_args();directory=args.run.resolve()
    directory.relative_to(Path(__file__).resolve().parents[2])
    identity=json.loads((directory/'run.json').read_text())
    if identity.get('socketSyscallsRequested') is not True or identity.get('syscallBuildType')!='userdebug':
        raise ValueError('A requested syscall run on the existing userdebug emulator is required')
    def read(path):
        with path.open(newline='',encoding='utf-8-sig') as source:
            reader=csv.DictReader(source)
            if not reader.fieldnames: raise ValueError('CSV header required')
            return list(reader)
    scheduling=json.loads((directory/'read-scheduling.json').read_text())
    report=summarize_syscalls(scheduling,read(args.syscalls),read(args.quality))
    (directory/'read-syscalls.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(dict(complete=report['complete'],samples=len(report['samples']),errors=report['errors'])))
    return 0 if report['complete'] else 1


if __name__=='__main__': raise SystemExit(main())
