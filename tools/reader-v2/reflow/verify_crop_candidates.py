#!/usr/bin/env python3
"""Exercise actual PDF rendering, crop CLI and pixels on the existing own fixture.

Requires PDFBox/Java 17, pypdfium2 and Pillow; no model or real book input.
Known proposals isolate boundary/mapping behavior from object recognition.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import pypdfium2 as pdfium
from PIL import Image, ImageChops

from source_payload import utf16

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
            ["java", "-cp", cp, "PdfReflowProbe", str(pdf), "1,2,3,4"], text=True).splitlines()
            if line.startswith("{")]
        # Some supplied application classpaths log SLF4J startup lines to stdout.
        encoded = [s for s in baseline.splitlines() if re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", s)]
        assert len(rows) == len(encoded) == 4
        assert [r["text"] for r in rows] == [base64.b64decode(s, validate=True).decode("utf-8") for s in encoded]
        layout = root / "layout"
        layout.mkdir()
        boxes = [[30, 80, 230, 170], [25, 35, 150, 90], None, [0, 0, 400, 400]]
        for row, box in zip(rows, boxes):
            row["regions"] = []  # These are known B proposals, not a B/C model-quality test.
            if box is None:
                # The whole known single line; a one-character proposal cuts a
                # word's ink and is correctly refused by the boundary search.
                glyphs = [g["bbox"] for g in row["glyphs"]]
                g = [min(b[0] for b in glyphs), min(b[1] for b in glyphs),
                     max(b[2] for b in glyphs), max(b[3] for b in glyphs)]
                mb, cb = row["mediabox"], row["cropbox"]
                box = [v + (cb[0]-mb[0] if i % 2 == 0 else mb[3]-cb[3]) for i, v in enumerate(g)]
            item = {"doc_sha256": row["doc_sha256"], "page_number": row["page"],
                    "rotation": row["rotation"], "mediabox": row["mediabox"], "cropbox": row["cropbox"],
                    "geometry": "page_space", "blocks": [{"id": "known", "kind": "figure", "bbox": box}]}
            (layout / f'{row["page"]:05d}.json').write_text(json.dumps(item), encoding="utf-8")
        probe = root / "probe.jsonl"
        probe.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
        output = root / "crops"
        command = [sys.executable, str(HERE / "crop_candidates.py"), "--pdf", str(pdf),
                   "--probe", str(probe), "--layout", str(layout), "--out", str(output)]
        subprocess.run(command, check=True, capture_output=True, text=True)
        accepted = []
        with pdfium.PdfDocument(str(pdf)) as document:
            for original in rows:
                result = json.loads((output / f'{original["page"]:05d}-private.json').read_text())
                assert result["text_utf16_sha256"] == hashlib.sha256(utf16(original["text"])).hexdigest()
                candidate, = result["candidates"]
                assert candidate["replacement_range"] is None
                assert not candidate["hide_source_allowed"] and not candidate["import_allowed"]
                assert candidate["semantic_status"] == "unverified"
                if original["page"] == 4:
                    assert candidate["status"] == "unsafe_geometry"
                    continue
                assert candidate["status"] == "boundary_verified", (original["page"], candidate["status"])
                page = document[original["page"]-1]
                try:
                    bitmap = page.render(scale=2)
                    try:
                        expected = bitmap.to_pil().convert("RGB").crop(candidate["pixel_box"])
                    finally:
                        bitmap.close()
                finally:
                    page.close()
                with Image.open(output / candidate["crop_file"]) as saved:
                    assert saved.size == expected.size
                    assert ImageChops.difference(saved.convert("RGB"), expected).getbbox() is None
                accepted.append(candidate)
        assert accepted[1]["mapping_status"] == "discontiguous"
        selected = "".join(utf16(rows[0]["text"])[a*2:b*2].decode("utf-16-le")
                           for a, b in accepted[0]["source_ranges"])
        assert "Header A" in selected and "B2" in selected and "Caption" not in selected
        # The CLI must reject a real input/probe mismatch, not merely a unit fixture.
        rows[0]["doc_sha256"] = "stale"
        probe.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
        command[-1] = str(root / "stale")
        assert subprocess.run(command, capture_output=True).returncode != 0
        print("Crop CLI integration: 4 canonical pages unchanged; 3 pixel-exact crops, "
              "discontiguous/CropBox mappings OK; rotation/stale refused; no import or hide permission")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
