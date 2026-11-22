package com.coach.server

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact arbitrary-precision rational arithmetic (`BigInteger` numerator/denominator, zero floating-point drift)
 * for verifying mock exam calculation sub-questions before publishing any question paper.
 */
data class Rational private constructor(
    val num: BigInteger,
    val den: BigInteger,
) : Comparable<Rational> {
    operator fun plus(other: Rational): Rational = of(
        num * other.den + other.num * den,
        den * other.den,
    )

    operator fun minus(other: Rational): Rational = of(
        num * other.den - other.num * den,
        den * other.den,
    )

    operator fun times(other: Rational): Rational = of(
        num * other.num,
        den * other.den,
    )

    operator fun div(other: Rational): Rational {
        require(other.num != BigInteger.ZERO) { "Division by zero" }
        return of(num * other.den, den * other.num)
    }

    operator fun unaryMinus(): Rational = Rational(-num, den)

    fun abs(): Rational = if (num < BigInteger.ZERO) -this else this

    fun pow(exponent: Int): Rational {
        require(exponent in -20..20) { "Exponent out of range: $exponent" }
        return when {
            exponent == 0 -> ONE
            exponent > 0 -> of(num.pow(exponent), den.pow(exponent))
            else -> {
                require(num != BigInteger.ZERO) { "Zero raised to negative power" }
                of(den.pow(-exponent), num.pow(-exponent))
            }
        }
    }

    fun toBigDecimal(scale: Int = 6, roundingMode: RoundingMode = RoundingMode.HALF_UP): BigDecimal {
        val mc = MathContext(maxOf(24, scale + 12), roundingMode)
        return BigDecimal(num).divide(BigDecimal(den), mc).setScale(scale, roundingMode)
    }

    override fun compareTo(other: Rational): Int = (num * other.den).compareTo(other.num * den)

    override fun toString(): String = if (den == BigInteger.ONE) num.toString() else "$num/$den"

    companion object {
        val ZERO: Rational = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE: Rational = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(numerator: Long, denominator: Long = 1L): Rational =
            of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

        fun of(numerator: BigInteger, denominator: BigInteger): Rational {
            require(denominator != BigInteger.ZERO) { "Denominator cannot be zero" }
            if (numerator == BigInteger.ZERO) return ZERO
            val sign = if (denominator.signum() < 0) -BigInteger.ONE else BigInteger.ONE
            val n = numerator * sign
            val d = denominator * sign
            val g = n.abs().gcd(d)
            return Rational(n / g, d / g)
        }

        fun parseDecimal(token: String): Rational {
            val clean = token.trim().replace(",", "")
            require(clean.isNotEmpty()) { "Empty numeric token" }
            val dot = clean.indexOf('.')
            if (dot < 0) {
                return of(BigInteger(clean), BigInteger.ONE)
            }
            val intPart = clean.substring(0, dot)
            val fracPart = clean.substring(dot + 1)
            require(fracPart.isNotEmpty() && fracPart.all { it.isDigit() }) { "Invalid decimal: $token" }
            val digits = (intPart.ifEmpty { "0" }) + fracPart
            val den = BigInteger.TEN.pow(fracPart.length)
            return of(BigInteger(digits), den)
        }
    }
}

data class RationalVerificationResult(
    val validExpression: Boolean,
    val exactRational: String = "",
    val verifiedValue: String = "",
    val matchesClaimed: Boolean = false,
    val errorMessage: String? = null,
)

object ExactRationalEvaluator {
    fun evaluate(rawExpression: String): Rational {
        val cleaned = normalizeExpression(rawExpression)
        require(cleaned.isNotEmpty()) { "Empty expression" }
        return Parser(cleaned).parse()
    }

    fun formatRounded(rational: Rational, decimalPlaces: Int = 2): String {
        val safeScale = decimalPlaces.coerceIn(0, 6)
        val bd = rational.toBigDecimal(safeScale, RoundingMode.HALF_UP)
        val normalized = if (bd.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO.setScale(safeScale) else bd
        return normalized.toPlainString()
    }

    fun verify(
        rawExpression: String,
        claimedExpected: String,
        decimalPlaces: Int = 2,
        tolerance: BigDecimal = BigDecimal("0.015"),
    ): RationalVerificationResult {
        val evaluated = runCatching { evaluate(rawExpression) }.getOrElse { err ->
            return RationalVerificationResult(
                validExpression = false,
                errorMessage = err.message ?: "invalid_expression",
            )
        }
        val roundedStr = formatRounded(evaluated, decimalPlaces)
        val roundedBd = BigDecimal(roundedStr)
        val claimedRational = runCatching {
            val trimmed = claimedExpected.trim().removePrefix("$").removePrefix("£").trim()
            if (trimmed.endsWith("%")) {
                evaluate(trimmed)
            } else {
                evaluate(trimmed)
            }
        }.getOrNull()
        val matches = if (claimedRational != null) {
            val claimedBd = claimedRational.toBigDecimal(maxOf(4, decimalPlaces + 2), RoundingMode.HALF_UP)
            val exactBd = evaluated.toBigDecimal(maxOf(4, decimalPlaces + 2), RoundingMode.HALF_UP)
            exactBd.subtract(claimedBd).abs() <= tolerance || roundedBd.subtract(claimedBd.setScale(decimalPlaces, RoundingMode.HALF_UP)).abs() <= tolerance
        } else {
            false
        }
        return RationalVerificationResult(
            validExpression = true,
            exactRational = evaluated.toString(),
            verifiedValue = roundedStr,
            matchesClaimed = matches,
        )
    }

    private fun normalizeExpression(raw: String): String = raw
        .replace("$", "")
        .replace("£", "")
        .replace(",", "")
        .replace("×", "*")
        .replace("÷", "/")
        .replace("**", "^")
        .replace("−", "-")
        .replace("–", "-")
        .trim()

    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Rational {
            val v = parseAddSub()
            skipSpaces()
            require(pos == src.length) { "Unexpected character at index $pos: '${src.getOrNull(pos)}'" }
            return v
        }

        private fun parseAddSub(): Rational {
            var left = parseMulDiv()
            while (true) {
                skipSpaces()
                left = when {
                    match('+') -> left + parseMulDiv()
                    match('-') -> left - parseMulDiv()
                    else -> return left
                }
            }
        }

        private fun parseMulDiv(): Rational {
            var left = parsePower()
            while (true) {
                skipSpaces()
                left = when {
                    match('*') -> left * parsePower()
                    match('/') -> left / parsePower()
                    else -> return left
                }
            }
        }

        private fun parsePower(): Rational {
            var base = parseUnary()
            skipSpaces()
            if (match('^')) {
                val expRat = parsePower()
                require(expRat.den == BigInteger.ONE) { "Rational exponent must be integer, got $expRat" }
                base = base.pow(expRat.num.toInt())
            }
            return base
        }

        private fun parseUnary(): Rational {
            skipSpaces()
            if (match('+')) return parseUnary()
            if (match('-')) return -parseUnary()
            return parsePrimary()
        }

        private fun parsePrimary(): Rational {
            skipSpaces()
            var value = if (match('(')) {
                val inner = parseAddSub()
                skipSpaces()
                require(match(')')) { "Missing closing parenthesis" }
                inner
            } else if (pos < src.length && src[pos].isLetter()) {
                val fnStart = pos
                while (pos < src.length && src[pos].isLetter()) pos++
                val fn = src.substring(fnStart, pos).lowercase()
                skipSpaces()
                require(match('(')) { "Expected '(' after function $fn" }
                val args = mutableListOf(parseAddSub())
                while (true) {
                    skipSpaces()
                    if (match(',')) args += parseAddSub() else break
                }
                skipSpaces()
                require(match(')')) { "Missing ')' after $fn arguments" }
                when (fn) {
                    "abs" -> {
                        require(args.size == 1) { "abs() takes 1 argument" }
                        args.single().abs()
                    }
                    "min" -> {
                        require(args.isNotEmpty()) { "min() requires >= 1 argument" }
                        args.min()
                    }
                    "max" -> {
                        require(args.isNotEmpty()) { "max() requires >= 1 argument" }
                        args.max()
                    }
                    else -> throw IllegalArgumentException("Unsupported function: $fn")
                }
            } else {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                require(pos > start) { "Expected number at index $pos" }
                Rational.parseDecimal(src.substring(start, pos))
            }
            skipSpaces()
            while (match('%')) {
                value /= Rational.of(100, 1)
                skipSpaces()
            }
            return value
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun match(ch: Char): Boolean {
            if (pos < src.length && src[pos] == ch) {
                pos++
                return true
            }
            return false
        }
    }
}
