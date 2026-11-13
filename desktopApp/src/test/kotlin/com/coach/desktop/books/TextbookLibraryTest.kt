package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

internal fun syntheticTextbook(directory: Path): Path {
    Files.createDirectories(directory)
    val file = directory.resolve("A friend's biology textbook.pdf")
    PDDocument().use { pdf ->
        pdf.documentInformation.title = "A new biology textbook"
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val chapters = listOf(
            "Chapter 1 Light" to "Photosynthesis is the process by which plants use light to make sugars.",
            "Chapter 2 Cells" to "Chlorophyll is a green pigment that absorbs light in plant cells.",
            "Chapter 3 Energy" to "Respiration is the process that releases energy from stored sugars.",
        )
        for ((heading, body) in chapters) {
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                out.beginText(); out.setFont(font, 18f); out.newLineAtOffset(50f, 710f); out.showText(heading)
                out.newLineAtOffset(0f, -48f); out.setFont(font, 11f); out.showText(body); out.endText()
            }
        }
        pdf.save(file.toFile())
    }
    return file
}

class TextbookLibraryTest {
    @Test fun ownPdfIsCopiedReadWithoutLearningThenResumedAsACourseWithExactSource() = runBlocking {
        val root = Files.createTempDirectory("coach-own-book-")
        val source = syntheticTextbook(root.resolve("friend"))
        val originalBytes = Files.readAllBytes(source)
        val library = TextbookLibrary(root.resolve("library"))
        val c = TextbookController(library)
        c.load(); c.importBook(source)
        assertEquals(BookPrompt.IMPORTED, c.state.value.prompt)
        val imported = c.state.value.book!!
        assertEquals(3, imported.pages.size)
        assertEquals(3, imported.lessons.size)
        assertFalse(imported.learning)
        assertEquals(QuestionMode.CHOICE, imported.questionMode)
        Files.move(source, source.resolveSibling("moved.pdf"))
        assertEquals(3, library.renderPdf(imported, 3).let { if (it.width > 0 && it.height > 0) 3 else 0 })
        c.read()
        assertEquals(BookScreen.READER, c.state.value.screen)
        assertEquals(BookPrompt.AFTER_READING, c.state.value.prompt)
        c.dismissPrompt(); c.page(3)
        assertFalse(library.book(imported.id).learning)
        assertTrue(library.book(imported.id).promptSeen)
        c.back(); c.learn(); c.mode(QuestionMode.CHOICE); c.enableLearning()
        assertEquals(BookScreen.LEARNING, c.state.value.screen)
        c.lesson(1)
        val lesson = c.state.value.book!!.lessons[1]
        assertEquals(2, lesson.firstPage)
        c.answer(PracticeAnswer("Chlorophyll", true))
        c.source()
        assertEquals(2, c.state.value.page)
        assertEquals(lesson.id, c.state.value.source!!.id)
        assertEquals(3, library.book(imported.id).readingPage, "Source jumps must not replace the reading bookmark")
        c.back()
        assertEquals(1, c.state.value.book!!.lessonIndex)
        val reopened = TextbookController(TextbookLibrary(root.resolve("library")))
        reopened.load(); reopened.learn()
        assertEquals(1, reopened.state.value.book!!.lessonIndex)
        assertEquals(QuestionMode.CHOICE, reopened.state.value.book!!.questionMode)
        assertEquals(PracticeAnswer("Chlorophyll", true), reopened.state.value.answer)
        val copied = library.directory.resolve("${imported.id}.pdf")
        assertContentEquals(originalBytes, Files.readAllBytes(copied))
        assertEquals(imported.id, library.importBook(source.resolveSibling("moved.pdf")).id)
        assertEquals(1, library.catalog().size)
        assertEquals(1, library.book(imported.id).lessonIndex, "Reimporting must keep progress")
        assertContentEquals(originalBytes, Files.readAllBytes(source.resolveSibling("moved.pdf")))
    }

    @Test fun allQuestionModesShareOnePersistedSettingAndNeverMoveTheLesson() = runBlocking {
        val root = Files.createTempDirectory("coach-question-modes-")
        val library = TextbookLibrary(root.resolve("library"))
        val c = TextbookController(library)
        c.importBook(syntheticTextbook(root.resolve("friend"))); c.enableLearning()
        val book = c.state.value.book!!
        val lesson = book.lessons[0]
        assertNull(StudyCoachCourse.practice(book, lesson, QuestionMode.NONE))
        c.mode(QuestionMode.CHOICE)
        val choice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
        assertEquals("Photosynthesis", choice.answer)
        assertEquals(1, choice.options.count { it == choice.answer })
        assertTrue(lesson.text.contains(choice.reference))
        assertEquals(choice, StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE), "A restart must not reshuffle choices")
        c.answer(PracticeAnswer(choice.answer, true))
        c.mode(QuestionMode.SHORT_ANSWER)
        assertEquals(PracticeAnswer(), c.state.value.answer)
        c.answer(PracticeAnswer("Plants turn light into stored chemical energy.", true, true))
        c.mode(QuestionMode.NONE)
        assertEquals(0, c.state.value.book!!.lessonIndex)
        c.mode(QuestionMode.CHOICE)
        assertEquals(PracticeAnswer(choice.answer, true), c.state.value.answer)
        c.mode(QuestionMode.SHORT_ANSWER)
        assertEquals(true, c.state.value.answer.understood)
        assertEquals(QuestionMode.SHORT_ANSWER, library.book(book.id).questionMode)
        c.lesson(book.lessons.size)
        assertTrue(library.book(book.id).finished)
        c.lesson(0)
        assertFalse(library.book(book.id).finished)
    }

    @Test fun scanOnlyPdfRemainsReadableAndCannotPretendToBeAGeneratedCourse() = runBlocking {
        val root = Files.createTempDirectory("coach-scanned-book-")
        val scan = root.resolve("scan.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(scan.toFile()) }
        val c = TextbookController(TextbookLibrary(root.resolve("library")))
        c.importBook(scan); c.read(); c.dismissPrompt(); c.learn(); c.enableLearning()
        assertFalse(c.state.value.book!!.learning)
        assertTrue(c.state.value.error!!.contains("没有可提取"))
        c.dismissPrompt(); c.read()
        assertEquals(BookScreen.READER, c.state.value.screen)
        assertTrue(c.library.renderPdf(c.state.value.book!!, 1).width > 0)
    }

    @Test fun anchorsKeepOriginalUnicodeOffsetsAndLongSourceIsNotTruncated() {
        val text = "# Ａ　量子\n\nﬁeld：原文包含兼容字符，定位时不能改变文字长度。\n\n" + "Long source paragraph with complete provenance. ".repeat(260)
        val pages = listOf(BookPage(1, text))
        val lessons = StudyCoachCourse.plan("synthetic", "Unicode", pages)
        assertTrue(lessons.size > 1)
        val blocks = lessons.flatMap { it.blocks }
        for (block in blocks) assertEquals(block.text, text.substring(block.start, block.end))
        assertEquals(260, Regex("complete provenance").findAll(blocks.filterNot { it.heading }.joinToString("") { it.text }).count())
        assertTrue(blocks.any { it.text.contains("ﬁeld") })
    }

    @Test fun epubUsesSpineOrderAndRejectsExternalXmlEntitiesWithoutChangingTheLibrary() {
        val root = Files.createTempDirectory("coach-epub-")
        fun epub(path: Path, unsafe: Boolean = false) {
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                entry("META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>")
                entry("OPS/package.opf", "<package xmlns='http://www.idpf.org/2007/opf'><metadata><title xmlns='http://purl.org/dc/elements/1.1/'>New chemistry</title></metadata><manifest><item id='b' href='b.xhtml'/><item id='a' href='a.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>")
                entry("OPS/b.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Second</h1><p>A molecule is a group of bonded atoms.</p></body></html>")
                entry("OPS/a.xhtml", (if (unsafe) "<!DOCTYPE html [<!ENTITY leak SYSTEM 'file:///not-a-learner-file'>]>" else "") +
                    "<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>First</h1><p>An atom is a unit of matter.</p></body></html>")
            }
        }
        val library = TextbookLibrary(root.resolve("library"))
        val safe = root.resolve("book.epub"); epub(safe)
        val book = library.importBook(safe)
        assertEquals("New chemistry", book.title)
        assertTrue(book.pages.first().text.contains("First"))
        assertTrue(book.pages.last().text.contains("Second"))
        val unsafe = root.resolve("unsafe.epub"); epub(unsafe, true)
        assertFails { library.importBook(unsafe) }
        assertEquals(listOf(book.id), library.catalog().map { it.id })
        val invalid = root.resolve("broken.pdf"); Files.writeString(invalid, "not a PDF")
        assertFails { library.importBook(invalid) }
        assertEquals(1, library.catalog().size)
        assertTrue(Files.list(library.directory).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".import-") } })
    }
}
