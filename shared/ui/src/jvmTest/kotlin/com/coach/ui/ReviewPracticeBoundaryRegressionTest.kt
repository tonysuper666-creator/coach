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

/** The three cross-review counterexamples, with actual LedgerStore-generated posed facts. */
class ReviewPracticeBoundaryRegressionTest {
    private inner class Fixture : AutoCloseable {
        var clock = 1_794_052_800_000L - 86_400_000
        val store = com.coach.store.LedgerStore(Files.createTempDirectory("review-real-pose")) { clock }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = ClientDatabase(driver)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No network")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val sync = SyncEngine(db,"phone",transport,listOf("C"),{"unused"},{"unused"})
        val item: ReviewItem
        val target: ReviewPracticeTarget
        init {
            ClientDatabase.Schema.create(driver)
            val options = listOf(Option("o1","One"),Option("o2","Two"))
            store.append("C","outline_revised",json("ops" to JsonArray(listOf(json("kpId" to "K","title" to "Synthetic")))))
            store.registerQuestion("original","C","","SINGLE_CHOICE","Original","VERIFIED","o1",options,listOf("K"))
            store.poseChoiceGroup("C","old",listOf(json("attemptId" to "old","questionId" to "original","kpIds" to listOf("K"),
                "purpose" to "PRACTICE","policyVersion" to 1,"mainline" to true,"posedAt" to clock)))
            store.append("C","answer_submitted",json("attemptId" to "old","answer" to "A"))
            store.append("C","attempt_graded",json("attemptId" to "old","score" to 0,"max" to 1,"status" to "EXACT"))
            store.registerQuestion("variant","C","","SINGLE_CHOICE","Actual frozen card","VERIFIED","o1",options,listOf("K"))
            clock += 86_400_000
            copyEvents()
            item = reviewBoard("C",view(),logicalDay(clock,"Europe/London"), VariantCandidatePort { c,_,kp -> store.reviewVariantCandidates(c,kp) }).items.single { it.variantAttemptId != null }
            target = assertNotNull(prepareReviewPractice(sync,"C",null,item,logicalDay(clock,"Europe/London")))
        }
        fun copyEvents() { store.events("C").forEach { db.ledgerQueries.putEvent(it.seq,"C",LedgerJson.encodeToString(Event.serializer(),it)) } }
        fun view() = CourseView(sync.localEvents("C"),project(sync.localEvents("C")))
        fun pose(extra: JsonObject = json()): Event {
            val p = store.poseChoiceGroup("C","chosen",listOf(JsonObject(json("attemptId" to target.attemptId,
                "questionId" to target.question.id,"kpIds" to listOf("K"),"purpose" to "RECALL","policyVersion" to 1,"review" to true) + extra)),
                device = "phone", thread = "review:C:K").single()
            copyEvents()
            return p
        }
        fun restore() = restoreReviewPracticeSession(sync,"C","K",null,view(),clock)
        override fun close() { store.close();driver.close() }
    }

    @Test fun actualLedgerStorePoseNeedsNoInventedAvailabilityField() = Fixture().use { f ->
        val actual = f.pose()
        assertFalse("availability" in actual.payload, "The real producer, without fixture-injected status")
        val ready = assertIs<ReviewPracticeSession.Ready>(f.restore())
        assertEquals("Actual frozen card",ready.group.cards.single().stem)
        assertEquals(f.target.attemptId,ready.group.cards.single().attemptId)
    }

    @Test fun validAgentSelectionCannotOverwriteTheLearnerCursorOrTarget() = Fixture().use { f ->
        val day = logicalDay(f.clock,"Europe/London")
        val agentItem = f.item.copy(agentRunId="R1",entryId="agent-entry",variantAttemptId="agent-attempt",variantQuestion=QueueQuestion("agent-q",null,"fp"))
        val agent = assertNotNull(prepareReviewPractice(f.sync,"C","R1",agentItem,day))
        assertEquals(f.target,reviewPracticeTarget(f.sync,"C"))
        assertEquals(agent,reviewPracticeTarget(f.sync,"C","R1"))
        assertNull(reviewPracticeTarget(f.sync,"C","R2"))
        assertEquals(f.target.attemptId,reviewCursorAttempt(f.sync,"C"))
    }

    @Test fun presentButUnparseableExpiryIsAlwaysRejectedAndAnswersRemainIntact() {
        for (expiry in listOf(JsonPrimitive("soon"),JsonNull,JsonPrimitive(true),json("bad" to 1))) Fixture().use { f ->
            f.pose(json("availability" to "VALID","expiresAt" to expiry))
            val before = f.sync.localEvents("C")
            assertEquals(ReviewPracticeSession.Rejected(ReviewPracticeFailure.UNAVAILABLE),f.restore())
            assertEquals(before,f.sync.localEvents("C"))
            assertEquals("A",before.single { it.type == "answer_submitted" }.payload.str("answer"))
        }
    }
}
