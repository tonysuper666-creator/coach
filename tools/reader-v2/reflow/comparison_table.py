"""Print the numeric A/B/C table from a completed benchmark.py metrics.json."""
import argparse
import json
from pathlib import Path


def comparison_table(receipt):
    pages = receipt["pages"]
    if not pages or [p["page"] for p in pages] != receipt["physical_pages"]:
        raise ValueError("Receipt must contain every requested physical page, in order")
    if any(p["B"] is None for p in pages):
        raise ValueError("Run benchmark.py with --models-dir to measure B")

    lines = [
        "| 物理页 | A 字符数 | B / C 候选框数 | B 文字差异 | B / C 回退页数 | A / B / C 秒 |",
        "|---:|---:|:---:|---:|:---:|:---:|",
    ]

    def add_row(label, group):
        chars = sum(p["source_codepoints"] for p in group)
        changed = sum(p["B"]["diff"]["changed"] for p in group)
        candidates = [sum(p[route]["regions"] for p in group) for route in ("B", "C")]
        fallback = [sum(p[route]["page_fallback"] for p in group) for route in ("B", "C")]
        elapsed = [sum(p[route][key] for p in group) for route, key in
                   (("A", "seconds"), ("B", "seconds"), ("C", "seconds_incremental"))]
        times = " / ".join(f"{seconds:.4f}" for seconds in elapsed)
        lines.append(f"| {label} | {chars} | {candidates[0]} / {candidates[1]} | {changed} "
                     f"| {fallback[0]} / {fallback[1]} | {times} |")

    for page in pages:
        add_row(page["page"], [page])
    add_row("合计", pages)
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metrics", type=Path)
    args = parser.parse_args()
    print(comparison_table(json.loads(args.metrics.read_text(encoding="utf-8"))))
