package com.coach.desktop.regression

import com.coach.desktop.books.CourseAutoAligner
import com.coach.desktop.books.CourseExamProfile
import com.coach.desktop.books.CourseStore
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/**
 * TON-1849: a textbook course and its weekly plan start at chapter 1 body, not the author intro.
 */
class R1849CourseBodyStartTest {
    @Test
    fun courseTopicsStartAtChapterOneNotFrontMatter() {
        val dir = Files.createTempDirectory("coach-r1849-body-")
        try {
            val pdf = dir.resolve("botany.pdf")
            writeFrontMatterPdf(pdf)
            val library = TextbookLibrary(dir.resolve("library"), lazyPdfExtras = false)
            val book = library.importBook(pdf)
            assertTrue(
                book.tocNodes.any { it.title.contains("About the Authors") && it.startPage == 18 },
                "fixture outline must keep the author intro at page 18, got ${book.tocNodes.map { it.title to it.startPage }}",
            )
            val detail = library.withCourseDatabase { db ->
                val (cid, rejected) = CourseStore.createCourse(
                    db = db,
                    title = "Botany",
                    examProfile = CourseExamProfile(),
                    materialBookIds = listOf(book.id),
                )
                check(rejected.isEmpty())
                CourseAutoAligner.alignLocally(db, cid)
            }
            val starts = detail.examinableTopics.flatMap { topic -> topic.sources.map { it.startPage } }
            assertTrue(starts.isNotEmpty(), "course must schedule textbook chapters")
            assertEquals(32, starts.first(), "first scheduled page was $starts")
            assertFalse(1 in starts || 18 in starts, "front matter pages were scheduled: $starts")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun writeFrontMatterPdf(path: Path) {
        val headings = mapOf(
            1 to "Copyright",
            18 to "About the Authors",
            19 to "Preface",
            32 to "Chapter 1 Light and Leaves",
            39 to "Chapter 2 Plant Cells",
        )
        PDDocument().use { doc ->
            doc.documentInformation.title = "Botany Textbook"
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val outline = PDDocumentOutline().also { doc.documentCatalog.documentOutline = it }
            for (number in 1..40) {
                val page = PDPage()
                doc.addPage(page)
                headings[number]?.let { heading ->
                    outline.addLast(PDOutlineItem().apply {
                        title = heading
                        destination = PDPageFitDestination().apply { this.page = page }
                    })
                }
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 18f)
                    stream.newLineAtOffset(50f, 710f)
                    stream.showText(headings[number] ?: "Botany textbook page $number")
                    stream.setFont(font, 11f)
                    for (line in listOf(
                        "Photosynthesis uses light to transform water and carbon dioxide into stored sugars.",
                        "Chlorophyll in leaf cells absorbs light and transfers the energy to other molecules.",
                        "Plants can use the stored sugars to grow leaves, roots, and other tissues.",
                    )) {
                        stream.newLineAtOffset(0f, -25f)
                        stream.showText(line)
                    }
                    stream.endText()
                }
            }
            doc.save(path.toFile())
        }
    }
}
