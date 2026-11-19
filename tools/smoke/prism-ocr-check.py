"""Check only this run's synthetic image import, without printing book text."""
import sqlite3
import sys
from pathlib import Path

path = Path(sys.argv[1]).resolve()
if not path.is_file():
    sys.exit(1)
try:
    with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=2) as db:
        rows = db.execute(
            "SELECT p.text FROM pages p JOIN books b ON b.id = p.book "
            "WHERE b.title LIKE ?", ('%01-clean-serif%',)
        ).fetchall()
        if any(len(text.strip()) >= 50 and 'memory' in text.lower() for (text,) in rows):
            print('Synthetic scan imported: recognized expected English marker and at least 50 characters')
            sys.exit(0)
except sqlite3.Error:
    pass
sys.exit(1)
