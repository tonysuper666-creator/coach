package com.coach.desktop.books

import com.coach.domain.fsrs.Fsrs5
import com.coach.domain.fsrs.FsrsConfig
import com.coach.domain.fsrs.FsrsRating
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal class ReviewTestClock(var day: Long = LocalDate.of(2026, 11, 16).toEpochDay()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    override fun instant(): Instant = LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant()
}

class UnifiedBookReviewTest {
    private fun library(clock: Clock): Pair<TextbookLibrary, Textbook> {
        val root = Files.createTempDirectory("unified-book-review-")
        val store = TextbookLibrary(root.resolve("library"), clock)
        return store to store.importBook(syntheticTextbook(root.resolve("synthetic")))
    }

    @Test fun guessedCorrectMergesWithWrongAndDueWithoutCountingAsIndependentCorrect() {
        val clock = ReviewTestClock()
        val (store, book) = library(clock)
        val lesson = book.lessons.first()
        val practice = com.coach.desktop.books.testPracticeFixture(book, lesson, QuestionMode.CHOICE)!!
        val wrong = PracticeAnswer(practice.options.first { it != practice.answer }, true)
        store.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, wrong)
        store.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, wrong)
        val guessed = PracticeAnswer(practice.answer, true, guessed = true)
        store.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, guessed)
        store.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, guessed)
        assertEquals(1, store.reviews().size)
        val original = store.reviews().single()
        assertEquals(setOf(LocalReviewSource.WRONG, LocalReviewSource.GUESSED), original.sources)
        assertEquals(wrong.text, original.originalAnswer)
        assertEquals(2, original.history.size)
        assertFalse(original.completed)
        assertTrue(practice.needsReview(QuestionMode.CHOICE, guessed))
        clock.day++
        val due = TextbookLibrary(store.directory, clock).reviews().single()
        assertEquals(setOf(LocalReviewSource.WRONG, LocalReviewSource.GUESSED, LocalReviewSource.DUE), due.sources)
        assertEquals(original.practice, due.practice)
        assertEquals(original.history, due.history)
    }

    @Test fun independentAnswersBecomeDueAndRepeatedDeliveryDoesNotPostponeThem() {
        val clock = ReviewTestClock()
        val (store, book) = library(clock)
        val lesson = book.lessons.first()
        val practice = com.coach.desktop.books.testPracticeFixture(book, lesson, QuestionMode.CHOICE)!!
        val correct = PracticeAnswer(practice.answer, true)
        val first = clock.day
        store.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, correct)
        assertTrue(store.reviews().isEmpty())
        // Due days come from the shared FSRS-5 kernel; a new card answered independently is GOOD.
        val s1 = Fsrs5.review(Fsrs5.newCard(first), FsrsRating.GOOD, first, FsrsConfig())
        assertTrue(s1.dueDay > first)
        assertEquals(s1.dueDay, store.review(book.id, lesson.id, QuestionMode.CHOICE).dueDay)
        clock.day = s1.dueDay
        store.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, correct)
        val due = store.reviews().single()
        assertEquals(setOf(LocalReviewSource.DUE), due.sources)
        assertEquals(PracticeAnswer(), due.answer)
        assertEquals(1, due.history.size)
        store.openReview(due)
        store.saveReviewAnswer(due, correct)
        store.saveReviewAnswer(due, correct)
        val completed = store.review(book.id, lesson.id, QuestionMode.CHOICE)
        assertTrue(completed.completed)
        val s2 = Fsrs5.review(s1, FsrsRating.GOOD, clock.day, FsrsConfig())
        assertEquals(s2.dueDay, completed.dueDay)
        assertTrue(s2.dueDay - clock.day > s1.dueDay - first, "a remembered card waits longer each time")
        assertEquals(2, completed.history.size)
        clock.day = s2.dueDay
        val next = TextbookLibrary(store.directory, clock).reviews().single()
        store.openReview(next)
        store.saveReviewAnswer(next, correct)
        val s3 = Fsrs5.review(s2, FsrsRating.GOOD, clock.day, FsrsConfig())
        assertEquals(s3.dueDay, store.review(book.id, lesson.id, QuestionMode.CHOICE).dueDay)
        assertEquals(s3, store.fsrsState(book.id, lesson.id, QuestionMode.CHOICE))
        assertEquals(practice.answer, store.review(book.id, lesson.id, QuestionMode.CHOICE).originalAnswer)
    }

    @Test fun hostRefreshUsesTheSameDueProjectionWithoutReplacingTheLearningDraft() = runBlocking {
        val clock = ReviewTestClock()
        val (store, book) = library(clock)
        val c = TextbookController(store)
        c.load(); c.learn(); c.enableLearning()
        val lesson = book.lessons.first()
        val practice = com.coach.desktop.books.testPracticeFixture(book, lesson, QuestionMode.CHOICE)!!
        c.answer(PracticeAnswer(practice.answer, true))
        c.answer(PracticeAnswer("Unfinished next answer"))
        assertTrue(c.state.value.reviews.isEmpty())
        clock.day = store.review(book.id, lesson.id, QuestionMode.CHOICE).dueDay
        c.refreshReviews()
        assertEquals(1, c.state.value.reviews.size)
        assertEquals(store.reviews(), c.state.value.reviews)
        assertEquals("Unfinished next answer", c.state.value.answer.text)
        assertEquals(BookScreen.LEARNING, c.state.value.screen)
    }

    @Test fun retryHistoryIsAppendOnlyAndDraftsAndOriginalAnswerStaySeparate() {
        val clock = ReviewTestClock()
        val (store, book) = library(clock)
        val lesson = book.lessons.first()
        val mode = QuestionMode.SHORT_ANSWER
        store.saveAnswer(book.id, lesson.id, mode, PracticeAnswer("Original self-check", true, false))
        val row = store.reviews().single()
        store.saveReviewAnswer(row, PracticeAnswer("First retry", true, false))
        store.saveReviewAnswer(row, PracticeAnswer())
        store.saveReviewAnswer(row, PracticeAnswer("Second retry", true, true, guessed = true))
        assertFalse(store.review(book.id, lesson.id, mode).completed)
        store.saveReviewAnswer(row, PracticeAnswer())
        store.saveReviewAnswer(row, PracticeAnswer("Independent explanation", true, true))
        val restored = TextbookLibrary(store.directory, clock).review(book.id, lesson.id, mode)
        assertEquals(listOf("Original self-check", "First retry", "Second retry", "Independent explanation"),
            restored.history.map { it.answer.text })
        assertEquals("Original self-check", restored.originalAnswer)
        assertEquals(PracticeAnswer("Original self-check", true, false), store.answer(book.id, lesson.id, mode))
        assertTrue(restored.completed)
        assertTrue(store.reviews().isEmpty())
    }

    @Test fun actualLegacySchemaMigratesWithoutLosingCompletedRetryOrInventingDates() {
        val clock = ReviewTestClock()
        val (store, book) = library(clock)
        val lesson = book.lessons.first()
        val practice = com.coach.desktop.books.testPracticeFixture(book, lesson, QuestionMode.CHOICE)!!
        val wrong = practice.options.first { it != practice.answer }
        store.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, true))
        store.saveReviewAnswer(store.reviews().single(), PracticeAnswer(practice.answer, true))
        // Recreate main's pre-change tables from their real columns, retaining every stored value.
        DriverManager.getConnection("jdbc:sqlite:${store.directory.resolve("library.sqlite")}").use { db -> db.createStatement().use { s ->
            s.execute("CREATE TABLE legacy_answers AS SELECT book,lesson,mode,text,revealed,understood FROM answers")
            s.execute("DROP TABLE answers")
            s.execute("ALTER TABLE legacy_answers RENAME TO answers")
            s.execute("CREATE TABLE legacy_reviews AS SELECT book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,answer_text,revealed,understood,completed FROM book_reviews")
            s.execute("DROP TABLE book_reviews")
            s.execute("ALTER TABLE legacy_reviews RENAME TO book_reviews")
            s.execute("CREATE UNIQUE INDEX legacy_review_key ON book_reviews(book,lesson,mode)")
            s.execute("DROP TABLE book_review_history")
        } }
        val migrated = TextbookLibrary(store.directory, clock)
        assertTrue(migrated.reviews().isEmpty())
        val old = migrated.review(book.id, lesson.id, QuestionMode.CHOICE)
        assertEquals(wrong, old.originalAnswer)
        assertEquals(practice.answer, old.answer.text)
        assertEquals(listOf(wrong, practice.answer), old.history.map { it.answer.text })
        assertTrue(old.history.all { it.day == null })
        assertEquals(old, TextbookLibrary(store.directory, clock).review(book.id, lesson.id, QuestionMode.CHOICE))
        clock.day++
        assertEquals(1, TextbookLibrary(store.directory, clock).reviews().size)
    }

    @Test fun aNewJvmRestoresExactReviewAndKeepsOriginalAndAllAttempts() = runBlocking {
        val clock = ReviewTestClock()
        val (store, imported) = library(clock)
        val c = TextbookController(store)
        c.load(); c.learn(); c.enableLearning()
        val book = c.state.value.book!!
        val questions = book.lessons.take(3).map { com.coach.desktop.books.testPracticeFixture(book, it, QuestionMode.CHOICE)!! }
        c.answer(PracticeAnswer(questions[0].options.first { it != questions[0].answer }, true))
        c.lesson(1)
        c.answer(PracticeAnswer(questions[1].answer, true, guessed = true))
        c.lesson(2)
        c.answer(PracticeAnswer(questions[2].answer, true))
        val guessed = c.state.value.reviews.single { it.lesson == book.lessons[1].id }
        c.openReview(guessed)
        c.saveReviewDraft(guessed.book, guessed.lesson, guessed.mode, PracticeAnswer("Unfinished retry"))
        val reopenDay = store.review(book.id, book.lessons[2].id, QuestionMode.CHOICE).dueDay
        val cp = (generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList() +
            System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val log = store.directory.resolve("new-process.log")
        val child = ProcessBuilder(java.toString(), "-Djava.awt.headless=true", "-cp", cp,
            UnifiedReviewChild::class.java.name, store.directory.toString(), reopenDay.toString(), book.id, guessed.lesson)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), "New JVM must finish its local restore")
            assertEquals(0, child.exitValue(), Files.readString(log))
        } finally {
            if (child.isAlive) { child.destroyForcibly(); child.waitFor() }
        }
        assertNotEquals(ProcessHandle.current().pid(), child.pid())
        val reopened = TextbookController(TextbookLibrary(store.directory, ReviewTestClock(reopenDay)))
        reopened.load()
        assertEquals(BookScreen.REVIEWING, reopened.state.value.screen)
        assertEquals(PracticeAnswer(questions[1].answer, true), reopened.state.value.answer)
        assertEquals(3, reopened.state.value.review!!.history.size)
        assertEquals(questions[1].answer, reopened.state.value.review!!.originalAnswer)
        assertEquals(PracticeAnswer(questions[1].answer, true, guessed = true),
            reopened.library.answer(imported.id, guessed.lesson, QuestionMode.CHOICE))
        assertEquals(2, reopened.state.value.reviews.size)
        println("UNIFIED_REVIEW_RESTART parent=${ProcessHandle.current().pid()} child=${child.pid()} pending=2 history=3 original=retained")
    }
}

internal object UnifiedReviewChild {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val clock = ReviewTestClock(args[1].toLong())
        val c = TextbookController(TextbookLibrary(Path.of(args[0]), clock))
        c.load()
        check(c.state.value.screen == BookScreen.REVIEWING)
        val row = checkNotNull(c.state.value.review)
        check(row.book == args[2] && row.lesson == args[3])
        check(row.answer.text == "Unfinished retry")
        check(c.state.value.reviews.size == 3)
        check(c.state.value.reviews.any { LocalReviewSource.DUE in it.sources })
        c.saveReviewDraft(row.book, row.lesson, row.mode, PracticeAnswer(row.practice.answer, true, guessed = true))
        check(c.state.value.reviews.size == 3)
        c.saveReviewDraft(row.book, row.lesson, row.mode, PracticeAnswer())
        c.saveReviewDraft(row.book, row.lesson, row.mode, PracticeAnswer(row.practice.answer, true))
        check(c.state.value.reviews.size == 2)
        check(c.state.value.review!!.history.size == 3)
        println("RESTORED_REVIEW pid=${ProcessHandle.current().pid()} pending=2 history=3 original=retained")
    }
}
