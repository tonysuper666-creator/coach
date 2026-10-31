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

Set `COACH_BRIDGE_URL` to the protected Coach bridge (default loopback 8791). The CLI uses a 90-second JSON timeout and the existing preflight before each call. `COACH_DEEP_TUTOR_BRIDGE_LOG` points to a read-only copied activity log (default `~/Coach/corpus/deeptutor-bridge.log`); `COACH_BRIDGE_STATE` can point to the Coach budget file. Keep bridge caps unchanged. Preflight failure exits explicitly. The command is synchronous, bounded by MAX_BATCHES, reports inventory/calls/duration and exits nonzero if the target is unmet. Accepted content remains available for retry. An already satisfied target makes zero calls. A batch costs at most one Claude generation plus one Codex review; rejection can require more batches. The command records `request_type=dev_live`.

The six-worker/no-nice build allowance expires 2026-10-31 08:00 GMT; thereafter invoke through `tools/dev/heavy` with `--max-workers=2`. No builds/live during 2026-11-02 00:00–15:00 GMT. Do not use port 8790 or DeepTutor runtime files.

## Evidence (2026-10-31)

- `./gradlew :server:ai:test :server:store:test --max-workers=6 --console=plain`: exit 0; 42 AI tests + 9 store tests, zero failures/errors/skips.
- Four injected generation failures: each yielded 3 existing cards and the matching fallback reason; empty inventory yielded 0 cards and actual queued job state.
- Reclaimed/restored job tokens rejected; group retry/restart did not duplicate consumption; two available questions did not get consumed by a three-question request.
- Confirmed grading with an emoji before the evidence persisted offset 3 and reopened to the exact original substring.
- `:server:ai:installDist :server:app:compileKotlin`: exit 0.
- Actual `PoolCommandKt` process against a local HTTP fixture: 3 VERIFIED questions persisted, 2 fixture calls; repeating the command made 0 calls. This is stub verification, **not** a live CLI latency measurement.

Real subscription calls this delivery: **0**. PR #13 is still pending; the prior handoff requires its merge before live work. No claim of ≤25-second refill, 49-KP supply, UI rendering, automatic scheduling or zero-wait user experience is made by these tests. Independent acceptance must run from the final rebased PR head and the real protected bridge after the dependency merges.
