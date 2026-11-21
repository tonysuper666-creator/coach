package com.coach.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

data class CourseMaterialTextBlock(
    val blockId: String,
    val bookId: String,
    val bookTitle: String,
    val role: String,
    val authorityRank: Int,
    val page: Int,
    val startUtf16: Int,
    val text: String,
)

data class CourseVerifiedCitation(
    val bookId: String,
    val bookTitle: String,
    val role: String,
    val page: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val timestampLabel: String? = null,
    val quote: String,
    val verified: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("bookId", bookId)
        put("bookTitle", bookTitle)
        put("role", role)
        put("page", page)
        put("startUtf16", startUtf16)
        put("endUtf16", endUtf16)
        timestampLabel?.let { put("timestampLabel", it) }
        put("quote", quote)
        put("verified", verified)
    }
}

data class CourseMathAssertion(
    val expression: String,
    val expected: BigDecimal,
    val tolerance: BigDecimal = BigDecimal("0.01"),
)

data class CourseMathAuditResult(
    val passed: Boolean,
    val checkedCount: Int,
    val failures: List<String> = emptyList(),
)

/**
 * Server-side enforcement of all 4 anti-leakage and quality gates for the course prep pipeline:
 * 1. Past-exam file blocking & user-note spoiler regex scrubbing
 * 2. Exact UTF-16 anchor verification across all source material blocks & `[mm:ss]` transcript offsets
 * 3. Bilingual zero-overlap guard (`en 8-gram = 0`, `zh 12-gram = 0`)
 * 4. Independent `BigDecimal` numerical/formula verifier (2 decimal places)
 */
object CourseQualityGates {
    private val pastExamRegex = Regex(
        """(?i)(?:past[\s_\-]*(?:exam|paper)|exam[\s_\-]*paper|mock[\s_\-]*exam[\s_\-]*20\d{2}|(?:19|20)\d{2}[\s_\-]*(?:exam|midterm|final|期末|期中|试卷|真题)|midterm[\s_\-]*20\d{2}|final[\s_\-]*20\d{2}|(?:历年|往年)?真题|历年(?:试卷|考题))""",
    )
    private val leakageLineRegex = Regex(
        """(?i)(?:(?:19|20)\d{2}\s*(?:年)?\s*(?:\d{1,2}\s*月)?\s*(?:真题|原题|考试|期中|期末|考过|出过|考题|试卷|exam|paper|midterm|final)|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\s_-]*(?:19|20)\d{2}\s*(?:exam|paper|midterm|final|question)|(?:期中|期末|大考|统考|补考|往年|历年|去年|前年|以前|上次|上学期|今年)\s*(?:考过|出过|考了|考到过|真题|原题|试卷|考题)|(?:历年|往年|去年|前年|以前)?\s*(?:真题|原题|机经|押题原题|考过原题|必考原题)|考过(?:这题|这道|一道|原题|真题)|past[\s_-]*(?:exam|paper)|exam[\s_-]*paper|verbatim[\s_-]*(?:question|extract)|exact[\s_-]*question)""",
    )
    private val inlineEquationRegex = Regex(
        """([$\s(+-]*[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?[%)\s]*(?:[+\-*/×÷^][$\s(+-]*[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?[%)\s]*)+)\s*=\s*([$]?[+-]?[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?%?)""",
    )
    private val isoDateOnlyRegex = Regex("""^\s*\d{4}-\d{1,2}-\d{1,2}\s*$""")
    private val enWordRegex = Regex("""[a-z0-9]+""")
    private val timestampRegex = Regex("""\[((?:\d{1,2}:)?\d{1,2}:\d{2})(?:[.,]\d{1,3})?\]""")

    fun isPastExamTitle(title: String): Boolean {
        val lower = title.lowercase()
        if ("syllabus" in lower || "handbook" in lower || "大纲" in title) return false
        return pastExamRegex.containsMatchIn(title)
    }

    fun sanitizeUserNote(text: String): Pair<String, Int> {
        if (text.isBlank()) return text to 0
        var redacted = 0
        val clean = text.lines().joinToString("\n") { line ->
            if (leakageLineRegex.containsMatchIn(line)) {
                redacted++
                "[已过滤：笔记中的过往考题线索不进入生成提示词]"
            } else {
                line
            }
        }
        return clean to redacted
    }

    fun verifyCitation(
        rawCitation: JsonObject,
        blocksByBookPage: Map<Pair<String, Int>, List<CourseMaterialTextBlock>>,
        allBlocksByBook: Map<String, List<CourseMaterialTextBlock>>,
    ): CourseVerifiedCitation? {
        val bookId = (rawCitation["bookId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val page = (rawCitation["page"] as? JsonPrimitive)?.intOrNull ?: 1
        val quote = (rawCitation["quote"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val tsLabel = (rawCitation["timestampLabel"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val reqStart = (rawCitation["startUtf16"] as? JsonPrimitive)?.intOrNull ?: -1
        val reqEnd = (rawCitation["endUtf16"] as? JsonPrimitive)?.intOrNull ?: -1
        if (bookId.isBlank() || quote.isBlank()) return null

        val pageBlocks = blocksByBookPage[bookId to page].orEmpty()
        for (blk in pageBlocks) {
            matchInsideBlock(blk, quote, tsLabel, reqStart, reqEnd)?.let { return it }
        }
        val bookBlocks = allBlocksByBook[bookId].orEmpty()
        if (!tsLabel.isNullOrBlank()) {
            for (blk in bookBlocks) {
                val tsIdx = findTimestampMatchIndex(blk.text, tsLabel)
                if (tsIdx >= 0) {
                    val lineEnd = blk.text.indexOf('\n', tsIdx).let { if (it < 0) blk.text.length else it }
                    val excerpt = blk.text.substring(tsIdx, lineEnd)
                    return CourseVerifiedCitation(
                        bookId = blk.bookId,
                        bookTitle = blk.bookTitle,
                        role = blk.role,
                        page = blk.page,
                        startUtf16 = blk.startUtf16 + tsIdx,
                        endUtf16 = blk.startUtf16 + lineEnd,
                        timestampLabel = tsLabel,
                        quote = excerpt,
                        verified = true,
                    )
                }
            }
        }
        for (blk in bookBlocks) {
            matchInsideBlock(blk, quote, tsLabel, -1, -1)?.let { return it }
        }
        return null
    }

    private fun matchInsideBlock(
        blk: CourseMaterialTextBlock,
        quote: String,
        tsLabel: String?,
        reqStart: Int,
        reqEnd: Int,
    ): CourseVerifiedCitation? {
        val text = blk.text
        if (reqStart >= blk.startUtf16 && reqEnd > reqStart && (reqEnd - blk.startUtf16) <= text.length) {
            val localStart = reqStart - blk.startUtf16
            val localEnd = reqEnd - blk.startUtf16
            val slice = text.substring(localStart, localEnd)
            if (slice == quote || normalizeSpace(slice) == normalizeSpace(quote)) {
                val detectedTs = tsLabel ?: extractLeadingTimestamp(slice)
                return CourseVerifiedCitation(
                    bookId = blk.bookId,
                    bookTitle = blk.bookTitle,
                    role = blk.role,
                    page = blk.page,
                    startUtf16 = reqStart,
                    endUtf16 = reqEnd,
                    timestampLabel = detectedTs,
                    quote = slice,
                    verified = true,
                )
            }
        }
        val idx = text.indexOf(quote)
        if (idx >= 0) {
            val detectedTs = tsLabel ?: extractLeadingTimestamp(text.substring(0, idx + quote.length).lines().lastOrNull().orEmpty())
            return CourseVerifiedCitation(
                bookId = blk.bookId,
                bookTitle = blk.bookTitle,
                role = blk.role,
                page = blk.page,
                startUtf16 = blk.startUtf16 + idx,
                endUtf16 = blk.startUtf16 + idx + quote.length,
                timestampLabel = detectedTs,
                quote = quote,
                verified = true,
            )
        }
        val normMatch = Utf16AnchorVerifier.findNormalizedUtf16Span(text, quote)
        if (normMatch != null) {
            val rawSlice = text.substring(normMatch.first, normMatch.second)
            val detectedTs = tsLabel ?: extractLeadingTimestamp(rawSlice)
            return CourseVerifiedCitation(
                bookId = blk.bookId,
                bookTitle = blk.bookTitle,
                role = blk.role,
                page = blk.page,
                startUtf16 = blk.startUtf16 + normMatch.first,
                endUtf16 = blk.startUtf16 + normMatch.second,
                timestampLabel = detectedTs,
                quote = rawSlice,
                verified = true,
            )
        }
        return null
    }

    fun auditZeroOverlap(generatedTexts: List<String>, sourceTexts: List<String>): OverlapAudit {
        if (generatedTexts.all { it.isBlank() } || sourceTexts.isEmpty()) {
            return OverlapAudit(en8GramHits = 0, zh12GramHits = 0, passed = true)
        }
        val enSources = HashSet<String>()
        val zhSources = HashSet<String>()
        for (src in sourceTexts) {
            collectEnGrams(src, 8, enSources)
            collectZhGrams(src, 12, zhSources)
        }
        var enHits = 0
        var zhHits = 0
        val samples = mutableListOf<String>()
        for (gen in generatedTexts) {
            val words = enWordRegex.findAll(gen.lowercase()).map { it.value }.toList()
            if (words.size >= 8) {
                for (i in 0..(words.size - 8)) {
                    val g = words.subList(i, i + 8).joinToString(" ")
                    if (g in enSources) {
                        enHits += 8
                        if (samples.size < 4) samples += g
                    }
                }
            }
            val zh = zhCharsOnly(gen)
            if (zh.length >= 12) {
                for (i in 0..(zh.length - 12)) {
                    val g = zh.substring(i, i + 12)
                    if (g in zhSources) {
                        zhHits += 12
                        if (samples.size < 4) samples += g
                    }
                }
            }
        }
        return OverlapAudit(
            en8GramHits = enHits,
            zh12GramHits = zhHits,
            passed = enHits == 0 && zhHits == 0,
        )
    }

    fun scrubPromptOverlap(text: String, sourceTexts: List<String>): String {
        if (text.isBlank() || sourceTexts.isEmpty()) return text
        val combined = sourceTexts.joinToString("\n")
        var current = text
        repeat(12) {
            if (auditZeroOverlap(listOf(current), sourceTexts).passed) return current
            val next = VerbatimOverlapGuard.scrubOverlaps(current, combined)
            if (next == current) {
                return "[已改写以遵循原创防抄录门禁]"
            }
            current = next
        }
        return if (auditZeroOverlap(listOf(current), sourceTexts).passed) {
            current
        } else {
            "[已改写以遵循原创防抄录门禁]"
        }
    }

    fun extractInlineMathAssertions(texts: List<String>): List<CourseMathAssertion> {
        val seen = LinkedHashSet<Pair<String, BigDecimal>>()
        val out = mutableListOf<CourseMathAssertion>()
        for (rawText in texts) {
            if (rawText.isBlank()) continue
            for (m in inlineEquationRegex.findAll(rawText)) {
                val lhs = m.groupValues[1].trim()
                val rhsRaw = m.groupValues[2].trim()
                if (isoDateOnlyRegex.matches(lhs)) continue
                if (evaluateExpression(lhs) == null) continue
                val rhsVal = evaluateExpression(rhsRaw) ?: continue
                val key = lhs to rhsVal.setScale(2, RoundingMode.HALF_UP)
                if (seen.add(key)) {
                    out += CourseMathAssertion(expression = lhs, expected = rhsVal)
                }
            }
        }
        return out
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

    fun auditMathAssertions(assertions: List<CourseMathAssertion>): CourseMathAuditResult {
        if (assertions.isEmpty()) {
            return CourseMathAuditResult(
                passed = false,
                checkedCount = 0,
                failures = listOf("未提供可验算的 mathAssertions，Gate 4 不能以 checkedCount=0 通过"),
            )
        }
        val failures = mutableListOf<String>()
        for (a in assertions) {
            val actual = evaluateExpression(a.expression)?.setScale(2, RoundingMode.HALF_UP)
            val expectedScaled = a.expected.setScale(2, RoundingMode.HALF_UP)
            if (actual == null) {
                failures += "算式「${a.expression}」无法求值"
            } else if (actual.subtract(expectedScaled).abs().compareTo(a.tolerance) > 0) {
                failures += "算式「${a.expression}」结果为 $actual，预期 $expectedScaled"
            }
        }
        return CourseMathAuditResult(
            passed = failures.isEmpty(),
            checkedCount = assertions.size,
            failures = failures,
        )
    }

    private fun extractLeadingTimestamp(line: String): String? =
        timestampRegex.find(line)?.groupValues?.getOrNull(1)

    private fun findTimestampMatchIndex(text: String, tsLabel: String): Int {
        val normTarget = tsLabel.trim('[', ']', ' ')
        val exactBracket = "[$normTarget]"
        val idx = text.indexOf(exactBracket)
        if (idx >= 0) return idx
        return text.indexOf(normTarget)
    }

    private fun collectEnGrams(text: String, n: Int, out: MutableSet<String>) {
        val words = enWordRegex.findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < n) return
        for (i in 0..(words.size - n)) out += words.subList(i, i + n).joinToString(" ")
    }

    private fun collectZhGrams(text: String, n: Int, out: MutableSet<String>) {
        val zh = zhCharsOnly(text)
        if (zh.length < n) return
        for (i in 0..(zh.length - n)) out += zh.substring(i, i + n)
    }

    private fun zhCharsOnly(text: String): String = buildString(text.length) {
        for (ch in text) if (ch in '\u4E00'..'\u9FFF') append(ch)
    }

    private fun normalizeSpace(s: String): String = s.replace(Regex("\\s+"), " ").trim()

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
            var v = if (match('(')) {
                val inner = parseAddSub()
                skipSpaces()
                require(match(')')) { "Missing ')'" }
                inner
            } else {
                val start = pos
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
                require(pos > start) { "Expected number at $pos" }
                BigDecimal(src.substring(start, pos))
            }
            skipSpaces()
            while (match('%')) {
                v = v.divide(BigDecimal("100"), mc)
                skipSpaces()
            }
            return v
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
