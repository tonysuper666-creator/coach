# Original PDF text geometry

This is the extraction and coordinate portion of TON-1670. The original-page UI
is still pending TON-1667 and TON-1671; this change does not make PDF the default
view or expose a new reading mode.

## Contract

`PdfPageTextExtraction.extract(document, page)` reads one physical page, numbered
from 1. Its `unit.text` is byte-for-byte equal to the existing importer's
`TextbookExtraction.clean(PDFTextStripper.getText(...))`: position sorting,
paragraph separators, CRLF folding and NUL removal stay the same. Offsets are
half-open UTF-16 ranges into that exact string. A ligature or supplementary
character occupies one physical glyph box with its full UTF-16 range.

The result uses existing domain types `ReaderTextUnit`, `ReaderTextGeometry`
and `ReaderGeometry`, not a second persistent page schema. Bounds use normalized,
unrotated CropBox coordinates with a top-left origin. Text rotation is undone
when extracting; page rotation is applied once by `ReaderGeometry.displayBoxes`.
Unrecognised PDFBox text rewrites are reported in `unmappedRanges` and are not
assigned guessed locations. This includes unsupported bidirectional rewrites.
Until a rewritten run has reliable geometry, the UI must fall back to the text
view for selection on that page; it must not snap a pointer there to a nearby
mapped word and claim it selected the original text.

`PdfTextSelection.rangeBetweenPoints` accepts coordinates normalized to the
displayed page image, after its page rotation. The caller first removes viewport
scroll, spread placement, paper margins and image letterboxing. Forward and
reverse drags return the same glyph-inclusive UTF-16 range. `boxesForRange`
returns displayed boxes for highlighting or a source jump.

## Remaining integration

- TON-1665 owns `PageTextLayout`/`PageTextBox` and their storage. Map each
  `unit.spans` entry to those fields; keep `unit.text` as the page text. Call the
  extractor once per page in the import pass, rather than stripping text again.
- Wait for TON-1663 to release `TextbookLibrary.kt` before wiring extraction and
  storage. Old-book backfill must compare the extracted text to the saved page;
  never replace saved text or attach boxes to a different string.
- Wait for TON-1667 before using its shared paper header/footer/bottom bar.
- Use TON-1671's reading-way component for both paging (single/wide spread) and
  vertical scrolling (successive PDF pages). Preferences belong to that shared
  component and are per book. Preserve physical page plus UTF-16 position when
  changing modes or switching original/text views.
- Both modes must exercise the same selection, explanation, highlight, note and
  source-jump callbacks. A source jump must make the referenced boxes visible.
- User-visible original reading, per-book persistence, ten mode switches without
  position loss, real-book acceptance and live explanation are **not verified**
  by these extraction tests.

## Verification

`PdfPageTextTest` creates only synthetic material: two columns, a table, a
formula and a knowledge box, plus cropped pages with four page rotations and
rotated text. It compares text against the existing extractor, round-trips a
drag selection, checks reverse selection and UTF-16 surrogate/ligature ranges,
checks blank pages and extraction permissions, and checks rendered ink in the
glyph boxes at 100% and 150%.

The focused Windows run passed 5 tests, 0 failures/errors/skipped (exit 0), via
the unchanged `tools/dev/winbox.ps1` heavy runner in interactive console 1.
The usual `tools/dev/heavy` transport was tried first; this Windows account has
no `winbox` SSH alias. A task-local harness used `tools/dev/winbox.py`'s snapshot,
argument and artifact helpers to invoke that same runner locally, retaining its
global mutexes, isolated worktree, memory check and low priority. No repository
runner was changed.

The subsequent full `:desktopApp:test` run also passed: 418 tests in 97 suites,
0 failures, 0 errors, 5 existing skipped tests, exit 0 (388.663 seconds). These
counts were read from that run's artifact ZIP, not accumulated local reports.
The actual task-local command was `python ./verify_pdf_geometry.py :desktopApp:test`;
on a host with the configured SSH alias, the repository command remains
`tools/dev/heavy ./gradlew :desktopApp:test`.

Synthetic overlays are generated under `desktopApp/build/screens/pdf-page-text`.
The complex page and 90-degree cropped page were visually inspected at 150%:
boxes follow the original letters, including the rotated text. No real-book
content is in this change or its evidence.
