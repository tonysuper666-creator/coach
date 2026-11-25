package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.LearningPath
import com.coach.desktop.books.QuestionMode
import com.coach.desktop.books.StudyCoachCourse
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.answerFor
import com.coach.desktop.books.generatedChoiceBlock
import com.coach.desktop.books.generatedChoiceGate
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsLearningEntryTest {
    /**
     * Uses runCoach's Windows host (the same controller WindowsTextbooksContent builds), real import/SQLite
     * and the existing SourcePractice.
     *
     * TON-1784（#570）：导入后直接进阅读器，不再弹「开始学」提示（enable-learning），首页也没有学习入口；
     * 进做题学只剩控制器 learn(QUESTION_FIRST)。所以这里自己持有控制器，导入走真按钮，进做题学走 learn()。
     * TON-1784（#570）起本地不再按模板出选择题，选择题由已配对的服务按本节第一段正文生成，作答只留在本窗口
     * （AnswerExplain.answers，不写进 SQLite）；测试用 generatedChoiceGate 顶替服务。
     */
    @Test fun enablingLearningInTheWindowsEntryReachesAnAnswerableQuestion() {
        println("WINDOWS_LEARNING_HOST=${System.getProperty("os.name")}")
        val root = Files.createTempDirectory("coach-win-learning-entry-")
        val source = root.resolve("synthetic-astronomy.md")
        Files.writeString(source, """
            # Synthetic astronomy

            Orbit is the path of an object around another body. An orbit can be circular or elliptical.

            Gravity is the attraction between bodies with mass. Gravity keeps an orbiting body near its star.
        """.trimIndent())
        val directory = root.resolve("library")
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            val controller = TextbookController(TextbookLibrary(directory, streamPdfExtras = true, lazyPdfExtras = true))
            val gate = generatedChoiceGate(root)
            setContent { WindowsTextbookHost(controller, answerExplain = gate) { source } }
            waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("upload-textbook") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            waitUntil(timeoutMillis = 20_000) {
                val ui = controller.state.value
                !ui.busy && ui.screen == BookScreen.READER && ui.prompt == null
            }
            runBlocking { controller.learn(LearningPath.QUESTION_FIRST) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("textbook-learning").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            // Old runCoach's Windows branch reached only a reading lesson with mode NONE.
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("choice-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("choice-0").assertIsDisplayed()
            val library = TextbookLibrary(directory)
            val book = library.book(library.catalog().single().id)
            assertTrue(book.learning)
            assertEquals(QuestionMode.CHOICE, book.questionMode)
            val lesson = book.lessons[book.lessonIndex]
            val question = assertNotNull(gate.practices["${book.id}:${lesson.id}"]).practice
            // 出题依据本节第一段正文（Orbit 那段），题目回到同一段可核对。
            assertEquals(generatedChoiceBlock(lesson).text, question.reference)
            assertTrue(question.reference.startsWith("Orbit"))
            assertTrue(question.options.size >= 2)
            assertEquals(1, question.options.count { it == question.answer })
            onNodeWithText(question.prompt).assertIsDisplayed()
            capture("question")
            onNodeWithTag("choice-${question.options.indexOf(question.answer)}").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("与本题答案一致。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("原文：${question.reference}").assertDoesNotExist()
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("reader-text").assertTextContains(question.reference, substring = true)
            onNodeWithTag("immersive-back").assertTextContains("返回学习", substring = true).performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("与本题答案一致。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("原文：${question.reference}").assertDoesNotExist()
            val saved = gate.answerFor(book, lesson)
            assertEquals(question.answer, saved.text)
            assertTrue(saved.revealed)
            capture("feedback")
            println("WINDOWS_LEARNING_TRACE upload:synthetic-md -> reader -> learn(QUESTION_FIRST) -> generated-choice:answerable -> explicit-source -> return:same-answer-in-window")
        }
    }

    private fun ComposeUiTest.capture(name: String) {
        val directory = Path.of("build/screens/windows-learning-entry").toFile().also { it.mkdirs() }
        val output = directory.resolve("$name.png")
        assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(), "png", output))
        val image = assertNotNull(ImageIO.read(output))
        assertEquals(1100, image.width)
        assertEquals(800, image.height)
        println("WINDOWS_LEARNING_CAPTURE=${output.name} width=${image.width} height=${image.height}")
    }
}
