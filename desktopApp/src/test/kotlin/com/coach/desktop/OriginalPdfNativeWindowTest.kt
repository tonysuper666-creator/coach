package com.coach.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.*
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.DeskTypeControl
import com.coach.ui.v7.LocalDeskTypeControl
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
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*

/** Real Windows window, native pointer input, and an isolated book and paired service. */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class OriginalPdfNativeWindowTest {
    @Test fun farPageViewSwitchKeepsTheWindowResponsiveAndAgreesWithSqlite() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-reader-view-")
        val library = TextbookLibrary(root.resolve("library"))
        BookHudPreferenceStore(library.directory).setPinned(true)
        val book = library.importBook(viewSwitchPdf(root))
        val databaseUrl = "jdbc:sqlite:${library.directory.resolve("library.sqlite")}"
        DriverManager.getConnection(databaseUrl).use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE view_writes(text_view INTEGER NOT NULL)")
                sql.execute("CREATE TRIGGER record_view_write AFTER INSERT ON book_reader_views " +
                    "BEGIN INSERT INTO view_writes VALUES (NEW.text_view); END")
            }
        }
        fun recordedWrites(): List<Boolean> = DriverManager.getConnection(databaseUrl).use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT text_view FROM view_writes ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add(rows.getBoolean(1)) }
                }
            }
        }
        // TON-1783（#571）起书库实例会记住读过的视图值；另开一个书库实例去读，只会一直拿到它第一次读到的那个。
        // 要核对真正落盘的值，就直接查 SQLite。
        fun sqliteTextView(): Boolean? = DriverManager.getConnection(databaseUrl).use { db ->
            db.prepareStatement("SELECT text_view FROM book_reader_views WHERE book=?").use { sql ->
                sql.setString(1, book.id)
                sql.executeQuery().use { if (it.next()) it.getBoolean(1) else null }
            }
        }
        fun waitForSqlite(label: String, ready: () -> Boolean) = waitFor(label) {
            try {
                ready()
            } catch (error: SQLException) {
                // A read can briefly meet an exclusive commit after we release the write lock.
                if ((error.errorCode and 0xff) != 5 /* SQLITE_BUSY */) throw error
                false
            }
        }
        for (dark in listOf(false, true)) {
            // Hold the controller's page acknowledgement while the reader is already navigating.
            val publishedPage = mutableIntStateOf(1)
            var requestedPage = 1
            var holdPage = false
            library.saveTextView(book.id, false)
            val window = edt { ComposeWindow().apply {
                title = "LearningCoach · 原页和文字切换"
                val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                setBounds(screen.x + 20, screen.y + 20, minOf(1100, screen.width - 40), minOf(830, screen.height - 40))
                setContent { CoachTheme(darkTheme = dark) {
                    Surface(color = CoachTheme.colors.bgCanvas) {
                        TextbookReader(LibraryUi(book = book, page = publishedPage.intValue, screen = BookScreen.READER), library,
                            UnconnectedBookLearningSupport, null, remember { FocusRequester() }, onNoteSession = {},
                            onPage = { requestedPage = it; if (!holdPage) publishedPage.intValue = it },
                            onBack = {}, onLearn = {})
                    }
                } }
                isVisible = true
            } }
            try {
                val edtThread = edt { Thread.currentThread() }
                // 窗口默认打开原页；toggle() 每次切到另一种视图。
                var textNow = false
                fun toggle() { selectView(window, text = !textNow); textNow = !textNow }
                fun jump(page: Int, hold: Boolean = false) {
                    edt { holdPage = hold }
                    // #570（TON-1784）起跳页输入框收在底栏页码后面：点页码才出现输入框和「前往」。
                    if (node(window, "page-jump") == null) click(window, "reader-page-number")
                    waitFor("page jump field") { node(window, "page-jump") != null }
                    edt {
                        val field = assertNotNull(node(window, "page-jump"))
                        assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(page.toString())))
                    }
                    // 输入框预填当前页，「前往」一开始就可点；要等重组把新页码交给按钮，否则点下去提交的还是旧页。
                    waitFor("page jump holds $page") {
                        node(window, "page-jump")?.config?.getOrNull(SemanticsProperties.EditableText)?.text == page.toString() &&
                            node(window, "page-jump-go")?.config?.contains(SemanticsProperties.Disabled) == false
                    }
                    edt { assertTrue(node(window, "page-jump-go")!!.config[SemanticsActions.OnClick].action!!.invoke()) }
                    assertEquals(page, edt { requestedPage })
                }
                fun visible(text: Boolean, page: Int) {
                    val label = if (text) "文字视图" else "原页视图"
                    waitFor("$label and page $page", detail = {
                        edt {
                            val body = node(window, "reader-text")
                            val sources = body?.config?.getOrNull(BookPageSources)?.map { it.importPage }.orEmpty()
                            val originals = findAll(window) { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("original-pdf-page-") == true }.mapNotNull { n ->
                                n.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("original-pdf-page-") }?.let { tag ->
                                    "$tag:" + n.children.joinToString("/") { c -> "${c.config.getOrNull(OriginalPageBitmapWidth)}@${c.boundsInRoot.isEmpty}" }
                                }
                            }
                            "loading=${node(window, "book-page-loading") != null} displayedSources=$sources originals=$originals" +
                                " published=${publishedPage.intValue} requested=$requestedPage sqlite=${runCatching { sqliteTextView() }.getOrElse { "busy" }}"
                        }
                    }, timeoutMillis = if (text) 60_000 else 20_000) {
                        edt {
                            // #570（TON-1784）起切换胶囊只在「更多」菜单里出现，菜单收起时没有按钮文字可读；
                            // 看的是屏上实际显示的视图，再由下面的 SQLite 断言核对保存值。
                            val body = node(window, "reader-text")
                            val original = node(window, "original-pdf-page-$page")
                            node(window, "immersive-more-menu") != null && if (text) {
                                body?.config?.getOrNull(SemanticsProperties.Text).orEmpty().any { "Page sample $page:" in it.text } &&
                                    original == null
                            } else {
                                body == null && original?.children?.any { image ->
                                    image.config.getOrNull(OriginalPageBitmapWidth) != null && !image.boundsInRoot.isEmpty
                                } == true
                            }
                        }
                    }
                    waitForSqlite("SQLite text_view=$text") { sqliteTextView() == text }
                    println("READER_VIEW dark=$dark page=$page text=$text sqlite=$text")
                }
                fun releasePage(page: Int) = edt { holdPage = false; publishedPage.intValue = page }
                fun responsiveFrame(firstToggle: Boolean = false) {
                    val frame = FutureTask<Unit> { window.renderImmediately() }
                    SwingUtilities.invokeLater(frame)
                    try {
                        frame.get(2, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        val stack = edtThread.stackTrace
                        println("READER_VIEW_BLOCKED sqlite=${runCatching { sqliteTextView() }.getOrElse { "busy" }}\n${stack.take(50).joinToString("\n")}")
                        if (!firstToggle) fail("Reader did not draw a frame within two seconds")
                        frame.get(90, TimeUnit.SECONDS)
                        fail("Reader did not draw the first text view within two seconds")
                    }
                }

                visible(false, 1)
                jump(154)
                visible(false, 154)
                shot(window, dark, "switch-before")
                toggle()
                responsiveFrame(firstToggle = true)
                assertNotNull(node(window, "book-page-loading"))
                shot(window, dark, "switch-loading")
                visible(true, 154)
                shot(window, dark, "switch-after")
                repeat(2) {
                    toggle()
                    visible(false, 154)
                    toggle()
                    visible(true, 154)
                }
                toggle()
                visible(false, 154)

                for ((target, endsInText) in listOf(150 to false, 154 to true)) {
                    edt { window.setSize(window.width, window.height - 64) }
                    jump(target, hold = true)
                    assertNotEquals(target, edt { publishedPage.intValue })
                    val priorWrites = recordedWrites()
                    val sequence = if (endsInText) listOf(true, false, true) else listOf(true, false)
                    DriverManager.getConnection(databaseUrl).use { db ->
                        db.createStatement().use { sql ->
                            // Simulate a concurrent writer holding the SQLite write lock during the jump.
                            sql.execute("BEGIN IMMEDIATE")
                            try {
                                for (text in sequence) {
                                    toggle()
                                    responsiveFrame()
                                    assertEquals(text, library.preferredTextView(book.id))
                                    if (text) assertNotNull(node(window, "book-page-loading"))
                                }
                                assertEquals(priorWrites, recordedWrites())
                            } finally { sql.execute("ROLLBACK") }
                        }
                    }
                    waitForSqlite("rapid SQLite writes drained in click order") {
                        recordedWrites() == priorWrites + sequence
                    }
                    releasePage(target)
                    visible(endsInText, target)
                }
            } finally { edt { window.dispose() } }
        }
    }

    private fun viewSwitchPdf(root: Path): Path = root.resolve("Reader view sample.pdf").also { file ->
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (number in 1..620) {
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(font, 11f)
                    content.newLineAtOffset(54f, 735f)
                    repeat(44) { line ->
                        content.showText("Page sample $number: a synthetic reading paragraph, line ${line + 1}.")
                        content.newLineAtOffset(0f, -15f)
                    }
                    content.endText()
                }
            }
            pdf.save(file.toFile())
        }
    }

    @Test fun draggingTheSecondOccurrenceExplainsHighlightsAndJumpsToThatExactOccurrence() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-original-pdf-native-")
        val library = TextbookLibrary(root.resolve("library"))
        BookHudPreferenceStore(library.directory).setPinned(true)
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
            service.cite.set("${book.id}:p1:$second")
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
                    // 位图按落定后的版面宽度栅格化；窗口刚出现时版面还会再排一次，先等这一张位图出来再比宽度。
                    waitFor("first raster at its laid-out width", {
                        node(window, "remote-image")?.let { "layout=${it.size.width} bitmap=${it.config.getOrNull(OriginalPageBitmapWidth)}" }.orEmpty()
                    }) {
                        node(window, "remote-image")?.let { it.config.getOrNull(OriginalPageBitmapWidth) == it.size.width } == true
                    }
                    val firstPageWidth = checkNotNull(node(window, "remote-image")).let { image ->
                        assertEquals(image.size.width, image.config[OriginalPageBitmapWidth])
                        image.size.width
                    }
                    shot(window, dark, "original")
                    drag(window, rects.first(), rects.last())
                    waitFor("visible native drag selection", { "expected=$range actual=" + selection(window) }) {
                        selection(window) == range
                    }
                    shot(window, dark, "drag-selection")
                    if (!dark) {
                        // #570（TON-1784）起单层阅读器把整页放进窗口，这一页受高度约束：只缩宽度页面不会变，
                        // 宽高一起缩才一定按新版面重新栅格化。
                        edt { window.setSize(window.width - 144, window.height - 144) }
                        waitFor("new raster after resizing the same page", {
                            node(window, "remote-image")?.let { "first=$firstPageWidth layout=${it.size.width} bitmap=${it.config.getOrNull(OriginalPageBitmapWidth)}" }.orEmpty()
                        }) {
                            val image = checkNotNull(node(window, "remote-image")) { "The page became blank during resize" }
                            image.size.width != firstPageWidth && image.config[OriginalPageBitmapWidth] == image.size.width
                        }
                        assertEquals(range, selection(window), "Resizing preserves the exact UTF-16 selection")
                        shot(window, dark, "resized-selection")
                        // #570 起提问从选段胶囊的「问…」进伴读侧栏，在侧栏输入框里发问。
                        service.last.set(null)
                        click(window, "selection-action-ask")
                        waitFor("companion question input for the selection") { node(window, "inline-followup-input") != null }
                        edt {
                            val field = checkNotNull(node(window, "inline-followup-input"))
                            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("What does this sentence show?")))
                        }
                        click(window, "inline-followup-send")
                        waitFor("selected passage question reply", { "request=${service.last.get()?.get("kind")}" }) {
                            service.last.get() != null && node(window, "inline-explain-loading") == null &&
                                citation(window) != null
                        }
                        val asked = checkNotNull(service.last.get())
                        assertEquals("ask", asked.getValue("kind").jsonPrimitive.content)
                        assertEquals("What does this sentence show?", asked.getValue("question").jsonPrimitive.content)
                        assertExactOccurrence(asked, phrase, "${book.id}:p1:$second")
                        clickNode(window, "ask citation") { citation(window) }
                        waitFor("question citation visible on original") { node(window, "remote-highlight") != null }
                        drag(window, rects.first(), rects.last())
                        waitFor("reselected original text") { selection(window) == range }
                    }
                    // #570 起「讲讲」也在选段胶囊上，讲解出现在伴读侧栏的同一段对话里。
                    service.last.set(null)
                    val turnsBefore = turns(window)
                    click(window, "selection-action-explain")
                    waitFor("paired explanation", { "request=${service.last.get()?.get("kind")} turns=${turns(window)}" }) {
                        service.last.get() != null && node(window, "inline-explain-loading") == null &&
                            turns(window) > turnsBefore && citation(window) != null
                    }
                    val request = checkNotNull(service.last.get())
                    assertEquals("explain", request.getValue("kind").jsonPrimitive.content)
                    assertExactOccurrence(request, phrase, "${book.id}:p1:$second")
                    shot(window, dark, "explanation")
                    clickNode(window, "explanation citation") { citation(window) }
                    waitFor("exact source visible") {
                        node(window, "remote-highlight") != null && selection(window) == null
                    }
                    shot(window, dark, "cite-original")
                    if (!dark) {
                        // The cited line has been scrolled into the viewport. Hit it in the same
                        // physical page again after re-opening the ordinary reading position.
                        selectView(window, text = true)
                        waitFor("text view") { node(window, "reader-text") != null }
                        waitFor("text view saved to SQLite") { library.savedTextView(book.id) == true }
                        selectView(window, text = false)
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

    @Test fun rightPageSelectionSurvivesTenSwitchesAndCitationsInBothModes() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-original-modes-")
        val library = TextbookLibrary(root.resolve("library"))
        BookHudPreferenceStore(library.directory).setPinned(true)
        val book = library.importBook(fixture(root, pages = 4))
        val right = book.page(2)!!
        val phrase = "The second column repeats this sentence."
        val start = right.text.lastIndexOf(phrase)
        assertTrue(start > right.text.indexOf(phrase))
        val range = ReaderTextRange(start, start + phrase.length)
        val layer = assertNotNull(library.pdfTextLayer(book, right))
        val boxes = PdfTextSelection.boxesForVerifiedRange(layer, right.text, range)
        LoopbackExplanation().use { service ->
            service.cite.set("${book.id}:p2:$start")
            val settings = DesktopConnectionSettings(root.resolve("paired"), environment = { null },
                property = { if (it == "jpackage.app-path") "/synthetic/Coach.exe" else null })
            val explainer = BookExplainer(settings, library)
            assertTrue(runBlocking { explainer.pair(service.url, "123456") })
            ExplainConsent(library.directory).allow(book.id)
            val questions = PairedBookLearningSupport(library, send = { block, question -> explainer.ask(block, question) })
            for (dark in listOf(false, true)) {
                library.savePdfScrollMode(book.id, true)
                val window = edt { ComposeWindow().apply {
                    title = "LearningCoach · 原版双页与滚动验收"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1280, screen.width - 40), minOf(620, screen.height - 40))
                    setContent { CoachTheme(darkTheme = dark) {
                        Surface(color = CoachTheme.colors.bgCanvas) {
                            val visible = remember { mutableIntStateOf(1) }
                            // 「Aa」排版控件由 WindowsTextbookHost 提供；这里直接渲染阅读器，按宿主同样的方式接上。
                            val prefs = remember { ReadingSettings(library.directory) }
                            val typePanel: DeskTypeControl = { extra ->
                                ReadingSettingsPanel(prefs, { library.preferredTextView(book.id) != true }, extra)
                            }
                            CompositionLocalProvider(LocalDeskTypeControl provides typePanel) {
                                TextbookReader(LibraryUi(book = book, page = visible.intValue, screen = BookScreen.READER), library,
                                    questions, null, remember { FocusRequester() }, onNoteSession = {},
                                    onPage = { visible.intValue = it }, onBack = {}, onLearn = {}, explainProvider = explainer)
                            }
                        }
                    } }
                    isAlwaysOnTop = true
                    isVisible = true
                } }
                try {
                    waitFor("continuous reading and desktop scrollbar") { node(window, "scroll-read-scrollbar") != null }
                    shot(window, dark, "modes-default")
                    mode(window, scroll = false)
                    waitFor("the right page has a bitmap and text layer") {
                        node(window, "original-pdf-right") != null && node(window, "original-pdf-image-2") != null &&
                            node(window, "original-pdf-text-layer-2") != null
                    }
                    shot(window, dark, "modes-paged")
                    val last = boxes.last()
                    // End just past the sentence. Its period can occupy less than one AWT pixel in the spread.
                    drag(window, boxes.first(), last.copy(x = last.x + last.width), "original-pdf-image-2")
                    shot(window, dark, "modes-right-drag")
                    waitFor("exact right page selection", {
                        "expected=$range actual=${selection(window)} " +
                            "visible=${lineVisible(window, boxes.first())} image=" +
                            node(window, "remote-image")?.config?.getOrNull(SemanticsProperties.ContentDescription)
                    }) { selection(window) == range && lineVisible(window, boxes.first()) }
                    shot(window, dark, "modes-right-before")
                    repeat(10) { index ->
                        mode(window, scroll = true)
                        waitFor("right page selection in scroll mode") {
                            node(window, "scroll-read-scrollbar") != null && selection(window) == range &&
                                lineVisible(window, boxes.first(), nearUpperThird = true)
                        }
                        if (index == 0) shot(window, dark, "modes-scroll")
                        mode(window, scroll = false)
                        waitFor("the same right page selection in paged mode") {
                            node(window, "original-pdf-right") != null && selection(window) == range &&
                                node(window, "original-pdf-page-2") != null && lineVisible(window, boxes.first())
                        }
                    }
                    shot(window, dark, "modes-right-after")
                    assertFalse(TextbookLibrary(library.directory).pdfScrollMode(book.id))
                    // #570（TON-1784）起「讲讲」在选段胶囊上，讲解出现在伴读侧栏。
                    service.last.set(null)
                    click(window, "selection-action-explain")
                    waitFor("selected right page explanation", { "request=${service.last.get()?.get("kind")}" }) {
                        service.last.get() != null && node(window, "inline-explain-loading") == null && citation(window) != null
                    }
                    val request = checkNotNull(service.last.get())
                    assertEquals("explain", request.getValue("kind").jsonPrimitive.content)
                    assertExactOccurrence(request, phrase, "${book.id}:p2:$start")
                    clickNode(window, "right page citation") { citation(window) }
                    // 伴读侧栏占去宽度时双页会退成单页；收起侧栏后回到双页，再查右页上的出处高亮。
                    click(window, "companion-close")
                    waitFor("companion closed") { node(window, "immersive-companion-drawer") == null }
                    waitFor("citation visible in the right sheet", {
                        "right=${node(window, "original-pdf-right") != null} highlight=${node(window, "remote-highlight") != null} " +
                            "visible=${lineVisible(window, boxes.first())} image=" +
                            node(window, "remote-image")?.config?.getOrNull(SemanticsProperties.ContentDescription)
                    }) {
                        node(window, "original-pdf-right") != null && node(window, "remote-highlight") != null && lineVisible(window, boxes.first())
                    }
                    mode(window, scroll = true)
                    waitFor("citation visible after switching back to scroll") {
                        node(window, "scroll-read-scrollbar") != null && node(window, "remote-highlight") != null && lineVisible(window, boxes.first())
                    }
                    shot(window, dark, "modes-citation")
                } finally { edt { window.dispose() } }
            }
        }
        println("ORIGINAL_PDF_MODES themes=2 switchesPerTheme=10 rightPage=2 sameRange=true citationInBoth=true")
    }

    // #570（TON-1784）起「Aa」排版菜单也收进顶栏「更多」菜单：先开「更多」，选完版式后再把「更多」收起。
    private fun mode(window: ComposeWindow, scroll: Boolean) {
        if (node(window, "v7-type-menu") == null) click(window, "immersive-more-menu")
        click(window, "v7-type-menu")
        click(window, if (scroll) "original-mode-scroll" else "original-mode-paged")
        waitFor("reading settings closed") { node(window, "reading-settings-panel") == null }
        if (node(window, "v7-type-menu") != null) click(window, "immersive-more-menu")
        waitFor("more menu closed") { node(window, "v7-type-menu") == null }
    }

    // #570（TON-1784）把「原页 / 文字」切换收进顶栏「更多」菜单里的分段胶囊：先开菜单，再点要去的那一段。
    private fun selectView(window: ComposeWindow, text: Boolean) {
        // 选中一段后菜单自己收起（有退出动画）；等它真正消失，下一次再开菜单才不会撞上正在关的那一个。
        click(window, "immersive-more-menu")
        click(window, if (text) "view-mode-text" else "view-mode-original")
        waitFor("more menu closed") { node(window, "view-mode-text") == null }
    }

    private fun lineVisible(window: ComposeWindow, rect: ReaderRect, nearUpperThird: Boolean = false): Boolean = edt {
        val viewport = node(window, "reader-page") ?: return@edt false
        val image = node(window, "remote-image") ?: return@edt false
        if (image.config.getOrNull(SemanticsProperties.ContentDescription)?.none { it.endsWith("第 2 页") } != false) return@edt false
        val origin = image.positionInRoot
        val point = Offset(origin.x + ((rect.x + rect.width / 2) * image.size.width).toFloat(),
            origin.y + ((rect.y + rect.height / 2) * image.size.height).toFloat())
        val bounds = viewport.boundsInRoot
        bounds.contains(point) && (!nearUpperThird ||
            kotlin.math.abs(point.y - (bounds.top + bounds.height / 3)) < bounds.height * .08f)
    }

    private fun fixture(root: Path, pages: Int = 1): Path = root.resolve("Original PDF acceptance.pdf").also { file ->
        PDDocument().use { pdf ->
            repeat(pages) { index ->
                val page = PDPage().also(pdf::addPage)
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                PDPageContentStream(pdf, page).use { out ->
                fun line(x: Float, y: Float, text: String, size: Float = 12f) {
                    out.beginText(); out.setFont(font, size); out.newLineAtOffset(x, y); out.showText(text); out.endText()
                }
                line(50f, 755f, if (pages == 1) "Learning from the original page" else "Original page ${index + 1}", 20f)
                val rightPageShift = if (index == 1) 230f else 0f
                line(50f, 703f - rightPageShift, "The second column repeats this sentence.")
                line(318f, 682f - rightPageShift, "The second column repeats this sentence.")
                out.addRect(50f, 540f, 510f, 100f); out.stroke()
                line(65f, 606f, "Knowledge box")
                line(65f, 575f, "An equation keeps its spacing: E = m c^2")
                }
            }
            pdf.save(file.toFile())
        }
    }

    private class LoopbackExplanation : AutoCloseable {
        val last = AtomicReference<JsonObject?>()
        val cite = AtomicReference<String?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}"
        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes() }.decodeToString()
                val (code, reply) = when (exchange.requestURI.path) {
                    "/v1/pair" -> 200 to """{"deviceId":"synthetic-reader","token":"${"a".repeat(43)}"}"""
                    "/v1/hello" -> 200 to """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                    "/v1/library/explain" -> {
                        val request = Json.parseToJsonElement(body).jsonObject
                        // #570（TON-1784）后阅读器还会预取本节导读；这里只记录、只回答选段讲解和提问。
                        if ("selection" !in request) 404 to "{}" else {
                            last.set(request)
                            val ids = request.getValue("blocks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                            println("LOOPBACK_EXPLAIN kind=${request["kind"]} blocks=$ids")
                            // 模拟服务端引用选中的那一处；它不在请求里时退回第一块，由测试断言指出。
                            val id = cite.get()?.takeIf { it in ids } ?: ids.first()
                            val page = id.substringAfterLast(":p").substringBefore(':').toInt()
                            200 to """{"text":"A repeated sentence tests location accuracy.【S1】","sources":[{"docId":"$id","pdfPage":$page,"anchor":"S1"}]}"""
                        }
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

    private fun SemanticsNode.hasLabel(label: String): Boolean =
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } || children.any { it.hasLabel(label) }

    private fun SemanticsNode.isAction(label: String): Boolean =
        config.getOrNull(SemanticsActions.OnClick) != null && !config.contains(SemanticsProperties.Disabled) && hasLabel(label)

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? =
        findNode(window) { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun findNode(window: ComposeWindow, matches: (SemanticsNode) -> Boolean): SemanticsNode? = edt {
        fun find(node: SemanticsNode): SemanticsNode? =
            if (matches(node)) node else node.children.firstNotNullOfOrNull(::find)
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

    private fun findAll(window: ComposeWindow, matches: (SemanticsNode) -> Boolean): List<SemanticsNode> = edt {
        val found = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { if (matches(node)) found += node; node.children.forEach(::walk) }
        fun walkIn(owner: Window) {
            val owners = when (owner) {
                is ComposeWindow -> owner.semanticsOwners
                is ComposeDialog -> owner.semanticsOwners
                else -> emptyList()
            }
            owners.forEach { walk(it.unmergedRootSemanticsNode) }
            owner.ownedWindows.filter { it.isVisible }.forEach(::walkIn)
        }
        walkIn(window)
        found
    }

    /** The newest citation chip in the companion conversation. */
    private fun citation(window: ComposeWindow): SemanticsNode? =
        findAll(window) { it.config.getOrNull(SemanticsProperties.TestTag) == "explain-citation" }.lastOrNull()

    private fun turns(window: ComposeWindow): Int =
        findAll(window) { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("inline-explain-turn-") == true }.size

    private fun clickNode(window: ComposeWindow, label: String, find: () -> SemanticsNode?) {
        waitFor(label) { find()?.config?.getOrNull(SemanticsActions.OnClick) != null }
        edt { assertTrue(checkNotNull(find()).config[SemanticsActions.OnClick].action!!.invoke(), label) }
    }

    // TON-1855 起请求带上本节上下文块，不再只有选中那一块；要核对的是选段原文和「被选中的那一处」作为单独一块在里面。
    private fun assertExactOccurrence(request: JsonObject, phrase: String, id: String) {
        assertEquals(phrase, request.getValue("selection").jsonPrimitive.content)
        val blocks = request.getValue("blocks").jsonArray.map { it.jsonObject }
        val ids = blocks.map { it.getValue("id").jsonPrimitive.content }
        val chosen = blocks.filter { it.getValue("id").jsonPrimitive.content == id }
        assertEquals(1, chosen.size, "The selected occurrence $id must be sent exactly once; blocks=$ids")
        assertEquals(phrase, chosen.single().getValue("text").jsonPrimitive.content, "blocks=$ids")
    }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag)?.config?.getOrNull(SemanticsActions.OnClick) != null }
        edt { assertTrue(node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke(), tag) }
    }

    private fun drag(window: ComposeWindow, from: ReaderRect, to: ReaderRect, imageTag: String = "remote-image") {
        repeat(3) {
            val arrived = NativeWindowForeground.action {
                NativeWindowForeground.activate(window)
                val (start, end) = edt {
                    val origin = window.contentPane.locationOnScreen
                    val scale = window.graphicsConfiguration.defaultTransform
                    // Semantics bounds can be clipped by the scroll viewport. Use the full bitmap size.
                    val image = node(window, imageTag)!!
                    val top = image.positionInRoot
                    fun point(box: ReaderRect) = Point(
                        origin.x + ((top.x + (box.x + box.width / 2) * image.size.width) / scale.scaleX).roundToInt(),
                        origin.y + ((top.y + (box.y + box.height / 2) * image.size.height) / scale.scaleY).roundToInt(),
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

    private fun waitFor(label: String, detail: () -> String = { "" }, timeoutMillis: Long = 20_000, ready: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
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
