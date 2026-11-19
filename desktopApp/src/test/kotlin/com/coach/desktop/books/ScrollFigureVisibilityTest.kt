package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.ui.CoachTheme
import com.coach.ui.pageturn.PageTurnStyle
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** TON-1671：滚动读文字版里，远处的插图滚进视口后能显示出来；合成书，只测滚动读本身。 */
@OptIn(ExperimentalTestApi::class)
class ScrollFigureVisibilityTest {
    @Test fun aFigureFarBelowBecomesVisibleOnceScrolledIntoTheViewport() {
        val dir = Files.createTempDirectory("scroll-figure-")
        try {
            val image = BufferedImage(900, 205, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try { g.color = Color(28, 104, 102); g.fillRect(0, 0, image.width, image.height) } finally { g.dispose() }
            ImageIO.write(image, "png", dir.resolve("wave.png").toFile())
            val filler = "这一段是用来把插图推到很远的地方的文字，读者需要一直往下读才能走到图。"
            val md = "# 滚动读插图\n\n" + filler.repeat(120) + "\n\n![图一 一个周期的波](wave.png)\n\n" + filler.repeat(60)
            val source = dir.resolve("Figs.md")
            Files.writeString(source, md)
            val library = TextbookLibrary(dir.resolve("library"))
            val book = library.importBook(source)
            val figure = book.pages.flatMap { it.illustrations }.single()
            val tag = "book-figure-${figure.page}-${figure.order}"
            runSkikoComposeUiTest(size = Size(1440f, 1440f), density = Density(1.5f)) {
                val state = BookPagingState(BookPageAnchor(book.pages.first().number))
                var mode by mutableStateOf(BookReadMode.Scroll)
                mainClock.autoAdvance = false
                setContent { CoachTheme(false) { androidx.compose.material3.Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                    Box(Modifier.fillMaxSize()) {
                        BookPageView(book, state, emptyMap(), onTurn = { state.move(it) },
                            libraryDirectory = library.directory, turnStyle = PageTurnStyle.None, readMode = mode)
                    }
                } } }
                mainClock.advanceTimeBy(300); waitForIdle()
                assertTrue(state.pages.size > 3, "the filler must span several sheets: ${state.pages.size}")
                val figurePage = state.pages.indexOfFirst { page -> page.visuals.any { it.item.order == figure.order && it.item.page == figure.page } }
                assertTrue(figurePage > 0, "the figure must sit on a later sheet, got $figurePage")
                onNodeWithTag(tag).assertDoesNotExist() // 还没滚到：懒加载列表里不应有它
                var steps = 0
                while (state.index < figurePage && steps < 60) {
                    runOnIdle { state.move(ReaderTurn.Next) }
                    mainClock.advanceTimeBy(400); waitForIdle(); steps++
                }
                // 再往下滚一屏，确保图所在那张纸的位置落进视口
                repeat(3) {
                    if (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
                        runOnIdle { state.move(ReaderTurn.Next) }
                        mainClock.advanceTimeBy(400); waitForIdle()
                    }
                }
                val node = onNodeWithTag(tag)
                node.assertExists()
                val root = onRoot().fetchSemanticsNode().boundsInRoot
                val b = node.fetchSemanticsNode().boundsInRoot
                assertTrue(b.width > 50f && b.height > 20f, "the figure has a real size: $b")
                assertTrue(b.top >= root.top - 1f && b.bottom <= root.bottom + 1f, "the figure is fully inside the viewport: $b in $root")
                // 切回翻页读再回来，图仍可见
                runOnIdle { mode = BookReadMode.Paged }
                mainClock.advanceTimeBy(300); waitForIdle()
                runOnIdle { mode = BookReadMode.Scroll }
                mainClock.advanceTimeBy(400); waitForIdle()
                node.assertExists()
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
