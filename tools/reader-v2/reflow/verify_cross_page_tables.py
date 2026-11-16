#!/usr/bin/env python3
"""Run PDFBox -> private payload CLI -> cross-page header/cut regressions.

Known model geometry tests the join, not Docling's ability to recognize a table.
"""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from cross_page_tables import build_continuations
from source_payload import utf16

HERE = Path(__file__).resolve().parent


def verify(classpath):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(root),
                        str(HERE / "PdfReflowProbe.java"), str(HERE / "CrossPagePdfFixture.java")], check=True)
        cp = str(root) + os.pathsep + classpath
        pdf = root / "cross-page.pdf"
        baseline = subprocess.check_output(["java", "-cp", cp, "CrossPagePdfFixture", str(pdf)], text=True)
        expected = [base64.b64decode(s).decode("utf-8") for s in baseline.splitlines()]
        probe_text = subprocess.check_output(["java", "-cp", cp, "PdfReflowProbe", str(pdf), "1,2,3"], text=True)
        probes = [json.loads(line) for line in probe_text.splitlines()]
        assert [p["text"] for p in probes] == expected
        values = [["Header A", "Header B"], ["A1", "B1"], ["A2", "B2"],
                  ["A3", "B3"], ["Header A", "Header B"], ["A4", "B4"]]
        cells = [{"r": r, "c": c, "rs": 1, "cs": 1, "text": value,
                  "column_header": r in (0, 4),
                  "bbox": [30+100*c, 80+(r % 2)*30, 130+100*c, 110+(r % 2)*30]}
                 for r, row in enumerate(values) for c, value in enumerate(row)]
        layouts = []
        for p in probes:
            layout = {"doc_sha256": p["doc_sha256"], "page_number": p["page"],
                      "rotation": 0, "cropbox": p["cropbox"], "mediabox": p["mediabox"],
                      "geometry": "page_space", "blocks": [{
                          "id": f'p{p["page"]}t', "kind": "table", "bbox": [30, 80, 230, 140],
                          "source_ref": "#/tables/0", "source_scope": "self-authored-source-graph", "provenance_count": 3,
                          "table": {"rows": 6, "cols": 2, "cells": cells,
                                    "geometry_status": "ambiguous_multi_page"}}]}
            layouts.append(layout)
            (root / f'{p["page"]:05d}.json').write_text(json.dumps(layout), encoding="utf-8")
        (root / "probe.jsonl").write_text(probe_text, encoding="utf-8")
        # Exercise the real CLI output, including batch validation and file naming.
        subprocess.run([sys.executable, str(HERE / "source_payload.py"), "--probe", str(root / "probe.jsonl"),
                        "--layout", str(root), "--out", str(root / "out")], check=True)
        out = json.loads((root / "out/table-continuations.json").read_text())
        table = out["tables"][0]
        assert table["status"] == "verified_source_fragments", table["reason"]
        assert [(f["row_start"], f["row_end"]) for f in table["fragments"]] == [(0, 2), (2, 4), (4, 6)]
        assert [f["header_mode"] for f in table["fragments"]] == ["source", "repeat_first_fragment", "source"]
        assert [f["row_breaks"][0]["before_row"] for f in table["fragments"]] == [1, 3, 5]
        header = table["repeat_header"]
        assert header["page"] == 1 and header["source_region"] == [0.075, 0.2, 0.575, 0.275]
        recovered = "".join(utf16(probes[0]["text"])[a*2:b*2].decode("utf-16-le")
                            for a, b in header["source_ranges"])
        assert "".join(recovered.split()) == "HeaderAHeaderB"
        for p, value in zip(probes, expected):
            payload = json.loads((root / f'out/{p["page"]:05d}.json').read_text())
            assert payload["text"] == value
            assert payload["blocks"][0]["table"]["split_status"] == "unverified"
            # Ordinary page payloads still do not guess multi-page cell geometry.
            assert all(c["source_region"] is None for c in payload["blocks"][0]["table"]["cells"])
        stale = copy.deepcopy(probes)
        stale[1]["text"] = stale[1]["text"].replace("A2", "XX")
        refused = build_continuations(stale, layouts)["tables"][0]
        assert refused["status"] == "unverified" and refused["fragments"] == []
        print("Cross-page PDFBox integration: 3 canonical pages unchanged; 1 explicit source table, "
              "3 exact fragments, 3 row cuts, header modes source/repeat/source; stale text refused")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
