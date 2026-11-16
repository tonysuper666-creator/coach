#!/usr/bin/env python3
"""Private tagged-PDF association evidence, independent of Docling.

This is not an automatic crop boundary or a semantic quality score. Tight glyph
envelopes exclude borders/padding and must not be used as replacement crops.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path

from source_payload import contains, intersects, rect_valid, utf16


def canonical_hash(text):
    return hashlib.sha256(utf16(text)).hexdigest()


def ranges_for(glyphs, text):
    """Exact disjoint slices only; never take the enclosing min/max interval."""
    raw = utf16(text)
    spans = []
    for g in glyphs:
        a, b = g.get("start"), g.get("end")
        if (type(a) is not int or type(b) is not int or not 0 <= a < b <= len(raw) // 2 or
                not rect_valid(g.get("bbox"))):
            raise ValueError("invalid_glyph")
        try:
            if raw[a*2:b*2].decode("utf-16-le") != g["text"]:
                raise ValueError("stale_glyph")
        except UnicodeDecodeError as e:
            raise ValueError("invalid_utf16_boundary") from e
        spans.append([a, b])
    spans.sort()
    out = []
    for a, b in spans:
        if out and a < out[-1][1]:
            raise ValueError("reused_glyph_range")
        if out and a == out[-1][1]:
            out[-1][1] = b
        else:
            out.append([a, b])
    return out


def envelope(glyphs):
    return [min(g["bbox"][0] for g in glyphs), min(g["bbox"][1] for g in glyphs),
            max(g["bbox"][2] for g in glyphs), max(g["bbox"][3] for g in glyphs)]


def certify(table, pages):
    out = {"source_ref": table["source_path"], "status": "unverified", "reason": None,
           "fragments": [], "repeat_header": None, "replacement_range": None,
           "hide_source_allowed": False, "crop_ready": False}
    try:
        if table.get("reason"):
            raise ValueError(table["reason"])
        rows = table["rows"]
        if not rows or len(rows) > 500 or not rows[0] or len(rows[0]) > 100:
            raise ValueError("verification_budget_or_empty_grid")
        if any(len(row) != len(rows[0]) for row in rows):
            raise ValueError("nonrectangular_grid")
        refs_used = set()
        local_rows = defaultdict(list)
        page_order = []
        checks = 0
        for r, row in enumerate(rows):
            row_page = set()
            cells = []
            all_glyphs = []
            for c, cell in enumerate(row):
                refs = cell["refs"]
                if not refs or len({ref["page"] for ref in refs}) != 1:
                    raise ValueError("cell_spans_pages_or_has_no_reference")
                number = refs[0]["page"]
                row_page.add(number)
                p = pages.get(number)
                if p is None or not p["safe"]:
                    raise ValueError("missing_or_unsafe_page")
                ids = set()
                for ref in refs:
                    key = (number, ref["mcid"])
                    if type(ref["mcid"]) is not int or ref["mcid"] < 0 or key in refs_used:
                        raise ValueError("reused_or_invalid_mcid")
                    refs_used.add(key)
                    ids.add(ref["mcid"])
                checks += len(p["glyphs"])
                if checks > 2_000_000:
                    raise ValueError("verification_budget")
                gs = [g for g in p["glyphs"] if g["mcid"] in ids]
                if not gs or {g["mcid"] for g in gs} != ids:
                    raise ValueError("missing_mcid_glyphs")
                spans = ranges_for(gs, p["text"])
                bb = envelope(gs)
                if not contains([0, 0, p["width"], p["height"]], bb):
                    raise ValueError("out_of_page_glyph")
                # A tight cell envelope may not enclose body text/another cell.
                if any(g["mcid"] not in ids and intersects(bb, g["bbox"]) for g in p["glyphs"]):
                    raise ValueError("cell_encloses_foreign_text")
                cells.append({"column": c, "header": cell["header"],
                              "source_path": cell["source_path"], "mcids": sorted(ids),
                              "source_ranges": spans, "glyph_envelope": bb})
                all_glyphs.extend(gs)
            if len(row_page) != 1:
                raise ValueError("row_spans_pages")
            number = next(iter(row_page))
            page_order.append(number)
            # Reject overlapping canonical ownership even if distinct MCIDs claim it.
            ranges_for(all_glyphs, pages[number]["text"])
            local_rows[number].append({"row": r, "cells": cells,
                                       "glyph_envelope": envelope(all_glyphs)})
        ordered = sorted(local_rows)
        if (len(ordered) < 2 or len(ordered) > 32 or page_order != sorted(page_order) or
                ordered != list(range(ordered[0], ordered[-1] + 1))):
            raise ValueError("not_a_complete_consecutive_cross_page_table")
        fragments = []
        for number in ordered:
            p = pages[number]
            rs = local_rows[number]
            # Disjoint row bands certify text-safe cuts, not complete graphic crops.
            cuts = []
            for a, b in zip(rs, rs[1:]):
                low, high = a["glyph_envelope"][3], b["glyph_envelope"][1]
                if low >= high:
                    raise ValueError("row_order_or_gap_unverified")
                y = (low + high) / 2
                left = min(x["glyph_envelope"][0] for x in rs)
                right = max(x["glyph_envelope"][2] for x in rs)
                if any(g["bbox"][0] < right and g["bbox"][2] > left and
                       g["bbox"][1] < y < g["bbox"][3] for g in p["glyphs"]):
                    raise ValueError("cut_crosses_foreign_glyph")
                cuts.append({"before_row": b["row"], "y": y / p["height"],
                             "evidence": "explicit_tag_rows_and_pdf_glyph_gap"})
            fragments.append({"page": number, "canonical_text_sha256": canonical_hash(p["text"]),
                              "rows": rs, "row_breaks": cuts, "source_region": None,
                              "replacement_range": None})
        out.update(status="verified_tagged_association", fragments=fragments)
    except ValueError as e:
        out["reason"] = str(e)
    return out


def build_tagged_continuations(native, probes):
    """Require the independently extracted canonical pages; reject stale evidence."""
    if (native.get("schema") != 1 or len(native.get("doc_sha256", "")) != 64):
        raise ValueError("invalid_native_document")
    pages = {p["page"]: p for p in native["pages"]}
    expected = {p["page"]: p for p in probes}
    if len(pages) != len(native["pages"]) or len(expected) != len(probes) or pages.keys() != expected.keys():
        raise ValueError("duplicate_or_mismatched_page_set")
    for n, p in pages.items():
        e = expected[n]
        if (e["doc_sha256"] != native["doc_sha256"] or p["text"] != e["text"] or
                (p["width"], p["height"]) != (e["width"], e["height"]) or
                [(g["start"], g["end"], g["bbox"]) for g in p["glyphs"]] !=
                [(g["start"], g["end"], g["bbox"]) for g in e["glyphs"]]):
            raise ValueError("stale_or_mismatched_canonical_page")
    tables = []
    if native["structure_status"] == "parsed":
        paths = [t["source_path"] for t in native["tables"]]
        if len(paths) != len(set(paths)):
            raise ValueError("duplicate_source_path")
        tables = [certify(t, pages) for t in native["tables"]]
    return {"schema": 1, "doc_sha256": native["doc_sha256"],
            "source_kind": "pdf_structure_tree_mcid", "offset_unit": "utf16",
            "structure_status": native["structure_status"], "tables": tables}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native", required=True, type=Path)
    parser.add_argument("--probe", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    result = build_tagged_continuations(json.loads(args.native.read_text(encoding="utf-8")),
        [json.loads(line) for line in args.probe.read_text(encoding="utf-8").splitlines()])
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"tables": len(result["tables"]), "verified": sum(
        t["status"] == "verified_tagged_association" for t in result["tables"])}))
