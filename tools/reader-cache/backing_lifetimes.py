"""Validate identity-preserving weak-backing high waters, not physical memory residency."""
import argparse
import json
from pathlib import Path


def analyze(trace):
    errors = []
    results = []
    allocations = {(a['image'], a['storage']): a for a in trace['allocations']}
    for field, count_field in (('peakWeakLiveDecodedSnapshot', 'peakWeakLiveDecodedBackings'),
                               ('peakWeakReachableBytesSnapshot', 'peakWeakReachableBackingBytes')):
        peak = trace.get(field)
        if not peak:
            errors.append(field + ':missing_peak_identity_snapshot')
            continue
        rows = peak['backings']
        keys = [(a['image'], a['storage']) for a in rows]
        decoded = [a for a in rows if a['storage'] == 'decoded']
        logical = [a for a in decoded if a['lease'] is None or not a['lease']['valueCleared']]
        byte_count = sum(a['bytes'] for a in rows)
        if len(keys) != len(set(keys)):
            errors.append(field + ':duplicate_backing_identity')
        if byte_count != peak['bytes'] or len(decoded) != peak['decoded'] or sum(a['storage'] == 'scratch' for a in rows) != peak['scratch']:
            errors.append(field + ':backing_totals_mismatch')
        actual = len(decoded) if count_field == 'peakWeakLiveDecodedBackings' else byte_count
        if actual != trace[count_field]:
            errors.append(field + ':high_water_mismatch')
        witnesses = [e for e in trace['events'] if e['ns'] == peak['ns'] and e['kind'] == peak['event']]
        if len(witnesses) != 1 or witnesses[0]['weakReachableBackingBytes'] != byte_count or witnesses[0]['weakLiveDecodedBackings'] != len(decoded):
            errors.append(field + ':missing_or_inconsistent_peak_event')
        for row in rows:
            allocation = allocations.get((row['image'], row['storage']))
            if allocation is None or any(row[key] != allocation[key] for key in ('width', 'height', 'bytes', 'sourceSession', 'sourcePage', 'prefetch')):
                errors.append(field + ':allocation_identity_mismatch')
            state = row['lease']
            if state is None:
                # Only the just-allocated decoded raster may predate construction of its lease.
                if row['storage'] == 'decoded' and not (len(witnesses) == 1 and witnesses[0]['image'] == row['image'] and
                        peak['event'] == 'allocation_decoded' and peak['decodeInFlight'] == 1):
                    errors.append(field + ':missing_lease_ownership')
            elif state['valueCleared'] and (state['cacheOwned'] or state['composedUses'] != 0):
                errors.append(field + ':inconsistent_lease_ownership')
            if row['retired'] and row['published']:
                errors.append(field + ':retired_still_published')
            if row['releaseDisposition'] is not None and (state is None or not state['valueCleared']):
                errors.append(field + ':release_before_lease_clear')
        results.append(dict(snapshot=field,ns=peak['ns'],decoded=len(decoded),scratch=peak['scratch'],bytes=byte_count,
            logicalDecodedIncludingHandoff=len(logical),logicalDecodedBytes=sum(a['bytes'] for a in logical),
            pendingLeaseRetirement=sum(a['retired'] and a['lease'] is not None and a['lease']['cacheOwned'] for a in decoded),
            retiredWeakBackings=sum(a['retired'] for a in rows),backings=rows))
    if not trace.get('complete') or trace.get('droppedEvents',0) or trace.get('allocationOverflow',False):
        errors.append('incomplete_trace')
    return dict(complete=not errors,scope='weak backing receipts and logical lease ownership; not physical/GPU residency',
        errors=errors,peaks=results,firstPresentLatencyVerified=False,physicalMemoryBudgetVerified=False,
        gpuReleaseVerified=False,matchedBaselineObserved=False)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    report=analyze(json.loads(args.trace.read_text()))
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    raise SystemExit(0 if report['complete'] else 1)
