package com.coach.domain.grade

import kotlin.test.*

class AnalyticalNumberTest {
    @Test fun acceptsSignedValuesAndPreservesTrailingZeroRequirement() {
        for (input in listOf("0.00", "-12.30", "+12.30", " 2.50 ", "0002.50", ".50", "-.50")) {
            assertNull(AnalyticalNumber.inputError(input), input)
        }
    }

    @Test fun reportsMissingInvalidAndWrongPrecisionSeparately() {
        for (input in listOf("", " \n ")) {
            assertEquals(AnalyticalNumber.InputError.MISSING, AnalyticalNumber.inputError(input))
        }
        for (input in listOf("NaN", "Infinity", "1e0", "1/2", "25%", "text", "1,000.00", "2 .50")) {
            assertEquals(AnalyticalNumber.InputError.NOT_NUMERIC, AnalyticalNumber.inputError(input), input)
        }
        for (input in listOf("2", "2.", "2.5", "2.500", "-2.5000")) {
            assertEquals(AnalyticalNumber.InputError.WRONG_DECIMAL_PLACES, AnalyticalNumber.inputError(input), input)
        }
    }
}
