#!/usr/bin/env python3
"""Exercise pending selections and all-or-nothing output on a self-authored PDF.

Requires Java 17, PDFBox, pypdfium2 and Pillow; no model or private book input.
Known layout/caption labels isolate planning from recognition accuracy.
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

from source_payload import utf16

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
        layouts = root / "layout"
        layouts.mkdir()
        boxes = [[30, 80, 230, 170], [25, 35, 150, 90], None, [0, 0, 400, 400]]
        for row, box in zip(rows, boxes):
            row["regions"] = []
            if box is None:
                glyphs = [g["bbox"] for g in row["glyphs"]]
                mb, cb = row["mediabox"], row["cropbox"]
                box = [min(b[0] for b in glyphs) + cb[0] - mb[0],
                       min(b[1] for b in glyphs) + mb[3] - cb[3],
                       max(b[2] for b in glyphs) + cb[0] - mb[0],
                       max(b[3] for b in glyphs) + mb[3] - cb[3]]
            blocks = [{"id": "known", "kind": "figure", "bbox": box}]
            if row["page"] == 1:
                blocks[0]["caption_refs"] = ["caption"]
                blocks += [{"id": "caption", "source_ref": "caption", "kind": "caption", "bbox": [25, 176, 150, 195]},
                           {"id": "prose", "kind": "text", "bbox": [25, 20, 160, 40]}]
                row["regions"] = [{"bbox": [25, 20, 160, 40]}]
            item = {"doc_sha256": row["doc_sha256"], "page_number": row["page"],
                    "rotation": row["rotation"], "mediabox": row["mediabox"], "cropbox": row["cropbox"],
                    "geometry": "page_space", "blocks": blocks}
            (layouts / f'{row["page"]:05d}.json').write_text(json.dumps(item), encoding="utf-8")
        probe = root / "probe.jsonl"

        def save():
            probe.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")

        command = [sys.executable, str(HERE / "object_review_plan.py"), "--pdf", str(pdf),
                   "--probe", str(probe), "--layout", str(layouts), "--out"]

        def invoke(output, success=True):
            result = subprocess.run([*command, str(output)], capture_output=True, text=True)
            assert (result.returncode == 0) == success, result.stderr
            if not success:
                assert not output.exists()

        save()
        output = root / "plan"
        invoke(output)
        summary = json.loads((output / "summary.json").read_text())
        assert summary["pending_selections"] == 3 and summary["approved"] == 0, summary
        assert not summary["import_allowed"] and not summary["hide_source_allowed"]
        selections = [json.loads(p.read_text()) for p in (output / "selections").glob("*.selection.json")]
        assert {s["page"] for s in selections} == {1, 2, 3}
        assert all(set(s) == {"left", "top", "right", "bottom", "page", "kind"} for s in selections)
        for row in rows:
            plan = json.loads((output / f'{row["page"]:05d}-private.json').read_text())
            assert plan["text_utf16_sha256"] == hashlib.sha256(utf16(row["text"])).hexdigest()
            if row["page"] == 1:
                assert "explicit_caption_outside_crop" in plan["items"][0]["blockers"]
                assert any("possible_body_text" in i["blockers"] for i in plan["items"])
                expanded, = [i for i in plan["items"] if i["id"].startswith("labels-")]
                assert expanded["selection"] is not None
                assert expanded["evidence"]["caption_part_ids"] == ["caption"]
            elif row["page"] == 4:
                assert all(i["selection"] is None for i in plan["items"])
        before = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob("*") if p.is_file()}
        assert subprocess.run([*command, str(output)], capture_output=True).returncode != 0
        assert before == {str(p.relative_to(output)): p.read_bytes() for p in output.rglob("*") if p.is_file()}
        # Invalid later input must not leave earlier pages or selections behind.
        layout4 = layouts / "00004.json"
        original = layout4.read_text()
        layout4.write_text(original.replace(rows[3]["doc_sha256"], "stale"))
        invoke(root / "bad-late-page", False)
        layout4.write_text(original)
        rows.append(rows[0])
        save()
        invoke(root / "duplicate-pages", False)
        rows.pop()
        rows[0]["doc_sha256"] = "stale"
        save()
        invoke(root / "stale", False)
        print("Object review plan CLI: 4 canonical pages unchanged; 3 pending selections; "
              "explicit caption expansion, prose veto, CropBox and rotation OK; "
              "late-invalid/duplicate/stale inputs leave no output; existing output unchanged; 0 approvals")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
