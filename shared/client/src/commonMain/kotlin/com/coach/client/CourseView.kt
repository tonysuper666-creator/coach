package com.coach.client

import com.coach.domain.Event
import com.coach.domain.Projection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

enum class KpStatus(val label: String) { NEW("未开始"), IN_PROGRESS("学习中"), PASSED("已通过"), SOLID("稳固"), NEEDS_REPAIR("需修复") }

data class KpRow(val id: String, val title: String, val status: KpStatus, val explained: Boolean, val conceptIds: List<String>)

data class QuizCard(
    val attemptId: String,
    val stem: String,
    /** (字母, 选项文字)，按字母排序；字母是服务端在 `question_posed` 里分配的。 */
    val options: List<Pair<String, String>>,
    val untaught: Boolean,
    val answer: String? = null,
    val grade: CardGrade? = null,
)

data class CardGrade(val correct: Boolean, val score: Int, val max: Int, val feedback: String)
data class QuizGroup(val groupId: String, val cards: List<QuizCard>) {
    val submitted: Boolean get() = cards.all { it.answer != null }
}

/** 账本回放出的课程视图：知识点列表、掌握状态、某个 KP 线程里的题组与判分。纯函数，重启/断线后结果不变。 */
class CourseView(private val events: List<Event>, val projection: Projection) {

    val kps: List<KpRow> by lazy {
        val titles = LinkedHashMap<String, String>()
        val concepts = HashMap<String, List<String>>()
        val explained = HashSet<String>()
        for (e in events) when (e.type) {
            "outline_revised" -> for (op in (e.payload["ops"] as? JsonArray).orEmpty()) {
                val o = op as? JsonObject ?: continue
                val id = o.s("kpId").ifBlank { o.s("id") }
                if (id.isBlank()) continue
                val title = o.s("title").ifBlank { o.s("name") }
                if (title.isNotBlank()) titles[id] = title else titles.putIfAbsent(id, id)
                (o["conceptIds"] as? JsonArray)?.let { concepts[id] = it.mapNotNull { c -> (c as? JsonPrimitive)?.contentOrNull } }
            }
            "kp_opened" -> e.payload.s("title").takeIf { it.isNotBlank() }?.let { titles.putIfAbsent(e.payload.s("kpId"), it) }
            "explanation_delivered" -> explained += e.payload.s("kpId")
        }
        projection.mastery.keys.forEach { titles.putIfAbsent(it, it) }
        val attempted = events.filter { it.type == "question_posed" }.flatMap { posed -> (posed.payload["kpIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.toSet()
        // 顺序以投影的大纲顺序为准（mastery 按大纲顺序排列）；没有 mastery 的才按事件顺序补在后面。
        val order = (projection.mastery.keys + titles.keys).distinct()
        order.filter { it in titles }.map { id ->
            val m = projection.mastery[id]
            val status = when {
                m == null -> KpStatus.NEW
                m.needsRepair -> KpStatus.NEEDS_REPAIR
                m.solid -> KpStatus.SOLID
                m.passed -> KpStatus.PASSED
                id in explained || id in attempted || projection.position.current == id -> KpStatus.IN_PROGRESS
                else -> KpStatus.NEW
            }
            KpRow(id, titles.getValue(id), status, id in explained, concepts[id] ?: listOf(id))
        }
    }

    fun kp(id: String): KpRow? = kps.firstOrNull { it.id == id }

    /** 「继续：<KP>」的目标：状态机定的目标优先，没有就取大纲里第一个未通过的。 */
    val continueTarget: KpRow? by lazy {
        projection.position.continueTarget?.let(::kp) ?: kps.firstOrNull { it.status != KpStatus.PASSED && it.status != KpStatus.SOLID }
    }

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
