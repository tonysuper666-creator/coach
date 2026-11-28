package com.coach.desktop.books

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer
import kotlin.math.abs

internal data class SanitizedNoteResult(
    val cleanText: String,
    val redactedCount: Int,
    val hasLeakage: Boolean,
)

internal data class NgramAuditResult(
    val passed: Boolean,
    val enOverlapCount: Int,
    val zhOverlapCount: Int,
    val matchedFragments: List<String> = emptyList(),
)

internal data class MathCheckResult(
    val passed: Boolean,
    val expression: String,
    val expected: BigDecimal,
    val actual: BigDecimal?,
    val reason: String? = null,
)

/**
 * Pure-Kotlin implementation of the four course quality and anti-leakage gates:
 * 1. Past-paper blocking + user-note spoiler sanitization
 * 2. Exact UTF-16 anchor verification across textbook/slides/transcript sources
 * 3. Bilingual zero-overlap gate (English 8-gram = 0, Chinese 12-gram = 0)
 * 4. Independent numerical/formula expression verifier (BigDecimal, 2 decimal places)
 */
internal object CourseClientGates {
    private val pastExamTitleRegex = Regex(
        """(?i)(?:past[\s_\-]*(?:exam|paper)|exam[\s_\-]*paper|mock[\s_\-]*exam[\s_\-]*20\d{2}|(?:19|20)\d{2}[\s_\-]*(?:exam|midterm|final|期末|期中|试卷|真题)|midterm[\s_\-]*20\d{2}|final[\s_\-]*20\d{2}|(?:历年|往年)?真题|(?:历年|往年)(?:试卷|考题|题))""",
    )
    private val leakageSentenceRegex = Regex(
        """(?i)(?:(?:19|20)\d{2}\s*(?:年)?\s*(?:\d{1,2}\s*月)?\s*(?:真题|原题|考试|期中|期末|考过|出过|考题|试卷|exam|paper|midterm|final)|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\s_-]*(?:19|20)\d{2}\s*(?:exam|paper|midterm|final|question)|(?:期中|期末|大考|统考|补考|往年|历年|去年|前年|以前|上次|上学期|今年)\s*(?:考过|出过|考了|考到过|真题|原题|试卷|考题)|(?:历年|往年|去年|前年|以前)?\s*(?:真题|原题|机经|押题原题|考过原题|必考原题)|考过(?:这题|这道|一道|原题|真题)|past[\s_-]*(?:exam|paper)|exam[\s_-]*paper|verbatim[\s_-]*(?:question|extract)|exact[\s_-]*question)""",
    )
    private val enWordRegex = Regex("""[a-z0-9]+""")

    fun isPastExamMaterial(title: String, sourceFilename: String? = null): Boolean {
        val combined = "$title ${sourceFilename.orEmpty()}"
        if ("大纲" in combined || "syllabus" in combined.lowercase() || "handbook" in combined.lowercase()) {
            return false
        }
        return pastExamTitleRegex.containsMatchIn(combined)
    }

    fun sanitizeUserNote(text: String): SanitizedNoteResult {
        if (text.isBlank()) return SanitizedNoteResult(text, 0, false)
        var redacted = 0
        val out = text.lines().joinToString("\n") { line ->
            if (leakageSentenceRegex.containsMatchIn(line)) {
                redacted++
                "[已过滤：笔记中的过往考题线索仅作提醒，不进入练习题干]"
            } else {
                line
            }
        }
        return SanitizedNoteResult(
            cleanText = out,
            redactedCount = redacted,
            hasLeakage = redacted > 0,
        )
    }

    fun verifyCitations(
        citations: List<CourseCitationRef>,
        pagesByBookId: Map<String, List<BookPage>>,
    ): List<CourseCitationRef> = citations.mapNotNull { ref ->
        verifySingleCitation(ref, pagesByBookId[ref.bookId].orEmpty())
    }

    fun verifySingleCitation(
        ref: CourseCitationRef,
        pages: List<BookPage>,
    ): CourseCitationRef? {
        if (pages.isEmpty() || ref.quote.isBlank()) return null
        val pageObj = pages.firstOrNull { it.number == ref.page }
        if (pageObj != null) {
            findVerifiedInPage(ref, pageObj)?.let { return it }
        }
        if (!ref.timestampLabel.isNullOrBlank()) {
            CourseTranscriptParser.findTimestampAnchor(pages, ref.timestampLabel)?.let { anchor ->
                return ref.copy(
                    page = anchor.pageNumber,
                    startUtf16 = anchor.startUtf16,
                    endUtf16 = anchor.endUtf16,
                    timestampLabel = anchor.timestampLabel,
                    verified = true,
                )
            }
        }
        return null
    }

    private fun findVerifiedInPage(ref: CourseCitationRef, page: BookPage): CourseCitationRef? {
        val text = page.text
        val s = ref.startUtf16
        val e = ref.endUtf16
        if (s in 0 until e && e <= text.length) {
            val slice = text.substring(s, e)
            if (slice == ref.quote || normalizeSpaces(slice) == normalizeSpaces(ref.quote)) {
                return ref.copy(startUtf16 = s, endUtf16 = e, verified = true)
            }
        }
        val exactIdx = text.indexOf(ref.quote)
        if (exactIdx >= 0) {
            return ref.copy(
                startUtf16 = exactIdx,
                endUtf16 = exactIdx + ref.quote.length,
                verified = true,
            )
        }
        findNormalizedUtf16Span(text, ref.quote)?.let { (normStart, normEnd) ->
            return ref.copy(
                startUtf16 = normStart,
                endUtf16 = normEnd,
                quote = text.substring(normStart, normEnd),
                verified = true,
            )
        }
        val snippet = ref.quote.trim().take(36)
        if (snippet.length >= 8) {
            val partialIdx = text.indexOf(snippet)
            if (partialIdx >= 0) {
                val endIdx = (partialIdx + ref.quote.length).coerceAtMost(text.length)
                return ref.copy(
                    startUtf16 = partialIdx,
                    endUtf16 = endIdx,
                    quote = text.substring(partialIdx, endIdx),
                    verified = true,
                )
            }
        }
        return null
    }

    /**
     * Maps a whitespace-collapsed, line-break-hyphen/soft-hyphen-joined citation quote back to
     * exact UTF-16 `[start, end)` in [raw], without relaxing any other matching rules.
     */
    internal fun findNormalizedUtf16Span(raw: String, query: String): Pair<Int, Int>? {
        findNormalizedUtf16SpanPass(raw, query, keepCompoundHyphen = false)?.let { return it }
        if (query.any(::isLineBreakHyphen)) {
            return findNormalizedUtf16SpanPass(raw, query, keepCompoundHyphen = true)
        }
        return null
    }

    private fun findNormalizedUtf16SpanPass(raw: String, query: String, keepCompoundHyphen: Boolean): Pair<Int, Int>? {
        val normQuery = normalizeForCitationMatch(query, keepCompoundHyphen)
        if (normQuery.length < 6) return null
        val normChars = StringBuilder(raw.length)
        val rawIndices = IntArray(raw.length + 1)
        var outLen = 0
        var lastWasSpace = true
        var i = 0
        while (i < raw.length) {
            val joinedNext = hyphenJoinNextIndex(raw, i)
            if (joinedNext >= 0) {
                if (keepCompoundHyphen && raw[i] != '\u00AD') {
                    rawIndices[outLen] = i
                    normChars.append('-')
                    outLen++
                    lastWasSpace = false
                }
                i = joinedNext
                continue
            }
            val ch = normalizeCitationChar(raw[i])
            if (ch.isWhitespace()) {
                if (!lastWasSpace && outLen > 0) {
                    rawIndices[outLen] = i
                    normChars.append(' ')
                    outLen++
                    lastWasSpace = true
                }
            } else {
                rawIndices[outLen] = i
                normChars.append(ch.lowercaseChar())
                outLen++
                lastWasSpace = false
            }
            i++
        }
        val haystack = normChars.toString().trimEnd()
        val hit = haystack.indexOf(normQuery)
        if (hit < 0) return null
        val endNorm = (hit + normQuery.length - 1).coerceAtMost(outLen - 1)
        val rawStart = rawIndices[hit]
        val rawEnd = (rawIndices[endNorm] + 1).coerceAtMost(raw.length)
        return if (rawStart in 0 until rawEnd) rawStart to rawEnd else null
    }

    private fun isHyphenJoinLetter(c: Char): Boolean =
        c.isLetter() && c.code !in 0x4E00..0x9FFF && c.code !in 0x3400..0x4DBF

    private fun isLineBreakHyphen(c: Char): Boolean =
        c == '-' || c == '\u2010' || c == '\u2011'

    private fun hyphenJoinNextIndex(text: String, i: Int): Int {
        if (i <= 0 || !isHyphenJoinLetter(text[i - 1])) return -1
        val ch = text[i]
        val isSoft = ch == '\u00AD'
        if (!isSoft && !isLineBreakHyphen(ch)) return -1
        var j = i + 1
        var sawSpace = false
        while (j < text.length && normalizeCitationChar(text[j]).isWhitespace()) {
            sawSpace = true
            j++
        }
        if (!isSoft && !sawSpace) return -1
        return if (j < text.length && isHyphenJoinLetter(text[j])) j else -1
    }

    private fun normalizeCitationChar(c: Char): Char = when (c) {
        '\u2010', '\u2011', '\u2212', '\u2013', '\u2014' -> '-'
        '\u2018', '\u2019' -> '\''
        '\u201c', '\u201d' -> '"'
        '\u00a0' -> ' '
        else -> c
    }

    private fun normalizeForCitationMatch(s: String, keepCompoundHyphen: Boolean): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var lastSpace = true
        var i = 0
        while (i < nfkc.length) {
            val joinedNext = hyphenJoinNextIndex(nfkc, i)
            if (joinedNext >= 0) {
                if (keepCompoundHyphen && nfkc[i] != '\u00AD') {
                    sb.append('-')
                    lastSpace = false
                }
                i = joinedNext
                continue
            }
            val ch = normalizeCitationChar(nfkc[i])
            if (ch.isWhitespace()) {
                if (!lastSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastSpace = true
                }
            } else {
                sb.append(ch.lowercaseChar())
                lastSpace = false
            }
            i++
        }
        return sb.toString().trim()
    }

    fun auditOverlap(generatedText: String, sourceTexts: List<String>): NgramAuditResult {
        if (generatedText.isBlank() || sourceTexts.isEmpty()) {
            return NgramAuditResult(passed = true, enOverlapCount = 0, zhOverlapCount = 0)
        }
        val sourceEnGrams = HashSet<String>()
        val sourceZhGrams = HashSet<String>()
        for (src in sourceTexts) {
            collectEnGrams(src, 8, sourceEnGrams)
            collectZhGrams(src, 12, sourceZhGrams)
        }
        val matches = mutableListOf<String>()
        val genEn = enWordRegex.findAll(generatedText.lowercase()).map { it.value }.toList()
        var enHits = 0
        if (genEn.size >= 8) {
            for (i in 0..(genEn.size - 8)) {
                val gram = genEn.subList(i, i + 8).joinToString(" ")
                if (gram in sourceEnGrams) {
                    enHits++
                    if (matches.size < 4) matches += gram
                }
            }
        }
        val genZh = extractZhOnly(generatedText)
        var zhHits = 0
        if (genZh.length >= 12) {
            for (i in 0..(genZh.length - 12)) {
                val gram = genZh.substring(i, i + 12)
                if (gram in sourceZhGrams) {
                    zhHits++
                    if (matches.size < 4) matches += gram
                }
            }
        }
        return NgramAuditResult(
            passed = enHits == 0 && zhHits == 0,
            enOverlapCount = enHits,
            zhOverlapCount = zhHits,
            matchedFragments = matches,
        )
    }

    fun evaluateExpression(rawExpr: String): BigDecimal? = runCatching {
        val cleaned = rawExpr
            .replace("$", "")
            .replace(",", "")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("**", "^")
            .replace("−", "-")
            .trim()
        if (cleaned.isEmpty()) return null
        MathParser(cleaned).parseExpression()
    }.getOrNull()

    fun verifyEqualityClaim(expression: String, expected: BigDecimal, tolerance: BigDecimal = BigDecimal("0.01")): MathCheckResult {
        val actual = evaluateExpression(expression)?.setScale(2, RoundingMode.HALF_UP)
        if (actual == null) {
            return MathCheckResult(false, expression, expected, null, "无法解析数学表达式")
        }
        val diff = actual.subtract(expected).abs()
        val ok = diff.compareTo(tolerance) <= 0
        return MathCheckResult(
            passed = ok,
            expression = expression,
            expected = expected.setScale(2, RoundingMode.HALF_UP),
            actual = actual,
            reason = if (ok) null else "算式结果为 $actual，与 $expected 相差 $diff",
        )
    }

    private fun collectEnGrams(text: String, n: Int, out: MutableSet<String>) {
        val tokens = enWordRegex.findAll(text.lowercase()).map { it.value }.toList()
        if (tokens.size < n) return
        for (i in 0..(tokens.size - n)) {
            out += tokens.subList(i, i + n).joinToString(" ")
        }
    }

    private fun collectZhGrams(text: String, n: Int, out: MutableSet<String>) {
        val zh = extractZhOnly(text)
        if (zh.length < n) return
        for (i in 0..(zh.length - n)) {
            out += zh.substring(i, i + n)
        }
    }

    private fun extractZhOnly(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch in '\u4E00'..'\u9FFF') append(ch)
        }
    }

    private fun normalizeSpaces(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private class MathParser(private val src: String) {
        private var pos = 0
        private val mc = MathContext.DECIMAL64

        fun parseExpression(): BigDecimal {
            val v = parseAddSub()
            skipSpaces()
            require(pos == src.length) { "Unexpected char at $pos" }
            return v
        }

        private fun parseAddSub(): BigDecimal {
            var left = parseMulDiv()
            while (true) {
                skipSpaces()
                left = when {
                    match('+') -> left.add(parseMulDiv(), mc)
                    match('-') -> left.subtract(parseMulDiv(), mc)
                    else -> return left
                }
            }
        }

        private fun parseMulDiv(): BigDecimal {
            var left = parsePower()
            while (true) {
                skipSpaces()
                left = when {
                    match('*') -> left.multiply(parsePower(), mc)
                    match('/') -> {
                        val right = parsePower()
                        require(abs(right.toDouble()) > 1e-12) { "Division by zero" }
                        left.divide(right, mc)
                    }
                    else -> return left
                }
            }
        }

        private fun parsePower(): BigDecimal {
            var base = parseUnary()
            skipSpaces()
            if (match('^')) {
                val exp = parsePower()
                val intExp = exp.toInt()
                base = if (exp.compareTo(BigDecimal(intExp)) == 0 && intExp in -20..20) {
                    if (intExp >= 0) base.pow(intExp, mc) else BigDecimal.ONE.divide(base.pow(-intExp, mc), mc)
                } else {
                    BigDecimal(Math.pow(base.toDouble(), exp.toDouble()), mc)
                }
            }
            return base
        }

        private fun parseUnary(): BigDecimal {
            skipSpaces()
            if (match('+')) return parseUnary()
            if (match('-')) return parseUnary().negate()
            return parsePrimary()
        }

        private fun parsePrimary(): BigDecimal {
            skipSpaces()
            var value = if (match('(')) {
                val inner = parseAddSub()
                skipSpaces()
                require(match(')')) { "Missing closing parenthesis" }
                inner
            } else {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                require(pos > start) { "Expected number at $pos" }
                BigDecimal(src.substring(start, pos))
            }
            skipSpaces()
            while (match('%')) {
                value = value.divide(BigDecimal("100"), mc)
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
