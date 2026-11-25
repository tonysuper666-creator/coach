package com.coach.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import com.coach.ui.theme.CoachTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

/**
 * The passage composer must offer exactly the range the provider accepts, so the reader is never
 * allowed to type a question that can only come back as a provider failure.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class WindowsPassageQuestionLimitTest {
    /** Records what the composer actually handed over; it never pretends to be an answer. */
    private class Recorder : BookLearningSupport {
        val questions = mutableListOf<String>()
        val passages = mutableListOf<BookPassage>()

        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("合成回执：本用例不评价讲解。")

        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            // Same guard OwnedBookLearningProvider.question() applies before any transport runs.
            require(BookPassageQuestion.accepts(request.question)) { "Outside the provider's question bound" }
            questions += request.question
            passages += request.passage
            return BookLearningReply.Unavailable("合成回执：只记录请求，尚未接通方案商。")
        }
    }

    /** 回答里指出这一次问的那一段；第一问挂着不返回，读者正好可以在等回答的空档里再去点别的段。 */
    private class Citing : BookLearningSupport {
        val questions = mutableListOf<String>()
        val passages = mutableListOf<BookPassage>()
        val answer = CompletableDeferred<Unit>()

        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("合成回执：本用例不评价讲解。")

        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            require(BookPassageQuestion.accepts(request.question)) { "Outside the provider's question bound" }
            questions += request.question
            passages += request.passage
            answer.await()
            return BookLearningReply.Text("合成回执：这一段的问题已经收到。", listOf(request.passage))
        }
    }

    @Test fun blankAndOverBoundQuestionsStayOutOfTheProvider() {
        val root = Files.createTempDirectory("coach-passage-limit-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Recorder()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            onNodeWithTag("book-passage-length").assertTextEquals("0 / 500")
            onNodeWithTag("book-passage-send").assertIsNotEnabled()

            onNodeWithTag("book-passage-question").performTextInput("x".repeat(501))
            onNodeWithTag("book-passage-length").assertTextContains("已超过 500 字", substring = true)
            onNodeWithTag("book-passage-length").assertTextContains("删掉 1 字", substring = true)
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            onNodeWithTag("book-passage-unavailable").assertDoesNotExist()
            assertEquals(0, support.questions.size)

            // The old composer stopped the reader at 1000 characters by silently dropping the rest.
            onNodeWithTag("book-passage-question").performTextReplacement("x".repeat(1200))
            assertEquals(1200, typedQuestion().length)
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            assertEquals(0, support.questions.size)

            // Bound counts UTF-16 code units, exactly like the provider's String.length check.
            val clef = "\uD834\uDD1E"
            onNodeWithTag("book-passage-question").performTextReplacement(clef.repeat(250))
            onNodeWithTag("book-passage-length").assertTextEquals("500 / 500")
            onNodeWithTag("book-passage-send").assertIsEnabled()
            onNodeWithTag("book-passage-question").performTextReplacement(clef.repeat(251))
            onNodeWithTag("book-passage-length").assertTextContains("删掉 2 字", substring = true)
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            assertEquals(0, support.questions.size)
        }
    }

    @Test fun fiveHundredCodeUnitsReachTheProviderAndTheDraftSurvives() {
        val root = Files.createTempDirectory("coach-passage-bound-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Recorder()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            val question = "问".repeat(500)
            onNodeWithTag("book-passage-question").performTextInput(question)
            onNodeWithTag("book-passage-length").assertTextEquals("500 / 500")
            onNodeWithTag("book-passage-send").assertIsEnabled()
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            assertEquals(question, support.questions.single())

            // A failed reply keeps the typed question, the chosen passage and the page it came from.
            onNodeWithTag("book-passage-unavailable").assertExists()
            assertEquals(question, typedQuestion())
            val block = controller.state.value.book!!.lessons[0].blocks.first { !it.heading }
            onNodeWithTag("book-selected-passage").assertTextEquals(block.text)
            assertEquals(1, controller.state.value.page)

            onNodeWithTag("book-passage-question").performTextReplacement("问".repeat(501))
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            onNodeWithTag("book-passage-length").assertTextContains("删掉 1 字", substring = true)
            assertEquals(1, support.questions.size)
        }
    }

    /** Holds the first question in flight, so a second Enter can be tried while the first is unanswered. */
    private class Holding : BookLearningSupport {
        val questions = mutableListOf<String>()
        val passages = mutableListOf<BookPassage>()
        val answer = CompletableDeferred<BookLearningReply>()
        var consent = true

        override fun consentRequired(book: String) = consent
        override fun allow(book: String) { consent = false }
        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("合成回执：本用例不评价讲解。")

        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            require(BookPassageQuestion.accepts(request.question)) { "Outside the provider's question bound" }
            questions += request.question
            passages += request.passage
            return answer.await()
        }
    }

    @Test fun enterSendsOnceThroughTheSendEntryWhileTheAnswerIsInFlight() {
        val root = Files.createTempDirectory("coach-passage-enter-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Holding().apply { consent = false }
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            onNodeWithTag("book-passage-question").performTextInput("这一页在讲什么")
            repeat(3) { onNodeWithTag("book-passage-question").performKeyInput { pressKey(Key.Enter) } }
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            waitForIdle()
            // Three Enters, one request: the second and third land while the first is still unanswered, exactly
            // like clicking the disabled 发送 button, and none of them leaves a newline in the question.
            assertEquals(listOf("这一页在讲什么"), support.questions)
            assertEquals("这一页在讲什么", typedQuestion())
            onNodeWithTag("book-passage-unavailable").assertDoesNotExist()

            support.answer.complete(BookLearningReply.Unavailable("合成回执：只记录请求，尚未接通方案商。"))
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, support.questions.size)
            assertEquals("这一页在讲什么", typedQuestion())
        }
    }

    @Test fun shiftEnterKeepsAMultiLineQuestionAndEnterSendsIt() {
        val root = Files.createTempDirectory("coach-passage-shift-enter-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Holding().apply { consent = false }
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            onNodeWithTag("book-passage-question").performTextInput("第一行")
            onNodeWithTag("book-passage-question").performKeyInput {
                keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft)
            }
            assertEquals("第一行\n", typedQuestion())
            onNodeWithTag("book-passage-question").performTextInput("第二行")
            assertEquals(0, support.questions.size)

            onNodeWithTag("book-passage-question").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            // Shift+Enter is a line break; the plain Enter sends that same two-line question unchanged.
            assertEquals("第一行\n第二行", support.questions.single())
        }
    }

    /**
     * 输入法里候选还没定时，那一下 Enter 是在确认候选：既不能把半截问题发出去，也不能往问题里插换行。
     * 候选落定、过了确认宽限之后再按 Enter，发出去的是汉字，不是拼音。
     */
    @Test fun enterThatConfirmsAnImeCandidateSendsNothingAndAddsNoLineBreak() {
        val root = Files.createTempDirectory("coach-passage-ime-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Recorder()
        var request: PlatformTextInputMethodRequest? = null
        val interceptor = PlatformTextInputInterceptor { input, next ->
            request = input
            try { next.startInputMethod(input) } finally { request = null }
        }
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support) { content -> InterceptPlatformTextInput(interceptor) { content() } }
            onNodeWithTag("book-passage-question").performClick().performTextReplacement("这一页在讲")
            waitUntil(timeoutMillis = 20_000) { request != null }
            // 走真的平台输入法：候选「什么」在输入框里，composition 还没落定。
            runOnIdle { checkNotNull(request).onEditCommand(listOf(SetComposingTextCommand("什么", 1))) }
            waitForIdle()
            assertNotNull(request?.value?.invoke()?.composition, "候选应当还停在输入框里")
            assertEquals("这一页在讲什么", typedQuestion())

            onNodeWithTag("book-passage-question").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(0, support.questions.size, "确认候选的那一下 Enter 不能发出去")
            assertEquals("这一页在讲什么", typedQuestion(), "确认候选的 Enter 不能往问题里插换行")

            // 候选落定，等过 60 ms 的确认宽限，再按一次 Enter 才发。
            runOnIdle { checkNotNull(request).onEditCommand(listOf(FinishComposingTextCommand())) }
            waitForIdle()
            assertNull(request?.value?.invoke()?.composition)
            Thread.sleep(150)
            onNodeWithTag("book-passage-question").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            assertEquals("这一页在讲什么", support.questions.single())
        }
    }

    @Test fun enterOverTheBoundSendsNothingAndAddsNoLineBreak() {
        val root = Files.createTempDirectory("coach-passage-enter-limit-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Holding().apply { consent = false }
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            onNodeWithTag("book-passage-question").performTextInput("x".repeat(501))
            onNodeWithTag("book-passage-question").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(0, support.questions.size)
            assertEquals(501, typedQuestion().length)
            onNodeWithTag("book-passage-length").assertTextContains("删掉 1 字", substring = true)
        }
    }

    @Test fun enterAsksForThePerBookConsentBeforeSending() {
        val root = Files.createTempDirectory("coach-passage-enter-consent-")
        val source = learningPathTextbook(root, "Synthetic astronomy")
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Holding()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openComposer(controller, source, support)
            onNodeWithTag("book-passage-question").performTextInput("这一页在讲什么")
            onNodeWithTag("book-passage-question").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag("ask-consent").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, support.questions.size)
            onNodeWithTag("ask-consent-allow").performClick()
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            assertEquals(listOf("这一页在讲什么"), support.questions)
            assertFalse(support.consent)
        }
    }

    /** 选段列表：一句提示，下面每段一行，行首是自己的段号。 */
    @Test fun everyPassageIsANumberedRowUnderTheChooseHint() {
        val root = Files.createTempDirectory("coach-passage-rows-")
        val source = pickerTextbook(root)
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Recorder()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openPicker(controller, source, support)
            onNodeWithTag("book-passage-hint").assertTextEquals("点一段，再问一句")
            val book = controller.state.value.book!!
            val blocks = StudyCoachCourse.blocks(book.id, listOf(book.pages.first())).filterNot { it.heading }
            check(blocks.size >= 2) { "the picker needs more than one paragraph to show numbering" }
            blocks.forEachIndexed { index, block ->
                // 行首是段号，后面是原文：合并后正好是 [段号, 原文]，不是一整块没有序号的文字。
                onNodeWithTag("ask-paragraph-$index").assertTextEquals("${index + 1}", ReadableText.reflow(block.text))
                onNodeWithTag("ask-paragraph-$index").assertIsNotSelected()
            }
            onNodeWithTag("ask-paragraph-${blocks.size}").assertDoesNotExist()
            onNodeWithTag("book-selected-passage").assertDoesNotExist()
            // 还没点段：问题框在，发送关着，读者得先挑一段。
            onNodeWithTag("book-passage-question").assertExists()
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
        }
    }

    /** 选中一段：留在原来的位置就地高亮，前后段落都看得见，送的仍是这段原文。 */
    @Test fun theChosenPassageStaysInPlaceAndSendsThatParagraph() {
        val root = Files.createTempDirectory("coach-passage-chosen-")
        val source = pickerTextbook(root)
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Citing()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openPicker(controller, source, support)
            val book = controller.state.value.book!!
            val blocks = StudyCoachCourse.blocks(book.id, listOf(book.pages.first())).filterNot { it.heading }
            check(blocks.size >= 3) { "the picker needs a paragraph on both sides of the chosen one" }
            val before = onNodeWithTag("ask-paragraph-1").fetchSemanticsNode().boundsInRoot
            onNodeWithTag("ask-paragraph-1").performClick(); waitForIdle()
            // 列表没有换成单独一张卡：提示、前后两段都还在，选中的那一行上沿一点没动。
            onNodeWithTag("book-passage-hint").assertExists()
            blocks.forEachIndexed { index, block ->
                onNodeWithTag("ask-paragraph-$index").assertExists()
                if (index != 1) {
                    onNodeWithTag("ask-paragraph-$index").assertTextEquals("${index + 1}", ReadableText.reflow(block.text))
                }
            }
            assertEquals(before, onNodeWithTag("ask-paragraph-1").fetchSemanticsNode().boundsInRoot)
            onNodeWithTag("book-selected-passage-number").assertTextEquals("2")
            onNodeWithTag("book-selected-passage").assertTextEquals(ReadableText.reflow(blocks[1].text))

            // 行首段号只属于列表：发出去的问题和引用的原文一字不多。
            onNodeWithTag("book-passage-question").performTextInput("这一段在说什么")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            assertEquals(listOf("这一段在说什么"), support.questions)
            assertEquals(blocks[1].text, support.passages.single().text)

            // 等回答的空档里再去点第 3 段：高亮不能跟着跑。请求的那一段、还亮着的那一段、
            // 点开的原文必须是同一段，否则同页的两段只差一个段号，读者核页码都发现不了。
            assertFalse(hasClickAction("ask-paragraph-0"), "发出去一段之后，前后两行不该还能换选段")
            assertFalse(hasClickAction("ask-paragraph-2"))
            onNodeWithTag("book-selected-passage-number").assertTextEquals("2")
            onNodeWithTag("book-selected-passage").assertTextEquals(ReadableText.reflow(blocks[1].text))

            support.answer.complete(Unit)
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag("book-passage-citation").fetchSemanticsNodes().isNotEmpty()
            }
            // 回答回来以后高亮仍在第 2 段，点「出处」打开的也是第 2 段原文。
            onNodeWithTag("book-selected-passage-number").assertTextEquals("2")
            onNodeWithTag("book-selected-passage").assertTextEquals(ReadableText.reflow(blocks[1].text))
            onNodeWithTag("book-passage-citation").performScrollTo().performClick()
            onNodeWithTag("book-passage-source").assertTextEquals(blocks[1].text)
            assertEquals(1, support.questions.size)
        }
    }

    /** 发送之前可以随便换段：点第 1 段、再点第 3 段，发出去的、还亮着的、出处打开的都是第 3 段。 */
    @Test fun pickingAnotherRowBeforeSendingSendsTheLastOne() {
        val root = Files.createTempDirectory("coach-passage-before-send-")
        val source = pickerTextbook(root)
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Citing()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openPicker(controller, source, support)
            val book = controller.state.value.book!!
            val blocks = StudyCoachCourse.blocks(book.id, listOf(book.pages.first())).filterNot { it.heading }
            check(blocks.size >= 3) { "the picker needs a third paragraph to switch to" }

            onNodeWithTag("ask-paragraph-0").performClick(); waitForIdle()
            onNodeWithTag("book-selected-passage-number").assertTextEquals("1")
            // 还没发出去：换段只是把高亮挪到新点的那一行。
            assertTrue(hasClickAction("ask-paragraph-2"), "发送前另一段应当还能点")
            onNodeWithTag("ask-paragraph-2").performClick(); waitForIdle()
            onNodeWithTag("book-selected-passage-number").assertTextEquals("3")
            onNodeWithTag("book-selected-passage").assertTextEquals(ReadableText.reflow(blocks[2].text))

            onNodeWithTag("book-passage-question").performTextInput("这一段在说什么")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            assertEquals(blocks[2].text, support.passages.single().text)

            support.answer.complete(Unit)
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-citation").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-selected-passage-number").assertTextEquals("3")
            onNodeWithTag("book-passage-citation").performScrollTo().performClick()
            onNodeWithTag("book-passage-source").assertTextEquals(blocks[2].text)
            assertEquals(1, support.questions.size)
        }
    }

    /** 「不允许」没有发出请求，段就没被钉住：读者换一段再问，问的是新选的那段。 */
    @Test fun denyingConsentLeavesThePassageUnlockedForANewPick() {
        val root = Files.createTempDirectory("coach-passage-consent-repick-")
        val source = pickerTextbook(root)
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        val support = Holding()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            openPicker(controller, source, support)
            val book = controller.state.value.book!!
            val blocks = StudyCoachCourse.blocks(book.id, listOf(book.pages.first())).filterNot { it.heading }
            check(blocks.size >= 3) { "the picker needs a third paragraph to switch to" }

            onNodeWithTag("ask-paragraph-0").performClick(); waitForIdle()
            onNodeWithTag("book-passage-question").performTextInput("这一页在讲什么")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("ask-consent").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("ask-consent-deny").performClick(); waitForIdle()
            assertEquals(0, support.questions.size)

            // 没发出去：还能换段，高亮跟着走。
            assertTrue(hasClickAction("ask-paragraph-2"), "「不允许」没发请求，段落不该被锁定")
            onNodeWithTag("ask-paragraph-2").performClick(); waitForIdle()
            onNodeWithTag("book-selected-passage-number").assertTextEquals("3")

            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("ask-consent").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("ask-consent-allow").performClick()
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            waitForIdle()
            assertEquals(listOf("这一页在讲什么"), support.questions)
            assertEquals(blocks[2].text, support.passages.single().text)
            // 真发出去之后才锁：其余两行不再可点。
            assertFalse(hasClickAction("ask-paragraph-0"))
            assertFalse(hasClickAction("ask-paragraph-1"))
        }
    }

    /** 位置认不出时不发请求；换一段那条提示就消失，再发出去的是新选的那段。 */
    @Test fun anUnlocatablePassageIsClearedWhenAnotherRowIsPicked() {
        val root = Files.createTempDirectory("coach-passage-unlocatable-")
        val source = pickerTextbook(root)
        val controller = TextbookController(TextbookLibrary(root.resolve("library")))
        runBlocking { controller.importBook(source) }
        val book = assertNotNull(controller.state.value.book)
        val page = book.pages.first()
        val blocks = StudyCoachCourse.blocks(book.id, listOf(page)).filterNot { it.heading }
        // 起止落在这一页正文之外、id 也对不上任何段落：BookPassage.from 认不出它。
        val stale = SourceBlock("${book.id}:p${page.number}:${page.text.length + 1}", page.number,
            page.text.length + 1, page.text.length + 5, false, "认不出位置的原文")
        val support = Recorder()
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { CoachTheme(darkTheme = false) { BookPassageAsk(book, page, support, initialSelection = stale) {} } }
            waitForIdle()
            onNodeWithTag("book-selected-passage").assertTextEquals("认不出位置的原文")
            onNodeWithTag("book-passage-question").performTextInput("这一段在说什么")
            onNodeWithTag("book-passage-send").performClick(); waitForIdle()
            onNodeWithTag("book-passage-unavailable").assertExists()
            onNodeWithTag("explain-trouble").assertTextEquals("这段原文的位置无法确认，请重新选择。")
            assertEquals(0, support.questions.size)

            // 那条提示说的是上一次的选段：换一段就跟着消失，再发出去的是新选的这段。
            onNodeWithTag("ask-paragraph-0").performClick(); waitForIdle()
            onNodeWithTag("book-passage-unavailable").assertDoesNotExist()
            onNodeWithTag("book-selected-passage").assertTextEquals(ReadableText.reflow(blocks[0].text))
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { support.questions.size == 1 }
            assertEquals(blocks[0].text, support.passages.single().text)
        }
    }

    /**
     * The same composer in a real Windows window, driven by the OS keyboard instead of the test
     * framework's synthetic events: Shift+Enter keeps a line, Enter sends exactly once through the
     * send entry while the answer is still in flight, and both themes are captured as screenshots.
     */
    @Test fun realKeyboardEnterSendsOnceAndShiftEnterKeepsTheLine() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-passage-keyboard-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val file = learningPathTextbook(root, "Synthetic astronomy")
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                val controller = TextbookController(TextbookLibrary(root.resolve("library-$dark")))
                runBlocking {
                    controller.importBook(file)
                    controller.chooseLearningPath(LearningPath.READ)
                    controller.enableLearning()
                }
                val support = KeyboardRecorder()
                val book = assertNotNull(controller.state.value.book)
                val page = book.pages.first()
                // 「ask-paragraph-0」是这一页第一个非标题段落，发送时要带回同一本书的同一段。
                val block = book.lessons.flatMap { it.blocks }.first { !it.heading && it.page == page.number }
                NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
                val window = onEdt { ComposeWindow().apply {
                    title = "LearningCoach · 选段提问键盘验收"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1280, screen.width - 40), minOf(920, screen.height - 40))
                    // #570 去掉了阅读器的「选段提问」入口：选段弹窗直接挂进这扇真窗口（见 openPicker 的说明）。
                    setContent { CoachTheme(darkTheme = dark) { BookPassageAsk(book, page, support) {} } }
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront(); requestFocus()
                } }
                try {
                    awaitWindow("passage composer", window) { windowNode(window, "ask-paragraph-0") != null }
                    windowClick(window, "ask-paragraph-0")
                    val robot = Robot().apply { autoDelay = 60 }
                    val arrivals = WindowKeyArrivals(window).start()
                    try {
                        // 真鼠标点进问题框：语义点击不会把 OS 键盘焦点交给输入框。
                        NativeWindowForeground.action {
                            NativeWindowForeground.activate(window)
                            val at = onEdt {
                                val bounds = windowNode(window, "book-passage-question")!!.boundsInRoot
                                val origin = window.contentPane.locationOnScreen
                                val scale = window.graphicsConfiguration.defaultTransform
                                Point(origin.x + (bounds.center.x / scale.scaleX).toInt(),
                                    origin.y + (bounds.center.y / scale.scaleY).toInt())
                            }
                            robot.mouseMove(at.x, at.y)
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                            robot.waitForIdle()
                        }
                        awaitWindow("keyboard focus in the question field", window) {
                            onEdt { windowNode(window, "book-passage-question")!!.config.getOrNull(SemanticsProperties.Focused) == true }
                        }
                        windowType(window, robot, arrivals, "why",
                            KeyEvent.VK_W to 'w', KeyEvent.VK_H to 'h', KeyEvent.VK_Y to 'y')
                        windowShiftEnter(window, robot, arrivals)
                        windowType(window, robot, arrivals, "why\nnot",
                            KeyEvent.VK_N to 'n', KeyEvent.VK_O to 'o', KeyEvent.VK_T to 't')
                        assertEquals(0, support.questions.size, "no request before Enter")
                        windowShot(window, dark, "typed-two-lines")

                        windowKey(window, robot, arrivals, KeyEvent.VK_ENTER)
                        awaitWindow("Enter sends the two-line question", window) { support.questions.size == 1 }
                        val sent = support.questions.single()
                        assertEquals("why\nnot", sent.question, "Enter must send exactly the typed text, no line break added")
                        assertEquals(page.number, sent.passage.page)
                        assertEquals(block.start, sent.passage.start)
                        assertEquals(block.end, sent.passage.end)
                        assertEquals(book.id, sent.passage.book)
                        // 发送中：再按两次 Enter 不能变成第二次请求，也不能往问题里塞换行。
                        windowKey(window, robot, arrivals, KeyEvent.VK_ENTER)
                        windowKey(window, robot, arrivals, KeyEvent.VK_ENTER)
                        Thread.sleep(500)
                        assertEquals(1, support.questions.size, "Enter while the answer is in flight must not send again")
                        assertEquals("why\nnot", fieldText(window))
                        windowShot(window, dark, "sent-once-in-flight")

                        support.release.complete(Unit)
                        awaitWindow("reply for the same passage", window) { windowNode(window, "book-passage-citation") != null }
                        windowShot(window, dark, "reply")
                        println("NATIVE_PASSAGE_KEYBOARD theme=${if (dark) "dark" else "light"} shiftEnterNewline=true " +
                            "enterSent=${support.questions.single().question.replace("\n", "\\n")} requests=${support.questions.size} " +
                            "block=${book.id}:p${page.number}:${block.start}")
                    } finally { arrivals.stop() }
                } finally { onEdt { window.dispose() } }
                NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }

    /**
     * 选段列表的真窗口版本：明暗两种主题下核对段号、真的把鼠标移到第二行看悬停浅底有没有画出来，
     * 再点它，核对这一行在屏幕上一点没挪、前后的段落还在，并拍下选中那行的松绿线加 `pineWash` 底。
     * 悬停和「原地高亮」都不是断言语义能证明的事，这里比对像素和在屏幕上的位置。
     */
    @Test fun realWindowPickerNumbersRowsAndShowsHoverAndSelection() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-passage-picker-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val file = pickerTextbook(root)
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                val controller = TextbookController(TextbookLibrary(root.resolve("library-$dark")))
                runBlocking {
                    controller.importBook(file)
                    controller.chooseLearningPath(LearningPath.READ)
                    controller.enableLearning()
                }
                val book = assertNotNull(controller.state.value.book)
                val page = book.pages.first()
                val blocks = StudyCoachCourse.blocks(book.id, listOf(page)).filterNot { it.heading }
                check(blocks.size >= 2) { "the picker sample needs at least two paragraphs" }
                NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
                val window = onEdt { ComposeWindow().apply {
                    title = "LearningCoach · 选段列表"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1280, screen.width - 40), minOf(920, screen.height - 40))
                    // #570 去掉了阅读器的「选段提问」入口：选段弹窗直接挂进这扇真窗口（见 openPicker 的说明）。
                    setContent { CoachTheme(darkTheme = dark) { BookPassageAsk(book, page, Recorder()) {} } }
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront(); requestFocus()
                } }
                try {
                    awaitWindow("numbered passage rows", window) { windowNode(window, "ask-paragraph-0") != null }
                    assertEquals(listOf("点一段，再问一句"), windowText(window, "book-passage-hint"))
                    blocks.forEachIndexed { index, block ->
                        assertEquals(listOf("${index + 1}", ReadableText.reflow(block.text)), windowText(window, "ask-paragraph-$index"))
                    }

                    // 先把真鼠标停在不带悬停的东西上（弹窗标题下面那块），再拍没有悬停的那张。
                    val robot = Robot().apply { autoDelay = 60 }
                    val hint = screenPoint(window, "book-passage-hint")
                    mouseTo(frontWindow(window), robot, Point(hint.x, hint.y - 40))
                    val idle = dialogImage(window)
                    saveDialogImage(idle, dark, "pick")

                    // 把真鼠标移到第二行上：悬停只有画出来了才算数，而且变色的地方得是这一行，
                    // 不是整扇窗重画了一遍。所以先等到像素稳定，再核对变化只在那一行的高度里。
                    var hover: BufferedImage? = null
                    var change: Changed? = null
                    var moved = 0
                    while (hover == null && moved < 4) {
                        mouseTo(frontWindow(window), robot, screenPoint(window, "ask-paragraph-1"))
                        val next = dialogImage(window)
                        val changed = changedBounds(idle, next)
                        if (changed.pixels > 0) { hover = next; change = changed }
                        moved++
                    }
                    val hovered = checkNotNull(hover) {
                        "hovering row 2 changed no pixel; ${NativeWindowForeground.describe(frontWindow(window))}"
                    }
                    val changed = checkNotNull(change)
                    assertTrue(changed.box.height < idle.height / 6,
                        "hovering row 2 repainted ${changed.box.height} of ${idle.height} pixel rows (${changed.box}), " +
                            "not just that row; ${NativeWindowForeground.describe(frontWindow(window))}")
                    saveDialogImage(hovered, dark, "pick-hover")

                    // 点第二行之前先把三行在屏幕上的上沿记下来：选中之后列表和滚动位置都不许动，
                    // 读者要能继续看到前后两段，而不是被换成单独一张卡整体往上跳。
                    val before = blocks.indices.map { screenTop(window, "ask-paragraph-$it") }
                    windowClick(window, "ask-paragraph-1")
                    awaitWindow("the chosen passage stays in place", window) { windowNode(window, "book-selected-passage") != null }
                    assertEquals(listOf("点一段，再问一句"), windowText(window, "book-passage-hint"))
                    blocks.forEachIndexed { index, block ->
                        assertEquals(listOf("${index + 1}", ReadableText.reflow(block.text)), windowText(window, "ask-paragraph-$index"))
                    }
                    val after = blocks.indices.map { screenTop(window, "ask-paragraph-$it") }
                    assertEquals(before, after, "选中第二段后整列往上跳了：$before -> $after")
                    assertEquals(listOf("2"), windowText(window, "book-selected-passage-number"))
                    assertEquals(listOf(ReadableText.reflow(blocks[1].text)), windowText(window, "book-selected-passage"))
                    saveDialogImage(dialogImage(window), dark, "selected")
                    println("NATIVE_PASSAGE_PICKER theme=${if (dark) "dark" else "light"} rows=${blocks.size} " +
                        "hoverPixels=${changed.pixels} hoverBox=${changed.box.width}x${changed.box.height} " +
                        "chosen=2 chosenText=true rowsMoved=${after[1] - before[1]} rowY=${before[1]}->${after[1]}")
                } finally { onEdt { window.dispose() } }
                NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }

    /** Holds the answer in flight and keeps the whole request, so page and paragraph can be checked. */
    private class KeyboardRecorder : BookLearningSupport {
        val questions = mutableListOf<BookPassageQuestion>()
        val release = CompletableDeferred<Unit>()

        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("合成回执：本用例不评价讲解。")

        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            require(BookPassageQuestion.accepts(request.question)) { "Outside the provider's question bound" }
            questions += request
            release.await()
            return BookLearningReply.Text("合成回执：这一段的问题已经收到。", listOf(request.passage))
        }
    }

    private fun windowNode(window: ComposeWindow, tag: String): SemanticsNode? = onEdt {
        fun find(node: SemanticsNode): SemanticsNode? =
            if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) node else node.children.firstNotNullOfOrNull(::find)
        fun findIn(owner: Window): SemanticsNode? {
            val owners = when (owner) {
                is ComposeWindow -> owner.semanticsOwners
                is ComposeDialog -> owner.semanticsOwners
                else -> emptyList()
            }
            return owners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
                ?: owner.ownedWindows.filter { it.isVisible }.firstNotNullOfOrNull(::findIn)
        }
        findIn(window)
    }

    private fun fieldText(window: ComposeWindow): String? =
        windowNode(window, "book-passage-question")?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    /**
     * 一个节点连同它的子节点在无合并树里的全部文字，按子节点顺序：行首段号在前，原文在后。
     * 可点的行把文字合进了自己，行节点本身没有文字，文字在它的两个子节点上。
     */
    private fun windowText(window: ComposeWindow, tag: String): List<String> = onEdt {
        fun text(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::text)
        windowNode(window, tag)?.let(::text).orEmpty()
    }

    /** 最上层的那个窗口：选段表在窗口自己里还是独立弹窗里，截图和鼠标坐标都跟着它走。 */
    private fun frontWindow(window: Window): Window = onEdt {
        window.ownedWindows.filterIsInstance<ComposeDialog>().lastOrNull { it.isVisible }?.let { frontWindow(it) } ?: window
    }

    /** 节点中心在屏幕上的坐标，和真鼠标用的是同一套坐标。 */
    private fun screenPoint(window: ComposeWindow, tag: String): Point = onEdt {
        val bounds = assertNotNull(windowNode(window, tag), tag).boundsInRoot
        val origin = window.contentPane.locationOnScreen
        val scale = window.graphicsConfiguration.defaultTransform
        Point(origin.x + (bounds.center.x / scale.scaleX).toInt(), origin.y + (bounds.center.y / scale.scaleY).toInt())
    }

    /** 节点上沿在屏幕上的 y：选中前后行有没有往上跳，看的就是这个数。 */
    private fun screenTop(window: ComposeWindow, tag: String): Int = onEdt {
        val bounds = assertNotNull(windowNode(window, tag), tag).boundsInRoot
        val origin = window.contentPane.locationOnScreen
        val scale = window.graphicsConfiguration.defaultTransform
        origin.y + (bounds.top / scale.scaleY).toInt()
    }

    /** 前台窗口当下这一帧的像素。悬停有没有画出来，只能从这里看出来。 */
    private fun captureFront(window: ComposeWindow): BufferedImage = onEdt {
        val front = frontWindow(window)
        when (front) {
            is ComposeWindow -> front.renderImmediately()
            is ComposeDialog -> front.renderImmediately()
        }
        val file = Files.createTempFile("coach-passage-shot-", ".png").toFile()
        try {
            VerifyHooks.capture(front as java.awt.Container, file)
            checkNotNull(ImageIO.read(file)) { "could not read back the capture of ${front.javaClass.simpleName}" }
        } finally { file.delete() }
    }

    /**
     * 连拍两张一样才算画完了。重排之后的第一次截图可能还是上一帧的合成结果（页面文字压在弹窗上），
     * 拿它当基准会把整扇窗的重画记成悬停效果。
     */
    private fun dialogImage(window: ComposeWindow): BufferedImage {
        var previous = captureFront(window)
        repeat(5) {
            Thread.sleep(150)
            val next = captureFront(window)
            if (samePixels(previous, next)) return next
            previous = next
        }
        return previous
    }

    private fun mouseTo(window: Window, robot: Robot, at: Point) {
        NativeWindowForeground.action {
            NativeWindowForeground.activate(window)
            robot.mouseMove(at.x, at.y)
            robot.waitForIdle()
        }
        Thread.sleep(300)
    }

    private fun saveDialogImage(image: BufferedImage, dark: Boolean, name: String) {
        val folder = Path.of("build/screens/passage-picker", if (dark) "dark" else "light")
        Files.createDirectories(folder)
        ImageIO.write(image, "png", folder.resolve("$name.png").toFile())
    }

    private fun changedPixels(a: BufferedImage, b: BufferedImage): Int {
        if (a.width != b.width || a.height != b.height) return maxOf(a.width * a.height, b.width * b.height)
        var changed = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
        return changed
    }

    private fun samePixels(a: BufferedImage, b: BufferedImage): Boolean =
        a.width == b.width && a.height == b.height && changedPixels(a, b) == 0

    /** 两张图不一样的地方有多少、落在哪块矩形里。 */
    private data class Changed(val pixels: Int, val box: Rectangle)

    private fun changedBounds(a: BufferedImage, b: BufferedImage): Changed {
        if (a.width != b.width || a.height != b.height) return Changed(maxOf(a.width * a.height, b.width * b.height), Rectangle())
        var pixels = 0
        var minX = a.width
        var minY = a.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getRGB(x, y) == b.getRGB(x, y)) continue
            pixels++
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return if (pixels == 0) Changed(0, Rectangle()) else Changed(pixels, Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1))
    }

    private fun windowClick(window: ComposeWindow, tag: String) {
        awaitWindow(tag, window) { windowNode(window, tag)?.config?.getOrNull(SemanticsActions.OnClick) != null }
        onEdt { assertTrue(windowNode(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(200)
    }

    /**
     * 送一个真键，等它真的到过这个窗口；只有完全没送到才补发。
     * winbox 的两道作业共用一个控制台会话，别的作业的窗口会在两次按键之间把前台拿走。
     */
    private fun windowKey(window: ComposeWindow, robot: Robot, arrivals: WindowKeyArrivals, key: Int) {
        var resends = 0
        while (true) {
            val before = arrivals.count
            NativeWindowForeground.action {
                NativeWindowForeground.activate(window)
                robot.keyPress(key)
                robot.keyRelease(key)
                robot.waitForIdle()
            }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (arrivals.count <= before && System.nanoTime() < deadline) Thread.sleep(20)
            if (arrivals.count > before) return
            check(resends++ < 2) { "key $key never reached this window; ${NativeWindowForeground.describe(window)}" }
        }
    }

    /**
     * 逐字打到 [expected]：每次只补发还缺的那一个键。一批键只送到一半时（前台被另一道作业的窗口
     * 拿走），按「当前文本是期望文本的前缀」补发，既不会重复打字，也不把被抢当成产品缺陷；
     * 文本一旦偏离期望就立刻失败，断言仍是整串相等。
     */
    private fun windowType(window: ComposeWindow, robot: Robot, arrivals: WindowKeyArrivals,
                           expected: String, vararg keys: Pair<Int, Char>) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            val text = fieldText(window).orEmpty()
            if (text == expected) return
            check(expected.startsWith(text)) {
                "the question field holds \"$text\", which is not a prefix of \"$expected\"; ${NativeWindowForeground.describe(window)}"
            }
            check(System.nanoTime() < deadline) {
                "Timed out typing \"$expected\"; the field holds \"$text\"; ${NativeWindowForeground.describe(window)}"
            }
            val missing = expected[text.length]
            val next = keys.firstOrNull { it.second == missing }
            checkNotNull(next) { "no key types '$missing' on the way to \"$expected\"" }
            windowKey(window, robot, arrivals, next.first)
        }
    }

    /**
     * 真 Shift+Enter 只加一行：一次没送到就补发，但一次也不许变成两行。
     * 补发只在「文本一个字都没变」时进行，所以重复执行不会被当成正常结果。
     */
    private fun windowShiftEnter(window: ComposeWindow, robot: Robot, arrivals: WindowKeyArrivals) {
        val before = fieldText(window).orEmpty()
        var attempts = 0
        while (true) {
            check(fieldText(window) == before) {
                "Shift+Enter changed more than a line break: \"${fieldText(window)}\""
            }
            NativeWindowForeground.action {
                NativeWindowForeground.activate(window)
                robot.keyPress(KeyEvent.VK_SHIFT)
                robot.keyPress(KeyEvent.VK_ENTER)
                robot.keyRelease(KeyEvent.VK_ENTER)
                robot.keyRelease(KeyEvent.VK_SHIFT)
                robot.waitForIdle()
            }
            val deadline = System.nanoTime() + 3_000_000_000L
            while (fieldText(window) == before && System.nanoTime() < deadline) Thread.sleep(20)
            if (fieldText(window) == "$before\n") return
            check(attempts++ < 3) {
                "Shift+Enter did not open a second line; the field holds \"${fieldText(window)}\"; " +
                    NativeWindowForeground.describe(window)
            }
        }
    }

    private fun windowShot(window: ComposeWindow, dark: Boolean, name: String) {
        Thread.sleep(400)
        NativeWindowForeground.action {
            NativeWindowForeground.activate(window)
            onEdt {
                val folder = Path.of("build/screens/passage-keyboard", if (dark) "dark" else "light")
                Files.createDirectories(folder)
                window.renderImmediately()
                VerifyHooks.capture(window, folder.resolve("$name.png").toFile())
                window.ownedWindows.filterIsInstance<ComposeDialog>().filter { it.isVisible }.forEachIndexed { index, dialog ->
                    dialog.renderImmediately()
                    VerifyHooks.capture(dialog, folder.resolve("$name-dialog-$index.png").toFile())
                }
            }
        }
    }

    private fun awaitWindow(label: String, window: ComposeWindow, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $label; ${runCatching { NativeWindowForeground.describe(window) }.getOrElse { "$it" }}" }
            Thread.sleep(100)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    /**
     * 打开选段列表但一段都不点：列表本身就是这次要核对的东西。
     *
     * TON-1784（#570）去掉了阅读器里的「选段提问」按钮（ask-book-passage），导入后也不再有
     * IMPORTED / AFTER_READING 提示（read-only），BookPassageAsk 目前没有产品入口。这里沿用
     * ExplainTroubleTest、ReadableScreensTest 等的做法：真导入同一本书，再把选段弹窗直接挂到第一页上，
     * 弹窗自身的选段、字数上限、按书同意和出处行为照旧核对。
     */
    private fun ComposeUiTest.openPicker(
        controller: TextbookController, source: Path, support: BookLearningSupport,
        wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        runBlocking { controller.importBook(source) }
        ready(controller) { it.book != null }
        val book = controller.state.value.book!!
        setContent { wrap { CoachTheme(darkTheme = false) { BookPassageAsk(book, book.pages.first(), support) {} } } }
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("ask-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.openComposer(
        controller: TextbookController, source: Path, support: BookLearningSupport,
        wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        openPicker(controller, source, support, wrap)
        onNodeWithTag("ask-paragraph-0").performClick()
    }

    private fun ComposeUiTest.typedQuestion(): String =
        onNodeWithTag("book-passage-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** 这一行现在还能不能点：段号发出去之后，其余段落不该再有点击动作。 */
    private fun ComposeUiTest.hasClickAction(tag: String): Boolean =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) != null

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }
}

/**
 * 第一页就有三段正文的合成教材：选段列表要排成好几行，才看得出它是一列可点的段落、
 * 而不是一个没有先后的大按钮。页眉照旧是标题块，不会被列进可选的段落里。
 */
private fun pickerTextbook(root: Path, title: String = "Synthetic astronomy, three paragraphs"): Path {
    Files.createDirectories(root)
    val file = root.resolve("$title.pdf")
    PDDocument().use { pdf ->
        pdf.documentInformation.title = title
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val page = PDPage(); pdf.addPage(page)
        PDPageContentStream(pdf, page).use { out ->
            var y = 710f
            out.beginText(); out.setFont(font, 18f); out.newLineAtOffset(50f, y); out.showText("Chapter 1 Orbits"); out.endText()
            y -= 48f
            for (paragraph in listOf(
                "An orbit is the path of an object around another body. An orbit can be elliptical.",
                "Gravity is the attraction between bodies with mass. Gravity acts across distance.",
                "A rotation is the turning of a body around its axis. One rotation determines a day.",
            )) {
                out.beginText(); out.setFont(font, 11f); out.newLineAtOffset(50f, y); out.showText(paragraph); out.endText()
                y -= 60f
            }
        }
        pdf.save(file.toFile())
    }
    return file
}
