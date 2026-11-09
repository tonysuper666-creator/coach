import copy
import json
from pathlib import Path
import tempfile
import unittest
from content import digest, emphasis_metadata, explanation_batch, markdown_notes, validate_notes

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

    def correction_fixture(self, root):
        baseline, corrected, candidate = [root / n for n in ['baseline', 'corrected', 'candidate']]
        for directory in [baseline, corrected, candidate]: directory.mkdir()
        pack={'sections':[{'items':[{'id':'S1','stem':'Synthetic pear?', 'options':['A','B'],
              'answer':0,'key_state':'UNREVIEWED','source':'Synthetic 1','fact_ids':['F1'],'explanation':'Old'}]}]}
        rows=[]
        for week in [1,5,6]:
            name=f'W{week:02}.json'; data=copy.deepcopy(pack); edits=[]
            (baseline/name).write_text(json.dumps(data))
            if week==5:
                data['sections'][0]['items'][0]['answer']=1
                edits=[dict(item_id='S1',field='answer',before=0,after=1)]
            if week==6:
                data['sections'][0]['items'][0]['source']='Synthetic 2'
                edits=[dict(item_id='S1',field='source',before='Synthetic 1',after='Synthetic 2')]
            (corrected/name).write_text(json.dumps(data))
            if edits: rows.append(dict(file=name,original_sha256=digest(baseline/name),
                                      corrected_sha256=digest(corrected/name),changes=edits))
            data['sections'][0]['items'][0]['explanation']='Improved synthetic explanation'
            (candidate/name).write_text(json.dumps(data))
        ledger=root/'ledger.json'
        ledger.write_text(json.dumps(dict(schema_version=1,authorization=dict(issue='SYNTHETIC-ONLY',
                          qa='synthetic QA evidence',cross_review='synthetic cross review evidence'), files=rows)))
        return baseline, corrected, candidate, ledger

    def test_two_stage_scope_and_apply_preserve_pre_correction_library(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); baseline,corrected,candidate,ledger=self.correction_fixture(root)
            raw={p.name:p.read_bytes() for p in baseline.glob('*.json')}
            frozen={p.name:p.read_bytes() for p in corrected.glob('*.json')}
            result=explanation_batch(corrected,candidate,baseline=baseline,corrections=ledger)
            self.assertEqual(2,result['corrections']['authorized_files'])
            self.assertEqual(2,result['corrections']['changes'])
            self.assertEqual('dry-run',result['mode'])
            result=explanation_batch(corrected,candidate,True,root/'backup',baseline,ledger)
            self.assertEqual('apply',result['mode'])
            for name in raw:
                self.assertEqual(raw[name],(baseline/name).read_bytes())
                self.assertEqual(frozen[name],(root/'backup'/name).read_bytes())
                self.assertEqual((candidate/name).read_bytes(),(corrected/name).read_bytes())
            self.assertIn('corrections',json.loads((root/'backup'/'snapshot.json').read_text()))

    def test_two_stage_rejects_unlisted_changes_stale_hashes_and_key_rewrites(self):
        for case in ['outside-ledger','omitted-edit','stale-hash','new-question','rewrite-key',
                     'rewrite-state','boolean-key','missing-review','missing-ledger']:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as d:
                root=Path(d); baseline,corrected,candidate,ledger=self.correction_fixture(root)
                auth=json.loads(ledger.read_text())
                if case=='outside-ledger':
                    p=corrected/'W01.json';data=json.loads(p.read_text());data['sections'][0]['items'][0]['stem']='Unlisted';p.write_text(json.dumps(data))
                elif case=='omitted-edit': auth['files'][0]['changes'][0]['after']=0
                elif case=='stale-hash': auth['files'][0]['corrected_sha256']='0'*64
                elif case=='missing-review': auth['authorization']['cross_review']=''
                else:
                    p=candidate/'W05.json';data=json.loads(p.read_text());item=data['sections'][0]['items'][0]
                    if case=='new-question':
                        extra=copy.deepcopy(item);extra['id']='NEW';data['sections'][0]['items'].append(extra)
                    elif case=='rewrite-key': item['answer']=0
                    elif case=='rewrite-state': item['key_state']='VERIFIED'
                    elif case=='boolean-key': item['answer']=True
                    p.write_text(json.dumps(data))
                ledger.write_text(json.dumps(auth))
                before={p:p.read_bytes() for directory in [baseline,corrected] for p in directory.glob('*.json')}
                with self.assertRaises(ValueError):
                    explanation_batch(corrected,candidate,True,root/'backup',baseline,None if case=='missing-ledger' else ledger)
                self.assertFalse((root/'backup').exists())
                self.assertTrue(all(p.read_bytes()==data for p,data in before.items()))

    def test_emphasis_merges_explicit_inline_context_and_long_recordings(self):
        mark=dict(fact_id='F1',recording_kind='lecture',recording_id='L1',timestamp='121:03',
                  summary='Synthetic pears were emphasized.',reviewed=True)
        data=dict(schema_version=1,week=5,marks=[mark])
        context={k:v for k,v in mark.items() if k!='timestamp'}
        inline='<!-- coach-emphasis '+json.dumps(context)+' -->【课上强调 02:01:03】'
        result=emphasis_metadata(data,{'F1':{}},5,inline)
        self.assertEqual(1,len(result['marks']))
        self.assertEqual('02:01:03',result['marks'][0]['timestamp'])
        other=copy.deepcopy(mark);other['recording_kind']='tutorial'
        data['marks'].append(other)
        self.assertEqual(2,len(emphasis_metadata(data,{'F1':{}},5)['marks']))

    def test_emphasis_rejects_pending_unknown_ambiguous_and_conflicting_markers(self):
        mark=dict(fact_id='F1',recording_kind='lecture',recording_id='L1',timestamp='121:03',summary='Synthetic',reviewed=True)
        for changes in [dict(fact_id='unknown'),dict(timestamp=''),dict(timestamp='01:99'),
                        dict(reviewed=False),dict(summary=''),dict(recording_id=''),dict(recording_kind='unknown')]:
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                emphasis_metadata(dict(schema_version=1,week=1,marks=[dict(mark,**changes)]),{'F1':{}},1)
        data=dict(schema_version=1,week=1,marks=[mark])
        with self.assertRaises(ValueError): emphasis_metadata(data,{'F1':{}},2)
        with self.assertRaises(ValueError): emphasis_metadata(data,{'F1':{}},1,'【课上强调 12:03】')
        data['marks'].append(dict(mark,summary='Conflicting'))
        with self.assertRaises(ValueError): emphasis_metadata(data,{'F1':{}},1)

if __name__ == '__main__': unittest.main()
