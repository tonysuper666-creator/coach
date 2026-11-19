"""Check only this run's synthetic image import, without printing book text."""
import argparse
import hashlib
import sqlite3
import sys
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("database", type=Path)
parser.add_argument("fixture", type=Path)
parser.add_argument("--reading-page", type=int)
args = parser.parse_args()
path, fixture = args.database.resolve(), args.fixture.resolve()
markers = {
    "01-clean-serif.png": ("memory",),
    "three-scanned-pages.pdf": ("memory", "observation", "practice"),
}[fixture.name]
if not path.is_file():
    sys.exit(1)
try:
    with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=2) as db:
        digest = hashlib.sha256(fixture.read_bytes()).hexdigest()
        if args.reading_page is not None:
            page = db.execute("SELECT reading_page FROM books WHERE id=?", (digest,)).fetchone()
            if page != (args.reading_page,):
                sys.exit(1)
            print(f"Installed reader saved page {page[0]} for {fixture.name}")
        rows = db.execute("SELECT page,ocr,text FROM pages JOIN books ON pages.book=books.id "
                          "WHERE books.id=? ORDER BY page", (digest,)).fetchall()
        if len(rows) == len(markers) and all(
            page == i and ocr == 1 and len(text.strip()) >= 50 and marker in text.lower()
            for i, ((page, ocr, text), marker) in enumerate(zip(rows, markers), 1)
        ):
            print(f"Installed scan import: {fixture.name} pages={len(rows)} chars={sum(len(row[2]) for row in rows)} "
                  "all page markers and OCR flags verified")
            sys.exit(0)
except sqlite3.Error:
    pass
sys.exit(1)
