"""Explicit table/picture notes remain separate sources and pending human review."""
import copy
from pathlib import Path
from types import SimpleNamespace as NS
import unittest

import source_payload as sp
import layout_pipeline as lp
from test_object_review_plan import sample, result
from test_source_payload import block, glyph


def notes():
    p, l = sample()
    p["text"] += " NOTE SRC"
    p["glyphs"] += [glyph("NOTE", 13, 17, [25, 105, 50, 115]),
                    glyph("SRC", 18, 21, [25, 135, 45, 145])]
    l["blocks"][0]["footnote_refs"] = ["note", "source"]
    l["blocks"] += [block("n", "footnote", [20, 100, 60, 120], source_ref="note"),
                    block("s", "footnote", [20, 130, 60, 150], source_ref="source")]
    for b in l["blocks"]:
        b["source_scope"] = "conversion-one"
        b["provenance_count"] = 1
    return p, l


class ObjectNotesTests(unittest.TestCase):
    def test_expansion_includes_caption_and_both_notes_without_claiming_body(self):
        p, l = notes()
        before = copy.deepcopy((p, l))
        out = result(p, l)
        original, expanded = out["items"]
        self.assertIn("explicit_notes_outside_crop", original["blockers"])
        self.assertIsNone(original["selection"])
        self.assertEqual(expanded["evidence"]["caption_part_ids"], ["c"])
        self.assertEqual(expanded["evidence"]["footnote_part_ids"], ["n", "s"])
        self.assertGreaterEqual(expanded["selection"]["bottom"], 150)
        self.assertEqual(expanded["evidence"]["source_ranges"], [[0, 7], [13, 21]])
        self.assertEqual(expanded["prose_block_ids"], [])
        self.assertIn("complete_object_labels_unverified", expanded["warnings"])
        self.assertEqual((p, l), before)
        self.assertEqual(out["approved"], 0)
        for row in out["items"]:
            self.assertIsNone(row["replacement_range"])
            self.assertFalse(row["import_allowed"])
            self.assertFalse(row["hide_source_allowed"])

    def test_each_note_retains_its_exact_source_not_a_surrounding_interval(self):
        p, l = notes()
        group = sp.build_payload(p, l)["object_label_groups"][0]
        self.assertEqual(group["status"], "linked")
        self.assertEqual([(x["role"], x["source_ranges"]) for x in group["source_parts"]],
                         [("caption", [[4, 7]]), ("footnote", [[13, 17]]),
                          ("footnote", [[18, 21]])])
        self.assertIsNone(group["replacement_range"])
        self.assertFalse(group["import_allowed"])
        self.assertFalse(group["hide_source_allowed"])

    def test_missing_duplicate_shared_wrong_role_and_cross_scope_notes_refuse(self):
        for change in ("missing", "duplicate", "shared", "wrong_role", "cross_scope", "duplicate_target"):
            p, l = notes()
            if change == "missing":
                l["blocks"][0]["footnote_refs"].append("absent")
            elif change == "duplicate":
                l["blocks"][0]["footnote_refs"].append("note")
            elif change == "shared":
                l["blocks"].append(block("other", "figure", [110, 10, 160, 60],
                                         source_scope="conversion-one", footnote_refs=["note"]))
            elif change == "wrong_role":
                l["blocks"][-2]["kind"] = "text"
            elif change == "cross_scope":
                l["blocks"][-2]["source_scope"] = "conversion-two"
            else:
                l["blocks"].append(dict(l["blocks"][-2], id="duplicate"))
            with self.subTest(change=change):
                out = result(p, l)
                self.assertFalse(any(a["id"].startswith("labels-") for i in out["items"]
                                     for a in i["alternatives"]))
                self.assertIn("unresolved_explicit_notes", out["items"][0]["blockers"])

    def test_note_and_owner_multiple_page_provenance_refuse(self):
        for index in (0, -2):
            p, l = notes()
            l["blocks"][index]["provenance_count"] = 2
            self.assertEqual(sp.build_payload(p, l)["object_label_groups"][0]["status"], "unresolved")
            self.assertEqual(len(result(p, l)["items"]), 1)

    def test_notes_without_explicit_links_are_not_claimed_by_nearness(self):
        p, l = notes()
        l["blocks"][0]["footnote_refs"] = []
        expanded = result(p, l)["items"][-1]
        self.assertEqual(expanded["evidence"]["footnote_part_ids"], [])
        self.assertLess(expanded["selection"]["bottom"], 100)

    def test_body_between_linked_parts_still_vetoes(self):
        p, l = notes()
        p["glyphs"][2]["bbox"] = [25, 90, 50, 95]
        l["blocks"][2]["bbox"] = [20, 89, 60, 96]
        expanded = result(p, l)["items"][-1]
        self.assertIn("possible_body_text", expanded["blockers"])
        self.assertIn("unowned_glyph_in_expansion", expanded["blockers"])
        self.assertIsNone(expanded["selection"])

    def test_unowned_footnote_inside_expansion_is_still_prose(self):
        p, l = notes()
        l["blocks"][0]["footnote_refs"] = ["source"]
        expanded = result(p, l)["items"][-1]
        self.assertIn("n", expanded["prose_block_ids"])
        self.assertIn("unowned_glyph_in_expansion", expanded["blockers"])
        self.assertIsNone(expanded["selection"])

    def test_partial_note_glyph_never_makes_a_linked_expansion(self):
        p, l = notes()
        p["glyphs"][-2]["bbox"][0] = 10
        self.assertEqual(sp.build_payload(p, l)["object_label_groups"][0]["placement_status"], "unverified")
        self.assertEqual(len(result(p, l)["items"]), 1)

    def test_small_glyph_edge_can_expand_only_with_exact_canonical_note_text(self):
        p, l = notes()
        l["blocks"][-2]["text"] = "N O T E"
        l["blocks"][-2]["bbox"][0] = 26  # Glyph starts at 25; only a clipped edge.
        before = copy.deepcopy((p, l))
        payload = sp.build_payload(p, l)
        self.assertEqual(payload["blocks"][-2]["mapping_status"], "ambiguous")
        part = payload["object_label_groups"][0]["source_parts"][1]
        self.assertEqual(part["source_ranges"], [[13, 17]])
        self.assertEqual(part["source_region"][0], 25 / 200)
        expanded = result(p, l)["items"][-1]
        self.assertIsNotNone(expanded["selection"])
        self.assertEqual(expanded["evidence"]["adjusted_note_part_ids"], ["n"])
        self.assertEqual((p, l), before)

    def test_glyph_adjustment_refuses_changed_or_incomplete_note_text(self):
        for text in ("N0TE", "NOTE missing line", "", None):
            p, l = notes()
            l["blocks"][-2].update(text=text, bbox=[26, 100, 60, 120])
            with self.subTest(text=text):
                self.assertEqual(len(result(p, l)["items"]), 1)

    def test_glyph_adjustment_never_searches_for_a_repeated_note_elsewhere(self):
        p, l = notes()
        l["blocks"][-2].update(text="SRC", bbox=[26, 100, 60, 120])
        self.assertEqual(len(result(p, l)["items"]), 1)

    def test_glyph_adjustment_has_a_four_point_outward_limit(self):
        p, l = notes()
        l["blocks"][-2].update(text="NOTE", bbox=[30, 100, 60, 120])
        self.assertEqual(len(result(p, l)["items"]), 1)

    def test_glyph_adjustment_does_not_claim_a_foreign_glyph_in_its_envelope(self):
        p, l = notes()
        l["blocks"][-2].update(text="NOTE", bbox=[26, 100, 60, 120])
        p["glyphs"][2]["bbox"] = [25, 101, 25.5, 103]  # Outside old note, inside envelope.
        self.assertEqual(len(result(p, l)["items"]), 1)

    def test_glyph_adjustment_requires_current_safe_geometry(self):
        for change in ("gap", "old_probe", "rotated", "clip"):
            p, l = notes()
            l["blocks"][-2].update(text="NOTE", bbox=[26, 100, 60, 120])
            if change == "gap":
                p["unmapped_ranges"] = [[8, 9]]
            elif change == "old_probe":
                p.pop("glyph_geometry_checked")
            elif change == "rotated":
                p["rotation"] = l["rotation"] = 90
            else:
                p["clip_unverified"] = True
            with self.subTest(change=change):
                self.assertTrue(all(i["selection"] is None for i in result(p, l)["items"]))

    def test_fallback_rotation_and_geometry_gap_keep_notes_unselected(self):
        for change in ("fallback", "rotation", "gap", "old_probe"):
            p, l = notes()
            if change == "fallback":
                l["fallback"] = "original_page"
            elif change == "rotation":
                p["rotation"] = l["rotation"] = 90
            elif change == "gap":
                p["unmapped_ranges"] = [[8, 9]]
            else:
                p.pop("glyph_geometry_checked")
            with self.subTest(change=change):
                self.assertTrue(all(i["selection"] is None for i in result(p, l)["items"]))

    def test_stale_pdf_is_rejected_before_note_expansion(self):
        p, l = notes()
        p["doc_sha256"] = "stale"
        with self.assertRaises(ValueError):
            result(p, l)

    def test_docling_note_references_survive_conversion_and_page_serialization(self):
        bb = NS(l=20, t=20, r=60, b=60)
        item = NS(label=NS(value="picture"), prov=[NS(page_no=1, bbox=NS(
                  to_top_left_origin=lambda _: bb))], self_ref="#/pictures/0",
                  captions=[NS(cref="#/texts/0")], footnotes=[NS(cref="#/texts/1")])
        doc = NS(pages={1: NS(size=NS(width=200, height=200))},
                 iterate_items=lambda: [(item, 0)])
        engine = object.__new__(lp.DoclingEngine)
        engine.conv = NS(convert=lambda *a, **kw: NS(document=doc))
        raw = engine.convert_range(Path("synthetic.pdf"), 1, 1)[1]
        meta = {"rotation": 0, "mediabox": [0, 0, 200, 200], "cropbox": [0, 0, 200, 200]}
        page = lp.build_page_json(1, raw, meta, "synthetic")
        b = page["blocks"][0]
        self.assertEqual(b["footnote_refs"], ["#/texts/1"])
        self.assertEqual(b["caption_refs"], ["#/texts/0"])
        self.assertEqual(b["source_scope"], raw["blocks"][0]["source_scope"])


if __name__ == "__main__":
    unittest.main()
