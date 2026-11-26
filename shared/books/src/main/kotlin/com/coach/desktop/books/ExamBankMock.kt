package com.coach.desktop.books

import com.coach.domain.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

internal data class BankQuestion(
    val id: String,
    val topicId: String,
    val topicTitle: String,
    val type: String,
    val stem: String,
    val options: List<String> = emptyList(),
    val answer: Int? = null,
    val marks: Int = 2,
    val bookId: String = "",
    val page: Int = 0,
    val quote: String = "",
    val pastOriginal: Boolean = false,
)

internal data class AssembledBankPaper(
    val items: List<BankQuestion>,
    val requested: List<Pair<String, Int>>,
    val pointsWithoutQuestions: List<String>,
)

internal data class PointScore(
    val topicId: String,
    val title: String,
    val earned: Long,
    val max: Long,
    val pending: Int,
) {
    val lost: Long get() = (max - earned).coerceAtLeast(0)
}

/** Timed paper built only from the local question bank. Past-paper originals are never included. */
internal object ExamBankMock {
    private val json = Json { ignoreUnknownKeys = true }
    private val countedMcq = Regex("""(\d+)\s*(?:道)?\s*(?:单选|选择题|mcq)""", RegexOption.IGNORE_CASE)
    private val countedEssay = Regex("""(\d+)\s*(?:道)?\s*(?:论述|简答|essay)""", RegexOption.IGNORE_CASE)
    private val countedCalc = Regex("""(\d+)\s*(?:道)?\s*(?:计算|calc)""", RegexOption.IGNORE_CASE)

    fun blueprint(format: String, durationMinutes: Int): List<Pair<String, Int>> {
        val text = format.trim()
        val lower = text.lowercase()
        val wantsMcq = countedMcq.containsMatchIn(lower) || listOf("单选", "选择", "mcq").any { it in lower }
        val wantsEssay = countedEssay.containsMatchIn(lower) || listOf("论述", "简答", "essay").any { it in lower }
        val wantsCalc = countedCalc.containsMatchIn(lower) || listOf("计算", "calc").any { it in lower }
        val anyNamed = wantsMcq || wantsEssay || wantsCalc
        val essay = countedEssay.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 6)
            ?: if (wantsEssay || !anyNamed) 1 else 0
        val calc = countedCalc.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 6)
            ?: if (wantsCalc) 1 else 0
        val explicitMcq = countedMcq.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 60)
        val mcq = explicitMcq ?: if (wantsMcq || !anyNamed) {
            val used = essay * 25 + calc * 15
            ((durationMinutes.coerceIn(15, 240) - used) / 3).coerceAtLeast(if (essay + calc == 0) 1 else 0)
        } else {
            0
        }
        return listOf("mcq" to mcq, "essay" to essay, "multi_part_calc" to calc).filter { it.second > 0 }
    }

    fun readPack(topicId: String, topicTitle: String, packJson: String?): List<BankQuestion> {
        if (packJson.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(packJson).jsonObject }.getOrNull() ?: return emptyList()
        val drill = root["drill"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<BankQuestion>()
        for (element in drill.arr("choices")) {
            val choice = element as? JsonObject ?: continue
            val options = choice.arr("options").mapNotNull { it as? JsonObject }
            val correct = options.indexOfFirst { (it["correct"] as? JsonPrimitive)?.booleanOrNull == true }
            val citation = options.getOrNull(correct.coerceAtLeast(0))?.get("citation") as? JsonObject
                ?: options.firstOrNull()?.get("citation") as? JsonObject
            out += BankQuestion(
                id = "${topicId}-${choice.str("id").ifBlank { "c${out.size}" }}",
                topicId = topicId,
                topicTitle = topicTitle,
                type = "mcq",
                stem = choice.str("stem"),
                options = options.map { it.str("text") },
                answer = correct.takeIf { it in options.indices },
                marks = 2,
                bookId = citation?.str("bookId").orEmpty(),
                page = citation?.int("page") ?: 0,
                quote = citation?.str("quote").orEmpty(),
                pastOriginal = choice.bool("pastOriginal") || flag(citation, "pastOriginal"),
            )
        }
        val essay = drill["essay"] as? JsonObject
        if (essay != null && !essay.bool("pastOriginal")) {
            val paragraph = essay.arr("paragraphs").firstOrNull() as? JsonObject
            val citation = paragraph?.get("citation") as? JsonObject
            out += BankQuestion(
                id = "$topicId-essay",
                topicId = topicId,
                topicTitle = topicTitle,
                type = "essay",
                stem = essay.str("thesis").ifBlank { paragraph?.str("claim").orEmpty() },
                marks = 10,
                bookId = citation?.str("bookId").orEmpty(),
                page = citation?.int("page") ?: 0,
                quote = citation?.str("quote").orEmpty(),
                pastOriginal = flag(paragraph, "pastOriginal") || flag(citation, "pastOriginal"),
            )
        }
        return out
    }

    fun bankCount(topicId: String, title: String, packJson: String?): Int =
        readPack(topicId, title, packJson).count { it.usable() }

    fun assemble(questions: List<BankQuestion>, topicIds: List<String>, format: String, durationMinutes: Int): AssembledBankPaper {
        val requested = blueprint(format, durationMinutes)
        val usable = questions.filter { it.usable() }
        val picked = mutableListOf<BankQuestion>()
        for ((type, count) in requested) picked += takeRoundRobin(usable.filter { it.type == type }, count)
        val covered = usable.map { it.topicId }.toSet()
        return AssembledBankPaper(
            items = picked,
            requested = requested,
            pointsWithoutQuestions = topicIds.filter { it !in covered },
        )
    }

    fun assemble(db: Connection, detail: CourseDetailSnapshot): AssembledBankPaper {
        val topics = detail.examinableTopics
        val questions = topics.flatMap { topic -> readPack(topic.topicId, topic.title, packJson(db, topic.topicId)) }
        return assemble(questions, topics.map { it.topicId }, detail.examProfile.examFormat, detail.examProfile.durationMinutes)
    }

    fun toPaper(scopeBookId: String, detail: CourseDetailSnapshot, assembled: AssembledBankPaper): JsonObject {
        val byType = listOf("mcq", "essay", "multi_part_calc")
        val sections = byType.mapNotNull { type ->
            val items = assembled.items.filter { it.type == type }
            if (items.isEmpty()) null else json(
                "title" to when (type) {
                    "mcq" -> "单选"
                    "essay" -> "论述"
                    else -> "计算"
                },
                "instructions" to "",
                "items" to JsonArray(items.map { item ->
                    val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                        "id" to JsonPrimitive(item.id),
                        "type" to JsonPrimitive(item.type),
                        "stem" to JsonPrimitive(item.stem),
                        "marks" to JsonPrimitive(item.marks),
                        "source" to json("docId" to item.bookId, "pdfPage" to item.page, "quote" to item.quote),
                        "topicId" to JsonPrimitive(item.topicId),
                        "weekLabel" to JsonPrimitive(item.topicTitle),
                        "key_state" to JsonPrimitive("UNREVIEWED"),
                    )
                    if (item.type == "mcq") {
                        fields["options"] = JsonArray(item.options.map { JsonPrimitive(it) })
                        fields["answer"] = JsonPrimitive(item.answer ?: 0)
                    }
                    JsonObject(fields)
                }),
            )
        }
        return json(
            "id" to "bank-${UUID.randomUUID()}",
            "bookId" to scopeBookId,
            "courseId" to detail.courseId,
            "title" to "${detail.title} 计时模考",
            "kind" to "paper",
            "origin" to "AUTHORED",
            "key_state" to "UNREVIEWED",
            "durationMinutes" to detail.examProfile.durationMinutes.coerceIn(15, 240),
            "closedBook" to detail.examProfile.closedBook,
            "basis" to "题库",
            "sections" to JsonArray(sections),
        )
    }

    fun scorePaper(paper: JsonObject, result: JsonObject): List<PointScore> {
        val grades = (result["items"] as? JsonArray)?.map { it.jsonObject }.orEmpty().associateBy { it.str("key") }
        val grouped = linkedMapOf<String, MutableList<JsonObject>>()
        val titles = linkedMapOf<String, String>()
        for (section in paper.arr("sections")) {
            val sec = section as? JsonObject ?: continue
            for (element in sec.arr("items")) {
                val item = element as? JsonObject ?: continue
                val topic = item.str("topicId").ifBlank { item.str("id") }
                titles.putIfAbsent(topic, item.str("weekLabel").ifBlank { topic })
                grouped.getOrPut(topic) { mutableListOf() }.add(grades[item.str("id")] ?: json())
            }
        }
        return grouped.map { (topic, rows) ->
            var earned = 0L
            var max = 0L
            var pending = 0
            for (grade in rows) {
                val waiting = grade.str("status") == "PENDING_REVIEW" || grade["score"] == null || grade["score"] is JsonNull
                if (waiting) pending++ else {
                    max += grade.num("max")
                    earned += grade.num("score")
                }
            }
            PointScore(topic, titles[topic] ?: topic, earned, max, pending)
        }
    }

    fun topLosses(scores: List<PointScore>, limit: Int = 3): List<PointScore> =
        scores.filter { it.lost > 0 }
            .sortedWith(compareByDescending<PointScore> { it.lost }.thenBy { it.topicId })
            .take(limit)

    fun schedulePins(today: LocalDate, daysRemaining: Int?, pointIds: List<String>): List<ExamTaskPin> {
        val ids = pointIds.distinct().take(3)
        if (ids.isEmpty()) return emptyList()
        val start = if (daysRemaining != null && daysRemaining <= 1) 0L else 1L
        val span = when {
            daysRemaining == null || daysRemaining < 0 -> 3
            daysRemaining == 0 -> 1
            else -> daysRemaining
        }.coerceAtLeast(1)
        return ids.mapIndexed { index, id -> ExamTaskPin(id, today.plusDays(start + (index % span))) }
    }

    private fun takeRoundRobin(pool: List<BankQuestion>, count: Int): List<BankQuestion> {
        if (count <= 0 || pool.isEmpty()) return emptyList()
        val byTopic = pool.groupBy { it.topicId }
        val topics = byTopic.keys.sorted()
        val cursor = topics.associateWith { 0 }.toMutableMap()
        val picked = mutableListOf<BankQuestion>()
        var moved = true
        while (picked.size < count && moved) {
            moved = false
            for (topic in topics) {
                if (picked.size >= count) break
                val list = byTopic.getValue(topic)
                val index = cursor.getValue(topic)
                if (index < list.size) {
                    picked += list[index]
                    cursor[topic] = index + 1
                    moved = true
                }
            }
        }
        return picked
    }

    private fun packJson(db: Connection, topicId: String): String? =
        db.prepareStatement("SELECT pack_payload_json FROM course_topics WHERE topic_id=?").use { sql ->
            sql.setString(1, topicId)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun BankQuestion.usable(): Boolean =
        !pastOriginal && stem.isNotBlank() && quote.isNotBlank() && bookId.isNotBlank() && page > 0 &&
            (type != "mcq" || (options.size >= 2 && answer != null))

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun flag(node: JsonObject?, key: String) = node != null && node.bool(key)
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
        ?: (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
        ?: 0L
}
