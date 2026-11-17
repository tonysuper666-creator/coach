package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Opt-in native window against a private real textbook and an isolated paired service.
 * Neither credentials, original text nor captures belong in Git or issue attachments.
 * Uses the production host and default learning provider, without an injected send function. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class BookLessonLiveWindowTest {
    @Test fun realWholeLessonShowsVerifiedSourceAndReturnsToTheSamePageInBothThemes() {
        val connection = System.getProperty("coach.liveBookConnection")
        assumeTrue(!connection.isNullOrBlank())
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        require(connection!!.matches(Regex("ton1663-live-[0-9a-f]{32}\\.json")))
        val config = Json.parseToJsonElement(Files.readString(Path.of("D:/agent/logs").resolve(connection))).jsonObject
        val url = config.getValue("url").jsonPrimitive.content
        require(java.net.URI(url).port !in listOf(18791, 8790, 8791))
        for (name in listOf("COACH_URL", "COACH_CLIENT_DIR", "COACH_TOKEN_FILE", "COACH_ACCEPTANCE_REPLAY", "COACH_AGENT_RUN_ID"))
            require(System.getenv(name).isNullOrBlank())
        val saved = listOf("user.home", "jpackage.app-path", "coach.windows.theme").associateWith(System::getProperty)
        val root = Files.createTempDirectory("coach-real-lesson-")
        try {
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                val home = Files.createDirectories(root.resolve(theme))
                System.setProperty("user.home", home.toString())
                System.setProperty("jpackage.app-path", home.resolve("LearningCoach.exe").toString())
                System.setProperty("coach.windows.theme", theme)
                val settings = DesktopConnectionSettings()
                val credentials = PairedCredentials(config.getValue("deviceId").jsonPrimitive.content,
                    config.getValue("token").jsonPrimitive.content)
                DesktopCredentials(settings.clientDirectory).apply { save(url, credentials); saveAddress(url) }
                val controller = TextbookController(TextbookLibrary(home.resolve("library")))
                runBlocking { controller.load() }
                val window = edt { ComposeWindow().apply {
                    title = "LearningCoach · 整节讲解真实教材验收"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1440, screen.width - 40), minOf(950, screen.height - 60))
                    setContent { WindowsTextbookHost(controller) { null } }
                    isVisible = true
                } }
                try {
                    waitFor("shelf") { node(window, "upload-textbook") != null }
                    importThroughNativeDrop(window)
                    waitFor("real PDF import") { !controller.state.value.busy && controller.state.value.prompt == BookPrompt.IMPORTED }
                    assertNull(controller.state.value.error)
                    click(window, "read-only")
                    click(window, "read-only")
                    waitFor("reader") { node(window, "textbook-reader") != null }
                    val book = controller.state.value.book!!
                    assertTrue(book.pages.size > 100, "Use the private real textbook")
                    runBlocking { controller.page(27) }
                    waitFor("page 27 rendered") { text(node(window, "reader-page-number")).contains("27") }
                    clickText(window, "下一页")
                    waitFor("page turn") { controller.state.value.page == 28 }
                    waitFor("page 28 rendered") { text(node(window, "reader-page-number")).contains("28") }
                    clickText(window, "上一页")
                    waitFor("page restored") { controller.state.value.page == 27 }
                    // Select a substantive, multi-block lesson from the actual book, never a fixture.
                    val index = book.lessons.indexOfFirst { it.firstPage >= 100 && it.blocks.count { b -> !b.heading && b.text.length >= 120 } > 6 }
                    assertTrue(index >= 0)
                    runBlocking { controller.learn(LearningPath.READ_AND_LEARN) }
                    click(window, "learning-path-READ_AND_LEARN")
                    click(window, "enable-learning")
                    // No consent yet, so moving to the chosen section cannot send front matter.
                    waitFor("lesson consent") { node(window, "book-lesson-consent-allow") != null }
                    runBlocking { controller.lesson(index) }
                    assertFalse(ExplainConsent(controller.library.directory).allowed(book.id))
                    shot(window, theme, "consent")
                    click(window, "book-lesson-consent-allow")
                    waitFor("real whole lesson") {
                        check(node(window, "book-explanation-unavailable") == null) { "Real whole-lesson request refused; original text omitted" }
                        node(window, "book-explanation-text") != null && node(window, "book-source-0") != null
                    }
                    assertTrue(text(node(window, "book-explanation-text")).isNotBlank())
                    val source = text(node(window, "book-source-0"))
                    assertTrue(source.contains("页"), "The source must visibly include a page")
                    val lesson = controller.state.value.book!!.lessons[index]
                    val citedBlock = lesson.blocks.single { block ->
                        source == "出处 · " + bookSourceLabel(BookPassage(book.id, block.page, block.start, block.end, block.text), book)
                    }
                    shot(window, theme, "explanation")
                    click(window, "book-source-0")
                    waitFor("source reader") { controller.state.value.screen == BookScreen.READER && controller.state.value.source != null }
                    val page = controller.state.value.page
                    assertEquals(citedBlock.page, page)
                    assertTrue(source.contains(book.pageLabel(page)), "Citation and returned original page must agree")
                    assertTrue(citedBlock.text == controller.library.book(book.id).page(page)!!.text.substring(citedBlock.start, citedBlock.end),
                        "Returned source must match the original UTF-16 range; original text omitted")
                    waitFor("original page") { node(window, "reader-page-number") != null }
                    assertTrue(text(node(window, "reader-page-number")).contains(page.toString()))
                    shot(window, theme, "source")
                    println("REAL_LESSON_WINDOW theme=$theme pages=${book.pages.size} lessonIndex=$index blocks=${lesson.blocks.count { !it.heading }} sourcePage=$page nativeImport=true pageTurn=true body=true pageCitation=true exactUtf16=true sourceRoundTrip=true defaultProvider=true")
                    val pageBlocks = StudyCoachCourse.blocks(book.id, listOf(book.page(page)!!)).filterNot { it.heading }
                    val paragraph = pageBlocks.indexOfFirst { it.text.length >= 120 }
                    assertTrue(paragraph >= 0, "The cited page must contain a substantive paragraph")
                    click(window, "book-explain-toggle")
                    edt {
                        val block = node(window, "explain-block-${pageBlocks[paragraph].id}")!!
                        fun button(parent: SemanticsNode): SemanticsNode? =
                            parent.takeIf { it.config.getOrNull(SemanticsProperties.TestTag) == "explain-button" }
                                ?: parent.children.firstNotNullOfOrNull(::button)
                        assertTrue(button(block)!!.config[SemanticsActions.OnClick].action!!.invoke())
                    }
                    waitFor("real inline explanation") {
                        check(node(window, "explain-failed") == null) { "Real inline request refused; original text omitted" }
                        node(window, "explain-text") != null && node(window, "explain-citation") != null
                    }
                    assertTrue(text(node(window, "explain-text")).isNotBlank())
                    val inlineCitation = text(node(window, "explain-citation"))
                    assertTrue(inlineCitation.contains("页"))
                    shot(window, theme, "inline-explanation")
                    click(window, "explain-citation")
                    waitFor("inline original card") { node(window, "explain-source-card") != null }
                    val inlineOriginal = text(node(window, "explain-source-text"))
                    assertTrue(inlineOriginal.isNotBlank())
                    assertTrue(book.lessons.flatMap { it.blocks }.any { block ->
                        block.text == inlineOriginal && inlineCitation.contains(book.pageLabel(block.page))
                    }, "Inline original and page must match the imported textbook; original text omitted")
                    shot(window, theme, "inline-source")
                    click(window, "explain-source-close")
                    click(window, "book-explain-toggle")
                    println("REAL_LESSON_WINDOW theme=$theme inlineExplanation=true inlineCitation=true inlineOriginalCard=true sameRound=true")
                    click(window, "ask-book-passage")
                    click(window, "ask-paragraph-$paragraph")
                    edt { assertTrue(node(window, "book-passage-question")!!.config[SemanticsActions.SetText].action!!
                        .invoke(androidx.compose.ui.text.AnnotatedString("请用自己的话解释这一段的主要意思。"))) }
                    click(window, "book-passage-send")
                    waitFor("real passage question") {
                        check(node(window, "book-passage-unavailable") == null) { "Real passage request refused; original text omitted" }
                        node(window, "book-passage-citation") != null
                    }
                    click(window, "book-passage-citation")
                    waitFor("passage source") { node(window, "book-passage-source") != null }
                    assertTrue(text(node(window, "book-passage-source")).isNotBlank())
                    shot(window, theme, "passage-source")
                    clickText(window, "回到问题")
                    click(window, "book-passage-close")
                    println("REAL_LESSON_WINDOW theme=$theme passageQuestion=true passageCitation=true sameRound=true")
                    // Returning reuses the held answer; the next section exercises real offline handling.
                    runBlocking { controller.back() }
                    waitFor("held reply") { node(window, "book-explanation-text") != null }
                    val dead = "http://127.0.0.1:19344"
                    DesktopCredentials(settings.clientDirectory).apply { save(dead, credentials); saveAddress(dead) }
                    click(window, "next-lesson")
                    waitFor("offline") { node(window, "book-explanation-unavailable") != null }
                    assertNotNull(node(window, "explain-trouble-retry"))
                    shot(window, theme, "offline")
                    println("REAL_LESSON_WINDOW theme=$theme offline=true retry=true originalTextLogged=false")
                } catch (failure: Throwable) {
                    shot(window, theme, "failure")
                    throw failure
                } finally { edt { window.dispose() } }
            }
        } finally {
            for ((key, value) in saved) if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            root.toFile().deleteRecursively()
        }
    }

    /** Exercise the production OS file-drop path using only a run-owned source window. */
    private fun importThroughNativeDrop(window: ComposeWindow) {
        val source = edt { javax.swing.JFrame("Coach · 本轮教材拖放源").apply {
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            setBounds(screen.x + 30, screen.y + screen.height - 110, 260, 80)
            contentPane.add(javax.swing.JList(arrayOf("拖入 Coach")).apply {
                selectedIndex = 0
                dragEnabled = true
                transferHandler = object : javax.swing.TransferHandler() {
                    override fun getSourceActions(c: javax.swing.JComponent) = COPY
                    override fun createTransferable(c: javax.swing.JComponent) = object : java.awt.datatransfer.Transferable {
                        override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                        override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) =
                            flavor == java.awt.datatransfer.DataFlavor.javaFileListFlavor
                        override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any {
                            require(isDataFlavorSupported(flavor))
                            return listOf(Path.of("D:/agent/friend-path-books/health-economics.pdf").toFile())
                        }
                    }
                }
            })
            isAlwaysOnTop = true
            isVisible = true
        } }
        try {
            val from = edt { source.contentPane.locationOnScreen.apply { translate(80, 12) } }
            val to = edt { window.contentPane.locationOnScreen.apply { translate(window.width / 2, window.height / 2) } }
            val robot = java.awt.Robot().apply { autoDelay = 40 }
            robot.mouseMove(from.x, from.y)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            try {
                repeat(30) { step -> robot.mouseMove(from.x + (to.x - from.x) * (step + 1) / 30,
                    from.y + (to.y - from.y) * (step + 1) / 30) }
            } finally { robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK) }
        } finally { edt { source.dispose() } }
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        fun dialogs(owner: java.awt.Window): List<ComposeDialog> = owner.ownedWindows.filter { it.isVisible }
            .flatMap { listOfNotNull(it as? ComposeDialog) + dialogs(it) }
        return (window.semanticsOwners + dialogs(window).flatMap { it.semanticsOwners }).flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun node(window: ComposeWindow, tag: String) = edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun text(node: SemanticsNode?): String = edt {
        node?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            ?: node?.children?.joinToString("") { text(it) }.orEmpty()
    }
    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(300)
    }
    private fun clickText(window: ComposeWindow, value: String) {
      edt {
        var button: SemanticsNode? = nodes(window).first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true }
        while (button != null && button.config.getOrNull(SemanticsActions.OnClick) == null) button = button.parent
        assertTrue(checkNotNull(button).config[SemanticsActions.OnClick].action!!.invoke())
      }
      Thread.sleep(300)
    }
    private fun shot(window: ComposeWindow, theme: String, name: String) {
        Thread.sleep(350)
        edt {
            window.renderImmediately()
            val directory = Path.of("build/screens/lesson-private", theme)
            Files.createDirectories(directory)
            VerifyHooks.capture(window, directory.resolve("$name.png").toFile())
            fun captureDialogs(owner: java.awt.Window) {
                owner.ownedWindows.filter { it.isVisible }.forEachIndexed { index, dialog ->
                    if (dialog is ComposeDialog) {
                        dialog.renderImmediately()
                        VerifyHooks.capture(dialog, directory.resolve("$name-dialog-$index.png").toFile())
                    }
                    captureDialogs(dialog)
                }
            }
            captureDialogs(window)
        }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 180_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label" }; Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
