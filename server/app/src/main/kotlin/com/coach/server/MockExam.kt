package com.coach.server

import com.coach.ai.GradeGate
import com.coach.ai.AnalyticalNumericKey
import com.coach.ai.RubricItem
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import com.coach.store.MockSession
import com.coach.store.sha256
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.math.roundToLong

/** One answer slot: a choice, an essay, or one part of an analytical question. */
internal data class MockUnit(val key: String, val itemId: String, val section: Int, val type: String, val label: String,
    val marks: Double, val kp: String, val week: Int?, val stem: String, val options: List<String>, val answer: Int,
    val numeric: String?, val tolerance: String, val rubric: List<RubricItem>, val reference: String, val explanation: String,
    val keyState: String = "") {
    val serverNumeric get() = numeric != null && rubric.isEmpty()
    /** Rubric marks short of the part total belong to the server-checked final value. */
    val numericMarks get() = marks - rubric.sumOf { it.points }
    val gradingRubric get() = if (numeric != null && rubric.isNotEmpty() && numericMarks > 1e-9)
        rubric + RubricItem("N", "最终数值", numericMarks, "Server-checked against the frozen key") else rubric
}

internal class MockPaper(val json: JsonObject) {
    val id = json.str("id")
    val title = json.str("title").ifBlank { id }
    val units: List<MockUnit>
    val sections: List<JsonObject> = (json["sections"] as? JsonArray).orEmpty().map { it.jsonObject }
    init {
        require(id.isNotBlank()) { "缺少 id" }
        require(json.str("kind") in setOf("paper", "pack", "drill")) { "kind 只能是 paper 或 pack" }
        require(sections.isNotEmpty()) { "没有 section" }
        val out = mutableListOf<MockUnit>()
        sections.forEachIndexed { index, section ->
            val items = (section["items"] as? JsonArray).orEmpty().map { it.jsonObject }
            require(items.isNotEmpty()) { "第 ${index + 1} 部分没有题" }
            section.int("choose")?.let { require(it in 1..items.size) { "第 ${index + 1} 部分 choose 超出题数" } }
            for (item in items) out += units(item, index)
        }
        require(out.map { it.itemId }.distinct().size == out.distinctBy { it.itemId to it.section }.size) { "题号重复" }
        require(out.map { it.key }.distinct().size == out.size) { "题号重复" }
        units = out
    }
    val max: Double get() = sections.indices.sumOf { sectionMax(it) }
    fun sectionMax(section: Int): Double {
        val items = units.filter { it.section == section }.groupBy { it.itemId }.values.map { parts -> parts.sumOf { it.marks } }
        val choose = sections[section].int("choose") ?: items.size
        return items.sortedDescending().take(choose).sum()
    }

    private fun units(item: JsonObject, section: Int): List<MockUnit> {
        val id = item.str("id"); require(id.isNotBlank() && '#' !in id) { "题目缺少 id" }
        val marks = item.double("marks")
        val week = item.int("kp_week")
        fun rubric(raw: JsonElement?, where: String) = (raw as? JsonArray).orEmpty().mapIndexed { i, r ->
            val o = r.jsonObject
            RubricItem("R${i + 1}", o.str("criterion"), o.double("marks"), o.str("look_for")).also {
                require(it.text.isNotBlank() && it.points > 0) { "$where 第 ${i + 1} 条评分细则不完整" }
            }
        }
        return when (item.str("type")) {
            "mcq" -> {
                val options = item.strings("options")
                val answer = item.int("answer") ?: -1
                require(marks > 0 && item.str("stem").isNotBlank()) { "$id 缺少分值或题干" }
                require(options.size in 2..26 && options.all { it.isNotBlank() } &&
                    options.map(::normalizeChoiceText).distinct().size == options.size) { "$id 选项无效" }
                require(answer in options.indices) { "$id 答案下标越界" }
                listOf(MockUnit(id, id, section, "mcq", "", marks, item.str("kp"), week, item.str("stem"), options, answer,
                    null, "0", emptyList(), "", item.str("explanation"), item.str("key_state")))
            }
            "essay" -> {
                val r = rubric(item["rubric"], id)
                require(r.isNotEmpty() && kotlin.math.abs(r.sumOf { it.points } - marks) < 1e-6) { "$id 评分细则合计不等于分值" }
                val target = item.int("word_target")
                listOf(MockUnit(id, id, section, "essay", "", marks, item.str("kp"), week,
                    item.str("stem") + (target?.let { "\n\n（建议约 $it 词）" } ?: ""), emptyList(), -1, null, "0", r,
                    item.str("model_answer"), ""))
            }
            "analytical" -> {
                val parts = (item["parts"] as? JsonArray).orEmpty().map { it.jsonObject }
                require(parts.isNotEmpty()) { "$id 没有小问" }
                val built = parts.map { p ->
                    val label = p.str("label"); require(label.isNotBlank()) { "$id 小问缺少 label" }
                    val partMarks = p.double("marks")
                    val r = rubric(p["rubric"], "$id($label)")
                    val numeric = p.str("answer").takeIf { it.toBigDecimalOrNull() != null }
                    // Keep original decimal literals: a Double round trip moves the inclusive boundary.
                    val tolerance = (p["tolerance"] as? JsonPrimitive)?.content ?: "0"
                    if (numeric != null) AnalyticalNumericKey(numeric, tolerance)
                    val rest = partMarks - r.sumOf { it.points }
                    require(partMarks > 0 && rest > -1e-6) { "$id($label) 评分细则超出分值" }
                    require(rest < 1e-6 || numeric != null) { "$id($label) 细则不足分值且没有数值答案" }
                    MockUnit("$id#$label", id, section, "part", label, partMarks, item.str("kp"), week,
                        item.str("stem") + "\n\n(" + label + ") " + p.str("prompt"), emptyList(), -1, numeric,
                        tolerance, r, p.str("answer"), "")
                }
                require(marks <= 0 || kotlin.math.abs(built.sumOf { it.marks } - marks) < 1e-6) { "$id 小问分值合计不等于总分" }
                built
            }
            else -> throw IllegalArgumentException("$id 题型未知：${item.str("type")}")
        }
    }

    /** What the learner may see before submitting: no keys, explanations, rubrics or model answers. */
    fun redacted(): JsonObject = buildJsonObject {
        put("id", id); put("title", title); put("kind", json.str("kind"))
        put("sections", JsonArray(sections.mapIndexed { index, section ->
            buildJsonObject {
                put("title", section.str("title")); put("instructions", section.str("instructions"))
                section.int("choose")?.let { put("choose", it) }
                put("items", JsonArray((section["items"] as JsonArray).map { raw ->
                    val item = raw.jsonObject
                    buildJsonObject {
                        for (k in listOf("id", "type", "kp", "stem")) put(k, item.str(k))
                        put("marks", unitsOf(item.str("id"), index).sumOf { it.marks })
                        item.int("word_target")?.let { put("word_target", it) }
                        if (item.str("type") != "analytical") item.str("figure").takeIf { it.isNotBlank() }?.let { put("figure", it) }
                        if (item.str("type") == "mcq") put("options", JsonArray(item.strings("options").map(::JsonPrimitive)))
                        if (item.str("type") == "analytical") put("parts", JsonArray(unitsOf(item.str("id"), index).map { u ->
                            json("key" to u.key, "label" to u.label, "marks" to u.marks, "numeric" to (u.numeric != null),
                                "prompt" to ((item["parts"] as JsonArray).map { it.jsonObject }.first { it.str("label") == u.label }.str("prompt")))
                        }))
                    }
                }))
            }
        }))
    }
    private fun unitsOf(item: String, section: Int) = units.filter { it.itemId == item && it.section == section }

    /** `choose` keeps the first answered items in paper order; unanswered ones fill the remaining places at zero. */
    fun counted(answers: JsonObject): Set<String> = sections.indices.flatMap { index ->
        val items = units.filter { it.section == index }.map { it.itemId }.distinct()
        val choose = sections[index].int("choose") ?: items.size
        val answered = items.filter { item -> unitsOf(item, index).any { answered(it, answers) } }
        (answered + (items - answered.toSet())).take(choose).flatMap { item -> unitsOf(item, index).map { it.key } }
    }.toSet()
}

internal fun answered(unit: MockUnit, answers: JsonObject): Boolean {
    val a = answers[unit.key] as? JsonObject ?: return false
    return if (unit.type == "mcq") (a.int("choice") ?: -1) in unit.options.indices
    else a.str("text").isNotBlank() || a.str("final").isNotBlank()
}

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.double(key: String): Double = (get(key) as? JsonPrimitive)?.doubleOrNull ?: 0.0
private fun hundredths(marks: Double) = (marks * 100).roundToLong()

private fun numericFact(unit: MockUnit, submitted: String): JsonObject {
    val grade = AnalyticalNumericKey(unit.numeric!!, unit.tolerance).grade(submitted)
    return json("expected" to unit.numeric, "submitted" to submitted, "correct" to grade.correct, "status" to grade.status,
        "absTolerance" to unit.tolerance, "relTolerance" to 0, "decimalPlaces" to 2, "rubricItemId" to "N")
}

/** Explains the numeric step; method explanations stay with their individual rubric criteria. */
private fun numericFeedback(fact: JsonObject): String = when (fact.str("status")) {
    "MISSING" -> "最终数值未填写；步骤分按评分细则另计。"
    "NOT_NUMERIC" -> "最终数值不是有效数字，请填写十进制数；步骤分按评分细则另计。"
    "WRONG_DECIMAL_PLACES" -> "最终数值须保留两位小数（例如 1.00）；步骤分按评分细则另计。"
    else -> (if (fact.flag("correct")) "最终数值正确" else "最终数值不符：你答 ${fact.str("submitted").trim()}") +
        "，参考答案 ${fact.str("expected")}（允许绝对误差 ±${fact["absTolerance"]?.jsonPrimitive?.content.orEmpty()}，含边界）。"
}

/** Papers are read from a private directory outside the repository; nothing here is ever committed. */
class MockLibrary(val directory: Path?, val minutesOverride: Int? = null) {
    data class Entry(val file: String, val paper: JsonObject?, val error: String?)
    fun entries(course: String): List<Entry> {
        val dir = directory?.takeIf { Files.isDirectory(it) } ?: return emptyList()
        return Files.walk(dir, 2).use { s -> s.filter { it.toString().endsWith(".json") && Files.isRegularFile(it) }.sorted().toList() }.mapNotNull { file ->
            val name = dir.relativize(file).toString()
            val raw = runCatching { LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject }.getOrElse { return@mapNotNull Entry(name, null, "不是有效 JSON") }
            if (!raw.str("course").equals(course, ignoreCase = true)) return@mapNotNull null
            runCatching { Entry(name, withFigures(raw, file.parent), null).also { MockPaper(it.paper!!) } }
                .getOrElse { Entry(name, null, it.message ?: it.javaClass.simpleName) }
        }
    }
    /** Figures are frozen into the session with the paper, so later edits cannot change a running exam. */
    private fun withFigures(raw: JsonObject, base: Path): JsonObject = JsonObject(raw + ("sections" to JsonArray((raw["sections"] as? JsonArray).orEmpty().map { s ->
        val section = s.jsonObject
        JsonObject(section + ("items" to JsonArray((section["items"] as? JsonArray).orEmpty().map { i ->
            val item = i.jsonObject
            val ref = item.str("figure_svg")
            val file = ref.takeIf { it.isNotBlank() }?.let { base.resolve(it).normalize() }?.takeIf { it.startsWith(base) && Files.isRegularFile(it) }
            val svg = if (ref.trimStart().startsWith("<svg")) ref else file?.let(Files::readString)
            if (svg == null) item else JsonObject(item + ((if (item.str("type") == "analytical") "standardFigure" else "figure") to JsonPrimitive(svg)))
        })))
    })))
    fun papers(course: String) = entries(course).mapNotNull { it.paper }.filter { it.str("kind") == "paper" }
    fun packs(course: String) = entries(course).mapNotNull { it.paper }.filter { it.str("kind") == "pack" }

    companion object {
        fun configured(env: Map<String, String> = System.getenv()): MockLibrary {
            val local = Properties().apply {
                val file = File(System.getProperty("user.home"), "Coach/config/local.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            val dir = env["COACH_MOCK_DIR"] ?: local.getProperty("coach.mockDir")
                ?: File(System.getProperty("user.home"), "Coach/materials/generated/ECNM10119").path
            return MockLibrary(Path.of(dir), env["COACH_MOCK_MINUTES"]?.toIntOrNull()?.takeIf { it > 0 })
        }
    }
}

class MockError(val status: HttpStatusCode, message: String) : Exception(message)

/** Same session engine for full papers (FULL_MOCK) and 7-question timed choice drills (TIMED). */
class MockEngine(private val library: MockLibrary, private val clock: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = java.security.SecureRandom()) {
    val graceMillis = 15_000L
    private fun newId() = "mock-" + ulid(clock(), ByteArray(10).also(random::nextBytes)).lowercase()

    fun home(store: LedgerStore, course: String): JsonObject {
        sweep(store, course)
        val entries = library.entries(course)
        val papers = entries.mapNotNull { it.paper }
        val packItems = papers.filter { it.str("kind") == "pack" }.flatMap { MockPaper(it).units.filter { u -> u.type == "mcq" } }
        val sessions = store.mocks(course, 20)
        val events = store.events(course)
        return json("papers" to JsonArray(papers.filter { it.str("kind") == "paper" }.map { p ->
            val paper = MockPaper(p)
            json("id" to paper.id, "title" to paper.title, "minutes" to (library.minutesOverride ?: p.int("minutes") ?: 120),
                "items" to paper.units.map { it.itemId }.distinct().size, "max" to paper.max)
        }), "drill" to json("total" to packItems.size, "weeks" to JsonArray(packItems.mapNotNull { it.week }.groupingBy { it }.eachCount()
            .toSortedMap().map { (w, n) -> json("week" to w, "count" to n) }), "minutes" to (library.minutesOverride ?: 14), "size" to 7),
            "invalid" to JsonArray(entries.filter { it.error != null }.map { json("file" to it.file, "reason" to it.error) }),
            "active" to sessions.firstOrNull { it.submittedAt == null }?.let { json("mockId" to it.id, "title" to MockPaper(it.paper).title, "deadline" to it.deadline) },
            "history" to JsonArray(sessions.filter { it.submittedAt != null }.take(10).map { s ->
                val r = result(store, s, events)
                json("mockId" to s.id, "title" to r.str("title"), "kind" to s.kind, "submittedAt" to s.submittedAt,
                    "score" to r["score"], "max" to r["max"], "pending" to r["pending"])
            }), "serverNow" to clock())
    }

    fun start(store: LedgerStore, course: String, input: JsonObject): JsonObject {
        sweep(store, course)
        store.mocks(course, 5).firstOrNull { it.submittedAt == null }?.let { throw MockError(HttpStatusCode.Conflict, "还有一场未交卷的考试：${MockPaper(it.paper).title}") }
        val paper: JsonObject; val kind: String
        val drill = input["drill"] as? JsonObject
        if (drill == null) {
            paper = library.papers(course).firstOrNull { it.str("id") == input.str("paperId") } ?: throw MockError(HttpStatusCode.NotFound, "找不到这套卷子")
            kind = "paper"
        } else { paper = drillPaper(store, course, drill); kind = "drill" }
        val minutes = library.minutesOverride ?: if (kind == "drill") 14 else paper.int("minutes") ?: 120
        val now = clock()
        val session = MockSession(newId(), course, kind, if (kind == "paper") "FULL_MOCK" else "TIMED",
            JsonObject(paper + ("minutes" to JsonPrimitive(minutes))), now, now + minutes * 60_000L)
        store.tutorTransaction {
            createMock(session)
            append(course, "mock_started", json("mockId" to session.id, "paperId" to paper.str("id"), "title" to MockPaper(paper).title,
                "kind" to kind, "purpose" to session.purpose, "minutes" to minutes, "deadline" to session.deadline), "mock:${session.id}:started")
        }
        return view(store, session)
    }

    /** Seven distinct choices from the packs: one week, or weighted by exam weight; recently drilled questions go last. */
    private fun drillPaper(store: LedgerStore, course: String, drill: JsonObject): JsonObject {
        val week = drill.int("week")
        val pool = library.packs(course).flatMap { pack ->
            val items = (pack["sections"] as JsonArray).flatMap { (it.jsonObject["items"] as? JsonArray).orEmpty() }.map { it.jsonObject }
            items.filter { it.str("type") == "mcq" && (week == null || it.int("kp_week") == week) }
                .map { JsonObject(it + ("id" to JsonPrimitive(pack.str("id") + "." + it.str("id"))) + ("key_state" to JsonPrimitive(it.str("key_state").ifBlank { pack.str("key_state") }))) }
        }
        if (pool.isEmpty()) throw MockError(HttpStatusCode.NotFound, if (week == null) "题包里还没有单选题" else "第 $week 周还没有单选题")
        val weights = examWeights(store, course)
        val recent = store.mocks(course, 10).filter { it.kind == "drill" }.flatMap { s -> MockPaper(s.paper).units.map { it.itemId } }.toSet()
        val picked = mutableListOf<JsonObject>()
        val left = pool.toMutableList()
        while (picked.size < 7 && left.isNotEmpty()) {
            val fresh = left.filter { it.str("id") !in recent }.ifEmpty { left }
            val w = fresh.map { item -> ((item["weight"] as? JsonPrimitive)?.doubleOrNull ?: item.int("kp_week")?.let { weights[it] } ?: 1.0).coerceAtLeast(0.01) }
            var r = random.nextDouble() * w.sum()
            val choice = fresh.indices.first { i -> r -= w[i]; r <= 0 || i == fresh.lastIndex }
            picked += fresh[choice]; left.remove(fresh[choice])
        }
        val title = if (week == null) "单选速刷 · 按押题权重" else "单选速刷 · 第 $week 周"
        return json("id" to "drill", "kind" to "drill", "course" to course, "title" to title,
            "sections" to JsonArray(listOf(json("title" to title, "instructions" to "共 ${picked.size} 题，限时作答。", "items" to JsonArray(picked)))))
    }

    /** Ledger exam weights are per KP; drills sample by the week those KPs belong to. */
    private fun examWeights(store: LedgerStore, course: String): Map<Int, Double> {
        val events = store.events(course)
        val weights = (events.lastOrNull { it.type == "exam_weights_set" }?.payload?.get("weights") as? JsonObject).orEmpty()
        val points = weekPoints(events).associateBy { it.id }
        val byWeek = mutableMapOf<Int, Double>()
        for ((kp, v) in weights) for (w in points[kp]?.weeks.orEmpty()) byWeek[w] = (byWeek[w] ?: 0.0) + (v.jsonPrimitive.doubleOrNull ?: 0.0)
        return byWeek
    }

    fun get(store: LedgerStore, course: String, id: String): JsonObject {
        val s = session(store, course, id)
        return if (s.submittedAt == null && clock() > s.deadline + graceMillis) submit(store, course, id, null, true)
        else if (s.submittedAt == null) view(store, s) else result(store, s)
    }

    /** [seq] orders saves from the app: one that turns up after a higher-numbered save is ignored, so the newest answers stay. */
    fun save(store: LedgerStore, course: String, id: String, answers: JsonObject, seq: Long? = null): JsonObject {
        val s = session(store, course, id)
        if (s.submittedAt != null) throw MockError(HttpStatusCode.Conflict, "已交卷")
        if (clock() > s.deadline + graceMillis) return submit(store, course, id, null, true)
        val stored = store.saveMockAnswers(id, clean(MockPaper(s.paper), answers), seq)
        return json("saved" to stored, "serverNow" to clock(), "deadline" to s.deadline)
    }

    /** Grading, ledger facts and the session close commit as one unit; a second submit just returns the result. */
    fun submit(store: LedgerStore, course: String, id: String, answers: JsonObject?, auto: Boolean): JsonObject = store.tutorTransaction {
        var s = session(this, course, id)
        if (s.submittedAt != null) return@tutorTransaction result(this, s)
        val paper = MockPaper(s.paper)
        val now = clock()
        if (answers != null && now <= s.deadline + graceMillis) saveMockAnswers(id, clean(paper, answers))
        val late = now > s.deadline + graceMillis
        check(closeMock(id, if (late) s.deadline else now, auto || late))
        s = session(this, course, id)
        val counted = paper.counted(s.answers)
        val points = weekPoints(events(course))
        val keyState = s.paper.str("key_state").ifBlank { "UNREVIEWED" }
        val items = paper.units.mapIndexed { index, unit ->
            if (unit.key !in counted) return@mapIndexed json("key" to unit.key, "counted" to false)
            val attempt = "${s.id}-${index + 1}"
            val a = s.answers[unit.key] as? JsonObject ?: JsonObject(emptyMap())
            // A blank answer (ran out of time, or the filler of a choose-N section) says nothing about the point; it still scores 0 here.
            val kpIds = if (!answered(unit, s.answers)) emptyList() else points.filter { p -> p.id == unit.kp || (unit.week != null && unit.week in p.weeks && p.title.contains(unit.kp, ignoreCase = true) && unit.kp.isNotBlank()) }.map { it.id }.take(1)
            val base = json("attemptId" to attempt, "kpIds" to kpIds, "purpose" to s.purpose, "keyState" to unit.keyState.ifBlank { keyState }, "policyVersion" to 1,
                "posedAt" to s.startedAt, "origin" to "COACH", "groupId" to s.id, "mockId" to s.id, "marks" to unit.marks, "stem" to unit.stem)
            if (unit.type == "mcq") {
                val options = unit.options.mapIndexed { i, t -> Option("o$i", t) }
                val fingerprint = questionFingerprint(unit.stem, options)
                val qid = "mock-" + fingerprint.take(24)
                if (!questionRegistered(qid)) registerQuestion(qid, course, fingerprint, "SINGLE_CHOICE", unit.stem, unit.keyState.ifBlank { keyState }, "o${unit.answer}", options)
                val letters = optionLetters(options.size)
                append(course, "question_posed", JsonObject(base + json("questionId" to qid, "format" to "SINGLE_CHOICE", "fingerprint" to fingerprint,
                    "options" to LedgerJson.encodeToJsonElement(options), "letterMap" to JsonObject(options.indices.associate { "o$it" to JsonPrimitive(letters[it]) }))),
                    "attempt:$attempt", occurredAt = s.startedAt)
                val choice = a.int("choice")?.takeIf { it in options.indices }
                append(course, "answer_submitted", json("attemptId" to attempt, "answer" to (choice?.let { letters[it] } ?: ""), "finalAnswer" to ""), "attempt:$attempt:answer", occurredAt = s.submittedAt!!)
                val right = choice == unit.answer
                val key = "正确答案 ${letters[unit.answer]}：${unit.options[unit.answer]}"
                val feedback = (if (choice == null) "未作答，$key" else if (right) "正确。" else "你选 ${letters[choice]}，$key") +
                    unit.explanation.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                append(course, "attempt_graded", json("attemptId" to attempt, "score" to if (right) hundredths(unit.marks) else 0, "max" to hundredths(unit.marks),
                    "status" to "EXACT", "feedback" to feedback), "attempt:$attempt:grade:exact")
            } else {
                val rubric = unit.gradingRubric
                val fingerprint = if (rubric.isEmpty()) sha256(unit.reference.toByteArray()) else GradeGate.rubricFingerprint(rubric)
                val qid = "mock-" + sha256((unit.stem + "\u0000" + fingerprint).toByteArray()).take(24)
                val format = if (unit.type == "essay") "ESSAY" else if (unit.numeric != null) "CALCULATION" else "SHORT_ANSWER"
                append(course, "question_posed", JsonObject(base + json("questionId" to qid, "format" to format, "fingerprint" to fingerprint,
                    "rubricFingerprint" to fingerprint)), "attempt:$attempt", occurredAt = s.startedAt)
                val text = a.str("text"); val final = a.str("final")
                append(course, "answer_submitted", json("attemptId" to attempt, "answer" to text, "finalAnswer" to final), "attempt:$attempt:answer", occurredAt = s.submittedAt!!)
                if (unit.serverNumeric) {
                    val numeric = numericFact(unit, final)
                    val right = numeric.flag("correct")
                    append(course, "attempt_graded", json("attemptId" to attempt, "score" to if (right) hundredths(unit.marks) else 0, "max" to hundredths(unit.marks),
                        "status" to "EXACT", "feedback" to numericFeedback(numeric), "numericFinal" to numeric,
                        "items" to JsonArray(listOf(json("id" to "N", "points" to if (right) unit.marks else 0.0, "reason" to "SERVER_NUMERIC")))), "attempt:$attempt:grade:exact")
                } else if (!answered(unit, s.answers)) {
                    append(course, "attempt_graded", json("attemptId" to attempt, "score" to 0, "max" to hundredths(unit.marks), "status" to "EXACT", "feedback" to "未作答。",
                        "items" to JsonArray(rubric.map { json("id" to it.id, "points" to 0, "reason" to if (it.id == "N") "SERVER_NUMERIC" else "未作答，未提供这一步的证据。") }),
                        "numericFinal" to if (rubric.any { it.id == "N" }) numericFact(unit, "") else null), "attempt:$attempt:grade:exact")
                } else {
                    val question = json("kp_id" to unit.kp, "stem" to unit.stem, "reference_answer" to unit.reference,
                        "rubric" to JsonArray(rubric.map { json("id" to it.id, "text" to it.text, "points" to it.points, "basis" to it.basis) }),
                        "numeric_final" to if (rubric.any { it.id == "N" }) json("expected" to unit.numeric, "rubricItemId" to "N", "relTolerance" to 0.0,
                            "absTolerance" to unit.tolerance, "decimalPlaces" to 2) else null)
                    val subject = json("course" to course, "attempt" to attempt, "questionId" to qid, "thread" to null, "answer" to text,
                        "finalAnswer" to final, "question" to question, "rubricFingerprint" to fingerprint, "deadline" to (now + 86_400_000), "mockId" to s.id)
                    enqueueJob("grade_attempt", subject.toString(), fingerprint, sha256(json("answer" to text, "finalAnswer" to final).toString().toByteArray(Charsets.UTF_8)))
                }
            }
            json("key" to unit.key, "counted" to true, "attemptId" to attempt, "marks" to unit.marks, "section" to unit.section, "kp" to unit.kp)
        }
        append(course, "mock_submitted", json("mockId" to s.id, "paperId" to s.paper.str("id"), "title" to paper.title, "kind" to s.kind,
            "purpose" to s.purpose, "auto" to s.auto, "max" to hundredths(paper.max), "items" to JsonArray(items)), "mock:${s.id}:submitted")
        result(this, s)
    }

    /** Closes sessions whose deadline passed while nobody had the exam open; returns the affected courses. */
    fun sweep(store: LedgerStore, course: String? = null): Set<String> {
        val now = clock()
        val open = store.openMocks().filter { (course == null || it.course == course) && now > it.deadline + graceMillis }
        for (s in open) submit(store, s.course, s.id, null, true)
        return open.map { it.course }.toSet()
    }

    private fun session(store: LedgerStore, course: String, id: String) =
        store.mock(id)?.takeIf { it.course == course } ?: throw MockError(HttpStatusCode.NotFound, "找不到这场考试")

    private fun clean(paper: MockPaper, answers: JsonObject): JsonObject = JsonObject(paper.units.mapNotNull { u ->
        val a = answers[u.key] as? JsonObject ?: return@mapNotNull null
        u.key to if (u.type == "mcq") json("choice" to a.int("choice")) else JsonObject(buildMap {
            put("text", JsonPrimitive(a.str("text").take(20_000)))
            a.str("final").take(200).takeIf { it.isNotBlank() }?.let { put("final", JsonPrimitive(it)) }
        })
    }.toMap())

    private fun view(store: LedgerStore, s: MockSession) = json("mockId" to s.id, "status" to "active", "kind" to s.kind, "purpose" to s.purpose,
        "minutes" to s.paper.int("minutes"), "startedAt" to s.startedAt, "deadline" to s.deadline, "serverNow" to clock(),
        "paper" to MockPaper(s.paper).redacted(), "answers" to s.answers, "answersSeq" to s.answersSeq)

    /** Built from ledger facts each time, so later AI grades and reviews appear without a separate copy. */
    fun result(store: LedgerStore, s: MockSession, events: List<Event> = store.events(s.course)): JsonObject {
        val paper = MockPaper(s.paper)
        val submitted = events.lastOrNull { it.type == "mock_submitted" && it.payload.str("mockId") == s.id }
            ?: return json("mockId" to s.id, "status" to "grading", "title" to paper.title)
        val attempts = (submitted.payload["items"] as JsonArray).map { it.jsonObject }.associateBy { it.str("key") }
        val facts = events.filter { it.type in setOf("attempt_graded", "grade_reviewed") }.groupBy { it.payload.str("attemptId") }
        var score = 0L; var pending = 0; var drafts = 0
        val lost = linkedMapOf<String, Pair<Long, Long>>()
        // A pack that names its knowledge point by id shows the outline title, not the id.
        val titles = weekPoints(events).associate { it.id to it.title }
        val items = paper.units.map { u ->
            val kp = titles[u.kp] ?: u.kp
            val info = attempts[u.key]
            val a = s.answers[u.key] as? JsonObject ?: JsonObject(emptyMap())
            val base = mutableMapOf<String, JsonElement>("key" to JsonPrimitive(u.key), "itemId" to JsonPrimitive(u.itemId), "label" to JsonPrimitive(u.label),
                "type" to JsonPrimitive(u.type), "section" to JsonPrimitive(u.section), "kp" to JsonPrimitive(kp), "stem" to JsonPrimitive(u.stem),
                "marks" to JsonPrimitive(u.marks), "answer" to a)
            if (u.type == "mcq") { base["options"] = JsonArray(u.options.map(::JsonPrimitive)); base["correct"] = JsonPrimitive(u.answer) }
            else base["reference"] = JsonPrimitive(u.reference)
            if (u.type == "part") paper.sections[u.section]["items"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("id") == u.itemId }?.let { item ->
                item.str("standardFigure").ifBlank { item.str("figure") }.takeIf { it.isNotBlank() }?.let { base["standardFigure"] = JsonPrimitive(it) }
            }
            if (info == null || !info.flag("counted")) return@map JsonObject(base + ("status" to JsonPrimitive("SKIPPED")))
            val attempt = info.str("attemptId"); base["attemptId"] = JsonPrimitive(attempt)
            val graded = facts[attempt].orEmpty()
            val first = graded.lastOrNull { it.type == "attempt_graded" }?.payload
            val review = graded.lastOrNull { it.type == "grade_reviewed" }?.payload
            val effective = (review?.get("revised") as? JsonObject) ?: first
            val status = when {
                review != null -> "REVIEWED"
                first == null -> if (store.constructedRecord(s.course, attempt)?.get("jobs")?.jsonArray?.any { it.jsonObject.str("state") == "failed" } == true) "FAILED" else "PENDING"
                first.str("status") == "AI_UNREVIEWED" -> "DRAFT"
                else -> "GRADED"
            }
            if (effective == null) { pending++; return@map JsonObject(base + ("status" to JsonPrimitive(status))) }
            if (status == "DRAFT") drafts++
            val got = effective.num("score"); score += got
            val (l, m) = lost[kp] ?: (0L to 0L); lost[kp] = (l + effective.num("max") - got) to (m + effective.num("max"))
            val byId = u.gradingRubric.associateBy { it.id }
            base["status"] = JsonPrimitive(status); base["score"] = JsonPrimitive(got / 100.0)
            base["feedback"] = JsonPrimitive(effective.str("feedback"))
            val numeric = effective["numericFinal"] as? JsonObject
            numeric?.let { base["numericFinal"] = it }
            base["reasons"] = JsonArray((effective["items"] as? JsonArray).orEmpty().map { it.jsonObject }.map { g ->
                json("criterion" to (byId[g.str("id")]?.text ?: if (g.str("id") == "N") "最终数值" else g.str("id")),
                    "points" to g["points"], "max" to (byId[g.str("id")]?.points ?: if (u.serverNumeric) u.marks else null),
                    "reason" to if (g.str("reason") == "SERVER_NUMERIC") numeric?.let(::numericFeedback) ?: "最终数值由服务器按参考答案核对" else g.str("reason"))
            })
            review?.str("note")?.takeIf { it.isNotBlank() }?.let { base["reviewNote"] = JsonPrimitive(it) }
            JsonObject(base)
        }
        return json("mockId" to s.id, "status" to "submitted", "title" to paper.title, "kind" to s.kind, "purpose" to s.purpose,
            "auto" to s.auto, "startedAt" to s.startedAt, "submittedAt" to s.submittedAt, "score" to score / 100.0,
            "max" to submitted.payload.num("max") / 100.0, "pending" to pending, "drafts" to drafts, "items" to JsonArray(items),
            "lostByKp" to JsonArray(lost.filter { it.value.first > 0 }.entries.sortedByDescending { it.value.first }
                .map { json("kp" to it.key, "lost" to it.value.first / 100.0, "max" to it.value.second / 100.0) }))
    }
}

fun Application.mockRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, engine: MockEngine) {
    /** Any request that lands ledger facts (start, submit, deadline close) wakes subscribed clients. */
    suspend fun ApplicationCall.answer(course: String, block: LedgerStore.(JsonObject) -> JsonObject) {
        try {
            val text = if (request.httpMethod == HttpMethod.Post) receiveText() else ""
            val input = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { LedgerJson.parseToJsonElement(text).jsonObject }
                .getOrElse { throw MockError(HttpStatusCode.BadRequest, "请求格式不对") }
            val (body, changed) = actor.execute { val before = courseSeq(course); block(input).let { it to (courseSeq(course) != before) } }
            if (changed) pushes.emit(course)
            respondText(body.toString(), ContentType.Application.Json)
        } catch (e: MockError) {
            respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status)
        }
    }
    routing {
        get("/v1/courses/{course}/mocks") { val c = call.parameters["course"]!!; call.answer(c) { engine.home(this, c) } }
        post("/v1/courses/{course}/mocks") { val c = call.parameters["course"]!!; call.answer(c) { engine.start(this, c, it) } }
        get("/v1/courses/{course}/mocks/{id}") { val c = call.parameters["course"]!!; call.answer(c) { engine.get(this, c, call.parameters["id"]!!) } }
        post("/v1/courses/{course}/mocks/{id}/answers") {
            val c = call.parameters["course"]!!
            call.answer(c) { engine.save(this, c, call.parameters["id"]!!, it["answers"] as? JsonObject ?: JsonObject(emptyMap()), (it["seq"] as? JsonPrimitive)?.longOrNull) }
        }
        post("/v1/courses/{course}/mocks/{id}/submit") {
            val c = call.parameters["course"]!!
            call.answer(c) { engine.submit(this, c, call.parameters["id"]!!, it["answers"] as? JsonObject, it.flag("auto")) }
        }
    }
    // The client also submits at zero; this covers an exam left closed past its deadline.
    launch {
        while (isActive) {
            delay(30_000)
            runCatching { actor.execute { engine.sweep(this) } }.getOrNull()?.forEach { pushes.emit(it) }
        }
    }
}
