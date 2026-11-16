#!/usr/bin/env python3
"""Execute PDFBox source-offset regressions on a generated four-page PDF.

Requires Java 17 and the PDFBox classpath. No Docling/model dependency: known
layout geometry isolates mapping from model accuracy. See README for B checks.
"""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unicodedata

from source_payload import build_payload, utf16

HERE = Path(__file__).resolve().parent


def verify(classpath):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(root),
                        str(HERE / "PdfReflowProbe.java"), str(HERE / "SyntheticPdfFixture.java")], check=True)
        cp = str(root) + os.pathsep + classpath
        pdf = root / "fixture.pdf"
        baseline = subprocess.check_output(["java", "-cp", cp, "SyntheticPdfFixture", str(pdf)], text=True)
        rows = [json.loads(line) for line in subprocess.check_output(
            ["java", "-cp", cp, "PdfReflowProbe", str(pdf), "1,2,3,4"], text=True).splitlines()]
        expected = [base64.b64decode(s).decode("utf-8") for s in baseline.splitlines()]
        assert [r["text"] for r in rows] == expected, "canonical source changed"
        count = 0
        for row in rows:
            text = utf16(row["text"])
            assert row["unmapped_ranges"] == []
            for g in row["glyphs"]:
                assert g["start"] is not None and g["end"] is not None
                actual = text[g["start"]*2:g["end"]*2].decode("utf-16-le")
                normal = "".join(unicodedata.normalize("NFKC", c).strip() if
                                 0xFB00 <= ord(c) <= 0xFDFF or 0xFE70 <= ord(c) <= 0xFEFF else c
                                 for c in g["text"])
                assert actual == normal, "glyph no longer locates the exact source substring"
                count += 1
        def layout(row, blocks):
            return {"doc_sha256": row["doc_sha256"], "page_number": row["page"],
                    "rotation": row["rotation"], "mediabox": row["mediabox"],
                    "cropbox": row["cropbox"], "geometry": "page_space", "blocks": blocks}
        cells = [{"r": r, "c": c, "rs": 1, "cs": 1, "text": f"cell {r},{c}",
                  "column_header": r == 0,
                  "bbox": [30 + c*100, 80 + r*30, 130 + c*100, 110 + r*30]}
                 for r in range(3) for c in range(2)]
        blocks = [{"id": "table", "kind": "table", "bbox": [30, 80, 230, 170],
                   "source_ref": "#/tables/0", "caption_refs": ["#/texts/0"],
                   "table": {"rows": 3, "cols": 2, "cells": cells, "geometry_status": "single_page"}},
                  {"id": "caption", "kind": "caption", "source_ref": "#/texts/0", "bbox": [25, 180, 200, 200]},
                  {"id": "callout", "kind": "unknown", "bbox": [30, 240, 230, 320]},
                  {"id": "formula", "kind": "formula", "bbox": [35, 288, 150, 305]}]
        payload = build_payload(rows[0], layout(rows[0], blocks))
        assert all(b["source_ranges"] for b in payload["blocks"]), "special block lost text mapping"
        table = payload["blocks"][0]["table"]
        assert [r["before_row"] for r in table["row_breaks"]] == [1, 2]
        assert table["repeat_header_region"] == [0.075, 0.2, 0.575, 0.275]
        assert payload["caption_groups"][0]["caption_ids"] == ["caption"]
        # The header separator consists of twenty independently painted short
        # edges. Preserve and join them; do not require one full-width operator.
        assert sum(r[1] == 110 and r[2] - r[0] == 10 for r in rows[0]["horizontal_rules"]) == 20
        borderless = copy.deepcopy(blocks[:1])
        values = ["Header A", "Header B", "A1", "B1", "A2", "B2"]
        for cell, value in zip(borderless[0]["table"]["cells"], values):
            r, c = cell["r"], cell["c"]
            cell["text"] = value
            cell["bbox"] = [35 + 100*c, 85 + 30*r, 125 + 100*c, 105 + 30*r]
        verified = build_payload(dict(rows[0], horizontal_rules=[]), layout(rows[0], borderless))
        table = verified["blocks"][0]["table"]
        assert table["cell_text_status"] == "exact_glyph_slices_whitespace_ignored"
        assert [r["before_row"] for r in table["row_breaks"]] == [1, 2]
        assert all(r["evidence"] == "exact_cell_text_and_pdf_glyph_gap" for r in table["row_breaks"])
        assert table["repeat_header_region"] == [0.075, 0.2, 0.575, 0.275]
        assert verified["text"] == expected[0]
        p = rows[1]
        b = {"id": "left", "kind": "unknown", "bbox": [25, 35, 150, 90]}
        mapped = build_payload(p, layout(p, [b]))["blocks"][0]
        assert mapped["mapping_status"] == "discontiguous"
        assert mapped["replacement_range"] is None and not mapped["hide_source_allowed"]
        cropped = rows[2]
        glyph = cropped["glyphs"][0]
        mb, cb = cropped["mediabox"], cropped["cropbox"]
        dx, dy = cb[0]-mb[0], mb[3]-cb[3]
        bb = [v + (dx if i % 2 == 0 else dy) for i, v in enumerate(glyph["bbox"])]
        mapped = build_payload(cropped, layout(cropped, [{"id": "crop", "kind": "formula", "bbox": bb}]))["blocks"][0]
        assert mapped["source_ranges"] == [[glyph["start"], glyph["end"]]]
        rotated = build_payload(rows[3], layout(rows[3], [{"id": "rot", "kind": "formula", "bbox": [0, 0, 400, 400]}]))
        assert rotated["geometry_status"] == "original_page"
        assert rotated["blocks"][0]["source_ranges"] == []
        print(f"PDFBox integration: 4 pages unchanged, {count} exact glyph spans; "
              "table/header, caption, callout, formula, interleaved columns, CropBox and rotation OK")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
