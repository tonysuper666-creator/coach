# Reflow experiments

Local measurement tools for TON-1669. These do not change the reader or its import
format. Inputs, extracted text, glyph coordinates, manual annotations and page
overlays are private. The output directory must resolve outside this repository.
Only aggregate numbers belong in the design report.

## Run

Requires Java 17+, the desktop application's PDFBox 3.0.8 jars (`pdfbox`,
`pdfbox-io`, `fontbox`, `commons-logging`) and Python 3.10+. For B, use the existing
Docling environment and pre-fetched models described in `../layout/README.md`.
For private overlays, that environment also supplies pypdfium2 and Pillow.

```sh
tools/dev/heavy --local /path/to/docling-venv/bin/python \
  tools/reader-v2/reflow/benchmark.py \
  --pdf /private/material/book.pdf \
  --pages 46,49,51 \
  --out /private/state/reflow-run \
  --pdfbox-classpath "$PDFBOX_CLASSPATH" \
  --models-dir /private/models \
  --review /private/state/manual-review.json \
  --sheets
```

Use the OS classpath separator (`:` on Mac, `;` on Windows). With no
`--models-dir`, B is unmeasured, even if the output directory contains files from
an older B run. Docling runs with `HF_HUB_OFFLINE=1` and
`TRANSFORMERS_OFFLINE=1`; models must already be present. The tool has no API/VLM
route and never sends pages to a remote service.

Outputs:

- `metrics.json`: per-page counts, code-point edit-script counts and timings;
- `ac-private.jsonl`: canonical text, glyphs and C candidate rectangles;
- `docling/*.json`: existing B pipeline output, including extracted text;
- `review-*.png`: original/B/C candidate boundary overlays, **not reader UI**;
- compilation artifacts and private diagnostic logs.

Do not commit or attach any of these outputs from real textbooks. An output
directory outside this checkout is an accidental-commit guard, not an upload or
filesystem access policy. Choose a private, unsynced location.

## Interpretation

A uses the current PDFTextStripper settings and cleaning. C adds paint/image and
math-font-line rectangle proposals without replacing A's text. Zero changed
characters for A/C is therefore by construction; it does not establish source
accuracy or validate selection/highlight offsets. C is an incomplete prototype:
there is no grid/column detector, production anchor mapping or crop renderer.
Rotation, different CropBox/MediaBox, clipping and shading require page fallback.
Candidate rectangles remain in diagnostic output even on fallback pages.

B reuses `layout_pipeline.py` rather than a new model configuration. Its diff is
the hypothetical replacement of A by B's ordered text/table cells joined with
newlines. Whitespace counts. `changed` sums insertions, deletions and the larger
side of **each** replacement from `difflib.SequenceMatcher(autojunk=False)`.
This is a deterministic edit-script measurement, not Levenshtein distance, a
character-error rate or an offset mapping. Report all component counts if making
claims about text changes.

Times are A extraction, B conversion/schema generation, and C's incremental
analysis after A. JVM startup/compile and B engine construction are separate;
none includes reader rendering, crop rasterisation, model installation or API
latency. Reusing a model cache is not a clean-install benchmark.

Optional manual review is a JSON object keyed by physical page number, e.g.:

```json
{
  "1": {
    "special_objects": 2,
    "A_complete_region_candidates": 0,
    "B_complete_region_candidates": 1,
    "C_complete_region_candidates": 1,
    "reading_order_errors": {"A": null, "B": null, "C": null, "D": null},
    "scope": "manual_conservative_region_boundary_screen_not_reader_render_acceptance"
  }
}
```

The runner records these human observations; it cannot regenerate them from
counts alone. A complete candidate must enclose a whole object, including its
essential labels, formula lines and knowledge-box header/frame. Nested formulas
inside a counted box are not counted twice. Keep unknown reading-order counts
`null`; a pipeline gate is not a manual reading-order assessment.

For the report's cross-block screen, compare the original page with A's canonical
text and B's ordered blocks (including figures/formulas without text). Count one
group for repeated interleaving of a caption with the same figure or sidebar with
body text; do not count the same figure's detached axis title again. Count
separate affected figures or body paragraphs separately. A table title moved
from above to below its table is one error. Adjacent floating figures may precede
or follow a complete paragraph, but must not interrupt it. Exclude headers,
footers, within-table cell order, within-formula linearisation, missing glyphs and
duplicates. These are confirmed lower bounds, not a complete readability score.
Save per-page event notes privately beside the counts. C's text order equals A
by construction; its eventual crop/body reading stream remains unmeasured.

For the separate structural-internal review, inspect every ordered B block,
every output table-cell entry and every formula region against the original
page, alongside the full A text. Save per-page coverage and observations
privately. Report confirmed structural defects separately from unresolved
orientation or spatial-association risks. Do not add these page observations to
the cross-block error-group counts: their units differ and some defects overlap.
Checking all entries does not establish character accuracy, formula semantics,
independent review or a working rendered reading stream.

## Existing environment and offline timing

`platform_probe.py` measures an already provisioned Docling environment and
pre-fetched models. It does not install packages or download models. Use the
same self-authored PDF on each machine; never transfer real textbooks to make a
platform comparison. Generate synthetic files with the existing
`../layout/make_synthetic.py`, then copy the exact `mixed.pdf` (including its hash)
to the second machine. Fresh generation may have a different PDF timestamp/hash.

```sh
tools/dev/heavy --local /path/to/docling-venv/bin/python \
  tools/reader-v2/reflow/platform_probe.py \
  --pdf /private/synthetic/mixed.pdf --pages 1,2,3,4 \
  --models-dir /private/models --out /private/state/platform-run --repeats 2
```

In an approved Windows working directory, invoke the same script with the
existing environment's Python, for example:

```powershell
& D:\work\venv\Scripts\python.exe tools\reader-v2\reflow\platform_probe.py `
  --pdf D:\work\synthetic\mixed.pdf --pages 1,2,3,4 `
  --models-dir D:\work\models --out D:\private-state\platform-run --repeats 2
```

Output is `platform-metrics.json` plus private per-page JSON. The receipt records
package versions, PDF hash, existing venv/model logical file bytes, engine
import/construction time and each page's first/repeated conversion time. The
first conversion may load models lazily; neither it nor a repeated process is a
clean-machine cold-start benchmark. Footprints exclude base Python, installers
and external download caches; they are not filesystem allocation, minimal
dependencies or the product's package-size increase.

Before importing Docling the process sets HF/Transformers offline and rejects
Python socket `connect/connect_ex`, recording attempted connections. Native
extensions and subprocess network access are not sandboxed; this is not an OS
firewall or packet-capture proof. No API route is added. Block counts/status are
diagnostics, not manual preservation or reading-order acceptance.

## Fresh dependency installation

`install_probe.py` creates a new venv on an existing machine, with system site
packages excluded. It installs explicit top-level pins using isolated pip with
its cache disabled, saves resolved dependency versions and private pip output,
then copies pre-provisioned local models. The output directory must be new and
outside this checkout. Package installation requires network access; model
download time is **not** measured.

```powershell
& D:\work\base-python\python.exe tools\reader-v2\reflow\install_probe.py `
  --out D:\private-state\fresh-install --models-dir D:\work\models `
  --packages docling==2.149.0 torch==2.15.0 transformers==5.22.2 pypdfium2==5.15.2

& D:\private-state\fresh-install\venv\Scripts\python.exe `
  tools\reader-v2\reflow\platform_probe.py `
  --pdf D:\work\synthetic\mixed.pdf --pages 1,2,3,4 `
  --models-dir D:\private-state\fresh-install\models `
  --out D:\private-state\fresh-install\verify --repeats 2
```

`install-metrics.json` separates empty venv bytes, installed venv bytes, their
dependency increment, model bytes, base Python bytes, pip time and local model
copy time. These are logical file sizes, not allocated disk space or a product
installer. Base Python is already installed; this is not a clean OS, a minimal
dependency search or a model-download benchmark. Transitive packages are
resolved at run time, so compare the saved versions before comparing runs.
Use the new venv in the second command to verify it can actually convert pages;
a successful pip exit alone does not establish a working runtime.

## Model download with empty application caches

`model_download_probe.py` uses an already installed Docling environment to
download layout, tableformer and RapidOCR models. The layout group includes all
default engine variants; this is not a minimal model set. Optional formula,
picture-classifier and VLM models are disabled. No document input is accepted.

```powershell
& D:\work\venv\Scripts\python.exe tools\reader-v2\reflow\model_download_probe.py `
  --out D:\private-state\model-download --timeout 300

& D:\work\venv\Scripts\python.exe tools\reader-v2\reflow\platform_probe.py `
  --pdf D:\work\synthetic\mixed.pdf --pages 1,2,3,4 `
  --models-dir D:\private-state\model-download\models `
  --out D:\private-state\model-download\verify --repeats 2
```

The output directory must be new and outside the checkout. The child process
gets fresh HF/Transformers/Torch/XDG cache locations; implicit hub authentication
is disabled and inherited hub tokens/endpoint/offline overrides are removed.
Do not pass credentials. This downloads public model files, without sending
documents to a model service.

`download-metrics.json` records package versions, initial empty output, child
exit code, wall time, model footprint and separate download-cache footprints.
The private log retains failures; a timeout records exit 124 and partial files.
`model-files-private.json` records relative file names, logical bytes and SHA-256
for the exact downloaded files. Model revisions are resolved at run time, so
retain this private manifest when comparing runs. Wall time includes child
imports and hub metadata requests, but excludes the later manifest hashing.
Footprints include model-directory metadata; they are not network traffic or
filesystem allocation. Logs, receipts and manifests stay outside Git.

OS, Python, installed packages and system/network caches already exist; this
does not measure a clean OS or product installation. Use the second command to
check the downloaded files can convert the synthetic PDF under the documented
offline constraints. Preserve failed attempts as well as successful receipts;
download success alone does not prove conversion success.

## Source mappings and structural payloads

These are extraction experiments, **not production assets or reader support**.
All output includes private source content and must remain outside Git. Use new
output directories for each run; neither command overwrites previous evidence.

`PdfReflowProbe.java` now emits document SHA, physical page/frame, exact UTF-16
glyph spans, unmatched stripper ranges and painted horizontal rules. It uses the
same stripper settings and cleaning as before. A word whose presentation-form
expansion does not match the stripper's output has null glyph offsets; it must
not be located by searching for possibly repeated words.

Given that probe's JSONL and pipeline-6 page JSON, join them with:

```sh
python3 tools/reader-v2/reflow/source_payload.py \
  --probe "$PRIVATE_STATE/probe.jsonl" \
  --layout "$PRIVATE_STATE/layout-pages" --out "$PRIVATE_STATE/source-payloads"
```

Document hash, physical page and frames must match before any output is written.
Each payload keeps the source `text` unchanged and hashes its UTF-16 bytes.
Source regions, cell regions, row bands and cut positions use normalized,
unrotated CropBox coordinates. Schema-1 payloads retain:

- `source_ranges`: exact half-open UTF-16 intervals selected by whole glyph
  boxes. Only intervening whitespace may join intervals. Partial/unmapped
  glyphs are ambiguous; interleaved body text keeps intervals discontiguous.
  `replacement_range`, `anchor_offset` and `hide_source_allowed` are candidate
  evidence only: production still needs boundary review, nonoverlapping blocks
  and persistence. A discontiguous or untrusted-order block cannot hide text.
- Table cells with row/column spans and header flags. `row_breaks` require a
  complete single-page grid and usable cell geometry. Evidence is either a
  unique painted PDF horizontal rule (including exactly collinear, touching
  cell-edge segments), or a gap verified against every cell's exact canonical
  glyph slices, ignoring whitespace only. For the latter, every table glyph
  must lie wholly within exactly one cell; reused/overlapping source slices,
  mismatched cell text and unmapped or partial-surrogate offsets reject the
  evidence. Model-box gaps alone never suffice. A cut cannot cross any glyph
  or rowspan; an empty adjacent row has no glyph-gap evidence. A matching cut
  after all leading header rows supplies `repeat_header_region`. Multi-page, clipped,
  rotated or unsupported geometry keeps logical cells but supplies no cell
  regions/cuts. `verified_boundaries` certifies these geometric checks only;
  it does not establish correct model row assignments or permit splitting a
  semantically incorrect table. Borderless tables may have no certified cut.
- `caption_groups`: explicit, unique, same-page Docling references to caption
  blocks. Missing/ambiguous/duplicate references remain unresolved. `source_parts`
  preserves each referenced caption fragment's own source region and exact
  ranges in reference order, including explicitly labelled continuations.
  `placement_status=source_parts_verified` requires trustworthy layout order,
  usable regions and mapped/discontiguous ranges for every part. This status
  certifies the fragment mappings, not the semantic continuation or the owner's
  placement. Group `replacement_range` is always null: intervening body text
  is never enclosed in a replacement span. `keep_together` is an association
  request, not proof of same-page rendering. Unlabelled continuations, final
  placement and caption deduplication still require verification.

Different nonrotated CropBox/MediaBox frames can be converted when both sources
agree; this is distinct from legacy C's conservative benchmark fallback flag.
No crops are rasterized or saved as `BookIllustration`, and no shared model,
canonical importer or UI flow is changed.

The same command also writes private `table-continuations.json`. This separate
schema-1 batch payload can certify `verified_source_fragments` only for one
explicit source table with complete occurrences on consecutive physical pages.
Both `source_scope` and `source_ref` must match; the scope identifies one Docling
conversion graph, not the PDF. Old page JSON remains usable for single-page
payloads, but missing scopes never certify a continuation. Separate tables are
never joined by proximity, matching column widths or matching header text.

Because Docling cell boxes lack page IDs, continuation evidence requires exactly
one complete ordered row partition whose every cell matches the canonical glyph
slices on its assigned page. Every table glyph must belong to exactly one cell;
extra/partial/unmapped/reused glyph ranges, mismatched text or ambiguous page
assignments reject the entire group. Every within-fragment row cut and the first
header boundary must also have the existing single-page evidence. Rowspans,
rotation, untrusted order, incomplete provenance and unsafe geometry are refused.
Limits of 500 rows, 32 pages and two million cell/glyph verification comparisons
bound the partition search; hitting a limit returns no partial fragments.

Each accepted fragment has a half-open global row interval, page-local cell/cut
regions and a canonical text hash. `header_mode=source` means that fragment
already has the verified source header. `repeat_first_fragment` requests a visual
copy of the first fragment's header, whose descriptor retains its source page,
region, exact UTF-16 ranges and text hash. It never creates a new text range at
the destination. All continuation `replacement_range` fields are null and the
group's `hide_source_allowed` is false. This is geometry/source evidence, not a
semantic table certificate or a renderer. The ordinary page payload still refuses
multi-page cell geometry; consumers must explicitly understand this batch payload.
Docling may emit independent page tables instead of one multi-page source item;
those remain unassociated and cannot use this continuation path.

### An explicit source independent of the model

`TaggedTableProbe.java` reads an existing PDF's structure tree and page MCIDs.
One standard `Table` with direct `TR` / `TH` / `TD` children supplies an explicit
cross-page identity even when Docling emits unrelated page tables. Both the
forward structure references and the page's reverse `ParentTree` owners must
agree. Source paths are local to the output's document SHA-256; never join paths
across documents. This is a conservative tagged-PDF subset, not a tag generator
or a heuristic for untagged books.

Keep all outputs private, as for the ordinary probe:

```sh
mkdir -p "$PRIVATE_STATE/classes"
javac --release 17 -cp "$PDFBOX_CLASSPATH" -d "$PRIVATE_STATE/classes" \
  tools/reader-v2/reflow/PdfReflowProbe.java \
  tools/reader-v2/reflow/TaggedTableProbe.java
java -cp "$PRIVATE_STATE/classes:$PDFBOX_CLASSPATH" PdfReflowProbe \
  "$PRIVATE_PDF" "$PHYSICAL_PAGES" > "$PRIVATE_STATE/probe.jsonl"
java -cp "$PRIVATE_STATE/classes:$PDFBOX_CLASSPATH" TaggedTableProbe \
  "$PRIVATE_PDF" "$PHYSICAL_PAGES" > "$PRIVATE_STATE/tagged-native.json"
python3 tools/reader-v2/reflow/tagged_tables.py \
  --native "$PRIVATE_STATE/tagged-native.json" \
  --probe "$PRIVATE_STATE/probe.jsonl" --out "$PRIVATE_STATE/tagged-tables.json"
```

The classpath example uses the POSIX separator; use `;` on Windows. Request all
physical pages referenced by a table. The consumer checks the independent
ordinary probe's document hash, exact canonical text, page frame and complete
glyph offsets/boxes before accepting evidence. MCIDs are tracked through the
same canonical stripper's actual `TextPosition` objects; repeated MCID sequences,
lost/merged glyphs, `ActualText`, forms, clipping and unsafe frames are refused.
Missing/reused references, mismatched reverse owners, nonconsecutive pages,
cross-page cells, overlapping rows, partial UTF-16 boundaries, row/column spans
and verification-budget exhaustion produce no partial table. Role maps and
non-direct table hierarchies are currently unsupported.

`source_kind=pdf_structure_tree_mcid` and `verified_tagged_association` mean the
source tags' rows/cells have exact canonical slices on their physical pages.
They do not establish that the author tagged the table correctly. The output
retains each cell's disjoint ranges, source path, MCIDs, TH flag and tight glyph
envelope, plus text-safe row-gap cuts. Tight glyph envelopes exclude borders,
padding and nontext content, so **they are not crop boundaries**: `crop_ready`
and `hide_source_allowed` remain false, `source_region`, `repeat_header` and all
`replacement_range` fields remain null. TH flags alone do not implement repeated
headers. This separate payload does not alter model groups or ordinary page
payloads, and is not connected to automatic import, persistence or display.

For native packages, retain source structure before a future importer flattens it:

```sh
python3 tools/reader-v2/reflow/native_payload.py \
  --source "$PRIVATE_BOOK" --out "$PRIVATE_STATE/native-payload"
```

Supports `.epub` and `.docx`. `package.json` retains EPUB spine order and each
member's namespace-expanded element tree, attributes, mixed `text`/`tail` and
children. Exact member bytes are stored as hash-named `blobs`, preserving styles,
images, relationships, MathML/OMML and merged-cell metadata even if a secondary
XML member cannot be parsed. Archive names are metadata, never output paths;
external resources are not fetched. This is source retention, not a renderer or
reconstruction of Word style inheritance. `offset_mapping_status` remains
`requires_import_integration`: native structure-to-`BookPage.text` mapping and
production storage still need implementation and regression tests.

## Checks

### Borderless B/C crop review experiment

`crop_candidates.py` tries the original B boxes, C regions and overlapping
pairwise unions (at most 1.5 times the larger proposal's area). A union is an
alternative, **not agreement that an object is complete**. Supply a fresh
`PdfReflowProbe` file and the existing local layout JSON files:

```sh
tools/dev/heavy --local "$DOCLING_PYTHON" tools/reader-v2/reflow/crop_candidates.py \
  --pdf "$PRIVATE_PDF" --probe "$PRIVATE_STATE/probe.jsonl" \
  --layout "$PRIVATE_LAYOUT" --out "$PRIVATE_STATE/new-crop-review"
```

The new output directory must be outside Git. JSON/PNGs contain private source
ranges and page content; keep them on the source machine. The CLI renders at 2x,
searches 2–8 points outward and checks a two-point edge band: every RGB channel
must be at least 250, and the interior must contain detectable ink. It verifies
whole glyph containment and keeps exact disjoint source ranges. It refuses stale
document/page frames, clipped/unsupported/rotated or incompletely mapped pages,
old probes missing safety facts, out-of-page edges and excessive budgets
(512 proposals, raster long edge 4096). Very pale ink can escape the threshold.

`boundary_verified` means these raster/mapping checks passed. Detached labels can
remain outside a white edge; body paragraphs can be fully enclosed. Consequently
**every** candidate keeps `semantic_status=unverified`, `import_allowed=false`,
`hide_source_allowed=false` and `replacement_range=null`. This experiment is not
connected to production import, storage, source-text replacement or rendering.
The report records actual manual false positives and incomplete objects; do not
batch-import this output. It does not associate cross-page tables.

### Production native-source smoke check with independently authored books

`NativeSourceProbe.java` calls the existing production extraction/mapping APIs.
Build the desktop classes for the checkout under review, then supply their full
runtime classpath (compiled classes plus dependencies):

```sh
mkdir -p "$PRIVATE_STATE/native-classes"
javac --release 17 -cp "$DESKTOP_CLASSPATH" -d "$PRIVATE_STATE/native-classes" \
  tools/reader-v2/reflow/NativeSourceProbe.java
java -cp "$PRIVATE_STATE/native-classes:$DESKTOP_CLASSPATH" NativeSourceProbe \
  "$PRIVATE_DOCX" docx
java -cp "$PRIVATE_STATE/native-classes:$DESKTOP_CLASSPATH" NativeSourceProbe \
  "$PRIVATE_EPUB" epub
```

Use `;` instead of `:` on Windows. Only a zero exit is a passing result; preserve
each input's exit code separately. This checks ZIP member hashes, namespace/name
and exact XML element paths, unique source identities (including EPUB spine
index), valid UTF-16 range endpoints, stale-text refusal and repeat extraction.
It prints aggregate counts only; the production parser can still fail before
mapping. It does not independently verify characters, semantic structure,
rendering or persistence. EPUB member identity checks use the production
`EpubXml` parser, then independently walk paths and check original-byte hashes;
this is not an independent XML parser audit. The initial real EPUB trial failed
at the DOCTYPE prohibition. With the guarded compatibility fix, the unchanged
original now passes (65 text pages / 21 members / 1,777 ranges); the report keeps
both the original failure and the subsequent result. The real Word trial still
passes (72 text pages / 1,559 ranges).

### Native-source index persistence

New EPUB/DOCX imports also try to retain the exact mapping in the library's
`book_native_sources` table. `TextbookLibrary.nativeSources` reads it after
reopening; `retainNativeSources` explicitly backfills an old book from its
retained original. The versioned index binds the original book identity, page
order/numbers and every canonical UTF-16 code unit. Stale, damaged or unsupported
records return no index. Backfill verifies the original archive before and after
mapping; it never searches for a new text location. Index writes share the book
import transaction. A mapping/validation failure leaves ordinary text import
available; a database write failure rolls the import back.

The record retains member hashes, XML element paths, namespaces/names, EPUB
spine indices and exact ranges, including empty and nested ranges. It is an
index into the already retained archive, **not** a flattened copy of styles,
attributes, MathML/OMML or image resources. `nativeDocument` now verifies
the indexed member hash before returning its original XML structure. No range grants source
hiding or rendering permission. Capture currently adds a second parser traversal;
large-book latency and cancellation inside that traversal have not been measured.

Build current desktop classes and run the Java 17 persistence exercise with a
new private directory outside Git (the probe deliberately changes a disposable
database and restores its pages):

```sh
javac --release 17 -cp "$DESKTOP_CLASSPATH" -d "$PRIVATE_STATE/native-classes" \
  tools/reader-v2/reflow/NativeSourcePersistenceProbe.java
java -cp "$PRIVATE_STATE/native-classes:$DESKTOP_CLASSPATH" NativeSourcePersistenceProbe \
  "$PRIVATE_EPUB" epub "$PRIVATE_NEW_EPUB_LIBRARY"
java -cp "$PRIVATE_STATE/native-classes:$DESKTOP_CLASSPATH" NativeSourcePersistenceProbe \
  "$PRIVATE_DOCX" docx "$PRIVATE_NEW_DOCX_LIBRARY"
```

Only exit 0 passes. This checks canonical pages, learning blocks and citation
results against ordinary extraction, equal reopened/backfilled indices, and
refusal of a same-length page-text change. It prints aggregate counts only.
Real originals and disposable libraries stay on their owning machine. The
exercise does not establish semantic/character correctness or reader display.

### Digest-checked native XML documents

`TextbookLibrary.nativeDocument(bookId, member)` reads an indexed EPUB/DOCX member
from the retained archive after validating the saved index against current
canonical pages. It hashes the raw member bytes before parsing with the existing
secure format parser. Every indexed element in that member must still resolve
by exact element-child path, namespace and local name, or the whole read returns
no document. Missing/changed members, excessive depth/size and unsupported XML
also return no document; cancellation propagates.

The inert tree preserves ordered mixed text, element and attribute namespaces,
prefixes, inline properties, table/MathML/OMML nodes and ancestor context. Repeated
EPUB spine occurrences share a node while retaining their separate source ranges.
Comments and processing instructions are omitted. Links and script text remain
data: nothing executes or fetches resources. The API does not resolve external
CSS, Word `styles.xml`, relationships or images, compute styles, render formulas,
rebuild indices or grant text replacement/hiding permission. Only indexed XML
members can be read. Each call checks its member, not the whole archive identity.

Read existing private libraries on their owning machine (no original contents
are printed):

```sh
javac --release 17 -cp "$DESKTOP_CLASSPATH" -d "$PRIVATE_STATE/native-classes" \
  tools/reader-v2/reflow/NativeSourcePersistenceProbe.java \
  tools/reader-v2/reflow/NativeDocumentProbe.java
java -Djava.awt.headless=true -cp "$PRIVATE_STATE/native-classes:$DESKTOP_CLASSPATH" NativeDocumentProbe \
  "$PRIVATE_EXISTING_EPUB_LIBRARY"
java -Djava.awt.headless=true -cp "$PRIVATE_STATE/native-classes:$DESKTOP_CLASSPATH" NativeDocumentProbe \
  "$PRIVATE_EXISTING_DOCX_LIBRARY"
```

Only exit 0 passes. The probe checks every indexed member and element, equal
trees after reopen, unchanged canonical pages/index/citation results, and prints
aggregate node counts and elapsed time. Counts describe payload availability,
not semantic accuracy or reader display. The real samples currently contain no
formula roots; MathML/OMML preservation is covered by synthetic tests only. Use
headless mode so the library's PDF warmup cannot keep a Mac AWT JVM alive.

### Explicit review of complete PDF objects and production persistence

`ReviewedPdfVisualsProbe.java` exercises `PdfReviewedVisuals` and the production
`TextbookLibrary.addReviewedPdfVisuals` API. It does not approve B/C candidates
automatically. Use a private PDF copy and directories outside Git:

```sh
mkdir -p "$PRIVATE_STATE/reviewed-classes"
javac --release 17 -cp "$DESKTOP_CLASSPATH" -d "$PRIVATE_STATE/reviewed-classes" \
  tools/reader-v2/reflow/ReviewedPdfVisualsProbe.java
java -cp "$PRIVATE_STATE/reviewed-classes:$DESKTOP_CLASSPATH" ReviewedPdfVisualsProbe \
  preview "$PRIVATE_PDF" "$PRIVATE_SELECTIONS" "$PRIVATE_NEW_PREVIEWS"
java -cp "$PRIVATE_STATE/reviewed-classes:$DESKTOP_CLASSPATH" ReviewedPdfVisualsProbe \
  apply "$PRIVATE_PDF" "$PRIVATE_APPROVED_REVIEWS" "$PRIVATE_LIBRARY"
```

Supply `*.selection.json` files with integer `page`, `left`, `top`, `right`,
`bottom` and `kind` (`PageVisualKind` enum name). Pixel edges refer to the
production PDFBox raster: scale 2, reduced if necessary to a 4096px maximum edge;
pages start at 1. The preview command writes original-page PNGs, individual PNGs
and `*.review.json`, all **UNREVIEWED**. Compare each full-size PNG with its page:
include the complete body, title, caption, notes and credits, exclude adjacent
prose, and retain one complete version of each object. The offline workspace
below records this decision without hand-editing receipts. If reviewing manually,
only then copy the chosen receipts into the approved directory and explicitly set
their decision to `COMPLETE_OBJECT_WITH_LABELS`. Keep rejected/draft receipts
separately. This is a local review declaration, not reviewer authentication or an
automated guarantee that the object is complete.

Import rechecks PDF bytes, canonical UTF-16 text, pixel selection and exact PNG
digest. Stale receipts, partial glyphs, rotated/OCR pages and same-page overlapping
objects (duplicates and nested boxes included) are refused as a set. Existing
overlapping assets also require manual resolution; the API never chooses a
largest box or silently replaces them. Exact existing supplements are idempotent.
It saves disjoint supplements and review declarations in one SQLite transaction,
preserves existing assets, and rolls back new files if the transaction fails.
Review rows are historical declarations, not a replacement for checking current
assets after later manual edits.

`apply` checks reopen equality, exact saved image bytes, unchanged canonical text,
study blocks and valid citations, and both text-hiding settings. The imported
objects have no replacement range: **all original text stays visible**. Geometry
alone cannot detect a clipped photograph; the real trial rejected such a preview
despite valid glyph mapping. No reader UI, automatic caption grouping, model approval,
cross-page table association or reader retention measurement is implemented here.
The old 66 B/C candidate receipts remain unapproved.

### Offline full-size batch review

`review_workspace.py` requires Pillow. Use a private, unsynced directory outside
**every** Git worktree; do not upload the HTML, saved decisions or real PNGs.
Start from fresh `ReviewedPdfVisualsProbe preview` output, not B/C candidate
receipts. Both output directories below must be new:

```sh
tools/dev/heavy --local "$REVIEW_PYTHON" tools/reader-v2/reflow/review_workspace.py \
  prepare --previews "$PRIVATE_NEW_PREVIEWS" --out "$PRIVATE_NEW_WORKSPACE"
# Open PRIVATE_NEW_WORKSPACE/review.html locally in a browser (file://).
# Compare the full page and crop; use 100% / 200% for small print and edges.
# Check all three statements, then explicitly approve or reject each object.
# Save progress; the browser downloads crop-review-decisions.json.
tools/dev/heavy --local "$REVIEW_PYTHON" tools/reader-v2/reflow/review_workspace.py \
  export --previews "$PRIVATE_NEW_PREVIEWS" --decisions "$PRIVATE_SAVED_DECISIONS" \
  --out "$PRIVATE_NEW_APPROVED_REVIEWS"
java -cp "$PRIVATE_STATE/reviewed-classes:$DESKTOP_CLASSPATH" ReviewedPdfVisualsProbe \
  apply "$PRIVATE_PDF" "$PRIVATE_NEW_APPROVED_REVIEWS" "$PRIVATE_LIBRARY"
```

The HTML embeds its images and scripts, opens without a server and blocks network
connections with a CSP. It shows the entire source page beside one crop, marks
other same-page candidates and supports native-size scrolling, light/dark themes,
previous/next navigation and save/resume. Opening or checking a crop never
approves it; explicit approval needs all three statements. Unchecking revokes an
approval. Two overlapping approvals are refused; the reviewer chooses one
complete version. This is an aid to human review, not semantic deduplication,
automatic label grouping, a signature or the reader's display acceptance.

Prepare checks bounded PNGs, crop hashes and pixel equality with the full-page
context. Fresh production-generated context is still a trust assumption; this
tool does not independently re-render the PDF. Export reloads current previews
and requires an exact match to saved draft identities, hashes and selections;
stale/unknown/duplicate rows or overlapping approvals fail before any output.
Pending and rejected items are omitted. Exported receipts use the existing
production schema; `apply` independently re-renders and checks the original PDF
again. Output is refused inside Git or over an existing directory; newly created
review directories use owner-only permissions where supported. Image budgets are
128 objects, 32 per page, 64 MiB encoded bytes and 64 × 1024² decoded pixels.

Browser acceptance: start with all objects pending, approve one, refuse an
overlapping duplicate, reject it, approve a disjoint object, uncheck/recheck,
save, reload and resume. Alter a saved digest and confirm resume refuses it
without changing the current decisions. Check 100% and 200% image widths, both
themes and a narrow viewport. Finally export and use production `apply`; a
successful browser save alone is not an import test. The private trial reused
the four objects independently accepted in PR #355 and its rejected photograph;
it adds workflow evidence, not new object coverage.

### Correct a crop before approving it

Use **在原页重新画框** to drag a replacement rectangle on the full page, or change
the four integer pixel edges and select **应用边界**. Reverse-direction drawing,
fit width and scrolled 100%/200% views use the original raster coordinates.
The orange dashed rectangle and right-hand image show a **draft**, not a fresh
production preview. Changing any edge revokes the old approval and checklist;
approving that draft is disabled. Discarding it also leaves the original pending.

Download **改框请求**, then regenerate and review the changed objects:

```sh
tools/dev/heavy --local "$REVIEW_PYTHON" tools/reader-v2/reflow/review_workspace.py \
  selections --previews "$PRIVATE_NEW_PREVIEWS" --edits "$PRIVATE_SAVED_EDITS" \
  --out "$PRIVATE_CHANGED_SELECTIONS"
java -cp "$PRIVATE_STATE/reviewed-classes:$DESKTOP_CLASSPATH" ReviewedPdfVisualsProbe \
  preview "$PRIVATE_PDF" "$PRIVATE_CHANGED_SELECTIONS" "$PRIVATE_CHANGED_PREVIEWS"
tools/dev/heavy --local "$REVIEW_PYTHON" tools/reader-v2/reflow/review_workspace.py \
  prepare --previews "$PRIVATE_CHANGED_PREVIEWS" --out "$PRIVATE_CHANGED_WORKSPACE"
# Open the new workspace, compare the new full page and crop, check and approve anew.
# Use export and apply above with the changed previews and newly saved decisions.
```

All output directories must be new and private. The request covers the exact old
batch, immutable draft receipts and full-page PNG hashes. Unknown, duplicate,
missing or stale rows, invalid/out-of-page edges and overlapping changed
rectangles fail before output. Only changed selections are emitted; there are
**zero approvals**. Production preview re-renders the supplied PDF and rechecks
text geometry; every new receipt starts UNREVIEWED. Old decisions cannot approve
the changed rectangle. Unchanged objects retain their separate review workflow.
Save/resume edits separately from review decisions; restoring old decisions is
refused while edits exist. A failed draft restore leaves current state intact.

This is manual grouping in a single rectangle, not automatic caption detection,
deduplication or a completeness proof. White space and valid text geometry can
still surround an incomplete object. A photo and its credit may have no single
rectangle that avoids nearby body text; retain rejection rather than importing
an unsafe expansion. The private round trip repaired a deliberately shortened
version of one previously accepted table, reproduced its complete pixels and
imported it after fresh approval. It adds workflow evidence, not new coverage;
an expanded photograph was refused by production preview. Old B/C candidates
remain unapproved, and no reader display acceptance follows from these checks.

### Plan pending selections from B/C evidence

`object_review_plan.py` requires pypdfium2 and Pillow. Use the current PDF, its
matching canonical probe JSONL and layout files named by physical page as
`00001.json`. Keep all inputs and output outside Git, local and unsynced:

```sh
tools/dev/heavy --local "$DOCLING_PYTHON" tools/reader-v2/reflow/object_review_plan.py \
  --pdf "$PRIVATE_PDF" --probe "$PRIVATE_PROBE_JSONL" \
  --layout "$PRIVATE_LAYOUT" --out "$PRIVATE_NEW_PLAN"
```

This regenerates raster boundaries instead of trusting old candidate receipts.
The private per-page JSON preserves every proposal and its evidence. Only equal
final pixel rectangles fold, retaining all aliases and vetoes. Connected overlap
groups are alternatives for comparison: no largest-box winner, semantic duplicate
claim or union of the group. Groups are metadata in the plan, not new review-page
controls. A possible prose label vetoes a selection without deleting any evidence
or canonical text. Unlabelled prose can still pass this filter.

Only explicit, uniquely owned and verified caption/note references suggest expansion.
`object_label_groups` preserves the role, region and exact ranges of each caption
and `footnote_refs` target within one conversion scope and page. Missing, shared,
duplicate, wrong-role or ambiguous references reject the entire group, with no
partial expansion. The union of that owner and all linked parts must pass the
raster/glyph checks; intervening glyphs outside those parts or a prose veto block
it. A verified footnote is exempt from the prose veto only for its own object;
unowned footnotes remain prose. Ownership is never inferred from proximity.

A note box that clips a glyph edge may be expanded by at most 4pt per edge to
contain the intersecting glyphs. This requires current safe geometry, no page
gaps, a complete mapping, and canonical slices equal to the full model note text
after whitespace removal. No text search or next-line inference is used. The
original block remains unchanged; the adjusted part records its exact ranges and
`bounded_glyph_envelope_exact_note_text` evidence. This checks local mapping, not
whether the model found every note or source line.
`complete_object_labels_unverified` remains on **every** item, even with explicit
links: model links can omit notes, sources or credits. Missing links additionally
carry `labels_not_established`. These are warnings, not completeness decisions.

`selections/*.selection.json` contains only page, kind and integer pixel edges,
with **zero approvals**. Read the plan, choose alternatives to inspect and copy
their selection files into a fresh private directory. Run the existing
`ReviewedPdfVisualsProbe preview` and `review_workspace.py prepare` commands above,
then compare the complete source page and full-size crop. The production preview
can reject a plan selection: the experimental PDFium/probe checks do not replace
PDFBox production mapping. Its existing batch command fails on an invalid member;
use a new directory with one selection to isolate refusal, and retain rejected
plans separately. Do not turn a refused plan into an approved receipt. Overlapping
alternatives can be reviewed together, but only a disjoint approved set can export
and import. Missing labels require a manual edit and fresh production preview.

Output must be new and outside all Git worktrees. Empty/duplicate/stale probe pages,
frame mismatches or a later invalid layout fail before output; a write failure
removes only the new directory. Limits are 64 pages, 4096px raster edges and
128 pending selections, at most 32 per page. This is a planning aid, not automatic
recognition, complete-object approval, import, text hiding or reader rendering.

The private 20-page trial reproduced 193 proposals and 66 boundary passes, added
8 explicit-link expansions and selected 41 pending rectangles. Zero rectangles
were exactly equal, so this did not eliminate the semantic duplicates seen in
independent review. Production preview accepted 34 and refused 7; the offline
workspace prepared those 34 on 11 pages, all pending. Of 6 expanded crops that
passed production preview, executor inspection found 5 table titles now included
but all 5 still missing lower notes/source lines. This is not independent review,
an object success rate or reader retention. No object was approved or imported.

### Glyph geometry evidence before planning

The seven refusals above shared one page with one zero-width glyph outside the
selected objects. Production extraction recorded its exact UTF-16 range as
unmapped and refused every selection on that page; the old experimental probe
omitted the gap. `PdfReflowProbe` now records missing bounds using production's
CropBox clamping and text-direction dimensions, including zero-width/height
glyphs and glyphs fully outside the crop. Canonical text and existing glyph
offsets/boxes remain unchanged.

Regenerate probe JSONL files before using `crop_candidates.py` or
`object_review_plan.py`. Both ordinary candidates and caption expansions require
`glyph_geometry_checked: true`; missing, false or non-boolean evidence is unsafe.
Any unmapped range vetoes the whole page, even outside a proposed rectangle.
This is eligibility evidence, not approval or a guarantee of production preview.

Rerunning the same private 20 pages matched production text and gap ranges on
every page. Boundary passes changed from 66 to 58; pending selections from 41 to
34. Exactly the seven previous refusals disappeared; the retained 34 selection
files were byte-identical and all passed fresh production preview, with zero
approvals/imports. All six prior caption expansions remain, including the five
tables still missing notes/source lines. Historical candidate/visual classifications
are unchanged. This correction improves refusal alignment, not object coverage.

### Explicit object notes: private follow-up

Fresh offline Docling conversions of three pages retained 6 explicit note links
for 4 owners. The other 17 historical layouts were reused. Removing the newly
retained note references, per-conversion scopes and result-level confidence left
the three layouts otherwise equal to their prior versions; the saved native
documents do not contain result-level confidence, so those three scores are null.
All 20 canonical page texts remained unchanged. The plan had 193 input candidates,
58 ordinary boundary passes, 201 total proposals and 36 pending selections. Of the
previous 34 selections, 32 were byte-identical, 2 caption-only selections were
replaced, and 4 selections were new/expanded. Fresh individual production previews
accepted all 36 on 11 pages, with zero approvals/imports. These used unchanged,
previously compiled production preview classes, not a new Kotlin build.

Executor inspection of the four full-size crops against their three source pages
found the explicit notes included and no adjacent body text. Three crops still
omit source lines. Only 2 of the 5 previously incomplete table expansions had
explicit note links; 4 of those 5 still lack source lines. This is not independent
review or a completeness rate. Every selection remains pending. Semantic
deduplication, missing source/credit association and untagged cross-page tables
remain unresolved; the old 66-candidate classification is not replaced.

### Regression commands

```sh
python3 -I -m unittest discover -s tools/reader-v2/reflow -v
tools/dev/heavy --local python3 tools/reader-v2/reflow/verify_probe_geometry.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
tools/dev/heavy --local "$DOCLING_PYTHON" tools/reader-v2/reflow/verify_object_review_plan.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
tools/dev/heavy --local "$DOCLING_PYTHON" tools/reader-v2/reflow/verify_explicit_notes.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
tools/dev/heavy --local "$DOCLING_PYTHON" tools/reader-v2/reflow/verify_crop_candidates.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
tools/dev/heavy --local python3 tools/reader-v2/reflow/verify_pdf_payload.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
tools/dev/heavy --local python3 tools/reader-v2/reflow/verify_cross_page_tables.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
tools/dev/heavy --local python3 tools/reader-v2/reflow/verify_tagged_tables.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
DOCLING_ARTIFACTS_PATH="$DOCLING_MODELS" HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  tools/dev/heavy --local "$DOCLING_PYTHON" \
  -m unittest discover -s tools/reader-v2/layout/tests -v
```

The 107 reflow tests (Pillow required for the 16 workspace/edit tests) cover diff accounting, private-output guards, stale identities,
UTF-16/surrogate mappings, discontiguous ranges, table cuts/headers/rowspans,
exact cell/glyph evidence and its refusal cases, touching vs gapped PDF rules,
caption fragment references, scoped cross-page table partitions and header copies,
batch refusal/budget cases, native MCID association/refusal cases, and self-authored
EPUB/Word structure/byte retention. The original 54 tests are unchanged; nine new
crop tests cover safe/refused boundaries, detached-label and body risks, exact
disjoint ranges, frame conversion and budgets. The new crop CLI integration uses
the existing own four-page fixture, verifies three saved crops pixel for pixel,
checks disjoint/CropBox mappings and refuses rotation and stale PDF identity.
It uses known proposals and does not measure B/C recognition.
The eight workspace tests additionally cover explicit approval/export, required
checks, stale/partial/duplicate decisions, overlap refusal, changed PNG/context,
mixed source identities, inert untrusted names/CSP and Git/symlink/overwrite guards.
Eight selection-edit tests additionally cover request/approval separation, exact
batch/page identity, stale full-page context outside the old crop, integer edges,
changed page/kind refusal, changed-rectangle approval refusal, overlap/touching,
and output guards. Nine planning tests add explicit caption expansion/refusal,
intervening prose/unowned glyph vetoes, lossless equal-rectangle folding,
transitive overlap preservation, unlinked-label warnings and stale/rotated/duplicate
layout refusal. The preceding 79 tests are unchanged. The planning CLI integration
uses the same self-authored four-page PDF and known proposals: all canonical pages
remain equal to an independent stripper baseline, 3 pending selections are emitted,
CropBox/rotation are handled and late invalid, stale, duplicate or existing-output
cases leave no partial output or overwrite. It does not run model recognition or
grant approval and does not substitute for production preview.
Two geometry tests add fail-closed legacy evidence and page-wide gap vetoes for
ordinary/caption proposals. Two existing fixtures now declare checked geometry;
their assertions are unchanged. `verify_probe_geometry.py` generates five own
pages: normal, zero-width, zero-height and two CropBox-clamped gaps. It compares
canonical text against an independent stripper, checks exact UTF-16 gap slices
and retains only the normal pending selection. It uses known layout/raster
evidence to isolate the gap contract, without models or recognition claims.
Seventeen note tests add explicit ownership/role/scope refusal, precise separate
source ranges, prose/unowned-note vetoes, bounded edge repair and exact-text
refusal, stale/unsafe geometry, and native Docling reference serialization. The
previous 90 tests are unchanged. `verify_explicit_notes.py` uses actual PDFBox
glyphs and PDFium rasters on the existing four-page fixture: canonical pages
remain unchanged, a clipped note edge yields one pending expansion, and changed,
incomplete or elsewhere text plus intervening unowned glyphs are refused. Known
layout roles isolate mapping; this does not test note recognition or completeness.
The Java integration compiles with `--release 17`, generates four self-authored
PDF pages and compares against an independent plain PDFTextStripper baseline:
120 glyph spans, segmented table rules, borderless table/header evidence,
callout/formula, interleaved columns, negative
MediaBox/CropBox and rotation. It uses known layout boxes to isolate mapping
from model quality. The second integration generates three self-authored pages,
checks three exact fragments and three row cuts, verifies source/repeat/source
header modes and refuses stale text. It also uses known source geometry and is
not a Docling table recognition test. The tagged integration instead reads actual
PDF structure/ParentTree/marked-content operators, with no injected layout JSON:
one table, three fragments and three text-safe cuts; seven unsupported/ambiguous
PDF modes plus stale/missing pages are refused, and all canonical pages match a
plain stripper. This remains a self-authored parser regression, not real-book
coverage. The 26 layout tests separately exercise real Docling on
synthetic PDFs with local models. Set the existing model path explicitly when
the default offline cache is empty. Real geometry still needs private visual
review; product offset regressions and the 32 reader UI cells remain unverified.
