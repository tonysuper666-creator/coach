# TON-1663 — first-request registration of personal imports

2026-11-16. Builds on merged PRs #323 (formats), #330 (OCR), #336 (operator bindings) and #337 (illustration model). Product decision 12 remains authoritative. This slice removes the operator/source/learning setup requirement for a newly imported personal book. Production paired-service acceptance, clean OS/MSI installation and format illustrations remain separate unfinished work on this issue.

## Behavior and boundaries

The existing per-book consent dialog still precedes transmission. The desktop hashes the original import and checks the submitted block against the saved page using exact UTF-16 indices. Its first explanation request carries page identity metadata alongside the already consented blocks. The server privately saves those fragments under the authenticated device; neither the original file nor the entire page is uploaded. Only the selected quote reaches the subscription model.

An existing catalog hash must still pass its trusted source/course/learned-scope checks. A personal receipt cannot reclassify it. Unreadable catalogs, changed page identities, conflicting overlapping excerpts and invalid bounds fail closed. Registration persists across restart and is rechecked after generation. Removing consent locally prevents further sends, but does not erase fragments already saved on the server; operator cleanup is currently required. The receipt is a device claim about its own book, not independent server verification of the original file.

## Reproduction and evidence

```sh
tools/dev/heavy ./gradlew :server:app:test \
  --tests LibraryExplainTest --tests ReaderTest :server:app:installDist
tools/dev/heavy ./gradlew :desktopApp:test
python3 -m unittest discover -s tools/reader -p 'test_import*.py'
tools/dev/heavy python3 tools/reader/check_local_book_live.py --automatic \
  --bridge-python /path/to/coach-bridge-venv/bin/python \
  --output /private/new-evidence-directory
```

Server exit 0: 29 tests, no failures/errors/skips. Windows full desktop gate exit 0: 433 tests, 0 failures/errors, 7 opt-in cases skipped (native live/format/OCR windows and live reader server); 426 executed. Importer exit 0: 10 tests. Coverage includes first registration without course grants, device isolation, restart persistence, conflicting pages, exact bounds, oversize requests, catalog protections and registration removal during generation. Existing tests were not removed or relaxed.

The isolated real-subscription check exited 0 for four committed self-authored Windows imports. It seeds no course event, trusted source page or outline grant. Four completed `READER_SELECTION` calls used requested/served model `gpt-6.1-sol`, one physical call each:

| Import | Saved UTF-16 range | Round trip |
| --- | --- | --- |
| GBK TXT | 10–45 | 6,055 ms |
| DOCX | 14–52 | 6,129 ms |
| PPTX | 0–38 | 6,304 ms |
| PNG OCR | 0–30 | 7,815 ms |

Each response retained the exact local book/page/block citation. These are actual subscription responses, not the explicit synthetic receipts in the earlier format/OCR window checks.

For native window acceptance, add `--native-windows`. The runner creates an isolated service and private SSH loopback forward, drops only the committed self-authored fixtures into its own Windows window, grants per-book consent, asks a real question and opens its exact saved source in both themes. It does not change the production service, existing pairing, accounts, real books or other windows. The server upgrade requires its own review and production gate.

The latest Windows console run exited 1: **7/8** format/theme combinations completed real questions and exact UTF-16 source navigation. TXT/DOCX/PPTX passed in both themes; PNG OCR passed in light, then returned `Unavailable` in dark. The opt-in test fails on that unavailable response; it does not count it as a successful explanation. The underlying cause of this response has not been established: the client maps multiple failure types to this generic unavailable result. An earlier run also received `Unavailable` for the OCR title; selecting the substantive paragraph improved coverage but did not establish reliable completion. No further real-subscription retry was made after these two Windows failures. The isolated server was removed after each run. This is partial native evidence, not production paired-service acceptance or an 8/8 pass.

## Remaining work

Update the existing paired service after review and verify its real Windows explanation/source chain. Verify clean Win10/11 and the final MSI. Populate DOCX/PPTX/HTML/Kindle illustrations using the now merged #337 model; PDF/EPUB illustration extraction remains TON-1665. This slice does not edit `TextbookLibrary.kt`; it is released for that work. Page strings and UTF-16 offsets remain unchanged.
