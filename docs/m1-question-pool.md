# M1 question supply integration

TON-1090 provides persistent MCQ and constructed-question generation/review jobs, verified inventory, atomic groups, and operator refill commands. TON-1128's application scheduling, lesson preparation/cache and UI are now merged through PR #15; this change preserves those integrations. See the closure evidence below for the actual 49-KP isolated validation and measured latency limits.

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

A successful reserve take after a real failed task writes `fallback_used(groupId, kpId, jobId, from=TASK, reason)`, once per failed job across group retries and restarts. EMPTY/TEXT_ONLY/MALFORMED/TIMEOUT generation failures are distinguished. Empty inventory and ordinary takes without failed work emit no fallback event and no placeholder cards. Job state, pool consumption and calls survive restart. `ai_calls` records actual served model (null stays null), physical calls and foreground/background classification; rejected stale results still retain their real call accounting.

`GradePipeline.Outcome.evidenceSpans` contains rubric item IDs and original-answer UTF-16 `[start,end)` offsets. `persistConfirmedGrade` commits those spans with a confirmed grade under the existing job epoch/fence, question version and answer hash. Live-harness reports also include the offsets. This is the persistence adapter for the grading integrator; it is not a new grading endpoint.

## Refill a specified KP

Prepare a **private, external** JSON file with `course`, `kp`, `objective`, `excerpt` (source text). Do not commit course material. Stop the Coach server for that data directory, or use its worker instead. The command holds the same `server.lock` as the server.

```sh
tools/dev/heavy ./gradlew :server:ai:refillPool \
  -PpoolArgs='/private/coach-test /private/kp-input.json 6 4' \
  --max-workers=2 --console=plain
```

Arguments are `DATA_DIRECTORY INPUT_JSON TARGET [MAX_BATCHES=4] [mcq|constructed]`. The constructed mode requires a passed KP and TARGET=2. Paths with spaces can use the direct Java entry point after `:server:ai:installDist`:

```sh
java -cp 'server/ai/build/install/ai/lib/*' com.coach.ai.PoolCommandKt \
  '/private/coach test' '/private/kp input.json' 6 4
```

Set `COACH_BRIDGE_URL` to the Coach bridge (default loopback 8791). The CLI uses a 90-second JSON timeout and checks bridge availability before each call. Per the owner’s 2026-10-31 00:00 change, it imposes no daily call count or utilization threshold; it retains the explicit 2026-11-02 00:00–15:00 GMT stop window. A real upstream 429 fails the job without admitting unreviewed questions; the UI can use existing inventory. Preflight failure exits explicitly. The command is synchronous, bounded by MAX_BATCHES, reports inventory/calls/duration and exits nonzero if the target is unmet. Accepted content remains available for retry. An already satisfied target makes zero calls. A newly generated MCQ batch uses one Claude generation plus one Codex review, with at most one additional Claude source-quote repair when needed; rejection can require more batches. For already-passed KPs, the worker can also drain queued constructed jobs, whose calls are included in the command total. The command records `request_type=dev_live`.

The six-worker/no-nice build allowance expires 2026-10-31 08:00 GMT; thereafter invoke through `tools/dev/heavy` with `--max-workers=2`. No builds/live during 2026-11-02 00:00–15:00 GMT. Do not use port 8790 or DeepTutor runtime files.

## Constructed-question supply

A passed learning fact (`attempt_graded`, `grade_reviewed`, `legacy_passed`, or `kp_overridden`) queues constructed supply in the same ledger transaction, using the latest previously supplied MCQ source pack. `ensure` also checks passed KPs. If there is no source pack, the application must supply one with `ensureConstructed`; no invented source is used. The existing application worker drains these persistent jobs. Unseen inventory below two triggers refill; no job is created before mastery passes.

Claude generates two questions with reference answers and a frozen 3–5-item rubric totaling ten points. Every basis must be a contiguous verbatim source quotation. One source-only repair is permitted before freezing: stems, reference answers, rubric IDs, criteria and points must remain identical. Both physical calls are recorded. Each question then gets its own durable Codex review; only an independent `CONFIRM` admits it. The question, reference answer, rubric, fingerprint, source-pack hash and served models persist together under epoch/fence checks. `constructedQuestions` is a server-side key accessor, not a client response.

```sh
java -cp 'server/ai/build/install/ai/lib/*' com.coach.ai.PoolCommandKt \
  '/private/coach test' '/private/kp input.json' 2 4 constructed
```

The merged bridge's exact-model policy remains enforced. Background routing additionally respects `X-Coach-Fallback: off`, including callers without the newer exact-model header. A reviewer cannot silently become the same vendor as the generator. Deploying this code is separate from changing the currently frozen APK or live service.

## Schema and integration

Rebased onto PR #15's merged V005 schema, retaining its pairing, source indexes and question thread linkage. Additional pool/constructed tables use capability checks with backups; reopening does not repeat migration. The source-page fixture's existing conditional table setup and column assertions remain intact. TON-1128 is done; its `LessonPreparation`, `ChatService` and UI are reused, not reimplemented. Its reported cached first-text samples were 35/16/14 ms; those are integration evidence, not new measurements in this run. Application-wide cache hit rate remains unmeasured.

## Historical evidence

The initial stub run used no real CLI calls. The 01:05 GMT run made four real calls on isolated bridge 18792, with two three-question batches at 14.680/16.876 seconds and six verified questions; shared 8791 then had a Codex environment failure. Those observations are superseded for current latency assessment by the larger closure sample below. Raw private course text and answer keys remain outside the repository.

## Closure verification (2026-10-31, after PR #15)

- Base: `397106a` (PR #15 merged; includes PR #13). Final local command: `tools/dev/heavy ./gradlew :server:ai:test :server:store:test :server:app:test :server:ai:installDist --max-workers=2 --console=plain` — BUILD SUCCESSFUL, AI **50**, store **19**, app **52**, zero failures/errors/skips. Bridge `fault_injection.py` with the service Python environment: **60/60**; `exam_protection.py`: **2/2**. The system Python lacked uvicorn; that invocation failed before running tests and is not counted as a passing check.
- Fallback regression covers no failed job, empty inventory, one failure across three groups, and reopen. Existing four fault injections retain valid reserve cards and real job reasons. No tests were removed or skipped.
- Constructed live sample on shared protected **8791**, using the final rebased runtime and a real copied source pack: Claude generation followed by separate Codex review for each question; two first-round reviews rejected, a second generation/review admitted **2 VERIFIED** questions. **6 physical calls, 136,339 ms** to fill. A separate JVM reopen retained identical reference answers, rubrics and fingerprints. Mastery was established only in an isolated database via three actual `submit_answer` commands with simulated correct answers; this is a replay, not the user's learning history.
- Earlier constructed diagnostics remain in the audit: the pre-rebase client was rerouted by the old shared bridge and failed closed; three exact-source validation failures were retained; an isolated patched bridge admitted two questions in **37,365 ms / 3 calls**. On the final rebased code, another isolated sample needed **100,970 ms / 5 calls** for one admission, followed by a **10,766 ms / 1 call** review retry on shared 8791 to reach two. No failed sample is substituted with a fixture. All owned isolated bridges were stopped.
- The one source-only repair has a regression proving unchanged criteria/points and rejection of a changed ten-point distribution. The successful live samples above did not require that repair; it is not claimed as a live-tested repair success.
- No changes to the frozen APK, live learning database, shared service lifecycle or DeepTutor. All source material was read from copies; generated questions and answer keys remain private. The final rebased build used the heavy wrapper and two workers; earlier six-worker builds were within the pre-08:00 allowance.

- A DESIGN/essay KP repeatedly failed admission because generated source quotes paraphrased text or rendered HTML mathematical notation. A separate real blind review confirmed the answers but the unchanged verbatim gate rejected all three quotes. The MCQ prompt now explicitly preserves contiguous original text, punctuation, HTML and LaTeX. A source-only repair can return only IDs and quotations; code retains stems, options and keys, rejects extra fields or unsupported quotes, then runs the normal independent blind review. Both repair paths have stub regressions; neither optional repair was invoked by the successful live samples in this audit. This is a generation fix, not a relaxed evidence check; all unsuccessful attempts remain in the timing/call audit.


### All-KP supply and timing

Real copied course content: **49/49 KPs**, including DESIGN/essay points; each input uses 17,090–19,481 characters from its actual anchored textbook pages. The first sweep used a frozen MCQ runtime before the final PR #15 rebase; the MCQ generation/review gate was unchanged by that rebase. Retries used the rebased runtime; the last two underfilled points were also retried after prompt hardening. No synthetic content was counted as live.

- First sweep: **40/49** reached three verified questions within three attempts, **159 physical calls**. All nine underfilled outcomes are retained. Two required further diagnostics/retries after the first refill pass.
- Final private validation inventory: **174 VERIFIED MCQs across 49/49 KPs**, each at least three. Current KP: **7**; an actual atomic three-card take left **4** unseen. This is an isolated validation database, not a deployment to the user's learning database.
- Fresh backup replay: **147 actual question_posed events** and three correct `submit_answer` commands per KP; **49/49** reached SYSTEM passed and queued one constructed generation job. Answers were supplied by a simulated learner, explicitly not genuine user study records.
- The **28 first-sweep single-batch successes** (exactly one Claude generation of three plus one Codex blind review): p50 **23.908 s**, nearest-rank p95 **34.859 s**, range **17.955–35.318 s**; **12/28 exceeded 25 s**. Selecting only these successes does not describe overall refill reliability.
- All **49 bounded first attempts**, including underfilled results: p50 **30.363 s**, p95 **122.349 s**, max **141.893 s**. Summing active CLI time through necessary retries to reach three: p50 **30.363 s**, p95 **162.685 s**, max **292.502 s**; diagnostic/build/idle time is excluded. Full per-KP records are in the issue's sanitized evidence attachment.
- Last previously underfilled DESIGN KP reached four in **17.883 s / 2 calls**, then the final installed binary added another three in **19.943 s / 2 calls**, reaching seven. These final samples did not invoke optional quote repair and do not replace the slow/rejected samples.
- Real physical calls this run, deduplicated by call_id across database copies: **245** = 116 MCQ generation + 107 blind review (including one diagnostic review) + 11 constructed generation + 10 constructed review + 1 diagnostic constructed generation. The earlier 01:05 run adds **4**, giving **249 recorded real calls across these TON-1090 runs**. Fixtures are excluded. Actual served model names and failed/null-served calls remain in the audit.

Result remains **partially achieved**: all-KP supply and command replay pass, but the 25-second target is not reliable. The measured current-KP reserve exceeds the three-question fallback buffer; no universal zero-wait claim is made. Application-wide lesson cache hit rate and uncached first-text latency were not remeasured here. Review must run from the final PR head before merge/done; the existing application/UI integration is preserved.
