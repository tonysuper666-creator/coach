#!/usr/bin/env python3
"""Content-free first-word stats: join production server msg.end to verified bridge calls by rid.
Historical lines without provenance stay unclassified. Never opens a network connection.
"""
import argparse
import json
import math
from pathlib import Path


def fields(line):
    return dict(part.split("=", 1) for part in line.split() if "=" in part)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-log", required=True, type=Path)
    parser.add_argument("--bridge-log", required=True, type=Path)
    args = parser.parse_args()
    server = [fields(line) for line in args.server_log.read_text().splitlines() if "COACH msg.end " in line]
    bridge = [fields(line) for line in args.bridge_log.read_text().splitlines() if "rid=" in line]
    verified = {row.get("rid"): row for row in bridge if row.get("traffic") == "user" and row.get("source") == "production"}
    matched = [row for row in server if row.get("rid") not in (None, "-") and row.get("rid") in verified
               and row.get("traffic") == "user" and row.get("source") == "production"]
    times = sorted(float(row["ttft"].removesuffix("ms")) for row in matched
                   if row.get("ttft", "").removesuffix("ms").replace(".", "", 1).isdigit())
    def percentile(p):
        return times[max(0, math.ceil(len(times)*p)-1)] if times else None
    print(json.dumps({"scope":"verified_production_only", "matched_server_ends":len(matched),
                      "first_word_samples":len(times), "p50_ms":percentile(.5), "p95_ms":percentile(.95),
                      "unverified_bridge_rows":sum(row.get("traffic") != "user" or row.get("source") != "production" for row in bridge)},ensure_ascii=False))

if __name__ == "__main__":
    main()
