"""Run: <venv>/python -m unittest discover -s tools/reader-v2/layout/tests -v
Fake-engine tests need pypdfium2 + reportlab; the Docling tests need docling and
cached model weights (skipped otherwise).  All inputs are self-made synthetic PDFs."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import layout_pipeline as lp  # noqa: E402
import make_synthetic  # noqa: E402

try:
    import docling  # noqa: F401
    HAVE_DOCLING = True
except ImportError:
    HAVE_DOCLING = False


def blk(label, bbox, order, text="t"):
    return {"label": label, "bbox": bbox, "text": text, "order": order}


class FakeEngine:
    name, version = "fake", "1"

    def __init__(self, fail_pages=()):
        self.fail_pages, self.calls = set(fail_pages), []

    def convert_range(self, pdf, first, last):
        self.calls.append((first, last))
        if self.fail_pages & set(range(first, last + 1)):
            raise RuntimeError("boom")
        return {p: {"size": (595.0, 842.0), "layout_score": 0.9,
                    "blocks": [blk("text", [72, 72, 500, 100], 0)]} for p in range(first, last + 1)}


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.syn = Path(cls.tmp.name) / "syn"
        make_synthetic.make(cls.syn)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self._c = tempfile.TemporaryDirectory()
        self.cache = Path(self._c.name)

    def tearDown(self):
        self._c.cleanup()


class CacheLogic(Base):
    def test_hit_then_version_change_then_failure_keeps_old(self):
        pdf = self.syn / "mixed.pdf"
        eng = FakeEngine()
        s1 = lp.ingest(pdf, self.cache, "d", eng)
        self.assertEqual((s1["pages_ok"], s1["cache_hit"], s1["error"]), (4, False, None))
        s2 = lp.ingest(pdf, self.cache, "d", eng)
        self.assertTrue(s2["cache_hit"])
        self.assertEqual(len(eng.calls), 1)          # hit: engine not called again
        self.assertEqual(lp.load_page(self.cache, "d", 3)["page_number"], 3)
        # new document version (different bytes) -> new version dir, latest moves
        pdf2 = self.syn / "single.pdf"
        s3 = lp.ingest(pdf2, self.cache, "d", eng)
        self.assertNotEqual(s3["version"], s1["version"])
        self.assertEqual(lp.load_page(self.cache, "d", 1)["doc_sha256"], lp.sha256_file(pdf2))
        # failing re-analysis of the first version with --force must not damage anything
        bad = FakeEngine(fail_pages={2})
        s4 = lp.ingest(pdf, self.cache, "d", bad, force=True, chunk=1)
        self.assertEqual((s4["pages_failed"], bool(s4["error"])), (1, True))
        latest = json.loads((self.cache / "d" / "latest.json").read_text())["version"]
        self.assertEqual(latest, s3["version"])      # pointer untouched by the failure
        self.assertTrue((self.cache / "d" / s1["version"] / "manifest.json").exists())

    def test_promotion_failure_keeps_old_version(self):
        import os
        from unittest import mock
        pdf = self.syn / "mixed.pdf"
        s1 = lp.ingest(pdf, self.cache, "d", FakeEngine())
        before = sorted(p.name for p in (self.cache / "d" / s1["version"] / "pages").glob("*.json"))
        real = os.replace

        def flaky(src, dst):
            if Path(src).name.startswith(".partial-"):
                raise OSError("disk says no")
            return real(src, dst)
        with mock.patch.object(lp.os, "replace", flaky):
            s2 = lp.ingest(pdf, self.cache, "d", FakeEngine(), force=True)
        self.assertIn("promote failed", s2["error"])
        after = sorted(p.name for p in (self.cache / "d" / s1["version"] / "pages").glob("*.json"))
        self.assertEqual(before, after)
        self.assertTrue((self.cache / "d" / s1["version"] / "manifest.json").exists())
        self.assertEqual(lp.load_page(self.cache, "d", 4)["page_number"], 4)
        self.assertFalse(list((self.cache / "d").glob(".old-*")))

    def test_page_json_has_every_field_the_kotlin_consumer_reads(self):
        # contract with server/.../reader/v2/LayoutCache.kt (runs on Windows, no JVM needed)
        lp.ingest(self.syn / "mixed.pdf", self.cache, "d", FakeEngine())
        p = lp.load_page(self.cache, "d", 1)
        for k in ("schema_version", "status", "columns", "reading_order_status", "rotation", "size", "blocks"):
            self.assertIn(k, p)
        for k in ("id", "kind", "bbox", "reading_order", "column", "status"):
            self.assertIn(k, p["blocks"][0])
        self.assertEqual(p["schema_version"], 1)
        self.assertRegex(json.dumps(p), r'"id": "p1b0"')
        self.assertTrue((self.cache / "d" / "latest.json").exists())
        self.assertEqual((self.cache / "d" / "latest.json").read_bytes()[:1], b"{")   # no BOM

    def test_document_index_page_is_unknown_with_fallback(self):
        def tb(b, o, label):
            return {"label": label, "bbox": b, "text": None, "order": o,
                    "table": {"rows": 1, "cols": 1, "cells": [{"r": 0, "c": 0, "rs": 1, "cs": 1, "text": "x"}]}}
        meta = {"rotation": 0, "mediabox": [0, 0, 595, 842], "cropbox": [0, 0, 595, 842]}
        raw = {"size": (595, 842), "layout_score": None,
               "blocks": [tb([60, 100, 280, 700], 0, "document_index"), tb([320, 100, 540, 700], 1, "document_index")]}
        pj = lp.build_page_json(1, raw, meta, "s")
        self.assertEqual((pj["reading_order_status"], pj["fallback"]), ("unknown", "original_page"))
        raw = {"size": (595, 842), "layout_score": None,
               "blocks": [tb([60, 100, 280, 400], 0, "table"), tb([320, 100, 540, 400], 1, "table"),
                          blk("text", [60, 500, 540, 540], 2)]}
        pj = lp.build_page_json(1, raw, meta, "s")
        self.assertEqual(pj["fallback"], "original_page")

    def test_normal_text_with_one_table_stays_trusted(self):
        meta = {"rotation": 0, "mediabox": [0, 0, 595, 842], "cropbox": [0, 0, 595, 842]}
        raw = {"size": (595, 842), "layout_score": None, "blocks": [
            blk("text", [72, 72, 520, 120], 0),
            {"label": "table", "bbox": [72, 140, 520, 260], "text": None, "order": 1,
             "table": {"rows": 1, "cols": 1, "cells": [{"r": 0, "c": 0, "rs": 1, "cs": 1, "text": "x"}]}},
            blk("text", [72, 280, 520, 330], 2)]}
        pj = lp.build_page_json(1, raw, meta, "s")
        self.assertEqual((pj["reading_order_status"], pj.get("fallback")), ("single_column", None))

    def test_table_grid_bounds_and_unique_cover(self):
        def g(rows, cols, cells):
            return {"rows": rows, "cols": cols,
                    "cells": [{"r": r, "c": c, "rs": rs, "cs": cs, "text": ""} for r, c, rs, cs in cells]}
        self.assertTrue(lp.table_grid_valid(g(2, 2, [(0, 0, 1, 2), (1, 0, 1, 1), (1, 1, 1, 1)])))
        self.assertFalse(lp.table_grid_valid(g(1, 2, [(0, 0, 1, 1), (0, 0, 1, 1)])))
        self.assertFalse(lp.table_grid_valid(g(1, 2, [(0, 0, 1, 1), (0, 1, 1, 3)])))
        self.assertFalse(lp.table_grid_valid(g(1, 2, [(0, 0, 1, 1), (0, 5, 1, 1)])))
        self.assertFalse(lp.table_grid_valid(g(2, 2, [(0, 0, 1, 1)])))

    def test_pipeline_version_change_invalidates_cache(self):
        from unittest import mock
        pdf = self.syn / "mixed.pdf"
        with mock.patch.object(lp, "PIPELINE_VERSION", "1"):
            lp.ingest(pdf, self.cache, "d", FakeEngine())
        eng = FakeEngine()
        s = lp.ingest(pdf, self.cache, "d", eng)
        self.assertFalse(s["cache_hit"])
        self.assertEqual(len(eng.calls), 1)
        self.assertIn("-p3-", s["version"])

    def test_resume_skips_done_pages(self):
        pdf = self.syn / "mixed.pdf"
        bad = FakeEngine(fail_pages={3})
        s1 = lp.ingest(pdf, self.cache, "d", bad, chunk=1)
        self.assertEqual((s1["pages_ok"], s1["pages_failed"]), (3, 1))
        self.assertIsNone(lp.load_page(self.cache, "d", 1))   # nothing promoted yet
        good = FakeEngine()
        s2 = lp.ingest(pdf, self.cache, "d", good, chunk=1)
        self.assertEqual((s2["pages_resumed"], s2["pages_failed"], good.calls), (3, 0, [(3, 3)]))
        self.assertIsNotNone(lp.load_page(self.cache, "d", 4))

    def test_missing_page_is_marked_not_guessed(self):
        class Drops(FakeEngine):
            def convert_range(self, pdf, a, b):
                r = super().convert_range(pdf, a, b)
                r.pop(2, None)
                return r
        lp.ingest(self.syn / "mixed.pdf", self.cache, "d", Drops())
        p = lp.load_page(self.cache, "d", 2)
        self.assertEqual((p["status"], p["fallback"], p["blocks"]), ("missing", "original_page", []))


class Heuristics(unittest.TestCase):
    def test_two_columns_and_order_conflict(self):
        bl = [{"kind": "text", "bbox": [60, 100 + 100 * i, 270, 150 + 100 * i], "reading_order": 0, "id": f"a{i}"} for i in range(3)]
        bl += [{"kind": "text", "bbox": [320, 100 + 100 * i, 530, 150 + 100 * i], "reading_order": 0, "id": f"b{i}"} for i in range(3)]
        for i, b in enumerate(bl):
            b["reading_order"] = i
        self.assertEqual(lp.assign_columns(bl), 2)
        self.assertEqual(lp.check_reading_order(bl, 2)[0], "ok")
        bl[1]["reading_order"], bl[3]["reading_order"] = 3, 1     # row-wise interleave
        st, sug = lp.check_reading_order(bl, 2)
        self.assertEqual(st, "conflict")
        self.assertEqual(sug[:3], ["a0", "a1", "a2"])

    def test_reversed_order_inside_a_column_is_conflict(self):
        bl = [{"kind": "text", "bbox": [60, 100 + 100 * i, 270, 150 + 100 * i], "reading_order": i, "id": f"a{i}"} for i in range(3)]
        bl += [{"kind": "text", "bbox": [320, 100 + 100 * i, 530, 150 + 100 * i], "reading_order": 3 + i, "id": f"b{i}"} for i in range(3)]
        self.assertEqual(lp.assign_columns(bl), 2)
        bl[0]["reading_order"], bl[2]["reading_order"] = 2, 0     # column 1 read bottom-to-top
        self.assertEqual(lp.check_reading_order(bl, 2)[0], "conflict")

    def test_empty_table_has_fallback(self):
        raw = {"size": (595, 842), "layout_score": None, "blocks": [
            {"label": "table", "bbox": [1, 1, 5, 5], "text": None, "order": 0, "table": {"rows": 0, "cols": 0, "cells": []}}]}
        meta = {"rotation": 0, "mediabox": [0, 0, 595, 842], "cropbox": [0, 0, 595, 842]}
        b = lp.build_page_json(1, raw, meta, "s")["blocks"][0]
        self.assertEqual((b["status"], b["fallback"]), ("unknown", "original_page_region"))

    def test_single_column_bottom_to_top_is_conflict(self):
        bl = [{"kind": "text", "bbox": [72, 700 - 80 * i, 520, 740 - 80 * i], "reading_order": i, "id": f"a{i}"} for i in range(5)]
        self.assertEqual(lp.assign_columns(bl, 842), 1)
        self.assertEqual(lp.check_reading_order(bl, 1)[0], "conflict")

    def test_side_by_side_narrow_blocks_without_columns_are_unknown(self):
        bl = [{"kind": "text", "bbox": [72, 100, 520, 150], "reading_order": 0, "id": "w"},
              {"kind": "caption", "bbox": [72, 200, 250, 240], "reading_order": 1, "id": "l"},
              {"kind": "caption", "bbox": [330, 205, 520, 245], "reading_order": 2, "id": "r"}]
        self.assertEqual(lp.assign_columns(bl, 842), 1)
        self.assertEqual(lp.check_reading_order(bl, 1)[0], "unknown")

    def test_short_narrow_captions_are_not_columns(self):
        bl = [{"kind": "caption", "bbox": [60, 100 + 30 * i, 250, 120 + 30 * i], "reading_order": i, "id": f"l{i}"} for i in range(2)]
        bl += [{"kind": "caption", "bbox": [330, 100 + 30 * i, 520, 120 + 30 * i], "reading_order": 2 + i, "id": f"r{i}"} for i in range(2)]
        self.assertEqual(lp.assign_columns(bl, 842), 1)

    def test_partial_table_grid_is_unknown(self):
        raw = {"size": (595, 842), "layout_score": None, "blocks": [
            {"label": "table", "bbox": [1, 1, 5, 5], "text": None, "order": 0,
             "table": {"rows": 2, "cols": 2, "cells": [{"r": 0, "c": 0, "rs": 1, "cs": 1, "text": "x"}]}},
            {"label": "table", "bbox": [1, 9, 5, 15], "text": None, "order": 1,
             "table": {"rows": 1, "cols": 2, "cells": [{"r": 0, "c": 0, "rs": 1, "cs": 2, "text": "m"}]}}]}
        meta = {"rotation": 0, "mediabox": [0, 0, 595, 842], "cropbox": [0, 0, 595, 842]}
        b = lp.build_page_json(1, raw, meta, "s")["blocks"]
        self.assertEqual([(x["status"], x.get("fallback")) for x in b],
                         [("unknown", "original_page_region"), ("ok", None)])

    def test_full_width_blocks_stay_single_column(self):
        bl = [{"kind": "text", "bbox": [72, 100 + 80 * i, 520, 150 + 80 * i], "reading_order": i, "id": f"a{i}"} for i in range(5)]
        self.assertEqual(lp.assign_columns(bl), 1)

    def test_table_not_in_reading_flow_and_formula_unverified(self):
        raw = {"size": (595, 842), "layout_score": None, "blocks": [
            {"label": "table", "bbox": [1, 1, 5, 5], "text": None, "order": 0,
             "table": {"rows": 1, "cols": 1, "cells": [{"r": 0, "c": 0, "rs": 1, "cs": 1, "text": "x"}]}},
            blk("formula", [1, 9, 5, 12], 1, text="x^2"), blk("formula", [1, 20, 5, 25], 2, text=None),
            blk("weird_label", [1, 30, 5, 35], 3)]}
        meta = {"rotation": 0, "mediabox": [0, 0, 595, 842], "cropbox": [0, 0, 595, 842]}
        pj = lp.build_page_json(1, raw, meta, "sha")
        b = pj["blocks"]
        self.assertFalse(b[0]["in_reading_flow"])
        self.assertEqual([x["status"] for x in b[1:]], ["unverified", "unrecognized", "unknown"])
        self.assertEqual(b[3]["fallback"], "original_page_region")

    def test_cropbox_shift_and_rotation_flag(self):
        raw = {"size": (555, 802), "layout_score": 0.5, "blocks": [blk("text", [52, 45, 300, 60], 0)]}
        meta = {"rotation": 0, "mediabox": [0, 0, 595, 842], "cropbox": [20, 20, 575, 822]}
        pj = lp.build_page_json(1, raw, meta, "s")
        self.assertEqual(pj["blocks"][0]["bbox"][:2], [72.0, 65.0])
        self.assertEqual(pj["size"], [595, 842])
        pj = lp.build_page_json(1, raw, dict(meta, rotation=90), "s")
        self.assertEqual((pj["reading_order_status"], pj["fallback"]), ("unknown", "original_page"))


@unittest.skipUnless(HAVE_DOCLING, "docling not installed")
class DoclingReal(Base):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        import os
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        try:
            cls.eng = lp.DoclingEngine()
        except Exception as e:  # weights missing offline
            raise unittest.SkipTest(f"docling engine unavailable: {e}")

    def page(self, name):
        s = lp.ingest(self.syn / f"{name}.pdf", self.cache, name, self.eng)
        self.assertIsNone(s["error"])
        return lp.load_page(self.cache, name, 1)

    def test_two_col(self):
        p = self.page("two_col")
        self.assertEqual((p["columns"], p["reading_order_status"]), (2, "ok"))

    def test_single(self):
        p = self.page("single")
        self.assertEqual(p["columns"], 1)
        self.assertEqual(p["blocks"][0]["kind"], "heading")

    def test_table_and_figure(self):
        kinds = {b["kind"] for b in self.page("figure_table")["blocks"]}
        self.assertTrue({"table", "figure"} <= kinds)

    def test_rotated_falls_back(self):
        p = self.page("rotated")
        self.assertEqual((p["rotation"], p["reading_order_status"]), (90, "unknown"))

    def test_scanned_is_ocr_processed_or_empty_never_crashes(self):
        self.assertIn(self.page("scanned")["status"], ("ok", "empty"))


if __name__ == "__main__":
    unittest.main()
