"""Independent JSON/UTF-16 consumer of the public synthetic Kotlin contract vectors.

No PDF/EPUB library, network, corpus access, or implementation import is needed.
This validates the promised resolved sentence, not a second copy of the resolver.
"""
import json
from pathlib import Path


def verify(path):
    payload = json.loads(path.read_text(encoding="utf-8"))
    # A non-Kotlin client can decode/encode non-ASCII data without losing fields.
    assert json.loads(json.dumps(payload, ensure_ascii=False)) == payload
    checked = 0
    for case in payload["cases"]:
        result = case["expected"]
        target = result["target"]
        if result["status"] != "RESOLVED":
            assert target is None, case["name"]
            continue
        assert target["version"] == case["index"]["version"]
        unit = next(u for u in case["index"]["units"] if u["resource"] == target["resource"])
        bounds = target["range"]
        raw = unit["text"].encode("utf-16-le")
        selected = raw[bounds["startUtf16"] * 2:bounds["endUtf16"] * 2].decode("utf-16-le")
        assert selected == case["anchor"]["quote"]["exact"], case["name"]
        if target["locator"] is not None:
            assert target["locator"]["href"] == target["resource"]["href"]
            assert not target["locator"]["locations"]["fragments"]
        checked += 1
    labels = payload["pageLabels"]
    assert all(0 <= p["pdfPageIndex0"] < labels["pdfPageCount"] for p in labels["entries"])
    assert "2" not in [p["label"] for p in labels["entries"]]  # never invent missing printed pages
    assert payload["entry"]["anchor"]["version"] != payload["cases"][0]["expected"]["targetVersion"]
    print(f"PASS: {len(payload['cases'])} JSON vectors; {checked} resolved sentences checked with UTF-16 offsets; unknown page retained")


if __name__ == "__main__":
    verify(Path(__file__).resolve().parents[2] / "docs/reader-anchors/contract-v1.json")
