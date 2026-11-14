package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

/** Builder-C's synthetic C/D probes, with the agreed C=+3 and corrected D=+1 contracts. */
class XvExamWindowLegacyTest {
    private val start = 1793188800000L
    private val events = mutableListOf<Event>()
    private fun e(type: String, p: JsonObject, actor: String = "SYSTEM") {
        val seq = events.size.toLong() + 1
        events.add(Event(seq, "e$seq", "course", type, recordedAt = start + 1000, occurredAt = start,
            deviceId = "phone", actor = actor, idemKey = "i$seq", payload = p))
    }
    private fun miss(id: String) {
        e("question_posed", json("attemptId" to id, "questionId" to id, "kpIds" to listOf("kp"), "fingerprint" to id,
            "keyState" to "VERIFIED", "origin" to "COACH", "purpose" to "PRACTICE", "format" to "SINGLE_CHOICE",
            "policyVersion" to 1, "review" to false, "mainline" to true))
        e("answer_submitted", json("attemptId" to id, "answer" to "synthetic", "offline" to false, "guessed" to false), "LEARNER")
        e("attempt_graded", json("attemptId" to id, "score" to 0, "max" to 10, "status" to "EXACT"))
    }
    private fun run(snooze: Boolean): Long {
        val d0 = logicalDay(start, "Europe/London")
        e("outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "kp", "title" to "Synthetic")))))
        e("legacy_passed", json("kpId" to "kp", "sourceRef" to "synthetic"))
        e("legacy_schedule", json("kpId" to "kp", "dueDay" to d0 + 20, "interval" to 20))
        e("exam_set", json("examDay" to d0 + 5))
        if (snooze) e("review_snoozed", json("kpId" to "kp", "untilDay" to d0 + 3))
        miss("m")
        return project(events).review.getValue("kp").dueDay - d0
    }
    @Test fun c_snoozedThenMissInExamWindow() {
        val r = run(true)
        println("XV_C due=d0+$r")
        assertEquals(3L, r, "253d66c8 keeps snooze in exam window")
    }
    @Test fun d_missInExamWindowNoSnooze() {
        val r = run(false)
        println("XV_D due=d0+$r")
        assertEquals(1L, r, "A new look-again must remain next day without invented overdue")
    }
}
