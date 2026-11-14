package com.coach.desktop.books.exam

import com.coach.desktop.books.*
import com.coach.domain.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Uses the existing mock paper/answer JSON shape; this first local slice supports mcq and essay. */
internal class LocalExamPaper(val snapshot: JsonObject) {
    val id = snapshot.str("id")
    val book = snapshot.str("bookId")
    val title = snapshot.str("title")
    val kind = snapshot.str("kind")
    val origin = snapshot.str("origin")
    val keyState = KeyState.UNREVIEWED
    val sections = (snapshot["sections"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    val items: List<LocalExamItem>
    val max: Long
    val hash: String get() = localExamHash(canonicalJson(snapshot))

    init {
        require(snapshot["schemaVersion"] == null || (snapshot["schemaVersion"] as? JsonPrimitive)?.intOrNull == 1) { "不支持的本地题卷版本。" }
        require(id.isNotBlank() && book.isNotBlank() && title.isNotBlank()) { "题卷缺少身份、教材或标题。" }
        require(kind in setOf("paper", "pack", "drill")) { "不支持的本地题卷类型。" }
        require(origin in setOf("AUTHORED", "TEXTBOOK_REFERENCE")) { "题卷必须声明自编或教材参考来源。" }
        require(snapshot.str("key_state").ifBlank { "UNREVIEWED" } == keyState.name) { "本地题键尚未经过独立核验。" }
        require(origin != "TEXTBOOK_REFERENCE" || kind == "drill") { "教材参考练习不能声明为正式试卷。" }
        require(sections.isNotEmpty()) { "题卷没有可用题目。" }
        items = sections.flatMap { section ->
            require(section["choose"] == null) { "本片暂不支持选做题，不能按整卷计分。" }
            val raw = (section["items"] as? JsonArray).orEmpty()
            require(raw.isNotEmpty()) { "题卷中有空的部分。" }
            raw.map { element ->
                val q = element.jsonObject
                require(q.str("key_state").ifBlank { "UNREVIEWED" } == keyState.name) { "题目答案尚未经过独立核验。" }
                val key = q.str("id")
                val type = q.str("type")
                val stem = q.str("stem")
                val marks = (q["marks"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
                val max = try { marks?.movePointRight(2)?.longValueExact() } catch (_: ArithmeticException) { null }
                require(key.isNotBlank() && stem.isNotBlank() && max != null && max > 0) { "题目缺少题号、题干或有效分值。" }
                require(type in setOf("mcq", "essay")) { "本片不支持题型：$type。" }
                val options = if (type == "mcq") q.strings("options").mapIndexed { i, text -> Option("o$i", text) } else emptyList()
                val correct = if (type == "mcq") {
                    val index = (q["answer"] as? JsonPrimitive)?.intOrNull
                    require(index != null && index in options.indices) { "题键缺失或答案下标越界。" }
                    "o$index".also { ChoiceKey(options, it) }
                } else null
                require(type != "essay" || q["options"] == null && q["answer"] == null) { "论述不能使用选择题答案。" }
                val source = q["source"] as? JsonObject ?: throw IllegalArgumentException("题目缺少教材出处。")
                require(source.str("docId") == book && source.str("lessonId").isNotBlank() &&
                    source.num("pdfPage") > 0 && source.str("quote").isNotBlank()) { "教材出处不完整或不属于本书。" }
                LocalExamItem(key, type, max, stem, options, correct, source)
            }
        }
        require(items.map { it.key }.distinct().size == items.size) { "题号重复。" }
        max = items.fold(0L) { sum, item -> Math.addExact(sum, item.max) }
    }

    /** Validate against the owned immutable book, then freeze actual page text, never caller-supplied text. */
    fun freeze(library: TextbookLibrary): LocalExamPaper {
        val textbook = library.book(book)
        val frozen = sections.map { section ->
            JsonObject(section + ("items" to JsonArray((section["items"] as JsonArray).map { element ->
                val q = element.jsonObject
                val source = q.getValue("source").jsonObject
                val lesson = textbook.lessons.find { it.id == source.str("lessonId") }
                    ?: throw IllegalArgumentException("题目出处不在这本教材的学习节中。")
                val page = textbook.pages.find { it.number.toLong() == source.num("pdfPage") }
                    ?: throw IllegalArgumentException("教材出处页不存在。")
                require(lesson.blocks.any { it.page == page.number } && source.str("quote") in page.text) { "原句不在声明的教材页中。" }
                JsonObject(q + ("source" to json("docId" to book, "lessonId" to lesson.id, "pdfPage" to page.number,
                    "quote" to source.str("quote"), "pageText" to page.text, "bookTitle" to textbook.title)))
            })))
        }
        return LocalExamPaper(JsonObject(snapshot + json("schemaVersion" to 1, "key_state" to keyState.name,
            "sections" to JsonArray(frozen))))
    }

    /** Same pre-submit redaction boundary as MockPaper; local storage is not an exam proctor. */
    fun redacted(): JsonObject = json("id" to id, "bookId" to book, "title" to title, "kind" to kind,
        "origin" to origin, "key_state" to keyState.name, "sections" to JsonArray(sections.map { section ->
            json("title" to section.str("title"), "instructions" to section.str("instructions"),
                "items" to JsonArray((section["items"] as JsonArray).map { element ->
                    JsonObject(element.jsonObject.filterKeys { it in setOf("id", "type", "stem", "marks", "options", "source") })
                }))
        }))

    fun clean(answers: JsonObject): JsonObject {
        require(answers.keys.all { key -> items.any { it.key == key } }) { "作答包含不属于本卷的题。" }
        return JsonObject(answers.mapValues { (key, raw) ->
            val item = items.first { it.key == key }
            val answer = raw as? JsonObject ?: throw IllegalArgumentException("作答格式不正确。")
            if (item.type == "mcq") {
                require(answer.keys.all { it == "choice" }) { "选择题只能保存选项。" }
                val value = answer["choice"]
                val choice = (value as? JsonPrimitive)?.intOrNull
                require(value == null || value == JsonNull || choice != null && choice in item.options.indices) { "选项下标越界。" }
                json("choice" to choice)
            } else {
                require(answer.keys.all { it == "text" }) { "论述只能保存文本。" }
                val value = answer["text"]
                require(value == null || value is JsonPrimitive && value.isString) { "论述作答必须是文本。" }
                val text = answer.str("text")
                require(text.length <= 20_000) { "论述超过20000字，请分段保存。" }
                json("text" to text)
            }
        })
    }

    /** Scores are hundredths, as in mock attempt facts; written answers need a real later review. */
    fun grade(attemptId: String, answers: JsonObject): JsonObject {
        val grades = items.map { item ->
            val answer = answers[item.key] as? JsonObject ?: json()
            val pending = item.type == "essay" && answer.str("text").isNotBlank()
            val correct = item.type == "mcq" &&
                (answer["choice"] as? JsonPrimitive)?.intOrNull?.let { "o$it" } == item.correctOptionId
            json("key" to item.key, "attemptId" to "$attemptId/${item.key}", "counted" to true, "max" to item.max,
                "score" to if (pending) null else if (correct) item.max else 0L,
                "status" to if (pending) "PENDING_REVIEW" else "EXACT", "key_state" to keyState.name,
                "source" to item.source)
        }
        val pending = grades.count { it.str("status") == "PENDING_REVIEW" }
        val known = grades.sumOf { it.num("score") }
        return json("score" to if (pending == 0) known else null, "knownScore" to known, "max" to max,
            "pending" to pending, "key_state" to keyState.name, "items" to JsonArray(grades))
    }
}

internal data class LocalExamItem(val key: String, val type: String, val max: Long, val stem: String,
    val options: List<Option>, val correctOptionId: String?, val source: JsonObject)

internal data class LocalPaperAvailability(val paper: LocalExamPaper?, val gaps: List<String>)

/** Reuses StudyCoachCourse's actual source practice. These are reference drills, never reviewed exam keys. */
internal fun textbookReferencePaper(book: Textbook): LocalPaperAvailability {
    val gaps = mutableListOf<String>()
    val items = book.lessons.mapNotNull { lesson ->
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)
        val page = practice?.let { p -> book.pages.firstOrNull { page ->
            lesson.blocks.any { it.page == page.number } && p.reference in page.text
        } }
        if (practice == null || page == null) {
            gaps += "${lesson.title}：缺少可用选择题或单页原句。"
            null
        } else json("id" to lesson.id, "type" to "mcq", "stem" to practice.prompt, "marks" to 1,
            "options" to JsonArray(practice.options.map(::JsonPrimitive)), "answer" to practice.options.indexOf(practice.answer),
            "source" to json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to page.number, "quote" to practice.reference))
    }
    if (items.isEmpty()) return LocalPaperAvailability(null, gaps.ifEmpty { listOf("教材没有可用于练习的文字和题目。") })
    return LocalPaperAvailability(LocalExamPaper(json("id" to "reference-${book.id}-v1", "bookId" to book.id,
        "title" to "${book.title} · 教材参考练习", "kind" to "drill", "origin" to "TEXTBOOK_REFERENCE",
        "key_state" to "UNREVIEWED", "sections" to JsonArray(listOf(json("title" to "原文选择",
            "items" to JsonArray(items)))))), gaps)
}

internal fun localExamHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
