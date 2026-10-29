#!/usr/bin/env python3
"""Read only course Markdown; copy three unmodified PDF pages to Coach staging."""

from pathlib import Path
import hashlib
import re
import sys
from local_config import value

source = Path(value("coach.textbookDir")).expanduser()
staging = Path.home() / "Coach/corpus/s0"
staging.mkdir(parents=True, exist_ok=True)
for prefix, term in [
    ("03-", "Grossman"),
    ("08-", "adverse selection"),
    ("09-", "adverse selection"),
]:
    original = next(source.glob(prefix + "*"))
    pages = re.split(r"(?=<!-- PDF 第)", original.read_text())[1:]
    page = next(page for page in pages if term.lower() in page.lower())
    target = staging / (prefix + "page.md")
    target.write_text(page)
    print(
        target.name,
        hashlib.sha256(page.encode()).hexdigest(),
        page.splitlines()[0],
        file=sys.stderr,
    )
print(staging)
