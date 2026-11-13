#!/usr/bin/env python3
"""Validate generated papers/packs and compare them with private exam text."""
import argparse
import json
import re
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

WORD = re.compile(r"[A-Za-z]+")
HAN_RUN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\U00020000-\U0002fa1f\U00030000-\U000323af]+")
TEXT_SUFFIXES = {'.md', '.txt', '.json', '.py', '.svg'}


def grams(text):
    words = [word.lower() for word in WORD.findall(text)]
    sequences = {tuple(words[i:i + 8]) for i in range(len(words) - 7)}
    for run in HAN_RUN.findall(text):
        sequences.update(run[i:i + 15] for i in range(len(run) - 14))
    return sequences


def exam_index(directory):
    if not directory.is_dir():
        raise ValueError('exam directory does not exist or is not a directory')
    paths = sorted(p for p in directory.rglob('*') if p.is_file() and p.suffix.lower() in {'.md', '.txt'})
    if not paths:
        raise ValueError('exam directory contains no .md or .txt files')
    bank = set()
    for path in paths:
        bank.update(grams(path.read_text(encoding='utf-8')))
    if not bank:
        raise ValueError('exam directory contains no eight-word or 15-Han-character sequences')
    return bank


def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    try:
        result = Decimal(str(value))
        return result if result.is_finite() else None
    except InvalidOperation:
        return None


def validate(data):
    errors = []

    def field(obj, key, expected, where):
        if key not in obj:
            errors.append(f'{where}.{key}: missing')
            return None
        val = obj[key]
        if not isinstance(val, expected) or isinstance(val, bool) and expected in (int, (int, float)):
            errors.append(f'{where}.{key}: wrong type')
            return None
        if expected is str and not val.strip():
            errors.append(f'{where}.{key}: empty')
        return val

    def marks(obj, where):
        val = field(obj, 'marks', (int, float), where)
        if val is not None:
            val = number(val)
            if val is None or val < 0:
                errors.append(f'{where}.marks: must be a nonnegative finite number')
                return None
        return val

    def rubric(obj, where):
        rules = field(obj, 'rubric', list, where)
        if rules is None:
            return
        if not rules:
            errors.append(f'{where}.rubric: empty')
        for j, rule in enumerate(rules):
            loc = f'{where}.rubric[{j}]'
            if not isinstance(rule, dict):
                errors.append(f'{loc}: expected object')
                continue
            field(rule, 'criterion', str, loc)
            marks(rule, loc)
            field(rule, 'look_for', str, loc)

    if not isinstance(data, dict):
        return ['document: expected object']
    for key in ('id', 'kind', 'course', 'variant_of'):
        field(data, key, str, 'document')
    minutes = field(data, 'minutes', (int, float), 'document')
    if minutes is not None and (number(minutes) is None or number(minutes) <= 0):
        errors.append('document.minutes: must be positive and finite')
    kind = data.get('kind')
    if kind not in ('paper', 'pack'):
        errors.append('document.kind: expected paper or pack')
    total = None
    if kind == 'paper' or 'total_marks' in data:
        raw = field(data, 'total_marks', (int, float), 'document')
        if raw is not None:
            total = number(raw)
            if total is None or total <= 0:
                errors.append('document.total_marks: must be positive and finite')
                total = None
    sections = field(data, 'sections', list, 'document')
    if sections is None:
        return errors
    if not sections:
        errors.append('document.sections: empty')
    if kind == 'pack' and len(sections) != 1:
        errors.append('document.sections: pack must have one section')
    seen = set()
    paper_marks = Decimal(0)
    computable = True
    for si, section in enumerate(sections):
        loc = f'sections[{si}]'
        if not isinstance(section, dict):
            errors.append(f'{loc}: expected object')
            computable = False
            continue
        field(section, 'title', str, loc)
        field(section, 'instructions', str, loc)
        if 'choose' not in section:
            errors.append(f'{loc}.choose: missing')
        choose = section.get('choose')
        items = field(section, 'items', list, loc)
        if items is None:
            computable = False
            continue
        if not items:
            errors.append(f'{loc}.items: empty')
        if choose is not None and (type(choose) is not int or not 1 <= choose <= len(items)):
            errors.append(f'{loc}.choose: invalid count')
            computable = False
        section_marks = []
        for ii, item in enumerate(items):
            where = f'{loc}.items[{ii}]'
            if not isinstance(item, dict):
                errors.append(f'{where}: expected object')
                computable = False
                continue
            iid = field(item, 'id', str, where)
            if iid is not None:
                if iid in seen:
                    errors.append(f'{where}.id: duplicate')
                seen.add(iid)
            typ = field(item, 'type', str, where)
            week = field(item, 'kp_week', int, where)
            if week is not None and week <= 0:
                errors.append(f'{where}.kp_week: must be positive')
            for key in ('kp', 'stem', 'source'):
                field(item, key, str, where)
            score = marks(item, where)
            if score is None:
                computable = False
            else:
                section_marks.append(score)
            if typ == 'mcq':
                options = field(item, 'options', list, where)
                if options is not None and (len(options) < 2 or any(not isinstance(x, str) or not x.strip() for x in options)):
                    errors.append(f'{where}.options: expected at least two nonempty strings')
                answer = field(item, 'answer', int, where)
                if answer is not None and options is not None and not 0 <= answer < len(options):
                    errors.append(f'{where}.answer: index out of range')
                field(item, 'explanation', str, where)
            elif typ == 'essay':
                target = field(item, 'word_target', int, where)
                if target is not None and target <= 0:
                    errors.append(f'{where}.word_target: must be positive')
                field(item, 'model_answer', str, where)
                rubric(item, where)
            elif typ == 'analytical':
                if 'figure_svg' in item:
                    field(item, 'figure_svg', str, where)
                parts = field(item, 'parts', list, where)
                if parts is not None:
                    if not parts:
                        errors.append(f'{where}.parts: empty')
                    part_marks = []
                    for pi, part in enumerate(parts):
                        ploc = f'{where}.parts[{pi}]'
                        if not isinstance(part, dict):
                            errors.append(f'{ploc}: expected object')
                            continue
                        for key in ('label', 'prompt', 'answer'):
                            field(part, key, str, ploc)
                        pmark = marks(part, ploc)
                        if pmark is not None:
                            part_marks.append(pmark)
                        rubric(part, ploc)
                        if part.get('tolerance') is not None:
                            tol = part['tolerance']
                            if isinstance(tol, bool) or not isinstance(tol, (int, float)) or number(tol) is None or number(tol) < 0:
                                errors.append(f'{ploc}.tolerance: expected nonnegative finite number')
                            if number(part.get('answer')) is None:
                                errors.append(f'{ploc}.answer: expected finite numeric answer for tolerance')
                    if score is not None and len(part_marks) == len(parts) and sum(part_marks) != score:
                        errors.append(f'{where}.parts: marks do not sum to item marks')
            elif typ is not None:
                errors.append(f'{where}.type: unsupported')
        if len(section_marks) != len(items):
            computable = False
        elif choose is None:
            paper_marks += sum(section_marks)
        elif type(choose) is int and 1 <= choose <= len(items):
            if choose < len(items) and len(set(section_marks)) > 1:
                errors.append(f'{loc}: selected questions have unequal marks; total is ambiguous')
                computable = False
            else:
                paper_marks += sum(section_marks[:choose])
    if total is not None and computable and paper_marks != total:
        errors.append('document.total_marks: does not match selected question marks')
    return errors


def string_values(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from string_values(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from string_values(item)


def check_file(path, bank):
    if path.suffix.lower() not in TEXT_SUFFIXES:
        return 'not checked (unsupported file type)', []
    try:
        text = path.read_text(encoding='utf-8')
        errors = []
        scope = 'overlap only'
        if path.suffix.lower() == '.json':
            try:
                data = json.loads(text)
            except (ValueError, RecursionError):
                return 'invalid JSON', ['invalid JSON']
            if isinstance(data, dict) and (data.get('kind') in ('paper', 'pack') or 'sections' in data or 'kind' in data):
                scope = 'format + overlap'
                errors.extend(validate(data))
            contents = string_values(data)
        else:
            contents = [text]
        hits = set()
        for content in contents:
            hits.update(grams(content) & bank)
        if hits:
            english = sum(isinstance(hit, tuple) for hit in hits)
            chinese = len(hits) - english
            if english:
                errors.append(f'overlap: {english} distinct eight-word matches')
            if chinese:
                errors.append(f'overlap: {chinese} distinct 15-Han-character matches')
        return scope, errors
    except (OSError, UnicodeError) as exc:
        return 'not checked (read error)', [f'read error: {type(exc).__name__}']


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path, help='generated file or directory')
    parser.add_argument('--exam-dir', type=Path, required=True, help='private directory containing exam .md/.txt files')
    parser.add_argument('--report', type=Path, help='write a local Markdown report')
    args = parser.parse_args(argv)
    try:
        bank = exam_index(args.exam_dir)
        if args.input.is_file():
            files = [args.input]
            root = args.input.parent
        elif args.input.is_dir():
            root = args.input
            files = sorted(p for p in root.rglob('*') if p.is_file())
        else:
            raise ValueError('input does not exist')
        if not files:
            raise ValueError('input contains no files')
        rows = []
        failures = 0
        unchecked = 0
        for path in files:
            scope, errors = check_file(path, bank)
            if scope.startswith('not checked'):
                unchecked += 1
            if errors:
                failures += 1
            status = f'{len(errors)} problem(s)' if errors else ('not checked' if scope.startswith('not checked') else 'pass')
            rows.append((str(path.relative_to(root)), scope, status, errors))
        lines = ['# Content validation', '', 'Exam index: all UTF-8 .md/.txt files under the supplied directory; matches are case-insensitive sequences of eight English letter-only words or 15 consecutive Han characters. No exam text is included.', '', 'Format validation applies to paper/pack JSON; other supported text is checked for overlap only. Unsupported files are not checked.', '', '| File | Scope | Result |', '| --- | --- | --- |']
        for name, scope, status, errors in rows:
            safe_name = name.replace("|", "\\|")
            lines.append(f'| {safe_name} | {scope} | {status} |')
        lines += ['', f'Files: {len(rows)}; with problems: {failures}; not checked: {unchecked}.', '', '## Problem details', '']
        for name, scope, status, errors in rows:
            if errors:
                lines.append(f'- `{name}`: ' + '; '.join(errors))
        report = '\n'.join(lines) + '\n'
        if args.report:
            args.report.write_text(report, encoding='utf-8')
        else:
            print(report, end='')
        return 1 if failures or unchecked else 0
    except (OSError, UnicodeError, ValueError) as exc:
        print(f'validation could not run: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
