"""Suggest unlinked source lines for human review, never establish ownership.

Unlike object_label_groups, marker/alignment evidence is only a hypothesis.
Keep the original explicit group intact and retain separate canonical ranges.
"""
from __future__ import annotations

import re
from collections import Counter

from source_payload import contains, utf16

MARKER = re.compile(r"^(?:sources?\s*[:：]|(?:资料)?来源\s*[:：])\s*\S", re.IGNORECASE)
MAX_GAP_PT = 24


def envelope(boxes):
    return [min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes)]


def proposals(probe, layout, payload, boxes):
    """Return additional hypotheses; caller must still check the raster/prose.

    A whole model block must reproduce the canonical glyph slices exactly
    (ignoring whitespace only). A source marker inside ordinary prose, an
    unresolved explicit link, or two geometrically plausible owners cannot
    establish a suggestion. No text search or surrounding min/max span is used.
    """
    indexed = {b["id"]: b for b in payload["blocks"]}
    groups = {g["owner_id"]: g for g in payload["object_label_groups"]}
    objects = [b for b in layout["blocks"] if b["kind"] in ("table", "figure")]
    claimed = {(b.get("source_scope"), ref) for b in layout["blocks"]
               for key in ("caption_refs", "footnote_refs") for ref in b.get(key, [])}
    ref_counts = Counter((b.get("source_scope"), b.get("source_ref")) for b in layout["blocks"])
    bases = {}
    for owner in objects:
        group = groups[owner["id"]]
        parts = ([p["id"] for p in group["source_parts"]]
                 if group["placement_status"] == "source_parts_verified" else [])
        bases[owner["id"]] = envelope([boxes[i] for i in [owner["id"], *parts]])
    text = utf16(probe["text"])
    assigned = {}
    for b in layout["blocks"]:
        scope, ref = b.get("source_scope"), b.get("source_ref")
        value = b.get("text") or ""
        mapped = indexed[b["id"]]
        if (b["kind"] not in ("text", "footnote") or not scope or not ref or
                b.get("provenance_count") != 1 or (scope, ref) in claimed or
                ref_counts[scope, ref] != 1 or
                not MARKER.match(value.strip()) or
                mapped["mapping_status"] not in ("mapped", "discontiguous")):
            continue
        actual = b"".join(text[a * 2:z * 2] for a, z in mapped["source_ranges"]).decode("utf-16-le")
        if "".join(actual.split()) != "".join(value.split()):
            continue
        box = boxes[b["id"]]
        glyphs = [g for g in probe["glyphs"]
                  if any(a <= g["start"] and g["end"] <= z for a, z in mapped["source_ranges"])]
        # Respect the mapper's 0.05pt precision tolerance, then include the
        # complete glyphs in the suggested region instead of clipping them.
        if not glyphs or not all(contains(box, g["bbox"]) for g in glyphs):
            continue
        box = envelope([box, *[g["bbox"] for g in glyphs]])
        if not contains([0, 0, probe["width"], probe["height"]], box, epsilon=0):
            continue
        matches = []
        for owner in objects:
            base = bases[owner["id"]]
            overlap = min(base[2], box[2]) - max(base[0], box[0])
            if (0 <= box[1] - base[3] <= MAX_GAP_PT and
                    overlap >= min(base[2] - base[0], box[2] - box[0]) / 2):
                matches.append(owner)
        if len(matches) != 1:
            continue
        owner, = matches
        group = groups[owner["id"]]
        if (owner.get("source_scope") != scope or owner.get("provenance_count") != 1 or
                group["placement_status"] != "source_parts_verified" or
                not owner.get("caption_refs")):
            continue
        assigned.setdefault(owner["id"], []).append({
            "id": b["id"], "role": "source_line_candidate", "source_ref": ref,
            "source_scope": scope, "source_ranges": mapped["source_ranges"],
            "source_region": [v / (probe["width"] if i % 2 == 0 else probe["height"])
                              for i, v in enumerate(box)],
            "geometry_evidence": "complete_glyph_envelope_within_mapping_precision",
            "ownership_status": "unverified"})
    return [{"owner_id": owner, "source_parts": parts,
             "base_part_ids": [owner, *[p["id"] for p in groups[owner]["source_parts"]]],
             "association_evidence": "source_marker_below_unique_object",
             "ownership_status": "unverified"} for owner, parts in assigned.items()]
