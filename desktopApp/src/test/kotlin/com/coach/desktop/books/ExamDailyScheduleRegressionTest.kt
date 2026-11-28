package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import com.coach.ui.CoachTheme
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExamDailyScheduleRegressionTest {
    private val today = LocalDate.of(2026, 11, 29)

    private fun course(id: String, days: Long, count: Int) = CourseDayInput(
        id, id, today.plusDays(days), (1..count).map { ExamPointWork("$id-$it", "Point $it", false) },
    )

    @Test fun dailyPaceAndFirstScheduledDateFollowTheSameSharedBudget() {
        val courses = listOf(course("a", 16, 255), course("b", 12, 107), course("c", 44, 67))
        val board = MultiCourseDailyPlanner.preview(today, courses, 120)
        val rows = board.first().courses.associateBy { it.courseId }
        assertTrue("255 个考点还没掌握，每天约需过 16 个" in rows.getValue("a").reason)
        assertTrue("107 个考点还没掌握，每天约需过 9 个" in rows.getValue("b").reason)
        assertTrue(rows.getValue("a").minutes > rows.getValue("b").minutes)
        assertTrue(board.all { it.totalMinutes <= 120 })
        assertTrue(board.all { day -> day.courses.single { it.courseId == "c" }.minutes == 0 })
        assertTrue("12 月 15 日开始" in assertNotNull(rows.getValue("c").deferral))
        assertEquals("今天的时间先给了a（12 月考）、b（12 月考），预计 12 月 15 日开始", rows.getValue("c").reason)
        assertTrue(board.all { day -> day.courses.single { it.courseId == "c" }.reason.endsWith("预计 12 月 15 日开始") })
        for (offset in 1L..16L) {
            val day = MultiCourseDailyPlanner.plan(today.plusDays(offset), courses, 120)
            assertEquals(offset == 16L, day.courses.single { it.courseId == "c" }.minutes > 0)
        }

        val moved = listOf(courses[0].copy(examDate = today.plusDays(3)), courses[1], courses[2])
        val skipped = MultiCourseDailyPlanner.preview(today.plusDays(1), moved, 120)
        val far = skipped.first().courses.single { it.courseId == "c" }
        val firstDate = generateSequence(today.plusDays(1)) { it.plusDays(1) }
            .takeWhile { it < courses[2].examDate!! }
            .first { date -> MultiCourseDailyPlanner.plan(date, moved, 120).courses.any { it.courseId == "c" && it.minutes > 0 } }
        assertTrue(firstDate < today.plusDays(16))
        assertTrue("${firstDate.monthValue} 月 ${firstDate.dayOfMonth} 日开始" in assertNotNull(far.deferral))
        assertTrue(far.reason.endsWith("预计 ${firstDate.monthValue} 月 ${firstDate.dayOfMonth} 日开始"))
    }

    @Test fun deferredReasonWithoutAStartDateExplainsWhereTheTimeWent() {
        val courseA = course("A", 1, 2)
        val courseB = course("B", 1, 1)
        val shared = MultiCourseDailyPlanner.plan(today, listOf(courseA, courseB), 20)
        assertEquals("今天的时间先给了A（11 月考），离考试近了会开始排", shared.courses.single { it.courseId == "B" }.reason)

        val tooShort = MultiCourseDailyPlanner.plan(today, listOf(courseB), 15)
        assertEquals("今天的 15 分钟排不下一项学习任务，离考试近了会开始排", tooShort.courses.single().reason)
    }

    @Test fun examDayReleasesEvenPinnedWorkAndLeavesRoomForAShortReview() {
        val point = ExamPointWork("p", "Point", false, wrongQuestions = 4, bankQuestions = 6)
        val exam = CourseDayInput("exam", "Exam", today, listOf(point), listOf(ExamTaskPin("p", today)))
        val other = course("other", 1, 1).copy(points = listOf(point.copy(mastered = true, wrongQuestions = 1, bankQuestions = 0)))
        val direct = ExamDailyTaskPlanner.plan(today, today, exam.points, 120, exam.pins)
        assertEquals(ExamTaskTiming.EXAM_DAY, direct.timing)
        assertEquals(0, direct.minutes)
        assertTrue(direct.newPointIds.isEmpty())
        assertTrue(direct.pointsWithoutQuestions.isEmpty())
        val planned = MultiCourseDailyPlanner.preview(today, listOf(exam, other), 120).first()
        assertTrue(planned.courses.single { it.courseId == "exam" }.lines.isEmpty())
        val review = planned.courses.single { it.courseId == "other" }
        assertEquals(1, review.tasks.reviewWrongCount)
        assertEquals(ExamDailyTaskPlanner.WRONG_MINUTES, planned.totalMinutes)
        assertNull(review.deferral)
    }

    @Test fun chapterWithAnExaminablePointOnItsPagesIsCountedOnlyOnce() {
        val root = Files.createTempDirectory("exam-chapters-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            library.withCourseDatabase { db ->
                val id = CourseStore.createCourse(db, "Test", CourseExamProfile(examDate = today.plusDays(1).toString())).first
                db.createStatement().use { sql ->
                    sql.execute("INSERT INTO books(id,title,format,touched) VALUES('book','Test','txt',1)")
                    sql.execute("""INSERT INTO course_topics(topic_id, collection_id, week_label, ord, title, exam_status, updated_at)
                        VALUES ('chapter', '$id', '第 1 章', 1, 'A', 'EXAMINABLE', 1),
                               ('point', '$id', '考点', 2, 'B', 'EXAMINABLE', 1),
                               ('standalone', '$id', '第 2 章', 3, 'C', 'EXAMINABLE', 1),
                               ('excluded', '$id', '考点', 4, 'D', 'EXCLUDED', 1)""")
                    sql.execute("""INSERT INTO course_topic_sources(topic_id,book_id,start_page,end_page,source_role)
                        VALUES ('chapter','book',1,5,'CORE_TEXTBOOK'), ('point','book',5,5,'CORE_TEXTBOOK'),
                               ('standalone','book',6,9,'CORE_TEXTBOOK'), ('excluded','book',7,7,'CORE_TEXTBOOK')""")
                }
            }
            ExamDailyHome.setSharedBudget(library, 120)
            val direct = assertNotNull(ExamDailyHome.load(library, today))
            val row = assertNotNull(ExamDailyHome.loadDay(library, today)).today.courses.single()
            assertEquals(listOf("point", "standalone"), direct.tasks.newPointIds)
            assertEquals(direct.tasks.newPointIds, row.tasks.newPointIds)
            assertTrue("2 个考点还没掌握" in row.reason)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun collapsedTodayCardShowsWhenTheDeferredCourseStarts() {
        val root = Files.createTempDirectory("exam-deferral-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val tomorrow = LocalDate.now().plusDays(1)
            library.withCourseDatabase { db ->
                for ((title, exam) in listOf("Soon" to tomorrow, "Later" to tomorrow.plusDays(10))) {
                    val id = CourseStore.createCourse(db, title, CourseExamProfile(examDate = exam.toString())).first
                    db.prepareStatement("""INSERT INTO course_topics(topic_id,collection_id,week_label,ord,title,updated_at)
                        VALUES(?,?,'考点',1,'Point',1)""").use { sql ->
                        sql.setString(1, "point-$id")
                        sql.setString(2, id)
                        sql.executeUpdate()
                    }
                }
            }
            runSkikoComposeUiTest(size = Size(1360f, 820f)) {
                setContent { CoachTheme(false) { TodayCard(LibraryUi(), library, {}, {}, {}) } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("home-exam-deferred").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("home-exam-deferred").assertIsDisplayed()
                    .assertTextContains("Later", substring = true)
                    .assertTextContains("${tomorrow.monthValue} 月 ${tomorrow.dayOfMonth} 日开始", substring = true)
                onAllNodesWithTag("home-exam-day").assertCountEquals(0)
                onNodeWithTag("home-exam-summary").performClick()
                onNodeWithText("Later · 今天的时间先给了Soon（${tomorrow.monthValue} 月考），预计 ${tomorrow.monthValue} 月 ${tomorrow.dayOfMonth} 日开始")
                    .assertIsDisplayed()
                onNodeWithTag("home-exam-minutes-120").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("home-exam-deferred").fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
