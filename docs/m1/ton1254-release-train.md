# Five-feature release train (TON-1254)

PR #89 integrates mistake review, weekly study, mistake explanations, streaming
rendering and Today week selection. It remains draft. The current stage verifies
an isolated server downgrade and forward roll; full train and production
acceptance remain open. No production database or device is used by this stage.

## Integration and review repairs

The train is rebased onto main `1e2048b`. Main's unified actionable review queue,
04:00/timezone day calculation, review auto-start, mock entry and diagnostics
remain. Main's `ReviewEntryTest` and `ReviewQueueSmoke` are unchanged.

| Source | Pinned head | Integration |
| --- | --- | --- |
| #42 | `8f0459a190d6014c6a1e89a4ee674e55f7ad4521` | Included earlier mistake-review patches. |
| #50 | `ab139bf48f70f020479fd7acb4f6af2dd947537d` | Included earlier trace/review patches. |
| #51 | `887a4d12ade782b1bdd8d2f1e684dbb990a05fa0` | Non-merge patches already integrated. |
| #57 | `01f5f54400854d9ca866982435584a136d9b029c` | Integrated; source PR closed into #89. |
| #58 | `bc24e9864e4e3804f8ebe074fc6811400a657dcf` | Integrated streaming/replay patches; source PR closed. |
| #59 | `41b16315b3dbe4e5f730f663bd739964925374c5` | Semantic patches included; source head recorded in ancestry. |
| #61 | `db96ae7cd31fd59a8a8676bf47b0c9a213d180b3` | Semantic patches included; source head recorded in ancestry; duplicate advancement logic retained once. |
| #85 | `e1ec86663e156f8a8401820a0caed129c7a1dd21` | Rendering aggregate gate and reclaim sampler integrated; source PR closed. |


#59/#61's source heads remain recorded in ancestry after the semantic repairs.
Duplicate auto-advance behavior is retained once. Current main's endpoint
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
neither was installed production. The current installed server is
`c0c5bb82510694a481351d60733b8c98d6162bb8` (TON-1298). Its source SHA must be
provided through an explicit private installation descriptor copy. The harness
checks `sourceHead` against the requested full SHA and the reviewed contract;
unknown or mismatched versions fail closed. It never discovers production paths.

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
Build a clean c0c5bb8 checkout with `check :server:app:installDist`.

```sh
tools/dev/heavy --priority release ./gradlew check \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:app:installDist \
  -PcoachApplicationId=com.coach.android.ton1291 \
  -Pcoach.course=ton1291-synthetic-review \
  -Pcoach.port=29254 -Pcoach.isolatedLaunchPort=29254 --console=plain
python3 -W error::ResourceWarning -m unittest \
  tools.dev.test_release_train tools.dev.test_release_train_review \
  tools.dev.test_release_train_contract

tools/dev/heavy --priority release python3 tools/dev/rehearse-release-train.py \
  --private-data-dir /PRIVATE/COPY/data \
  --old-distribution /PRIVATE/BUILT-OLD/server/app/build/install/app \
  --old-head 5ef99895d9fad277bfbdbb97b64eda6a551242e9 \
  --rollback-checkout /PRIVATE/CLEAN-INSTALLED \
  --rollback-head c0c5bb82510694a481351d60733b8c98d6162bb8 \
  --rollback-installation /PRIVATE/installation.json \
  --out /PRIVATE/OUTPUT/sanitized-summary.json
```

The workdir runner `ton1254-final-gates.sh` runs candidate full gates/APKs,
restore tests, clean installed-baseline gates and rehearsal sequentially in one
heavy invocation. It requires explicit private input, old distribution, rollback
checkout/head and installation-descriptor environment variables.

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

## Current stage evidence

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

This stage goes to QA-C for an independent **downgrade-contract re-review** after
all required local gates exit 0. Do not dispatch whole-train final acceptance
before that stage passes. Keep #89 draft; do not merge or change production.

Real private-copy rehearsal, `ReviewQueueSmoke` and `MistakeReviewSmoke` remain
unverified. The current stage does not touch an emulator or a physical device.
The smoke runners retain their existing latency/correctness assertions and must
use isolated packages/ports and owned processes under `heavy --emulator`.

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
