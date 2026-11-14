package com.coach.client

import com.coach.domain.*
import com.coach.domain.reviewqueue.QueueReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 独立复核（TON-1443 Expert，2026-11-13）交回的两项边界反例，落成本仓持久回归：
 * 复习入口不能把没有显式关联的到期日程猜成某道错题，也不能在账本要求服务端快照时崩掉。
 * 合成账本，不涉及任何正式内容或设备事实。
 */
class DependencyBoundaryCounterexampleTest {
    private val now = 1_794_052_800_000L
    private val day = logicalDay(now, "Europe/London")
    private var seq = 0L
    private fun event(type: String, payload: JsonObject, at: Long = now): Event =
        Event(++seq, "probe-$seq", "SYNTHETIC-C", type, recordedAt = at, occurredAt = at,
            deviceId = "dependency-probe", actor = "SYSTEM", payload = payload)

    @Test
    fun dueWithoutExplicitQuestionLinkMustRetainUnknownIdentity() {
        val yesterday = now - 86_400_000L
        val events = listOf(
            event("outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic K"))))),
            event("question_posed", json("attemptId" to "a", "questionId" to "q", "groupId" to "g",
                "kpIds" to JsonArray(listOf(JsonPrimitive("K"))), "format" to "SINGLE_CHOICE",
                "mainline" to true, "posedAt" to yesterday, "keyState" to "VERIFIED"), yesterday),
            event("answer_submitted", json("attemptId" to "a", "answer" to "B"), yesterday),
            event("attempt_graded", json("attemptId" to "a", "score" to 0, "max" to 1, "status" to "VERIFIED"), yesterday),
        )
        val view = CourseView(events, project(events))
        val board = reviewBoard("SYNTHETIC-C", view, day)
        assertTrue(board.items.any { it.original == null && QueueReason.DUE in it.reasons },
            "No allocator/migration supplied a due-to-question identity; same KP/day must not manufacture it: ${board.items.map { it.original to it.reasons }}")
        // 不猜不等于整队关掉：真正有原题的那条错题仍在，且只带自己的来源。
        val mistake = board.items.single { it.original?.id == "q" }
        assertEquals(listOf(QueueReason.WRONG), mistake.reasons)
        assertFalse(mistake.hasUnknownOriginal)
        assertEquals(2, board.items.size, "到期行保持未知身份，错题独立成行：${board.items.map { it.original to it.reasons }}")
    }

    @Test
    fun authoritativeSnapshotRequirementMustNotCrashReviewEntry() {
        val events = listOf(event("future_synthetic_fact_v99", json()))
        val projection = project(events)
        assertTrue(projection.requiresServerSnapshot)
        // An explicit unavailable/unconfirmed result would be acceptable; throwing from composition is not.
        val board = reviewBoard("SYNTHETIC-C", CourseView(events, projection), day)
        // 不能用空列表冒充修复：必须给出明确的「待快照/不可判定」状态，也不许顺手报下一次到期。
        assertTrue(board.snapshotRequired, "需要权威快照时入口要明确说不可判定")
        assertTrue(board.items.isEmpty())
        assertEquals("", board.headline, "不能显示成空队列的完成态")
        assertEquals(0, board.unverifiedAnswered)
        assertTrue(board.nextDueDay == null, "未确认的投影不能拿来承诺下一次复习日期")
    }

    @Test
    fun ordinaryLedgerIsNotReportedAsSnapshotRequired() {
        val events = listOf(
            event("outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic K"))))),
            event("legacy_passed", json("kpId" to "K")),
            event("legacy_schedule", json("kpId" to "K", "dueDay" to day, "interval" to 15)),
        )
        val view = CourseView(events, project(events))
        val board = reviewBoard("SYNTHETIC-C", view, day)
        assertFalse(board.snapshotRequired, "正常账本不能被一律说成待快照")
        assertEquals("1 节到期", board.headline)
        assertEquals(1, board.items.size)
    }
}
