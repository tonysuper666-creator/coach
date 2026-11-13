#!/usr/bin/env python3
"""Synthetic contract reference consumer. It is not the production loader."""
import argparse
import json
import re
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
    course = pack.get('course_id')
    if course is not None and (not isinstance(course, str) or not course.strip()):
        raise ValueError('invalid course identity')
    capabilities = pack.get('applicable_capabilities')
    if capabilities is not None:
        if not isinstance(capabilities, list) or any(not isinstance(c, dict) or set(c) != {'kp', 'capability'} or any(not isinstance(c[k], str) or not c[k] for k in ('kp', 'capability')) for c in capabilities):
            raise ValueError('invalid applicable capability denominator')
        pairs = [(c['kp'], c['capability']) for c in capabilities]
        if len(set(pairs)) != len(pairs) or (scope is not None and any(kp not in scope for kp, _ in pairs)):
            raise ValueError('duplicate capability or KP outside scope')
    else:
        pairs = None
    inventory = pack.get('candidate_inventory_complete')
    if inventory is not None and type(inventory) is not bool:
        raise ValueError('invalid candidate inventory status')
    questions = pack['questions']
    index = {q['ref']: q for q in questions}
    if len(index) != len(questions):
        raise ValueError('duplicate question version')
    aliases = {}
    for a in pack['prerequisite_aliases']:
        if a['from'] in aliases or a['from'] not in index or a['to'] not in index:
            raise ValueError('ambiguous or dangling alias')
        previous, successor = index[a['from']], index[a['to']]
        for field in ('id', 'kp', 'capability', 'format'):
            if not isinstance(previous.get(field), str) or not previous[field] or previous[field] != successor.get(field):
                raise ValueError('alias must preserve question id, KP, capability and format')
        versions = [previous.get('version'), successor.get('version')]
        if not all(isinstance(v, str) and re.fullmatch(r'v[1-9][0-9]*', v) for v in versions):
            raise ValueError('alias versions must use canonical vN positive integers')
        if int(versions[1][1:]) <= int(versions[0][1:]):
            raise ValueError('alias must point to a newer version')
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
        if pairs is not None and (q['kp'], q['capability']) not in pairs:
            raise ValueError('question capability outside applicable denominator')
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
        identity = q.get('essay_identity')
        if identity is not None:
            if q['format'] != 'essay' or not isinstance(identity, dict) or set(identity) != {'model_answer_id', 'rubric_point_ids', 'linked_question_refs'}:
                raise ValueError('invalid essay identity')
            answer_id = identity['model_answer_id']
            points, links = identity['rubric_point_ids'], identity['linked_question_refs']
            if not isinstance(answer_id, str) or not answer_id.strip() or not isinstance(points, list) or not points or any(not isinstance(p, str) or not p.strip() for p in points) or len(points) != len(set(points)):
                raise ValueError('invalid essay resource identities')
            if q['rubric'] is not None and len(points) != len(q['rubric']):
                raise ValueError('rubric identities and texts differ in length')
            if not isinstance(links, list) or any(not isinstance(r, str) for r in links) or len(links) != len(set(links)) or any(r not in index or index[r]['format'] != 'mcq' for r in links):
                raise ValueError('invalid linked question references')
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
    capabilities = pack.get('applicable_capabilities')
    active = [q for q in pack['questions'] if q['ref'] not in aliases]
    def gap(rows):
        refs = [q['ref'] for q in rows]
        return {'candidate_refs': refs, 'status': ('candidate_present_unverified' if refs else
                'candidate_missing' if pack.get('candidate_inventory_complete') is True else 'unknown')}
    return {'synthetic_only': True, 'production_authorized': False,
            'course_id': pack.get('course_id'),
            'denominator': None if scope is None else len(scope),
            'applicable_kps': scope,
            'capability_denominator': None if capabilities is None else len(capabilities),
            'applicable_capabilities': capabilities,
            'candidate_inventory_complete': pack.get('candidate_inventory_complete'),
            'kp_gaps': None if scope is None else [dict(kp=kp, **gap([q for q in active if q['kp'] == kp])) for kp in scope],
            'capability_gaps': None if capabilities is None else [dict(item, **gap([q for q in active if q['kp'] == item['kp'] and q['capability'] == item['capability']])) for item in capabilities],
            'questions': records,
            'history_policy': 'aliases resolve prerequisite references only; historical attempts remain unchanged',
            'pr150_adapter': 'base v1 synthetic adapter exists; identity and scope extension unsupported; no production integration'}


def _ready_question(pack, ref, known_capabilities, submitted):
    if not submitted:
        raise ValueError('synthetic submission acknowledgement required')
    index, _ = validate(pack)
    if ref not in index:
        raise ValueError('unknown question version')
    row = next(q for q in consume(pack, known_capabilities)['questions'] if q['ref'] == ref)
    if not row['ready_for_synthetic_exercise']:
        raise ValueError('question is not ready for synthetic exercise')
    return index[ref]


def reveal(pack, ref, known_capabilities, submitted):
    q = _ready_question(pack, ref, known_capabilities, submitted)
    if q['format'] != 'mcq':
        raise ValueError('essay must be accessed through read-essay')
    return {'ref': ref, 'synthetic_only': True, 'mastery_evidence': False, 'answer': q['answer']}


def read_essay(pack, course_id, ref, known_capabilities, submitted):
    """Synthetic identity and submission gate, not an authentication service."""
    index, _ = validate(pack)
    if not course_id or pack.get('course_id') is None or course_id != pack['course_id']:
        raise ValueError('course identity unknown or mismatch')
    if ref not in index or index[ref]['format'] != 'essay':
        raise ValueError('unknown essay identity')
    if index[ref].get('essay_identity') is None:
        raise ValueError('essay resource identity unknown')
    q = _ready_question(pack, ref, known_capabilities, submitted)
    result = {'ref': ref, 'synthetic_only': True, 'mastery_evidence': False,
              'model_answer': q['model_answer'], 'rubric': q['rubric']}
    identity = q['essay_identity']
    _, aliases = validate(pack)
    return dict(result, course_id=course_id, question_id=q['id'], version=q['version'],
                kp=q['kp'], capability=q['capability'], access='synthetic_post_submission_only',
                model_answer_id=identity['model_answer_id'], rubric_point_ids=identity['rubric_point_ids'],
                recorded_linked_question_refs=identity['linked_question_refs'],
                resolved_linked_question_refs=[resolve(r, aliases) for r in identity['linked_question_refs']],
                production_authorized=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['validate', 'consume', 'reveal', 'read-essay'])
    parser.add_argument('input', type=Path)
    parser.add_argument('--known-capability', action='append', default=[])
    parser.add_argument('--ref')
    parser.add_argument('--course-id')
    parser.add_argument('--synthetic-submitted', action='store_true')
    args = parser.parse_args()
    pack = json.loads(args.input.read_text(encoding='utf-8'))
    if args.action == 'validate':
        validate(pack)
        print('PASS: synthetic contract valid')
    elif args.action == 'consume':
        print(json.dumps(consume(pack, args.known_capability), ensure_ascii=False, indent=2))
    elif args.action == 'reveal':
        print(json.dumps(reveal(pack, args.ref, args.known_capability, args.synthetic_submitted), ensure_ascii=False, indent=2))
    else:
        print(json.dumps(read_essay(pack, args.course_id, args.ref, args.known_capability, args.synthetic_submitted), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
