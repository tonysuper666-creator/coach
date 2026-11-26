package com.coach.desktop.books

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import com.coach.domain.reader.ReaderTextRange
import com.coach.ui.CoachTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookPageLayoutTest {
    @Test fun scrollReadingLineCanFindASecondDisplayOnTheSameSheet() = runSkikoComposeUiTest(density = Density(1f)) {
        val text = "First equation. Interlude. Second equation. After."
        val blocks = listOf("First equation.", "Second equation.").mapIndexed { order, eq ->
            val at = text.indexOf(eq)
            BookIllustration(1, at, "a".repeat(64), "png", 280, 30, order = order, kind = PageVisualKind.FORMULA,
                sourceStart = at, sourceEnd = at + eq.length, sourceRegion = com.coach.domain.reader.ReaderRect(.1, .1 + .3 * order, .5, .1))
        }
        var positions = emptyList<Int?>()
        setContent { CoachTheme {
            val type = BookPageTypography(MaterialTheme.typography.bodyLarge, SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp)
            val measurer = rememberTextMeasurer()
            val pages = BookPageLayouter(measurer, LocalDensity.current, type).layout(listOf(BookPage(1, text, illustrations = blocks)), 450, 900)
            val sheet = pages.single()
            val second = sheet.visuals.last()
            val explicit = BookPageAnchor(1, second.item.anchorOffset, second.item.order)
            val citation = BookPageAnchor(1, second.item.anchorOffset + 2)
            val underLine = sheet.anchorAtY(second.topPx + 1, measurer, type, 450)
            positions = listOf(second.topPx) + listOf(explicit, citation, underLine).map { sheet.yOfAnchor(it, measurer, type, 450) }
        } }
        waitForIdle()
        assertTrue(positions.first()!! > 0)
        assertEquals(1, positions.distinct().size)
    }

    @Test fun conflictingReplacementKeepsTheTextAndTheOtherVisualAnchor() = runSkikoComposeUiTest(density = Density(1f)) {
        val text = "Before the heading, value and footnote. After."
        val start = text.indexOf("heading")
        val table = BookIllustration(1, start, "a".repeat(64), "png", 240, 170, kind = PageVisualKind.TABLE,
            sourceRegion = com.coach.domain.reader.ReaderRect(.1, .1, .5, .3), sourceStart = start, sourceEnd = text.indexOf(" After"))
        val figure = BookIllustration(1, text.indexOf("value"), "b".repeat(64), "png", 80, 60, order = 1)
        var sheets = emptyList<BookLaidPage>()
        setContent { CoachTheme {
            val layouter = BookPageLayouter(rememberTextMeasurer(), LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge, SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp))
            sheets = layouter.layout(listOf(BookPage(1, text, illustrations = listOf(table, figure))), 310, 390)
        } }
        waitForIdle()
        assertEquals(listOf(figure), sheets.flatMap { it.visuals }.map { it.item })
        assertEquals(text, sheets.flatMap { it.runs }.joinToString("") { originalBookSelection(it.text) })
    }

    @Test fun ordinaryTableUsesSavedRowsAndRepeatsItsHeader() = runSkikoComposeUiTest(density = Density(1f)) {
        val text = "列\n甲😀\n乙\n丙\n丁"
        val table = ImportedTable(listOf(
            ImportedTableRow(30, ReaderTextRange(0, 1)),
            ImportedTableRow(170, ReaderTextRange(2, 5)),
            ImportedTableRow(310, ReaderTextRange(6, 7)),
            ImportedTableRow(450, ReaderTextRange(8, 9)),
            ImportedTableRow(590, ReaderTextRange(10, 11)),
        ), bodyFontPx = 18f, tableFontPx = 14f)
        val item = BookIllustration(7, 0, "a".repeat(64), "png", 240, 590, kind = PageVisualKind.TABLE, table = table)
        // The second row's shortened range would end between the surrogate pair.
        val invalid = table.copy(rows = table.rows.mapIndexed { i, row ->
            if (i == 1) row.copy(source = ReaderTextRange(2, 4)) else row
        })
        var layouts = emptyList<List<BookLaidPage>>()
        setContent { CoachTheme {
            val layouter = BookPageLayouter(rememberTextMeasurer(), LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp))
            layouts = listOf(item, item.copy(table = invalid), item.copy(table = null)).map { visual ->
                layouter.layout(listOf(BookPage(7, text, illustrations = listOf(visual))), 280, 215)
            }
        } }
        waitForIdle()
        val fragments = layouts.first().filter { it.visuals.isNotEmpty() }.map { it.visuals.single() }
        val bands = fragments.map { assertNotNull(it.band) }
        assertEquals(table.rows.dropLast(1).map { it.bottomPx }, bands.map { it.startY })
        assertEquals(table.rows.drop(1).map { it.bottomPx }, bands.map { it.endY })
        assertTrue(bands.all { it.headerBottom == table.rows.first().bottomPx })
        assertTrue(fragments.all { it.replacement == null })
        for (fallback in layouts.drop(1)) assertNull(fallback.flatMap { it.visuals }.single().band)
        for (pages in layouts) {
            assertEquals(text, pages.flatMap { it.sources }.joinToString("") { text.substring(it.startUtf16, it.endUtf16) })
            assertEquals(text, pages.flatMap { it.runs }.joinToString("") { originalBookSelection(it.text) })
        }
    }

    @Test fun reflowIsLosslessAndCitationsKeepLocalUtf16Coordinates() = runSkikoComposeUiTest {
        val raw = "第一章 源头\r\n\r\n" + ("天地之间😀𠮷，读书使人明理。".repeat(31) + "\r\n\r\n").repeat(6) +
            "第二章 继续\r\n\r\n" + "The next paragraph has English words and spaces. ".repeat(75)
        val split = safeBookBoundary(raw, 1100)
        val sources = listOf(BookPage(41, raw.substring(0, split)), BookPage(42, raw.substring(split)))
        var runs = emptyList<List<BookLaidPage>>()
        setContent { CoachTheme {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            runs = listOf(280, 440, 594).flatMap { width -> listOf(.9f, 1f, 1.15f).map { scale ->
                val body = MaterialTheme.typography.bodyLarge.copy(fontSize = (18 * scale).sp, lineHeight = (33.3 * scale).sp)
                BookPageLayouter(measurer, density, BookPageTypography(body,
                    MaterialTheme.typography.headlineLarge.toSpanStyle(), MaterialTheme.typography.titleLarge.toSpanStyle(),
                    40.sp, 32.sp, 12.sp, 32.sp)).layout(sources, width, 560)
            } }
        } }
        waitForIdle()
        for (pages in runs) {
            assertTrue(pages.size > 2)
            val restored = pages.flatMap { it.sources }.joinToString("") { s ->
                sources.single { it.number == s.importPage }.text.substring(s.startUtf16, s.endUtf16)
            }
            assertEquals(raw, restored)
            assertEquals(raw, pages.joinToString("") { originalBookSelection(it.text) })
            for (page in pages) {
                assertTrue(page.heightPx + page.topPx <= 560, "full last line fits: ${page.heightPx}+${page.topPx}")
                for (slice in page.sources) {
                    val source = sources.single { it.number == slice.importPage }.text
                    assertEquals(slice.startUtf16, safeBookBoundary(source, slice.startUtf16))
                    assertEquals(slice.endUtf16, safeBookBoundary(source, slice.endUtf16))
                    assertEquals(page, pages[pages.pageContaining(BookPageAnchor(slice.importPage, slice.startUtf16))])
                }
                assertEquals(page.sources, page.selection(0, page.text.length))
            }
            val second = pages.first { it.title == "第二章 继续" }
            assertTrue(second.opener)
            assertEquals("二", second.chapterNumber)
            assertTrue(originalBookSelection(second.text).startsWith("第二章"))
        }
        println("PASS lossless reflow: 9 width/font combinations; local UTF-16 citations and CRLF/non-BMP copy")
    }

    @Test fun importBoundaryFlowsIntoSamePageAndSelectionReturnsBothSources() = runSkikoComposeUiTest {
        var pages = emptyList<BookLaidPage>()
        val sources = listOf(BookPage(41, "甲乙😀"), BookPage(42, "丙丁𠮷"))
        setContent { CoachTheme {
            val measurer = rememberTextMeasurer()
            pages = BookPageLayouter(measurer, LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 33.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp)).layout(sources, 594, 600)
        } }
        waitForIdle()
        assertEquals(1, pages.size)
        assertEquals("第 41–42 页", pages.single().sourceLabel)
        assertEquals(listOf(BookPageSlice(41, 2, 4), BookPageSlice(42, 0, 2)), pages.single().selection(2, 6))
        assertEquals("甲乙😀丙丁𠮷", originalBookSelection(pages.single().text))
    }

    @Test fun completeLinesWidowsAndHeadingsSurviveReflow() = runSkikoComposeUiTest {
        val raw = "甲乙丙丁戊己庚辛壬癸。".repeat(45) + "\n\n## 问题与证据\n\n" +
            "阅读这段话时要回到原文，检查证据与结论的关系。".repeat(45)
        var runs = emptyList<List<BookLaidPage>>()
        var layouts = emptyList<List<androidx.compose.ui.text.TextLayoutResult>>()
        setContent { CoachTheme {
            val measurer = rememberTextMeasurer()
            val type = BookPageTypography(MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 33.sp),
                SpanStyle(fontSize = 28.sp), SpanStyle(fontSize = 24.sp), 40.sp, 32.sp, 12.sp, 32.sp)
            runs = listOf(225, 360, 594).map { width ->
                BookPageLayouter(measurer, LocalDensity.current, type).layout(listOf(BookPage(7, raw)), width, 230)
            }
            layouts = runs.mapIndexed { i, pages -> pages.map { page ->
                measurer.measure(page.text, type.body, placeholders = page.placeholders,
                    constraints = Constraints(maxWidth = listOf(225, 360, 594)[i]))
            } }
        } }
        waitForIdle()
        for ((i, pages) in runs.withIndex()) {
            assertEquals(raw, pages.joinToString("") { originalBookSelection(it.text) })
            for ((j, page) in pages.withIndex()) {
                val layout = layouts[i][j]
                assertTrue(layout.lineCount >= 2, "no lone line at either end of a long paragraph: widthIndex=$i page=$j source=${page.sources} text=${originalBookSelection(page.text).replace("\n", "<LF>")}")
                assertTrue(layout.getLineBottom(layout.lineCount - 1) <= 230)
                assertFalse(originalBookSelection(page.text).trim().endsWith("## 问题与证据"), "heading stays with body")
            }
            val anchor = BookPageAnchor(7, raw.indexOf("证据与结论"))
            val containing = pages[pages.pageContaining(anchor)]
            assertTrue(containing.sources.any { anchor.offsetUtf16 in it.startUtf16 until it.endUtf16 })
            val state = BookPagingState(anchor).apply { this.pages = pages }
            val chapter = pages[state.index].chapter
            val chapterStart = pages.indexOfFirst { it.chapter == chapter }
            val chapterEnd = pages.indexOfLast { it.chapter == chapter }
            state.move(ReaderTurn.First)
            assertEquals(chapterStart, state.index)
            state.spread = true
            state.move(ReaderTurn.Next)
            assertEquals(chapterStart + 2, state.index)
            state.move(ReaderTurn.Last)
            assertEquals(chapterEnd, state.index)
        }
    }

    @Test fun copyingAcrossSheetsOmitsOnlyTheInjectedSeparator() {
        val selected = buildAnnotatedString {
            append("甲😀\r\n乙")
            addStringAnnotation("coach.book.page-body", "", 0, length)
            append("\n") // Compose's separator between two selectable Text nodes.
            val second = length
            append("丙𠮷丁")
            addStringAnnotation("coach.book.page-body", "", second, length)
        }
        assertEquals("甲😀\r\n乙丙𠮷丁", originalPagedBookSelection(selected))
        val crossingCrop = buildAnnotatedString {
            append(selected)
            append("\n")
            val next = length
            append("戊己")
            addStringAnnotation("coach.book.crop-gap", "框𠮷内", next, next + 1)
            addStringAnnotation("coach.book.page-body", "", next, length)
        }
        assertEquals("甲😀\r\n乙丙𠮷丁框𠮷内戊己", originalPagedBookSelection(crossingCrop))
        assertEquals("戊己", originalPagedBookSelection(crossingCrop.subSequence(crossingCrop.length - 2, crossingCrop.length)))
        val page = BookLaidPage(listOf(BookPageSlice(41, 0, 4)), 0, null, null, false,
            AnnotatedString("甲😀乙"), emptyList(), 0, 33)
        assertEquals(listOf(BookPageSlice(41, 1, 3)), page.selection(2, 3))
        assertEquals(page.selection(1, 3), page.selection(3, 1))
        assertEquals(emptyList(), page.selection(2, 2))
    }

    @Test fun trimmedImportedChaptersKeepSemanticBreakWithoutAddingText() = runSkikoComposeUiTest {
        val sources = listOf(BookPage(1, "第一章 起点\n\n甲乙丙。"), BookPage(2, "第二章 证据\n\n丁戊己。"))
        var pages = emptyList<BookLaidPage>()
        setContent { CoachTheme {
            pages = BookPageLayouter(rememberTextMeasurer(), LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 33.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp)).layout(sources, 594, 600)
        } }
        waitForIdle()
        assertEquals(listOf("第一章 起点", "第二章 证据"), pages.map { it.title })
        assertTrue(pages.all { it.opener })
        assertEquals(sources.joinToString("") { it.text }, pages.joinToString("") { originalBookSelection(it.text) })
        assertEquals(BookPageAnchor(2), pages[1].anchor)
    }

    @Test fun spreadThresholdTracksFontAndKeepsHysteresis() {
        for (scale in listOf(.9f, 1f, 1.15f)) {
            val col = 594 * scale
            val threshold = 2 * (col + 112) + 48
            assertFalse(bookSpread(threshold - 1, col, BookPageMode.Auto, false))
            assertTrue(bookSpread(threshold, col, BookPageMode.Auto, false))
            assertTrue(bookSpread(threshold - 23, col, BookPageMode.Auto, true))
            assertFalse(bookSpread(threshold - 25, col, BookPageMode.Auto, true))
            assertFalse(bookSpread(3000f, col, BookPageMode.Single, true))
            val minimum = 2 * (28 * 18 * scale + 112) + 48
            assertFalse(bookSpread(minimum - 1, col, BookPageMode.Spread, true))
            assertTrue(bookSpread(minimum + .01f, col, BookPageMode.Spread, false))
        }
    }

    @Test fun physicalPageMetadataTravelsWithItsChapterWithoutLosingSourceOffsets() = runSkikoComposeUiTest {
        val sources = listOf(
            BookPage(5, "<!-- PDF 第 5 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n第五页独立正文\n"),
            BookPage(6, "<!-- PDF 第 6 页 · 书第 2 页 -->\n# Chapter 2 Cells\n\n第六页独立正文\n"),
        )
        var pages = emptyList<BookLaidPage>()
        setContent { CoachTheme {
            pages = BookPageLayouter(rememberTextMeasurer(), LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 33.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp)).layout(sources, 594, 600)
        } }
        waitForIdle()
        assertEquals(listOf("Chapter 1 Light", "Chapter 2 Cells"), pages.map { it.title })
        assertTrue(pages.all { it.opener })
        assertEquals(listOf(BookPageAnchor(5), BookPageAnchor(6)), pages.map { it.anchor })
        assertTrue(originalBookSelection(pages[0].text).contains("第五页独立正文"))
        assertTrue(originalBookSelection(pages[1].text).contains("第六页独立正文"))
        assertEquals(sources.joinToString("") { it.text }, pages.joinToString("") { originalBookSelection(it.text) })
    }

    @Test fun deferredVisualsOfEveryKindKeepTheirReadingOrder() = runSkikoComposeUiTest(density = Density(1f)) {
        val before = "短句接续。".repeat(11)
        val covered = "原框😀内的文字"
        val after = "后文𠮷，接着读。".repeat(110)
        val raw = before + covered + after
        val region = com.coach.domain.reader.ReaderRect(.1, .1, .4, .3)
        val kinds = PageVisualKind.entries
        val layouts = mutableMapOf<PageVisualKind, List<BookLaidPage>>()
        setContent { CoachTheme {
            val layouter = BookPageLayouter(rememberTextMeasurer(), LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp))
            for (kind in kinds) {
                val figure = kind == PageVisualKind.ILLUSTRATION
                val item = BookIllustration(41, before.length, "a".repeat(64), "png", 280, 340,
                    caption = if (figure) "原图的说明" else null, kind = kind, sourceRegion = region,
                    sourceStart = if (figure) null else before.length, sourceEnd = if (figure) null else before.length + covered.length)
                layouts[kind] = layouter.layout(listOf(BookPage(41, raw, illustrations = listOf(item))), 310, 390)
            }
        } }
        waitForIdle()
        for ((kind, pages) in layouts) {
            val placed = pages.flatMap { it.visuals }.single()
            assertEquals(1, pages.indexOfFirst { it.visuals.isNotEmpty() }, kind.name)
            assertTrue(placed.heightPx + placed.captionGapPx + placed.captionHeightPx <= 390 * .85f)
            val firstEnd = pages.first().runs.last().sources.last().endUtf16
            // Text after the anchor never appears on a sheet before its visual (TON-1671).
            assertEquals(before.length, firstEnd, kind.name)
            val visualSheet = pages[1]
            assertTrue(visualSheet.runs.all { run -> run.topPx >= placed.topPx + placed.heightPx }, kind.name)
            val rendered = pages.flatMap { it.runs }.joinToString("") { originalBookSelection(it.text) }
            assertEquals(if (kind == PageVisualKind.ILLUSTRATION) raw else before + after, rendered)
            if (kind != PageVisualKind.ILLUSTRATION) assertEquals(1, pages.pageContaining(BookPageAnchor(41, before.length + 2)))
            assertEquals(raw, pages.flatMap { it.sources }.joinToString("") { raw.substring(it.startUtf16, it.endUtf16) })
            pages.forEachIndexed { index, page -> assertEquals(index, pages.pageContaining(page.anchor)) }
        }
    }

    @Test fun consecutiveImagesKeepOrderAndImageOnlyLeavesRemainReachable() = runSkikoComposeUiTest(density = Density(1f)) {
        val figures = (0..2).map { BookIllustration(7, 0, "a".repeat(64), "png", 280, 410, order = it) }
        var pages = emptyList<BookLaidPage>()
        setContent { CoachTheme {
            pages = BookPageLayouter(rememberTextMeasurer(), LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp)).layout(
                listOf(BookPage(7, "", illustrations = figures.reversed()), BookPage(8, "第二章 继续\n正文。")), 310, 390)
        } }
        waitForIdle()
        assertTrue(pages.filter { it.visuals.isNotEmpty() }.all { it.title == null })
        assertEquals(figures, pages.flatMap { it.visuals }.map { it.item })
        pages.forEachIndexed { index, page -> assertEquals(index, pages.pageContaining(page.anchor)) }
    }

    @Test fun pdfFigureOnlyTakesAnExactCaptionLineAndRetainsItsOffsets() = runSkikoComposeUiTest(density = Density(1f)) {
        val caption = "图 2 观察记录"
        val before = "前段𠮷。\r\n  "
        val after = "  \r\n接着读。"
        val raw = before + caption + after
        val item = BookIllustration(12, before.length, "a".repeat(64), "png", 240, 280, caption = caption,
            sourceRegion = com.coach.domain.reader.ReaderRect(.1, .1, .8, .8))
        var isolated = emptyList<BookLaidPage>()
        var embedded = emptyList<BookLaidPage>()
        setContent { CoachTheme {
            val layouter = BookPageLayouter(rememberTextMeasurer(), LocalDensity.current, BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 30.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp), pdf = true)
            isolated = layouter.layout(listOf(BookPage(12, raw, illustrations = listOf(item))), 260, 320)
            embedded = layouter.layout(listOf(BookPage(12, before + caption + "见正文" + after, illustrations = listOf(item))), 260, 320)
        } }
        waitForIdle()
        val index = isolated.indexOfFirst { it.visuals.isNotEmpty() }
        val visual = isolated[index].visuals.single()
        assertEquals(BookPageSlice(12, before.length, before.length + caption.length), visual.replacement)
        assertEquals(index, isolated.pageContaining(BookPageAnchor(12, before.length + 1)))
        assertEquals(before + after, isolated.flatMap { it.runs }.joinToString("") { originalBookSelection(it.text) })
        assertEquals(raw, isolated.flatMap { it.sources }.joinToString("") { raw.substring(it.startUtf16, it.endUtf16) })
        assertNull(embedded.flatMap { it.visuals }.single().replacement)
    }

    @Test fun imageAndCaptionMoveTogetherAndFormulaStaysInFlow() {
        val block = BookFigureBlock(BookPageAnchor(42, 8), 1000, 1200, 30)
        val placed = placeBookFigure(block, 594, 700, 100)
        assertEquals(1, placed.pageDelta)
        assertTrue(placed.heightPx + placed.captionHeightPx <= 700 * .85)
        assertTrue(placed.widthPx <= 594)
        assertEquals(0, placeBookFigure(block.copy(kind = BookFigureBlock.Kind.Formula), 594, 700, 100).pageDelta)
    }

    @Test fun immersiveLaptopColumnsAndPdfPairingScaleAcrossLaptopWidths() {
        // 1128x828 @ 150% (~738dp client) & 980dp window: single full-bleed column, no 720dp nesting box
        assertEquals(1, bookImmersiveColumns(738f, 684f, BookPageMode.Auto, 1))
        assertEquals(1, bookImmersiveColumns(980f, 684f, BookPageMode.Auto, 1))
        // 1536x864 @ 125% (~1213dp), 1920x1080 @ 150% (~1266dp), 1366x768 @ 100% (~1350dp),
        // 2560x1440 @ 150% (~1706dp), 1920dp and 2880dp: two facing pages, each line <= 720dp (TON-1671 ruling)
        for (w in listOf(1080f, 1213f, 1266f, 1350f, 1440f, 1706f, 1920f, 2880f)) {
            val cols = bookImmersiveColumns(w, 684f, BookPageMode.Auto, 1)
            assertEquals(2, cols, "widthDp=$w should use 2 facing pages")
            val sheet = w / cols
            val text = bookImmersiveTextDp(sheet, 6f)
            assertTrue(text <= 720.01f, "widthDp=$w line width $text exceeds 720dp")
            val (outer, spine) = bookImmersiveExtraMargins(sheet - 6f - text, spread = true)
            assertEquals(sheet, 6f + text + outer + spine, 0.01f, "widthDp=$w extra width goes to the margins")
            assertTrue(outer >= spine, "widthDp=$w outer margin $outer narrower than spine side $spine")
        }
        assertEquals(2, bookImmersiveColumns(1706f, 684f, BookPageMode.Spread, 2))
        assertEquals(2, bookImmersiveColumns(2880f, 684f, BookPageMode.Spread, 1))
        assertEquals(1, bookImmersiveColumns(750f, 684f, BookPageMode.Spread, 1))
        assertEquals(2, bookImmersiveColumns(760f, 684f, BookPageMode.Spread, 1))
        // A single page caps the line the same way and centres it.
        assertEquals(720f, bookImmersiveTextDp(1000f, 8f), 0.01f)
        assertEquals(Pair(136f, 136f), bookImmersiveExtraMargins(272f, spread = false))
        // Hysteresis keeps 2 columns slightly below 1080dp when shrinking
        assertEquals(2, bookImmersiveColumns(1060f, 684f, BookPageMode.Auto, 2))
        assertEquals(1, bookImmersiveColumns(1050f, 684f, BookPageMode.Auto, 2))
        assertEquals(1, bookImmersiveColumns(1600f, 684f, BookPageMode.Single, 2))

        // Immersive original PDF pairs 2 portrait pages on 1128x828 (738x511dp) and drops to 1 when TOC narrows width
        val aspects = mapOf(31 to 0.7683f, 32 to 0.7683f)
        assertEquals(listOf(listOf(31, 32)), originalPdfSheets(listOf(31, 32), aspects, 738f, 511f, immersive = true))
        assertEquals(listOf(listOf(31), listOf(32)), originalPdfSheets(listOf(31, 32), aspects, 460f, 511f, immersive = true))
        // Fit-width continuous scroll pairs portrait pages on wide windows and stays 1-up on compact windows or landscape pages
        assertEquals(listOf(listOf(31, 32)), originalPdfScrollSheets(listOf(31, 32), aspects, twoUp = true))
        assertEquals(listOf(listOf(31), listOf(32)), originalPdfScrollSheets(listOf(31, 32), aspects, twoUp = false))
        assertEquals(listOf(listOf(31), listOf(32)), originalPdfScrollSheets(listOf(31, 32), mapOf(31 to 1.3f, 32 to 0.7683f), twoUp = true))
    }

    @Test fun singleColumnUpwardDragSpotAnchorUsesTopEndOfSelection() = runSkikoComposeUiTest(density = Density(1f)) {
        val raw = (1..25).joinToString("\n\n") { "Paragraph $it discusses health economics demand elasticity and empirical bounds." }
        var pages = emptyList<BookLaidPage>()
        var capturedMeasurer: TextMeasurer? = null
        var capturedType: BookPageTypography? = null
        setContent { CoachTheme {
            val measurer = rememberTextMeasurer()
            val type = BookPageTypography(
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 33.sp),
                SpanStyle(), SpanStyle(), 40.sp, 32.sp, 12.sp, 32.sp,
            )
            capturedMeasurer = measurer
            capturedType = type
            pages = BookPageLayouter(measurer, LocalDensity.current, type, immersive = true)
                .layout(listOf(BookPage(32, raw)), 640, 500)
        } }
        waitForIdle()
        val measurer = assertNotNull(capturedMeasurer)
        val type = assertNotNull(capturedType)
        assertTrue(pages.isNotEmpty())
        val topAnchor = spotAnchor(
            pages, 0, multiColumn = false, side = 0,
            down = androidx.compose.ui.geometry.Offset(200f, 360f),
            up = androidx.compose.ui.geometry.Offset(120f, 40f),
            dragged = true, sheetWidthPx = 680f, measurer = measurer, typography = type, widthPx = 640,
        )
        val pressAnchor = spotAnchor(
            pages, 0, multiColumn = false, side = 0,
            down = androidx.compose.ui.geometry.Offset(200f, 360f),
            up = androidx.compose.ui.geometry.Offset(200f, 360f),
            dragged = false, sheetWidthPx = 680f, measurer = measurer, typography = type, widthPx = 640,
        )
        val expectedTop = pages[0].anchorAtY(40, measurer, type, 640)
        assertEquals(expectedTop, topAnchor, "single-column upward drag must record top end (up.y), not press point (down.y)")
        assertNotNull(pressAnchor)
        assertTrue(topAnchor!!.offsetUtf16 < pressAnchor.offsetUtf16, "top anchor offset ${topAnchor.offsetUtf16} must precede press offset ${pressAnchor.offsetUtf16}")
    }
}
