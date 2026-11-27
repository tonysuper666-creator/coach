package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TON-1937: a just-imported PDF whose front matter runs straight into chapter 1.
 * The first lesson starts on page 1 (before any outline chapter) but ends on the body page,
 * so it used to be picked as the opening and the first question never got asked.
 */
class FirstOpenFrontMatterTest {
    @Test fun openingSkipsFrontMatterThatRunsIntoChapterOne() {
        val root = Files.createTempDirectory("coach-first-open-front-")
        try {
            val pdf = frontMatterPdf(root.resolve("ledger.pdf"))
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.stageBookImportTier0(pdf).book
            val bodyPage = book.bodyStart.importPage
            val first = book.lessons.first()
            assertTrue(
                first.firstPage < bodyPage && first.lastPage >= bodyPage,
                "fixture must reproduce front matter running into the body: first lesson ${first.firstPage}..${first.lastPage}, body $bodyPage",
            )
            val group = assertNotNull(FirstOpenPlanner.openingGroup(book), "opening must not be empty for a book with chapter text")
            val text = group.sections.flatMap { it.blocks }.joinToString(" ") { it.text }
            assertTrue("PREFACE_MARKER" !in text, "front matter was sent as the opening: $text")
            assertTrue(group.firstPage >= bodyPage, "opening starts before the body: ${group.firstPage} < $bodyPage")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun frontMatterPdf(target: Path): Path {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = "Clinic Ledger Notes"
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            val chapterPage = 6
            var chapterItem: PDOutlineItem? = null
            for (p in 1..12) {
                val page = PDPage()
                pdf.addPage(page)
                val dest = PDPageFitDestination().apply { this.page = page }
                when (p) {
                    chapterPage -> PDOutlineItem().apply { title = "1 Counting the cost of care"; destination = dest }
                        .also { outline.addLast(it); chapterItem = it }
                    chapterPage + 1 -> chapterItem?.addLast(PDOutlineItem().apply { title = "1.1 Where the money goes"; destination = dest })
                    10 -> PDOutlineItem().apply { title = "2 Patients and prices"; destination = dest }.also { outline.addLast(it) }
                }
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.newLineAtOffset(54f, 730f)
                    val lines: List<Pair<Boolean, String>> = when {
                        p < chapterPage -> List(8) { false to "PREFACE_MARKER page $p thanks the many readers who helped shape this edition line $it." }
                        p == chapterPage -> List(8) { false to "Health spending is a large share of national income in every rich country line $it." }
                        p == chapterPage + 1 -> listOf(true to "1.1 Where the money goes") +
                            List(8) { false to "Hospitals and clinics employ millions of workers and use scarce resources line $it." }
                        p == 10 -> listOf(true to "2 Patients and prices") +
                            List(8) { false to "Demand for care falls when the price a patient pays rises line $it." }
                        else -> List(8) { false to "Insurance changes what patients pay at the point of care on page $p line $it." }
                    }
                    for ((heading, line) in lines) {
                        out.setFont(if (heading) bold else font, if (heading) 16f else 11f)
                        out.showText(line)
                        out.newLineAtOffset(0f, if (heading) -28f else -16f)
                    }
                    out.endText()
                }
            }
            pdf.save(target.toFile())
        }
        return target
    }
}
