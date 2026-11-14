#!/usr/bin/env python3
"""Collect the synthetic lesson test receipts and verified-size screenshots after a Mac run."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def collect(repo: Path, output: Path) -> dict:
    screenshots = repo / "shared/ui/build/screens/ton1447"
    reports = repo / "shared/ui/build/test-results/jvmTest"
    manifest = {"head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
                "screenshots": [], "suites": [], "missing_or_invalid": []}
    files = []
    expected = {f"ladder-{stage}-933x704-{theme}.png" for stage in ("recap", "restored") for theme in ("light", "dark")}
    for path in sorted(screenshots.glob("*")):
        if path.suffix not in (".png", ".txt") or not path.name.startswith(("ladder-", "mainline-", "outer-", "inner-", "week-practice-")):
            continue
        files.append(path)
        if path.suffix != ".png":
            continue
        data = path.read_bytes()
        if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            manifest["missing_or_invalid"].append(path.name)
            continue
        width, height = struct.unpack(">II", data[16:24])
        manifest["screenshots"].append({"file": str(path.relative_to(repo)), "width": width, "height": height,
                                        "sha256": hashlib.sha256(data).hexdigest()})
        if path.name in expected:
            if (width, height) != (933, 704):
                manifest["missing_or_invalid"].append(f"{path.name}: {width}x{height}")
            expected.remove(path.name)
    manifest["missing_or_invalid"].extend(sorted(expected))
    for name in ("LadderFlowTest", "MainlineScreenTest", "ReviewEntryTest"):
        matched = sorted(reports.glob(f"TEST-*{name}*.xml"))
        if not matched:
            manifest["missing_or_invalid"].append(f"{name} test XML")
        for path in matched:
            root = ET.parse(path).getroot()
            manifest["suites"].append({"file": str(path.relative_to(repo)), "name": root.get("name"),
                                      **{key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")},
                                      "failed_cases": [case.get("name") for case in root.findall("testcase") if case.find("failure") is not None or case.find("error") is not None]})
            files.append(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, str(path.relative_to(repo)))
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/ton1447-ui-evidence.zip"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    target = args.output.resolve()
    receipt = collect(root, target)
    print(json.dumps({"bundle": str(target), **receipt}, ensure_ascii=False, indent=2))
    raise SystemExit(1 if receipt["missing_or_invalid"] else 0)
