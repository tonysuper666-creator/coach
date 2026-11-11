package com.coach.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class PendingMainlineScoreTest {
    private val events = mutableListOf<Event>()
    private val now = 1_800_000_000_000L

    private fun event(type: String, payload: JsonObject) {
        val seq = events.size.toLong() + 1
        events += Event(seq, "e$seq", "C", type, recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "LEARNER", payload = payload)
    }

    private fun answer(id: String, key: String?, mainline: Boolean = true, purpose: String = "PRACTICE") {
        event("question_posed", JsonObject(json("attemptId" to id, "questionId" to id,
            "kpIds" to listOf("kp"), "fingerprint" to id, "format" to "SINGLE_CHOICE",
            "purpose" to purpose, "mainline" to mainline) + if (key == null) emptyMap() else json("keyState" to key)))
        event("answer_submitted", json("attemptId" to id, "answer" to "A"))
        event("attempt_graded", json("attemptId" to id, "status" to "EXACT", "score" to 100, "max" to 100))
    }

    @Test fun uncheckedAndMissingKeysNeverCountOrBecomeBackdatedMastery() {
        for (key in listOf("UNREVIEWED", "REJECTED", null)) {
            events.clear()
            repeat(3) { answer("a$it", key) }
            val before = project(events)
            assertEquals(0, before.journal.values.sumOf { it.correct }, "key=$key")
            assertFalse(before.mastery.getValue("kp").passed)
            assertEquals(logicalDay(now, "Europe/London") + 1, before.revisit["kp"])
            repeat(3) { event("question_key_changed", json("questionId" to "a$it", "keyState" to "VERIFIED")) }
            val after = project(events)
            assertEquals(0, after.journal.values.sumOf { it.correct }, "verification cannot backdate correctness")
            assertTrue(after.mastery.getValue("kp").window.isEmpty())
            assertFalse(after.mastery.getValue("kp").passed)
            assertEquals(after, project(events.reversed()))
        }
    }

    @Test fun verifiedMainlineAndOrdinaryTutoringAndMockGradesStillCount() {
        answer("verified", "VERIFIED")
        answer("tutor", "UNREVIEWED", mainline = false)
        answer("mock", "VERIFIED", mainline = false, purpose = "FULL_MOCK")
        assertEquals(3, project(events).journal.values.sumOf { it.correct })
    }

    @Test fun pendingAnswerKeepsNextDayReviewForAnAlreadyPassedPoint() {
        event("legacy_passed", json("kpId" to "kp"))
        val day = logicalDay(now, "Europe/London")
        event("legacy_schedule", json("kpId" to "kp", "dueDay" to day + 30, "interval" to 30))
        answer("pending", "UNREVIEWED")
        val state = project(events)
        assertEquals(day + 1, state.review.getValue("kp").dueDay)
        assertTrue(state.mastery.getValue("kp").passed)
        assertFalse(state.mastery.getValue("kp").needsRepair)
        assertEquals(0, state.journal.values.sumOf { it.correct })
    }
}
