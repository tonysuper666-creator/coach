"""Source identity, complete cell coverage and page-local repeated headers."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from cross_page_tables import build_continuations
import source_payload as sp
import layout_pipeline as lp
from test_source_payload import fixture, glyph


def long_table(repeated=False):
    values = [["HA", "HB"], ["A1", "B1"],
              ["HA", "HB"] if repeated else ["A2", "B2"], ["A3", "B3"]]
    cells = [{"r": r, "c": c, "rs": 1, "cs": 1, "text": value,
              "column_header": r == 0 or (repeated and r == 2),
              "bbox": [10 + c*40, 10 + (r % 2)*30, 50 + c*40, 35 + (r % 2)*30]}
             for r, row in enumerate(values) for c, value in enumerate(row)]
    probes, layouts = [], []
    for page in (1, 2):
        rows = values[(page-1)*2:page*2]
        text = "\n".join(" ".join(row) for row in rows) + "\nBODY"
        p, l = fixture(text)
        p["page"] = l["page_number"] = page
        cursor = 0
        for r, row in enumerate(rows):
            for c, value in enumerate(row):
                p["glyphs"].append(glyph(value, cursor, cursor+len(value),
                                         [15+c*40, 15+r*30, 35+c*40, 25+r*30]))
                cursor += len(value) + 1
        p["glyphs"].append(glyph("BODY", cursor, cursor+4, [10, 100, 40, 110]))
        l["blocks"] = [{"id": f"p{page}t", "kind": "table", "bbox": [10, 10, 90, 65],
                        "source_ref": "#/tables/0", "source_scope": "synthetic-conversion", "provenance_count": 2,
                        "table": {"rows": 4, "cols": 2, "cells": copy.deepcopy(cells),
                                  "geometry_status": "ambiguous_multi_page"}}]
        probes.append(p)
        layouts.append(l)
    return probes, layouts


class ContinuationTests(unittest.TestCase):
    def result(self, p, l):
        return build_continuations(p, l)["tables"][0]

    def test_exact_partition_header_reference_and_original_text(self):
        p, l = long_table()
        before = copy.deepcopy((p, l))
        out = self.result(p, l)
        self.assertEqual(out["status"], "verified_source_fragments")
        self.assertEqual([(f["page"], f["row_start"], f["row_end"]) for f in out["fragments"]],
                         [(1, 0, 2), (2, 2, 4)])
        self.assertEqual([f["header_mode"] for f in out["fragments"]], ["source", "repeat_first_fragment"])
        self.assertEqual(out["repeat_header"]["page"], 1)
        self.assertEqual(out["repeat_header"]["source_ranges"], [[0, 5]])
        self.assertTrue(out["repeat_header"]["visual_copy_only"])
        self.assertEqual(out["fragments"][1]["row_breaks"][0]["before_row"], 3)
        self.assertIsNone(out["replacement_range"])
        self.assertFalse(out["hide_source_allowed"])
        self.assertEqual((p, l), before)
        self.assertEqual([sp.build_payload(a, b)["text"] for a, b in zip(p, l)], [a["text"] for a in p])

    def test_existing_repeated_header_is_not_requested_twice(self):
        p, l = long_table(repeated=True)
        out = self.result(p, l)
        self.assertEqual(out["status"], "verified_source_fragments")
        self.assertEqual([f["header_mode"] for f in out["fragments"]], ["source", "source"])

    def test_missing_duplicate_gapped_pages_or_source_occurrences_refused(self):
        for change in ("missing", "duplicate", "gap", "count"):
            with self.subTest(change=change):
                p, l = long_table()
                if change == "missing":
                    p, l = p[:1], l[:1]
                elif change == "duplicate":
                    l[0]["blocks"].append(copy.deepcopy(l[0]["blocks"][0]))
                elif change == "gap":
                    p[1]["page"] = l[1]["page_number"] = 3
                else:
                    l[1]["blocks"][0]["provenance_count"] = 3
                self.assertEqual(self.result(p, l)["reason"], "incomplete_or_nonconsecutive_provenance")

    def test_nearby_tables_with_different_source_refs_are_never_joined(self):
        p, l = long_table()
        l[1]["blocks"][0]["source_ref"] = "#/tables/1"
        out = build_continuations(p, l)["tables"]
        self.assertEqual(len(out), 2)
        self.assertTrue(all(t["status"] == "unverified" and not t["fragments"] for t in out))

    def test_local_refs_from_different_conversion_graphs_are_never_joined(self):
        p, l = long_table()
        l[1]["blocks"][0]["source_scope"] = "a-separate-conversion"
        out = build_continuations(p, l)["tables"]
        self.assertEqual(len(out), 2)
        self.assertTrue(all(t["status"] == "unverified" for t in out))
        for layout in l:
            layout["blocks"][0].pop("source_scope")
        self.assertEqual(self.result(p, l)["reason"], "unscoped_source_reference")
        for layout in l:
            layout["blocks"][0]["provenance_count"] = 1
        self.assertEqual(build_continuations(p, l)["tables"], [])

    def test_wrong_text_extra_glyph_partial_or_reused_offsets_reject_all_fragments(self):
        for change in ("wrong", "extra", "partial", "reused", "unmapped", "surrogate"):
            with self.subTest(change=change):
                p, l = long_table()
                if change == "wrong":
                    p[1]["text"] = p[1]["text"].replace("A2", "XX")
                elif change == "extra":
                    p[1]["glyphs"].append(glyph("BODY", 12, 16, [10, 30, 40, 36]))
                elif change == "partial":
                    p[1]["glyphs"][0]["bbox"][0] = 5
                elif change == "reused":
                    p[1]["glyphs"][1]["start"] = 0
                elif change == "unmapped":
                    p[1]["glyphs"][0]["start"] = None
                else:
                    p[1]["text"] = "\U0001f600" + p[1]["text"][2:]
                    p[1]["glyphs"][0]["end"] = 1
                out = self.result(p, l)
                self.assertEqual(out["status"], "unverified")
                self.assertEqual(out["fragments"], [])

    def test_rotation_clipping_order_and_stale_frames_refused(self):
        for change in ("rotation", "clipping", "order", "frame", "unmapped"):
            with self.subTest(change=change):
                p, l = long_table()
                if change == "rotation":
                    p[1]["rotation"] = l[1]["rotation"] = 90
                elif change == "clipping":
                    p[1]["clip_unverified"] = True
                elif change == "order":
                    l[1]["fallback"] = "original_page"
                elif change == "unmapped":
                    p[1]["unmapped_ranges"] = [[0, 2]]
                else:
                    l[1]["cropbox"][0] = 1
                    with self.assertRaises(ValueError):
                        self.result(p, l)
                    continue
                self.assertEqual(self.result(p, l)["reason"], "unsafe_geometry_or_order")

    def test_verification_budget_refuses_without_partial_evidence(self):
        p, l = long_table()
        with patch("cross_page_tables.MAX_CELL_GLYPH_CHECKS", 1):
            out = self.result(p, l)
        self.assertEqual(out["reason"], "verification_budget")
        self.assertEqual(out["fragments"], [])

    def test_adapter_scopes_each_conversion_and_preserves_scope_in_cache(self):
        class Box:
            l, t, r, b = 10, 20, 30, 40
            def to_top_left_origin(self, height):
                return self
        item = SimpleNamespace(label=SimpleNamespace(value="picture"), self_ref="#/pictures/0",
                               prov=[SimpleNamespace(page_no=n, bbox=Box()) for n in (1, 2)])
        doc = SimpleNamespace(pages={n: SimpleNamespace(size=SimpleNamespace(width=200, height=200))
                                     for n in (1, 2)}, iterate_items=lambda: [(item, 0)])
        engine = lp.DoclingEngine.__new__(lp.DoclingEngine)
        engine.conv = SimpleNamespace(convert=lambda *a, **kw: SimpleNamespace(document=doc))
        a = engine.convert_range(Path("synthetic.pdf"), 1, 2)
        b = engine.convert_range(Path("synthetic.pdf"), 1, 2)
        scope = a[1]["blocks"][0]["source_scope"]
        self.assertEqual(scope, a[2]["blocks"][0]["source_scope"])
        self.assertNotEqual(scope, b[1]["blocks"][0]["source_scope"])
        meta = {"cropbox": [0, 0, 200, 200], "mediabox": [0, 0, 200, 200], "rotation": 0}
        cached = lp.build_page_json(1, a[1], meta, "synthetic")
        self.assertEqual(cached["blocks"][0]["source_scope"], scope)

    def test_missing_header_rowspan_different_grid_and_header_conflict_refused(self):
        for change in ("header", "span", "grid", "repeat"):
            with self.subTest(change=change):
                p, l = long_table(repeated=True)
                if change == "grid":
                    l[1]["blocks"][0]["table"]["cells"][0]["text"] = "different"
                else:
                    for layout in l:
                        cells = layout["blocks"][0]["table"]["cells"]
                        if change == "header":
                            for cell in cells:
                                cell["column_header"] = False
                        elif change == "span":
                            cells[0]["rs"] = 2
                            cells.remove(next(c for c in cells if c["r"] == 1 and c["c"] == 0))
                        else:
                            cells[4]["text"] = "HX"
                    if change == "repeat":
                        p[1]["text"] = p[1]["text"].replace("HA", "HX")
                out = self.result(p, l)
                self.assertEqual(out["status"], "unverified")
                self.assertEqual(out["fragments"], [])

    def test_equal_cropbox_shift_keeps_page_local_regions(self):
        p, l = long_table()
        l[1]["cropbox"] = p[1]["cropbox"] = [20, 30, 220, 230]
        l[1]["mediabox"] = p[1]["mediabox"] = [0, 0, 300, 300]
        b = l[1]["blocks"][0]
        for bb in [b["bbox"]] + [c["bbox"] for c in b["table"]["cells"]]:
            for i in range(4):
                bb[i] += 20 if i % 2 == 0 else 70
        out = self.result(p, l)
        self.assertEqual(out["status"], "verified_source_fragments")
        self.assertEqual(out["fragments"][0]["source_region"], out["fragments"][1]["source_region"])

    def test_batch_mixed_documents_rejected_before_any_files_written(self):
        p, l = long_table()
        p[1]["doc_sha256"] = l[1]["doc_sha256"] = "other"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "probe.jsonl").write_text("\n".join(json.dumps(x) for x in p))
            for layout in l:
                (root / f'{layout["page_number"]:05d}.json').write_text(json.dumps(layout))
            with self.assertRaises(ValueError):
                sp.run(SimpleNamespace(out=root / "out", probe=root / "probe.jsonl", layout=root))
            self.assertFalse((root / "out").exists())


if __name__ == "__main__":
    unittest.main()
