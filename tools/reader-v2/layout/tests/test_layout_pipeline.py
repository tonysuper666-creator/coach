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
