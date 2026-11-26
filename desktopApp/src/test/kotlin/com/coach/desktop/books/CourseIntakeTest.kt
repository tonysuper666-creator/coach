package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseIntakeTest {
    private val names = listOf("Axial tilt", "Solar wind", "Lunar phase", "Transit depth", "Stellar parallax")

    private fun course(root: Path, library: TextbookLibrary): String {
        val books = listOf("Textbook", "Slides").map { role ->
            val file = root.resolve("$role.md")
            Files.writeString(file, names.joinToString("\n\n") { "## $it\nA synthetic $role example of $it." })
            library.stageBookImport(file).book
        }
        return library.withCourseDatabase { db ->
            val (id, _) = CourseStore.createCourse(db, "天文学", CourseExamProfile(examEnabled = true), books.map { it.id })
            CourseAutoAligner.alignLocally(db, id)
            id
        }
    }

    @Test
    fun droppedPastPaperKeepsOnlyTechniquesAndWrongTypesCanBeFixedInPlace() {
        val root = Files.createTempDirectory("course-intake-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val courseId = course(root, library)
            val handout = root.resolve("Handout.md").also {
                Files.writeString(it, "## Axial tilt\nAnother synthetic handout example of Axial tilt.")
            }
            val marker = "Reserved synthetic stem wording"
            val paper = root.resolve("2023 Final Exam.md").also {
                Files.writeString(it, names.take(3).mapIndexed { i, n -> "${i + 1}. Compare $n. $marker." }.joinToString("\n"))
            }
            val controller = TextbookController(library)
            runBlocking { controller.load(); controller.openCourse(courseId) }
            val booksBefore = library.catalog().size

            runBlocking { controller.addMaterialsToCourse(courseId, listOf(handout, paper)).join() }
            val receipt = assertNotNull(controller.state.value.courseIntake)
            val paperItem = receipt.items.single { it.fileName == paper.fileName.toString() }
            assertNull(paperItem.role)
            assertEquals(3, paperItem.techniqueCount)
            assertNotNull(paperItem.paperKey)
            assertEquals(booksBefore + 1, library.catalog().size, "the past paper itself is not copied into the library")
            var detail = library.courseDetail(courseId)
            assertEquals(3, detail.pastPaperTechniques.size)
            assertFalse(detail.toString().contains(marker))
            assertTrue(controller.state.value.courseNotice.orEmpty().contains("1 份当往年题，提取 3 条考法，题干不保存"))

            // Material -> another material: locked so later re-alignment keeps it.
            val handoutItem = receipt.items.single { it.fileName == "Handout.md" }
            runBlocking { controller.changeCourseIntakeType(courseId, handoutItem.key, CourseMaterialRole.LECTURE_SLIDES) }
            detail = library.courseDetail(courseId)
            val handoutEntry = detail.materials.single { it.bookId == handoutItem.bookId }
            assertEquals(CourseMaterialRole.LECTURE_SLIDES, handoutEntry.role)
            assertTrue(handoutEntry.userLocked)

            // Past paper -> material: its techniques go, the file joins the course with the chosen type.
            runBlocking { controller.changeCourseIntakeType(courseId, paperItem.key, CourseMaterialRole.USER_NOTES) }
            detail = library.courseDetail(courseId)
            assertTrue(detail.pastPaperTechniques.isEmpty())
            val asMaterial = controller.state.value.courseIntake!!.items.single { it.key == paperItem.key }
            assertEquals(CourseMaterialRole.USER_NOTES, asMaterial.role)
            val entry = detail.materials.single { it.bookId == asMaterial.bookId }
            assertEquals(CourseMaterialRole.USER_NOTES, entry.role)
            assertTrue(entry.userLocked)
            assertEquals(booksBefore + 2, library.catalog().size)
            library.withCourseDatabase { db -> CourseAutoAligner.alignLocally(db, courseId) }
            assertTrue(library.courseDetail(courseId).materials.any { it.bookId == asMaterial.bookId }, "re-align keeps the corrected file")

            // ... and back to past paper: the copy this drop made is removed again, techniques return.
            runBlocking { controller.changeCourseIntakeType(courseId, paperItem.key, null) }
            detail = library.courseDetail(courseId)
            assertEquals(3, detail.pastPaperTechniques.size)
            assertFalse(detail.materials.any { it.bookId == asMaterial.bookId })
            assertEquals(booksBefore + 1, library.catalog().size)
            assertEquals("已把 2023 Final Exam.md 改为往年题，提取 3 条考法，题干不保存。", controller.state.value.courseNotice)

            // Importing the same paper again does not double-count.
            library.withCourseDatabase { db -> CoursePastPaper.import(db, courseId, paper) }
            assertEquals(3, library.courseDetail(courseId).pastPaperTechniques.size)

            controller.dismissCourseIntake()
            assertNull(controller.state.value.courseIntake)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun pastPaperNeedsExamPointsAndASupportedFormat() {
        val root = Files.createTempDirectory("course-intake-notes-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            val courseId = course(root, library)
            val docx = root.resolve("2022 Final Exam.docx").also { Files.writeString(it, "x") }
            val missing = root.resolve("2021 Final Exam.md")
            library.withCourseDatabase { db ->
                assertEquals("往年题只认 PDF、TXT、Markdown，这份没读", CourseIntake.importPaper(db, courseId, docx).note)
                assertEquals("原文件不在了，请重新拖进来", CourseIntake.importPaper(db, courseId, missing).note)
                val (empty, _) = CourseStore.createCourse(db, "空课", CourseExamProfile(examEnabled = true), emptyList())
                Files.writeString(missing, "1. Compare Axial tilt.")
                assertEquals("还没有考点：先放教材和课件，再放往年题", CourseIntake.importPaper(db, empty, missing).note)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedPastPaperCorrectionKeepsTheMaterialWhereItWas() {
        val root = Files.createTempDirectory("course-intake-fail-")
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false)
            // No textbook or slides yet, so there are no exam points to map a past paper onto.
            val courseId = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "空课程", CourseExamProfile(examEnabled = true), emptyList()).first
            }
            val quiz = root.resolve("Week 2 quiz.md").also { Files.writeString(it, "## Axial tilt\n1. Compare Axial tilt.") }
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openCourse(courseId)
                controller.addMaterialsToCourse(courseId, listOf(quiz)).join()
            }
            val item = assertNotNull(controller.state.value.courseIntake).items.single()
            val bookId = assertNotNull(item.bookId)
            val before = library.courseDetail(courseId).materials.single { it.bookId == bookId }

            runBlocking { controller.changeCourseIntakeType(courseId, item.key, null) }

            val after = library.courseDetail(courseId).materials.single { it.bookId == bookId }
            assertEquals(before.role, after.role)
            assertEquals(before.weekNum, after.weekNum)
            assertTrue(library.catalog().any { it.id == bookId }, "library copy kept")
            assertEquals(item, controller.state.value.courseIntake?.items?.single(), "receipt still shows it as a material")
            assertTrue(controller.state.value.courseNotice.orEmpty().contains("材料先留在课程里"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
