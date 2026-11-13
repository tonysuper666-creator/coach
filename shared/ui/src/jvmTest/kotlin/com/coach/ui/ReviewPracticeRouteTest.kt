package com.coach.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.domain.reviewqueue.*
import com.coach.protocol.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** Existing SQLite replica and existing QuizGroup restoration, using synthetic facts only. */
class ReviewPracticeRouteTest {
    private val now = 1_794_052_800_000L
    private val day = logicalDay(now, "Europe/London")
    private val transport = object : LedgerTransport {
        override suspend fun hello(epoch: String?): Hello = error("No network")
        override suspend fun snapshot(course: String): Snapshot = error("No network")
        override suspend fun events(course: String, after: Long): EventsPage = error("No network")
        override suspend fun command(command: WireCommand): Receipt = error("No network")
        override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
    }
    private inner class Fixture(url: String = JdbcSqliteDriver.IN_MEMORY, create: Boolean = true) : AutoCloseable {
        val driver = JdbcSqliteDriver(url)
        val db = ClientDatabase(driver)
        val sync = sync()
        init { if (create) ClientDatabase.Schema.create(driver) }
        fun sync(device: String = "phone", run: String? = null) = SyncEngine(db, device, transport, listOf("C"),
            { "unused-command" }, { "unused-hash" }, agentRunId = run)
        fun add(type: String, payload: JsonObject, thread: String? = null): Event {
            val seq = sync.localEvents("C").maxOfOrNull { it.seq }?.plus(1) ?: 1
            val e = Event(seq, "e$seq", "C", type, recordedAt = now, occurredAt = now - 86_400_000,
                deviceId = "phone", actor = "SYSTEM", threadId = thread, payload = payload)
            db.ledgerQueries.putEvent(seq, "C", LedgerJson.encodeToString(Event.serializer(), e))
            return e
        }
        fun view(events: List<Event> = sync.localEvents("C")) = CourseView(events, project(events))
        fun prepare(): ReviewPracticeTarget {
            add("outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic")))))
            add("question_posed", json("attemptId" to "original", "questionId" to "q-original", "kpIds" to listOf("K"),
                "format" to "SINGLE_CHOICE", "mainline" to true, "keyState" to "VERIFIED", "posedAt" to now - 86_400_000))
            add("answer_submitted", json("attemptId" to "original", "answer" to "A"))
            add("attempt_graded", json("attemptId" to "original", "score" to 0, "max" to 1, "status" to "VERIFIED"))
            val port = VariantCandidatePort { _, _, kp -> listOf(VariantCandidate("C", kp, QueueQuestion("q-exact", "v2", "fp2"), keyVerified = true)) }
            val item = reviewBoard("C", view(), day, port).items.single { it.variantAttemptId != null }
            return assertNotNull(prepareReviewPractice(sync, "C", null, item, day))
        }
        fun pose(target: ReviewPracticeTarget, changed: JsonObject = json(), thread: String = "review:C:K"): Event = add("question_posed", JsonObject(json(
            "attemptId" to target.attemptId, "questionId" to target.question.id, "questionVersion" to target.question.version,
            "fingerprint" to target.question.fingerprint, "kpIds" to listOf(target.kpId), "keyState" to "VERIFIED", "availability" to "VALID",
            "format" to "SINGLE_CHOICE", "review" to true, "groupId" to "exact-group", "stem" to "Synthetic exact question",
            "options" to JsonArray(listOf(json("optionId" to "o1", "text" to "One"), json("optionId" to "o2", "text" to "Two"))),
            "letterMap" to json("o1" to "A", "o2" to "B")) + changed), thread)
        fun restore(kp: String = "K") = restoreReviewPracticeSession(sync, "C", kp, null, view(), now)
        override fun close() { driver.close() }
    }

    @Test fun selectedIdentityConsumesTheCursorAndRejectsOtherCourseDeviceOrRun() = Fixture().use { f ->
        val target = f.prepare()
        assertEquals(target.attemptId, reviewCursorAttempt(f.sync, "C"))
        assertEquals(target, reviewPracticeTarget(f.sync, "C"))
        assertNull(reviewPracticeTarget(f.sync, "other-course"))
        assertNull(reviewPracticeTarget(f.sync("other-device"), "C"))
        assertNull(reviewPracticeTarget(f.sync, "C", "agent-run"))
        assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.IDENTITY_MISMATCH), f.restore("other-kp"))
        saveReviewCursor(f.sync, "C", target.entryId, day, "another-attempt")
        assertNull(reviewPracticeTarget(f.sync, "C"), "A stale scalar cursor cannot redirect the persisted question")
    }

    @Test fun missingAllocationNeverLoadsAnotherQuestionOnTheSameKpOrWritesFacts() = Fixture().use { f ->
        val target = f.prepare()
        f.pose(target.copy(attemptId = "other-attempt", question = QueueQuestion("other-question", "v2", "fp2")))
        val before = f.sync.localEvents("C")
        assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.MISSING_ALLOCATION), f.restore())
        assertEquals(before, f.sync.localEvents("C"))
        assertEquals(target, reviewPracticeTarget(f.sync, "C"))
    }

    @Test fun exactExistingSessionRestoresOriginalCardAndAnswerAfterDatabaseRestart() {
        val file = Files.createTempDirectory("synthetic-review-route").resolve("client.sqlite")
        val url = "jdbc:sqlite:$file"
        val target = Fixture(url).use { f ->
            val t = f.prepare()
            f.pose(t)
            f.sync.saveMainlineState("C", json(t.attemptId to "B"), "choices:review:C:K")
            f.add("answer_submitted", json("attemptId" to t.attemptId, "answer" to "B"))
            t
        }
        Fixture(url, create = false).use { f ->
            val ready = assertIs<ReviewPracticeSession.Ready>(f.restore())
            assertEquals(target, ready.target)
            assertEquals(target.attemptId, ready.group.cards.single().attemptId)
            assertEquals("Synthetic exact question", ready.group.cards.single().stem)
            assertEquals("B", ready.group.cards.single().answer)
            assertEquals("B", f.sync.mainlineState("C", "choices:review:C:K").str(target.attemptId))
            assertEquals("A", f.sync.localEvents("C").single { it.type == "answer_submitted" && it.payload.str("attemptId") == "original" }.payload.str("answer"))
        }
    }

    @Test fun versionQuestionKpAndAgentMismatchCannotLoadARealSession() {
        for (change in listOf(json("questionId" to "wrong"), json("questionVersion" to "v3"), json("fingerprint" to "fp3"),
            json("kpIds" to listOf("other-kp")))) Fixture().use { f ->
            val t = f.prepare(); f.pose(t, change)
            assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.IDENTITY_MISMATCH), f.restore())
        }
        Fixture().use { f ->
            val t = f.prepare(); f.pose(t, json("agentRunId" to "other-run"))
            // The real CourseView excludes the entire agent attempt before the route can see it.
            assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.MISSING_ALLOCATION), f.restore())
        }
    }

    @Test fun unverifiedRetiredVoidedOrIncompletePosesAreRejectedWithoutLosingSelection() {
        val cases = listOf(json("keyState" to "UNREVIEWED") to ReviewPracticeFailure.UNVERIFIED,
            json("availability" to "RETIRED") to ReviewPracticeFailure.UNAVAILABLE,
            json("availability" to null) to ReviewPracticeFailure.UNAVAILABLE,
            json("availability" to "UNKNOWN") to ReviewPracticeFailure.UNAVAILABLE,
            json("expiresAt" to now) to ReviewPracticeFailure.UNAVAILABLE,
            json("stem" to "") to ReviewPracticeFailure.INCOMPLETE_CARD)
        for ((change, reason) in cases) Fixture().use { f ->
            val t = f.prepare(); f.pose(t, change)
            assertEquals(ReviewPracticeSession.Rejected(reason), f.restore())
            assertEquals(t, reviewPracticeTarget(f.sync, "C"))
        }
        Fixture().use { f ->
            val t = f.prepare(); f.pose(t)
            f.add("attempt_voided", json("attemptId" to t.attemptId))
            assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.UNAVAILABLE), f.restore())
        }
    }

    @Test fun currentKeyWithdrawalAndUnknownLedgerFactsBlockRestoration() = Fixture().use { f ->
        val t = f.prepare(); f.pose(t)
        f.add("question_key_changed", json("questionId" to t.question.id, "keyState" to "REJECTED"))
        assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.UNVERIFIED), f.restore())
        f.add("future_synthetic_fact", json())
        assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.SNAPSHOT_REQUIRED), f.restore())
    }
}
