# Five-feature release train (TON-1254)

PR #89 integrates mistake review, weekly study, mistake explanations, streaming
rendering and Today week selection. It remains draft. The current stage verifies
an isolated server downgrade and forward roll; full train and production
acceptance remain open. No production database or physical device is used by this stage.

## Integration and review repairs

The follow-main candidate merges main `b1b5e49` into the existing PR #89
head `81180a0`; neither history nor the PR is replaced. This includes #91's exam
panel (`5f5cf55`) and #101's local reminders. Main's actionable review queue,
04:00/timezone day calculation, review auto-start, mock entry and diagnostics
remain. Main's `ReviewEntryTest` and `ReviewQueueSmoke` assertions are retained.

The three conflicts preserve both Android BackHandler and reminder settings,
pass reminder settings into the movable review pane, and keep the route's exam
panel review callback. StudyScreen retains panel launch/cancellation and exactly
one `AdvanceOnNewPass`; reopening a passed point must not advance it.

| Source | Pinned head | Integration |
| --- | --- | --- |
| #42 | `8f0459a190d6014c6a1e89a4ee674e55f7ad4521` | Included earlier mistake-review patches. |
| #50 | `ab139bf48f70f020479fd7acb4f6af2dd947537d` | Included earlier trace/review patches. |
| #51 | `887a4d12ade782b1bdd8d2f1e684dbb990a05fa0` | Non-merge patches already integrated. |
| #57 | `01f5f54400854d9ca866982435584a136d9b029c` | Integrated; source PR closed into #89. |
| #58 | `bc24e9864e4e3804f8ebe074fc6811400a657dcf` | Integrated streaming/replay patches; source PR closed. |
| #59 | `41b16315b3dbe4e5f730f663bd739964925374c5` | Semantic patches included via `cf19b08` / `f8cc642`; source SHA is provenance, not an ancestor. |
| #61 | `db96ae7cd31fd59a8a8676bf47b0c9a213d180b3` | Semantic regression retained in `81f92fa`; duplicate advancement logic retained once. |
| #85 | `e1ec86663e156f8a8401820a0caed129c7a1dd21` | Rendering aggregate gate and reclaim sampler integrated; source PR closed. |


#59's `b5fd3a5` → `cf19b08` and `41b1631` → `f8cc642` have identical
added/deleted patch lines after ignoring rebased context. #61's `db96ae7` →
`81f92fa` imports the additional navigation regression; its implementation and
helper test already came from #59. The source SHAs are not ancestors of this
rebased history. Imported assertions remain; later adaptations only add a
Compose height import and nullable list types. Duplicate auto-advance behavior
is retained once. Current main's endpoint
resolver, source lookup and first-word diagnostics are retained.

- `PROJECTION_VERSION` is 8.
- Default review tab is Due when due items exist, otherwise Mistakes.
- Retry uses an independent `reviewThreadId`, `review=true` and RECALL questions.
  The candidate client selects the learning thread for Continue learning.
- Retry registers its 15-second timeout before sending; cards/errors cancel it.
- Mistake cards deduplicate by question ID. Exempt week 7 is disabled in both
  buttons and dropdown; next-week skips it.
- Existing tests are retained, including main's review tests and the unchanged
  reviewer fixtures. Test import/type corrections preserve their assertions.

The private migration restore helper compares every durable SQLite table,
including questions, keys, sources, offline packs, threads, future tables, blobs
and SQLite/FTS bookkeeping. Only rebuildable `projection_cache` is excluded.
The known v6-to-v7 visibility backfill is normalized on the v6 side only. Any
post-migration durable write refuses restoration, including writes without a new
event sequence. A no-write restore requires a stopped server, valid v6 backup
and current v7. It is a private rehearsal helper, not a production restore tool.

## Installed rollback target and downgrade contract

Architect's decision retires `8e644b5` and rejects `37f89ed` as rollback targets:
neither was installed production. Each installed server gets its own reviewed
entry in `ROLLBACK_CONTRACTS`; the source SHA must be provided through an explicit
private installation descriptor copy. The harness checks `sourceHead` against the
requested full SHA and selects that entry; unknown or mismatched versions fail
closed. It never discovers production paths.

| Installed head | Copy | Old release | First migration | Review on rollback | Mocks on rollback | v6 refusal |
| --- | --- | --- | --- | --- | --- | --- |
| `c0c5bb82…` (TON-1298, historical) | v6 | pre-v7 `5ef9989` | v6→v7 window, restore rehearsed | rejected, no durable write | 404 client error | old binary |
| `cf1f1598…` (TON-1341 #112, previous; production may still return here) | v7 | the installed build itself | none; no `backups/pre-*` may appear, schema stays 7 | supported | answers | `--historical-distribution` |
| `5ee14d69…` (#125, installed now) | v7 | the installed build itself | none; no `backups/pre-*` may appear, schema stays 7 | supported | answers | `--historical-distribution` |

For `cf1f1598` the copy and the candidate share schema 7 and the same table
migrations, so the train is a same-database server swap: old (installed) →
candidate → rollback to installed → exit-71 recovery → candidate forward roll.
Its client already keeps Continue learning off review threads, so both clients
must report zero misroutes. The pre-v7 `5ef9989` binary must still exit 1 with
"Database schema is newer than this server" on the copy; the run refuses to start
without it. The `c0c5bb82` rows below remain the reviewed historical contract.

`5ee14d69` keeps every `cf1f1598` check and adds `readerOnRollback`. It is the first
installed build with the reader routes (`ReaderRoutes`/`ReaderLibrary`, unchanged
up to the candidate) and the separate `ReaderAnchorStore` module with its three
tables in its own `reader-anchors.sqlite`; neither build wires that store into the
server, so `coach.sqlite` keeps schema 7 and the same tables. Reading progress and
anchored notes are ledger events (`reader_progress_saved`, `reader_note_saved`).
With this contract the harness serves an invented two-page PDF
(`tools/dev/mainline-reader-fixture.py`) on its own `ton1254-reader` course via
`COACH_READER_LIBRARY`. The candidate saves progress (PDF p.2) and one note
anchored to an exact substring of the served page-1 text and its `textSha256`.
`/state` and `notes.md` must then read back byte-identical on the 5ee rollback,
after exit-71 recovery and after the candidate forward roll, with exactly one
event of each type. Real course content never reaches the reader routes. The
contract is chosen from the descriptor present at run time: a `cf1f1598`
descriptor still selects the `cf1f1598` entry, without the reader readback.

**Rollback only the server. Keep the candidate #89 client; do not reinstall an
old APK.** The c0c5bb8 client chooses the latest `kp_opened` without filtering
`via=REVIEW`. On a retried KP it routes Continue learning to a review thread that
the old server rejects. The candidate client filters review openings and keeps
Continue learning on the learning thread. The rehearsal runs the actual built
client/domain libraries from both distributions and asserts these two outcomes.

The installed c0c5bb8 server accepts schema v7 but lacks review-thread support:

- Every existing learning context must still return `tutor.state` and history.
- Every `via=REVIEW` context must return `msg.error`; the request's before/after
  `durable_fingerprint` must be identical. This is expected degraded capability,
  not permission to delete the review row or skip checking it.
- Candidate events, wrong answers, retry cards, original rows, token hashes and
  integrity remain intact. Unsupported mocks return 404. The real candidate HTTP
  client must finish with an error; a Compose test requires visible error and Retry.
- Forward roll the candidate on the **same database**, open review with
  `review=true`, require state/history and the same pending RECALL cards, at least
  26 candidate events, unchanged original events/token hashes and integrity `ok`.

Keep the historical `5ef9989` binary and v6 fixture for first-migration checks.
Its real schema-v7 refusal (exit 1) is an additional diagnostic; it is not the
current rollback target. Never lower `user_version`, delete review/mock records,
or restore a snapshot that loses candidate writes. Recover unsupported features
by forward rolling.

## Reproduce the gates

Use JDK 17 and the installed Android SDK. All Gradle/JVM work runs through the
current main `tools/dev/heavy --priority release`; never override its global locks.
Build the two clean pinned historical checkouts with `:server:app:installDist`
through current main's `tools/dev/heavy --priority release`. The client probe
uses the candidate test classes with each historical distribution's libraries;
it does not require compiling tests in the historical checkout.

```sh
tools/dev/heavy --priority release ./gradlew check \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:app:installDist :server:app:testClasses \
  -PcoachApplicationId=com.coach.android.ton1254 \
  -Pcoach.course=ton1291-synthetic-review \
  -Pcoach.port=29254 -Pcoach.isolatedLaunchPort=29254 --console=plain
python3 -W error::ResourceWarning -m unittest \
  tools.dev.test_release_train tools.dev.test_release_train_review \
  tools.dev.test_release_train_contract

# Current installation (v7 copy): old and rollback are the clean installed checkout's build.
tools/dev/heavy --priority release python3 tools/dev/rehearse-release-train.py \
  --private-data-dir /PRIVATE/COPY/data \
  --old-distribution /PRIVATE/CLEAN-INSTALLED/server/app/build/install/app \
  --old-head cf1f159833a4fd5fadad0b8450b147b4e413f7b2 \
  --rollback-checkout /PRIVATE/CLEAN-INSTALLED \
  --rollback-head cf1f159833a4fd5fadad0b8450b147b4e413f7b2 \
  --rollback-installation /PRIVATE/installation.json \
  --historical-distribution /PRIVATE/BUILT-OLD/server/app/build/install/app \
  --out /PRIVATE/OUTPUT/sanitized-summary.json

# Installed now (5ee14d69, v7 copy made by that build): same flags, its own descriptor/checkout.
tools/dev/heavy --priority release python3 tools/dev/rehearse-release-train.py \
  --private-data-dir /PRIVATE/INDEPENDENT-SYNTHETIC-V7-5EE/data \
  --old-distribution /PRIVATE/CLEAN-5EE14D69/server/app/build/install/app \
  --old-head 5ee14d69faf0f9e6a79a7292a370981263b40d3f \
  --rollback-checkout /PRIVATE/CLEAN-5EE14D69 \
  --rollback-head 5ee14d69faf0f9e6a79a7292a370981263b40d3f \
  --rollback-installation /PRIVATE/5ee14d69-installation.json \
  --historical-distribution /PRIVATE/BUILT-OLD/server/app/build/install/app \
  --out /PRIVATE/OUTPUT/5ee-summary.json

# Historical contract (v6 copy).
tools/dev/heavy --priority release python3 tools/dev/rehearse-release-train.py \
  --private-data-dir /PRIVATE/V6-COPY/data \
  --old-distribution /PRIVATE/BUILT-OLD/server/app/build/install/app \
  --old-head 5ef99895d9fad277bfbdbb97b64eda6a551242e9 \
  --rollback-checkout /PRIVATE/CLEAN-C0C5BB82 \
  --rollback-head c0c5bb82510694a481351d60733b8c98d6162bb8 \
  --rollback-installation /PRIVATE/c0c5bb82-installation.json \
  --out /PRIVATE/OUTPUT/historical-summary.json
```

For an independently generated synthetic input, prepare a new output directory
from the built historical v6 server (do not reuse another author's private copy):

```sh
tools/dev/heavy --priority release python3 tools/dev/prepare-train-fixture.py \
  --old-distribution /PRIVATE/BUILT-OLD/server/app/build/install/app \
  --out /PRIVATE/INDEPENDENT-SYNTHETIC-V6
# A v7 input made by the installed build itself, for the cf1f1598 contract:
tools/dev/heavy --priority release python3 tools/dev/prepare-train-fixture.py \
  --old-distribution /PRIVATE/CLEAN-INSTALLED/server/app/build/install/app --schema 7 \
  --out /PRIVATE/INDEPENDENT-SYNTHETIC-V7
```

Pass `/PRIVATE/INDEPENDENT-SYNTHETIC-V6/data` as `--private-data-dir` to both
rehearsal and emulator runners. A synthetic installation descriptor containing
`sourceHead=c0c5bb82510694a481351d60733b8c98d6162bb8` tests the reviewed contract;
it is not proof of the actual installed version or an authorized learner copy.

The harness backs up only an explicitly supplied private database, refuses live
installed-tree/symlink inputs, keeps one copied database through candidate,
server-only rollback, exit-71 automatic recovery and candidate forward roll.
Four phases each perform a synthetic lecture plus three-question quiz. Review
readback calls no bridge. The first migration separately proves actual v6
no-write restoration/old-token authentication and with-write refusal.

The current input is a **synthetic v6 private fixture**, not a learner database.
The installation descriptor was read as metadata only and copied privately;
no production dataDir was read. A real private production copy remains missing.
The fixture retains its original event and paired token hash without printing
values. All listeners use isolated loopback ports, bridge output is synthetic,
managed services/source lookup are disabled, JVM home/temp/math state is private,
and inherited COACH_/BRIDGE_/JVM options are cleared. All owned children are
reaped and temporary copies deleted. Publish only sanitized metadata.

## Main bb2ff2ba merge and 5ee14d69 contract — 2026-11-10

PR #89 merges main `bb2ff2ba` in one merge commit (no rebase), keeping main's
paused-answer/reading-position restore and #89's wide-screen first-question
positioning; no test was removed. Synthetic evidence only, built locally with
`tools/dev/heavy --local`: clean `5ee14d69`, `cf1f1598` and `5ef9989` checkouts
(`installDist`, exit 0); fresh v7 fixtures made by the `5ee14d69` and `cf1f1598`
builds (`prepare-train-fixture.py --schema 7`, exit 0) with synthetic descriptors.
The `5ee14d69` rehearsal exits 0: schema 7 in every phase, no backup, v6
refusal by `5ef9989` (exit 1, schema newer), review and mocks 200 on rollback,
reader state/notes digests identical on rollback, fault recovery and forward roll,
26 candidate events, 3 RECALL cards, 7 children reaped without forced kill. The
`cf1f1598` rehearsal with its own descriptor also exits 0 (no reader readback).

## Follow-main evidence — 2026-11-06

Code head `449bfc204899519a75718121b6db064928a05edf` contains main
`b1b5e494ac62c4330b34f36a8df321710bc1322b` and original PR head `81180a0`.
The full candidate command above exits 0 (221 executed tasks, 6m56s): 647
JUnit tests in 137 suites, zero failures/errors/skips. Breakdown: UI 118,
client 86, domain 56, server app/core/store 168/37/38, AI 71, desktop 8,
Android unit 13, migration tools 52. Exam-panel launch/cancellation/exemption,
review-only reminder settings, source navigation and new-pass advancement
regressions pass. Restore/reviewer/installation-contract tests also exit 0
(eight methods). No existing assertion was removed or weakened.

The Gradle command began at `f2ea699bb5dcbccc3e21c8fced667fa260c6a5db`;
`449bfc2` changes only the Python emulator cleanup runner. All Android,
server and shared source trees are identical between these heads. APK manifest
inspection confirms `com.coach.android.ton1254` and
`com.coach.android.ton1254.test`. The runner accepts an isolated package argument,
does not enumerate phones, and attempts removal of both APKs before reaping
its owned emulator. Actual emulator execution/cleanup remains unverified.

Both pinned historical server distributions were built locally (installDist,
exit 0). An independent synthetic v6 fixture was generated from the newly built
`5ef9989` distribution (exit 0), rather than reusing a previous author's copy.
The corrected rehearsal against `c0c5bb8` exits 0 at code head `449bfc2`:

- Server-only downgrade preserves all 26 candidate events, original event rows
  and paired-token hashes; integrity is `ok`. Review rejects with zero durable
  writes, while learning state/history remain readable.
- Candidate/installed client Continue learning misroutes remain 0/1; the old
  APK is not installed. Mocks returns a completed 404/error on the rollback server.
- Injected exit 71 recovers the rollback server without restoring the database;
  forward roll reopens the same three pending RECALL cards in that same database.
- Original credentials authenticate on eight starts; four synthetic lecture/quiz
  phases pass. Ten owned children are reaped without forced kill, no isolated
  listeners remain and the temporary rehearsal copy is deleted. Real tutor calls,
  production connections/control calls and protected-port usage are zero.

Two corrected invocation failures are recorded in the issue evidence: historical
`testClasses` hits an old Gradle configuration-resolution error (exit 1; only
historical installDist is needed), and supplying the fixture parent instead of
its `data` child is refused before launch (exit 1). Neither required changing
historical code or weakening a gate. A production-shaped package is deliberately
rejected before emulator launch (expected exit 1).

At 22:34Z the emulator lane is held by a live TON-1253 process. Per the dispatch
contract, neither `ReviewQueueSmoke` nor `MistakeReviewSmoke` was queued or run;
there is no instrumentation success or uninstall evidence for this stage. No
physical-device registration was made. QA-C can review the integrated candidate;
the two emulator gates still need an available lane before advancing device gates.

## Previous-stage evidence (historical)

At code head `1ed7a93`, candidate full Gradle check, debug APK, instrumentation
APK and server installDist exit 0. The JUnit reports contain 619 tests with zero
failures, errors or skips: UI 110, client 75, domain 56, server app/core/store
167/37/38, AI 71, desktop 8, Android unit 5 and migration tools 52. This includes
the mocks-404 Compose test, main's review entry, week selection, deduplication
and retry timeout tests. Restore/reviewer/installation-contract tests exit 0
(eight methods, including the reviewer's five non-event-write cases).

The clean installed c0c5bb8 baseline's full check and installDist exit 0:
476 JUnit tests, zero failures/errors/skips, 156 executed tasks in 5m13s.
The synthetic downgrade, exit-71 recovery and same-copy forward roll exit 0:

- Installed-server review opens return `msg.error` with zero durable changes;
  learning state/history and the original payload/pending-card checks pass.
- Actual candidate client libraries select the learning thread (zero misroutes);
  installed client libraries reproduce one misroute, confirming the old-APK ban.
- Forward roll reopens the review with `review=true` and the same three pending
  RECALL cards. All 26 candidate events and original event rows remain; original
  paired-token hashes are unchanged, accepted on eight server starts, integrity `ok`.
- Four phases complete a synthetic lecture plus three-question quiz. Historical
  v6-to-v7 incompatibility is diagnosed with actual old-binary exit 1. The injected
  exit 71 automatically recovers the installed server without a snapshot restore.
- Actual candidate mocks HTTP completes with 404/error; the Compose error/Retry
  test passes. Ten owned server children are reaped (eight exit 143, one exit 1,
  one exit 71), none forcibly killed; zero listeners remain and the temporary
  rehearsal copy is deleted. Probe subprocesses also complete synchronously.

These are synthetic-input results, not real learner acceptance. Earlier results
at `7eb9d66` / `bd81198` are historical. The delivery commit changes this document
only after tested code `1ed7a93`. Command logs and raw reports remain private in
workdir; the issue attachment records commands, exits, counts and hashes only.

## Later acceptance and production gates

The follow-main stage goes to QA-C for independent integration acceptance;
QA-C arranges Architect's critical review. This stage does not dispatch
whole-train final acceptance. Keep #89 draft; do not merge or change production.

Real authorized private-copy rehearsal and physical-device acceptance remain
unverified. The follow-main smoke invocation is:

```sh
tools/dev/heavy --emulator --priority release python3 tools/dev/run-review-emulator.py \
  --private-data-dir /PRIVATE/INDEPENDENT-SYNTHETIC-V6/data \
  --application-id com.coach.android.ton1254 --out /PRIVATE/SMOKE-OUTPUT
```

The smoke runners retain their existing latency/correctness assertions and use
isolated packages/ports and an owned emulator. The runner removes both test and
app packages before reaping its emulator, including after a failed assertion.
An occupied emulator lane is an unverified gate, not a reason to bypass the lock.

| Owner card | Required before production |
| --- | --- |
| TON-1146 | OPPO review list, retry latency, correct-answer removal and four-form behavior. |
| TON-1164 | OPPO week switch, retired-point route/history and exact package/head. |
| TON-1167 | OPPO four forms, actual reclaim/restoration, rendering/performance aggregate exits. |
| TON-1169 | Independent integrated study/review/explanation/rendering acceptance. |
| TON-1173 | OPPO normal-icon isolated launch and week selection within two taps. |
| TON-1242 | All five emulator preacceptance gates with exact package/head/server provenance. |
| TON-1094 | Resident service/sleep recovery and rollback acceptance. |
| TON-1193 / TON-1175 | Approved production switch/seal/first-migration protocol. |


TON-1298 is the earlier production train. These owner/production gates and
Architect's later critical-path safety review are not replaced by synthetic
rehearsal or a passing local build. Before a production switch, preserve the
installed descriptor/binaries/config/data/token, verify the descriptor still
matches the reviewed downgrade contract, and prove actual learner idle/coverage.
No periodic wakeups or duplicate review dispatches are part of this work.
