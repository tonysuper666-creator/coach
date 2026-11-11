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
`IDLE_JSON INSTALLATION`. Use JDK 17's `java` for the observed JVM. `--jcmd` is
retained for controller compatibility but is no longer executed for lifetime binding.
Private idle evidence retains the prototype fields `windowStart`, `windowEnd`,
`verifiedAtEpoch`, `sourceHead`, `pid`, `recording`, `clientSocketReads: 0`,
`latestClientRead: null`, and adds:

- `installationSha256`: exact installation record hash;
- `recordingSha256`: exact immutable recording hash;
- `processIdentitySha256`: SHA-256 of stripped stdout from
  `env LC_ALL=C TZ=UTC /bin/ps -p PID -o lstart= -o command=` at observation time;
- `bridgeIdentitySha256`: SHA-256 of the NUL-joined byte values `bridge PID`,
  `guard PID`, stripped bridge ps stdout, stripped guard ps stdout. Both ps calls
  use the same `LC_ALL=C TZ=UTC` and `lstart=/command=` format. Capture this at
  observation time. The PIDs come from the installed `runDir/bridge.json`. This
  binding is required only when positive reads need a health-poll exemption.

The verifier requires at least 900 seconds, an end no more than 120 seconds old,
the same live installed server command, its exclusive whole-file write lock on
`dataDir/server.lock`, and unchanged installation/recording before and after
inspection. JFR's JVM PID, main class and start time must match the kernel process
start from `ps lstart` (UTC, C locale): `0 <= jvmStart - lstart < 5` seconds.
`lstart` truncates the fork time to seconds and JFR reports VM initialization
later. The bounded one-sided interval accounts for both effects and rejects a
VM predating the process. Host sleep does not change that start time. The full
start/command hash is still fixed before and after verification; PID alone is
never sufficient.
On macOS the verifier reads the native `F_GETLK` result for the lock inode and
requires the exact JVM PID, write-lock type and whole-file range. This is a
read-only kernel query: Darwin `lsof` can report a blank lock field even for a
real Java `FileChannel` lock. Missing, shared, partial, foreign or replaced locks
fail closed. Other platforms retain the `lsof` whole-file lock check.

`Ton1175Jfr.java` inspects the recording directly through JDK's event API. It
requires enabled `SocketRead`, `ActiveSetting`, `DataLoss`, `CPULoad` and
`JVMInformation` evidence, zero socket threshold, no disabling during the window,
no data loss, and CPU heartbeat coverage bracketing the window with at most
two-second gaps. Every positive socket read overlapping the window is retained,
including Unix/control reads. The only exclusion is a complete sequence of managed
bridge health responses: the JFR address must be exactly `127.0.0.1`, remote port
8791 (the installed ChildServices invariant), and a non-truncated recorded stack
must contain `com.coach.server.ChildServices$supervise$1.invokeSuspend`. Enable
`jdk.SocketRead` stack traces when collecting; missing stacks cannot earn an
exclusion. When the window has positive reads, the inspector requires the
`stackTrace=true` setting at window start and no disabling inside the window.
It explicitly rejects missing coverage as
`JFR evidence rejected: SocketRead stackTrace setting missing or disabled`,
before attempting health classification. The Python gate preserves this exact
fixed reason, while still hiding arbitrary subprocess error output. A caller's zero count or whitelist
is never consumed.

For the next cutover, collect a **new** recording using the checked-in
`tools/dev/ton1175-idle.jfc` (including `SocketRead` enabled, zero threshold and
stackTrace true), for example with the authorized collector's existing
`jcmd PID JFR.start name=ton1175 settings=/absolute/path/tools/dev/ton1175-idle.jfc`.
The launchd rehearsal copies this same configuration and captures its JVM ps
identity with `LC_ALL=C TZ=UTC`. Earlier positive-read recordings made without
stacks, including TON-1441's old recording, cannot prove the new exemption;
do not retrofit settings or remove reads from them. Real managed-health stacks
must also be checked for truncation in that new recording before a cutover.

If the inspector proves **zero** positive reads, the original strict idle path
requires no health polls, runDir or bridge identity. All JFR coverage/settings,
900-second, freshness, JVM lifetime/identity, lock and file-hash checks still
apply. The unmanaged launchd rehearsal uses this path; its random bridge URL
does not grant any read exemption.

For a health exemption the verifier additionally binds the observed bridge/guard identities to the
installed scripts, their kernel parent chain back to this JVM, and the exact
loopback listener owner twice during verification. Both children must predate
the window. `runDir/bridge.json` must remain healthy, belong to this JVM and be
at most 15 seconds old. The managed port cannot be changed through idle evidence.
Missing bridge evidence or a foreign listener fails closed.

TCP fragments from one thread are grouped into exchanges lasting at most one
second. Every exchange must have the same total bytes, with poll starts 10 seconds
apart (one-second jitter) and a poll within 11 seconds of each window boundary.
Other endpoints/callers, concurrent reads, varying sizes, extra/missing polls or
reads crossing a boundary reject the window. Neither freshness nor the 900-second
minimum is reduced. Positive-read evidence needs the new bridge binding and recorded stacks.

Run `python3 -m unittest tools.dev.test_ton1460_idle -v` for portable offline policy
and real JFR fixtures (JDK required). On macOS also run the original suite
plus new orchestration cases with `python3 -m pytest tools/dev/test_ton1175_verify.py`.
The short JFR fixture exercises the parser; it never qualifies as production idle.
The existing raw-socket fixture now enables stack traces without changing any
assertions. To reproduce the unmanaged rehearsal hook alone with synthetic
process/recording boundaries, run on macOS:
`python3 -m unittest tools.dev.test_ton1175_verify.IdleTest.test_rehearsal_idle_hook_accepts_strict_zero_reads_without_managed_bridge -v`.
This calls the actual `hook('idle')` and `idle()`; it is an offline fixture, not
a replacement for the full launchd observation. The original MathJax test needs
the existing `tools/mathsvc` dependencies (`npm ci` there in a clean checkout).

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
