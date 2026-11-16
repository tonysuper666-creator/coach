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

Given that probe's JSONL and pipeline-5 page JSON, join them with:

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

```sh
python3 -I -m unittest discover -s tools/reader-v2/reflow -v
tools/dev/heavy --local python3 tools/reader-v2/reflow/verify_pdf_payload.py \
  --pdfbox-classpath "$PDFBOX_CLASSPATH"
DOCLING_ARTIFACTS_PATH="$DOCLING_MODELS" HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  tools/dev/heavy --local "$DOCLING_PYTHON" \
  -m unittest discover -s tools/reader-v2/layout/tests -v
```

The 33 reflow tests cover diff accounting, private-output guards, stale identities,
UTF-16/surrogate mappings, discontiguous ranges, table cuts/headers/rowspans,
exact cell/glyph evidence and its refusal cases, touching vs gapped PDF rules,
caption fragment references and self-authored EPUB/Word structure/byte retention.
The Java integration compiles with `--release 17`, generates four self-authored
PDF pages and compares against an independent plain PDFTextStripper baseline:
120 glyph spans, segmented table rules, borderless table/header evidence,
callout/formula, interleaved columns, negative
MediaBox/CropBox and rotation. It uses known layout boxes to isolate mapping
from model quality; the 26 layout tests separately exercise real Docling on
synthetic PDFs with local models. Set the existing model path explicitly when
the default offline cache is empty. Real geometry still needs private visual
review; product offset regressions and the 32 reader UI cells remain unverified.
