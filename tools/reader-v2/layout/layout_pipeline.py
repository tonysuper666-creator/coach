#!/usr/bin/env python3
"""Reader v2 / phase 1: offline Docling page-structure pipeline.

Per document version (sha256 of the PDF bytes) it stores one JSON per page:
columns, blocks (text/heading/list/table/figure/formula/caption/...), reading
order, original-page coordinates and confidence/missing status.  Everything is
local: no remote conversion, OCR or VLM service is called.  The cache lives
outside Git (default ~/Coach/state/reader-v2-layout); page JSON contains book
text, so it must never be committed, attached or posted.

See README.md for the schema and the consumer contract.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path

PIPELINE_VERSION = "3"   # bump on any behaviour change: it is part of the cache version key
SCHEMA_VERSION = 1
CHUNK_PAGES = 10

KIND_MAP = {
    "text": "text", "paragraph": "text", "section_header": "heading", "title": "heading",
    "list_item": "list", "table": "table", "picture": "figure", "formula": "formula",
    "caption": "caption", "footnote": "footnote", "page_header": "header_footer",
    "page_footer": "header_footer", "code": "code", "checkbox_selected": "text",
    "checkbox_unselected": "text", "document_index": "table", "key_value_region": "unknown",
    "form": "unknown", "reference": "text",
}
TEXT_FLOW = {"text", "heading", "list", "caption", "footnote"}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def atomic_write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, path)


# ---------------------------------------------------------------- columns ----

def assign_columns(blocks: list[dict], page_h: float | None = None) -> int:
    """Set block['column'] (0 = full-width/unassigned, 1..n = column); return n.

    Only text-flow blocks narrower than 60% of the occupied width take part; the
    columns are the merged x-intervals of those blocks.  Needs >=2 disjoint
    intervals each holding >=2 blocks, else the page is treated as single column.
    """
    flow = [b for b in blocks if b["kind"] in TEXT_FLOW and b["bbox"]]
    for b in blocks:
        b["column"] = 0
    if len(flow) < 4:
        return 1
    x0 = min(b["bbox"][0] for b in flow)
    x1 = max(b["bbox"][2] for b in flow)
    span = max(x1 - x0, 1.0)
    narrow = [b for b in flow if (b["bbox"][2] - b["bbox"][0]) < 0.6 * span]
    intervals: list[list] = []
    for b in sorted(narrow, key=lambda b: b["bbox"][0]):
        l, r = b["bbox"][0], b["bbox"][2]
        if intervals and l < intervals[-1][1] - 0.05 * span:
            intervals[-1][1] = max(intervals[-1][1], r)
            intervals[-1][2].append(b)
        else:
            intervals.append([l, r, [b]])
    def tall_enough(iv):   # a column must carry a real share of the page, not a few captions
        if not page_h:
            return True
        return sum(b["bbox"][3] - b["bbox"][1] for b in iv[2]) >= 0.25 * page_h
    cols = [iv for iv in intervals if len(iv[2]) >= 2 and tall_enough(iv)]
    if len(cols) < 2:
        return 1
    for i, (_, _, members) in enumerate(cols, 1):
        for b in members:
            b["column"] = i
    return len(cols)


def layout_ambiguous(blocks: list[dict]) -> bool:
    """Single-column verdict is not trustworthy when narrow text blocks sit side by
    side (a missed or partial multi-column layout)."""
    flow = [b for b in blocks if (b["kind"] in TEXT_FLOW or b["kind"] == "table") and b["bbox"]]
    if len(flow) < 2:
        return False
    x0 = min(b["bbox"][0] for b in flow)
    x1 = max(b["bbox"][2] for b in flow)
    span = max(x1 - x0, 1.0)
    mid = (x0 + x1) / 2
    narrow = [b for b in flow if (b["bbox"][2] - b["bbox"][0]) < 0.6 * span]
    left = [b for b in narrow if (b["bbox"][0] + b["bbox"][2]) / 2 < mid]
    right = [b for b in narrow if (b["bbox"][0] + b["bbox"][2]) / 2 >= mid]
    return any(a["bbox"][1] < c["bbox"][3] and c["bbox"][1] < a["bbox"][3]
               for a in left for c in right)


def structure_insufficient(blocks: list[dict], page_area: float) -> bool:
    """Body mostly classified as table/document_index: the model did not understand
    the page, so no text order may be trusted."""
    body = [b for b in blocks if b["bbox"] and b["kind"] != "header_footer"]
    if not body:
        return False
    text = [b for b in body if b["kind"] in TEXT_FLOW]
    tab = [b for b in body if b["kind"] == "table"]
    if any(b.get("raw_label") == "document_index" for b in tab):
        return True
    tab_area = sum(max(0.0, b["bbox"][2] - b["bbox"][0]) * max(0.0, b["bbox"][3] - b["bbox"][1]) for b in tab)
    if page_area and tab_area >= 0.35 * page_area:
        return True
    return len(tab) > len(text) and len(tab) >= 2


def table_grid_valid(t) -> bool:
    """Cells must lie inside rows x cols and cover every grid slot exactly once."""
    if not t or not t["cells"] or not t["rows"] or not t["cols"]:
        return False
    seen = set()
    for c in t["cells"]:
        r, cc, rs, cs = c["r"], c["c"], c["rs"], c["cs"]
        if r < 0 or cc < 0 or rs < 1 or cs < 1 or r + rs > t["rows"] or cc + cs > t["cols"]:
            return False
        for i in range(r, r + rs):
            for j in range(cc, cc + cs):
                if (i, j) in seen:
                    return False
                seen.add((i, j))
    return len(seen) == t["rows"] * t["cols"]


def _single_column_order(blocks: list[dict]) -> bool:
    """Text-flow blocks must run top to bottom (2pt tolerance)."""
    last = None
    for b in sorted(blocks, key=lambda b: b["reading_order"]):
        if b["kind"] not in TEXT_FLOW or not b["bbox"]:
            continue
        if last is not None and b["bbox"][1] < last - 2.0:
            return False
        last = b["bbox"][1]
    return True


def check_reading_order(blocks: list[dict], ncols: int):
    """Compare Docling order with a column-major expectation.

    Returns ("single_column"|"ok"|"conflict", suggested_order_ids|None).
    A conflict means a block of an earlier column comes after a later column's
    block; the consumer must then fall back to the original page.
    """
    if ncols < 2:
        if layout_ambiguous(blocks):
            return "unknown", None
        if not _single_column_order(blocks):
            keyed = sorted((x for x in blocks if x["bbox"]), key=lambda x: x["bbox"][1])
            return "conflict", [x["id"] for x in keyed]
        return "single_column", None
    seen_max = 0
    last_top: dict = {}
    for b in sorted(blocks, key=lambda b: b["reading_order"]):
        if b["column"] == 0:
            continue
        top = b["bbox"][1]
        reversed_in_col = b["column"] in last_top and top < last_top[b["column"]] - 2.0
        last_top[b["column"]] = max(top, last_top.get(b["column"], top))
        if b["column"] < seen_max or reversed_in_col:
            keyed = sorted((x for x in blocks if x["bbox"]),
                           key=lambda x: (x["column"], x["bbox"][1]))
            return "conflict", [x["id"] for x in keyed]
        seen_max = b["column"]
    return "ok", None


# -------------------------------------------------------------- docling ----

def _pkg_version(name: str):
    try:
        from importlib.metadata import version
        return version(name)
    except Exception:
        return None


class DoclingEngine:
    name = "docling"

    def __init__(self, models_dir: str | None = None):
        from docling.document_converter import DocumentConverter  # lazy: tests use fakes
        self.version = _pkg_version("docling")
        kwargs = {}
        if models_dir:
            from docling.datamodel.base_models import InputFormat
            from docling.datamodel.pipeline_options import PdfPipelineOptions
            from docling.document_converter import PdfFormatOption
            opts = PdfPipelineOptions(artifacts_path=models_dir)
            kwargs["format_options"] = {InputFormat.PDF: PdfFormatOption(pipeline_options=opts)}
        self.conv = DocumentConverter(**kwargs)

    def convert_range(self, pdf: Path, first: int, last: int) -> dict:
        """Return {page_no(1-based): {"size":(w,h), "blocks":[...], "layout_score":x}}."""
        res = self.conv.convert(str(pdf), page_range=(first, last))
        doc = res.document
        pages = {}
        for pno, p in doc.pages.items():
            pages[pno] = {"size": (p.size.width, p.size.height), "blocks": [], "layout_score": None}
        conf = getattr(res, "confidence", None)
        if conf is not None:
            for pno, pc in getattr(conf, "pages", {}).items():
                if pno in pages:
                    s = getattr(pc, "layout_score", None)
                    pages[pno]["layout_score"] = None if s is None or s != s else float(s)
        order = 0
        for item, _lvl in doc.iterate_items():
            label = getattr(item.label, "value", str(item.label))
            for prov in (item.prov or [])[:1]:
                pg = pages.get(prov.page_no)
                if pg is None:
                    continue
                bb = prov.bbox.to_top_left_origin(pg["size"][1])
                blk = {"label": label, "bbox": [bb.l, bb.t, bb.r, bb.b],
                       "text": getattr(item, "text", None) or None, "order": order}
                if label in ("table", "document_index"):
                    d = getattr(item, "data", None)
                    if d is not None:
                        blk["table"] = {"rows": d.num_rows, "cols": d.num_cols, "cells": [
                            {"r": c.start_row_offset_idx, "c": c.start_col_offset_idx,
                             "rs": c.row_span, "cs": c.col_span, "text": c.text}
                            for c in (getattr(d, "table_cells", None) or [])]}
                pg["blocks"].append(blk)
            order += 1
        return pages


def pdf_page_meta(pdf: Path) -> list:
    """Rotation / mediabox / cropbox per page, straight from the PDF."""
    import pypdfium2 as pdfium
    out = []
    pdoc = pdfium.PdfDocument(str(pdf))
    try:
        for i in range(len(pdoc)):
            pg = pdoc[i]
            out.append({"rotation": int(pg.get_rotation()),
                        "mediabox": [round(v, 3) for v in pg.get_mediabox()],
                        "cropbox": [round(v, 3) for v in pg.get_cropbox()]})
            pg.close()
    finally:
        pdoc.close()
    return out


# ------------------------------------------------------------ page JSON ----

def build_page_json(page_no: int, raw: dict | None, meta: dict, doc_sha: str) -> dict:
    """raw None => engine returned nothing for the page (status 'missing')."""
    pj = {
        "schema_version": SCHEMA_VERSION,
        "doc_sha256": doc_sha,
        "page_number": page_no,   # 1-based physical page; printed page labels are NOT inferred
        "page_index": page_no - 1,
        "page_base": 1,
        "coord_space": "pdf-points; origin = MediaBox top-left; x right, y down; unrotated page space (see README rotation note)",
        "rotation": meta["rotation"],
        "mediabox": meta["mediabox"],
        "cropbox": meta["cropbox"],
        "crop_note": "bbox are NOT cropped; the consumer applies margin cropping itself. cropbox is the PDF CropBox in PDF user space (bottom-left origin; == mediabox if absent).",
    }
    if raw is None:
        pj.update(status="missing", size=None, columns=None, reading_order_status="unknown",
                  blocks=[], fallback="original_page")
        return pj
    # Docling measures from the CropBox origin; shift back to the MediaBox origin so
    # bbox are original-page coordinates.  With /Rotate != 0 Docling's geometry is
    # unreliable (see README), so nothing is shifted and the order is flagged unknown.
    rotated = meta["rotation"] != 0
    mb, cb = meta["mediabox"], meta["cropbox"]
    dx = 0.0 if rotated else cb[0] - mb[0]
    dy = 0.0 if rotated else mb[3] - cb[3]
    pj["geometry"] = "unreliable_rotated" if rotated else "page_space"
    blocks = []
    for i, b in enumerate(sorted(raw["blocks"], key=lambda b: b["order"])):
        kind = KIND_MAP.get(b["label"], "unknown")
        bb = b["bbox"]
        blk = {"id": f"p{page_no}b{i}", "kind": kind, "raw_label": b["label"],
               "bbox": [round(v, 2) for v in (bb[0] + dx, bb[1] + dy, bb[2] + dx, bb[3] + dy)],
               "reading_order": i,
               "confidence": None,   # Docling gives no per-block score; only page layout_score
               "status": "ok"}
        if kind == "table":
            t = b.get("table")
            blk["table"] = t
            blk["status"] = "ok" if table_grid_valid(t) else "unknown"   # partial/overlapping grids are not trusted
            if blk["status"] == "unknown":
                blk["fallback"] = "original_page_region"
            blk["in_reading_flow"] = False   # a grid is never a run of sentences
        elif kind == "figure":
            blk["in_reading_flow"] = False
        elif kind == "formula":
            blk["text"] = b["text"]
            # recognised text is not evidence of a correct reconstruction
            blk["status"] = "unverified" if b["text"] else "unrecognized"
            blk["fallback"] = "original_page_region"
        elif kind == "unknown":
            blk["status"] = "unknown"
            blk["fallback"] = "original_page_region"
        else:
            blk["text"] = b["text"]
            if not b["text"]:
                blk["status"] = "missing"
        blocks.append(blk)
    ncols = assign_columns(blocks, mb[3] - mb[1])
    ro_status, suggested = check_reading_order(blocks, ncols)
    if rotated or structure_insufficient(blocks, (mb[2] - mb[0]) * (mb[3] - mb[1])):
        ro_status, suggested = "unknown", None
    pj.update(status="ok", size=[round(mb[2] - mb[0], 2), round(mb[3] - mb[1], 2)],
              layout_score=raw["layout_score"], columns=ncols,
              reading_order_status=ro_status, blocks=blocks)
    if suggested:
        pj["suggested_order_columnwise"] = suggested
    if suggested or ro_status in ("conflict", "unknown"):
        pj["fallback"] = "original_page"
    if rotated:
        pj["fallback"] = "original_page"
    if not blocks:
        pj["status"] = "empty"          # blank or scanned: no structure claimed
        pj["fallback"] = "original_page"
    return pj


# ------------------------------------------------------------- pipeline ----

def version_key(sha: str, engine_name: str, engine_version: str | None) -> str:
    return f"{sha[:16]}-p{PIPELINE_VERSION}-{engine_name}{engine_version or ''}"


def ingest(pdf: Path, cache: Path, doc_id: str | None, engine, force: bool = False,
           chunk: int = CHUNK_PAGES, log=print) -> dict:
    """Process one PDF.  An existing complete version is never touched until a
    fully successful new one is promoted; failed runs leave a resumable partial."""
    t0 = time.time()
    sha = sha256_file(pdf)
    doc_id = doc_id or pdf.stem
    ddir = cache / doc_id
    vkey = version_key(sha, engine.name, engine.version)
    final = ddir / vkey
    stats = {"doc_id": doc_id, "version": vkey, "cache_hit": False, "pages_total": 0,
             "pages_ok": 0, "pages_failed": 0, "pages_resumed": 0, "seconds": 0.0, "error": None}
    if (final / "manifest.json").exists() and not force:
        m = json.loads((final / "manifest.json").read_text(encoding="utf-8"))
        atomic_write_json(ddir / "latest.json", {"version": vkey})
        stats.update(cache_hit=True, pages_total=m["pages_total"], pages_ok=m["pages_ok"],
                     seconds=round(time.time() - t0, 3))
        return stats
    metas = pdf_page_meta(pdf)
    n = len(metas)
    stats["pages_total"] = n
    part = ddir / f".partial-{vkey}"
    (part / "pages").mkdir(parents=True, exist_ok=True)
    if force:
        for f in (part / "pages").glob("*.json"):
            f.unlink()
    todo = [p for p in range(1, n + 1) if not (part / "pages" / f"{p:05d}.json").exists()]
    stats["pages_resumed"] = n - len(todo)
    errors: list = []
    i = 0
    while i < len(todo):
        j = i   # contiguous run of at most `chunk` pages
        while j + 1 < len(todo) and todo[j + 1] == todo[j] + 1 and j + 1 - i < chunk - 1:
            j += 1
        first, last = todo[i], todo[j]
        try:
            raw = engine.convert_range(pdf, first, last)
            for p in range(first, last + 1):
                pj = build_page_json(p, raw.get(p), metas[p - 1], sha)
                atomic_write_json(part / "pages" / f"{p:05d}.json", pj)
        except Exception as e:
            errors.append({"pages": [first, last], "error": f"{type(e).__name__}: {e}"[:500]})
            log(f"chunk {first}-{last} failed: {e}")
        i = j + 1
    done = sorted(int(f.stem) for f in (part / "pages").glob("*.json"))
    stats["pages_ok"] = len(done)
    stats["pages_failed"] = n - len(done)
    if stats["pages_failed"]:
        stats["error"] = errors or "incomplete"
        atomic_write_json(part / "errors.json", errors)
        stats["seconds"] = round(time.time() - t0, 3)
        return stats   # not promoted; rerun resumes from the partial dir
    kinds: dict = {}
    unk = 0
    for p in done:
        pj = json.loads((part / "pages" / f"{p:05d}.json").read_text(encoding="utf-8"))
        for b in pj["blocks"]:
            kinds[b["kind"]] = kinds.get(b["kind"], 0) + 1
            unk += b["status"] in ("unknown", "unrecognized", "missing")
    manifest = {"schema_version": SCHEMA_VERSION, "pipeline_version": PIPELINE_VERSION,
                "engine": engine.name, "engine_version": engine.version, "doc_id": doc_id,
                "doc_sha256": sha, "source_name": pdf.name, "pages_total": n, "pages_ok": n,
                "block_counts": kinds, "blocks_unknown_or_missing": unk,
                "created_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    atomic_write_json(part / "manifest.json", manifest)   # written last: marks completeness
    aside = ddir / f".old-{vkey}"
    if aside.exists():
        shutil.rmtree(aside)
    had_old = final.exists()
    if had_old:
        os.replace(final, aside)          # old data is only moved, never deleted before success
    try:
        os.replace(part, final)
        atomic_write_json(ddir / "latest.json", {"version": vkey})
    except Exception as e:
        if final.exists() and had_old:
            shutil.rmtree(final, ignore_errors=True)
        if had_old:
            os.replace(aside, final)      # restore the previous complete version
        stats.update(error=f"promote failed: {type(e).__name__}: {e}"[:300],
                     seconds=round(time.time() - t0, 3))
        return stats
    if had_old:
        shutil.rmtree(aside, ignore_errors=True)
    stats["seconds"] = round(time.time() - t0, 3)
    return stats


def cache_size(path: Path) -> int:
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file())


def load_page(cache: Path, doc_id: str, page_number: int) -> dict | None:
    """Reference reader: follows latest.json; no re-analysis, no engine import."""
    ddir = cache / doc_id
    try:
        v = json.loads((ddir / "latest.json").read_text(encoding="utf-8"))["version"]
        return json.loads((ddir / v / "pages" / f"{page_number:05d}.json").read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None


def default_cache() -> Path:
    return Path(os.environ.get("READER_V2_LAYOUT_CACHE", Path.home() / "Coach" / "state" / "reader-v2-layout"))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Offline Docling layout pipeline (reader v2)")
    ap.add_argument("pdfs", nargs="+", type=Path)
    ap.add_argument("--cache", type=Path, default=default_cache())
    ap.add_argument("--doc-id", help="only valid with a single PDF; default file stem")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--chunk", type=int, default=CHUNK_PAGES)
    ap.add_argument("--models-dir", help="private local Docling model dir")
    ap.add_argument("--online-models", action="store_true",
                    help="allow the one-time model weight download (default: offline)")
    ap.add_argument("--stats-json", type=Path)
    a = ap.parse_args(argv)
    if not a.online_models:
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
    engine = DoclingEngine(a.models_dir)
    allstats, rc = [], 0
    for pdf in a.pdfs:
        s = ingest(pdf, a.cache, a.doc_id if len(a.pdfs) == 1 else None, engine, a.force, a.chunk)
        allstats.append(s)
        print(json.dumps(s, ensure_ascii=False))
        rc |= 1 if s["error"] else 0
    summary = {"docs": len(allstats), "ok": sum(1 for s in allstats if not s["error"]),
               "failed": sum(1 for s in allstats if s["error"]),
               "cache_bytes": cache_size(a.cache) if a.cache.exists() else 0,
               "docling": engine.version, "pipeline_version": PIPELINE_VERSION}
    print(json.dumps(summary))
    if a.stats_json:
        atomic_write_json(a.stats_json, {"summary": summary, "docs": allstats})
    return rc


if __name__ == "__main__":
    sys.exit(main())
