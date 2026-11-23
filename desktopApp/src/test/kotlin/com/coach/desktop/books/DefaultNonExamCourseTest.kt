package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.exam.CourseMockHubPane
import com.coach.desktop.books.exam.rememberLocalExamController
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachTheme as Theme
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DefaultNonExamCourseTest {

    private val forbiddenNonExamWords = listOf(
        "必考",
        "不考",
        "考点",
        "考纲",
        "往年题",
        "考点优先",
        "做题",
        "备考",
        "模考",
        "考试",
    )

    private fun writeSyntheticTextbook(dir: Path, filename: String = "Principles-Textbook.md"): Path {
        val p = dir.resolve(filename)
        Files.writeString(
            p,
            buildString {
                for (ch in 1..6) {
                    append("# Chapter $ch Core Concepts Part $ch\n")
                    for (para in 1..6) {
                        append("Section $ch.$para explains how households choose between consumption and saving under uncertainty in chapter $ch.\n\n")
                    }
                }
            },
        )
        return p
    }

    @Test
    fun textbookOnlyCourseDefaultsToNonExamWithWholeBookChaptersAndNoExamMode() {
        val dir = Files.createTempDirectory("coach-ton1820-unit-")
        try {
            val library = TextbookLibrary(dir, lazyPdfExtras = false)
            val bookPath = writeSyntheticTextbook(dir)
            val textbook = library.stageBookImport(bookPath).book

            val detail = library.withCourseDatabase { db ->
                val (cid, rejected) = CourseStore.createCourse(
                    db = db,
                    title = "微观经济学基础",
                    examProfile = CourseExamProfile(),
                    materialBookIds = listOf(textbook.id),
                )
                assertTrue(rejected.isEmpty())
                CourseAutoAligner.alignLocally(db, cid)
            }

            assertFalse(detail.isExam, "Course without exam date or explicit toggle must default to non-exam")
            assertNull(detail.countdownPlan, "Non-exam course must not compute exam countdown plan")
            assertTrue(detail.examinableTopics.isNotEmpty(), "Textbook chapters must be available for study")
            assertTrue(detail.excludedTopics.isEmpty(), "Textbook-only course must never mark chapters as 本课不考")

            // Smart default study mode for book in a non-exam course must NOT be EXAM_FOCUS
            val pref = StudyModeStore.loadOrResolve(library, textbook.id, textbook.title, textbook.pages.size)
            assertFalse(pref.examMode, "Book in non-exam course must have examMode=false")
            assertEquals(BookStudyMode.READ_THROUGH, pref.mode)
            assertFalse(BookStudyMode.EXAM_FOCUS in BookStudyMode.availableModes(pref.examMode))
            assertEquals("自测带读", BookStudyMode.PROBLEM_DRIVEN.displayTitle(false))
            assertFalse(BookStudyMode.PROBLEM_DRIVEN.displaySummary(false).contains("公式"))
            assertFalse(BookStudyMode.PROBLEM_DRIVEN.displaySummary(false).contains("做题"))

            // Setting examEnabled = true flips to exam mode without dropping chapters
            val examDetail = library.withCourseDatabase { db ->
                CourseStore.updateExamProfile(db, detail.courseId, detail.examProfile.copy(examEnabled = true))
                CourseAutoAligner.alignLocally(db, detail.courseId)
            }
            assertTrue(examDetail.isExam)
            assertTrue(examDetail.examinableTopics.isNotEmpty(), "Exam course without syllabus must still schedule whole book")
            assertTrue(examDetail.excludedTopics.isEmpty())
            assertTrue(examDetail.shouldShowSyllabusPrompt, "Exam course without syllabus should show first-time prompt")

            // Reloading study mode preference now picks up examMode = true live
            val examPref = StudyModeStore.loadOrResolve(library, textbook.id, textbook.title, textbook.pages.size)
            assertTrue(examPref.examMode)
            assertTrue(BookStudyMode.EXAM_FOCUS in BookStudyMode.availableModes(examPref.examMode))

            // Skipping syllabus prompt persists across DB reloads
            val afterSkip = library.withCourseDatabase { db ->
                CourseStore.updateExamProfile(
                    db,
                    detail.courseId,
                    examDetail.examProfile.copy(syllabusPromptState = CourseSyllabusPromptState.SKIPPED),
                )
                CourseStore.loadCourseDetail(db, detail.courseId)
            }
            assertFalse(afterSkip.shouldShowSyllabusPrompt)
            assertEquals(CourseSyllabusPromptState.SKIPPED, afterSkip.examProfile.syllabusPromptState)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun uiVerificationCoversNonExamCourseStudyMenuQuizEntryExamPromptAndRouteAlignment() {
        val shotsDir = (System.getProperty("coach.ton1820.shotsDir") ?: System.getenv("COACH_TON1820_SHOTS_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).also { p -> Files.createDirectories(p) } }
        val dir = Files.createTempDirectory("coach-ton1820-ui-")
        try {
            val libDir = dir.resolve("library")
            val srcDir = dir.resolve("src").also { Files.createDirectories(it) }
            val textbookFile = writeSyntheticTextbook(srcDir, "Micro-Textbook.md")

            val library = TextbookLibrary(libDir, lazyPdfExtras = false)
            val controller = TextbookController(library)
            val book = library.stageBookImport(textbookFile).book

            val courseId = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db = db,
                    title = "现代微观导论",
                    examProfile = CourseExamProfile(),
                    materialBookIds = listOf(book.id),
                )
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }
            runBlocking {
                controller.load()
                controller.openCourse(courseId)
            }

            // 1 & 2: CourseScreen non-exam -> set exam date -> weekly plan prompt -> skip -> re-enter no prompt
            runSkikoComposeUiTest(size = Size(1100f, 780f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            WindowsTextbookHost(controller = controller) { null }
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("course-screen").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                // Check 1a: Non-exam course page has zero forbidden exam words and has a concrete first step
                val nonExamTreeText = collectAllVisibleText()
                for (word in forbiddenNonExamWords) {
                    assertFalse(
                        word in nonExamTreeText,
                        "Non-exam course page must not contain '$word', full text:\n$nonExamTreeText",
                    )
                }
                assertTrue(
                    "从第一章开始" in nonExamTreeText && "分钟" in nonExamTreeText,
                    "Expected first-step CTA on non-exam course page, got:\n$nonExamTreeText",
                )
                shotsDir?.let { savePng(it.resolve("01-non-exam-course-page.png")) }

                // Check 2a: Set exam date on the same course via ⋯ -> 设置考试日
                onNodeWithTag("course-overflow").performClick()
                waitForIdle()
                onNodeWithTag("course-exam-profile-action").performClick()
                waitForIdle()
                onNodeWithTag("exam-profile-date-input").performTextInput("2027-01-15")
                onNodeWithTag("exam-profile-save").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("course-exam-scope-line").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                val examPageText = collectAllVisibleText()
                assertTrue(
                    "按整本书安排，放进考纲后可以缩小范围" in examPageText,
                    "Expected whole-book scope hint line, got:\n$examPageText",
                )
                assertFalse("必考专题 0 项" in examPageText)
                assertFalse("必考 0 项" in examPageText)
                shotsDir?.let { savePng(it.resolve("04-exam-course-scope-line.png")) }

                // Check 2b: Click 「排本周计划」 -> first-time inline prompt appears at top
                onNodeWithTag("course-weekly-plan-btn").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("course-weekly-plan-view").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                onNodeWithTag("course-syllabus-prompt-bar").assertIsDisplayed()
                onNodeWithText("有考纲或往年题吗？没有也能先学", substring = true).assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-add").assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-skip").assertIsDisplayed()
                shotsDir?.let { savePng(it.resolve("05-weekly-plan-first-prompt.png")) }

                // Click 「跳过」, return to course, verify recorded answer visible on course page, then re-enter 「排本周计划」
                onNodeWithTag("course-syllabus-prompt-skip").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("course-syllabus-prompt-bar").fetchSemanticsNodes().isEmpty()
                }
                onNodeWithTag("course-schedule-back").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("course-screen").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                val afterSkipCourseText = collectAllVisibleText()
                assertTrue("暂不放考纲" in afterSkipCourseText, "Skipped answer should be visible on course page")
                assertTrue("按整本书安排，放进考纲后可以缩小范围" in afterSkipCourseText)

                // Re-enter 「排本周计划」 -> prompt must NOT appear again
                onNodeWithTag("course-weekly-plan-btn").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("course-weekly-plan-view").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                assertTrue(
                    onAllNodesWithTag("course-syllabus-prompt-bar").fetchSemanticsNodes().isEmpty(),
                    "Syllabus prompt must not reappear after skip",
                )
                shotsDir?.let { savePng(it.resolve("06-weekly-plan-after-skip.png")) }

                // Check 「考前排程」 & 「模考」 also respect skipped state
                onNodeWithTag("course-schedule-back").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("course-screen").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("course-countdown-plan-btn").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("course-countdown-plan-view").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodesWithTag("course-syllabus-prompt-bar").fetchSemanticsNodes().isEmpty())

                onNodeWithTag("course-schedule-back").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("course-screen").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("course-mock-exam-btn").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("course-mock-hub").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(onAllNodesWithTag("course-syllabus-prompt-bar").fetchSemanticsNodes().isEmpty())
            }

            // 1b & 1c: Study mode menu + quiz entry on a non-exam textbook
            val nonExamBookPref = BookStudyPreference(
                bookId = book.id,
                mode = BookStudyMode.READ_THROUGH,
                userLocked = false,
                defaultReason = "按章节顺序读，随时可以切换学法。",
                examMode = false,
            )
            runSkikoComposeUiTest(size = Size(920f, 760f)) {
                var pref by mutableStateOf(nonExamBookPref)
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            Column(
                                Modifier.fillMaxSize().background(Theme.colors.bgCanvas).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                StudyModeSelectorButton(
                                    preference = pref,
                                    onUpdatePreference = { next, _ -> pref = next },
                                    tag = "non-exam-mode-trigger",
                                )
                            }
                        }
                    }
                }
                onNodeWithTag("non-exam-mode-trigger").performClick()
                waitForIdle()
                onNodeWithTag("study-mode-popover").assertIsDisplayed()
                assertTrue(
                    onAllNodesWithTag("mode-option-EXAM_FOCUS").fetchSemanticsNodes().isEmpty(),
                    "Non-exam study mode menu must hide EXAM_FOCUS (考点优先)",
                )
                val menuText = collectAllVisibleText()
                for (word in forbiddenNonExamWords + listOf("公式")) {
                    assertFalse(
                        word in menuText,
                        "Non-exam study mode menu must not contain '$word', got:\n$menuText",
                    )
                }
                assertTrue("自测带读" in menuText, "Expected 自测带读 in non-exam menu")
                shotsDir?.let { savePng(it.resolve("02-non-exam-study-mode-menu.png")) }
            }

            // 1c: Quiz/self-test entry (SectionEndSummaryCard + CompanionModuleTab) uses 「自测」, never 「做题」
            runSkikoComposeUiTest(size = Size(920f, 640f)) {
                val loadedBook = library.book(book.id)
                val sampleLesson = loadedBook.lessons.firstOrNull() ?: BookLesson(
                    id = "ch-1",
                    title = "Chapter 1 Core Concepts Part 1",
                    blocks = listOf(SourceBlock("b1", 1, 0, 48, false, "households choose between consumption and saving")),
                )
                val samplePractices = listOf(
                    Practice(
                        prompt = "这一章主要讨论哪类决策问题？",
                        reference = "households choose between consumption and saving",
                        answer = "跨期消费与储蓄选择",
                        options = listOf("跨期消费与储蓄选择", "汇率制度比较"),
                    ),
                )
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            Column(
                                Modifier.fillMaxSize().background(Theme.colors.bgCanvas).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                SectionEndSummaryCard(
                                    section = sampleLesson,
                                    summaryLines = listOf("核心概念：跨期消费与储蓄权衡。"),
                                    practices = samplePractices,
                                    answers = emptyMap(),
                                    onAnswer = { _, _, _ -> },
                                    onOpenMainline = {},
                                    onDismiss = {},
                                )
                            }
                        }
                    }
                }
                waitForIdle()
                val quizEntryText = collectAllVisibleText()
                for (word in forbiddenNonExamWords) {
                    assertFalse(
                        word in quizEntryText,
                        "Non-exam quiz entry must not contain '$word', got:\n$quizEntryText",
                    )
                }
                assertTrue("自测" in quizEntryText, "Expected 自测 in quiz entry, got:\n$quizEntryText")
                shotsDir?.let { savePng(it.resolve("03-non-exam-quiz-entry.png")) }
            }

            // 3: TON-1786 route alignment — create a course via 「为一门课备考」 without setting exam date -> defaults to non-exam
            val emptyLibDir = dir.resolve("library-empty")
            val emptyLib = TextbookLibrary(emptyLibDir, lazyPdfExtras = false)
            val emptyController = TextbookController(emptyLib)
            runBlocking { emptyController.load() }

            runSkikoComposeUiTest(size = Size(1100f, 780f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            WindowsTextbookHost(
                                controller = emptyController,
                                chooseBooks = { listOf(textbookFile) },
                            ) { textbookFile }
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("empty-route-course").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("empty-route-course").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("create-collection-dialog").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("collection-title-input").performTextInput("宏观专题研读")
                onNodeWithTag("collection-pick-files").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("collection-staged-file-0").fetchSemanticsNodes().isNotEmpty()
                }
                // Deliberately leave exam date blank and confirm
                onNodeWithTag("collection-confirm").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    emptyController.state.value.collections.any { it.record.kind == CollectionKind.COURSE }
                }
                val createdCourseId = emptyController.state.value.collections
                    .first { it.record.kind == CollectionKind.COURSE }.record.id
                runBlocking { emptyController.openCourse(createdCourseId) }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("course-screen").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                val createdDetail = emptyLib.courseDetail(createdCourseId)
                assertFalse(createdDetail.isExam, "Course created via 「为一门课备考」 without exam date must default to non-exam")
                val routeCourseText = collectAllVisibleText()
                for (word in forbiddenNonExamWords) {
                    assertFalse(
                        word in routeCourseText,
                        "Route-created non-exam course must not contain '$word', got:\n$routeCourseText",
                    )
                }
                assertTrue("从第一章开始" in routeCourseText)
                shotsDir?.let { savePng(it.resolve("07-route-prep-default-non-exam.png")) }
            }

            // 4: CourseMockHubPane first-time syllabus prompt when entered directly on an exam course with no syllabus
            val mockCourseId = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db = db,
                    title = "计量经济学",
                    examProfile = CourseExamProfile(examDate = "2027-02-01", examEnabled = true),
                    materialBookIds = listOf(book.id),
                )
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }
            val mockDetail = library.courseDetail(mockCourseId)
            assertTrue(mockDetail.shouldShowSyllabusPrompt)
            runSkikoComposeUiTest(size = Size(1000f, 760f)) {
                var activeDetail by mutableStateOf(mockDetail)
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            val exams = rememberLocalExamController(library)
                            CourseMockHubPane(
                                detail = activeDetail,
                                controller = exams,
                                library = library,
                                onBackToCourse = {},
                                onJumpToBookPage = { _, _, _ -> },
                                onAddMaterials = {},
                                onSkipSyllabusPrompt = {
                                    activeDetail = activeDetail.copy(
                                        examProfile = activeDetail.examProfile.copy(
                                            syllabusPromptState = CourseSyllabusPromptState.SKIPPED,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("course-mock-hub").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("course-syllabus-prompt-bar").assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-skip").performClick()
                waitForIdle()
                assertTrue(onAllNodesWithTag("course-syllabus-prompt-bar").fetchSemanticsNodes().isEmpty())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun ComposeUiTest.collectAllVisibleText(): String =
        onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { collectVisibleText(it) }

    private fun collectVisibleText(root: SemanticsNode): String {
        val sb = StringBuilder()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { sb.append(it.text).append('\n') }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { sb.append(it).append('\n') }
            node.children.forEach(::walk)
        }
        walk(root)
        return sb.toString()
    }

    private fun ComposeUiTest.savePng(path: Path) {
        val img = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
        assertTrue(ImageIO.write(img, "png", path.toFile()))
    }
}
