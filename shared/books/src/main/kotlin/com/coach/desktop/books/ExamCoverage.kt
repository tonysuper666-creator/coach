package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

internal data class CoverageBankPoint(
    val name: String,
    val choices: Int,
    val essay: Boolean,
)

internal data class CoverageGap(
    val name: String,
    val reason: String,
)

internal data class CoverageReport(
    val heldOutPoints: Int,
    val covered: Int,
    val rate: Double,
    val uncovered: List<CoverageGap>,
    val coveredPoints: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("heldOutPoints", heldOutPoints)
        put("covered", covered)
        put("coverage", rate)
        put("neverGenerated", uncovered.count { it.reason == ExamCoverage.NEVER_GENERATED })
        put("droppedByCheck", uncovered.count { it.reason == ExamCoverage.QUESTION_DROPPED })
        put("coveredPoints", buildJsonArray {
            coveredPoints.forEach { add(JsonPrimitive(it)) }
        })
        put("uncovered", buildJsonArray {
            uncovered.forEach { gap ->
                add(buildJsonObject {
                    put("name", gap.name)
                    put("reason", gap.reason)
                })
            }
        })
    }
}

/**
 * Scores a course bank against points taken from held-out questions.
 * The question text is an input to the decomposer and is not written into the report.
 */
internal object ExamCoverage {
    const val TEXTBOOK_SILENT = "教材没讲"
    const val POINT_MISSED = "考点没识别出"
    const val NEVER_GENERATED = "从未生成"
    const val QUESTION_DROPPED = "识别了但题被核对丢掉"

    private val json = Json { ignoreUnknownKeys = true }

    fun evaluate(
        questions: List<String>,
        identified: List<String>,
        bank: List<CoverageBankPoint>,
        textbook: String,
    ): CoverageReport {
        val points = HeldOutDecomposer.decomposeQuestions(questions, identified, textbook)
        val textbookNorm = CourseExamPoints.normalize(textbook)
        val textbookSpaced = textbookNorm.replace('-', ' ')
        val coveredPoints = mutableListOf<String>()
        val uncovered = mutableListOf<CoverageGap>()
        for (point in points) {
            if (!point.matched) {
                uncovered += CoverageGap(point.name, POINT_MISSED)
                continue
            }
            val name = point.name
            val taught = termIn(name, textbookNorm, textbookSpaced)
            val known = identified.any { samePoint(name, it) }
            val item = bank.firstOrNull { samePoint(name, it.name) }
            val hasTeaching = item != null && (item.choices > 0 || item.essay)
            if (taught && known && hasTeaching) {
                coveredPoints += name
            } else {
                val reason = when {
                    !taught -> TEXTBOOK_SILENT
                    !known -> POINT_MISSED
                    item == null -> NEVER_GENERATED
                    else -> QUESTION_DROPPED
                }
                uncovered += CoverageGap(name, reason)
            }
        }
        val total = coveredPoints.size + uncovered.size
        val rate = if (total == 0) 0.0 else coveredPoints.size.toDouble() / total
        return CoverageReport(total, coveredPoints.size, rate, uncovered, coveredPoints)
    }

    fun readBank(path: Path): List<CoverageBankPoint> {
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val rows = root["points"] as? JsonArray ?: JsonArray(emptyList())
        return rows.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name").ifBlank { return@mapNotNull null }
            CoverageBankPoint(
                name = name,
                choices = (obj["choices"] as? JsonPrimitive)?.intOrNull ?: 0,
                essay = (obj["essay"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
    }

    fun readIdentified(path: Path?, bank: List<CoverageBankPoint>, teaching: List<ExamTeachingPage>): List<String> {
        if (teaching.isNotEmpty()) return CourseExamPoints.identifyFromPages("coverage", teaching).map { it.title }
        if (path != null && Files.exists(path)) {
            val root = json.parseToJsonElement(Files.readString(path)).jsonObject
            val rows = root["points"] as? JsonArray ?: return bank.map { it.name }
            return rows.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                obj.str("name").ifBlank { null }
            }
        }
        return bank.map { it.name }
    }

    fun readTeaching(path: Path?): List<ExamTeachingPage> {
        if (path == null || !Files.exists(path)) return emptyList()
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val rows = root["pages"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val text = obj.str("text")
            if (text.isBlank()) return@mapNotNull null
            ExamTeachingPage(
                bookId = obj.str("bookId").ifBlank { "book" },
                page = (obj["page"] as? JsonPrimitive)?.intOrNull ?: 1,
                role = CourseMaterialRole.fromWire(obj.str("role").ifBlank { "CORE_TEXTBOOK" }),
                text = text,
            )
        }
    }

    private fun termIn(name: String, textbookNorm: String, textbookSpaced: String): Boolean {
        val key = CourseExamPoints.normalize(name)
        if (key.length < 2 || textbookNorm.isBlank()) return false
        if (CourseExamPoints.termPattern(key).containsMatchIn(textbookNorm)) return true
        val keySpaced = key.replace('-', ' ')
        return CourseExamPoints.termPattern(keySpaced).containsMatchIn(textbookSpaced)
    }

    private fun samePoint(left: String, right: String): Boolean {
        val a = CourseExamPoints.normalize(left).replace('-', ' ')
        val b = CourseExamPoints.normalize(right).replace('-', ' ')
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length < 4) return false
        return CourseExamPoints.termPattern(short).containsMatchIn(long)
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

internal data class DecomposedQuestion(
    val name: String,
    val matched: Boolean,
)

/** Names the point a held-out question is asking. The stem itself is not returned. */
internal object HeldOutDecomposer {
    private val pointLine = Regex("""(?im)^\s*(?:考点|point)\s*[:：]\s*(.{2,80})\s*$""")
    private val explicitQuestionStart = Regex(
        """^\s*(?:(?:question|q)\s*\.?\s*\d{1,3}\b|第\s*\d{1,3}\s*题)(?:\s*[.)、:：])?(?:\s+.*|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val dotQuestionStart = Regex("""^\s*(\d{1,3})\.(?!\d)\s+\S.*$""")
    private val parenQuestionStart = Regex("""^\s*(\d{1,3})[)）、](?!\.)\s+\S.*$""")
    private val numberPrefix = Regex(
        """^\s*(?:(?:question|q)\s*\.?\s*\d{1,3}\s*[.)、:：]?\s*|\d{1,3}\s*[.)、:：]\s*)""",
        RegexOption.IGNORE_CASE,
    )
    private val partHeader = Regex(
        """(?i)^\s*(?:(?:part|section)\s+(?:[ivxlcdm]+|[a-z]|\d+|one|two|three|four|five)(?:\s*[:：\-–—.]\s*.*|\s+\(.*\)|\s+(?:multiple\s+choice|short\s+answer|long\s+answer|essay|problem|questions?|true\s*(?:/|or)\s*false|definitions?|calculations?|analysis).*|$)|第\s*[一二三四五六七八九十百\d]+\s*[部分节篇](?:.*)|[一二三四五六七八九十]+[、.]\s*(?:单项选择|多项选择|选择|填空|判断|简答|论述|计算|分析|名词解释|综合)题.*)$""",
    )
    private val instructionsHeader = Regex(
        """(?i)^\s*(?:(?:exam(?:ination)?|general|candidate)\s+)?(?:instructions|directions|rules|notes\s+to\s+candidates|read\s+(?:the\s+following\s+)?instructions)(?:\s+to\s+(?:candidates|students))?\s*[:：]?\s*$|^\s*(?:考场须知|考生须知|答题须知|注意事项)\s*[:：]?\s*$""",
    )
    private val instructionLine = Regex(
        """(?i)\b(?:student\s+(?:id|number|name)|candidate\s+number|seat\s+number|examination\s+number|write\s+your\s+(?:name|id|answers?)|do\s+not\s+(?:open|turn|write|remove)|calculators?\s+(?:are|is)\s+(?:not\s+)?(?:permitted|allowed)|(?:is|are)\s+(?:not\s+)?permitted\s+in\s+this\s+exam|time\s+allowed|this\s+(?:exam(?:ination)?|paper)\s+consists\s+of|answer\s+(?:all|any\s+\d+)\s+questions?|marks?\s+will\s+be\s+awarded|read\s+(?:all\s+)?instructions\s+carefully|use\s+(?:black|blue)\s+(?:pen|ink)|invigilator)\b|考生须知|考场须知|答题须知|不得携带|禁止使用计算器|请勿翻开|考试时间|共\s*\d+\s*页|满分\s*\d+\s*分|填写姓名|填写学号""",
    )
    private val draftOrBlankLine = Regex(
        """(?i)^\s*\[?\s*(?:this\s+page\s+(?:is\s+)?(?:intentionally\s+)?left\s+blank(?:\s+for\s+rough\s+work)?|intentionally\s+left\s+blank|blank\s+page|rough\s+work|draft\s+page|scrap\s+paper|space\s+for\s+rough\s+work|use\s+this\s+page\s+for\s+rough\s+work|additional\s+space(?:\s+for\s+answers?)?|草稿纸|草稿页|空白页|此页留白)\s*\]?\.?\s*$""",
    )
    private val endOfExamLine = Regex(
        """(?i)^\s*(?:end\s+of\s+(?:exam(?:ination)?|paper|test|questions?)|(?:[-–—*=#]\s*){2,}end\s+of\s+(?:exam(?:ination)?|paper|test)(?:\s*[-–—*=#]){2,}|试卷结束|全卷完|考试结束)\s*\.?\s*$""",
    )
    private val footerLine = Regex(
        """(?i)^\s*(?:page\s*\d+(?:\s*(?:of|/)\s*\d+)?|p\.?\s*\d+|第\s*\d+\s*页(?:\s*(?:/|共)\s*\d+\s*页)?|[-–—]\s*\d+\s*[-–—]|\d{1,3}|(?:please\s+)?turn\s+over|p\.?t\.?o\.?|continued\s+on\s+(?:the\s+)?next\s+page|©\s*.*|copyright\b.*)\s*$|\bpage\s+\d+\s+of\s+\d+\b|(?:please\s+)?turn\s+over\s*$""",
    )
    private val sectionSubtitleLine = Regex(
        """(?i)^\s*(?:\(?total\s*[:：]?\s*\d+\s*marks?\)?|\[?\d+\s*marks?\]?|answer\s+(?:all|any\s+\d+)\s+questions?.*|(?:multiple\s+choice|short\s+answer|long\s+answer|essay|problem)\s*(?:questions?)?(?:\s*\(.*\))?)\s*\.?\s*$""",
    )

    private val sectionNumber = Regex("""(?i)^(?:(?:chapter|section|lecture|week)\s+\d+(?:\.\d+)*|第[一二三四五六七八九十\d]+[章节周]|\d+(?:\.\d+)*)[\s.、:：—-]+""")
    private val definitionLine = Regex("""^([^:：]{2,64})[:：]\s*\S""")
    private val boldSpan = Regex("""\*\*([^*\n]{2,64})\*\*|__([^_\n]{2,64})__""")
    private val inlineDefinition = Regex(
        """(?:^|[.;]\s+)(?:The\s+|An?\s+)?([A-Za-z][A-Za-z0-9 -]{2,48})\s+(?:is\s+defined\s+as|refers\s+to|is\s+called|is\s+known\s+as)\b""",
    )
    private val capitalRun = Regex("""\b[A-Z][A-Za-z0-9]+(?:-[A-Z][A-Za-z0-9]+)*(?:\s+[A-Z][A-Za-z0-9]+(?:-[A-Z][A-Za-z0-9]+)*){1,5}\b""")
    private val romanNumeral = Regex("""^[ivxlcdm]+$""", RegexOption.IGNORE_CASE)
    private val boilerplate = setOf("contents", "outline", "summary", "references", "overview", "目录", "小结", "参考文献")
    private val glueWords = setOf(
        "of", "in", "on", "at", "to", "for", "and", "or", "the", "a", "an", "with", "by", "as",
        "is", "are", "was", "were", "be", "vs", "via", "per",
    )
    private val skip = setOf(
        "explain", "discuss", "compare", "contrast", "define", "calculate", "describe", "carefully",
        "what", "why", "how", "does", "do", "can", "could", "would", "should", "the", "and", "for",
        "with", "from", "that", "this", "are", "was", "were", "been", "being", "have", "has", "had",
        "into", "about", "between", "which", "when", "where", "your", "their", "there", "these",
        "those", "using", "used", "make", "makes", "provide", "including", "include", "answer",
        "question", "points", "point", "graph", "graphs", "model", "case", "than", "then", "also",
        "each", "such", "only", "over", "under", "after", "before", "within", "without",
        "suppose", "consider", "assume", "given", "find", "show", "prove", "determine", "identify",
        "state", "list", "briefly", "evaluate", "analyze", "analyse", "illustrate", "derive",
        "compute", "estimate", "justify", "verify", "sketch", "draw", "plot", "figure", "table",
        "chart", "panel", "exhibit", "appendix", "option", "choice", "section", "part", "marks",
        "please", "following", "below", "above", "true", "false", "correct", "incorrect",
    )

    private enum class QuestionStyle { EXPLICIT_Q, DOT, PAREN }

    private data class QuestionMarker(val style: QuestionStyle, val number: Int)

    private data class TermMatcher(
        val title: String,
        val fromIdentified: Boolean,
        val wordCount: Int,
        val length: Int,
        val acronym: String?,
        val prefilter: String,
        val exactRegex: Regex,
        val contentMatchers: List<Pair<String, Regex>>,
    )

    fun questionsFromDocument(text: String): List<String> {
        extractStructuredOriginalQuestions(text)?.let { return it }
        return extractRawExamQuestions(text)
    }

    private fun extractStructuredOriginalQuestions(text: String): List<String>? {
        val held = mutableListOf<String>()
        var capture = false
        var sawOriginal = false
        val buf = StringBuilder()
        fun flush() {
            val question = buf.toString().trim().trim('>', ' ')
            if (question.isNotEmpty() && "原题详解已移除" !in question) held += question
            buf.clear()
        }
        for (line in text.lineSequence()) {
            if (line.startsWith("### ")) {
                if (capture) flush()
                capture = line.removePrefix("### ").trim().startsWith("英文原题")
                if (capture) sawOriginal = true
                continue
            }
            if (capture && line.startsWith("## ")) {
                flush()
                capture = false
                continue
            }
            if (capture) buf.appendLine(line)
        }
        if (capture) flush()
        return if (sawOriginal) held else null
    }

    private fun extractRawExamQuestions(text: String): List<String> {
        val pages = splitExamPages(text)
        val repeatedLines = findRepeatedPageLines(pages)
        val filteredPages = pages.filterNot { isBlankOrDraftPage(it) }

        val questions = mutableListOf<StringBuilder>()
        var startedQuestions = false
        var sawStructuralMarker = false
        var inInstructions = false
        var inTrailingNoise = false
        var skipSubtitleLines = 0
        var lastInstructionNum = 0
        var currentSectionStyle: QuestionStyle? = null
        var lastQuestionNum = 0

        for (page in filteredPages) {
            if (pages.size >= 2) inTrailingNoise = false
            for (rawLine in page) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) {
                    skipSubtitleLines = 0
                    continue
                }
                if (PRINTED_PAGE_MARK.matches(trimmed) || trimmed in repeatedLines || footerLine.matches(trimmed)) {
                    sawStructuralMarker = true
                    continue
                }
                if (draftOrBlankLine.matches(trimmed) || endOfExamLine.matches(trimmed)) {
                    sawStructuralMarker = true
                    inTrailingNoise = true
                    continue
                }
                if (partHeader.matches(trimmed)) {
                    sawStructuralMarker = true
                    inInstructions = false
                    inTrailingNoise = false
                    skipSubtitleLines = 2
                    currentSectionStyle = null
                    lastQuestionNum = 0
                    continue
                }
                if (instructionsHeader.matches(trimmed)) {
                    sawStructuralMarker = true
                    inInstructions = true
                    inTrailingNoise = false
                    lastInstructionNum = 0
                    continue
                }
                if (inTrailingNoise) continue

                val marker = parseQuestionMarker(trimmed)
                if (inInstructions) {
                    val exitToQuestion = marker != null && !instructionLine.containsMatchIn(trimmed) && (
                        marker.style == QuestionStyle.EXPLICIT_Q ||
                            (lastInstructionNum > 0 && marker.number <= lastInstructionNum)
                        )
                    if (!exitToQuestion) {
                        if (marker != null) lastInstructionNum = marker.number
                        continue
                    }
                    inInstructions = false
                }

                if (!startedQuestions && marker != null && instructionLine.containsMatchIn(trimmed)) {
                    sawStructuralMarker = true
                    inInstructions = true
                    lastInstructionNum = marker.number
                    continue
                }

                if (skipSubtitleLines > 0) {
                    if (marker == null && (sectionSubtitleLine.matches(trimmed) || ('?' !in trimmed && trimmed.length <= 70))) {
                        skipSubtitleLines -= 1
                        continue
                    }
                    skipSubtitleLines = 0
                }

                if (marker != null && isNewQuestionStart(marker, currentSectionStyle, lastQuestionNum)) {
                    startedQuestions = true
                    sawStructuralMarker = true
                    if (currentSectionStyle == null || marker.style == QuestionStyle.EXPLICIT_Q) {
                        currentSectionStyle = marker.style
                    }
                    lastQuestionNum = marker.number
                    questions += StringBuilder(trimmed)
                } else if (startedQuestions && questions.isNotEmpty()) {
                    questions.last().append('\n').append(rawLine.trimEnd())
                }
            }
        }

        val parsed = questions.map { it.toString().trim() }.filter { it.length >= 12 }
        if (parsed.isNotEmpty() || sawStructuralMarker) return parsed
        val single = text.trim()
        return if (single.length >= 12 && !draftOrBlankLine.matches(single) && !footerLine.matches(single)) {
            listOf(single)
        } else {
            emptyList()
        }
    }

    private fun splitExamPages(text: String): List<List<String>> {
        val pages = mutableListOf<MutableList<String>>(mutableListOf())
        for (rawLine in text.lineSequence()) {
            if ('\u000C' in rawLine) {
                val parts = rawLine.split('\u000C')
                for ((idx, part) in parts.withIndex()) {
                    if (idx > 0) pages += mutableListOf<String>()
                    if (part.isNotBlank()) pages.last() += part
                }
                continue
            }
            if (PRINTED_PAGE_MARK.matches(rawLine.trim())) {
                if (pages.last().isNotEmpty()) pages += mutableListOf<String>()
                continue
            }
            pages.last() += rawLine
        }
        return pages.filter { page -> page.any { it.isNotBlank() } }
    }

    private fun findRepeatedPageLines(pages: List<List<String>>): Set<String> {
        if (pages.size < 2) return emptySet()
        val counts = mutableMapOf<String, Int>()
        for (page in pages) {
            val nonEmpty = page.map { it.trim() }.filter { it.isNotEmpty() }
            val edgeLines = (nonEmpty.take(2) + nonEmpty.takeLast(2))
                .filter { it.length in 4..100 && parseQuestionMarker(it) == null && !footerLine.matches(it) }
                .toSet()
            for (line in edgeLines) counts[line] = (counts[line] ?: 0) + 1
        }
        return counts.filterValues { it >= 2 }.keys
    }

    private fun isBlankOrDraftPage(page: List<String>): Boolean {
        val nonEmpty = page.map { it.trim() }.filter { it.isNotEmpty() && !footerLine.matches(it) }
        if (nonEmpty.isEmpty()) return true
        val hasDraft = nonEmpty.any { draftOrBlankLine.matches(it) }
        if (!hasDraft) return false
        return nonEmpty.none { parseQuestionMarker(it) != null }
    }

    private fun isNewQuestionStart(
        marker: QuestionMarker,
        currentStyle: QuestionStyle?,
        lastQuestionNum: Int,
    ): Boolean = when (currentStyle) {
        null -> true
        QuestionStyle.EXPLICIT_Q -> marker.style == QuestionStyle.EXPLICIT_Q
        QuestionStyle.DOT -> marker.style == QuestionStyle.EXPLICIT_Q ||
            (marker.style == QuestionStyle.DOT && marker.number > lastQuestionNum)
        QuestionStyle.PAREN -> marker.style != QuestionStyle.PAREN || marker.number > lastQuestionNum
    }

    private fun parseQuestionMarker(trimmed: String): QuestionMarker? {
        if (explicitQuestionStart.matches(trimmed)) {
            val num = Regex("""\d{1,3}""").find(trimmed)?.value?.toIntOrNull() ?: return null
            if (num in 1..200) return QuestionMarker(QuestionStyle.EXPLICIT_Q, num)
        }
        dotQuestionStart.matchEntire(trimmed)?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@let
            if (num in 1..200) return QuestionMarker(QuestionStyle.DOT, num)
        }
        parenQuestionStart.matchEntire(trimmed)?.let { match ->
            val num = match.groupValues[1].toIntOrNull() ?: return@let
            if (num in 1..200) return QuestionMarker(QuestionStyle.PAREN, num)
        }
        return null
    }

    fun decompose(questions: List<String>): List<String> {
        val names = linkedSetOf<String>()
        for (question in questions) {
            val name = focus(question)
            if (name.length in 2..80) names += name
        }
        return names.toList()
    }

    fun decomposeQuestions(
        questions: List<String>,
        identified: List<String>,
        textbook: String,
    ): List<DecomposedQuestion> {
        val matchers = buildTermMatchers(identified, extractTextbookTerms(textbook))
        val results = mutableListOf<DecomposedQuestion>()
        val seen = mutableSetOf<String>()
        var unmatchedIndex = 0
        for (question in questions) {
            val name = focusOrNull(question, matchers)
            if (name != null && name.length in 2..80) {
                val key = CourseExamPoints.normalize(name).replace('-', ' ')
                if (seen.add(key)) results += DecomposedQuestion(name, matched = true)
            } else {
                unmatchedIndex += 1
                results += DecomposedQuestion("未识别考点 #$unmatchedIndex", matched = false)
            }
        }
        return results
    }

    fun focus(stem: String): String = focusOrNull(stem, emptyList()).orEmpty()

    private fun focusOrNull(stem: String, matchers: List<TermMatcher>): String? {
        pointLine.find(stem)?.let { return clean(it.groupValues[1]) }
        val body = stem.replace(numberPrefix, "").trim()
        bestTermMatch(body, matchers)?.let { return clean(it) }
        // Question stems open with Explain/Discuss/…. Those words are not the point name.
        val titled = capitalRun.findAll(body)
            .map { stripLeadingSkip(it.value) }
            .filter { it.length in 8..80 }
            .maxByOrNull { it.length }
        if (titled != null) return clean(titled)
        return null
    }

    private fun extractTextbookTerms(textbook: String): List<String> {
        if (textbook.isBlank()) return emptyList()
        val terms = linkedMapOf<String, String>()
        fun addTerm(raw: String) {
            val candidate = clean(raw.replace(sectionNumber, ""))
            if (candidate.length !in 2..64) return
            if (candidate.any { it in "。！？!?；;." } || candidate.none(Char::isLetter)) return
            val norm = CourseExamPoints.normalize(candidate).replace('-', ' ')
            if (norm.length < 2 || norm in boilerplate || norm in skip) return
            val words = norm.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty() || words.size > 6) return
            if (words.size == 1 && norm.all { it in 'a'..'z' || it in '0'..'9' }) {
                val isAcronym = candidate.length in 3..5 && candidate.all { it.isUpperCase() || it.isDigit() } && !romanNumeral.matches(candidate)
                if (!isAcronym && norm.length < 4) return
            }
            terms.putIfAbsent(norm, candidate)
        }

        for (rawLine in textbook.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || PRINTED_PAGE_MARK.matches(line)) continue
            when {
                line.startsWith("#") -> addTerm(line.trimStart('#', ' '))
                definitionLine.containsMatchIn(line) -> addTerm(definitionLine.find(line)!!.groupValues[1])
                sectionNumber.containsMatchIn(line) -> addTerm(line)
            }
            for (m in boldSpan.findAll(line)) {
                addTerm(m.groupValues[1].ifEmpty { m.groupValues[2] })
            }
            for (m in inlineDefinition.findAll(line)) {
                addTerm(stripLeadingSkip(m.groupValues[1]))
            }
            for (m in capitalRun.findAll(line)) {
                val stripped = stripLeadingSkip(m.value)
                if (stripped.length >= 4) addTerm(stripped)
            }
        }
        return terms.values.toList()
    }

    private fun buildTermMatchers(identified: List<String>, textbookTerms: List<String>): List<TermMatcher> {
        val matchers = mutableListOf<TermMatcher>()
        val seen = mutableSetOf<String>()
        fun register(rawTitle: String, fromIdentified: Boolean) {
            val trimmed = rawTitle.trim()
            if (trimmed.length < 2) return
            val strippedParen = trimmed.replace(Regex("""\s*\([^)]{1,16}\)\s*$"""), "").trim()
            val variants = if (strippedParen.length >= 2 && strippedParen != trimmed) listOf(trimmed, strippedParen) else listOf(trimmed)
            for (variant in variants) {
                val norm = CourseExamPoints.normalize(variant).replace('-', ' ')
                if (norm.length < 2 || norm in skip || norm in boilerplate || !seen.add(norm)) continue
                val tokens = norm.split(' ').filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue
                val isAcronym = tokens.size == 1 && variant.length in 3..5 &&
                    variant.all { it.isUpperCase() || it.isDigit() } && !romanNumeral.matches(variant)
                if (tokens.size == 1 && !isAcronym && norm.all { it in 'a'..'z' || it in '0'..'9' } && norm.length < 4) continue
                if (tokens.all { it in skip || it in glueWords }) continue

                val exactRegex = buildPhraseRegex(tokens, norm)
                val lastToken = tokens.last()
                val prefilter = if (lastToken.all { it in 'a'..'z' } && lastToken.length >= 5 && lastToken.endsWith("y") && lastToken[lastToken.length - 2] !in "aeiou") {
                    lastToken.dropLast(1)
                } else {
                    lastToken
                }
                val contentTokens = tokens.filter { token ->
                    (token.length >= 4 || token.any { c -> c !in 'a'..'z' && c !in '0'..'9' }) &&
                        token !in skip && token !in glueWords
                }
                val contentMatchers = if (contentTokens.size >= 2) {
                    contentTokens.map { token -> tokenPrefilter(token) to buildPhraseRegex(listOf(token), token) }
                } else {
                    emptyList()
                }
                matchers += TermMatcher(
                    title = rawTitle.trim(),
                    fromIdentified = fromIdentified,
                    wordCount = tokens.size,
                    length = norm.length,
                    acronym = if (isAcronym) variant else null,
                    prefilter = prefilter,
                    exactRegex = exactRegex,
                    contentMatchers = contentMatchers,
                )
            }
        }
        identified.forEach { register(it, fromIdentified = true) }
        textbookTerms.forEach { register(it, fromIdentified = false) }
        return matchers
    }

    private fun tokenPrefilter(token: String): String =
        if (token.all { it in 'a'..'z' } && token.length >= 5 && token.endsWith("y") && token[token.length - 2] !in "aeiou") {
            token.dropLast(1)
        } else {
            token
        }

    private fun buildPhraseRegex(tokens: List<String>, norm: String): Regex {
        val last = tokens.last()
        val lastPattern = when {
            last.all { it in 'a'..'z' } && last.length >= 5 && last.endsWith("y") && last[last.length - 2] !in "aeiou" ->
                Regex.escape(last.dropLast(1)) + "(?:y|ies)"
            last.all { it in 'a'..'z' } && last.length >= 4 &&
                (last.endsWith("s") || last.endsWith("x") || last.endsWith("z") || last.endsWith("ch") || last.endsWith("sh")) ->
                Regex.escape(last) + "(?:es)?"
            last.all { it in 'a'..'z' } && last.length >= 4 ->
                Regex.escape(last) + "s?"
            else -> Regex.escape(last)
        }
        val bodyPattern = if (tokens.size == 1) {
            lastPattern
        } else {
            tokens.dropLast(1).joinToString("""\s+""") { Regex.escape(it) } + """\s+""" + lastPattern
        }
        val start = if (norm.first() in 'a'..'z') "(?<![a-z0-9])" else ""
        val end = if (norm.last() in 'a'..'z') "(?![a-z0-9])" else ""
        return Regex(start + bodyPattern + end)
    }

    private fun bestTermMatch(body: String, matchers: List<TermMatcher>): String? {
        if (matchers.isEmpty() || body.isBlank()) return null
        val bodySearch = CourseExamPoints.normalize(body).replace('-', ' ')
        var bestTitle: String? = null
        var bestScore = -1
        for (matcher in matchers) {
            val tier = when {
                matcher.acronym != null -> {
                    val found = matcher.acronym in body &&
                        Regex("""(?<![A-Za-z0-9])${Regex.escape(matcher.acronym)}(?![A-Za-z0-9])""").containsMatchIn(body)
                    if (found) 2 else 0
                }
                matcher.prefilter in bodySearch && matcher.exactRegex.containsMatchIn(bodySearch) -> 2
                matcher.contentMatchers.isNotEmpty() &&
                    matcher.contentMatchers.all { (pre, regex) -> pre in bodySearch && regex.containsMatchIn(bodySearch) } -> 1
                else -> 0
            }
            if (tier == 0) continue
            val score = tier * 100_000 + matcher.wordCount * 1_000 + matcher.length * 2 + if (matcher.fromIdentified) 1 else 0
            if (score > bestScore) {
                bestScore = score
                bestTitle = matcher.title
            }
        }
        return bestTitle
    }

    private fun stripLeadingSkip(run: String): String =
        run.trim().split(Regex("""\s+""")).dropWhile { it.lowercase() in skip }.joinToString(" ")

    private fun clean(raw: String): String = raw.trim().trim('.', ',', ';', ':', '。', '，').take(80)
}
