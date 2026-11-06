package com.coach.domain.grade

/** Input contract for analytical papers that explicitly require two decimal places. */
object AnalyticalNumber {
    enum class InputError { MISSING, NOT_NUMERIC, WRONG_DECIMAL_PLACES }

    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
    private val twoPlaces = Regex("[+-]?[0-9]*\\.[0-9]{2}")

    /** Shared by the immediate client hint and the authoritative server check. */
    fun inputError(input: String): InputError? {
        val value = input.trim()
        return when {
            value.isEmpty() -> InputError.MISSING
            !decimal.matches(value) -> InputError.NOT_NUMERIC
            !twoPlaces.matches(value) -> InputError.WRONG_DECIMAL_PLACES
            else -> null
        }
    }
}
