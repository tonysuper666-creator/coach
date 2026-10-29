package com.coach.domain.grade

/**
 * ADR-0003 决定 6：逐字证据校验（服务端确定性，AI 只给引文）。
 *
 * 步骤：NFKC + 空白归一 + 各种减号统一成 `-` → 在归一化原答里找引文 → 起止必须落在**原答词元边界**上
 * → 门槛：有效字符 ≥8，或恰为所在整个分句（≥2 有效字符），或就是整个答案。
 * 区间由这里算，返回**原答**的 UTF-16 `[start, end)` 与原文片段。
 *
 * 词元（三类，互不吞并）：
 * - 每个汉字（含扩展 B 以后的星平面汉字）一个词元；
 * - 整个数字 `[+\-]?\d+([.,/:]\d+)*%?` 一个词元（所以 `0.25`、`1/4`、`-3/11`、`20%` 不会被切开）；
 * - 连续拉丁字母一个词元（数字后紧邻的汉字是另一个词元）。
 * 其余字符（空白、标点）都是分隔符。
 */
object VerbatimEvidence {

    /** 门槛之一：有效字符数。 */
    const val MIN_EFFECTIVE_CHARS = 8

    /** 门槛之二：恰为整个分句时所需的最少有效字符数（挡住「不」「是」这类单字伪证）。 */
    const val MIN_CLAUSE_EFFECTIVE_CHARS = 2

    fun verify(answer: String, quote: String): EvidenceCheck {
        val normalized = normalizeAnswer(answer)
        val text = normalized.text
        val query = normalizeQuote(quote)
        if (query.isEmpty()) return EvidenceCheck.Rejected(EvidenceReject.EMPTY_QUOTE, 0, 0)

        val quoteTokens = tokenize(query)
        if (quoteTokens.isEmpty()) {
            return EvidenceCheck.Rejected(EvidenceReject.SEPARATORS_ONLY, 0, 0)
        }
        val effectiveChars = quoteTokens.sumOf { textCodePoints(query, it.start, it.end) }
        val edges = tokenEdges(text)

        var best: EvidenceReject? = null
        var index = text.indexOf(query)
        var sawSubstring = false
        while (index >= 0) {
            sawSubstring = true
            val end = index + query.length
            val aligned = edges[index] && edges[end]
            val wholeAnswer = index == 0 && end == text.length
            val wholeClause = aligned && isWholeClause(text, index, end)
            val passesFloor = wholeAnswer ||
                effectiveChars >= MIN_EFFECTIVE_CHARS ||
                (wholeClause && effectiveChars >= MIN_CLAUSE_EFFECTIVE_CHARS)
            if (aligned && passesFloor) {
                if (normalized.exactStart[index] && normalized.exactStart[end]) {
                    val start = normalized.origin[index]
                    val stop = normalized.origin[end]
                    return EvidenceCheck.Accepted(
                        EvidenceSpan(start = start, end = stop, text = answer.substring(start, stop)),
                        effectiveChars = effectiveChars,
                        tokens = quoteTokens.size,
                    )
                }
                if (best == null) best = EvidenceReject.OFFSET_NOT_TRACEABLE
            } else if (aligned) {
                if (best == null || best == EvidenceReject.OFFSET_NOT_TRACEABLE) best = EvidenceReject.BELOW_FLOOR
            } else {
                if (best == null) best = EvidenceReject.CROSSES_TOKEN_BOUNDARY
            }
            index = text.indexOf(query, index + 1)
        }
        val reason = when {
            !sawSubstring -> EvidenceReject.NOT_IN_ANSWER
            else -> best ?: EvidenceReject.BELOW_FLOOR
        }
        return EvidenceCheck.Rejected(reason, effectiveChars, quoteTokens.size)
    }

    internal fun tokenize(value: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < value.length) {
            val cp = codePointAt(value, i)
            val width = charCount(cp)
            when {
                isHan(cp) -> {
                    tokens.add(Token(i, i + width))
                    i += width
                }
                isNumberStart(value, i) -> {
                    val end = scanNumber(value, i)
                    tokens.add(Token(i, end))
                    i = end
                }
                isLetterToken(cp) -> {
                    var end = i
                    while (end < value.length) {
                        val next = codePointAt(value, end)
                        if (!isLetterToken(next)) break
                        end += charCount(next)
                    }
                    tokens.add(Token(i, end))
                    i = end
                }
                else -> i += width
            }
        }
        return tokens
    }

    internal data class Token(val start: Int, val end: Int)

    /**
     * 「词元边界」= 不在任何词元内部的位置。标点、空白的前后都是边界，
     * 所以引文可以带着句末的 `。`/`.` 结尾（批改 prompt 正是要求逐字照抄整个分句）。
     */
    private fun tokenEdges(text: String): BooleanArray {
        val edges = BooleanArray(text.length + 1) { true }
        for (token in tokenize(text)) {
            var index = token.start + 1
            while (index < token.end) {
                edges[index] = false
                index++
            }
        }
        return edges
    }

    /** 引文是否恰好是原答里的一个完整分句（左右两侧紧邻分隔符或边界）。 */
    private fun isWholeClause(text: String, start: Int, end: Int): Boolean {
        var left = start
        while (left > 0 && !isClauseSeparator(text[left - 1])) left--
        var right = end
        while (right < text.length && !isClauseSeparator(text[right])) right++
        return left == start && right == end
    }

    private fun isClauseSeparator(ch: Char): Boolean {
        if (ch.isWhitespace()) return true
        return ch in CLAUSE_SEPARATORS
    }

    private val CLAUSE_SEPARATORS = "，。；;、！？!?：:,（）()【】「」『』“”\"'‘’…".toSet()

    private fun isNumberStart(value: String, index: Int): Boolean {
        val cp = codePointAt(value, index)
        if (isDigit(cp)) return true
        if (cp == '+'.code || cp == '-'.code) {
            val next = index + 1
            return next < value.length && isDigit(codePointAt(value, next))
        }
        return false
    }

    private fun scanNumber(value: String, from: Int): Int {
        var i = from
        val first = codePointAt(value, i)
        if (first == '+'.code || first == '-'.code) i += 1
        while (i < value.length && isDigit(codePointAt(value, i))) i += 1
        while (i + 1 < value.length && value[i] in NUMBER_GROUP_SEPARATORS && isDigit(codePointAt(value, i + 1))) {
            i += 1
            while (i < value.length && isDigit(codePointAt(value, i))) i += 1
        }
        if (i < value.length && value[i] == '%') i += 1
        return i
    }

    private val NUMBER_GROUP_SEPARATORS = ".,/:".toSet()

    private fun isDigit(cp: Int): Boolean = cp in '0'.code..'9'.code

    private fun isHan(cp: Int): Boolean =
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x2EBEF || cp in 0x30000..0x3134F

    /**
     * 字母词元比 ADR 写的「连续拉丁字母」宽：这里的 isLetter 也包括希腊字母（σ、β、γ、δ）、
     * 假名和星平面字母。计量课里公式记号就是希腊字母，按拉丁字母处理会把 σ 切成单字符、把词元边界搞乱。
     */
    private fun isLetterToken(cp: Int): Boolean = !isHan(cp) && isLetter(cp)

    private fun isLetter(cp: Int): Boolean = if (cp >= 0x10000) true else cp.toChar().isLetter()

    private fun textCodePoints(value: String, start: Int, end: Int): Int {
        var count = 0
        var i = start
        while (i < end) {
            i += charCount(codePointAt(value, i))
            count++
        }
        return count
    }

    internal fun codePointAt(value: String, index: Int): Int {
        val ch = value[index]
        if (ch.code in 0xD800..0xDBFF && index + 1 < value.length) {
            val low = value[index + 1]
            if (low.code in 0xDC00..0xDFFF) return 0x10000 + ((ch.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
        return ch.code
    }

    internal fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

    internal fun charsOf(codePoint: Int): String =
        if (codePoint < 0x10000) {
            codePoint.toChar().toString()
        } else {
            val value = codePoint - 0x10000
            val high = ((value shr 10) + 0xD800).toChar()
            val low = ((value and 0x3FF) + 0xDC00).toChar()
            charArrayOf(high, low).concatToString()
        }

    internal fun foldDashes(value: String): String {
        if (value.none { it in DASHES }) return value
        val builder = StringBuilder(value.length)
        for (ch in value) builder.append(if (ch in DASHES) '-' else ch)
        return builder.toString()
    }

    private val DASHES: Set<Char> = buildSet {
        add('\u2212') // MINUS SIGN
        for (ch in 0x2010..0x2015) add(ch.toChar()) // HYPHEN … HORIZONTAL BAR
        add('\uFE63') // SMALL HYPHEN-MINUS
        add('\uFF0D') // FULLWIDTH HYPHEN-MINUS
    }

    /** 同一条归一化管道，但不需要偏移映射（用于引文）。 */
    internal fun normalizeQuote(value: String): String {
        val builder = StringBuilder(value.length)
        var lastSpace = false
        var index = 0
        while (index < value.length) {
            val cp = codePointAt(value, index)
            val folded = foldDashes(nfkc(charsOf(cp)))
            val blank = folded.isEmpty() || folded.all { it.isWhitespace() }
            if (blank) {
                if (builder.isNotEmpty() && !lastSpace) {
                    builder.append(' ')
                    lastSpace = true
                }
            } else {
                builder.append(folded)
                lastSpace = false
            }
            index += charCount(cp)
        }
        while (builder.isNotEmpty() && builder.last() == ' ') builder.setLength(builder.length - 1)
        return builder.toString()
    }

    /**
     * 归一化原答，逐 UTF-16 位置记下它来自原答的哪个码点起点：
     * `origin[i]` 是归一化文本第 i 个 UTF-16 单元对应的原答 UTF-16 下标，
     * `exactStart[i]` 表示第 i 个位置是不是一个原答码点的起点（NFKC 把一个字符拆成多个时，只有第一个是）。
     */
    internal fun normalizeAnswer(answer: String): NormalizedAnswer {
        val text = StringBuilder(answer.length + 8)
        val origin = ArrayList<Int>(answer.length + 8)
        val exact = ArrayList<Boolean>(answer.length + 8)
        var lastSpace = false
        var keepEnd = 0
        var index = 0
        while (index < answer.length) {
            val cp = codePointAt(answer, index)
            val folded = foldDashes(nfkc(charsOf(cp)))
            val blank = folded.isEmpty() || folded.all { it.isWhitespace() }
            if (blank) {
                if (text.isNotEmpty() && !lastSpace) {
                    // 空白归一成一个空格；空格本身是原答里的合法边界（引文两端已被 trim，不会落在空格里）
                    text.append(' ')
                    origin.add(index)
                    exact.add(true)
                    lastSpace = true
                }
            } else {
                var offset = 0
                while (offset < folded.length) {
                    text.append(folded[offset])
                    origin.add(index)
                    exact.add(offset == 0)
                    offset++
                }
                lastSpace = false
                keepEnd = index + charCount(cp)
            }
            index += charCount(cp)
        }
        if (text.isNotEmpty() && text.last() == ' ') {
            text.setLength(text.length - 1)
            origin.removeAt(origin.size - 1)
            exact.removeAt(exact.size - 1)
        }
        // 末尾哨兵指向「最后一个保留字符之后」，不是原答长度：否则引到结尾的区间会把尾部空白/换行也包进去
        origin.add(keepEnd)
        exact.add(true)
        return NormalizedAnswer(
            text = text.toString(),
            origin = origin.toIntArray(),
            exactStart = exact.toBooleanArray(),
        )
    }

    internal class NormalizedAnswer(
        val text: String,
        val origin: IntArray,
        val exactStart: BooleanArray,
    )
}

/** 通过校验的区间：原答的 UTF-16 `[start, end)`，以及落在原答里的片段本身。 */
data class EvidenceSpan(val start: Int, val end: Int, val text: String)

sealed interface EvidenceCheck {
    val effectiveChars: Int
    val tokens: Int

    data class Accepted(
        val span: EvidenceSpan,
        override val effectiveChars: Int,
        override val tokens: Int,
    ) : EvidenceCheck

    data class Rejected(
        val reason: EvidenceReject,
        override val effectiveChars: Int,
        override val tokens: Int,
    ) : EvidenceCheck
}

/** 拒绝原因（ADR-0003：失败要带原因，好让修复重试只改一处）。 */
enum class EvidenceReject {
    EMPTY_QUOTE,
    SEPARATORS_ONLY,
    NOT_IN_ANSWER,
    CROSSES_TOKEN_BOUNDARY,
    BELOW_FLOOR,
    OFFSET_NOT_TRACEABLE,
}
