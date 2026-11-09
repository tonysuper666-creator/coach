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
