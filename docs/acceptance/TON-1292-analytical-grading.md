# TON-1292 analytical grading

PR #100 preserves the first increment and integrates the TON-1247 mock engine
from main. Analytical parts now accept separate working and final values, retain
method points for invalid or incorrect finals, explain each criterion, and show
the frozen standard figure beside submitted work after submission. Narrow screens
stack the comparison; widths of at least 640 dp use two columns. Device acceptance
is still pending: JVM layout checks do not establish the emulator gate.

## Contract and integration

`AnalyticalNumber.inputError` is shared Kotlin code for immediate input feedback.
It distinguishes a missing value, an invalid decimal number and the wrong number
of decimal places. Two places are required, including trailing zeros. Signs and
outer whitespace are accepted; fractions, percentages and exponent notation are
not accepted for these analytical inputs. General `NumericValue` behavior stays
available for questions without this explicit analytical requirement.

`AnalyticalNumericKey(answer, tolerance).grade(submitted)` compares the frozen
decimal literals exactly, with an inclusive absolute tolerance. Preserve the
generated `answer` and `tolerance` text when constructing it; converting through
`Double` first can discard the precision this check is intended to preserve.
The result reports `CORRECT`, `INCORRECT`, or the shared input error code.

For an existing constructed question, opt in on the frozen numeric criterion:

```json
{
  "numeric_final": {
    "rubricItemId": "N",
    "expected": "1.005",
    "absTolerance": 0.005,
    "relTolerance": 0,
    "decimalPlaces": 2
  }
}
```

This example is synthetic. Strict keys require an explicit nonnegative absolute
tolerance and zero relative tolerance (zero is also used when omitted).
Unsupported precision and malformed keys are rejected before grading calls.
Keys without `decimalPlaces` retain the existing general numeric behavior.

The constructed worker adds the numeric criterion to the process rubric grade,
then persists the usual `attempt_graded` and `grade_reviewed` facts. It recomputes
the numeric result after process review. Invalid or missing final values earn no
numeric points while valid method points remain. `numericFinal.status` and the
exact frozen tolerance are recorded with the existing numeric fact. There is no
additional ledger or score unit.

The mock adapter uses this validator in analytical final-value fields and this
key for numeric-only parts. Constructed jobs include `decimalPlaces: 2` and the
original tolerance literal, and use the existing process grading/review path.
Flattened part keys and hundredths-of-a-mark score units stay on the existing
ledger. Omitted, null or blank mock-part `tolerance` uses the new analytical default `0.005`;
an explicit `tolerance: 0` still requires exact equality. Numeric-only parts read
only `final`, intentionally never interpreting the working `text` as a final.
An empty final never falls back to working. A blank part scores zero
without discarding another part's marks. The standard `figure_svg` is frozen at
exam start and excluded from pre-submission responses, including legacy papers
that stored it under `figure`.

## Verification

The initial five synthetic numeric tests produced three failures on the base:
inclusive tolerance boundary, two-place enforcement, and distinct missing/invalid
feedback. Command (exit 1):

```sh
tools/dev/heavy ./gradlew :server:ai:test --tests com.coach.ai.AnalyticalNumericTest --max-workers=2
```

After implementation, run the domain and AI suites (exit 0):

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :server:ai:test --max-workers=2
```

Coverage includes both directions of the exact 0.005 boundary, just outside it
with a high precision key, zero tolerance, negative values, signed zero, invalid
formats, missing final values, malformed keys, legacy behavior, and persistence
through constructed grading and cross-vendor review using an HTTP stub. No live
model calls are made by these tests.

Three randomly selected generated problems were independently calculated before
reading their keys. All three calculated results matched this production numeric
checker. The private calculations and material stay outside Git; only the
aggregate result belongs in the issue report.

## Mock integration verification (2026-11-06)

Nine new synthetic adapter tests initially failed against the unmodified mock
engine (exit 1), then all passed with the integration (exit 0):

```sh
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.AnalyticalMockTest --max-workers=2
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.AnalyticalMockTest --tests com.coach.server.MockExamTest --max-workers=2
```

The partial-credit test runs the real constructed worker and cross-vendor review
through an ephemeral local HTTP stub. It checks the preserved decimal literals,
format explanation, 300 hundredths of method credit, and normal `attempt_graded`
and `grade_reviewed` facts. Other independent tests cover each tolerance direction,
just outside the boundary, zero tolerance, wrong precision, non-numbers, missing
finals, hidden/frozen figures, unanswered parts, and reopening the ledger.

The full command passes with exit 0 (54 domain, 50 client, 78 UI, 80 AI and 157
server tests; 419 total, no skips). It also builds the suffixed debug APK:

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:client:jvmTest :shared:ui:jvmTest :androidApp:assembleDebug :server:ai:test :server:app:test -PcoachApplicationId=com.coach.android.ton1292 -Pcoach.port=19292 --max-workers=2
```

Two new Compose JVM tests check immediate field hints and non-overlapping stacked
and two-column work/figure layouts, including retained work after resizing.

An isolated server uses ports 19292/19293 and a SQLite backup opened from the
production database in read-only mode. Copied pending jobs are cancelled only in
the copy. Model transport points exclusively to a local synthetic HTTP stub, so
this checks the real worker/ledger protocol, not live model grading quality.
The synthetic three-part submission scores 4 + 3 + 0 = 7/12: correct numeric
answer, correct method with incorrect final, then blank. The method grade reaches
`REVIEWED`; its error explanation identifies the incorrect final-copying step.
The standard SVG is absent before submission and present with submitted work
afterwards. The full result survives server restart unchanged.

A fresh independent sample of three local generated problems also matched 3/3;
all five numeric finals matched `AnalyticalNumericKey`. No sampled material,
answers, private filenames or copied database are committed or attached.

## QA follow-up: compatibility and device runner

QA-C verified head `5b70f2b` but withheld acceptance pending the emulator gate.
This PR defines an analytical default of `0.005` for omitted mock-part tolerance,
while explicit zero stays strict. This is new semantics, not restoration of main
behavior: main used zero with a `1e-9` floor. Earlier handoffs incorrectly called
it a restored legacy default; the existing regression test name is historical.
The independent `omittedToleranceRetainsLegacyDefault` test failed against
`5b70f2b` (exit 1, assertion failure), then passed after the correction (exit 0):

```sh
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.AnalyticalMockTest.omittedToleranceRetainsLegacyDefault --max-workers=2
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.AnalyticalMockTest --tests com.coach.server.MockExamTest --max-workers=2
```

The latter command passes all 14 focused tests. The full command below exits 0
(`BUILD SUCCESSFUL in 5m 37s`): 54 domain, 50 client, 78 UI, 80 AI and 158 server
tests, 420 total with no failures or skips. Unchanged tasks can be up to date.

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:client:jvmTest :shared:ui:jvmTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:ai:test :server:app:test :server:app:installDist -PcoachApplicationId=com.coach.android.ton1292 -Pcoach.port=19292 --max-workers=2
```

`AnalyticalMockSmoke` is the prepared Android instrumentation journey, not an
executed device result. It enters separate working/final values through the UI,
checks format hints, submits the synthetic 4 + 3 + 0 example, captures the result
and wrong-step explanation, and supports reopening after force-stop. It only
accepts port 19292 and the `.ton1292` package. The issue attachment contains its
isolated server/AVD runner; the runner uses only `coach_1128_c70_r`, switches
between 360×640 dp portrait and 1280×800 dp landscape, restarts its own server,
checks the result remains identical, restores window overrides and uninstalls
both suffixed packages. It must run under `tools/dev/heavy --emulator`.
Neither compiling this test nor preparing the runner satisfies device acceptance.

## Remaining acceptance and device handoff

The `tools/dev/heavy --emulator` queue was occupied by another live owner; no lock
was bypassed and no device result is claimed. Still required: use only
`coach_1128_c70_r`, an isolated server/database and `com.coach.android.ton1292`;
record full step-by-step input, pre/post submission, portrait and tablet-landscape
figure comparison, criterion explanations, and reopening with screenshots.
Use synthetic questions and a local model stub for this acceptance run.
Remove the suffixed test APK afterwards. Never touch Fold8 or probe
the phone queue while emulator acceptance is incomplete.

The consolidated release card TON-1254 should repeat these on a physical device:

1. Open an analytical question, move between parts, and retain entered work.
2. Enter a missing value, text, one decimal place, and a valid two-place value;
   verify immediate hints before submission.
3. Submit a mix of correct, incorrect and blank parts. Verify per-criterion
   points, an explanation based on the transformed question, and ledger totals.
4. Verify the standard figure appears only after submission, alongside the
   learner's work, with usable scrolling in phone portrait and tablet landscape.
5. Reopen the graded attempt and verify answers, explanations, figures and totals
   survive reload and process review.


## Sage full-rubric correction

A full rubric previously suppressed `numeric_final`, so the separate final field
was neither checked by the server nor sent to grading. This affected the normal
full-rubric shape, not just an edge case. Every analytical numeric part now
records the same exact server check, including entirely blank parts.

When rubric points already cover the maximum, the frozen numeric configuration
sets `feedbackOnly: true`. The worker retains all original rubric criteria and
points; it does not invent a numeric allocation or deduct arbitrary method
marks. Both working and verbatim final fields reach grading and cross-vendor
review. Rubric evidence can quote either field, with stored offsets mapped back
to `answer` or `finalAnswer` rather than the concatenated grading input.
`numericFinal` records the independent check and result feedback displays it.
Rubric scoring remains model-reviewed; the HTTP fixtures verify transport and
ledger behavior rather than the correctness of a live model judgment.

When rubric points leave a numeric allocation, the existing server-owned N
criterion still supplies only those remaining points. Missing or invalid finals
continue to preserve earned method marks. Null tolerance now follows the same
analytical default as missing tolerance instead of parsing the literal `null`.

Sage's unchanged assertion reproduced on `2826a82`: exit 1, neither final input
in the grading request nor a numeric fact. The corrected implementation passes
that probe alongside the existing 14 tests. Independent full-rubric regressions
cover final-only rubric credit and correct evidence offsets, wrong final with
method credit, invalid input, precision, missing final, a completely blank part,
exact 0.005 boundary, above-boundary precision, null tolerance, reviewed facts,
and reopening the ledger. The device fixture now uses a full-rubric first part
with final-only input, plus the existing partial-credit and blank parts (7/12).

## Final correction verification (2026-11-06)

The final implementation, including main through `37f89ed`, was tested from a
clean worktree at `dc3ef7a`. The following documentation commit changes no code.

```sh
tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:client:jvmTest :shared:ui:jvmTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:ai:test :server:app:test :server:app:installDist -PcoachApplicationId=com.coach.android.ton1292 -Pcoach.port=19292 --max-workers=2 --rerun-tasks
```

Exit 0: `BUILD SUCCESSFUL in 6m 10s`, all 153 tasks executed. JUnit XML records
57 domain, 57 client, 81 UI, 80 AI and 176 server tests: 451 total, no failures,
errors or skips. Both suffixed APKs and the isolated server distribution built.
One earlier local rerun was cancelled (exit 130) to incorporate main's newly
landed ledger replay patch; the complete run above followed that merge.

The standalone reproduction was then repeated after the full run:

```sh
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.SageFullRubricProbeTest --max-workers=2
```

Exit 0, `BUILD SUCCESSFUL in 6s`; its output reports both
`grader-request contains final 7.77: true` and
`numericFinal present after grading: true`. Before the fix, the same assertion
failed on `2826a82` (exit 1, 8s). The full run includes the original 14 focused
tests, this probe and nine additional full-rubric regression cases.

The actual device command was:

```sh
tools/dev/heavy --emulator python3 ../ton1292_device.py
```

It remained queued behind live owner PID 68950. Only this run's waiting wrapper
was cancelled (exit 130); no AVD command or device assertion ran. No device
screenshots or device ledger sequence are available. The updated synthetic
runner is a continuation artifact, not evidence of acceptance. Keep PR100 draft
and the issue in review pending the specified emulator journey and independent
non-GPT review of the exact candidate head.

## Sage P2 follow-up: legacy replay and shared numeric verdict

The mock adapter accepts legacy answer-key notation again: surrounding spaces,
trailing punctuation and percentages. Decimal literals retain their precision;
percentages use exact decimal scaling. Other previously accepted key forms retain
the existing `NumericValue.parse` interpretation. This normalization applies to
frozen answer keys only: student final inputs still require two decimal places.
The original key remains available as the displayed reference. Blank tolerance
uses the analytical default, while an explicit zero remains strict.

The grading and cross-review prompts now include the actual server-owned numeric
verdict, computed by the same function that writes `numericFinal`. Review also
receives the frozen draft's numeric fact. This covers missing, malformed and
wrong-precision finals as well as numeric mismatches; the reviewer is instructed
to use the server verdict for final-value criteria and preserve method credit.

For a full rubric, full credit alongside a nonmatching final records
`rubricReviewWarning: FULL_CREDIT_WITH_NONMATCHING_FINAL`. The reviewer must explain
that allocation against the original criteria or revise it; if confirmed, the
warning remains visible in result feedback. The worker does not invent an N
criterion or an arbitrary deduction. An integration test starts with 4/4 despite
an incorrect final, then records an independent review revising to 2/4 while
preserving the same server verdict. Another test confirms that an unresolved
full-credit discrepancy remains flagged after review and ledger reopening.

The unchanged `SageBLegacyPaperProbeTest` attachment produced four failures on
`a515fc4`. Adding server-verdict assertions to the existing full-rubric scenarios
also failed against that implementation (8 answered scenarios failed, one blank
scenario passed). The combined baseline command exited 1, 13 tests / 12 failures:

```sh
tools/dev/heavy ./gradlew :server:app:test --tests com.coach.server.SageBLegacyPaperProbeTest --tests com.coach.server.FullRubricAnalyticalTest --max-workers=2
```

After the fix, the following command exited 0 (`BUILD SUCCESSFUL in 6s`):
80 AI tests and 38 selected server tests, no failures/errors/skips. These include
the unchanged four legacy probes, seven autosave/submission/frozen-replay cases,
12 full-rubric cases, the original 14 mock regressions and Sage's full-rubric probe.

```sh
tools/dev/heavy ./gradlew :server:ai:test :server:app:test --tests '*Analytical*' --tests com.coach.server.SageBLegacyPaperProbeTest --tests com.coach.server.MockExamTest --tests com.coach.server.SageFullRubricProbeTest --max-workers=2
```

Both Sage attachments were read. The five `SageBAnalyticalProbeTest` assertions
concern input normalization, zero-tolerance rounding and halfway rounding. They
were not added to the regression suite: two assert mutually incompatible behavior
for the same zero-tolerance key, and rounding would change the explicitly requested
strict-zero and inclusive-tolerance contract. They remain separate product-policy
questions from these two P2 fixes. No existing test was removed or weakened.

The complete forced acceptance command above was rerun on `19d9ba1`, including
main through `79f7823` and the P2 implementation `0fdb871`. Exit 0:
`BUILD SUCCESSFUL in 6m 12s`, all 153 actionable tasks executed. Fresh JUnit XML
records domain 57 / client 57 / UI 81 / AI 80 / server 191: **466 tests**, no
failures, errors or skips. Both `.ton1292` APKs and the isolated server distribution
built. This is the current follow-up's full run, rather than the historical 451
test result above. The subsequent documentation commit changes no tested code.

The emulator command was attempted again for this P2 follow-up. It remained in
the current wrapper's queue behind live owner PID 60354 (`winvm` build task).
After preparing the handoff, only our waiting wrapper PID 40920 was cancelled:
exit 130. Its waiting ticket and sleep-only child were checked before signalling;
no device runner, AVD, installation or isolated service began. No other process
or lock was modified. Therefore there are still no device screenshots, device
ledger sequence or portrait/tablet/reopen acceptance results. The attached runner
now also requires the local synthetic grader/reviewer to receive the actual server
verdict; it is a continuation artifact, not passed evidence. Keep PR100 draft and
request independent review of the exact new head; the first device gate remains
required before acceptance/merge. Physical follow-up stays with TON-1254.

## Device harness follow-up after QA-C's input failure

QA-C ran the device command on `756642f` and reported exit 1 at `input-a`:
`Typed value missing`, although the screenshot showed `abc` and its validation
message. The harness previously waited 300 ms and searched only visible editors
for a substring. It now refreshes the original input node for up to five seconds
and requires its text to equal the submitted value exactly. Validation-message,
score, figure, orientation and reopen assertions remain in place.

The adjacent continuation scripts now allocate a new private `isolated/session-*`
directory for every command invocation and back up the source database read-only
into that directory. Both server starts within one invocation reuse that same
copy, so the restart assertion still checks persistence rather than a reset.
Every invocation has a separate evidence directory and reinstalls only this
card's suffixed packages to remove interrupted-run screenshot caches. No old
database, another session's process or production data is deleted. Use the two
scripts from the same attached continuation bundle; the server requires the
runner's restricted session-directory environment variable.

The focused build command (exit 0, `BUILD SUCCESSFUL in 9s`) was:

```sh
tools/dev/heavy ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :server:app:installDist -PcoachApplicationId=com.coach.android.ton1292 -Pcoach.port=19292 --max-workers=2
tools/dev/heavy --emulator python3 ../ton1292_device.py
```

The first command built the changed instrumentation APK; it is not a new run of
the historical 466 JVM tests. Python syntax checks passed, and executing the
runner's directory-allocation statements twice produced two distinct private
directories without an inherited database. These checks do not prove a complete
device rerun or reentrancy after a failed UI journey.

The new device attempt exited **130** after queueing from 21:40Z to 21:50Z on
2026-11-06 behind live QA owner PID 38700 (`qa_emulator.py --all`). Only this
checkout's waiting wrapper PID 95315 was terminated after verifying its cwd,
exact command and sleep-only child, and excluding daemon PID 50222. The runner
never started: there is no new AVD, input journey, screenshot, ledger-sequence or
server-restart evidence. The two intended complete runs (including reentrancy)
remain unverified. QA-C should rebuild this exact candidate and run the same
device command with the paired continuation scripts. Three new independent
hand calculations also remain outstanding; Sage's earlier 3/3 is historical.
Keep the PR draft. Full-rubric mark allocation still depends on model grading
and review against the original rubric; the server records the numeric verdict
and discrepancy warning rather than inventing a deduction. Physical follow-up
remains TON-1254.
