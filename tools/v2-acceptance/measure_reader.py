#!/usr/bin/env python3
"""Unified reader viewport, body pixel, nesting, and drawer-occlusion measurement tool.

Combines Compose SemanticsNode rectangles (from `<shot>.meta.json`) with actual rendered
bitmap pixel measurements (from `<shot>.png`). Explicitly labels each result as either
`real_os_window` ("真 OS 窗口") or `image_compose_scene_offscreen` ("ImageComposeScene 离屏").
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import zlib
from collections import Counter
from dataclasses import dataclass
from typing import Any, Iterable

try:
    from PIL import Image as _PilImage  # type: ignore
except ImportError:
    _PilImage = None


SURFACE_LABELS = {
    "real_os_window": "真 OS 窗口",
    "image_compose_scene_offscreen": "ImageComposeScene 离屏",
}

OVERLAY_TAGS = {
    "immersive-toc-drawer",
    "v7-directory-drawer",
    "book-directory-drawer",
    "immersive-companion-drawer",
    "immersive-top-hud",
    "immersive-bottom-hud",
    "selection-explain-toolbar",
    "book-search-panel",
}

DRAWER_ONLY_TAGS = {
    "immersive-toc-drawer",
    "v7-directory-drawer",
    "book-directory-drawer",
    "immersive-companion-drawer",
}


@dataclass(frozen=True)
class Rect:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return max(0, self.right - self.left)

    @property
    def height(self) -> int:
        return max(0, self.bottom - self.top)

    @property
    def area(self) -> int:
        return self.width * self.height

    def clamp(self, w: int, h: int) -> "Rect":
        return Rect(
            max(0, min(w, self.left)),
            max(0, min(h, self.top)),
            max(0, min(w, self.right)),
            max(0, min(h, self.bottom)),
        )

    def intersect(self, other: "Rect") -> "Rect | None":
        l = max(self.left, other.left)
        t = max(self.top, other.top)
        r = min(self.right, other.right)
        b = min(self.bottom, other.bottom)
        if r <= l or b <= t:
            return None
        return Rect(l, t, r, b)

    def to_list(self) -> list[int]:
        return [self.left, self.top, self.right, self.bottom]


def rect_from_node(node: dict[str, Any], w: int, h: int) -> Rect:
    b = node.get("bounds") or [0, 0, 0, 0]
    return Rect(int(round(b[0])), int(round(b[1])), int(round(b[2])), int(round(b[3]))).clamp(w, h)


def subtract_rects(base: Rect, blockers: Iterable[Rect]) -> list[Rect]:
    """Return disjoint rectangles representing `base \\ union(blockers)`."""
    pieces = [base] if base.area > 0 else []
    for blk in blockers:
        next_pieces: list[Rect] = []
        for p in pieces:
            inter = p.intersect(blk)
            if inter is None:
                next_pieces.append(p)
                continue
            if inter.top > p.top:
                next_pieces.append(Rect(p.left, p.top, p.right, inter.top))
            if inter.bottom < p.bottom:
                next_pieces.append(Rect(p.left, inter.bottom, p.right, p.bottom))
            if inter.left > p.left:
                next_pieces.append(Rect(p.left, inter.top, inter.left, inter.bottom))
            if inter.right < p.right:
                next_pieces.append(Rect(inter.right, inter.top, p.right, inter.bottom))
        pieces = [q for q in next_pieces if q.area > 0]
    return pieces


def disjoint_union(rects: Iterable[Rect]) -> list[Rect]:
    """Return pairwise-disjoint rectangles covering the exact union of `rects`."""
    disjoint: list[Rect] = []
    for r in rects:
        if r.area <= 0:
            continue
        pieces = subtract_rects(r, disjoint)
        disjoint.extend(pieces)
    return disjoint


def union_area(rects: Iterable[Rect]) -> int:
    """Exact area of union of axis-aligned rectangles via sweep-line."""
    rect_list = [r for r in rects if r.area > 0]
    if not rect_list:
        return 0
    xs = sorted({r.left for r in rect_list} | {r.right for r in rect_list})
    total = 0
    for i in range(len(xs) - 1):
        x0, x1 = xs[i], xs[i + 1]
        if x1 <= x0:
            continue
        intervals = sorted((r.top, r.bottom) for r in rect_list if r.left <= x0 and r.right >= x1)
        if not intervals:
            continue
        cur_s, cur_e = intervals[0]
        y_len = 0
        for s, e in intervals[1:]:
            if s <= cur_e:
                cur_e = max(cur_e, e)
            else:
                y_len += cur_e - cur_s
                cur_s, cur_e = s, e
        y_len += cur_e - cur_s
        total += (x1 - x0) * y_len
    return total


def read_png_rgb(path: str) -> tuple[int, int, Any]:
    """Load PNG as `(width, height, get_rgb_fn_or_rows)` using PIL when available, else stdlib."""
    if _PilImage is not None:
        img = _PilImage.open(path).convert("RGB")
        w, h = img.size
        return w, h, img

    with open(path, "rb") as f:
        sig = f.read(8)
        if sig != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"Not a PNG file: {path}")
        width = height = bit_depth = color_type = None
        idat = bytearray()
        while True:
            hdr = f.read(8)
            if not hdr:
                break
            length, ctype = struct.unpack(">I4s", hdr)
            data = f.read(length)
            f.read(4)
            if ctype == b"IHDR":
                width, height, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", data)
            elif ctype == b"IDAT":
                idat.extend(data)
            elif ctype == b"IEND":
                break
    if bit_depth != 8 or color_type not in (2, 6):
        raise ValueError(f"Unsupported PNG format depth={bit_depth} type={color_type}: {path}")
    assert width is not None and height is not None
    bpp = 3 if color_type == 2 else 4
    raw = zlib.decompress(bytes(idat))
    stride = width * bpp
    rows: list[bytes] = []
    prev = bytearray(stride)
    pos = 0
    for _ in range(height):
        filt = raw[pos]
        pos += 1
        row = bytearray(raw[pos : pos + stride])
        pos += stride
        if filt == 1:
            for i in range(bpp, stride):
                row[i] = (row[i] + row[i - bpp]) & 0xFF
        elif filt == 2:
            for i in range(stride):
                row[i] = (row[i] + prev[i]) & 0xFF
        elif filt == 3:
            for i in range(stride):
                left = row[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filt == 4:
            for i in range(stride):
                a = row[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                row[i] = (row[i] + pr) & 0xFF
        rows.append(bytes(row))
        prev = row
    return width, height, (bpp, rows)


def pixel_iter_rect(img_obj: Any, rect: Rect) -> Iterable[tuple[int, int, tuple[int, int, int]]]:
    if _PilImage is not None and isinstance(img_obj, _PilImage.Image):
        crop = img_obj.crop((rect.left, rect.top, rect.right, rect.bottom))
        data = crop.tobytes()
        cw = rect.width
        idx = 0
        for dy in range(rect.height):
            y = rect.top + dy
            for dx in range(cw):
                yield rect.left + dx, y, (data[idx], data[idx + 1], data[idx + 2])
                idx += 3
        return

    bpp, rows = img_obj
    for y in range(rect.top, rect.bottom):
        row = rows[y]
        for x in range(rect.left, rect.right):
            o = x * bpp
            yield x, y, (row[o], row[o + 1], row[o + 2])


def color_dist(a: tuple[int, int, int], b: tuple[int, int, int]) -> int:
    return abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])


def walk_nodes(node: dict[str, Any], path: tuple[dict[str, Any], ...] = ()) -> Iterable[tuple[dict[str, Any], tuple[dict[str, Any], ...]]]:
    cur_path = path + (node,)
    yield node, cur_path
    for child in node.get("children") or []:
        yield from walk_nodes(child, cur_path)


def modal_bg_in_pieces(img_obj: Any, pieces: list[Rect]) -> tuple[int, int, int]:
    """Sample pixels across `pieces` to robustly identify paper/canvas background RGB."""
    counts: Counter[tuple[int, int, int]] = Counter()
    for rect in pieces:
        step_x = max(1, rect.width // 32)
        step_y = max(1, rect.height // 32)
        if _PilImage is not None and isinstance(img_obj, _PilImage.Image):
            px = img_obj.load()
            for y in range(rect.top, rect.bottom, step_y):
                for x in range(rect.left, rect.right, step_x):
                    counts[px[x, y]] += 1
        else:
            bpp, rows = img_obj
            for y in range(rect.top, rect.bottom, step_y):
                row = rows[y]
                for x in range(rect.left, rect.right, step_x):
                    o = x * bpp
                    counts[(row[o], row[o + 1], row[o + 2])] += 1
    if not counts:
        return (255, 255, 255)
    return counts.most_common(1)[0][0]


def measure_bitmap_ink(img_obj: Any, pieces: list[Rect], bg_rgb: tuple[int, int, int], threshold: int = 18) -> dict[str, Any]:
    """Count real non-background pixels and compute tight non-background ink bounding box."""
    total_px = sum(r.area for r in pieces)
    nonbg_px = 0
    min_x = min_y = 10**9
    max_x = max_y = -1
    if _PilImage is not None and isinstance(img_obj, _PilImage.Image):
        for r in pieces:
            if r.area <= 0:
                continue
            crop = img_obj.crop((r.left, r.top, r.right, r.bottom))
            data = crop.tobytes()
            cw = r.width
            ch = r.height
            br, bg, bb = bg_rgb
            idx = 0
            for dy in range(ch):
                y = r.top + dy
                for dx in range(cw):
                    d = abs(data[idx] - br) + abs(data[idx + 1] - bg) + abs(data[idx + 2] - bb)
                    if d > threshold:
                        nonbg_px += 1
                        x = r.left + dx
                        if x < min_x:
                            min_x = x
                        if x + 1 > max_x:
                            max_x = x + 1
                        if y < min_y:
                            min_y = y
                        if y + 1 > max_y:
                            max_y = y + 1
                    idx += 3
    else:
        br, bg, bb = bg_rgb
        bpp, rows = img_obj
        for r in pieces:
            for y in range(r.top, r.bottom):
                row = rows[y]
                for x in range(r.left, r.right):
                    o = x * bpp
                    d = abs(row[o] - br) + abs(row[o + 1] - bg) + abs(row[o + 2] - bb)
                    if d > threshold:
                        nonbg_px += 1
                        if x < min_x:
                            min_x = x
                        if x + 1 > max_x:
                            max_x = x + 1
                        if y < min_y:
                            min_y = y
                        if y + 1 > max_y:
                            max_y = y + 1

    bbox_area = max(0, max_x - min_x) * max(0, max_y - min_y) if max_x > min_x and max_y > min_y else 0
    bbox = [min_x, min_y, max_x, max_y] if bbox_area > 0 else [0, 0, 0, 0]
    return {
        "region_area_px": total_px,
        "bg_rgb": list(bg_rgb),
        "nonbg_pixels": nonbg_px,
        "ink_bbox_px": bbox,
        "ink_bbox_area_px": bbox_area,
    }


def compute_nesting_layers(all_nodes: list[tuple[dict[str, Any], tuple[dict[str, Any], ...]]], w: int, h: int) -> int:
    """Compute container nesting layers from Compose semantics hierarchy.

    Counts distinct visible container chrome/card layers wrapping the reading content:
    - Outer host navigation shell (`host-navigation` visible alongside content)
    - V7 Desk shell (`v7-desk`)
    - V7 outer paper card (`v7-paper`)
    - Reader frame (`textbook-reader` or `textbook-learning`) when strictly padded/shrunk by
      persistent toolbars/cards around `reader-page`, OR counts 1 for the full-bleed reader itself
    - Inner page centering / desk wrapper (`book-page-turn` narrower than `reader-page`, or
      `original-pdf-scroll` inside `v7-paper` framing PDF pages)
    - Inner paper card / explanation card (`book-sheet-*` inside `v7-paper`/`book-page-turn`, or
      `book-lesson-explanation` inside `textbook-learning`)
    """
    by_tag: dict[str, list[tuple[dict[str, Any], tuple[dict[str, Any], ...]]]] = {}
    for node, path in all_nodes:
        tag = node.get("tag") or ""
        if tag:
            by_tag.setdefault(tag, []).append((node, path))

    def visible_rect(tag: str) -> Rect | None:
        for n, _ in by_tag.get(tag, []):
            r = rect_from_node(n, w, h)
            if r.area > 0:
                return r
        return None

    def prefix_rect(prefix: str) -> Rect | None:
        for tag, items in by_tag.items():
            if tag.startswith(prefix):
                for n, _ in items:
                    r = rect_from_node(n, w, h)
                    if r.area > 0:
                        return r
        return None

    has_v7_desk = visible_rect("v7-desk") is not None
    has_v7_paper = visible_rect("v7-paper") is not None
    has_rail = visible_rect("host-navigation") is not None
    reader_r = visible_rect("textbook-reader")
    learning_r = visible_rect("textbook-learning")
    vp_r = visible_rect("reader-page")

    # Guided / learning-only screen (e.g., 1.1.1 边看边学)
    if vp_r is None and learning_r is not None:
        layers = 0
        if has_rail:
            layers += 1
        if has_v7_desk:
            layers += 1
        if has_v7_paper:
            layers += 1
        layers += 1  # textbook-learning
        if visible_rect("book-lesson-explanation") is not None:
            layers += 1
        return max(1, layers)

    if vp_r is None:
        return 0

    layers = 0
    if has_rail:
        layers += 1
    if has_v7_desk:
        layers += 1
    if has_v7_paper:
        layers += 1

    # Reader layer: if reader_r has toolbars/guide-card shrinking vp_r, or is the primary canvas
    if reader_r is not None and (
        vp_r.left > reader_r.left + 2
        or vp_r.top > reader_r.top + 2
        or vp_r.right < reader_r.right - 2
        or vp_r.bottom < reader_r.bottom - 2
        or layers == 0
    ):
        layers += 1

    # Inner centering/desk wrappers inside reader-page
    turn_r = visible_rect("book-page-turn")
    orig_scroll_r = visible_rect("original-pdf-scroll")
    orig_page_r = prefix_rect("original-pdf-page-")
    sheet_r = visible_rect("book-sheet-0") or visible_rect("book-sheet-1")

    if turn_r is not None and (turn_r.width < vp_r.width - 4 or has_v7_paper):
        layers += 1
    if sheet_r is not None and (has_v7_paper or (turn_r is not None and turn_r.width < vp_r.width - 4)):
        layers += 1
    if orig_scroll_r is not None and orig_page_r is not None and has_v7_paper:
        # In 1.1.1 Original PDF, original-pdf-scroll adds inner desk padding around original-pdf-page-*
        if orig_page_r.width < orig_scroll_r.width - 4 or orig_page_r.height < orig_scroll_r.height - 4:
            layers += 1

    return max(1, layers)


def measure_pair(png_path: str, meta_path: str) -> dict[str, Any]:
    with open(meta_path, "r", encoding="utf-8") as f:
        meta = json.load(f)

    w, h, img_obj = read_png_rgb(png_path)
    win_area = w * h
    surface_type = meta.get("surface_type") or "unknown"
    if surface_type not in SURFACE_LABELS:
        raise ValueError(f"Invalid or missing surface_type in {meta_path}: {surface_type!r}")

    roots = meta.get("semantics") or []
    all_nodes: list[tuple[dict[str, Any], tuple[dict[str, Any], ...]]] = []
    for root in roots:
        all_nodes.extend(walk_nodes(root))

    # Locate reader viewport
    vp_rects: list[Rect] = []
    for n, _ in all_nodes:
        if n.get("tag") == "reader-page":
            r = rect_from_node(n, w, h)
            if r.area > 0:
                vp_rects.append(r)

    # Locate body/page nodes (original-pdf-page-* or reader-text* / book-figure-*)
    pdf_rects: list[Rect] = []
    text_rects: list[Rect] = []
    explain_rects: list[Rect] = []
    for n, _ in all_nodes:
        tag = n.get("tag") or ""
        r = rect_from_node(n, w, h)
        if r.area <= 0:
            continue
        if tag.startswith("original-pdf-page-"):
            pdf_rects.append(r)
        elif tag == "reader-text" or tag.startswith("reader-text-") or tag.startswith("book-figure-"):
            text_rects.append(r)
        elif tag == "book-lesson-explanation":
            explain_rects.append(r)

    is_pdf = len(pdf_rects) > 0
    body_rects = pdf_rects if is_pdf else text_rects

    # Locate visible overlays & drawers
    overlay_rects: list[Rect] = []
    drawer_only_rects: list[Rect] = []
    active_overlays: list[str] = []
    for n, _ in all_nodes:
        tag = n.get("tag") or ""
        if tag in OVERLAY_TAGS:
            r = rect_from_node(n, w, h)
            if r.area > 0:
                overlay_rects.append(r)
                active_overlays.append(tag)
                if tag in DRAWER_ONLY_TAGS:
                    drawer_only_rects.append(r)

    # Hairline progress bar (2dp) is an overlay at bottom edge when HUD is hidden
    for n, _ in all_nodes:
        if n.get("tag") == "immersive-progress-hairline":
            r = rect_from_node(n, w, h)
            if r.area > 0:
                overlay_rects.append(r)

    # Viewport area minus overlays/drawers
    vp_pieces: list[Rect] = []
    for vr in vp_rects:
        vp_pieces.extend(subtract_rects(vr, overlay_rects))
    vp_unoccluded_area = union_area(vp_pieces)
    vp_raw_area = union_area(vp_rects)

    # Occlusion by overlays/drawers
    vp_drawer_intersections = [
        inter for vr in vp_rects for dr in drawer_only_rects if (inter := vr.intersect(dr)) is not None
    ]
    body_drawer_intersections = [
        inter for br in body_rects for dr in drawer_only_rects if (inter := br.intersect(dr)) is not None
    ]
    drawer_occluded_vp_px = union_area(vp_drawer_intersections)
    drawer_occluded_body_px = union_area(body_drawer_intersections)

    # Unoccluded body pieces (pairwise disjoint so overlapping tags never double-count)
    body_pieces: list[Rect] = []
    for br in disjoint_union(body_rects):
        body_pieces.extend(subtract_rects(br, overlay_rects))
    body_unoccluded_area = union_area(body_pieces)

    # Real bitmap measurement inside unoccluded body pieces
    if body_pieces:
        bg_rgb = modal_bg_in_pieces(img_obj, body_pieces)
        ink_info = measure_bitmap_ink(img_obj, body_pieces, bg_rgb)
    else:
        ink_info = {
            "region_area_px": 0,
            "bg_rgb": [0, 0, 0],
            "nonbg_pixels": 0,
            "ink_bbox_px": [0, 0, 0, 0],
            "ink_bbox_area_px": 0,
        }

    # Verify drawer occlusion pixels in actual bitmap when occluded
    occluded_bitmap_nonwhite_px = 0
    if body_drawer_intersections:
        occ_pieces = disjoint_union(body_drawer_intersections)
        occ_info = measure_bitmap_ink(img_obj, occ_pieces, (255, 255, 255), threshold=6)
        occluded_bitmap_nonwhite_px = occ_info["nonbg_pixels"]

    nesting = compute_nesting_layers(all_nodes, w, h)
    explain_area = union_area(explain_rects)

    return {
        "file": os.path.basename(png_path),
        "flavor": meta.get("flavor", ""),
        "state_name": meta.get("state_name", ""),
        "surface_type": surface_type,
        "surface_label": SURFACE_LABELS[surface_type],
        "resolution": f"{w}x{h}",
        "window_px": [w, h],
        "window_area_px": win_area,
        "scale": meta.get("scale", 1.0),
        "mode": "original_pdf" if is_pdf else ("reflowed_text" if text_rects else "guided_or_other"),
        "nesting_layers": nesting,
        "viewport_raw_px": vp_raw_area,
        "viewport_px": vp_unoccluded_area,
        "viewport_pct": round(vp_unoccluded_area / win_area * 100.0, 2) if win_area else 0.0,
        "body_bounds_px": body_unoccluded_area,
        "body_bounds_pct": round(body_unoccluded_area / win_area * 100.0, 2) if win_area else 0.0,
        "body_nonbg_ink_px": ink_info["nonbg_pixels"],
        "body_nonbg_ink_pct": round(ink_info["nonbg_pixels"] / win_area * 100.0, 2) if win_area else 0.0,
        "body_ink_bbox_px": ink_info["ink_bbox_px"],
        "body_ink_bbox_pct": round(ink_info["ink_bbox_area_px"] / win_area * 100.0, 2) if win_area else 0.0,
        "drawer_occluded_viewport_px": drawer_occluded_vp_px,
        "drawer_occluded_body_px": drawer_occluded_body_px,
        "drawer_occluded_bitmap_nonwhite_px": occluded_bitmap_nonwhite_px,
        "active_overlays": sorted(set(active_overlays)),
        "explanation_card_pct": round(explain_area / win_area * 100.0, 2) if (win_area and explain_area) else 0.0,
    }


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Unified reader area & nesting measurement")
    parser.add_argument("targets", nargs="+", help="PNG files or directories containing .png + .meta.json pairs")
    parser.add_argument("--json-out", help="Optional path to write JSON results")
    args = parser.parse_args(argv)

    pairs: list[tuple[str, str]] = []
    for target in args.targets:
        if os.path.isdir(target):
            for name in sorted(os.listdir(target)):
                if name.endswith(".png"):
                    png = os.path.join(target, name)
                    meta = os.path.splitext(png)[0] + ".meta.json"
                    if os.path.isfile(meta):
                        pairs.append((png, meta))
        elif target.endswith(".png"):
            meta = os.path.splitext(target)[0] + ".meta.json"
            if not os.path.isfile(meta):
                raise FileNotFoundError(f"Missing companion metadata JSON: {meta}")
            pairs.append((target, meta))
        else:
            raise ValueError(f"Unsupported target (expect .png or directory): {target}")

    results = [measure_pair(png, meta) for png, meta in pairs]
    output = json.dumps(results, indent=2, ensure_ascii=False)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            f.write(output + "\n")
    print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
