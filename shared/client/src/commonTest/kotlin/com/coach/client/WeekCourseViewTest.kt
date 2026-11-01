package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

class WeekCourseViewTest {
    private fun e(seq: Long, type: String, p: kotlinx.serialization.json.JsonObject) = Event(seq, "e$seq", "C", type, recordedAt=seq, occurredAt=seq, deviceId="a", actor="SYSTEM", payload=p)
    @Test fun realCalendarLabelsIncludeSecondaryWeeksWithoutInventingMissingWeeks() {
        assertEquals(setOf(1, 4), titleWeeks("【W4·Ch8】First ｜ 【W1·slides】Second"))
        assertTrue(titleWeeks("Year W4, chapter 4").isEmpty())
        val events = listOf(e(1, "outline_revised", json("knowledgePoints" to JsonArray(listOf(
            json("id" to "B", "title" to "Explicit week", "week" to 2),
            json("id" to "A", "title" to "【W2·Ch3】Next"),
            json("id" to "C", "title" to "【W3·Ch4】Other")
        )), "ops" to JsonArray(listOf(json("kpId" to "B"))))))
        val view = CourseView(events, project(events))
        assertEquals(listOf(2, 3), view.weeks)
        assertEquals(listOf("B", "A"), view.weekKps(2).map { it.id })
    }
    @Test fun passedOldThreadCannotTrapContinueAndScopeOverridesUnfinishedWorkElsewhere() {
        val events = listOf(
            e(1, "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "old", "title" to "【W1·Ch1】Old"), json("kpId" to "next", "title" to "【W2·Ch3】Next"))))),
            e(2, "kp_opened", json("kpId" to "old")),
            e(3, "kp_overridden", json("kpId" to "old", "kind" to "MARK_PASSED"))
        )
        val projection = project(events).copy(position=Position(current="old", continueTarget="old"))
        val view = CourseView(events, projection)
        assertEquals("next", view.continueTarget?.id)
        assertFalse(view.isResume(view.continueTarget!!))
        val scoped = CourseView(events, projection.copy(studyScope=StudyScope("scope", 2, listOf("next"))))
        assertEquals("next", scoped.continueTarget?.id)
        assertEquals("第 2 周 · 第 1 / 1 个", scoped.weekProgress("next"))
        val done = CourseView(events, projection.copy(studyScope=StudyScope("scope", 2, listOf("next"), 1)))
        assertNull(done.continueTarget)
    }
}
