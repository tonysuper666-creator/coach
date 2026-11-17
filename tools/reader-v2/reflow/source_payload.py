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
        try:
            text[start * 2:end * 2].decode("utf-16-le")
        except UnicodeDecodeError:
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


def verified_cell_bands(table, layout, probe, table_box):
    """Prove cell text/geometry against PDF glyphs, without searching for strings.

    Empty space between model boxes is insufficient. Every table glyph must
    belong wholly to exactly one cell, and every cell must reproduce its text
    from canonical UTF-16 slices (ignoring whitespace only). This is geometric
    split evidence, not proof that the model chose the right semantic grid.
    """
    cells = table["cells"]
    boxes = [crop_rect(c["bbox"], layout) for c in cells]
    assigned = [[] for _ in cells]
    text = utf16(probe["text"])
    for glyph in probe["glyphs"]:
        gb = glyph["bbox"]
        if not intersects(table_box, gb):
            continue
        hits = [i for i, box in enumerate(boxes) if intersects(box, gb)]
        if len(hits) != 1 or not contains(boxes[hits[0]], gb) or not contains(table_box, gb):
            return None
        start, end = glyph.get("start"), glyph.get("end")
        if type(start) is not int or type(end) is not int or not 0 <= start < end <= len(text) // 2:
            return None
        assigned[hits[0]].append((start, end, gb))
    bands = [[] for _ in range(table["rows"])]
    all_ranges = []
    for cell, glyphs in zip(cells, assigned):
        spans = sorted(glyphs)
        try:
            actual = "".join(text[a*2:b*2].decode("utf-16-le") for a, b, _ in spans)
        except UnicodeDecodeError:
            return None
        if "".join(actual.split()) != "".join(cell.get("text", "").split()):
            return None
        all_ranges.extend((a, b) for a, b, _ in spans)
        if cell["rs"] == 1:
            bands[cell["r"]].extend(bb for _, _, bb in spans)
    # Reused/overlapping source runs cannot independently verify two cells.
    ranges = sorted(all_ranges)
    if any(a[1] > b[0] for a, b in zip(ranges, ranges[1:])):
        return None
    return [[min(bb[1] for bb in row), max(bb[3] for bb in row)] if row else None
            for row in bands]


def spanning_rule_ys(rules, left, right, low, high):
    """A horizontal rule may consist of separate touching cell-edge segments.

    Merge only collinear intervals. A gap, even a narrow one, is not a rule.
    The endpoint tolerance is the existing PDF coordinate tolerance.
    """
    by_y = {}
    for rule in rules:
        if (len(rule) == 4 and all(math.isfinite(v) for v in rule) and
                rule[0] < rule[2] and abs(rule[1] - rule[3]) < 0.05 and
                low - 0.05 <= rule[1] <= high + 0.05):
            by_y.setdefault(rule[1], []).append((rule[0], rule[2]))
    matches = []
    for y, spans in by_y.items():
        spans.sort()
        start, end = spans[0]
        for a, b in spans[1:]:
            if a > end:
                if start <= left + 0.5 and end >= right - 0.5:
                    matches.append(y)
                    break
                start = a
            end = max(end, b)
        else:
            if start <= left + 0.5 and end >= right - 0.5:
                matches.append(y)
    return sorted(set(matches))


def table_payload(block, layout, probe, geometry_safe=True):
    """Retain model rows; certify cuts with PDF rules or exact cell/glyph evidence.

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
              "repeat_header_region": None, "split_status": "unverified",
              "cell_text_status": "unverified"}
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
    verified_bands = verified_cell_bands(table, layout, probe, bb)
    if verified_bands is not None:
        result["cell_text_status"] = "exact_glyph_slices_whitespace_ignored"
    for row in range(1, n):
        if (not bands[row - 1] or not bands[row] or
                any(c["r"] < row < c["r"] + c["rs"] for c in cells)):
            continue
        low, high = bands[row - 1][1], bands[row][0]
        if low > high:
            continue
        ys = spanning_rule_ys(probe.get("horizontal_rules", []), bb[0], bb[2], low, high)
        evidence = "pdf_horizontal_rule_and_cell_grid"
        if len(ys) == 1:
            y = ys[0]
        elif (not ys and verified_bands is not None and verified_bands[row - 1] and
              verified_bands[row] and low < high and
              verified_bands[row - 1][1] <= low < high <= verified_bands[row][0]):
            y = (low + high) / 2
            evidence = "exact_cell_text_and_pdf_glyph_gap"
        else:
            continue
        if any(g["bbox"][1] + 0.05 < y < g["bbox"][3] - 0.05 and
               g["bbox"][0] < bb[2] and g["bbox"][2] > bb[0] for g in probe["glyphs"]):
            continue
        result["row_breaks"].append({"before_row": row, "y": y / probe["height"],
                                     "evidence": evidence})
    header_end = len(headers)
    header_cut = next((r["y"] for r in result["row_breaks"] if r["before_row"] == header_end), None)
    if header_cut is not None:
        result["repeat_header_region"] = [bb[0] / probe["width"], bb[1] / probe["height"],
                                           bb[2] / probe["width"], header_cut]
    result["split_status"] = "verified_boundaries" if result["row_breaks"] else "unverified"
    return result


def explicit_note_part(note, original, layout, probe):
    """Repair only a clipped glyph edge, using exact note text as a cross-check.

    No string search, next-line inference or rewriting of the original block.
    The bounded envelope must map completely and reproduce the model note text
    from canonical slices, ignoring whitespace only. This is not completeness.
    """
    part = {k: original[k] for k in ("id", "source_region", "source_ranges", "mapping_status")}
    if (part["mapping_status"] != "ambiguous" or part["source_region"] is None or
            not isinstance(note.get("text"), str) or not note["text"].strip() or
            probe.get("glyph_geometry_checked") is not True or probe.get("unmapped_ranges")):
        return part
    box = crop_rect(note["bbox"], layout)
    glyphs = [g for g in probe["glyphs"] if intersects(box, g["bbox"])]
    if not glyphs:
        return part
    envelope = [min([box[0]] + [g["bbox"][0] for g in glyphs]),
                min([box[1]] + [g["bbox"][1] for g in glyphs]),
                max([box[2]] + [g["bbox"][2] for g in glyphs]),
                max([box[3]] + [g["bbox"][3] for g in glyphs])]
    if (any(abs(a - b) > 4 for a, b in zip(box, envelope)) or
            not contains([0, 0, probe["width"], probe["height"]], envelope, epsilon=0)):
        return part
    mapped = source_mapping(envelope, probe)
    if mapped["mapping_status"] not in ("mapped", "discontiguous"):
        return part
    text = utf16(probe["text"])
    actual = "".join(text[a*2:b*2].decode("utf-16-le") for a, b in mapped["source_ranges"])
    if "".join(actual.split()) != "".join(note["text"].split()):
        return part
    return {**part, "source_region": normalized(envelope, layout),
            "source_ranges": mapped["source_ranges"], "mapping_status": mapped["mapping_status"],
            "geometry_evidence": "bounded_glyph_envelope_exact_note_text"}


def object_label_groups(layout, indexed, probe):
    """Keep explicit captions AND table/picture notes; never infer ownership.

    A model link is extraction evidence for human review, not proof of semantic
    completeness. Resolve within one conversion scope and one physical page.
    """
    refs, claims = {}, {}
    roles = (("caption", "caption_refs"), ("footnote", "footnote_refs"))
    for b in layout["blocks"]:
        scope = b.get("source_scope")
        if b.get("source_ref"):
            refs.setdefault((scope, b["source_ref"]), []).append(b)
        for _, field in roles:
            for ref in b.get(field, []):
                claims.setdefault((scope, ref), []).append(b["id"])
    groups = []
    for b in layout["blocks"]:
        if b["kind"] not in ("table", "figure"):
            continue
        requested = [(role, ref) for role, field in roles for ref in b.get(field, [])]
        parts = []
        valid = bool(requested) and b.get("provenance_count", 1) == 1
        for role, ref in requested:
            key = (b.get("source_scope"), ref)
            matches = refs.get(key, [])
            if (len(matches) != 1 or matches[0]["kind"] != role or
                    claims[key] != [b["id"]] or
                    matches[0].get("provenance_count", 1) != 1):
                valid = False
                continue
            target = matches[0]
            part = indexed[target["id"]]
            source = (explicit_note_part(target, part, layout, probe) if role == "footnote" else
                      {k: part[k] for k in ("id", "source_region", "source_ranges", "mapping_status")})
            parts.append(dict(role=role, **source))
        verified = (valid and not layout.get("fallback") and
                    all(p["source_region"] is not None and
                        p["mapping_status"] in ("mapped", "discontiguous") for p in parts))
        groups.append({"owner_id": b["id"], "status": "linked" if valid else "unresolved",
                       "placement_status": "source_parts_verified" if verified else "unverified",
                       "source_parts": parts if valid else [], "replacement_range": None,
                       "hide_source_allowed": False, "import_allowed": False})
    return groups


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
    indexed = {item["id"]: item for item in payload["blocks"]}
    for b in layout["blocks"]:
        if b["kind"] not in ("table", "figure"):
            continue
        caption_refs = b.get("caption_refs", [])
        resolved = [refs.get(ref, []) for ref in caption_refs]
        valid = (bool(caption_refs) and len(set(caption_refs)) == len(caption_refs) and
                 all(len(matches) == 1 and matches[0]["kind"] == "caption" and
                     len(owners[ref]) == 1 for ref, matches in zip(caption_refs, resolved)))
        group = {"owner_id": b["id"], "caption_ids": [m[0]["id"] for m in resolved] if valid else [],
                 "caption_refs": caption_refs, "status": "linked" if valid else "unresolved",
                 "keep_together": valid}
        # A caption can have several explicitly referenced continuation blocks.
        # Preserve each piece's exact ranges; never enclose intervening body text
        # in one range or infer an unlabelled continuation from its proximity.
        parts = [indexed[m[0]["id"]] for m in resolved] if valid else []
        group["source_parts"] = [{"id": part["id"], "source_region": part["source_region"],
                                  "source_ranges": part["source_ranges"],
                                  "mapping_status": part["mapping_status"]} for part in parts]
        group["placement_status"] = ("source_parts_verified" if valid and trusted_order and
                                      all(part["source_region"] is not None and
                                          part["mapping_status"] in ("mapped", "discontiguous")
                                          for part in parts) else "unverified")
        group["replacement_range"] = None
        payload["caption_groups"].append(group)
    payload["object_label_groups"] = object_label_groups(layout, indexed, probe)
    return payload


def run(args):
    from cross_page_tables import build_continuations

    out = outside_repo(args.out)
    # Validate the whole batch before writing any payload (no mixed old/new rows).
    pages = []
    probes, layouts = [], []
    seen = set()
    for line in args.probe.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if row["page"] in seen:
            raise ValueError("duplicate probe page")
        seen.add(row["page"])
        layout = json.loads((args.layout / f'{row["page"]:05d}.json').read_text(encoding="utf-8"))
        pages.append(build_payload(row, layout))
        probes.append(row)
        layouts.append(layout)
    continuations = build_continuations(probes, layouts)
    if out.exists():
        raise ValueError("output must be new; refusing stale payloads")
    out.mkdir(parents=True)
    for page in pages:
        (out / f'{page["page"]:05d}.json').write_text(json.dumps(page, ensure_ascii=False), encoding="utf-8")
    (out / "table-continuations.json").write_text(json.dumps(continuations, ensure_ascii=False), encoding="utf-8")
    print(f"source payloads: {len(pages)} pages; private output written")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--layout", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    run(parser.parse_args())
