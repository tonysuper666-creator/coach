package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assume.assumeTrue
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real Windows window, OS keyboard and an imported, self-authored PDF. No paired service. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class BookPageNativeWindowTest {
    private var diagnostics: () -> String = { "not initialized" }
    @Test fun keyboardReflowSourceAndPdfViewsPreserveAnchors() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-pagination-native-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val file = root.resolve("分页验收.pdf")
            PDDocument().use { pdf ->
                val font = PDType0Font.load(pdf, Path.of("../shared/ui/src/androidMain/res/font/noto_serif_sc.ttf").toFile())
                repeat(11) { index ->
                    val page = PDPage().also(pdf::addPage)
                    PDPageContentStream(pdf, page).use { out ->
                        out.beginText(); out.setFont(font, 12f); out.newLineAtOffset(50f, 740f)
                        listOf("Chapter ${index + 1} Reading", "阅读是一种缓慢而主动的工作，读者把新知识接",
                            "回已有经验，再检查前后两句之间的关系。",
                            "English readers connect new knowledge with existing",
                            "experience and check the original passage again.").forEach { line ->
                            out.showText(line); out.newLineAtOffset(0f, -14f)
                        }
                        out.endText()
                    }
                }
                pdf.save(file.toFile())
            }
            val controller = TextbookController(TextbookLibrary(root.resolve("library")))
            runBlocking {
                controller.importBook(file)
                controller.chooseLearningPath(LearningPath.READ)
                controller.enableLearning()
            }
            val originals = controller.state.value.book!!.pages.associate { it.number to it.text }
            assertEquals(11, originals.size)
            originals.values.forEach { source ->
                val model = bookParagraphs(source, true)
                assertEquals(source.length, model.display.length)
                source.indices.filterNot { source[it].isWhitespace() }.forEach { assertEquals(source[it], model.display[it]) }
                assertTrue(model.display.contains("接\u200b回"), "PDF Chinese hard line must join: ${source.replace("\n", "<LF>")}")
                assertTrue(model.display.contains("existing experience"), "PDF English hard line must join: ${source.replace("\n", "<LF>")}")
            }
            var requested: BookPassage? = null
            val support = object : BookLearningSupport {
                override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Unavailable("隔离排版验收")
                override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                    requested = request.passage
                    return BookLearningReply.Text("隔离验收回复，用于检查出处。", listOf(request.passage))
                }
            }
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                controller.library.saveTextView(controller.state.value.book!!.id, false)
                runBlocking { controller.page(1) }
                val window = edt { ComposeWindow().apply {
                    title = "LearningCoach · 分页验收（自造 PDF）"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1600, screen.width - 40), minOf(1000, screen.height - 40))
                    setContent { WindowsTextbookHost(controller, learningSupport = support) { null } }
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront(); requestFocus()
                } }
                val keysHere = AtomicInteger()
                val keyListener = AWTEventListener { event ->
                    if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED &&
                        event.keyCode in listOf(KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_PAGE_UP) &&
                        (event.source as? java.awt.Component)?.let { SwingUtilities.getWindowAncestor(it) ?: it } === window) {
                        keysHere.incrementAndGet()
                        println("KEY_ARRIVAL key=${event.keyCode} ${diagnostics()}")
                    }
                }
                Toolkit.getDefaultToolkit().addAWTEventListener(keyListener, AWTEvent.KEY_EVENT_MASK)
                diagnostics = { "windowFocused=${window.isFocused}, pageFocused=${node(window, "reader-page")?.config?.getOrNull(SemanticsProperties.Focused)}, busy=${controller.state.value.busy}, importPage=${controller.state.value.page}, receivedKeys=${keysHere.get()}" }
                try {
                    waitFor("PDF original") { node(window, "book-original-toggle") != null }
                    shot(window, dark, "pdf-original")
                    click(window, "book-original-toggle")
                    waitFor("reflow text") { node(window, "reader-text") != null }
                    shot(window, dark, "pdf-text")
                    val robot = Robot().apply { autoDelay = 50 }
                    // A semantics click does not activate the OS window. Take keyboard foreground
                    // with a real click, then focus the reader rather than the view toggle.
                    focusReader(window, robot)
                    val seen = mutableSetOf<String>()
                    fun text() = edt { node(window, "reader-text")!!.config[SemanticsProperties.Text].single().text }
                    seen += text()
                    repeat(10) { index ->
                        val previous = text()
                        pressReaderKey(window, robot, KeyEvent.VK_PAGE_DOWN, keysHere)
                        waitFor("keyboard layout page ${index + 2}") { controller.state.value.page == index + 2 && text() != previous }
                        seen += text()
                    }
                    assertEquals(11, seen.size)
                    val beforeResize = controller.state.value.page
                    shot(window, dark, "keyboard-page-11")
                    click(window, "ask-book-passage")
                    click(window, "ask-paragraph-0")
                    edt { assertTrue(node(window, "book-passage-question")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("解释这段话。"))) }
                    click(window, "book-passage-send")
                    click(window, "book-passage-citation")
                    waitFor("source dialog") { node(window, "book-passage-source") != null }
                    val passage = assertNotNull(requested)
                    assertEquals(originals.getValue(beforeResize).substring(passage.start, passage.end), passage.text)
                    assertEquals(passage.text, edt { node(window, "book-passage-source")!!.config[SemanticsProperties.Text].single().text })
                    shot(window, dark, "exact-source")
                    foreground {
                        activate(frontWindow(window))
                        robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE)
                    }
                    waitFor("source closed") { node(window, "book-passage-source") == null }
                    click(window, "book-passage-close")
                    waitFor("reader restored") { node(window, "book-passage-dialog") == null }
                    click(window, "book-original-toggle")
                    waitFor("original view restored") { node(window, "reader-text") == null }
                    shot(window, dark, "pdf-original-return")
                    click(window, "book-original-toggle")
                    waitFor("text view restored") { node(window, "reader-text") != null }
                    assertEquals(beforeResize, controller.state.value.page)
                    shot(window, dark, "pdf-text-return")
                    println("NATIVE_PAGINATION theme=${if (dark) "dark" else "light"} keyboardTurns=10 sourceExact=true viewRoundTrip=true; checking spread resize next")
                    // Leave a following chapter available, so two sheets means two
                    // actual bodies rather than an empty final right-hand sheet.
                    focusReader(window, robot)
                    val lastText = text()
                    pressReaderKey(window, robot, KeyEvent.VK_PAGE_UP, keysHere)
                    waitFor("chapter before final page") { controller.state.value.page == beforeResize - 1 && text() != lastText }
                    val anchorBeforeResize = controller.state.value.page
                    edt { window.setSize(2500, minOf(window.height, 1000)) }
                    waitFor("actual reader reaches spread threshold") {
                        edt { val area = node(window, "book-page-layout")!!.boundsInRoot
                            val scale = window.graphicsConfiguration.defaultTransform.scaleX
                            area.width / scale >= 1460 }
                    }
                    edt {
                        val scale = window.graphicsConfiguration.defaultTransform.scaleX
                        val area = node(window, "book-page-layout")!!.boundsInRoot
                        println("NATIVE_SIZE awtWidth=${window.width} scale=$scale readerPx=${area.width} readerDp=${area.width / scale}")
                    }
                    waitFor("two touching sheets with both bodies") {
                        node(window, "book-sheet-1") != null && node(window, "reader-text-right") != null
                    }
                    assertTrue(edt { node(window, "reader-text-right")!!.config[SemanticsProperties.Text].single().text.isNotBlank() })
                    shot(window, dark, "wide-spread")
                    edt { window.setSize(1100, minOf(window.height, 850)) }
                    waitFor("single sheet after resize") { node(window, "book-sheet-1") == null }
                    assertEquals(anchorBeforeResize, controller.state.value.page)
                    shot(window, dark, "narrow-single-page")

                } finally {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(keyListener)
                    edt { window.dispose() }
                }
                assertEquals(originals, controller.library.book(controller.state.value.book!!.id).pages.associate { it.number to it.text })
                println("NATIVE_PAGINATION theme=${if (dark) "dark" else "light"} keyboardTurns=10 sourceExact=true sourceOffsetsUnchanged=true spreadResize=true viewRoundTrip=true")
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }

    private fun nodes(window: java.awt.Window): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = when (window) {
            is ComposeWindow -> window.semanticsOwners
            is ComposeDialog -> window.semanticsOwners
            else -> emptyList()
        }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) } + window.ownedWindows.filter { it.isVisible }.flatMap(::nodes)
    }
    private fun node(window: ComposeWindow, tag: String) = edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(200)
    }
    private fun shot(window: ComposeWindow, dark: Boolean, name: String) {
        Thread.sleep(400)
        foreground {
            val active = frontWindow(window)
            activate(active)
            edt {
                val path = Path.of("build/screens/pagination-native", if (dark) "dark" else "light")
                Files.createDirectories(path)
                window.renderImmediately()
                VerifyHooks.capture(window, path.resolve("$name.png").toFile())
                ImageIO.write(Robot().createScreenCapture(window.bounds), "png", path.resolve("$name-window.png").toFile())
                window.ownedWindows.filterIsInstance<ComposeDialog>().filter { it.isVisible }.forEachIndexed { i, dialog ->
                    dialog.renderImmediately()
                    VerifyHooks.capture(dialog, path.resolve("$name-dialog-$i.png").toFile())
                }
            }
        }
    }
    private fun frontWindow(window: Window): Window = edt {
        window.ownedWindows.filterIsInstance<ComposeDialog>().lastOrNull { it.isVisible }?.let(::frontWindow) ?: window
    }
    private fun focusReader(window: ComposeWindow, robot: Robot) {
        foreground {
            activate(window)
            val target = edt {
                val bounds = node(window, "reader-page")!!.boundsInRoot
                val origin = window.contentPane.locationOnScreen
                val scale = window.graphicsConfiguration.defaultTransform
                Point(origin.x + (bounds.center.x / scale.scaleX).toInt(),
                    origin.y + ((bounds.bottom - 30) / scale.scaleY).toInt())
            }
            robot.mouseMove(target.x, target.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            edt { assertTrue(node(window, "reader-page")!!.config[SemanticsActions.RequestFocus].action!!.invoke()) }
            assertTrue(window.isFocused, diagnostics())
        }
        waitFor("Compose reader keyboard focus") {
            node(window, "reader-page")!!.config.getOrNull(SemanticsProperties.Focused) == true
        }
    }
    private fun pressReaderKey(window: ComposeWindow, robot: Robot, key: Int, keysHere: AtomicInteger) {
        val before = keysHere.get()
        foreground {
            activate(window)
            // Reclaiming OS foreground must preserve the reader's Compose focus.
            assertTrue(node(window, "reader-page")!!.config.getOrNull(SemanticsProperties.Focused) == true, diagnostics())
            robot.keyPress(key); robot.keyRelease(key)
            robot.waitForIdle()
        }
        waitFor("OS key $key reaches this window") { keysHere.get() > before }
        println("KEY_DELIVERED key=$key; ${diagnostics()}")
    }
    private fun activate(window: Window) {
        val handle = Native.getComponentPointer(window)
        val user = NativeUser
        repeat(10) { attempt ->
            if (user.IsIconic(handle)) user.ShowWindow(handle, 9)
            if (user.GetForegroundWindow() != handle) {
                val thread = user.GetWindowThreadProcessId(user.GetForegroundWindow(), null)
                val caller = NativeKernel.GetCurrentThreadId()
                user.GetQueueStatus(0x04ff)
                val attached = thread != 0 && caller != thread && user.AttachThreadInput(caller, thread, true)
                try {
                    user.BringWindowToTop(handle)
                    user.SetForegroundWindow(handle)
                } finally { if (attached) user.AttachThreadInput(caller, thread, false) }
            }
            Thread.sleep(150)
            if (user.GetForegroundWindow() == handle) return
            val title = CharArray(256)
            user.GetWindowTextW(user.GetForegroundWindow(), title, title.size)
            println("FOREGROUND_RETRY attempt=${attempt + 1} target=${window.javaClass.simpleName}:$handle foreground=${user.GetForegroundWindow()}:${Native.toString(title)}; ${diagnostics()}")
        }
        assertEquals(handle, user.GetForegroundWindow(), "Our window must own OS foreground; ${diagnostics()}")
    }

    private fun <T> foreground(block: () -> T): T {
        // Same short action mutex as Win-Drive. Never hold it while waiting for reflow or saving.
        val mutex = NativeKernel.CreateMutexW(null, false, WString("Global\\Coach.WinDrive.Foreground"))!!
        var held = false
        try {
            val result = NativeKernel.WaitForSingleObject(mutex, 30_000)
            held = result == 0 || result == 0x80 // An abandoned mutex also grants ownership.
            check(held) { "Foreground action mutex failed: $result" }
            return block()
        } finally { if (held) NativeKernel.ReleaseMutex(mutex); NativeKernel.CloseHandle(mutex) }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label; ${diagnostics()}" }; Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}

private interface Kernel : StdCallLibrary {
    fun CreateMutexW(attributes: Pointer?, owner: Boolean, name: WString): Pointer?
    fun WaitForSingleObject(handle: Pointer, timeout: Int): Int
    fun ReleaseMutex(handle: Pointer): Boolean
    fun CloseHandle(handle: Pointer): Boolean
    fun GetCurrentThreadId(): Int
}
private interface User : StdCallLibrary {
    fun IsIconic(window: Pointer): Boolean
    fun ShowWindow(window: Pointer, command: Int): Boolean
    fun GetWindowTextW(window: Pointer, text: CharArray, count: Int): Int
    fun GetForegroundWindow(): Pointer
    fun GetWindowThreadProcessId(window: Pointer, process: Pointer?): Int
    fun GetQueueStatus(flags: Int): Int
    fun AttachThreadInput(from: Int, to: Int, attach: Boolean): Boolean
    fun BringWindowToTop(window: Pointer): Boolean
    fun SetForegroundWindow(window: Pointer): Boolean
}
private val NativeKernel by lazy { Native.load("kernel32", Kernel::class.java) }
private val NativeUser by lazy { Native.load("user32", User::class.java) }
