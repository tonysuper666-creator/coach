package com.coach.desktop.books

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiCourseDailyPlannerTest {
    private val today = LocalDate.of(2026, 11, 27)

    private fun points(count: Int, prefix: String) =
        (1..count).map { ExamPointWork("$prefix$it", "$prefix$it", mastered = false) }

    private fun course(id: String, title: String, exam: LocalDate, count: Int) =
        CourseDayInput(id, title, exam, points(count, id))

    @Test fun oneCourseMatchesTheExistingDailyPlanner() {
        val work = (1..10).map {
            ExamPointWork("p$it", "考点$it", mastered = false, wrongQuestions = 1, bankQuestions = 2)
        }
        val exam = today.plusDays(10)
        val direct = ExamDailyTaskPlanner.plan(today, exam, work, 90)
        val shared = MultiCourseDailyPlanner.plan(today, listOf(CourseDayInput("c", "课", exam, work)), 90)
        val task = shared.courses.single()
        assertEquals(direct.newPointIds, task.tasks.newPointIds)
        assertEquals(direct.reviewWrongCount, task.tasks.reviewWrongCount)
        assertEquals(direct.practiceQuestionCount, task.tasks.practiceQuestionCount)
        assertEquals(direct.minutes, task.minutes)
        assertEquals(direct.pointsWithoutQuestions, task.tasks.pointsWithoutQuestions)
        assertTrue(task.lines.all { "距考试" in it })
    }

    @Test fun closerAndWeakerCoursesGetMoreOfOneCap() {
        val courses = listOf(
            course("econ", "微观", today.plusDays(3), 15),
            course("hist", "历史", today.plusDays(6), 12),
            course("bio", "生物", today.plusDays(20), 20),
        )
        val day = MultiCourseDailyPlanner.plan(today, courses, 120)
        assertTrue(day.totalMinutes <= 120)
        val econ = day.courses.first { it.courseId == "econ" }
        val hist = day.courses.first { it.courseId == "hist" }
        val bio = day.courses.first { it.courseId == "bio" }
        assertTrue(econ.minutes > hist.minutes)
        assertTrue(hist.minutes > bio.minutes)
        assertTrue(econ.lines.all { "所以今天" in it || "分钟" in it })
        assertTrue(econ.reason.contains("还没掌握"))
        assertTrue(day.courses.sumOf { it.minutes } == day.totalMinutes)
    }

    @Test fun aMissedDaySpreadsInsteadOfPiling() {
        val courses = listOf(
            course("econ", "微观", today.plusDays(3), 15),
            course("hist", "历史", today.plusDays(6), 12),
            course("bio", "生物", today.plusDays(20), 20),
        )
        val skipped = MultiCourseDailyPlanner.plan(today.plusDays(1), courses, 120)
        assertTrue(skipped.totalMinutes <= 120)
        val econ = skipped.courses.first { it.courseId == "econ" }
        assertTrue(econ.tasks.newPointIds.size < 15)
        assertTrue(econ.minutes <= 120)
        val preview = MultiCourseDailyPlanner.preview(today, courses, 120, days = 5)
        assertEquals(5, preview.size)
        assertTrue(preview.all { it.totalMinutes <= 120 })
        assertEquals(today.plusDays(4), preview.last().date)
    }

    @Test fun aFinishedExamOrAMovedDateReschedulesTheNextDay() {
        val courses = listOf(
            course("econ", "微观", today.plusDays(3), 15),
            course("hist", "历史", today.plusDays(6), 12),
            course("bio", "生物", today.plusDays(20), 20),
        )
        val afterEcon = MultiCourseDailyPlanner.plan(today.plusDays(4), courses, 120)
        val finished = afterEcon.courses.first { it.courseId == "econ" }
        assertEquals(0, finished.minutes)
        assertTrue("考试已过" in finished.reason)
        assertTrue(afterEcon.totalMinutes <= 120)
        assertTrue(afterEcon.courses.first { it.courseId == "hist" }.minutes > 0)

        val moved = courses.map { if (it.courseId == "hist") it.copy(examDate = today.plusDays(1)) else it }
        val next = MultiCourseDailyPlanner.plan(today, moved, 120)
        val hist = next.courses.first { it.courseId == "hist" }
        val econ = next.courses.first { it.courseId == "econ" }
        assertTrue(hist.minutes > econ.minutes)
        assertTrue(next.totalMinutes <= 120)
    }

    @Test fun aPointWithoutQuestionsIsReadFirstNotAnEmptyTask() {
        val work = listOf(
            ExamPointWork("gap", "弹性", mastered = false, bankQuestions = 0, questionGap = QuestionGap.NEVER_GENERATED),
            ExamPointWork("drop", "成本", mastered = false, bankQuestions = 0, questionGap = QuestionGap.DROPPED),
            ExamPointWork("ok", "供需", mastered = false, bankQuestions = 3),
        )
        val day = MultiCourseDailyPlanner.plan(
            today,
            listOf(CourseDayInput("c", "微观", today.plusDays(1), work)),
            90,
        )
        val task = day.courses.single()
        assertEquals(listOf("弹性", "成本"), task.readFirstTitles)
        assertTrue(task.lines.any { it.startsWith("弹性 · ") && "先读这一节" in it && "还没有题" in it })
        assertTrue(task.lines.any { it.startsWith("成本 · ") && "先读这一节" in it && "核对丢掉" in it })
        assertTrue(task.lines.none { "做 0 道" in it })
        assertTrue(task.lines.any { it.startsWith("做 3 道题") })
        assertEquals(3, task.tasks.practiceQuestionCount)
    }

    @Test fun coverageSeparatesNeverGeneratedFromDropped() {
        val textbook = "Demand elasticity: Buyers respond to price."
        val questions = listOf("Point: demand elasticity\nExplain the idea.")
        val never = ExamCoverage.evaluate(
            questions,
            identified = listOf("demand elasticity"),
            bank = emptyList(),
            textbook = textbook,
        )
        assertEquals(listOf(ExamCoverage.NEVER_GENERATED), never.uncovered.map { it.reason })
        assertEquals(1, never.toJson()["neverGenerated"].toString().filter { it.isDigit() }.toInt())
        val dropped = ExamCoverage.evaluate(
            questions,
            identified = listOf("demand elasticity"),
            bank = listOf(CoverageBankPoint("demand elasticity", choices = 0, essay = false)),
            textbook = textbook,
        )
        assertEquals(listOf(ExamCoverage.QUESTION_DROPPED), dropped.uncovered.map { it.reason })

        val bare = topic("n", drill = null)
        val rejected = topic(
            "d",
            ExamPointDrill(
                choices = emptyList(),
                essay = null,
                missingChoices = 3,
                essayMissing = false,
                discards = mapOf("source_mismatch" to 1),
                checked = 2,
                agreed = 0,
                emptyReason = "只有往年题，不能进题库",
            ),
        )
        val report = ExamPointDrillLocal.report(listOf(bare, rejected))
        assertEquals(1, report.neverGenerated)
        assertEquals(1, report.droppedByCheck)
        assertTrue(report.summary().contains("从未生成1"))
        assertTrue(report.summary().contains("核对丢掉1"))
        assertFalse(report.summary().contains("Supply"))
    }

    private fun topic(id: String, drill: ExamPointDrill?) = CourseTopicEntry(
        topicId = id,
        collectionId = "course",
        weekLabel = CourseExamPoints.LABEL,
        weekNum = null,
        ord = 1,
        title = "point",
        examStatus = CourseTopicStatus.EXAMINABLE,
        userLocked = false,
        pack = CourseTopicPack(topicId = id, summary = "s", authorityNotice = "", drill = drill),
    )
}
