package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SameSessionExplanationConsistencyTest {
    @Test
    fun sameSessionImportAfterTier1MatchesReopenedBookAndExplainsEveryChapter() = runBlocking {
        val root = Files.createTempDirectory("coach-ton1866-same-session-")
        try {
            val pdfPath = buildOutlinePdfWithHeadingDivergence(root.resolve("outline-textbook.pdf"))
            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val controller = TextbookController(library)
            controller.load()
            controller.importBook(pdfPath)

            val deadline = System.nanoTime() + 30_000_000_000L
            while (controller.state.value.book?.indexState != "READY") {
                assertTrue("Timed out waiting for Tier 1 indexing to reach READY", System.nanoTime() < deadline)
                Thread.sleep(50)
            }

            val uiBook = requireNotNull(controller.state.value.book)
            val persistedBook = library.book(uiBook.id)
            assertTrue("Expected multi-chapter lessons after Tier 1", uiBook.lessons.size >= 3)
            assertEquals(
                "Same-session ui.book.lessons must match library.book(id).lessons without reopening",
                persistedBook.lessons,
                uiBook.lessons,
            )

            val provider = OwnedBookLearningProvider(library) { prompt ->
                OwnedBookAnswer(
                    text = "Verified explanation grounded in the selected section blocks.",
                    citations = listOf(prompt.excerpts.first().citation),
                )
            }

            for ((index, lesson) in uiBook.lessons.withIndex()) {
                val reply = provider.explain(BookExplanationRequest(uiBook.id, lesson.id, lesson.blocks))
                assertTrue(
                    "Same-session explanation must succeed for lesson #$index (${lesson.title}), got $reply",
                    reply is BookLearningReply.Text,
                )
            }

            // Reopen in a fresh controller and verify identical lessons and explanation availability.
            val reopenedController = TextbookController(TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true))
            reopenedController.load()
            reopenedController.openBook(uiBook.id)
            val reopenedBook = requireNotNull(reopenedController.state.value.book)
            assertEquals(reopenedBook.lessons, uiBook.lessons)
            for ((index, lesson) in reopenedBook.lessons.withIndex()) {
                val reply = provider.explain(BookExplanationRequest(reopenedBook.id, lesson.id, lesson.blocks))
                assertTrue(
                    "Reopened explanation must succeed for lesson #$index (${lesson.title}), got $reply",
                    reply is BookLearningReply.Text,
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun plannedStructureLessonsMatchToLessonsAcrossBothHeadingDirections() {
        val bookId = "a".repeat(64)
        val pages = listOf(
            BookPage(1, "1 Foundations of Scarcity\n\nScarcity shapes how households and firms allocate limited resources over time."),
            BookPage(2, "2 Elasticity and Choice\n\n1. Key Assumption\n\nBuyers respond to relative price changes when substitutes exist in the market."),
            BookPage(3, "Market Failure Overview\n\nExternalities arise when private decisions impose unpriced costs or benefits on others."),
        )
        val outline = listOf(
            BookOutlineItem("1 Foundations of Scarcity", 1, 1, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("2 Elasticity and Choice", 1, 2, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("Market Failure Overview", 1, 3, HeadingSource.PDF_OUTLINE),
        )

        val planned = BookStructurePlanner.plan(bookId, "Synthetic Economics", "pdf", pages, outline)
        val reloaded = planned.toLessons(bookId, pages)
        assertTrue(planned.resolvedLessons.size >= 3)
        assertEquals(
            "BookStructurePlanner.plan resolvedLessons must equal BookStructure.toLessons",
            reloaded,
            planned.resolvedLessons,
        )
    }

    private fun buildOutlinePdfWithHeadingDivergence(target: Path): Path {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = "Outline Heading Parity Textbook"
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline

            data class ChapterSpec(val title: String, val subheading: String?, val body: String)
            val chapters = listOf(
                ChapterSpec(
                    title = "1 Foundations of Scarcity",
                    subheading = null,
                    body = "Scarcity requires explicit trade-offs when societies choose how to produce and distribute goods.",
                ),
                ChapterSpec(
                    title = "2 Demand and Supply",
                    subheading = "1. Key Market Assumption",
                    body = "Price signals coordinate decentralized decisions by buyers and sellers across competitive markets.",
                ),
                ChapterSpec(
                    title = "Welfare and Public Choice",
                    subheading = "2. Redistribution Effects",
                    body = "Efficiency and equity criteria often diverge when governments evaluate tax and transfer policies.",
                ),
            )

            chapters.forEach { spec ->
                val page = PDPage()
                pdf.addPage(page)
                val dest = PDPageFitDestination().apply { this.page = page }
                outline.addLast(PDOutlineItem().apply { title = spec.title; destination = dest })

                PDPageContentStream(pdf, page).use { out ->
                    out.beginText()
                    out.setFont(bold, 15f)
                    out.newLineAtOffset(54f, 730f)
                    out.showText(spec.title)
                    out.endText()

                    if (spec.subheading != null) {
                        out.beginText()
                        out.setFont(bold, 12f)
                        out.newLineAtOffset(54f, 670f)
                        out.showText(spec.subheading)
                        out.endText()
                    }

                    out.beginText()
                    out.setFont(font, 11f)
                    out.newLineAtOffset(54f, 610f)
                    out.showText(spec.body)
                    out.endText()
                }
            }
            pdf.save(target.toFile())
        }
        return target
    }
}
