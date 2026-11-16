"""Conservative continuation evidence for one explicitly shared source table.

No proximity-based table joining, text rewriting, hiding or rendering. A Docling
cell has no page id: resolve it only when exactly one complete, ordered partition
matches every cell and every PDF glyph in all of the table's page occurrences.
"""
from functools import lru_cache

from source_payload import (build_payload, contains, crop_rect, normalized,
                            rect_valid, source_mapping, table_grid_valid,
                            table_payload, verified_cell_bands)

MAX_ROWS = 500
MAX_PAGES = 32
MAX_CELL_GLYPH_CHECKS = 2_000_000


def logical_grid(table):
    """Compare source content, excluding page-dependent coordinate transforms."""
    keys = ("r", "c", "rs", "cs", "text", "column_header", "row_header", "row_section")
    return (table.get("rows"), table.get("cols"), sorted(
        [tuple(c.get(k) for k in keys) for c in table.get("cells", [])], key=lambda c: c[:2]))


def leading_headers(table):
    headers = []
    for row in range(table["rows"]):
        cells = [c for c in table["cells"] if c["r"] <= row < c["r"] + c["rs"]]
        if not cells or not all(c.get("column_header") for c in cells):
            break
        headers.append(row)
    return headers


def header_signature(table, count):
    return sorted((c["r"], c["c"], c["cs"], "".join(c["text"].split()))
                  for c in table["cells"] if c["r"] < count)


def certify_group(occurrences):
    first = occurrences[0]
    result = {"source_ref": first[2]["source_ref"], "source_scope": first[2].get("source_scope"),
              "status": "unverified",
              "reason": None, "fragments": [], "repeat_header": None,
              "replacement_range": None, "hide_source_allowed": False}

    def reject(reason):
        result["reason"] = reason
        return result

    if not result["source_scope"]:
        return reject("unscoped_source_reference")

    pages = [probe["page"] for probe, _, _, _ in occurrences]
    if (len(pages) < 2 or len(set(pages)) != len(pages) or
            pages != list(range(pages[0], pages[0] + len(pages))) or
            any(b.get("provenance_count") != len(pages) for _, _, b, _ in occurrences)):
        return reject("incomplete_or_nonconsecutive_provenance")
    table = first[2].get("table") or {}
    if not table_grid_valid(table):
        return reject("invalid_grid")
    if table["rows"] > MAX_ROWS or len(pages) > MAX_PAGES:
        return reject("verification_budget")
    if any(c["rs"] != 1 for c in table["cells"]):
        return reject("rowspan_page_assignment_unverified")
    signature = logical_grid(table)
    for probe, _, block, payload in occurrences:
        t = block.get("table") or {}
        if (logical_grid(t) != signature or t.get("geometry_status") != "ambiguous_multi_page"):
            return reject("inconsistent_source_grid")
        item = next(b for b in payload["blocks"] if b["id"] == block["id"])
        if (payload["geometry_status"] != "mapped" or payload["placement_status"] != "candidate" or
                item["source_region"] is None or probe.get("unmapped_ranges") or
                any(not rect_valid(c.get("bbox")) for c in t["cells"])):
            return reject("unsafe_geometry_or_order")

    class VerificationBudget(Exception):
        pass

    checks = 0

    def verify(table, layout, probe, bb):
        nonlocal checks
        checks += len(table["cells"]) * len(probe["glyphs"])
        if checks > MAX_CELL_GLYPH_CHECKS:
            raise VerificationBudget
        return verified_cell_bands(table, layout, probe, bb)

    @lru_cache(None)
    def row_fits(page_index, row):
        probe, layout, block, _ = occurrences[page_index]
        cells = [dict(c, r=0) for c in block["table"]["cells"] if c["r"] == row]
        boxes = [crop_rect(c["bbox"], layout) for c in cells]
        bb = [min(b[0] for b in boxes), min(b[1] for b in boxes),
              max(b[2] for b in boxes), max(b[3] for b in boxes)]
        if not contains(crop_rect(block["bbox"], layout), bb):
            return False
        bands = verify(dict(rows=1, cols=table["cols"], cells=cells), layout, probe, bb)
        return bands is not None and bool(bands[0])

    @lru_cache(None)
    def fragment(page_index, start, end):
        probe, layout, block, _ = occurrences[page_index]
        # Geometry is taken from THIS occurrence after that page's CropBox shift.
        cells = [dict(c, r=c["r"] - start) for c in block["table"]["cells"]
                 if start <= c["r"] < end]
        local = dict(block["table"], rows=end - start, cells=cells, geometry_status="single_page")
        bb = crop_rect(block["bbox"], layout)
        if (not table_grid_valid(local) or
                any(not contains(bb, crop_rect(c["bbox"], layout)) for c in cells) or
                not any(c["text"].strip() for c in cells)):
            return None
        # A complete text match alone does not prove top-to-bottom row order.
        bands = verify(local, layout, probe, bb)
        if (bands is None or any(not band for band in bands) or
                any(a[1] > b[0] for a, b in zip(bands, bands[1:]))):
            return None
        evidence = table_payload(dict(block, table=local), layout, probe)
        return local, evidence

    # Enumerate ordered partitions, saturating the solution count at two. Memoized
    # suffixes bound ambiguity handling; no Cartesian product of page assignments.
    @lru_cache(None)
    def partitions(page_index, start):
        remaining = len(pages) - page_index
        if remaining == 0:
            return ((),) if start == table["rows"] else ()
        solutions = []
        last = table["rows"] - remaining + 1
        ends = [table["rows"]] if remaining == 1 else range(start + 1, last + 1)
        for end in ends:
            if not all(row_fits(page_index, row) for row in range(start, end)):
                break
            if fragment(page_index, start, end) is None:
                continue
            for tail in partitions(page_index + 1, end):
                solutions.append(((start, end),) + tail)
                if len(solutions) == 2:
                    return tuple(solutions)
        return tuple(solutions)

    try:
        solutions = partitions(0, 0)
    except VerificationBudget:
        return reject("verification_budget")
    if len(solutions) != 1:
        return reject("no_exact_partition" if not solutions else "ambiguous_partition")
    partition = solutions[0]
    local, evidence = fragment(0, *partition[0])
    headers = leading_headers(local)
    region = evidence["repeat_header_region"]
    if not headers or len(headers) == local["rows"] or region is None:
        return reject("header_boundary_unverified")
    probe, layout, block, payload = first
    header_box = [v * (probe["width"] if i % 2 == 0 else probe["height"])
                  for i, v in enumerate(region)]
    mapping = source_mapping(header_box, probe)
    if mapping["mapping_status"] not in ("mapped", "discontiguous"):
        return reject("header_source_unverified")
    header = {"page": probe["page"], "block_id": block["id"], "source_region": region,
              "source_ranges": mapping["source_ranges"],
              "text_utf16_sha256": payload["text_utf16_sha256"],
              "replacement_range": None, "visual_copy_only": True}
    fragments = []
    for index, (start, end) in enumerate(partition):
        probe, layout, block, payload = occurrences[index]
        t, ev = fragment(index, start, end)
        native = leading_headers(t)
        if native and (len(native) != len(headers) or
                       header_signature(t, len(native)) != header_signature(local, len(headers))):
            return reject("inconsistent_repeated_header")
        if len(native) == t["rows"]:
            return reject("header_only_fragment")
        # Every within-page row cut must be independently safe; cross-page cuts
        # are physical page boundaries and do not invent a normalized y value.
        if [r["before_row"] for r in ev["row_breaks"]] != list(range(1, t["rows"])):
            return reject("fragment_row_boundary_unverified")
        fragments.append({"page": probe["page"], "block_id": block["id"],
                          "row_start": start, "row_end": end,
                          "source_region": normalized(crop_rect(block["bbox"], layout), layout),
                          "text_utf16_sha256": payload["text_utf16_sha256"],
                          "row_breaks": [dict(r, before_row=r["before_row"] + start)
                                         for r in ev["row_breaks"]],
                          "cells": [dict(c, r=c["r"] + start) for c in ev["cells"]],
                          "header_mode": "source" if native else "repeat_first_fragment",
                          "replacement_range": None})
    result.update(status="verified_source_fragments", reason=None,
                  fragments=fragments, repeat_header=header)
    return result


def build_continuations(probes, layouts):
    """Validate one private document batch and retain explicit rejected groups."""
    if len(probes) != len(layouts):
        raise ValueError("probe/layout batch size mismatch")
    payloads = [build_payload(p, l) for p, l in zip(probes, layouts)]
    if (len({p["doc_sha256"] for p in probes}) > 1 or
            len({p["page"] for p in probes}) != len(probes)):
        raise ValueError("mixed documents or duplicate pages")
    groups = {}
    for probe, layout, payload in zip(probes, layouts, payloads):
        for block in layout["blocks"]:
            if block["kind"] == "table" and block.get("source_ref"):
                key = (block.get("source_scope"), block["source_ref"])
                groups.setdefault(key, []).append((probe, layout, block, payload))
    candidates = [items for items in groups.values()
                  if any(b.get("provenance_count", 1) > 1 for _, _, b, _ in items)]
    return {"schema_version": 1, "doc_sha256": probes[0]["doc_sha256"] if probes else None,
            "offset_unit": "utf16", "coord_space": "normalized_unrotated_cropbox_top_left",
            "tables": [certify_group(sorted(items, key=lambda item: item[0]["page"]))
                       for items in candidates]}
