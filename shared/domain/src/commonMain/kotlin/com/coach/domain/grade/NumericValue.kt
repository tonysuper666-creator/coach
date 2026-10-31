package com.coach.domain.grade

/**
 * ADR-0003 决定 7：计算题终值由服务端数值比对（带容差，解析常见中文数字），AI 只批过程。
 *
 * 认得：`0.25`、`-0.27`、`1/4`、`−3/11`（各种减号）、`20%`、`百分之二十五`、
 * `十一分之三`、`负十一分之三`、`二十五`、`一百二十`、`三点一四`、`六分之一`、`2.33万`。
 */
object NumericValue {

    const val DEFAULT_REL_TOLERANCE = 1e-6
    const val DEFAULT_ABS_TOLERANCE = 1e-9

    /** 解析成一个终值；认不出（不是单个数值）就返回 null，由调用方判「不适用」。 */
    fun parse(text: String): Double? {
        var body = VerbatimEvidence.normalizeQuote(text)
        if (body.isEmpty()) return null
        while (body.isNotEmpty() && body.last() in TRAILING_JUNK) body = body.dropLast(1)
        if (body.isEmpty()) return null
        return parseValue(body)?.takeIf { it.isFinite() }
    }

    fun matches(
        learner: String,
        expected: String,
        relTolerance: Double = DEFAULT_REL_TOLERANCE,
        absTolerance: Double = DEFAULT_ABS_TOLERANCE,
    ): Boolean {
        val a = parse(learner) ?: return false
        val b = parse(expected) ?: return false
        return close(a, b, relTolerance, absTolerance)
    }

    fun close(
        a: Double,
        b: Double,
        relTolerance: Double = DEFAULT_REL_TOLERANCE,
        absTolerance: Double = DEFAULT_ABS_TOLERANCE,
    ): Boolean {
        if (!a.isFinite() || !b.isFinite() || !relTolerance.isFinite() || !absTolerance.isFinite() || relTolerance < 0 || absTolerance < 0) return false
        val tolerance = maxOf(absTolerance, relTolerance * kotlin.math.abs(b))
        return kotlin.math.abs(a - b) <= tolerance
    }

    private fun parseValue(raw: String): Double? {
        val body = raw.trim()
        if (body.isEmpty()) return null
        if (body.startsWith("负")) return parseValue(body.drop(1))?.let { -it }
        if (body.startsWith("百分之")) {
            val part = parseValue(body.removePrefix("百分之")) ?: return null
            return part / 100.0
        }
        val fenZhi = body.lastIndexOf("分之")
        if (fenZhi > 0) {
            val denominator = parseValue(body.substring(0, fenZhi)) ?: return null
            val numerator = parseValue(body.substring(fenZhi + 2)) ?: return null
            if (denominator == 0.0) return null
            return numerator / denominator
        }
        if (body.endsWith("%")) {
            val part = parseValue(body.dropLast(1)) ?: return null
            return part / 100.0
        }
        val slash = body.indexOf('/')
        if (slash > 0 && slash < body.length - 1) {
            val numerator = parseValue(body.substring(0, slash)) ?: return null
            val denominator = parseValue(body.substring(slash + 1)) ?: return null
            if (denominator == 0.0) return null
            return numerator / denominator
        }
        body.toDoubleOrNull()?.let { return it.takeIf { n -> n.isFinite() } }
        if (body.last() in "万亿") {
            // Scale decimal/Arabic coefficients only; Chinese sections must be parsed together.
            val prefix=body.dropLast(1)
            if (prefix.toDoubleOrNull()!=null || prefix.contains('点')) {
                val part=parseValue(prefix) ?: return null
                return part * if(body.last()=='万') 10_000.0 else 100_000_000.0
            }
        }
        return parseChinese(body)
    }

    private fun parseChinese(raw: String): Double? {
        var body = raw.replace("负", "-")
        var negative = false
        if (body.startsWith("-")) {
            negative = true
            body = body.substring(1)
        }
        if (body.isEmpty()) return null
        var fraction = 0.0
        val point = body.indexOf('点')
        if (point >= 0) {
            val decimals = body.substring(point + 1)
            body = body.substring(0, point)
            if (decimals.isEmpty()) return null
            var scale = 0.1
            for (ch in decimals) {
                val digit = CHINESE_DIGITS[ch] ?: return null
                fraction += digit * scale
                scale /= 10
            }
            if (body.isEmpty()) {
                val value = fraction
                return if (negative) -value else value
            }
        }
        val whole = parseChineseInteger(body) ?: return null
        val value = whole + fraction
        return if (negative) -value else value
    }

    private fun parseChineseInteger(body: String): Double? {
        if (body.isEmpty()) return null
        if(body.all { it in CHINESE_DIGITS })
            return body.map { CHINESE_DIGITS.getValue(it).toInt() }.joinToString("").toDoubleOrNull()
        var lastUnit=10_000.0
        var total = 0.0
        var section = 0.0
        var number = 0.0
        var sawNumber = false
        var sawUnit = false
        for (ch in body) {
            val digit = CHINESE_DIGITS[ch]
            if (digit != null) {
                if (sawNumber && number != 0.0) return null // 零是占位；连续数字仅在纯数字串中接受
                number = digit
                sawNumber = true
                continue
            }
            val unit = CHINESE_UNITS[ch]
            when {
                ch == '万' || ch == '亿' -> {
                    val scale = if (ch == '万') 10_000.0 else 100_000_000.0
                    section += number
                    if (ch == '亿') {
                        total = (total + section) * scale
                    } else {
                        total += section * scale
                    }
                    lastUnit=10_000.0
                    section = 0.0
                    number = 0.0
                    sawNumber = false
                    sawUnit = true
                }
                unit != null -> {
                    if (unit >= lastUnit) return null
                    if (!sawNumber) {
                        if(unit!=10.0 || section!=0.0) return null
                        number=1.0 // 「十一」= 11
                    }
                    lastUnit=unit
                    section += number * unit
                    number = 0.0
                    sawNumber = false
                    sawUnit = true
                }
                else -> return null
            }
        }
        val result = total + section + number
        if (!sawUnit && !sawNumber) return null
        return result
    }

    private val CHINESE_DIGITS = mapOf(
        '零' to 0.0, '〇' to 0.0, '一' to 1.0, '二' to 2.0, '两' to 2.0, '三' to 3.0, '四' to 4.0,
        '五' to 5.0, '六' to 6.0, '七' to 7.0, '八' to 8.0, '九' to 9.0,
    )

    private val CHINESE_UNITS = mapOf('十' to 10.0, '百' to 100.0, '千' to 1000.0)

    private val TRAILING_JUNK = "。.，,；;！!？?、 \t".toSet()
}
