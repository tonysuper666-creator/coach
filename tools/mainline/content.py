#!/usr/bin/env python3
"""Private, offline mainline authoring tools. stdout contains counts/hashes, never course prose."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def facts_from(path):
    text = Path(path).read_text(encoding='utf-8')
    match = re.search(r'```jsonl\s*\n(.*?)```', text, re.S)
    if not match:
        raise ValueError('facts: missing jsonl block')
    rows = [json.loads(line) for line in match[1].splitlines() if line.strip()]
    facts = {f['fact_id']: f for f in rows}
    if not facts or len(facts) != len(rows):
        raise ValueError('facts: empty or duplicate ids')
    return facts


def markdown_notes(text):
    week = re.search(r'<!--\s*coach-week:\s*(\d+)\s*-->', text)
    if not week:
        raise ValueError('notes: missing coach-week marker')
    markers = list(re.finditer(r'<!--\s*coach-section\s+(\{.*?\})\s*-->', text, re.S))
    sections = []
    for i, marker in enumerate(markers):
        meta = json.loads(marker[1])
        prose = text[marker.end():markers[i+1].start() if i+1 < len(markers) else len(text)].strip()
        heading, sep, body = prose.partition('\n')
        if not heading.startswith('## ') or not sep:
            raise ValueError('notes: each section needs a ## heading and body')
        sections.append(dict(meta, title=heading[3:].strip(), body=body.strip()))
    return dict(schema_version=1, week=int(week[1]), sections=sections)


def validate_notes(notes, facts, week=None):
    if notes.get('schema_version') != 1 or type(notes.get('week')) is not int or notes['week'] < 1:
        raise ValueError('notes: invalid version/week')
    if week is not None and notes['week'] != week:
        raise ValueError('notes: wrong week')
    sections = notes.get('sections', [])
    if not sections:
        raise ValueError('notes: empty sections')
    seen, covered = set(), set()
    for s in sections:
        sid = s.get('section_id', '')
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,95}', sid) or sid in seen:
            raise ValueError('notes: invalid/duplicate section_id')
        seen.add(sid)
        if not isinstance(s.get('title'), str) or not s['title'].strip() or not isinstance(s.get('body'), str) or not s['body'].strip():
            raise ValueError('notes: empty title/body')
        links = s.get('fact_ids', [])
        if not links or any(not isinstance(x, str) for x in links) or len(set(links)) != len(links) or set(links) - facts.keys():
            raise ValueError('notes: unknown/empty/duplicate fact_ids')
        sources = s.get('sources', [])
        if any(not isinstance(x, str) for x in sources) or set(sources) != {facts[f]['source'] for f in links}:
            raise ValueError('notes: sources differ from linked facts')
        covered.update(links)
    if covered != facts.keys():
        raise ValueError('notes: incomplete fact coverage')
    return dict(sections=len(sections), facts=len(covered))


def emphasis_metadata(data, facts, week, inline=''):
    """Provisional normalized interface; real authoring format awaits materials."""
    if type(data.get('schema_version')) is not int or data['schema_version'] != 1 or type(data.get('week')) is not int or data['week'] != week:
        raise ValueError('emphasis: invalid version/week')
    marks = data.get('marks', [])
    if not isinstance(marks, list):
        raise ValueError('emphasis: invalid marks')
    marks = copy.deepcopy(marks)
    pattern = r'<!--\s*coach-emphasis\s+(\{.*?\})\s*-->\s*【课上强调\s+(\d+(?::\d+){1,2})】'
    matches = list(re.finditer(pattern, inline, re.S))
    if inline.count('【课上强调') != len(matches):
        raise ValueError('emphasis: inline marker lacks explicit recording/fact context')
    for match in matches:
        marks.append(dict(json.loads(match[1]), timestamp=match[2]))
    unique = {}
    for mark in marks:
        if mark.get('fact_id') not in facts or mark.get('reviewed') is not True:
            raise ValueError('emphasis: unknown fact or pending independent verification')
        if mark.get('recording_kind') not in ['lecture', 'tutorial'] or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,95}', mark.get('recording_id', '')):
            raise ValueError('emphasis: invalid recording context')
        stamp = mark.get('timestamp', '')
        if not re.fullmatch(r'(?:[0-9]{1,9}:[0-5][0-9]|[0-9]{1,9}:[0-5][0-9]:[0-5][0-9])', stamp):
            raise ValueError('emphasis: missing/invalid timestamp')
        parts = [int(p) for p in stamp.split(':')]
        seconds = parts[-1] + parts[-2]*60 + (parts[-3]*3600 if len(parts)==3 else 0)
        summary = mark.get('summary')
        if not isinstance(summary, str) or not summary.strip():
            raise ValueError('emphasis: missing summary')
        normalized = {k:mark[k] for k in ['fact_id','recording_id','recording_kind']}
        normalized.update(timestamp=f'{seconds//3600:02}:{seconds//60%60:02}:{seconds%60:02}', summary=summary.strip(), reviewed=True)
        key = (mark['fact_id'],mark['recording_id'],mark['recording_kind'],seconds)
        if key in unique and unique[key] != normalized:
            raise ValueError('emphasis: conflicting duplicate marker')
        unique[key] = normalized
    return dict(schema_version=1, week=week, marks=list(unique.values()))


def frozen_pack(pack):
    frozen = copy.deepcopy(pack)
    for section in frozen['sections']:
        for item in section['items']:
            if not isinstance(item.get('explanation'), str):
                raise ValueError('pack: explanation must be a string')
            item.pop('explanation')
    return frozen


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical(value):
    # Keep JSON booleans distinct from numeric answer indexes.
    return json.dumps(value, sort_keys=True, ensure_ascii=False)


def correction_snapshot(baseline, corrected, ledger_path):
    """Read-only check of an independently authorized correction snapshot.

    Authorization references are supplied by the caller, not verified approval
    signatures. This tool checks the exact scope/hashes; QA owns their validity.
    """
    baseline, corrected = Path(baseline).resolve(), Path(corrected).resolve()
    if baseline == corrected or baseline in corrected.parents or corrected in baseline.parents:
        raise ValueError('corrections: baseline and working snapshot must be separate')
    ledger_path = Path(ledger_path)
    if ledger_path.is_symlink():
        raise ValueError('corrections: symlink ledger is not accepted')
    ledger = read_json(ledger_path)
    if not isinstance(ledger, dict) or not isinstance(ledger.get('authorization'), dict):
        raise ValueError('corrections: invalid authorization object')
    auth = ledger.get('authorization', {})
    if type(ledger.get('schema_version')) is not int or ledger['schema_version'] != 1 or any(
            not isinstance(auth.get(k), str) or not auth[k].strip()
            for k in ['issue', 'qa', 'cross_review']):
        raise ValueError('corrections: missing independent authorization references')
    rows = ledger.get('files')
    if not isinstance(rows, list) or not rows:
        raise ValueError('corrections: empty ledger')
    authorized = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('corrections: invalid file entry')
        name = row['file']
        if not isinstance(name, str) or not re.fullmatch(r'W[0-9][0-9]\.json', name) or name in authorized:
            raise ValueError('corrections: invalid/duplicate file')
        authorized[name] = row
    old = {p.name: p for p in baseline.glob('W[0-9][0-9].json')}
    new = {p.name: p for p in corrected.glob('W[0-9][0-9].json')}
    if not old or old.keys() != new.keys() or authorized.keys() - old.keys():
        raise ValueError('corrections: file set must stay unchanged')
    snapshots, changes = [], 0
    permitted = {'stem', 'options', 'answer', 'key_state', 'source', 'sources', 'fact_ids'}
    for name in sorted(old):
        if old[name].is_symlink() or new[name].is_symlink():
            raise ValueError('corrections: symlinks are not accepted')
        before, after = digest(old[name]), digest(new[name])
        original, actual = read_json(old[name]), read_json(new[name])
        expected = copy.deepcopy(original)
        row = authorized.get(name)
        if row is not None:
            if row.get('original_sha256') != before or row.get('corrected_sha256') != after:
                raise ValueError('corrections: snapshot hash mismatch')
            edits = row.get('changes')
            if not isinstance(edits, list) or not edits:
                raise ValueError('corrections: empty changes')
            items = [i for s in expected['sections'] for i in s['items']]
            by_id = {i['id']: i for i in items}
            if len(by_id) != len(items):
                raise ValueError('corrections: duplicate item ids')
            seen = set()
            for edit in edits:
                if not isinstance(edit, dict) or not isinstance(edit.get('item_id'), str) or not isinstance(edit.get('field'), str):
                    raise ValueError('corrections: invalid change entry')
                key = (edit['item_id'], edit['field'])
                if key in seen or key[0] not in by_id or key[1] not in permitted:
                    raise ValueError('corrections: invalid/duplicate item field')
                seen.add(key)
                item = by_id[key[0]]
                if key[1] not in item or canonical(item[key[1]]) != canonical(edit['before']) or canonical(edit['before']) == canonical(edit['after']):
                    raise ValueError('corrections: stale or ineffective change')
                item[key[1]] = copy.deepcopy(edit['after'])
                changes += 1
        if canonical(expected) != canonical(actual):
            raise ValueError('corrections: change outside authorized ledger in ' + name)
        snapshots.append(dict(file=name, before=before, after=after))
    return dict(files=len(old), authorized_files=len(authorized), changes=changes,
                ledger_sha256=digest(ledger_path), snapshots=snapshots)


def atomic_write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def explanation_batch(original, candidate, apply=False, backup=None, baseline=None, corrections=None):
    original, candidate = Path(original).resolve(), Path(candidate).resolve()
    if (baseline is None) != (corrections is None):
        raise ValueError('batch: --baseline and --corrections must be provided together')
    audit = correction_snapshot(baseline, original, corrections) if baseline is not None else None
    if audit and (Path(baseline).resolve() == candidate or Path(baseline).resolve() in candidate.parents or candidate in Path(baseline).resolve().parents):
        raise ValueError('batch: candidate must be separate from immutable baseline')
    authorized_hashes = {r['file']: r['after'] for r in audit['snapshots']} if audit else {}
    old = {p.name: p for p in original.glob('W[0-9][0-9].json')}
    new = {p.name: p for p in candidate.glob('W[0-9][0-9].json')}
    if not old or old.keys() != new.keys() or original == candidate:
        raise ValueError('batch: directories must contain the same nonempty set of Wxx.json files')
    snapshots = []
    for name in sorted(old):
        if old[name].is_symlink() or new[name].is_symlink():
            raise ValueError('batch: symlinks are not accepted')
        if audit and digest(old[name]) != authorized_hashes[name]:
            raise ValueError('batch: authorized snapshot changed during validation')
        if canonical(frozen_pack(read_json(old[name]))) != canonical(frozen_pack(read_json(new[name]))):
            raise ValueError('batch: non-explanation change in ' + name)
        snapshots.append(dict(file=name, before=digest(old[name]), after=digest(new[name])))
    result = dict(mode='apply' if apply else 'dry-run', files=len(old), snapshots=snapshots)
    if audit:
        result['corrections'] = audit
    if apply:
        if backup is None:
            raise ValueError('batch: --backup required for apply')
        backup = Path(backup).resolve()
        if backup.exists() or backup == original or original in backup.parents or candidate in backup.parents:
            raise ValueError('batch: backup must be a new separate directory')
        if audit and (backup == Path(baseline).resolve() or Path(baseline).resolve() in backup.parents):
            raise ValueError('batch: backup must be outside immutable baseline')
        # No target is touched until EVERY candidate passes and EVERY original is backed up.
        backup.mkdir(parents=True)
        for name, path in old.items():
            shutil.copy2(path, backup / name)
        atomic_write(backup / 'snapshot.json', json.dumps(result, indent=2) + '\n')
        try:
            for row in snapshots:
                name = row['file']
                if digest(old[name]) != row['before'] or digest(new[name]) != row['after']:
                    raise ValueError('batch: concurrent edit detected')
                atomic_write(old[name], new[name].read_text(encoding='utf-8'))
        except BaseException:
            for name in old:
                shutil.copy2(backup / name, old[name])
            raise
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    notes = sub.add_parser('notes')
    notes.add_argument('--input', type=Path, required=True)
    notes.add_argument('--facts', type=Path, required=True)
    notes.add_argument('--week', type=int, required=True)
    notes.add_argument('--output', type=Path, help='write validated Wxx-notes.json; omit for dry-run')
    batch = sub.add_parser('explanations')
    batch.add_argument('--original', type=Path, required=True)
    batch.add_argument('--candidate', type=Path, required=True)
    batch.add_argument('--apply', action='store_true')
    batch.add_argument('--backup', type=Path)
    batch.add_argument('--report', type=Path, required=True)
    batch.add_argument('--baseline', type=Path, help='immutable library before independently authorized corrections')
    batch.add_argument('--corrections', type=Path, help='exact authorized correction ledger; requires --baseline')
    emphasis = sub.add_parser('emphasis')
    emphasis.add_argument('--input', type=Path, required=True, help='normalized private JSON, not an unverified transcript')
    emphasis.add_argument('--facts', type=Path, required=True)
    emphasis.add_argument('--week', type=int, required=True)
    emphasis.add_argument('--inline', type=Path, help='optional draft with explicitly contextualized emphasis markers')
    emphasis.add_argument('--output', type=Path, help='validated Wxx-emphasis.json; omit for dry-run')
    args = parser.parse_args()
    try:
        if args.command == 'notes':
            text = args.input.read_text(encoding='utf-8')
            data = read_json(args.input) if args.input.suffix == '.json' else markdown_notes(text)
            result = validate_notes(data, facts_from(args.facts), args.week)
            if args.output:
                atomic_write(args.output, json.dumps(data, ensure_ascii=False, indent=2) + '\n')
            result['mode'] = 'write' if args.output else 'dry-run'
        elif args.command == 'emphasis':
            data = emphasis_metadata(read_json(args.input), facts_from(args.facts), args.week,
                                     args.inline.read_text(encoding='utf-8') if args.inline else '')
            if args.output:
                atomic_write(args.output, json.dumps(data, ensure_ascii=False, indent=2) + '\n')
            result = dict(mode='write' if args.output else 'dry-run', week=args.week, marks=len(data['marks']))
        else:
            report = args.report.resolve()
            protected = [args.original.resolve(), args.candidate.resolve()]
            if args.baseline:
                protected.append(args.baseline.resolve())
            if args.backup:
                protected.append(args.backup.resolve())
            if any(report == directory or directory in report.parents for directory in protected):
                raise ValueError('batch: report must be outside content/backup directories')
            if args.corrections and report == args.corrections.resolve():
                raise ValueError('batch: report must not overwrite correction ledger')
            result = explanation_batch(args.original, args.candidate, args.apply, args.backup,
                                       args.baseline, args.corrections)
            atomic_write(args.report, json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    except (ValueError, KeyError, TypeError, OSError):
        # JSON decode errors may contain private input. Only emit a generic error on the public terminal.
        parser.exit(1, 'Validation failed; no candidate content was printed. Check private inputs against tools/mainline/README.md.\n')


if __name__ == '__main__':
    main()
