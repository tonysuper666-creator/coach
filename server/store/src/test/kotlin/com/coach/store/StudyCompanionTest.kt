package com.coach.store

import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class StudyCompanionTest {
    private fun wire(type: String, p: kotlinx.serialization.json.JsonObject, course: String="course"): WireCommand {
        val text = LedgerJson.encodeToString(CommandBody(type,course,Instant.parse("2026-10-25T05:00:00Z").toEpochMilli(),p))
        return WireCommand("desktop",java.util.UUID.randomUUID().toString(),text,sha256(text.toByteArray()))
    }
    @Test fun quickThreadIsPerCourseDayKeepsItsOriginalKpAndFilesWithoutEvidence() {
        val dir = Files.createTempDirectory("coach-companion-")
        var now = Instant.parse("2026-10-25T03:59:59Z").toEpochMilli()
        try {
            var db = LedgerStore(dir) { now }
            val configured = db.command(wire("configure_course",json("timeZone" to "Europe/London")))
            assertEquals("OK",configured.status)
            db.append("course","kp_opened",json("kpId" to "kp-1"))
            val first = db.openQuickAsk("course")
            assertEquals(first,db.openQuickAsk("course"))
            db.append("course","kp_opened",json("kpId" to "kp-2"))
            assertEquals("kp-1",db.openQuickAsk("course").kpId)
            val message = db.quickQuestion("course",first.threadId,"Synthetic question and answer")
            val before = db.snapshot("course").projection
            assertEquals("QUICK_READ_ONLY",db.command(wire("ask",json("threadId" to first.threadId,"pendingAttemptId" to "pending"))).error)
            val note = wire("file_quick_ask",json("threadId" to first.threadId,"messageId" to message,"action" to "NOTE"))
            assertEquals("OK",db.command(note).status)
            assertEquals("DUPLICATE",db.command(note).status)
            val moved = db.command(wire("file_quick_ask",json("threadId" to first.threadId,"messageId" to message,"action" to "MOVE")))
            assertEquals("OK",moved.status)
            assertEquals("kp-1",moved.result.str("kpId"))
            assertEquals("Synthetic question and answer",db.recentTurns(moved.result.str("targetThreadId")).single().second)
            assertEquals(before.mastery,db.snapshot("course").projection.mastery)
            assertEquals(before.position,db.snapshot("course").projection.position)
            assertEquals(1,db.events("course").count { it.type == "note_saved" })
            assertEquals("REJECTED",db.command(wire("file_quick_ask",json("threadId" to first.threadId,"messageId" to message,"action" to "NOTE"),course="other")).status)
            assertNotEquals(first.threadId,db.openQuickAsk("other").threadId)
            db.close(); db = LedgerStore(dir) { now }
            assertEquals(first,db.openQuickAsk("course"))
            now += 1000
            assertEquals(first.day+1,db.openQuickAsk("course").day)
            assertNotEquals(first.threadId,db.openQuickAsk("course").threadId)
            db.close()
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun configuredCourseTimezoneControlsQuickAskAndJournalDay() {
        val dir = Files.createTempDirectory("coach-zone-")
        var now = Instant.parse("2026-10-30T19:59:59Z").toEpochMilli()
        try { LedgerStore(dir) { now }.use { db ->
            assertEquals("OK",db.command(wire("configure_course",json("timeZone" to "Asia/Shanghai"))).status)
            val before = db.openQuickAsk("course")
            assertEquals(java.time.LocalDate.of(2026,10,30).toEpochDay(),before.day)
            db.append("course","note_saved",json("content" to "Before four"))
            now += 1000
            val after = db.openQuickAsk("course")
            assertEquals(java.time.LocalDate.of(2026,10,31).toEpochDay(),after.day)
            db.append("course","note_saved",json("content" to "After four"))
            assertEquals(1,db.journalDay("course",before.day).notes)
            assertEquals(1,db.journalDay("course",after.day).notes)
            db.importLegacyJournal("course",before.day,"source-entry","Old log")
            db.importLegacyJournal("course",before.day,"source-entry","Old log")
            assertEquals(listOf(LegacyJournalEntry("source-entry","Old log")),db.journalDay("course",before.day).legacyLogs)
        } } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun weightsAndOptimisticJournalEditsHaveReviewableResults() {
        val dir = Files.createTempDirectory("coach-journal-")
        try { LedgerStore(dir).use { db ->
            assertEquals("OK",db.command(wire("set_exam_weights",json("examWeights" to json("kp" to 25,"rest" to 75)))).status)
            db.append("course","kp_overridden",json("kpId" to "kp","kind" to "MARK_PASSED"))
            assertEquals(ExamCoverage(25,0,100),db.todayProgress("course").examCoverage)
            assertEquals("REJECTED",db.command(wire("set_exam_weights",json("examWeights" to json("kp" to -1)))).status)
            assertEquals("REJECTED",db.command(wire("configure_course",json("timeZone" to "invalid"))).status)
            val edit = wire("edit_journal",json("day" to 12,"expectedRev" to 0,"content" to "First edit"))
            assertEquals("OK",db.command(edit).status)
            assertEquals("DUPLICATE",db.command(edit).status)
            val conflict = db.command(wire("edit_journal",json("day" to 12,"expectedRev" to 0,"content" to "Other device")))
            assertEquals("CONFLICT",conflict.status)
            assertEquals(1L,conflict.result.num("currentRev"))
            assertEquals("First edit",db.journalDay("course",12).manual)
            assertEquals(1L,db.journalDay("course",12).rev)
        } } finally { dir.toFile().deleteRecursively() }
    }
}
