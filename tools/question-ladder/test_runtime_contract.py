import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from runtime_contract import consume, read_essay, reveal, validate

HERE = Path(__file__).parent

class ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)/'fixture.json'
            subprocess.run([sys.executable,str(HERE/'generate_runtime_fixture.py'),str(p)],check=True)
            cls.pack = json.loads(p.read_text())

    def test_consumed_alias_and_history(self):
        row = consume(self.pack, ['SYN-add'])['questions'][2]
        self.assertEqual(row['recorded_prerequisites'], ['SYN-Q1@v1'])
        self.assertEqual(row['resolved_prerequisites'], ['SYN-Q1@v2'])
        self.assertTrue(row['ready_for_synthetic_exercise'])
        self.assertIn('stem', row['exercise'])
        self.assertNotIn('model_answer', row)
        self.assertFalse(consume(self.pack)['questions'][0]['ready_for_synthetic_exercise'])
        self.assertFalse(consume(self.pack)['questions'][2]['ready_for_synthetic_exercise'])

    def test_synthetic_reveal_requires_submission_and_capabilities(self):
        with self.assertRaises(ValueError): reveal(self.pack, 'SYN-Q2@v1', ['SYN-add'], False)
        with self.assertRaises(ValueError): reveal(self.pack, 'SYN-Q2@v1', [], True)
        with self.assertRaises(ValueError): reveal(self.pack, 'SYN-Q3@v1', [], True)
        result = reveal(self.pack, 'SYN-Q2@v1', ['SYN-add'], True)
        self.assertEqual(result['model_answer'], 'Two objects form a pair.')
        self.assertEqual(len(result['rubric']), 2)
        self.assertFalse(result['mastery_evidence'])
        self.assertEqual(reveal(self.pack, 'SYN-Q1@v2', [], True)['answer'], 1)

    def test_alias_rejects_identity_and_scope_changes(self):
        for field, value in [('id', 'SYN-other'), ('kp', 'SYN-K2'),
                             ('capability', 'SYN-other-cap'), ('format', 'essay')]:
            p = copy.deepcopy(self.pack)
            p['questions'][1][field] = value
            if field == 'id':
                p['questions'][1]['ref'] = value + '@v2'
                p['prerequisite_aliases'][0]['to'] = value + '@v2'
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'alias must preserve'):
                validate(p)

    def test_alias_rejects_reverse_same_and_unordered_versions(self):
        for source, target in [('SYN-Q1@v2', 'SYN-Q1@v1'), ('SYN-Q1@v1', 'SYN-Q1@v1')]:
            p = copy.deepcopy(self.pack)
            p['prerequisite_aliases'] = [{'from': source, 'to': target}]
            with self.subTest(source=source, target=target), self.assertRaisesRegex(ValueError, 'newer version'):
                validate(p)
        p = copy.deepcopy(self.pack)
        p['questions'][1]['version'] = '1453-2'
        p['questions'][1]['ref'] = 'SYN-Q1@1453-2'
        p['prerequisite_aliases'][0]['to'] = 'SYN-Q1@1453-2'
        with self.assertRaisesRegex(ValueError, 'canonical vN'):
            validate(p)

    def test_alias_version_order_is_numeric(self):
        p = copy.deepcopy(self.pack)
        p['questions'][1]['version'] = 'v10'
        p['questions'][1]['ref'] = 'SYN-Q1@v10'
        p['prerequisite_aliases'][0]['to'] = 'SYN-Q1@v10'
        validate(p)
        self.assertEqual(consume(p, ['SYN-add'])['questions'][2]['resolved_prerequisites'], ['SYN-Q1@v10'])

    def test_unknown_never_counts_as_complete(self):
        p = copy.deepcopy(self.pack)
        p['applicable_kps'] = None
        out = consume(p)
        self.assertIsNone(out['denominator'])
        self.assertFalse(out['questions'][3]['ready_for_synthetic_exercise'])
        self.assertFalse(out['production_authorized'])

    def test_reject_cycles_missing_refs_and_capability_self_reference(self):
        for change in ('alias_cycle','missing','self','cycle'):
            p = copy.deepcopy(self.pack)
            if change == 'alias_cycle': p['prerequisite_aliases'].append({'from':'SYN-Q1@v2','to':'SYN-Q1@v1'})
            if change == 'missing': p['questions'][2]['prerequisite_refs'] = ['SYN-absent@v1']
            if change == 'self': p['questions'][2]['required_capabilities'] = ['SYN-explain']
            if change == 'cycle': p['questions'][1]['prerequisite_refs'] = ['SYN-Q1@v1']
            with self.subTest(change=change), self.assertRaises(ValueError): validate(p)

    def test_reject_untruthful_denominator_and_missing_essay(self):
        p=copy.deepcopy(self.pack); p['applicable_kps']=['SYN-K1']
        with self.assertRaises(ValueError): validate(p)
        p=copy.deepcopy(self.pack); p['questions'][2]['model_answer']=None
        self.assertFalse(consume(p)['questions'][2]['ready_for_synthetic_exercise'])

    def test_course_scoped_essay_identity_and_access(self):
        other = copy.deepcopy(self.pack)
        other['course_id'] = 'SYN-COURSE-B'
        other['questions'][2]['model_answer'] = 'A different synthetic answer.'
        for pack, course, expected in [(self.pack, 'SYN-COURSE-A', 'Two objects form a pair.'),
                                       (other, 'SYN-COURSE-B', 'A different synthetic answer.')]:
            result = read_essay(pack, course, 'SYN-Q2@v1', ['SYN-add'], True)
            self.assertEqual(result['model_answer'], expected)
            self.assertEqual((result['question_id'], result['version'], result['course_id']), ('SYN-Q2', 'v1', course))
            self.assertEqual(result['model_answer_id'], 'SYN-ANSWER-2')
            self.assertEqual(result['rubric_point_ids'], ['SYN-RUBRIC-2A', 'SYN-RUBRIC-2B'])
            self.assertEqual(result['recorded_linked_question_refs'], ['SYN-Q1@v1'])
            self.assertEqual(result['resolved_linked_question_refs'], ['SYN-Q1@v2'])
            self.assertFalse(result['production_authorized'])
        for course, ref, submitted in [('SYN-COURSE-B', 'SYN-Q2@v1', True),
                                       (None, 'SYN-Q2@v1', True),
                                       ('SYN-COURSE-A', 'SYN-Q2@v2', True),
                                       ('SYN-COURSE-A', 'SYN-Q1@v2', True),
                                       ('SYN-COURSE-A', 'SYN-Q2@v1', False),
                                       ('SYN-COURSE-A', 'SYN-Q3@v1', True)]:
            with self.subTest(course=course, ref=ref, submitted=submitted), self.assertRaises(ValueError):
                read_essay(self.pack, course, ref, ['SYN-add'], submitted)
        legacy = copy.deepcopy(self.pack); del legacy['course_id']
        with self.assertRaises(ValueError): read_essay(legacy, 'SYN-COURSE-A', 'SYN-Q2@v1', ['SYN-add'], True)
        missing = copy.deepcopy(self.pack); del missing['questions'][2]['essay_identity']
        with self.assertRaises(ValueError): read_essay(missing, 'SYN-COURSE-A', 'SYN-Q2@v1', ['SYN-add'], True)

    def test_essay_identity_rejects_mismatched_points_and_links(self):
        for mode in ('rubric', 'dangling', 'essay-link'):
            p = copy.deepcopy(self.pack)
            identity = p['questions'][2]['essay_identity']
            if mode == 'rubric': identity['rubric_point_ids'].pop()
            if mode == 'dangling': identity['linked_question_refs'] = ['SYN-missing@v1']
            if mode == 'essay-link': identity['linked_question_refs'] = ['SYN-Q3@v1']
            with self.subTest(mode=mode), self.assertRaises(ValueError): validate(p)

    def test_scope_and_missing_candidates_do_not_imply_mastery(self):
        out = consume(self.pack)
        self.assertEqual((out['denominator'], out['capability_denominator']), (4, 5))
        self.assertEqual(out['kp_gaps'][3]['status'], 'candidate_missing')
        self.assertEqual(out['capability_gaps'][2]['status'], 'candidate_missing')
        self.assertEqual(out['capability_gaps'][0]['candidate_refs'], ['SYN-Q1@v2'])
        self.assertEqual(out['capability_gaps'][3]['status'], 'candidate_present_unverified')
        p = copy.deepcopy(self.pack); p['candidate_inventory_complete'] = None
        self.assertEqual(consume(p)['kp_gaps'][3]['status'], 'unknown')
        p['applicable_kps'] = None; p['applicable_capabilities'] = None
        out = consume(p)
        self.assertIsNone(out['denominator']); self.assertIsNone(out['capability_denominator'])
        self.assertIsNone(out['kp_gaps']); self.assertIsNone(out['capability_gaps'])

    def test_capability_scope_rejects_duplicate_outside_and_omitted(self):
        for mode in ('duplicate', 'outside', 'omitted'):
            p = copy.deepcopy(self.pack)
            if mode == 'duplicate': p['applicable_capabilities'].append(p['applicable_capabilities'][0])
            if mode == 'outside': p['applicable_capabilities'][0]['kp'] = 'SYN-other'
            if mode == 'omitted': p['applicable_capabilities'].pop(0)
            with self.subTest(mode=mode), self.assertRaises(ValueError): validate(p)

if __name__ == '__main__': unittest.main()
