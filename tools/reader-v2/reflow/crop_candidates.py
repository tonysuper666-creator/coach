#!/usr/bin/env python3
"""Local B/C crop experiment. Near-white edges are not a whole-object certificate.

All crops remain review candidates. Nothing here authorizes import, replacement,
caption ownership or hiding text. Real content output must stay outside Git.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path

from benchmark import outside_repo
from source_payload import build_payload, contains, crop_rect, intersects, rect_valid, source_mapping, utf16

MAX_CANDIDATES = 512
SCALE = 2
SPECIAL = {"figure", "table", "formula", "unknown"}


def proposals(probe, layout):
    """Keep B, C and pairwise union alternatives; never call a union consensus."""
    b = [("B:" + str(i), crop_rect(row["bbox"], layout))
         for i, row in enumerate(layout["blocks"])
         if row.get("kind") in SPECIAL and rect_valid(row.get("bbox"))]
    c = [("C:" + str(i), row["bbox"]) for i, row in enumerate(probe["regions"])
         if rect_valid(row.get("bbox"))]
    if len(b) + len(c) > MAX_CANDIDATES:
        raise ValueError("candidate budget exceeded")
    result = {}

    def add(ids, box):
        key = tuple(box)
        result.setdefault(key, set()).update(ids)
        if len(result) > MAX_CANDIDATES:
            raise ValueError("candidate budget exceeded")

    for identity, box in b + c:
        add([identity], box)
    for bi, bb in b:
        for ci, cb in c:
            if not intersects(bb, cb):
                continue
            union = [min(bb[0], cb[0]), min(bb[1], cb[1]), max(bb[2], cb[2]), max(bb[3], cb[3])]
            area = lambda r: (r[2] - r[0]) * (r[3] - r[1])
            # Avoid sprawling unions; even a small union still needs object review.
            if area(union) <= 1.5 * max(area(bb), area(cb)):
                add([bi, ci], union)
    return [(sorted(ids), list(box)) for box, ids in result.items()]


def white_edge(pixels, width, box, band):
    left, top, right, bottom = box
    for y in range(top, bottom):
        if y < top + band or y >= bottom - band:
            if any(v < 250 for v in pixels[y * width + left:y * width + right]):
                return False
        elif (any(v < 250 for v in pixels[y * width + left:y * width + left + band]) or
              any(v < 250 for v in pixels[y * width + right - band:y * width + right])):
            return False
    return True


def boundary(probe, box, pixels, width, height, scale):
    """Bounded outward search, two-point white moat, exact glyph containment.

    A detached label may be entirely outside this rectangle; ink with all RGB
    channels >= 250 escapes the edge threshold. The caller must keep semantic
    completeness unverified even when this function succeeds.
    """
    if not rect_valid(box) or not contains([0, 0, probe["width"], probe["height"]], box, epsilon=0):
        return {"status": "outside_page"}
    band = math.ceil(2 * scale)
    for padding in range(2, 9):
        pixel_box = [math.floor((box[0] - padding) * scale), math.floor((box[1] - padding) * scale),
                     math.ceil((box[2] + padding) * scale), math.ceil((box[3] + padding) * scale)]
        left, top, right, bottom = pixel_box
        # Do not clamp a failed edge: that would remove the evidence we require.
        if left < 0 or top < 0 or right > width or bottom > height:
            return {"status": "no_page_margin"}
        if not white_edge(pixels, width, pixel_box, band):
            continue
        points = [v / scale for v in pixel_box]
        if not contains([0, 0, probe["width"], probe["height"]], points, epsilon=0):
            return {"status": "no_page_margin"}
        if not any(v < 250 for y in range(top, bottom) for v in pixels[y * width + left:y * width + right]):
            return {"status": "empty"}
        mapping = source_mapping(points, probe)
        if mapping["mapping_status"] == "ambiguous":
            continue
        return {"status": "boundary_verified", "pixel_box": pixel_box, "padding_points": padding,
                "source_region": [v / (probe["width"] if i % 2 == 0 else probe["height"]) for i, v in enumerate(points)],
                "source_ranges": mapping["source_ranges"], "mapping_status": mapping["mapping_status"]}
    return {"status": "no_verified_boundary"}


def prepare(probe, layout, pixels, width, height, scale=SCALE):
    payload = build_payload(probe, layout)  # Validate document, page and page frames first.
    if (not math.isfinite(scale) or not 0 < scale <= 4 or max(width, height) > 4096 or
            width != math.ceil(probe["width"] * scale) or height != math.ceil(probe["height"] * scale) or
            len(pixels) != width * height):
        raise ValueError("raster/frame mismatch or raster budget exceeded")
    # Older probe files cannot establish these facts. Regenerate them, do not assume safe.
    required = ("clip_unverified", "graphics_unsupported", "rotated_glyphs", "unmapped_ranges")
    safe = (payload["geometry_status"] == "mapped" and all(k in probe for k in required) and
            not probe["unmapped_ranges"])
    rows = []
    for ids, box in proposals(probe, layout):
        row = {"proposers": ids, "proposal_box": box, "semantic_status": "unverified",
               "replacement_range": None, "hide_source_allowed": False, "import_allowed": False}
        row.update(boundary(probe, box, pixels, width, height, scale) if safe else {"status": "unsafe_geometry"})
        rows.append(row)
    return {"schema": 1, "page": probe["page"], "doc_sha256": probe["doc_sha256"],
            "text_utf16_sha256": hashlib.sha256(utf16(probe["text"])).hexdigest(),
            "coord_space": "normalized_unrotated_cropbox_top_left", "candidates": rows}


def run(args):
    output = outside_repo(args.out)
    output.mkdir(parents=True, exist_ok=False)
    # No remote input, model call or hidden download. Dependencies are supplied locally.
    import pypdfium2 as pdfium
    from PIL import ImageChops
    digest = hashlib.sha256(args.pdf.read_bytes()).hexdigest()
    probes = [json.loads(line) for line in args.probe.read_text(encoding="utf-8").splitlines()]
    if len({p["page"] for p in probes}) != len(probes):
        raise ValueError("duplicate probe pages")
    counts = Counter()
    with pdfium.PdfDocument(str(args.pdf)) as document:
        for probe in probes:
            if probe["doc_sha256"] != digest:
                raise ValueError("stale PDF/probe")
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
            # A bright yellow/blue stroke can have near-white luminance. Require
            # every RGB channel to be white, not just its grayscale conversion.
            ink = ImageChops.darker(ImageChops.darker(red, green), blue)
            result = prepare(probe, layout, ink.tobytes(), image.width, image.height)
            for i, row in enumerate(result["candidates"]):
                counts[row["status"]] += 1
                if row["status"] == "boundary_verified":
                    name = f'{probe["page"]:05d}-{i:03d}.png'
                    image.crop(row["pixel_box"]).save(output / name)
                    row["crop_file"] = name
            (output / f'{probe["page"]:05d}-private.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {"pages": len(probes), "candidate_statuses": dict(counts), "semantic_quality": "unverified",
               "import_allowed": False, "hide_source_allowed": False}
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--layout", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    run(parser.parse_args())
