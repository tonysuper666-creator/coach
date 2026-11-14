package com.coach.desktop.books

import kotlinx.coroutines.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class TextbookReviewPersistenceTest {
    @Test fun existingWrongAnswersMigrateOnceAndCompletionSurvivesReopen() {
        val root = Files.createTempDirectory("coach-old-wrong-answer-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val choice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val wrong = choice.options.first { it != choice.answer }
        // The pre-upgrade writer had no review table: insert its actual legacy answer shape.
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.prepareStatement("INSERT INTO answers(book,lesson,mode,text,revealed,understood) VALUES(?,?,?,?,1,NULL)").use {
                it.setString(1, book.id); it.setString(2, lesson.id)
                it.setString(3, QuestionMode.CHOICE.name); it.setString(4, wrong); it.executeUpdate()
            }
        }
        val upgraded = TextbookLibrary(library.directory)
        val entry = upgraded.reviews().single()
        assertEquals(choice, entry.practice)
        assertEquals(wrong, entry.originalAnswer)
        // Resetting the ordinary practice must not erase the remembered wrong answer.
        upgraded.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer())
        assertEquals(entry, upgraded.reviews().single())
        upgraded.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, true))
        upgraded.saveReviewAnswer(entry, PracticeAnswer(choice.answer, true))
        assertTrue(TextbookLibrary(library.directory).reviews().isEmpty())
        // Re-importing or opening the old wrong lesson must not resurrect a completed review.
        upgraded.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(choice.answer, true))
        assertTrue(TextbookLibrary(library.directory).reviews().isEmpty())
        upgraded.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, true))
        val fresh = TextbookLibrary(library.directory).reviews().single()
        assertEquals(PracticeAnswer(), fresh.answer)
        assertEquals(wrong, fresh.originalAnswer)
    }

    @Test fun shortAnswerNeedsExplicitSelfCheckAndKeepsSeparateBookAndModeIdentity() {
        val root = Files.createTempDirectory("coach-review-identities-")
        val library = TextbookLibrary(root.resolve("library"))
        val first = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val path = root.resolve("another.md")
        Files.writeString(path, "# A different textbook\n\n## Cells\n\nMitosis is the process by which a cell divides into two new cells.\n")
        val second = library.importBook(path)
        val lesson = first.lessons.first()
        library.saveAnswer(first.id, lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("My draft", true))
        assertTrue(library.reviews().isEmpty(), "Revealing a reference is not an automatic wrong mark")
        library.saveAnswer(first.id, lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("My draft", true, false))
        val short = library.reviews().single()
        assertEquals(QuestionMode.SHORT_ANSWER, short.mode)
        val choice = StudyCoachCourse.practice(second, second.lessons.first(), QuestionMode.CHOICE)!!
        val wrong = choice.options.first { it != choice.answer }
        library.saveAnswer(second.id, second.lessons.first().id, QuestionMode.CHOICE, PracticeAnswer(wrong, true))
        assertEquals(setOf(first.id, second.id), library.reviews().map { it.book }.toSet())
        library.saveReviewAnswer(short, PracticeAnswer("Still uncertain", true, false))
        assertEquals(2, TextbookLibrary(library.directory).reviews().size)
        library.saveReviewAnswer(short, PracticeAnswer("I can explain it", true, true))
        val remaining = TextbookLibrary(library.directory).reviews().single()
        assertEquals(second.id, remaining.book)
        assertEquals(PracticeAnswer("My draft", true, false), library.answer(first.id, lesson.id, QuestionMode.SHORT_ANSWER))
    }

    @Test fun queuedReviewDraftsAndLessonDraftsDoNotOverwriteEachOther() = runBlocking {
        val root = Files.createTempDirectory("coach-review-draft-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        library.saveAnswer(book.id, lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Original", true, false))
        val c = TextbookController(library)
        c.load()
        val review = c.state.value.reviews.single()
        c.openReview(review)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val drafts = AnswerDrafts(c, scope)
            drafts.saveReview(review, PracticeAnswer("Review draft"))
            drafts.save(book.id, lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Lesson draft"))
            assertTrue(drafts.flush())
            assertEquals(PracticeAnswer("Review draft"), c.state.value.answer)
            val reopened = TextbookController(TextbookLibrary(library.directory))
            reopened.load()
            assertEquals(BookScreen.REVIEWING, reopened.state.value.screen)
            assertEquals(PracticeAnswer("Review draft"), reopened.state.value.answer)
            assertEquals("Original", reopened.state.value.review!!.originalAnswer)
            assertEquals(PracticeAnswer("Lesson draft"), library.answer(book.id, lesson.id, QuestionMode.SHORT_ANSWER))
            assertEquals(book.lessonIndex, library.book(book.id).lessonIndex)
            assertEquals(book.questionMode, library.book(book.id).questionMode)
        } finally { scope.cancel() }
    }
}
