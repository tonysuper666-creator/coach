package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class ExamPanelTest {
    private val now = 1_800_000_000_000L
    private var seq = 0L
    private fun e(type: String, p: JsonObject) = Event(++seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq, deviceId = "a", actor = "SYSTEM", payload = p)

    // 合成课程：三周，每周两个点（W3 只有一个）。
    private val outline = e("outline_revised", json("knowledgePoints" to JsonArray(listOf(
        json("id" to "a1", "title" to "【W1·Ch1】A one"), json("id" to "a2", "title" to "【W1·Ch1】A two"),
        json("id" to "b1", "title" to "【W2·Ch2】B one"), json("id" to "b2", "title" to "【W2·Ch2】B two"),
        json("id" to "c1", "title" to "【W3·Ch3】C one"),
    ))))
    private val focus = ExamFocus(listOf(WeekFocus(1, 1.0, "Topic A"), WeekFocus(2, 3.0, "Topic B"), WeekFocus(3, 2.0, "Topic C")))

    private fun view(vararg extra: Event, mastery: Map<String, Mastery> = emptyMap()): CourseView {
        val events = listOf(outline) + extra
        val p = project(events)
        return CourseView(events, p.copy(mastery = p.mastery + mastery))
    }

    @Test fun countdownSaysUnsetUntilHeFillsTheDateAndCountsDownAfter() {
        assertEquals(ExamCountdown.Unset, view().examPanel(now, focus).countdown)
        val today = logicalDay(now, "Europe/London")
        assertEquals(ExamCountdown.Days(12, today + 12), view(e("exam_set", json("examDay" to today + 12))).examPanel(now, focus).countdown)
        assertEquals(ExamCountdown.Days(0, today), view(e("exam_set", json("examDay" to today))).examPanel(now, focus).countdown)
        assertEquals(ExamCountdown.Past(today - 1), view(e("exam_set", json("examDay" to today - 1))).examPanel(now, focus).countdown)
    }

    @Test fun weekMasteryIsTheLedgerStatusOfThatWeeksPoints() {
        val v = view(mastery = mapOf("a1" to Mastery(passed = true, solid = true), "a2" to Mastery(passed = true), "b1" to Mastery(passed = true, needsRepair = true)))
        val rows = v.examPanel(now, focus).weeks.associateBy { it.week }
        assertEquals(listOf(1, 2, 3), rows.keys.toList())
        for (row in rows.values) {
            val kps = v.weekKps(row.week)
            assertEquals(kps.size, row.total)
            assertEquals(kps.count { it.status == KpStatus.PASSED || it.status == KpStatus.SOLID }, row.passed)
            assertEquals(kps.count { it.status == KpStatus.SOLID }, row.solid)
        }
        assertEquals(0.75, rows.getValue(1).score)
        assertEquals(1, rows.getValue(2).needsRepair)
        assertEquals(0, rows.getValue(2).passed, "a point that needs repair is not counted as mastered")
    }

    @Test fun topThreeFollowWeightTimesUnmasteredAndOpenTheRightThing() {
        val v = view(mastery = mapOf(
            "a1" to Mastery(passed = true), "a2" to Mastery(passed = true),       // W1 全过未稳：0.5 × 1
            "b1" to Mastery(passed = true, solid = true), "b2" to Mastery(passed = true, needsRepair = true), // W2：0.5 × 3
        ))
        val panel = v.examPanel(now, focus)
        assertTrue(panel.weighted)
        assertEquals(listOf(3, 2, 1), panel.focus.map { it.week }, "W3 untouched ×2 beats W2 half-done ×3, W1 last")
        assertEquals(ExamAction.Teach("c1", "【W3·Ch3】C one", repair = false), panel.focus[0].action)
        assertEquals(ExamAction.Teach("b2", "【W2·Ch2】B two", repair = true), panel.focus[1].action, "repair comes before new points")
        assertEquals(ExamAction.Practice(1, "a1", 0), panel.focus[2].action, "a week with everything passed goes to practice")
    }

    @Test fun solidWeeksAndUnweightedWeeksNeverTakeASlot() {
        val solid = Mastery(passed = true, solid = true)
        val v = view(mastery = mapOf("a1" to solid, "a2" to solid))
        val panel = v.examPanel(now, ExamFocus(listOf(WeekFocus(1, 3.0), WeekFocus(2, 0.0))))
        assertEquals(emptyList(), panel.focus.map { it.week }, "W1 is solid, W2 has weight 0, W3 is not in the exam focus")
        val unweighted = v.examPanel(now, null)
        assertFalse(unweighted.weighted)
        assertEquals(listOf(2, 3), unweighted.focus.map { it.week })
    }

    @Test fun examWeeksTheOutlineHasNotReachedAreListedButNeverPassedOffAsMastered() {
        val panel = view().examPanel(now, ExamFocus(focus.weeks + WeekFocus(4, 3.0, "Topic D")))
        val w4 = panel.weeks.single { it.week == 4 }
        assertEquals(listOf(1, 2, 3, 4), panel.weeks.map { it.week })
        assertEquals(0, w4.total)
        assertEquals(0.0, w4.score)
        assertNull(w4.action)
        assertFalse(4 in panel.focus.map { it.week }, "a week with nothing to open cannot be one of the three blocks")
    }

    @Test fun malformedPrivateConfigIsIgnoredRatherThanTrusted() {
        val parsed = ExamFocus.parse(json("weeks" to JsonArray(listOf(
            json("week" to 4, "weight" to 3, "topic" to "T"), json("week" to 0, "weight" to 9),
            json("week" to 5, "weight" to -1), json("week" to "x", "weight" to 2), json("week" to 4, "weight" to 1),
        ))))
        assertEquals(listOf(WeekFocus(4, 3.0, "T")), parsed?.weeks)
        assertNull(ExamFocus.parse(json("weeks" to JsonArray(emptyList()))))
        assertNull(ExamFocus.parse(null))
    }

    @Test fun examDateReadsAsACalendarDate() {
        assertEquals("1970年1月1日", examDateLabel(0))
        assertEquals("2024年2月29日", examDateLabel(19_782))
        assertEquals("2027年1月11日", examDateLabel(20_829))
    }
}
