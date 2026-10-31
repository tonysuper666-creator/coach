package com.coach.client

import com.coach.domain.Event
import com.coach.domain.Mastery
import com.coach.domain.Projection
import com.coach.domain.project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 记录页的一行：某个知识点的掌握发生了什么变化，以及支撑它的作答。 */
enum class ChangeKind(val label: String) {
    PASSED("已通过"), SOLID("达到稳固"), PASS_REVOKED("撤销已通过"), SOLID_LOST("曾稳固"), NEEDS_REPAIR("需回炉"),
}

data class MasteryChange(
    val seq: Long, val at: Long, val kpId: String, val kind: ChangeKind,
    /** 这次变化为什么发生，写给学习者看的一句话。 */
    val reason: String,
    /** 支撑（或被作废的）作答，每个都能下钻到原题原答。 */
    val attemptIds: List<String>,
    val causeEventType: String,
)

/** 没有算进任何一层、但要如实显示的作答（并发作答、题目已变更等）。 */
data class OrphanedAnswer(val seq: Long, val at: Long, val attemptId: String, val reason: String, val stem: String, val rawAnswer: String)

data class GradeItem(val id: String, val points: Double, val reason: String, val evidence: String)

/** 逐字证据：区间是 UTF-16 下标，落在原答 [field] 上。 */
data class EvidenceSpan(val rubricItemId: String, val field: String, val start: Int, val end: Int, val text: String)

data class GradeView(
    val status: String, val score: Int, val max: Int, val feedback: String,
    val items: List<GradeItem>, val evidence: List<EvidenceSpan>, val graderModel: String,
)

data class ReviewView(val decision: String, val note: String, val reviewerModel: String, val graderModel: String, val revised: GradeView?)

/** 一次作答的全部事实：原题、原答、批改与复核。构答题的评分标准要联网向 Mac 取（[rubricFrom]），离线时只有账本里的部分。 */
data class AttemptDetail(
    val attemptId: String, val kpIds: List<String>, val format: String, val purpose: String, val stem: String,
    val options: List<Pair<String, String>>, val answer: String, val finalAnswer: String,
    val submittedAt: Long?, val grade: GradeView?, val review: ReviewView?,
    val voided: Boolean, val hinted: Boolean,
) {
    val isChoice: Boolean get() = format == "SINGLE_CHOICE"
    /** 复核之后以复核后的批改为准；还没复核的构答草稿不计分。 */
    val effectiveGrade: GradeView? get() = review?.revised ?: grade
    val counted: Boolean get() = grade != null && (grade.status == "EXACT" || grade.status == "VERIFIED" || review != null)
}

/** 评分标准（冻结题）：只在提交后由 Mac 返回。 */
data class RubricView(val rubric: List<RubricItem>, val referenceAnswer: String, val fingerprint: String)
/** [basis] 是评分点对应的教材原文。 */
data class RubricItem(val id: String, val points: Double, val text: String, val basis: String = "")

/**
 * 账本回放出的记录视图。纯函数：重启、断线、换设备结果不变。
 * 掌握变化 = 每个可能改变掌握的事件前后各投影一次，对比两次的掌握状态（见 DESIGN §2.2：锁存与撤销按事件逐个判定）。
 */
class RecordsView(private val events: List<Event>) {
    private val sorted = events.sortedBy { it.seq }

    private val triggers = setOf(
        "attempt_graded", "grade_reviewed", "attempt_voided", "question_key_changed", "kp_overridden", "override_cleared",
        "legacy_passed", "server_restored",
    )

    val changes: List<MasteryChange> by lazy {
        val out = ArrayList<MasteryChange>()
        var previous = project(emptyList())
        for ((i, e) in sorted.withIndex()) {
            if (e.type !in triggers) continue
            val current = project(sorted.subList(0, i + 1))
            out += diff(previous, current, e)
            previous = current
        }
        out
    }

    val orphans: List<OrphanedAnswer> by lazy {
        sorted.filter { it.type == "answer_orphaned" }.map { e ->
            val snapshot = e.payload["posedSnapshot"] as? JsonObject
            OrphanedAnswer(e.seq, e.occurredAt, e.payload.s("attemptId"), orphanReason(e.payload.s("reason")), snapshot?.s("stem").orEmpty(), e.payload.s("rawAnswer"))
        }
    }

    fun attempt(attemptId: String): AttemptDetail? {
        val es = sorted.filter { it.payload.s("attemptId") == attemptId }
        val posed = es.firstOrNull { it.type == "question_posed" } ?: return null
        val p = posed.payload
        val letters = p["letterMap"] as? JsonObject
        val options = (p["options"] as? JsonArray).orEmpty().mapNotNull { option ->
            val o = option as? JsonObject ?: return@mapNotNull null
            val letter = (letters?.get(o.s("optionId")) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            letter to o.s("text")
        }.sortedBy { it.first }
        val submitted = es.firstOrNull { it.type == "answer_submitted" }
        val graded = es.lastOrNull { it.type == "attempt_graded" }
        val reviewed = es.lastOrNull { it.type == "grade_reviewed" }
        return AttemptDetail(
            attemptId, p.strings("kpIds"), p.s("format"), p.s("purpose"), p.s("stem"), options,
            submitted?.payload?.s("answer").orEmpty(), submitted?.payload?.s("finalAnswer").orEmpty(), submitted?.occurredAt,
            graded?.payload?.let(::gradeView), reviewed?.payload?.let(::reviewView),
            es.any { it.type == "attempt_voided" }, es.any { it.type == "hint_revealed" },
        )
    }

    private fun diff(before: Projection, after: Projection, cause: Event): List<MasteryChange> {
        val out = ArrayList<MasteryChange>()
        for ((kp, now) in after.mastery) {
            val was = before.mastery[kp] ?: Mastery()
            fun add(kind: ChangeKind, reason: String, attempts: List<String>) { out += MasteryChange(cause.seq, cause.occurredAt, kp, kind, reason, attempts, cause.type) }
            if (!was.passed && now.passed) add(ChangeKind.PASSED, passedReason(now, cause), now.support.ifEmpty { now.window })
            if (was.passed && !now.passed) add(ChangeKind.PASS_REVOKED, revokedReason(now.revokedReason, cause), was.support.ifEmpty { was.window })
            if (!was.solid && now.solid) add(ChangeKind.SOLID, "有效构答 ${now.solidProgress.count} 条（其中构答 ${now.solidProgress.constructed}），分布在 ${now.solidProgress.days} 天，平均得分率 ${"%.2f".format(now.solidProgress.average)}。", now.solidLatch)
            if (was.solid && !now.solid) add(ChangeKind.SOLID_LOST, "最近一次合格情境没通过，显示「曾稳固」，直到新的合格通过。", was.solidLatch)
            if (!was.needsRepair && now.needsRepair) add(ChangeKind.NEEDS_REPAIR, "已通过之后又答错一道：不撤销已通过，只标需回炉，明天进复习。", now.window.takeLast(1))
        }
        return out
    }

    private fun passedReason(m: Mastery, cause: Event) = when (m.passedBy) {
        "LEGACY" -> "来自 DeepTutor 的已通过（迁移时锁存），不依赖新的作答。"
        "LEARNER" -> "你手动标记为已通过。"
        else -> "最近 3 次有效首答全部答对，且 3 道题互不相同。"
    }

    /** 账本里写的是「事件类型:原因:作答号」。 */
    private fun revokedReason(raw: String?, cause: Event): String {
        val parts = raw.orEmpty().split(":")
        val what = when (parts.getOrNull(0)) {
            "attempt_voided" -> "有一次作答被作废"
            "question_key_changed" -> "一道题的答案键被判错"
            "grade_reviewed" -> "复核推翻了原批改"
            else -> "支撑证据失效"
        }
        val why = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "evidence_invalid" }?.let { "（$it）" }.orEmpty()
        return "$what$why，支撑已通过的有效首答不足 3 条，已撤销。之后按窗口重新达标。"
    }

    private fun gradeView(p: JsonObject) = GradeView(
        p.s("status"), p.i("score"), p.i("max").coerceAtLeast(1), p.s("feedback"),
        items(p), evidence(p), p.s("graderModel"),
    )

    private fun reviewView(p: JsonObject) = ReviewView(p.s("decision"), p.s("note"), p.s("reviewerModel"), p.s("graderModel"), (p["revised"] as? JsonObject)?.let { r ->
        GradeView("REVIEWED", r.i("score"), r.i("max").coerceAtLeast(1), r.s("feedback"), items(r), evidence(r), p.s("reviewerModel"))
    })

    private fun items(p: JsonObject): List<GradeItem> = (p["items"] as? JsonArray).orEmpty().mapNotNull { v ->
        val o = v as? JsonObject ?: return@mapNotNull null
        GradeItem(o.s("id"), (o["points"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, o.s("reason"), o.s("evidence"))
    }

    private fun evidence(p: JsonObject): List<EvidenceSpan> = (p["evidence"] as? JsonArray).orEmpty().mapNotNull { v ->
        val o = v as? JsonObject ?: return@mapNotNull null
        EvidenceSpan(o.s("rubricItemId"), o.s("field").ifBlank { "answer" }, o.i("start"), o.i("end"), o.s("text"))
    }

    private fun orphanReason(raw: String) = when (raw) {
        "CONCURRENT_ANSWER" -> "同一道题在另一台设备上已经答过，这次作答没有计入。"
        "QUESTION_MISSING" -> "这道题在服务端找不到了，这次作答没有计入。"
        "QUESTION_CHANGED" -> "提交时题目已经变了，这次作答没有计入。"
        else -> "这次作答没有计入（$raw）。"
    }

    private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.i(key: String): Int = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() } ?: 0
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

/** `GET /v1/courses/{course}/attempts/{attempt}` 返回的冻结题。 */
fun rubricFrom(record: JsonObject): RubricView? {
    val q = record["question"] as? JsonObject ?: return null
    val rubric = (q["rubric"] as? JsonArray).orEmpty().mapNotNull { v ->
        val o = v as? JsonObject ?: return@mapNotNull null
        RubricItem(o.s2("id"), (o["points"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, o.s2("text"), o.s2("basis"))
    }
    if (rubric.isEmpty()) return null
    return RubricView(rubric, q.s2("reference_answer"), record.s2("rubricFingerprint"))
}

private fun JsonObject.s2(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
