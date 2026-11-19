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
