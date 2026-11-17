#!/usr/bin/env python3
"""Check explicit note expansion against real PDF glyphs and raster boundaries.

Requires Java 17, PDFBox, pypdfium2 and Pillow. Known labels in the existing
self-authored fixture isolate note mapping from model recognition/completeness.
"""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

import pypdfium2 as pdfium
from PIL import ImageChops

from crop_candidates import SCALE
from object_review_plan import plan_page, union
from source_payload import build_payload, utf16

HERE = Path(__file__).resolve().parent


def verify(classpath):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(root),
                        str(HERE / "PdfReflowProbe.java"), str(HERE / "SyntheticPdfFixture.java")], check=True)
        cp = str(root) + os.pathsep + classpath
        pdf = root / "fixture.pdf"
        reference = subprocess.check_output(["java", "-cp", cp, "SyntheticPdfFixture", str(pdf)], text=True)
        rows = [json.loads(line) for line in subprocess.check_output(
            ["java", "-cp", cp, "PdfReflowProbe", str(pdf), "1,2,3,4"], text=True).splitlines()
            if line.startswith("{")]
        encoded = [s for s in reference.splitlines() if re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", s)]
        assert len(rows) == len(encoded) == 4
        assert [r["text"] for r in rows] == [base64.b64decode(s, validate=True).decode("utf-8") for s in encoded]
        row = rows[0]
        original = copy.deepcopy(rows)

        def known_box(text):
            # This fixture-only lookup supplies known model geometry; the mapper
            # under test may only use intersecting glyph slices, never search.
            start = len(utf16(row["text"].split(text, 1)[0])) // 2
            end = start + len(utf16(text)) // 2
            return union([g["bbox"] for g in row["glyphs"] if start <= g["start"] < end])

        note_text = "Table 1. Caption"
        box = known_box(note_text)
        # Deliberately clip the first glyph by 1pt. The explicit target still
        # intersects it, but only exact canonical text can justify repair.
        note_box = [box[0] + 1, box[1] - 1, box[2] + 1, box[3] + 1]
        layout = {"doc_sha256": row["doc_sha256"], "page_number": 1,
                  "rotation": 0, "mediabox": row["mediabox"], "cropbox": row["cropbox"],
                  "geometry": "page_space", "blocks": [
                      {"id": "table", "kind": "table", "bbox": [30, 80, 230, 170],
                       "source_ref": "table", "source_scope": "fixture", "provenance_count": 1,
                       "footnote_refs": ["note"]},
                      {"id": "note", "kind": "footnote", "bbox": note_box, "text": note_text,
                       "source_ref": "note", "source_scope": "fixture", "provenance_count": 1}]}
        payload = build_payload(row, layout)
        assert payload["blocks"][1]["mapping_status"] == "ambiguous"
        part, = payload["object_label_groups"][0]["source_parts"]
        assert part["geometry_evidence"] == "bounded_glyph_envelope_exact_note_text"
        text = b"".join(utf16(row["text"])[a * 2:b * 2] for a, b in part["source_ranges"]).decode("utf-16-le")
        assert "".join(text.split()) == "".join(note_text.split())

        with pdfium.PdfDocument(str(pdf)) as document:
            page = document[0]
            bitmap = page.render(scale=SCALE)
            try:
                image = bitmap.to_pil().convert("RGB")
            finally:
                bitmap.close()
                page.close()
            try:
                red, green, blue = image.split()
                ink = ImageChops.darker(ImageChops.darker(red, green), blue)

                def plan(value):
                    result = plan_page(row, value, ink.tobytes(), image.width, image.height)
                    assert result["approved"] == 0
                    assert not result["import_allowed"] and not result["hide_source_allowed"]
                    assert all(i["replacement_range"] is None for i in result["items"])
                    return result

                result = plan(layout)
                expanded, = [a for i in result["items"] for a in i["alternatives"]
                             if a["id"].startswith("labels-")]
                assert expanded["selection"] is not None, expanded["blockers"]
                assert expanded["evidence"]["adjusted_note_part_ids"] == ["note"]
                assert any("explicit_notes_outside_crop" in i["blockers"] for i in result["items"])
                for wrong in ("Table 1. changed", "Before table", "Table 1. Caption missing line"):
                    changed = copy.deepcopy(layout)
                    changed["blocks"][1]["text"] = wrong
                    assert not any(a["id"].startswith("labels-") for i in plan(changed)["items"]
                                   for a in i["alternatives"])
                # A uniquely linked note on the other side of the table does
                # not permit the intervening table's glyphs to be claimed.
                changed = copy.deepcopy(layout)
                changed["blocks"][0]["bbox"] = [30, 240, 230, 320]
                other = known_box("Before table")
                changed["blocks"][1].update(bbox=[other[0] - 1, other[1] - 1, other[2] + 1, other[3] + 1],
                                            text="Before table")
                expanded, = [a for i in plan(changed)["items"] for a in i["alternatives"]
                             if a["id"].startswith("labels-")]
                assert "unowned_glyph_in_expansion" in expanded["blockers"]
                assert expanded["selection"] is None
            finally:
                image.close()
        assert rows == original
        print("Explicit note PDF regression: 4 canonical pages unchanged; clipped note edge repaired "
              "from exact glyph slices; 1 pending expansion; changed/incomplete/elsewhere text and "
              "intervening unowned glyphs refused; 0 approvals/imports/hiding")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
