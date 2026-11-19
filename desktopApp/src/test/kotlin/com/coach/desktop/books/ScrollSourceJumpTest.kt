package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import coach.design.tokens.CoachV7
import com.coach.ui.CoachTheme
import com.coach.ui.pageturn.PageTurnStyle
import java.nio.file.Files
import kotlin.test.*

/**
 * TON-1671：滚动读文字版里，从出处跳转（把阅读锚点设到被引句，高亮传入被引范围，和 `TextbookReader.openPassage` 同一做法）后，
 * 被引原句整句落在视口内、整句带 hl 背景，并停在屏幕上方 1/3 附近。自写多页材料、独立空书库、离线。
 */
@OptIn(ExperimentalTestApi::class)
class ScrollSourceJumpTest {
    private class Spot(val inside: Boolean, val fullyMarked: Boolean, val topFromViewport: Float, val viewportHeight: Float)

    private fun SkikoComposeUiTest.spotOf(marker: String): Spot? {
        val hl = CoachV7.Light.hl
        val viewport = onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("reader-text", "reader-text-right")) for (node in onAllNodesWithTag(tag).fetchSemanticsNodes()) {
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            val text = layout.layoutInput.text
            val at = text.text.indexOf(marker)
            if (at < 0) continue
            val origin = node.positionInRoot
            var inside = true
            var top = Float.MAX_VALUE
            for (i in at until at + marker.length) {
                val box = layout.getBoundingBox(i)
                top = minOf(top, origin.y + box.top)
                if (origin.y + box.top < viewport.top || origin.y + box.bottom > viewport.bottom ||
                    origin.x + box.left < viewport.left || origin.x + box.right > viewport.right) inside = false
            }
            val marked = (at until at + marker.length).all { i -> text.spanStyles.any { it.item.background == hl && it.start <= i && it.end > i } }
            return Spot(inside, marked, top - viewport.top, viewport.height)
        }
        return null
    }

    @Test fun clickingASourceInContinuousScrollShowsTheCitedSentenceFullyMarked() {
        val dir = Files.createTempDirectory("scroll-source-jump")
        try {
            val input = dir.resolve("numbered.md")
            Files.writeString(input, "# 出处跳转\n\n" + (1..520).joinToString("") { "句" + it.toString().padStart(4, '0') + "。读者找到这句话，再接着读下一句。" })
            val library = TextbookLibrary(dir.resolve("library"))
            val book = library.importBook(input)
            fun cited(n: Int): Triple<String, Int, IntRange> {
                val marker = "句" + n.toString().padStart(4, '0') + "。"
                val page = book.pages.first { it.text.contains(marker) }
                val start = page.text.indexOf(marker)
                return Triple(marker, page.number, start until start + marker.length)
            }
            runSkikoComposeUiTest(size = Size(1440f, 1440f), density = Density(1.5f)) {
                val state = BookPagingState(BookPageAnchor(book.pages.first().number))
                var highlights by mutableStateOf(emptyMap<Int, List<IntRange>>())
                mainClock.autoAdvance = false
                setContent { CoachTheme(false) { androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(book, state, highlights, onTurn = { state.move(it) },
                            turnStyle = PageTurnStyle.None, readMode = BookReadMode.Scroll)
                    }
                } } }
                mainClock.advanceTimeBy(300); waitForIdle()
                // 先后两次出处：远处的第 400 句，再回到较前的第 120 句
                for (n in listOf(400, 120, 301)) {
                    val (marker, pageNumber, range) = cited(n)
                    runOnIdle {
                        highlights = mapOf(pageNumber to listOf(range))
                        state.anchor = BookPageAnchor(pageNumber, range.first) // openPassage 的做法
                    }
                    mainClock.advanceTimeBy(900); waitForIdle()
                    val seen = listOf("reader-text", "reader-text-right").flatMap { t -> onAllNodesWithTag(t).fetchSemanticsNodes().mapNotNull { node ->
                        val r = mutableListOf<TextLayoutResult>(); node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(r)
                        Regex("句[0-9]{4}。").findAll(r.single().layoutInput.text.text).map { it.value }.let { m -> m.firstOrNull()?.plus("..")?.plus(m.lastOrNull()) } } }
                    val spot = assertNotNull(spotOf(marker), "cited sentence $marker must be laid out in scroll mode; anchor=${state.anchor} index=${state.index} pages=${state.pages.size} nodes=$seen")
                    assertTrue(spot.inside, "$marker must be fully inside the viewport (top=${spot.topFromViewport}, viewport=${spot.viewportHeight})")
                    assertTrue(spot.fullyMarked, "$marker must be fully marked with the highlight background")
                    assertTrue(spot.topFromViewport in 0f..(spot.viewportHeight * 0.6f),
                        "$marker should land in the upper part of the screen, got ${spot.topFromViewport} of ${spot.viewportHeight}")
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
