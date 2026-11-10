#!/usr/bin/env python3
"""Count content-free reader.end receipts in [start,end); never export log lines or course text.

Denominator: admitted requests with visible evidence that reached ReaderExplanation's timer.
Includes cancellation/disconnection. Rejected/empty-evidence requests are outside this population.
Missing historical instrumentation is NOT a zero-timeout baseline.
"""
import argparse
from collections import Counter
import datetime as dt
import json
from pathlib import Path
import re
import sqlite3


def summarize(logs, start_ms, end_ms):
    counts = Counter()
    synthetic = 0
    for path in sorted(logs.glob('server-*.log')):
        with path.open(errors='replace') as lines:
            for line in lines:
                if 'COACH reader.end ' not in line:
                    continue
                fields = dict(re.findall(r'(\w+)=([\w.-]+)', line.split('COACH reader.end ', 1)[1]))
                if not fields.get('at_ms', '').isdigit() or not start_ms <= int(fields['at_ms']) < end_ms:
                    continue
                if fields.get('traffic') != 'user':
                    synthetic += 1
                    continue
                if fields.get('status') in {'complete', 'failed', 'cancelled', 'disconnected', 'timeout_first', 'timeout_gap', 'timeout_total'}:
                    counts[fields['status']] += 1
    total = sum(counts.values())
    timeouts = sum(n for status, n in counts.items() if status.startswith('timeout_'))
    return dict(requests=total, timeouts=timeouts, timeoutRate=timeouts/total if total else None,
        statuses=dict(counts), excludedSynthetic=synthetic, comparable=total > 0,
        limitation=None if total else 'No instrumented production samples; missing instrumentation or no traffic. Do not infer zero historical timeouts.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--logs', type=Path, required=True)
    p.add_argument('--start', required=True, help='ISO8601 with timezone, inclusive')
    p.add_argument('--end', required=True, help='ISO8601 with timezone, exclusive')
    p.add_argument('--ledger', type=Path, help='Optional read-only cumulative call count; not a timed denominator')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    def instant(s):
        value = dt.datetime.fromisoformat(s.replace('Z', '+00:00'))
        if value.tzinfo is None:
            p.error('Timezones required')
        return int(value.timestamp()*1000)
    start, end = instant(args.start), instant(args.end)
    if start >= end:
        p.error('start must precede end')
    result = dict(start=args.start, end=args.end, denominator='admitted reader explanation requests; includes cancelled/disconnected',
                  **summarize(args.logs, start, end))
    if args.ledger:
        with sqlite3.connect(args.ledger.resolve().as_uri()+'?mode=ro', uri=True) as db:
            result['legacyCumulativeCallsWithoutWindowOrTimeoutAttribution'] = dict(db.execute(
                "select status,count(*) from ai_calls where task='READER_SELECTION' group by status"))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ('requests', 'timeouts', 'timeoutRate', 'comparable')}))


if __name__ == '__main__':
    main()
