package com.coach.domain

import kotlin.test.*

/** Missing §12 cases; existing DST, restore, void and late-review cases stay in their suites. */
class ReplayGoldenRulesTest {
    private fun events(origin: String = "COACH"): MutableList<Event> {
        val result = mutableListOf<Event>()
        fun add(type: String, payload: kotlinx.serialization.json.JsonObject) {
            val seq = result.size + 1L
            result += Event(seq,"golden-"+seq,"course",type,recordedAt=1793188800000L,
                occurredAt=1793188800000L,deviceId="phone",actor="SYSTEM",idemKey="golden-"+seq,payload=payload)
        }
        repeat(if(origin=="LEGACY") 108 else 3) { i ->
            val id = "q"+i
            add("question_posed",json("attemptId" to id,"questionId" to id,"kpIds" to listOf("kp"),"origin" to origin,
                "fingerprint" to id,"format" to "SINGLE_CHOICE","purpose" to "PRACTICE","keyState" to "UNREVIEWED"))
            add("answer_submitted",json("attemptId" to id,"answer" to "A"))
            add("attempt_graded",json("attemptId" to id,"score" to 1,"max" to 1,"status" to "EXACT"))
        }
        repeat(if(origin=="LEGACY") 108 else 3) { i ->
            add("question_key_changed",json("questionId" to "q"+i,"keyState" to "VERIFIED"))
        }
        return result
    }

    @Test fun reviewingAll108LegacyKeysNeverCreatesAnyMasteryWindow() {
        val state = project(events("LEGACY"))
        assertTrue(state.mastery.values.all { it.window.isEmpty() && it.support.isEmpty() && it.solidLatch.isEmpty() && !it.passed && !it.solid })
        // Identical newly collected evidence does count after its keys are reviewed.
        assertTrue(project(events()).mastery.getValue("kp").passed)
    }

    @Test fun examCompressionUsesTheCapOnlyInTheFinalSevenDays() {
        // DESIGN §1/§2.4 and TON-1291: compression applies only on days 7..0.
        // Keep the old 8..12-day cases, asserting the normal schedule outside that window.
        for(days in -1..12) {
            val e = events()
            var at = e.last().occurredAt
            fun add(type: String, p: kotlinx.serialization.json.JsonObject) {
                val seq=e.size+1L
                e += Event(seq,"exam-"+seq,"course",type,recordedAt=at,occurredAt=at,
                    deviceId="phone",actor="SYSTEM",idemKey="exam-"+seq,payload=p)
            }
            repeat(8) {
                at+=86400000L
                add("review_completed",json("kpId" to "kp","result" to "PASS"))
            }
            val day = logicalDay(at,"Europe/London")
            val normal = project(e).review.getValue("kp")
            add("exam_set",json("examDay" to day+days))
            val review = project(e).review.getValue("kp")
            if (days in 0..7) {
                val expected = ((days - 1) / 2).coerceAtLeast(1)
                assertTrue(normal.intervalDays > expected)
                assertEquals(expected,review.intervalDays,"days="+days)
                assertTrue(review.dueDay <= day+expected)
            } else {
                assertEquals(normal,review,"normal schedule must remain intact at days="+days)
            }
        }
    }
}
