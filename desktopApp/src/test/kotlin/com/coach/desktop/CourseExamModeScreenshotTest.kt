package com.coach.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import com.coach.ui.CoachTheme
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Light and dark screenshot evidence for TON-1820 (a course is 「要考」 or 「不考」, 不考 by default):
 * 1. A course made through 「为一门课备考」 with only a textbook and no date: course page, study menu and the
 *    practice entry show no exam words, and the course page shows today's first step.
 * 2. After a date is set: the course-page line, the scope ask on 「排本周计划」, and no second ask after 跳过.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class CourseExamModeScreenshotTest {
    private val out = Path.of("build/screens/ton1820")
    private val examWords = listOf("必考", "不考", "考点", "考纲", "往年题", "考点优先", "0 项")

    private fun SkikoComposeUiTest.shot(name: String) {
        Files.createDirectories(out)
        assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", out.resolve("$name.png").toFile()))
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.assertNoExamWords(where: String) {
        for (w in examWords) {
            val hits = onAllNodes(hasText(w, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(hits.isEmpty(), "「$w」 must not show on the $where of a course without an exam")
        }
    }

    private fun flow(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val root = Files.createTempDirectory("ton1820-shot-")
        try {
            val textbook = root.resolve("Core-Textbook.md").also {
                Files.writeString(
                    it,
                    "# Chapter 1 Choice Under Uncertainty\n" +
                        "Expected utility weighs contingent outcomes by their probabilities, so a risk-averse buyer values " +
                        "insurance above its expected payout.\n\n" +
                        "# Chapter 2 Adverse Selection\n" +
                        "Low-risk buyers exit as pooled premiums climb above their reservation value.\n\n" +
                        "# Chapter 3 Moral Hazard\n" +
                        "Insurance changes behaviour once coverage is bought.\n",
                )
            }
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val c = TextbookController(library)
            // The 「为一门课备考」 route with only a textbook and the date left empty (TON-1786 path).
            runBlocking {
                c.load()
                c.createCollection(CollectionKind.COURSE, "Self Study", examDate = null, newFiles = listOf(textbook)).join()
            }
            val courseId = assertNotNull(c.state.value.activeCourseId)
            assertFalse(library.courseDetail(courseId)!!.examProfile.isExam, "为一门课备考 without a date is 不考")
            runBlocking { c.openCourse(courseId) }

            runSkikoComposeUiTest(size = Size(1440f, 960f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                        CoachTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                WindowsTextbookHost(c) { null }
                            }
                        }
                    }
                }
                ready(c) { it.activeCourseDetail?.courseId == courseId }
                onNodeWithTag("course-screen").assertIsDisplayed()

                // 1a. Course page: no exam words, first step on the button.
                onNodeWithTag("course-primary-cta").assertIsDisplayed()
                onNode(hasTestTag("course-primary-cta") and hasText("从「", substring = true)).assertExists()
                onNode(hasText("分钟", substring = true), useUnmergedTree = true).assertExists()
                onNodeWithTag("course-exam-intent-on").assertExists()
                onNodeWithTag("course-scope-hint").assertDoesNotExist()
                onNodeWithTag("course-plan-entry").assertDoesNotExist()
                assertNoExamWords("course page")
                shot("01-no-exam-course-$mode")

                // 1b. Study menu in the reader opened from the first step.
                onNodeWithTag("course-primary-cta").performClick()
                ready(c) { it.screen == BookScreen.READER }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty() }
                // The reader chrome shows when the pointer reaches the top edge.
                onNodeWithTag("textbook-reader").performMouseInput { moveTo(Offset(width / 2f, 300f)); moveTo(Offset(width / 2f, 10f)) }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("immersive-mode-trigger").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithTag("immersive-mode-trigger").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("study-mode-popover").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mode-option-EXAM_FOCUS").assertDoesNotExist()
                onNode(hasText("做题", substring = true), useUnmergedTree = true).assertDoesNotExist()
                assertNoExamWords("study menu")
                shot("02-no-exam-study-menu-$mode")
                onNodeWithTag("immersive-mode-trigger").performClick()
                waitForIdle()

                // 1c. Practice entry.
                runBlocking { c.learn(LearningPath.READ_AND_LEARN) }
                ready(c) { it.screen == BookScreen.LEARNING && !it.questionsOpen }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("guided-start-questions").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasTestTag("guided-start-questions") and hasText("继续自测")).assertExists()
                onNode(hasText("做题", substring = true), useUnmergedTree = true).assertDoesNotExist()
                assertNoExamWords("practice entry")
                shot("03-no-exam-practice-entry-$mode")

                // 2. Set a date: the course becomes an exam and shows the one clickable scope line.
                runBlocking {
                    c.openCourse(courseId)
                    val profile = c.state.value.activeCourseDetail!!.examProfile
                    c.updateCourseExamProfile(courseId, profile.copy(examDate = LocalDate.now().plusDays(30).toString()))
                }
                ready(c) { it.activeCourseDetail?.examProfile?.isExam == true }
                onNodeWithTag("course-scope-hint").assertTextEquals("按整本书安排，放进考纲后可以缩小范围")
                onNode(hasText("0 项", substring = true), useUnmergedTree = true).assertDoesNotExist()
                shot("04-exam-course-scope-line-$mode")

                // 排本周计划: the ask on top, the first time.
                onNodeWithTag("course-plan-entry").performScrollTo().performClick()
                waitForIdle()
                onNodeWithTag("course-scope-ask").assertExists()
                onNode(hasAnyAncestor(hasTestTag("course-scope-ask")) and hasText("有考纲或往年题吗？没有也能先学")).assertExists()
                onNodeWithTag("course-week-plan").performScrollTo()
                shot("05-plan-scope-ask-$mode")

                // 跳过, then open again: no second ask, the answer stays on the course page.
                onNodeWithTag("course-scope-ask-skip").performClick()
                ready(c) { it.activeCourseDetail?.examProfile?.scopeAsk == CourseExamProfile.SCOPE_ASK_SKIPPED }
                onNodeWithTag("course-scope-ask").assertDoesNotExist()
                onNodeWithTag("course-week-plan-close").performClick()
                waitForIdle()
                onNodeWithTag("course-plan-entry").performScrollTo().performClick()
                waitForIdle()
                onNodeWithTag("course-week-plan").assertExists()
                onNodeWithTag("course-scope-ask").assertDoesNotExist()
                onNodeWithTag("course-scope-answer").assertExists()
                onNodeWithTag("course-scope-answer-provide").assertExists()
                onNodeWithTag("course-plan-entry").assertExists()
                onNodeWithTag("course-scope-hint").performScrollTo()
                shot("06-plan-after-skip-$mode")
            }
            // The skip is stored with the course, not just on screen.
            assertEquals(CourseExamProfile.SCOPE_ASK_SKIPPED, library.courseDetail(courseId)!!.examProfile.scopeAsk)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun courseExamModeLight() = flow(dark = false)
    @Test fun courseExamModeDark() = flow(dark = true)
}
