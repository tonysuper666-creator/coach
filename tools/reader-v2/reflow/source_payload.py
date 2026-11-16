#!/usr/bin/env python3
"""Join local PDFBox offsets and Docling structures without rewriting source text.

This extraction experiment is not connected to the reader/importer. Output has
private book text and coordinates; write it outside Git and never upload it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

from benchmark import outside_repo

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "layout"))
from layout_pipeline import table_grid_valid  # noqa: E402


def utf16(text):
    return text.encode("utf-16-le")


def rect_valid(bb):
    return (isinstance(bb, list) and len(bb) == 4 and
            all(isinstance(v, (int, float)) and math.isfinite(v) for v in bb) and
            bb[2] > bb[0] and bb[3] > bb[1])


def intersects(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def contains(a, b, epsilon=0.05):
    return (a[0] - epsilon <= b[0] and a[1] - epsilon <= b[1] and
            a[2] + epsilon >= b[2] and a[3] + epsilon >= b[3])


def crop_rect(bb, layout):
    """MediaBox top-left points -> unrotated CropBox top-left points."""
    mb, cb = layout["mediabox"], layout["cropbox"]
    dx, dy = cb[0] - mb[0], mb[3] - cb[3]
    return [bb[0] - dx, bb[1] - dy, bb[2] - dx, bb[3] - dy]


def normalized(bb, layout):
    cb = layout["cropbox"]
    width, height = cb[2] - cb[0], cb[3] - cb[1]
    if not rect_valid(bb) or not contains([0, 0, width, height], bb):
        return None
    return [max(0.0, min(1.0, v / (width if i % 2 == 0 else height)))
            for i, v in enumerate(bb)]


def source_mapping(bb, probe):
    """Exact glyph spans only. Never replace an enclosing min/max interval.

    Whitespace inserted by PDFBox between selected glyphs may join adjacent
    spans. Any intervening body characters keep the ranges discontiguous.
    """
    selected, ambiguous = [], False
    text = utf16(probe["text"])
    for g in probe["glyphs"]:
        gb = g["bbox"]
        if not intersects(bb, gb):
            continue
        start, end = g.get("start"), g.get("end")
        if (not contains(bb, gb) or type(start) is not int or type(end) is not int or
                not 0 <= start < end <= len(text) // 2):
            ambiguous = True
            continue
        # Probe offsets are made by the same stripper operation, not by finding
        # an OCR string in the text (repeated words would make that ambiguous).
        selected.append([start, end])
    ranges = []
    for start, end in sorted(selected):
        if ranges:
            previous = ranges[-1][1]
            gap = text[previous * 2:start * 2].decode("utf-16-le") if start >= previous else ""
            if start <= previous or not gap or gap.isspace():
                ranges[-1][1] = max(previous, end)
                continue
        ranges.append([start, end])
    status = ("ambiguous" if ambiguous else "no_text" if not ranges else
              "discontiguous" if len(ranges) > 1 else "mapped")
    return {"source_ranges": ranges, "mapping_status": status,
            "replacement_range": ranges[0] if status == "mapped" else None}


def table_payload(block, layout, probe, geometry_safe=True):
    """Retain model rows; certify cuts only with matching PDF horizontal rules.

    Model cell boxes can be text boxes. Gaps alone are never cut coordinates.
    A complete grid can still be semantically wrong; this is not a quality score.
    """
    table = block.get("table") or {}
    cells = []
    single_page = table.get("geometry_status") == "single_page"
    for cell in table.get("cells", []):
        item = dict(cell)
        box = item.pop("bbox", None)
        item["source_region"] = (normalized(crop_rect(box, layout), layout)
                                 if box and geometry_safe and single_page else None)
        cells.append(item)
    result = {"rows": table.get("rows"), "cols": table.get("cols"), "cells": cells,
              "row_bands": [], "row_breaks": [], "header_rows": [],
              "repeat_header_region": None, "split_status": "unverified"}
    if (not table_grid_valid(table) or not geometry_safe or not single_page or
            any(c["source_region"] is None for c in cells)):
        return result
    bb = crop_rect(block["bbox"], layout)
    if any(not contains(bb, crop_rect(c["bbox"], layout)) for c in table["cells"]):
        return result
    n = table["rows"]
    # A rowspan's outer box does not reveal its internal row boundaries. Keep
    # every span, but only use rows with single-row geometry as cut evidence.
    bands = []
    for row in range(n):
        boxes = [crop_rect(c["bbox"], layout) for c in table["cells"] if c["r"] == row and c["rs"] == 1]
        bands.append([min(b[1] for b in boxes), max(b[3] for b in boxes)] if boxes else None)
    result["row_bands"] = [[y / probe["height"] for y in band] if band else None for band in bands]
    headers = []
    for row in range(n):
        cover = [c for c in cells if c["r"] <= row < c["r"] + c["rs"]]
        if cover and all(c.get("column_header") for c in cover):
            headers.append(row)
        else:
            break
    result["header_rows"] = headers
    for row in range(1, n):
        if (not bands[row - 1] or not bands[row] or
                any(c["r"] < row < c["r"] + c["rs"] for c in cells)):
            continue
        low, high = bands[row - 1][1], bands[row][0]
        if low > high:
            continue
        ys = sorted({r[1] for r in probe.get("horizontal_rules", [])
                     if r[0] <= bb[0] + 0.5 and r[2] >= bb[2] - 0.5 and
                     low - 0.05 <= r[1] <= high + 0.05})
        if len(ys) != 1:
            continue
        y = ys[0]
        if any(g["bbox"][1] + 0.05 < y < g["bbox"][3] - 0.05 and
               g["bbox"][0] < bb[2] and g["bbox"][2] > bb[0] for g in probe["glyphs"]):
            continue
        result["row_breaks"].append({"before_row": row, "y": y / probe["height"],
                                     "evidence": "pdf_horizontal_rule_and_cell_grid"})
    header_end = len(headers)
    header_cut = next((r["y"] for r in result["row_breaks"] if r["before_row"] == header_end), None)
    if header_cut is not None:
        result["repeat_header_region"] = [bb[0] / probe["width"], bb[1] / probe["height"],
                                           bb[2] / probe["width"], header_cut]
    result["split_status"] = "verified_boundaries" if result["row_breaks"] else "unverified"
    return result


def build_payload(probe, layout):
    if (probe.get("doc_sha256") != layout["doc_sha256"] or not probe.get("doc_sha256") or
            probe["page"] != layout["page_number"] or probe.get("offset_unit") != "utf16"):
        raise ValueError("probe/layout document, page or offset convention mismatch")
    for key in ("cropbox", "mediabox"):
        if len(probe[key]) != 4 or any(abs(a - b) > 0.01 for a, b in zip(probe[key], layout[key])):
            raise ValueError("probe/layout page frame mismatch")
    if (abs(probe["width"] - (probe["cropbox"][2] - probe["cropbox"][0])) > 0.01 or
            abs(probe["height"] - (probe["cropbox"][3] - probe["cropbox"][1])) > 0.01):
        raise ValueError("invalid probe CropBox dimensions")
    geometry_safe = (probe.get("rotation") == layout["rotation"] == 0 and
                     layout.get("geometry") == "page_space" and
                     not probe.get("rotated_glyphs") and not probe.get("clip_unverified") and
                     not probe.get("graphics_unsupported"))
    trusted_order = not layout.get("fallback")
    payload = {"schema_version": 1, "doc_sha256": probe["doc_sha256"], "page": probe["page"],
               "offset_unit": "utf16", "text": probe["text"],
               "text_utf16_sha256": hashlib.sha256(utf16(probe["text"])).hexdigest(),
               "coord_space": "normalized_unrotated_cropbox_top_left",
               "geometry_status": "mapped" if geometry_safe else "original_page",
               "placement_status": "candidate" if trusted_order else "original_page",
               "blocks": [], "caption_groups": []}
    for b in layout["blocks"]:
        bb = crop_rect(b["bbox"], layout)
        region = normalized(bb, layout) if geometry_safe else None
        mapped = source_mapping(bb, probe) if region else {
            "source_ranges": [], "replacement_range": None, "mapping_status": "unreliable_geometry"}
        item = {"id": b["id"], "kind": b["kind"], "source_ref": b.get("source_ref"),
                "source_region": region, **mapped}
        item["hide_source_allowed"] = (trusted_order and mapped["mapping_status"] == "mapped")
        # Existing BookIllustration accepts only one sourceStart/sourceEnd.
        # Multiple ranges are kept for a future model extension, never flattened.
        if not item["hide_source_allowed"]:
            item["replacement_range"] = None
        item["anchor_offset"] = mapped["source_ranges"][0][0] if item["hide_source_allowed"] else None
        if b["kind"] == "table":
            item["table"] = table_payload(b, layout, probe, geometry_safe=bool(region))
        payload["blocks"].append(item)
    refs = {}
    for b in layout["blocks"]:
        if b.get("source_ref"):
            refs.setdefault(b["source_ref"], []).append(b)
    owners = {}
    for b in layout["blocks"]:
        for ref in b.get("caption_refs", []):
            owners.setdefault(ref, set()).add(b["id"])
    for b in layout["blocks"]:
        if b["kind"] not in ("table", "figure"):
            continue
        caption_refs = b.get("caption_refs", [])
        resolved = [refs.get(ref, []) for ref in caption_refs]
        valid = bool(caption_refs) and all(len(matches) == 1 and matches[0]["kind"] == "caption" and
                    len(owners[ref]) == 1 for ref, matches in zip(caption_refs, resolved))
        group = {"owner_id": b["id"], "caption_ids": [m[0]["id"] for m in resolved] if valid else [],
                 "caption_refs": caption_refs, "status": "linked" if valid else "unresolved",
                 "keep_together": valid}
        payload["caption_groups"].append(group)
    return payload


def run(args):
    out = outside_repo(args.out)
    # Validate the whole batch before writing any payload (no mixed old/new rows).
    pages = []
    seen = set()
    for line in args.probe.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if row["page"] in seen:
            raise ValueError("duplicate probe page")
        seen.add(row["page"])
        layout = json.loads((args.layout / f'{row["page"]:05d}.json').read_text(encoding="utf-8"))
        pages.append(build_payload(row, layout))
    if out.exists():
        raise ValueError("output must be new; refusing stale payloads")
    out.mkdir(parents=True)
    for page in pages:
        (out / f'{page["page"]:05d}.json').write_text(json.dumps(page, ensure_ascii=False), encoding="utf-8")
    print(f"source payloads: {len(pages)} pages; private output written")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--layout", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    run(parser.parse_args())
