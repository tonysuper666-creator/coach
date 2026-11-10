"""Report handler/draw/submission/pixel-observation intervals without equating them to first presentation."""

import argparse
import json
import math
from pathlib import Path


def summarize(trace, expected_counts=None):
    if trace.get("complete") is not True:
        raise ValueError("incomplete trace cannot establish a measurement chain")
    events = trace["events"]
    inputs = [(i, event) for i, event in enumerate(events) if event["kind"].startswith("input_")]
    rows = []
    for number, (start_index, action) in enumerate(inputs):
        end_index = inputs[number + 1][0] if number + 1 < len(inputs) else len(events)
        following = events[start_index + 1:end_index]
        draws = [e for e in following if e["kind"] == "target_draw_commands" and e["action"] == action["action"] and
                 e["ns"] >= action["ns"] and (action["page"] == 0 or e["page"] == action["page"])]
        if not draws:
            rows.append(dict(action=action["action"], kind=action["kind"], complete=False))
            continue
        draw = draws[0]
        submitted = next((e for e in following if e["kind"] == "frame_submitted" and
                          e["action"] == action["action"] and e["image"] == draw["image"] and e["ns"] >= draw["ns"]), None)
        observed = next((e for e in following if e["kind"] == "pixels_observed" and
                         e["page"] == draw["page"] and e["ns"] >= draw["ns"]), None)
        ready = [e for e in events[:start_index] if e["kind"] == "prefetch_decode_ready" and e["image"] == draw["image"]]
        lookup = next((e for e in following if e["kind"] in ("lookup_hit", "lookup_miss", "lookup_wait") and
                       e["action"] == action["action"] and e["page"] == draw["page"]), None)
        rows.append(dict(action=action["action"], kind=action["kind"], page=draw["page"],
                         complete=observed is not None,
                         handlerToDrawCommandsMs=(draw["ns"] - action["ns"]) / 1e6,
                         handlerToSubmissionMs=(submitted["ns"] - action["ns"]) / 1e6 if submitted else None,
                         handlerToPixelsUpperBoundMs=(observed["ns"] - action["ns"]) / 1e6 if observed else None,
                         decodedCacheHit=(lookup["kind"] == "lookup_hit") if lookup else None,
                         prefetchDecodeReadyBeforeInput=bool(ready)))
    summary = {}
    def timing_stats(times):
        times = sorted(times)
        if not times:
            return dict(count=0, p50UpperBoundMs=None, p95UpperBoundMs=None, maxUpperBoundMs=None)
        middle = len(times) // 2
        return dict(count=len(times), p50UpperBoundMs=(times[middle] if len(times) % 2 else
                    (times[middle - 1] + times[middle]) / 2),
                    p95UpperBoundMs=times[math.ceil(len(times) * .95) - 1], maxUpperBoundMs=times[-1])
    for kind in {row["kind"] for row in rows}:
        valid = [row for row in rows if row["kind"] == kind and row["complete"]]
        times = [row["handlerToPixelsUpperBoundMs"] for row in valid]
        if times:
            summary[kind] = timing_stats(times)
            summary[kind]["hits"] = timing_stats([row["handlerToPixelsUpperBoundMs"] for row in valid if row["decodedCacheHit"] is True])
            summary[kind]["misses"] = timing_stats([row["handlerToPixelsUpperBoundMs"] for row in valid if row["decodedCacheHit"] is False])
            summary[kind]["unknownLookupCount"] = sum(row["decodedCacheHit"] is None for row in valid)
            summary[kind]["prefetchReadyBeforeInputCount"] = sum(row["prefetchDecodeReadyBeforeInput"] for row in valid)
    expected = expected_counts if expected_counts is not None else {"input_next": 20, "input_previous": 20, "input_citation": 2}
    counts = {kind: sum(row["kind"] == kind for row in rows) for kind in expected}
    return dict(samples=rows, summary=summary, firstPresentLatencyVerified=False, memoryBudgetVerified=False,
                complete=all(row["complete"] for row in rows) and len(rows) == sum(expected.values()) and counts == expected,
                boundary="click handler -> recorded target draw/submission -> verified capture; physical presentation unobserved")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = summarize(json.loads(args.trace.read_text(encoding="utf-8")))
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("complete", "firstPresentLatencyVerified", "memoryBudgetVerified")}))
