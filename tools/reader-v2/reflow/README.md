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

## Checks

```sh
python3 -m unittest discover -s tools/reader-v2/reflow -p 'test_*.py' -v
```

These check diff accounting, private-output guards and stale-run isolation. Real
page geometry still needs local visual review; synthetic block/offset regression
tests belong with the eventual production implementation.
