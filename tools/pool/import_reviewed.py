#!/usr/bin/env python3
"""Import reviewed MCQs from an immutable staging copy, while the target server is stopped.

Dry-run by default. Apply requires the same fcntl server.lock used by the JVM,
creates a full SQLite backup, and commits only question inventory in one transaction.
Never copies learning events, devices, jobs, or messages. No model calls.
"""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import fcntl
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata


def require(condition, message="Invalid reviewed inventory"):
    if not condition:
        raise ValueError(message)


def normalized(text):
    return re.sub(r'\s+', ' ', unicodedata.normalize('NFKC', text).strip()).lower()


def fingerprint(stem, options):
    values = [normalized(stem)] + sorted(normalized(o['text']) for o in options)
    return hashlib.sha256(''.join(f'{len(v.encode("utf-16-le")) // 2}:{v}' for v in values).encode()).hexdigest()


def quote_text(text):
    return normalized(re.sub(r'[*_`#>]', '', text))


def readonly(path):
    connection = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def plan(source, target, course, packs):
    kps = {r['kp_id']: r['title'] for r in target.execute(
        'select kp_id,title from outline_kp where course_id=? and retired=0', (course,))}
    require(kps, 'Target course has no active knowledge points')
    require(re.fullmatch(r"[A-Za-z0-9_-]+", course) and all(re.fullmatch(r"[A-Za-z0-9_-]+", kp) for kp in kps), "Invalid course/KP asset ID")
    candidates = []
    for row in source.execute("""select q.*,k.kp_id,k.is_primary,a.key_json,a.reviewed_by,a.reviewed_at,
            d.pack_hash,d.explanation,d.source_quote,d.generator,d.reviewer
        from questions q join question_kp k using(question_id) join answer_keys a using(question_id)
        join pool_details d using(question_id)
        where q.course_id=? and q.format='SINGLE_CHOICE' and q.key_state='VERIFIED' order by q.question_id""", (course,)):
        if row['kp_id'] not in kps:
            continue
        options = json.loads(row['options'])
        key = json.loads(row['key_json'])['correctOptionId']
        require(row['origin'] == 'COACH' and row['created_seq'] is None, 'Only generated pool inventory is importable')
        require(len(options) == 4 and len({o['optionId'] for o in options}) == 4)
        require(key in {o['optionId'] for o in options})
        require(all(o['text'].strip() for o in options) and len({normalized(o['text']) for o in options}) == 4)
        require(len(row['stem_md']) >= 15 and fingerprint(row['stem_md'], options) == row['fingerprint'])
        require(row['generator'].startswith('claude') and row['reviewer'].startswith('gpt'))
        require(row['reviewed_by'] == row['reviewer'] and row['reviewed_at'])
        # Exactly the same source-pack prefix/hash used by the application worker.
        content = (packs / course / (row['kp_id'] + '.md')).read_bytes().decode('utf-8')
        # Kotlin String.take counts UTF-16 code units; preserve CRLF too. Java's UTF-8
        # encoder replaces a possible trailing unpaired surrogate with '?'.
        content = content.encode('utf-16-le')[:80000].decode('utf-16-le', 'surrogatepass')
        pack_hash = hashlib.sha256((kps[row['kp_id']] + '\0' + content).encode('utf-8', 'replace')).hexdigest()
        require(row['pack_hash'] == pack_hash, 'Source pack changed; re-review instead of importing stale material')
        quote = quote_text(row['source_quote'])
        require(len(quote) >= 8 and quote in quote_text(content), 'Unsupported source quotation')
        existing = target.execute('select question_id,fingerprint from questions where question_id=?', (row['question_id'],)).fetchone()
        if existing:
            require(existing['fingerprint'] == row['fingerprint'], 'Question ID collision')
            continue
        if target.execute('select 1 from questions where course_id=? and fingerprint=?', (course, row['fingerprint'])).fetchone():
            continue
        candidates.append(dict(row))
    return kps, candidates


def inventory(db, course, kps):
    counts = {}
    for kp in kps:
        counts[kp] = db.execute("""select count(*) from questions q join question_kp k using(question_id)
            join pool_details d using(question_id) join answer_keys a using(question_id)
            where q.course_id=? and k.kp_id=? and q.format='SINGLE_CHOICE' and q.key_state='VERIFIED'
            and d.reviewer=a.reviewed_by and d.reviewer like 'gpt%' and d.generator like 'claude%'
            and not exists(select 1 from events e where e.course_id=q.course_id and e.type='question_posed'
                and json_extract(e.payload,'$.questionId')=q.question_id)
            and not exists(select 1 from offline_reservations r where r.question_id=q.question_id
                and r.expires_at>cast(strftime('%s','now') as integer)*1000)""", (course, kp)).fetchone()[0]
    return counts


def insert(db, rows):
    tables = {
        'questions': ['question_id','course_id','format','stem_md','options','origin','fingerprint','key_state','created_seq'],
        'question_kp': ['question_id','kp_id','is_primary'],
        'answer_keys': ['question_id','key_json','reviewed_by','reviewed_at'],
        'pool_details': ['question_id','pack_hash','explanation','source_quote','generator','reviewer'],
    }
    for row in rows:
        for table, columns in tables.items():
            db.execute(f'insert into {table} ({",".join(columns)}) values ({",".join("?" for _ in columns)})',
                       [row[column] for column in columns])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Immutable staging SQLite copy')
    parser.add_argument('--target', type=Path, required=True, help='Target data directory')
    parser.add_argument('--course', required=True)
    parser.add_argument('--packs', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup', type=Path, help='New SQLite backup path, mandatory for apply')
    args = parser.parse_args()
    target_path = args.target / 'coach.sqlite'
    require(args.source.resolve() != target_path.resolve())
    now = datetime.now(timezone.utc)
    require(not datetime(2026,11,2,tzinfo=timezone.utc) <= now < datetime(2026,11,2,15,tzinfo=timezone.utc), 'Examination stop window')
    with closing(readonly(args.source)) as source, (args.target / 'server.lock').open('a+b') as lock:
        # lockf, not flock: Java FileChannel uses POSIX record locks on macOS.
        fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with closing(sqlite3.connect(target_path.resolve().as_uri() + '?mode=rw', uri=True)) as target:
            target.row_factory = sqlite3.Row
            target.execute('pragma foreign_keys=ON')
            kps, rows = plan(source, target, args.course, args.packs)
            before = inventory(target, args.course, kps)
            # Dry-run uses an in-memory copy and the very same insert/validation path.
            with closing(sqlite3.connect(':memory:')) as preview:
                target.backup(preview)
                insert(preview, rows)
                after = inventory(preview, args.course, kps)
                require(all(n >= 6 for n in after.values()), 'At least one KP would remain below six unseen reviewed questions')
            report = dict(applied=False, course=args.course, inserted=len(rows), kpCount=len(kps), before=before, after=after)
            if args.apply:
                require(args.backup and not args.backup.exists(), 'Provide a new backup path')
                args.backup.parent.mkdir(parents=True, exist_ok=True)
                with closing(sqlite3.connect(args.backup)) as backup:
                    target.backup(backup)
                    require(backup.execute('pragma integrity_check').fetchone()[0] == 'ok')
                with target:
                    target.execute('begin immediate')
                    insert(target, rows)
                    require(inventory(target, args.course, kps) == after)
                    require(target.execute('pragma foreign_key_check').fetchone() is None)
                report.update(applied=True, importedIds=[r['question_id'] for r in rows])
            args.report.write_text(json.dumps(report, indent=2))
            print(json.dumps({k:v for k,v in report.items() if k not in ('before','after','importedIds')}))


if __name__ == '__main__':
    main()
