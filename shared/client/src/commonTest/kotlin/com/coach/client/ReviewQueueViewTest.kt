package com.coach.client

import com.coach.domain.Event
import com.coach.domain.json
import com.coach.domain.logicalDay
import com.coach.domain.project
import com.coach.domain.reviewqueue.QueueReadiness
import com.coach.domain.reviewqueue.QueueReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** 合成账本：只验证真实复习入口的状态源，不碰内容、服务器或设备。 */
class ReviewQueueViewTest {
    private val now = 1_794_052_800_000L
    private val zone = "Europe/London"
    private val day = logicalDay(now, zone)
    private val yesterday = now - 86_400_000L

    private var seq = 0L
    private fun event(type: String, payload: JsonObject, at: Long = now): Event =
        Event(++seq, "e$seq", "C", type, recordedAt = at, occurredAt = at, deviceId = "synthetic", actor = "SYSTEM", payload = payload)

    private fun outline(vararg ops: JsonObject) = event("outline_revised", json("ops" to JsonArray(ops.toList())))
    private fun kp(id: String, title: String, vararg prereqs: String) =
        json("kpId" to id, "title" to title, "prereqs" to JsonArray(prereqs.map { kotlinx.serialization.json.JsonPrimitive(it) }))

    private fun posed(attemptId: String, questionId: String, kpId: String, keyState: String?, at: Long = yesterday) = event(
        "question_posed",
        json("attemptId" to attemptId, "questionId" to questionId, "groupId" to "g-$questionId",
            "kpIds" to JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(kpId))),
            "format" to "SINGLE_CHOICE", "mainline" to true, "posedAt" to at,
            "keyState" to keyState), at)

    private fun answered(attemptId: String, answer: String, guessed: Boolean = false, at: Long = yesterday) = event(
        "answer_submitted", json("attemptId" to attemptId, "answer" to answer, "guessed" to guessed), at)

    private fun graded(attemptId: String, score: Int, max: Int = 1, at: Long = yesterday) = event(
        "attempt_graded", json("attemptId" to attemptId, "score" to score, "max" to max, "status" to "VERIFIED"), at)

    private fun board(events: List<Event>) = reviewBoard("C", CourseView(events, project(events)), day)

    @Test fun wrongAndGuessedOnOneQuestionFormOneEntryNextToTheDueSchedule() {
        val events = listOf(
            outline(kp("K0", "前置：极限"), kp("K1", "合成：导数应用", "K0")),
            event("legacy_passed", json("kpId" to "K0")),
            event("legacy_schedule", json("kpId" to "K0", "dueDay" to day, "interval" to 15)),
            event("exam_weights_set", json("scope" to "course", "weights" to json("K0" to 1, "K1" to 3))),
            // 同一道题：先猜对，再答错。两个来源必须合成一条，不能变成两行。
            posed("a1", "q1", "K1", "VERIFIED"), answered("a1", "B", guessed = true), graded("a1", 1),
            posed("a2", "q1", "K1", "VERIFIED"), answered("a2", "C"), graded("a2", 0),
        )
        val b = board(events)
        assertEquals(day, b.day)
        assertEquals(2, b.items.size, "到期日程 + 同一道错题/猜对题目 = 两项，不是三项")
        assertFalse(b.dueOnly)
        assertEquals("2 项复习", b.headline)

        val mistake = b.items[0]
        assertEquals("K1", mistake.kp.id, "考纲权重高的排在同一天的前面")
        // 这道题既答错过也猜对过，重练日又是同一天：三个来源合并成一行，原题身份只有一个。
        assertEquals(setOf(QueueReason.DUE, QueueReason.WRONG, QueueReason.GUESSED), mistake.reasons.toSet())
        assertEquals("q1", mistake.original?.id)
        assertFalse(mistake.hasUnknownOriginal)
        assertTrue(mistake.revisit, "到时重练的那一行仍然标成「再看一次」")
        assertEquals(QueueReadiness.NEEDS_VARIANT, mistake.readiness)
        assertEquals(0, mistake.variantCount)
        assertTrue(mistake.identityLabel.contains("待生成变式"), mistake.identityLabel)
        assertEquals(listOf("前置：极限"), mistake.prereqTitles, "先修来自大纲里真实写下的 prereqs")
        assertEquals(3, mistake.examWeight, "权重来自账本里的 exam_weights_set")
        assertEquals(0, mistake.overdueDays)

        val due = b.items[1]
        assertEquals("K0", due.kp.id)
        assertTrue(due.hasUnknownOriginal, "到期日程没有原题时保持未知，不借用同知识点的错题")
        assertNotNull(due.unknownDueId)
        assertEquals(listOf(QueueReason.DUE), due.reasons)
        assertTrue(due.identityLabel.contains("原题未关联"), due.identityLabel)
        assertEquals(0, b.unverifiedAnswered)
        assertNull(b.nextDueDay)
    }

    @Test fun unverifiedAnswerIsCountedForDisplayButNeverEntersTheQueue() {
        val events = listOf(
            outline(kp("K2", "合成：未核题知识点")),
            posed("a9", "q9", "K2", keyState = null), answered("a9", "A"), graded("a9", 0),
        )
        val b = board(events)
        // 未核答案按 Projector 的口径只排一次「再看一次」，但不是错题：不挂原题、不算分。
        assertEquals(1, b.items.size)
        assertEquals(listOf(QueueReason.DUE), b.items[0].reasons)
        assertTrue(b.items[0].revisit)
        assertTrue(b.items[0].hasUnknownOriginal, "未核答案不能被说成某道错题")
        assertEquals(1, b.unverifiedAnswered)
        assertEquals("1 项复习", b.headline)
        assertNull(b.nextDueDay)
    }

    @Test fun emptyQueueStillSaysWhenTheNextReviewIs() {
        val events = listOf(
            outline(kp("K3", "合成：还没到期")),
            event("legacy_passed", json("kpId" to "K3")),
            event("legacy_schedule", json("kpId" to "K3", "dueDay" to day + 3, "interval" to 15)),
        )
        val b = board(events)
        assertEquals(emptyList(), b.items)
        assertEquals(day + 3, b.nextDueDay, "没到期时说清下一次是哪天")
        assertEquals(0, b.unverifiedAnswered)
    }

    @Test fun dueOnlyQueueKeepsTheOlderCountCopy() {
        val events = listOf(
            outline(kp("K4", "合成：到期一"), kp("K5", "合成：到期二")),
            event("legacy_passed", json("kpId" to "K4")),
            event("legacy_schedule", json("kpId" to "K4", "dueDay" to day, "interval" to 15)),
            event("legacy_passed", json("kpId" to "K5")),
            event("legacy_schedule", json("kpId" to "K5", "dueDay" to day - 2, "interval" to 15)),
        )
        val b = board(events)
        assertTrue(b.dueOnly)
        assertEquals("2 节到期", b.headline)
        assertEquals(2, b.items[0].overdueDays, "逾期天数仍按逻辑日算")
        assertEquals(listOf(QueueReason.DUE), b.items[0].reasons)
    }
}
