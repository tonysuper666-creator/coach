import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from runtime_contract import consume, reveal, validate

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

if __name__ == '__main__': unittest.main()
