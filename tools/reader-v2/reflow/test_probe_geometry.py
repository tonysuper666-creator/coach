"""A page-wide production gap must veto both ordinary and caption selections."""
import unittest

import crop_candidates as cc
from source_payload import build_payload
from test_object_review_plan import sample, result


class ProbeGeometryTests(unittest.TestCase):
    def test_legacy_false_or_non_boolean_evidence_does_not_certify_a_page(self):
        for value in (None, False, 1, "true"):
            with self.subTest(value=value):
                p, l = sample()
                if value is None:
                    del p["glyph_geometry_checked"]
                else:
                    p["glyph_geometry_checked"] = value
                self.assertFalse(cc.preview_geometry_safe(p, build_payload(p, l)))
                out = result(p, l)
                self.assertTrue(out["items"])
                self.assertTrue(all(i["selection"] is None for i in out["items"]))
                self.assertFalse(any(i["id"].startswith("labels-") for i in out["items"]))
                self.assertTrue(all(i["blockers"] == ["unsafe_geometry"] for i in out["items"]))

    def test_gap_outside_the_crop_vetoes_all_selections_without_changing_text(self):
        p, l = sample()
        p["unmapped_ranges"] = [[8, 12]]  # BODY is outside the object/caption.
        text = p["text"]
        out = result(p, l)
        self.assertEqual(p["text"], text)
        self.assertTrue(all(i["selection"] is None for i in out["items"]))
        self.assertTrue(all(i["blockers"] == ["unsafe_geometry"] for i in out["items"]))
        self.assertFalse(any(i["id"].startswith("labels-") for i in out["items"]))
        self.assertEqual(out["approved"], 0)
        self.assertFalse(out["hide_source_allowed"])
        self.assertFalse(out["import_allowed"])


if __name__ == "__main__":
    unittest.main()
