package com.coach.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Point
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real Windows window, native pointer input, and an isolated book and paired service. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class OriginalPdfNativeWindowTest {
    @Test fun draggingTheSecondOccurrenceExplainsHighlightsAndJumpsToThatExactOccurrence() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-original-pdf-native-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(fixture(root))
        val page = book.pages.single()
        val phrase = "The second column repeats this sentence."
        val first = page.text.indexOf(phrase)
        val second = page.text.lastIndexOf(phrase)
        assertTrue(first >= 0 && second > first)
        val range = ReaderTextRange(second, second + phrase.length)
        val unit = assertNotNull(library.pdfTextLayer(book, page))
        val rects = PdfTextSelection.boxesForVerifiedRange(unit, page.text, range)
        assertTrue(rects.size > 2)
        LoopbackExplanation().use { service ->
            val settings = DesktopConnectionSettings(root.resolve("paired"), environment = { null },
                property = { if (it == "jpackage.app-path") "/synthetic/Coach.exe" else null })
            val explainer = BookExplainer(settings, library)
            assertTrue(runBlocking { explainer.pair(service.url, "123456") })
            ExplainConsent(library.directory).allow(book.id)
            val questions = PairedBookLearningSupport(library, send = { block, question -> explainer.ask(block, question) })
            for (dark in listOf(false, true)) {
                val window = edt { ComposeWindow().apply {
                    title = "LearningCoach · 原版拖选验收"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1220, screen.width - 40), minOf(900, screen.height - 40))
                    setContent { CoachTheme(darkTheme = dark) {
                        Surface(color = CoachTheme.colors.bgCanvas) {
                            TextbookReader(LibraryUi(book = book, page = 1, screen = BookScreen.READER), library,
                                questions, null, remember { FocusRequester() },
                                onNoteSession = {}, onPage = {}, onBack = {}, onLearn = {}, explainProvider = explainer)
                        }
                    } }
                    isAlwaysOnTop = true
                    isVisible = true
                } }
                try {
                    waitFor("original PDF default") { node(window, "original-pdf-text-layer") != null }
                    assertNull(node(window, "reader-text"))
                    shot(window, dark, "original")
                    drag(window, rects.first(), rects.last())
                    waitFor("visible native drag selection", { "expected=$range actual=" + selection(window) }) {
                        selection(window) == range
                    }
                    shot(window, dark, "drag-selection")
                    if (!dark) {
                        click(window, "ask-book-passage")
                        waitFor("question prefilled with selected text") { node(window, "book-passage-question") != null }
                        edt {
                            val field = checkNotNull(node(window, "book-passage-question"))
                            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("What does this sentence show?")))
                        }
                        click(window, "book-passage-send")
                        waitFor("selected passage question reply") { node(window, "book-passage-citation") != null }
                        val asked = checkNotNull(service.last.get())
                        assertEquals("ask", asked.getValue("kind").jsonPrimitive.content)
                        assertEquals(phrase, asked.getValue("selection").jsonPrimitive.content)
                        assertEquals("${book.id}:p1:$second", asked.getValue("blocks").jsonArray.single().jsonObject
                            .getValue("id").jsonPrimitive.content)
                        click(window, "book-passage-citation")
                        waitFor("question citation visible on original") { node(window, "remote-highlight") != null }
                        drag(window, rects.first(), rects.last())
                        waitFor("reselected original text") { selection(window) == range }
                    }
                    click(window, "book-explain-toggle")
                    click(window, "explain-button")
                    waitFor("paired explanation") { node(window, "explain-citation") != null }
                    val request = checkNotNull(service.last.get())
                    assertEquals(phrase, request.getValue("selection").jsonPrimitive.content)
                    val sent = request.getValue("blocks").jsonArray.single().jsonObject
                    assertEquals("${book.id}:p1:$second", sent.getValue("id").jsonPrimitive.content)
                    assertEquals(phrase, sent.getValue("text").jsonPrimitive.content)
                    shot(window, dark, "explanation")
                    click(window, "explain-citation")
                    waitFor("exact source visible") {
                        node(window, "remote-highlight") != null && selection(window) == null &&
                            node(window, "explain-button") == null
                    }
                    shot(window, dark, "cite-original")
                    if (!dark) {
                        // The cited line has been scrolled into the viewport. Hit it in the same
                        // physical page again after re-opening the ordinary reading position.
                        click(window, "book-original-toggle")
                        waitFor("text view") { node(window, "reader-text") != null }
                        assertEquals(true, TextbookLibrary(library.directory).savedTextView(book.id))
                        click(window, "book-original-toggle")
                        waitFor("original view") { node(window, "original-pdf-text-layer") != null }
                    }
                    drag(window, rects.first(), rects.last())
                    waitFor("selection before saving") { selection(window) == range }
                    val hasMark = TextbookLibrary(library.directory).highlights(book.id, page).contains(range)
                    click(window, "original-pdf-highlight")
                    waitFor("saved highlight") { selection(window) == null }
                    assertEquals(!hasMark, TextbookLibrary(library.directory).highlights(book.id, page).contains(range))
                    shot(window, dark, if (hasMark) "highlight-removed" else "highlight-saved")
                } finally { edt { window.dispose() } }
            }
        }
        println("ORIGINAL_PDF_NATIVE themes=2 exactSelection=true exactExplanation=true exactCitation=true persistedHighlight=true")
    }

    private fun fixture(root: Path): Path = root.resolve("Original PDF acceptance.pdf").also { file ->
        PDDocument().use { pdf ->
            val page = PDPage().also(pdf::addPage)
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            PDPageContentStream(pdf, page).use { out ->
                fun line(x: Float, y: Float, text: String, size: Float = 12f) {
                    out.beginText(); out.setFont(font, size); out.newLineAtOffset(x, y); out.showText(text); out.endText()
                }
                line(50f, 755f, "Learning from the original page", 20f)
                line(50f, 703f, "The second column repeats this sentence.")
                line(318f, 682f, "The second column repeats this sentence.")
                out.addRect(50f, 540f, 510f, 100f); out.stroke()
                line(65f, 606f, "Knowledge box")
                line(65f, 575f, "An equation keeps its spacing: E = m c^2")
            }
            pdf.save(file.toFile())
        }
    }

    private class LoopbackExplanation : AutoCloseable {
        val last = AtomicReference<JsonObject?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}"
        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes() }.decodeToString()
                val (code, reply) = when (exchange.requestURI.path) {
                    "/v1/pair" -> 200 to """{"deviceId":"synthetic-reader","token":"${"a".repeat(43)}"}"""
                    "/v1/hello" -> 200 to """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                    "/v1/library/explain" -> {
                        val request = Json.parseToJsonElement(body).jsonObject.also(last::set)
                        val id = request.getValue("blocks").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
                        200 to """{"text":"A repeated sentence tests location accuracy.【S1】","sources":[{"docId":"$id","pdfPage":1,"anchor":"S1"}]}"""
                    }
                    else -> 404 to "{}"
                }
                val bytes = reply.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    private fun selection(window: ComposeWindow) = node(window, "original-pdf-text-layer")?.config?.getOrNull(OriginalPdfSelectionRange)

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? = edt {
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

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag)?.config?.getOrNull(SemanticsActions.OnClick) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
    }

    private fun drag(window: ComposeWindow, from: ReaderRect, to: ReaderRect) {
        repeat(3) {
            val arrived = NativeWindowForeground.action {
                NativeWindowForeground.activate(window)
                val (start, end) = edt {
                    val origin = window.contentPane.locationOnScreen
                    val scale = window.graphicsConfiguration.defaultTransform
                    // Semantics bounds can be clipped by the scroll viewport. Use the full bitmap size.
                    val image = node(window, "remote-image")!!
                    val top = image.positionInRoot
                    fun point(box: ReaderRect) = Point(
                        origin.x + ((top.x + (box.x + box.width / 2) * image.size.width) / scale.scaleX).toInt(),
                        origin.y + ((top.y + (box.y + box.height / 2) * image.size.height) / scale.scaleY).toInt(),
                    )
                    point(from) to point(to)
                }
                val robot = Robot().apply { autoDelay = 35 }
                val arrivals = WindowMouseArrivals(window) { Rectangle(start.x - 3, start.y - 3, 7, 7) }.start()
                try {
                    robot.mouseMove(start.x, start.y)
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    try {
                        for (step in 1..12) robot.mouseMove(
                            start.x + (end.x - start.x) * step / 12, start.y + (end.y - start.y) * step / 12,
                        )
                    } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                    robot.waitForIdle()
                    arrivals.pressCount > 0
                } finally { arrivals.stop() }
            }
            // Retry a press only when the OS did not deliver it to our window.
            // Once delivered, an incorrect selection fails the caller's assertion.
            if (arrived) return
        }
        fail(
            "The native drag did not land in this PDF window. " +
                NativeWindowForeground.describe(window),
        )
    }

    private fun shot(window: ComposeWindow, dark: Boolean, step: String) = NativeWindowForeground.action {
        NativeWindowForeground.activate(window)
        edt {
            val folder = Path.of("build/screens/original-pdf-native", if (dark) "dark" else "light")
            Files.createDirectories(folder)
            window.renderImmediately()
            VerifyHooks.capture(window, folder.resolve("$step.png").toFile())
        }
    }

    private fun waitFor(label: String, detail: () -> String = { "" }, ready: () -> Boolean) {
        val limit = System.nanoTime() + 20_000_000_000L
        while (!ready()) {
            check(System.nanoTime() < limit) { "Timed out: $label ${detail()}" }
            Thread.sleep(80)
        }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
