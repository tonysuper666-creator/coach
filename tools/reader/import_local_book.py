#!/usr/bin/env python3
"""Bind ONE saved desktop import to an existing learner source. No network or permission grants."""
import argparse
from contextlib import closing
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile

FORMATS = {'pdf', 'epub', 'txt', 'md', 'docx', 'pptx', 'html', 'mobi', 'azw3', 'rtf', 'odt', 'png', 'jpg'}


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            value.update(block)
    return value.hexdigest()


def atomic_file(directory, name, content):
    target = directory / name
    if target.exists() and (target.is_symlink() or not target.is_file()):
        raise ValueError('invalid private target')
    fd, pending = tempfile.mkstemp(prefix='.binding-', dir=directory)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(pending, target)
    finally:
        Path(pending).unlink(missing_ok=True)


def import_local_book(desktop, book, library, *, document_id, course, explain_source_id):
    if not re.fullmatch('[0-9a-f]{64}', book):
        raise ValueError('invalid book hash')
    if not re.fullmatch('[A-Za-z0-9_-]{1,80}', document_id) or not 1 <= len(course) <= 128:
        raise ValueError('invalid document/course')
    if not re.fullmatch('[A-Za-z0-9._:-]{1,80}', explain_source_id):
        raise ValueError('invalid existing source ID')
    desktop = Path(desktop).resolve(strict=True)
    # Read one selected book, in a consistent read-only transaction; never scan/copy a user's library.
    database = desktop / 'library.sqlite'
    with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as db:
        db.execute('BEGIN')
        row = db.execute('SELECT title,format FROM books WHERE id=?', (book,)).fetchone()
        if row is None:
            raise ValueError('book is not saved in desktop library')
        title, format_ = row
        if format_ not in FORMATS or not isinstance(title, str) or not 1 <= len(title) <= 300:
            raise ValueError('unsupported saved book')
        has_ocr = any(column[1] == 'ocr' for column in db.execute('PRAGMA table_info(pages)'))
        query = 'SELECT page,text,' + ('ocr' if has_ocr else '0') + ' FROM pages WHERE book=? ORDER BY page'
        pages = [dict(number=n, text=text, ocr=bool(ocr)) for n, text, ocr in db.execute(query, (book,))]
    if not 1 <= len(pages) <= 100000 or len({p['number'] for p in pages}) != len(pages) or any(
            not 1 <= p['number'] <= 100000 or not isinstance(p['text'], str) for p in pages):
        raise ValueError('invalid saved pages')
    snapshot = json.dumps(dict(schema=1, book=book, format=format_, pages=pages), ensure_ascii=False).encode('utf-8')
    if len(snapshot) > 32 * 1024 * 1024:
        raise ValueError('saved page snapshot exceeds 32 MB')
    source = (desktop / f'{book}.{format_}').resolve(strict=True)
    if not source.is_relative_to(desktop) or not source.is_file() or not 0 < source.stat().st_size <= 256 * 1024 * 1024:
        raise ValueError('invalid original')
    library = Path(library).expanduser().resolve()
    if any((parent / '.git').exists() for parent in (library, *library.parents)):
        raise ValueError('private library must be outside Git')
    library.mkdir(parents=True, exist_ok=True, mode=0o700)
    if library.stat().st_mode & 0o077:
        raise ValueError('private library must have permissions 0700')
    with (library / '.import.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        catalog_path = library / 'local-books.json'
        catalog = json.loads(catalog_path.read_text()) if catalog_path.exists() else dict(schema=1, books=[])
        if catalog.get('schema') != 1 or not isinstance(catalog.get('books'), list):
            raise ValueError('unsupported local book catalog')
        books = library / 'books'
        books.mkdir(mode=0o700, exist_ok=True)
        if not books.resolve().is_relative_to(library):
            raise ValueError('book directory outside library')
        fd, pending = tempfile.mkstemp(prefix='.original-', dir=books)
        try:
            with os.fdopen(fd, 'wb') as output, source.open('rb') as input_file:
                copied = 0
                for block in iter(lambda: input_file.read(65536), b''):
                    copied += len(block)
                    if copied > 256 * 1024 * 1024:
                        raise ValueError('original exceeds 256 MB')
                    output.write(block)
                output.flush()
                os.fsync(output.fileno())
            if digest(Path(pending)) != book:
                raise ValueError('original no longer matches desktop identity')
            target = books / f'{book}.{format_}'
            if target.exists():
                if target.is_symlink() or digest(target) != book:
                    raise ValueError('existing original is corrupt')
            else:
                os.replace(pending, target)
            pages_hash = hashlib.sha256(snapshot).hexdigest()
            pages_name = f'{book}-{pages_hash}.pages.json'
            snapshot_target = books / pages_name
            if snapshot_target.exists():
                if snapshot_target.is_symlink() or digest(snapshot_target) != pages_hash:
                    raise ValueError('existing page snapshot is corrupt')
            else:
                atomic_file(books, pages_name, snapshot)
            entry = dict(id=document_id, course=course, title=title, file=f'books/{target.name}',
                         sha256=book, pagesFile=f'books/{pages_name}', pagesSha256=pages_hash,
                         explainSourceId=explain_source_id)
            entries = [e for e in catalog['books'] if (e['course'], e['id']) != (course, document_id)]
            entries.append(entry)
            if len(entries) > 500:
                raise ValueError('local book catalog is full')
            result = json.dumps(dict(schema=1, books=entries), ensure_ascii=False, indent=2).encode()
            if len(result) > 1048576:
                raise ValueError('local book catalog exceeds 1 MB')
            # Preserve the independent PDF catalog. An empty one permits binding-only servers.
            if not (library / 'catalog.json').exists():
                atomic_file(library, 'catalog.json', b'{"schema":1,"documents":[]}\n')
            atomic_file(library, 'local-books.json', result)
        finally:
            Path(pending).unlink(missing_ok=True)
    return entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--desktop-library', type=Path, required=True)
    parser.add_argument('--book', required=True, help='SHA-256 of one already imported desktop book')
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--id', required=True)
    parser.add_argument('--course', required=True)
    parser.add_argument('--explain-source-id', required=True, help='existing trusted ledger source with identical saved pages')
    args = parser.parse_args()
    try:
        import_local_book(args.desktop_library, args.book, args.library, document_id=args.id,
                          course=args.course, explain_source_id=args.explain_source_id)
    except (ValueError, OSError, sqlite3.Error, KeyError, TypeError):
        parser.exit(1, 'Binding failed: check the explicitly selected saved book, private catalog and existing source locally.\n')
    print('Saved-page binding imported. Existing source visibility and learned scope still apply; no AI called.')


if __name__ == '__main__':
    main()
