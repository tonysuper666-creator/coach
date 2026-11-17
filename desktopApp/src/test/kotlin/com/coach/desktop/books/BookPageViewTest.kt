package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.coach.ui.CoachTheme
import com.coach.ui.v7.Desk
import com.coach.desktop.WindowsTextbookHost
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

    @Test fun spreadKeepsBothInnerFootersAndSourceAnchorOnNarrowing() = runSkikoComposeUiTest(
        size = Size(1800f, 1000f), density = Density(1f),
    ) {
        val book = Textbook("isolated-spread", "阅读与证据", "md", listOf(BookPage(41,
            "# 第三章 阅读与证据\n\n" + "读者回到原文检查证据，再把结论接回已有经验。😀𠮷".repeat(300))))
        val state = BookPagingState(BookPageAnchor(41))
        var width by mutableStateOf(1600.dp)
        setContent { CoachTheme {
            Box(Modifier.width(width).height(900.dp)) {
                BookPageView(book, state, emptyMap(), onTurn = { state.move(it) })
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
    }
}
