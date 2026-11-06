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
ledger. Omitted or null mock-part `tolerance` uses the new analytical default `0.005`;
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
