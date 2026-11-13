#!/usr/bin/env python3
"""Summarize allowed mainline metadata only; never emits question text."""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path, help="authorized mcq-mainline directory")
    args = parser.parse_args()
    root = args.root
    index = root / "kp-ids.json"
    weeks = json.loads(index.read_text(encoding="utf-8"))["weeks"]
    out = {"scope": "stored metadata; not a release or semantic coverage audit",
           "index_sha256": digest(index), "weeks": {}}
    for week in range(1, 12):
        key = str(week)
        ids = [x["id"] for x in weeks.get(key, [])]
        pack = root / f"W{week:02}.json"
        fact = root / f"W{week:02}-facts.md"
        row = {"catalogue_kp_count": len(ids), "pack_present": pack.is_file()}
        if pack.is_file():
            data = json.loads(pack.read_text(encoding="utf-8"))
            items = [item for section in data["sections"] for item in section["items"]]
            per = {}
            for kp in ids:
                qs = [q for q in items if q.get("kp") == kp]
                per[kp] = {"questions": len(qs), "stored_tiers": dict(sorted(collections.Counter(q.get("tier", "MISSING") for q in qs).items())),
                           "stored_key_states": dict(sorted(collections.Counter(q.get("key_state", "MISSING") for q in qs).items()))}
            row.update({"pack_sha256": digest(pack), "facts_sha256": digest(fact) if fact.is_file() else None,
                        "question_count": len(items), "stored_tiers": dict(sorted(collections.Counter(q.get("tier", "MISSING") for q in items).items())),
                        "stored_key_states": dict(sorted(collections.Counter(q.get("key_state", "MISSING") for q in items).items())),
                        "unknown_kp_questions": sum(q.get("kp") not in ids for q in items), "per_kp": per})
        out["weeks"][f"W{week:02}"] = row
    print(json.dumps(out, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
