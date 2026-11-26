package com.coach.desktop.books

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory

class QaRenamedScanTest {
    @Test
    fun renamedScanIsExtractedOrKeptInTheCourse() {
        val root = Files.createTempDirectory("qa-intake-scan-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = true)
            val references = listOf("Textbook", "Slides").map { role ->
                val path = root.resolve("$role.md")
                Files.writeString(path, "## Axial tilt\nA synthetic $role explanation of Axial tilt.")
                library.stageBookImport(path).book.id
            }
            val courseId = library.withCourseDatabase { db ->
                val (id, _) = CourseStore.createCourse(db, "Astronomy", CourseExamProfile(examEnabled = true), references)
                CourseAutoAligner.alignLocally(db, id)
                require(CoursePastPaper.hasExamPoints(db, id))
                id
            }
            val source = root.resolve("Week 2 quiz.pdf")
            PDDocument().use { pdf ->
                val page = PDPage()
                pdf.addPage(page)
                val image = BufferedImage(360, 90, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = Color.WHITE
                g.fillRect(0, 0, image.width, image.height)
                g.color = Color.BLACK
                g.drawString("1. Compare Axial tilt.", 20, 45)
                g.dispose()
                PDPageContentStream(pdf, page).use { it.drawImage(LosslessFactory.createFromImage(pdf, image), 10f, 500f) }
                pdf.save(source.toFile())
            }
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openCourse(courseId)
                controller.addMaterialsToCourse(courseId, listOf(source)).join()
            }
            val item = assertNotNull(controller.state.value.courseIntake).items.single()
            val bookId = assertNotNull(item.bookId)
            Files.move(source, root.resolve("renamed.pdf"))
            runBlocking { controller.changeCourseIntakeType(courseId, item.key, null) }
            val detail = library.courseDetail(courseId)
            val techniques = detail.pastPaperTechniques.size
            val courseMaterial = detail.materials.any { it.bookId == bookId }
            val libraryCopy = library.catalog().any { it.id == bookId }
            println("QA_SCAN techniques=$techniques courseMaterial=$courseMaterial libraryCopy=$libraryCopy")
            assertTrue(techniques > 0 || (courseMaterial && libraryCopy), "Scan lost before extracting any techniques")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
