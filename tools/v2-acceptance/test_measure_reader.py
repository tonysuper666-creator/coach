#!/usr/bin/env python3
"""Unit tests for measure_reader.py covering 1.1.1 baseline, 2.0 pure/split/overlay, and error guards."""

import json
import os
import struct
import tempfile
import unittest
import zlib

import measure_reader as mr


def write_test_png(path: str, w: int, h: int, bg=(255, 255, 255), rects=None):
    """Write an uncompressed/zlib RGB PNG with optional filled rectangles."""
    rects = rects or []
    row_len = 1 + w * 3
    raw = bytearray(row_len * h)
    for y in range(h):
        base = y * row_len
        raw[base] = 0
        for x in range(w):
            col = bg
            for rx0, ry0, rx1, ry1, rcol in rects:
                if rx0 <= x < rx1 and ry0 <= y < ry1:
                    col = rcol
            off = base + 1 + x * 3
            raw[off] = col[0]
            raw[off + 1] = col[1]
            raw[off + 2] = col[2]

    def chunk(ctype: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + ctype + data + struct.pack(">I", zlib.crc32(ctype + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    idat = zlib.compress(bytes(raw))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat))
        f.write(chunk(b"IEND", b""))


class MeasureReaderTests(unittest.TestCase):
    def test_v111_orig_text_and_guided_match_audit_metrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            # 1.1.1 1920x1080 Original PDF: 1070x745 = 38.44%, 5 layers
            p_orig = os.path.join(tmp, "orig.png")
            m_orig = os.path.join(tmp, "orig.meta.json")
            write_test_png(
                p_orig, 1920, 1080, bg=(232, 226, 214),
                rects=[(463, 243, 1533, 988, (255, 255, 255)), (500, 300, 1480, 920, (25, 25, 25))],
            )
            with open(m_orig, "w", encoding="utf-8") as f:
                json.dump({
                    "flavor": "1.1.1",
                    "state_name": "pure_read_original",
                    "surface_type": "image_compose_scene_offscreen",
                    "scale": 1.0,
                    "semantics": [{
                        "tag": "windows-host", "bounds": [0, 0, 1920, 1080], "children": [
                            {"tag": "host-navigation", "bounds": [0, 56, 76, 285], "children": []},
                            {"tag": "windows-textbooks", "bounds": [76, 0, 1920, 1080], "children": [
                                {"tag": "v7-desk", "bounds": [76, 0, 1920, 1080], "children": [
                                    {"tag": "v7-paper", "bounds": [92, 60, 1904, 1068], "children": [
                                        {"tag": "textbook-reader", "bounds": [112, 80, 1884, 1048], "children": [
                                            {"tag": "reader-page", "bounds": [112, 243, 1884, 988], "children": [
                                                {"tag": "original-pdf-scroll", "bounds": [112, 243, 1884, 988], "children": [
                                                    {"tag": "original-pdf-page-32", "bounds": [463, 243, 1533, 988], "children": []}
                                                ]}
                                            ]}
                                        ]}
                                    ]}
                                ]}
                            ]}
                        ]
                    }],
                }, f)
            res_orig = mr.measure_pair(p_orig, m_orig)
            self.assertEqual(res_orig["surface_type"], "image_compose_scene_offscreen")
            self.assertEqual(res_orig["surface_label"], "ImageComposeScene 离屏")
            self.assertEqual(res_orig["nesting_layers"], 5)
            self.assertAlmostEqual(res_orig["body_bounds_pct"], 38.44, places=2)
            self.assertAlmostEqual(res_orig["viewport_pct"], 63.66, places=2)
            self.assertGreater(res_orig["body_nonbg_ink_px"], 0)
            self.assertEqual(res_orig["drawer_occluded_body_px"], 0)

            # 1.1.1 1920x1080 Text: 594x631 + 594x595 = 35.12%, 6 layers
            p_text = os.path.join(tmp, "text.png")
            m_text = os.path.join(tmp, "text.meta.json")
            write_test_png(p_text, 1920, 1080, bg=(255, 255, 255), rects=[(360, 310, 940, 900, (30, 30, 30))])
            with open(m_text, "w", encoding="utf-8") as f:
                json.dump({
                    "flavor": "1.1.1",
                    "state_name": "pure_read_text",
                    "surface_type": "image_compose_scene_offscreen",
                    "scale": 1.0,
                    "semantics": [{
                        "tag": "windows-host", "bounds": [0, 0, 1920, 1080], "children": [
                            {"tag": "host-navigation", "bounds": [0, 56, 76, 285], "children": []},
                            {"tag": "v7-desk", "bounds": [76, 0, 1920, 1080], "children": [
                                {"tag": "v7-paper", "bounds": [92, 60, 1904, 1068], "children": [
                                    {"tag": "textbook-reader", "bounds": [112, 80, 1884, 1048], "children": [
                                        {"tag": "reader-page", "bounds": [112, 243, 1884, 1000], "children": [
                                            {"tag": "book-page-turn", "bounds": [292, 243, 1704, 1000], "children": [
                                                {"tag": "book-sheet-0", "bounds": [292, 243, 998, 1000], "children": [
                                                    {"tag": "reader-text", "bounds": [356, 295, 950, 926], "children": []}
                                                ]},
                                                {"tag": "book-sheet-1", "bounds": [998, 243, 1704, 1000], "children": [
                                                    {"tag": "reader-text-right", "bounds": [1046, 295, 1640, 890], "children": []}
                                                ]},
                                            ]}
                                        ]}
                                    ]}
                                ]}
                            ]}
                        ]
                    }],
                }, f)
            res_text = mr.measure_pair(p_text, m_text)
            self.assertEqual(res_text["nesting_layers"], 6)
            self.assertAlmostEqual(res_text["body_bounds_pct"], 35.12, places=2)

            # 1.1.1 Guided: 0% inline book text, 8.62% explanation card
            p_g = os.path.join(tmp, "guided.png")
            m_g = os.path.join(tmp, "guided.meta.json")
            write_test_png(p_g, 1920, 1080, bg=(252, 250, 246))
            with open(m_g, "w", encoding="utf-8") as f:
                json.dump({
                    "flavor": "1.1.1",
                    "state_name": "guided",
                    "surface_type": "image_compose_scene_offscreen",
                    "scale": 1.0,
                    "semantics": [{
                        "tag": "windows-host", "bounds": [0, 0, 1920, 1080], "children": [
                            {"tag": "host-navigation", "bounds": [0, 56, 76, 285], "children": []},
                            {"tag": "v7-desk", "bounds": [76, 0, 1920, 1080], "children": [
                                {"tag": "v7-paper", "bounds": [475, 60, 1521, 1068], "children": [
                                    {"tag": "textbook-learning", "bounds": [475, 60, 1521, 1068], "children": [
                                        {"tag": "book-lesson-explanation", "bounds": [499, 268, 1497, 447], "children": []}
                                    ]}
                                ]}
                            ]}
                        ]
                    }],
                }, f)
            res_g = mr.measure_pair(p_g, m_g)
            self.assertEqual(res_g["body_bounds_pct"], 0.0)
            self.assertAlmostEqual(res_g["explanation_card_pct"], 8.62, places=2)
            self.assertEqual(res_g["nesting_layers"], 5)

    def test_v2_overlay_vs_docked_companion_occlusion(self):
        with tempfile.TemporaryDirectory() as tmp:
            # Overlay companion drawer occluding right page
            p_ov = os.path.join(tmp, "ov.png")
            m_ov = os.path.join(tmp, "ov.meta.json")
            write_test_png(
                p_ov, 1920, 1080, bg=(255, 255, 255),
                rects=[(1540, 0, 1920, 1080, (240, 238, 234)), (200, 100, 1500, 900, (30, 30, 30))],
            )
            with open(m_ov, "w", encoding="utf-8") as f:
                json.dump({
                    "flavor": "proto/v2-reader",
                    "state_name": "companion_initial",
                    "surface_type": "real_os_window",
                    "scale": 1.0,
                    "semantics": [{
                        "tag": "textbook-reader", "bounds": [0, 0, 1920, 1080], "children": [
                            {"tag": "reader-page", "bounds": [0, 0, 1920, 1080], "children": [
                                {"tag": "original-pdf-page-34", "bounds": [140, 4, 960, 1074], "children": []},
                                {"tag": "original-pdf-page-35", "bounds": [960, 4, 1780, 1074], "children": []},
                            ]},
                            {"tag": "immersive-companion-drawer", "bounds": [1540, 0, 1920, 1080], "children": []},
                        ]
                    }],
                }, f)
            res_ov = mr.measure_pair(p_ov, m_ov)
            self.assertEqual(res_ov["surface_label"], "真 OS 窗口")
            self.assertEqual(res_ov["nesting_layers"], 1)
            self.assertEqual(res_ov["drawer_occluded_body_px"], (1780 - 1540) * (1074 - 4))
            self.assertEqual(res_ov["drawer_occluded_viewport_px"], 380 * 1080)

            # Docked companion drawer: zero occlusion
            p_dk = os.path.join(tmp, "dk.png")
            m_dk = os.path.join(tmp, "dk.meta.json")
            write_test_png(p_dk, 1920, 1080, bg=(255, 255, 255))
            with open(m_dk, "w", encoding="utf-8") as f:
                json.dump({
                    "flavor": "proto/v2-reader",
                    "state_name": "wide_split_companion",
                    "surface_type": "image_compose_scene_offscreen",
                    "scale": 1.0,
                    "semantics": [{
                        "tag": "textbook-reader", "bounds": [0, 0, 1920, 1080], "children": [
                            {"tag": "reader-page", "bounds": [0, 0, 1540, 1080], "children": [
                                {"tag": "original-pdf-page-34", "bounds": [20, 40, 760, 1040], "children": []},
                                {"tag": "original-pdf-page-35", "bounds": [780, 40, 1520, 1040], "children": []},
                            ]},
                            {"tag": "immersive-companion-drawer", "bounds": [1540, 0, 1920, 1080], "children": []},
                        ]
                    }],
                }, f)
            res_dk = mr.measure_pair(p_dk, m_dk)
            self.assertEqual(res_dk["nesting_layers"], 1)
            self.assertEqual(res_dk["drawer_occluded_body_px"], 0)
            self.assertEqual(res_dk["drawer_occluded_viewport_px"], 0)
            self.assertGreater(res_dk["body_bounds_pct"], 65.0)


if __name__ == "__main__":
    unittest.main()
