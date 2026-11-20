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

/** Explicit console-only acceptance. Uses our two windows and original fixtures, never Explorer,
 * the clipboard, screen capture of other apps, real books, accounts or a network AI provider.
 * Native Robot drag crosses windows; Compose semantics operates the real rendered controls.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class OcrNativeWindowTest {
    @Test fun nativeFileDropAndPassageSourceInBothThemes() {
        assumeTrue(System.getProperty("coach.nativeOcr") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-native-ocr-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val files = listOf("01-clean-serif.png", "03-camera-skew.jpg", "three-scanned-pages.pdf").map { name ->
                root.resolve(name).also { file ->
                    checkNotNull(javaClass.getResourceAsStream("/books/ocr/$name")).use { Files.copy(it, file) }
                }
            }
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                val controller = TextbookController(TextbookLibrary(root.resolve(if (dark) "dark" else "light")))
                runBlocking { controller.load() }
                val requests = mutableListOf<BookPassageQuestion>()
                val support = object : BookLearningSupport {
                    override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Unavailable("合成验收不用整节讲解。")
                    override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                        val p = request.passage
                        assertEquals(p.text, controller.library.book(p.book).page(p.page)!!.text.substring(p.start, p.end))
                        requests += request
                        return BookLearningReply.Text("本地合成回执：这段介绍一个生物学概念。可点出处核对原文。", listOf(p))
                    }
                }
                val window = edt { ComposeWindow().apply {
                    title = "Coach · 自造材料验收 · 本地合成讲解"
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
                    fun drop(file: Path) {
                        edt { sourceFile = file; source.toFront() }
                        val from = edt { source.contentPane.locationOnScreen.apply { translate(80, 12) } }
                        val to = edt { window.contentPane.locationOnScreen.apply { translate(window.width / 2, window.height / 2) } }
                        robot.mouseMove(from.x, from.y)
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                        try {
                            repeat(30) { step -> robot.mouseMove(from.x + (to.x - from.x) * (step + 1) / 30, from.y + (to.y - from.y) * (step + 1) / 30) }
                        } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                    }
                    drop(files.last())
                    waitFor("OCR progress") { controller.state.value.importing?.recognizing == true }
                    waitFor("visible progress") { node(window, "book-import-progress") != null }
                    shot(window, dark, "import-progress")
                    click(window, "book-import-cancel")
                    waitFor("cancelled") { !controller.state.value.busy && controller.state.value.importing == null && controller.state.value.catalog.isEmpty() }
                    assertTrue(controller.state.value.catalog.isEmpty())
                    assertContains(controller.state.value.error!!, "取消")
                    shot(window, dark, "import-cancelled")
                    println("NATIVE_OCR_WINDOW theme=${if (dark) "dark" else "light"} cancellation=button emptyShelf=true")
                    for ((index, file) in files.withIndex()) {
                        drop(file)
                        waitFor("native drop ${file.fileName}; ${controller.state.value.error}") {
                            controller.state.value.let { !it.busy && it.importing == null && it.prompt == BookPrompt.IMPORTED && it.catalog.size == index + 1 }
                        }
                        assertNull(controller.state.value.error)
                        click(window, "read-only")
                        waitFor("after reading") { controller.state.value.prompt == BookPrompt.AFTER_READING }
                        click(window, "read-only")
                        waitFor("reader") { controller.state.value.prompt == null && node(window, "ask-book-passage") != null }
                        assertTrue(controller.state.value.book!!.page(controller.state.value.page)!!.ocr)
                        waitFor("ocr notice") { node(window, "book-ocr-notice") != null }
                        shot(window, dark, "${file.fileName}-reader")
                        click(window, "book-original-toggle")
                        waitFor("original image") { node(window, "remote-image") != null }
                        shot(window, dark, "${file.fileName}-original")
                        click(window, "book-original-toggle")
                        click(window, "ask-book-passage")
                        click(window, "ask-paragraph-0")
                        waitFor("question field") { node(window, "book-passage-question") != null }
                        edt { assertTrue(node(window, "book-passage-question")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("请解释这个概念，并给一个例子。"))) }
                        click(window, "book-passage-send")
                        waitFor("local synthetic reply") { requests.size == index + 1 && node(window, "book-passage-citation") != null }
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
                        println("NATIVE_OCR_WINDOW theme=${if (dark) "dark" else "light"} file=${file.fileName} pages=${controller.state.value.book!!.pages.size} sourcePage=${requests.last().passage.page} exactUtf16=true")
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
            val path = Path.of("build/screens/ocr-native", if (dark) "dark" else "light")
            Files.createDirectories(path)
            VerifyHooks.capture(window, path.resolve("$name.png").toFile())
            ownedDialogs(window).forEachIndexed { index, dialog ->
                dialog.renderImmediately()
                VerifyHooks.capture(dialog, path.resolve("$name-dialog-$index.png").toFile())
            }
        }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 90_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label" }; Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
