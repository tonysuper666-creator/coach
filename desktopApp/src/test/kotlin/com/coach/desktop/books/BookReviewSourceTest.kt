package com.coach.desktop.books

import com.coach.domain.reviewqueue.QueueAvailability
import com.coach.domain.reviewqueue.QueueQuestion
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.DriverManager
import kotlin.test.*

/** Uses the existing approved synthetic payload, but never its injected library for consumption. */
internal class PersistedSourceFixture {
    val data = VariantFixture()
    val authority = BookReviewSourceAuthority { submitted -> require(submitted.origin == data.input!!.origin) }
    val library get() = TextbookLibrary(data.library.directory, sourceAuthority = authority) { data.day }
    val input get() = data.input!!
    fun review() = library.reviews().single()
    fun publish(revision: Long = 1, input: BookReviewVariantInput? = this.input, approval: String = "synthetic-approval") {
        library.updateReviewVariantSource(review(), BookReviewSourceUpdate(approval, revision, input))
    }
    fun snapshot(name: String): Path {
        val out = Path.of("build/screens/persisted-source")
        Files.createDirectories(out)
        return Files.copy(library.directory.resolve("library.sqlite"), out.resolve(name), REPLACE_EXISTING)
    }
    fun scalar(sql: String): Int = DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
        db.createStatement().use { it.executeQuery(sql).use { r -> r.next(); r.getInt(1) } }
    }
}

class BookReviewSourceTest {
    @Test fun ordinaryLibraryReadsApprovedPayloadAndPosedSetAfterReopen() {
        val f = PersistedSourceFixture()
        assertFalse(f.review().canStartVariant)
        val input = f.input.copy(usedQuestions = setOf(QueueQuestion("variant-a", "v0", "posed-elsewhere")))
        f.publish(input = input)
        f.publish(input = input) // exact revision replay is idempotent
        f.snapshot("ready-library.sqlite") // produced by the real import/answer/registration APIs
        assertEquals(input.usedQuestions + input.origin.question,
            BookReviewSources(f.authority) { DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}") }
                .input(f.review())!!.usedQuestions)
        val selected = f.library.startReviewVariant(f.review())
        assertEquals("variant-b", selected.variant!!.allocation.question.id)
        f.library.saveReviewAnswer(selected, PracticeAnswer("Supported", true, guessed = true))
        val reopened = f.review()
        assertEquals(selected.variant, reopened.variant)
        assertEquals(PracticeAnswer("Supported", true, guessed = true), reopened.answer)
        assertEquals(f.data.wrong, reopened.originalAnswer)
        assertFalse(reopened.completed)
        assertEquals(1, f.data.rows()); assertEquals("ok", f.data.integrity())
        f.snapshot("answered-library.sqlite")
    }

    @Test fun revisionConflictsAndInvalidOriginalRollBackWithoutChangingReadySource() {
        val f = PersistedSourceFixture(); f.publish()
        val badOrigins = listOf(
            f.input.origin.copy(courseId = ""), f.input.origin.copy(kpId = ""),
            f.input.origin.copy(question = QueueQuestion("original", null)),
            f.input.origin.copy(attemptId = ""), f.input.origin.copy(answer = "not the original answer"),
            f.input.origin.copy(practice = f.input.origin.practice.copy(prompt = "similar is insufficient")),
            f.input.origin.copy(guessed = false),
        )
        for (origin in badOrigins) assertFailsWith<IllegalArgumentException> {
            f.publish(2, f.input.copy(origin = origin))
        }
        assertFailsWith<IllegalArgumentException> { f.publish(2, approval = "different-approval") }
        assertFailsWith<IllegalArgumentException> { f.publish(0) }
        assertFailsWith<IllegalArgumentException> { f.publish(1, f.input.copy(usedQuestions = setOf(QueueQuestion("variant-a", "v1")))) }
        assertFailsWith<IllegalArgumentException> { f.publish(2, f.input.copy(usedQuestions = setOf(QueueQuestion("", "v1")))) }
        assertEquals(1, f.scalar("SELECT revision FROM book_review_sources"))
        assertEquals(1, f.scalar("SELECT count(*) FROM book_review_source_posed"))
        assertTrue(f.review().canStartVariant)
        assertEquals(0, f.data.rows()); assertEquals("ok", f.data.integrity())
    }

    @Test fun withdrawalSurvivesReopenRejectsStaleDraftAndCannotErasePosedFacts() {
        val f = PersistedSourceFixture()
        f.publish(input = f.input.copy(usedQuestions = setOf(QueueQuestion("variant-a", "v1"))))
        val selected = f.library.startReviewVariant(f.review())
        assertEquals("variant-b", selected.variant!!.allocation.question.id)
        f.publish(2, null)
        assertFalse(f.review().canStartVariant); assertFalse(f.review().canAnswer)
        assertNull(f.review().variant)
        assertFailsWith<IllegalArgumentException> { f.library.saveReviewAnswer(selected, PracticeAnswer("Supported", true)) }
        assertFailsWith<IllegalArgumentException> { f.publish(1) }
        f.publish(3) // source snapshot omits the old external posed list
        assertFalse(f.review().canStartVariant, "variant-a remains posed; variant-b has persisted local history")
        assertEquals(2, f.scalar("SELECT count(*) FROM book_review_source_posed"))
        assertEquals(1, f.data.rows()); assertEquals(f.data.wrong, f.review().originalAnswer)
        assertEquals("ok", f.data.integrity())
        f.snapshot("withdrawn-then-restored.sqlite")
    }

    @Test fun invalidOrReplacedOffersNeverBecomeSupplyThroughDefaultAdapter() {
        val mutations: List<(BookReviewVariantOffer) -> BookReviewVariantOffer> = listOf(
            { it.copy(originalQuestion = QueueQuestion("other-original", "v1")) },
            { it.copy(originalAttemptId = "other-attempt") },
            { it.copy(book = "other-book") }, { it.copy(lesson = "other-lesson") },
            { it.copy(candidate = it.candidate.copy(courseId = "other-course")) },
            { it.copy(candidate = it.candidate.copy(kpId = "other-kp")) },
            { it.copy(candidate = it.candidate.copy(question = it.candidate.question.copy(version = null))) },
            { it.copy(candidate = it.candidate.copy(availability = QueueAvailability.RETIRED)) },
            { it.copy(candidate = it.candidate.copy(keyVerified = false)) },
            { it.copy(approved = false) },
            { it.copy(practice = it.practice.copy(reference = "untraceable source")) },
        )
        for (mutate in mutations) {
            val f = PersistedSourceFixture()
            f.publish(input = f.input.copy(offers = listOf(mutate(f.input.offers.first()))))
            assertFalse(f.review().canStartVariant)
            assertNull(f.library.startReviewVariant(f.review()).variant)
            assertEquals(0, f.data.rows())
        }
        val f = PersistedSourceFixture(); f.publish()
        val selected = f.library.startReviewVariant(f.review())
        f.publish(2, f.input.copy(offers = f.input.offers.map {
            it.copy(candidate = it.candidate.copy(question = it.candidate.question.copy(version = "v2")))
        }))
        assertNull(f.review().variant); assertFalse(f.review().canAnswer)
        assertFailsWith<IllegalArgumentException> { f.library.saveReviewAnswer(selected, PracticeAnswer("Supported", true)) }
        assertEquals(1, f.data.rows()); assertEquals("ok", f.data.integrity())
    }

    @Test fun oldLibraryUpgradeDoesNotInventAnAssociationOrChangeQueue() {
        val f = PersistedSourceFixture()
        // Simulate the exact pre-adapter schema; the ordinary constructor performs the migration.
        DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { it.execute("DROP TABLE book_review_sources"); it.execute("DROP TABLE book_review_source_posed") }
        }
        repeat(2) {
            assertFalse(f.review().canStartVariant)
            assertEquals(f.data.original, f.review().practice)
            assertEquals(f.data.wrong, f.review().originalAnswer)
            assertEquals(0, f.scalar("SELECT count(*) FROM book_review_sources"))
            assertEquals(0, f.scalar("SELECT count(*) FROM book_review_source_posed"))
            assertEquals(0, f.data.rows()); assertEquals("ok", f.data.integrity())
        }
    }
}
