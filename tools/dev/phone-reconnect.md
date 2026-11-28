# Isolated real-phone reconnection

This is a bounded TON-1094 test of the real Android SyncEngine over the Mac's
Tailscale listener. It does not prove OS sleep, login startup, Tailscale restart,
message truncation during real sleep, or production readiness.

The 2026-11-06 13:20 owner handoff restricts the current work to consolidating
PR #77 into PR #18: no device use or new queue entry is authorized for this step.
Before requesting another lease, document the root-cause analysis on TON-1094
as required for cards with more than five unsuccessful leases. The foreground
pass below identifies a successful test condition, not the cause of the earlier
background stalls; that cause remains unknown. Production bridge repair belongs
to TON-1298 and is separate from this harness.

For a subsequently authorized device run, wait for the existing device guardian
to grant TON-1094 a lease. Renew it with
`~/Coach/testphone/lease.sh acquire TON-1094 60` before building. No grant means
no device probing: rejoin the existing `wait.sh` queue instead. The runner checks
the grant and the unlocked, awake, ten-minute-idle OPPO before installation.
The phone lease does not authorize host sleep, logout, or network interruption.

The reconnection instrumentation opens and later finishes a debug-only, unexported
blank activity in its own card package. This makes the intended foreground
client lifecycle explicit without launching the learning UI, storing pairing
credentials, changing power settings, or keeping the display awake. A resumed
activity is asserted before measurement. This is a lifecycle experiment after
the background instrumentation stalled; it is not a claim that background
operation or real system-sleep reconnection has been fixed. Both original
heartbeat and OFFLINE gates remain unchanged.

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
was actually reached.
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
installer failure diagnostics still require a failing guarded installation to
exercise; successful installation does not validate that failure path.

On 2026-11-06 at 06:37–06:38 UTC, head `9aa6ebd` (main `8e644b5`)
ran the independent-file diagnostic on the leased, unlocked, idle OPPO PMA120.
The previous test package was confirmed absent before installation. Both APKs
installed and the network gate passed 3/3. The 15-second no-fault control failed
before STOP (`faultInjected=false`). For run
`b123720172c24c9a8c8b1f4b740e39a2`, both private counters advanced from 1 to 3:
the main clock advanced 4,013 ms and IO advanced 4,003 ms. Logged heartbeats
also stopped after about four seconds; the last hello/events requests had END
checkpoints, with no OFFLINE or SYNC_LOOP_FINISHED. Thus logcat loss alone does
not explain the observations. File writes are also an observation path: this
does not prove the process was frozen or identify the cause. At failure the
process was present, kernel state S, device Awake, curProcState=4,
cached=false and isFrozen=false; those are point-in-time samples only.
Test-package absence after uninstall was confirmed, private synthetic data and
the owned server were cleaned, the lease was released and ai_calls=0.

The Android/server/Gradle build inputs for this run matched the successful
`6234a50` heavy build. Reusing those matching artifacts is not a rebuild or a
reconnection pass. This prompted investigation of why both heartbeat paths
stopped while the server remained running, including the phone/test execution and
file/log observation paths. Do not repeat JVM pauses until the unchanged healthy
control passes, infer freezing from missing samples, or change phone power
settings as a substitute for diagnosis. Further phone observation requires a
new guardian grant and the same idle protection; use the existing queue when
another phone measurement is needed.
Process snapshots also count a fixed allowlist of kernel wait symbols from only
the card process's tasks, read under its own UID. No task IDs, thread names,
raw wait-channel text or stack dumps are saved. Unsupported symbols are counted
as unknown, access failure stays unknown, and `0` may mean a hidden wait point.
This observes scheduling without changing phone power settings or signalling
the process. A snapshot still describes only that instant, and does not by itself
identify a cause or prove a reconnection pass.

On 2026-11-06 at 06:55–06:56 UTC, `976cd9a` passed the network gate 3/3
but again failed the unchanged healthy control before STOP. Both private counters
advanced 1 to 3 (main 4,009 ms, IO 4,004 ms). The last hello/events requests
returned. The failure snapshot contained 33 unknown wait-channel observations
and no recognized symbol; it did not identify a cause. The test package was
confirmed absent after cleanup, the isolated server/data were removed, the
lease was released and ai_calls=0. Source review found that the instrumentation
had no foreground activity. The debug-only blank activity experiment described
above subsequently built successfully with matching server and both APKs via
the heavy lock (135 tasks, 17 seconds).

On 2026-11-06 at 07:08:36–07:09:40 UTC, head `0ae8d63` ran that foreground
experiment on the guardian-leased, unlocked OPPO PMA120 after thirteen minutes
idle. Run `c79f905088a0402da4512c0d38a05fa1` passed the network gate 3/3 and
reconnection 1/1. `TEST_ACTIVITY_RESUMED` preceded READY. The unchanged healthy
control passed with main and IO spans of 10,028 and 10,017 ms before STOP.
The isolated JVM was confirmed paused (state Ts) for 32.126 seconds. The phone
reached OFFLINE within the original 30-second deadline, then RECONNECTED with
the same epoch and a newer revision; the test completed 1.133 seconds after
resume. Both heartbeat streams continued during the controlled pause.
The test package was confirmed absent after uninstall; the owned server,
private synthetic data and diagnostic files were cleaned and the lease released.
Production writes and ai_calls were zero.

This establishes a pass under an explicit foreground test lifecycle. It does
not identify the cause of the earlier background instrumentation stalls, prove
background reliability, or measure real system sleep. Do not repeat this gate
merely to wait for a host-interruption window. Real login startup, system sleep
with JVM-clock/message-finalization evidence and phone reconnection, and
Tailscale restart/rebinding still require their own guarded measurements.

A host-only companion regression runs the
ledger without a tutor socket; it does not substitute for the real-phone gate:

```sh
tools/dev/heavy ./gradlew :server:app:test \
  --tests com.coach.server.LedgerClientStopResumeTest --offline
```
