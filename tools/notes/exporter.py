"""Read a ledger COPY, build immutable note data, then render local artifacts.

The functions between load_snapshot and write_export do no I/O and can be called
from a future desktop export action. They never call a model or a running server.
"""

import argparse
import hashlib
import html
import json
import random
import re
import sqlite3
from contextlib import closing
from pathlib import Path

from .mathml import MathError, mathml

WEEK = re.compile(r'【W([1-9][0-9]*)(?=[·・\s】])')
MATH = re.compile(r'\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\((.+?)\\\)|(?<![\\$])\$(?!\$)([^\n$]+?)(?<!\\)\$(?!\$)')


def math_matches(text):
    position = 0
    while (match := MATH.search(text, position)) is not None:
        # "$361→$731" is two currency amounts, not a formula named "361→".
        if match[4] and match[4][0].isdigit() and (
            text[match.end():match.end() + 1].isdigit() or
            (re.search('[\u3400-\u9fff]', match[4]) and '\\' not in match[4])
        ):
            position = match.start() + 1
            continue
        yield match
        position = match.end()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_snapshot(database, course):
    """Read only a consistent snapshot. The caller must first make a .backup."""
    path = Path(database).expanduser().resolve(strict=True)
    live = (Path.home() / 'Coach' / 'data').resolve()
    if path.is_relative_to(live):
        raise ValueError('请先用 sqlite3 .backup 创建拷贝；禁止直接读取生产 data 目录')
    with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as db:
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if 'events' not in tables:
            raise ValueError('缺少 events 账本')
        events = [dict(r) for r in db.execute('SELECT seq,type,payload FROM events WHERE course_id=? ORDER BY seq', (course,))]
        if not events:
            raise ValueError('课程没有账本事件；请明确指定正确的 --course')
        for event in events:
            event['payload'] = json.loads(event['payload'])
        # A revised migration history needs the domain reducer, not a guessed export.
        if any(e['type'] == 'legacy_source_revised' for e in events):
            raise ValueError('含 legacy_source_revised；需先接入领域历史修订归约器')
        points = [dict(r) for r in db.execute('SELECT * FROM outline_kp WHERE course_id=?', (course,))] if 'outline_kp' in tables else []
        for point in points:
            point['id'] = point['kp_id']
            point['anchors'] = json.loads(point.get('anchors') or '[]')
        questions = [dict(r) for r in db.execute('SELECT * FROM questions WHERE course_id=?', (course,))] if 'questions' in tables else []
        for question in questions:
            question['options'] = json.loads(question.get('options') or '[]')
        ids = {q['question_id'] for q in questions}
        def question_rows(table):
            return {r['question_id']: dict(r) for r in db.execute(f'SELECT * FROM {table}') if r['question_id'] in ids} if table in tables else {}
        keys = question_rows('answer_keys')
        pool = question_rows('pool_details')
        constructed = question_rows('constructed_details')
        for value in keys.values():
            value['key'] = json.loads(value['key_json'])
        caches = [dict(r) for r in db.execute("SELECT * FROM projection_cache WHERE course_id=? AND name='all'", (course,))] if 'projection_cache' in tables else []
        for cache in caches:
            cache['data'] = json.loads(cache.pop('blob'))
    return {'course': course, 'events': events, 'points': points, 'questions': {q['question_id']: q for q in questions},
            'keys': keys, 'pool': pool, 'constructed': constructed, 'caches': caches,
            'database_sha256': digest(path.read_bytes())}


def point_weeks(point):
    week = point.get('week')
    return [week] if isinstance(week, int) and not isinstance(week, bool) and week > 0 else sorted({int(w) for w in WEEK.findall(point.get('title', ''))})


def fold_outline(snapshot, supplement=None):
    """Replay outline events; keep multiple explicitly labelled week anchors."""
    points = {p['id']: dict(p) for p in sorted(snapshot['points'], key=lambda p: (p.get('ord', 0), p['id'])) if not p.get('retired')}
    for event in snapshot['events']:
        if event['type'] != 'outline_revised':
            continue
        payload = event['payload']
        for raw in payload.get('knowledgePoints', []) + payload.get('ops', []):
            kp = raw.get('kpId') or raw.get('id')
            if not kp:
                continue
            if raw.get('retired') or raw.get('op') == 'retire':
                points.pop(kp, None)
                continue
            previous = points.get(kp, {})
            merged = previous | raw | {'id': kp, 'candidate': False}
            merged['title'] = raw.get('title') or raw.get('name') or previous.get('title', '')
            # An explicit title change without week must replace an older explicit week.
            if ('title' in raw or 'name' in raw) and 'week' not in raw:
                merged.pop('week', None)
            points[kp] = merged
            if raw.get('op') == 'move':
                rows = list(points.items())
                row = next(item for item in rows if item[0] == kp)
                rows.remove(row)
                rows.insert(max(0, min(int(raw.get('index', 0)), len(rows))), row)
                points = dict(rows)
    if supplement:
        course = supplement.get('course', {})
        course_id = course.get('id') if isinstance(course, dict) else course
        if course_id and course_id != snapshot['course']:
            raise ValueError('补充提纲课程与账本不符')
        for raw in supplement.get('knowledgePoints', []):
            kp = raw.get('id')
            if kp and kp not in points and not raw.get('retired'):
                points[kp] = dict(raw, title=raw.get('title') or raw.get('name', ''), candidate=True)
    for point in points.values():
        point['weeks'] = point_weeks(point)
    return list(points.values())


def effective_attempts(snapshot):
    """All historical wrong attempts, not just the latest unresolved question.

    A later success does not erase an earlier mistake. Voids, grade reviews and
    unresolved legacy answers are respected. Unverified marks stay labelled.
    """
    attempts = {}
    for event in snapshot['events']:
        p, typ = event['payload'], event['type']
        aid = p.get('attemptId')
        if not aid:
            continue
        if typ in ('question_posed', 'legacy_attempt'):
            legacy = typ == 'legacy_attempt'
            attempt = dict(p, seq=event['seq'], legacy=legacy, submitted=False, voided=bool(p.get('reset')), grade=None)
            if legacy:
                result = p.get('result') or {}
                correct = result.get('is_correct')
                if result.get('final') is True and isinstance(correct, bool) and not result.get('needs_clarification') and not p.get('answer_unresolved'):
                    attempt['grade'] = {'score': int(correct), 'max': 1, 'status': 'HISTORICAL', 'feedback': result.get('feedback', '')}
                attempt['submitted'] = 'answer' in p and not p.get('answer_unresolved')
                attempt['kpIds'] = p.get('kpIds') or [p.get('kp_id')]
            attempts[aid] = attempt
        elif aid in attempts:
            a = attempts[aid]
            if typ == 'answer_submitted' and not a['submitted']:
                a['answer'] = p.get('answer', '')
                a['submitted'] = True
            elif typ == 'attempt_graded':
                a['grade'] = p
            elif typ == 'grade_reviewed':
                a['grade'] = (a['grade'] or {}) | p.get('revised', {})
                a['reviewed'] = p.get('decision') in ('CONFIRM', 'REVISE')
            elif typ == 'attempt_voided':
                a['voided'] = True
    return list(attempts.values())


def answer_text(answer, options, letter_map):
    if isinstance(answer, str):
        matches = [oid for oid, letter in letter_map.items() if letter == answer.strip().upper()]
        if len(matches) == 1:
            answer = matches[0]
    for option in options:
        oid = option.get('optionId') or option.get('id')
        if answer == oid:
            letter = letter_map.get(oid)
            return (f'{letter}. ' if letter else '') + option.get('text', '')
    if isinstance(answer, str) and answer:
        # An unresolvable ID must not masquerade as the answer text.
        return f'选项无法解析（{answer}）' if answer.startswith('option:') else answer
    return json.dumps(answer, ensure_ascii=False) if answer is not None else '未记录'


def mistake_record(a, snapshot):
    qid = a.get('questionId', '')
    stored = snapshot['questions'].get(qid, {})
    legacy_q = a.get('question') or {}
    options = a.get('options') or legacy_q.get('options') or stored.get('options') or []
    archived = a.get('legacy_explanation_archive') or {}
    letter_map = a.get('letterMap') or archived.get('legacyLetterMap') or {}
    options = sorted(options, key=lambda o: letter_map.get(o.get('optionId') or o.get('id'), ''))
    key = snapshot['keys'].get(qid, {}).get('key', {})
    correct = key.get('correctOptionId') or legacy_q.get('correctOptionId')
    reference = snapshot['constructed'].get(qid, {}).get('reference_answer')
    status = a.get('keyState') or stored.get('key_state', 'UNREVIEWED')
    is_legacy = a['legacy']
    feedback = (a['grade'] or {}).get('feedback')
    pool = snapshot['pool'].get(qid, {})
    verified_explanation = pool.get('explanation') if stored.get('key_state') == 'VERIFIED' and pool.get('generator') and pool.get('reviewer') else None
    explanation = feedback or '账本没有记录具体错因；需复核，不推测。'
    if verified_explanation and verified_explanation not in explanation:
        explanation += '\n\n题库已复核讲解（用于解释题目，不推测个人思路）：\n' + verified_explanation
    # Legacy letter-based explanations are only intelligible with the old map.
    if is_legacy and archived.get('explanation_references_letters') and not archived.get('letter_map_complete'):
        explanation = '历史讲解引用选项字母，但原始字母映射不完整；讲解暂不导出，需复核。'
    return {'attempt_id': a['attemptId'], 'seq': a['seq'], 'kp_ids': a.get('kpIds', []),
            'stem': a.get('stem') or legacy_q.get('prompt') or stored.get('stem_md') or '题干未记录',
            'answer': answer_text(a.get('answer'), options, letter_map),
            'correct': answer_text(correct, options, letter_map) if correct else reference or '账本未保存参考答案，需复核',
            'options': [answer_text(o.get('optionId') or o.get('id'), options, letter_map) for o in options],
            'explanation': explanation,
            'provenance': ('历史迁移记录；' if is_legacy else '') + f'答案键 {status}；评分 {a["grade"].get("status", "未记录")}' + ('；已人工复核' if a.get('reviewed') else ''),
            'score': a['grade']['score'], 'max': a['grade']['max']}


def mistakes(snapshot):
    result = []
    for a in effective_attempts(snapshot):
        g = a['grade'] or {}
        if a['submitted'] and not a['voided'] and isinstance(g.get('score'), (int, float)) and isinstance(g.get('max'), (int, float)) and 0 <= g['score'] < g['max']:
            result.append(mistake_record(a, snapshot))
    return result


def mastery_rows(snapshot, points):
    last_seq = max(e['seq'] for e in snapshot['events'])
    cache = next((c for c in snapshot['caches'] if c['version'] == 4 and c['last_seq'] == last_seq and c['data'].get('version') == 4 and c['data'].get('lastSeq') == last_seq), None)
    result = {}
    for p in points:
        if p.get('candidate'):
            state = '候选提纲尚未安装；掌握度未验证'
        elif not cache:
            state = '掌握度未验证（投影缺失、版本不支持或序号落后）'
        else:
            m = cache['data'].get('mastery', {}).get(p['id'], {})
            state = '已巩固' if m.get('solid') else '已通过' if m.get('passed') else '尚无通过记录'
            if m.get('passedBy') == 'LEGACY':
                state += '（历史迁移，不代表新验收）'
            if m.get('needsRepair'):
                state += '；需要修复'
            if cache['data'].get('requiresServerSnapshot') or cache['data'].get('unsupportedTypes'):
                reasons = '、'.join(cache['data'].get('unsupportedTypes') or ['需服务端快照'])
                state = f'缓存记录：{state}；未验证（{reasons}）'
        result[p['id']] = state
    return result


def material_sections(materials):
    """Only explicit Week N / Week N–M heading labels bind content to weeks."""
    sections = {week: [] for week in range(1, 12)}
    for name, text in materials:
        stack = []
        current = None
        for line in text.splitlines():
            heading = re.match(r'^(#{1,6})\s+(.+)', line)
            if heading:
                level, title = len(heading[1]), heading[2]
                while stack and stack[-1][0] >= level:
                    stack.pop()
                weeks = set()
                for match in re.finditer(r'Week\s*([1-9][0-9]*)(?:\s*[–—-]\s*([1-9][0-9]*))?', title, re.I):
                    first, last = int(match[1]), int(match[2] or match[1])
                    if 1 <= first <= last <= 11:
                        weeks.update(range(first, last + 1))
                weeks = weeks or (stack[-1][1] if stack else set())
                stack.append((level, weeks))
                current = {'source': name, 'heading': title, 'lines': []}
                for week in weeks:
                    sections[week].append(current)
            elif current is not None:
                current['lines'].append(line)
    return sections


def formulas(text):
    values = [next(g for g in match.groups() if g is not None).strip() for match in math_matches(text)]
    # Existing revision notes also contain Unicode equations without TeX fences.
    for line in text.splitlines():
        if '=' in line and not list(math_matches(line)) and not line.lstrip().startswith('|'):
            line = re.sub(r'^[-*\d.\s]+', '', line).replace('**', '').strip()
            # Split prose clauses so a long topic title cannot overflow a printed page.
            # Full original qualifications remain in the key point/material section.
            for clause in re.split('[；，、｜]', line):
                if '=' not in clause:
                    continue
                prefix = clause[:clause.index('=')]
                chinese = list(re.finditer('[\u3400-\u9fff：】]', prefix))
                if chinese:
                    clause = clause[chinese[-1].end():]
                values.append(clause.strip())
    return list(dict.fromkeys(values))


def build_notes(snapshot, supplement=None, materials=(), weeks=11):
    """Pure core: explicit course data in, serializable weekly notes out."""
    if not 1 <= weeks <= 52:
        raise ValueError('周数必须在 1–52 内')
    points = fold_outline(snapshot, supplement)
    wrong = mistakes(snapshot)
    mastery = mastery_rows(snapshot, points)
    sections = material_sections(materials)
    assigned_ids = {p['id'] for p in points if any(1 <= w <= weeks for w in p['weeks'])}
    unassigned = sum(not assigned_ids.intersection(r['kp_ids']) for r in wrong)
    result = []
    for week in range(1, weeks + 1):
        selected = [p for p in points if week in p['weeks']]
        ids = {p['id'] for p in selected}
        records = [r for r in wrong if ids.intersection(r['kp_ids'])]
        source_sections = sections.get(week, [])
        formula_text = '\n'.join([p['title'] for p in selected] + ['\n'.join(s['lines']) for s in source_sections] + [r['explanation'] for r in records])
        result.append({'week': week, 'course': snapshot['course'], 'last_seq': max(e['seq'] for e in snapshot['events']),
                       'points': selected, 'mistakes': records, 'mastery': {p['id']: mastery[p['id']] for p in selected},
                       'formulas': formulas(formula_text), 'materials': source_sections,
                       'week_note': (supplement or {}).get(f'week{week}', {}).get('reason', ''),
                       'unassigned_mistakes': unassigned})
    return result


def render_markdown(note):
    lines = [f'# 第 {note["week"]} 周 · 我的复习笔记', '', f'账本序号：{note["last_seq"]}。本周有效历史错题：{len(note["mistakes"])} 条。',
             '错题按有效作答逐次保留，后续答对不删除旧错题；跨周知识点会出现在各所属周，不能把周数相加当作全课程总数。', '', '## 要点与掌握度', '']
    if note['week_note']:
        lines += [note['week_note'], '']
    if not note['points']:
        lines += ['本周没有已安装的周次知识点；不据此推断已学或已掌握。', '']
    for p in note['points']:
        lines += [f'### {p["title"]}', '', f'掌握度：{note["mastery"][p["id"]]}。', '']
        anchors = p.get('anchors') or []
        for anchor in anchors:
            lines += [f'- 来源：{anchor.get("docId", "未记录")}；PDF 页 {anchor.get("pdfPage", "未记录")}；书页 {anchor.get("bookPage", "未记录")}。']
        lines.append('')
    lines += ['## 公式', '']
    if not note['formulas']:
        lines += ['本周来源未提取到明确公式；不自动编造。', '']
    else:
        lines += ['公式为来源中的显式数学标记或带等号条目；完整适用条件见要点和材料原文。', '']
    for formula in note['formulas']:
        lines += ['$$', formula, '$$', '']
    if note['materials']:
        lines += ['## 课程复习材料摘录', '', '以下按材料标题的显式 Week 标签归周；多周章节共享同一摘录。', '']
        for section in note['materials']:
            lines += [f'### {section["heading"]}', '', f'来源：{section["source"]}', ''] + section['lines'] + ['']
    lines += ['## 我的错题', '']
    if not note['mistakes']:
        lines += ['账本未记录本周有效错题；这不表示本周已经掌握。', '']
    for index, record in enumerate(note['mistakes'], 1):
        lines += [f'### 错题 {index}', '', f'记录：{record["attempt_id"]}；序号 {record["seq"]}；得分 {record["score"]}/{record["max"]}。', '',
                  record['provenance'], '', '题干：', '', record['stem'], '']
        lines += [f'- {option}' for option in record['options']]
        lines += ['', '我的答案：' + record['answer'], '', '正确或参考答案：' + record['correct'], '', '账本记录的错因／讲解：', '', record['explanation'], '']
    if note['unassigned_mistakes']:
        lines += [f'提醒：全课程另有 {note["unassigned_mistakes"]} 条错题缺少可归周知识点，见 audit.json，不能归作本周。', '']
    return '\n'.join(lines)


def render_html(markdown, title):
    """Safe small Markdown renderer. Raw HTML and network assets stay inert."""
    warnings = []
    def inline(text):
        parts, previous = [], 0
        for match in math_matches(text):
            parts.append(html.escape(text[previous:match.start()]))
            tex = next(g for g in match.groups() if g is not None)
            try:
                parts.append(mathml(tex, display=match[1] is not None or match[2] is not None))
            except (MathError, RecursionError) as error:
                warnings.append(str(error))
                parts.append('<math xmlns="http://www.w3.org/1998/Math/MathML"><merror><mtext>' + html.escape(f'{tex} [公式需复核：{error}]') + '</mtext></merror></math>')
            previous = match.end()
        parts.append(html.escape(text[previous:]))
        return re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', ''.join(parts))
    # Protect multiline math before treating ordinary lines as block elements.
    blocks, previous = [], 0
    for match in re.finditer(r'(?m)^\$\$\s*\n[\s\S]*?\n\$\$\s*$', markdown):
        blocks.extend(markdown[previous:match.start()].splitlines())
        blocks.append(match[0])
        previous = match.end()
    blocks.extend(markdown[previous:].splitlines())
    output, in_list, in_table = [], False, False
    for line in blocks:
        bullet = re.match(r'^\s*[-*]\s+(.+)', line)
        table = line.strip().startswith('|') and line.strip().endswith('|')
        if in_list and not bullet:
            output.append('</ul>'); in_list = False
        if in_table and not table:
            output.append('</tbody></table>'); in_table = False
        heading = re.match(r'^(#{1,6})\s+(.+)', line)
        if not line.strip():
            continue
        if heading:
            level = len(heading[1])
            anchor = {'公式': 'formulas', '我的错题': 'mistakes'}.get(heading[2])
            attribute = f' id="{anchor}"' if anchor else ''
            output.append(f'<h{level}{attribute}>{inline(heading[2])}</h{level}>')
        elif bullet:
            if not in_list:
                output.append('<ul>'); in_list = True
            output.append('<li>' + inline(bullet[1]) + '</li>')
        elif table:
            cells = line.strip().strip('|').split('|')
            if all(re.fullmatch(r'\s*:?-+:?\s*', c) for c in cells):
                continue
            if not in_table:
                output.append('<table><tbody>'); in_table = True
            output.append('<tr>' + ''.join('<td>' + inline(c.strip()) + '</td>' for c in cells) + '</tr>')
        else:
            output.append('<div class="equation">' + inline(line) + '</div>' if line.startswith('$$') else '<p>' + inline(line) + '</p>')
    if in_list: output.append('</ul>')
    if in_table: output.append('</tbody></table>')
    style = '''body{max-width:960px;margin:40px auto;padding:0 28px;color:#172b35;background:#fff;font:17px/1.75 system-ui,sans-serif}h1{font-size:30px;border-bottom:3px solid #247d82;padding-bottom:14px}h2{margin-top:2em;color:#17666c}h3{font-size:19px;margin-top:1.5em}p,li{overflow-wrap:anywhere;white-space:pre-wrap}table{border-collapse:collapse;width:100%;font-size:15px}td{border:1px solid #cddbdd;padding:8px}math{font-size:1.12em;max-width:100%;overflow-wrap:anywhere}math[display=block]{margin:20px 0;overflow-x:auto;padding:8px}.equation{overflow-x:auto}merror{color:#a32121}header{color:#52636c;font-size:14px}@page{size:A4;margin:16mm}@media print{body{margin:0;padding:0;font-size:10.5pt;max-width:none}h1{font-size:20pt}h2{font-size:16pt;break-after:avoid}h3{font-size:12pt;break-after:avoid}li,tr,math{break-inside:avoid}.equation,math[display=block]{overflow:visible}table{font-size:9pt}}'''
    document = '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; img-src \'none\'; base-uri \'none\'; form-action \'none\'"><title>' + html.escape(title) + '</title><style>' + style + '</style></head><body><header>Coach · 本地复习笔记 · 离线 MathML · 可打印</header>' + '\n'.join(output) + '</body></html>\n'
    return document, warnings


def write_export(notes, output, snapshot, seed=1280, inputs=()):
    directory = Path(output).expanduser().resolve()
    if any((p / '.git').exists() for p in (directory, *directory.parents)):
        raise ValueError('导出目录必须在 Git 仓库之外，避免学习记录进入仓库')
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    def save(name, content):
        path = directory / name
        if path.is_symlink():
            raise ValueError('拒绝覆盖符号链接')
        path.write_text(content, encoding='utf-8')
        path.chmod(0o600)
        return digest(content.encode('utf-8'))
    manifest, warnings = {}, []
    for note in notes:
        stem = f'week-{note["week"]:02d}'
        md = render_markdown(note)
        page, errors = render_html(md, f'第 {note["week"]} 周复习笔记')
        warnings += [{'week': note['week'], 'error': error} for error in errors]
        manifest[stem + '.md'] = save(stem + '.md', md)
        manifest[stem + '.html'] = save(stem + '.html', page)
    all_wrong = mistakes(snapshot)
    assigned = {r['attempt_id'] for n in notes for r in n['mistakes']}
    audit = {'database_sha256': snapshot['database_sha256'], 'last_seq': max(e['seq'] for e in snapshot['events']),
             'count_policy': 'all valid historically wrong attempts; voids excluded; latest reviewed grade wins; later success retained',
             'weekly_counts': {str(n['week']): len(n['mistakes']) for n in notes},
             'sample_seed': seed, 'sample_weeks': sorted(random.Random(seed).sample([n['week'] for n in notes], min(3, len(notes)))),
             'unique_mistakes': len(all_wrong), 'unassigned_attempt_ids': [r['attempt_id'] for r in all_wrong if r['attempt_id'] not in assigned],
             'formula_warnings': warnings, 'inputs': list(inputs), 'files_sha256': manifest}
    save('audit.json', json.dumps(audit, ensure_ascii=False, indent=2) + '\n')
    index = '# 每周复习笔记\n\n' + '\n'.join(f'- [第 {n["week"]} 周](week-{n["week"]:02d}.html) · {len(n["mistakes"])} 条有效错题' for n in notes) + '\n\n原始数据和导出均为私人学习资料，请保留在本机。\n'
    save('index.md', index)
    home, _ = render_html('# 每周复习笔记\n\n原始数据和导出均为私人学习资料，请保留在本机。', '每周复习笔记')
    links = '<ul>' + ''.join(f'<li><a href="week-{n["week"]:02d}.html">第 {n["week"]} 周</a> · {len(n["mistakes"])} 条有效错题</li>' for n in notes) + '</ul>'
    save('index.html', home.replace('</body>', links + '</body>'))
    return audit


def main(argv=None):
    parser = argparse.ArgumentParser(description='从 SQLite 拷贝导出每周私人复习笔记；不连接应用服务')
    parser.add_argument('--database', required=True, type=Path, help='先用 sqlite3 .backup 创建的拷贝')
    parser.add_argument('--course', required=True)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--outline', type=Path, help='可选本地候选提纲 JSON；不会写入账本')
    parser.add_argument('--material', action='append', default=[], type=Path, help='带显式 Week 标题的本地 Markdown，可重复')
    parser.add_argument('--weeks', type=int, default=11)
    parser.add_argument('--audit-seed', type=int, default=1280)
    args = parser.parse_args(argv)
    try:
        snapshot = load_snapshot(args.database, args.course)
        supplement = json.loads(args.outline.read_text(encoding='utf-8')) if args.outline else None
        materials = [(p.name, p.read_text(encoding='utf-8')) for p in args.material]
        notes = build_notes(snapshot, supplement, materials, args.weeks)
        inputs = [{'name': p.name, 'sha256': digest(p.read_bytes())} for p in ([args.outline] if args.outline else []) + args.material]
        audit = write_export(notes, args.out, snapshot, args.audit_seed, inputs)
    except (ValueError, OSError, sqlite3.Error) as error:
        parser.exit(2, f'导出停止：{error}\n')
    print(json.dumps({'weeks': len(notes), 'markdown': len(notes), 'html': len(notes), 'unique_mistakes': audit['unique_mistakes'], 'formula_warnings': len(audit['formula_warnings']), 'sample_weeks': audit['sample_weeks']}, ensure_ascii=False))
    return 2 if audit['formula_warnings'] else 0
