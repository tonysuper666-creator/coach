package com.coach.domain

import kotlinx.serialization.json.*
import kotlin.test.*

/** Independent Builder-C counterexamples for PR190, retained as synthetic regressions. */
class XvRetentionAnchorTest {
    private val start = 1793188800000L
    private val events = mutableListOf<Event>()
    private fun e(type: String, p: JsonObject, actor: String = "SYSTEM") {
        val seq = events.size.toLong() + 1
        events.add(Event(seq, "e$seq", "course", type, recordedAt = start + 1000, occurredAt = start,
            deviceId = "phone", actor = actor, idemKey = "i$seq", payload = p))
    }
    private fun missMainline(id: String) {
        e("question_posed", json("attemptId" to id, "questionId" to id, "kpIds" to listOf("kp"), "fingerprint" to id,
            "keyState" to "VERIFIED", "origin" to "COACH", "purpose" to "PRACTICE", "format" to "SINGLE_CHOICE",
            "policyVersion" to 1, "review" to false, "mainline" to true))
        e("answer_submitted", json("attemptId" to id, "answer" to "synthetic", "offline" to false, "guessed" to false), "LEARNER")
        e("attempt_graded", json("attemptId" to id, "score" to 0, "max" to 10, "status" to "EXACT"))
    }
    private fun base(d0: Long) {
        e("outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "kp", "title" to "Synthetic")))))
        e("legacy_passed", json("kpId" to "kp", "sourceRef" to "synthetic"))
        e("legacy_schedule", json("kpId" to "kp", "dueDay" to d0 + 20, "interval" to 20))
    }

    /** A: checked three-star point, verified mainline miss on d0 → look again d0+1, not "overdue". */
    @Test fun threeStarLookAgainIsNotTurnedIntoInventedOverdue() {
        val d0 = logicalDay(start, "Europe/London")
        base(d0)
        e("course_configured", json("timeZone" to "Europe/London", "reviewPriorities" to
            json("kp" to json("stars" to 3, "reviewed" to true))))
        missMainline("miss-1")
        val r = project(events).review.getValue("kp")
        println("XV_A d0=$d0 due=${r.dueDay} (d0${r.dueDay - d0}) interval=${r.intervalDays} target=${r.targetRetention} dueTodayAfterMiss=${"kp" in project(events).reviewsOn(d0)}")
        assertEquals(d0 + 1, r.dueDay, "look-again must stay next day; got d0${r.dueDay - d0}")
    }

    /** B: no priority config at all (legacy store); snoozed to d0+10, then verified mainline miss on d0. */
    @Test fun unconfiguredSnoozedMissKeepsLegacyLookAgain() {
        val d0 = logicalDay(start, "Europe/London")
        base(d0)
        e("review_snoozed", json("kpId" to "kp", "untilDay" to d0 + 10))
        missMainline("miss-2")
        val r = project(events).review.getValue("kp")
        println("XV_B d0=$d0 due=${r.dueDay} (d0+${r.dueDay - d0}) snoozedUntil=${r.snoozedUntilDay?.minus(d0)}")
        assertEquals(d0 + 1, r.dueDay, "legacy (253d66c8) look-again day for unconfigured data")
    }
}
