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
 * Definitions and frameworks are read off the course pages. A local miss stays a miss:
 * a model cannot clear it. Whether a used point was used correctly is asked of Haiku
 * and, separately, of a GPT checker that does not see Haiku's verdict. One disagreement
 * gets one more blind ask. Still apart, the point is not marked correct.
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

    data class Vote(val correct: Boolean, val gap: String)

    data class Reconciliation(val correct: Boolean, val gap: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val definitionLine = Regex("""^([^:：]{2,64})[:：]\s+(\S.{7,220})$""")
    private val marker = Regex("""(?i)(?<![a-z0-9])(framework|model|theory)(?![a-z0-9])""")
    private val token = Regex("""[a-z0-9][\w'-]*""")
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
        只输出 JSON：{"items":[{"id":"...","correct":true,"gap":"..."}]}
    """

    fun extract(topicTitle: String, pages: List<Page>): List<Point> {
        val accepted = pages.filter { it.text.isNotBlank() && it.page > 0 && it.role.uppercase() !in droppedRoles }
        if (accepted.isEmpty()) return emptyList()
        val topicKey = normalize(topicTitle)
        val relevant = if (topicKey.isBlank()) accepted else accepted.filter { normalize(it.text).contains(topicKey) }
        val scoped = if (relevant.isNotEmpty()) relevant else accepted
        val candidates = mutableListOf<Candidate>()
        for (page in scoped) {
            for (line in page.text.lineSequence().map(String::trim).filter { it.isNotEmpty() && it.length <= 300 }) {
                val match = definitionLine.matchEntire(line) ?: continue
                val name = match.groupValues[1].trim()
                if (!usableName(name)) continue
                val kind = if (isFrameworkName(name)) Kind.FRAMEWORK else Kind.DEFINITION
                candidates += Candidate(name, kind, line, page, fromDefinition = true)
            }
            for (sentence in sentences(page.text)) {
                for (name in frameworkNames(sentence)) {
                    val shown = originalSlice(page.text, name).ifBlank { name }
                    if (!usableName(shown)) continue
                    candidates += Candidate(shown, Kind.FRAMEWORK, sentence.trim(), page, fromDefinition = false)
                }
            }
        }
        val best = linkedMapOf<String, Candidate>()
        for (candidate in candidates) {
            val key = normalize(candidate.name)
            val previous = best[key]
            if (previous == null || better(candidate, previous)) best[key] = candidate
        }
        val ordered = best.values.sortedWith(
            compareBy<Candidate> { it.kind.ordinal }.thenBy { it.page.authorityRank }.thenBy { it.page.page }.thenBy { normalize(it.name) },
        )
        val kept = (ordered.filter { it.kind == Kind.FRAMEWORK } + ordered.filter { it.kind == Kind.DEFINITION })
            .take(8)
            .mapNotNull { candidate -> toPoint(candidate) }
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

    private fun toPoint(candidate: Candidate): Point? {
        val quote = quoteFor(candidate.page.text, candidate.statement, candidate.name)
        val anchor = originalSlice(candidate.page.text, candidate.name).ifBlank { quote.take(24) }
        if (quote.isBlank() || anchor.isBlank()) return null
        if (!candidate.page.text.contains(quote) || !candidate.page.text.contains(anchor)) return null
        return Point(
            id = "",
            kind = candidate.kind,
            name = candidate.name.trim(),
            statement = candidate.statement.trim(),
            bookId = candidate.page.bookId,
            bookTitle = candidate.page.bookTitle,
            role = candidate.page.role,
            page = candidate.page.page,
            quote = quote,
            quoteAnchor = anchor,
        )
    }

    private fun quoteFor(page: String, statement: String, name: String): String {
        if (statement.isNotBlank() && page.contains(statement)) return statement
        val line = page.lineSequence().map(String::trim).firstOrNull { it.contains(name, ignoreCase = true) }
        if (!line.isNullOrBlank() && page.contains(line)) return line
        return originalSlice(page, name)
    }

    fun judge(points: List<Point>, answer: String): List<Judgement> = points.map { point ->
        val named = mentions(answer, point.name)
        val phrased = !named && phraseUsed(point.statement, point.name, answer)
        val usage = when {
            !named && !phrased -> Usage.MISSING
            named && contradicted(point.statement, answer, point.name) -> Usage.WRONG
            else -> Usage.USED
        }
        val quote = when {
            named -> originalSlice(answer, point.name)
            phrased -> phraseSlice(point.statement, point.name, answer)
            else -> ""
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

    fun blindUserPrompt(rows: List<Judgement>, answer: String): String = buildJsonObject {
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
    }.toString()

    fun parseVotes(raw: String): Map<String, Vote> {
        val obj = extractObject(raw) ?: return emptyMap()
        val items = obj["items"] as? JsonArray ?: return emptyMap()
        return items.mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val id = (row["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val correct = (row["correct"] as? JsonPrimitive)?.contentOrNull
            if (id.isBlank() || correct == null) return@mapNotNull null
            id to Vote(correct.equals("true", ignoreCase = true), (row["gap"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
        }.toMap()
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
        if (key in genericNames) return false
        val first = key.substringBefore(' ')
        if (first in boundary || first in setOf("the", "a", "an", "this", "that")) return false
        return true
    }

    private fun isFrameworkName(name: String): Boolean {
        val key = normalize(name)
        return key.endsWith(" framework") || key.endsWith(" model") || key.endsWith(" theory")
    }

    private fun frameworkNames(text: String): List<String> {
        val norm = normalize(text)
        return marker.findAll(norm).mapNotNull { hit ->
            val before = token.findAll(norm.substring(0, hit.range.first)).map { it.value }.toList()
            val collected = mutableListOf<String>()
            for (word in before.asReversed()) {
                if (word in internalWord) {
                    collected += word
                    continue
                }
                if (word in boundary) break
                collected += word
                if (collected.count { it !in internalWord } >= 4) break
            }
            val content = collected.asReversed().dropWhile { it in internalWord || it in boundary }
            if (content.isEmpty()) return@mapNotNull null
            val name = (content + hit.value.lowercase()).joinToString(" ")
            name.takeIf { it.length in 5..80 }
        }.distinct().toList()
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

    private fun phraseUsed(statement: String, name: String, answer: String): Boolean {
        val body = normalize(statement).replace(normalize(name), " ")
        val raw = token.findAll(body).map { it.value }.toList()
        val normAnswer = normalize(answer)
        for (index in 0..raw.size - 4) {
            val gram = raw.subList(index, index + 4)
            if (gram.none { it.length >= 6 && it !in stop }) continue
            val phrase = gram.joinToString(" ")
            if (phrase.length >= 18 && normAnswer.contains(phrase)) return true
        }
        return false
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
            val at = normAnswer.indexOf(phrase)
            if (at < 0) continue
            return answerSlice(answer, at, phrase.length)
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

    private fun sentences(text: String): List<String> =
        text.split(Regex("""(?<=[.。])\s+""")).map(String::trim).filter { it.length in 12..300 }

    private fun originalSlice(text: String, name: String): String {
        val at = text.lowercase().indexOf(name.lowercase())
        if (at < 0) return ""
        return text.substring(at, (at + name.length).coerceAtMost(text.length))
    }

    private fun answerSlice(answer: String, normalizedAt: Int, normalizedLength: Int): String {
        val norm = normalize(answer)
        if (normalizedAt !in norm.indices) return ""
        val end = (normalizedAt + normalizedLength).coerceAtMost(norm.length)
        val wanted = norm.substring(normalizedAt, end)
        val at = answer.lowercase().indexOf(wanted)
        if (at < 0) return wanted
        return answer.substring(at, (at + wanted.length).coerceAtMost(answer.length))
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
    )
}
