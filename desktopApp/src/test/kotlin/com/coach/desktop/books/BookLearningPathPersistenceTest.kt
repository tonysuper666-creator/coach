package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal fun learningPathTextbook(root: Path, title: String, chemistry: Boolean = false): Path {
    Files.createDirectories(root)
    val file = root.resolve("$title.pdf")
    val material = if (chemistry) listOf(
        "Atoms" to "Atom is a small unit of matter. Atoms combine to form larger structures.",
        "Molecules" to "Molecule is a group of bonded atoms. A molecule can contain different elements.",
        "Elements" to "Element is a substance made of one kind of atom. Elements have distinct properties.",
    ) else listOf(
        "Orbits" to "Orbit is the path of an object around another body. An orbit can be elliptical.",
        "Gravity" to "Gravity is the attraction between bodies with mass. Gravity acts across distance.",
        "Rotation" to "Rotation is the turning of a body around its axis. One rotation determines a day.",
    )
    PDDocument().use { pdf ->
        pdf.documentInformation.title = title
        for ((index, row) in material.withIndex()) {
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                out.newLineAtOffset(40f, 710f); out.showText("Chapter ${index + 1} ${row.first}")
                out.newLineAtOffset(0f, -35f); out.showText(row.second); out.endText()
            }
        }
        pdf.save(file.toFile())
    }
    return file
}

class BookLearningPathPersistenceTest {
    @Test fun twoBooksRestoreTheirOwnWalkAndKeepPagesLessonsAndBothAnswerModes() = runBlocking {
        val root = Files.createTempDirectory("coach-learning-path-storage-")
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        val c = TextbookController(library)
        c.importBook(learningPathTextbook(root, "Synthetic astronomy")); c.enableLearning(); c.lesson(1)
        val a = c.state.value.book!!
        val lesson = a.lessons[1]
        val choice = StudyCoachCourse.practice(a, lesson, QuestionMode.CHOICE)!!
        c.answer(PracticeAnswer(choice.answer, true))
        c.mode(QuestionMode.SHORT_ANSWER); c.answer(PracticeAnswer("A retained guided draft"))
        c.read(); c.page(2); c.back(); c.learn(LearningPath.READ_AND_LEARN)
        assertNull(c.state.value.error)
        assertEquals(LearningPath.READ_AND_LEARN, c.state.value.learningPath)
        c.importBook(learningPathTextbook(root, "Synthetic chemistry", chemistry = true))
        c.mode(QuestionMode.NONE); c.chooseLearningPath(LearningPath.READ); c.enableLearning(); c.page(3)
        val b = c.state.value.book!!

        val restarted = TextbookController(TextbookLibrary(directory))
        restarted.load(); restarted.select(b.id); restarted.learn()
        assertNull(restarted.state.value.error)
        assertEquals(BookScreen.READER, restarted.state.value.screen)
        assertEquals(3, restarted.state.value.page)
        assertEquals(QuestionMode.NONE, restarted.state.value.book!!.questionMode)
        restarted.back(); restarted.select(a.id); restarted.learn()
        assertEquals(LearningPath.READ_AND_LEARN, restarted.state.value.learningPath)
        assertEquals(1, restarted.state.value.book!!.lessonIndex)
        assertEquals(PracticeAnswer("A retained guided draft"), restarted.state.value.answer)
        assertTrue(restarted.state.value.questionsOpen)
        restarted.source(); restarted.page(3); restarted.back()
        assertEquals(2, library.book(a.id).readingPage, "Source browsing must not change the independent reading bookmark")
        assertEquals(LearningPath.READ_AND_LEARN, restarted.state.value.learningPath)
        restarted.learn(LearningPath.QUESTION_FIRST); restarted.mode(QuestionMode.CHOICE)
        assertEquals(PracticeAnswer(choice.answer, true), restarted.state.value.answer)

        val again = TextbookController(TextbookLibrary(directory))
        again.load(); again.select(a.id); again.learn()
        assertEquals(LearningPath.QUESTION_FIRST, again.state.value.learningPath)
        assertEquals(1, again.state.value.book!!.lessonIndex)
        assertEquals(PracticeAnswer(choice.answer, true), again.state.value.answer)
        again.back(); again.select(b.id); again.learn()
        assertEquals(LearningPath.READ, again.state.value.learningPath)
        assertEquals(BookScreen.READER, again.state.value.screen)
        assertEquals(3, again.state.value.page)
        assertEquals(PracticeAnswer(), library.answer(b.id, b.lessons[0].id, QuestionMode.CHOICE))
        assertEquals(PracticeAnswer("A retained guided draft"), library.answer(a.id, lesson.id, QuestionMode.SHORT_ANSWER))
    }

    @Test fun changingQuestionTypeDoesNotChangeThePersistedWalk() = runBlocking {
        val root = Files.createTempDirectory("coach-path-vs-question-type-")
        val library = TextbookLibrary(root.resolve("library"))
        val c = TextbookController(library)
        c.importBook(learningPathTextbook(root, "Synthetic three walks"))
        c.chooseLearningPath(LearningPath.READ_AND_LEARN); c.enableLearning()
        val id = c.state.value.book!!.id
        for (mode in QuestionMode.entries) {
            c.mode(mode)
            assertEquals(LearningPath.READ_AND_LEARN, c.state.value.learningPath)
            assertEquals("READ_AND_LEARN", library.learningPath(id))
            assertEquals(0, c.state.value.book!!.lessonIndex)
        }
        c.read()
        assertEquals("READ", library.learningPath(id))
        assertEquals(QuestionMode.SHORT_ANSWER, library.book(id).questionMode)
        assertEquals(BookScreen.READER, c.state.value.screen)
    }

    @Test fun passageIdentityUsesTheCurrentBookAndExactUnicodeOffsets() {
        val text = "# 标题\n\nﬁeld 与量子是本段自造文字，不能修改偏移。"
        val a = Textbook("a".repeat(64), "A", "md", listOf(BookPage(1, text)))
        val b = a.copy(id = "b".repeat(64), title = "B")
        val block = a.lessons[0].blocks.first { !it.heading }
        assertEquals(block.text, BookPassage.from(a, block).text)
        assertEquals(block.start, BookPassage.from(a, block).start)
        assertFailsWith<IllegalArgumentException> { BookPassage.from(b, block) }
        assertFailsWith<IllegalArgumentException> { BookPassage.from(a, block.copy(start = block.start + 1)) }
    }
}
