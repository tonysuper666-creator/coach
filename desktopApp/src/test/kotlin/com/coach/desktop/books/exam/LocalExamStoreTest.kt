package com.coach.desktop.books.exam

import com.coach.desktop.books.*
import com.coach.domain.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LocalExamStoreTest {
    private class Fixture {
        val root = Files.createTempDirectory("coach-local-exam-")
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val book = imported.copy(readingPage = 3, lessonIndex = 1, learning = true, promptSeen = true).also(library::save)
        val lesson = book.lessons.first()
        val quote = lesson.blocks.first { !it.heading }.text
        val source = json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to 1, "quote" to quote)
        init { library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer("existing learner draft")) }
        fun paper(id: String = "authored-v1", essay: Boolean = true, firstSource: JsonObject = source): LocalExamPaper =
            LocalExamPaper(json("id" to id, "bookId" to book.id, "title" to "Self-authored synthetic biology",
                "kind" to "paper", "origin" to "AUTHORED", "key_state" to "UNREVIEWED",
                "sections" to JsonArray(listOf(json("title" to "Synthetic questions", "items" to JsonArray(buildList {
                    add(json("id" to "q-choice", "type" to "mcq", "stem" to "Which resource supplies energy for photosynthesis?",
                        "marks" to 2, "options" to JsonArray(listOf(JsonPrimitive("Light"), JsonPrimitive("Rock"))),
                        "answer" to 0, "explanation" to "This is a synthetic self-authored key.", "source" to firstSource))
                    if (essay) add(json("id" to "q-essay", "type" to "essay", "stem" to "Explain the mechanism in your own words.",
                        "marks" to 3, "model_answer" to quote, "source" to source))
                }))))))
        fun store(run: String? = null, at: Long = 1000) = LocalExamStore(TextbookLibrary(library.directory), run) { at }
        fun assertLearnerUnchanged() {
            val reopened = TextbookLibrary(library.directory)
            assertEquals(3, reopened.book(book.id).readingPage)
            assertEquals(1, reopened.book(book.id).lessonIndex)
            assertEquals(PracticeAnswer("existing learner draft"), reopened.answer(book.id, lesson.id, QuestionMode.CHOICE))
        }
    }

    private fun answers(choice: Int = 0, essay: String? = null) = json(
        "q-choice" to json("choice" to choice),
        *if (essay == null) emptyArray() else arrayOf("q-essay" to json("text" to essay)),
    )

    private fun evidence(name: String, value: JsonObject) {
        val dir = Path.of("build/evidence/local-exam")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.json"), value.toString())
    }

    @Test fun authoredPaperAnswersReopenSubmitAndTraceOriginalAttempt() {
        val f = Fixture()
        val store = f.store()
        assertTrue(store.papers(f.book.id).isEmpty())
        val registration = store.register(f.paper())
        val started = store.start(f.book.id, "authored-v1")
        val id = started.str("mockId")
        val visibleItems = started["paper"]!!.jsonObject["sections"]!!.jsonArray.first().jsonObject["items"]!!.jsonArray
        assertTrue(visibleItems.all { "answer" !in it.jsonObject && "model_answer" !in it.jsonObject })
        val saved = store.save(f.book.id, id, answers(0, "Plants use light to make sugars."), 2)
        assertTrue(saved.flag("saved"))
        evidence("01-before-reopen", store.get(f.book.id, id))
        assertFalse(store.save(f.book.id, id, answers(1, "older"), 1).flag("saved"))
        val reopened = f.store(at = 2000)
        val resumed = reopened.get(f.book.id, id)
        assertEquals(saved["answers"], resumed["answers"])
        assertEquals(2, resumed.num("answersSeq"))
        assertEquals(id, reopened.active(f.book.id)!!.str("mockId"))
        assertEquals(id, reopened.start(f.book.id, "authored-v1").str("mockId"))
        assertEquals(registration.str("snapshotSha256"), resumed.str("snapshotSha256"))
        evidence("02-after-sqlite-reopen", resumed)
        val submitted = reopened.submit(f.book.id, id)
        assertEquals("submitted", submitted.str("status"))
        assertEquals(2000, submitted.num("submittedAt"))
        val result = submitted["result"]!!.jsonObject
        assertEquals(JsonNull, result["score"], "An ungraded essay must not be reported as a final zero or a final score")
        assertEquals(200, result.num("knownScore"))
        assertEquals(500, result.num("max"))
        assertEquals(1, result.num("pending"))
        assertEquals("UNREVIEWED", result.str("key_state"))
        assertEquals("PENDING_REVIEW", result["items"]!!.jsonArray[1].jsonObject.str("status"))
        val frozenSource = submitted["paper"]!!.jsonObject["sections"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray[0].jsonObject["source"]!!.jsonObject
        assertEquals(f.book.pages[0].text, frozenSource.str("pageText"))
        assertEquals(f.quote, frozenSource.str("quote"))
        assertEquals(f.book.id, frozenSource.str("docId"))
        assertEquals(submitted, f.store(at = 9000).submit(f.book.id, id))
        assertEquals(submitted, f.store().history(f.book.id).single())
        assertNull(reopened.active(f.book.id))
        assertFailsWith<IllegalStateException> { reopened.save(f.book.id, id, answers(1), 3) }
        evidence("03-after-submit", submitted)
        DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT id,owner,book,paper_id,paper_hash,answers_seq,answers_json,submitted_at,result_json FROM local_exam_attempts").use { rows ->
                    assertTrue(rows.next())
                    evidence("04-direct-sqlite-readback", json("mockId" to rows.getString("id"), "owner" to rows.getString("owner"),
                        "bookId" to rows.getString("book"), "paperId" to rows.getString("paper_id"),
                        "snapshotSha256" to rows.getString("paper_hash"), "answersSeq" to rows.getLong("answers_seq"),
                        "answers" to LedgerJson.parseToJsonElement(rows.getString("answers_json")),
                        "submittedAt" to rows.getLong("submitted_at"), "result" to LedgerJson.parseToJsonElement(rows.getString("result_json"))))
                    assertFalse(rows.next())
                }
            }
        }
        f.assertLearnerUnchanged()
        println("LOCAL_EXAM_SQLITE_PASS: create -> answer(seq2) -> reopen -> submit -> original attempt; essay pending; original book/draft unchanged")
    }

    @Test fun noPaperAndNoUsableQuestionHaveExplicitGaps() {
        val f = Fixture()
        val store = f.store()
        assertTrue(store.papers(f.book.id).isEmpty())
        assertFailsWith<IllegalArgumentException> { store.start(f.book.id, "missing") }
        val blank = Textbook("a".repeat(64), "Scanned only", "pdf", listOf(BookPage(1, "")))
        val unavailable = textbookReferencePaper(blank)
        assertNull(unavailable.paper)
        assertTrue(unavailable.gaps.isNotEmpty())
        assertFailsWith<IllegalArgumentException> {
            LocalExamPaper(json("id" to "empty", "bookId" to f.book.id, "title" to "Empty", "kind" to "paper",
                "origin" to "AUTHORED", "sections" to JsonArray(emptyList())))
        }
        assertNull(store.active(f.book.id))
    }

    @Test fun actualTextbookPracticeIsAnUnreviewedReferenceDrill() {
        val f = Fixture()
        val availability = textbookReferencePaper(f.book)
        val drill = availability.paper!!
        assertEquals("TEXTBOOK_REFERENCE", drill.origin)
        assertEquals("drill", drill.kind)
        assertEquals(f.book.lessons.size, drill.items.size)
        val practice = StudyCoachCourse.practice(f.book, f.book.lessons[0], QuestionMode.CHOICE)!!
        assertEquals(practice.prompt, drill.items.first().stem)
        assertEquals(practice.options, drill.items.first().options.map { it.text })
        assertEquals(practice.reference, drill.items.first().source.str("quote"))
        val store = f.store()
        store.register(drill)
        val attempt = store.start(f.book.id, drill.id)
        assertEquals(Purpose.PRACTICE.name, attempt.str("purpose"))
        assertEquals("UNREVIEWED", attempt.str("key_state"))
        assertFailsWith<IllegalArgumentException> { LocalExamPaper(JsonObject(drill.snapshot + ("kind" to JsonPrimitive("paper")))) }
        assertFailsWith<IllegalArgumentException> { LocalExamPaper(JsonObject(drill.snapshot + ("key_state" to JsonPrimitive("VERIFIED")))) }
    }

    @Test fun invalidSourceOrReusedIdentityCannotOverwriteFrozenPaper() {
        val f = Fixture()
        val store = f.store()
        val original = store.register(f.paper(essay = false))
        val forged = JsonObject(f.source + json("quote" to "This sentence is not in this PDF", "pageText" to "forged"))
        assertFailsWith<IllegalArgumentException> { store.register(f.paper("bad-source", false, forged)) }
        val replacement = f.paper(essay = false).snapshot.toMutableMap().apply { put("title", JsonPrimitive("Changed edition")) }
        assertFailsWith<IllegalArgumentException> { store.register(LocalExamPaper(JsonObject(replacement))) }
        assertEquals(original, store.register(f.paper(essay = false)))
        assertEquals(1, store.papers(f.book.id).size)
        val start = store.start(f.book.id, "authored-v1")
        assertEquals(original.str("snapshotSha256"), start.str("snapshotSha256"))
        store.register(f.paper("authored-v2", false))
        assertFailsWith<IllegalArgumentException> { store.start(f.book.id, "authored-v2") }
    }

    @Test fun learnerTwoRunsAndDifferentBooksHaveSeparateAttemptIdentity() {
        val f = Fixture()
        val learner = f.store()
        learner.register(f.paper(essay = false))
        val runA = f.store("run-A")
        val runB = f.store("run-B")
        val l = learner.start(f.book.id, "authored-v1").str("mockId")
        val a = runA.start(f.book.id, "authored-v1").str("mockId")
        val b = runB.start(f.book.id, "authored-v1").str("mockId")
        assertEquals(3, setOf(l, a, b).size)
        learner.save(f.book.id, l, answers(0), 1)
        runA.save(f.book.id, a, answers(1), 1)
        assertEquals(json(), runB.get(f.book.id, b)["answers"])
        assertFailsWith<IllegalArgumentException> { runA.get(f.book.id, l) }
        assertFailsWith<IllegalArgumentException> { learner.get("different-book", l) }
        runA.submit(f.book.id, a)
        assertTrue(learner.history(f.book.id).isEmpty())
        assertEquals(a, runA.history(f.book.id).single().str("mockId"))
        assertEquals("run-A", runA.get(f.book.id, a).str("agentRunId"))
        assertEquals(answers(0), f.store().get(f.book.id, l)["answers"])
        f.assertLearnerUnchanged()
    }

    @Test fun invalidAndLateDraftSavesDoNotChangePersistedAnswers() {
        val f = Fixture()
        val a = f.store()
        a.register(f.paper(essay = false))
        val id = a.start(f.book.id, "authored-v1").str("mockId")
        a.save(f.book.id, id, answers(0), 5)
        val b = f.store()
        assertFalse(b.save(f.book.id, id, answers(1), 4).flag("saved"))
        assertFailsWith<IllegalArgumentException> { b.save(f.book.id, id, answers(99), 6) }
        assertFailsWith<IllegalArgumentException> { b.save(f.book.id, id, json("not-in-paper" to json("choice" to 0)), 6) }
        assertEquals(5, b.get(f.book.id, id).num("answersSeq"))
        assertEquals(answers(0), b.get(f.book.id, id)["answers"])
        assertEquals(200, b.submit(f.book.id, id)["result"]!!.jsonObject.num("score"))
    }

    @Test fun failedSubmissionRollsBackAndCanBeRetried() {
        val f = Fixture()
        val store = f.store()
        store.register(f.paper(essay = false))
        val id = store.start(f.book.id, "authored-v1").str("mockId")
        store.save(f.book.id, id, answers(0), 1)
        DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db ->
            db.createStatement().use { it.execute("""CREATE TRIGGER reject_submit BEFORE UPDATE OF result_json ON local_exam_attempts
                WHEN NEW.result_json IS NOT NULL BEGIN SELECT RAISE(ABORT,'synthetic write failure'); END""") }
        }
        assertFails { store.submit(f.book.id, id) }
        val reopened = f.store()
        assertEquals("active", reopened.get(f.book.id, id).str("status"))
        assertEquals(answers(0), reopened.get(f.book.id, id)["answers"])
        assertTrue(reopened.history(f.book.id).isEmpty())
        DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db ->
            db.createStatement().use { it.execute("DROP TRIGGER reject_submit") }
        }
        assertEquals(200, reopened.submit(f.book.id, id)["result"]!!.jsonObject.num("score"))
        f.assertLearnerUnchanged()
    }

    @Test fun twoConnectionsSubmitOnlyOneResultAndKeepOriginalTimestamp() {
        val f = Fixture()
        val a = f.store(at = 2000)
        a.register(f.paper(essay = false))
        val id = a.start(f.book.id, "authored-v1").str("mockId")
        a.save(f.book.id, id, answers(0), 1)
        val b = f.store(at = 3000)
        val gate = CountDownLatch(1)
        val first = CompletableFuture.supplyAsync { gate.await(); a.submit(f.book.id, id) }
        val second = CompletableFuture.supplyAsync { gate.await(); b.submit(f.book.id, id) }
        gate.countDown()
        assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
        assertEquals(1, a.history(f.book.id).size)
        assertEquals(200, b.history(f.book.id).single()["result"]!!.jsonObject.num("score"))
    }

    @Test fun snapshotHashesAreCheckedAndCatalogChangesCannotAlterAnExistingAttempt() {
        val f = Fixture()
        val store = f.store()
        store.register(f.paper(essay = false))
        val id = store.start(f.book.id, "authored-v1").str("mockId")
        DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE local_exam_papers SET paper_hash='corrupt'") }
        }
        assertFailsWith<IllegalStateException> { store.papers(f.book.id) }
        assertFailsWith<IllegalStateException> { store.start(f.book.id, "authored-v1") }
        // The attempt owns its own original snapshot, independently of the catalog.
        assertEquals("Self-authored synthetic biology", store.get(f.book.id, id)["paper"]!!.jsonObject.str("title"))
        DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE local_exam_attempts SET paper_hash='corrupt'") }
        }
        assertFailsWith<IllegalStateException> { f.store().get(f.book.id, id) }
    }

    @Test fun unsupportedSchemaOrClaimedVerifiedQuestionKeyCannotBecomeAvailable() {
        val f = Fixture()
        val raw = f.paper(essay = false).snapshot
        assertFailsWith<IllegalArgumentException> { LocalExamPaper(JsonObject(raw + ("schemaVersion" to JsonPrimitive(2)))) }
        val section = raw["sections"]!!.jsonArray[0].jsonObject
        val question = section["items"]!!.jsonArray[0].jsonObject
        val claimed = JsonObject(question + ("key_state" to JsonPrimitive("VERIFIED")))
        val changed = JsonObject(section + ("items" to JsonArray(listOf(claimed))))
        assertFailsWith<IllegalArgumentException> { LocalExamPaper(JsonObject(raw + ("sections" to JsonArray(listOf(changed))))) }
        val duplicate = JsonObject(question + ("options" to JsonArray(listOf(JsonPrimitive("Light"), JsonPrimitive(" light ")))))
        val ambiguous = JsonObject(section + ("items" to JsonArray(listOf(duplicate))))
        assertFailsWith<IllegalArgumentException> { LocalExamPaper(JsonObject(raw + ("sections" to JsonArray(listOf(ambiguous))))) }
        assertTrue(f.store().papers(f.book.id).isEmpty())
    }
}
