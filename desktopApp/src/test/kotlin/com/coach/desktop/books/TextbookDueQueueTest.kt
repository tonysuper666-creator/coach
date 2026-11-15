package com.coach.desktop.books

import com.coach.domain.initialReview
import com.coach.domain.nextReview
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class TextbookDueQueueTest {
    private class Fixture {
        val root = Files.createTempDirectory("coach-local-due-")
        var day = 21000L
        val library = TextbookLibrary(root.resolve("library")) { day }
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        val wrong = practice.options.first { it != practice.answer }
        fun reopen() = TextbookLibrary(library.directory) { day }
        fun original(text: String = wrong, guessed: Boolean = false) =
            library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(text, true, guessed = guessed))
        fun row(): List<Any?> = db { c -> c.createStatement().use { s -> s.executeQuery(
            "SELECT completed,due_day,interval,ease,anchor FROM book_reviews").use { r ->
            check(r.next())
            listOf(r.getInt(1), r.getLong(2).let { if (r.wasNull()) null else it },
                r.getInt(3).let { if (r.wasNull()) null else it },
                r.getDouble(4).let { if (r.wasNull()) null else it },
                r.getLong(5).let { if (r.wasNull()) null else it })
        } } }
        fun <T> db(block: (java.sql.Connection) -> T): T =
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use(block)
        fun count() = db { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM book_reviews").use { r -> r.next(); r.getInt(1) } } }
    }

    @Test fun firstCorrectAnswerSchedulesThenDueReopensTheFrozenOriginal() {
        val f = Fixture()
        f.original(f.practice.answer)
        assertTrue(f.library.reviews().isEmpty())
        assertEquals(listOf(1, f.day + 1, 1, 2.3, null), f.row())
        f.day++
        val due = f.library.reviews().single()
        assertEquals(f.practice, due.practice)
        assertEquals(PracticeAnswer(), due.answer)
        assertFalse(due.completed)
        assertEquals(f.day, f.library.reviewDueDay(due))
        assertEquals(due, f.library.review(due.book, due.lesson, due.mode))
        f.library.saveReviewAnswer(due, PracticeAnswer(f.practice.answer, true))
        val next = nextReview(initialReview(f.day - 1), f.day, "PASS")
        assertEquals(listOf(1, next.dueDay, next.intervalDays, next.ease, f.day), f.row())
        assertTrue(f.library.reviews().isEmpty())
        f.day = next.dueDay
        assertEquals(PracticeAnswer(), f.reopen().reviews().single().answer)
    }

    @Test fun wrongGuessedAndDueFactsShareOneOriginalAndTodayCount() {
        val f = Fixture()
        f.original()
        f.day++
        f.original(f.practice.answer, guessed = true)
        f.original()
        val entry = f.library.reviews().single()
        assertEquals(f.practice, entry.practice)
        assertEquals(1, f.count())
        assertEquals(f.day, f.library.reviewDueDay(entry))
        // Today's controller consumes this exact list, not a second count query.
        assertEquals(f.library.reviews().size, f.reopen().reviews().size)
    }

    @Test fun normalCloseAndNewInstanceRestorePositionDraftAndQueueWithoutInserting() {
        val f = Fixture()
        f.original(f.practice.answer)
        f.day++
        val due = f.library.reviews().single()
        f.library.openReview(due)
        f.library.saveReviewAnswer(due, PracticeAnswer("my unfinished retry"))
        val reopened = f.reopen()
        assertEquals("my unfinished retry", reopened.activeReview()!!.answer.text)
        reopened.closeReview()
        val closed = f.reopen()
        assertNull(closed.activeReview())
        assertEquals(reopened.reviews(), closed.reviews())
        repeat(3) { assertEquals(1, f.reopen().reviews().size) }
        assertEquals(1, f.count())
    }

    @Test fun guessedCorrectStaysUnfinishedAndRepeatedWritesNeverAdvanceSchedule() {
        val f = Fixture()
        f.original()
        val entry = f.library.reviews().single()
        val guess = PracticeAnswer(f.practice.answer, true, guessed = true)
        f.library.saveReviewAnswer(entry, guess)
        val expected = nextReview(initialReview(f.day), f.day, "NEEDS_WORK")
        assertEquals(listOf(0, expected.dueDay, expected.intervalDays, expected.ease, f.day), f.row())
        repeat(3) { f.library.saveReviewAnswer(entry, guess) }
        f.day++
        val before = f.row()
        f.reopen().saveReviewAnswer(entry, guess) // persisted result is not a new answer tomorrow
        assertEquals(before, f.row())
        assertFalse(f.reopen().reviews().single().completed)
        assertEquals(guess, f.reopen().reviews().single().answer)
        f.library.saveReviewAnswer(entry, PracticeAnswer())
        f.library.saveReviewAnswer(entry, PracticeAnswer(f.wrong, true))
        val firstDaily = f.row()
        f.library.saveReviewAnswer(entry, PracticeAnswer(f.practice.answer, true))
        assertEquals(firstDaily.drop(1), f.row().drop(1), "only the first assessment of the day schedules")
    }

    @Test fun additiveMigrationPreservesHistoricalRowsWithoutInventingDeadlines() {
        val f = Fixture()
        f.original()
        f.db { c -> c.createStatement().use { s ->
            for (name in listOf("due_day", "interval", "ease", "anchor")) s.execute("ALTER TABLE book_reviews DROP COLUMN $name")
        } }
        val output = Path.of("build/local-due-evidence")
        Files.createDirectories(output)
        Files.copy(f.library.directory.resolve("library.sqlite"), output.resolve("migration-before.sqlite"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val before = f.db { c -> c.createStatement().use { s -> s.executeQuery("SELECT * FROM book_reviews").use { r ->
            check(r.next()); (1..r.metaData.columnCount).associate { r.metaData.getColumnName(it) to r.getObject(it) }
        } } }
        val migrated = f.reopen()
        val entry = migrated.reviews().single()
        assertNull(migrated.reviewDueDay(entry))
        assertEquals(listOf(0, null, null, null, null), f.row())
        val after = f.db { c -> c.createStatement().use { s -> s.executeQuery("SELECT * FROM book_reviews").use { r ->
            check(r.next()); (1..r.metaData.columnCount).associate { r.metaData.getColumnName(it) to r.getObject(it) }
        } } }
        assertEquals(before, after.filterKeys { it in before })
        assertEquals(setOf("due_day", "interval", "ease", "anchor"), after.keys - before.keys)
        Files.copy(f.library.directory.resolve("library.sqlite"), output.resolve("migration-after.sqlite"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        f.db { c -> c.createStatement().use { it.executeUpdate("UPDATE book_reviews SET completed=1") } }
        f.day += 100
        assertTrue(f.reopen().reviews().isEmpty(), "null deadline never becomes due, even much later")
        assertEquals(1, f.count())
        println("MIGRATION before=$before after=$after; historical completed row remains not due")
    }

    @Test fun shortAnswerSchedulesOnlyAfterExplicitAssessmentAndKeepsModeIdentity() {
        val f = Fixture()
        f.library.saveAnswer(f.book.id, f.lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("draft", true))
        assertEquals(0, f.count())
        f.library.saveAnswer(f.book.id, f.lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("explained", true, true))
        f.original()
        assertEquals(2, f.count())
        assertEquals(listOf(QuestionMode.CHOICE), f.library.reviews().map { it.mode })
        f.day++
        assertEquals(setOf(QuestionMode.CHOICE, QuestionMode.SHORT_ANSWER), f.reopen().reviews().map { it.mode }.toSet())
    }
}
