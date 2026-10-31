#!/usr/bin/env python3
"""Export content-free evidence from an isolated alpha smoke ledger (read only)."""
import argparse
import json
import sqlite3
from collections import Counter
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', required=True, type=Path)
    parser.add_argument('--course', required=True)
    parser.add_argument('--kp', action='append', required=True)
    parser.add_argument('--ttft-file', type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    db = sqlite3.connect(args.db.expanduser().resolve().as_uri() + '?mode=ro', uri=True)
    db.execute('PRAGMA query_only=ON')
    events = [(seq, kind, json.loads(payload)) for seq, kind, payload in db.execute(
        'SELECT seq,type,payload FROM events WHERE course_id=? ORDER BY seq', (args.course,))]
    posed = {p['attemptId']: p for _, kind, p in events if kind == 'question_posed'}
    kp_labels = {kp: 'KP' + str(i + 1) for i, kp in enumerate(args.kp)}
    selected = set(args.kp)
    evidence = []
    counts = {label: Counter() for label in kp_labels.values()}
    for seq, kind, payload in events:
        ids = payload.get('kpIds') or ([payload['kpId']] if 'kpId' in payload else [])
        if kind in {'answer_submitted', 'attempt_graded'}:
            ids = posed.get(payload.get('attemptId'), {}).get('kpIds', [])
        targets = selected.intersection(ids)
        if not targets or kind not in {'explanation_delivered', 'question_posed', 'answer_submitted', 'attempt_graded'}:
            continue
        row = {'seq': seq, 'type': kind, 'knowledge_points': sorted(kp_labels[kp] for kp in targets)}
        if kind == 'question_posed':
            row.update(options=len(payload.get('options', [])), server_letters=sorted(payload.get('letterMap', {}).values()), untaught=payload.get('untaught', False))
        elif kind == 'attempt_graded':
            row.update(score=payload.get('score'), max=payload.get('max'), grade_status=payload.get('status'))
        evidence.append(row)
        for kp in targets:
            counts[kp_labels[kp]][kind] += 1
    calls = [{'task': task, 'requested_model': requested, 'served_by': served, 'status': status, 'physical_calls': physical}
             for task, requested, served, status, physical in db.execute(
                 'SELECT task,requested_model,served_by,status,physical_calls FROM ai_calls')]
    timings = []
    if args.ttft_file:
        for line in args.ttft_file.expanduser().read_text().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            thread = row.get('thread') or row.get('threadId', '')
            kp = next((kp for kp in args.kp if kp in thread), None)
            if kp:
                timings.append({'knowledge_point': kp_labels[kp], 'kind': row.get('kind'), 'ms': row['ms'],
                                'model': row.get('model'), 'fallback': row.get('fallback', False)})
    summary = {'scope': 'isolated_smoke_ledger', 'counts': counts, 'events': evidence, 'client_first_word': timings,
               'physical_calls': sum(row['physical_calls'] or 0 for row in calls), 'ai_calls': calls,
               'three_kp_journeys': len(counts) == 3 and all(c['explanation_delivered'] >= 1 and
                   all(c[t] >= 3 for t in ['question_posed', 'answer_submitted', 'attempt_graded']) for c in counts.values())}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'journeys': summary['three_kp_journeys'], 'events': len(evidence), 'client_timings': len(timings), 'physical_calls': summary['physical_calls']}))


if __name__ == '__main__':
    main()
