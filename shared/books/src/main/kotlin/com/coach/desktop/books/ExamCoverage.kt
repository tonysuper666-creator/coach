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
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("heldOutPoints", heldOutPoints)
        put("covered", covered)
        put("coverage", rate)
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
    const val QUESTION_DROPPED = "识别了但题被核对丢掉"

    private val json = Json { ignoreUnknownKeys = true }

    fun evaluate(
        questions: List<String>,
        identified: List<String>,
        bank: List<CoverageBankPoint>,
        textbook: String,
    ): CoverageReport {
        val points = HeldOutDecomposer.decompose(questions)
        val textbookNorm = CourseExamPoints.normalize(textbook)
        val uncovered = mutableListOf<CoverageGap>()
        var covered = 0
        for (name in points) {
            val taught = termIn(name, textbookNorm)
            val known = identified.any { samePoint(name, it) }
            val item = bank.firstOrNull { samePoint(name, it.name) }
            val hasTeaching = item != null && (item.choices > 0 || item.essay)
            if (taught && known && hasTeaching) {
                covered += 1
            } else {
                val reason = when {
                    !taught -> TEXTBOOK_SILENT
                    !known -> POINT_MISSED
                    else -> QUESTION_DROPPED
                }
                uncovered += CoverageGap(name, reason)
            }
        }
        val rate = if (points.isEmpty()) 0.0 else covered.toDouble() / points.size
        return CoverageReport(points.size, covered, rate, uncovered)
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

    private fun termIn(name: String, textbookNorm: String): Boolean {
        val key = CourseExamPoints.normalize(name)
        if (key.length < 2 || textbookNorm.isBlank()) return false
        return CourseExamPoints.termPattern(key).containsMatchIn(textbookNorm)
    }

    private fun samePoint(left: String, right: String): Boolean {
        val a = CourseExamPoints.normalize(left)
        val b = CourseExamPoints.normalize(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length < 4) return false
        return CourseExamPoints.termPattern(short).containsMatchIn(long)
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/** Names the point a held-out question is asking. The stem itself is not returned. */
internal object HeldOutDecomposer {
    private val pointLine = Regex("""(?im)^\s*(?:考点|point)\s*[:：]\s*(.{2,80})\s*$""")
    private val questionStart = Regex(
        """(?m)^\s*(?=(?:(?:Question|Q)\s*\d+\b|\d+[.)、]\s*|第\s*\d+\s*题))""",
        RegexOption.IGNORE_CASE,
    )
    private val numberPrefix = Regex(
        """^\s*(?:(?:question|q)\s*)?\d+\s*[.)、:：]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val capitalRun = Regex("""\b[A-Z][A-Za-z0-9]+(?:-[A-Z][A-Za-z0-9]+)*(?:\s+[A-Z][A-Za-z0-9]+(?:-[A-Z][A-Za-z0-9]+)*){1,5}\b""")
    private val skip = setOf(
        "explain", "discuss", "compare", "contrast", "define", "calculate", "describe", "carefully",
        "what", "why", "how", "does", "do", "can", "could", "would", "should", "the", "and", "for",
        "with", "from", "that", "this", "are", "was", "were", "been", "being", "have", "has", "had",
        "into", "about", "between", "which", "when", "where", "your", "their", "there", "these",
        "those", "using", "used", "make", "makes", "provide", "including", "include", "answer",
        "question", "points", "point", "graph", "graphs", "model", "case", "than", "then", "also",
        "each", "such", "only", "over", "under", "after", "before", "within", "without",
    )

    fun questionsFromDocument(text: String): List<String> {
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
        if (sawOriginal) return held
        return text.split(questionStart).map { it.trim() }.filter { it.length >= 12 }
    }

    fun decompose(questions: List<String>): List<String> {
        val names = linkedSetOf<String>()
        for (question in questions) {
            val name = focus(question)
            if (name.length in 2..80) names += name
        }
        return names.toList()
    }

    fun focus(stem: String): String {
        pointLine.find(stem)?.let { return clean(it.groupValues[1]) }
        val body = stem.replace(numberPrefix, "").trim()
        val titled = capitalRun.findAll(body).map { it.value.trim() }.maxByOrNull { it.length }
        if (titled != null && titled.length in 8..80) return clean(titled)
        val words = CourseExamPoints.normalize(body).split(' ').filter { it.length >= 4 && it !in skip }
        return clean(words.take(3).joinToString(" "))
    }

    private fun clean(raw: String): String = raw.trim().trim('.', ',', ';', ':', '。', '，').take(80)
}
