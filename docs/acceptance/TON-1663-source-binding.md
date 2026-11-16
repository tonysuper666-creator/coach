# TON-1663 — saved import source binding

2026-11-16. Phase-one formats (#323) and offline OCR (#330) are merged. Product decision 12 remains the format scope. This slice adds an operator-owned server binding for the exact pages saved by those imports, then exercises real subscription explanations using self-authored fixtures. The whole issue is not complete.

## Result

Previously, a newly imported scan or common-format book returned HTTP 409 because the server could not map its local hash/page/block to an authorized source. `import_local_book.py` now copies one explicitly selected saved import into the private server library and writes its exact page snapshot and source mapping. The server checks that snapshot, original bytes, trusted page text and existing learned scope. It does not re-extract or recognize pages, translate page numbers, or change UTF-16 anchors.

This preserves the existing trust boundary: a binding cannot create learner sources, courses or learned permissions. An operator must first establish the corresponding trusted source with identical page text. Automatic enrollment from arbitrary desktop drag-and-drop remains unfinished. No production service was upgraded or restarted.

## Local gates

Commands run through the repository heavy entry where applicable:

```sh
tools/dev/heavy ./gradlew :server:app:test \
  --tests com.coach.server.LibraryExplainTest \
  --tests com.coach.server.ReaderTest :server:app:installDist
python3 -m unittest discover -s tools/reader -p 'test_import*.py'
tools/dev/heavy ./gradlew :desktopApp:test --tests SavedBookBindingTest
```

- Server: exit 0, 25 tests, zero failures/errors/skips. New coverage rejects wrong UTF-16 starts, forged block text, missing/unread pages, snapshot tampering and ambiguous PDF/local mappings. Revoking the binding during generation withholds the answer.
- Importers: exit 0, 10 tests. One selected book only, exact OCR/Unicode page preservation, read-only desktop database, legacy pages without OCR flags, private permissions, corrupt originals/snapshots, PDF-catalog preservation and path containment.
- Windows export: exit 0, one test. Actual `TextbookLibrary.importBook` imports the committed GBK, DOCX, PPTX and PNG fixtures, reopens their saved pages and verifies exact block substrings. PNG uses the bundled OCR engine. This is not a new native-window screenshot run. Existing tests were not removed, skipped or weakened.

## Real subscription check

```sh
tools/dev/heavy python3 tools/reader/check_local_book_live.py \
  --bridge-python /path/to/coach-bridge-venv/bin/python \
  --output /private/new-evidence-directory
```

Exit 0. The checker verifies original hashes against committed self-authored fixtures, imports their actual Windows-saved pages using the new binding tool, and seeds only a disposable database. It starts separate loopback server and subscription-bridge processes with private state, then cleans up those exact processes. Existing service ports, accounts, real textbooks and other windows were not touched.

All four `/v1/library/explain` responses passed the citation gate. Each retained the actual local book/page/block ID; the block start/end still index the saved page in UTF-16.

| Import | Page | Saved block range | Round trip | Result |
| --- | --- | --- | --- | --- |
| GBK TXT | 1 | 10–45 | 5,913 ms | pass |
| DOCX | 1 | 14–52 | 6,682 ms | pass |
| PPTX | 1 | 0–38 | 5,810 ms | pass |
| PNG OCR | 1 | 0–30 | 8,706 ms | pass |

The isolated server recorded four completed `READER_SELECTION` calls: requested and served model `gpt-6.1-sol`, one physical call each. These are real subscription replies, not the synthetic receipts used by the earlier native-window tests. This check exercises the transport used by the paired reader, but it does not operate that paired Windows window.

## Remaining acceptance

- Establish the same import/source flow in the existing paired service and verify real explanation plus source navigation in its Windows window; this run proves the isolated interface only.
- Automatic registration of newly imported books still needs a source/learning setup flow; the new tool deliberately requires an existing trusted source.
- Clean Win10/11 and final MSI installation remain unverified. This server/tool slice does not change the desktop package or repeat the prior MSI size measurement.
- TON-1665 owns the illustration model and `TextbookLibrary.kt`. This slice does not edit that file. Once that model is merged, rebase and populate Word/PPT/HTML/Kindle images as already requested; PDF/EPUB extraction remains on TON-1665.
- The failed Flash-G delegation stopped for provider quota. Its prior OCR review was completed by QA-C; it is not pending and this run did not retry the exhausted pool.
