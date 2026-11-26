package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.ui.pageturn.PageTurnStyle
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.test.*

/** TON-1671：书页接上共用翻页组件后，双页对开的翻转/滑动在真实分页上能翻、落准、可拖。合成文字，不含教材。 */
@OptIn(ExperimentalTestApi::class)
class BookPageTurnViewTest {
    private val book = Textbook("isolated-turn", "阅读与证据", "md", listOf(BookPage(41,
        "# 第三章 阅读与证据\n\n" + "读者回到原文检查证据，再把结论接回已有经验。😀𠮷".repeat(500))))

    private fun SkikoComposeUiTest.shot(name: String) {
        val dir = File(System.getProperty("coach.turnShots") ?: "build/screens/ton1671").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    private fun run(style: PageTurnStyle, dark: Boolean) = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        mainClock.autoAdvance = false
        setContent { CoachTheme(dark) { androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) { Box(Modifier.fillMaxSize()) {
            BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, turnStyle = style)
        } } } }
        mainClock.advanceTimeBy(100); waitForIdle()
        assertTrue(state.spread, "2560x1440 should open as a spread")
        assertTrue(state.pages.size > 6)
        val tag = "${style.name.lowercase()}-${if (dark) "dark" else "light"}"
        shot("turn-start-$tag")
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(120); waitForIdle()
        shot("turn-mid-$tag")
        mainClock.advanceTimeBy(600); waitForIdle()
        assertEquals(2, state.index)
        onNodeWithTag("book-sheet-0").assertExists(); onNodeWithTag("book-sheet-1").assertExists()
        shot("turn-landed-$tag")
        // 键盘跳到章末（快翻）后落在最后一页
        runOnIdle { state.move(ReaderTurn.Last) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        assertEquals(state.pages.lastIndex, state.index)
        // 拖页角：从右下角往左拖过半，松手翻回上一页组合（向后）
        val before = state.index
        onNodeWithTag("book-page-turn").performTouchInput {
            val w = width.toFloat(); val h = height.toFloat()
            down(Offset(w * 0.03f, h - 60f))
            moveTo(Offset(w * 0.3f, h - 60f)); moveTo(Offset(w * 0.6f, h - 60f))
            up()
        }
        mainClock.advanceTimeBy(800); waitForIdle()
        assertTrue(state.index < before, "dragging the left corner right should turn back: $before -> ${state.index}")
        shot("turn-dragged-$tag")
    }

    @Test fun flipLight() = run(PageTurnStyle.Flip, false)
    @Test fun flipDark() = run(PageTurnStyle.Flip, true)
    @Test fun slideLight() = run(PageTurnStyle.Slide, false)

    private fun SkikoComposeUiTest.spreadHost(state: BookPagingState, mode: androidx.compose.runtime.MutableState<BookReadMode>) {
        mainClock.autoAdvance = false
        setContent { CoachTheme(false) { androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) { Box(Modifier.fillMaxSize()) {
            BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, turnStyle = PageTurnStyle.None, readMode = mode.value)
        } } } }
        mainClock.advanceTimeBy(100); waitForIdle()
    }

    private fun SkikoComposeUiTest.tenRounds(state: BookPagingState, mode: androidx.compose.runtime.MutableState<BookReadMode>, baseline: BookPageAnchor) {
        repeat(10) { round ->
            runOnIdle { mode.value = BookReadMode.Scroll }
            mainClock.advanceTimeBy(150); waitForIdle()
            onNodeWithTag("book-scroll-read").assertExists()
            runOnIdle { mode.value = BookReadMode.Paged }
            mainClock.advanceTimeBy(150); waitForIdle()
            assertEquals(baseline, state.anchor, "round $round: back in paging the anchor must equal the baseline")
        }
    }

    @Test fun leftPageOfASpreadKeepsTheExactAnchorOverTenRoundTrips() = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Paged)
        spreadHost(state, mode)
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(100); waitForIdle()
        assertTrue(state.spread)
        val baseline = state.anchor
        assertEquals(state.pages[state.index].anchor, baseline)
        runOnIdle { mode.value = BookReadMode.Scroll }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor, "entering scroll without scrolling must not move the anchor")
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor)
        tenRounds(state, mode, baseline)
    }

    @Test fun rightPageClickBecomesTheReadingSpotAndSurvivesTenRoundTrips() = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Paged)
        spreadHost(state, mode)
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(100); waitForIdle()
        val groupLeft = state.index
        val leftAnchor = state.anchor
        onNodeWithTag("book-sheet-1").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        mainClock.advanceTimeBy(100); waitForIdle()
        val baseline = state.anchor
        assertTrue(baseline.offsetUtf16 > leftAnchor.offsetUtf16, "a click on the right page must move the anchor into it")
        assertEquals(groupLeft, state.index, "the spread must not shift when the right page is clicked")
        runOnIdle { mode.value = BookReadMode.Scroll }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor)
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(baseline, state.anchor)
        tenRounds(state, mode, baseline)
    }

    @Test fun scrollModeKeepsThePageWhenSwitchingBackAndForthTenTimes() = runSkikoComposeUiTest(size = Size(2560f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Paged)
        spreadHost(state, mode)
        runOnIdle { state.move(ReaderTurn.Next); state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(100); waitForIdle()
        val baseline = state.anchor
        tenRounds(state, mode, baseline)
        runOnIdle { mode.value = BookReadMode.Scroll }
        mainClock.advanceTimeBy(150); waitForIdle()
        val scrollStartIndex = state.index
        onNodeWithTag("book-scroll-read").performTouchInput { swipeUp(startY = height * 0.9f, endY = height * 0.05f, durationMillis = 100) }
        mainClock.advanceTimeBy(3000); waitForIdle()
        assertTrue(state.index > scrollStartIndex, "scrolling down should advance the page: $scrollStartIndex -> ${state.index}")
        val after = state.anchor
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(after, state.anchor)
        shot("read-mode-paged-after-scroll")
    }

    @Test fun inScrollModeNextAndPreviousScrollOneScreenWithOverlap() = runSkikoComposeUiTest(size = Size(1440f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Scroll)
        spreadHost(state, mode)
        assertTrue(!state.spread)
        val start = state.anchor
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        val one = state.anchor
        assertTrue(one.offsetUtf16 > start.offsetUtf16, "Next in scroll mode must scroll forward: $start -> $one")
        // 一屏约 0.9 个视口：不会像翻页那样直接跳到下一页页首
        assertTrue(state.index <= 1, "one screen must not skip whole pages: index ${state.index}")
        runOnIdle { state.move(ReaderTurn.Previous) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        val back = state.anchor
        assertTrue(kotlin.math.abs(back.offsetUtf16 - start.offsetUtf16) < (one.offsetUtf16 - start.offsetUtf16) / 3 + 80,
            "Previous must return near the start: $start -> $one -> $back")
        repeat(30) { runOnIdle { state.move(ReaderTurn.Next) }; mainClock.advanceTimeBy(400); waitForIdle() }
        assertTrue(state.index == state.pages.lastIndex || state.anchor.offsetUtf16 > one.offsetUtf16, "repeated Next keeps scrolling to the end")
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        val pagedBefore = state.index
        runOnIdle { state.move(ReaderTurn.Previous) }
        assertTrue(state.index < pagedBefore || pagedBefore == 0, "in paged mode Previous still turns a page")
    }

    @Test fun inScrollModeThePreviousButtonStateFollowsTheRealScrollEdges() = runSkikoComposeUiTest(size = Size(1440f, 1440f), density = Density(1.5f)) {
        val state = BookPagingState(BookPageAnchor(41))
        val mode = androidx.compose.runtime.mutableStateOf(BookReadMode.Scroll)
        spreadHost(state, mode)
        assertFalse(state.canPrevious, "at the very top there is nothing above")
        assertTrue(state.canNext)
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(1500); waitForIdle()
        assertTrue(state.canPrevious, "after scrolling half a screen down the previous button must be enabled")
        repeat(60) { runOnIdle { state.move(ReaderTurn.Next) }; mainClock.advanceTimeBy(300); waitForIdle() }
        assertFalse(state.canNext, "at the very bottom there is nothing below")
        runOnIdle { mode.value = BookReadMode.Paged }
        mainClock.advanceTimeBy(150); waitForIdle()
        assertEquals(state.index > 0, state.canPrevious, "paged mode goes back to page-index rules")
    }

    @Test fun immersiveTopLeftMouseDragSelectsTextWithoutFlippingPage() = runSkikoComposeUiTest(
        size = Size(2160f, 1350f),
        density = Density(1.5f),
    ) {
        val pages = (1..8).map { p ->
            BookPage(p, "Section $p opening sentence for selection verification. " + "Additional textbook evidence on page $p. ".repeat(40))
        }
        val longBook = Textbook("immersive-drag-select", "健康经济学", "pdf", pages)
        val state = BookPagingState(BookPageAnchor(4))
        var selected by mutableStateOf<SourceBlock?>(null)
        setContent {
            CoachTheme(false) {
                androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(
                            book = longBook,
                            state = state,
                            highlights = selected?.let { mapOf(it.page to listOf(it.start until it.end)) } ?: emptyMap(),
                            onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.Flip,
                            onSelectText = { selected = it },
                            immersive = true,
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { state.pages.isNotEmpty() && state.index >= 2 }
        waitForIdle()

        val initialIndex = state.index
        val initialAnchor = state.anchor
        val turnBounds = onNodeWithTag("book-page-turn").fetchSemanticsNode().boundsInRoot
        val textBounds = onNodeWithTag("reader-text").fetchSemanticsNode().boundsInRoot
        // Start on the top-left first line of reader-text inside PageTurnView's inEdge (< 8%/14%) and inCorner (< 18%)
        val startX = (textBounds.left - turnBounds.left) + 8f
        val startY = (textBounds.top - turnBounds.top) + 12f
        assertTrue(startX < turnBounds.width * 0.08f, "startX=$startX must sit inside PageTurnView mouse edge (${turnBounds.width * 0.08f})")
        assertTrue(startY < turnBounds.height * 0.18f, "startY=$startY must sit inside PageTurnView mouse corner (${turnBounds.height * 0.18f})")

        onNodeWithTag("book-page-turn").performMouseInput {
            moveTo(Offset(startX, startY))
            press()
            moveTo(Offset(startX + 260f, startY))
            release()
        }
        mainClock.advanceTimeBy(800); waitForIdle()

        val chosen = assertNotNull(selected, "top-left mouse drag must select text")
        assertEquals(initialAnchor.importPage, chosen.page, "selected text must come from current page, not previous page")
        assertTrue(chosen.text.isNotBlank(), "selected text must not be blank")
        assertEquals(initialIndex, state.index, "top-left text drag must not flip PageTurnView")
    }

    @Test fun deskOuterMarginClickStillTurnsPageWhileTextClickDoesNot() = runSkikoComposeUiTest(
        size = Size(900f, 900f),
        density = Density(1f),
    ) {
        val pages = (1..6).map { p ->
            BookPage(p, "Page $p sentence for margin click check. " + "Supporting content on page $p. ".repeat(30))
        }
        val testBook = Textbook("margin-click-turn", "阅读", "md", pages)
        val state = BookPagingState(BookPageAnchor(3))
        var selected by mutableStateOf<SourceBlock?>(null)
        setContent {
            CoachTheme(false) {
                androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(
                            book = testBook,
                            state = state,
                            highlights = emptyMap(),
                            onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.Flip,
                            onSelectText = { selected = it },
                            immersive = false,
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { state.pages.isNotEmpty() && state.index > 0 }
        waitForIdle()
        val before = state.index
        val turnBounds = onNodeWithTag("book-page-turn").fetchSemanticsNode().boundsInRoot
        val textBounds = onNodeWithTag("reader-text").fetchSemanticsNode().boundsInRoot
        // Click on text inside PageTurnView's left 14% edge: must NOT turn page
        val textClickX = (textBounds.left - turnBounds.left) + 12f
        val clickY = (textBounds.top - turnBounds.top) + 24f
        assertTrue(textClickX < turnBounds.width * 0.14f)
        onNodeWithTag("book-page-turn").performMouseInput { click(Offset(textClickX, clickY)) }
        mainClock.advanceTimeBy(800); waitForIdle()
        assertEquals(before, state.index, "clicking on text inside left edge must not turn page")

        // Click on non-text outer margin (x = 12f < 64.dp leftInset): explicit margin click turns back
        onNodeWithTag("book-page-turn").performMouseInput { click(Offset(12f, clickY)) }
        mainClock.advanceTimeBy(800); waitForIdle()
        assertTrue(state.index < before, "clicking non-text left margin should still turn back: $before -> ${state.index}")
    }

    @Test fun mouseClickOnReaderTextSelectsTheWordUnderThePointer() = runSkikoComposeUiTest(
        size = Size(900f, 900f),
        density = Density(1f),
    ) {
        val pages = listOf(BookPage(1, "Memory and understanding starts with active recall rather than rereading."))
        val testBook = Textbook("click-word", "阅读", "pdf", pages)
        val state = BookPagingState(BookPageAnchor(1))
        var selected by mutableStateOf<SourceBlock?>(null)
        setContent {
            CoachTheme(false) {
                androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(
                            book = testBook,
                            state = state,
                            highlights = emptyMap(),
                            onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.None,
                            onSelectText = { selected = it },
                            immersive = true,
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { state.pages.isNotEmpty() }
        waitForIdle()
        onNodeWithTag("reader-text").performMouseInput { click(Offset(width * 0.35f, 8f)) }
        mainClock.advanceTimeBy(300); waitForIdle()
        val chosen = assertNotNull(selected, "a mouse click on the glyphs must select the word under the pointer")
        assertTrue(chosen.text.isNotBlank())
        assertTrue(pages[0].text.contains(chosen.text), "selected word must come from the page text: ${chosen.text}")
    }

    @Test fun backgroundLayoutLongBookKeepsCompletedPaginationOnHighlightUpdates() = runSkikoComposeUiTest(
        size = Size(1280f, 800f),
        density = Density(1f),
    ) {
        val pages = (1..75).map { p ->
            BookPage(p, "# 第 $p 章\n\nImport page $p discusses demand elasticity and marginal valuation in healthcare markets. " +
                "Repeated paragraph text to ensure each page lays out cleanly across the book. ".repeat(4))
        }
        val longBook = Textbook("long-bg-book", "长书测试", "pdf", pages)
        val state = BookPagingState(BookPageAnchor(40))
        var highlights by mutableStateOf<Map<Int, List<IntRange>>>(emptyMap())
        setContent {
            CoachTheme(false) {
                androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(
                            book = longBook,
                            state = state,
                            highlights = highlights,
                            onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.Flip,
                            immersive = true,
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { state.completedPagination != null && state.pages.size > 64 }
        waitForIdle()

        val completedBefore = assertNotNull(state.completedPagination)
        val pageCountBefore = state.pages.size
        val indexBefore = state.index
        val visibleSourcesBefore = state.pages[indexBefore].sources
        val unaffectedPageBefore = state.pages[0]

        // Apply a textSelection-style highlight on import page 40
        highlights = mapOf(40 to listOf(0 until 32))
        mainClock.advanceTimeBy(500); waitForIdle()

        onNodeWithTag("book-page-loading").assertDoesNotExist()
        assertSame(completedBefore, state.completedPagination, "highlight change must not invalidate completedPagination")
        assertEquals(pageCountBefore, state.pages.size, "highlight change must not fall back to near layout")
        assertEquals(indexBefore, state.index, "highlight change must not shift visible page index")
        assertEquals(visibleSourcesBefore, state.pages[state.index].sources, "highlight change must not alter page boundaries")
        assertSame(unaffectedPageBefore, state.pages[0], "unaffected laid pages should be reused directly")
        assertTrue(
            state.pages[state.index].runs.any { run -> run.text.spanStyles.any { it.item.background != androidx.compose.ui.graphics.Color.Unspecified } },
            "updated highlight must still be applied to the visible page runs",
        )

        // Clear highlight again and ensure layout stays completely stable
        highlights = emptyMap()
        mainClock.advanceTimeBy(500); waitForIdle()
        assertSame(completedBefore, state.completedPagination)
        assertEquals(pageCountBefore, state.pages.size)
        assertEquals(indexBefore, state.index)
        assertEquals(visibleSourcesBefore, state.pages[state.index].sources)
    }

    @Test fun spreadRightColumnHighlightKeepsGroupIndexAndAdvancesByColumns() = runSkikoComposeUiTest(
        size = Size(2560f, 1440f),
        density = Density(1.5f),
    ) {
        val state = BookPagingState(BookPageAnchor(41))
        var selected by mutableStateOf<SourceBlock?>(null)
        var extraHighlights by mutableStateOf<Map<Int, List<IntRange>>>(emptyMap())
        setContent {
            CoachTheme(false) {
                androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        val mergedHighlights = buildMap<Int, List<IntRange>> {
                            extraHighlights.forEach { (k, v) -> put(k, v) }
                            selected?.let { put(it.page, getOrDefault(it.page, emptyList()) + listOf(it.start until it.end)) }
                        }
                        BookPageView(
                            book = book,
                            state = state,
                            highlights = mergedHighlights,
                            onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.Flip,
                            onSelectText = { selected = it },
                            immersive = true,
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { state.pages.size >= 6 && state.spread }
        waitForIdle()

        val cols = state.columns
        assertTrue(cols >= 2, "2560x1440 @1.5 must be multi-column spread")
        val groupStart = state.index
        assertEquals(0, groupStart)

        // Select text on the second column (book-sheet-1) so anchor moves to page 1 and highlights become non-empty
        onNodeWithTag("book-sheet-1").performMouseInput {
            moveTo(Offset(width * 0.25f, height * 0.35f))
            press()
            moveTo(Offset(width * 0.75f, height * 0.35f))
            release()
        }
        mainClock.advanceTimeBy(800); waitForIdle()

        assertNotNull(selected, "dragging on right column must select text")
        assertEquals(groupStart + 1, state.pages.pageContaining(state.anchor), "anchor must sit in second column")
        assertEquals(groupStart, state.index, "highlight update on right column must keep spread group's first index")

        // Also update external highlights (e.g. bookmark/citation) while anchor remains in right column
        val slice = state.pages[groupStart + 1].sources.first()
        extraHighlights = mapOf(slice.importPage to listOf(slice.startUtf16 until (slice.startUtf16 + 6).coerceAtMost(slice.endUtf16)))
        mainClock.advanceTimeBy(500); waitForIdle()
        assertEquals(groupStart, state.index, "external highlight update must still keep spread group's first index")
        assertTrue(
            state.pages[groupStart + 1].runs.any { run ->
                run.text.spanStyles.any { it.item.background != androidx.compose.ui.graphics.Color.Unspecified }
            },
            "highlight span must still render on the right column",
        )

        // Turning Next must advance by exactly `columns` from `groupStart`, never skipping from `groupStart + 1`
        runOnIdle { state.move(ReaderTurn.Next) }
        mainClock.advanceTimeBy(800); waitForIdle()
        assertEquals(groupStart + cols, state.index, "move(Next) must advance by columns ($cols), not skip a group")
    }

    @Test fun nearChapterLayoutMatchesFullBookBoundariesMidChapterWithZeroJump() = runSkikoComposeUiTest(
        size = Size(1280f, 800f),
        density = Density(1f),
    ) {
        // 80 import pages: Chapter 1 (1..14), Chapter 2 (15..35, >2 pages before anchor at page 25), Chapter 3 (36..80)
        val pages = (1..80).map { p ->
            val prefix = when (p) {
                1 -> "# Chapter 1 Foundations\n\n"
                15 -> "# Chapter 2 Health Demand\n\n"
                36 -> "# Chapter 3 Insurance Markets\n\n"
                else -> ""
            }
            BookPage(p, prefix + "Page $p body sentence explaining empirical marginal value and demand schedules.\n\n" +
                "Continuation paragraph on page $p for cross-page layout boundary verification. ".repeat(6))
        }
        val longBook = Textbook("mid-chapter-near-test", "健康经济学", "pdf", pages)
        val midAnchor = BookPageAnchor(25)

        // 1. Direct layouter equivalence: nearChapterLayoutPages at page 25 (10 pages after Chapter 2 start)
        // produces the exact same laid page slices & run text as full-book layout.
        var nearSlicesAtAnchor = emptyList<BookPageSlice>()
        var nearTextAtAnchor = ""
        var fullSlicesAtAnchor = emptyList<BookPageSlice>()
        var fullTextAtAnchor = ""
        val state = BookPagingState(midAnchor)
        setContent {
            CoachTheme(false) {
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val density = androidx.compose.ui.platform.LocalDensity.current
                val body = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                val type = BookPageTypography(
                    body,
                    androidx.compose.ui.text.SpanStyle(), androidx.compose.ui.text.SpanStyle(),
                    body.lineHeight, body.lineHeight,
                    with(density) { 12.dp.toSp() }, with(density) { 32.dp.toSp() },
                )
                androidx.compose.runtime.SideEffect {
                    if (nearSlicesAtAnchor.isEmpty()) {
                        val nearWin = nearChapterLayoutPages(pages, midAnchor, tailPages = 2, maxPages = 64, pdf = true)
                        assertEquals(15, nearWin.pages.first().number, "near window must start from Chapter 2 start page 15")
                        assertEquals(27, nearWin.pages.last().number)
                        val nearLaid = BookPageLayouter(measurer, density, type, pdf = true, immersive = true)
                            .layout(nearWin.pages, 680, 740, forceAuthoritativeTocHeadings = nearWin.authoritativeTocHeadings)
                        val fullLaid = BookPageLayouter(measurer, density, type, pdf = true, immersive = true)
                            .layout(pages, 680, 740)
                        val nearIdx = nearLaid.pageContaining(midAnchor)
                        val fullIdx = fullLaid.pageContaining(midAnchor)
                        nearSlicesAtAnchor = nearLaid[nearIdx].sources
                        nearTextAtAnchor = nearLaid[nearIdx].runs.joinToString("\n") { it.text.text }
                        fullSlicesAtAnchor = fullLaid[fullIdx].sources
                        fullTextAtAnchor = fullLaid[fullIdx].runs.joinToString("\n") { it.text.text }
                    }
                }
                androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(
                            book = longBook,
                            state = state,
                            highlights = emptyMap(),
                            onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.Flip,
                            immersive = true,
                        )
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 30_000) { state.completedPagination != null && state.pages.isNotEmpty() }
        waitForIdle()
        assertEquals(fullSlicesAtAnchor, nearSlicesAtAnchor, "near and full layout must have identical slices at mid-chapter anchor")
        assertEquals(fullTextAtAnchor, nearTextAtAnchor, "near and full layout must have identical text at mid-chapter anchor")
        assertEquals(midAnchor.importPage, state.anchor.importPage, "completed full layout must keep anchor on page 25")
        val topLine = onNodeWithTag("reader-text").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.lineSequence().first()
        assertTrue(topLine.contains("Page 25") || state.pages[state.index].sources.any { it.importPage == 25 },
            "visible sheet after laidOut completion must stay on page 25, topLine=$topLine")
    }
}
