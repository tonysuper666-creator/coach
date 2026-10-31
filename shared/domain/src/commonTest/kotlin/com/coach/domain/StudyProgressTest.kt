package com.coach.domain

import kotlin.test.*

class StudyProgressTest {
    private val events = mutableListOf<Event>()
    private var at = 1793188800000L
    private fun fact(type: String, payload: kotlinx.serialization.json.JsonObject, thread: String? = null) {
        val seq = events.size + 1L
        events += Event(seq,"e$seq","course",type,recordedAt=at,occurredAt=at,deviceId="test",actor="SYSTEM",payload=payload,threadId=thread)
    }
    private fun answer(id: String, kp: String, thread: String? = null) {
        fact("question_posed",json("attemptId" to id,"questionId" to id,"fingerprint" to id,"kpIds" to listOf(kp),
            "format" to "SHORT_ANSWER","purpose" to "CLOSED_BOOK","keyState" to "VERIFIED","origin" to "COACH"),thread)
        fact("answer_submitted",json("attemptId" to id),thread)
        fact("attempt_graded",json("attemptId" to id,"score" to 10,"max" to 10,"status" to "EXACT"),thread)
    }

    @Test fun coverageUsesExamPointsAtThreeLedgerCheckpointsAndHonorsRevocation() {
        fact("exam_weights_set",json("weights" to json("valuable" to 30,"other" to 70,"prereq" to 0)))
        fact("kp_overridden",json("kpId" to "prereq","kind" to "MARK_PASSED"))
        assertEquals(ExamCoverage(0,0,100),project(events).examCoverage)
        answer("a","valuable"); at += 86400000; answer("b","valuable")
        val solid = project(events)
        assertEquals(ExamCoverage(30,30,100),solid.examCoverage)
        assertEquals("按期中分值：已通过覆盖 30 / 100 分，稳固 30 分",solid.today(at).coverageText)
        fact("attempt_voided",json("attemptId" to "b"))
        assertEquals(ExamCoverage(0,0,100),project(events).examCoverage)
    }

    @Test fun deterministicAnswersShareTodayNumbersAndDoNotGuess() {
        val state = Projection(kpTitles=mapOf("kp" to "Synthetic objective","overdue" to "Earlier objective"),lastSeq=42,coverage=ExamCoverage(43,30,100),position=Position(current="kp"),
            mastery=mapOf("kp" to Mastery(passed=true)),review=mapOf("overdue" to Review(1),"later" to Review(999999)))
        val today = state.today(at)
        assertEquals(today.coverageText,today.answer(assertNotNull(progressIntent("请问，我学了多少？"))))
        assertTrue(today.answer(assertNotNull(progressIntent("还差多少"))).contains("57 分覆盖"))
        assertEquals(listOf("overdue"),today.dueKpIds)
        assertEquals("今天到期复习：Earlier objective。",today.answer(ProgressIntent.REVIEW_TODAY))
        assertEquals("Synthetic objective：已通过，尚未稳固。",today.answer(ProgressIntent.CURRENT_KP))
        for (s in listOf("学了多少就忘了多少怎么办", "今天复习什么是逆向选择", "这个点过了没关系吗", "还差多少样本才能估计", "继续", "什么是覆盖率")) assertNull(progressIntent(s),s)
        for (s in listOf("学了多少","还差多少？","今天复习什么","这个点过了没")) assertNotNull(progressIntent(s),s)
        assertTrue(Projection().today(at).coverageText.contains("尚未设置"))
    }

    @Test fun quickAnswersAndFollowupsCannotEnterEitherMasteryWindow() {
        fact("quick_ask_opened",json("day" to logicalDay(at,"Europe/London"),"kpId" to "valuable"),"quick")
        repeat(3) { answer("q$it","valuable","quick"); at += 86400000 }
        fact("followup_asked",json("pendingAttemptId" to "q0"),"quick")
        val state = project(events)
        assertTrue(state.mastery.values.none { it.passed || it.solid || it.window.isNotEmpty() || it.support.isNotEmpty() })
        assertTrue(state.position.pending.isEmpty())
        assertTrue(state.review.isEmpty())
    }

    @Test fun oldDaySummaryBecomesStaleWhenLateGradeChangesThatDaysStatistics() {
        answer("a","kp")
        val day = logicalDay(at,"Europe/London")
        val from = events.last().seq
        fact("journal_summarized",json("day" to day,"fromSeq" to from,"summary" to "A summary"))
        assertFalse(project(events).journal.getValue(day).summaryStale)
        at += 86400000
        fact("grade_reviewed",json("attemptId" to "a","decision" to "REVISE","revised" to json("score" to 0,"max" to 10)))
        val old = project(events).journal.getValue(day)
        assertEquals(0,old.correct)
        assertTrue(old.summaryStale)
        fact("journal_edited",json("day" to day,"rev" to 1,"content" to "My own reflection"))
        fact("legacy_journal_imported",json("day" to day,"sourceId" to "legacy-1","text" to "Old journal"))
        val combined = project(events).journal.getValue(day)
        assertEquals("A summary",combined.summary)
        assertEquals("My own reflection",combined.manual)
        assertEquals(listOf(LegacyJournalEntry("legacy-1","Old journal")),combined.legacyLogs)
    }
}
