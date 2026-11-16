"""Regression cases for lost body text, unsafe table cuts and broken captions."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import source_payload as sp
import layout_pipeline as lp


def fixture(text="ABcdEF"):
    probe = {"doc_sha256": "synthetic", "page": 1, "offset_unit": "utf16", "rotation": 0,
             "cropbox": [0, 0, 200, 200], "mediabox": [0, 0, 200, 200],
             "width": 200, "height": 200, "text": text, "glyphs": [], "horizontal_rules": []}
    layout = {"doc_sha256": "synthetic", "page_number": 1, "rotation": 0,
              "cropbox": [0, 0, 200, 200], "mediabox": [0, 0, 200, 200],
              "geometry": "page_space", "blocks": []}
    return probe, layout


def glyph(text, start, end, box):
    return {"text": text, "start": start, "end": end, "bbox": box}


def block(identity, kind, box, **kwargs):
    return {"id": identity, "kind": kind, "bbox": box, **kwargs}


class MappingTests(unittest.TestCase):
    def test_interleaved_column_keeps_body_outside_replacement(self):
        p, l = fixture()
        p["glyphs"] = [glyph("AB", 0, 2, [10, 10, 30, 20]),
                       glyph("cd", 2, 4, [110, 10, 130, 20]),
                       glyph("EF", 4, 6, [10, 30, 30, 40])]
        l["blocks"] = [block("left", "figure", [5, 5, 40, 50])]
        out = sp.build_payload(p, l)
        b = out["blocks"][0]
        self.assertEqual(b["source_ranges"], [[0, 2], [4, 6]])
        self.assertEqual(b["mapping_status"], "discontiguous")
        self.assertIsNone(b["replacement_range"])
        self.assertFalse(b["hide_source_allowed"])
        self.assertEqual(out["text"], "ABcdEF")
        self.assertEqual(out["text_utf16_sha256"], hashlib.sha256(sp.utf16(p["text"])).hexdigest())

    def test_supplementary_character_offsets_are_utf16(self):
        p, l = fixture("x\U0001f600\n\ny")
        p["glyphs"] = [glyph("\U0001f600", 1, 3, [10, 10, 20, 20]),
                       glyph("y", 5, 6, [10, 30, 20, 40])]
        l["blocks"] = [block("box", "formula", [5, 5, 25, 45])]
        b = sp.build_payload(p, l)["blocks"][0]
        self.assertEqual(b["replacement_range"], [1, 6])
        self.assertEqual(b["anchor_offset"], 1)
        self.assertEqual(sp.utf16(p["text"])[2:12].decode("utf-16-le"), "\U0001f600\n\ny")

    def test_partial_glyph_or_missing_alignment_cannot_hide_source(self):
        for g in [glyph("AB", 0, 2, [0, 10, 20, 20]),
                  glyph("AB", None, None, [10, 10, 20, 20])]:
            p, l = fixture()
            p["glyphs"] = [g]
            l["blocks"] = [block("box", "formula", [5, 5, 25, 25])]
            b = sp.build_payload(p, l)["blocks"][0]
            self.assertEqual(b["mapping_status"], "ambiguous")
            self.assertFalse(b["hide_source_allowed"])

    def test_cropbox_shift_and_normalized_coordinates(self):
        p, l = fixture("A")
        for row in (p, l):
            row["mediabox"] = [-50, -30, 250, 270]
            row["cropbox"] = [-30, -10, 170, 190]
        p["glyphs"] = [glyph("A", 0, 1, [10, 10, 20, 20])]
        l["blocks"] = [block("box", "formula", [25, 85, 45, 105])]
        b = sp.build_payload(p, l)["blocks"][0]
        self.assertEqual(b["replacement_range"], [0, 1])
        self.assertEqual(b["source_region"], [0.025, 0.025, 0.125, 0.125])

    def test_rotation_and_untrusted_order_keep_original(self):
        p, l = fixture("A")
        p["glyphs"] = [glyph("A", 0, 1, [10, 10, 20, 20])]
        l["blocks"] = [block("box", "formula", [5, 5, 25, 25])]
        l["fallback"] = "original_page"
        self.assertFalse(sp.build_payload(p, l)["blocks"][0]["hide_source_allowed"])
        p["rotation"] = l["rotation"] = 90
        b = sp.build_payload(p, l)["blocks"][0]
        self.assertIsNone(b["source_region"])
        self.assertEqual(b["source_ranges"], [])

    def test_clipping_and_unsupported_paint_do_not_supply_crops(self):
        for flag in ("clip_unverified", "graphics_unsupported"):
            p, l = table_fixture()
            p[flag] = True
            out = sp.build_payload(p, l)["blocks"][0]
            self.assertIsNone(out["source_region"])
            self.assertTrue(all(c["source_region"] is None for c in out["table"]["cells"]))
            self.assertEqual(out["table"]["row_breaks"], [])

    def test_stale_document_and_page_frame_are_rejected(self):
        p, l = fixture()
        for field, value in [("doc_sha256", "other"), ("cropbox", [1, 0, 200, 200]), ("page", 2)]:
            stale = dict(p, **{field: value})
            with self.assertRaises(ValueError):
                sp.build_payload(stale, l)

    def test_no_caption_is_inferred_from_vertical_proximity(self):
        p, l = fixture()
        l["blocks"] = [block("f", "figure", [5, 5, 50, 50], source_ref="#/pictures/0"),
                       block("c", "caption", [5, 55, 50, 65], source_ref="#/texts/2")]
        self.assertEqual(sp.build_payload(p, l)["caption_groups"][0]["status"], "unresolved")
        l["blocks"][0]["caption_refs"] = ["#/texts/2"]
        group = sp.build_payload(p, l)["caption_groups"][0]
        self.assertEqual(group["caption_ids"], ["c"])
        self.assertTrue(group["keep_together"])
        l["blocks"].append(block("other", "figure", [100, 5, 150, 50], caption_refs=["#/texts/2"]))
        self.assertTrue(all(g["status"] == "unresolved" for g in sp.build_payload(p, l)["caption_groups"]))


def table_fixture():
    p, l = fixture()
    cells = [{"r": r, "c": c, "rs": 1, "cs": 1, "text": f"{r},{c}",
              "bbox": [10 + 40*c, 10 + 20*r, 50 + 40*c, 30 + 20*r],
              "column_header": r == 0} for r in range(3) for c in range(2)]
    b = block("t", "table", [10, 10, 90, 70], table={"rows": 3, "cols": 2,
              "geometry_status": "single_page", "cells": cells})
    l["blocks"] = [b]
    p["horizontal_rules"] = [[10, y, 90, y] for y in (10, 30, 50, 70)]
    return p, l


class TableTests(unittest.TestCase):
    def test_long_table_cuts_and_repeat_header_have_rule_evidence(self):
        p, l = table_fixture()
        t = sp.build_payload(p, l)["blocks"][0]["table"]
        self.assertEqual([r["before_row"] for r in t["row_breaks"]], [1, 2])
        self.assertEqual(t["repeat_header_region"], [0.05, 0.05, 0.45, 0.15])
        self.assertEqual(t["header_rows"], [0])
        self.assertEqual(t["row_bands"], [[0.05, 0.15], [0.15, 0.25], [0.25, 0.35]])
        self.assertEqual(t["cells"][0]["source_region"], [0.05, 0.05, 0.25, 0.15])
        self.assertNotIn("bbox", t["cells"][0])

    def test_missing_rule_crossing_glyph_or_outside_cell_prevents_cut(self):
        p, l = table_fixture()
        p["horizontal_rules"] = []
        self.assertEqual(sp.build_payload(p, l)["blocks"][0]["table"]["row_breaks"], [])
        p["horizontal_rules"] = [[10, 30, 90, 30]]
        p["glyphs"] = [glyph("A", 0, 1, [20, 25, 30, 35])]
        self.assertEqual(sp.build_payload(p, l)["blocks"][0]["table"]["row_breaks"], [])
        p, l = table_fixture()
        l["blocks"][0]["table"]["cells"][0]["bbox"][0] = 0
        result = sp.build_payload(p, l)["blocks"][0]["table"]
        self.assertEqual(result["row_breaks"], [])
        self.assertIsNone(result["repeat_header_region"])

    def test_rowspan_is_retained_and_not_split(self):
        p, l = table_fixture()
        t = l["blocks"][0]["table"]
        t["cells"] = [c for c in t["cells"] if not (c["c"] == 0 and c["r"] in (1, 2))]
        t["cells"].append({"r": 1, "c": 0, "rs": 2, "cs": 1, "text": "merged", "bbox": [10, 30, 50, 70]})
        out = sp.build_payload(p, l)["blocks"][0]["table"]
        self.assertEqual([r["before_row"] for r in out["row_breaks"]], [1])
        self.assertEqual(out["cells"][-1]["rs"], 2)

    def test_incomplete_or_multi_page_cell_grid_stays_unverified(self):
        for change in ("incomplete", "multi_page"):
            p, l = table_fixture()
            t = l["blocks"][0]["table"]
            if change == "incomplete":
                t["cells"].pop()
            else:
                t["geometry_status"] = "ambiguous_multi_page"
            out = sp.build_payload(p, l)["blocks"][0]["table"]
            self.assertEqual(out["row_breaks"], [])
            self.assertIsNone(out["repeat_header_region"])
            if change == "multi_page":
                self.assertTrue(all(c["source_region"] is None for c in out["cells"]))


class AdapterTests(unittest.TestCase):
    def test_pipeline_keeps_cell_geometry_and_refs_without_mutating_engine_result(self):
        p, l = table_fixture()
        b = l["blocks"][0]
        b.update(label="table", order=0, source_ref="#/tables/0", caption_refs=["#/texts/0"])
        raw = {"blocks": [b], "layout_score": None}
        before = copy.deepcopy(raw)
        meta = {"mediabox": [0, 0, 300, 300], "cropbox": [20, 30, 220, 230], "rotation": 0}
        out = lp.build_page_json(1, raw, meta, "synthetic")["blocks"][0]
        self.assertEqual(out["table"]["cells"][0]["bbox"], [30, 80, 70, 100])
        self.assertEqual(out["caption_refs"], ["#/texts/0"])
        self.assertEqual(out["source_ref"], "#/tables/0")
        self.assertEqual(raw, before)

    def test_docling_adapter_keeps_all_page_occurrences_and_marks_cell_ambiguity(self):
        class Box:
            l, t, r, b = 10, 20, 30, 40
            def to_top_left_origin(self, height):
                return self
        cell = SimpleNamespace(start_row_offset_idx=0, start_col_offset_idx=0, row_span=1,
                               col_span=1, text="x", bbox=Box(), column_header=True,
                               row_header=False, row_section=False)
        item = SimpleNamespace(label=SimpleNamespace(value="table"), self_ref="#/tables/0",
                               captions=[SimpleNamespace(cref="#/texts/0")],
                               prov=[SimpleNamespace(page_no=n, bbox=Box()) for n in (1, 2)],
                               data=SimpleNamespace(num_rows=1, num_cols=1, table_cells=[cell]))
        doc = SimpleNamespace(pages={n: SimpleNamespace(size=SimpleNamespace(width=200, height=200)) for n in (1, 2)},
                              iterate_items=lambda: [(item, 0)])
        engine = lp.DoclingEngine.__new__(lp.DoclingEngine)
        engine.conv = SimpleNamespace(convert=lambda *args, **kwargs: SimpleNamespace(document=doc))
        pages = engine.convert_range(Path("synthetic.pdf"), 1, 2)
        self.assertEqual(set(pages), {1, 2})
        for page in pages.values():
            b = page["blocks"][0]
            self.assertEqual(b["table"]["geometry_status"], "ambiguous_multi_page")
            self.assertTrue(b["table"]["cells"][0]["column_header"])

    def test_batch_validates_identity_before_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            p, l = fixture()
            l["doc_sha256"] = "stale"
            (root / "probe.jsonl").write_text(json.dumps(p))
            (root / "00001.json").write_text(json.dumps(l))
            args = SimpleNamespace(out=root / "out", probe=root / "probe.jsonl", layout=root)
            with self.assertRaises(ValueError):
                sp.run(args)
            self.assertFalse(args.out.exists())


if __name__ == "__main__":
    unittest.main()
