package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import java.nio.file.Files
import java.nio.file.Paths
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.test.Test

/** TON-1724 诊断：只在给了 BOYDEN_PDF 环境变量时跑，量文字版首次出字耗时，只输出计数和毫秒。 */
@OptIn(ExperimentalTestApi::class)
class BoydenLayoutTimingTest {
    @Test fun timeFirstPaint() {
        val src = System.getenv("BOYDEN_PDF") ?: return
        val root = Files.createTempDirectory("boyden")
        try {
            val t0 = System.nanoTime()
            val book = TextbookLibrary(root).importBook(Paths.get(src))
            println("TIMING import ms=${(System.nanoTime() - t0) / 1_000_000} pages=${book.pages.size} chars=${book.pages.sumOf { it.text.length }}")
            for (round in 1..2) runSkikoComposeUiTest(size = Size(900f, 1000f), density = Density(1f)) {
                val state = BookPagingState(BookPageAnchor(20))
                val start = System.nanoTime()
                setContent { CoachTheme { Box(Modifier.width(800.dp).height(900.dp)) {
                    BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, libraryDirectory = root)
                } } }
                waitUntil(timeoutMillis = 600_000) { onAllNodesWithTag("book-sheet-0").fetchSemanticsNodes().isNotEmpty() }
                val firstMs = (System.nanoTime() - start) / 1_000_000; val firstPages = state.pages.size
                waitUntil(timeoutMillis = 600_000) { state.pages.size > firstPages || firstPages >= 436 }
                println("TIMING round=$round firstTextMs=$firstMs firstPages=$firstPages fullMs=${(System.nanoTime() - start) / 1_000_000}")
                println("TIMING round=$round firstPaint ms=${(System.nanoTime() - start) / 1_000_000} laidPages=${state.pages.size}")
            }
        } finally { root.toFile().deleteRecursively() }
    }
}

/** TON-1724：Fold8 展开→合上，同一本 Boyden 第 20 页，宽屏排版后改窄，看窄屏选中的子页是否含同一锚点。BOYDEN_PDF 未设则跳过。 */
@OptIn(ExperimentalTestApi::class)
class BoydenFoldReflowTest {
    @Test fun narrowPageKeepsTheAnchor() {
        val src = System.getenv("BOYDEN_PDF") ?: return
        val root = Files.createTempDirectory("boyden-fold")
        try {
            val book = TextbookLibrary(root).importBook(Paths.get(src))
            runSkikoComposeUiTest(size = Size(1400f, 1000f), density = Density(1f)) {
                val state = BookPagingState(BookPageAnchor(20))
                var width by androidx.compose.runtime.mutableStateOf(1200)
                setContent { CoachTheme { Box(Modifier.width(width.dp).height(900.dp)) {
                    BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, libraryDirectory = root)
                } } }
                fun settled(n: Int) = waitUntil(timeoutMillis = 600_000) { state.pages.size > n && onAllNodesWithTag("book-sheet-0").fetchSemanticsNodes().isNotEmpty() }
                settled(100)
                val wide = state.pages[state.index]
                val s = wide.sources.first { it.importPage == 20 }
                val target = (s.startUtf16 + s.endUtf16) / 2
                println("DIAG wide pages=${state.pages.size} index=${state.index} slice=${s.startUtf16}..${s.endUtf16} target=$target")
                for (anchorKind in listOf("pageStart", "exactTarget")) {
                    width = 1200; waitForIdle()
                    state.anchor = if (anchorKind == "pageStart") wide.anchor else BookPageAnchor(20, target)
                    val before = state.pages.size
                    width = 520; waitForIdle()
                    waitUntil(timeoutMillis = 600_000) { state.pages.size != before }
                    val narrow = state.pages[state.index]
                    val ns = narrow.sources.filter { it.importPage == 20 }
                    println("DIAG $anchorKind narrowPages=${state.pages.size} narrowIdx=${state.index} slices=${ns.map { "${it.startUtf16}..${it.endUtf16}" }} containsAnchor=${ns.any { state.anchor.offsetUtf16 in it.startUtf16 until it.endUtf16 }} containsTarget=${ns.any { target in it.startUtf16 until it.endUtf16 }}")
                }
                fun reflow(to: Int) { val b = state.pages; width = to; waitUntil(timeoutMillis = 600_000) { state.pages !== b && state.pages.size > 100 } }
                fun shows(a: BookPageAnchor) = state.pages[state.index].sources.any { it.importPage == a.importPage && a.offsetUtf16 in it.startUtf16 until it.endUtf16 }
                width = 1200; waitForIdle()
                val spot = BookPageAnchor(20, target)
                state.anchor = spot
                val steps = mutableListOf<Boolean>()
                steps += shows(spot); reflow(520); steps += shows(spot); reflow(1200); steps += shows(spot)
                println("DIAG spot wide/narrow/wide visible=$steps")
                reflow(520)
                val moved = state.move(ReaderTurn.Next)!!
                val afterTurn = shows(moved); reflow(1200); val wideAfter = shows(moved) && state.anchor == moved
                reflow(520); val narrowAfter = shows(moved) && state.anchor == moved
                println("DIAG activeTurn movedPage=${moved.importPage}@${moved.offsetUtf16} visibleAfterTurn=$afterTurn wide=$wideAfter narrow=$narrowAfter")
                check(steps.all { it } && afterTurn && wideAfter && narrowAfter)
            }
        } finally { root.toFile().deleteRecursively() }
    }
}

/** TON-1724：翻页 → 保存 → 冷启 的锚点写入与解析（只输出页码和偏移）。BOYDEN_PDF 未设则跳过。 */
@OptIn(ExperimentalTestApi::class)
class BoydenSaveRestoreTest {
    @Test fun turnSaveColdStart() { for (w in listOf(800, 1500)) turnSaveColdStartAt(w) }
    private fun turnSaveColdStartAt(W: Int) {
        val src = System.getenv("BOYDEN_PDF") ?: return
        val root = Files.createTempDirectory("boyden-save")
        try {
            val library = TextbookLibrary(root)
            val book = library.importBook(Paths.get(src))
            var savedPage = 20
            var savedSpot: BookPageAnchor? = null
            fun shownFirst(state: BookPagingState) = state.pages[state.index].let { "${it.anchor.importPage}@${it.anchor.offsetUtf16}" }
            runSkikoComposeUiTest(size = Size(1400f, 1000f), density = Density(1f)) {
                val state = BookPagingState(BookPageAnchor(20))
                setContent { CoachTheme { Box(Modifier.width(W.dp).height(900.dp)) {
                    BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, libraryDirectory = root,
                        onPosition = { savedPage = it }, onSpot = { savedSpot = it })
                } } }
                waitUntil(timeoutMillis = 600_000) { state.pages.size > 100 }
                for (turn in 1..3) {
                    state.move(ReaderTurn.Next); waitForIdle()
                    println("DIAG w=$W spread=${state.spread} turn=$turn shown=${shownFirst(state)} anchor=${state.anchor.importPage}@${state.anchor.offsetUtf16} savedPage=$savedPage savedSpot=${savedSpot?.importPage}@${savedSpot?.offsetUtf16}")
                }
            }
            library.saveReadSpot(book.id, savedSpot!!.importPage, savedSpot!!.offsetUtf16)
            val spot = library.savedReadSpot(book.id)?.takeIf { it.first == savedPage }
            println("DIAG cold restore savedPage=$savedPage spot=${library.savedReadSpot(book.id)} used=${spot != null}")
            runSkikoComposeUiTest(size = Size(1400f, 1000f), density = Density(1f)) {
                val state = BookPagingState(BookPageAnchor(savedPage, spot?.second ?: 0))
                setContent { CoachTheme { Box(Modifier.width(W.dp).height(900.dp)) {
                    BookPageView(book, state, emptyMap(), onTurn = { state.move(it) }, libraryDirectory = root)
                } } }
                waitUntil(timeoutMillis = 600_000) { state.pages.isNotEmpty() }
                println("DIAG cold near-phase pages=${state.pages.size} shown=${shownFirst(state)} contains=${state.pages[state.index].sources.any { it.importPage == 20 && 1726 in it.startUtf16 until it.endUtf16 }}")
                waitUntil(timeoutMillis = 600_000) { state.pages.size > 100 }
                println("DIAG w=$W cold full pages=${state.pages.size} shown=${shownFirst(state)} contains=${state.pages[state.index].sources.any { it.importPage == 20 && 1726 in it.startUtf16 until it.endUtf16 }}")
                println("DIAG cold shown=${shownFirst(state)} (should equal the last turn's shown)")
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
