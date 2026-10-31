#!/usr/bin/env python3
"""Prepare a blind, local-only 20-answer worksheet; compare returned marks with hashed AI results."""
import argparse
import hashlib
import html
import json
import math
from pathlib import Path

BANDS = ('PASS', 'NEEDS_WORK', 'FAIL')


def digest(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def load(path):
    return json.loads(Path(path).expanduser().read_text(encoding='utf-8'))


def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def prepare(args):
    source = Path(args.mapping).expanduser()
    mapping = load(source)
    candidates = {q['question_id']: q.get('kp_ids', []) for q in mapping['question_kp_candidates']}
    kp_names = {q['id']: q['name'] for q in mapping['knowledge_points']}
    kp_names.update({q.get('legacy_id'): q['name'] for q in mapping['knowledge_points']})
    if args.reviewed:
        reviewed = load(args.reviewed)
        if set(q['id'] for q in reviewed['questions']) != set(q['id'] for q in mapping['questions']):
            raise ValueError('Reviewed mappings belong to another snapshot')
        candidates.update({q['id']: q['kp_ids'] for q in reviewed['questions']})
    historical = {}
    for attempt in mapping['legacy_attempts']:
        raw = attempt['source_record']
        question = raw.get('question_json', {})
        if isinstance(question, str):
            try:
                question = json.loads(question)
            except ValueError:
                continue
        stem = question.get('question', question.get('stem', ''))
        if stem and raw.get('user_answer', '').strip():
            historical[stem.strip()] = (raw['user_answer'], attempt['id'])
    rows = []
    for item in mapping['questions']:
        q = item['source_record']
        if q.get('question_type') != 'short_answer':
            continue
        stem = q['question'].strip()
        answer, answer_source = historical.get(stem, ('', None))
        rows.append(dict(id=item['id'], question=stem, kp_ids=candidates.get(item['id'], candidates.get(q.get('id'), [])),
                         tags=q.get('tags', []), answer=answer, answer_source=answer_source,
                         reference_answer=str(q.get('correct_answer', ''))))
    selected, covered, tags = [], set(), set()
    while len(selected) < 20 and rows:
        best = max(rows, key=lambda q: (bool(q['answer']), len(set(q['kp_ids']) - covered),
                                       len(set(q['tags']) - tags)))
        rows.remove(best)
        selected.append(best)
        covered.update(best['kp_ids'])
        tags.update(best['tags'])
    if len(selected) != 20:
        raise ValueError('Need 20 distinct source short-answer questions')
    output = Path(args.out).expanduser()
    output.mkdir(parents=True, exist_ok=True)
    manifest = dict(schema_version=1, source_sha256=digest(source.read_text(encoding='utf-8')),
                    selected=len(selected), kp_coverage=len(covered), historical_answers=sum(bool(q['answer']) for q in selected),
                    questions=selected)
    write(output / 'benchmark-manifest.json', manifest)
    cards = []
    for i, q in enumerate(selected):
        labels=[kp_names.get(k, k) for k in q['kp_ids']]
        title=' / '.join(label.split(' | ')[0][:60] for label in labels[:2])
        if len(labels)>2:
            title+=' 等知识点'
        cards.append(f'<article data-id="{html.escape(q["id"], quote=True)}"><h2>{i+1}. '
                     f'{html.escape(title or "、".join(q["tags"]))}</h2>'
                     f'<p class="stem">{html.escape(q["question"])}</p><label>你的答案'
                     f'<textarea rows="5">{html.escape(q["answer"])}</textarea></label><fieldset><legend>你给几分？</legend>'
                     + ''.join(f'<label><input type="radio" name="q{i}" value="{b}">{label}</label>' for b, label in
                               [('PASS', '核心点答对（≥80%）'), ('NEEDS_WORK', '有关键遗漏（50%–80%）'),
                                ('FAIL', '核心判断错（<50%）'), ('UNSURE', '不确定，先跳过')]) + '</fieldset></article>')
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>你给打个分</title><style>body{font:17px/1.6 system-ui,sans-serif;max-width:840px;margin:auto;padding:24px;color:#18202a;background:#f7f8fa}article{background:white;border:1px solid #d7dee7;border-radius:12px;padding:20px;margin:20px 0}h1{font-size:30px}h2{font-size:18px}.stem{white-space:pre-wrap}textarea{display:block;box-sizing:border-box;width:100%;font:inherit;padding:12px}fieldset{border:0;padding:12px 0}fieldset label{display:block;margin:8px 0}button{font:inherit;padding:12px 20px;cursor:pointer}#status{font-weight:600}</style>
<h1>你给打个分</h1><p>先写你的答案，再按你对答案的判断选一档。这里不会显示 AI 的分数或参考答案。</p>
<p>没有可靠匹配的历史作答会留空，请你现写。20 道现写可能超过 15 分钟，可以分几次做；先点“保存”保留进度。网页不会自动保存或上传内容。</p>
<p><button id="save">保存答案与打分</button> <label>恢复上次进度 <input id="restore" type="file" accept="application/json"></label></p><p id="status"></p>
''' + ''.join(cards) + '''<button id="finish">保存答案与打分</button><script>
const articles=[...document.querySelectorAll('article')];
function save(){const answers=articles.map(a=>({id:a.dataset.id,answer:a.querySelector('textarea').value,human_band:a.querySelector('input:checked')?.value??null}));const blob=new Blob([JSON.stringify({schema_version:1,answers},null,2)],{type:'application/json'});const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='my-20-grades.json';link.click();URL.revokeObjectURL(link.href);document.querySelector('#status').textContent='已保存。把文件交回这张任务卡即可。';}
document.querySelector('#save').onclick=save;document.querySelector('#finish').onclick=save;
document.querySelector('#restore').onchange=async e=>{try{const data=JSON.parse(await e.target.files[0].text());if(!Array.isArray(data.answers)||new Set(data.answers.map(r=>r.id)).size!==articles.length)throw Error();for(const a of articles){const row=data.answers.find(r=>r.id===a.dataset.id);if(!row||typeof row.answer!=='string')throw Error();a.querySelector('textarea').value=row.answer;for(const radio of a.querySelectorAll('input'))radio.checked=radio.value===row.human_band;}document.querySelector('#status').textContent='已恢复进度。';}catch{document.querySelector('#status').textContent='无法恢复：请选用本清单保存的答案文件。';}};
</script></html>'''
    (output / 'manual-grade-benchmark.html').write_text(page, encoding='utf-8')
    print(json.dumps({k: v for k, v in manifest.items() if k != 'questions'}, ensure_ascii=False))


def compare(args):
    manifest = load(args.manifest)
    human_rows = load(args.human)['answers']
    ai_rows = load(args.ai)['grades']
    expected = {q['id'] for q in manifest['questions']}
    if len(expected) != 20:
        raise ValueError('Manifest must contain 20 unique questions')
    def index(rows):
        result = {q['id']: q for q in rows}
        if len(result) != len(rows) or set(result) != expected:
            raise ValueError('Missing, duplicate, or foreign question IDs')
        return result
    humans, grades = index(human_rows), index(ai_rows)
    report = []
    for q in manifest['questions']:
        key = q['id']
        human, ai = humans[key], grades[key]
        if ai['answer_sha256'] != digest(human['answer']):
            raise ValueError('AI score belongs to a different answer: ' + key)
        if human['human_band'] not in (*BANDS, 'UNSURE', None):
            raise ValueError('Unknown human grade: ' + key)
        if human['human_band'] in BANDS and not human['answer'].strip():
            raise ValueError('Comparable answers must not be blank: ' + key)
        if ai.get('review_decision') not in ('CONFIRM', 'REVISE') or not ai.get('rubric_fingerprint'):
            raise ValueError('Need a frozen rubric and completed review: ' + key)
        score, maximum = ai['score'], ai['max']
        if not (isinstance(score, (int, float)) and isinstance(maximum, (int, float)) and math.isfinite(score) and math.isfinite(maximum) and 0 <= score <= maximum and maximum > 0):
            raise ValueError('Invalid AI score: ' + key)
        band = 'PASS' if score / maximum >= .8 else 'NEEDS_WORK' if score / maximum >= .5 else 'FAIL'
        report.append(dict(id=key, human=human['human_band'], ai=band, score=score, max=maximum,
                           comparable=human['human_band'] in BANDS,
                           agree=human['human_band'] == band, reason=ai.get('reason', '')))
    comparable = [r for r in report if r['comparable']]
    agreed = sum(r['agree'] for r in comparable)
    result = dict(comparable=len(comparable), total=20, agreed=agreed,
                  agreement_percent=100 * agreed / len(comparable) if comparable else None,
                  target_met=len(comparable) == 20 and agreed >= 17, rows=report)
    write(args.out, result)
    print(json.dumps({k: v for k, v in result.items() if k != 'rows'}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('prepare')
    p.add_argument('--mapping', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--reviewed', help='Optional independently reviewed question-to-KP mapping')
    p.set_defaults(run=prepare)
    p = sub.add_parser('compare')
    for field in ('manifest', 'human', 'ai', 'out'):
        p.add_argument('--' + field, required=True)
    p.set_defaults(run=compare)
    args = parser.parse_args()
    args.run(args)
