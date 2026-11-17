#!/usr/bin/env python3
"""Prepare pending production selections from local B/C evidence.

Only identical pixel rectangles are folded. Overlap groups are alternatives,
not semantic duplicates. Explicit caption/note links can suggest a larger rectangle;
all suggestions still require fresh production previews and human review.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil

from benchmark import outside_repo
from crop_candidates import boundary, prepare, preview_geometry_safe, SCALE
from source_payload import build_payload, contains, crop_rect, intersects, utf16

PROSE = {"text", "paragraph", "heading", "list", "footnote"}
KINDS = {"table": "TABLE", "formula": "FORMULA", "figure": "ILLUSTRATION"}


def union(boxes):
    return [min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes)]


def plan_page(probe, layout, pixels, width, height, scale=SCALE):
    # Regenerate boundaries from current inputs; old candidate files confer no trust.
    candidates = prepare(probe, layout, pixels, width, height, scale)
    payload = build_payload(probe, layout)
    blocks = layout["blocks"]
    if len({b["id"] for b in blocks}) != len(blocks):
        raise ValueError("duplicate layout block IDs")
    by_id = {b["id"]: b for b in blocks}
    boxes = {b["id"]: crop_rect(b["bbox"], layout) for b in blocks}
    links = {g["owner_id"]: g for g in payload["object_label_groups"]}
    for group in links.values():
        if group["placement_status"] == "source_parts_verified":
            for part in group["source_parts"]:
                if part.get("geometry_evidence") == "bounded_glyph_envelope_exact_note_text":
                    boxes[part["id"]] = [v * (probe["width"] if i % 2 == 0 else probe["height"])
                                         for i, v in enumerate(part["source_region"])]
    rows = []

    def annotate(identity, evidence, owners, allowed_parts=()):
        row = {"id": identity, "evidence": evidence, "owners": sorted(owners),
               "semantic_status": "unverified", "import_allowed": False,
               "hide_source_allowed": False, "replacement_range": None,
               "blockers": [], "warnings": ["complete_object_labels_unverified"],
               "selection": None}
        if evidence["status"] != "boundary_verified":
            row["blockers"].append(evidence["status"])
            return row
        pixel_box = evidence["pixel_box"]
        box = [v / scale for v in pixel_box]
        selected = [g for g in probe["glyphs"] if intersects(box, g["bbox"])]
        if evidence["mapping_status"] not in ("mapped", "discontiguous"):
            row["blockers"].append("no_production_text_mapping")
        # Model labels are only a conservative veto, never permission to discard text.
        owned_notes = {p["id"] for o in owners for p in links.get(o, {}).get("source_parts", [])
                       if p["role"] == "footnote" and
                       links[o]["placement_status"] == "source_parts_verified"}
        prose = [b["id"] for b in blocks if b["kind"] in PROSE and b["id"] not in owned_notes and
                 any(intersects(boxes[b["id"]], g["bbox"]) for g in selected)]
        row["prose_block_ids"] = prose
        if prose:
            row["blockers"].append("possible_body_text")
        for owner in owners:
            group = links.get(owner)
            if by_id[owner].get("caption_refs"):
                if not group or group["placement_status"] != "source_parts_verified":
                    row["blockers"].append("unresolved_explicit_caption")
                elif not all(contains(box, boxes[p["id"]], epsilon=0) for p in group["source_parts"]
                             if p["role"] == "caption"):
                    row["blockers"].append("explicit_caption_outside_crop")
            if by_id[owner].get("footnote_refs"):
                if not group or group["placement_status"] != "source_parts_verified":
                    row["blockers"].append("unresolved_explicit_notes")
                elif not all(contains(box, boxes[p["id"]], epsilon=0) for p in group["source_parts"]
                             if p["role"] == "footnote"):
                    row["blockers"].append("explicit_notes_outside_crop")
        if allowed_parts and any(not any(contains(boxes[part], g["bbox"]) for part in allowed_parts)
                                 for g in selected):
            row["blockers"].append("unowned_glyph_in_expansion")
        # Even an explicit caption link does not establish complete notes/credits.
        # Missing links are never filled from proximity or a caption-looking string.
        if not owners or any(not by_id[o].get("caption_refs") for o in owners):
            row["warnings"].append("labels_not_established")
        row["blockers"] = sorted(set(row["blockers"]))
        if not row["blockers"]:
            kinds = {KINDS.get(by_id[o]["kind"], "COMPLEX_REGION") for o in owners}
            kind = next(iter(kinds)) if len(kinds) == 1 else "COMPLEX_REGION"
            row["selection"] = dict(zip(("left", "top", "right", "bottom"), pixel_box),
                                    page=probe["page"], kind=kind)
        return row

    for i, candidate in enumerate(candidates["candidates"]):
        owners = {blocks[int(ref[2:])]["id"] for ref in candidate["proposers"] if ref.startswith("B:")}
        # Do not copy a candidate's experimental replacement_range into this plan.
        evidence = {k: v for k, v in candidate.items() if k not in
                    ("replacement_range", "hide_source_allowed", "import_allowed", "semantic_status")}
        rows.append(annotate(f"candidate-{i:03d}", evidence, owners))
    safe = preview_geometry_safe(probe, payload)
    for group in payload["object_label_groups"]:
        if group["placement_status"] != "source_parts_verified" or not safe:
            continue
        parts = [group["owner_id"], *[p["id"] for p in group["source_parts"]]]
        evidence = boundary(probe, union([boxes[p] for p in parts]), pixels, width, height, scale)
        evidence["caption_part_ids"] = [p["id"] for p in group["source_parts"] if p["role"] == "caption"]
        evidence["footnote_part_ids"] = [p["id"] for p in group["source_parts"] if p["role"] == "footnote"]
        evidence["adjusted_note_part_ids"] = [p["id"] for p in group["source_parts"]
                                              if p.get("geometry_evidence")]
        rows.append(annotate("labels-" + str(len(rows)), evidence, {group["owner_id"]}, parts))

    # Lossless folding: only equal pixel rectangles; conflicting kinds stay generic.
    # Preserve all evidence and use the union of vetoes; a clean C proposal must
    # not bypass a body/caption warning on the identical B proposal.
    folded, seen = [], {}
    for row in rows:
        selection = row["selection"]
        key = tuple(row["evidence"].get("pixel_box", []))
        if not key:
            folded.append(dict(row, aliases=[row["id"]], alternatives=[row]))
            continue
        if key not in seen:
            seen[key] = len(folded)
            folded.append(dict(row, aliases=[row["id"]], alternatives=[row]))
            continue
        target = folded[seen[key]]
        target["aliases"].append(row["id"])
        target["alternatives"].append(row)
        target["blockers"] = sorted(set(target["blockers"] + row["blockers"]))
        target["warnings"] = sorted(set(target["warnings"] + row["warnings"]))
        if target["blockers"]:
            target["selection"] = None
        elif selection and target["selection"] and selection["kind"] != target["selection"]["kind"]:
            target["selection"] = dict(target["selection"], kind="COMPLEX_REGION")

    # Connected overlap components group alternatives for inspection. No largest
    # box winner, IoU threshold, union of a component, or transitive suppression.
    groups, remaining = [], set(range(len(folded)))
    while remaining:
        pending, component = [min(remaining)], []
        remaining.remove(pending[0])
        while pending:
            i = pending.pop()
            component.append(i)
            box = folded[i]["evidence"].get("pixel_box")
            hits = [j for j in sorted(remaining) if box and
                    folded[j]["evidence"].get("pixel_box") and
                    intersects(box, folded[j]["evidence"]["pixel_box"])]
            remaining.difference_update(hits)
            pending.extend(hits)
        groups.append(sorted(component))
    for group_id, component in enumerate(groups):
        for i in component:
            folded[i]["overlap_group"] = group_id
    return {"schema": 1, "page": probe["page"], "doc_sha256": probe["doc_sha256"],
            "text_utf16_sha256": hashlib.sha256(utf16(probe["text"])).hexdigest(),
            "input_candidates": len(candidates["candidates"]), "proposals": len(rows),
            "items": folded, "overlap_groups": groups, "approved": 0,
            "import_allowed": False, "hide_source_allowed": False}


def run(args):
    import pypdfium2 as pdfium
    from PIL import ImageChops

    output = outside_repo(args.out)
    if output.exists():
        raise ValueError("output must be new")
    probes = [json.loads(line) for line in args.probe.read_text(encoding="utf-8").splitlines()]
    if not probes or len(probes) > 64 or len({p["page"] for p in probes}) != len(probes):
        raise ValueError("empty, duplicate or excessive probe pages")
    digest = hashlib.sha256(args.pdf.read_bytes()).hexdigest()
    if any(p["doc_sha256"] != digest for p in probes):
        raise ValueError("stale PDF/probe")
    plans = []
    with pdfium.PdfDocument(str(args.pdf)) as document:
        for probe in probes:
            if type(probe["page"]) is not int or not 1 <= probe["page"] <= len(document):
                raise ValueError("invalid physical page")
            layout = json.loads((args.layout / f'{probe["page"]:05d}.json').read_text(encoding="utf-8"))
            if max(probe["width"], probe["height"]) * SCALE > 4096:
                raise ValueError("raster budget exceeded")
            page = document[probe["page"] - 1]
            try:
                bitmap = page.render(scale=SCALE)
                try:
                    image = bitmap.to_pil().convert("RGB")
                finally:
                    bitmap.close()
            finally:
                page.close()
            red, green, blue = image.split()
            ink = ImageChops.darker(ImageChops.darker(red, green), blue)
            plans.append(plan_page(probe, layout, ink.tobytes(), image.width, image.height))
            image.close()
    selections = [(plan, item) for plan in plans for item in plan["items"] if item["selection"]]
    if len(selections) > 128 or any(n > 32 for n in Counter(p["page"] for p, _ in selections).values()):
        raise ValueError("production preview budget exceeded; use smaller page batches")
    summary = {"pages": len(plans), "input_candidates": sum(p["input_candidates"] for p in plans),
               "boundary_candidates": sum(sum(a["evidence"]["status"] == "boundary_verified" and
                                              a["id"].startswith("candidate-")
                                              for i in p["items"] for a in i["alternatives"]) for p in plans),
               "proposals": sum(p["proposals"] for p in plans),
               "exact_rectangles_folded": sum(p["proposals"] - len(p["items"]) for p in plans),
               "pending_selections": len(selections), "approved": 0,
               "blockers": dict(Counter(b for p in plans for i in p["items"] for b in i["blockers"])),
               "warnings": dict(Counter(w for p in plans for i in p["items"] for w in i["warnings"])),
               "import_allowed": False, "hide_source_allowed": False}
    output.mkdir(parents=True, mode=0o700)
    try:
        (output / "selections").mkdir(mode=0o700)
        for plan in plans:
            (output / f'{plan["page"]:05d}-private.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
        for plan, item in selections:
            name = f'{plan["page"]:05d}-{item["id"]}.selection.json'
            (output / "selections" / name).write_text(json.dumps(item["selection"]), encoding="utf-8")
        (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    except BaseException:
        shutil.rmtree(output)
        raise
    print(json.dumps(summary))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--layout", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    run(parser.parse_args())
