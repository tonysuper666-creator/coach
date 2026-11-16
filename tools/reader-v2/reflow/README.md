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

## Checks

```sh
python3 -m unittest discover -s tools/reader-v2/reflow -p 'test_*.py' -v
```

These check diff accounting, private-output guards and stale-run isolation. Real
page geometry still needs local visual review; synthetic block/offset regression
tests belong with the eventual production implementation.
