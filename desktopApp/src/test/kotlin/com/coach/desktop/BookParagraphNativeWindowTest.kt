package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import javax.imageio.ImageIO
import kotlin.test.*

/** Real Windows window, OS keyboard and an imported, self-authored PDF. No paired service. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class BookParagraphNativeWindowTest {
    @Test fun keyboardPagesSourceAndPdfViewsPreserveOriginalOffsets() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-paragraph-native-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val file = root.resolve("段落验收.pdf")
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
            // #570（TON-1784）起问一段走伴读侧栏，请求经 BookExplainer 发出；这里替身只记下被选中的那一段。
            var requested: SourceBlock? = null
            val explainer = object : BookExplainer(library = controller.library) {
                override suspend fun load() {
                    loaded = true
                    identity = DesktopIdentity(serverUrl = "http://127.0.0.1:1", credentials = PairedCredentials("desktop", "tok", "owner"))
                }
                override suspend fun explainSelection(kind: String, chosen: SourceBlock, context: ExplainSectionContext?,
                    question: String?, history: List<FollowUpTurn>, requestId: String): ExplainResult {
                    requested = chosen
                    return ExplainResult.Done(Explanation("隔离验收回复，用于检查出处。【S1】", listOf(Citation("S1", chosen.id, chosen.page)), 10L))
                }
            }
            for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                controller.library.saveTextView(controller.state.value.book!!.id, false)
                runBlocking { controller.page(1) }
                // 同批次里前一个原生用例的置顶窗口可能还盖在上面：真实点击和截图都会落到它那儿，
                // 等它真的关掉再摆这一轮的窗口。
                NativeWindowForeground.awaitOtherWindowsClosed(keep = null)
                val window = edt { ComposeWindow().apply {
                    title = "LearningCoach · 段落验收（自造 PDF）"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1600, screen.width - 40), minOf(1000, screen.height - 40))
                    setContent { WindowsTextbookHost(controller, bookExplainer = explainer) { null } }
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront(); requestFocus()
                } }
                try {
                    waitFor("PDF original") { node(window, "immersive-more-menu") != null && (node(window, "original-pdf-paged") != null || node(window, "original-pdf-scroll") != null) }
                    shot(window, dark, "pdf-original")
                    selectView(window, text = true)
                    waitFor("reflow text") { node(window, "reader-text") != null }
                    shot(window, dark, "pdf-text")
                    val robot = Robot().apply { autoDelay = 50 }
                    // A semantics click does not activate the OS window. Take keyboard foreground
                    // with a real click, then focus the reader rather than the view toggle.
                    focusReader(window, robot)
                    waitFor("native reader keyboard focus") {
                        edt { window.isFocused && node(window, "reader-page")!!.config.getOrNull(SemanticsProperties.Focused) == true }
                    }
                    // 十次翻页之间前台可能被另一道作业拿走（winbox 两个槽共用一个控制台会话），
                    // 被吃掉的那次按键根本进不了这个进程。所以每次按键都在前台互斥量里送到本窗口，
                    // 并核对键真的到过这里：没到就重发（它没到过应用，不算产品没翻页）；到了却没翻页才是产品问题。
                    val arrivals = WindowKeyArrivals(window).start()
                    try {
                        repeat(10) { index ->
                            val page = index + 2
                            var resends = 0
                            while (true) {
                                val before = arrivals.count
                                NativeWindowForeground.action {
                                    NativeWindowForeground.activate(window) { diagnostics(window) }
                                    assertTrue(edt { node(window, "reader-page")!!.config.getOrNull(SemanticsProperties.Focused) == true },
                                        "reader lost Compose focus before page $page; ${diagnostics(window)}")
                                    robot.keyPress(KeyEvent.VK_PAGE_DOWN); robot.keyRelease(KeyEvent.VK_PAGE_DOWN)
                                    robot.waitForIdle()
                                }
                                if (awaitArrival(arrivals, before)) break
                                if (resends++ >= 2) fail("PageDown never reached this window for page $page; ${diagnostics(window)}")
                                println("TON-1700 keyboard page $page: PageDown went to another window; re-sending")
                            }
                            waitFor("keyboard page $page", { diagnostics(window) }) { controller.state.value.page == page }
                        }
                    } finally { arrivals.stop() }
                    assertEquals(11, controller.state.value.page)
                    shot(window, dark, "keyboard-page-11")
                    // Resize the actual window; this card still uses the existing single column.
                    edt { window.setSize(1100, minOf(window.height, 850)) }
                    shot(window, dark, "narrow-single-column")
                    // #570（TON-1784）起「问这段」在伴读侧栏：开侧栏 → 提问 → 选本页段落 → 在侧栏输入框发问；
                    // 原来的出处弹窗已删，出处是回复里的引用，点它回到原文。
                    requested = null
                    click(window, "immersive-companion-toggle")
                    click(window, "companion-tab-ask")
                    // TON-1848 起这本书上一次的问答存进 SQLite，再开提问页会接着显示：上一轮问过的那段还挂在引文里，
                    // 「选本页段落」要等引文清掉才出现。像人一样先点引文旁的「清除」，再另选一段。
                    waitFor("companion ask pane") { node(window, "companion-ask-pane") != null }
                    if (node(window, "companion-ask-quote") != null) {
                        clickText(window, "清除")
                        waitFor("restored quote cleared") { node(window, "companion-ask-quote") == null }
                    }
                    click(window, "companion-pick-paragraph")
                    click(window, "ask-passage-block-0")
                    waitFor("companion question input") { node(window, "inline-followup-input") != null }
                    edt { assertTrue(node(window, "inline-followup-input")!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("解释这段话。"))) }
                    click(window, "inline-followup-send")
                    waitFor("companion reply with its citation") { requested != null && node(window, "explain-citation") != null }
                    val passage = assertNotNull(requested)
                    assertEquals(11, passage.page)
                    assertEquals(originals.getValue(11).substring(passage.start, passage.end), passage.text)
                    shot(window, dark, "exact-source")
                    click(window, "explain-citation")
                    waitFor("citation keeps the asked page") { !controller.state.value.busy && controller.state.value.page == 11 }
                    click(window, "companion-close")
                    waitFor("reader restored") { node(window, "immersive-companion-drawer") == null }
                    // TON-1802 起点出处会直接切到原页去标原句；「原页」那一段此时已选中，再点不响应。
                    // 改为确认出处把人带到了原页，再切回文字，往返照旧走一遍。
                    waitFor("citation opens the original page") { node(window, "reader-text") == null }
                    shot(window, dark, "pdf-original-return")
                    selectView(window, text = true)
                    waitFor("text view restored") { node(window, "reader-text") != null }
                    assertEquals(11, controller.state.value.page)
                    shot(window, dark, "pdf-text-return")
                } finally { edt { window.dispose() } }
                assertEquals(originals, controller.library.book(controller.state.value.book!!.id).pages.associate { it.number to it.text })
                println("NATIVE_PARAGRAPHS theme=${if (dark) "dark" else "light"} keyboardTurns=10 sourceExact=true sourceOffsetsUnchanged=true singleColumn=true viewRoundTrip=true")
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
    // #570（TON-1784）把「原页 / 文字」切换收进顶栏「更多」菜单里的分段胶囊：先开菜单，再点要去的那一段。
    private fun selectView(window: ComposeWindow, text: Boolean) {
        // 选中一段后菜单自己收起（有退出动画）；等它真正消失，下一次再开菜单才不会撞上正在关的那一个。
        click(window, "immersive-more-menu")
        click(window, if (text) "view-mode-text" else "view-mode-original")
        waitFor("more menu closed") { node(window, "view-mode-text") == null }
    }
    private fun clickText(window: ComposeWindow, text: String) {
        val target = edt { nodes(window).firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && n.config.getOrNull(SemanticsActions.OnClick) != null
        } }
        edt { assertTrue(assertNotNull(target, text).config[SemanticsActions.OnClick].action!!.invoke(), text) }
        Thread.sleep(200)
    }
    private fun click(window: ComposeWindow, tag: String) {
        // 等不到按钮时把侧栏里此刻有哪些控件一起报出来，看得出是侧栏没开、停在别的分页，还是那一段没有可选的段落。
        waitFor(tag, {
            "tags=" + edt { nodes(window).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
                .filter { it.startsWith("companion") || it.startsWith("ask") || it.startsWith("inline") || it.startsWith("immersive") }.distinct() }
        }) { node(window, tag) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
        Thread.sleep(200)
    }
    private fun shot(window: ComposeWindow, dark: Boolean, name: String) {
        Thread.sleep(400)
        // 截图和置顶窗口的前台一起拿：别的作业的窗口压在上面时，拍到的就不是这一页。
        NativeWindowForeground.action {
            NativeWindowForeground.activate(window) { diagnostics(window) }
            edt {
                val path = Path.of("build/screens/paragraph-native", if (dark) "dark" else "light")
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
    private fun focusReader(window: ComposeWindow, robot: Robot) {
        NativeWindowForeground.action {
            NativeWindowForeground.activate(window) { diagnostics(window) }
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
            assertTrue(window.isFocused, "the click did not leave this window with the keyboard foreground; ${diagnostics(window)}")
        }
    }
    private fun awaitArrival(arrivals: WindowKeyArrivals, before: Int, timeoutMillis: Long = 2_000): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (arrivals.count <= before) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(20)
        }
        return true
    }
    private fun diagnostics(window: ComposeWindow): String = runCatching {
        "${NativeWindowForeground.describe(window)} readerFocused=${node(window, "reader-page")?.config?.getOrNull(SemanticsProperties.Focused)}"
    }.getOrElse { "diagnostics unavailable: $it" }
    private fun waitFor(label: String, diagnose: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                val detail = diagnose().takeIf { it.isNotBlank() }?.let { "; $it" }.orEmpty()
                "Timed out: $label$detail"
            }
            Thread.sleep(100)
        }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
