package com.coach.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer

/**
 * Exam-point essay ruler.
 *
 * Each point quotes one complete source statement. A literal match supplies evidence directly.
 * The blind graders can also locate paraphrases, which require a quote from the actual answer.
 */
internal object CourseEssayFramework {
    enum class Kind { FRAMEWORK, DEFINITION }
    enum class Usage { USED, WRONG, MISSING }

    data class Page(
        val bookId: String,
        val bookTitle: String,
        val role: String,
        val page: Int,
        val text: String,
        val authorityRank: Int,
    )

    data class Point(
        val id: String,
        val kind: Kind,
        val name: String,
        val statement: String,
        val bookId: String,
        val bookTitle: String,
        val role: String,
        val page: Int,
        val quote: String,
        val quoteAnchor: String,
    )

    data class Judgement(
        val point: Point,
        val usage: Usage,
        val studentQuote: String,
    )

    data class Vote(val correct: Boolean, val gap: String, val studentQuote: String = "")

    data class Reconciliation(val correct: Boolean, val gap: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val term = """[\p{L}][\p{L}'’-]*(?:\s+[\p{L}][\p{L}'’-]*){0,7}?"""
    private val glossary = Regex("""^($term)\s*[:：]\s*(.+)""")
    private val prose = Regex(
        """^(?:(?i:the|a|an)\s+)?($term)\s+(?:(?:clearly|always|also|usually)\s+)?(?i:is|are|refers? to|means?|describes?|measures?|represents?|""" +
            """focus(?:es)? on|looks? at|shows?|suggests?|explains?|predicts?|limits?|requires?|determines?)\s+(.+)""",
    )
    private val nonBody = Regex("""(?i)^(?:[•●▪]|(?:figure|table|chapter)\s+[\dA-Z]\b|[\d.]+\s|(?:https?://|www\.))""")
    private val whitespace = Regex("""\s+""")
    private val token = Regex("""[\p{L}0-9][\p{L}0-9'’-]*""")
    private val bothAnd = Regex("""(?i)\bboth\s+(.{2,48}?)\s+and\s+(.{2,48}?)(?=[.]|$)""")
    private val number = Regex("""(?<![A-Za-z0-9])[+-]?\d+(?:\.\d+)?%?""")
    private val negation = Regex("""(?<![a-z])(?:not|never|without|no)(?![a-z])""")
    private val droppedRoles = setOf("PAST_EXAM", "USER_NOTES", "SOLUTION_KEY", "SYLLABUS")
    private val genericNames = setOf(
        "note", "example", "figure", "table", "chapter", "section", "summary", "definition", "overview",
        "introduction", "contents", "reference", "references",
    )
    private val boundary = setOf(
        "use", "uses", "using", "when", "this", "that", "we", "is", "are", "in", "on", "for", "with",
        "by", "to", "as", "at", "from", "into", "over", "under", "after", "before", "our", "their", "its",
    )
    private val internalWord = setOf("of", "and", "the", "a", "an")
    private val stop = boundary + internalWord + setOf("it", "be", "or", "if", "than")

    const val CHECK_SYSTEM: String = """
        你在核对论述有没有把材料里的这一条用对。你看不到另一家的结论，也不要替学生重写。
        correct 为 true 表示用上了 statement 的含义，没有把定义说反。
        gap 用一句简体中文，不超过 40 字。用对了就写「和材料一致」。
        points 逐项核对。另在 findEvidence 的每条原句中，按含义找学生实际使用这一概念的证据。
        接受换词、翻译、计算和例子。只说不会用或与主题无关都不算。用反了仍算使用，但 correct 为 false。
        evidence 用从 0 开始的原句序号，quote 必须逐字摘自 answer，足以证明判断。找不到证据就省略该项。
        只输出 JSON：{"items":[{"id":"...","correct":true,"gap":"..."}],"evidence":[{"index":0,"quote":"...","correct":true,"gap":"..."}]}
    """

    fun extract(topicTitle: String, pages: List<Page>): List<Point> {
        val accepted = pages.filter { it.text.isNotBlank() && it.page > 0 && it.role.uppercase() !in droppedRoles }
        if (accepted.isEmpty()) return emptyList()
        val topicKey = normalize(topicTitle)
        val topicWords = words(topicKey)
        val candidates = mutableListOf<Candidate>()
        for (page in accepted) {
            val topicPage = topicKey.isEmpty() || normalize(page.text).contains(topicKey)
            val statements = sourceStatements(page.text)
            val glossaryPage = statements.isNotEmpty() && statements.all { glossary.matches(it.replace(whitespace, " ")) }
            for (source in statements) {
                val afterColon = source.substringAfter(':', "").trim()
                val quote = if (afterColon.firstOrNull()?.isUpperCase() == true &&
                    prose.matches(afterColon.replace(whitespace, " "))) afterColon else source
                val sentence = quote.replace(whitespace, " ")
                val entry = glossary.matchEntire(sentence)
                val match = entry ?: prose.matchEntire(sentence) ?: continue
                val name = match.groupValues[1]
                if (!usableName(name) || match.groupValues[2].length < 8) continue
                val overlap = words(name).intersect(topicWords).size
                if (overlap == 0 && !(glossaryPage && topicPage) && !name.containsHanTopic(topicKey)) continue
                val kind = if (isFrameworkName(name)) Kind.FRAMEWORK else Kind.DEFINITION
                candidates += Candidate(name, kind, quote, page, entry != null, overlap)
            }
        }
        val best = linkedMapOf<String, Candidate>()
        for (candidate in candidates) {
            val key = normalize(candidate.name)
            val previous = best[key]
            if (previous == null || better(candidate, previous)) best[key] = candidate
        }
        val ordered = best.values.sortedWith(
            compareByDescending<Candidate> { it.overlap }
                .thenBy { it.kind.ordinal }.thenBy { it.page.authorityRank }
                .thenBy { it.page.page }.thenBy { normalize(it.name) },
        )
        val kept = ordered
            .take(8)
            .map { candidate -> toPoint(candidate) }
        var frameworkCount = 0
        var definitionCount = 0
        return kept.map { point ->
            if (point.kind == Kind.FRAMEWORK) {
                frameworkCount += 1
                point.copy(id = "F$frameworkCount")
            } else {
                definitionCount += 1
                point.copy(id = "D$definitionCount")
            }
        }
    }

    private fun toPoint(candidate: Candidate): Point {
        val quote = candidate.statement
        return Point(
            id = "",
            kind = candidate.kind,
            name = candidate.name.trim(),
            statement = quote.replace(whitespace, " "),
            bookId = candidate.page.bookId,
            bookTitle = candidate.page.bookTitle,
            role = candidate.page.role,
            page = candidate.page.page,
            quote = quote,
            quoteAnchor = quote,
        )
    }

    fun judge(points: List<Point>, answer: String): List<Judgement> = points.map { point ->
        val alias = point.name.substringBeforeLast(' ', "")
        val matchedName = when {
            mentions(answer, point.name) -> point.name
            point.kind == Kind.FRAMEWORK && words(alias).isNotEmpty() && mentions(answer, alias) -> alias
            else -> ""
        }
        val namedQuote = originalSlice(answer, matchedName)
        val quote = namedQuote.ifEmpty { phraseSlice(point.statement, point.name, answer) }
        val usage = when {
            quote.isEmpty() -> Usage.MISSING
            namedQuote.isNotEmpty() && contradicted(point.statement, answer, matchedName) -> Usage.WRONG
            else -> Usage.USED
        }
        Judgement(point, usage, quote)
    }

    fun missedFrameworkIds(rows: List<Judgement>): Set<String> =
        rows.filter { it.point.kind == Kind.FRAMEWORK && it.usage == Usage.MISSING }.map { it.point.id }.toSet()

    fun reconcile(row: Judgement, haiku: Vote?, gpt: Vote?, retry: Vote?): Reconciliation {
        val page = row.point.page
        if (row.usage == Usage.MISSING) return Reconciliation(false, "没用到。回第${page}页补读。")
        if (row.usage == Usage.WRONG) return Reconciliation(false, "用到了，但和这一页的定义不一致。")
        if (haiku == null || gpt == null) return Reconciliation(false, "核对暂时不可用。先回第${page}页看原句。")
        if (haiku.correct == gpt.correct) {
            val fallback = if (haiku.correct) "用到了，和材料一致。" else "用到了，但没用对。回第${page}页看原句。"
            return Reconciliation(haiku.correct, cleanGap(haiku.gap, fallback))
        }
        if (retry != null && retry.correct == haiku.correct) {
            val fallback = if (haiku.correct) "用到了，和材料一致。" else "用到了，但没用对。回第${page}页看原句。"
            return Reconciliation(haiku.correct, cleanGap(haiku.gap, fallback))
        }
        return Reconciliation(false, "两家核对不一致。先回第${page}页看原句。")
    }

    fun blindUserPrompt(rows: List<Judgement>, answer: String, unmatched: List<Judgement>): String = buildJsonObject {
        put("points", buildJsonArray {
            for (row in rows) {
                add(buildJsonObject {
                    put("id", row.point.id)
                    put("name", row.point.name)
                    put("statement", row.point.statement)
                })
            }
        })
        put("answer", answer)
        put("findEvidence", buildJsonArray { unmatched.forEach { add(JsonPrimitive(it.point.statement)) } })
    }.toString()

    fun parseVotes(raw: String, unmatched: List<Judgement>, answer: String): Map<String, Vote> {
        val obj = extractObject(raw) ?: return emptyMap()
        fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val ungrounded = unmatched.map { it.point.id }.toSet()
        return buildMap {
            for (entry in (obj["items"] as? JsonArray).orEmpty()) {
                val row = entry as? JsonObject ?: continue
                val id = row.field("id")
                val correct = row.field("correct").toBooleanStrictOrNull() ?: continue
                if (id.isNotBlank() && id !in ungrounded) put(id, Vote(correct, row.field("gap")))
            }
            for (entry in (obj["evidence"] as? JsonArray).orEmpty()) {
                val row = entry as? JsonObject ?: continue
                val index = row.field("index").toIntOrNull() ?: continue
                val point = unmatched.getOrNull(index)?.point ?: continue
                val quote = row.field("quote")
                val correct = row.field("correct").toBooleanStrictOrNull() ?: continue
                if (quote.any(Char::isLetterOrDigit) && answer.contains(quote)) {
                    put(point.id, Vote(correct, row.field("gap"), quote))
                }
            }
        }
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().replace(Regex("""[\s\p{Z}]+"""), " ").trim()

    private fun better(next: Candidate, previous: Candidate): Boolean {
        if (next.page.authorityRank != previous.page.authorityRank) return next.page.authorityRank < previous.page.authorityRank
        if (next.fromDefinition != previous.fromDefinition) return next.fromDefinition
        return next.page.page < previous.page.page
    }

    private fun usableName(name: String): Boolean {
        val key = normalize(name)
        if (key.length !in 5..64 || key.none(Char::isLetter)) return false
        if (key.substringBefore(' ') in genericNames || key.split(' ').any { it in setOf("is", "are", "was", "were") }) return false
        val first = key.substringBefore(' ')
        if (first in boundary || first in setOf("the", "a", "an", "this", "that")) return false
        return true
    }

    private fun isFrameworkName(name: String): Boolean {
        val key = normalize(name)
        return key.endsWith(" framework") || key.endsWith(" model") || key.endsWith(" theory")
    }

    private fun String.containsHanTopic(topic: String): Boolean =
        any { it.code in 0x4E00..0x9FFF } && (topic.contains(this) || (topic.isNotBlank() && contains(topic)))

    private fun words(value: String): Set<String> =
        token.findAll(normalize(value)).map { it.value }.filter {
            it.length >= 3 && it !in topicStopWords
        }.toSet()

    private val topicStopWords = stop + setOf("how", "what", "why", "does", "based", "can", "model", "framework", "theory")

    /** Keep the original substring (including PDF line wraps) for the reader's page anchor. */
    private fun sourceStatements(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var start = 0
        var offset = 0
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            val heading = trimmed.isNotEmpty() && trimmed.none { it in ".!?:。：" } &&
                trimmed.split(whitespace).all { it.firstOrNull()?.isUpperCase() == true || it.lowercase() in stop }
            if (trimmed.isEmpty() || nonBody.containsMatchIn(trimmed) || heading) {
                blocks += text.substring(start, offset).trim()
                start = (offset + line.length + 1).coerceAtMost(text.length)
            }
            offset += line.length + 1
        }
        blocks += text.substring(start)
        return blocks.flatMap { block ->
            block.split(Regex("""(?<!\b[A-Z]\.)(?<=[.!?])\s+|(?<=。)\s*""")).map(String::trim)
        }.filter { sentence ->
            sentence.length in 15..600 && sentence.last() in ".。" &&
                (sentence.first().isUpperCase() || glossary.matches(sentence.replace(whitespace, " "))) &&
                sentence.none { it in "•▪?" } &&
                !sentence.contains("http", ignoreCase = true)
        }
    }

    private fun mentions(answer: String, name: String): Boolean {
        val normAnswer = normalize(answer)
        val normName = normalize(name)
        if (normName.length < 2) return false
        val pattern = Regex("(?<![a-z0-9])" + Regex.escape(normName) + "(?![a-z0-9])")
        val hits = pattern.findAll(normAnswer).toList()
        if (hits.isEmpty()) return false
        return hits.any { hit ->
            val sentenceStart = normAnswer.lastIndexOfAny(charArrayOf('.', '。'), hit.range.first - 1)
                .let { if (it < 0) 0 else it + 1 }
            val from = maxOf(sentenceStart, hit.range.first - 96)
            !negation.containsMatchIn(normAnswer.substring(from, hit.range.first))
        }
    }

    private fun phraseSlice(statement: String, name: String, answer: String): String {
        val body = normalize(statement).replace(normalize(name), " ")
        val raw = token.findAll(body).map { it.value }.toList()
        val normAnswer = normalize(answer)
        for (index in 0..raw.size - 4) {
            val gram = raw.subList(index, index + 4)
            if (gram.none { it.length >= 6 && it !in stop }) continue
            val phrase = gram.joinToString(" ")
            if (phrase.length < 18) continue
            if (normAnswer.contains(phrase)) {
                val quote = originalSlice(answer, phrase)
                if (quote.isNotEmpty()) return quote
            }
        }
        return ""
    }

    private fun contradicted(statement: String, answer: String, name: String): Boolean {
        val window = windowAround(answer, name) ?: return false
        val both = bothAnd.find(normalize(statement))
        if (both != null) {
            val right = both.groupValues[2].trim().lowercase()
            val rightWord = token.findAll(right).map { it.value }.lastOrNull { it.length >= 6 } ?: right.take(24)
            val folded = normalize(window)
            val only = Regex("""(?<![a-z])only(?![a-z])""").containsMatchIn(folded)
            val denies = rightWord.isNotBlank() &&
                Regex("""(?<![a-z])not(?![a-z]).{0,24}${Regex.escape(rightWord)}""").containsMatchIn(folded)
            if (only && denies) return true
        }
        val expected = magnitudes(statement)
        if (expected.isEmpty()) return false
        val nearby = magnitudes(window)
        return nearby.any { seen -> expected.none { kotlin.math.abs(it - seen) < 0.001 } }
    }

    private fun magnitudes(text: String): List<Double> = number.findAll(text).mapNotNull { match ->
        val raw = match.value.removeSuffix("%")
        val value = raw.toDoubleOrNull() ?: return@mapNotNull null
        if (value in 1900.0..2100.0 && !match.value.endsWith("%")) return@mapNotNull null
        value
    }.toList()

    private fun windowAround(answer: String, name: String): String? {
        val normAnswer = normalize(answer)
        val normName = normalize(name)
        val at = Regex("(?<![a-z0-9])" + Regex.escape(normName) + "(?![a-z0-9])").find(normAnswer)?.range?.first ?: return null
        val from = (at - 20).coerceAtLeast(0)
        val to = (at + normName.length + 90).coerceAtMost(normAnswer.length)
        return normAnswer.substring(from, to)
    }

    private fun originalSlice(text: String, name: String): String {
        if (name.isEmpty()) return ""
        val pattern = name.split(whitespace).joinToString("""\s+""") { Regex.escape(it) }
        return Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.value.orEmpty()
    }

    private fun cleanGap(gap: String, fallback: String): String {
        val trimmed = gap.replace('\n', ' ').trim()
        val han = trimmed.count { it.code in 0x4E00..0x9FFF }
        if (han < 4) return fallback
        return trimmed.take(42)
    }

    private fun extractObject(raw: String): JsonObject? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private data class Candidate(
        val name: String,
        val kind: Kind,
        val statement: String,
        val page: Page,
        val fromDefinition: Boolean,
        val overlap: Int,
    )
}
