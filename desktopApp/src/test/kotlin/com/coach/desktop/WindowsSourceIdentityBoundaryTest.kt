package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.DriverManager
import kotlin.test.*

/** Exercise refusals through the actual file picker/controller, without registering a ready source. */
@OptIn(ExperimentalTestApi::class)
class WindowsSourceIdentityBoundaryTest {
    private val envelopeError = "来源登记未保存：文件缺少完整身份或与当前题不一致。"
    private val originError = "来源身份须明确，且原题、原答和原作答记录须与本地一致。"

    private fun state(f: SourceReceiptFixture): Map<String, List<List<String?>>> =
        DriverManager.getConnection("jdbc:sqlite:${f.library.directory.resolve("library.sqlite")}").use { db ->
            listOf("answers", "book_reviews", "book_review_sources", "book_review_source_posed",
                "book_review_variant_attempts").associateWith { table ->
                db.createStatement().use { sql -> sql.executeQuery("SELECT * FROM $table ORDER BY rowid").use { rows ->
                    buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }
                } }
            }
        }

    private fun snapshot(f: SourceReceiptFixture, name: String) {
        val out = Path.of("build/screens/source-identity"); Files.createDirectories(out)
        Files.copy(f.library.directory.resolve("library.sqlite"), out.resolve("$name.sqlite"), REPLACE_EXISTING)
    }

    private fun refused(name: String, receipt: (SourceReceiptFixture) -> JsonObject,
        error: String = envelopeError, answerOriginal: Boolean = false) {
        val f = SourceReceiptFixture()
        val file = f.file(receipt(f))
        val before = state(f)
        snapshot(f, "$name-before")
        val c = TextbookController(f.library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { file } }
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.book != null }
            onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
            onNodeWithTag("host-today-review").performClick()
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.screen == BookScreen.REVIEWING }
            onNodeWithTag("review-import-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.error != null }
            onNodeWithTag("book-error").assertTextEquals(error)
            onNodeWithTag("review-start-variant").assertDoesNotExist()
            onNodeWithTag("review-variant-state").performScrollTo().assertTextEquals(
                "新变式未备好：缺少明确关联、有效版本或可查来源。")
            assertEquals(before, state(f), "Rejected import must not change answers, queue, sources or posed history")
            assertFalse(f.review().completed)
            snapshot(f, "$name-rejected")
            if (answerOriginal) {
                onNodeWithTag("guess-mark").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { c.state.value.answer.guessed }
                onNodeWithTag("choice-${f.data.original.options.indexOf(f.data.original.answer)}")
                    .performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { f.review().answer.revealed }
                assertEquals(PracticeAnswer(f.data.original.answer, true, guessed = true), f.review().answer)
                assertFalse(f.review().completed)
            }
        }
        val restored = f.library.reviews().single()
        assertFalse(restored.completed); assertFalse(restored.canStartVariant)
        assertEquals(f.data.wrong, restored.originalAnswer)
        if (answerOriginal) assertEquals(PracticeAnswer(f.data.original.answer, true, guessed = true), restored.answer)
        assertEquals(0, f.data.rows()); assertEquals("ok", f.data.integrity())
        snapshot(f, "$name-reopened")
        println("IDENTITY_REFUSAL $name: persisted unchanged at rejection; reopened queue=1 completed=false variants=0 integrity=ok")
    }

    @Test fun unrelatedBookLessonAndModeRefuseAtTheActualRegistrationEntry() {
        for ((field, value) in listOf("bookSha256" to "other-book", "lessonId" to "other-lesson", "mode" to "SHORT_ANSWER")) {
            refused(field, { JsonObject(it.receipt() + (field to JsonPrimitive(value))) }, answerOriginal = field == "bookSha256")
        }
    }

    @Test fun undeclaredCompleteOrMissingPosedSetRefuseAtTheActualRegistrationEntry() {
        refused("incomplete-flag", { JsonObject(it.receipt() + ("posedSetComplete" to JsonPrimitive(false))) })
        refused("missing-posed-set", {
            JsonObject(it.receipt() + ("input" to JsonObject(BookReviewSourceJson.encode(it.input) - "usedQuestions")))
        })
    }

    @Test fun changedOriginalQuestionOrAnswerRefusesWithoutOverwritingLocalSnapshots() {
        refused("original-question", { f ->
            f.receipt(f.input.copy(origin = f.input.origin.copy(practice = f.data.original.copy(prompt = "Different original"))))
        }, originError)
        refused("original-answer", { f -> f.receipt(f.input.copy(origin = f.input.origin.copy(answer = "Different answer"))) }, originError)
    }

    @Test fun fingerprintOnlyLedgerIdentityIsNotSilentlyAssignedASyntheticVersion() {
        refused("fingerprint-only", { f ->
            f.receipt(f.input.copy(origin = f.input.origin.copy(question = f.input.origin.question.copy(
                version = null, fingerprint = "synthetic-fingerprint"))))
        }, originError)
    }
}
