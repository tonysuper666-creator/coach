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

/** 2026-11-19 round: the import-checklist remainder that does not need `shared/books`.
 *
 *  Material is authored and checked in for this round (`books/formats/fresh/author.py`), not the
 *  fixtures the 11-18 run reused: coastal-biology notes in HTML, the same article saved as `.txt` so
 *  the suffix must lose to content detection, Markdown, RTF, ODT with a table row and a footnote, and
 *  a MOBI / KF8 pair rebuilt from that text. Each theme imports into its own empty library, then a
 *  DRM Kindle must be refused with the human-readable message and leave the shelf unchanged. The
 *  passage answer is a local synthetic receipt; no model, no network, no real books or accounts.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class MultiformatFreshMaterialNativeWindowTest {
    private data class Sample(val file: String, val format: String, val pages: IntRange)

    private val samples = listOf(
        Sample("fresh-notes.html", "html", 3..3),
        Sample("fresh-notes.txt", "html", 3..3),
        Sample("fresh-coast.mobi", "mobi", 3..3),
        Sample("fresh-coast.azw3", "azw3", 3..3),
        Sample("fresh-lab.rtf", "rtf", 3..3),
        Sample("fresh-lab.odt", "odt", 3..3),
        Sample("fresh-observation.md", "md", 3..3),
    )

    /** Runs off the console too: proves the authored files parse before spending a window run. */
    @Test fun freshMaterialParsesWithExpectedFormatsAndPages() {
        val root = Files.createTempDirectory("coach-fresh-parse-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            for (sample in samples) {
                val source = copySample(root, sample.file)
                val book = library.importBook(source)
                assertEquals(sample.format, book.format, sample.file)
                assertTrue(book.pages.size in sample.pages, "${sample.file}: ${book.pages.size} pages")
                assertTrue(book.pages.all { it.text.isNotBlank() }, sample.file)
                val all = book.pages.joinToString("\n") { it.text }
                assertContains(all, "藤壶", message = sample.file)
                assertTrue(book.lessons.isNotEmpty(), sample.file)
                println("FRESH_PARSE file=${sample.file} format=${book.format} pages=${book.pages.size} label=${book.pageLabel(1)}")
            }
            val htmlText = library.importBook(copySample(root, "fresh-notes.html")).pages.joinToString("\n") { it.text }
            assertFalse(htmlText.contains("不要导入"), "nav/header/footer must be stripped")
            val odt = library.importBook(copySample(root, "fresh-lab.odt"))
            val odtText = odt.pages.joinToString("\n") { it.text }
            assertContains(odtText, "六平方毫米", message = "ODT keeps table text")
            assertContains(odtText, "脚注", message = "ODT keeps the footnote")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun freshMaterialDropPassageAndSourceInBothThemes() {
        assumeTrue(System.getProperty("coach.nativeFormats") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-native-fresh-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val files = samples.associate { it.file to copySample(root, it.file) }
            val locked = copySample(root, "fresh-locked.mobi")
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                System.setProperty("coach.windows.theme", theme)
                // Each theme gets its own brand-new empty library; nothing is reused between runs.
                val controller = TextbookController(TextbookLibrary(root.resolve("library-$theme")))
                runBlocking { controller.load() }
                assertTrue(controller.state.value.catalog.isEmpty(), "$theme: library must start empty")
                val requests = mutableListOf<BookPassageQuestion>()
                val support = object : BookLearningSupport {
                    override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Unavailable("合成验收不用整节讲解。")
                    override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                        val p = request.passage
                        assertEquals(p.text, controller.library.book(p.book).page(p.page)!!.text.substring(p.start, p.end))
                        requests += request
                        return BookLearningReply.Text("本地合成回执：这段讲的是一个生物学概念，可点出处核对原文。", listOf(p))
                    }
                }
                val window = edt { ComposeWindow().apply {
                    title = "Coach · 新写材料验收 · 本地合成讲解"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1600, screen.width - 40), minOf(950, screen.height - 150))
                    setContent { WindowsTextbookHost(controller, learningSupport = support) { null } }
                    isVisible = true
                } }
                var sourceFile = files.getValue(samples.first().file)
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
                    waitFor("initial empty shelf") { node(window, "upload-textbook") != null }
                    shot(window, dark, "shelf-empty")
                    val robot = Robot().apply { autoDelay = 40 }
                    for ((index, sample) in samples.withIndex()) {
                        val file = files.getValue(sample.file)
                        edt { sourceFile = file; source.toFront() }
                        val from = edt { source.contentPane.locationOnScreen.apply { translate(80, 12) } }
                        val to = edt { window.contentPane.locationOnScreen.apply { translate(window.width / 2, window.height / 2) } }
                        robot.mouseMove(from.x, from.y)
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                        try {
                            repeat(30) { step -> robot.mouseMove(from.x + (to.x - from.x) * (step + 1) / 30, from.y + (to.y - from.y) * (step + 1) / 30) }
                        } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        waitFor("native drop ${sample.file}; ${controller.state.value.error}") {
                            controller.state.value.let { !it.busy && it.prompt == BookPrompt.IMPORTED && it.catalog.size == index + 1 }
                        }
                        assertNull(controller.state.value.error, sample.file)
                        click(window, "read-only")
                        waitFor("after reading") { controller.state.value.prompt == BookPrompt.AFTER_READING }
                        click(window, "read-only")
                        waitFor("reader") { controller.state.value.prompt == null && node(window, "ask-book-passage") != null }
                        shot(window, dark, "${sample.file}-reader")
                        val book = controller.state.value.book!!
                        assertEquals(sample.format, book.format, sample.file)
                        assertTrue(book.pages.size in sample.pages, "${sample.file}: ${book.pages.size} pages")
                        click(window, "ask-book-passage")
                        click(window, "ask-paragraph-0")
                        waitFor("question field") { node(window, "book-passage-question") != null }
                        edt { assertTrue(node(window, "book-passage-question")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("请解释这段话，并给一个例子。"))) }
                        click(window, "book-passage-send")
                        waitFor("local synthetic reply") { requests.size == index + 1 && node(window, "book-passage-citation") != null }
                        // Text-class imports number pages by import-time blocks, so the label is 本章第 N 段
                        // and must not pretend to be a printed page number (fa7544a7).
                        val citation = edt {
                            nodes(window).firstNotNullOfOrNull { n ->
                                n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                                    ?.takeIf { it.startsWith("出处 · ") }
                            }
                        }
                        val label = checkNotNull(citation) { "${sample.file}: 回答卡上没有出处" }.removePrefix("出处 · ")
                        assertTrue(Regex("本章第 \\d+ 段").matches(label), "${sample.file}: $label")
                        shot(window, dark, "${sample.file}-reply")
                        click(window, "book-passage-citation")
                        waitFor("source") { node(window, "book-passage-source") != null }
                        val shown = edt { node(window, "book-passage-source")!!.config[SemanticsProperties.Text].joinToString("") { it.text } }
                        assertEquals(requests.last().passage.text, shown)
                        val title = edt { node(window, "book-passage-source-title")!!.config[SemanticsProperties.Text].joinToString("") { it.text } }
                        assertEquals("原文 · $label", title)
                        shot(window, dark, "${sample.file}-source")
                        clickText(window, "回到问题")
                        click(window, "book-passage-close")
                        println("NATIVE_FRESH_FORMAT theme=$theme file=${sample.file} format=${book.format} pages=${book.pages.size} citation=$label sourcePage=${requests.last().passage.page} exactUtf16=true libraryBefore=empty")
                    }
                    // A DRM Kindle must be refused in the same window with the actionable message,
                    // and must not add a book or disturb the seven already imported.
                    val before = controller.state.value.catalog.size
                    edt { sourceFile = locked; source.toFront() }
                    val from = edt { source.contentPane.locationOnScreen.apply { translate(80, 12) } }
                    val to = edt { window.contentPane.locationOnScreen.apply { translate(window.width / 2, window.height / 2) } }
                    robot.mouseMove(from.x, from.y)
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    try {
                        repeat(30) { step -> robot.mouseMove(from.x + (to.x - from.x) * (step + 1) / 30, from.y + (to.y - from.y) * (step + 1) / 30) }
                    } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                    waitFor("DRM refusal") { controller.state.value.let { !it.busy && it.error != null } }
                    val message = controller.state.value.error.orEmpty()
                    assertContains(message, "版权保护", message = "DRM Kindle message")
                    assertEquals(before, controller.state.value.catalog.size, "$theme: rejected file must not join the shelf")
                    val shownMessage = edt { textNodes(window).any { it == message } }
                    assertTrue(shownMessage, "$theme: 版权保护提示没有显示在窗口里：$message")
                    shot(window, dark, "fresh-locked-refused")
                    println("NATIVE_FRESH_REJECT theme=$theme file=fresh-locked.mobi catalog=$before unchanged=true message=$message")
                } finally { edt { source.dispose(); window.dispose() } }
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }

    private fun copySample(root: Path, name: String): Path = root.resolve(name).also { file ->
        checkNotNull(javaClass.getResourceAsStream("/books/formats/fresh/$name")).use {
            Files.copy(it, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun textNodes(window: ComposeWindow): List<String> = nodes(window).flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
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
            val path = Path.of("build/screens/formats-native-fresh", if (dark) "dark" else "light")
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
