#!/usr/bin/env python3
"""Print per-version crash and explain/ask rates from reliability/counts.json.

Numbers and closed reason codes only. Run while the server is up; this does not start it.

    python3 tools/dev/version-rates.py --data "$COACH_DATA_DIR"
"""
import argparse
import json
from pathlib import Path

REASONS = ("timeout", "quota", "refusal", "source", "other")
NAMES = {
    "timeout": "超时",
    "quota": "额度",
    "refusal": "拒答",
    "source": "出处",
    "other": "其他",
}


def rate(bucket):
    ok = int(bucket.get("ok") or 0)
    fail = sum(int(bucket.get(reason) or 0) for reason in REASONS)
    total = ok + fail
    if total == 0:
        return "n/a"
    tenths = (fail * 1000 + total // 2) // total
    return f"{tenths / 10:.1f}% ({fail}/{total})"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True)
    args = parser.parse_args()
    path = Path(args.data) / "reliability" / "counts.json"
    data = {}
    if path.is_file():
        data = json.loads(path.read_text(encoding="utf-8"))
    crashes = data.get("crashes") or {}
    active = data.get("active") or {}
    explain = data.get("explain") or {}
    ask = data.get("ask") or {}
    versions = set(crashes) | set(active) | set(explain) | set(ask)
    ordered = sorted(version for version in versions if version != "unknown")
    if "unknown" in versions:
        ordered.append("unknown")
    print("版本\t崩溃/活跃设备天\t讲解失败率\t提问失败率\t前三失败原因")
    for version in ordered:
        days = len(active.get(version) or [])
        crash = int(crashes.get(version) or 0)
        explain_bucket = explain.get(version) or {}
        ask_bucket = ask.get(version) or {}
        totals = []
        for index, reason in enumerate(REASONS):
            count = int(explain_bucket.get(reason) or 0) + int(ask_bucket.get(reason) or 0)
            if count:
                totals.append((count, index, reason))
        totals.sort(key=lambda item: (-item[0], item[1]))
        top = "，".join(f"{NAMES[reason]} {count}" for count, _, reason in totals[:3]) or "-"
        print(f"{version}\t{crash}/{days}\t{rate(explain_bucket)}\t{rate(ask_bucket)}\t{top}")


if __name__ == "__main__":
    main()
