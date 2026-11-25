package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** This class also compiles on main before the three-route implementation, for a behavioral red. */
@OptIn(ExperimentalTestApi::class)
class WindowsLearningPathsEntryTest {
    /**
     * 2.0 书架首页（TON-1790）把「读 / 带读 / 先做题」三个入口（home-guided、home-question、home-learn）
     * 收成焦点卡上的一个「继续」加一个学法选择（home-mode-trigger，TON-1805），题型仍跟三条路分开存。
     */
    @Test fun sameBookOffersThreeWalksSeparateFromQuestionTypes() = entry { controller, _ ->
        onNodeWithText("返回书架").performClick()
        ready(controller) { it.screen == BookScreen.HOME }
        onNodeWithTag("home-read").assertIsDisplayed()
        onNodeWithTag("home-mode-trigger").assertIsDisplayed()
        onNodeWithTag("home-guided").assertDoesNotExist()
        onNodeWithTag("home-question").assertDoesNotExist()
        onNodeWithTag("home-learn").assertDoesNotExist()
        assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
        assertEquals(QuestionMode.CHOICE, controller.state.value.book!!.questionMode)
        capture("three-entries")
    }

    /** TON-1784（#570）起本地不再按模板出选择题，选择题由已配对的服务按本节第一段正文生成，作答只留在本窗口
     * （AnswerExplain.answers，不写进 SQLite）；测试用 generatedChoiceGate 顶替服务。 */
    @Test fun answeringNeverOpensRawSourceUntilRequestedAndReturnKeepsTheAnswer() = entry { controller, gate ->
        val book = controller.state.value.book!!
        val lesson = book.lessons[book.lessonIndex]
        waitUntil(timeoutMillis = 20_000) { gate.practices["${book.id}:${lesson.id}"] != null }
        val practice = gate.practices.getValue("${book.id}:${lesson.id}").practice
        assertEquals(generatedChoiceBlock(lesson).text, practice.reference)
        onNodeWithText(lesson.text).assertDoesNotExist()
        onNodeWithText("原文：${practice.reference}").assertDoesNotExist()
        onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
        waitUntil(timeoutMillis = 20_000) { gate.answerFor(book, lesson).revealed }
        onNodeWithText("与本题答案一致。").assertExists()
        onNodeWithText("原文：${practice.reference}").assertDoesNotExist()
        onNodeWithText(lesson.text).assertDoesNotExist()
        onNodeWithTag("textbook-reader").assertDoesNotExist()
        capture("questions-after-answer")
        onNodeWithTag("open-practice-source").performScrollTo().performClick()
        ready(controller) { it.screen == BookScreen.READER && it.source != null }
        onNodeWithTag("reader-text").assertExists()
        onNodeWithText(practice.reference, substring = true).assertExists()
        capture("source-after-click")
        onNodeWithTag("immersive-back").assertTextContains("返回学习", substring = true).performClick()
        ready(controller) { it.screen == BookScreen.LEARNING }
        onNodeWithText("原文：${practice.reference}").assertDoesNotExist()
        onNodeWithText(lesson.text).assertDoesNotExist()
        onNodeWithTag("open-practice-source").assertIsFocused()
        assertEquals(PracticeAnswer(practice.answer, true), gate.answerFor(book, lesson))
    }

    private fun entry(block: ComposeUiTest.(TextbookController, AnswerExplain) -> Unit) {
        val root = Files.createTempDirectory("coach-three-walks-entry-")
        val source = root.resolve("synthetic-astronomy.md")
        Files.writeString(source, """
            # Synthetic astronomy

            Orbit is the path of an object around another body. Gravity keeps a body near its star.

            Rotation is the turning of an object around its own axis. A day follows one rotation.
        """.trimIndent())
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            val gate = generatedChoiceGate(root)
            setContent { WindowsTextbookHost(controller, answerExplain = gate) { source } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            // TON-1784（#570）：导入直接进阅读器、没有「开始学」提示；进做题学只剩 learn(QUESTION_FIRST)。
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            runBlocking { controller.learn(LearningPath.QUESTION_FIRST) }
            ready(controller) { it.screen == BookScreen.LEARNING }
            block(controller, gate)
        }
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/learning-paths").toFile().also { it.mkdirs() }
        val file = dir.resolve("$name.png")
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", file))
        val decoded = assertNotNull(ImageIO.read(file))
        assertEquals(1100, decoded.width); assertEquals(800, decoded.height)
    }
}
