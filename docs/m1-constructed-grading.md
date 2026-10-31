# Constructed grading ledger (TON-1091)

Submitted answers to reviewed constructed pool questions enqueue `grade_attempt` in the same SQLite transaction as `answer_submitted`. The job freezes the entire question, reference answer, rubric, rubric fingerprint, raw answer and answer hash. Legacy questions without a reviewed constructed detail record are not silently graded.

`ConstructedGradeWorker` runs outside the single-writer actor for bridge I/O. It first checks arithmetic and locates original UTF-16 evidence, including the one permitted repair. It commits `attempt_graded(status=AI_UNREVIEWED)` and a `review_grade` job atomically. A separate worker step performs the cross-vendor review. Until `grade_reviewed`, the draft supplies no mastery or review-window evidence.

CONFIRM retains the draft. REVISE must provide every rubric item's corrected score, explanation and verbatim evidence in `revised_grade`; a revised total alone is rejected. Both paths pass the same arithmetic and evidence checks. Records retain the initial and corrected items, both actual served models, call IDs, rubric fingerprint and original-answer intervals. Failed calls are accounted too. Missing or same-vendor `served_by` cannot confirm a grade.

The review job makes an initial attempt plus up to three background retries (after 1 minute, 1 hour and 6 hours). Its persisted wall-clock deadline is 24 hours after submission. Restart preserves delays; suspension invalidates outstanding fences; restored epochs reject old results. Exhausted or expired reviews leave the draft uncounted. A Claude grading 429 falls back to GPT with a notice, and its reviewer switches to Claude to preserve vendor independence. An unavailable independent reviewer leaves the answer uncounted for retry. The exam protection window also pauses this grading worker.

## Calculation answers

Reviewed keys may carry `numeric_final` with `rubricItemId`, `expected`, and optional nonnegative finite `relTolerance` / `absTolerance`. The key review checks this specification with the reference answer. These questions enter the pool as CALCULATION. The student submits process text as `answer` and its final value separately as `finalAnswer`.

Only process criteria go to the grader. The reviewer sees the full answer, complete frozen rubric and numeric specification, but only revises process criteria. The server independently compares the final field and adds its reserved points after either draft or review. A model cannot award or remove those points. Numeric evidence explicitly targets the original `finalAnswer` field; other UTF-16 spans target `answer`. The record preserves both fields. Non-numbers, zero denominators, nonfinite values and invalid tolerances cannot earn numeric points.

## API

All routes use existing paired-device authentication.

- `POST /v1/courses/{course}/constructed`: `{kpId, attemptId, purpose?}` takes an unseen reviewed constructed question. Default purpose is CLOSED_BOOK; PRACTICE is also supported. Reusing an attempt returns its original card. Keys, reference answers and rubric content are not exposed in the card.
- `POST /v1/commands`: the existing immutable `submit_answer` command carries `{attemptId, answer, finalAnswer?, posedSnapshot}`. Its normal receipt confirms durable submission; AI results arrive as subsequent ledger events.
- `GET /v1/courses/{course}/attempts/{attempt}`: after submission, returns posed data, original submitted answer, complete frozen question and the initial/reviewed grading facts. Before submission or across courses it returns 404.

## Verification

```sh
date
uptime
tools/dev/heavy ./gradlew :server:ai:test :server:store:test :shared:domain:jvmTest :server:app:test --offline --max-workers=2
```

The worker tests exercise SQLite, stub HTTP bridge, restart, reclaim/restore fencing, window exclusion, validated REVISE, vendor failures, retry limits/deadlines, rate-limit fallback and server numeric scoring. The HTTP test exercises authenticated pool posing, immutable submission and record retrieval.

Opt-in actual-bridge replay, using the prior locally stored, reviewed TON-1041 question fixture:

```sh
COACH_GRADE_FIXTURE="$HOME/Coach/corpus/gradegen/runs/constructed-11-11-1.json" \
COACH_GRADE_PROBE_OUT="$HOME/Coach/corpus/ton1091" \
tools/dev/heavy ./gradlew :server:ai:gradeLedgerProbe --offline --max-workers=2
```

This explicitly submits a reference-answer replay into an isolated corpus database. Its summary labels it `REFERENCE_FIXTURE_NOT_HUMAN_BENCHMARK`; it cannot establish agreement with a learner. Raw records stay local. No DeepTutor running database or 8790 files are changed.

## Blind human benchmark

The public repository contains only the worksheet generator and comparator. Private question text, answers, frozen scoring materials and per-question results stay in the local corpus or authenticated issue attachments.

```sh
python3 tools/corpus/manual-grade-benchmark.py prepare \
  --mapping "$HOME/Coach/corpus/ton-1076/migration/mapping.json" \
  --reviewed "$HOME/Coach/corpus/ton-1095/proposal-reviewed.json" \
  --out "$HOME/Coach/corpus/ton1091"
```

Selection prefers reliably matched historical answers, then new reviewed KP coverage and tags. A historical answer is used only when its stored question text matches exactly. The manifest freezes the selected source IDs and source-file hash. The single HTML worksheet excludes reference answers, rubrics and AI marks, allows answer entry and four choices (PASS, NEEDS_WORK, FAIL, unsure), and exports/imports a JSON progress file. It does not upload or automatically save private content.

Current preparation selected 20 of the 48 original short-answer questions. Reviewed mappings associate them with 40 distinct KPs (multiple KPs per question); these are associations, not evidence of coverage quality. No historical answer matched reliably, so the answer fields are blank. Writing 20 answers may take longer than 15 minutes. Do not replace them with model-written learner answers.

On or after 2026-11-02 15:00 GMT, attach `manual-grade-benchmark.html`, expose the task's panel link and set the exact `ask` requested in the issue. Keep the issue blocked with `resume_condition=李政交回 20 道打分`. Do not publish an agreement figure before he returns his answers and marks.

After return, freeze and independently review a rubric/reference for each original question using its source packs, without including his marks in any model input. Score the exact returned answers through the pipeline and retain the reviewed result. The comparison input is `{grades:[{id,answer_sha256,score,max,review_decision,rubric_fingerprint,reason?}]}`. Answer hashes are SHA-256 of the exact UTF-8 `answer` field. `review_decision` must be CONFIRM or REVISE. Thresholds are PASS ≥0.8, NEEDS_WORK ≥0.5, FAIL <0.5.

```sh
python3 tools/corpus/manual-grade-benchmark.py compare \
  --manifest "$HOME/Coach/corpus/ton1091/benchmark-manifest.json" \
  --human "$HOME/Coach/corpus/ton1091/my-20-grades.json" \
  --ai "$HOME/Coach/corpus/ton1091/ai-grades.json" \
  --out "$HOME/Coach/corpus/ton1091/comparison.json"
```

The comparator rejects missing/duplicate/foreign IDs, stale answer hashes, blank marked answers, unreviewed AI grades and invalid scores. Unsure/unmarked answers are reported outside the comparable denominator; the 85% target is reached only with all 20 comparable and at least 17 agreeing. Report every disagreement, not only the aggregate; below 85%, revise rubric/prompts and run one further measured round as specified by the issue.
