package com.coach.desktop.books

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.Surface
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.coach.desktop.reader.Located
import com.coach.ui.CoachTheme
import org.apache.pdfbox.Loader
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookCitedRangeTest {
    @Test fun verifiedCitationShowsOnlyTheLowerOccurrenceAndScrollsIntoViewInBothThemes() {
        val root = Files.createTempDirectory("synthetic-cited-range-")
        val input = root.resolve("synthetic.pdf")
        Files.write(input, citedRangePdf(740f to "prefix G", 700f to "ABCDE oFce.", 100f to "ABCDE oFce."))
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(input)
        val page = book.pages.single()
        val quote = "中文无空格 office."
        val start = page.text.lastIndexOf(quote)
        assertTrue(start > page.text.indexOf(quote))
        val target = assertIs<BookSourceResolution.Resolved>(BookSourceResolver(library).resolve(
            OwnedBookCitation(book.id, 1, start, start + quote.length), quote)).target
        val lesson = book.lessons.first { it.blocks.any { b -> start in b.start until b.end } }
        val location = Loader.loadPDF(input.toFile()).use {
            locateCitedPdfRange(PdfPageTextExtraction.extract(it, 1), page.text, start until start + quote.length)
        }
        val rects = assertIs<Located.Found>(location.located).rects
        for (dark in listOf(false, true)) runComposeUiTest {
            val source = mutableStateOf(PracticeSource(1, quote, null))
            setContent { CoachTheme(dark = dark) { Surface(color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                TextbookReader(LibraryUi(book = book, screen = BookScreen.READER, page = 1, source = lesson),
                    library, UnconnectedBookLearningSupport, source.value, remember { FocusRequester() }, {}, {}, {}, {})
            } } }
            // The unverified path still uses the unchanged strict token matcher and reports failure.
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("source-anchor-status").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("source-anchor-status").assertTextContains("这一页没有找到对应内容", substring = true)
            onNodeWithTag("remote-highlight").assertDoesNotExist()
            capture(if (dark) "dark" else "light", "unverified")
            runOnIdle { source.value = PracticeSource(1, quote, null, target) }
            waitUntil(timeoutMillis = 20_000) {
                val highlight = onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().singleOrNull() ?: return@waitUntil false
                val viewport = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                val top = highlight.positionInRoot.y + rects.minOf { it.y0 } / location.boxes.height * highlight.size.height
                val bottom = highlight.positionInRoot.y + rects.maxOf { it.y1 } / location.boxes.height * highlight.size.height
                top >= viewport.top && bottom <= viewport.bottom
            }
            onNodeWithTag("remote-highlight").assertContentDescriptionEquals("原句：$quote")
            onNodeWithTag("source-anchor-status").assertDoesNotExist()
            capture(if (dark) "dark" else "light", "verified")
        }
    }

    private fun ComposeUiTest.capture(theme: String, state: String) {
        val output = Path.of("build/screens/cited-range-synthetic", theme, "$state.png")
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output.toFile()))
    }
}
