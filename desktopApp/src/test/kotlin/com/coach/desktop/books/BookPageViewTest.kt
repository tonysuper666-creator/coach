package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.coach.ui.CoachTheme
import com.coach.ui.v7.Desk
import com.coach.desktop.WindowsTextbookHost
import com.coach.domain.reader.ReaderTextRange
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import kotlin.test.*

/** Source anchors survive both component reflow and Desk's opt-in width. */
@OptIn(ExperimentalTestApi::class)
class BookPageViewTest {
    @Test fun reviewedTableRepeatsHeaderPixelsAndKeepsEachRowAndItsSourceOnOneSheet() {
        val root = Files.createTempDirectory("coach-table-rows-")
        try {
            val image = java.awt.image.BufferedImage(210, 1080, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val headerColor = java.awt.Color.RED
            val rowColors = listOf(java.awt.Color.BLUE, java.awt.Color.GREEN)
            val g = image.createGraphics()
            try {
                g.color = headerColor
                g.fillRect(0, 0, image.width, 30)
                repeat(7) { row ->
                    g.color = rowColors[row % 2]
                    g.fillRect(0, 30 + 150 * row, image.width, 150)
                }
            } finally { g.dispose() }
            val bytes = java.io.ByteArrayOutputStream().use {
                javax.imageio.ImageIO.write(image, "png", it)
                it.toByteArray()
            }
            val raw = "表头\n" + (1..7).joinToString("") { "第${it}行𠮷\n" }
            val newlineOffsets = raw.indices.filter { raw[it] == '\n' }
            val table = ImportedTable(newlineOffsets.mapIndexed { i, end ->
                ImportedTableRow(30 + i * 150, ReaderTextRange(if (i == 0) 0 else newlineOffsets[i - 1] + 1, end))
            }, 18f, 15f)
            val item = BookIllustration(41, 0, BookIllustrationAssets.sha256(bytes), "png", image.width, image.height,
                kind = PageVisualKind.TABLE, sourceStart = 0, sourceEnd = raw.length, table = table,
                sourceRegion = com.coach.domain.reader.ReaderRect(.1, .1, .9, .9))
            val book = Textbook("a".repeat(64), "校验表", "pdf", listOf(BookPage(41, raw, illustrations = listOf(item))))
            val file = BookIllustrationAssets.imageFile(root, book.id, item)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
            val ends = raw.indices.filter { raw[it] == '\n' }.drop(1)
            val rows = BookTableRows(30, ends.mapIndexed { i, end -> BookTableRow(30 + (i + 1) * 150, end + 1) }, 18f, 15f)
            var directory by mutableStateOf(root)
            var geometry by mutableStateOf(rows)
            var mode by mutableStateOf(BookReadMode.Paged)
            var opened by mutableStateOf<BookPassage?>(null)
            val state = BookPagingState(BookPageAnchor(41))
            runSkikoComposeUiTest(size = Size(900f, 510f), density = Density(1f)) {
                setContent { CoachTheme {
                    BookPageView(book, state, mapOf(41 to listOf(ends[4]..ends[4])), onTurn = { state.move(it) },
                        libraryDirectory = directory, tableRows = mapOf(item to geometry),
                        readMode = mode, onOpenTableRow = { opened = it })
                } }
                waitForIdle()
                assertEquals(4, state.pages.size)
                var nextRow = 0
                state.pages.forEachIndexed { i, page ->
                    val block = page.visuals.single()
                    val band = assertNotNull(block.band)
                    assertEquals(30 + 150 * nextRow, band.startY)
                    assertEquals(0, (band.endY - band.startY) % 150)
                    val slice = assertNotNull(block.replacement)
                    assertEquals(i, state.pages.pageContaining(BookPageAnchor(41, slice.endUtf16 - 1)))
                    assertEquals(i, state.pages.pageContaining(page.anchor))
                    assertEquals(ends[4] in slice.startUtf16 until slice.endUtf16, block.highlighted)
                    runOnIdle { state.anchor = page.anchor }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("第 41 页表格", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
                    waitForIdle()
                    val pixels = onNodeWithTag("book-block-41-0").captureToImage().toPixelMap()
                    assertEquals(headerColor.rgb, pixels[pixels.width / 2, 12].toArgb())
                    assertEquals(rowColors[nextRow % 2].rgb, pixels[pixels.width / 2, 50].toArgb())
                    val blockNode = onNodeWithTag("book-block-41-0")
                    fun clickRow(y: Int, row: Int) {
                        blockNode.performMouseInput { click(Offset(center.x, y.toFloat())) }
                        val text = if (row == 0) "表头" else "第${row}行𠮷"
                        val start = raw.indexOf(text)
                        assertEquals(BookPassage(book.id, 41, start, start + text.length, text), opened)
                    }
                    // Last pixel of the repeated header, then the first pixel of each visible data row.
                    val headerHeight = pixels.height * 30 / band.sourceHeight
                    val rowCount = (band.endY - band.startY) / 150
                    clickRow(headerHeight - 1, 0)
                    repeat(rowCount) { j ->
                        clickRow(headerHeight + j * (pixels.height - headerHeight) / rowCount, nextRow + j + 1)
                    }
                    nextRow += rowCount
                }
                assertEquals(7, nextRow)
                assertEquals(raw, state.pages.flatMap { it.sources }.joinToString("") { raw.substring(it.startUtf16, it.endUtf16) })
                runOnIdle { mode = BookReadMode.Scroll; opened = null }
                val lastRow = onNodeWithContentDescription("查看原页第 7 行数据")
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("查看原页第 7 行数据").fetchSemanticsNodes().isNotEmpty() }
                lastRow.performMouseInput { click() }
                val lastText = "第7行𠮷"
                assertEquals(BookPassage(book.id, 41, raw.indexOf(lastText), raw.length - 1, lastText), opened)
                runOnIdle { mode = BookReadMode.Paged }
                runOnIdle { geometry = rows.copy(rows = rows.rows.reversed()) }
                waitForIdle()
                assertNull(state.pages.single().visuals.single().band)
                onNodeWithContentDescription("查看原页表头").assertDoesNotExist()
                runOnIdle { directory = root.resolve("missing") }
                waitUntil(timeoutMillis = 10_000) { state.pages.isNotEmpty() && state.pages.all { it.visuals.isEmpty() } }
                assertEquals(raw, state.pages.flatMap { it.runs }.joinToString("") { originalBookSelection(it.text) })
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun pendingBookmarkAllowsContinuousTurnsAndLateAcknowledgementsKeepTheVisibleText() {
        val root = Files.createTempDirectory("coach-page-bookmark-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(root.resolve("Reading.md").also {
                Files.writeString(it, "# Reading\n\n" + "Reading evidence is preserved through every page turn. ".repeat(180))
            })
            assertEquals(3, book.pages.size)
            var ui by mutableStateOf(LibraryUi(book = book, screen = BookScreen.READER, busy = true))
            val writes = mutableListOf<Int>()
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                val focus = FocusRequester()
                setContent { CoachTheme {
                    TextbookReader(ui, library, UnconnectedBookLearningSupport, null, focus,
                        onNoteSession = {}, onPage = { writes += it }, onBack = {}, onLearn = {})
                } }
                waitForIdle()
                fun sources() = onNodeWithTag("reader-text").fetchSemanticsNode().config[BookPageSources]
                val first = sources()
                while (sources().first().importPage < 3) {
                    onNodeWithText("下一页").assertIsEnabled().performClick()
                    waitForIdle()
                }
                assertEquals(listOf(2, 3), writes)
                val latest = sources()
                runOnIdle { ui = ui.copy(page = 2) }
                waitForIdle()
                assertEquals(latest, sources(), "an older bookmark acknowledgement cannot rewind the current sheet")
                runOnIdle { ui = ui.copy(page = 3) }
                waitForIdle()
                assertEquals(latest, sources())
                while (isEnabled().matches(onNodeWithText("上一页").fetchSemanticsNode())) {
                    onNodeWithText("上一页").performClick()
                    waitForIdle()
                }
                assertEquals(first, sources(), "pending saves cannot stop a continuous reverse traversal")
                assertEquals(listOf(2, 3, 2, 1), writes)
                onNodeWithText("上一页").assertIsNotEnabled()
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun deskKeepsItsDefaultWidthAndAllowsAnExplicitSpread() = runSkikoComposeUiTest(
        size = Size(1900f, 1000f), density = Density(1f),
    ) {
        val book = Textbook("desk-spread", "阅读", "md", listOf(BookPage(1, "阅读证据。".repeat(1000))))
        val state = BookPagingState(BookPageAnchor(1))
        var wide by mutableStateOf(false)
        setContent { CoachTheme {
            Desk(crumb = "阅读", hasMargin = false, paperMaxWidth = if (wide) Dp.Infinity else Dp.Unspecified) {
                BookPageView(book, state, emptyMap(), onTurn = { state.move(it) })
            }
        } }
        waitForIdle()
        assertEquals(1046f, onNodeWithTag("book-page-layout").fetchSemanticsNode().size.width.toFloat(), 1f)
        onNodeWithTag("book-sheet-1").assertDoesNotExist()
        val anchor = state.pages[2].anchor
        runOnIdle { state.anchor = anchor; wide = true }
        waitForIdle()
        assertTrue(onNodeWithTag("book-page-layout").fetchSemanticsNode().size.width >= 1460)
        onNodeWithTag("book-sheet-1").assertExists()
        assertEquals(anchor, state.anchor)
        runOnIdle { wide = false }
        waitForIdle()
        onNodeWithTag("book-sheet-1").assertDoesNotExist()
        assertEquals(anchor, state.anchor)
    }

    @Test fun originalViewRoundTripKeepsPositionWithinOneImportPage() {
        val root = Files.createTempDirectory("coach-page-view-anchor-")
        try {
            val file = root.resolve("Reading.pdf")
            PDDocument().use { pdf ->
                val page = PDPage().also(pdf::addPage)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 8f)
                    out.newLineAtOffset(36f, 755f)
                    out.showText("Chapter 1 Reading")
                    repeat(60) { index ->
                        out.newLineAtOffset(0f, -11f)
                        out.showText("Paragraph $index: Readers return to the source and check the evidence before drawing conclusions.")
                    }
                    out.endText()
                }
                pdf.save(file.toFile())
            }
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(file)
            assertEquals(1, book.pages.size)
            library.save(book.copy(promptSeen = true))
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.book != null && !controller.state.value.busy }
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER }
                onNodeWithTag("book-original-toggle").performClick()
                waitForIdle()
                fun displayed() = onNodeWithTag("reader-text").fetchSemanticsNode()
                    .config[SemanticsProperties.Text].single().text
                val opening = displayed()
                onNodeWithText("下一页").performClick()
                waitForIdle()
                val continuation = displayed()
                assertNotEquals(opening, continuation)
                assertEquals(1, controller.state.value.page)
                onNodeWithTag("book-original-toggle").performClick()
                waitForIdle()
                onNodeWithTag("book-original-toggle").performClick()
                waitForIdle()
                assertEquals(continuation, displayed())
            }
        } finally { root.toFile().deleteRecursively() }
    }

    /** TON-1724: 章内读到后面的子页，存下偏移；冷启用该偏移起步，回到同一句附近，且换排版宽度也一样。 */
    @Test fun readSpotInsideAChapterSurvivesColdStartAndReflowWidth() {
        val sentence = "The multiplication of two whole numbers is the repeated addition of one of them, and the order of the factors does not alter the product. "
        val raw = "CHAPTER 1\n\n" + (1..200).joinToString("\n\n") { n -> "Paragraph $n. " + sentence.repeat(3) }
        val book = Textbook("spot-book", "Elements of arithmetic", "epub", listOf(BookPage(1, raw)))
        val root = Files.createTempDirectory("spot")
        try {
            val library = TextbookLibrary(root)
            assertEquals(null, library.savedReadSpot(book.id))
            val target = raw.indexOf("Paragraph 150.")
            library.saveReadSpot(book.id, 1, target)
            library.saveReadSpot(book.id, 1, target) // 重复保存是覆盖，不是堆叠
            assertEquals(1 to target, TextbookLibrary(root).savedReadSpot(book.id))
            for (width in listOf(800, 560)) runSkikoComposeUiTest(size = Size(900f, 1000f), density = Density(1f)) {
                val state = BookPagingState(BookPageAnchor(1, target))
                var spot: BookPageAnchor? = null
                setContent { CoachTheme {
                    Box(Modifier.width(width.dp).height(900.dp)) {
                        BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, onSpot = { spot = it })
                    }
                } }
                waitUntil(timeoutMillis = 60_000) { state.pages.isNotEmpty() && spot != null }
                assertTrue(state.index > 0, "冷启不应回章首")
                val shown = state.pages[state.index].sources.single { it.importPage == 1 }
                assertTrue(target in shown.startUtf16 until shown.endUtf16, "起步页应包含存下的那句 @width=$width")
                assertEquals(target, spot!!.offsetUtf16) // 定位过的那一句就在当前页：存精确偏移，不退回页首
            }
        } finally { root.toFile().deleteRecursively() }
    }

    /** TON-1724: 长书先只排读位前后几页就出字，整本排完后换上，读位始终在同一页。 */
    @Test fun longBookShowsNearbyPagesFirstThenTheWholeBookKeepingThePlace() = runSkikoComposeUiTest(
        size = Size(900f, 1000f), density = Density(1f),
    ) {
        val sentence = "The multiplication of two whole numbers is the repeated addition of one of them, and the order of the factors does not alter the product. "
        val pages = (1..60).map { n -> BookPage(n, (1..4).joinToString("\n\n") { sentence.repeat(10) }) }
        assertTrue(pages.sumOf { it.text.length } > 60_000)
        val book = Textbook("near-first", "Elements of arithmetic", "epub", pages)
        val state = BookPagingState(BookPageAnchor(30))
        setContent { CoachTheme {
            Box(Modifier.width(800.dp).height(900.dp)) {
                BookPageView(book, state, emptyMap(), onTurn = { state.move(it) })
            }
        } }
        waitUntil(timeoutMillis = 120_000) { state.pages.isNotEmpty() }
        val firstCount = state.pages.size
        assertTrue(state.pages[state.index].sources.any { it.importPage == 30 })
        waitUntil(timeoutMillis = 120_000) { state.pages.size > firstCount }
        assertTrue(state.pages.any { page -> page.sources.any { it.importPage == 1 } })
        assertTrue(state.pages[state.index].sources.any { it.importPage == 30 })
    }

    /** TON-1724: 折叠屏宽→窄重排，锚点是原文精确偏移时，窄屏选中的子页必含同一句；锚点只是宽页页首时，目标句会落到下一子页（已知取舍）。 */
    @Test fun narrowReflowKeepsAnExactAnchorButAPageStartAnchorMayDrift() = runSkikoComposeUiTest(
        size = Size(1400f, 1000f), density = Density(1f),
    ) {
        val sentence = "The multiplication of two whole numbers is the repeated addition of one of them, and the order of the factors does not alter the product. "
        val pages = (1..30).map { n -> BookPage(n, (1..4).joinToString("\n\n") { sentence.repeat(10) }) }
        val book = Textbook("fold-reflow", "Elements of arithmetic", "epub", pages)
        val state = BookPagingState(BookPageAnchor(20))
        var width by androidx.compose.runtime.mutableStateOf(1200)
        setContent { CoachTheme { Box(Modifier.width(width.dp).height(900.dp)) {
            BookPageView(book, state, emptyMap(), onTurn = { state.move(it) })
        } } }
        waitUntil(timeoutMillis = 120_000) { state.pages.size > 30 }
        val wide = state.pages[state.index]
        val slice = wide.sources.first { it.importPage == 20 }
        val target = (slice.startUtf16 + slice.endUtf16) / 2
        fun narrowSlices(anchor: BookPageAnchor): List<BookPageSlice> {
            width = 1200; waitForIdle()
            state.anchor = anchor
            val before = state.pages
            width = 520
            waitUntil(timeoutMillis = 120_000) { state.pages !== before && state.pages.size > 30 }
            return state.pages[state.index].sources.filter { it.importPage == 20 }
        }
        assertTrue(narrowSlices(BookPageAnchor(20, target)).any { target in it.startUtf16 until it.endUtf16 }, "精确锚点：窄屏子页含同一句")
        // 锚点是宽页页首（翻页后的形态）：窄屏选中的子页含该锚点本身，但读者看的那句（页中间）会落到下一子页。
        narrowSlices(wide.anchor)
        val shown = state.pages[state.index].sources
        assertTrue(shown.any { it.importPage == wide.anchor.importPage && wide.anchor.offsetUtf16 in it.startUtf16 until it.endUtf16 })
    }

    /** TON-1724: 读者定位过的那一句：宽→窄→宽始终在当前页；边界没翻页保留；主动翻页后不再被旧锚点拉回。 */
    @Test fun explicitSpotStaysVisibleAcrossWidthsAndActiveTurnsDoNotJumpBack() = runSkikoComposeUiTest(
        size = Size(1400f, 1000f), density = Density(1f),
    ) {
        val sentence = "The multiplication of two whole numbers is the repeated addition of one of them, and the order of the factors does not alter the product. "
        val pages = (1..30).map { n -> BookPage(n, (1..4).joinToString("\n\n") { sentence.repeat(10) }) }
        val book = Textbook("spot-fold", "Elements of arithmetic", "epub", pages)
        val state = BookPagingState(BookPageAnchor(20))
        var width by androidx.compose.runtime.mutableStateOf(1200)
        setContent { CoachTheme { Box(Modifier.width(width.dp).height(900.dp)) {
            BookPageView(book, state, emptyMap(), onTurn = { state.move(it) })
        } } }
        waitUntil(timeoutMillis = 120_000) { state.pages.size > 30 }
        fun reflow(to: Int) {
            val before = state.pages
            width = to
            waitUntil(timeoutMillis = 120_000) { state.pages !== before && state.pages.size > 30 }
        }
        fun shows(anchor: BookPageAnchor) = state.pages[state.index].sources.any {
            it.importPage == anchor.importPage && anchor.offsetUtf16 in it.startUtf16 until it.endUtf16 }
        val slice = state.pages[state.index].sources.first { it.importPage == 20 }
        val spot = BookPageAnchor(20, (slice.startUtf16 + slice.endUtf16) / 2)
        state.anchor = spot
        assertTrue(shows(spot), "宽屏")
        reflow(520); assertTrue(shows(spot), "窄屏")
        reflow(1200); assertTrue(shows(spot), "回到宽屏")
        // 按钮到边界、没有实际翻页：保留原锚点
        state.anchor = BookPageAnchor(1, 5)
        assertTrue(state.index == 0)
        state.move(ReaderTurn.Previous)
        assertEquals(BookPageAnchor(1, 5), state.anchor)
        // 定位后主动翻页：锚点换成新页，合起再展开都不回跳
        state.anchor = spot
        reflow(520)
        val moved = state.move(ReaderTurn.Next)!!
        assertTrue(moved != spot && state.anchor == moved)
        assertTrue(shows(moved), "翻页后")
        reflow(1200); assertTrue(shows(moved) && state.anchor == moved, "展开后仍在新页")
        reflow(520); assertTrue(shows(moved) && state.anchor == moved, "再合起仍在新页")
    }

    /** TON-1724: 长英文教材首开，分页在后台做，主线程先显示「正在排版」而不是卡住。 */
    @Test fun longBookLaysOutOffTheMainThreadAndShowsPagesWhenReady() = runSkikoComposeUiTest(
        size = Size(900f, 1000f), density = Density(1f),
    ) {
        val sentence = "The multiplication of two whole numbers is the repeated addition of one of them, and the order of the factors does not alter the product. "
        val raw = (1..40).joinToString("\n\n") { chapter ->
            "CHAPTER $chapter\n\n" + (1..60).joinToString("\n\n") { sentence.repeat(3) }
        }
        assertTrue(raw.length > 60_000)
        val book = Textbook("long-english", "Elements of arithmetic", "epub", listOf(BookPage(1, raw)))
        val state = BookPagingState(BookPageAnchor(1))
        setContent { CoachTheme {
            Box(Modifier.width(800.dp).height(900.dp)) {
                BookPageView(book, state, emptyMap(), onTurn = { state.move(it) })
            }
        } }
        waitUntil(timeoutMillis = 120_000) { onAllNodesWithTag("book-sheet-0").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(state.pages.size > 100)
    }

    @Test fun spreadKeepsBothInnerFootersAndSourceAnchorOnNarrowing() = runSkikoComposeUiTest(
        size = Size(1800f, 1000f), density = Density(1f),
    ) {
        val book = Textbook("isolated-spread", "阅读与证据", "md", listOf(BookPage(41,
            "# 第三章 阅读与证据\n\n" + "读者回到原文检查证据，再把结论接回已有经验。😀𠮷".repeat(300))))
        val state = BookPagingState(BookPageAnchor(41))
        var width by mutableStateOf(1600.dp)
        var mode by mutableStateOf(BookPageMode.Auto)
        setContent { CoachTheme {
            Box(Modifier.width(width).height(900.dp)) {
                BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, mode = mode)
            }
        } }
        waitForIdle()
        onNodeWithTag("book-sheet-0").assertExists()
        onNodeWithTag("book-sheet-1").assertExists()
        onAllNodes(SemanticsMatcher("remaining chapter pages") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { Regex("本章还剩 \\d+ 页").matches(it.text) } == true
        }).assertCountEquals(2)
        assertTrue(state.pages.size > 3)
        val anchor = state.pages[2].anchor.let { it.copy(offsetUtf16 = it.offsetUtf16 + 10) }
        runOnIdle { state.anchor = anchor }
        waitForIdle()
        runOnIdle { width = 1100.dp }
        waitForIdle()
        onNodeWithTag("book-sheet-1").assertDoesNotExist()
        assertFalse(state.spread)
        assertEquals(anchor, state.anchor)
        assertTrue(state.pages[state.index].sources.any {
            it.importPage == anchor.importPage && anchor.offsetUtf16 in it.startUtf16 until it.endUtf16
        })
        runOnIdle { mode = BookPageMode.Spread; width = 1290.dp }
        waitForIdle()
        onNodeWithTag("book-sheet-0").assertWidthIsEqualTo(621.dp)
        onNodeWithTag("book-sheet-1").assertWidthIsEqualTo(621.dp)
        runOnIdle { width = 1100.dp }
        waitForIdle()
        onNodeWithTag("book-sheet-1").assertDoesNotExist()
        runOnIdle { mode = BookPageMode.Single; width = 1600.dp }
        waitForIdle()
        onNodeWithTag("book-sheet-1").assertDoesNotExist()
        assertEquals(anchor, state.anchor)
    }
}
