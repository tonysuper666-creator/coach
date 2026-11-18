package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
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

        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("合成回执：本用例不评价讲解。")

        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            // Same guard OwnedBookLearningProvider.question() applies before any transport runs.
            require(BookPassageQuestion.accepts(request.question)) { "Outside the provider's question bound" }
            questions += request.question
            return BookLearningReply.Unavailable("合成回执：只记录请求，尚未接通方案商。")
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
        val answer = CompletableDeferred<BookLearningReply>()
        var consent = true

        override fun consentRequired(book: String) = consent
        override fun allow(book: String) { consent = false }
        override suspend fun explain(request: BookExplanationRequest) =
            BookLearningReply.Unavailable("合成回执：本用例不评价讲解。")

        override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
            require(BookPassageQuestion.accepts(request.question)) { "Outside the provider's question bound" }
            questions += request.question
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
                    setContent { WindowsTextbookHost(controller, learningSupport = support) { null } }
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront(); requestFocus()
                } }
                try {
                    awaitWindow("reader with the passage composer", window) { windowNode(window, "ask-book-passage") != null }
                    windowClick(window, "ask-book-passage")
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
                        windowKeys(window, robot, arrivals, KeyEvent.VK_W, KeyEvent.VK_H, KeyEvent.VK_Y)
                        awaitWindow("typed letters", window) { fieldText(window) == "why" }
                        windowKeys(window, robot, arrivals, KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER)
                        awaitWindow("Shift+Enter keeps a second line", window) { fieldText(window) == "why\n" }
                        windowKeys(window, robot, arrivals, KeyEvent.VK_N, KeyEvent.VK_O, KeyEvent.VK_T)
                        awaitWindow("second line typed", window) { fieldText(window) == "why\nnot" }
                        assertEquals(0, support.questions.size, "no request before Enter")
                        windowShot(window, dark, "typed-two-lines")

                        windowKeys(window, robot, arrivals, KeyEvent.VK_ENTER)
                        awaitWindow("Enter sends the two-line question", window) { support.questions.size == 1 }
                        val sent = support.questions.single()
                        assertEquals("why\nnot", sent.question, "Enter must send exactly the typed text, no line break added")
                        assertEquals(page.number, sent.passage.page)
                        assertEquals(block.start, sent.passage.start)
                        assertEquals(block.end, sent.passage.end)
                        assertEquals(book.id, sent.passage.book)
                        // 发送中：再按两次 Enter 不能变成第二次请求，也不能往问题里塞换行。
                        windowKeys(window, robot, arrivals, KeyEvent.VK_ENTER)
                        windowKeys(window, robot, arrivals, KeyEvent.VK_ENTER)
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

    private fun windowClick(window: ComposeWindow, tag: String) {
        awaitWindow(tag, window) { windowNode(window, tag)?.config?.getOrNull(SemanticsActions.OnClick) != null }
        onEdt { assertTrue(windowNode(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(200)
    }

    /** Presses real OS keys and retries only when the event never reached this window at all. */
    private fun windowKeys(window: ComposeWindow, robot: Robot, arrivals: WindowKeyArrivals, vararg keys: Int) {
        var resends = 0
        while (true) {
            val before = arrivals.count
            NativeWindowForeground.action {
                NativeWindowForeground.activate(window)
                keys.forEach { robot.keyPress(it) }
                keys.reversed().forEach { robot.keyRelease(it) }
                robot.waitForIdle()
            }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (arrivals.count <= before && System.nanoTime() < deadline) Thread.sleep(20)
            if (arrivals.count > before) return
            check(resends++ < 2) { "keys ${keys.toList()} never reached this window" }
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

    private fun ComposeUiTest.openComposer(controller: TextbookController, source: Path, support: BookLearningSupport) {
        setContent { TextbookWorkspace(controller, support = support) { source } }
        ready(controller) { it.catalog.isEmpty() }
        onNodeWithTag("upload-textbook").performClick()
        ready(controller) { it.prompt == BookPrompt.IMPORTED }
        onNodeWithTag("read-only").performClick()
        ready(controller) { it.screen == BookScreen.READER && it.prompt == BookPrompt.AFTER_READING }
        onNodeWithTag("read-only").performClick()
        ready(controller) { it.prompt == null }
        onNodeWithTag("ask-book-passage").performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("ask-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("ask-paragraph-0").performClick()
    }

    private fun ComposeUiTest.typedQuestion(): String =
        onNodeWithTag("book-passage-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }
}
