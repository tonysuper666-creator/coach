#!/usr/bin/env python3
"""Local A/B/C reflow experiment. No network calls; all content output stays outside Git.

Requires Java 17+, desktopApp's PDFBox 3 jars, and (for --models-dir) the existing
Docling environment. This is a measurement tool, not an import or rendering API.
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]


def outside_repo(path: Path) -> Path:
    path = path.expanduser().resolve()
    if path == REPO or REPO in path.parents:
        raise ValueError("Content output must be outside the repository")
    return path


def changed_chars(source: str, result: str) -> dict:
    """Deterministic edit-script counts in Unicode code points (not edit distance).

    Whitespace is significant. This is deliberately not an alignment of source
    offsets: source UTF-16 offsets must continue to index the canonical text.
    """
    counts = {"inserted": 0, "deleted": 0, "replaced_source": 0, "replaced_result": 0, "changed": 0}
    for kind, a, b, c, d in difflib.SequenceMatcher(None, source, result, autojunk=False).get_opcodes():
        if kind == "insert":
            counts["inserted"] += d - c
            counts["changed"] += d - c
        elif kind == "delete":
            counts["deleted"] += b - a
            counts["changed"] += b - a
        elif kind == "replace":
            counts["replaced_source"] += b - a
            counts["replaced_result"] += d - c
            counts["changed"] += max(b - a, d - c)
    return counts


def docling_text(page: dict) -> str:
    pieces = []
    for block in sorted(page["blocks"], key=lambda b: b["reading_order"]):
        if block["kind"] == "table":
            cells = (block.get("table") or {}).get("cells", [])
            pieces.extend(c.get("text", "") for c in sorted(cells, key=lambda c: (c["r"], c["c"])))
        elif block.get("text"):
            pieces.append(block["text"])
    return "\n".join(pieces)


def b_regions(page: dict) -> list:
    # Do not count a formula as preserved just because it has a recognised string.
    return [{"kind": b["kind"], "bbox": b["bbox"]} for b in page["blocks"]
            if b["kind"] in {"table", "figure", "formula", "unknown"} and b.get("bbox")]


def run(args) -> None:
    output = outside_repo(args.out)
    pdf = args.pdf.expanduser().resolve()
    pages = [int(n) for n in args.pages.split(",")]
    if not pages or len(set(pages)) != len(pages) or min(pages) < 1:
        raise ValueError("Use distinct, positive, one-based physical PDF page numbers")
    output.mkdir(parents=True, exist_ok=True)
    (output / "classes").mkdir(exist_ok=True)
    started = time.perf_counter()
    subprocess.run(["javac", "-cp", args.pdfbox_classpath, "-d", str(output / "classes"), str(HERE / "PdfReflowProbe.java")], check=True)
    compiled = time.perf_counter() - started
    cp = str(output / "classes") + os.pathsep + args.pdfbox_classpath
    started = time.perf_counter()
    with (output / "ac-private.jsonl").open("w", encoding="utf-8") as out, (output / "ac-private.log").open("w", encoding="utf-8") as log:
        subprocess.run(["java", "-cp", cp, "PdfReflowProbe", str(pdf), args.pages], stdout=out, stderr=log, check=True)
    wall = time.perf_counter() - started
    ac = [json.loads(line) for line in (output / "ac-private.jsonl").read_text(encoding="utf-8").splitlines()]
    btime = {}
    if args.models_dir:
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        import sys
        sys.path.insert(0, str(HERE.parent / "layout"))
        import layout_pipeline as lp
        started = time.perf_counter()
        engine = lp.DoclingEngine(args.models_dir.expanduser().resolve())
        btime["construction_seconds"] = time.perf_counter() - started
        meta = lp.pdf_page_meta(pdf)
        sha = lp.sha256_file(pdf)
        btime["engine_version"] = engine.version
        btime["pages"] = []
        (output / "docling").mkdir(exist_ok=True)
        for n in pages:
            started = time.perf_counter()
            raw = engine.convert_range(pdf, n, n)
            page = lp.build_page_json(n, raw.get(n), meta[n - 1], sha)
            elapsed = time.perf_counter() - started
            btime["pages"].append({"page": n, "seconds": elapsed})
            (output / "docling" / f"{n:05d}.json").write_text(json.dumps(page, ensure_ascii=False), encoding="utf-8")
    bseconds = {r["page"]: r["seconds"] for r in btime.get("pages", [])}
    review = json.loads(args.review.read_text(encoding="utf-8")) if args.review else {}
    metrics = []
    for row in ac:
        n = row["page"]
        bfile = output / "docling" / f"{n:05d}.json"
        b = json.loads(bfile.read_text(encoding="utf-8")) if n in bseconds else None
        item = {"page": n, "source_codepoints": len(row["text"]), "source_utf16_units": len(row["text"].encode("utf-16-le")) // 2,
                "A": {"changed": 0, "seconds": row["a_seconds"], "regions": 0},
                "B": None if b is None else {"diff": changed_chars(row["text"], docling_text(b)), "seconds": bseconds.get(n),
                     "regions": len(b_regions(b)), "order_gate": b["reading_order_status"], "page_fallback": b.get("fallback") == "original_page"},
                "C": {"changed": 0, "seconds_incremental": row["c_seconds"], "regions": len(row["regions"]), "page_fallback": row["fallback"],
                      "clip_unverified": row["clip_unverified"], "rotated_glyphs": row["rotated_glyphs"]},
                "D": {"status": "not_run_local_content_boundary"}, "manual_review": review.get(str(n))}
        metrics.append(item)
    receipt = {"schema": 1, "source_sha256": hashlib.sha256(pdf.read_bytes()).hexdigest(), "physical_pages": pages,
               "java_compile_seconds": compiled, "ac_process_wall_seconds": wall, "docling": btime, "pages": metrics}
    (output / "metrics.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.sheets:
        draw_sheets(pdf, ac, output, measured_b=set(bseconds))
    print(f"Measured {len(metrics)} pages; private outputs written outside repository")


def draw_sheets(pdf: Path, ac: list, output: Path, measured_b: set[int]) -> None:
    import pypdfium2 as pdfium
    from PIL import Image, ImageDraw
    with pdfium.PdfDocument(str(pdf)) as document:
        for row in ac:
            n = row["page"]
            page = document[n - 1]
            scale = 650 / page.get_width()
            original = page.render(scale=scale).to_pil().convert("RGB")
            page.close()
            sheet = Image.new("RGB", (original.width * 3, original.height + 30), "#dddddd")
            bfile = output / "docling" / f"{n:05d}.json"
            b = json.loads(bfile.read_text(encoding="utf-8")) if n in measured_b else None
            groups = [[], b_regions(b) if b else [], row["regions"]]
            for i, regions in enumerate(groups):
                img = original.copy()
                draw = ImageDraw.Draw(img)
                for k, region in enumerate(regions):
                    box = [v * scale for v in region["bbox"]]
                    draw.rectangle(box, outline="#e63d21" if i == 1 else "#005ede", width=2)
                    draw.text((box[0], max(0, box[1] - 12)), str(k), fill="#cf2000")
                sheet.paste(img, (i * original.width, 30))
            ImageDraw.Draw(sheet).text((8, 8), f"physical page {n} | Original / B regions / C regions; whole-page fallback reported separately", fill="black")
            sheet.save(output / f"review-{n:05d}.png")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--pages", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--pdfbox-classpath", required=True, help="PDFBox, fontbox, pdfbox-io and commons-logging jars, separated by the OS classpath separator")
    parser.add_argument("--models-dir", type=Path, help="Run B with pre-fetched local Docling models; absent means B is unmeasured")
    parser.add_argument("--review", type=Path, help="Private manual per-page results; never commit real textbook annotations")
    parser.add_argument("--sheets", action="store_true", help="Render private original/B/C overlays, not reader screenshots")
    run(parser.parse_args())
