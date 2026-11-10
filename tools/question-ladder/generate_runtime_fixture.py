#!/usr/bin/env python3
"""Generate a fully fictional, public contract example."""
import json
import sys
from pathlib import Path

base = {'required_capabilities': [], 'prerequisite_refs': [], 'options': None, 'answer': None,
        'model_answer': None, 'rubric': None, 'teaching_kind': 'concept'}
def question(ident, version, kp, capability, fmt, **fields):
    return dict(base, id=ident, version=version, ref=ident+'@'+version, kp=kp,
                capability=capability, format=fmt, **fields)
pack = {'schema': 'synthetic-runtime-contract-v1', 'synthetic': True,
        'applicable_kps': ['SYN-K1', 'SYN-K2', 'SYN-K3'],
        'prerequisite_aliases': [{'from': 'SYN-Q1@v1', 'to': 'SYN-Q1@v2'}],
        'questions': [
            question('SYN-Q1','v1','SYN-K1','SYN-add','mcq', stem='What is one plus one?', options=['1','2'], answer=1),
            question('SYN-Q1','v2','SYN-K1','SYN-add','mcq', stem='How many objects are one and one?', options=['1','2'], answer=1),
            question('SYN-Q2','v1','SYN-K2','SYN-explain','essay', required_capabilities=['SYN-add'],
                     stem='Explain how one object and another make a pair.', prerequisite_refs=['SYN-Q1@v1'], model_answer='Two objects form a pair.', rubric=['States the number.', 'Explains the grouping.']),
            question('SYN-Q3','v1','SYN-K3','SYN-review','essay', required_capabilities=None,
                     stem='Explain another grouping.', prerequisite_refs=None, model_answer=None, rubric=None)]}
Path(sys.argv[1]).write_text(json.dumps(pack,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
