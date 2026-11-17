"""Non-lossy alternatives and explicit-label suggestions never grant approval."""
import copy
import unittest

import object_review_plan as plan
from test_source_payload import fixture, glyph, block


def sample():
    p, l = fixture("FIG CAP BODY")
    p.update(regions=[], clip_unverified=False, graphics_unsupported=False,
             rotated_glyphs=0, unmapped_ranges=[], glyph_geometry_checked=True)
    p["glyphs"] = [glyph("FIG", 0, 3, [25, 25, 35, 35]),
                   glyph("CAP", 4, 7, [25, 75, 45, 85]),
                   glyph("BODY", 8, 12, [120, 75, 150, 85])]
    l["blocks"] = [block("f", "figure", [20, 20, 60, 60], caption_refs=["cap"]),
                   block("c", "caption", [20, 70, 70, 90], source_ref="cap"),
                   block("body", "text", [110, 70, 160, 90])]
    return p, l


def result(p, l):
    pixels = bytearray([255]) * 200 * 200
    for g in p["glyphs"]:
        x1, y1, x2, y2 = map(int, g["bbox"])
        for y in range(y1, y2):
            for x in range(x1, x2):
                pixels[y * 200 + x] = 0
    return plan.plan_page(p, l, pixels, 200, 200, 1)


class ObjectReviewPlanTests(unittest.TestCase):
    def test_explicit_caption_expands_and_original_incomplete_crop_is_blocked(self):
        p, l = sample()
        before = copy.deepcopy((p, l))
        out = result(p, l)
        original, expanded = out["items"]
        self.assertIn("explicit_caption_outside_crop", original["blockers"])
        self.assertIsNone(original["selection"])
        self.assertEqual(expanded["evidence"]["caption_part_ids"], ["c"])
        self.assertLessEqual(expanded["selection"]["top"], 20)
        self.assertGreaterEqual(expanded["selection"]["bottom"], 90)
        self.assertEqual(expanded["evidence"]["source_ranges"], [[0, 7]])
        self.assertIn("complete_object_labels_unverified", expanded["warnings"])
        self.assertEqual((p, l), before)
        self.assertEqual(out["approved"], 0)
        for i in out["items"]:
            self.assertFalse(i["import_allowed"])
            self.assertFalse(i["hide_source_allowed"])
            self.assertIsNone(i["replacement_range"])

    def test_body_between_object_and_caption_blocks_the_expansion(self):
        p, l = sample()
        p["glyphs"][2]["bbox"] = [25, 62, 50, 67]
        l["blocks"][2]["bbox"] = [20, 61, 60, 68]
        expanded = result(p, l)["items"][-1]
        self.assertIn("possible_body_text", expanded["blockers"])
        self.assertIn("unowned_glyph_in_expansion", expanded["blockers"])
        self.assertIsNone(expanded["selection"])

    def test_unlabelled_glyph_between_parts_is_not_claimed_by_proximity(self):
        p, l = sample()
        p["glyphs"][2]["bbox"] = [25, 62, 50, 67]
        l["blocks"].pop()
        expanded = result(p, l)["items"][-1]
        self.assertEqual(expanded["blockers"], ["unowned_glyph_in_expansion"])

    def test_missing_duplicate_or_shared_caption_is_never_expanded(self):
        for change in ("missing", "duplicate", "shared", "fallback"):
            p, l = sample()
            if change == "missing":
                l["blocks"][0]["caption_refs"] = ["absent"]
            elif change == "duplicate":
                l["blocks"][0]["caption_refs"] *= 2
            elif change == "shared":
                l["blocks"].append(block("f2", "figure", [100, 10, 160, 60], caption_refs=["cap"]))
            else:
                l["fallback"] = "original_page"
            with self.subTest(change=change):
                out = result(p, l)
                self.assertFalse(any(a["id"].startswith("labels-") for i in out["items"] for a in i["alternatives"]))
                self.assertIn("unresolved_explicit_caption", out["items"][0]["blockers"])

    def test_identical_final_rectangles_fold_without_losing_evidence_or_veto(self):
        p, l = sample()
        l["blocks"][0]["bbox"] = [20.2, 20.2, 60.2, 60.2]
        p["regions"] = [{"bbox": [20.3, 20.3, 60.3, 60.3]}]
        out = result(p, l)
        merged = out["items"][0]
        self.assertGreater(len(merged["aliases"]), 1)
        self.assertEqual(len(merged["aliases"]), len(merged["alternatives"]))
        self.assertIn("explicit_caption_outside_crop", merged["blockers"])
        self.assertIsNone(merged["selection"])  # A C-only alias cannot bypass B's veto.
        self.assertEqual(sum(len(i["aliases"]) for i in out["items"]), out["proposals"])

    def test_transitive_overlap_never_deletes_or_unions_distinct_alternatives(self):
        p, l = sample()
        l["blocks"] = []
        # Each rectangle has actual ink, including the end of the overlap chain.
        for g, x in zip(p["glyphs"], (25, 45, 70)):
            g["bbox"] = [x, 25, x + 3, 30]
        p["regions"] = [{"bbox": b} for b in ([20, 20, 40, 40], [30, 20, 65, 40], [55, 20, 80, 40])]
        out = result(p, l)
        self.assertEqual(len(out["items"]), 3)
        self.assertEqual(out["overlap_groups"], [[0, 1, 2]])
        self.assertEqual([i["evidence"]["proposal_box"] for i in out["items"]],
                         [r["bbox"] for r in p["regions"]])

    def test_unlinked_nearby_caption_is_warning_not_an_inferred_ownership(self):
        p, l = sample()
        l["blocks"][0]["caption_refs"] = []
        out = result(p, l)
        self.assertEqual(len(out["items"]), 1)
        self.assertEqual(out["items"][0]["warnings"],
                         ["complete_object_labels_unverified", "labels_not_established"])
        self.assertLess(out["items"][0]["selection"]["bottom"], 70)

    def test_possible_prose_stays_in_the_plan_but_has_no_selection(self):
        p, l = sample()
        l["blocks"] = [l["blocks"][2]]
        p["regions"] = [{"bbox": [110, 70, 160, 90]}]
        out = result(p, l)
        self.assertEqual(out["items"][0]["prose_block_ids"], ["body"])
        self.assertIn("possible_body_text", out["items"][0]["blockers"])
        self.assertIsNone(out["items"][0]["selection"])

    def test_stale_rotated_and_duplicate_ids_cannot_make_selections(self):
        p, l = sample()
        stale = copy.deepcopy(p)
        stale["doc_sha256"] = "changed"
        with self.assertRaises(ValueError):
            result(stale, l)
        p["rotation"] = l["rotation"] = 90
        self.assertTrue(all(i["selection"] is None for i in result(p, l)["items"]))
        p, l = sample()
        l["blocks"][1]["id"] = "f"
        with self.assertRaises(ValueError):
            result(p, l)


if __name__ == "__main__":
    unittest.main()
