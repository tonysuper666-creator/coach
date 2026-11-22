package com.coach.desktop.books

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class ControllableClock(
    private var nowInstant: Instant = Instant.parse("2026-11-22T09:00:00Z"),
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = ControllableClock(nowInstant, zone)
    override fun instant(): Instant = nowInstant
    fun advanceDays(days: Long) {
        nowInstant = nowInstant.plusSeconds(days * 86_400L)
    }
}

class ExamCountdownPlannerTest {

    private fun topic(
        id: String,
        weekLabel: String,
        weekNum: Int,
        ord: Int,
        title: String,
        status: CourseTopicStatus = CourseTopicStatus.EXAMINABLE,
        weight: Double = 1.0,
        bookId: String = "book-1",
        discrepancies: Int = 0,
    ): CourseTopicEntry {
        val discList = List(discrepancies) { idx ->
            CourseDiscrepancyItem(
                point = "分歧 $idx",
                authoritativeView = "L1 讲义口径",
                secondaryView = "L2 教材口径",
                advice = "以讲义为准",
            )
        }
        return CourseTopicEntry(
            topicId = id,
            collectionId = "course-1",
            weekLabel = weekLabel,
            weekNum = weekNum,
            ord = ord,
            title = title,
            examStatus = status,
            userLocked = false,
            examWeight = weight,
            sources = listOf(
                CourseTopicSourceLink(
                    topicId = id,
                    bookId = bookId,
                    bookTitle = "Lecture Notes",
                    bookFormat = "pdf",
                    role = CourseMaterialRole.LECTURE_SLIDES,
                    authorityRank = 1,
                ),
            ),
            pack = if (discrepancies > 0) {
                CourseTopicPack(
                    topicId = id,
                    summary = "Summary",
                    authorityNotice = "L1 > L2",
                    discrepancies = discList,
                )
            } else null,
        )
    }

    @Test
    fun gapRankingThreePhasesAndExcludedFilteringWorkDeterministically() {
        val today = LocalDate.of(2026, 11, 22)
        val nowMs = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()

        val topics = listOf(
            topic("t-w1", "第 1 周", 1, 0, "需求弹性", weight = 1.0, bookId = "b-w1"),
            topic("t-w4", "第 4 周", 4, 1, "信息不对称与 Akerlof", weight = 3.5, bookId = "b-w4", discrepancies = 2),
            topic("t-w9-ex", "第 9 周", 9, 2, "不考专题：客座讲座", status = CourseTopicStatus.EXCLUDED, weight = 10.0, bookId = "b-w9"),
        )
        val concepts = listOf(
            ConceptMasteryRecord(
                conceptKey = "t-w1",
                bookId = "b-w1",
                tocNodeId = "t-w1",
                canonicalName = "需求弹性",
                pMastery = 0.88,
                fsrsStability = 20.0,
                hasActiveTest = true,
                dominantErrorTag = null,
                updatedAt = nowMs - 86_400_000L,
            ),
            ConceptMasteryRecord(
                conceptKey = "t-w4",
                bookId = "b-w4",
                tocNodeId = "t-w4",
                canonicalName = "Akerlof 市场",
                pMastery = 0.22,
                fsrsStability = 3.0,
                hasActiveTest = true,
                dominantErrorTag = "SIGN_OR_FORMULA",
                updatedAt = nowMs - 86_400_000L,
            ),
        )

        // D = 14 (>10): FOUNDATION, FSRS cap 20
        val d14 = ExamCountdownPlanner.compute(
            courseId = "course-1",
            examDateRaw = today.plusDays(14).toString(),
            topics = topics,
            concepts = concepts,
            today = today,
            nowMs = nowMs,
        )
        assertEquals(CountdownPhase.FOUNDATION, d14.phase)
        assertEquals(20, d14.fsrsDailyCap)
        assertEquals(0, d14.overdueAlertCount)
        assertEquals("t-w4", d14.todayFocusTopicId)
        assertFalse(d14.rankedTopics.any { it.topicId == "t-w9-ex" })
        assertTrue(d14.rankedTopics[0].gapScore > d14.rankedTopics[1].gapScore)

        // D = 7 (10 >= D > 3): SPRINT_CLEAR, FSRS cap 30
        val d7 = ExamCountdownPlanner.compute(
            courseId = "course-1",
            examDateRaw = today.plusDays(7).toString(),
            topics = topics,
            concepts = concepts,
            today = today,
            nowMs = nowMs,
        )
        assertEquals(CountdownPhase.SPRINT_CLEAR, d7.phase)
        assertEquals(30, d7.fsrsDailyCap)
        assertTrue("错因" in d7.headerSummaryLine)
        assertTrue(d7.dailySlots.all { it.totalMinutes <= it.budgetMinutes })

        // D = 2 (<= 3): FINAL_MOCK, FSRS cap 30, schedules timed mock + missed page review
        val mockLosses = listOf(
            MockTopicLossEvidence(topicId = "t-w4", bookId = "b-w4", missedPages = listOf(18), errorClass = "SIGN_OR_FORMULA"),
        )
        val d2 = ExamCountdownPlanner.compute(
            courseId = "course-1",
            examDateRaw = today.plusDays(2).toString(),
            topics = topics,
            concepts = concepts,
            recentMockLosses = mockLosses,
            today = today,
            nowMs = nowMs,
        )
        assertEquals(CountdownPhase.FINAL_MOCK, d2.phase)
        assertEquals(30, d2.fsrsDailyCap)
        assertTrue(d2.dailySlots.first().includeTimedMock)
        assertTrue(d2.rankedTopics.first().hasRecentMockLoss)
        assertTrue("回看第 18 页" in d2.rankedTopics.first().cardMetaLine)
    }

    @Test
    fun absenceRebalanceAndJumpAheadUpdateFocusDeterministicallyWithZeroOverdueAlerts() {
        val day0 = LocalDate.of(2026, 11, 20)
        val day3 = day0.plusDays(3)
        val nowMs = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()

        val topics = listOf(
            topic("t1", "第 1 周", 1, 0, "导论与均衡", weight = 2.0, bookId = "b1"),
            topic("t2", "第 2 周", 2, 1, "Grossman 模型", weight = 3.0, bookId = "b2"),
            topic("t3", "第 3 周", 3, 2, "保险需求", weight = 2.5, bookId = "b3"),
        )
        val initialConcepts = listOf(
            ConceptMasteryRecord(conceptKey = "t1", bookId = "b1", tocNodeId = "t1", canonicalName = "导论与均衡", pMastery = 0.25, fsrsStability = 4.0),
            ConceptMasteryRecord(conceptKey = "t2", bookId = "b2", tocNodeId = "t2", canonicalName = "Grossman 模型", pMastery = 0.20, fsrsStability = 3.5),
            ConceptMasteryRecord(conceptKey = "t3", bookId = "b3", tocNodeId = "t3", canonicalName = "保险需求", pMastery = 0.70, fsrsStability = 12.0),
        )

        val beforeStart = System.nanoTime()
        val afterAbsencePlan = ExamCountdownPlanner.compute(
            courseId = "course-1",
            examDateRaw = day0.plusDays(9).toString(),
            topics = topics,
            concepts = initialConcepts,
            today = day3,
            nowMs = nowMs,
            dailyBudgetMinutes = 60,
        )
        val elapsedMs = (System.nanoTime() - beforeStart) / 1_000_000.0
        assertTrue(elapsedMs < 20.0, "Expected < 20ms plan compute, got ${elapsedMs}ms")
        assertEquals(6, afterAbsencePlan.daysRemaining)
        assertEquals(0, afterAbsencePlan.overdueAlertCount)
        assertEquals("t2", afterAbsencePlan.todayFocusTopicId)
        assertTrue(afterAbsencePlan.dailySlots.all { it.totalMinutes <= 60 })

        // Jump ahead: learner finishes t2 early with high mastery -> t1 becomes today's focus!
        val jumpedConcepts = initialConcepts.map { rec ->
            if (rec.topicIdOrSelf() == "t2") rec.copy(pMastery = 0.94, fsrsStability = 40.0) else rec
        }
        val afterJumpPlan = ExamCountdownPlanner.compute(
            courseId = "course-1",
            examDateRaw = day0.plusDays(9).toString(),
            topics = topics,
            concepts = jumpedConcepts,
            today = day3,
            nowMs = nowMs,
            dailyBudgetMinutes = 60,
        )
        assertEquals("t1", afterJumpPlan.todayFocusTopicId)
        assertEquals(0, afterJumpPlan.overdueAlertCount)
    }

    private fun ConceptMasteryRecord.topicIdOrSelf(): String = tocNodeId.ifBlank { conceptKey }

    @Test
    fun fsrsDailyQueueAutomaticallySwitchesCapFrom20To30WithinTenDaysOfExam() {
        val root = Files.createTempDirectory("coach-fsrs-switch-")
        try {
            val clock = ControllableClock(Instant.parse("2026-11-22T09:00:00Z"))
            val lib = TextbookLibrary(root.resolve("library"), clock = clock)
            val book = lib.importBook(syntheticTextbook(root.resolve("src")))
            val lesson = book.lessons.first()

            val day0 = LocalDate.now(clock).toEpochDay()
            lib.withCourseDatabase { db ->
                CourseStore.createCourse(
                    db = db,
                    title = "ECNM10119",
                    examProfile = CourseExamProfile(examDate = "2026-12-10", examDateTentative = false),
                    materialBookIds = listOf(book.id),
                )
                for (i in 1..28) {
                    val keyLesson = "${lesson.id}#q$i"
                    db.prepareStatement(
                        """INSERT INTO book_reviews(
                            book, lesson, mode, book_title, lesson_title, prompt, reference, solution,
                            options, wrong_text, answer_text, revealed, understood, completed, guessed,
                            sources, due_day, streak
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    ).use { s ->
                        s.setString(1, book.id)
                        s.setString(2, keyLesson)
                        s.setString(3, QuestionMode.CHOICE.name)
                        s.setString(4, book.title)
                        s.setString(5, "节 $i")
                        s.setString(6, "第 $i 题题干")
                        s.setString(7, "原句")
                        s.setString(8, "A")
                        s.setString(9, """["A","B"]""")
                        s.setString(10, "B")
                        s.setString(11, "")
                        s.setInt(12, 1)
                        s.setInt(13, 0)
                        s.setInt(14, 0)
                        s.setInt(15, 0)
                        s.setInt(16, 1)
                        s.setLong(17, day0)
                        s.setInt(18, 0)
                        s.executeUpdate()
                    }
                    db.prepareStatement(
                        """INSERT INTO book_fsrs_cards(
                            book, lesson, mode, stability, difficulty, last_review_day, due_day, reps, lapses, origin, updated_at
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    ).use { s ->
                        s.setString(1, book.id)
                        s.setString(2, keyLesson)
                        s.setString(3, QuestionMode.CHOICE.name)
                        s.setDouble(4, 1.5)
                        s.setDouble(5, 5.0)
                        s.setLong(6, day0 - 2)
                        s.setLong(7, day0)
                        s.setInt(8, 1)
                        s.setInt(9, 0)
                        s.setString(10, "SEED")
                        s.setLong(11, clock.millis())
                        s.executeUpdate()
                    }
                }
            }

            // 2026-11-22 -> 2026-12-10 is D=18 (> 10 days): daily cap MUST be 20 (deferred = 8).
            val farQueue = lib.reviewQueue()
            assertEquals(20, farQueue.cards.size)
            assertEquals(8, farQueue.deferred)

            // Advance clock by 9 days to 2026-12-01 (D=9 <= 10 days): automatic switch to cap 30 (deferred = 0)!
            clock.advanceDays(9)
            val sprintQueue = lib.reviewQueue()
            assertEquals(28, sprintQueue.cards.size)
            assertEquals(0, sprintQueue.deferred)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
