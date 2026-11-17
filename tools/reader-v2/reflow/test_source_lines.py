"""Source-line hypotheses must not become trusted links or discard alternatives."""
import copy
import unittest

from source_payload import build_payload, utf16
from test_object_review_plan import sample, result
from test_source_payload import block, glyph


def sample_source(value="Source: Example"):
    p, l = sample()
    start = len(utf16(p["text"] + " ")) // 2
    p["text"] += " " + value
    p["glyphs"].append(glyph(value, start, start + len(utf16(value)) // 2, [25, 100, 65, 110]))
    l["blocks"].append(block("s", "text", [20, 98, 70, 112], text=value, source_ref="src"))
    for b in l["blocks"]:
        b.update(source_scope="conversion", provenance_count=1)
    return p, l


def hypotheses(p, l):
    return [a for i in result(p, l)["items"] for a in i["alternatives"]
            if a["id"].startswith("source-lines-")]


class SourceLinesTests(unittest.TestCase):
    def test_source_is_added_as_separate_pending_alternative_with_exact_ranges(self):
        p, l = sample_source()
        original = copy.deepcopy((p, l))
        before = build_payload(p, l)
        out = result(p, l)
        proposed, = hypotheses(p, l)
        self.assertIsNotNone(proposed["selection"])
        self.assertEqual(proposed["semantic_status"], "unverified")
        self.assertIn("source_line_ownership_unverified", proposed["warnings"])
        part, = proposed["evidence"]["source_line_hypothesis"]["source_parts"]
        self.assertEqual(part["source_ranges"], [[13, 28]])
        self.assertEqual(part["ownership_status"], "unverified")
        old, = [a for i in out["items"] for a in i["alternatives"] if a["id"].startswith("labels-")]
        self.assertLess(old["selection"]["bottom"], proposed["selection"]["bottom"])
        self.assertEqual(build_payload(p, l), before)  # Source is never added to verified links.
        self.assertEqual((p, l), original)
        self.assertEqual(out["approved"], 0)
        for item in out["items"]:
            self.assertIsNone(item["replacement_range"])
            self.assertFalse(item["import_allowed"])
            self.assertFalse(item["hide_source_allowed"])

    def test_source_markers_in_english_chinese_and_wrapped_blocks(self):
        for value in ("Sources: A; B", "来源：示例", "资料来源: 示例", "Source: first\nsecond"):
            with self.subTest(value=value):
                self.assertIsNotNone(hypotheses(*sample_source(value))[0]["selection"])

    def test_body_with_marker_in_middle_or_empty_marker_is_not_suggested(self):
        for value in ("The Source: prose", "Source:", "Sources were considered", "SOURCE"):
            with self.subTest(value=value):
                self.assertEqual(hypotheses(*sample_source(value)), [])

    def test_model_text_must_match_whole_canonical_block(self):
        for value in ("Source: changed", "Source: Example missing line"):
            p, l = sample_source()
            l["blocks"][-1]["text"] = value
            self.assertEqual(hypotheses(p, l), [])

    def test_matching_text_elsewhere_does_not_relocate_a_source(self):
        p, l = sample_source()
        l["blocks"][-1]["bbox"] = [110, 70, 160, 90]  # BODY, not the source glyph.
        self.assertEqual(hypotheses(p, l), [])

    def test_source_outside_bounded_gap_or_alignment_is_not_suggested(self):
        for box in ([20, 120, 70, 134], [140, 98, 190, 112], [20, 50, 70, 64]):
            p, l = sample_source()
            l["blocks"][-1]["bbox"] = box
            p["glyphs"][-1]["bbox"] = [box[0]+1, box[1]+1, box[2]-1, box[3]-1]
            self.assertEqual(hypotheses(p, l), [])

    def test_two_possible_owners_do_not_pick_nearest_or_first(self):
        p, l = sample_source()
        l["blocks"].append(block("other", "table", [20, 70, 70, 95]))
        for order in (l["blocks"], list(reversed(l["blocks"]))):
            changed = dict(l, blocks=order)
            self.assertEqual(hypotheses(p, changed), [])

    def test_claimed_elsewhere_and_duplicate_source_references_are_not_reassigned(self):
        for change in ("claimed", "duplicate"):
            p, l = sample_source()
            if change == "claimed":
                l["blocks"][2]["footnote_refs"] = ["src"]
            else:
                l["blocks"].append(dict(l["blocks"][-1], id="same-ref"))
            self.assertEqual(hypotheses(p, l), [])

    def test_unknown_cross_scope_and_multi_page_sources_are_not_suggested(self):
        for patch in ({"source_scope": "other"}, {"source_scope": None},
                      {"provenance_count": 2}, {"source_ref": None}):
            p, l = sample_source()
            l["blocks"][-1].update(patch)
            self.assertEqual(hypotheses(p, l), [])

    def test_unresolved_caption_or_notes_do_not_get_source_hypotheses(self):
        for patch in ({"caption_refs": ["missing"]}, {"footnote_refs": ["missing"]},
                      {"caption_refs": []}, {"provenance_count": 2}):
            p, l = sample_source()
            l["blocks"][0].update(patch)
            self.assertEqual(hypotheses(p, l), [])

    def test_intervening_body_and_unlabelled_glyphs_still_block_the_extra_crop(self):
        for labelled in (True, False):
            p, l = sample_source()
            p["glyphs"][2]["bbox"] = [25, 93, 55, 96]
            if labelled:
                l["blocks"][2]["bbox"] = [20, 92, 60, 97]
            else:
                l["blocks"].pop(2)
            proposed, = hypotheses(p, l)
            self.assertIsNone(proposed["selection"])
            self.assertIn("unowned_glyph_in_expansion", proposed["blockers"])
            if labelled:
                self.assertIn("possible_body_text", proposed["blockers"])

    def test_partial_glyph_and_extra_glyph_in_source_block_are_rejected(self):
        for change in ("partial", "extra"):
            p, l = sample_source()
            if change == "partial":
                l["blocks"][-1]["bbox"][0] = 26
            else:
                p["glyphs"][2]["bbox"] = [25, 106, 50, 110]
            self.assertEqual(hypotheses(p, l), [])

    def test_precision_rounding_keeps_complete_glyph_envelope(self):
        p, l = sample_source()
        l["blocks"][-1]["bbox"] = [25.004, 100.003, 64.996, 109.997]
        proposed, = hypotheses(p, l)
        self.assertIsNotNone(proposed["selection"])
        part, = proposed["evidence"]["source_line_hypothesis"]["source_parts"]
        self.assertEqual(part["source_region"], [.125, .5, .325, .55])
        self.assertEqual(build_payload(p, l)["blocks"][-1]["source_region"],
                         [25.004/200, 100.003/200, 64.996/200, 109.997/200])

    def test_discontiguous_source_keeps_exact_ranges_around_body(self):
        p, l = sample_source("Source: A")
        p["text"] += " BODY B"
        p["glyphs"] += [glyph("BODY", 23, 27, [130, 130, 155, 140]),
                        glyph("B", 28, 29, [25, 113, 30, 118])]
        l["blocks"][-1].update(text="Source: A B", bbox=[20, 98, 70, 119])
        proposed, = hypotheses(p, l)
        part, = proposed["evidence"]["source_line_hypothesis"]["source_parts"]
        self.assertEqual(part["source_ranges"], [[13, 22], [28, 29]])
        self.assertIsNotNone(proposed["selection"])
        self.assertIsNone(proposed["replacement_range"])

    def test_unsafe_geometry_fallback_and_stale_inputs_do_not_make_source_crops(self):
        for change in ("old", "gap", "rotate", "fallback"):
            p, l = sample_source()
            if change == "old":
                p.pop("glyph_geometry_checked")
            elif change == "gap":
                p["unmapped_ranges"] = [[8, 9]]
            elif change == "rotate":
                p["rotation"] = l["rotation"] = 90
            else:
                l["fallback"] = "original_page"
            self.assertEqual(hypotheses(p, l), [])
        p, l = sample_source()
        l["doc_sha256"] = "stale"
        with self.assertRaises(ValueError):
            result(p, l)
