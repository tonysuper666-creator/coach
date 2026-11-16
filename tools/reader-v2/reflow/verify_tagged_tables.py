#!/usr/bin/env python3
"""Exercise real PDF tags -> MCIDs -> canonical glyph slices, without a model."""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from tagged_tables import build_tagged_continuations

HERE = Path(__file__).resolve().parent


def verify(classpath):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        subprocess.run(["javac", "--release", "17", "-cp", classpath, "-d", str(root),
                        *[str(HERE / name) for name in ("PdfReflowProbe.java", "TaggedTableProbe.java",
                                                       "TaggedTablePdfFixture.java")]], check=True)
        cp = str(root) + os.pathsep + classpath
        for mode in ("normal", "untagged", "split_tables", "bad_parent", "duplicate_owner",
                     "duplicate_mcid", "rowspan", "actual_text"):
            pdf = root / f"{mode}.pdf"
            baseline = subprocess.check_output(["java", "-cp", cp, "TaggedTablePdfFixture", str(pdf), mode], text=True)
            expected = [base64.b64decode(s).decode("utf-8") for s in baseline.splitlines()]
            regular_text = subprocess.check_output(["java", "-cp", cp, "PdfReflowProbe", str(pdf), "1,2,3"], text=True)
            probes = [json.loads(line) for line in regular_text.splitlines()]
            native_text = subprocess.check_output(["java", "-cp", cp, "TaggedTableProbe", str(pdf), "1,2,3"], text=True)
            native = json.loads(native_text)
            assert [p["text"] for p in probes] == expected
            assert [p["text"] for p in native["pages"]] == expected
            result = build_tagged_continuations(native, probes)
            passed = [t for t in result["tables"] if t["status"] == "verified_tagged_association"]
            if mode == "normal":
                assert native["structure_status"] == "parsed", native["structure_status"]
                assert len(passed) == 1, result
                table = passed[0]
                assert len(table["fragments"]) == 3
                assert [len(f["row_breaks"]) for f in table["fragments"]] == [1, 1, 1]
                assert [[r["row"] for r in f["rows"]] for f in table["fragments"]] == [[0, 1], [2, 3], [4, 5]]
                assert not table["crop_ready"] and not table["hide_source_allowed"]
                assert all(f["source_region"] is None and f["replacement_range"] is None for f in table["fragments"])
                (root / "native.json").write_text(native_text, encoding="utf-8")
                (root / "probe.jsonl").write_text(regular_text, encoding="utf-8")
                subprocess.run([sys.executable, str(HERE / "tagged_tables.py"), "--native", str(root / "native.json"),
                                "--probe", str(root / "probe.jsonl"), "--out", str(root / "out.json")], check=True)
                assert json.loads((root / "out.json").read_text()) == result
                stale = copy.deepcopy(probes)
                stale[1]["text"] = "Stale" + stale[1]["text"]
                try:
                    build_tagged_continuations(native, stale)
                except ValueError as e:
                    assert str(e) == "stale_or_mismatched_canonical_page"
                else:
                    raise AssertionError("stale text accepted")
                partial = copy.deepcopy(native)
                partial["pages"] = partial["pages"][:2]
                missing = build_tagged_continuations(partial, probes[:2])
                assert missing["tables"][0]["reason"] == "missing_or_unsafe_page"
            else:
                assert not passed, (mode, result)
            print(f"Tagged PDF mode {mode}: canonical pages unchanged; association {'verified' if passed else 'refused'}")
        print("Tagged PDF integration: 1 native table, 3 explicit fragments, 3 text-safe cuts; "
              "7 unsupported/ambiguous modes and stale/missing pages refused; no crop/hiding permission")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdfbox-classpath", required=True)
    verify(parser.parse_args().pdfbox_classpath)
