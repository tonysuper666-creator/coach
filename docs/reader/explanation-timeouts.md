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
An invalid JSON prefix (including fences, leading commentary and invalid escapes) stops immediately
with outcome `format` and the existing missing-verifiable-page-evidence message. Final validation
failures use the same outcome. Format failures are included in requests but never in timeout counts.

Android and Mac share `CourseHttp.explainSelection`: its HTTP request limit is 195000 ms, allowing
the default server cap of 180000 ms plus 15000 ms to deliver the final JSON. Both defaults live in
`ReaderTimeoutPolicy`; shared-client tests inspect the actual request capability, and a server test
checks it against the server default. Coordinate any `COACH_READER_TOTAL_TIMEOUT_MS` override with
the compiled client limit and bridge ceilings. A client transport timeout reports waiting timed out.

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
tools/dev/heavy --local ./gradlew :shared:client:jvmTest :server:app:testClasses --console=plain
tools/dev/heavy --local python3 tools/dev/check-reader-timeouts.py \
  --bridge-python /path/to/bridge-venv/bin/python --output /path/to/new-evidence-directory
```

The Python environment needs `tools/bridge/requirements.txt`. The replay launches isolated ports
and data, follows HTTP → real reader → real BridgeClient → real subscription bridge → synthetic
CLI, and runs first-content absence, stalled body, total cap, active cancel, socket disconnect,
invalid JSON before/after body starts, and 121 body events spanning at least 120 real seconds.
The long and format cases go through the real shared `CourseHttp(CIO)` client; other cases use an
HTTP fault driver to control cancellation and socket closure. No subscription calls or production writes.
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
request denominator, timeout count, rate, stage counts and window together; list `format`, `cancelled`
and `disconnected` separately from `timeout_*`. Zero recorded samples
means missing instrumentation or no traffic, not proof of zero historical timeouts. Legacy
`ai_calls` has neither a usable time window nor reader-timeout attribution; its cumulative counts
are supplementary only.

Before deployment, retain a read-only aggregate baseline and deployed SHA. QA must independently
repeat the replay; production deployment also needs the current team's separate critical review,
green required checks and the existing release/rollback procedure. Release scope is the existing
Mac Coach service **and Android and Mac desktop clients**. Old clients stop at 95 seconds. Record
server release SHA and deployment UTC, Android/Mac client build versions and actual installation
UTC separately; verify the installed clients use the new limit before opening the observation window.
Keep previous client artifacts and record the platform's actual reinstall/rollback commands at release.
Coordinate ReaderExplanation/Mainline call sites with TON-1465/TON-1466, and re-run
`ReaderExplanationTest` on the integrated result if #142 merges later.

Install the server with `python3 tools/dev/coach-service.py install`; it copies an immutable release
and restores the prior installation on failure. Never run production from a mutable workspace
`installDist`. Before installing, retain both installation files (current installation was
`releases/ton1441-main-5ee14d69` at review; re-read it at deployment):

```sh
backup="$HOME/Coach/service/rollback-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup"
cp "$HOME/Library/LaunchAgents/local.coach.server.plist" "$backup/local.coach.server.plist"
cp "$HOME/Coach/service/installation.json" "$backup/installation.json"
```

Record the concrete backup directory in the deployment receipt. To roll back, restore both files
from that directory and reload launchd (retain the old release directory until acceptance):

```sh
cp "$backup/local.coach.server.plist" "$HOME/Library/LaunchAgents/local.coach.server.plist"
cp "$backup/installation.json" "$HOME/Coach/service/installation.json"
launchctl bootout "gui/$UID/local.coach.server"
launchctl bootstrap "gui/$UID" "$HOME/Library/LaunchAgents/local.coach.server.plist"
```

There is no installer rollback subcommand. The installer does not forward `COACH_READER_*`, so
this deployment uses defaults. Verify service readiness after install and rollback; verify all three
deadline stages with synthetic test traffic excluded from production statistics. Also make one
test-marked real subscription CLI request and record `first_ms` against the 30000 ms first-body
limit, including queue/source preparation. Do not point learner traffic at the replay's bridge.

The observation window starts only after successful server deployment **and actual installation of
the updated Android/Mac clients** (use the latest completion UTC). Retain an additional server-only
window if useful, but do not pool old-client traffic into the updated-client comparison. Create one
issue wakeup at the updated-client window start plus seven days, assigned to Builder. At that wakeup collect the seven-day production window,
compare only compatible populations, and explicitly report absent baseline or insufficient samples.
The pre-deployment read-only baseline currently has zero `READER_SELECTION` rows and no compatible
instrumented population: report post-rollout figures alone unless a compatible baseline becomes
available, including zero or small sample sizes explicitly. Do not schedule the week from PR creation, merge, server-only installation or a test run. Code acceptance, deployment,
production checks and seven-day observation are separate evidence; this document claims none of
the latter steps have occurred.
