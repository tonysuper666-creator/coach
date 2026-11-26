package com.coach.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
class MultiformatNativeWindowTest {
    @Test fun nativeFileDropAndPassageSourceInBothThemes() {
        assumeTrue(System.getProperty("coach.nativeFormats") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-native-formats-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val files = listOf("gbk.txt", "gb18030.txt", "lesson.docx", "lesson.pptx", "lesson.mobi").map { name ->
                root.resolve(name).also { file ->
                    checkNotNull(javaClass.getResourceAsStream("/books/formats/$name")).use { Files.copy(it, file) }
                    if (name == "gb18030.txt") {
                        // The base fixture is also valid GBK. Add a four-byte character for this drag.
                        Files.writeString(file, "\n\n扩展汉字示例：𠮷。", Charset.forName("GB18030"), StandardOpenOption.APPEND)
                    }
                }
            }
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                val library = TextbookLibrary(root.resolve(if (dark) "dark" else "light"), streamPdfExtras = true, lazyPdfExtras = true)
                val controller = TextbookController(library)
                runBlocking { controller.load() }
                val requests = mutableListOf<SourceBlock>()
                val explainer = object : BookExplainer(DesktopConnectionSettings(home = root, environment = { null }), library) {
                    init {
                        loaded = true
                        identity = DesktopIdentity("http://127.0.0.1:1", PairedCredentials("test-device", "test-token"))
                    }

                    override suspend fun explainSelection(
                        kind: String, chosen: SourceBlock, context: ExplainSectionContext?,
                        question: String?, history: List<FollowUpTurn>, requestId: String,
                    ): ExplainResult {
                        assertEquals("ask", kind)
                        assertEquals("请解释这个概念，并给一个例子。", question)
                        val book = checkNotNull(controller.state.value.book)
                        assertEquals(chosen.text, library.book(book.id).page(chosen.page)!!.text.substring(chosen.start, chosen.end))
                        requests += chosen
                        return ExplainResult.Done(Explanation(
                            text = "本地合成回执：这段介绍一个生物学概念。",
                            citations = listOf(Citation("S1", chosen.id, chosen.page)),
                            elapsedMs = 1,
                        ))
                    }
                }
                val window = edt { ComposeWindow().apply {
                    title = "Coach · 自造材料验收 · 本地合成讲解"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1600, screen.width - 40), minOf(950, screen.height - 150))
                    setContent { WindowsTextbookHost(controller, bookExplainer = explainer) { null } }
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
                        NativeWindowForeground.action {
                            edt { sourceFile = file; source.toFront() }
                            NativeWindowForeground.activate(source)
                            val from = edt { source.contentPane.locationOnScreen.apply { translate(80, 12) } }
                            val to = edt { window.contentPane.locationOnScreen.apply { translate(window.width / 2, window.height / 2) } }
                            robot.mouseMove(from.x, from.y)
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                            try {
                                repeat(30) { step -> robot.mouseMove(from.x + (to.x - from.x) * (step + 1) / 30, from.y + (to.y - from.y) * (step + 1) / 30) }
                            } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        }
                        waitFor("native drop ${file.fileName}; ${controller.state.value.error}") {
                            controller.state.value.let { !it.busy && it.screen == BookScreen.READER && it.catalog.size == index + 1 }
                        }
                        assertNull(controller.state.value.error)
                        assertNull(controller.state.value.prompt)
                        val book = assertNotNull(controller.state.value.book)
                        assertEquals(file.toString().substringAfterLast('.'), book.format)
                        assertEquals(LearningPath.READ, controller.state.value.learningPath)
                        if (file.fileName.toString() == "gb18030.txt") assertContains(book.pages.last().text, "𠮷")
                        shot(window, dark, "${file.fileName}-reader")
                        click(window, "immersive-companion-toggle")
                        click(window, "companion-tab-ask")
                        click(window, "companion-pick-paragraph")
                        click(window, "ask-passage-block-0")
                        waitFor("question field") { node(window, "inline-followup-input") != null }
                        edt { assertTrue(node(window, "inline-followup-input")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("请解释这个概念，并给一个例子。"))) }
                        click(window, "inline-followup-send")
                        waitFor("local synthetic reply") { edt { requests.size == index + 1 } && node(window, "explain-citation") != null }
                        val block = edt { requests.last() }
                        val location = if (book.format == "pptx") book.pageLabel(block.page) else "阅读器第 ${block.page} 页"
                        val citation = edt { node(window, "explain-citation")!!.config[SemanticsProperties.Text].joinToString("") { it.text } }
                        assertContains(citation, location)
                        shot(window, dark, "${file.fileName}-reply")
                        assertFalse(sourceHighlighted(window, block))
                        click(window, "explain-citation")
                        waitFor("highlighted UTF-16 source") {
                            controller.state.value.page == block.page && sourceHighlighted(window, block)
                        }
                        shot(window, dark, "${file.fileName}-source")
                        println("NATIVE_FORMAT theme=${if (dark) "dark" else "light"} file=${file.fileName} pages=${book.pages.size} sourcePage=${block.page} exactUtf16=true")
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
    private fun sourceHighlighted(window: ComposeWindow, block: SourceBlock): Boolean = edt {
        nodes(window).any { node ->
            val slices = node.config.getOrNull(BookPageSources) ?: return@any false
            val text = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull() ?: return@any false
            var offset = 0
            slices.any { slice ->
                val start = offset + block.start - slice.startUtf16
                val end = offset + block.end - slice.startUtf16
                offset += slice.endUtf16 - slice.startUtf16
                slice.importPage == block.page && block.start >= slice.startUtf16 && block.end <= slice.endUtf16 &&
                    end <= text.length && text.text.substring(start, end) == block.text &&
                    text.spanStyles.any { it.start <= start && it.end >= end && it.item.background != Color.Unspecified }
            }
        }
    }
    private fun shot(window: ComposeWindow, dark: Boolean, name: String) {
        Thread.sleep(350)
        edt {
            window.renderImmediately()
            val path = Path.of("build/screens/formats-native", if (dark) "dark" else "light")
            Files.createDirectories(path)
            VerifyHooks.capture(window, path.resolve("$name.png").toFile())
            ownedDialogs(window).forEachIndexed { index, dialog ->
                dialog.renderImmediately()
                VerifyHooks.capture(dialog, path.resolve("$name-dialog-$index.png").toFile())
            }
        }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label" }; Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
