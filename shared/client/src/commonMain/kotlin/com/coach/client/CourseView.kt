package com.coach.client

import com.coach.domain.courseExamDay
import com.coach.domain.calendarDay
import com.coach.domain.reviewsOn
import com.coach.domain.REVISIT
import com.coach.domain.weekPoints
import com.coach.domain.Event
import com.coach.domain.Projection
import com.coach.domain.logicalDay
import com.coach.domain.reader.KnowledgeSourceReferences
import com.coach.domain.reader.readKnowledgeSources
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 全 App 唯一的学习状态词（TON-1444）：未学 / 在学 / 会了 / 稳了 / 要补。所有页面只通过 [label]、[symbol] 输出。
 * 定义：未学 = 没打开过；在学 = 打开过或做过题、还没会；会了 = 最近 3 道不同题答对且不含猜对；
 * 稳了 = 会了之后在不同两天用简答/论述/限时题答对；要补 = 会了之后又答错，已排进复习。
 */
enum class KpStatus(val state: com.coach.domain.KpState, val symbol: String) {
    NEW(com.coach.domain.KpState.NEW, "○"), IN_PROGRESS(com.coach.domain.KpState.IN_PROGRESS, "◐"),
    PASSED(com.coach.domain.KpState.PASSED, "✓"), SOLID(com.coach.domain.KpState.SOLID, "✓✓"),
    NEEDS_REPAIR(com.coach.domain.KpState.NEEDS_REPAIR, "!");
    val label: String get() = state.label
}

data class KpRow(
    val id: String, val title: String, val status: KpStatus, val explained: Boolean, val conceptIds: List<String>,
    /** 课程模块（章）名；没有就空。 */
    val module: String = "",
    /** 备课教材页范围，如「教材 p.12–15」；锚点没有书页号就空。 */
    val reference: String = "",
    /** 曾稳固后又没过合格情境（路线上写「曾稳固」，不是「稳固」）。 */
    val formerlySolid: Boolean = false,
    /** 稳了进度，如「2/2 次 · 简答 1 · 2 天 · 均分 0.88」；还没有任何合格证据就空。 */
    val solidProgress: String = "",
    /** 前置知识点（软锁：只提示「建议先学 X」，不阻止打开）。 */
    val prereqs: List<String> = emptyList(),
    /** 备课教材页的文档与起止页（PDF 页），资料页用它跳到「本次参考」。 */
    val anchorDoc: String = "", val anchorPdfPage: Int = 0, val anchorBookPage: Int = 0,
    /** Claim-specific references keep documents/ranges separate; the legacy jump is blank for this wire format. */
    val sourceReferences: KnowledgeSourceReferences = KnowledgeSourceReferences(),
    /** Provenance of these outline references; a reader from another course must not consume them. */
    val sourceCourseId: String? = null,
)

data class QuizCard(
    val attemptId: String,
    val stem: String,
    /** (字母, 选项文字)，按字母排序；字母是服务端在 `question_posed` 里分配的。 */
    val options: List<Pair<String, String>>,
    val untaught: Boolean,
    val answer: String? = null,
    val grade: CardGrade? = null,
)

/** 到期复习的一项：[overdueDays] 为 0 表示今天到期。 */
/** [revisit]：答错或猜对的题把这个点排回来再看一次，不是间隔复习到期。 */
data class DueReview(val kp: KpRow, val dueDay: Long, val overdueDays: Int, val revisit: Boolean = false)

data class CardGrade(val correct: Boolean, val score: Int, val max: Int, val feedback: String, val note: String = "")
data class QuizGroup(val groupId: String, val cards: List<QuizCard>) {
    val submitted: Boolean get() = cards.all { it.answer != null }
}

/** 账本回放出的课程视图：知识点列表、掌握状态、某个 KP 线程里的题组与判分。纯函数，重启/断线后结果不变。 */
class CourseView(events: List<Event>, val projection: Projection, private val agentRun: String? = null) {
    val events: List<Event> = com.coach.domain.learnerEvidence(events, agentRun)
    private fun lessonThread(thread: String) = agentRun?.let { com.coach.domain.agentThreadId(it, thread) } ?: thread

    /** Reopen the persisted tutor thread for a KP; new KPs get the stable client-side ID. */
    fun tutorThreadId(kpId: String): String = lessonThread(events.lastOrNull {
        it.type == "kp_opened" && it.payload.s("via") != "REVIEW" && it.payload.s("kpId") == kpId && !it.threadId.isNullOrBlank()
    }?.threadId ?: "kp:$kpId")

    fun reviewThreadId(kpId: String): String = lessonThread(events.filter {
        it.type == "kp_opened" && it.payload.s("via") == "REVIEW" && it.payload.s("kpId") == kpId && !it.threadId.isNullOrBlank()
    }.maxByOrNull { it.seq }?.threadId ?: "review:${events.firstOrNull()?.courseId.orEmpty()}:$kpId")

    val mistakes: MistakeReview by lazy { mistakeReview(events) }

    /**
     * 全量行 + 退休集合。名称查询（记录、错题、已打开的知识点）要包含退休点，
     * 否则历史会露出原始 id；学习路线和周目标只看 [kps]（活跃点）。
     */
    private class Rows(val all: List<KpRow>, val retired: Set<String>)

    private val rows: Rows by lazy {
        val titles = LinkedHashMap<String, String>()
        val concepts = HashMap<String, List<String>>()
        val modules = HashMap<String, String>()
        val moduleOf = HashMap<String, String>()
        val references = HashMap<String, String>()
        val prereqs = HashMap<String, List<String>>()
        val anchorDocs = HashMap<String, Triple<String, Int, Int>>()
        // 退休的点不再出现在学习路线里；语义与 weekPoints 一致：后一条 add 可以把它重新启用。
        val retired = HashSet<String>()
        val sourceReferences = HashMap<String, KnowledgeSourceReferences>()
        val sourceCourses = HashMap<String, String>()
        for (e in events) when (e.type) {
            "outline_revised" -> {
            // 课程内容装载（TON-1127）把整份 CourseContent 放在事件里：知识点名、模块、概念、教材页锚点。
            for (m in (e.payload["modules"] as? JsonArray).orEmpty()) (m as? JsonObject)?.let { modules[it.s("id")] = it.s("title").ifBlank { it.s("name") } }
            for (k in (e.payload["knowledgePoints"] as? JsonArray).orEmpty()) {
                val o = k as? JsonObject ?: continue
                val id = o.s("id"); if (id.isBlank()) continue
                if ((o["retired"] as? JsonPrimitive)?.contentOrNull == "true") { retired += id; continue }
                retired -= id
                titles[id] = o.s("title").ifBlank { o.s("name") }.ifBlank { id }
                moduleOf[id] = o.s("moduleId")
                (o["conceptIds"] as? JsonArray)?.mapNotNull { c -> (c as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }?.let { concepts[id] = it }
                for (key in listOf("prereqs", "prerequisites", "prerequisiteIds")) (o[key] as? JsonArray)?.mapNotNull { c -> (c as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }?.let { prereqs[id] = it }
                val sources = readKnowledgeSources(o)
                sourceReferences[id] = sources
                sourceCourses[id] = e.courseId
                // 课程内容装载写的是单个 `sourceAnchor`（旧形态是 `anchors` 数组），两种都认。
                // 「看原文」选页与备课选页同口径：先看 `anchors[]` 里第一个有文档和页码的，没有再用 `sourceAnchor`。
                val anchorObjects = (o["anchors"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } + listOfNotNull(o["sourceAnchor"] as? JsonObject)
                val openable = { a: JsonObject -> a.s("docId").isNotBlank() && ((a["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0) > 0 }
                if (sources.structured) {
                    anchorDocs.remove(id)
                    references.remove(id)
                } else anchorObjects.firstOrNull(openable)?.let { a ->
                    anchorDocs[id] = Triple(a.s("docId"), (a["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0, (a["bookPage"] as? JsonPrimitive)?.intOrNull ?: 0)
                }
                val pages = anchorObjects.mapNotNull { a -> (a["bookPage"] as? JsonPrimitive)?.intOrNull }
                if (!sources.structured && pages.isNotEmpty()) references[id] = if (pages.min() == pages.max()) "教材 p.${pages.min()}" else "教材 p.${pages.min()}–${pages.max()}"
            }
            for (op in (e.payload["ops"] as? JsonArray).orEmpty()) {
                val o = op as? JsonObject ?: continue
                val id = o.s("kpId").ifBlank { o.s("id") }
                if (id.isBlank()) continue
                val operation = o.s("op").ifBlank { "add" }
                if (operation == "retire") { retired += id; continue }
                if (operation == "add") retired -= id
                val title = o.s("title").ifBlank { o.s("name") }
                if (title.isNotBlank()) titles[id] = title else titles.putIfAbsent(id, "")
                (o["conceptIds"] as? JsonArray)?.let { concepts[id] = it.mapNotNull { c -> (c as? JsonPrimitive)?.contentOrNull } }
                (o["prereqs"] as? JsonArray)?.let { prereqs[id] = it.mapNotNull { c -> (c as? JsonPrimitive)?.contentOrNull } }
            }
            }
            "kp_opened" -> e.payload.s("title").takeIf { it.isNotBlank() }?.let { titles.putIfAbsent(e.payload.s("kpId"), it) }
        }
        projection.mastery.keys.forEach { titles.putIfAbsent(it, "") }
        // The outline owns display order; serialized mastery maps may sort their keys.
        val order = (titles.keys + projection.mastery.keys).distinct()
        Rows(order.filter { it in titles }.map { id ->
            val m = projection.mastery[id]
            // The list and chat read the same projected facts, including historical opens and quick-ask exclusion.
            val status = when (com.coach.domain.kpState(m, id in projection.startedKpIds)) {
                com.coach.domain.KpState.NEEDS_REPAIR -> KpStatus.NEEDS_REPAIR
                com.coach.domain.KpState.SOLID -> KpStatus.SOLID
                com.coach.domain.KpState.PASSED -> KpStatus.PASSED
                com.coach.domain.KpState.IN_PROGRESS -> KpStatus.IN_PROGRESS
                com.coach.domain.KpState.NEW -> KpStatus.NEW
            }
            val progress = m?.solidProgress?.takeIf { it.count > 0 }?.let { "${KpStatus.SOLID.label}进度 ${it.count}/2 次 · 简答 ${it.constructed} · ${it.days} 天 · 均分 ${(kotlin.math.round(it.average * 100) / 100.0)}" }.orEmpty()
            val anchor = anchorDocs[id]
            KpRow(id, titles.getValue(id), status, id in projection.explainedKpIds, concepts[id] ?: listOf(id), modules[moduleOf[id]].orEmpty(), references[id].orEmpty(),
                formerlySolid = m?.formerlySolid == true && m.solid.not(), solidProgress = progress, prereqs = prereqs[id].orEmpty(),
                anchorDoc = anchor?.first.orEmpty(), anchorPdfPage = anchor?.second ?: 0, anchorBookPage = anchor?.third ?: 0,
                sourceReferences = sourceReferences[id] ?: KnowledgeSourceReferences(), sourceCourseId = sourceCourses[id])
        }, retired)
    }

    /** 活跃知识点：学习路线、周目标和到期复习用它，退休点不在其中。 */
    val kps: List<KpRow> by lazy { rows.all.filter { it.id !in rows.retired } }

    /** 活跃查询：退休点返回 null。选点、队列、周目标用这个。 */
    fun kp(id: String): KpRow? = kps.firstOrNull { it.id == id }

    /** 全量查询：包含已退休的点。只供历史名称展示（记录、错题、已打开的知识点），不进队列。 */
    fun kpAny(id: String): KpRow? = rows.all.firstOrNull { it.id == id }

    /** Shares the projector clock, including the 04:00 logical-day boundary. */
    val zone: String get() = projection.timeZone

    fun logicalToday(nowMillis: Long): Long = logicalDay(nowMillis, zone)

    /** 到期（含逾期）的复习，最久逾期的在前；只列大纲里有的知识点。 */
    fun dueReviews(nowMillis: Long): List<DueReview> {
        val today = logicalToday(nowMillis)
        return projection.reviewsOn(today).mapNotNull { (id, review) ->
            val kp = kp(id) ?: return@mapNotNull null
            DueReview(kp, review.dueDay, (today - review.dueDay).toInt(), review.lastResult == REVISIT)
        }
    }

    /** 期末考试日：课程配置（课程内容 `course.examDate`）与手动 `exam_set` 里账本中最后写入的一个；都没有就是 null。 */
    val examDay: Long? by lazy { courseExamDay(events, zone) }

    /** 离期末还有几天（按课程时区的当前日历日，不是 04:00 逻辑日，也不是 UTC 小时差）；没设日期、或已经考过返回 null。 */
    fun daysToExam(nowMillis: Long): Int? = examDay?.let { (it - calendarDay(nowMillis, zone)).toInt() }?.takeIf { it >= 0 }

    val weekPoints by lazy { weekPoints(events) }
    /** The imported ECNM10119 outline explicitly records Week 7 as a zero-point midterm exemption. */
    val midtermExemptionWeeks: Set<Int> by lazy {
        events.asSequence().filter { it.type == "outline_revised" }.mapNotNull { event ->
            val week7 = event.payload["week7"] as? JsonObject ?: return@mapNotNull null
            val count = (week7["knowledgePoints"] as? JsonPrimitive)?.intOrNull
            val reason = (week7["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            7.takeIf { count == 0 && reason.contains("Midterm Exam", ignoreCase = true) }
        }.toSet()
    }
    val weeks: List<Int> by lazy { (weekPoints.flatMap { it.weeks } + midtermExemptionWeeks).distinct().sorted() }
    fun weekLabel(week: Int): String = if (week in midtermExemptionWeeks) "第 $week 周 · 期中周，无新课" else "第 $week 周"
    fun weekKps(week: Int): List<KpRow> = weekPoints.filter { week in it.weeks }.mapNotNull { kp(it.id) }
    /** 周目标与 `advance_study_scope` 用同一个可用集合（退休的点已排除），否则客户端选出的点服务端会判 STUDY_SCOPE_MOVED。 */
    val weekTarget: KpRow? get() = projection.studyScope?.target(projection.mastery, weekPoints.map { it.id }.toSet())?.let(::kp)
    fun weekProgress(kpId: String): String? = projection.studyScope?.let { s ->
        s.kpIds.indexOf(kpId).takeIf { it >= s.cursor }?.let { "第 ${s.week} 周 · 第 ${it + 1} / ${s.kpIds.size} 个" }
    }

    /** Pending work only wins while its KP is unfinished. A passed old thread cannot trap Continue. */
    val continueTarget: KpRow? by lazy {
        // Actual unfinished work wins over a previously selected week.
        projection.position.continueTarget?.let(::kp)?.takeIf { isResume(it) && it.status !in setOf(KpStatus.PASSED, KpStatus.SOLID) }
            ?: if (projection.studyScope != null) weekTarget else
                kps.firstOrNull { it.status == KpStatus.NEEDS_REPAIR }
                    ?: kps.firstOrNull { it.status !in setOf(KpStatus.PASSED, KpStatus.SOLID) }
    }
    fun isResume(kp: KpRow): Boolean = kp.explained || events.any { it.type == "kp_opened" && it.payload.s("kpId") == kp.id }

    fun quizGroups(threadId: String): List<QuizGroup> {
        val posed = events.filter { it.type == "question_posed" && it.threadId == threadId && it.payload.s("format") == "SINGLE_CHOICE" }
        if (posed.isEmpty()) return emptyList()
        val answers = HashMap<String, String>()
        val grades = HashMap<String, CardGrade>()
        for (e in events) when (e.type) {
            "answer_submitted" -> answers.putIfAbsent(e.payload.s("attemptId"), e.payload.s("answer").trim().uppercase())
            "attempt_graded" -> {
                val score = (e.payload["score"] as? JsonPrimitive)?.intOrNull ?: 0
                val max = (e.payload["max"] as? JsonPrimitive)?.intOrNull ?: 1
                grades[e.payload.s("attemptId")] = CardGrade(score >= max, score, max, e.payload.s("feedback"))
            }
        }
        val voided = events.filter { it.type == "attempt_voided" }.map { it.payload.s("attemptId") }.toSet()
        return posed.filter { it.payload.s("attemptId") !in voided }.groupBy { it.payload.s("groupId") }.map { (group, cards) ->
            QuizGroup(group, cards.map { card ->
                val p = card.payload
                val letters = p["letterMap"] as? JsonObject
                val options = (p["options"] as? JsonArray).orEmpty().mapNotNull { option ->
                    val o = option as? JsonObject ?: return@mapNotNull null
                    val letter = (letters?.get(o.s("optionId")) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    letter to o.s("text")
                }.sortedBy { it.first }
                val id = p.s("attemptId")
                QuizCard(id, p.s("stem"), options, p["untaught"]?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } == true, answers[id], grades[id])
            })
        }
    }

    /** 当前线程待答（已出题未提交）的最新一组。 */
    fun pendingGroup(threadId: String): QuizGroup? = quizGroups(threadId).lastOrNull { !it.submitted }

    private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
