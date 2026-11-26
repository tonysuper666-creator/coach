package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.awt.geom.Rectangle2D
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseExamPointsTest {
    private fun writePdf(path: Path, lines: List<String>) {
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (line in lines) {
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(font, 12f)
                    content.newLineAtOffset(40f, 650f)
                    content.showText(line)
                    content.endText()
                }
            }
            pdf.save(path.toFile())
        }
    }

    private fun writeSlides(path: Path, lines: List<String>) {
        XMLSlideShow().use { show ->
            lines.forEach { line ->
                show.createSlide().createTextBox().apply {
                    anchor = Rectangle2D.Double(40.0, 40.0, 520.0, 150.0)
                    text = line
                }
            }
            Files.newOutputStream(path).use(show::write)
        }
    }

    @Test
    fun twoCoursesKeepDistinctPageLinksAndOnlyDerivedExamTechniques() {
        val directory = Files.createTempDirectory("course-exam-points")
        try {
            val library = TextbookLibrary(directory, lazyPdfExtras = false)
            val courses = listOf(
                listOf("Tidal locking", "Lunar phase", "Axial tilt", "Solar wind", "Stellar parallax",
                    "Event horizon", "Orbital period", "Escape velocity", "Light curve", "Transit depth"),
                listOf("Opportunity cost", "Price ceiling", "Consumer surplus", "Market clearing", "Tax incidence",
                    "Public goods", "Demand elasticity", "Marginal utility", "Adverse selection", "Capital stock"),
            )
            for ((index, terms) in courses.withIndex()) {
                val ids = listOf("Textbook", "Slides", "Handout").mapIndexed { roleIndex, role ->
                    val extension = if (role == "Slides") "pptx" else "pdf"
                    val file = directory.resolve("Course-$index-$role.$extension")
                    val orderedTerms = terms.drop(roleIndex) + terms.take(roleIndex)
                    val lines = orderedTerms.map { "$it: A synthetic $role example for practice." }
                    if (role == "Slides") writeSlides(file, lines) else writePdf(file, lines)
                    library.stageBookImport(file).book.id
                }
                val marker = "Reserved synthetic question wording $index"
                val paper = directory.resolve("Past-Paper-$index.pdf")
                writePdf(paper, terms.mapIndexed { i, name -> "${i + 1}. Compare $name. $marker." })
                library.withCourseDatabase { db ->
                    val (courseId, _) = CourseStore.createCourse(db, "Synthetic course $index", CourseExamProfile(examEnabled = true), ids)
                    val detail = CourseAutoAligner.alignLocally(db, courseId)
                    val points = detail.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }
                    assertEquals(terms.toSet(), points.map { it.title }.toSet())
                    val roles = listOf(CourseMaterialRole.CORE_TEXTBOOK, CourseMaterialRole.LECTURE_SLIDES, CourseMaterialRole.HANDOUT)
                    for (point in points) {
                        assertEquals(roles, point.sources.map { it.role })
                        for (source in point.sources) {
                            assertEquals(source.startPage, source.endPage)
                            assertTrue(CourseStore.loadPagesForBook(db, source.bookId).single { it.number == source.startPage }.text.contains(point.title))
                        }
                    }
                    repeat(2) { assertEquals(10, CoursePastPaper.import(db, courseId, paper)) }
                    val refreshed = CourseAutoAligner.alignLocally(db, courseId)
                    assertEquals(points.map { it.topicId }.toSet(), refreshed.pastPaperTechniques.keys)
                    assertTrue(refreshed.pastPaperTechniques.values.all { it == listOf(ExamTechnique.COMPARISON) })
                    for (point in refreshed.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }) {
                        val pages = ids.associateWith { CourseStore.loadPagesForBook(db, it) }
                        assertFalse(CourseAutoAligner.buildTopicPackRequest(refreshed, point, pages).toString().contains(marker))
                        assertFalse(CourseAutoAligner.buildOfflineTopicPack(refreshed, point, pages).toString().contains(marker))
                    }
                    assertEquals(3, refreshed.materials.size)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
