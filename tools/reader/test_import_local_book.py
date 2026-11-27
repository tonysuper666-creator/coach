import hashlib
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock

import import_local_book as import_local_book_mod
from import_local_book import MAX_ORIGINAL_BYTES, import_local_book


class LocalBookImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='coach-saved-book-')
        self.root = Path(self.temp.name)
        self.desktop = self.root / 'desktop'
        self.desktop.mkdir()
        self.book = hashlib.sha256(b'Synthetic scan original').hexdigest()
        self.original = self.desktop / (self.book + '.png')
        self.original.write_bytes(b'Synthetic scan original')
        self.text = '自写 🌱 Orchard\n\nSynthetic pears grow.\n识别可能有错字。'
        with closing(sqlite3.connect(self.desktop / 'library.sqlite')) as db, db:
            db.executescript('CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,format TEXT);'
                             'CREATE TABLE pages(book TEXT,page INTEGER,text TEXT,ocr INTEGER);')
            db.execute('INSERT INTO books VALUES(?,?,?)', (self.book, 'Self-authored scan', 'png'))
            db.execute('INSERT INTO pages VALUES(?,?,?,?)', (self.book, 1, self.text, 1))
            db.execute('INSERT INTO books VALUES(?,?,?)', ('other', 'Do not copy', 'txt'))
            db.execute('INSERT INTO pages VALUES(?,?,?,?)', ('other', 1, 'OTHER_BOOK_SECRET', 0))
        self.library = self.root / 'server-library'

    def tearDown(self):
        self.temp.cleanup()

    def bind(self, **options):
        return import_local_book(self.desktop, self.book, self.library,
                                 document_id='self-authored', course='SYNTHETIC',
                                 explain_source_id=options.get('source', 'existing-source'))

    def test_ocr_and_utf16_page_text_are_exact_and_only_selected_book_is_copied(self):
        before = (self.desktop / 'library.sqlite').read_bytes()
        entry = self.bind()
        snapshot = json.loads((self.library / entry['pagesFile']).read_bytes())
        self.assertEqual([dict(number=1, text=self.text, ocr=True)], snapshot['pages'])
        self.assertGreater(len(self.text.encode('utf-16-le')) // 2, len(self.text))
        self.assertNotIn('OTHER_BOOK_SECRET', (self.library / entry['pagesFile']).read_text())
        self.assertEqual(before, (self.desktop / 'library.sqlite').read_bytes())
        for path in [self.library / 'local-books.json', self.library / entry['file'], self.library / entry['pagesFile']]:
            self.assertEqual(0, path.stat().st_mode & 0o077)
        self.assertEqual(b'Synthetic scan original', (self.library / entry['file']).read_bytes())

    def test_reimport_replaces_binding_without_changing_pdf_catalog(self):
        self.bind()
        pdf = self.library / 'catalog.json'
        before = pdf.read_bytes()
        self.bind(source='new-explicit-source')
        entries = json.loads((self.library / 'local-books.json').read_text())['books']
        self.assertEqual(1, len(entries))
        self.assertEqual('new-explicit-source', entries[0]['explainSourceId'])
        self.assertEqual(before, pdf.read_bytes())

    def test_wrong_original_or_corrupt_snapshot_does_not_change_catalog(self):
        entry = self.bind()
        catalog = self.library / 'local-books.json'
        before = catalog.read_bytes()
        self.original.write_text('changed')
        with self.assertRaises(ValueError):
            self.bind()
        self.assertEqual(before, catalog.read_bytes())
        self.original.write_bytes(b'Synthetic scan original')
        (self.library / entry['pagesFile']).write_text('changed')
        with self.assertRaises(ValueError):
            self.bind()
        self.assertEqual(before, catalog.read_bytes())

    def test_legacy_book_without_ocr_column_is_read_only_and_keeps_page_numbers(self):
        with closing(sqlite3.connect(self.desktop / 'library.sqlite')) as db, db:
            db.execute('ALTER TABLE pages DROP COLUMN ocr')
            db.execute('UPDATE pages SET page=7 WHERE book=?', (self.book,))
        entry = self.bind()
        snapshot = json.loads((self.library / entry['pagesFile']).read_bytes())
        self.assertEqual([dict(number=7, text=self.text, ocr=False)], snapshot['pages'])

    def test_paths_cannot_read_other_books_or_write_inside_git(self):
        self.original.unlink()
        external = self.root / 'external.png'
        external.write_bytes(b'Synthetic scan original')
        self.original.symlink_to(external)
        with self.assertRaises(ValueError):
            self.bind()
        self.original.unlink()
        self.original.write_bytes(b'Synthetic scan original')
        (self.root / '.git').write_text('gitdir: synthetic')
        with self.assertRaises(ValueError):
            self.bind()
        self.assertFalse(self.library.exists())

    def test_original_size_limit_is_512_mb_and_rejects_oversize(self):
        self.assertEqual(512 * 1024 * 1024, MAX_ORIGINAL_BYTES)
        with mock.patch.object(import_local_book_mod, 'MAX_ORIGINAL_BYTES', len(b'Synthetic scan original') - 1):
            with self.assertRaises(ValueError):
                self.bind()
        self.assertFalse((self.library / 'local-books.json').exists())

        oversize_book = hashlib.sha256(b'oversize-placeholder').hexdigest()
        oversize_path = self.desktop / f'{oversize_book}.pdf'
        with oversize_path.open('wb') as f:
            f.truncate(MAX_ORIGINAL_BYTES + 1)
        with closing(sqlite3.connect(self.desktop / 'library.sqlite')) as db, db:
            db.execute('INSERT INTO books VALUES(?,?,?)', (oversize_book, 'Oversize Textbook', 'pdf'))
            db.execute('INSERT INTO pages VALUES(?,?,?,?)', (oversize_book, 1, 'Page 1', 0))
        with self.assertRaises(ValueError):
            import_local_book(self.desktop, oversize_book, self.library,
                              document_id='oversize', course='SYNTHETIC',
                              explain_source_id='existing-source')
        self.assertFalse((self.library / 'local-books.json').exists())


if __name__ == '__main__':
    unittest.main()
