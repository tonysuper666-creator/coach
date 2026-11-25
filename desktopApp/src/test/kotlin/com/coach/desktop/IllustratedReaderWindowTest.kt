package com.coach.desktop

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import com.coach.ui.pageturn.PageTurnStyle
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import com.coach.desktop.books.*
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.*
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assume.assumeTrue
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

/** Captures only this window's rendered surface. No screen capture or desktop-global input. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class IllustratedReaderWindowTest {
    private val caption = "Figure 1. A wave over one period"
    private val intro = "Follow one cycle of the wave from left to right."
    private val after = "At the end of the period, the signal returns to its starting value."

    private fun fixture(dir: Path, format: String): Path {
        val path = dir.resolve("Waves.$format")
        val image = BufferedImage(900, 205, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(250, 248, 241)
            g.fillRect(0, 0, image.width, image.height)
            g.color = Color(165, 158, 145)
            g.drawLine(30, 102, 870, 102)
            val curve = java.awt.geom.Path2D.Double().apply {
                moveTo(30.0, 102.0)
                for (x in 1..840) lineTo(30.0 + x, 102 - 72 * kotlin.math.sin(x * 2 * Math.PI / 840))
            }
            g.color = Color(28, 104, 102)
            g.stroke = BasicStroke(4f)
            g.draw(curve)
        } finally { g.dispose() }
        when (format) {
            "md" -> {
                ImageIO.write(image, "png", dir.resolve("wave.png").toFile())
                Files.writeString(path, "$intro\n\n![$caption](wave.png)\n\n$after\n\n" + "$after ".repeat(100))
            }
            "epub" -> ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                fun entry(name: String, body: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
                entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>")
                entry("book.opf", "<package><metadata><title>Waves</title></metadata><manifest><item id='c' href='waves.xhtml'/></manifest><spine><itemref idref='c'/></spine></package>")
                entry("waves.xhtml", "<html><body><p>$intro</p><figure><img src='wave.png'/><figcaption>$caption</figcaption></figure><p>$after</p></body></html>")
                zip.putNextEntry(ZipEntry("wave.png"))
                ImageIO.write(image, "png", zip)
                zip.closeEntry()
            }
            "pdf" -> PDDocument().use { pdf ->
                val page = PDPage().also(pdf::addPage)
                PDPageContentStream(pdf, page).use { out ->
                    fun line(y: Float, value: String) {
                        out.beginText()
                        out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        out.newLineAtOffset(50f, y)
                        out.showText(value)
                        out.endText()
                    }
                    line(740f, intro)
                    out.drawImage(LosslessFactory.createFromImage(pdf, image), 50f, 590f, 450f, 102.5f)
                    line(565f, caption)
                    line(530f, after)
                    out.moveTo(50f, 420f)
                    out.lineTo(130f, 480f)
                    out.lineTo(210f, 420f)
                    out.stroke()
                    line(398f, "Figure 2. A triangular pulse")
                }
                pdf.save(path.toFile())
            }
        }
        return path
    }

    private fun <T> edt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = java.util.concurrent.FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }

    private fun nodes(window: Window): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        val owners = when (window) {
            is ComposeWindow -> window.semanticsOwners
            is ComposeDialog -> window.semanticsOwners
            else -> emptyList()
        }
        return owners.flatMap { descendants(it.unmergedRootSemanticsNode) } +
            window.ownedWindows.filter { it.isVisible }.flatMap(::nodes)
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!edt(ready)) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $label" }
            Thread.sleep(40)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun pointerAndKeyboardDismissWithoutMovingTheReadingAnchor() {
        val dir = Files.createTempDirectory("coach-figure-input-")
        try {
            val library = TextbookLibrary(dir.resolve("library"))
            val book = library.importBook(fixture(dir, "md"))
            val figure = book.pages.first().illustrations.single()
            for (style in listOf(PageTurnStyle.Flip, PageTurnStyle.Slide)) {
                runSkikoComposeUiTest(size = Size(1440f, 960f), density = Density(1f)) {
                    val state = BookPagingState(BookPageAnchor(1))
                    setContent { CoachTheme(false) {
                        BookPageView(book, state, emptyMap(), { state.move(it) },
                            libraryDirectory = library.directory, turnStyle = style)
                    } }
                    val inline = onNodeWithTag("book-figure-${figure.page}-${figure.order}")
                    waitUntil(timeoutMillis = 20_000) { runCatching { inline.assertExists() }.isSuccess }
                    val anchor = state.anchor
                    inline.performTouchInput { click() }
                    val close = onNodeWithTag("figure-close")
                    close.assertExists()
                    onNodeWithTag("figure-full-image").performTouchInput { click() }
                    close.performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.PageDown) }
                    close.assertExists()
                    close.performKeyInput { pressKey(Key.Escape) }
                    close.assertDoesNotExist()
                    assertEquals(anchor, state.anchor)
                    inline.performTouchInput { click() }
                    onNodeWithTag("figure-backdrop").performTouchInput { click(Offset(12f, 12f)) }
                    close.assertDoesNotExist()
                    assertEquals(anchor, state.anchor)
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun importedFiguresInLightAndDarkReaders() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val originalTheme = System.getProperty("coach.windows.theme")
        val root = Files.createTempDirectory("coach-figure-window-")
        try {
            for (format in listOf("md", "epub", "pdf")) {
                val dir = Files.createDirectory(root.resolve(format))
                val library = TextbookLibrary(dir.resolve("library"))
                val book = library.importBook(fixture(dir, format))
                assertEquals(if (format == "pdf") 2 else 1, book.pages.sumOf { it.illustrations.size })
                library.save(book.copy(promptSeen = true))
                for (theme in listOf("light", "dark")) {
                    System.setProperty("coach.windows.theme", theme)
                    val controller = TextbookController(library)
                    val window = edt { ComposeWindow().apply {
                        setAutoRequestFocus(false)
                        setSize(1440, 960)
                        setContent { WindowsTextbookHost(controller) { null } }
                        isVisible = true
                    } }
                    try {
                        fun tagged(tag: String) = nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
                        fun click(tag: String): Float {
                            var width = 0f
                            await(tag) {
                                val node = tagged(tag) ?: return@await false
                                width = node.boundsInRoot.width
                                width > 0f && node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
                            }
                            return width
                        }
                        fun shot(name: String) {
                            // The semantics tree is ready before the native enter transition finishes.
                            Thread.sleep(600)
                            edt {
                                val path = Path.of("build/screens/illustrated-reader/$format-$theme-$name.png")
                                Files.createDirectories(path.parent)
                                val dialog = window.ownedWindows.filterIsInstance<ComposeDialog>().lastOrNull { it.isVisible }
                                if (dialog != null) dialog.renderImmediately() else window.renderImmediately()
                                VerifyHooks.capture(dialog ?: window, path.toFile())
                            }
                        }
                        // TON-1782 起重开零点击回原位：同一本书第二次开窗直接回到阅读器，只有落在书架时才点「看书」。
                        await("shelf or restored reader") {
                            tagged("home-read") != null || controller.state.value.screen == BookScreen.READER
                        }
                        if (controller.state.value.screen != BookScreen.READER) click("home-read")
                        if (format == "pdf" && library.savedTextView(book.id) != true) {
                            // #570（TON-1784）起「原页 / 文字」在顶栏「更多」菜单里，切到文字版才看得到图注。
                            click("immersive-more-menu")
                            click("view-mode-text")
                        }
                        await("loaded figure in $format") { nodes(window).any {
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(caption) == true
                        } }
                        shot("inline")
                        val figure = book.pages.first().illustrations.first { it.caption == caption }
                        val figureTag = "book-figure-${figure.page}-${figure.order}"
                        // #570（TON-1784）的沉浸阅读器把翻页样式定为翻转，窗口里不再有 turn-style 切换；
                        // 滑动样式下的放大 / 关闭仍由上面的 pointerAndKeyboardDismissWithoutMovingTheReadingAnchor 覆盖。
                        for (style in listOf("flip")) {
                            val sources = edt { nodes(window).mapNotNull { it.config.getOrNull(BookPageSources) } }
                            assertTrue(sources.isNotEmpty())
                            val inlineWidth = click(figureTag)
                            await("expanded $style caption") {
                                tagged("figure-caption")?.config?.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == caption
                            }
                            await("expanded image at least as wide as the inline figure") {
                                (tagged("figure-full-image")?.boundsInRoot?.width ?: 0f) >= inlineWidth
                            }
                            shot("expanded-$style")
                            click("figure-close")
                            await("returned to inline figure") { tagged("figure-preview") == null && tagged(figureTag) != null }
                            assertEquals(sources, edt { nodes(window).mapNotNull { it.config.getOrNull(BookPageSources) } })
                        }
                    } finally { edt { window.dispose() } }
                    assertEquals(book.pages, library.book(book.id).pages)
                }
            }
        } finally {
            if (originalTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", originalTheme)
            root.toFile().deleteRecursively()
        }
    }
}
