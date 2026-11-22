package com.coach.desktop.books.exam

import com.coach.desktop.books.*
import com.coach.domain.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Uses the mock paper/answer JSON shape; supports mcq, essay, multi_part_calc, and graph_analysis. */
internal class LocalExamPaper(val snapshot: JsonObject) {
    val id = snapshot.str("id")
    val book = snapshot.str("bookId")
    val courseId = snapshot.str("courseId")
    val isCourseMock: Boolean get() = courseId.isNotBlank()
    val title = snapshot.str("title")
    val kind = snapshot.str("kind")
    val origin = snapshot.str("origin")
    val keyState = KeyState.UNREVIEWED
    val isTentativeFormat: Boolean = (snapshot["isTentativeFormat"] as? JsonPrimitive)?.booleanOrNull ?: false
    val basis: String = snapshot.str("basis")
    val defaultDurationMinutes: Int? = (snapshot["durationMinutes"] as? JsonPrimitive)?.intOrNull
    val closedBook: Boolean? = (snapshot["closedBook"] as? JsonPrimitive)?.booleanOrNull
    val calculatorAllowed: String = snapshot.str("calculatorAllowed")
    val sections = (snapshot["sections"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    val items: List<LocalExamItem>
    val max: Long
    val hash: String get() = localExamHash(canonicalJson(snapshot))
    val hasStructuredGrading: Boolean
        get() = isCourseMock || items.any {
            it.rubricCriteria.isNotEmpty() || it.type in setOf("multi_part_calc", "graph_analysis")
        }

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
                require(type in setOf("mcq", "essay", "multi_part_calc", "graph_analysis")) { "本片不支持题型：$type。" }
                val options = if (type == "mcq") q.strings("options").mapIndexed { i, text -> Option("o$i", text) } else emptyList()
                val correct = if (type == "mcq") {
                    val index = (q["answer"] as? JsonPrimitive)?.intOrNull
                    require(index != null && index in options.indices) { "题键缺失或答案下标越界。" }
                    "o$index".also { ChoiceKey(options, it) }
                } else null
                require(type == "mcq" || (q["options"] == null && q["answer"] == null)) { "论述不能使用选择题答案。" }
                val source = q["source"] as? JsonObject ?: throw IllegalArgumentException("题目缺少教材出处。")
                if (isCourseMock) {
                    require(source.str("docId").isNotBlank() && source.num("pdfPage") > 0 && source.str("quote").isNotBlank()) {
                        "教材出处不完整或缺少页码原句。"
                    }
                } else {
                    require(source.str("docId") == book && source.str("lessonId").isNotBlank() &&
                        source.num("pdfPage") > 0 && source.str("quote").isNotBlank()) { "教材出处不完整或不属于本书。" }
                }
                val subQuestions = (q["subQuestions"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val sq = el as? JsonObject ?: return@mapNotNull null
                    val subId = sq.str("subId")
                    val prompt = sq.str("prompt")
                    if (subId.isBlank() || prompt.isBlank()) null else LocalExamSubQuestion(
                        subId = subId,
                        label = sq.str("label").ifBlank { "($subId)" },
                        prompt = prompt,
                        kind = sq.str("kind").ifBlank { if (sq["mathSpec"] != null) "calculation" else "analysis" },
                        marks = (sq["marks"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                        mathSpec = sq["mathSpec"] as? JsonObject,
                    )
                }
                val rubricCriteria = (q["rubricCriteria"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                LocalExamItem(
                    key = key,
                    type = type,
                    max = max,
                    stem = stem,
                    options = options,
                    correctOptionId = correct,
                    source = source,
                    subQuestions = subQuestions,
                    rubricCriteria = rubricCriteria,
                    topicId = q.str("topicId"),
                    weekLabel = q.str("weekLabel"),
                    conceptKey = q.str("conceptKey"),
                    targetErrorTag = q.str("targetErrorTag"),
                )
            }
        }
        require(items.map { it.key }.distinct().size == items.size) { "题号重复。" }
        max = items.fold(0L) { sum, item -> Math.addExact(sum, item.max) }
    }

    /** Validate against the owned immutable book(s), then freeze actual page text, never caller-supplied text. */
    fun freeze(library: TextbookLibrary): LocalExamPaper {
        if (!isCourseMock) {
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

        val bookCache = LinkedHashMap<String, Textbook>()
        fun loadBook(docId: String): Textbook = bookCache.getOrPut(docId) { library.book(docId) }
        val frozen = sections.map { section ->
            JsonObject(section + ("items" to JsonArray((section["items"] as JsonArray).map { element ->
                val q = element.jsonObject
                val source = q.getValue("source").jsonObject
                val docId = source.str("docId")
                val textbook = loadBook(docId)
                val page = textbook.pages.find { it.number.toLong() == source.num("pdfPage") }
                    ?: throw IllegalArgumentException("教材出处页不存在。")
                val rawQuote = source.str("quote")
                val resolvedQuote = when {
                    rawQuote in page.text -> rawQuote
                    else -> SentenceCitationGate.locatePassageInPage(docId, page, rawQuote)?.text
                        ?: throw IllegalArgumentException("原句不在声明的教材页中。")
                }
                val frozenSource = json(
                    "docId" to docId,
                    "lessonId" to source.str("lessonId").ifBlank { "course-page" },
                    "pdfPage" to page.number,
                    "quote" to resolvedQuote,
                    "pageText" to page.text,
                    "bookTitle" to textbook.title,
                )
                val rawCriteria = (q["rubricCriteria"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val frozenCriteria = if (rawCriteria.isEmpty()) null else JsonArray(
                    rawCriteria.map { cr ->
                        val crBookId = cr.str("sourceBookId").ifBlank { docId }
                        val crPageNo = cr.num("pdfPage").toInt().let { if (it > 0) it else page.number }
                        val crBook = runCatching { loadBook(crBookId) }.getOrNull() ?: textbook
                        val crPage = crBook.pages.find { it.number == crPageNo } ?: page
                        val crQuote = cr.str("quoteAnchor").ifBlank { resolvedQuote }
                        val excerpt = cr.str("sourceExcerpt").ifBlank {
                            val idx = crPage.text.indexOf(crQuote)
                            if (idx >= 0) {
                                val start = (idx - 220).coerceAtLeast(0)
                                val end = (start + 650).coerceAtMost(crPage.text.length)
                                crPage.text.substring(start, end).trim()
                            } else {
                                crPage.text.take(650).trim()
                            }
                        }
                        JsonObject(
                            cr + mapOf(
                                "sourceBookId" to JsonPrimitive(crBook.id),
                                "pdfPage" to JsonPrimitive(crPage.number),
                                "quoteAnchor" to JsonPrimitive(crQuote),
                                "sourceExcerpt" to JsonPrimitive(excerpt),
                            ),
                        )
                    },
                )
                val updated = LinkedHashMap(q)
                updated["source"] = frozenSource
                if (frozenCriteria != null) updated["rubricCriteria"] = frozenCriteria
                JsonObject(updated)
            })))
        }
        return LocalExamPaper(
            JsonObject(
                snapshot + json(
                    "schemaVersion" to 1,
                    "key_state" to keyState.name,
                    "sections" to JsonArray(frozen),
                ),
            ),
        )
    }

    /** Same pre-submit redaction boundary as MockPaper; never exposes answer keys, mathSpec, or rubricCriteria pre-submit. */
    fun redacted(): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(id)
        out["bookId"] = JsonPrimitive(book)
        if (courseId.isNotBlank()) out["courseId"] = JsonPrimitive(courseId)
        out["title"] = JsonPrimitive(title)
        out["kind"] = JsonPrimitive(kind)
        out["origin"] = JsonPrimitive(origin)
        out["key_state"] = JsonPrimitive(keyState.name)
        for (metaKey in listOf("isTentativeFormat", "basis", "durationMinutes", "closedBook", "calculatorAllowed")) {
            snapshot[metaKey]?.let { out[metaKey] = it }
        }
        out["sections"] = JsonArray(sections.map { section ->
            json("title" to section.str("title"), "instructions" to section.str("instructions"),
                "items" to JsonArray((section["items"] as JsonArray).map { element ->
                    val orig = element.jsonObject
                    val kept = LinkedHashMap(
                        orig.filterKeys {
                            it in setOf("id", "type", "stem", "marks", "options", "source", "topicId", "weekLabel", "conceptKey", "targetErrorTag")
                        },
                    )
                    (orig["subQuestions"] as? JsonArray)?.let { subs ->
                        kept["subQuestions"] = JsonArray(
                            subs.mapNotNull { subEl ->
                                val subObj = subEl as? JsonObject ?: return@mapNotNull null
                                JsonObject(subObj.filterKeys { it in setOf("subId", "label", "prompt", "kind", "marks") })
                            },
                        )
                    }
                    JsonObject(kept)
                }))
        })
        return JsonObject(out)
    }

    fun clean(answers: JsonObject): JsonObject {
        require(answers.keys.all { key -> items.any { it.key == key } }) { "作答包含不属于本卷的题。" }
        return JsonObject(answers.mapValues { (key, raw) ->
            val item = items.first { it.key == key }
            val answer = raw as? JsonObject ?: throw IllegalArgumentException("作答格式不正确。")
            when {
                item.type == "mcq" -> {
                    require(answer.keys.all { it == "choice" }) { "选择题只能保存选项。" }
                    val value = answer["choice"]
                    val choice = (value as? JsonPrimitive)?.intOrNull
                    require(value == null || value == JsonNull || choice != null && choice in item.options.indices) { "选项下标越界。" }
                    json("choice" to choice)
                }
                item.type in setOf("multi_part_calc", "graph_analysis") -> {
                    require(answer.keys.all { it in setOf("parts", "text") }) { "分步题只能保存小问作答或补充说明。" }
                    val cleaned = LinkedHashMap<String, JsonElement>()
                    val partsEl = answer["parts"]
                    if (partsEl != null && partsEl != JsonNull) {
                        val partsObj = partsEl as? JsonObject ?: throw IllegalArgumentException("小问作答格式不正确。")
                        var totalLen = 0
                        val cleanedParts = LinkedHashMap<String, JsonElement>()
                        for ((sid, rawVal) in partsObj) {
                            require(sid.isNotBlank()) { "小问编号不能为空。" }
                            require(rawVal is JsonPrimitive && rawVal.isString) { "小问作答必须是文本。" }
                            val txt = rawVal.content
                            require(txt.length <= 10_000) { "单个小问作答超过10000字，请精简。" }
                            totalLen += txt.length
                            require(totalLen <= 25_000) { "整题作答超过25000字，请分段保存。" }
                            cleanedParts[sid] = JsonPrimitive(txt)
                        }
                        cleaned["parts"] = JsonObject(cleanedParts)
                    }
                    val textEl = answer["text"]
                    if (textEl != null && textEl != JsonNull) {
                        require(textEl is JsonPrimitive && textEl.isString) { "补充说明必须是文本。" }
                        val txt = textEl.content
                        require(txt.length <= 20_000) { "论述超过20000字，请分段保存。" }
                        cleaned["text"] = JsonPrimitive(txt)
                    }
                    JsonObject(cleaned)
                }
                else -> {
                    require(answer.keys.all { it == "text" }) { "论述只能保存文本。" }
                    val value = answer["text"]
                    require(value == null || value is JsonPrimitive && value.isString) { "论述作答必须是文本。" }
                    val text = answer.str("text")
                    require(text.length <= 20_000) { "论述超过20000字，请分段保存。" }
                    json("text" to text)
                }
            }
        })
    }

    /** Scores are hundredths, as in mock attempt facts; written answers need a real later review or structured rubric grading. */
    fun grade(attemptId: String, answers: JsonObject): JsonObject {
        val grades = items.map { item ->
            val answer = answers[item.key] as? JsonObject ?: json()
            val hasWritten = when (item.type) {
                "essay" -> answer.str("text").isNotBlank()
                "multi_part_calc", "graph_analysis" -> {
                    val partsNonBlank = (answer["parts"] as? JsonObject)?.values
                        ?.any { (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true } == true
                    partsNonBlank || answer.str("text").isNotBlank()
                }
                else -> false
            }
            val correct = item.type == "mcq" &&
                (answer["choice"] as? JsonPrimitive)?.intOrNull?.let { "o$it" } == item.correctOptionId
            json("key" to item.key, "attemptId" to "$attemptId/${item.key}", "counted" to true, "max" to item.max,
                "score" to if (hasWritten) null else if (correct) item.max else 0L,
                "status" to if (hasWritten) "PENDING_REVIEW" else "EXACT", "key_state" to keyState.name,
                "source" to item.source)
        }
        val pending = grades.count { it.str("status") == "PENDING_REVIEW" }
        val known = grades.sumOf { it.num("score") }
        return json("score" to if (pending == 0) known else null, "knownScore" to known, "max" to max,
            "pending" to pending, "key_state" to keyState.name, "items" to JsonArray(grades))
    }
}

internal data class LocalExamSubQuestion(
    val subId: String,
    val label: String,
    val prompt: String,
    val kind: String,
    val marks: Double,
    val mathSpec: JsonObject? = null,
)

internal data class LocalExamItem(
    val key: String,
    val type: String,
    val max: Long,
    val stem: String,
    val options: List<Option>,
    val correctOptionId: String?,
    val source: JsonObject,
    val subQuestions: List<LocalExamSubQuestion> = emptyList(),
    val rubricCriteria: List<JsonObject> = emptyList(),
    val topicId: String = "",
    val weekLabel: String = "",
    val conceptKey: String = "",
    val targetErrorTag: String = "",
)

internal data class LocalPaperAvailability(val paper: LocalExamPaper?, val gaps: List<String>)

/** 2.0 已移除正则挖空题生成器（`StudyCoachCourse.practice`）；不再合成低质挖空参考练习。 */
internal fun textbookReferencePaper(book: Textbook): LocalPaperAvailability =
    LocalPaperAvailability(null, listOf("整本教材"))

internal fun localExamHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
