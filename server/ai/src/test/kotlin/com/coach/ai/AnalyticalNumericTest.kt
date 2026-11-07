package com.coach.ai

import com.coach.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic values only; no generated paper or source material is copied here. */
class AnalyticalNumericTest {
    private fun grade(submitted: String, expected: String = "1.005", tolerance: Double = 0.005,
        strict: Boolean = true): JsonObject {
        val question = ConstructedQuestion("synthetic", "Show the synthetic calculation.", "Synthetic worked answer",
            listOf(RubricItem("P", "Correct method", 3.0, "Synthetic method"),
                RubricItem("N", "Final value", 2.0, "Synthetic value")))
        val process = DraftGrade(listOf(GradeItem("P", 3.0, "Valid method", "method")), 3.0, 3.0)
        val config = json("rubricItemId" to "N", "expected" to expected,
            "relTolerance" to 0.0, "absTolerance" to tolerance)
        val numeric = if (strict) JsonObject(config + ("decimalPlaces" to JsonPrimitive(2))) else config
        return gradePayload(process, "method", numeric, question, submitted, emptyList())
    }

    @Test fun exactBoundaryIsInclusiveInBothDirections() {
        for (submitted in listOf("1.00", "1.01")) {
            val result = grade(submitted)
            assertTrue(result.getValue("numericFinal").jsonObject.flag("correct"), submitted)
            assertEquals(500, result.num("score"))
        }
    }

    @Test fun outsideToleranceRetainsProcessPoints() {
        for (submitted in listOf("0.99", "1.02")) {
            val result = grade(submitted)
            assertFalse(result.getValue("numericFinal").jsonObject.flag("correct"))
            assertEquals(300, result.num("score"))
            assertEquals(500, result.num("max"))
        }
        assertFalse(grade("1.01", "1.004999999999999999").getValue("numericFinal").jsonObject.flag("correct"))
    }

    @Test fun precisionIsCheckedBeforeComparingTheValue() {
        for (submitted in listOf("1", "1.0", "1.000")) {
            val result = grade(submitted, "1.00")
            assertFalse(result.getValue("numericFinal").jsonObject.flag("correct"), submitted)
            assertEquals("WRONG_DECIMAL_PLACES", result.getValue("numericFinal").jsonObject.str("status"))
            assertEquals(300, result.num("score"))
        }
    }

    @Test fun blankAndNonNumericHaveDistinctFeedbackAndNoFinalPoints() {
        for ((submitted, status) in listOf("" to "MISSING", "   " to "MISSING", "unknown" to "NOT_NUMERIC",
            "NaN" to "NOT_NUMERIC", "1/1" to "NOT_NUMERIC", "1e0" to "NOT_NUMERIC")) {
            val result = grade(submitted, "1.00")
            val fact = result.getValue("numericFinal").jsonObject
            assertFalse(fact.flag("correct"), submitted)
            assertEquals(status, fact.str("status"))
            assertEquals(300, result.num("score"))
        }
    }

    @Test fun strictPolicyDoesNotChangeLegacyNumericQuestions() {
        assertTrue(grade("1.0", "1.00", strict = false).getValue("numericFinal").jsonObject.flag("correct"))
    }

    @Test fun signedZeroNegativeValuesAndZeroToleranceUseExactDecimals() {
        assertTrue(AnalyticalNumericKey("-2.345", "0.005").grade("-2.35").correct)
        assertTrue(AnalyticalNumericKey("0", "0").grade("-0.00").correct)
        assertTrue(AnalyticalNumericKey("2.50", "0").grade(" +2.50 ").correct)
        assertFalse(AnalyticalNumericKey("2.500000000000000001", "0").grade("2.50").correct)
    }

    @Test fun invalidFrozenKeysAreRejectedRatherThanUsingDefaults() {
        val valid=json("expected" to "1.00","decimalPlaces" to 2,"absTolerance" to 0.005)
        for (invalid in listOf(
            JsonObject(valid + ("decimalPlaces" to JsonPrimitive(3))),
            JsonObject(valid + ("absTolerance" to JsonPrimitive(-0.005))),
            JsonObject(valid + ("relTolerance" to JsonPrimitive(0.1))),
            JsonObject(valid + ("expected" to JsonPrimitive("NaN"))),
            JsonObject(valid - "absTolerance"))) {
            assertFails { analyticalNumericKey(invalid) }
        }
    }
}
