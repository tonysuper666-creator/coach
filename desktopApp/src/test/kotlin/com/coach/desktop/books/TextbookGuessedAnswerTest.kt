package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class TextbookGuessedAnswerTest {
    @Test fun guessedCorrectChoiceIsQueuedAndNeverCountsAsReviewCompletion() = runBlocking {
        val root = Files.createTempDirectory("coach-guessed-correct-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val choice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val correct = choice.answer
        val guessed = PracticeAnswer(correct, true, guessed = true)

        val c = TextbookController(library)
        c.load()
        c.answer(guessed)

        val queued = c.state.value.reviews.single()
        assertEquals(correct, queued.originalAnswer, "A lucky hit keeps the original answer text")
        assertTrue(queued.originalGuessed, "The guess fact must survive the save")
        assertFalse(queued.completed)
        assertEquals(guessed, c.state.value.answer)
        assertEquals(guessed, library.answer(book.id, lesson.id, QuestionMode.CHOICE))

        // The same save twice is one review: the facts are keyed by book, lesson and mode.
        c.answer(guessed)
        assertEquals(1, c.state.value.reviews.size)

        // A new instance restores the queue entry and the mark, not a mastered answer.
        val reopened = TextbookController(TextbookLibrary(library.directory))
        reopened.load()
        val afterRestart = reopened.state.value.reviews.single()
        assertEquals(correct, afterRestart.originalAnswer)
        assertTrue(afterRestart.originalGuessed)
        reopened.enableLearning()
        assertEquals(guessed, reopened.state.value.answer)

        // Correct in review but marked as a guess: the row stays queued and is not completed.
        reopened.openReview(afterRestart)
        val reviewGuess = PracticeAnswer(correct, true, guessed = true)
        assertTrue(reopened.saveReviewDraft(book.id, lesson.id, QuestionMode.CHOICE, reviewGuess))
        val stillQueued = TextbookLibrary(library.directory).reviews().single()
        assertFalse(stillQueued.completed)
        assertEquals(reviewGuess, stillQueued.answer)
        assertEquals(guessed, library.answer(book.id, lesson.id, QuestionMode.CHOICE),
            "The review answer must not overwrite the lesson's original answer")

        // Correct in review and not a guess completes the same row.
        assertTrue(reopened.saveReviewDraft(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(correct, true)))
        assertTrue(TextbookLibrary(library.directory).reviews().isEmpty())
    }

    @Test fun guessedFactStaysInsideItsOwnBookLessonAndMode() = runBlocking {
        val root = Files.createTempDirectory("coach-guessed-scope-")
        val library = TextbookLibrary(root.resolve("library"))
        val first = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val firstLesson = first.lessons.first()
        val firstChoice = StudyCoachCourse.practice(first, firstLesson, QuestionMode.CHOICE)!!

        val secondPath = root.resolve("second.md")
        Files.writeString(secondPath, "# Another book\n\nMitosis is the process by which a cell divides into two cells.\n")
        val second = library.importBook(secondPath)
        val secondChoice = StudyCoachCourse.practice(second, second.lessons.first(), QuestionMode.CHOICE)!!
        library.saveAnswer(second.id, second.lessons.first().id, QuestionMode.CHOICE,
            PracticeAnswer(secondChoice.answer, true, guessed = false))

        library.saveAnswer(first.id, firstLesson.id, QuestionMode.SHORT_ANSWER,
            PracticeAnswer("A real summary", true, true))
        assertTrue(library.reviews().isEmpty(), "A correct non-guess elsewhere must not create a review")

        library.saveAnswer(first.id, firstLesson.id, QuestionMode.CHOICE,
            PracticeAnswer(firstChoice.answer, true, guessed = true))
        val entry = library.reviews().single()
        assertEquals(first.id, entry.book)
        assertEquals(firstLesson.id, entry.lesson)
        assertEquals(QuestionMode.CHOICE, entry.mode)
        assertTrue(library.reviews().none { it.book == second.id }, "A correct answer in another book is not queued")
        assertEquals(PracticeAnswer(), library.answer(second.id, second.lessons.first().id, QuestionMode.SHORT_ANSWER))
    }

    @Test fun oldLibraryGainsGuessedColumnsAndTreatsExistingAnswersAsNotGuessed() {
        val root = Files.createTempDirectory("coach-legacy-guessed-")
        val libraryDir = root.resolve("library")
        Files.createDirectories(libraryDir)
        val bookId = "a".repeat(64)
        val pages = "Photosynthesis is the process by which plants use light to make sugars."
        // Exactly the pre-guess tables: no guessed / wrong_guessed / answer_guessed columns.
        DriverManager.getConnection("jdbc:sqlite:${libraryDir.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
                sql.execute("CREATE TABLE pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,PRIMARY KEY(book,page))")
                sql.execute("CREATE TABLE answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,PRIMARY KEY(book,lesson,mode))")
                sql.execute("""CREATE TABLE book_reviews(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,
                    book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,prompt TEXT NOT NULL,reference TEXT NOT NULL,
                    solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,
                    answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,
                    completed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))""")
            }
            db.prepareStatement("INSERT INTO books(id,title,format,mode,touched) VALUES(?,?,?,?,?)").use {
                it.setString(1, bookId); it.setString(2, "Legacy biology"); it.setString(3, "md")
                it.setString(4, QuestionMode.CHOICE.name); it.setLong(5, 1L); it.executeUpdate()
            }
            db.prepareStatement("INSERT INTO pages(book,page,text) VALUES(?,?,?)").use {
                it.setString(1, bookId); it.setInt(2, 1); it.setString(3, pages); it.executeUpdate()
            }
        }
        val book = Textbook(bookId, "Legacy biology", "md", listOf(BookPage(1, pages)))
        val lesson = book.lessons.first()
        val choice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val wrong = choice.options.first { it != choice.answer }
        DriverManager.getConnection("jdbc:sqlite:${libraryDir.resolve("library.sqlite")}").use { db ->
            db.prepareStatement("INSERT INTO answers(book,lesson,mode,text,revealed,understood) VALUES(?,?,?,?,1,NULL)").use {
                it.setString(1, bookId); it.setString(2, lesson.id); it.setString(3, QuestionMode.CHOICE.name)
                it.setString(4, wrong); it.executeUpdate()
            }
            db.prepareStatement("INSERT INTO answers(book,lesson,mode,text,revealed,understood) VALUES(?,?,?,?,1,NULL)").use {
                it.setString(1, bookId); it.setString(2, lesson.id); it.setString(3, QuestionMode.SHORT_ANSWER.name)
                it.setString(4, "旧简答"); it.executeUpdate()
            }
        }

        val migrated = TextbookLibrary(libraryDir)
        DriverManager.getConnection("jdbc:sqlite:${libraryDir.resolve("library.sqlite")}").use { db ->
            fun columns(table: String): Set<String> = db.createStatement().use { sql ->
                sql.executeQuery("PRAGMA table_info($table)").use { rows -> buildSet { while (rows.next()) add(rows.getString("name")) } }
            }
            assertTrue("guessed" in columns("answers"))
            assertTrue("wrong_guessed" in columns("book_reviews"))
            assertTrue("answer_guessed" in columns("book_reviews"))
        }
        // Only the actually wrong answer was queued; the old rows never claim to be guesses.
        val entry = migrated.reviews().single()
        assertEquals(wrong, entry.originalAnswer)
        assertFalse(entry.originalGuessed)
        val stored = migrated.answer(bookId, lesson.id, QuestionMode.CHOICE)
        assertEquals(PracticeAnswer(wrong, true), stored)
        assertFalse(stored.guessed)
        assertTrue(migrated.answer(bookId, lesson.id, QuestionMode.SHORT_ANSWER).guessed.not())
    }
}
