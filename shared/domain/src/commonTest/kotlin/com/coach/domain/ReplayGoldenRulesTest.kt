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

    @Test fun examCompressionUsesFloorOfDaysMinusOneAndAtLeastOne() {
        for((offset, expected) in listOf(1,1,1,1,2,2,3,3,4,4,5,5).withIndex()) {
            val days=offset+1
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
            assertTrue(project(e).review.getValue("kp").intervalDays > expected)
            add("exam_set",json("examDay" to day+days))
            val review = project(e).review.getValue("kp")
            assertEquals(expected,review.intervalDays,"days="+days)
            assertTrue(review.dueDay <= day+expected)
        }
    }
}
