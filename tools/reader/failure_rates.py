#!/usr/bin/env python3
"""Report rolling 24-hour HTTP outcomes from the service's content-free access logs."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys


ROUTES = {"/v1/library/explain": "explain", "/v1/library/collection/ask": "ask"}
OUTCOMES = ("200", "429", "5xx", "abandoned")


def read_counts(directory, end):
    start = end - timedelta(hours=24)
    counts = defaultdict(Counter)
    missing_days = []
    found = False
    for day in sorted({start.date(), end.date()}):
        path = directory / f"access-{day}.log"
        if not path.exists():
            missing_days.append(day.isoformat())
            continue
        found = True
        with path.open(encoding="utf-8") as rows:
            for line in rows:
                row = json.loads(line)
                route = ROUTES.get(row.get("route"))
                if route is None or row.get("method") != "POST":
                    continue
                at = datetime.fromisoformat(row["at"].replace("Z", "+00:00")).astimezone(timezone.utc)
                if not start <= at < end:
                    continue
                status = row["status"]
                if 500 <= status <= 599:
                    outcome = "5xx"
                elif status == 499:
                    outcome = "abandoned"
                else:
                    outcome = str(status)
                if outcome in OUTCOMES:
                    counts[at.strftime("%Y-%m-%dT%H:00Z"), route][outcome] += 1
    if not found:
        raise FileNotFoundError
    if missing_days:
        print("Access log missing for UTC date " + ", ".join(missing_days) + "; totals cover available records.", file=sys.stderr)
    return counts


def report(counts):
    print("UTC-hour route 200 429 5xx abandoned failure-rate")
    def emit(period, route, values):
        total = sum(values.values())
        rate = f"{(total - values['200']) / total:.2%}" if total else "n/a"
        print(period, route, *(values[key] for key in OUTCOMES), rate)

    totals = defaultdict(Counter)
    for (hour, route), values in sorted(counts.items()):
        emit(hour, route, values)
        totals[route].update(values)
    for route in ROUTES.values():
        emit("24h", route, totals[route])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--logs", type=Path, default=Path(os.environ.get("COACH_LOG_DIR", Path.home() / "Coach/logs")))
    parser.add_argument("--end", help="exclusive end of window (ISO 8601 with UTC offset)")
    args = parser.parse_args()
    try:
        end = datetime.fromisoformat(args.end.replace("Z", "+00:00")) if args.end else datetime.now(timezone.utc)
        if end.tzinfo is None:
            parser.error("--end requires a UTC offset")
        counts = read_counts(args.logs, end.astimezone(timezone.utc))
    except FileNotFoundError:
        sys.exit("No access logs in this 24-hour window.")
    except (ValueError, OSError, TypeError, KeyError):
        sys.exit("Could not read access records for this window.")
    report(counts)


if __name__ == "__main__":
    main()
