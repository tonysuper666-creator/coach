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
the runner's port. The script never uses the general `check-android.py` runner,
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
A host-only companion regression runs the
ledger without a tutor socket; it does not substitute for the real-phone gate:

```sh
tools/dev/heavy ./gradlew :server:app:test \
  --tests com.coach.server.LedgerClientStopResumeTest --offline
```
