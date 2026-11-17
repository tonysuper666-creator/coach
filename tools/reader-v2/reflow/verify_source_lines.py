#!/usr/bin/env python3
"""Exercise pending source-line alternatives against real PDF glyphs and pixels.

Requires Java 17, PDFBox, reportlab, pypdfium2 and Pillow. Fixture geometry is
known; this does not test model recognition or semantic object completeness.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile

import pypdfium2 as pdfium
from PIL import ImageChops
from reportlab.pdfgen import canvas

from crop_candidates import SCALE
from object_review_plan import plan_page, union
from source_payload import build_payload, utf16

HERE = Path(__file__).resolve().parent
LINES = [(40, "Table 1. A small table"), (75, "A B"), (100, "C D"),
         (132, "Note: both rows are examples."), (146, "Source: Example collection"),
         (158, "continued source line."), (200, "Body remains outside the object.")]
# PDFBox emits its existing three-newline paragraph separator at large gaps.
EXPECTED_TEXT = "\n\n\n".join([LINES[0][1], LINES[1][1], LINES[2][1],
                                  "\n".join(text for _, text in LINES[3:6]), LINES[6][1], ""])


def verify(classpath):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        pdf = root / "source-lines.pdf"
        drawing = canvas.Canvas(str(pdf), pagesize=(400, 360), invariant=1)
        for cropped in (False, True):
            if cropped:
                drawing.setCropBox((10, 20, 390, 340))
            drawing.setFont("Helvetica", 10)
            for y, text in LINES:
                drawing.drawString(40, 360 - y, text)
            drawing.rect(36, 250, 220, 55)
            drawing.showPage()
        drawing.save()
        subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(root),
                        str(HERE / "PdfReflowProbe.java")], check=True)
        rows = [json.loads(line) for line in subprocess.check_output(
            ["java", "-cp", str(root) + os.pathsep + classpath, "PdfReflowProbe", str(pdf), "1,2"],
            text=True).splitlines() if line.startswith("{")]
        assert len(rows) == 2
        assert rows[0]["cropbox"] != rows[1]["cropbox"]
        assert all(r["text"] == EXPECTED_TEXT for r in rows)
        original = copy.deepcopy(rows)

        with pdfium.PdfDocument(str(pdf)) as document:
            for row in rows:
                # Fixture-only text lookup supplies known model boxes. Production
                # mapping under test uses intersecting glyph offsets, never search.
                def known_box(text):
                    start = len(utf16(row["text"].split(text, 1)[0])) // 2
                    end = start + len(utf16(text)) // 2
                    return union([g["bbox"] for g in row["glyphs"] if start <= g["start"] < end])

                def media_box(box):
                    dx = row["cropbox"][0] - row["mediabox"][0]
                    dy = row["mediabox"][3] - row["cropbox"][3]
                    return [v + (dx if i % 2 == 0 else dy) for i, v in enumerate(box)]

                def block(identity, kind, text):
                    box = known_box(text)
                    box = [box[0]-1, box[1]-1, box[2]+1, box[3]+1]
                    return {"id": identity, "kind": kind, "bbox": media_box(box), "text": text,
                            "source_ref": identity, "source_scope": "fixture", "provenance_count": 1}

                layout = {"doc_sha256": row["doc_sha256"], "page_number": row["page"],
                          "rotation": 0, "mediabox": row["mediabox"], "cropbox": row["cropbox"],
                          "geometry": "page_space", "blocks": [
                              {"id": "table", "kind": "table", "bbox": [35, 54, 257, 111],
                               "source_ref": "table", "source_scope": "fixture", "provenance_count": 1,
                               "caption_refs": ["caption"], "footnote_refs": ["note"]},
                              block("caption", "caption", LINES[0][1]),
                              block("note", "footnote", LINES[3][1]),
                              block("source", "text", LINES[4][1] + "\n" + LINES[5][1]),
                              block("body", "text", LINES[6][1])]}
                payload = build_payload(row, layout)
                page = document[row["page"] - 1]
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
                        output = plan_page(row, value, ink.tobytes(), image.width, image.height)
                        assert output["approved"] == 0
                        assert not output["import_allowed"] and not output["hide_source_allowed"]
                        assert all(i["replacement_range"] is None for i in output["items"])
                        return output

                    def sources(output):
                        return [a for i in output["items"] for a in i["alternatives"]
                                if a["id"].startswith("source-lines-")]

                    output = plan(layout)
                    extra, = sources(output)
                    assert extra["selection"] is not None, extra["blockers"]
                    assert "source_line_ownership_unverified" in extra["warnings"]
                    part, = extra["evidence"]["source_line_hypothesis"]["source_parts"]
                    actual = b"".join(utf16(row["text"])[a*2:b*2] for a, b in part["source_ranges"])
                    assert actual.decode("utf-16-le") == LINES[4][1] + "\n" + LINES[5][1]
                    old, = [a for i in output["items"] for a in i["alternatives"]
                            if a["id"].startswith("labels-")]
                    assert old["selection"] is not None, old["blockers"]
                    assert old["selection"]["bottom"] < extra["selection"]["bottom"]
                    assert build_payload(row, layout) == payload
                    for wrong in ("Source: changed", LINES[4][1], "The Source: ordinary prose"):
                        changed = copy.deepcopy(layout)
                        changed["blocks"][3]["text"] = wrong
                        assert not sources(plan(changed))
                    changed = copy.deepcopy(layout)
                    changed["blocks"].append(dict(changed["blocks"][0], id="ambiguous-owner"))
                    assert not sources(plan(changed))
                    changed = copy.deepcopy(layout)
                    changed["blocks"].append(block("body-veto", "text", LINES[3][1]))
                    veto, = sources(plan(changed))
                    assert veto["selection"] is None and "possible_body_text" in veto["blockers"]
                finally:
                    image.close()
        assert rows == original
        print("Source-line PDF regression: 2 canonical pages unchanged; normal/CropBox multiline "
              "source slices and pending alternatives OK; original explicit groups retained; "
              "changed/incomplete/body text and ambiguous owners refused; 0 approvals/imports/hiding")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
