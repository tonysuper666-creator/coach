# TON-1292 analytical grading: first increment

This draft implements the numeric contract needed by analytical parts. The mock
exam adapter, step-by-step screen, explanations and submitted-work/standard-figure
comparison are still pending. It does not establish the issue's emulator gate.

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

TON-1247 was still developing its mock engine with no linked PR when this
increment started. When that engine lands, use this same validator in its
analytical final-value fields and this key for numeric-only parts. Where it
creates a constructed process-grading job, add `decimalPlaces: 2`, preserve the
generated tolerance literal, and leave process grading/review on its existing
path. Preserve its flattened part keys and hundredths-of-a-mark score units.
The standard `figure_svg` must remain hidden until submission.

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

## Remaining acceptance and device handoff

After TON-1247 integration, first test an isolated server on its own port with a
copy of the production database. Use the issue's specified emulator, build with
the `com.coach.android.ton1292` application ID, and wrap the emulator session in
`tools/dev/heavy --emulator`. Record the full step-by-step submission and figure
comparison path with screenshots. Check that an entirely unanswered part is
zero and does not discard another part's result.

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
