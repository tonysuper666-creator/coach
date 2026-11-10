# Reader explanation block citations

## PR142 compatibility preparation

PR148 carries only PR142's byte-identical public `VisibleScope` type and its
contract tests (from `042c242f134f6b981ef48a1ed1d40df9976c9777`), plus the store's
domain dependency. It does not carry PR142's retrieval, learned-window, tutor or
outline implementation. PR148 still targets main and can compile independently.

`ReaderExplanation.explain` requires a scope. It filters the input before making
block IDs and rechecks that same request scope in `readerSourceCurrent` before
releasing either block or whole-page snapshots. The latter also checks current
course ownership, learner/tutor permissions, exact text and import hash; callers
cannot replace this check with a permissive callback. Unknown block IDs may fall
back only to a page already retained inside this scope. Unknown page IDs fail.

Main's existing route uses a private, file-local adapter with the learner ceiling.
This preserves main's existing behavior and does not implement learned windows.
When PR142 is composed, its route supplies the section-derived `learnedThrough`
scope directly to the required-scope member, bypassing the adapter. Thus the same
frozen window governs retrieval, block creation, page fallback and delayed release.
There is no public unscoped explanation overload. PR142's learning-record mapping
and the composed tests remain prerequisites for claiming unread-page protection.

Composition order: start at frozen main, merge PR142's exact head, then PR148's
new head. The common type/test files must merge identically; neither branch is
the other's PR base. Verify the actual composed tree, including
`ReaderScopedCitationsTest`, `VisibleScopeBoundaryTest`, existing citation and
three-stage timing tests. Keep real textbook sampling and click-to-draw acceptance
separate from these synthetic checks.

`ReaderExplanation` keeps the existing structured paragraphs and citation gate.
For learner-visible source pages, `ReaderCitationLayout` reads completed layout
caches from `READER_V2_LAYOUT_CACHE` (default `~/Coach/state/reader-v2-layout`).
Set this to the layout pipeline's cache root before starting the candidate server;
it is the directory containing document directories with `latest.json`, not a
document or version directory. The reader never builds or edits this cache.

A block requires one unique cache version pinned by full file hashes, the physical PDF page, trusted reading order, and an
unambiguous text range in the authorized `source_pages.text`. Layout text selects
the range; it cannot replace source evidence. Whitespace may collapse for the
match; case and punctuation must match. Repeated/overlapping matches, unsupported
blocks, rotated pages, malformed/competing caches and missing hashes fail closed
to an explicitly labelled page. `ReaderAnchorResolver` captures and resolves the
range against a same-version partial page index. No new persistent anchor store
or document-wide completeness claim is introduced.

Existing course imports hash Markdown, whereas layout caches hash PDFs. These
are different digest domains. For a transformed import, the local library owner
must verify the Markdown/PDF provenance and physical page correspondence, then
provide `source-bindings.json` in the cache root. It is private runtime metadata,
never a checked-in mapping of real books. Its synthetic example is:

```json
{"schema_version":1,"bindings":[{"source_doc_id":"synthetic-book","source_sha256":"<full imported Markdown SHA-256>","pdf_sha256":"<full original PDF SHA-256>"}]}
```

Both hashes must be 64 lowercase hex characters. A binding is valid for that
exact source ID and imported version only; duplicate bindings fail closed. When
no binding matches, only a direct identical file hash can establish provenance.
The anchor index version and model token include both digests. Binding metadata
does not grant visibility or substitute PDF/cache text for authorized source text.
Do not create bindings by matching titles or similar passages. The author found
no direct hash intersection between the current visible imports and the existing
10-document layout caches; independent QA must establish provenance first.

Opaque model IDs bind document, full version, page, layout block and exact source
snapshot/range. The server renders short citation labels and returns verified text
snapshots. Unknown block IDs can select only an already authorized page fallback;
unknown page namespaces and missing paragraph citations fail the existing gate.
Before returning delayed answers, the production route rechecks text, version and
current browsing/tutor permissions. Keep the route's scope selection when rebasing
over the separately owned visible-scope change (PR142).

The actual `WeeklyReader` source buttons open these snapshots without another
request. Old page-only answers retain the guarded page fetch. Closing the card
preserves the question/reading state. Long originals scroll while return remains
reachable. Successful content draws emit only:

```text
COACH reader.citation status=block elapsed_ms=...
COACH reader.citation status=page elapsed_ms=...
```

The clock starts in the click handler and stops after the first text draw, once
per click. A missing page emits `status=missing` after drawing the failure message;
this is never a content-visible success. Receipts contain no source IDs, version,
book title, selection, original text or generated answer. Do not collect general
application logs as citation evidence; extract only these receipts.

## Candidate checks (Mac)

```sh
tools/dev/heavy --local ./gradlew :server:app:test \
  --tests 'com.coach.server.ReaderBlockCitationsTest' \
  --tests 'com.coach.server.ReaderExplanationTest' \
  --tests 'com.coach.server.ReaderTimingTest' \
  --tests 'com.coach.server.SourceLookup*' \
  :server:store:test --tests 'com.coach.store.ReaderSourceSnapshotTest' \
  --tests 'com.coach.store.SourcePageStoreTest' \
  :shared:ui:jvmTest --tests 'com.coach.ui.ReaderCitationCardsTest' \
  --tests 'com.coach.ui.WeeklyReaderTest'
```

The UI suite clicks real source buttons, checks content and return state, and
separates blocks, fallback pages, legacy page loads and missing resources. Its
timings and screenshots use invented material; they do not establish corpus hit
rate or real textbook latency.

## Independent acceptance still required

At the exact candidate head, a reviewer from a different model vendor must fix
50 references across 10 local textbooks and the trusted-order criterion before
running them. Record only neutral textbook IDs, physical page numbers, block IDs
and correctness, plus excluded untrusted-page counts. Do not export book names,
original text, page images, questions or answers. Page fallbacks are not block
hits; the threshold is at least 45 correct blocks out of the fixed 50.

Measure each actual click through content draw with the receipts above. Report
sample count, maximum and distribution separately for block and page outcomes;
each successful sample must be at most 300 ms. Report missing outcomes separately.
Run applicable cross-platform checks and repeat integration checks after related
changes enter main. Local synthetic checks alone do not complete TON-1465.
