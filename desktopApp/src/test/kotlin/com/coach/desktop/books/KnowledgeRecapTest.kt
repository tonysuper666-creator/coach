package com.coach.desktop.books

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.domain.QuestionLadder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

internal fun knowledgeRecapTextbook(root: Path): Path {
    Files.createDirectories(root)
    val file = root.resolve("knowledge-recap.md")
    val sections = listOf(
        "细胞" to "细胞是生物体结构和功能的基本单位。细胞里有细胞核。",
        "细胞膜" to "细胞膜是包围细胞的一层薄膜。它控制物质进出。",
        "细胞核" to "细胞核是细胞里储存遗传信息的结构。它里面有染色体。",
        "染色体" to "染色体是遗传物质的载体。同一种生物的数目相对稳定。",
        "线粒体" to "线粒体是细胞里提供能量的结构。它有自己的遗传物质。",
        "核糖体" to "核糖体是合成蛋白质的场所。它附着在内质网上。",
    )
    Files.writeString(file, sections.mapIndexed { index, (title, body) ->
        "## 第${index + 1}节 $title\n\n$body"
    }.joinToString("\n\n"))
    return file
}

@OptIn(ExperimentalTestApi::class)
class KnowledgeRecapTest {
    /**
     * 教材这一节一道题，六节才凑够一次小结；同一节重答不重复累计，继续后游标推进但答案还在。
     * 这里只走控制器和库，不碰 Compose，专门证明「累计」和「保留」这两件事。
     */
    @Test fun sixDistinctLessonsMakeOneRecapAndRepeatingALessonDoesNotCountTwice() = runBlocking {
        val root = Files.createTempDirectory("coach-recap-library-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(knowledgeRecapTextbook(root))
        val c = TextbookController(library)
        c.load(); c.learn()
        assertEquals(BookPrompt.START, c.state.value.prompt)
        c.chooseLearningPath(LearningPath.QUESTION_FIRST); c.enableLearning()
        assertEquals(BookScreen.LEARNING, c.state.value.screen)

        val answers = mutableMapOf<String, String>()
        repeat(6) { index ->
            c.lesson(index)
            val current = c.state.value.book!!
            val lesson = current.lessons[index]
            val practice = StudyCoachCourse.practice(current, lesson, QuestionMode.CHOICE)!!
            c.answer(PracticeAnswer(practice.answer, revealed = true))
            answers[lesson.id] = practice.answer
            val session = library.ladder(book.id)
            if (index < 5) {
                assertFalse(session.recapOpen, "第 ${index + 1} 题还不该出小结")
            } else {
                assertTrue(session.recapOpen, "第六题后该出小结")
                assertEquals(6, session.responses.size)
                val lines = QuestionLadder.recap(session)
                assertTrue(lines.size in 3..5, "小结是 3–5 句，实际 ${lines.size}")
                assertTrue(lines.all { "：" in it }, "每句都带本节出处")
                assertFalse(lines.first().contains("你选了"), "小结不是作答摘要")
            }
        }

        // 重答同一节不重复累计。
        c.lesson(0)
        val first = c.state.value.book!!.lessons[0]
        c.answer(PracticeAnswer("细胞", revealed = true))
        assertEquals(6, library.ladder(book.id).responses.size)

        // 继续：游标推进，小结关掉，作答和走到哪一节都不动。
        c.recapContinue()
        val after = library.ladder(book.id)
        assertFalse(after.recapOpen); assertEquals(6, after.recapThrough)
        assertEquals(BookScreen.LEARNING, c.state.value.screen)
        assertEquals(0, c.state.value.book!!.lessonIndex)
        for ((lessonId, answer) in answers + (first.id to "细胞")) {
            assertEquals(PracticeAnswer(answer, revealed = true), library.answer(book.id, lessonId, QuestionMode.CHOICE))
        }
        assertNull(c.state.value.error)
    }

    /**
     * 简答是另一种交卷方式：明确自检过就算做过一题，点「我已理解」也要累计，只有「还需复习」才进复习。
     * 这条回归覆盖的就是「六节都点我已理解，重开本机库却还是 0 条」那个缺陷。
     */
    @Test fun shortAnswerCountsOnceAfterASelfCheckWhetherTheReaderUnderstoodItOrNot() = runBlocking {
        val root = Files.createTempDirectory("coach-recap-short-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(knowledgeRecapTextbook(root))
        val c = TextbookController(library)
        c.load(); c.learn()
        c.chooseLearningPath(LearningPath.QUESTION_FIRST); c.enableLearning()
        c.mode(QuestionMode.SHORT_ANSWER)
        assertEquals(QuestionMode.SHORT_ANSWER, c.state.value.book!!.questionMode)

        repeat(6) { index ->
            c.lesson(index)
            c.answer(PracticeAnswer("第${index + 1}节的回答", revealed = true, understood = true))
            val session = library.ladder(book.id)
            assertEquals(index + 1, session.responses.size, "「我已理解」第 ${index + 1} 题应累计")
            if (index < 5) assertFalse(session.recapOpen) else {
                assertTrue(session.recapOpen, "简答第六题后该出小结")
                val lines = QuestionLadder.recap(session)
                assertTrue(lines.size in 3..5, "小结是 3–5 句，实际 ${lines.size}")
                assertTrue(lines.all { "：" in it }, "每句都带本节出处")
            }
        }
        assertTrue(library.reviews().isEmpty(), "「我已理解」不该加入复习")

        // 重答同一节不重复累计，重开本机库仍是 6 条、小结游标还停着。
        c.lesson(0)
        c.answer(PracticeAnswer("第1节的回答", revealed = true, understood = true))
        val reopened = TextbookLibrary(root.resolve("library"))
        val session = reopened.ladder(book.id)
        assertEquals(6, session.responses.size)
        assertTrue(session.recapOpen)
        assertEquals(PracticeAnswer("第1节的回答", revealed = true, understood = true),
            reopened.answer(book.id, book.lessons[0].id, QuestionMode.SHORT_ANSWER))

        // 对照组：同样六节点「还需复习」，题梯一样累计 6 条，但六节都进了复习。
        val controlRoot = Files.createTempDirectory("coach-recap-short-review-")
        val controlLibrary = TextbookLibrary(controlRoot.resolve("library"))
        val controlBook = controlLibrary.importBook(knowledgeRecapTextbook(controlRoot))
        val control = TextbookController(controlLibrary)
        control.load(); control.learn()
        control.chooseLearningPath(LearningPath.QUESTION_FIRST); control.enableLearning()
        control.mode(QuestionMode.SHORT_ANSWER)
        repeat(6) { index ->
            control.lesson(index)
            control.answer(PracticeAnswer("第${index + 1}节的回答", revealed = true, understood = false))
            assertEquals(index + 1, controlLibrary.ladder(controlBook.id).responses.size)
        }
        assertTrue(controlLibrary.ladder(controlBook.id).recapOpen)
        assertEquals(6, controlLibrary.reviews().size, "「还需复习」六节都应进复习")
    }

    @Test fun recapCardShowsOnTheSixthAnswerAndContinuingKeepsTheAnswerAndTheWalk() {
        val root = Files.createTempDirectory("coach-recap-card-")
        val directory = root.resolve("library")
        val file = knowledgeRecapTextbook(root)
        val library = TextbookLibrary(directory)
        var controller by mutableStateOf(TextbookController(library))
        val gate = AnswerExplain(ExplainConsent(directory), load = {}, ready = { true }, paired = { false },
            explain = { _, _ -> ExplainResult.Refused("测试里不配对") })
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { key(controller) { TextbookWorkspace(controller, answerExplain = gate) { file } } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.questionsOpen }
            val book = controller.state.value.book!!
            assertEquals(6, book.lessons.size)
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)

            var last = ""
            for (index in 0 until 6) {
                val current = controller.state.value.book!!
                val practice = StudyCoachCourse.practice(current, current.lessons[index], QuestionMode.CHOICE)!!
                last = practice.answer
                onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
                ready(controller) { it.answer.revealed }
                if (index < 5) {
                    onNodeWithTag("book-knowledge-recap").assertDoesNotExist()
                    onNodeWithTag("next-lesson").performScrollTo().performClick()
                    ready(controller) { it.book?.lessonIndex == index + 1 }
                }
            }
            onNodeWithTag("book-knowledge-recap").performScrollTo().assertExists()
            onNodeWithTag("book-knowledge-recap-line-0").assertExists()
            val title = book.lessons[1].title
            onNodeWithTag("book-knowledge-recap-line-0").assertTextContains(title, substring = true)
            assertTrue((0..4).count { onAllNodesWithTag("book-knowledge-recap-line-$it").fetchSemanticsNodes().isNotEmpty() } in 3..5)
            capture("sixth-answer-recap")

            // 继续后：卡收起，原答还在，节没变。
            onNodeWithTag("book-knowledge-recap-continue").performScrollTo().performClick()
            ready(controller) { !it.ladder.recapOpen }
            onNodeWithTag("book-knowledge-recap").assertDoesNotExist()
            assertEquals(last, controller.state.value.answer.text)
            assertTrue(controller.state.value.answer.revealed)
            assertEquals(5, controller.state.value.book!!.lessonIndex)
            assertEquals(6, library.ladder(book.id).recapThrough)
            assertEquals(PracticeAnswer(last, revealed = true), library.answer(book.id, book.lessons[5].id, QuestionMode.CHOICE))
            capture("after-recap-continue")

            // 重开窗口：走法、节、原来那句答案、关掉的小结都照旧。
            runOnIdle { controller = TextbookController(TextbookLibrary(directory)) }
            ready(controller) { it.book?.id == book.id && it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").performScrollTo().performClick()
            ready(controller) { it.screen == BookScreen.LEARNING }
            assertEquals(LearningPath.QUESTION_FIRST, controller.state.value.learningPath)
            assertEquals(5, controller.state.value.book!!.lessonIndex)
            assertEquals(PracticeAnswer(last, revealed = true), controller.state.value.answer)
            assertEquals(6, controller.state.value.ladder.recapThrough)
            assertFalse(controller.state.value.ladder.recapOpen)
            onNodeWithTag("book-knowledge-recap").assertDoesNotExist()
            onNodeWithTag("book-summary-card").assertExists()
            capture("reopened-after-recap")
        }
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/knowledge-recap").toFile().also { it.mkdirs() }
        val file = dir.resolve("$name.png")
        val root = onAllNodesWithTag("windows-textbooks").fetchSemanticsNodes()
        val node = if (root.isNotEmpty()) onNodeWithTag("windows-textbooks") else onRoot()
        assertTrue(ImageIO.write(node.captureToImage().toAwtImage(), "png", file))
        val image = assertNotNull(ImageIO.read(file))
        assertEquals(1100, image.width); assertEquals(800, image.height)
    }
}
