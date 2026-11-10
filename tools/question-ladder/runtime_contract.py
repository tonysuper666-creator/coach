#!/usr/bin/env python3
"""Synthetic contract reference consumer. It is not the production loader."""
import argparse
import json
from pathlib import Path


def resolve(ref, aliases):
    seen = set()
    while ref in aliases:
        if ref in seen:
            raise ValueError('alias cycle')
        seen.add(ref)
        ref = aliases[ref]
    return ref


def validate(pack):
    if pack.get('schema') != 'synthetic-runtime-contract-v1' or pack.get('synthetic') is not True:
        raise ValueError('synthetic contract required')
    scope = pack['applicable_kps']
    if scope is not None and (not isinstance(scope, list) or len(set(scope)) != len(scope) or not all(isinstance(k, str) and k for k in scope)):
        raise ValueError('invalid applicable KP denominator')
    questions = pack['questions']
    index = {q['ref']: q for q in questions}
    if len(index) != len(questions):
        raise ValueError('duplicate question version')
    aliases = {}
    for a in pack['prerequisite_aliases']:
        if a['from'] in aliases or a['from'] not in index or a['to'] not in index:
            raise ValueError('ambiguous or dangling alias')
        aliases[a['from']] = a['to']
    for ref in aliases:
        resolve(ref, aliases)
    graph = {}
    for q in questions:
        if not all(isinstance(q.get(k), str) and q[k] for k in ('ref', 'id', 'version', 'kp', 'teaching_kind', 'capability', 'stem')):
            raise ValueError('missing question identity or capability')
        if q['ref'] != q['id'] + '@' + q['version']:
            raise ValueError('ref must bind id and version')
        if scope is not None and q['kp'] not in scope:
            raise ValueError('question KP outside applicable denominator')
        if q['format'] not in ('mcq', 'essay'):
            raise ValueError('unknown format')
        if q['required_capabilities'] is not None and (not isinstance(q['required_capabilities'], list) or any(not isinstance(c, str) or not c for c in q['required_capabilities']) or q['capability'] in q['required_capabilities']):
            raise ValueError('invalid or self-referential capabilities')
        refs = q['prerequisite_refs']
        if refs is not None and not isinstance(refs, list):
            raise ValueError('prerequisite refs must be list or unknown')
        graph[q['ref']] = []
        for ref in refs or []:
            target = resolve(ref, aliases)
            if target not in index:
                raise ValueError('dangling prerequisite')
            graph[q['ref']].append(target)
        if q['format'] == 'mcq' and (not isinstance(q['options'], list) or len(q['options']) < 2 or not isinstance(q['answer'], int) or isinstance(q['answer'], bool) or not 0 <= q['answer'] < len(q['options'])):
            raise ValueError('invalid MCQ')
        if q['format'] == 'essay':
            # Missing is explicitly unknown, not an empty answer or a passed gate.
            if q['model_answer'] is not None and (not isinstance(q['model_answer'], str) or not q['model_answer'].strip()):
                raise ValueError('invalid essay answer')
            if q['rubric'] is not None and (not isinstance(q['rubric'], list) or not q['rubric'] or any(not isinstance(p, str) or not p.strip() for p in q['rubric'])):
                raise ValueError('invalid essay rubric')
    visiting, done = set(), set()
    def visit(ref):
        if ref in visiting:
            raise ValueError('prerequisite cycle')
        if ref in done:
            return
        visiting.add(ref)
        for target in graph[ref]:
            visit(target)
        visiting.remove(ref)
        done.add(ref)
    for ref in graph:
        visit(ref)
    return index, aliases


def consume(pack, known_capabilities=()):
    index, aliases = validate(pack)
    records = []
    for q in pack['questions']:
        missing = []
        if q['ref'] in aliases:
            missing.append('superseded_version')
        if q['required_capabilities'] is None or q['prerequisite_refs'] is None:
            missing.append('prerequisites_unknown')
        if q['format'] == 'essay' and (q['model_answer'] is None or q['rubric'] is None):
            missing.append('essay_answer_or_rubric_unknown')
        # This synthetic readiness report is not a production approval check.
        known = set(known_capabilities)
        unmet = None if q['required_capabilities'] is None else [c for c in q['required_capabilities'] if c not in known]
        ready = not missing and not unmet
        records.append({'ref': q['ref'], 'kp': q['kp'], 'format': q['format'],
                        'teaching_kind': q['teaching_kind'], 'target_capability': q['capability'],
                        'required_capabilities': q['required_capabilities'],
                        'recorded_prerequisites': q['prerequisite_refs'],
                        'resolved_prerequisites': None if q['prerequisite_refs'] is None else [resolve(r, aliases) for r in q['prerequisite_refs']],
                        'ready_for_synthetic_exercise': ready,
                        'exercise': ({'stem': q['stem'], 'options': q['options']} if ready else None),
                        'unmet_capabilities': unmet, 'missing': missing})
    scope = pack['applicable_kps']
    return {'synthetic_only': True, 'production_authorized': False,
            'denominator': None if scope is None else len(scope),
            'applicable_kps': scope, 'questions': records,
            'history_policy': 'aliases resolve prerequisite references only; historical attempts remain unchanged',
            'pr150_adapter': 'unsupported: capability IDs are not prerequisite KP IDs; no runtime integration'}


def reveal(pack, ref, known_capabilities, submitted):
    if not submitted:
        raise ValueError('synthetic submission acknowledgement required')
    index, _ = validate(pack)
    if ref not in index:
        raise ValueError('unknown question version')
    row = next(q for q in consume(pack, known_capabilities)['questions'] if q['ref'] == ref)
    if not row['ready_for_synthetic_exercise']:
        raise ValueError('question is not ready for synthetic exercise')
    q = index[ref]
    result = {'ref': ref, 'synthetic_only': True, 'mastery_evidence': False}
    if q['format'] == 'essay':
        result.update(model_answer=q['model_answer'], rubric=q['rubric'])
    else:
        result['answer'] = q['answer']
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['validate', 'consume', 'reveal'])
    parser.add_argument('input', type=Path)
    parser.add_argument('--known-capability', action='append', default=[])
    parser.add_argument('--ref')
    parser.add_argument('--synthetic-submitted', action='store_true')
    args = parser.parse_args()
    pack = json.loads(args.input.read_text(encoding='utf-8'))
    if args.action == 'validate':
        validate(pack)
        print('PASS: synthetic contract valid')
    elif args.action == 'consume':
        print(json.dumps(consume(pack, args.known_capability), ensure_ascii=False, indent=2))
    else:
        print(json.dumps(reveal(pack, args.ref, args.known_capability, args.synthetic_submitted), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
