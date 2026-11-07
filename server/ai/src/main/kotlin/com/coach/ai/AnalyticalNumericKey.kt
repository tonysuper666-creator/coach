package com.coach.ai

import com.coach.domain.str
import com.coach.domain.grade.AnalyticalNumber
import kotlinx.serialization.json.*
import java.math.BigDecimal

/** One generated part's frozen key. Compare decimal literals without a Double round trip. */
class AnalyticalNumericKey(expected: String, tolerance: String) {
    private val expectedValue = BigDecimal(expected)
    private val absoluteTolerance = BigDecimal(tolerance).also {
        require(it.signum() >= 0) { "negative_analytical_tolerance" }
    }

    data class Result(val correct: Boolean, val status: String)

    fun grade(submitted: String): Result {
        AnalyticalNumber.inputError(submitted)?.let { return Result(false, it.name) }
        val correct = BigDecimal(submitted.trim()).subtract(expectedValue).abs() <= absoluteTolerance
        return Result(correct, if (correct) "CORRECT" else "INCORRECT")
    }
}

/** Opt in per frozen numeric criterion; the existing general numeric contract stays available. */
internal fun analyticalNumericKey(numeric: JsonObject): AnalyticalNumericKey? {
    if ("decimalPlaces" !in numeric) return null
    require(numeric.getValue("decimalPlaces").jsonPrimitive.intOrNull == 2) { "invalid_decimal_places" }
    require(numeric["relTolerance"]?.jsonPrimitive?.content?.let { BigDecimal(it).signum() == 0 } != false) {
        "analytical_requires_absolute_tolerance"
    }
    return AnalyticalNumericKey(numeric.str("expected"), numeric.getValue("absTolerance").jsonPrimitive.content)
}
