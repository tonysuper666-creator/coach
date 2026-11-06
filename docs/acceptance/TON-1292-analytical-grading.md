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
ledger. An empty final never falls back to working. A blank part scores zero
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
