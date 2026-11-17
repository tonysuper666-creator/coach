"""Boundary evidence must not become semantic completeness or hide permission."""
import copy
import unittest

import crop_candidates as cc
from test_source_payload import fixture, glyph, block


class CropCandidateTests(unittest.TestCase):
    def setUp(self):
        self.probe, self.layout = fixture("ABcdEF")
        self.probe.update(regions=[], clip_unverified=False, graphics_unsupported=False,
                          rotated_glyphs=0, unmapped_ranges=[], glyph_geometry_checked=True)
        self.probe["glyphs"] = [glyph("AB", 0, 2, [25, 25, 35, 35]),
                                glyph("cd", 2, 4, [140, 25, 150, 35]),
                                glyph("EF", 4, 6, [25, 45, 35, 55])]
        self.layout["blocks"] = [block("figure", "figure", [20, 20, 60, 60])]
        self.pixels = bytearray([255]) * 200 * 200
        self.paint([25, 25, 35, 35])
        self.paint([25, 45, 35, 55])

    def paint(self, box, value=0):
        for y in range(box[1], box[3]):
            for x in range(box[0], box[2]):
                self.pixels[y * 200 + x] = value

    def result(self):
        return cc.prepare(self.probe, self.layout, self.pixels, 200, 200, 1)["candidates"][0]

    def test_borderless_crop_keeps_discontiguous_ranges_without_body(self):
        row = self.result()
        self.assertEqual(row["status"], "boundary_verified")
        self.assertEqual(row["source_ranges"], [[0, 2], [4, 6]])
        self.assertEqual(row["mapping_status"], "discontiguous")
        self.assertEqual(row["semantic_status"], "unverified")
        self.assertIsNone(row["replacement_range"])
        self.assertFalse(row["hide_source_allowed"])
        self.assertFalse(row["import_allowed"])

    def test_continuous_paint_cannot_be_cut_to_fit_budget(self):
        self.paint([30, 28, 95, 32])
        self.assertEqual(self.result()["status"], "no_verified_boundary")

    def test_outward_search_includes_whole_crossing_glyph(self):
        self.probe["glyphs"].append(glyph("cd", 2, 4, [59, 25, 64, 35]))
        self.paint([59, 25, 64, 35])
        row = self.result()
        self.assertEqual(row["status"], "boundary_verified")
        self.assertGreaterEqual(row["pixel_box"][2], 66)
        self.assertEqual(row["source_ranges"], [[0, 6]])
        self.assertEqual(row["semantic_status"], "unverified")  # It may have included body.

    def test_white_margin_cannot_prove_a_detached_label_is_included(self):
        self.paint([90, 30, 100, 40])
        row = self.result()
        self.assertEqual(row["status"], "boundary_verified")
        self.assertLess(row["pixel_box"][2], 90)
        self.assertEqual(row["semantic_status"], "unverified")
        self.assertFalse(row["import_allowed"])

    def test_no_page_margin_or_empty_crop_is_rejected(self):
        self.layout["blocks"][0]["bbox"] = [0, 0, 60, 60]
        self.assertEqual(self.result()["status"], "no_page_margin")
        self.layout["blocks"][0]["bbox"] = [80, 80, 100, 100]
        self.assertEqual(self.result()["status"], "empty")

    def test_missing_or_unsafe_probe_facts_fail_closed(self):
        for key, value in [("clip_unverified", True), ("graphics_unsupported", True),
                           ("rotated_glyphs", 1), ("unmapped_ranges", [[0, 1]])]:
            with self.subTest(key=key):
                before = copy.deepcopy(self.probe)
                self.probe[key] = value
                self.assertEqual(self.result()["status"], "unsafe_geometry")
                self.probe = before
                del self.probe[key]
                self.assertEqual(self.result()["status"], "unsafe_geometry")
                self.probe = before

    def test_stale_frames_rasters_and_document_are_rejected(self):
        for field, value in [("doc_sha256", "stale"), ("cropbox", [1, 0, 200, 200]), ("page", 2)]:
            with self.subTest(field=field):
                p = dict(self.probe, **{field: value})
                with self.assertRaises(ValueError):
                    cc.prepare(p, self.layout, self.pixels, 200, 200, 1)
        with self.assertRaises(ValueError):
            cc.prepare(self.probe, self.layout, self.pixels, 199, 200, 1)

    def test_media_to_crop_and_pixel_rounding_preserve_frame(self):
        for row in (self.probe, self.layout):
            row.update(mediabox=[-50, -30, 250, 270], cropbox=[-30, -10, 170, 190])
        self.layout["blocks"][0]["bbox"] = [40.25, 100.25, 80.25, 140.25]
        row = self.result()
        self.assertEqual(row["pixel_box"], [18, 18, 63, 63])
        self.assertEqual(row["source_region"], [0.09, 0.09, 0.315, 0.315])

    def test_pairwise_unions_are_alternatives_and_budgets_are_bounded(self):
        self.probe["regions"] = [{"bbox": [50, 30, 65, 50]}]
        rows = cc.prepare(self.probe, self.layout, self.pixels, 200, 200, 1)["candidates"]
        self.assertEqual(len(rows), 3)
        self.assertTrue(any(r["proposers"] == ["B:0", "C:0"] for r in rows))
        self.assertTrue(all(r["semantic_status"] == "unverified" and not r["import_allowed"] for r in rows))
        self.probe["regions"] *= 513
        with self.assertRaises(ValueError):
            self.result()


if __name__ == "__main__":
    unittest.main()
