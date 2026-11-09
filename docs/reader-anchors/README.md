# Reader sentence anchors, wire contract v1

This is the independent TON-1423 foundation. It adds `com.coach.domain.reader`
and `com.coach.store.reader`; it does not change `SourceAnchor`, the study ledger,
existing reader routes, Main.kt, Mainline.kt, or MainlineScreen.kt. Native reader
adapters connect this module in TON-1426/1427/1431 after the existing reader foundation
is merged. The same wire contract serves ordinary iPhone/Android, Fold8, Duo,
and Mac/Windows Compose Desktop. All checked-in text and vectors here are synthetic.

The text selector follows [W3C TextQuoteSelector](https://www.w3.org/TR/annotation-model/#text-quote-selector).
EPUB locators use [Readium's href, locations and text fields](https://readium.org/architecture/models/locators/).
The enclosing document/version, ranges, evidence, and coordinate convention are
Coach's contract, not a claim to implement the full Web Annotation protocol.

## Identity and page labels

- `DocumentVersion.documentId` is the library's stable document identity across
  editions. `sha256` is lowercase SHA-256 of the original file bytes, not its
  title, extracted text, current viewport, or import path. `format` is PDF/EPUB.
- `ReaderAnchor.id` stays fixed across font changes and edition resolution.
  Its captured version/selectors are immutable. `schemaVersion` is 1; a higher
  major version fails decoding. Unknown optional keys are ignored.
- `pdfPageIndex0` is a physical zero-based page index. A displayed/API physical
  page number is `pdfPageIndex0 + 1`. Printed labels are opaque, case-sensitive
  strings such as `iv`, `123`, or `A-2`. They never change that base.
- `VerifiedPageLabels` belongs to one exact `DocumentVersion`, declares the PDF's
  physical page count, and records a local verification/import evidence ID.
  Adapters must populate it only from a checked map for that document. Missing
  labels/pages return UNKNOWN, duplicate labels AMBIGUOUS, and invalid physical
  indices OUT_OF_RANGE. There is no global offset, implicit numeric sequence,
  Roman numeral conversion, or guessed page 31 offset.
- `betweenLabels` returns an inclusive physical range between two uniquely
  known labels. Unmapped physical pages make the range UNKNOWN; the returned
  entries are only the checked subset. A missing printed label is not filled in
  (a scan may jump from printed `1` to `3`).

## Text and EPUB

`ReaderDocumentIndex` is an import adapter's local plain-text/geometry snapshot.
PDF units each represent one physical page; `complete=true` requires every page
index in the declared count. EPUB units each represent one publication resource
href; the adapter must include the whole reading order before declaring complete.
`text=null` means no usable text layer; `""` means a known empty layer. Neither
OCR, PDF text extraction nor Docling is implemented here (TON-1424 owns import).

The resolver searches the full declared index for `exact`, then requires every
supplied prefix/suffix to match immediately. It resolves only one surviving
candidate. It reports both exact and context candidate counts, searched unit
count, completeness, and an explicit proof reason. This is a confidence basis,
not a probability. Page number, original range, locator progression and boxes
never break a tie. Changed context remains unresolved even if exact occurs once.

`reader-whitespace-v1` collapses runs of U+0009..U+000D, U+0020 and U+00A0 to one
space, preserving a space at either edge. It preserves case, punctuation and all
other code units; it performs no fuzzy, compatibility, or accent folding. Match
ranges map back to the **original unit text**, not the collapsed string. Ranges
are half-open **UTF-16 code-unit offsets**: Kotlin string indices, Swift
`String.UTF16View`, and JS string indices agree. A Python consumer must slice
UTF-16 bytes, not its code-point string indices. Synthetic vectors include CJK
and an emoji before the target to expose this distinction. Plain text must be
captured in reading order before viewport wrapping; display-only line breaks,
font size, and screen pagination must not become stored identities.

EPUB `ReaderResource.href`/`ReaderLocator.href` names a publication resource and
contains no `#fragment`. Fragments go in `locations.fragments`; the locator also
round-trips Readium progression/position fields and before/highlight/after text.
Position/progression are navigation hints, not proof of sentence identity and
never a permanent screen page number. When an edition changes, the resolver
searches all resources and builds a target with its new href/text; it clears the
old fragment/progression/position. It does not fabricate a new CFI or DOM range.
The native adapter uses returned text offsets to build its current DOM range.

## Geometry and safe failure

PDF point rectangles use unrotated PDF user space (bottom-left, points).
`PdfPageFrame` stores MediaBox, CropBox and the effective clockwise PDF page
rotation, limited to 0/90/180/270. `normalize` clips a point rectangle to CropBox
and converts it to `unrotated-crop-top-left-v1`: normalized [0,1] coordinates,
origin top-left, x right, y down. Page rotation is not already baked into boxes.
`displayBoxes` intersects with any additional reader trim in that canonical
space, renormalizes, and rotates once. View zoom/pixels are deliberately absent.

A current index span may supply geometry covering exactly the returned text
range. Otherwise an original box is reusable only for the **same file version,
resource and original range**, after the unique quote has been verified. Across
editions, geometry comes only from a matching current span; without it, the
target is text-only. Adapters must never substitute a paragraph's box for a
sentence's exact range. Multi-line sentence boxes may be stored in one span.

Only `status=RESOLVED` has a non-null `target`. If a quote is deleted, ambiguous,
has changed context, the index is partial, or any resource lacks usable text,
`target` is null. Status is UNRESOLVED for the captured version, STALE for another
version. A page/box-only legacy anchor has MISSING_QUOTE and cannot claim a known
sentence. Native UI should say “原句改过了，无法确认” or the more specific failure
reason, retaining the old note and offering the old edition separately.

This conservative first version does not fuzzy-match edited sentences, infer
hyphenation, stitch a quote across page/resource boundaries, OCR scanned pages,
or resolve a partial scanned document's uniqueness. Such cases fail explicitly.

## Local persistence and integration

`ReaderAnchorStore(privateDirectory)` owns a separate `reader-anchors.sqlite`.
The directory must be private and scoped to the authenticated library/account;
do not expose it directly or put it in Git/attachments. `register(index)` is
idempotent for equal snapshots; a different extraction/map for the same file
version throws `ReaderVersionConflict`. Re-extraction requires a deliberate
future index-revision migration, not silent replacement of existing evidence.

`save(entry)` creates revision 1. An edit supplies `expectedRevision` equal to the
entry revision; SQLite compare-and-swap rejects stale device updates with
`ReaderAnchorConflict`, preserving the current note/progress. The captured anchor
cannot be replaced by a client edit. `resolve(id,targetVersion)` computes and
persists a separate result from the registered index. It never updates the
captured entry, note, progress, or revision. Unknown indexes/anchors fail without
writing a result. There is no last-writer-wins edition rebinding and no automatic
production migration or deployment.

To connect existing routes later: obtain the original file digest and verified
map from the local library, supply a complete import snapshot, capture quote and
range in extraction coordinates, persist the entry, then call `resolve` for the
currently opened version. Render/flash only its non-null target, use the current
native layout to translate its text range, and keep the return navigation state
outside the permanent anchor. The existing `SourceAnchor` alone is insufficient
to create a verified sentence; a legacy bridge must return unresolved until text
and version evidence exist. Existing reader protocol DTOs remain unchanged.

## Reproduction and limits of this evidence

On winbox, use these foreground checks. The PowerShell runner uses the existing
`Global\CoachHeavySlot0/1` mutexes, rejects memory below 4096 MiB, runs at Idle/
low priority with repository Gradle memory settings, and requires existing SDKs
and offline caches. It does not use a Mac lock or access the desktop session.

```powershell
powershell -NoProfile -File tools/reader-anchor/check.ps1 -Phase shared
powershell -NoProfile -File tools/reader-anchor/check.ps1 -Phase store
python tools/reader-anchor/verify_contract.py
```

The first command runs `:shared:domain:jvmTest :shared:protocol:jvmTest`; protocol
currently has no test sources. The second runs only `:server:store:test --tests
com.coach.store.reader.ReaderAnchorStoreTest`, the new platform-independent
SQLite module. Logs are under `build/reader-anchor-check`, XML/reports under the
normal module build paths. Full `server:*` suites remain Mac work.

From Mac, the equivalent permitted entrypoints are:

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:protocol:jvmTest --offline
tools/dev/heavy ./gradlew :server:store:test --tests com.coach.store.reader.ReaderAnchorStoreTest --offline
python3 tools/reader-anchor/verify_contract.py
```

The Kotlin tests cover unique migration, ambiguous/deleted/changed/scanned/
partial negatives, overlapping occurrences, original UTF-16 offsets, EPUB
relocation, map gaps/ranges, all four rotations, trim, JSON vectors, restart,
two-connection stale-write conflicts and note/progress preservation. The Python
consumer independently checks the promised original sentence at the wire's
UTF-16 range. It does not claim Swift/iOS, actual font reflow, live PDF/EPUB
extraction, real textbook or holdout-exam acceptance.

The whole-phase gates (six Duo screenshots, question-to-sentence navigation and
return, actual font/layout changes and edition replacement, real-book open-time
measurements and prior flip/restart/cross-device gates), plus ordinary phone,
Fold8 and desktop layout/interaction evidence, remain TON-1426/1427/1431/1428
work. This isolated module passing does not satisfy those gates.
