package com.coach.client

import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** TON-1444: the 2026-12-15 final exam comes from the course content, every screen counts down by the local calendar day. */
class FinalExamDateTest {
    private var seq = 0L
    private fun e(type: String, p: JsonObject) = Event(++seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq, deviceId = "a", actor = "SYSTEM", payload = p)
    private fun outline(examDate: String? = "2026-12-15", tz: String = "Europe/London") = e("outline_revised", json(
        "course" to json(*listOfNotNull("tz" to tz, examDate?.let { "examDate" to it }).toTypedArray()),
        "knowledgePoints" to JsonArray(listOf(json("id" to "a1", "title" to "【W1·Ch1】A one")))))

    private fun view(vararg events: Event): CourseView {
        val all = events.toList()
        return CourseView(all, project(all))
    }

    /** UTC millis of a wall-clock time in a zone with no DST on the given dates (Nov/Dec in London = UTC; Shanghai = UTC+8). */
    private fun at(day: String, hour: Int, minute: Int = 0, offsetHours: Int = 0): Long =
        (parseIsoDay(day)!! * 86_400_000L) + (hour - offsetHours) * 3_600_000L + minute * 60_000L

    @Test fun isoDatesParseAndBadOnesAreNoDate() {
        assertEquals(20_766L, parseIsoDay("2026-11-09"))
        assertEquals(20_802L, parseIsoDay("2026-12-15"))
        assertEquals(0L, parseIsoDay("1970-01-01"))
        assertEquals(19_782L, parseIsoDay("2024-02-29"))
        for (bad in listOf("2026-02-29", "2026-13-01", "2026-12-32", "15/12/2026", "", null, "2026-1-5")) assertNull(parseIsoDay(bad), "$bad")
    }

    @Test fun theCourseContentDateIsTheExamDayEverywhere() {
        val v = view(outline())
        assertEquals(20_802L, v.examDay)
        assertEquals(20_802L, v.projection.examDay, "the review scheduler reads the same date")
        assertEquals(20_802L, courseExamDay(v.events, v.zone))
    }

    @Test fun countdownFollowsTheLocalCalendarDay() {
        val v = view(outline())
        assertEquals(36, v.daysToExam(at("2026-11-09", 9)))
        assertEquals(36, v.daysToExam(at("2026-11-09", 23, 59)))
        assertEquals(35, v.daysToExam(at("2026-11-10", 0, 1)))
        assertEquals(1, v.daysToExam(at("2026-12-14", 12)))
        assertEquals(0, v.daysToExam(at("2026-12-15", 8)))
        // Between 00:00 and 04:00 the review "logical day" is still yesterday; the countdown must not be.
        assertEquals(0, v.daysToExam(at("2026-12-15", 0, 30)))
        assertEquals(1, v.daysToExam(at("2026-12-14", 23, 59)))
        assertNull(v.daysToExam(at("2026-12-16", 9)))
        assertEquals("距期末 36 天", v.examCountdownText(at("2026-11-09", 9)))
        assertEquals("距期末 35 天", v.examCountdownText(at("2026-11-10", 9)))
        assertEquals("距期末 1 天", v.examCountdownText(at("2026-12-14", 9)))
        assertEquals("距期末 0 天", v.examCountdownText(at("2026-12-15", 9)))
        assertEquals("期末已结束", v.examCountdownText(at("2026-12-16", 9)))
    }

    @Test fun theCourseTimeZoneDecidesWhenTheDayTurnsNotTheHourDifferenceFromUtc() {
        val v = view(outline(tz = "Asia/Shanghai"))
        // 2026-12-14 16:30 UTC is already 2026-12-15 00:30 in Shanghai.
        assertEquals(0, v.daysToExam(at("2026-12-14", 16, 30)))
        assertEquals(1, v.daysToExam(at("2026-12-14", 15, 30)))
        // 36.4 days away in raw hours, still "36 days" until local midnight.
        assertEquals(36, v.daysToExam(at("2026-11-09", 1, 0, offsetHours = 8)))
    }

    @Test fun noDateIsShownAsNoDateNotAsAPlaceholderCountdown() {
        val v = view(outline(examDate = null))
        assertNull(v.examDay)
        assertNull(v.daysToExam(at("2026-11-09", 9)))
        assertEquals("未设期末日期", v.examCountdownText(at("2026-11-09", 9)))
    }

    @Test fun theLaterLedgerWriteWinsBetweenManualAndConfiguredDate() {
        val manualEarly = e("exam_set", json("examDay" to 20_600L)) // an old midterm date entered by hand
        val configured = outline()                                   // reseeded afterwards
        assertEquals(20_802L, view(manualEarly, configured).examDay)
        val manualLate = e("exam_set", json("examDay" to 20_810L))   // he corrects it afterwards
        assertEquals(20_810L, view(manualEarly, configured, manualLate).examDay)
    }

    @Test fun examPanelCountdownUsesTheSameCalendarDay() {
        val v = view(outline())
        assertEquals(ExamCountdown.Days(0, 20_802L), v.examPanel(at("2026-12-15", 0, 30), null).countdown)
        assertEquals(ExamCountdown.Days(36, 20_802L), v.examPanel(at("2026-11-09", 9), null).countdown)
        assertEquals(ExamCountdown.Past(20_802L), v.examPanel(at("2026-12-16", 9), null).countdown)
    }

    @Test fun weightsAreOnlyCalledFinalWhenTheyAreFinal() {
        val weights = e("exam_weights_set", json("weights" to json("a1" to 100)))
        assertEquals("", project(listOf(outline(), weights)).examCoverage.scope)
        val final = e("exam_weights_set", json("weights" to json("a1" to 100), "scope" to "FINAL"))
        assertEquals("FINAL", project(listOf(outline(), weights, final)).examCoverage.scope)
        val text = project(listOf(outline(), weights)).today(at("2026-11-09", 9)).coverageText
        assertTrue("期末分值" in text && "还没有期末的分值数据" in text && "期中" !in text, text)
    }
}
