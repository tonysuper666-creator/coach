package com.coach.desktop.books

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TON-1820: a course is 「要考」 or 「不考」, 不考 by default, and a 不考 course shows no exam words. */
class CourseExamModeTest {

    private val examWords = listOf("必考", "不考", "考点", "考纲", "往年题", "考点优先", "0 项")

    private fun assertNoExamWords(text: String) {
        examWords.forEach { w -> assertFalse(w in text, "「$w」 must not appear in a course without an exam: $text") }
    }

    @Test
    fun textbookOnlyCourseWithoutDateIsNoExamAndKeepsTheWholeBookInOrder() {
        val dir = Files.createTempDirectory("coach-course-exam-mode-")
        try {
            val library = TextbookLibrary(dir, lazyPdfExtras = false)
            val p = dir.resolve("Core-Textbook.md")
            Files.writeString(
                p,
                "# Chapter 1 Choice Under Uncertainty\nExpected utility weighs contingent outcomes by probabilities.\n\n" +
                    "# Chapter 2 Adverse Selection\nLow-risk buyers exit as pooled premiums climb above reservation value.\n\n" +
                    "# Chapter 3 Moral Hazard\nInsurance changes behaviour once coverage is bought.\n",
            )
            val textbook = library.stageBookImport(p).book

            // Same call the 「为一门课备考」 route makes when no date is filled in.
            val detail = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db = db,
                    title = "Self Study",
                    examProfile = CourseExamProfile(examDate = ""),
                    materialBookIds = listOf(textbook.id),
                )
                CourseAutoAligner.alignLocally(db, cid)
            }

            assertFalse(detail.examProfile.isExam, "No date and no 「要考」 means 不考")
            assertTrue(detail.excludedTopics.isEmpty(), "Without a syllabus nothing is parked as 不考: ${detail.excludedTopics.map { it.title }}")
            val topics = CourseExamMode.selfStudyTopics(detail)
            assertTrue(topics.size >= 3, "Every chapter stays in the course: ${topics.map { it.title }}")
            assertTrue(topics.first().title.contains("Choice Under Uncertainty"), "Book order is kept: ${topics.map { it.title }}")

            val firstStep = assertNotNull(CourseExamMode.firstStepLabel(detail))
            assertTrue(firstStep.startsWith("从「") && firstStep.endsWith("分钟"), firstStep)
            assertNoExamWords(firstStep)
            assertNoExamWords(CourseExamMode.arrangementLine(detail))
            assertNoExamWords(CourseExamMode.selfStudyMeta(detail))
            topics.forEach { assertNoExamWords(it.weekLabel) }
            assertFalse(CourseExamMode.shouldAskScope(detail.examProfile, detail.materials))
            assertNull(CourseExamMode.scopeAnswerLine(detail.examProfile, detail.materials))

            // Tapping 「要考」: becomes an exam, the scope hint line shows, and the ask is pending.
            val exam = library.withCourseDatabase { db ->
                CourseStore.updateExamProfile(db, detail.courseId, detail.examProfile.copy(examIntent = true))
                CourseStore.loadCourseDetail(db, detail.courseId, library.clock)
            }
            assertTrue(exam.examProfile.isExam)
            assertEquals(CourseExamMode.SCOPE_HINT, CourseExamMode.arrangementLine(exam))
            assertTrue(CourseExamMode.shouldAskScope(exam.examProfile, exam.materials))
            assertFalse("0 项" in buildExamMeta(exam), buildExamMeta(exam))

            // 跳过 is remembered for this course and stays visible and editable on the course page.
            val skipped = library.withCourseDatabase { db ->
                CourseStore.updateExamProfile(db, exam.courseId, exam.examProfile.copy(scopeAsk = CourseExamProfile.SCOPE_ASK_SKIPPED))
                CourseStore.loadCourseDetail(db, exam.courseId, library.clock)
            }
            assertFalse(CourseExamMode.shouldAskScope(skipped.examProfile, skipped.materials), "跳过 must not ask again")
            assertEquals("考纲或往年题：先不放", CourseExamMode.scopeAnswerLine(skipped.examProfile, skipped.materials))
            assertEquals(skipped.allTopics.map { it.packSourcesHash }, exam.allTopics.map { it.packSourcesHash })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun examDateAloneMakesItAnExamAndLegacyParkedChaptersReadBackAsWholeBook() {
        assertTrue(CourseExamProfile(examDate = "2026-12-12").isExam)
        assertTrue(CourseExamProfile(examIntent = true).isExam)
        assertFalse(CourseExamProfile().isExam)

        val parked = CourseTopicEntry(
            topicId = "t1", collectionId = "c1", weekLabel = "本课不考", weekNum = null, ord = 9,
            title = "Chapter 9", examStatus = CourseTopicStatus.EXCLUDED, userLocked = false, examWeight = 0.0,
        )
        val locked = parked.copy(topicId = "t2", userLocked = true)
        val normalized = CourseExamMode.normalizeTopics(listOf(parked, locked), materials = emptyList())
        assertEquals(CourseTopicStatus.EXAMINABLE, normalized[0].examStatus)
        assertEquals(CourseExamMode.WHOLE_BOOK_LABEL, normalized[0].weekLabel)
        assertEquals(CourseTopicStatus.EXCLUDED, normalized[1].examStatus, "A chapter the user moved by hand stays put")
    }

    @Test
    fun newProfileFieldsRoundTripButStayOutOfTheContentHash() {
        val profile = CourseExamProfile(examDate = "", examIntent = true, scopeAsk = CourseExamProfile.SCOPE_ASK_SKIPPED)
        val decoded = CourseJson.decodeExamProfile(CourseJson.encodeExamProfile(profile))
        assertTrue(decoded.examIntent)
        assertEquals(CourseExamProfile.SCOPE_ASK_SKIPPED, decoded.scopeAsk)
        assertEquals(
            CourseJson.encodeExamProfile(CourseExamProfile().forContentHash()),
            CourseJson.encodeExamProfile(profile.forContentHash()),
        )
        assertNotEquals(CourseJson.encodeExamProfile(CourseExamProfile()), CourseJson.encodeExamProfile(profile))
    }

    @Test
    fun planEntryLabelSwitchesToCountdownInTheLastTenDays() {
        assertEquals("排本周计划", CourseExamMode.planEntryLabel(null))
    }

    @Test
    fun studyMenuDropsExamFocusForBooksWithoutAnExam() {
        val noExam = SmartDefaultModeResolver.resolve(
            bookId = "b", title = "Health Economics", format = "pdf", pageCount = 400,
            sampleText = "", materialKind = "TEXTBOOK", inCourse = true, courseExamDate = null,
            inBooklist = false, testedSectionRatio = 0.0, readChapterRatio = 0.0,
        )
        assertNotEquals(BookStudyMode.EXAM_FOCUS, noExam.mode, "A course without an exam must not default to 考点优先")
        examWords.forEach { w -> assertFalse(w in noExam.defaultReason, noExam.defaultReason) }

        val modes = noExam.copy(examAvailable = false).visibleModes()
        assertFalse(BookStudyMode.EXAM_FOCUS in modes)
        modes.forEach { m -> examWords.forEach { w -> assertFalse(w in m.titleZh || w in m.shortSummaryZh, "${m.titleZh}: ${m.shortSummaryZh}") } }
        assertTrue(modes.none { "做题" in it.titleZh }, "「做题」 is called 「自测」")

        val withExam = SmartDefaultModeResolver.resolve(
            bookId = "b", title = "Health Economics", format = "pdf", pageCount = 400,
            sampleText = "", materialKind = "TEXTBOOK", inCourse = true, courseExamDate = null,
            inBooklist = false, testedSectionRatio = 0.0, readChapterRatio = 0.0, courseIsExam = true,
        )
        assertEquals(BookStudyMode.EXAM_FOCUS, withExam.mode)
        assertTrue(BookStudyMode.EXAM_FOCUS in withExam.copy(examAvailable = true).visibleModes())
        assertTrue(BookStudyMode.EXAM_FOCUS in withExam.visibleModes(), "Unknown keeps every mode")
    }
}
