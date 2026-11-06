# TON-1175 evidence and rollback transaction

These tools are for independent review and isolated rehearsal. Coverage protocol
v1 now binds a process-owned journal, live counters and a held admission fence to
the shutdown record. Legacy access files and caller-supplied `coverage=true` still
fail closed. See [the coverage contract](activity-coverage.md) for collection,
fault tests and limits. This implementation does not authorize production use;
QA-C and Architect review remain required, as do the real 900-second JFR and
five-minute original-device/learning gates below.

## Reproduce without production

Use JDK 17, Node 22+, Python 3 and locked MathJax dependencies in this checkout:

```sh
tools/dev/heavy npm ci --prefix tools/mathsvc --omit=dev --ignore-scripts --no-audit --no-fund
JAVA_HOME=$(/usr/libexec/java_home -v 17) python3 -m unittest tools.dev.test_ton1175_switch tools.dev.test_ton1175_verify tools.dev.test_first_word_report -v
tools/dev/heavy ./gradlew :server:app:installDist --offline
python3 tools/dev/check-candidate-smoke.py
git diff --check
```

The 16 transaction tests perform actual atomic file replacement with an isolated
controller. They cover startup/acceptance failures, timeout, SIGTERM, recovery
retry, unchanged old pointers, missing/stale/short idle evidence, requests before
and during stopping, repeated operations, restricted rollback materials and
retention of post-switch writes. A shortened timeout is injected in one test;
it first asserts that the real transaction supplied its remaining 300-second
budget, then verifies that a timed-out verifier's descendant cannot write later.

The 11 verifier tests include a real disposable venv with a local pinned fixture
package, actual MathJax rendering, a real JDK-generated JFR recording with a
socket read, PID mismatch, short-window rejection, an isolated HTTP server, and
SQLite/log fixtures. The original-desktop HTTP test also wires the real snapshot
and acceptance CLI into the switch transaction: a credential failure rolls back
and retains a newly inserted ledger row. Its candidate/idle/coverage verifiers
and service controller remain controlled fixtures. This does not prove live
launchd behavior, all deployed Python pins, a live 900-second JFR identity/lock
check, access-log completeness, original production phone authentication or real
learning acceptance. No production database copy is needed for these tests.

## Candidate evidence before the outage

Prepare the app and dependencies at their **final immutable release path**.
`prepare --apply` copies an already populated payload and performs no installation
or network access; an ordinary copied venv is not necessarily relocatable.
Construct/rebuild its venv at the final path before attestation. Include the
tracked bridge/mathsvc sources, `tools/dev/service-child.py`, built `app/`, npm
dependencies and `source-head.txt`. Keep the previous release runnable.

`ton1175_verify.py attest --checkout CHECKOUT --release RELEASE --node NODE
--java JAVA --output MANIFEST` requires a clean source checkout, builds with
`tools/dev/heavy ./gradlew :server:app:installDist --offline`, compares the built
distribution and tracked dependency sources with the release, imports each
locked Python package through the final venv (checking version and import origin),
and actually renders MathJax. It binds the final tree, Python/Node/Java executable
hashes and JDK/Node versions. Unbound external symlinks are rejected. Only the
venv's Python interpreter links may leave the release, and their targets are
hashed. The evidence file must be new and outside the release; it is written 0600.
Review the clean head, command result and manifest SHA independently, then pin
that SHA in the private switch configuration. A self-authored source-head file
or an unreviewed manifest is not provenance by itself.

Use the mandatory argument array:

```json
{
  "candidateVerifier": ["python3", "/reviewed/tools/dev/ton1175_verify.py", "candidate", "--manifest", "/private/build-manifest.json", "--sha256", "REVIEWED_SHA256"]
}
```

The transaction appends `RELEASE HEAD PLIST`. The verifier checks the attested
tree and external runtime hashes, requires the plist's `COACH_NODE` and
`JAVA_HOME/bin/java` to match the reviewed runtimes, and reruns Python imports
and actual MathJax rendering. Stage/switch do not build, pip-install or npm-install.
Pin and review the verifier and controller code as trusted gate executables too.

## JFR and access coverage

`idleVerifier` can be configured as `["python3", ".../ton1175_verify.py", "idle",
"--java", JAVA, "--jcmd", JCMD, "--lsof", LSOF]`; the transaction appends
`IDLE_JSON INSTALLATION`. Use JDK 17's `java` and `jcmd` for the observed JVM.
Private idle evidence retains the prototype fields `windowStart`, `windowEnd`,
`verifiedAtEpoch`, `sourceHead`, `pid`, `recording`, `clientSocketReads: 0`,
`latestClientRead: null`, and adds:

- `installationSha256`: exact installation record hash;
- `recordingSha256`: exact immutable recording hash;
- `processIdentitySha256`: SHA-256 of stripped stdout from
  `/bin/ps -p PID -o lstart= -o command=` at observation time.

The verifier requires at least 900 seconds, an end no more than 120 seconds old,
the same live installed server command, its exclusive whole-file write lock on
`dataDir/server.lock`, and unchanged installation/recording before and after
inspection. JFR's JVM PID, main class and start time must match the live lifetime
measured by `jcmd VM.uptime`; a reused PID alone cannot satisfy it.
On macOS the verifier reads the native `F_GETLK` result for the lock inode and
requires the exact JVM PID, write-lock type and whole-file range. This is a
read-only kernel query: Darwin `lsof` can report a blank lock field even for a
real Java `FileChannel` lock. Missing, shared, partial, foreign or replaced locks
fail closed. Other platforms retain the `lsof` whole-file lock check.

`Ton1175Jfr.java` inspects the recording directly through JDK's event API. It
requires enabled `SocketRead`, `ActiveSetting`, `DataLoss`, `CPULoad` and
`JVMInformation` evidence, zero socket threshold, no disabling during the window,
no data loss, and CPU heartbeat coverage bracketing the window with at most
two-second gaps. By default every positive socket read overlapping the window
counts, including hello and bridge traffic. A staged managed verifier may bind
the bridge port to the active transaction's old plist and configuration hash.
Only a non-truncated stack with the observed old `ChildServices` health frame,
no client/routing/session frame, loopback address, matching port and at most
1024 bytes can be attributed to health checks. SocketRead stack traces must be
enabled throughout. Attributed reads are grouped by start time into rounds:
reads no more than five seconds after the round's first read belong to that
round, must come from one event thread, and are capped at four reads and 1024
bytes in total. Consecutive round starts must be at least nine seconds apart,
and the number of rounds cannot exceed `ceil(seconds/10)+2`.
Two otherwise identical same-thread reads 250 ms apart cannot be distinguished
from two reads in one probe with this data; do not treat this algorithm as proof
that such a pair cannot be rapid retries. This ambiguity awaits Architect review.
All other positive reads count as client reads and reject idle; evidence JSON
cannot declare exclusions. Inspector output reports total, attributed health and
client reads, rounds, per-round maxima, minimum gap and a fixed rejection code,
including on rejection. The stage files, old plist and installation are read
without following links and rechecked around inspection. Missing
events/settings/coverage fail closed. Old recordings made without these enabled
events cannot be used. The unit fixture is deliberately short; it verifies the
real parser and proves the Python entrypoint rejects it as a production idle proof.

`logVerifier` receives `IDLE_JSON LOG...` before stopping. For protocol v1,
`accessLogs` in the switch configuration must list **all activity journal files
for the observed lifetime**; acceptance's separate `accessLogs` still names the
ordinary access files. The switcher obtains a live atomic seal on the private
control socket before invoking the controller. After stopping it appends
`--closed-seal NONCE` to the verifier arguments and requires the same process's
closed chain with that nonce, zero active/failed/rejected changes and process
exit. The held socket releases the fence on abort/EOF. A missing seal or an
unsupported journal cannot pass. Do not replace this gate with an unconditional
success command; configured gate executables are trusted review inputs.

## Five-minute acceptance

The private acceptance configuration has absolute `database`, `desktopTokenFile`
and `accessLogs` paths, existing `desktopDevice`, `phoneDevice`, `course`, `thread`
identifiers and an explicit loopback `helloUrl`, for example
`http://127.0.0.1:29751/v1/hello` in an isolated rehearsal. It must point to the
same owned service/data/logs as the reviewed switch configuration. Treat both
configurations and their paths as trusted private inputs. No defaults point at
production, and these examples do not authorize using production ports.

```json
{
  "acceptanceSnapshot": ["python3", "/reviewed/tools/dev/ton1175_verify.py", "snapshot", "--config", "/private/acceptance.json"],
  "acceptanceVerifier": ["python3", "/reviewed/tools/dev/ton1175_verify.py", "accept", "--config", "/private/acceptance.json"]
}
```

`stage` requires both commands and saves a version-2 contract; legacy staged
prototype state cannot switch without a new stage. The switcher creates a 0600
baseline **before stopping**, binding configuration, database integrity/epoch,
event high-water mark, original paired token hashes and log identity/prefixes.
The original desktop token must already match its paired record. No raw token
or learning content is written to evidence or printed.

Immediately before candidate startup, the transaction starts one fixed
300-second budget. Startup consumes that budget. The journal remains `validating`
while the foreground acceptance child runs; `switched` is committed only after
it succeeds within the remaining time and pointers still match. Acceptance
requires:

- HTTP 200 and unchanged epoch from `/v1/hello` using the original desktop token,
  at entry and before success; credentials go only to the configured numeric
  loopback address, with no redirects;
- a new authenticated original-phone HTTP 200 in the observed access file,
  with unchanged paired token hash; new pairing, another device, WS rows,
  pre-switch rows and non-200 results cannot substitute;
- a new completed nonempty assistant message joined to its
  `explanation_delivered` event in the selected course/thread;
- three distinct new attempts from one group in that thread, each with ordered
  posed, original-device answer and graded events in the acceptance window.

The verifier only sends desktop hello requests. Phone activity and learning are
observed passively; it never pairs a device, sends phone traffic or invokes a
tutor. Missing evidence waits until the deadline and fails. Token/epoch changes,
log rotation/truncation or invalid evidence fail immediately. SQLite is opened
read-only; Python fetches IDs/counts, not answers or message content. A positive
access row can prove an observed success even though absence cannot prove idle.

Acceptance can finish early once all required proofs exist; this is a
**within-five-minutes acceptance deadline**, not a further five-minute monitor
after successful acceptance. Phone offline or no authorized learning activity
means acceptance cannot succeed. Production remains blocked until these real
requirements and the access-coverage prerequisite are satisfied.

## Failure and recovery

Use the [bounded foreground supervisor](ton1175-supervisor.md) for staged
`switch`, `rollback` and `recover`. It owns command groups across worker death,
preserves the 300-second acceptance reserve and adds separate rollback/recovery
budgets. The direct entry below remains a low-level transaction; it does not
clean up its children after its own SIGKILL.

Observation end to seal is at most 120 seconds. Background jobs can start in
that gap and be cancelled at stop; SQLite transactions protect committed data.

`ton1175_switch.py prepare|stage|switch|rollback|recover --config CONFIG [--apply]`
defaults to read-only preflight: no state creation, service control or pointer
replacement. Explicit `--apply` is required for side effects. The configuration
retains the prototype's absolute payload/release/state/plist/installation and
idle/log paths, expected old head/hash and candidate tree/head hashes. The
controller accepts `stop` and `start` and must control only its owned instance;
a successful controller return must mean the requested state is established.

After stopping, any failure in pointer replacement, startup or acceptance
(nonzero, timeout, SIGTERM/SIGHUP or KeyboardInterrupt) attempts to stop the
candidate, restore the retained old plist/installation atomically, and start the
old release. Timed-out/interrupted child process groups are killed and reaped.
**The current database and all post-switch writes are retained.** The snapshot
command above is identity evidence, not a database backup or restore mechanism.
Separate approved backup policy still applies.

If rollback fails, `recovery_required` and the 0700 directory/0600 retained files
allow `recover --config CONFIG --apply` after diagnosing the controller. Inspect
`journal.json` before any new operation. Power loss, host failure and SIGKILL of
the cleanup owner cannot execute rollback; `switching`/`validating` journals are explicitly
recoverable by `recover`. Unknown externally modified pointers are refused for
manual investigation. Do not leave the transaction running in the background.
Independent QA and Architect review are required before any production handoff.
