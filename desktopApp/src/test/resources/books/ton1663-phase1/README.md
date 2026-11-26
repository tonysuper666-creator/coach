Original sentences written for the TON-1663 phase-1 import spec. Not a textbook, not a published book, and not learner material.

Regenerate with Python 3's standard library only:

```
python desktopApp/src/test/resources/books/ton1663-phase1/author.py
```

No extra package is installed. `author.py` uses `struct`, `zlib`, and `zipfile`. The encrypted-PDF case is built inside `Ton1663Phase1SpecTest` with Apache PDFBox, which the desktop tests already depend on (Apache-2.0).

`bytes/` is marked binary in `.gitattributes` so GB18030, UTF-16, and Big5 files are not rewritten as UTF-8.
