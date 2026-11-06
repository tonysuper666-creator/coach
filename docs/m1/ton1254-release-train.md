# Five-feature release train (TON-1254)

PR #89 integrates mistake review, weekly study, mistake explanations, streaming
rendering and Today week selection. It remains draft. This document records the
integration and isolated rehearsal; production acceptance is still blocked.

## Pins and integration

The reviewed train was rebased from `12bad60` onto the requested main commit
`37f89eda20068e2197137b3d3f8aaa814fca5a7d`. Later main commits are outside this
review pin. No production switch or physical-device operation is part of this run.

| Source | Pinned head | Integration |
| --- | --- | --- |
| #42 | `8f0459a190d6014c6a1e89a4ee674e55f7ad4521` | Included earlier mistake-review patches. |
| #50 | `ab139bf48f70f020479fd7acb4f6af2dd947537d` | Included earlier trace/review patches. |
| #51 | `887a4d12ade782b1bdd8d2f1e684dbb990a05fa0` | Non-merge patches already integrated. |
| #57 | `01f5f54400854d9ca866982435584a136d9b029c` | Integrated; source PR closed into #89. |
| #58 | `bc24e9864e4e3804f8ebe074fc6811400a657dcf` | Integrated streaming/replay patches; source PR closed. |
| #59 | `41b16315b3dbe4e5f730f663bd739964925374c5` | Earlier integration plus `b5fd3a5` and `41b16315` cherry-picked as `6615f98` / `d9013ed`. |
| #61 | `db96ae7cd31fd59a8a8676bf47b0c9a213d180b3` | Earlier integration plus `db96ae7` cherry-picked as `4e0b457`; duplicate advancement logic retained once. |
| #85 | `e1ec86663e156f8a8401820a0caed129c7a1dd21` | Rendering aggregate gate and reclaim sampler integrated; source PR closed. |

After the semantic cherry-picks, ancestry merges `001f07f` and `3b3b790`
record the #59/#61 source heads. Both merges have the same tree as their first
parent: all source behavior had already been integrated, with the current main
navigation and corrected test imports/types preserved. `git cherry HEAD` for
both latest source heads emits zero unique patches.

## Review repairs and conflict decisions

- `CourseView` retains main's timezone/04:00 logic-day behavior and adds a separate
  `reviewThreadId`. `PROJECTION_VERSION` is **8**, invalidating older projections.
- `CoachApp` retains main's mock-exam entry and review routing, plus study back
  navigation imported from #59. `ReviewScreen` selects Due when due questions
  exist, otherwise Mistakes. Main's `ReviewEntryTest` and `ReviewQueueSmoke` are
  unchanged. Its due auto-start, exam notice and mock-exam entry remain present.
- Mistake retry opens the independent review thread with `review=true`, chooses
  unseen RECALL questions and leaves the learning Continue thread unchanged.
  The client shows a visible timeout after 15 seconds if an old server silently
  ignores `tutor.retryMistakes`; cards/errors cancel that timer.
- Mistake cards deduplicate by question ID. The unchanged reviewer duplicate
  test is included. Week 7 remains visible but unavailable during the midterm
  exemption; both direct buttons and dropdown obey the same availability rule.
- `CoachActivity` retains main's shared endpoint resolver and requires an explicit
  isolated launch port. Invalid intents fail closed. Rejected-host/port tests
  remain, adapted only to the resolver's current signature.
- `ChatService` retains staged preparation/bridge/first-word diagnostics and
  privacy filtering. Both sets of `ChatFlowTest` regression tests remain.
- #59/#61's compact layout, source page, accessibility and passed-point navigation
  tests are retained. Test-only fixes import `DpRect.height` and type nullable
  expected lists explicitly; their assertions are preserved.

The private migration helper now compares every durable SQLite table, including
question/key/source/offline-pack/thread data, future tables, binary values and
SQLite/FTS bookkeeping. Only rebuildable `projection_cache` is excluded. The
known v6-to-v7 visibility backfill is normalized on the v6 side only. A durable
write after migration refuses restoration, even if the event watermark did not
change. Restoration requires a stopped server, a valid v6 backup and current v7;
no manual `user_version` downgrade is used. This is an exclusively owned private
rehearsal helper, not a production restore or seal-protocol alternative.

## Reproduce the gates

Use JDK 17 and the installed Android SDK. All Gradle, JVM rehearsal and emulator
work goes through `tools/dev/heavy`; its shared lock must never be bypassed.

```sh
tools/dev/heavy --priority release ./gradlew check \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:app:installDist \
  -PcoachApplicationId=com.coach.android.ton1291 \
  -Pcoach.course=ton1291-synthetic-review \
  -Pcoach.port=29254 -Pcoach.isolatedLaunchPort=29254 --console=plain
python3 -W error::ResourceWarning -m unittest \
  tools.dev.test_release_train tools.dev.test_release_train_review
python3 tools/render/check-android.py --self-test
python3 tools/render/acceptance.py --self-test
python3 tools/render/selftest.py
python3 -m unittest tools.dev.test_ton1175_switch tools.dev.test_ton1175_verify \
  tools.dev.test_ton1175_launchd tools.dev.test_ton1175_supervise \
  tools.dev.test_service_child tools.dev.test_first_word_report
```

Current results are recorded below and in the sanitized issue attachment. Raw
build logs, test reports, private copies and command outputs remain in workdir.
Historical 510-test results from before this rebase are not current-head evidence.

| Gate at code head `7eb9d66` | Result |
| --- | --- |
| Full Gradle check + debug APK + instrumentation APK + installDist | **exit 0**, 221 tasks; 600 JUnit tests, 0 failures/errors/skips. |
| Server app / core / store | 167 / 28 / 38 tests, all passed (included in 600). |
| Shared UI / client / domain | 109 / 70 / 55 tests, all passed (included in 600). |
| ReviewEntry / TodayWeekSelection / reviewer mistake dedup / client retry timeout | 3 / 5 / 1 / 3 tests, all passed; main ReviewEntry and ReviewQueueSmoke unchanged. |
| Restore helper + unchanged reviewer fixture | **exit 0**, 7 test methods, including all five reviewer non-event-write cases. |
| Deployment tooling | **exit 0**, 91 tests. |
| Render self-tests / formula mutation | **exit 0**, 5 + 5 self-tests; 265 formulas, zero missed mutations. |
| Clean fixed `8e644b5` full check + installDist | **exit 0**, 156 tasks. |
| Clean additional `37f89ed` full check + installDist | **exit 0**, 156 tasks. |
| Fixed `8e644b5` synthetic private-copy rollback | **exit 1**, independent review-context readback fails (details below). |
| Additional `37f89ed` synthetic private-copy rollback + failure recovery | **exit 0**, metadata below; baseline not yet approved. |
| ReviewQueueSmoke + MistakeReviewSmoke emulator | **unverified**: global emulator lane unavailable; owned queued process cancelled/reaped, **exit 130** before launch. |
| Actual learner private-copy data and credential acceptance | **unverified**: no authorized existing copy supplied. Production dataDir was not read. |

The Gradle result aggregates the final successful incremental invocation and its
up-to-date successful test reports; all source changes were compiled/tested, and
both APKs were built. Earlier test compilation and Today-card matcher failures
were corrected without weakening assertions. The final code run is `7eb9d66`;
the following delivery change updates this document only.

The additional `37f89ed` rehearsal retained 26 candidate events, the original
synthetic event and paired-token hashes, with integrity `ok`. Original credentials
were accepted on seven starts. Four successful phases each completed a lecture
and three-question quiz. One wrong answer and three unseen retry cards remained
readable after rollback and injected exit-71 recovery. The original v6 binary's
v7 refusal was observed (exit 1). Nine owned JVM children were reaped, no forced
kill was needed, and zero isolated listeners remained. Temporary rehearsal copies
were removed. This is synthetic input evidence; it does not validate real learner
records or approve `37f89ed` as the new fallback.

## Rollback compatibility and private inputs

The former fixed fallback `8e644b587ad2760e918574d07dc6af3d62e3a4b6` builds
successfully but **fails** the strengthened rehearsal: candidate-period mistake
retry creates an independent review context, and that baseline's `tutor.open`
ignores `review=true`. Reopening that context triggers the one-learning-thread
per-KP guard and returns `msg.error`. Do not drop the context/readback check or
relax the server guard to make rollback pass.

The installed old binary `5ef99895d9fad277bfbdbb97b64eda6a551242e9` is still
incompatible with v7 and must reject it. Do not lower the version or discard
candidate writes by restoring an earlier snapshot. The requested rebase main
`37f89ed` includes independent review threads and passed as an additional
synthetic fallback candidate. It does not silently replace the previously fixed fallback;
its choice and deployment still need coordinator/Architect acceptance.

The harness **requires** an explicit `--private-data-dir`, `--old-distribution`
and `--old-head`. It never discovers the production data directory or installed
release descriptor. A live production path and symlinked inputs are refused.
No production dataDir was read in this repair run. An existing authorized private
production copy was unavailable, so current rehearsal input is **synthetic v6**:
one original synthetic event and an original paired credential hash. This proves
the machinery, not compatibility with the learner's actual original data/token.
The latter acceptance remains unverified until a private copy is supplied.

Build a clean fallback checkout with `check :server:app:installDist`, then run:

```sh
tools/dev/heavy python3 tools/dev/rehearse-release-train.py \
  --private-data-dir /PRIVATE/COPY/data \
  --old-distribution /PRIVATE/BUILT-OLD/server/app/build/install/app \
  --old-head 5ef99895d9fad277bfbdbb97b64eda6a551242e9 \
  --rollback-checkout /PRIVATE/CLEAN-BASELINE \
  --rollback-head FULL_ACCEPTED_BASELINE_SHA \
  --out /PRIVATE/OUTPUT/sanitized-summary.json
```

The untracked workdir runner `ton1254-final-gates.sh` likewise requires explicit
private input/distribution/baseline environment variables. It runs candidate
full gates, clean baseline gates and the rehearsal sequentially in one heavy
invocation. It has no production-directory fallback.

The real JVM rehearsal keeps one copied database across old, candidate, rollback
and injected-failure recovery. Each successful phase performs a synthetic lecture
and three-question quiz. Candidate also answers one question incorrectly and
requests three unseen RECALL questions on its review thread. Rollback/recovery
must read candidate wrong answers, pending retry cards, full event payloads,
original-course projections and existing learn/review histories without bridge
calls. It checks integrity, original event rows and credential hashes, and keeps
all candidate-period writes. An exit-71 binary must automatically restore the
fallback pointer and recover. Migration has separate zero-write restore+old-token
authentication and with-write restore-refusal cases.

Binary-rehearsal listeners bind loopback on isolated ports; the emulator runner
also uses the configured allowlisted host on its isolated port. Source lookup/managed services are disabled,
bridge output is synthetic, JVM home/temp/math/cache state is private, inherited
COACH_/BRIDGE_/JVM options are cleared, and owned children are reaped. Temporary
copies and raw responses are removed. Only hashes, counts and other safe metadata
may be attached to Multica/GitHub.

Server and phone rollback must use a reviewed matching APK while retaining local
credentials/state. The timeout protects a new client's retry button against old
servers, but does not establish paired-phone rollback acceptance.

## Emulator and later production gates

`run-review-emulator.py` runs under `heavy --emulator` on an owned read-only AVD;
only that serial is addressed. `check-review-copy.py` also requires the explicit
private copy and exercises a synthetic course with the isolated package above.
ReviewQueueSmoke and MistakeReviewSmoke retain their main latency/correctness
assertions. Mistake smoke covers live retry, offline cache after server stop and
restart/resume with the learning thread preserved. All owned JVM/emulator children
must be collected before delivery. Do not claim a queued/cancelled run as passed.

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

TON-1298 remains the first train; #89 is the later train. QA-C independently
accepts this integration and Architect reviews the critical rollback path once
the complete required evidence exists. Previous preliminary review does not
accept this head. Keep draft, do not merge, switch production, touch physical
devices/protected ports, create periodic wakeups or repeat an ongoing review.
A proposed baseline also needs independent acceptance and at least one real
learning session before the feature train can rely on it. Before any later
production switch, preserve binaries/config/data/token and prove actual same-
process learner idle/coverage; calendar time or an unanswered message is not proof.
