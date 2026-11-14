package com.coach.desktop.books

import com.coach.domain.reviewqueue.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

internal class VariantFixture {
    val root = Files.createTempDirectory("synthetic-book-variant-")
    var day = 21000L
    var input: BookReviewVariantInput? = null
    val port = BookReviewVariantPort { input }
    val library = TextbookLibrary(root.resolve("library"), port) { day }
    val book = library.importBook(syntheticTextbook(root.resolve("source")))
    val lesson = book.lessons.first()
    val original = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
    val wrong = original.options.first { it != original.answer }
    init {
        library.save(book.copy(learning = true))
        library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, true, guessed = true))
        val origin = BookReviewOrigin("synthetic-course", "synthetic-kp", QueueQuestion("original", "v1"),
            "original-attempt", original, wrong, guessed = true)
        input = BookReviewVariantInput(origin, listOf("variant-a", "variant-b").map { id ->
            BookReviewVariantOffer(VariantCandidate(origin.courseId, origin.kpId, QueueQuestion(id, "v1"),
                keyVerified = true, variantGroupId = "approved-synthetic"), origin.question, origin.attemptId,
                book.id, lesson.id, Practice("Synthetic $id: choose the supported statement", original.reference,
                    "Supported", listOf("Unsupported", "Supported")), approved = true)
        }, emptySet())
    }
    fun review() = library.review(book.id, lesson.id, QuestionMode.CHOICE)
    fun reopen() = TextbookLibrary(library.directory, port) { day }
    fun rows(): Int = DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
        db.createStatement().use { sql -> sql.executeQuery("SELECT count(*) FROM book_review_variant_attempts").use { r -> r.next(); r.getInt(1) } }
    }
    fun integrity(): String = DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
        db.createStatement().use { sql -> sql.executeQuery("PRAGMA integrity_check").use { r -> r.next(); r.getString(1) } }
    }
}

class BookReviewVariantTest {
    @Test fun selectsUnusedLinkedVariantAndPreservesBothAttemptsAcrossNewLibrary() = runBlocking {
        val f = VariantFixture()
        f.input = f.input!!.copy(usedQuestions = setOf(QueueQuestion("variant-a", "v1")))
        val c = TextbookController(f.library)
        c.load(); c.openReview(f.review()); c.startReviewVariant()
        val selected = c.state.value.review!!
        assertEquals("variant-b", selected.variant!!.allocation.question.id)
        assertEquals("v1", selected.variant.allocation.question.version)
        assertEquals(f.original, selected.originalPractice)
        assertTrue(c.saveReviewDraft(f.book.id, f.lesson.id, QuestionMode.CHOICE,
            PracticeAnswer("Supported", true, guessed = true), selected.variant.allocation.attemptId))
        assertFalse(f.review().completed)
        f.day++ // wrong + guessed + due remain the same local queue item
        assertEquals(1, f.library.reviews().size)
        val reopened = f.reopen()
        val restored = TextbookController(reopened)
        restored.load()
        assertEquals(selected.variant, restored.state.value.review!!.variant)
        assertEquals(PracticeAnswer("Supported", true, guessed = true), restored.state.value.answer)
        assertEquals(f.wrong, restored.state.value.review!!.originalAnswer)
        assertEquals(PracticeAnswer(f.wrong, true, guessed = true), reopened.answer(f.book.id, f.lesson.id, QuestionMode.CHOICE))
        assertEquals(1, f.rows()); assertEquals("ok", f.integrity())
        val out = java.nio.file.Path.of("build/screens/book-variants")
        Files.createDirectories(out)
        Files.copy(f.library.directory.resolve("library.sqlite"), out.resolve("synthetic-reopened.sqlite"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        println("VARIANT_RESTORE: original=original@v1/original-attempt variant=variant-b@v1 attempt=${selected.variant.allocation.attemptId}; queue=1 completed=false integrity=ok")
    }

    @Test fun secondVariantDoesNotReplayFirstAndLateDraftCannotOverwriteIt() = runBlocking {
        val f = VariantFixture(); val c = TextbookController(f.library)
        c.load(); c.openReview(f.review()); c.startReviewVariant()
        val first = c.state.value.review!!.variant!!.allocation
        assertTrue(c.saveReviewDraft(f.book.id, f.lesson.id, QuestionMode.CHOICE,
            PracticeAnswer("Supported", true, guessed = true), first.attemptId))
        c.startReviewVariant()
        val second = c.state.value.review!!.variant!!.allocation
        assertNotEquals(first.question, second.question)
        assertEquals(PracticeAnswer(), c.state.value.answer)
        assertFalse(c.saveReviewDraft(f.book.id, f.lesson.id, QuestionMode.CHOICE,
            PracticeAnswer("Unsupported", true), first.attemptId))
        assertEquals(PracticeAnswer(), f.review().answer)
        assertEquals(2, f.rows())
        assertTrue(c.saveReviewDraft(f.book.id, f.lesson.id, QuestionMode.CHOICE,
            PracticeAnswer("Supported", true), second.attemptId))
        assertTrue(f.review().completed)
        assertEquals(f.wrong, f.review().originalAnswer)
    }

    @Test fun completedVariantReturnsWhenDueWithoutReplayingItsAnswer() {
        val f = VariantFixture()
        val first = f.library.startReviewVariant(f.review())
        f.library.saveReviewAnswer(first, PracticeAnswer("Supported", true))
        assertTrue(f.review().completed)
        f.day = f.review().dueDay!!
        val due = f.reopen().reviews().single()
        assertFalse(due.completed); assertEquals(PracticeAnswer(), due.answer)
        assertFalse(due.canAnswer); assertTrue(due.canStartVariant)
        assertFailsWith<IllegalArgumentException> { f.library.saveReviewAnswer(first, PracticeAnswer("Supported", true)) }
        val next = f.reopen().startReviewVariant(due)
        assertNotEquals(first.variant!!.allocation.question, next.variant!!.allocation.question)
        assertEquals(PracticeAnswer(), next.answer); assertTrue(next.canAnswer)
        assertEquals(2, f.rows()); assertEquals(1, f.reopen().reviews().size)
    }

    @Test fun allPreviouslyPosedCandidatesStayNotReadyWithoutCreatingRows() {
        val f = VariantFixture()
        f.input = f.input!!.copy(usedQuestions = f.input!!.offers.map { it.candidate.question }.toSet())
        assertFalse(f.review().canStartVariant)
        assertNull(f.library.startReviewVariant(f.review()).variant)
        assertEquals(0, f.rows())
    }

    @Test fun rejectsWrongAssociationScopeVersionKeyApprovalAndMissingSource() {
        val mutations: List<(BookReviewVariantOffer) -> BookReviewVariantOffer> = listOf(
            { it.copy(originalQuestion = QueueQuestion("different", "v1")) },
            { it.copy(originalAttemptId = "other-attempt") },
            { it.copy(candidate = it.candidate.copy(courseId = "other-course")) },
            { it.copy(candidate = it.candidate.copy(kpId = "other-kp")) },
            { it.copy(candidate = it.candidate.copy(question = it.candidate.question.copy(version = null))) },
            { it.copy(candidate = it.candidate.copy(availability = QueueAvailability.RETIRED)) },
            { it.copy(candidate = it.candidate.copy(keyVerified = false)) },
            { it.copy(approved = false) },
            { it.copy(practice = it.practice.copy(reference = "No such source in this book")) },
        )
        for (mutate in mutations) {
            val f = VariantFixture()
            f.input = f.input!!.copy(offers = listOf(mutate(f.input!!.offers.first())))
            val review = f.library.startReviewVariant(f.review())
            assertNull(review.variant); assertFalse(review.canStartVariant)
            assertEquals(0, f.rows()); assertEquals(f.original, review.practice)
        }
    }

    @Test fun unknownOriginalNeverGetsIdentityFromSharedKpOrSimilarText() {
        val f = VariantFixture()
        f.input = f.input!!.copy(origin = f.input!!.origin.copy(question = QueueQuestion("original", null)))
        assertNull(f.library.startReviewVariant(f.review()).variant)
        assertEquals(0, f.rows())
        f.input = null
        assertNull(f.reopen().reviews().single().variant)
        assertFalse(f.review().canStartVariant)
    }

    @Test fun withdrawalOrVersionReplacementBlocksAnswerAndRetainsPersistedHistory() {
        val f = VariantFixture()
        val selected = f.library.startReviewVariant(f.review())
        f.input = f.input!!.copy(offers = f.input!!.offers.map {
            it.copy(candidate = it.candidate.copy(question = it.candidate.question.copy(version = "v2")))
        })
        val reopened = f.reopen().review(f.book.id, f.lesson.id, QuestionMode.CHOICE)
        assertNull(reopened.variant); assertFalse(reopened.canAnswer)
        assertFailsWith<IllegalArgumentException> { f.library.saveReviewAnswer(selected, PracticeAnswer("Supported", true)) }
        assertEquals(1, f.rows()); assertEquals("ok", f.integrity())
        assertEquals(f.wrong, reopened.originalAnswer)
    }
}
