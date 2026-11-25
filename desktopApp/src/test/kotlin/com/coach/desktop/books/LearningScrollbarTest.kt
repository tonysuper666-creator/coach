package com.coach.desktop.books

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.theme.CoachColors
import com.coach.ui.theme.CoachTheme
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1659 第 8 项（B9）：做题和六题小结共用学习页那个滚动区。一屏放不下时右侧要有一道看得见的
 * V7 细滚动条——滑块比轨道短，读者就知道下面还有卡；拖到底，最后的操作（选项 D、「接着做题」）整颗露出来。
 * 条子落在正文右侧 24dp 的内边距里，不压住卡片；一屏放得下的短页不多这一条。
 */
@OptIn(ExperimentalTestApi::class)
class LearningScrollbarTest {
    @Test fun longPracticeAndRecapPagesCarryAThinBarAndScrollToTheLastActionInBothThemes() {
        for (dark in listOf(false, true)) withLearning(dark, 520f) { dark, _, practice ->
            // 做题页：题干、四个选项一屏放不下。
            val quizBar = bar()
            val option = onNodeWithTag("choice-0").fetchSemanticsNode().boundsInRoot
            assertTrue(option.right <= quizBar.left + 1f, "滚动条不压住选项：option=${option.right} bar=${quizBar.left}（dark=$dark）")
            val quizThumb = thumb(quizBar, dark)
            assertTrue(quizThumb.last - quizThumb.first + 1 < quizBar.height - 20f,
                "做题页滑块应比轨道短：thumb=$quizThumb bar=${quizBar.height}（dark=$dark）")
            dragToBottom(dark)
            val movedThumb = thumb(quizBar, dark)
            assertTrue(movedThumb.first > quizThumb.first, "拖到底后滑块下移：before=$quizThumb after=$movedThumb（dark=$dark）")
            val last = onNodeWithTag("choice-3").fetchSemanticsNode().boundsInRoot
            assertTrue(last.height > 0f && last.bottom <= quizBar.bottom + 1f,
                "拖到底后选项 D 应整颗看得见：bounds=$last bar=${quizBar.bottom}（dark=$dark）")
            assertEquals(quizBar, bar(), "滚动条本身不挪位（dark=$dark）")
            capture("${theme(dark)}-practice-bottom", 520)
            scrollToTop()
            capture("${theme(dark)}-practice", 520)

            // 答第六题：两张小结同屏，比做题页更长；图从最上面那张卡的顶边取起，不从半句话开始。
            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("book-knowledge-recap").performScrollTo()
            waitForIdle()
            val recapBar = bar()
            val recap = onNodeWithTag("book-knowledge-recap").fetchSemanticsNode().boundsInRoot
            assertTrue(recap.height > 0f && recap.right <= recapBar.left + 1f,
                "滚动条不压住小结卡：card=${recap.right} bar=${recapBar.left}（dark=$dark）")
            assertTrue(thumb(recapBar, dark).last - thumb(recapBar, dark).first + 1 < recapBar.height - 20f,
                "小结页滑块应比轨道短（dark=$dark）")
            cardTop("book-summary-card")
            capture("${theme(dark)}-recap", 520)
            dragToBottom(dark)
            val keepGoing = onNodeWithTag("book-knowledge-recap-continue").fetchSemanticsNode().boundsInRoot
            assertTrue(keepGoing.height > 0f && keepGoing.bottom <= recapBar.bottom + 1f,
                "拖到底后「接着做题」应整颗看得见：bounds=$keepGoing bar=${recapBar.bottom}（dark=$dark）")
            capture("${theme(dark)}-recap-bottom", 520)
        }
    }

    @Test fun aPageThatFitsHasNoScrollbar() = withLearning(false, 1_400f) { dark, _, _ ->
        assertEquals(0, onAllNodesWithTag("learning-scrollbar").fetchSemanticsNodes().size,
            "一屏放得下就不该多出一条滚动条（dark=$dark）")
    }

    private fun ComposeUiTest.bar() = onNodeWithTag("learning-scrollbar").fetchSemanticsNode().boundsInRoot

    /** 沿轨道中线数出滑块占的那一段：和纸面底色不同的行。 */
    private fun ComposeUiTest.thumb(bar: androidx.compose.ui.geometry.Rect, dark: Boolean): IntRange {
        val pixels = onRoot().captureToImage().toPixelMap()
        val x = bar.center.x.toInt()
        val paper = colors(dark).surface1
        var first = -1
        var last = -1
        for (y in bar.top.toInt() until bar.bottom.toInt()) {
            if (pixels[x, y] != paper) { if (first < 0) first = y; last = y }
        }
        assertTrue(first >= 0, "轨道上应当看得到滑块：bar=$bar")
        return first..last
    }

    private fun ComposeUiTest.dragToBottom(dark: Boolean) {
        // 按住滑块本身往下拖：页面滚到哪儿，滑块就在哪儿，不能假定它还在轨道顶端。
        val bar = bar()
        val thumb = thumb(bar, dark)
        val grabY = (thumb.first + thumb.last) / 2f - bar.top
        onNodeWithTag("learning-scrollbar").performMouseInput {
            moveTo(Offset(width / 2f, grabY))
            press()
            moveTo(Offset(width / 2f, height * 2f), 300)
            release()
        }
        waitForIdle()
    }

    /** 做题页的卡从标题开始拍：整页推回最上面。 */
    private fun ComposeUiTest.scrollToTop() {
        onNodeWithTag("textbook-learning").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        waitForIdle()
    }

    /** 把某张卡的顶边对齐滚动区顶边：图从卡片顶部取起，不从半句话开始（B9）。 */
    private fun ComposeUiTest.cardTop(tag: String) {
        // 卡在屏外时边界是空的，算不出该滚多少；先把它滚进视野，再对齐顶边。
        onNodeWithTag(tag).performScrollTo()
        waitForIdle()
        val card = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        val view = onNodeWithTag("textbook-learning").fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag("textbook-learning").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, card - view) }
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String, height: Int) {
        val dir = Path.of("build/screens/learning-scroll").toFile().also { it.mkdirs() }
        val file = dir.resolve("$name.png")
        val root = onAllNodesWithTag("windows-textbooks").fetchSemanticsNodes()
        val node = if (root.isNotEmpty()) onNodeWithTag("windows-textbooks") else onRoot()
        assertTrue(ImageIO.write(node.captureToImage().toAwtImage(), "png", file))
        val image = assertNotNull(ImageIO.read(file))
        assertEquals(1100, image.width); assertEquals(height, image.height)
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private fun colors(dark: Boolean) = if (dark) CoachColors.dark() else CoachColors.light()

    /** 导入六节样书，走到第六节还没作答：做题页一屏放不下，答完就是两张小结同屏。 */
    private fun withLearning(dark: Boolean, height: Float, body: ComposeUiTest.(Boolean, TextbookController, Practice) -> Unit) {
        val root = Files.createTempDirectory("coach-learning-scroll-")
        val directory = root.resolve("library")
        val file = knowledgeRecapTextbook(root)
        val controller = TextbookController(TextbookLibrary(directory))
        // TON-1784 retired the local cloze generator. Exercise an existing learning session with service-generated questions.
        runBlocking {
            controller.importBook(file)
            controller.chooseLearningPath(LearningPath.QUESTION_FIRST)
            controller.enableLearning()
        }
        val gate = AnswerExplain(ExplainConsent(directory), load = {}, ready = { true }, paired = { true },
            explain = { _, _ -> error("Generated questions carry their explanation") },
            ask = { block, _ ->
                ExplainResult.Done(Explanation(
                    "题干：合成练习的标记是什么？ 选项A：甲 选项B：乙 选项C：丙 选项D：丁 答案：A 解析：标记为甲。【S1】",
                    listOf(Citation("S1", block.id, block.page)), 1L,
                ))
            })
        runSkikoComposeUiTest(size = Size(1100f, height), density = Density(1f)) {
            setContent { CoachTheme(dark) { TextbookWorkspace(controller, loadOnMount = false, answerExplain = gate) { file } } }
            ready(controller) { it.screen == BookScreen.LEARNING && it.questionsOpen }
            val book = checkNotNull(controller.state.value.book)
            repeat(5) { index ->
                val key = "${book.id}:${book.lessons[index].id}"
                waitUntil(timeoutMillis = 20_000) { gate.practices[key] != null }
                onNodeWithTag("choice-0").assertTextContains("甲", substring = true).performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { gate.answers[key]?.revealed == true }
                onNodeWithTag("next-lesson").performScrollTo().performClick()
                ready(controller) { it.book?.lessonIndex == index + 1 }
            }
            val lastKey = "${book.id}:${book.lessons[5].id}"
            waitUntil(timeoutMillis = 20_000) { gate.practices[lastKey] != null }
            body(dark, controller, checkNotNull(gate.practices[lastKey]).practice)
        }
        root.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }
}
