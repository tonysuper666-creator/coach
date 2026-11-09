package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong

internal data class MainlineFact(val id: String, val text: String, val type: String, val source: String, val kp: String)
internal data class MainlineItem(val unit: MockUnit, val tier: String, val factIds: List<String>, val source: String) {
    val verified get() = unit.keyState == "VERIFIED"
    val options get() = unit.options.mapIndexed { i, t -> Option("o$i", t) }
    val fingerprint get() = questionFingerprint(unit.stem, options)
}
/**
 * Display order for one showing of a question: `order[shown] = index in the pack`. The pack is not shuffled (the model tends to put the
 * key first), so the order is a fixed shuffle of the fingerprint and how many times the question was posed: the same showing always
 * lays out the same way, and the next look at it is reshuffled.
 */
internal fun optionOrder(fingerprint: String, round: Int, size: Int): List<Int> {
    val seed = java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest("mainline-order:$fingerprint:$round".toByteArray())).long
    return (0 until size).shuffled(java.util.Random(seed))
}

internal class MainlineWeek(val week: Int, val title: String, val items: List<MainlineItem>, val facts: List<MainlineFact>)

private val tiers = listOf("setup", "detail", "bridge")

/** Explanation sections from FORMAT.md: 正确 / 错项N (N is the 0-based option index) / 背景 / 出处; unlabelled lines join the previous section. */
internal fun explanationParts(text: String): JsonObject {
    val label = Regex("^\\s*(正确|错项\\s*(\\d+)|背景|出处)\\s*[:：]\\s*(.*)$")
    val parts = linkedMapOf<String, StringBuilder>()
    var current: String? = null
    for (line in text.lines()) {
        val m = label.find(line)
        if (m != null) {
            current = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { "o$it" } ?: m.groupValues[1]
            parts.getOrPut(current) { StringBuilder() }.append(m.groupValues[3].trim())
        } else if (line.isNotBlank()) parts.getOrPut(current ?: "正确") { StringBuilder() }.also { if (it.isNotEmpty()) it.append('\n') }.append(line.trim())
    }
    fun take(key: String) = parts[key]?.toString().orEmpty()
    return json("correct" to take("正确"), "background" to take("背景"), "source" to take("出处"),
        "options" to JsonObject(parts.filterKeys { it.startsWith("o") }.mapKeys { it.key.drop(1) }.mapValues { JsonPrimitive(it.value.toString()) }))
}

/** One latest answer per question, matched by fingerprint so practice of the same question elsewhere also counts. */
private class ChoiceHistory(events: List<Event>) {
    data class Latest(val chosen: String, val guessed: Boolean, val day: Long)
    val latest = mutableMapOf<String, Latest>()
    init {
        val answers = events.filter { it.type == "answer_submitted" }.associateBy { it.payload.str("attemptId") }
        val voided = events.filter { it.type == "attempt_voided" }.map { it.payload.str("attemptId") }.toSet()
        val graded = events.filter { it.type in setOf("attempt_graded", "grade_reviewed") }.map { it.payload.str("attemptId") }.toSet()
        for (posed in events.filter { it.type == "question_posed" && it.payload.str("format") == "SINGLE_CHOICE" }) {
            val attempt = posed.payload.str("attemptId")
            val answer = answers[attempt] ?: continue
            if (attempt in voided || attempt !in graded) continue
            val letters = (posed.payload["letterMap"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
            val letter = answer.payload.str("answer").trim().uppercase()
            val chosen = (posed.payload["options"] as? JsonArray).orEmpty().map { it.jsonObject }
                .firstOrNull { letters[it.str("optionId")] == letter }?.str("text") ?: continue
            latest[posed.payload.str("fingerprint")] = Latest(normalizeChoiceText(chosen), answer.payload.flag("guessed"), answer.occurredAt / 86_400_000)
        }
    }
    /** Mastered: the latest answer picks the current key, was not a guess, and someone has checked that key. */
    fun status(item: MainlineItem): String {
        val last = latest[item.fingerprint] ?: return "new"
        val right = last.chosen == normalizeChoiceText(item.unit.options[item.unit.answer])
        return when {
            !item.verified -> "pending"
            right && last.guessed -> "guessed"
            right -> "mastered"
            else -> "missed"
        }
    }
}

/** Weekly choice mainline: packs and fact lists live under `mcq-mainline/` in the private generated directory, never in the repository. */
class Mainline(private val library: MockLibrary, private val clock: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = java.security.SecureRandom()) {
    private val dir: Path? get() = library.directory?.resolve("mcq-mainline")

    /** Packs are labelled with the course code, the ledger with its own id; the code is the first word of the course name. */
    private fun courseNames(events: List<Event>, course: String): Set<String> {
        val name = events.asReversed().firstNotNullOfOrNull { (it.payload["course"] as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) }.orEmpty()
        return setOfNotNull(course, name.trim().substringBefore(' ').takeIf { it.isNotBlank() })
    }

    internal fun weeks(names: Set<String>): Pair<List<MainlineWeek>, List<JsonObject>> {
        val root = dir?.takeIf { Files.isDirectory(it) } ?: return emptyList<MainlineWeek>() to emptyList()
        val files = Files.list(root).use { s -> s.filter { Regex("W\\d{2}\\.json").matches(it.fileName.toString()) }.sorted().toList() }
        val invalid = mutableListOf<JsonObject>()
        val weeks = files.mapNotNull { file ->
            val name = file.fileName.toString()
            runCatching {
                val raw = LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject
                if (names.none { it.equals(raw.str("course"), ignoreCase = true) }) return@mapNotNull null
                parse(raw, name.removeSuffix(".json").drop(1).toInt(), root.resolve(name.replace(".json", "-facts.md")))
            }.getOrElse { invalid += json("file" to name, "reason" to (it.message ?: it.javaClass.simpleName)); null }
        }
        return weeks to invalid
    }

    private fun parse(raw: JsonObject, week: Int, factsFile: Path): MainlineWeek {
        val paper = MockPaper(raw)
        val items = (raw["sections"] as JsonArray).flatMap { (it.jsonObject["items"] as JsonArray).map { i -> i.jsonObject } }
        val built = paper.units.map { unit ->
            val item = items.first { it.str("id") == unit.itemId }
            require(unit.type == "mcq") { "${unit.itemId} 不是选择题" }
            require(item.str("tier") in tiers) { "${unit.itemId} tier 只能是 setup、detail 或 bridge" }
            MainlineItem(unit, item.str("tier"), item.strings("fact_ids"), item.str("source"))
        }.sortedBy { tiers.indexOf(it.tier) }
        val facts = if (!Files.isRegularFile(factsFile)) emptyList() else {
            val block = Regex("```jsonl\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(Files.readString(factsFile))?.groupValues?.get(1).orEmpty()
            block.lines().filter { it.isNotBlank() }.map { line ->
                val f = LedgerJson.parseToJsonElement(line).jsonObject
                MainlineFact(f.str("fact_id"), f.str("fact"), f.str("type"), f.str("source"), f.str("kp"))
            }
        }
        return MainlineWeek(week, (raw["sections"] as JsonArray).first().jsonObject.str("title").ifBlank { "Week $week" }, built, facts)
    }

    private fun round(events: List<Event>, item: MainlineItem) = events.count { it.type == "question_posed" && it.payload.str("fingerprint") == item.fingerprint }

    private fun coverage(week: MainlineWeek, history: ChoiceHistory): JsonObject {
        val status = week.items.associate { it.unit.itemId to history.status(it) }
        val linked = week.facts.associate { f -> f.id to week.items.filter { f.id in it.factIds } }
        return json("facts" to week.facts.size, "factsWithQuestions" to linked.count { it.value.isNotEmpty() },
            "factsMastered" to linked.count { (_, qs) -> qs.isNotEmpty() && qs.all { status[it.unit.itemId] == "mastered" } },
            "questions" to week.items.size, "answered" to status.count { it.value != "new" },
            "mastered" to status.count { it.value == "mastered" }, "keysPending" to week.items.count { !it.verified })
    }

    /**
     * Facts and questions whose registered source lands on one PDF page. A source that is blank, unregistered or ambiguous is counted
     * as `unlocated` for the whole course and never guessed onto a page, so the reader can say the index is incomplete.
     */
    internal fun pageStudy(store: LedgerStore, course: String, documentId: String, page: Int, locate: (String) -> Pair<String, Int>?): JsonObject {
        val events = store.events(course)
        val (weeks, _) = weeks(courseNames(events, course))
        val history = ChoiceHistory(events)
        fun refs(source: String) = source.split(Regex("[;；\n]")).map { it.trim() }.filter { it.isNotEmpty() }
        fun onPage(source: String) = refs(source).any { locate(it) == documentId to page }
        val questions = mutableListOf<JsonObject>(); val facts = mutableListOf<JsonObject>(); var unlocated = 0
        for (w in weeks) {
            for (item in w.items) {
                if (item.source.isBlank() || refs(item.source).none { locate(it) != null }) unlocated++
                else if (onPage(item.source)) questions += json("week" to w.week, "id" to item.unit.itemId, "tier" to item.tier,
                    "stem" to item.unit.stem.take(160), "status" to history.status(item))
            }
            for (f in w.facts) {
                if (f.source.isBlank() || refs(f.source).none { locate(it) != null }) unlocated++
                else if (onPage(f.source)) {
                    val linked = w.items.filter { f.id in it.factIds }
                    facts += json("week" to w.week, "id" to f.id, "text" to f.text.take(200), "type" to f.type, "kp" to f.kp,
                        "questions" to linked.size, "mastered" to (linked.isNotEmpty() && linked.all { history.status(it) == "mastered" }))
                }
            }
        }
        return json("documentId" to documentId, "pdfPage" to page, "facts" to JsonArray(facts), "questions" to JsonArray(questions),
            "factsMastered" to facts.count { it.flag("mastered") }, "questionsMastered" to questions.count { it.str("status") == "mastered" },
            "unlocated" to unlocated)
    }

    private fun find(names: Set<String>, week: Int) = weeks(names).first.firstOrNull { it.week == week } ?: throw MockError(HttpStatusCode.NotFound, "找不到第 $week 周的选择题")

    fun home(store: LedgerStore, course: String): JsonObject {
        val events = store.events(course)
        val (weeks, invalid) = weeks(courseNames(events, course))
        val history = ChoiceHistory(events)
        return json("weeks" to JsonArray(weeks.map { w -> json("week" to w.week, "title" to w.title, "coverage" to coverage(w, history)) }),
            "invalid" to JsonArray(invalid))
    }

    /** Questions in setup → detail → bridge order, without keys or explanations. */
    fun week(store: LedgerStore, course: String, number: Int, shuffled: Boolean = false): JsonObject {
        val events = store.events(course)
        val week = find(courseNames(events, course), number)
        val history = ChoiceHistory(events)
        return json("week" to week.week, "title" to week.title, "coverage" to coverage(week, history),
            "items" to JsonArray(week.items.map { item ->
                val layout = round(events, item)
                val order = if (shuffled) optionOrder(item.fingerprint, layout, item.unit.options.size) else item.unit.options.indices.toList()
                json("id" to item.unit.itemId, "tier" to item.tier, "stem" to item.unit.stem,
                    "options" to order.map { item.unit.options[it] }, "layout" to if (shuffled) layout else null,
                    "status" to history.status(item), "keyState" to item.unit.keyState.ifBlank { "UNREVIEWED" })
            }))
    }

    /**
     * One answer is one attempt: posed, submitted and graded commit together, then the explanation is returned at once.
     * `choice`, `answer` and the per-option reasons are positions on screen; `layout` says which shuffle was on screen, and a client
     * that sends none showed the pack order.
     */
    fun answer(store: LedgerStore, course: String, number: Int, input: JsonObject): JsonObject = store.tutorTransaction {
        val events = events(course)
        val week = find(courseNames(events, course), number)
        val item = week.items.firstOrNull { it.unit.itemId == input.str("itemId") } ?: throw MockError(HttpStatusCode.NotFound, "找不到这道题")
        val unit = item.unit
        var choice = (input["choice"] as? JsonPrimitive)?.intOrNull?.takeIf { it in unit.options.indices } ?: throw MockError(HttpStatusCode.BadRequest, "请选一个选项")
        var guessed = input.flag("guessed")
        val submission = input.str("submissionId").filter { it.isLetterOrDigit() || it == '-' }.take(64)
        val attempt = "mainline-" + submission.ifBlank { ulid(clock(), ByteArray(10).also(random::nextBytes)).lowercase() }
        val keyState = unit.keyState.ifBlank { "UNREVIEWED" }
        val options = item.options
        val fingerprint = item.fingerprint
        val qid = "mock-" + fingerprint.take(24)
        val letters = optionLetters(options.size)
        val posed = events.firstOrNull { it.type == "question_posed" && it.payload.str("attemptId") == attempt }
        if (posed != null && (!posed.payload.flag("mainline") || posed.payload.num("mainlineWeek") != week.week.toLong() ||
                posed.payload.str("mainlineItem") != unit.itemId || posed.payload.str("questionId") != qid)) {
            throw MockError(HttpStatusCode.Conflict, "这次提交已用于另一道题，请重新打开这一题")
        }
        val order = if (posed != null) {
            // A resend answers with what was recorded the first time, whatever layout it carries now.
            val shown = (posed.payload["letterMap"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
            letters.map { l -> shown.entries.first { it.value == l }.key.drop(1).toInt() }.also {
                val sent = events.first { it.type == "answer_submitted" && it.payload.str("attemptId") == attempt }.payload
                choice = letters.indexOf(sent.str("answer")); guessed = sent.flag("guessed")
            }
        } else (input["layout"] as? JsonPrimitive)?.intOrNull?.let { optionOrder(fingerprint, it, options.size) } ?: options.indices.toList()
        val picked = order[choice]
        val shownAnswer = order.indexOf(unit.answer)
        val points = weekPoints(events)
        val kpIds = points.filter { it.id == unit.kp }.ifEmpty {
            points.filter { p -> unit.week != null && unit.week in p.weeks && unit.kp.isNotBlank() && p.title.contains(unit.kp, ignoreCase = true) }
        }.map { it.id }.take(1)
        if (posed == null) {
            if (!questionRegistered(qid)) registerQuestion(qid, course, fingerprint, "SINGLE_CHOICE", unit.stem, keyState, "o${unit.answer}", options)
            // The checked key in the pack is the source of truth; the ledger follows it so retries and mastery see the same state.
            val ledgerKey = events.lastOrNull { it.type == "question_key_changed" && it.payload.str("questionId") == qid }?.payload?.str("keyState")
                ?: events.firstOrNull { it.type == "question_posed" && it.payload.str("questionId") == qid }?.payload?.str("keyState")
            if (ledgerKey != null && ledgerKey != keyState) append(course, "question_key_changed", json("questionId" to qid, "keyState" to keyState, "reason" to "MAINLINE_PACK"), "mainline:$attempt:key")
            val now = clock()
            append(course, "question_posed", json("attemptId" to attempt, "kpIds" to kpIds, "purpose" to "RECALL", "keyState" to keyState, "policyVersion" to 1,
                "posedAt" to now, "origin" to "COACH", "groupId" to attempt, "mainline" to true, "mainlineWeek" to week.week, "mainlineItem" to unit.itemId,
                "tier" to item.tier, "marks" to unit.marks, "stem" to unit.stem, "questionId" to qid, "format" to "SINGLE_CHOICE", "fingerprint" to fingerprint,
                "options" to LedgerJson.encodeToJsonElement(options), "letterMap" to JsonObject(order.withIndex().associate { (shown, i) -> "o$i" to JsonPrimitive(letters[shown]) })),
                "attempt:$attempt", occurredAt = now)
            append(course, "answer_submitted", json("attemptId" to attempt, "answer" to letters[choice], "finalAnswer" to "", "guessed" to guessed), "attempt:$attempt:answer", occurredAt = now)
            val right = picked == unit.answer
            val key = "正确答案 ${letters[shownAnswer]}：${unit.options[unit.answer]}"
            // The pack numbers distractors by pack position; the record names them by the letter that was on screen.
            val explanation = unit.explanation.replace(Regex("(?m)^(\\s*)错项\\s*(\\d+)")) { m ->
                m.groupValues[2].toInt().takeIf { it in order.indices }?.let { "${m.groupValues[1]}错项 ${letters[order.indexOf(it)]}" } ?: m.value
            }
            val feedback = (if (!item.verified) "答案键待核对，这次不记对错。" else if (right) "正确。" else "你选 ${letters[choice]}，$key") + (if (guessed) "（标记为猜的）" else "") +
                explanation.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            val max = (unit.marks * 100).roundToLong()
            append(course, "attempt_graded", json("attemptId" to attempt, "score" to if (right) max else 0, "max" to max, "status" to "EXACT", "feedback" to feedback),
                "attempt:$attempt:grade:exact")
        }
        val projection = snapshot(course).projection
        val after = events(course)
        val history = ChoiceHistory(after)
        val kp = kpIds.firstOrNull()
        val due = kp?.let { projection.revisit[it] ?: projection.review[it]?.dueDay }
        val status = history.status(item)
        val book = (status == "missed" || status == "guessed") && kp != null && weekMistakes(after, unit.week ?: number).any { it.payload.str("questionId") == qid }
        val index = week.items.indexOf(item)
        val parts = explanationParts(unit.explanation)
        val reasons = (parts["options"] as JsonObject).mapNotNull { (i, why) -> i.toIntOrNull()?.takeIf { it in order.indices }?.let { "${order.indexOf(it)}" to why } }.toMap()
        json("itemId" to unit.itemId, "attemptId" to attempt, "choice" to choice, "answer" to shownAnswer, "guessed" to guessed,
            "keyState" to keyState, "status" to status, "tier" to item.tier, "explanation" to JsonObject(parts + ("options" to JsonObject(reasons))),
            "source" to item.source, "kpId" to kp, "dueDay" to due, "mistakeWeek" to if (book) (unit.week ?: number) else null,
            "next" to week.items.getOrNull(index + 1)?.unit?.itemId, "coverage" to coverage(week, history))
    }
}

fun Application.mainlineRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, mainline: Mainline) {
    suspend fun ApplicationCall.answer(course: String, block: LedgerStore.(JsonObject) -> JsonObject) {
        try {
            val text = if (request.httpMethod == HttpMethod.Post) receiveText() else ""
            val input = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { LedgerJson.parseToJsonElement(text).jsonObject }.getOrElse { throw MockError(HttpStatusCode.BadRequest, "请求格式不对") }
            val (body, changed) = actor.execute { val before = courseSeq(course); block(input).let { it to (courseSeq(course) != before) } }
            if (changed) pushes.emit(course)
            respondText(body.toString(), ContentType.Application.Json)
        } catch (e: MockError) {
            respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status)
        }
    }
    fun ApplicationCall.week() = parameters["week"]?.toIntOrNull() ?: throw MockError(HttpStatusCode.BadRequest, "周数不对")
    routing {
        get("/v1/courses/{course}/mainline") { val c = call.parameters["course"]!!; call.answer(c) { mainline.home(this, c) } }
        get("/v1/courses/{course}/mainline/{week}") {
            val c = call.parameters["course"]!!
            // Older clients submit only a displayed index, so only capable clients receive a shuffled layout.
            val shuffled = call.request.queryParameters["layout"] == "shuffled-v1"
            call.answer(c) { mainline.week(this, c, call.week(), shuffled) }
        }
        post("/v1/courses/{course}/mainline/{week}/answer") { val c = call.parameters["course"]!!; call.answer(c) { mainline.answer(this, c, call.week(), it) } }
    }
}
