"""PDF evidence for borderless rows and explicit caption continuations."""
import copy
import unittest

import source_payload as sp
from test_source_payload import fixture, glyph, block, table_fixture


def text_table():
    p, l = table_fixture()
    p["horizontal_rules"] = []
    values = ["H😀", "H", "same", "same", "last", "row"]
    p["text"] = " ".join(values)
    offset = 0
    for cell, value in zip(l["blocks"][0]["table"]["cells"], values):
        cell["text"] = value
        bb = cell["bbox"]
        cell["bbox"] = [bb[0] + 2, bb[1] + 2, bb[2] - 2, bb[3] - 2]
        length = len(sp.utf16(value)) // 2
        p["glyphs"].append(glyph(value, offset, offset + length,
                                 [bb[0] + 4, bb[1] + 5, bb[2] - 4, bb[3] - 5]))
        offset += length + 1
    return p, l


class TableEvidenceTests(unittest.TestCase):
    def result(self, p, l):
        return sp.build_payload(p, l)["blocks"][0]["table"]

    def test_borderless_rows_need_every_cell_and_every_glyph(self):
        p, l = text_table()
        t = self.result(p, l)
        self.assertEqual(t["cell_text_status"], "exact_glyph_slices_whitespace_ignored")
        self.assertEqual([r["before_row"] for r in t["row_breaks"]], [1, 2])
        self.assertTrue(all(r["evidence"] == "exact_cell_text_and_pdf_glyph_gap" for r in t["row_breaks"]))
        self.assertEqual(t["repeat_header_region"], [0.05, 0.05, 0.45, 0.15])
        self.assertEqual(p["text"], "H😀 H same same last row")

    def test_wrong_cell_text_missing_glyph_duplicate_or_outside_glyph_rejects_gaps(self):
        for change in ("wrong_text", "missing", "duplicate", "outside", "partial", "bad_offset", "surrogate"):
            with self.subTest(change=change):
                p, l = text_table()
                if change == "wrong_text":
                    l["blocks"][0]["table"]["cells"][2]["text"] = "sarne"
                elif change == "missing":
                    p["glyphs"].pop()
                elif change == "duplicate":
                    p["glyphs"].append(copy.deepcopy(p["glyphs"][0]))
                elif change == "outside":
                    p["glyphs"].append(glyph("body", 0, 1, [47, 25, 49, 26]))
                elif change == "partial":
                    p["glyphs"][0]["bbox"][0] = 11
                elif change == "bad_offset":
                    p["glyphs"][0]["end"] = None
                elif change == "surrogate":
                    p["glyphs"][0]["end"] = 2
                t = self.result(p, l)
                self.assertEqual(t["row_breaks"], [])
                self.assertIsNone(t["repeat_header_region"])
                self.assertEqual(t["cell_text_status"], "unverified")

    def test_one_repeated_word_cannot_supply_two_cells(self):
        p, l = text_table()
        p["glyphs"][3]["start"] = p["glyphs"][2]["start"]
        p["glyphs"][3]["end"] = p["glyphs"][2]["end"]
        self.assertEqual(self.result(p, l)["row_breaks"], [])

    def test_cell_edge_segments_join_only_when_collinear_and_touching(self):
        p, l = table_fixture()
        p["horizontal_rules"] = [[10, 30, 40, 30], [40, 30, 70, 30], [70, 30, 90, 30]]
        self.assertEqual([r["before_row"] for r in self.result(p, l)["row_breaks"]], [1])
        for rules in ([[10, 30, 49, 30], [50, 30, 90, 30]],
                      [[10, 30, 50, 30], [50, 30.01, 90, 30.01]],
                      [[10, 29.98, 90, 29.98], [10, 30.02, 90, 30.02]]):
            p["horizontal_rules"] = rules
            self.assertEqual(self.result(p, l)["row_breaks"], [])

    def test_header_spans_two_rows_and_empty_body_row_has_no_gap_evidence(self):
        p, l = text_table()
        cells = l["blocks"][0]["table"]["cells"]
        for c in cells[:4]:
            c["column_header"] = True
        self.assertEqual(self.result(p, l)["repeat_header_region"], [0.05, 0.05, 0.45, 0.25])
        for c in cells[2:4]:
            c["text"] = ""
        p["glyphs"] = p["glyphs"][:2] + p["glyphs"][4:]
        self.assertEqual(self.result(p, l)["row_breaks"], [])

    def test_gaps_never_cut_rowspans_or_unverified_geometry(self):
        p, l = text_table()
        for flag in ("clip_unverified", "rotated_glyphs", "graphics_unsupported"):
            self.assertEqual(self.result(dict(p, **{flag: True}), l)["row_breaks"], [])
        cells = l["blocks"][0]["table"]["cells"]
        cells[2]["rs"] = 2
        cells[2]["bbox"][3] = cells[4]["bbox"][3]
        cells[2]["text"] += cells[4]["text"]
        cells.remove(cells[4])
        self.assertNotIn(2, [r["before_row"] for r in self.result(p, l)["row_breaks"]])


class CaptionEvidenceTests(unittest.TestCase):
    def test_explicit_continuation_keeps_body_between_parts_outside_source_ranges(self):
        p, l = fixture("cap BODY tail")
        p["glyphs"] = [glyph("cap", 0, 3, [10, 60, 30, 70]),
                       glyph("BODY", 4, 8, [110, 60, 140, 70]),
                       glyph("tail", 9, 13, [10, 80, 30, 90])]
        l["blocks"] = [block("f", "figure", [5, 5, 50, 50], caption_refs=["c1", "c2"]),
                       block("c1", "caption", [5, 55, 40, 75], source_ref="c1"),
                       block("body", "paragraph", [100, 55, 150, 75]),
                       block("c2", "caption", [5, 75, 40, 95], source_ref="c2")]
        out = sp.build_payload(p, l)
        group = out["caption_groups"][0]
        self.assertEqual(group["placement_status"], "source_parts_verified")
        self.assertEqual([part["source_ranges"] for part in group["source_parts"]], [[[0, 3]], [[9, 13]]])
        self.assertIsNone(group["replacement_range"])
        self.assertEqual(out["text"], p["text"])
        l["fallback"] = "original_page"
        self.assertEqual(sp.build_payload(p, l)["caption_groups"][0]["placement_status"], "unverified")

    def test_duplicate_missing_or_ambiguous_caption_refs_never_supply_parts(self):
        for refs in (["c", "c"], ["c", "missing"]):
            p, l = fixture()
            l["blocks"] = [block("f", "figure", [5, 5, 50, 50], caption_refs=refs),
                           block("c", "caption", [5, 55, 50, 65], source_ref="c")]
            group = sp.build_payload(p, l)["caption_groups"][0]
            self.assertEqual(group["status"], "unresolved")
            self.assertEqual(group["source_parts"], [])
            self.assertFalse(group["keep_together"])


if __name__ == "__main__":
    unittest.main()
