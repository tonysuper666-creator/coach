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

internal data class MainlineFact(val id: String, val text: String, val type: String, val source: String, val kp: String, val anchor: String = "")
internal data class MainlineItem(val unit: MockUnit, val tier: String, val factIds: List<String>, val source: String, val anchor: String = "", val learning: JsonObject? = null) {
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

internal class MainlineWeek(val week: Int, val title: String, val items: List<MainlineItem>, val facts: List<MainlineFact>,
    val notes: List<JsonObject>, val notesError: String?, val emphasis: List<JsonObject>)

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
    data class Latest(val chosen: String, val guessed: Boolean, val day: Long, val dontKnow: Boolean = false)
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
            if (posed.payload.flag("tryFirst")) continue
            val chosen = (posed.payload["options"] as? JsonArray).orEmpty().map { it.jsonObject }
                .firstOrNull { letters[it.str("optionId")] == letter }?.str("text")
            if (chosen == null && !answer.payload.flag("dontKnow")) continue
            latest[posed.payload.str("fingerprint")] = Latest(normalizeChoiceText(chosen.orEmpty()), answer.payload.flag("guessed"), answer.occurredAt / 86_400_000, answer.payload.flag("dontKnow"))
        }
    }
    /** Mastered: the latest answer picks the current key, was not a guess, and someone has checked that key. */
    fun status(item: MainlineItem): String {
        val last = latest[item.fingerprint] ?: return "new"
        val right = last.chosen == normalizeChoiceText(item.unit.options[item.unit.answer])
        return when {
            !item.verified -> "pending"
            last.dontKnow -> "missed"
            right && last.guessed -> "guessed"
            right -> "mastered"
            else -> "missed"
        }
    }
}

/** Weekly choice mainline: packs and fact lists live under `mcq-mainline/` in the private generated directory, never in the repository. */
class Mainline(private val library: MockLibrary, private val clock: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = java.security.SecureRandom(),
    private val syntheticRuntime: SyntheticLadderRuntime? = null,
    private val syntheticCapabilities: Set<String> = emptySet()) {
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
            MainlineItem(unit, item.str("tier"), item.strings("fact_ids"), item.str("source"), item.str("anchor"), item["learning"] as? JsonObject)
        }.sortedBy { tiers.indexOf(it.tier) }
        val facts = if (!Files.isRegularFile(factsFile)) emptyList() else {
            val block = Regex("```jsonl\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(Files.readString(factsFile))?.groupValues?.get(1).orEmpty()
            block.lines().filter { it.isNotBlank() }.map { line ->
                val f = LedgerJson.parseToJsonElement(line).jsonObject
                MainlineFact(f.str("fact_id"), f.str("fact"), f.str("type"), f.str("source"), f.str("kp"), f.str("anchor"))
            }
        }
        val (notes, notesError) = loadMainlineNotes(factsFile.resolveSibling("W%02d-notes.json".format(week)), week, facts)
        val emphasis = loadMainlineEmphasis(factsFile.resolveSibling("W%02d-emphasis.json".format(week)), week, facts)
        return MainlineWeek(week, (raw["sections"] as JsonArray).first().jsonObject.str("title").ifBlank { "Week $week" }, built, facts, notes, notesError, emphasis)
    }

    private fun notes(week: MainlineWeek, history: ChoiceHistory) = JsonArray(week.notes.map { section ->
        val mastered = section.strings("fact_ids").all { fact ->
            val linked = week.items.filter { fact in it.factIds }
            linked.isNotEmpty() && linked.all { history.status(it) == "mastered" }
        }
        JsonObject(section + ("mastered" to JsonPrimitive(mastered)))
    })

    private fun round(events: List<Event>, item: MainlineItem) = events.count { it.type == "question_posed" && it.payload.str("fingerprint") == item.fingerprint }

    private fun coverage(week: MainlineWeek, history: ChoiceHistory): JsonObject {
        val status = week.items.associate { it.unit.itemId to history.status(it) }
        val linked = week.facts.associate { f -> f.id to week.items.filter { f.id in it.factIds } }
        return json("facts" to week.facts.size, "factsWithQuestions" to linked.count { it.value.isNotEmpty() },
            "factsMastered" to linked.count { (_, qs) -> qs.isNotEmpty() && qs.all { status[it.unit.itemId] == "mastered" } },
            "questions" to week.items.size, "answered" to status.count { it.value != "new" },
            "mastered" to status.count { it.value == "mastered" }, "keysPending" to week.items.count { !it.verified })
    }

    /** Same citation splitting as the reader client's citationParts; the dot inside p.N remains intact. */
    private fun refs(source: String): List<String> =
        source.split(Regex("[;；\n]")).map { it.trim().trimEnd('。', '．', '.', '，', ',', '、').trimEnd() }.filter { it.isNotEmpty() }

    /**
     * Facts and questions whose registered source lands on one PDF page. A source that is blank, unregistered or ambiguous is counted
     * as `unlocated` for the whole course and never guessed onto a page, so the reader can say the index is incomplete.
     */
    internal fun pageStudy(store: LedgerStore, course: String, documentId: String, page: Int, locate: (String) -> Pair<String, Int>?): JsonObject {
        val events = store.events(course)
        val (weeks, _) = weeks(courseNames(events, course))
        val history = ChoiceHistory(events)
        fun onPage(source: String) = refs(source).any { locate(it) == documentId to page }
        val questions = mutableListOf<JsonObject>(); val facts = mutableListOf<JsonObject>(); var unlocated = 0
        for (w in weeks) {
            for (item in w.items) {
                if (item.source.isBlank() || refs(item.source).none { locate(it) != null }) unlocated++
                else if (onPage(item.source)) questions += json("week" to w.week, "id" to item.unit.itemId, "tier" to item.tier,
                    "stem" to item.unit.stem.take(160), "status" to history.status(item), "anchor" to item.anchor.take(300))
            }
            for (f in w.facts) {
                if (f.source.isBlank() || refs(f.source).none { locate(it) != null }) unlocated++
                else if (onPage(f.source)) {
                    val linked = w.items.filter { f.id in it.factIds }
                    facts += json("week" to w.week, "id" to f.id, "text" to f.text.take(200), "type" to f.type, "kp" to f.kp, "anchor" to f.anchor.take(300),
                        "questions" to linked.size, "mastered" to (linked.isNotEmpty() && linked.all { history.status(it) == "mastered" }))
                }
            }
        }
        return json("documentId" to documentId, "pdfPage" to page, "facts" to JsonArray(facts), "questions" to JsonArray(questions),
            "factsMastered" to facts.count { it.flag("mastered") }, "questionsMastered" to questions.count { it.str("status") == "mastered" },
            "unlocated" to unlocated)
    }

    /**
     * Chapter → section → exam point → question for one document. Placement uses only registered sources resolved through [locate]
     * and the catalog outline: a point with no placeable source, with sources on several different pages, or outside every chapter
     * is counted and reported, never guessed into the tree.
     */
    internal fun documentMap(store: LedgerStore, course: String, documentId: String, pages: Int, outline: List<OutlineNode>,
                             locate: (String) -> Pair<String, Int>?): JsonObject {
        val events = store.events(course)
        val (weeks, _) = weeks(courseNames(events, course))
        val history = ChoiceHistory(events)
        var unlocated = 0; var ambiguous = 0; var outside = 0
        /** null when it must not be placed; the counters say why. */
        fun place(source: String): Int? {
            val resolved = refs(source).mapNotNull(locate)
            if (resolved.isEmpty()) { unlocated++; return null }
            val here = resolved.filter { it.first == documentId }.map { it.second }.distinct()
            if (here.size > 1) { ambiguous++; return null }
            return here.singleOrNull()
        }
        class Bucket(val title: String, val page: Int, val end: Int) {
            val facts = mutableListOf<JsonObject>(); val questions = mutableListOf<JsonObject>()
            val sections = mutableListOf<Bucket>()
            fun view(): JsonObject = json("title" to title, "pdfPage" to page, "endPage" to end, "facts" to JsonArray(facts), "questions" to JsonArray(questions),
                "factsMastered" to facts.count { it.flag("mastered") }, "questionsMastered" to questions.count { it.str("status") == "mastered" },
                "sections" to JsonArray(sections.map { it.view() }))
        }
        val chapters = outline.mapIndexed { i, c ->
            val end = (outline.getOrNull(i + 1)?.page ?: (pages + 1)) - 1
            Bucket(c.title, c.page, end).also { b -> b.sections += c.sections.mapIndexed { j, s ->
                Bucket(s.title, s.page, (c.sections.getOrNull(j + 1)?.page ?: (end + 1)) - 1) } }
        }
        fun bucket(page: Int): Bucket? {
            val chapter = chapters.lastOrNull { it.page <= page && page <= it.end } ?: return null
            return chapter.sections.lastOrNull { it.page <= page && page <= it.end } ?: chapter
        }
        for (w in weeks) {
            for (item in w.items) {
                val page = place(item.source) ?: continue
                val b = bucket(page) ?: run { outside++; null } ?: continue
                b.questions += json("week" to w.week, "id" to item.unit.itemId, "tier" to item.tier, "stem" to item.unit.stem.take(160), "anchor" to item.anchor.take(300),
                    "status" to history.status(item), "pdfPage" to page)
            }
            for (f in w.facts) {
                val page = place(f.source) ?: continue
                val b = bucket(page) ?: run { outside++; null } ?: continue
                val linked = w.items.filter { f.id in it.factIds }
                b.facts += json("week" to w.week, "id" to f.id, "text" to f.text.take(200), "type" to f.type, "kp" to f.kp, "anchor" to f.anchor.take(300), "pdfPage" to page,
                    "questions" to linked.size, "mastered" to (linked.isNotEmpty() && linked.all { history.status(it) == "mastered" }))
            }
        }
        return json("documentId" to documentId, "hasOutline" to outline.isNotEmpty(), "chapters" to JsonArray(chapters.map { it.view() }),
            "unlocated" to unlocated, "ambiguous" to ambiguous, "outsideOutline" to outside)
    }

    private fun find(names: Set<String>, week: Int) = weeks(names).first.firstOrNull { it.week == week } ?: throw MockError(HttpStatusCode.NotFound, "找不到第 $week 周的选择题")

    /** The client cannot supply its own evidence or ask about an obsolete/forged selection. */
    internal fun selection(store: LedgerStore, course: String, number: Int, input: JsonObject): String {
        val week = find(courseNames(store.events(course), course), number)
        if (input.str("itemId").isNotBlank()) {
            val item = week.items.firstOrNull { it.unit.itemId == input.str("itemId") && it.eligibleForLearning(courseNames(store.events(course), course), number,
                weekPoints(store.events(course)).map { it.id }.toSet().takeIf { it.isNotEmpty() }) }
                ?: throw MockError(HttpStatusCode.Conflict, "题目已更新或尚未通过核验，请刷新后再问。")
            val question = input.str("question").trim()
            if (question.isBlank() || question.length > 500) throw MockError(HttpStatusCode.BadRequest, "请写下要问的一句话。")
            // The server supplies the current frozen stem, never a caller's substituted quote or key.
            return "当前题：${item.unit.stem}\n学习提问：$question"
        }
        val section = week.notes.firstOrNull { it.str("section_id") == input.str("sectionId") }
            ?: throw MockError(HttpStatusCode.Conflict, "要点已更新，请重新读取后再选中。")
        val text = section.str("body")
        val start = input.num("start"); val end = input.num("end")
        if (input.str("version") != section.str("content_version") || start < 0 || end <= start ||
            end > text.length || end - start > 2000 || text.substring(start.toInt(), end.toInt()) != input.str("quote"))
            throw MockError(HttpStatusCode.Conflict, "选中文本与当前要点不一致，请重新读取后再选中。")
        return input.str("quote")
    }

    fun home(store: LedgerStore, course: String): JsonObject {
        val events = store.events(course)
        val (weeks, invalid) = weeks(courseNames(events, course))
        val history = ChoiceHistory(events)
        return json("weeks" to JsonArray(weeks.map { w -> json("week" to w.week, "title" to w.title, "coverage" to coverage(w, history)) }),
            "invalid" to JsonArray(invalid))
    }

    /** Questions in setup → detail → bridge order, without keys or explanations. */
    fun week(store: LedgerStore, course: String, number: Int, shuffled: Boolean = false, learning: Boolean = false): JsonObject {
        if (course == "SYNTHETIC" && number == 1 && syntheticRuntime != null)
            return syntheticRuntime.mainlineWeek(syntheticCapabilities)
        val events = store.events(course)
        val kpIds = weekPoints(events).map { it.id }.toSet().takeIf { it.isNotEmpty() }
        val names = courseNames(events, course)
        val week = find(names, number)
        val history = ChoiceHistory(events)
        return json("week" to week.week, "title" to week.title, "coverage" to coverage(week, history),
            "notes" to notes(week, history), "notesError" to week.notesError, "emphasis" to JsonArray(week.emphasis),
            "learning" to learning, "runtime" to LedgerJson.encodeToJsonElement(LadderRuntimeContext()),
            "withheldReasons" to JsonObject(week.items.flatMap { it.ladder(kpIds)?.contractIssues.orEmpty() }
                .groupingBy { it }.eachCount().mapValues { JsonPrimitive(it.value) }),
            "withheld" to if (learning) week.items.count { !it.eligibleForLearning(names, number, kpIds) } else 0,
            "items" to JsonArray(week.items.filter { !learning || it.eligibleForLearning(names, number, kpIds) }.map { item ->
                val layout = round(events, item)
                val order = if (shuffled) optionOrder(item.fingerprint, layout, item.unit.options.size) else item.unit.options.indices.toList()
                json("id" to item.unit.itemId, "tier" to item.tier, "stem" to item.unit.stem, "factIds" to item.factIds,
                    "options" to order.map { item.unit.options[it] }, "layout" to if (shuffled) layout else null,
                    "status" to history.status(item), "keyState" to item.unit.keyState.ifBlank { "UNREVIEWED" },
                    "ladder" to item.ladder(kpIds)?.let { LedgerJson.encodeToJsonElement(it) })
            }))
    }

    /**
     * One answer is one attempt: posed, submitted and graded commit together, then the explanation is returned at once.
     * `choice`, `answer` and the per-option reasons are positions on screen; `layout` says which shuffle was on screen, and a client
     * that sends none showed the pack order.
     */
    fun answer(store: LedgerStore, course: String, number: Int, input: JsonObject): JsonObject = store.tutorTransaction {
        if (course == "SYNTHETIC" && number == 1 && syntheticRuntime != null)
            return@tutorTransaction syntheticRuntime.mainlineAnswer(input, syntheticCapabilities)
        val events = events(course)
        val week = find(courseNames(events, course), number)
        val item = week.items.firstOrNull { it.unit.itemId == input.str("itemId") } ?: throw MockError(HttpStatusCode.NotFound, "找不到这道题")
        val unit = item.unit
        var dontKnow = input.flag("dontKnow")
        var choice = if (dontKnow) -1 else (input["choice"] as? JsonPrimitive)?.intOrNull?.takeIf { it in unit.options.indices } ?: throw MockError(HttpStatusCode.BadRequest, "请选一个选项")
        var guessed = input.flag("guessed")
        val submission = input.str("submissionId").filter { it.isLetterOrDigit() || it == '-' }.take(64)
        val attempt = "mainline-" + submission.ifBlank { ulid(clock(), ByteArray(10).also(random::nextBytes)).lowercase() }
        val keyState = unit.keyState.ifBlank { "UNREVIEWED" }
        val options = item.options
        val fingerprint = item.fingerprint
        val qid = "mock-" + fingerprint.take(24)
        val letters = optionLetters(options.size)
        val posed = events.firstOrNull { it.type == "question_posed" && it.payload.str("attemptId") == attempt }
        if (posed == null && input.flag("learning") && !item.eligibleForLearning(courseNames(events, course), number,
                weekPoints(events).map { it.id }.toSet().takeIf { it.isNotEmpty() }))
            throw MockError(HttpStatusCode.Conflict, "这道题尚未通过内容核验，请刷新题路。原作答仍保留。")
        val tryFirst = if (posed != null) posed.payload.flag("tryFirst") else input.flag("learning") && item.ladder()?.kind == "try_first"
        val assisted = posed?.payload?.flag("assisted") ?: input.flag("assisted")
        if (posed != null && (!posed.payload.flag("mainline") || posed.payload.num("mainlineWeek") != week.week.toLong() ||
                posed.payload.str("mainlineItem") != unit.itemId || posed.payload.str("questionId") != qid)) {
            throw MockError(HttpStatusCode.Conflict, "这次提交已用于另一道题，请重新打开这一题")
        }
        val order = if (posed != null) {
            // A resend answers with what was recorded the first time, whatever layout it carries now.
            val shown = (posed.payload["letterMap"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
            letters.map { l -> shown.entries.first { it.value == l }.key.drop(1).toInt() }.also {
                val sent = events.first { it.type == "answer_submitted" && it.payload.str("attemptId") == attempt }.payload
                choice = letters.indexOf(sent.str("answer")); guessed = sent.flag("guessed"); dontKnow = sent.flag("dontKnow")
            }
        } else (input["layout"] as? JsonPrimitive)?.intOrNull?.let { optionOrder(fingerprint, it, options.size) } ?: options.indices.toList()
        val picked = order.getOrNull(choice)
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
                "tier" to item.tier, "tryFirst" to tryFirst, "assisted" to assisted, "variantGroupId" to item.ladder()?.variantGroupId,
                "candidateId" to item.learning?.str("id"), "candidateHash" to (item.learning?.get("runtime_approval") as? JsonObject)?.str("candidate_sha256"),
                "calculation" to item.learning?.get("calculation"), "essay" to item.learning?.get("essay"),
                "marks" to unit.marks, "stem" to unit.stem, "questionId" to qid, "format" to "SINGLE_CHOICE", "fingerprint" to fingerprint,
                "options" to LedgerJson.encodeToJsonElement(options), "letterMap" to JsonObject(order.withIndex().associate { (shown, i) -> "o$i" to JsonPrimitive(letters[shown]) })),
                "attempt:$attempt", occurredAt = now)
            append(course, "answer_submitted", json("attemptId" to attempt, "answer" to if (dontKnow) "" else letters[choice], "finalAnswer" to "", "guessed" to guessed, "dontKnow" to dontKnow), "attempt:$attempt:answer", occurredAt = now)
            if (assisted) append(course, "hint_revealed", json("attemptId" to attempt, "cause" to "FOLLOWUP"), "attempt:$attempt:followup")
            val right = picked == unit.answer
            val key = "正确答案 ${letters[shownAnswer]}：${unit.options[unit.answer]}"
            // The pack numbers distractors by pack position; the record names them by the letter that was on screen.
            val explanation = unit.explanation.replace(Regex("(?m)^(\\s*)错项\\s*(\\d+)")) { m ->
                m.groupValues[2].toInt().takeIf { it in order.indices }?.let { "${m.groupValues[1]}错项 ${letters[order.indexOf(it)]}" } ?: m.value
            }
            val feedback = (if (dontKnow) "这次先看讲解，已记录学习，不算答对。" else if (!item.verified) "答案键待核对，这次不记对错。" else if (right) "正确。" else "你选 ${letters[choice]}，$key") + (if (guessed) "（标记为猜的）" else "") +
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
        val parts = if (input.flag("learning")) item.ladderExplanation() else explanationParts(unit.explanation)
        val reasons = (parts["options"] as JsonObject).mapNotNull { (i, why) -> i.toIntOrNull()?.takeIf { it in order.indices }?.let { "${order.indexOf(it)}" to why } }.toMap()
        json("itemId" to unit.itemId, "attemptId" to attempt, "choice" to choice, "answer" to shownAnswer, "guessed" to guessed, "dontKnow" to dontKnow,
            "independentCorrect" to (picked == unit.answer && !guessed && !dontKnow && !tryFirst && !assisted && item.verified), "tryFirst" to tryFirst,
            "remember" to item.learning?.str("remember"), "tryFirstRefs" to item.learning?.get("try_first_refs"),
            "calculation" to item.learning?.get("calculation"), "essay" to item.learning?.get("essay"),
            "keyState" to keyState, "status" to status, "tier" to item.tier, "explanation" to JsonObject(parts + ("options" to JsonObject(reasons))),
            "source" to item.source, "anchor" to item.anchor.take(300), "kpId" to kp, "dueDay" to due, "mistakeWeek" to if (book) (unit.week ?: number) else null,
            "next" to week.items.getOrNull(index + 1)?.unit?.itemId, "coverage" to coverage(week, history), "notes" to notes(week, history))
    }
}

fun Application.mainlineRoutes(actor: LedgerActor, pushes: MutableSharedFlow<String>, mainline: Mainline, reader: ReaderExplanation? = null) {
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
            call.answer(c) { mainline.week(this, c, call.week(), shuffled, call.request.queryParameters["learning"] == "v1") }
        }
        post("/v1/courses/{course}/mainline/{week}/answer") { val c = call.parameters["course"]!!; call.answer(c) { mainline.answer(this, c, call.week(), it) } }
        post("/v1/courses/{course}/mainline/{week}/explain") { call.withReaderConnection {
            try {
                val input = LedgerJson.parseToJsonElement(call.receiveText()).jsonObject
                val course = call.parameters["course"]!!
                val (quote, pages) = actor.execute {
                    val quote = mainline.selection(this, course, call.week(), input)
                    quote to searchSourcePages(course, quote).filter { it.learnerVisible }
                }
                val body = reader?.explain(call.attributes[observedDevice], course, input, quote, pages)
                    ?: json("text" to "课程依据服务暂不可用，无法确认。", "sources" to JsonArray(emptyList()), "elapsedMs" to 0)
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (e: MockError) {
                call.respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status)
            } catch (e: IllegalArgumentException) {
                call.respondText(json("error" to "选中追问格式不对。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        } }
        post("/v1/courses/{course}/mainline/{week}/explain/cancel") {
            val input = runCatching { LedgerJson.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
            reader?.cancel(call.attributes[observedDevice], call.parameters["course"]!!, input?.str("requestId").orEmpty())
            call.respondText("{}", ContentType.Application.Json)
        }
    }
}
