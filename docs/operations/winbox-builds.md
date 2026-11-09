# Windows build offload

`tools/dev/heavy` automatically offloads direct `./gradlew` (also
`bash ./gradlew` / `sh ./gradlew`) commands from the repository root when all
tasks explicitly name `androidApp` or `shared:{domain,protocol,client,ui}`.
Nothing needs to be installed on each Mac worktree. Python 3.9+, Git, SSH and
SCP use the existing `winbox` SSH alias. Windows needs the provisioned JDK 21,
JDK 17 toolchain in the Gradle cache, SDK 37 and shared debug keystore.

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug :shared:domain:jvmTest :shared:protocol:jvmTest --offline
tools/dev/heavy --local ./gradlew :androidApp:assembleDebug
tools/dev/heavy --emulator python3 tools/dev/check-android.py
```

The wrapper prints `host=winbox` or `host=Mac local lock`. Remote work never
holds a Mac heavy lane. Remote code 75 (free memory below 4 GiB or slot timeout)
and SSH/transport connection failure (255) fall back to Mac under its regular
lock. Compilation/test failures propagate without a local retry. Missing
artifacts, bad snapshots or runner errors fail visibly (70), avoiding a
misleading successful local retry.

Keep these commands on Mac:

| Work | Reason |
| --- | --- |
| All `server:*`, including a command also containing shared/Android tasks | Local CLI, process and service assumptions |
| `--emulator`, connected/device/managed tests, install/uninstall | Devices and emulator live on Mac |
| `desktopApp:*`, native/iOS/macOS/Xcode, desktop packaging | Host tooling and runtime |
| Unqualified `test`/`build`/`check`, shared `allTests`/`check`/`build` | Can select server or native tasks |
| Arbitrary scripts, shell commands, `env ... ./gradlew`, non-root invocations | Cannot infer their environment or subprocesses |
| Init scripts, project-directory overrides, unknown options, machine paths | Not portable; use explicit task paths and supported options |

`--local` or `COACH_HEAVY_HOST=local` forces the Mac lock. `--priority release`
retains local priority behavior on Mac; Windows slots are independently queued
without preemption or release priority. `COACH_WINBOX_HOST` overrides the SSH
alias (useful for an unreachable-host rehearsal), and
`COACH_WINBOX_QUEUE_SECONDS` defaults to 900. Windows always caps workers at 2
and uses low-priority Gradle processes, with its launcher at Idle priority.

Each job has a UUID, a bundle of the exact current source, a detached worktree
and separate output. Snapshotting uses a private index and temporary Git ref;
it preserves HEAD and staging, includes untracked nonignored files and excludes
ignored SDK settings/build output. Avoid placing private files in the repo
unless they are ignored. Submodules and Git LFS are not transported separately.

Two machine-wide named Windows mutexes enforce the limit across callers and
worktrees. A separate mutex protects bare-repository/worktree administration.
An exited process releases its mutex even if it is killed. Queue waits time
out; active jobs are never killed to make room. Interrupted jobs can leave
files for inspection, but cannot retain a slot after the runner process exits.

After completion, active job worktrees and bundles move to
`D:\agent\_trash\<job>`; only missing worktree metadata is pruned. No automatic
trash deletion or system cleanup is installed. The original
`D:\agent\work\coach` checkout and the user's desktop session are untouched.
Do not use `~/Coach/service/winbox/build.sh` concurrently: that old entrypoint
does not participate in this limit and mutates its single shared checkout.

Logs/metadata live in `.coach-winbox/<job>/{request.json,result.json,remote.log,gradle.log}`.
`artifacts.zip` preserves repository-relative paths; APKs, `build/outputs`,
`build/reports` and `build/test-results` are restored into the calling worktree,
including failed-test reports. Reports from an earlier invocation may still
exist for modules not selected by the current command; use the job archive
when provenance matters. Remote logs stay in `D:\agent\logs\<job>`.

The default mode is `isolated` (no persistent Gradle daemon/configuration cache,
shared task build cache enabled). `COACH_WINBOX_MODE=reuse` enables daemon and
configuration cache for measurement. Each job uses a fresh worktree, so there
is no configuration-cache reuse across jobs. Caller options such as `--tests`,
`--offline`, `--rerun-tasks`, `--build-cache`/`--no-build-cache` and `-Pname=value`
are preserved; daemon/configuration-cache mode, priority and worker limits are
owned by the runner. Local environment variables are not implicitly forwarded.

Regression checks:

```sh
python3 tools/dev/test_winbox.py
python3 tools/dev/check-heavy.py
python3 tools/dev/check-resource-queues.py
```

Independent acceptance must use a fresh worktree: run the first example,
verify the APK with SDK `apksigner verify --print-certs`, then repeat with
`COACH_WINBOX_HOST=127.0.0.1` and verify the Mac fallback, reports, certificate
and exit code. If localhost has SSH configured, use a dedicated SSH alias with
`ProxyCommand /usr/bin/false` so the connection failure is deterministic.
