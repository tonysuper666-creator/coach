# M1 question supply integration

TON-1090's overnight slice provides persistent MCQ generation/review jobs, verified inventory, atomic groups, and an operator refill command. It does not install an application scheduler or UI. TON-1128 owns prewarming and presentation. The complete M1 card's explanation pregeneration, constructed-question supply and all-49-KP inventory rollout remain outside this slice.

## Server integration

Use the existing `LedgerActor` for **every** store operation. Construct `PoolWorker(actor, BridgeClient(...))` in the application. Its `runOne()` claims a queued job and performs bridge I/O outside the actor; call it from an owned application coroutine, serially until it returns null. On application suspension use the existing `recoverSuspension()`; each reclaimed job gets a new fence. A restored database cancels and requeues unfinished jobs in the new epoch. Do not run a second worker or CLI against a live server's data directory.

```kotlin
val worker = PoolWorker(actor, client)
// Current KP is foreground, regardless of its kind (Essay/concept/design included).
worker.ensure(course, kp, objective, excerpt, target = 6, background = false)
// Other KPs: target = 3, background = true, ordered by the application.
// Schedule runOne() in the application's worker, not inside the ledger actor.
val group = actor.execute { takePoolGroup(course, kp, groupId, size = 3) }
```

`ensure` deduplicates active work for the same course/KP/source hash. One `gen_mcq` job requests exactly 3 text-only choices from Claude; a separate durable `blind_review` job asks Codex to solve them without keys. Only structurally valid, source-supported questions with agreeing review enter VERIFIED inventory. Missing `served_by`, same-vendor review, bridge fallback, malformed generation, missing/duplicate review IDs or disagreement cannot admit content. Partial review can admit fewer than three; the caller/operator can refill again. Identical question fingerprints are not added twice.

`takePoolGroup` selects unseen VERIFIED questions and appends all `question_posed` events in one transaction. It accepts group size 1 or 3, never emits a partial group, and is idempotent by group ID. Rendering must use only its returned events; answer keys and explanations stay server-side. Repeated group IDs must refer to the same course/KP/group size. Request a new group ID for a genuinely new group.

Return contract:

- `READY`: render `events`; `available` is the remaining unseen inventory.
- `REFILLING`: no cards; render `message` (“题池暂空，正在补题（约 20 秒）”) and the actual `(jobId, state)` entries in `jobs`. Queued means queued, not a completed percentage. A failed generation/review records a terminal job state and failure reason.
- `EMPTY`: no cards and no active job; message says replenishment has not started. Call `ensure` and schedule the worker to start it. Do not pretend a job is running.

A failed task followed by a pool take writes one `fallback_used(groupId, kpId, from=TASK, reason)` for the group. EMPTY/TEXT_ONLY/MALFORMED/TIMEOUT generation failures are distinguished. An empty pool also records fallback without creating placeholder cards. Job state, pool consumption and calls survive restart. `ai_calls` records actual served model (null stays null), physical calls and foreground/background classification; rejected stale results still retain their real call accounting.

`GradePipeline.Outcome.evidenceSpans` contains rubric item IDs and original-answer UTF-16 `[start,end)` offsets. `persistConfirmedGrade` commits those spans with a confirmed grade under the existing job epoch/fence, question version and answer hash. Live-harness reports also include the offsets. This is the persistence adapter for the grading integrator; it is not a new grading endpoint.

## Refill a specified KP

Prepare a **private, external** JSON file with `course`, `kp`, `objective`, `excerpt` (source text). Do not commit course material. Stop the Coach server for that data directory, or use its worker instead. The command holds the same `server.lock` as the server.

```sh
./gradlew :server:ai:refillPool \
  -PpoolArgs='/private/coach-test /private/kp-input.json 6 4' \
  --max-workers=6 --console=plain
```

Arguments are `DATA_DIRECTORY INPUT_JSON TARGET MAX_BATCHES`; the last defaults to 4. Paths with spaces can use the direct Java entry point after `:server:ai:installDist`:

```sh
java -cp 'server/ai/build/install/ai/lib/*' com.coach.ai.PoolCommandKt \
  '/private/coach test' '/private/kp input.json' 6 4
```

Set `COACH_BRIDGE_URL` to the Coach bridge (default loopback 8791). The CLI uses a 90-second JSON timeout and checks bridge availability before each call. Per the owner’s 2026-10-31 00:00 change, it imposes no daily call count or utilization threshold; it retains the explicit 2026-11-02 00:00–15:00 GMT stop window. A real upstream 429 fails the job without admitting unreviewed questions; the UI can use existing inventory. Preflight failure exits explicitly. The command is synchronous, bounded by MAX_BATCHES, reports inventory/calls/duration and exits nonzero if the target is unmet. Accepted content remains available for retry. An already satisfied target makes zero calls. A batch costs at most one Claude generation plus one Codex review; rejection can require more batches. The command records `request_type=dev_live`.

The six-worker/no-nice build allowance expires 2026-10-31 08:00 GMT; thereafter invoke through `tools/dev/heavy` with `--max-workers=2`. No builds/live during 2026-11-02 00:00–15:00 GMT. Do not use port 8790 or DeepTutor runtime files.

## Initial stub evidence (2026-10-31 00:06 GMT)

- `./gradlew :server:ai:test :server:store:test --max-workers=6 --console=plain`: exit 0; 42 AI tests + 9 store tests, zero failures/errors/skips.
- Four injected generation failures: each yielded 3 existing cards and the matching fallback reason; empty inventory yielded 0 cards and actual queued job state.
- Reclaimed/restored job tokens rejected; group retry/restart did not duplicate consumption; two available questions did not get consumed by a three-question request.
- Confirmed grading with an emoji before the evidence persisted offset 3 and reopened to the exact original substring.
- `:server:ai:installDist :server:app:compileKotlin`: exit 0.
- Actual `PoolCommandKt` process against a local HTTP fixture: 3 VERIFIED questions persisted, 2 fixture calls; repeating the command made 0 calls. This is stub verification, **not** a live CLI latency measurement.

Real subscription calls this delivery: **0**. PR #13 is still pending; the prior handoff requires its merge before live work. No claim of ≤25-second refill, 49-KP supply, UI rendering, automatic scheduling or zero-wait user experience is made by these tests. Independent acceptance must run from the final rebased PR head and the real protected bridge after the dependency merges.


## Rebased live evidence (2026-10-31 01:05 GMT)

PR #13 merged as `0d41533`; this branch now rebases onto it. Preserve the upstream V002 → V003 migration ordering before V004. The schema regression now expects V004 and still verifies that reopening does not migrate again. `PoolCommand` opts into the upstream `testTraffic` bridge marker.

- `./gradlew :server:ai:test :server:store:test :server:ai:installDist --max-workers=6 --console=plain`: exit 0, **45 AI + 10 store tests**, zero failures/errors/skips.
- Real source: a private copy of KP `01-1-1` from the existing course corpus, 2,929 source characters. Dedicated local database, not the learning database.
- Two real CLI batches, each Claude Sonnet 5.5 generates 3 and GPT-6.1 Sol independently reviews: **14,680 ms / 16,876 ms**, 3/3 VERIFIED each. These are two observations, not a percentile/SLA claim. Generation durations: 7,976 / 9,244 ms; review durations: 6,633 / 7,567 ms.
- Inventory reached **6**. A separate Java process called `takePoolGroup`: **3 actual question_posed**, READY, 3 unseen remaining; repeated group ID returned identical events. Reopened database retained 3 unseen questions.
- `ai_calls`: **4 real physical calls** (2 Claude, 2 Codex), all actual served model names present, all complete. No same-vendor substitute.
- Shared port 8791 was already owned by the integration service. Its health reported Codex configuration permission failure from inherited task-local `CODEX_HOME`; the same account's ordinary Codex login worked. The measurements therefore used the repository's real bridge on **isolated loopback 18792**, with ordinary Codex home and separate bridge work directory. No daily call limit; no 0.5 utilization gate. This validates the pipeline, **not the shared 8791 service**.
- Isolated bridge process lifetimes: 01:04:04–01:04:20 GMT (PID 85396), 01:04:39–01:04:58 GMT (PID 89629); both stopped. Shared 8791 and DeepTutor 8790 were neither restarted nor changed.

The overnight backend slice is ready for independent review. The complete card remains partial: 49-KP supply, automatic application scheduling/UI, explanation pregeneration and constructed-question supply are not validated by this isolated run. Integration must correct the shared bridge environment before prewarming through that service.
