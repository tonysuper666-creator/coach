# Same-request first-word report

`tools/dev/first_word_report.py` turns the `COACH first_word` lines added by
TON-1175 into a reproducible, machine-readable stage report. It reads log files
only: no network, no service, no production data source, and it never rewrites
its input. Standard library only.

```sh
python3 tools/dev/first_word_report.py --log <path> [--log <path> ...] \
    [--slow-ms 5000] [--max-details 20] [--source-label unverified]
python3 -m unittest tools.dev.test_first_word_report -v
```

Exit code `0` prints the report on stdout. Exit code `2` means usage error or an
unreadable input file (message on stderr, no report).

## What it does not do

- It makes no attribution. The offsets are observation points on one clock; a
  long interval is not proof of a cause inside it.
- It never subtracts percentiles of different stages or samples, and never
  subtracts bridge or client clocks from server offsets. `bridge_queue_ms` and
  `bridge_ttft_ms` are reported as their own block for that reason.
- It does not decide provenance. See "Provenance" below.
- It does not print prompt, answer, chunk, URL, exception or any unknown field.

## Input

One JSON object per line, written by `server/app/FIRST_WORD_DIAGNOSTICS.md`'s
`FirstWordTrace.finish`. A line may carry a log prefix (a timestamp); everything
from `COACH first_word ` to the end of the line is parsed as JSON.

| field | meaning |
|---|---|
| `msg` | message id of this attempt; required, `[A-Za-z0-9_.-]{1,128}` |
| `rid` | bridge request id, nullable, same token form |
| `parent_msg` | the primary attempt this fallback belongs to; null for a primary |
| `attempt` | informational label; the report classifies by `parent_msg` and counts disagreements |
| `prepared_ms` | receipt → preparation |
| `bridge_start_ms` | receipt → bridge invocation |
| `raw_first_ms` | receipt → first raw body chunk |
| `visible_first_ms` | receipt → first visible body text |
| `end_ms` | receipt → attempt end |
| `status` | `complete`, `failed`, `cancelled`, `truncated`, or empty |
| `bridge_queue_ms`, `bridge_ttft_ms` | bridge-reported metrics, own clock; optional |

A missing event is `null` (cancellation, failure, cache hit, empty output).
`null` is not an error: the affected stage simply loses that sample.

## Stages (primary and fallback reported separately)

| stage | value | reading |
|---|---|---|
| `prepared_ms` | `prepared_ms` | receipt to preparation |
| `bridge_start_gap_ms` | `bridge_start_ms - prepared_ms` | post-preparation scheduling and request construction |
| `bridge_first_raw_ms` | `raw_first_ms - bridge_start_ms` | bridge invocation to first raw body |
| `visible_after_raw_ms` | `visible_first_ms - raw_first_ms` | filtering and waiting for visible body |
| `visible_first_ms` (own block) | `visible_first_ms` | receipt to visible body for that attempt |

A difference is only computed when both endpoints exist, and every difference
uses one record's offsets only. A fallback repeats no preparation (its
`prepared_ms` is null) and shares the original receipt, so its
`visible_first_ms` includes the primary's elapsed time; its non-streaming bridge
response makes `raw_first_ms` a whole-body observation, not a first token.

## Percentiles

Nearest-rank on that stage's own sorted samples: `index = ceil(p * n) - 1`,
clamped to `[0, n - 1]`. An even-sized sample's `p50` is the lower middle value;
`n = 0` reports `null` for `p50`, `p95`, `min` and `max`. No interpolation, no
combination across stages.

## Duplicates and conflicts

Each attempt is keyed by `(msg, rid, parent_msg)`. Identical lines for one key
collapse to one attempt (`duplicates.identical_removed`). Lines that disagree
for the same key are *all* dropped and counted (`duplicates.conflict_keys`,
`duplicates.conflict_attempts_removed`); the tool never picks one. A line whose
`msg` is missing or not a safe token cannot be keyed and is excluded earlier as
`bad.missing_key` / `bad.unsafe_id`.

## Validation

A record is excluded when any of these hold, counted in `bad`:

`json` (unparsable), `not_object`, `missing_key`, `unsafe_id`, `unknown_status`,
`non_number` (offset is text or a boolean), `non_finite` (`NaN`/`Infinity`),
`negative` (an offset below zero), `ordering` (a present endpoint precedes an
earlier present one).

`unknown_field` is counted but the record is kept: a future server field must
not silently empty the report. Field names outside the whitelist and all values
are never echoed.

`bad_samples` maps each kind to at most ten `{file, line}` positions. No line
content is included.

## Provenance

`COACH first_word` lines contain no traffic or source fields, so this tool cannot
confirm that an input came from production. `input.source_verified` is always
`false`; `--source-label` only records the operator's claim. A report from this
tool is never evidence that a production percentile target was met — joining
provenance stays with `tools/bridge/production_stats.py`, which this tool does
not replace or change.

## Report shape

```json
{
  "tool": "first_word_report",
  "input": {"files": [{"path": "...", "lines": 23}], "source_verified": false},
  "lines": {"total": 23, "first_word": 20, "unrelated": 2, "blank": 1},
  "attempts": {"primary": 8, "fallback": 1, "fallback_parent_missing": 0},
  "duplicates": {"identical_removed": 1, "conflict_keys": 1, "conflict_attempts_removed": 2},
  "bad": {"json": 1, "negative": 1, "ordering": 1, "...": 0},
  "excluded_attempts": 10,
  "stages": {
    "primary": {"prepared_ms": {"n": 7, "p50": 50, "p95": 1000, "min": 0, "max": 1000}, "...": {}},
    "fallback": {"prepared_ms": {"n": 0, "p50": null, "p95": null, "min": null, "max": null}, "...": {}}
  },
  "visible_first_ms": {"primary": {"n": 6, "p50": 820, "p95": 7000, "min": 40, "max": 7000}},
  "slow_first_word": {"threshold_ms": 5000, "count": 3, "primary": 2, "fallback": 1,
                      "unknown_visible": 2, "details": [{"msg": "...", "visible_first_ms": 7000, "...": null}]},
  "bridge_metrics": {"primary": {"bridge_ttft_ms": {"n": 1, "p50": 42}}, "note": "own clocks; never combined"},
  "bad_samples": {"json": [{"file": 0, "line": 13}]}
}
```

A slow attempt has `visible_first_ms >= threshold`. `slow_first_word.details`
lists at most `--max-details` attempts (largest first) as safe ids plus the four
stage values; `details_truncated` says whether more exist. `unknown_visible`
counts attempts whose `visible_first_ms` is null, which can never be called slow.

## Fixture

`tools/dev/fixtures/first_word/mixed.log` is a synthetic log: an ordinary
primary, a zero-gap primary, a slow-filtering primary, a cache hit, a cancelled
attempt, a fallback with a missing preparation, an attempt carrying unknown
fields, threshold-boundary attempts, an exact duplicate, a conflicting pair, and
one line for each bad shape. It contains no real user text. The tests assert its
exact counts, so changes to the tool's classification show up there.
