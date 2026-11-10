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
<cache>/<doc_id>/latest.json                 {"version": "<sha16>-p1-docling2.149.0"}
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

## Known limits (measured on synthetic pages, Docling 2.149.0)

- `/Rotate` ≠ 0: Docling reports unrotated-space geometry and (with a CropBox) a wrong page size, and 180° pages come out in reverse order → flagged `unknown` + fallback; no shift applied.
- Display formulas are not enriched by default; a standalone formula line may be labelled `figure`/`text`. Do not infer formula correctness from OCR.
- Scanned pages go through Docling's built-in RapidOCR (local); a blank/undetected scan gives `empty`.
- `PIPELINE_VERSION` is part of the cache key: any behaviour change must bump it so stale structures are re-analysed (now "4").
- Single-column verdict is checked (text must run top-to-bottom) and downgraded to `unknown`+fallback when narrow blocks sit side by side without forming columns; a column must hold >=25% of page height in text. Tables whose cells do not cover rows*cols are `unknown`. Thresholds are tuned on synthetic pages only; real-page tuning needs the Mac run.
- Column detection is geometric (≥2 disjoint x-intervals with ≥2 narrow blocks each); full-width headings on a two-column page may land in column 1.
- Server consumer: `server/app/.../reader/v2/LayoutCache.kt` (read-only; `LayoutPage.orderTrusted` is the single gate for sentence anchoring). Its test passed on Windows via `./gradlew :server:app:test --tests 'com.coach.server.reader.v2.*'`; the Python test `test_page_json_has_every_field_the_kotlin_consumer_reads` pins the contract.
- Not done here: real-textbook runs (Mac-resident material).
