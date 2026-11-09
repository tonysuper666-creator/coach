import copy
import json
from pathlib import Path
import tempfile
import unittest
from content import explanation_batch, markdown_notes, validate_notes

class ContentTest(unittest.TestCase):
    def notes(self):
        return markdown_notes('''<!-- coach-week: 1 -->
<!-- coach-section {"section_id":"W01-orchard","fact_ids":["F1","F2"],"sources":["Synthetic slide 1"]} -->
## Synthetic orchard
Pears grow here. Plums ripen later.
''')

    def test_stable_markers_and_complete_coverage(self):
        facts = {f: dict(source='Synthetic slide 1') for f in ['F1','F2']}
        self.assertEqual(dict(sections=1, facts=2), validate_notes(self.notes(), facts, 1))
        for mutate in [lambda n: n['sections'][0].update(fact_ids=['F1']),
                       lambda n: n['sections'][0].update(fact_ids=['UNKNOWN']),
                       lambda n: n['sections'][0].update(sources=['Wrong source']),
                       lambda n: n['sections'].append(copy.deepcopy(n['sections'][0])),
                       lambda n: n.update(week=2)]:
            n = self.notes(); mutate(n)
            with self.assertRaises(ValueError): validate_notes(n, facts, 1)

    def test_24_file_dry_run_apply_backup_and_frozen_key(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root); old=root/'old'; new=root/'new'; old.mkdir(); new.mkdir()
            original={'sections':[{'items':[{'id':'S1','stem':'Synthetic pear?', 'options':['A','B'], 'answer':1, 'fact_ids':['F1'], 'explanation':'Old'}]}]}
            candidate=copy.deepcopy(original); candidate['sections'][0]['items'][0]['explanation']='Improved'
            for i in range(1,25):
                (old/f'W{i:02}.json').write_text(json.dumps(original)); (new/f'W{i:02}.json').write_text(json.dumps(candidate))
            result=explanation_batch(old,new)
            self.assertEqual(24,result['files']); self.assertEqual('dry-run',result['mode'])
            self.assertEqual(original,json.loads((old/'W01.json').read_text()))
            bad=copy.deepcopy(candidate); bad['sections'][0]['items'][0]['answer']=True
            (new/'W24.json').write_text(json.dumps(bad))
            with self.assertRaises(ValueError): explanation_batch(old,new,True,root/'badbackup')
            self.assertFalse((root/'badbackup').exists())
            self.assertEqual(original,json.loads((old/'W01.json').read_text()))
            (new/'W24.json').write_text(json.dumps(candidate))
            explanation_batch(old,new,True,root/'backup')
            self.assertEqual(original,json.loads((root/'backup'/'W01.json').read_text()))
            self.assertEqual(candidate,json.loads((old/'W01.json').read_text()))
            self.assertEqual(24,len(json.loads((root/'backup'/'snapshot.json').read_text())['snapshots']))

if __name__ == '__main__': unittest.main()
