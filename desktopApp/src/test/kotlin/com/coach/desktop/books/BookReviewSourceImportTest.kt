package com.coach.desktop.books

import com.coach.domain.reviewqueue.QueueQuestion
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.*

/** Files contain explicit synthetic source identities. No registration API is called by this fixture. */
internal class SourceReceiptFixture {
    val data = VariantFixture()
    val library get() = TextbookLibrary(data.library.directory) { data.day }
    val input get() = data.input!!.let { it.copy(usedQuestions = setOf(it.offers.first().candidate.question)) }
    fun review() = library.reviews().single()
    fun receipt(input: BookReviewVariantInput? = this.input, revision: Long = 1) = buildJsonObject {
        put("format", "coach-book-review-source/v1")
        put("bookSha256", data.book.id); put("lessonId", data.lesson.id); put("mode", "CHOICE")
        put("approvalId", "synthetic-source-owner-receipt"); put("revision", revision)
        put("posedSetComplete", true)
        put("input", input?.let(BookReviewSourceJson::encode) ?: JsonNull)
    }
    fun file(receipt: JsonObject = receipt()): Path = Files.writeString(data.root.resolve("source-receipt.json"), receipt.toString())
    fun snapshot(name: String) {
        val out = Path.of("build/screens/source-registration"); Files.createDirectories(out)
        Files.copy(library.directory.resolve("library.sqlite"), out.resolve(name), REPLACE_EXISTING)
        Files.copy(data.root.resolve("source-receipt.json"),
            out.resolve(name.removeSuffix(".sqlite") + "-last-submitted.json"), REPLACE_EXISTING)
    }
}

class BookReviewSourceImportTest {
    @Test fun explicitFileRegistersIdempotentlyAndWithdrawalKeepsBothAnswers() {
        val f = SourceReceiptFixture()
        assertFalse(f.review().canStartVariant)
        repeat(2) { f.library.importReviewVariantSource(f.review(), f.file()) }
        val selected = f.library.startReviewVariant(f.review())
        assertEquals("variant-b", selected.variant!!.allocation.question.id)
        assertEquals(f.input.origin, selected.variant.origin)
        f.library.saveReviewAnswer(selected, PracticeAnswer("Supported", true, guessed = true))
        f.library.importReviewVariantSource(f.review(), f.file(f.receipt(null, 2)))
        val restored = f.review()
        assertNull(restored.variant); assertFalse(restored.canStartVariant); assertFalse(restored.canAnswer)
        assertEquals(f.data.wrong, restored.originalAnswer)
        assertEquals(PracticeAnswer("Supported", true, guessed = true), restored.answer)
        assertFailsWith<IllegalArgumentException> { f.library.importReviewVariantSource(restored, f.file()) }
        assertEquals(1, f.data.rows()); assertEquals("ok", f.data.integrity())
        f.snapshot("withdrawn.sqlite")
    }

    @Test fun wrongIdentityIncompletePosedSetAndMalformedFilesDoNotRegisterAnything() {
        val f = SourceReceiptFixture()
        val mutations = listOf<Pair<String, JsonElement>>(
            "format" to JsonPrimitive("unknown/v1"), "bookSha256" to JsonPrimitive("other-book"),
            "lessonId" to JsonPrimitive("other-lesson"), "mode" to JsonPrimitive("SHORT_ANSWER"),
            "posedSetComplete" to JsonPrimitive(false), "revision" to JsonPrimitive(0),
            "approvalId" to JsonPrimitive(""),
        )
        for ((key, value) in mutations) {
            assertFailsWith<IllegalArgumentException> {
                f.library.importReviewVariantSource(f.review(), f.file(JsonObject(f.receipt() + (key to value))))
            }
            assertFalse(f.review().canStartVariant)
        }
        val noPosed = JsonObject(BookReviewSourceJson.encode(f.input) - "usedQuestions")
        assertFailsWith<IllegalArgumentException> {
            f.library.importReviewVariantSource(f.review(), f.file(JsonObject(f.receipt() + ("input" to noPosed))))
        }
        val path = f.file()
        for (text in listOf("{", "", " ".repeat(1_048_577))) {
            Files.writeString(path, text)
            assertFailsWith<IllegalArgumentException> { f.library.importReviewVariantSource(f.review(), path) }
        }
        assertFalse(f.review().canStartVariant); assertEquals(0, f.data.rows())
        assertEquals("ok", f.data.integrity()); f.snapshot("rejected.sqlite")
    }

    @Test fun fileCannotInventAnOriginalAttemptOrBypassCandidateValidation() {
        val f = SourceReceiptFixture()
        val origin = f.input.origin
        for (badOrigin in listOf(origin.copy(answer = "other answer"),
            origin.copy(question = QueueQuestion("original", null)), origin.copy(attemptId = ""))) {
            assertFailsWith<IllegalArgumentException> {
                f.library.importReviewVariantSource(f.review(), f.file(f.receipt(f.input.copy(origin = badOrigin))))
            }
        }
        // Complete explicit input can still be not-ready: registration does not grant approval.
        val unapproved = f.input.copy(offers = f.input.offers.map { it.copy(approved = false) })
        f.library.importReviewVariantSource(f.review(), f.file(f.receipt(unapproved)))
        assertFalse(f.review().canStartVariant)
        assertNull(f.library.startReviewVariant(f.review()).variant)
        assertEquals(0, f.data.rows()); assertEquals(f.data.wrong, f.review().originalAnswer)
        assertEquals("ok", f.data.integrity())
    }
}
