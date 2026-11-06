#!/usr/bin/env python3
"""Recompute first-word stage timings from `COACH first_word` log lines.

Read-only and offline: standard library only, no network access, no writes.
Every stage value is a difference of two offsets that belong to one record and
to the server's single monotonic clock. Percentiles are computed per stage over
that stage's own valid samples and are never subtracted from each other.
Output is JSON on stdout; see tools/dev/first_word_report.md for the contract.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

PREFIX = "COACH first_word "

# Field whitelist. Anything else in a line is counted, never echoed.
KNOWN_FIELDS = (
    "msg", "rid", "parent_msg", "attempt", "prepared_ms", "bridge_start_ms",
    "raw_first_ms", "visible_first_ms", "end_ms", "status",
    "bridge_queue_ms", "bridge_ttft_ms",
)
# Same shape the server accepts for a request ID (server/app FirstWordTrace.kt).
SAFE_ID = re.compile(r"[A-Za-z0-9_.-]{1,128}\Z")
# Offsets in the order the server observes them; each later one must not precede
# an earlier present one, otherwise the record is an out-of-order fragment.
CHAIN = ("prepared_ms", "bridge_start_ms", "raw_first_ms", "visible_first_ms", "end_ms")
BRIDGE_METRICS = ("bridge_queue_ms", "bridge_ttft_ms")
STATUSES = {"", "complete", "failed", "cancelled", "truncated"}
ATTEMPTS = {"primary", "fallback"}

BAD_KINDS = (
    "json", "not_object", "missing_key", "unsafe_id", "unknown_status",
    "non_number", "non_finite", "negative", "out_of_range", "ordering",
    "unknown_field",
)
MAX_BAD_SAMPLES = 10
# Millisecond offsets far above any live request are still valid JSON; cap them
# so a corrupt value cannot reach float conversion or the percentile samples.
MAX_OFFSET_MS = 10 ** 12


class NonFinite(ValueError):
    """Raised for the NaN/Infinity literals Python's json module accepts."""


def _reject_constant(literal):
    raise NonFinite(literal)


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _number_error(value):
    """Return a bad-kind name when a value is unusable as a millisecond offset.

    Integers are compared directly: `math.isfinite(10 ** 400)` raises
    OverflowError, so the range check must precede any float conversion.
    """
    if not _is_number(value):
        return "non_number"
    if isinstance(value, int):
        if value < 0:
            return "negative"
        return "out_of_range" if value > MAX_OFFSET_MS else None
    if not math.isfinite(value):
        return "non_finite"
    if value < 0:
        return "negative"
    return "out_of_range" if value > MAX_OFFSET_MS else None


def key_errors(obj):
    """A duplicate key is only usable when every part is a safe token or null."""
    kinds = []
    msg = obj.get("msg")
    if msg is None:
        kinds.append("missing_key")
    elif not _is_id(msg):
        kinds.append("unsafe_id")
    for name in ("rid", "parent_msg"):
        value = obj.get(name)
        if value is not None and not _is_id(value):
            kinds.append("unsafe_id")
    return kinds


def _is_id(value):
    return isinstance(value, str) and bool(SAFE_ID.match(value))


def note_position(samples, kind, file_index, line_no):
    """Record where a rejected line sits; the line content itself is never kept."""
    positions = samples.setdefault(kind, [])
    if len(positions) < MAX_BAD_SAMPLES:
        positions.append({"file": file_index, "line": line_no})


def attempt_of(record):
    """`parent_msg` is the authority; the `attempt` label is only reported."""
    return "fallback" if record["parent_msg"] is not None else "primary"


def stage_values(record):
    """Differences of one record's offsets, plus the receipt-to-visible total."""
    prepared = record["prepared_ms"]
    bridge = record["bridge_start_ms"]
    raw = record["raw_first_ms"]
    visible = record["visible_first_ms"]

    def diff(start, end):
        return None if start is None or end is None else end - start

    return {
        "prepared_ms": prepared,
        "bridge_start_gap_ms": diff(prepared, bridge),
        "bridge_first_raw_ms": diff(bridge, raw),
        "visible_after_raw_ms": diff(raw, visible),
        "visible_first_ms": visible,
    }


def validate(obj):
    """Return (record, bad_kinds, notes).

    A bad kind excludes the record; `notes` (`unknown_field`) is counted only, so a
    future server field cannot silently empty the report.
    """
    bad = set()
    notes = set()
    msg = obj.get("msg")
    rid = obj.get("rid")
    parent = obj.get("parent_msg")
    if msg is None:
        bad.add("missing_key")
    elif not _is_id(msg):
        bad.add("unsafe_id")
    if rid is not None and not _is_id(rid):
        bad.add("unsafe_id")
    if parent is not None and not _is_id(parent):
        bad.add("unsafe_id")

    status = obj.get("status")
    if not isinstance(status, str) or status not in STATUSES:
        bad.add("unknown_status")

    if any(key not in KNOWN_FIELDS for key in obj):
        notes.add("unknown_field")

    for key in CHAIN + BRIDGE_METRICS:
        value = obj.get(key)
        if value is None:
            continue
        error = _number_error(value)
        if error:
            bad.add(error)

    previous = None
    for key in CHAIN:
        value = obj.get(key)
        if value is None:
            continue
        if _number_error(value) is not None:
            # Already counted; ordering is undefined against an unusable endpoint.
            previous = None
            continue
        if previous is not None and value < previous:
            bad.add("ordering")
            break
        previous = value

    record = {key: obj.get(key) for key in KNOWN_FIELDS}
    return record, sorted(bad), sorted(notes)


def nearest_rank(sorted_values, fraction):
    """Nearest-rank percentile on one stage's own sorted sample."""
    index = math.ceil(len(sorted_values) * fraction) - 1
    return sorted_values[min(max(index, 0), len(sorted_values) - 1)]


def stats(values):
    if not values:
        return {"n": 0, "p50": None, "p95": None, "min": None, "max": None}
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "p50": nearest_rank(ordered, 0.50),
        "p95": nearest_rank(ordered, 0.95),
        "min": ordered[0],
        "max": ordered[-1],
    }


def stage_block(records):
    values = [stage_values(record) for record in records]
    return {
        name: stats([value[name] for value in values if value[name] is not None])
        for name in ("prepared_ms", "bridge_start_gap_ms", "bridge_first_raw_ms",
                     "visible_after_raw_ms")
    }


def collect(paths):
    """Read every file once. Returns (kept_lines, counters, bad, samples, files, excluded)."""
    # kept_lines: (file_index, line_no, raw_object) for structurally usable lines.
    kept = []
    counters = {"total": 0, "first_word": 0, "unrelated": 0, "blank": 0}
    bad = {kind: 0 for kind in BAD_KINDS}
    samples = {}
    files = []
    excluded = 0

    def mark(file_index, line_no, kind):
        nonlocal excluded
        bad[kind] = bad.get(kind, 0) + 1
        excluded += 1
        note_position(samples, kind, file_index, line_no)

    for file_index, path in enumerate(paths):
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        files.append({"path": str(path), "lines": len(lines)})
        counters["total"] += len(lines)
        for line_no, line in enumerate(lines, 1):
            if not line.strip():
                counters["blank"] += 1
                continue
            start = line.find(PREFIX)
            if start < 0:
                counters["unrelated"] += 1
                continue
            counters["first_word"] += 1
            try:
                obj = json.loads(line[start + len(PREFIX):], parse_constant=_reject_constant)
            except NonFinite:
                mark(file_index, line_no, "non_finite")
                continue
            except ValueError:
                mark(file_index, line_no, "json")
                continue
            if not isinstance(obj, dict):
                mark(file_index, line_no, "not_object")
                continue
            kinds = key_errors(obj)
            if kinds:
                # Checked here, before any dict keying: a list or object in a key
                # part is unhashable and would otherwise abort the whole report.
                mark(file_index, line_no, kinds[0])
                continue
            kept.append((file_index, line_no, obj))
    return kept, counters, bad, samples, files, excluded


def deduplicate(kept, samples):
    """Identical attempts collapse; conflicting attempts for one key are dropped."""
    groups = {}
    for file_index, line_no, obj in kept:
        key = (obj.get("msg"), obj.get("rid"), obj.get("parent_msg"))
        groups.setdefault(key, []).append((file_index, line_no, obj))

    unique = []
    identical_removed = 0
    conflict_keys = 0
    conflict_removed = 0
    for entries in groups.values():
        first = entries[0][2]
        if all(entry[2] == first for entry in entries[1:]):
            identical_removed += len(entries) - 1
            unique.append(entries[0])
        else:
            conflict_keys += 1
            conflict_removed += len(entries)
            for file_index, line_no, _obj in entries:
                note_position(samples, "duplicate_conflict", file_index, line_no)
    duplicates = {
        "identical_removed": identical_removed,
        "conflict_keys": conflict_keys,
        "conflict_attempts_removed": conflict_removed,
    }
    return unique, duplicates


def summarize_unique(unique, samples):
    """Split valid attempts by type and collect statistics."""
    bad = {kind: 0 for kind in BAD_KINDS}
    records = {"primary": [], "fallback": []}
    attempt_mismatch = 0
    invalid = 0
    for file_index, line_no, obj in unique:
        record, kinds, notes = validate(obj)
        for kind in kinds:
            bad[kind] += 1
            note_position(samples, kind, file_index, line_no)
        for kind in notes:
            bad[kind] += 1
            note_position(samples, kind, file_index, line_no)
        if kinds:
            invalid += 1
            continue
        declared = obj.get("attempt")
        if isinstance(declared, str) and declared in ATTEMPTS and declared != attempt_of(record):
            attempt_mismatch += 1
        records[attempt_of(record)].append(record)

    primary_msgs = {record["msg"] for record in records["primary"]}
    parent_present = sum(1 for record in records["fallback"] if record["parent_msg"] in primary_msgs)
    parents = {record["parent_msg"] for record in records["fallback"]}
    status_counts = {}
    for record in records["primary"] + records["fallback"]:
        key = record["status"] or "unknown"
        status_counts[key] = status_counts.get(key, 0) + 1

    return records, bad, {
        "unique_attempts": len(records["primary"]) + len(records["fallback"]),
        "primary": len(records["primary"]),
        "fallback": len(records["fallback"]),
        "fallback_parents": len(parents),
        "fallback_parent_present": parent_present,
        "fallback_parent_missing": len(records["fallback"]) - parent_present,
        "attempt_label_mismatch": attempt_mismatch,
        "status": dict(sorted(status_counts.items())),
    }, invalid


def slow_section(records, threshold_ms, limit):
    details = []
    counts = {"primary": 0, "fallback": 0, "unknown_visible": 0}
    for kind in ("primary", "fallback"):
        for record in records[kind]:
            visible = record["visible_first_ms"]
            if visible is None:
                counts["unknown_visible"] += 1
                continue
            if visible < threshold_ms:
                continue
            counts[kind] += 1
            values = stage_values(record)
            details.append({
                "msg": record["msg"],
                "rid": record["rid"],
                "parent_msg": record["parent_msg"],
                "attempt": kind,
                "status": record["status"],
                "visible_first_ms": visible,
                "prepared_ms": values["prepared_ms"],
                "bridge_start_gap_ms": values["bridge_start_gap_ms"],
                "bridge_first_raw_ms": values["bridge_first_raw_ms"],
                "visible_after_raw_ms": values["visible_after_raw_ms"],
            })
    details.sort(key=lambda item: (-item["visible_first_ms"], item["msg"], item["attempt"]))
    return {
        "threshold_ms": threshold_ms,
        "count": len(details),
        "primary": counts["primary"],
        "fallback": counts["fallback"],
        "unknown_visible": counts["unknown_visible"],
        "details_truncated": len(details) > limit,
        "details": details[:limit],
    }


def build_report(paths, threshold_ms, limit, source_label):
    kept, counters, bad, samples, files, collect_excluded = collect(paths)
    unique, duplicates = deduplicate(kept, samples)
    records, validation_bad, attempts, invalid = summarize_unique(unique, samples)
    for kind, value in validation_bad.items():
        bad[kind] += value

    report = {
        "tool": "first_word_report",
        "schema_version": 1,
        "input": {
            "files": files,
            "source_label": source_label,
            "source_verified": False,
            "source_note": (
                "first_word lines carry no provenance fields, so this report cannot "
                "confirm production origin; it never claims a production percentile was met"
            ),
        },
        "options": {"slow_threshold_ms": threshold_ms, "max_slow_details": limit},
        "percentiles": {
            "method": "nearest-rank",
            "definition": "index = ceil(p * n) - 1 clamped to [0, n - 1] on that stage's own sorted samples; p50 of an even sample is the lower middle value; n = 0 is null",
        },
        "lines": counters,
        "attempts": attempts,
        "duplicates": duplicates,
        "bad": dict(sorted(bad.items())),
        "excluded_attempts": collect_excluded + duplicates["conflict_attempts_removed"] + invalid,
        "stages": {
            "primary": stage_block(records["primary"]),
            "fallback": stage_block(records["fallback"]),
        },
        "visible_first_ms": {
            "primary": stats([record["visible_first_ms"] for record in records["primary"]
                              if record["visible_first_ms"] is not None]),
            "fallback": stats([record["visible_first_ms"] for record in records["fallback"]
                               if record["visible_first_ms"] is not None]),
        },
        "slow_first_word": slow_section(records, threshold_ms, limit),
        "bridge_metrics": {
            "primary": {name: stats([record[name] for record in records["primary"]
                                     if record[name] is not None]) for name in BRIDGE_METRICS},
            "fallback": {name: stats([record[name] for record in records["fallback"]
                                      if record[name] is not None]) for name in BRIDGE_METRICS},
            "note": "bridge-reported metrics use their own clocks and are never combined with same-clock stage offsets",
        },
        "notes": [
            "stage values are differences of one attempt's offsets on the server's single monotonic clock",
            "percentiles are per stage over that stage's own valid samples; percentiles are never subtracted from each other",
            "visible_first_ms is receipt-to-visible-body wait for that attempt; a fallback shares the original receipt and has no prepared offset",
            "a slow attempt is one whose visible_first_ms is greater than or equal to the threshold",
            "identical attempts with the same msg/rid/parent_msg are counted once; conflicting ones are excluded and counted under duplicates",
            "every bad kind except unknown_field excludes the attempt; unknown_field is counted and the attempt is kept",
            "bad lines are reported as counts and safe line positions only; no line content, prompt, answer, URL or unknown field is echoed",
        ],
    }
    report["bad_samples"] = dict(sorted(samples.items()))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Recompute same-clock first-word stage timings from COACH first_word lines.")
    parser.add_argument("--log", required=True, action="append", nargs="+", type=Path,
                        metavar="PATH",
                        help="log file(s) to read; repeat the flag or list paths after it; read-only")
    parser.add_argument("--slow-ms", type=float, default=5000.0, metavar="MS",
                        help="slow first-word threshold in milliseconds (default 5000)")
    parser.add_argument("--max-details", type=int, default=20, metavar="N",
                        help="maximum slow attempts listed in the report (default 20)")
    parser.add_argument("--source-label", default="unverified",
                        help="operator-supplied label for the input; never verified by the tool")
    args = parser.parse_args(argv)
    if not math.isfinite(args.slow_ms):
        parser.error("--slow-ms must be a finite number")
    if args.max_details < 0:
        parser.error("--max-details must be zero or greater")

    try:
        paths = [path for group in args.log for path in group]
        report = build_report(paths, args.slow_ms, args.max_details, args.source_label)
    except OSError as error:
        print("first_word_report: cannot read input: " + str(error), file=sys.stderr)
        return 2
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
