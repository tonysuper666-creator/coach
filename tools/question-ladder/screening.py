#!/usr/bin/env python3
"""Create a private screening ledger from a frozen inventory; report counts only."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path


def sha(data):
    return hashlib.sha256(data).hexdigest()


def initialise(root, inventory):
    frozen = json.loads(inventory.read_bytes())
    rows, seen = [], set()
    for week, info in sorted(frozen['weeks'].items()):
        if not info.get('pack_present'):
            continue
        raw = (root / (week + '.json')).read_bytes()
        if sha(raw) != info['pack_sha256']:
            raise ValueError(f'{week} changed since inventory was frozen')
        items = [q for section in json.loads(raw)['sections'] for q in section['items']]
        if len(items) != info['question_count']:
            raise ValueError(f'{week} count differs from inventory')
        for q in items:
            qid = q['id']
            if not qid or qid in seen:
                raise ValueError('missing or duplicate question ID')
            seen.add(qid)
            rows.append(dict(id=qid, week=week, kp_id=q['kp'], origin='original',
                             pack_sha256=info['pack_sha256'], disposition='pending',
                             rationale='', evidence=[], star='unknown', star_source=None,
                             kind='unknown', variant_group_id=None,
                             independent_key_review='pending', isolation='unknown'))
    return {'inventory_sha256': sha(inventory.read_bytes()), 'questions': rows}


def report(ledger):
    dispositions = {'pending', 'keep', 'rewrite', 'remove'}
    stars = {'unknown', '1', '2', '3'}
    kinds = {'unknown', 'introduction', 'concept', 'detail', 'application', 'bridge', 'exam_prototype', 'try_first'}
    per_week, distribution, seen = defaultdict(Counter), Counter(), set()
    for q in ledger['questions']:
        if not q.get('id') or q['id'] in seen:
            raise ValueError('missing or duplicate question ID')
        seen.add(q['id'])
        if q.get('origin') not in ('original', 'new') or q.get('disposition') not in dispositions:
            raise ValueError('invalid origin or disposition')
        if q.get('star') not in stars or q.get('kind') not in kinds:
            raise ValueError('invalid star or kind')
        if q['disposition'] != 'pending' and (not q.get('rationale') or not q.get('evidence')):
            raise ValueError('a decision requires a rationale and evidence')
        if q['star'] != 'unknown' and not q.get('star_source'):
            raise ValueError('a star requires a source')
        w = q['week']
        if w not in per_week:
            for origin in ('original', 'new'):
                per_week[w][origin + '_total'] = 0
                for decision in dispositions:
                    per_week[w][origin + '_' + decision] = 0
        per_week[w][q['origin'] + '_total'] += 1
        per_week[w][q['origin'] + '_' + q['disposition']] += 1
        distribution[(w, q['origin'], q['star'], q['kind'])] += 1
    return {'scope': 'provisional author decisions; unknown is not a pass or zero coverage',
            'inventory_sha256': ledger['inventory_sha256'],
            'weeks': {w: dict(sorted(c.items())) for w, c in sorted(per_week.items())},
            'distribution': [dict(week=w, origin=o, star=s, kind=k, questions=n)
                             for (w, o, s, k), n in sorted(distribution.items())]}


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    init = sub.add_parser('init')
    init.add_argument('root', type=Path)
    init.add_argument('inventory', type=Path)
    rep = sub.add_parser('report')
    rep.add_argument('ledger', type=Path)
    args = parser.parse_args()
    data = initialise(args.root, args.inventory) if args.command == 'init' else report(json.loads(args.ledger.read_text()))
    print(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
