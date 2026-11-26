package com.coach.desktop.books

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class QaIntakeRecoveryTest {
    @Test
    fun movedSourceCanBeRecoveredOrOriginalMaterialIsKept() = checkCorrection(shared = false)

    @Test
    fun correctingOneCourseKeepsTheOtherCoursesCopy() = checkCorrection(shared = true)

    private fun checkCorrection(shared: Boolean) {
        val root = Files.createTempDirectory("qa-course-correction-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val references = listOf("Textbook", "Slides").map { role ->
                val path = root.resolve("$role.md")
                Files.writeString(path, "## Axial tilt\nA synthetic $role explanation of Axial tilt.")
                library.stageBookImport(path).book.id
            }
            val courseId = library.withCourseDatabase { db ->
                val (id, _) = CourseStore.createCourse(db, "Astronomy", CourseExamProfile(examEnabled = true), references)
                CourseAutoAligner.alignLocally(db, id)
                id
            }
            val source = root.resolve("Week 2 quiz.md")
            Files.writeString(source, "1. Compare Axial tilt.")
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openCourse(courseId)
                controller.addMaterialsToCourse(courseId, listOf(source)).join()
            }
            val item = assertNotNull(controller.state.value.courseIntake).items.single()
            val bookId = assertNotNull(item.bookId)
            assertTrue(item.newlyStaged)
            val second = if (shared) library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "Other course", CourseExamProfile(), references).first
            } else null
            if (second != null) {
                val otherWindow = TextbookController(library)
                runBlocking {
                    otherWindow.load()
                    otherWindow.openCourse(second)
                    otherWindow.addMaterialsToCourse(second, listOf(source)).join()
                }
                assertTrue(library.courseDetail(second).materials.any { it.bookId == bookId })
            }
            if (!shared) Files.move(source, root.resolve("renamed.md"))
            runBlocking { controller.changeCourseIntakeType(courseId, item.key, null) }
            val detail = library.courseDetail(courseId)
            val hasMaterial = detail.materials.any { it.bookId == bookId }
            val inLibrary = library.catalog().any { it.id == bookId }
            val other = second?.let { library.courseDetail(it).materials.any { m -> m.bookId == bookId } }
            val count = detail.pastPaperTechniques.size
            println("QA_RESULT shared=$shared techniques=$count courseMaterial=$hasMaterial libraryCopy=$inLibrary otherCourseMaterial=$other")
            if (shared) assertTrue(other == true && inLibrary, "Other course lost its material")
            else assertTrue(count > 0 || (hasMaterial && inLibrary), "Correction lost the material before extracting any techniques")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
