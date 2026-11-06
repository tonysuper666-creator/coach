# Five-feature release train (TON-1254)

This branch combines mistake review, weekly study, mistake explanations, streaming
rendering, and week selection on Today. This delivery covers integration and an
isolated binary deployment/rollback rehearsal. Production remains on its existing
release until the device and deployment gates below have passed.

## Pinned train

Base: `8e644b587ad2760e918574d07dc6af3d62e3a4b6` (main, including PR84).
Heads were read from GitHub on 2026-11-06. The PR numbers alone are not pins.

| PR | Pinned head | Treatment/order |
| --- | --- | --- |
| #42 | `8f0459a190d6014c6a1e89a4ee674e55f7ad4521` | Earlier mistake review; ancestor of the integrated train. No additional merge. |
| #50 | `ab139bf48f70f020479fd7acb4f6af2dd947537d` | Earlier review/trace integration; ancestor. No additional merge. |
| #51 | `887a4d12ade782b1bdd8d2f1e684dbb990a05fa0` | Its non-merge patches are already included. Its merge commit need not be replayed. |
| #57 | `01f5f54400854d9ca866982435584a136d9b029c` | Weekly study/retired titles integrated upstream; two remaining commits are patch-equivalent (`git cherry` reports `-`), zero unique patches. |
| #58 | `bc24e9864e4e3804f8ebe074fc6811400a657dcf` | Streaming/replay optimization; ancestor of #59 and #61. |
| #61 | `d5a8e5accf05195a2d4737efb11bf89e22d27261` | Merge first onto main: Today week selection, integrated study/review/rendering and newer endpoint guard. |
| #59 | `8a49430a0bbb99baa016983f6890991d6c572212` | Merge second: latest canonical review-thread fix and regression tests. |
| #85 | `e1ec86663e156f8a8401820a0caed129c7a1dd21` | Merge third: rendering aggregate gate, including reclaim sampler `7e150d6`. |

The final branch must have #42/#50/#58/#59/#61/#85 as ancestors, no unique
non-merge patches from #51/#57, and the newest main changes retained.

## Conflict decisions

Three files conflicted while merging #59 after #61:

- `CoachActivity.kt`: retain #61's shared `ServerEndpoint.kt` resolver and
  `COACH_ISOLATED_LAUNCH_PORT`. Card builds require an explicit safe launcher port
  or a valid intent. Invalid intents fail closed. Adapt #59's additional tests
  to the shared resolver, retaining their rejected-destination cases.
- `ChatService.kt`: retain the newer staged diagnostics from #61 (`e418ba9`).
  Model input preparation occurs before `prepared`; bridge request preparation
  occurs before `bridge_start`; first-word diagnostics stay tied to the first
  upstream when the parallel lesson finishes earlier.
- `ChatFlowTest.kt`: retain both staged diagnostic regression tests, including
  #59's queue/TTFT correlation and filtered-content privacy assertions.

`ReviewScreen.kt` and its canonical-thread test merge cleanly. A bound KP uses
the existing ledger thread, avoiding the server's one-thread-per-KP rejection.
No server guard is relaxed. The imported endpoint test uses a synthetic DNS name.

## Reproduce local gates

Use JDK 17 and the local SDK/configuration already installed on the build host.
Do not use a production package or port for the train APK.

```sh
tools/dev/heavy ./gradlew check :androidApp:assembleDebug \
  :androidApp:assembleDebugAndroidTest :server:app:installDist \
  -PcoachApplicationId=com.coach.android.ton1254 \
  -Pcoach.port=29254 -Pcoach.isolatedLaunchPort=29254 --console=plain
python3 tools/render/check-android.py --self-test
python3 tools/render/acceptance.py --self-test
npm ci --omit=dev --prefix tools/mathsvc
python3 -m unittest tools.dev.test_ton1175_switch tools.dev.test_ton1175_verify \
  tools.dev.test_ton1175_launchd tools.dev.test_ton1175_supervise \
  tools.dev.test_service_child tools.dev.test_first_word_report
```

`check` includes the real isolated HTTP/WS activity-coverage protocol suite.
That suite ran 22 tests successfully (exit 0).
Opt-in real CLI/source replays and live device tests are separate gates.
An initial offline attempt lacked three cached Android dependencies; resolve
them normally rather than removing checks. Python tests must be invoked as
modules so relative imports resolve; the dependency verifier needs local npm
dependencies. These setup failures do not count as passes.

Recorded on 2026-11-06: the complete Gradle command exits 0 in 5m43s
(221 tasks). Its 102 JUnit suites contain 510 tests, with zero failures,
errors or skips. Deployment/tool Python tests: 91 passed (exit 0).
Both rendering verifier self-tests: 5 passed each (exit 0). The installed
render environment's `python tools/render/selftest.py`: 265 formulas,
zero missed mutations (exit 0). Android instrumentation APK assembly passes;
instrumentation tests have not been executed on a device by this delivery.
DESIGN 12.1's tracked-file scan has the same four hits as the pinned main:
one documented scan command and three synthetic test-fixture occurrences.
No new privacy-pattern hits were introduced. No private database, token,
course, answer, raw log or screenshot is added to the branch.

The last endpoint-test fixture edit (synthetic DNS instead of a synthetic IP)
was made after that successful build compiled its tests. Neither that rerun
nor the new JVM readback probes have been compiled or executed. The one-time
11:01 UTC resource-window wakeup was consumed on 2026-11-06; another task
continued to own the shared heavy-work lock. The continuation made one queued
`tools/dev/heavy bash ../ton1254-final-gates.sh` attempt, cancelled and reaped
it at 11:18 UTC (exit 143), before any of its three gates began. That is a
cancelled queue, not a Gradle failure or pass. No owned work remains running,
and no additional timed/periodic wakeup is registered. The clean compatible
baseline check/build and compatible rollback/fault-recovery rehearsal below
remain unverified. This is a partial delivery, kept draft.

The continuation's code revision is `ac68b58077454a9e3593ece0dac3d91d5f8c8ce4`.
It adds the independent review's stronger gates:
the selected rollback binary's client reads candidate wrong answers and cards,
and the first schema migration has separate zero-write/with-write scenarios.
`python3 -W error::ResourceWarning -m unittest tools.dev.test_release_train`
passes four tests (exit 0), including new events, same-watermark event edits,
speech-only writes, changed credential hashes, a running server, and the wrong
backup schema. The zero-write case restores the actual table schema as well
as its version, removing the synthetic v7 visibility column. This small guard
test does not validate the JVM rehearsal. QA-C acceptance and Architect's
security review still require the concrete full-gate and rehearsal results;
their earlier preliminary reviews do not establish acceptance of this head.

## Isolated deployment and rollback

The installed `5ef9989` starts against the v6 production copy. The candidate
also starts and completes the lecture/quiz, then upgrades that copy to v7.
Returning to the installed binary exits 1 with
`Database schema is newer than this server`. This is a real failed rehearsal,
not a usable rollback path. The v7 source-visibility migration already exists
on the pinned main, rather than being introduced by these train merges.
Do not lower `user_version` or restore an older snapshot to mask this failure.

### Two-stage production transition and paired phone rollback

Stage A must first take the installed v6 release to the independently accepted
`8e644b5` main baseline. Before admitting writes, its automatic
`backups/pre-V007-*/coach.sqlite` is a possible migration fallback: stop the
server, require the same maximum event sequence **and full event-row digest**,
and require unchanged speech and credential rows. Only this zero-write window
may restore that exact pre-migration backup and restart the original binary.
Any event append, edit, message, or credential change closes the window; refuse
restoration and retain the v7 data for forward repair. Never edit `user_version`.
The harness tests both outcomes on a separate private clone, including actual
old-binary authentication after the zero-write restore. Its helper is for a
stopped, exclusively owned private rehearsal, not a production restore tool or
an alternative to TON-1193/TON-1175's approved switch/seal protocol.

After Stage A is accepted and at least one real learning session has completed
successfully on that baseline, Stage B may install the feature train. Stage B's
fallback is the compatible baseline on the **same current v7 database**; retain
every train-period learner write. `8e644b5` is not yet a proven production
baseline, and neither stage is authorized by this document alone.

Server and phone versions must roll back together. The baseline server does
not handle the train APK's `tutor.retryMistakes` command; it silently ignores
that command and the new phone can wait without feedback. Do not leave the
train APK paired with the baseline after rollback. Prepare the reviewed,
matching prior APK and preserve its local state/credentials; paired rollback
and user-visible recovery still require the owning device cards' acceptance.
This rehearsal neither installs an APK nor claims that device gate passed.

Build a compatible fallback from the clean, pinned main baseline, keeping
the installed binary separately. This fallback contains main's schema and
source-visibility fixes, without this train's additional feature merges.

```sh
git worktree add --detach ../coach-rollback-base 8e644b587ad2760e918574d07dc6af3d62e3a4b6
# In that checkout, with the installed SDK configuration and JDK 17:
tools/dev/heavy ./gradlew check :server:app:installDist --console=plain
# Back in the train checkout:
tools/dev/heavy python3 tools/dev/rehearse-release-train.py \
  --rollback-checkout ../coach-rollback-base \
  --rollback-head 8e644b587ad2760e918574d07dc6af3d62e3a4b6 \
  --out release-train-report.json
```

The command reads the installed release descriptor, copies that distribution,
copies the candidate distribution, takes a SQLite online backup through a
readonly connection, and copies the token into a private temporary directory.
It cancels queued/running jobs **only in the copy**. All exercised courses,
questions, answers and bridge completions are synthetic. Pending original
course work is never sent to a bridge. Source lookup and managed services are
disabled, listeners bind only loopback, and protected ports are rejected.

A temporary atomic `current` symlink selects installed → candidate. The
installed binary's schema rejection is explicitly reproduced, then the
pointer selects the compatible main baseline. Each
successful phase starts a real JVM, authenticates with the original desktop
token, completes a synthetic lecture and a three-card quiz within 300 seconds,
then stops and reaps that child. An injected exit-71 release fails readiness;
the pointer returns to the compatible baseline and a fourth lecture/quiz
succeeds. The same copied
database is used throughout: rollback never restores an earlier database.
All original event rows and paired-device token hashes must remain unchanged,
candidate events must survive rollback, and SQLite integrity must remain `ok`.
The candidate deliberately answers one question incorrectly, then explicitly
requests three unseen retry questions. After both rollback and failure
recovery, the harness reads its full event payloads, pending retry attempts,
using the selected distribution's actual shared client projector. The train
client separately reconstructs its retained mistake and explanation from the
rollback server's response (the old client has no new mistake list). It also
reads every original course's snapshot, today progress and
mistake projection, and opens each pre-existing tutor context/history without
asking for teaching or sending a bridge request. Responses stay private;
only counts leave the copy. A `msg.error` fails these protocol checks.
The report contains only metadata, hashes and aggregate counts. Temporary
copies/logs are deleted and all owned listeners/processes are collected.
JVM home/temp directories are also isolated; inherited JVM option variables
are cleared, preventing fallback to production configuration or math files.

On success, this harness would verify binary startup, schema compatibility
and data-preserving pointer rollback. Its compatible-baseline mode has not
yet been run successfully. It does not exercise launchd, real bridge/math child management,
900-second production idle evidence, phone credentials in live traffic, the
production seal protocol, or production user-visible behavior. Keep the
existing TON-1193/TON-1175 deployment contract and independent review gates.

## Remaining release gates

| Owner card | Required before production |
| --- | --- |
| TON-1146 | OPPO review list, retry latency, correct-answer removal and four-form behavior on the reviewed head. |
| TON-1164 | OPPO week selection/switching, retired-point route/history and exact package/head acceptance. |
| TON-1167 | OPPO four forms, actual process reclaim/restoration, performance and aggregate diagnostic exits. `phone-acceptance.py` outside this repo is not yet proven to propagate the aggregate result. |
| TON-1169 | Independent latest-head review and OPPO integrated study/review/mistake explanation/rendering acceptance. |
| TON-1173 | OPPO normal icon launch against an isolated service and week selection within two taps. |
| TON-1242 | All five candidates' emulator preacceptance with exact package/head/server provenance. |
| TON-1094 | Resident-service/sleep recovery acceptance and its rollback evidence. |
| TON-1193 / TON-1175 | Production adapter review and legacy first-migration decision; installed `5ef9989` lacks the v1 coverage/seal protocol. An isolated binary rollback does not authorize a production bypass. |

The production rollback target must be the independently reviewed compatible
baseline, with its original-token/data rehearsal accepted before switching.
Returning directly to the installed `5ef9989` after v7 migration is blocked.

After any device fixes, rebuild and rerun affected train gates on the resulting
head. Independent QA-C acceptance and Architect's critical deployment review
are required. Keep the train PR draft while production gates remain open.
Do not merge it early via a reviewer automation.

At production handoff record the last actual learning activity and the same
process's required idle/coverage proof; calendar time or an unanswered message
does not establish that the learner is inactive. Keep old binaries/config and
original data/token available. Use the reviewed production switch transaction;
within its 300-second budget verify the original credentials and all five
features, capture sanitized screenshots, and immediately roll back on failure
without discarding candidate-period learner writes. No production switch,
launchd change, phone installation or device acceptance is performed by this
integration delivery.
