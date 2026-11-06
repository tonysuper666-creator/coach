# Isolated real-phone reconnection

This is a bounded TON-1094 test of the real Android SyncEngine over the Mac's
Tailscale listener. It does not prove OS sleep, login startup, Tailscale restart,
message truncation during real sleep, or production readiness.

Wait for the existing device guardian to grant TON-1094 a lease. Renew it with
`~/Coach/testphone/lease.sh acquire TON-1094 60` before building. No grant means
no device probing: rejoin the existing `wait.sh` queue instead. The runner checks
the grant and the unlocked, awake, ten-minute-idle OPPO before installation.
The phone lease does not authorize host sleep, logout, or network interruption.

Build with JDK 17, the configured Android SDK and the global heavy lock:

```sh
uptime
pgrep -f GradleWrapperMain | wc -l
tools/dev/heavy ./gradlew :server:app:installDist \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  -PcoachApplicationId=com.coach.android.ton1094 -Pcoach.port=29194 --offline
python3 tools/dev/check-phone-reconnect.py --serial "$COACH_TESTPHONE" \
  --port 29194 --evidence ../phone-reconnect-evidence
```

Use a fresh evidence directory. The configured host stays in local properties;
no personal address or credential goes into source control. The test refuses
production ports, uses a fresh synthetic database and token, disables managed
children, source lookup and the real bridge, and requires all three network
security tests to pass before fault injection. The Android tests use the built
`COACH_PORT`, refuse the production service, and the READY checkpoint must match
the runner's port. After READY, a bounded 15-second control leaves the JVM running
and requires both main-thread and IO heartbeats to span at least ten seconds,
with no gap over four seconds and every sampled sync status online/active.
Failure aborts before STOP: it shows a diagnostic failure without JVM pause,
not a reconnection pass or proof of freezing. This additional control does not
extend the original 30-second OFFLINE deadline. On 2026-11-06 at 06:06–06:07 UTC,
head `9ea336c` failed this control before any JVM pause: both logged heartbeat
streams stopped after about four seconds, while the final HTTP requests returned.
This rules out JVM pause as a necessary trigger for the observed diagnostic stall;
it does not distinguish process scheduling from a log collection failure.
The script never uses the general `check-android.py` runner,
which also has emulator and Tailscale Serve side effects.

Only the runner's JVM process group receives STOP/CONT for about 32 seconds.
The phone must observe online → offline → online, a newer sync revision and
the same ledger epoch. Checkpoints include Android elapsed-realtime readings;
the summary records the host's measured pause and completion after resume.
This controlled pause is explicitly **not** a system-sleep measurement.

The foreground runner stops its JVM, force-stops its own card package,
uninstalls `com.coach.android.ton1094.test`, deletes its private synthetic server
data/token/logs and releases the lease, including on failure. Ctrl-C or TERM
uses the same cleanup. It leaves the card's main package installed without
saved pairing credentials. If the runner was forcibly killed, first check that
the lease still belongs to TON-1094, remove its `.test` package and release with
`~/Coach/testphone/lease.sh release TON-1094`; do not terminate unrelated services.

Share only `summary.json`, `network.txt`, `reconnect.txt` and `checkpoints.txt`.
There is no need to deploy or upgrade production to run this test.

On failure, keep the original deadlines. The checkpoints include safe operation
names, exception classes and periodic sync-loop state, so an exited loop can be
distinguished from a pending request. They contain no request bodies, tokens,
exception messages or personal URLs. The summary also records when the owned
JVM was paused and its process state. A main-looper heartbeat and allowlisted
scheduling/freezer flags from only the card package's process record help separate
coroutine stalls from whole-process suspension. Unsupported or inaccessible
process diagnostics remain unknown; a missing heartbeat alone does not prove
freezing. The raw process dump is neither retained nor shared.
At READY and failure, the runner also samples the card process's allowlisted
Linux task-state letter and the device's current wakefulness enum. These are
point-in-time observations, not proof of the state throughout the pause. A
restricted `/proc` or power dump remains unknown; diagnostic failures do not
suppress the other samples. Raw `/proc` and power output, including identifiers
and wake-lock details, are neither retained nor shared. These read-only samples
do not change power settings or wake the phone.
The test now also saves each channel's count and elapsed-realtime reading in
separate atomic cache files before writing to logcat. IO adds only connection
and loop-active state. READY/failure snapshots read these files through `run-as`
only for the card package, and reject other run IDs, channels, types or fields.
Missing or unreadable files remain unknown; no raw private data is shared.
Compare these independent counters with logged heartbeats on the next phone run
to distinguish logging loss from execution stopping. These counters are diagnostic
only: they cannot replace or relax the existing heartbeat or OFFLINE gates.
Normal test completion removes its files; failure cleanup force-stops the card
package and removes only that random run's files and atomic-write sidecars before
uninstalling `.test`, along with the test's synthetic SQLite and journal files.
The summary's `faultInjected` field records whether STOP
was actually reached. This independent-file diagnostic has not run on the phone.
On 2026-11-06 at 06:22 UTC, matching server and both APKs built successfully
through the heavy lock (135 tasks, 10 seconds), but the phone run failed during
APK installation before any network gate or heartbeat measurement. Its installer
output was discarded, so the reason remains unknown; test-package removal was
also unconfirmed. The lease was released and isolated server data removed.
The runner now records the failing APK role, exit code, known package-manager
error codes and password/transport booleans, without preserving raw installer
output. Unknown errors stay unknown. Cleanup checks the exact card test package
before and after uninstall, so a confirmed absent package is safe to clean twice;
an inaccessible or unexpected query is a cleanup failure, not proof of absence.
Main-package file cleanup and test-package removal run independently. These
installer and cleanup changes still require a guarded phone run.
A host-only companion regression runs the
ledger without a tutor socket; it does not substitute for the real-phone gate:

```sh
tools/dev/heavy ./gradlew :server:app:test \
  --tests com.coach.server.LedgerClientStopResumeTest --offline
```
