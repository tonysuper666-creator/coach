# Winbox validation, 2026-11-09

Measured source: `5d389410ead4889fa5ab3cf7f2236a6d3a048bbf`.
All nine timing runs used that clean SHA and the same tasks, sequentially.
All returned exit 0. Times include heavy routing, source snapshot/bundle,
transfer, worktree setup, Gradle, quarantine and artifact return.

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug :shared:domain:jvmTest :shared:protocol:jvmTest \
  --offline --rerun-tasks --no-build-cache --max-workers=2 --console=plain
```

Use `COACH_WINBOX_MODE=isolated` for the first group and
`COACH_WINBOX_MODE=reuse` for the second. For Mac, set
`COACH_HEAVY_HOST=local` and append
`--no-daemon --no-configuration-cache --priority=low`; its existing heavy lock
was acquired on every run. Remote policy adds equivalent low priority,
two-worker and isolated-mode switches. Mac used Homebrew JDK 21 and SDK 37;
Windows used its provisioned JDK 21 and SDK 37. Both use the cached JDK 17
toolchain required by the project.

| Mode | Run 1 (s) | Run 2 (s) | Run 3 (s) | Median (s) |
| --- | ---: | ---: | ---: | ---: |
| Winbox isolated | 69.105 | 72.206 | 73.799 | 72.206 |
| Winbox daemon + configuration cache requested | 78.476 | 78.247 | 70.236 | 78.247 |
| Mac under heavy lock | 32.282 | 25.230 | 24.635 | 25.230 |

Remote Gradle-only medians were 57.415 and 65.433 seconds respectively.
Each timing run executed 90 actionable tasks; domain tests contain 63 tests
in 13 suites, while protocol `jvmTest` has no test sources. Dependencies were
already cached, task output caching was disabled and tasks were forced to
rerun. Worktrees were fresh on Windows; Mac reused its local build directory.
These are representative operational paths, not cold-machine comparisons.
Other users' host load was not controlled.

Default to isolated mode. Requesting daemon/configuration cache was about 8%
slower in this sample. All three reuse logs reported starting a daemon and
storing a configuration cache entry; no cross-job daemon or configuration
cache reuse was observed. New worktree paths prevent configuration cache
reuse. No Java process remained after the experiment. Shared task output
caching remains enabled by default for ordinary remote invocations.

Winbox was about 2.86 times slower end to end than Mac for this forced-rerun
task set. The delivered benefit is moving compilation and intermediate output
off Mac, while returning APKs and reports. A reduction in Mac SSD writes was
not directly measured. Speed improvement is not claimed.

## Regressions

```sh
python3 tools/dev/test_winbox.py             # exit 0; 7 cases
python3 tools/dev/check-heavy.py             # exit 0; locking/priority/cancellation
python3 tools/dev/check-resource-queues.py   # exit 0; 7 queue/device-lock cases
python3 -m py_compile tools/dev/winbox.py tools/dev/test_winbox.py # exit 0
bash -n tools/dev/heavy                     # exit 0
git diff --check                            # exit 0
```

The new transport checks cover actual Git snapshots without touching the
caller index, safe artifact paths, deterministic 75/255 Mac fallback, build
failure propagation, caller option normalization and Mac-only routing.
## Fresh-worktree integration

Three clean detached worktrees at the measured SHA submitted this command
concurrently, each returning exit 0 and 137 tests with zero failures
(domain 63, client 74):

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:client:jvmTest \
  --rerun-tasks --no-build-cache
```

Jobs 0 and 1 used slots 1 and 0. Job 2 emitted
`winbox waiting for one of two slots` before acquiring a slot. All three
finished with `host=winbox sha=5d389410...`; none acquired a Mac lane.
These jobs used `heavy-b820e554d71142cea580594b308b95ea`,
`heavy-1971c34238ba45089298c7694e59032b` and
`heavy-aa86c9d9fe7043c09200053ec918a368` respectively.

In the second fresh worktree, the normal default-cache invocation also
returned exit 0, producing an APK and 63 passing domain tests:

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug :shared:domain:jvmTest --offline
apksigner verify --print-certs androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Job `heavy-16892bbdae0d4271a4dedc99340d3b38` logged Windows Gradle exit 0
in 59.045 seconds. `apksigner` returned exit 0.

The first fresh worktree had no Mac build output before this real DNS-failure
rehearsal (its earlier shared test output came from Windows):

```sh
COACH_WINBOX_HOST=coach-winbox-unreachable.invalid tools/dev/heavy ./gradlew \
  :androidApp:assembleDebug :shared:domain:jvmTest :shared:client:jvmTest \
  --offline --rerun-tasks --no-build-cache
apksigner verify --print-certs androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

SSH returned 255, then heavy printed `falling back to Mac` and
`host=Mac local lock`. The real Mac build returned exit 0 in 31.204 seconds,
with 137 passing shared tests. `apksigner` returned exit 0. Both APKs have
certificate SHA-256
`4435a6c41f1c4cbae89765961064df3c488f82a8dd5ae296dc9475a31a6d4e30`.
Certificate equality is established; byte-identical APKs are not claimed.

Raw logs, timing JSON, job metadata, XML test reports and certificate output
are attached to the TON-1414 delivery comment. QA-C must independently repeat
the APK/shared test command and unreachable-host command in a new worktree;
the checks above are the implementer's evidence, not independent acceptance.

## Cross-review corrections

After cross-review, UI test/check tasks stay on Mac, Android jobs compare
the three configuration value hashes before Gradle, and artifact archives
include `build/screens`. The original timing table above predates these
corrections; timings were not repeated or presented as new measurements.

The corrected transport suite has 9 passing cases (exit 0), including Java
Properties defaults, Unicode escapes/continuations, UI routing, JVM environment
overrides and screenshots. The existing heavy checks and 7 resource queue
cases also returned exit 0, as did Python compilation and shell syntax checks.

In a fresh detached worktree containing the corrected transport, both commands
below returned exit 0 and logged only `host=Mac local lock`:

```sh
tools/dev/heavy ./gradlew :shared:ui:jvmTest \
  --tests com.coach.ui.RenderingQualityTest --tests com.coach.ui.RecordsScreenshotTest --rerun-tasks
tools/dev/heavy --local ./gradlew :shared:ui:jvmTest \
  --tests com.coach.ui.RenderingQualityTest --tests com.coach.ui.RecordsScreenshotTest --rerun-tasks
```

Each invocation ran RenderingQuality's 8 tests and RecordsScreenshot's 3
tests: 11 total, zero skipped/failed/errors. Each produced 3 new records PNGs;
the first run's screenshot directory was moved aside before the second run.
The cross-review's `tests="8"` figure described RenderingQuality's suite,
not the sum of both selected suites.

To verify Windows screenshot return while UI tests remain Mac-only, a temporary
JVM test fixture was added only to the acceptance worktree's domain test
sources. It writes a uniquely named PNG to `shared/ui/build/screens` using
Java ImageIO. This command returned exit 0 on Windows, logged matching Android
configuration hashes for all 3 keys, and passed the fixture's single test:

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug :shared:domain:jvmTest \
  --tests com.coach.domain.WinboxScreensRoundTripTest --offline
```

Job `heavy-3ca31e365dad4e57b1e7af88c98c6287` used slot 0, Idle priority and
completed Gradle in 21.559 seconds. The screenshot did not exist locally
before this call, appeared in that job's artifact archive and returned to the
same repository-relative path on Mac, byte-for-byte identical to its ZIP
entry. `apksigner verify --print-certs` returned exit 0 with the certificate
recorded above. The fixture is included in the evidence harness, not in
application sources.

For each of `coach.tailHost`, `coach.course` and `coach.port`, a separate job
sent a deliberately different hash in `request.json`. All three real Windows
runner calls returned 75, named only the differing key, and never started
Gradle. Neither host's configuration file was changed. A fourth job with
matching hashes but a missing source bundle also returned 75 before Gradle,
confirming preparation-failure fallback eligibility.

An SCP shim then changed only the port hash in one uploaded request for the
following unmodified heavy command (both real host configuration files stayed
unchanged):

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug --offline
```

The real runner rejected the request with 75, heavy logged
`falling back to Mac` and acquired the Mac lock, and the APK build returned 0.
`apksigner` returned 0 and the signing certificate matched the Windows APK.
This checks the complete configuration-mismatch fallback, beyond separately
testing the runner gate and mocked transport.

The evidence attachment contains the foreground harnesses, commands/exit codes,
test XML, newly generated PNGs, hash-only requests and raw logs. These are
implementer checks; QA-C must independently repeat UI routing/screens checks
on the new PR head and arrange `cross_review` before merge.
