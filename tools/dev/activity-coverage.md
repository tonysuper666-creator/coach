# Process-owned activity coverage v1 (TON-1191)

This contract adds evidence for the TON-1175 gate. It grants no permission to
stop or switch production. QA-C must independently reproduce the isolated
results, then Architect reviews the publication safety boundary. Existing JFR,
900-second idle, original paired devices and five-minute lecture/three-attempt
acceptance requirements remain in force.

## Lifecycle and evidence

One `ActivityCoverage` owner serves both application listeners. Its monitor
serializes admission, completion, counters, durable writes, snapshots and seals.
HTTP starts in the application plugin before routing/authentication and ends
only at `ResponseSent`. Unknown routes, unauthorized requests and hello probes
count too. An exception/cancellation without that completion remains active and
refuses the gate. A WS upgrade also has a separate ticket spanning the whole
`/v1/ws` handler, including its sender/tutor/revocation cleanup. Even a dormant
established socket prevents idle; disconnect it and start a fresh observation.
A socket disconnected during a reply does not establish quiet while the chat
session or pool waiter still exists. No learning, pairing or queue wire format
changes. The existing JFR gate independently observes socket/bridge traffic.

Each lifetime uses a random UUID plus OS PID and OS process-start instant. Every
row includes a strict increasing sequence, SHA-256 of the preceding raw UTF-8
row (excluding newline), wall and monotonic time, cumulative admitted/rejected
counters, active count and sticky write-failure count. Admission IDs are
monotonic and completion IDs must match. Only `http`/`ws` kind metadata is added;
there are no devices, tokens, request paths/URLs, bodies or learning data in this
journal/control protocol. Ordinary access logging remains for positive learning
acceptance; its write/close errors also make coverage permanently unhealthy.

The owner appends each JSON row plus newline to
`activity-LIFETIME-UTC_DATE.log`, forces the file and directory before advancing
the in-memory tail hash, and closes the descriptor. The hash/sequence continues
across UTC-day rotation. These files have no automatic retention deletion:
retain all files from lifetime-open through closure for verification, archive or
remove only after that lifetime is no longer needed. Any IO exception increments
a failure counter which cannot be reset in that lifetime. Missing/duplicate rows,
partial writes, omitted rotation files, prefix/tail deletion and broken links
fail against live high-water marks. Readability or an empty file is insufficient.
Synchronous file/directory forcing adds disk latency to admission/completion;
this conservative durability choice needs independent performance review before release.

Control uses a Unix-domain socket under an owned 0700 directory, socket mode
0600. Default: `state/activity-LIFETIME.sock`; `COACH_ACTIVITY_SOCKET` can explicitly
choose another private path. A preexisting explicit socket path is never unlinked
at startup; diagnose ownership before removal. A control startup failure marks
coverage unhealthy and keeps the learning service available, with a diagnostic.
Long Unix socket paths therefore refuse the release gate without preventing use. No network control endpoint or
learner authentication token is introduced. This assumes the local account,
reviewed binaries, configuration and evidence files are trusted; it is not a
signature or protection against that account rewriting both program and proof.
Linux/macOS filesystem fsync semantics and normal graceful JVM shutdown apply.
Power loss/crash without a sealed closure refuses reuse of the evidence.

## Observation and stop barrier

1. With zero active work, collect a live `start` snapshot, then begin the real JFR
   window. Complete the real >=900-second JFR window, then collect `end`. Both
   snapshots must be healthy, unfenced, from the same lifetime, have unchanged
   ingress/rejected counters, bracket the JFR window and span >=900 monotonic
   seconds with consistent wall time. End must be <=120 seconds old.
2. The verifier obtains another live snapshot over the owned socket. It validates
   the entire durable chain and both checkpoints against that independent tail.
   Activity after `end`, even if already completed, changes the ingress counter
   and refuses the proof. Replaced JVM/socket identity also refuses it.
3. Immediately before controller stop, the switcher asks for a `seal` using the
   expected end counters. Under the same monitor, the owner checks quiet/healthy
   state and unchanged counters, disables admission and durably records a random
   transaction nonce. The switcher keeps this connection open and validates the
   sealed chain. Any failure here aborts **before controller stop**; EOF releases
   the fence so the original service can continue. Each admitted HTTP/WS call
   raced before the seal prevents it. Calls arriving after sealing receive 503
   (or WS close 1013), increment rejection and invalidate the final proof.
4. Only then may the controller stop its owned instance. Main stops both
   listeners and workers before closing the observation owner. It forces a
   `closed` row carrying the held nonce. The post-stop verifier requires the same
   lifetime, that exact nonce, a complete reconciled chain, no new admitted or
   rejected calls, zero active/failures and absence of the observed PID. A reused
   PID safely refuses. No closure, wrong nonce, lost close write, unfinished
   activity or restart cannot pass. Failures after stop use the existing rollback
   transaction to restore the old release without restoring its database.

The socket connection is the fence lifetime. EOF on checker exit releases it;
a checker frozen with an open connection needs the
[foreground supervisor](ton1175-supervisor.md) or operator cancellation. The
same local account already controls the service. Only one control connection is
served at a time. Do not run another checkpoint client during a held stop fence.
Unadmitted TCP bytes in OS queues do not count as application work; shutdown
closes those connections without executing routes. Background supply/maintenance
semantics are unchanged; this is an application ingress/completion contract,
not evidence that a queue is empty. Their network use remains subject to JFR and
review. The post-switch learning gate is still independently mandatory.

## Private collection (reviewed isolated instance only)

No command below chooses a service by default. Substitute explicit private
paths for an authorized isolated service. Do not copy a production database or
use real samples to run these tests.

```sh
python3 tools/dev/activity_coverage.py start --socket /private/state/activity-LIFETIME.sock --output /private/start.json
# Collect the existing >=900-second JFR/identity evidence into /private/idle.json.
python3 tools/dev/activity_coverage.py finish --start /private/start.json --idle /private/idle.json --output /private/covered-idle.json
python3 tools/dev/ton1175_verify.py logs /private/covered-idle.json /private/logs/activity-LIFETIME-DAY.log
```

Outputs are new 0600 files. Pin the reviewed collector/verifier/controller along
with candidate provenance. Set switch `idleEvidence` to the covered evidence,
`logVerifier` to `["python3", "/reviewed/tools/dev/ton1175_verify.py", "logs"]`
and switch `accessLogs` to every current lifetime journal file. Rotation between
file enumeration and closure can conservatively refuse and roll back; collect a
new observation/file set rather than omit the new file. Acceptance configuration
continues to use ordinary access logs, separately from this coverage journal.

## Isolated verification

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.ActivityCoverageProtocolTest --tests com.coach.server.UsageObservationTest --offline
JAVA_HOME=$(/usr/libexec/java_home -v 17) python3 -m unittest tools.dev.test_ton1175_switch tools.dev.test_ton1175_verify tools.dev.test_first_word_report -v
git diff --check
```

The Gradle protocol test launches the Python verifier against disposable JVMs
with the real Ktor routes, loopback HTTP, established WS, Unix sockets and private
synthetic ledgers. Faults include journal/access write failures, missing middle
and tail rows, omitted/broken rotation, unfinished WS/detached work, replaced
process identity, observation-to-seal requests, fence-time requests and abrupt
exit. Pre-stop refusals run the actual switch command with the shipped log verifier,
assert no controller stop or pointer change, and assert the original server still
returns HTTP 200. A request injected between preflight and seal also refuses
before stop. Candidate/JFR/learning gates are controlled fixtures in these
transaction tests; the closure proof and pointer transaction run for real. The
positive case completes HTTP/WS, rotates, seals, gracefully stops the disposable
JVM and verifies the persisted closed chain. The test-only clock advances 901
seconds; it does **not** claim a real 900-second JFR run or production acceptance.
Another case starts the installed Main with synthetic private state, holds its
seal and sends SIGTERM, then checks the real shutdown hook's durable closed row.
Production has no shortened duration option. Independent review must retain this
boundary and inspect real lifecycle integration before use.
