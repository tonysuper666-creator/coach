# Reader explanation deadlines

TON-1464 replaces the single 90-second cutoff. The server still returns one checked JSON answer;
only its internal bridge connection streams. No incomplete answer is displayed. Existing source
visibility and per-paragraph citation checks remain authoritative.

| Environment variable | Default | Meaning |
| --- | ---: | --- |
| `COACH_READER_FIRST_CONTENT_TIMEOUT_MS` | 30000 ms | Time from admission to first actual explanation character |
| `COACH_READER_CONTENT_GAP_TIMEOUT_MS` | 30000 ms | Longest interval without another explanation character |
| `COACH_READER_TOTAL_TIMEOUT_MS` | 180000 ms | Absolute cap from admission, regardless of progress |

Each setting accepts an integer from 1 through 86400000 milliseconds; invalid values fail startup.
The monotonic clock includes the source sandbox, bridge queue, CLI startup and HTTP headers.
Only decoded non-whitespace content in `paragraphs[*].text` advances the content clock. JSON keys,
anchors, whitespace, metadata, heartbeats and empty deltas do not. Fragmented strings and Unicode
escapes are handled without storing explanation text in the timer. Late content cannot revive an
expired deadline. The final JSON and citation gate still decides whether an answer is usable.

Bridge transport/CLI safeguards remain additional ceilings. When increasing reader settings,
check `BridgeConfig.requestTimeout` (240 seconds), bridge first-output, stall and total settings;
raising a reader setting alone does not raise those safeguards. Defaults support 120 seconds of
continued body output. The response distinguishes no beginning, stalled body and absolute cap in
plain Chinese, returns no sources on failure, and leaves source pages and reading state untouched.

Timeout/cancel closes the bridge stream before waiting for the worker to finish. Bridge cancellation
reaps the CLI process group. Source sandbox processes are also stopped. Netty connection closure
cancels the explanation coroutine; the listener is removed when that request ends. Other server
engines retain their coroutine cancellation behavior.

## Local evidence

Run on Mac, using only a disposable synthetic course and synthetic CLI:

```sh
tools/dev/heavy --local ./gradlew :server:app:test :server:ai:test --console=plain
tools/dev/heavy --local python3 tools/dev/check-reader-timeouts.py \
  --bridge-python /path/to/bridge-venv/bin/python --output /path/to/new-evidence-directory
```

The Python environment needs `tools/bridge/requirements.txt`. The replay launches isolated ports
and data, follows HTTP → real reader → real BridgeClient → real subscription bridge → synthetic
CLI, and runs first-content absence, stalled body, total cap, active cancel, socket disconnect,
and 121 body events spanning at least 120 real seconds. No subscription calls or production writes.
The CLI also starts a child in its process group; both PIDs must exit. Each case records configured
thresholds, monotonic first/last body offsets, server first/last/end offsets, user error, elapsed
round trip and remaining processes. Source text and ledger events must stay unchanged.
Do not rebuild `installDist` while replaying: the JVM loads classes lazily from that installation.
A report from a failed replay is partial evidence, never a passing result.

## Production comparison and handoff

`COACH reader.end` contains a wall-clock endpoint for window selection, monotonic durations,
limits, traffic class and outcome. It contains no quote, prompt, source, answer, course ID or token.
Use `tools/dev/reader-timeout-stats.py --help` to summarize a half-open ISO8601 window. The
population is admitted explanation requests with usable evidence; it includes cancellations and
disconnections, excludes early rejected/empty-evidence requests, and excludes test traffic. Report
request denominator, timeout count, rate, stage counts and window together. Zero recorded samples
means missing instrumentation or no traffic, not proof of zero historical timeouts. Legacy
`ai_calls` has neither a usable time window nor reader-timeout attribution; its cumulative counts
are supplementary only.

Before deployment, retain a read-only aggregate baseline and deployed SHA. QA must independently
repeat the replay; production deployment also needs the current team's separate critical review,
green required checks and the existing release/rollback procedure. Deploy only to the existing
Mac Coach service, record previous/new release and rollback command, then verify stage behavior
with synthetic traffic excluded from the production population. Do not point learner traffic at
this replay's bridge. Coordinate ReaderExplanation/Mainline call sites with TON-1465/TON-1466.

After successful deployment, record its actual UTC time and create one issue wakeup at that time
plus seven days, assigned to Builder. At that wakeup collect the seven-day production window,
compare only compatible populations, and explicitly report absent baseline or insufficient samples.
Do not schedule the week from PR creation, merge, or a test run. Code acceptance, deployment,
production checks and seven-day observation are separate evidence; this document claims none of
the latter steps have occurred.
