package com.coach.desktop.regression

import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.BookBoundaries
import com.coach.desktop.books.BookChapterResolver
import com.coach.desktop.books.BookLaidPage
import com.coach.desktop.books.BookPageSlice
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/**
 * TON-1846: the header for a front-matter page names that TOC node, not only the book title.
 */
class R1846HeaderMatchesTocTest {
    @Test
    fun pageEighteenHeaderMatchesTheAuthorIntroNode() {
        val dir = Files.createTempDirectory("coach-r1846-header-")
        try {
            val pdf = dir.resolve("botany.pdf")
            writeFrontMatterPdf(pdf)
            val book = TextbookLibrary(dir.resolve("library"), lazyPdfExtras = false).importBook(pdf)
            val node = book.tocNodes.first { it.startPage == 18 }
            assertTrue(node.title.contains("About the Authors"), node.title)
            val laid = BookLaidPage(
                sources = listOf(BookPageSlice(18, 0, 40)),
                chapter = 0,
                title = book.title,
                chapterNumber = null,
                opener = false,
                text = AnnotatedString("About the authors of this synthetic botany volume."),
                placeholders = emptyList(),
                topPx = 0,
                heightPx = 400,
            )
            assertEquals(node.title, headerTitle(book, laid))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The running header is the active TOC title, including front matter.
     * Before that helper exists, only a body chapter is available, and this page has none.
     */
    private fun headerTitle(book: com.coach.desktop.books.Textbook, laid: BookLaidPage): String? {
        val ranged = BookBoundaries::class.java.methods.firstOrNull {
            it.name == "runningHeaderTitle" && it.parameterCount == 3
        }
        return if (ranged != null) {
            ranged.invoke(BookBoundaries, book, laid, null) as String
        } else {
            BookChapterResolver.chapterForPage(book, laid.anchor.importPage)?.title
        }
    }

    private fun writeFrontMatterPdf(path: Path) {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            repeat(40) { index ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                val line = when (index + 1) {
                    18 -> "About the authors of this synthetic botany volume."
                    32 -> "Chapter 1 Light and Leaves begins the body."
                    else -> "Filler page ${index + 1}."
                }
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 14f)
                    stream.newLineAtOffset(72f, 720f)
                    stream.showText(line)
                    stream.endText()
                }
            }
            val outline = PDDocumentOutline()
            fun item(title: String, pageIndex: Int): PDOutlineItem {
                val node = PDOutlineItem()
                node.title = title
                val dest = PDPageFitDestination()
                dest.page = doc.getPage(pageIndex)
                node.destination = dest
                return node
            }
            outline.addLast(item("About the Authors", 17))
            outline.addLast(item("Chapter 1 Light and Leaves", 31))
            doc.documentCatalog.documentOutline = outline
            doc.save(path.toFile())
        }
    }
}
