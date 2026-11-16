package com.coach.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import kotlin.test.*

/** Opt-in real subscription acceptance against an explicitly isolated, disposable server.
 * Only committed self-authored fixtures and our own two windows are opened.
 * Connection credentials come from a run-owned file outside the checkout and are never logged. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class ImportedBookLiveWindowTest {
    @Test fun firstConsentedImportUsesRealPairedTransportAndReturnsToSavedSourceInBothThemes() {
        val connection = System.getProperty("coach.liveBookConnection")
        assumeTrue(!connection.isNullOrBlank())
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-native-formats-")
        require(connection!!.matches(Regex("ton1663-live-[0-9a-f]{32}\\.json")))
        val configuration = kotlinx.serialization.json.Json.parseToJsonElement(
            Files.readString(Path.of("D:/agent/logs").resolve(connection))).jsonObject
        val token = root.resolve("native-token")
        Files.writeString(token, configuration.getValue("token").jsonPrimitive.content)
        val environment = mapOf("COACH_URL" to configuration.getValue("url").jsonPrimitive.content,
            "COACH_TOKEN_FILE" to token.toString(), "COACH_CLIENT_DIR" to root.resolve("client").toString())
        val settings = DesktopConnectionSettings(home = root, environment = environment::get, mac = false)
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val files = listOf("formats/gbk.txt", "formats/lesson.docx", "formats/lesson.pptx", "ocr/01-clean-serif.png").map { name ->
                root.resolve(name.substringAfterLast('/')).also { file ->
                    checkNotNull(javaClass.getResourceAsStream("/books/$name")).use { Files.copy(it, file) }
                }
            }
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                val controller = TextbookController(TextbookLibrary(root.resolve(if (dark) "dark" else "light")))
                runBlocking { controller.load() }
                val requests = mutableListOf<BookPassageQuestion>()
                var failedReply: String? = null
                val paired = PairedBookLearningSupport(controller.library, send = { block, question ->
                    BookExplainer(settings, controller.library).run { load(); ask(block, question) }
                })
                val support = object : BookLearningSupport {
                    override fun consentRequired(book: String) = paired.consentRequired(book)
                    override fun allow(book: String) = paired.allow(book)
                    override suspend fun explain(request: BookExplanationRequest) = paired.explain(request)
                    override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                        val p = request.passage
                        assertEquals(p.text, controller.library.book(p.book).page(p.page)!!.text.substring(p.start, p.end))
                        val reply = paired.ask(request)
                        if (reply is BookLearningReply.Text) {
                            assertTrue(reply.text.isNotBlank())
                            requests += request
                        } else failedReply = reply.toString()
                        return reply
                    }
                }
                val window = edt { ComposeWindow().apply {
                    title = "Coach · 自造材料验收 · 真实订阅讲解"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1600, screen.width - 40), minOf(950, screen.height - 150))
                    setContent { WindowsTextbookHost(controller, learningSupport = support) { null } }
                    isVisible = true
                } }
                var sourceFile = files.first()
                val source = edt { JFrame("Coach · 自造文件拖放源").apply {
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 30, screen.y + screen.height - 110, 260, 80)
                    contentPane.add(JList(arrayOf("拖入 Coach（仅自造材料）")).apply {
                        selectedIndex = 0
                        dragEnabled = true
                        transferHandler = object : TransferHandler() {
                            override fun getSourceActions(c: JComponent) = COPY
                            override fun createTransferable(c: JComponent): Transferable = object : Transferable {
                                override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
                                override fun getTransferData(flavor: DataFlavor): Any {
                                    require(isDataFlavorSupported(flavor))
                                    println("NATIVE_DRAG_EXPORT ${sourceFile.fileName}")
                                    return listOf(sourceFile.toFile())
                                }
                            }
                        }
                    })
                    isAlwaysOnTop = true
                    isVisible = true
                } }
                try {
                    waitFor("initial shelf") { node(window, "upload-textbook") != null }
                    shot(window, dark, "shelf")
                    val robot = Robot().apply { autoDelay = 40 }
                    for ((index, file) in files.withIndex()) {
                        edt { sourceFile = file; source.toFront() }
                        val from = edt { source.contentPane.locationOnScreen.apply { translate(80, 12) } }
                        val to = edt { window.contentPane.locationOnScreen.apply { translate(window.width / 2, window.height / 2) } }
                        robot.mouseMove(from.x, from.y)
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                        try {
                            repeat(30) { step -> robot.mouseMove(from.x + (to.x - from.x) * (step + 1) / 30, from.y + (to.y - from.y) * (step + 1) / 30) }
                        } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        waitFor("native drop ${file.fileName}; ${controller.state.value.error}") {
                            controller.state.value.let { !it.busy && it.prompt == BookPrompt.IMPORTED && it.catalog.size == index + 1 }
                        }
                        assertNull(controller.state.value.error)
                        click(window, "read-only")
                        waitFor("after reading") { controller.state.value.prompt == BookPrompt.AFTER_READING }
                        click(window, "read-only")
                        waitFor("reader") { controller.state.value.prompt == null && node(window, "ask-book-passage") != null }
                        shot(window, dark, "${file.fileName}-reader")
                        click(window, "ask-book-passage")
                        // The OCR page begins with an unmarked title. Ask about its substantive paragraph.
                        val paragraphs = StudyCoachCourse.blocks(controller.state.value.book!!.id,
                            listOf(controller.state.value.book!!.pages.first())).filterNot { it.heading }
                        val paragraph = if (file.fileName.toString().endsWith(".png"))
                            paragraphs.indexOfFirst { it.text.contains("主动回忆") } else 0
                        assertTrue(paragraph >= 0)
                        click(window, "ask-paragraph-$paragraph")
                        waitFor("question field") { node(window, "book-passage-question") != null }
                        edt { assertTrue(node(window, "book-passage-question")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("请解释这个概念，并给一个例子。"))) }
                        click(window, "book-passage-send")
                        waitFor("per-book consent") { node(window, "ask-consent-allow") != null }
                        assertEquals(index, requests.size, "No send before per-book consent")
                        click(window, "ask-consent-allow")
                        waitFor("real subscription reply") {
                            check(failedReply == null) { "Real paired explanation refused: $failedReply" }
                            requests.size == index + 1 && node(window, "book-passage-citation") != null
                        }
                        val pageLabel = controller.state.value.book!!.pageLabel(requests.last().passage.page)
                        assertTrue(edt { nodes(window).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "出处 · $pageLabel" } == true } })
                        shot(window, dark, "${file.fileName}-reply")
                        click(window, "book-passage-citation")
                        waitFor("source") { node(window, "book-passage-source") != null }
                        val shown = edt { node(window, "book-passage-source")!!.config[SemanticsProperties.Text].joinToString("") { it.text } }
                        assertEquals(requests.last().passage.text, shown)
                        assertTrue(edt { nodes(window).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "原文 · $pageLabel" } == true } })
                        shot(window, dark, "${file.fileName}-source")
                        // Close the two actual dialogs through their semantics, preserving the source page.
                        clickText(window, "回到问题")
                        click(window, "book-passage-close")
                        println("NATIVE_LIVE_BOOK theme=${if (dark) "dark" else "light"} file=${file.fileName} pages=${controller.state.value.book!!.pages.size} sourcePage=${requests.last().passage.page} exactUtf16=true realTransport=true")
                    }
                } finally { edt { source.dispose(); window.dispose() } }
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> = window.ownedWindows
        .filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }
    private fun node(window: ComposeWindow, tag: String) = edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(250)
    }
    private fun clickText(window: ComposeWindow, text: String) {
        edt {
            val node = nodes(window).first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
            var button: SemanticsNode? = node
            while (button != null && button.config.getOrNull(SemanticsActions.OnClick) == null) button = button.parent
            assertTrue(checkNotNull(button).config[SemanticsActions.OnClick].action!!.invoke())
        }
        Thread.sleep(250)
    }
    private fun shot(window: ComposeWindow, dark: Boolean, name: String) {
        Thread.sleep(350)
        edt {
            window.renderImmediately()
            val path = Path.of("build/screens/imports-live-native", if (dark) "dark" else "light")
            Files.createDirectories(path)
            VerifyHooks.capture(window, path.resolve("$name.png").toFile())
            ownedDialogs(window).forEachIndexed { index, dialog ->
                dialog.renderImmediately()
                VerifyHooks.capture(dialog, path.resolve("$name-dialog-$index.png").toFile())
            }
        }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 100_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label" }; Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
