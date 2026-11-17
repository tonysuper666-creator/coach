package com.coach.desktop.books

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import javax.imageio.ImageIO
import java.io.File
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class GeneratedLessonPracticeTest {
    private val text = "In a synthetic system, planets follow an orbit around a star. The star supplies light to the planets."
    private val book = Textbook("synthetic", "Synthetic astronomy", "txt", listOf(BookPage(1, text)))
    private val chosen get() = book.lessons.first().blocks.first { !it.heading }
    private fun reply(block: SourceBlock = chosen) = Explanation(
        "题干：行星围绕什么运行？ 选项A：恒星 选项B：自身发光的行星 选项C：固定不动的卫星 选项D：不沿轨道运行 答案：A 解析：这里的关系是行星沿轨道绕恒星运行，恒星提供光。【c1】",
        listOf(Citation("c1", block.id, block.page)), 1)

    @Test fun acceptsOnlyACompleteQuestionWithTheActualSentSource() {
        assertTrue(BookPassageQuestion.accepts(LessonPracticeWire.question))
        val parsed = assertNotNull(LessonPracticeWire.parse(book, chosen, reply()))
        assertEquals("恒星", parsed.practice.answer)
        assertEquals(4, parsed.practice.options.size)
        assertEquals(chosen.text, parsed.practice.reference)
        assertEquals(listOf(BookPassage.from(book, chosen)), parsed.explanation.sources)
        assertFalse(parsed.explanation.text.contains("答案："))
        assertNull(LessonPracticeWire.parse(book, chosen, reply().copy(citations = emptyList())))
        assertNull(LessonPracticeWire.parse(book, chosen, reply().copy(citations = listOf(Citation("c1", "other:p1:0", 1)))))
        assertNull(LessonPracticeWire.parse(book, chosen.copy(start = 1), reply(chosen.copy(start = 1))))
    }

    @Test fun rejectsMissingAnswerDuplicateChoicesAndUnstructuredReplies() {
        for (bad in listOf(reply().text.replace("答案：A", "答案：E"), reply().text.replace("选项B：自身发光的行星", "选项B：恒星"),
            "这段讲的是轨道。【c1】", reply().text.replace("选项D：不沿轨道运行", ""))) {
            assertNull(LessonPracticeWire.parse(book, chosen, reply().copy(text = bad)))
        }
    }

    @Test fun asksAfterConsentRevealsAfterAnswerAndNeverSavesGeneratedAnswers() {
        val root = Files.createTempDirectory("generated-practice-")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runBlocking {
            controller.importBook(learningPathTextbook(root, "Synthetic orbits"))
            controller.chooseLearningPath(LearningPath.QUESTION_FIRST)
            controller.enableLearning()
        }
        val consent = ExplainConsent(root.resolve("consent"))
        var calls = 0
        val gate = AnswerExplain(consent, {}, { true }, { true }, { _, _ -> error("legacy explain must not run") },
            ask = { block, question ->
                assertEquals(LessonPracticeWire.question, question)
                calls++
                ExplainResult.Done(reply(block))
            })
        runSkikoComposeUiTest(size = Size(1100f, 900f), density = Density(1f)) {
            setContent { TextbookWorkspace(controller, loadOnMount = false, answerExplain = gate) { null } }
            waitForIdle()
            assertEquals(0, calls)
            onNodeWithTag("practice-allow").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("generated-practice").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-explanation-text").assertDoesNotExist()
            onNodeWithTag("choice-1").performScrollTo().performClick()
            onNodeWithTag("book-explanation-text").assertExists()
            onNodeWithTag("book-summary-detail").assertTextContains("本题答案是", substring = true)
            onNodeWithTag("book-summary-detail").assertTextContains("只保留在这次练习", substring = true)
            assertFalse(controller.state.value.answer.revealed)
            assertTrue(controller.state.value.reviews.isEmpty())
            onNodeWithTag("next-lesson").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book?.lessonIndex == 1 }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("generated-practice").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("previous-lesson").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book?.lessonIndex == 0 }
            onNodeWithTag("book-summary-card").assertExists()
            assertEquals(2, calls) // first lesson is cached; only the next lesson is new.
        }
        root.toFile().deleteRecursively()
    }

    @Test fun rendersSyntheticBeforeAndAfterInBothThemes() {
        for (dark in listOf(false, true)) for (generated in listOf(false, true)) {
            val root = Files.createTempDirectory("synthetic-practice-visual-")
            try {
                val controller = TextbookController(TextbookLibrary(root.resolve("library")))
                runBlocking {
                    controller.importBook(learningPathTextbook(root, "Synthetic astronomy"))
                    controller.chooseLearningPath(LearningPath.QUESTION_FIRST)
                    controller.enableLearning()
                }
                val consent = ExplainConsent(root.resolve("consent"))
                consent.allow(checkNotNull(controller.state.value.book).id)
                val gate = AnswerExplain(consent, {}, { true }, { true }, { block, _ -> ExplainResult.Done(reply(block)) },
                    ask = if (generated) ({ block, _ -> ExplainResult.Done(reply(block)) }) else null)
                runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
                    setContent {
                        com.coach.ui.theme.CoachTheme(darkTheme = dark) {
                            TextbookWorkspace(controller, loadOnMount = false, answerExplain = gate) { null }
                        }
                    }
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("choice-0").fetchSemanticsNodes().isNotEmpty() }
                    val dir = File("build/screens/generated-practice").also { it.mkdirs() }
                    val name = "${if (generated) "after" else "before"}-${if (dark) "dark" else "light"}"
                    ImageIO.write(onNodeWithTag("windows-textbooks").captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
                    onNodeWithTag("choice-0").performScrollTo().performClick()
                    onNodeWithTag("book-summary-card").performScrollTo()
                    waitForIdle()
                    ImageIO.write(onNodeWithTag("windows-textbooks").captureToImage().toAwtImage(), "png", File(dir, "$name-answer.png"))
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

}
