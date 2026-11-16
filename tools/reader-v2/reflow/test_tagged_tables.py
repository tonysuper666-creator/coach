"""Tagged association refuses ambiguity while preserving canonical source slices."""
import copy
import unittest

from tagged_tables import build_tagged_continuations, ranges_for


def fixture():
    native = {"schema": 1, "doc_sha256": "a" * 64, "structure_status": "parsed", "pages": [],
              "tables": [{"source_path": "/StructTreeRoot/K/0", "reason": None, "rows": []}]}
    probes = []
    for page in (1, 2):
        glyphs = [{"mcid": r*2+c, "text": value, "start": r*6+c*3, "end": r*6+c*3+2,
                   "bbox": [10+c*30, 10+r*30, 20+c*30, 20+r*30]}
                  for r in (0, 1) for c, value in enumerate(("AA", "BB"))]
        p = {"page": page, "safe": True, "width": 100, "height": 100,
             "text": "AA BB\nAA BB", "glyphs": glyphs}
        native["pages"].append(p)
        probes.append(dict(copy.deepcopy(p), doc_sha256=native["doc_sha256"]))
        for r in (0, 1):
            native["tables"][0]["rows"].append([
                {"header": False, "source_path": f"/table/{page}/{r}/{c}",
                 "refs": [{"page": page, "mcid": r*2+c}]} for c in (0, 1)])
    return native, probes


class TaggedTableTests(unittest.TestCase):
    def test_repeated_text_uses_distinct_tagged_slices_and_never_authorizes_crop(self):
        native, probes = fixture()
        before = copy.deepcopy((native, probes))
        table = build_tagged_continuations(native, probes)["tables"][0]
        self.assertEqual(table["status"], "verified_tagged_association")
        self.assertEqual([r["cells"][0]["source_ranges"] for r in table["fragments"][0]["rows"]],
                         [[[0, 2]], [[6, 8]]])
        self.assertEqual([len(f["row_breaks"]) for f in table["fragments"]], [1, 1])
        self.assertFalse(table["crop_ready"])
        self.assertFalse(table["hide_source_allowed"])
        self.assertIsNone(table["repeat_header"])
        self.assertTrue(all(f["source_region"] is None for f in table["fragments"]))
        self.assertEqual((native, probes), before)

    def test_distinct_native_tables_are_not_joined_by_matching_headers(self):
        native, probes = fixture()
        table = native["tables"][0]
        native["tables"] = [dict(table, rows=table["rows"][:2]),
                            dict(table, source_path="/StructTreeRoot/K/1", rows=table["rows"][2:])]
        out = build_tagged_continuations(native, probes)
        self.assertTrue(all(t["status"] == "unverified" and not t["fragments"] for t in out["tables"]))

    def test_missing_unsafe_or_nonconsecutive_pages_refused(self):
        for change in ("missing", "unsafe", "gap", "backward"):
            with self.subTest(change=change):
                native, probes = fixture()
                if change == "missing":
                    native["pages"] = native["pages"][:1]
                    probes = probes[:1]
                elif change == "unsafe":
                    native["pages"][1]["safe"] = False
                elif change == "gap":
                    native["pages"][1]["page"] = probes[1]["page"] = 3
                    for row in native["tables"][0]["rows"][2:]:
                        for c in row:
                            c["refs"][0]["page"] = 3
                else:
                    native["tables"][0]["rows"].reverse()
                t = build_tagged_continuations(native, probes)["tables"][0]
                self.assertEqual(t["status"], "unverified")
                self.assertFalse(t["fragments"])

    def test_missing_reused_mcid_or_cross_page_cell_refused(self):
        for change in ("missing", "reused", "cross_page", "duplicate", "negative"):
            with self.subTest(change=change):
                native, probes = fixture()
                cell = native["tables"][0]["rows"][0][0]
                if change == "missing":
                    cell["refs"][0]["mcid"] = 90
                elif change == "reused":
                    native["tables"][0]["rows"][0][1]["refs"][0]["mcid"] = 0
                elif change == "cross_page":
                    cell["refs"].append({"page": 2, "mcid": 0})
                elif change == "duplicate":
                    cell["refs"].append(copy.deepcopy(cell["refs"][0]))
                else:
                    cell["refs"][0]["mcid"] = -1
                self.assertEqual(build_tagged_continuations(native, probes)["tables"][0]["status"], "unverified")

    def test_stale_doc_text_frame_or_glyphs_reject_batch(self):
        for change in ("doc", "text", "frame", "glyph", "duplicate_page", "duplicate_path"):
            with self.subTest(change=change):
                native, probes = fixture()
                if change == "doc":
                    probes[0]["doc_sha256"] = "b" * 64
                elif change == "text":
                    probes[0]["text"] += "!"
                elif change == "frame":
                    probes[0]["width"] += 1
                elif change == "glyph":
                    probes[0]["glyphs"][0]["start"] += 1
                elif change == "duplicate_page":
                    native["pages"].append(native["pages"][0])
                else:
                    native["tables"].append(copy.deepcopy(native["tables"][0]))
                with self.assertRaises(ValueError):
                    build_tagged_continuations(native, probes)

    def test_row_overlap_foreign_text_and_oversize_grid_refused(self):
        for change in ("overlap", "foreign", "budget"):
            with self.subTest(change=change):
                native, probes = fixture()
                if change == "overlap":
                    for p in (native["pages"][0], probes[0]):
                        for g in p["glyphs"][2:]:
                            g["bbox"][1] = 19
                elif change == "foreign":
                    for p in (native["pages"][0], probes[0]):
                        p["glyphs"].append({"mcid": -1, "text": "AA", "start": 0, "end": 2,
                                            "bbox": [11, 11, 19, 19]})
                else:
                    native["tables"][0]["rows"] *= 126
                t = build_tagged_continuations(native, probes)["tables"][0]
                self.assertEqual(t["status"], "unverified")
                self.assertFalse(t["fragments"])

    def test_disjoint_ranges_do_not_include_intervening_body(self):
        gs = [{"text": "A", "start": 0, "end": 1, "bbox": [1, 1, 2, 2]},
              {"text": "B", "start": 6, "end": 7, "bbox": [1, 3, 2, 4]}]
        self.assertEqual(ranges_for(gs, "A BODYB"), [[0, 1], [6, 7]])

    def test_partial_surrogate_or_stale_glyph_refused(self):
        for text, start, end, value in (("😀", 0, 1, "?"), ("AB", 0, 1, "X"), ("AB", 0, 0, "")):
            with self.subTest(text=text, start=start, end=end):
                with self.assertRaises(ValueError):
                    ranges_for([{"text": value, "start": start, "end": end, "bbox": [1, 1, 2, 2]}], text)

    def test_untagged_never_uses_model_or_geometric_similarity(self):
        native, probes = fixture()
        native["structure_status"] = "untagged"
        self.assertEqual(build_tagged_continuations(native, probes)["tables"], [])


if __name__ == "__main__":
    unittest.main()
