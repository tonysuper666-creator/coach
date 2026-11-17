#!/usr/bin/env python3
"""Check canonical offsets and page-wide crop vetoes on self-authored glyph gaps.

Requires Java 17 + PDFBox. No models, private inputs, renderer or network.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from object_review_plan import plan_page

HERE = Path(__file__).resolve().parent


def verify(classpath):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(root),
                        *[str(HERE / f) for f in ("PdfReflowProbe.java", "SyntheticPdfFixture.java",
                                                 "ProbeGeometryFixture.java")]], check=True)
        cp = str(root) + os.pathsep + classpath
        pdf = root / "geometry.pdf"
        reference = subprocess.check_output(["java", "-cp", cp, "ProbeGeometryFixture", str(pdf)], text=True)
        rows = [json.loads(line) for line in subprocess.check_output(
            ["java", "-cp", cp, "PdfReflowProbe", str(pdf), "1,2,3,4,5"], text=True).splitlines()
            if line.startswith("{")]
        encoded = [s for s in reference.splitlines() if re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", s)]
        assert len(rows) == len(encoded) == 5
        assert [r["text"] for r in rows] == [base64.b64decode(s, validate=True).decode("utf-8") for s in encoded]
        for i, row in enumerate(rows):
            assert row["glyph_geometry_checked"] is True
            assert bool(row["unmapped_ranges"]) == (i != 0), (i, row["unmapped_ranges"])
            for start, end in row["unmapped_ranges"]:
                assert row["text"].encode("utf-16-le")[2*start:2*end].decode("utf-16-le") == "x"
            # The gap is outside the otherwise viable object. It still vetoes
            # ordinary proposals and any caption expansion on that page.
            row["regions"] = []
            layout = {"doc_sha256": row["doc_sha256"], "page_number": row["page"],
                      "rotation": row["rotation"], "mediabox": row["mediabox"], "cropbox": row["cropbox"],
                      "geometry": "page_space", "blocks": [{"id": "f", "kind": "figure", "bbox": [25, 80, 85, 110]}]}
            pixels = bytearray([255]) * 800 * 800
            for y in range(185, 200):
                pixels[y*800+60:y*800+150] = bytes(90)
            out = plan_page(row, layout, pixels, 800, 800)
            selections = [item for item in out["items"] if item["selection"]]
            assert len(selections) == (1 if i == 0 else 0)
            if i:
                assert all(item["blockers"] == ["unsafe_geometry"] for item in out["items"])
            assert out["approved"] == 0 and not out["import_allowed"] and not out["hide_source_allowed"]
        print("Probe geometry: 5 canonical pages unchanged; normal crop retained; zero-width/zero-height/"
              "CropBox-clamped glyph gaps veto every selection; exact UTF-16 gap slices; 0 approvals")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
