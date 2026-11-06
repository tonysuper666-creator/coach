package com.coach.client

import com.coach.domain.Event
import com.coach.domain.ProgressIntent
import com.coach.domain.Review
import com.coach.domain.answer
import com.coach.domain.json
import com.coach.domain.logicalDay
import com.coach.domain.onDay
import com.coach.domain.project
import com.coach.domain.today
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TON-1331 复核用失败测试：复习队列（CourseView.dueReviews）与聊天/`/today`（Projection.today）应同一口径。 */
class ReviewQueueConsistencyReviewTest {
    private var seq = 0L
    private fun event(type: String, payload: JsonObject) =
        Event(++seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq, deviceId = "d", actor = "SYSTEM", payload = payload)
    private val now = 1_793_448_000_000L // 2026-10-31 12:00 UTC
    private val today = logicalDay(now, "Europe/London")
    private fun events(examDay: Long) = listOf(
        event("outline_revised", json("ops" to JsonArray(listOf(JsonObject(mapOf(
            "op" to JsonPrimitive("add"), "kpId" to JsonPrimitive("K1"), "title" to JsonPrimitive("甲"))))))),
        event("exam_set", json("examDay" to examDay)),
    )

    // 账本最后一条事件在考前窗口之外，今天静默跨入窗口：投影里的覆盖层仍是窗口外的原始日程。
    @Test fun chatTodayMatchesQueueAfterSilentlyEnteringExamWindow() {
        val ev = events(examDay = today + 5)
        val p = project(ev).copy(review = mapOf("K1" to Review(dueDay = today + 4, intervalDays = 20)))
        val queue = CourseView(ev, p).dueReviews(now).map { it.kp.id }
        assertEquals(listOf("K1"), queue)
        assertEquals(queue, p.today(now).dueKpIds, p.today(now).answer(ProgressIntent.REVIEW_TODAY))
    }

    // 今天已复习：队列已清空，聊天仍报到期。
    @Test fun chatTodaySkipsKnowledgePointsAlreadyReviewedToday() {
        val ev = events(examDay = today + 1)
        val p = project(ev).copy(review = mapOf("K1" to Review(dueDay = today, intervalDays = 1, lastReviewDay = today)))
        assertTrue(CourseView(ev, p).dueReviews(now).isEmpty())
        assertEquals(emptyList(), p.today(now).dueKpIds, p.today(now).answer(ProgressIntent.REVIEW_TODAY))
    }

    // 已删除的知识点不该出现在聊天答案里。
    @Test fun chatTodayIgnoresRemovedKnowledgePoints() {
        val ev = events(examDay = today + 30)
        val p = project(ev).copy(review = mapOf("removed" to Review(dueDay = today - 1)))
        assertTrue(CourseView(ev, p).dueReviews(now).isEmpty())
        assertEquals(emptyList(), p.today(now).dueKpIds, p.today(now).answer(ProgressIntent.REVIEW_TODAY))
    }

    // 上次复习第 90 天、间隔 30、第 120 天考试：进入考前 7 天窗口当天不应被标成「逾期」。
    @Test fun enteringExamWindowDoesNotMarkItemsOverdue() {
        val r = Review(dueDay = 120, intervalDays = 30, lastReviewDay = 90).onDay(113, 120)
        assertTrue(r.dueDay >= 113, "进窗当天 dueDay=${r.dueDay}，复习页会显示逾期 ${113 - r.dueDay} 天")
    }
}
