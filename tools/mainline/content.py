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


def explanation_batch(original, candidate, apply=False, backup=None):
    original, candidate = Path(original).resolve(), Path(candidate).resolve()
    old = {p.name: p for p in original.glob('W[0-9][0-9].json')}
    new = {p.name: p for p in candidate.glob('W[0-9][0-9].json')}
    if not old or old.keys() != new.keys() or original == candidate:
        raise ValueError('batch: directories must contain the same nonempty set of Wxx.json files')
    snapshots = []
    for name in sorted(old):
        if old[name].is_symlink() or new[name].is_symlink():
            raise ValueError('batch: symlinks are not accepted')
        if json.dumps(frozen_pack(read_json(old[name])), sort_keys=True) != json.dumps(frozen_pack(read_json(new[name])), sort_keys=True):
            raise ValueError('batch: non-explanation change in ' + name)
        snapshots.append(dict(file=name, before=digest(old[name]), after=digest(new[name])))
    result = dict(mode='apply' if apply else 'dry-run', files=len(old), snapshots=snapshots)
    if apply:
        if backup is None:
            raise ValueError('batch: --backup required for apply')
        backup = Path(backup).resolve()
        if backup.exists() or backup == original or original in backup.parents or candidate in backup.parents:
            raise ValueError('batch: backup must be a new separate directory')
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
    args = parser.parse_args()
    try:
        if args.command == 'notes':
            text = args.input.read_text(encoding='utf-8')
            data = read_json(args.input) if args.input.suffix == '.json' else markdown_notes(text)
            result = validate_notes(data, facts_from(args.facts), args.week)
            if args.output:
                atomic_write(args.output, json.dumps(data, ensure_ascii=False, indent=2) + '\n')
            result['mode'] = 'write' if args.output else 'dry-run'
        else:
            report = args.report.resolve()
            protected = [args.original.resolve(), args.candidate.resolve()]
            if args.backup:
                protected.append(args.backup.resolve())
            if any(report == directory or directory in report.parents for directory in protected):
                raise ValueError('batch: report must be outside content/backup directories')
            result = explanation_batch(args.original, args.candidate, args.apply, args.backup)
            atomic_write(args.report, json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    except (ValueError, KeyError, TypeError, OSError):
        # JSON decode errors may contain private input. Only emit a generic error on the public terminal.
        parser.exit(1, 'Validation failed; no candidate content was printed. Check private inputs against tools/mainline/README.md.\n')


if __name__ == '__main__':
    main()
