package com.coach.desktop.books.exam

import com.coach.desktop.books.*
import com.coach.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal class TimedExamFixture {
    val root = Files.createTempDirectory("coach-timer-")
    val library = TextbookLibrary(root.resolve("library"))
    val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
    val now = AtomicLong(1_000_000)
    val draft = json("essay" to json("text" to "隔离论述草稿\nSynthetic 😀"))
    val paper: LocalExamPaper
    init {
        val lesson = book.lessons.first()
        val quote = lesson.blocks.first { !it.heading }.text
        val source = json("docId" to book.id, "lessonId" to lesson.id, "pdfPage" to 1, "quote" to quote)
        paper = LocalExamPaper(json("id" to "timed-authored", "bookId" to book.id, "title" to "Synthetic timed exam",
            "kind" to "paper", "origin" to "AUTHORED", "key_state" to "UNREVIEWED",
            "sections" to JsonArray(listOf(json("title" to "Synthetic", "items" to JsonArray(listOf(
                json("id" to "essay", "type" to "essay", "stem" to "Explain the synthetic mechanism.", "marks" to 3,
                    "model_answer" to quote, "source" to source))))))))
        store().register(paper)
    }
    fun store(owner: String? = null) = LocalExamStore(TextbookLibrary(library.directory), owner, now::get)
    fun start() = store().start(book.id, paper.id, 1)
}

class LocalExamTimingTest {
    private fun record(f: TimedExamFixture, name: String, view: JsonObject) {
        val dir = Path.of("build/evidence/local-timer")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.json"), json("controlledNow" to f.now.get(), "view" to view).toString())
        Files.copy(f.store().databasePath, dir.resolve("$name.sqlite"), REPLACE_EXISTING)
    }

    @Test fun timedDraftReopensWithOriginalDeadlineAndSqlIdentity() {
        val f = TimedExamFixture()
        val store = f.store()
        val started = f.start()
        val id = started.str("mockId")
        assertEquals("TIMED", started.str("timing"))
        assertEquals(1, started.num("durationMinutes"))
        assertEquals(1_060_000, started.num("deadline"))
        record(f, "01-start", started)
        f.now.set(1_030_000)
        assertTrue(store.save(f.book.id, id, f.draft, 2).flag("saved"))
        record(f, "02-saved", store.get(f.book.id, id))
        val reopened = f.store()
        val resumed = reopened.get(f.book.id, id)
        assertEquals(id, reopened.active(f.book.id)!!.str("mockId"))
        assertEquals(resumed, reopened.start(f.book.id, f.paper.id, 30), "Resuming cannot restart or reconfigure the clock")
        assertEquals(started.num("startedAt"), resumed.num("startedAt"))
        assertEquals(started.num("deadline"), resumed.num("deadline"))
        assertEquals(started.str("snapshotSha256"), resumed.str("snapshotSha256"))
        assertEquals(f.draft, resumed["answers"])
        assertEquals(2, resumed.num("answersSeq"))
        assertFailsWith<IllegalArgumentException> { f.store("other").get(f.book.id, id) }
        assertFailsWith<IllegalArgumentException> { reopened.get("other-book", id) }
        assertFalse(reopened.save(f.book.id, id, json("essay" to json("text" to "stale")), 1).flag("saved"))
        record(f, "03-reopened", resumed)
        f.now.set(1_045_000)
        val submitted = reopened.submit(f.book.id, id)
        assertEquals(1_045_000, submitted.num("submittedAt"))
        assertFalse(submitted.flag("autoSubmitted"))
        f.now.set(9_000_000)
        assertEquals(submitted, f.store().submit(f.book.id, id))
        record(f, "04-manual-submit", submitted)
        DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db -> db.createStatement().use { sql ->
            sql.executeQuery("SELECT * FROM local_exam_attempts WHERE id='$id'").use { rows ->
                assertTrue(rows.next())
                assertEquals("learner", rows.getString("owner"))
                assertEquals("TIMED", rows.getString("timing"))
                assertEquals(1, rows.getInt("duration_minutes"))
                assertEquals(1_000_000, rows.getLong("started_at"))
                assertEquals(1_060_000, rows.getLong("deadline"))
                assertEquals(2, rows.getLong("answers_seq"))
                assertEquals(f.draft, LedgerJson.parseToJsonElement(rows.getString("answers_json")))
                assertEquals(1_045_000, rows.getLong("submitted_at"))
            }
            sql.executeQuery("PRAGMA integrity_check").use { assertTrue(it.next()); assertEquals("ok", it.getString(1)) }
        } }
    }

    @Test fun deadlineAndGraceAreInclusiveButLateAnswersCannotEnterResult() {
        val f = TimedExamFixture()
        val store = f.store()
        val id = f.start().str("mockId")
        for ((seq, time) in listOf(1_059_999L, 1_060_000L, 1_074_999L, 1_075_000L).withIndex()) {
            f.now.set(time)
            assertTrue(store.save(f.book.id, id, f.draft, seq + 1L).flag("saved"))
            assertEquals("active", f.store().get(f.book.id, id).str("status"))
        }
        record(f, "05-inclusive-grace", store.get(f.book.id, id))
        f.now.set(1_075_001)
        val late = store.save(f.book.id, id, json("essay" to json("text" to "LATE MUST NOT ENTER")), 5)
        assertFalse(late.flag("saved"))
        assertTrue(late.flag("expired"))
        assertTrue(late.flag("autoSubmitted"))
        assertEquals("submitted", late.str("status"))
        assertEquals(1_060_000, late.num("submittedAt"))
        assertEquals(4, late.num("answersSeq"))
        assertEquals(f.draft, late["answers"])
        record(f, "06-late-rejected", late)
        val final = f.store().get(f.book.id, id)
        assertEquals(final, store.submit(f.book.id, id))
        assertEquals(final, f.store().submit(f.book.id, id, auto = true))
        assertNull(store.active(f.book.id))
        assertEquals(final, store.history(f.book.id).single())
        assertFailsWith<IllegalStateException> { store.save(f.book.id, id, f.draft, 6) }
    }

    @Test fun eachReadAndSubmissionPathExpiresFromPersistedDeadline() {
        for (path in listOf("get", "active", "history", "submit", "start")) {
            val f = TimedExamFixture()
            val store = f.store()
            val id = f.start().str("mockId")
            store.save(f.book.id, id, f.draft, 1)
            f.now.set(87_460_000)
            val reopened = f.store()
            when (path) {
                "get" -> assertEquals("submitted", reopened.get(f.book.id, id).str("status"))
                "active" -> assertNull(reopened.active(f.book.id))
                "history" -> assertEquals(id, reopened.history(f.book.id).single().str("mockId"))
                "submit" -> assertTrue(reopened.submit(f.book.id, id).flag("autoSubmitted"))
                "start" -> assertNotEquals(id, reopened.start(f.book.id, f.paper.id, 1).str("mockId"))
            }
            val closed = reopened.get(f.book.id, id)
            assertEquals(1_060_000, closed.num("submittedAt"), path)
            assertEquals(f.draft, closed["answers"], path)
            assertEquals(closed, store.submit(f.book.id, id), path)
            if (path == "get") record(f, "07-reopen-after-day", closed)
        }
    }

    @Test fun independentConnectionsCannotRaceLateInputIntoAnIdempotentResult() {
        val f = TimedExamFixture()
        val one = f.store()
        val two = f.store()
        val id = f.start().str("mockId")
        one.save(f.book.id, id, f.draft, 1)
        f.now.set(1_075_001)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        fun race(block: () -> Unit) = CompletableFuture.runAsync {
            ready.countDown(); check(go.await(10, TimeUnit.SECONDS)); block()
        }
        val save = race {
            try { assertFalse(one.save(f.book.id, id, json("essay" to json("text" to "late race")), 2).flag("saved")) }
            catch (_: IllegalStateException) { /* Another connection closed it first. */ }
        }
        val submit = race { two.submit(f.book.id, id) }
        assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown()
        save.get(10, TimeUnit.SECONDS); submit.get(10, TimeUnit.SECONDS)
        val closed = one.get(f.book.id, id)
        assertEquals(f.draft, closed["answers"])
        assertEquals(1, closed.num("answersSeq"))
        assertEquals(1_060_000, closed.num("submittedAt"))
        assertEquals(closed, two.submit(f.book.id, id))
    }

    @Test fun timingIsOptInAndReferencePracticeCannotEnableIt() {
        val f = TimedExamFixture()
        val store = f.store()
        for (duration in listOf(0, -1, 1441, Int.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { store.start(f.book.id, f.paper.id, duration) }
        val reference = textbookReferencePaper(f.book).paper!!
        store.register(reference)
        assertFailsWith<IllegalArgumentException> { store.start(f.book.id, reference.id, 1) }
        val untimed = store.start(f.book.id, f.paper.id)
        assertEquals("UNTIMED", untimed.str("timing"))
        assertEquals(JsonNull, untimed["deadline"])
        f.now.set(87_460_000)
        assertTrue(store.save(f.book.id, untimed.str("mockId"), f.draft, 1).flag("saved"))
        assertEquals("active", f.store().get(f.book.id, untimed.str("mockId")).str("status"))
    }

    @Test fun legacyElevenColumnSchemaMigratesWithoutChangingUntimedDraftsOrResults() {
        val f = TimedExamFixture()
        val store = f.store()
        val submittedId = store.start(f.book.id, f.paper.id).str("mockId")
        store.save(f.book.id, submittedId, f.draft, 3)
        val submitted = store.submit(f.book.id, submittedId)
        val activeId = store.start(f.book.id, f.paper.id).str("mockId")
        store.save(f.book.id, activeId, f.draft, 7)
        DriverManager.getConnection("jdbc:sqlite:${store.databasePath}").use { db -> db.createStatement().use { sql ->
            // Produce the original 11-column input, rather than a new-schema row merely labelled UNTIMED.
            for (column in listOf("timing", "duration_minutes", "deadline", "auto_submitted"))
                sql.execute("ALTER TABLE local_exam_attempts DROP COLUMN $column")
            sql.execute("PRAGMA user_version=0")
            sql.executeQuery("PRAGMA table_info(local_exam_attempts)").use { rows ->
                var count = 0; while (rows.next()) count++; assertEquals(11, count)
            }
        } }
        val dir = Path.of("build/evidence/local-timer")
        Files.createDirectories(dir)
        Files.copy(store.databasePath, dir.resolve("08-legacy-before.sqlite"), REPLACE_EXISTING)
        f.now.set(87_460_000)
        val reopened = f.store()
        val active = reopened.get(f.book.id, activeId)
        assertEquals("UNTIMED", active.str("timing"))
        assertEquals(JsonNull, active["deadline"])
        assertEquals(1_000_000, active.num("startedAt"))
        assertEquals(7, active.num("answersSeq"))
        assertEquals(f.draft, active["answers"])
        assertEquals(submitted, reopened.get(f.book.id, submittedId))
        assertTrue(reopened.save(f.book.id, activeId, f.draft, 8).flag("saved"))
        record(f, "09-legacy-after", reopened.get(f.book.id, activeId))
    }

    @Test fun shelfRestoresTimedAttemptAndOnlyCourseEntryPreparesReference() = runBlocking {
        val f = TimedExamFixture()
        val active = f.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = LocalExamController(f.library, scope, f.now::get)
            controller.load(f.book, prepareReference = false)
            assertEquals(active.str("mockId"), controller.state.value.active?.str("mockId"))
            assertNull(controller.state.value.reference)
            controller.load(f.book)
            assertNotNull(controller.state.value.reference)
            assertEquals(active.str("mockId"), controller.state.value.active?.str("mockId"))
        } finally { scope.cancel() }
    }

    @Test fun controllerKeepsCountdownAndDraftAcrossScopeRecreationAndAutoSubmitsOffPane() = runBlocking {
        val f = TimedExamFixture()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var controller = LocalExamController(f.library, scope, f.now::get)
            controller.load(f.book)
            controller.start(f.paper.id, 1)
            val id = controller.state.value.attempt!!.str("mockId")
            controller.answer("essay", f.draft.getValue("essay").jsonObject)
            assertTrue(controller.flush())
            controller.back()
            f.now.set(1_030_000)
            controller.tick()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            controller = LocalExamController(TextbookLibrary(f.library.directory), scope, f.now::get)
            controller.load(f.book)
            controller.open(id)
            assertEquals(1_060_000, controller.state.value.attempt!!.num("deadline"))
            assertEquals(f.draft, controller.state.value.attempt!!["answers"])
            assertEquals("00:30", localExamRemaining(1_060_000, controller.state.value.nowMillis))
            controller.back() // The timer belongs to the window, not the attempt composable.
            f.now.set(1_060_000)
            controller.tick()
            assertNull(controller.state.value.active)
            assertNull(controller.state.value.attempt)
            val closed = f.store().get(f.book.id, id)
            assertTrue(closed.flag("autoSubmitted"))
            assertEquals(f.draft, closed["answers"])
        } finally { scope.cancel() }
    }

    @Test fun timerCannotDiscardAPendingDraftWhenCloseGuardFlushFails() = runBlocking {
        val f = TimedExamFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = LocalExamController(f.library, scope, f.now::get)
            controller.load(f.book); controller.start(f.paper.id, 1)
            val id = controller.state.value.attempt!!.str("mockId")
            fun trigger(create: Boolean) = DriverManager.getConnection("jdbc:sqlite:${f.store().databasePath}").use { db ->
                db.createStatement().use { it.execute(if (create) """CREATE TRIGGER fail_timer_draft BEFORE UPDATE OF answers_json
                    ON local_exam_attempts BEGIN SELECT RAISE(FAIL,'controlled disk failure'); END""" else "DROP TRIGGER fail_timer_draft") }
            }
            trigger(true)
            controller.answer("essay", f.draft.getValue("essay").jsonObject)
            assertFalse(controller.flush())
            f.now.set(1_060_000)
            controller.tick()
            assertFalse(controller.flush())
            assertEquals(f.draft, controller.state.value.attempt!!["answers"])
            assertEquals("active", controller.state.value.attempt!!.str("status"))
            trigger(false)
            f.now.set(1_075_001)
            controller.tick()
            assertTrue(controller.state.value.conflict)
            assertFalse(controller.flush(), "Expired rejected draft must keep close protection until explicit reload")
            assertEquals(f.draft, controller.state.value.attempt!!["answers"])
            assertEquals(json(), f.store().get(f.book.id, id)["answers"])
            controller.reloadSaved()
            assertTrue(controller.flush())
            assertEquals("submitted", controller.state.value.attempt!!.str("status"))
        } finally { scope.cancel() }
    }
}
