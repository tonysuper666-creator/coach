# Reader v2 · phase 1 · local Docling layout pipeline (TON-1424)

Offline, per-document-version page structure for textbooks. Nothing here calls a
remote conversion/OCR/VLM service. Docling model weights are fetched once
(`--online-models`, from the Hugging Face hub) into the normal HF cache or a
private `--models-dir`; every normal run sets `HF_HUB_OFFLINE=1`.

**Never commit the cache, models or real PDFs.** Page JSON contains book text.
Default cache `~/Coach/state/reader-v2-layout` (override `READER_V2_LAYOUT_CACHE` or `--cache`).

## Setup / run

```sh
python -m venv ~/reader-v2-venv && ~/reader-v2-venv/bin/pip install docling reportlab   # tested: docling 2.149.0, py3.12
python layout_pipeline.py --online-models --cache /tmp/c some.pdf        # first run only, downloads weights
python layout_pipeline.py book1.pdf book2.pdf --stats-json stats.json    # offline batch; rerun resumes
python -m unittest discover -s tests -v                                  # synthetic PDFs only (make_synthetic.py)
```
Exit code 1 if any document failed. Per-document stats line: pages ok/failed/resumed, seconds, cache_hit.

## Cache layout (consumers only read this; no engine needed)

```
<cache>/<doc_id>/latest.json                 {"version": "<sha16>-p7-docling2.149.0"}
<cache>/<doc_id>/<version>/manifest.json     written last = complete
<cache>/<doc_id>/<version>/pages/00001.json  one file per page, 1-based physical page number
<cache>/<doc_id>/.partial-<version>/         unfinished run; reruns skip finished pages
```
Version key = sha256(PDF bytes)[:16] + pipeline version + Docling version, so changed bytes,
pipeline or engine invalidate automatically. A new version is built in `.partial-*` and promoted
(`latest.json` switched) only when every page succeeded; a failed/forced rerun never touches the
old version or pointer.

## Page JSON (`schema_version` 1)

| field | meaning |
|---|---|
| `page_number` / `page_index` / `page_base` | physical page, 1-based / 0-based / `1`. Printed page labels are **not** inferred (unknown page ≠ guessed). |
| `coord_space`, `size`, `rotation`, `mediabox`, `cropbox` | bbox `[l,t,r,b]` in PDF points, origin = **MediaBox top-left**, y down, **unrotated** page space. `size` = MediaBox size. `mediabox`/`cropbox` are raw PDF user-space (bottom-left). Docling measures from the CropBox origin; the pipeline shifts back. bbox are *not* cropped — margin cropping is the consumer's job. |
| `status` | `ok` / `empty` (no blocks: blank or scan with no recognised text) / `missing` (engine returned nothing). |
| `columns` | detected text columns (1 = single). `block.column` 0 = full-width/unassigned, 1..n. |
| `reading_order_status` | `single_column` / `ok` / `conflict` (Docling order contradicts column-major; `suggested_order_columnwise` given, but treat as unreliable) / `unknown`. |
| `fallback` | present ⇒ do **not** trust text order for sentence anchoring; show/locate on the original page. |
| `geometry` | `page_space` or `unreliable_rotated`. |
| `layout_score` | Docling page layout confidence (0–1) or null. Per-block `confidence` is always null (Docling provides none). |
| `blocks[]` | `id`, `kind` (`text heading list caption footnote table figure formula code header_footer unknown`), `raw_label`, `bbox`, `reading_order`, `column`, `status`, `text` (text kinds), `table` {rows, cols, cells[{r,c,rs,cs,text}]}, `in_reading_flow:false` for tables/figures. |

Block `status`: `ok`, `missing` (text block without text), `unknown` (unclassified/empty table; has `fallback: original_page_region`),
`unverified` (formula text exists but is OCR/model output, **not** evidence of a correct reconstruction),
`unrecognized` (formula region, no text). Consumers: tables/figures are never part of sentence flow;
formulas/unknown/rotated pages anchor to the original-page region.

Pipeline 5 adds optional extraction evidence without changing schema 1's existing
fields or the consumer contract:

- Blocks retain Docling `source_ref`, explicit `caption_refs` and
  `provenance_count`. Every page occurrence is emitted; references are document
  local, not canonical-text offsets. Missing links must not be inferred from y.
- Table cells additionally retain `bbox`, `column_header`, `row_header` and
  `row_section`. Cell boxes use the same unrotated MediaBox points as block
  boxes. They may enclose text rather than the full grid cell: a gap is not
  evidence of a safe row cut.
- Table `geometry_status` is `single_page` only for one provenance occurrence.
  For `ambiguous_multi_page`, cell boxes have no page identifiers; preserve the
  logical grid but do not assign these boxes to individual page crops.

Pipeline 7 also retains Docling `footnote_refs` on tables/pictures. These are
explicit object notes, separate from `caption_refs`; neither proves that all
titles, notes, source lines or credits were recognized. The reflow review planner
resolves unique, same-page references within `source_scope` and keeps every
part's own canonical ranges. It never assigns unlinked nearby text to an object.

`../reflow/source_payload.py` joins this evidence with PDFBox's unchanged source
text and exact glyph offsets in a separate private experiment. It does not
change `BookPage`, the importer or the reader. Existing schema-1 caches remain
readable; the pipeline-version bump causes new runs to regenerate missing fields.

## Known limits (measured on synthetic pages, Docling 2.149.0)

- `/Rotate` ≠ 0: Docling reports unrotated-space geometry and (with a CropBox) a wrong page size, and 180° pages come out in reverse order → flagged `unknown` + fallback; no shift applied.
- Display formulas are not enriched by default; a standalone formula line may be labelled `figure`/`text`. Do not infer formula correctness from OCR.
- Scanned pages go through Docling's built-in RapidOCR (local); a blank/undetected scan gives `empty`.
- `PIPELINE_VERSION` is part of the cache key: any behaviour change must bump it so stale structures are re-analysed (now "7"). Blocks carry a `source_scope` unique to one conversion graph: `source_ref` alone is not unique across separate page/chunk conversions.
- Single-column verdict is checked (text must run top-to-bottom) and downgraded to `unknown`+fallback when narrow blocks sit side by side without forming columns; a column must hold >=25% of page height in text. Tables whose cells do not cover rows*cols are `unknown`. Thresholds are tuned on synthetic pages only; real-page tuning needs the Mac run.
- Column detection is geometric (≥2 disjoint x-intervals with ≥2 narrow blocks each); full-width headings on a two-column page may land in column 1.
- Server consumer: `server/app/.../reader/v2/LayoutCache.kt` (read-only; `LayoutPage.orderTrusted` is the single gate for sentence anchoring). Its test passed on Windows via `./gradlew :server:app:test --tests 'com.coach.server.reader.v2.*'`; the Python test `test_page_json_has_every_field_the_kotlin_consumer_reads` pins the contract.
- Tests here use synthetic PDFs. Separate Mac-only real-textbook runs and their
  limits are recorded in `docs/design/reflow-options.md`; source outputs stay private.
