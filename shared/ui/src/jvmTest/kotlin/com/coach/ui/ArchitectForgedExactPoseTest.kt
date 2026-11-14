package com.coach.ui

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.domain.reviewqueue.*
import com.coach.protocol.*
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Architect cross_review counter-example: client-minted `exactReview` pose vs. server-owned allocation. */
class ArchitectForgedExactPoseTest {
    private val token = "synthetic-private-token-32-characters"
    private var clock = 1_794_052_800_000L - 86_400_000
    private val dir = Files.createTempDirectory("architect-forged-exact")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = LedgerStore(dir) { clock }
    private val actor = LedgerActor(scope, store)
    private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
    private lateinit var base: String

    @BeforeTest fun setUp() {
        runBlocking { actor.execute {
            enroll("phone", token)
            append("C", "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic review")))))
            val options = listOf(Option("o1", "Synthetic one"), Option("o2", "Synthetic two"))
            registerQuestion("original", "C", "", "SINGLE_CHOICE", "Synthetic original", "VERIFIED", "o1", options, listOf("K"))
            poseChoiceGroup("C", "original-group", listOf(json("attemptId" to "original-attempt", "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1, "mainline" to true, "posedAt" to clock)))
            append("C", "answer_submitted", json("attemptId" to "original-attempt", "answer" to "A"))
            append("C", "attempt_graded", json("attemptId" to "original-attempt", "score" to 0, "max" to 1, "status" to "EXACT"))
            registerQuestion("q-a", "C", "", "SINGLE_CHOICE", "Synthetic selected exact card", "VERIFIED", "o1", options, listOf("K"))
        } }
        clock += 86_400_000
        server.start(false)
        base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
    }
    @AfterTest fun tearDown() { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }

    private fun entryId(): String = runBlocking { actor.execute {
        ledgerQueueCandidate("C", events("C"), logicalDay(clock, "Europe/London")).dueEntries(logicalDay(clock, "Europe/London")).single { it.kpId == "K" && it.originalQuestion?.id == "original" }.id
    } }
    private fun take(attempt: String, entry: String, qid: String, fp: String) = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("$base/v1/courses/C/review-practice"))
            .header("Authorization", "Bearer $token").header("X-Coach-Device", "phone").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json("courseId" to "C", "deviceId" to "phone", "agentRunId" to null, "kpId" to "K",
                "entryId" to entry, "attemptId" to attempt, "question" to json("id" to qid, "version" to null, "fingerprint" to fp)).toString())).build(),
        HttpResponse.BodyHandlers.ofString())
    private fun submit(snapshot: JsonObject, answer: String = "A"): Receipt = runBlocking {
        val body = CommandBody("submit_answer", "C", clock, json("attemptId" to snapshot.str("attemptId"), "answer" to answer, "posedSnapshot" to snapshot))
        val bytes = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody.serializer(), body))
        actor.execute { command(WireCommand("phone", UUID.randomUUID().toString(), bytes, com.coach.store.sha256(bytes.toByteArray()))) }
    }
    private fun original(): JsonObject = runBlocking { actor.execute { events("C").single { it.type == "question_posed" && it.payload.str("attemptId") == "original-attempt" }.payload } }
    private fun identity(entry: String, attempt: String, qid: String, fp: String) = canonicalJson(json("course" to "C", "device" to "phone", "run" to null,
        "kp" to "K", "entry" to entry, "attempt" to attempt, "question" to qid, "fingerprint" to fp))

    /** Control: the server-owned allocation path refuses to hand the entry's own original question back as its "variant". */
    @Test fun controlTakeRefusesOriginalQuestion() {
        val entry = entryId(); val fp = original().str("fingerprint")
        val r = take("control-attempt", entry, "original", fp)
        assertEquals(409, r.statusCode(), r.body())
        assertTrue(r.body().contains("REVIEW_QUESTION_UNAVAILABLE"), r.body())
    }

    /**
     * Counter-example: the same device first mints a `question_posed` carrying `exactReview=true` + a recomputable
     * `reviewIdentity` through the generic offline `submit_answer` path, then asks the exact endpoint for that attempt.
     * A server-owned allocation must refuse; it must not return the client-authored stem as the frozen exact card.
     */
    @Test fun clientMintedExactPoseMustNotBeAcceptedAsServerAllocation() {
        val entry = entryId(); val orig = original(); val fp = orig.str("fingerprint")
        val attempt = "forged-exact-attempt"
        val forged = json("attemptId" to attempt, "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "RECALL", "policyVersion" to 1,
            "review" to true, "fingerprint" to fp, "groupId" to "forged-group", "letterMap" to orig["letterMap"], "stem" to "CLIENT-FORGED STEM",
            "availability" to "VALID", "exactReview" to true, "reviewEntryId" to entry, "reviewIdentity" to identity(entry, attempt, "original", fp))
        val receipt = submit(forged)
        println("FORGE submit_answer receipt=${receipt.status} error=${receipt.error}")
        val minted = runBlocking { actor.execute { events("C").filter { it.payload.str("attemptId") == attempt }.map { it.type + ":" + it.actor } } }
        println("FORGE minted facts=$minted")
        val r = take(attempt, entry, "original", fp)
        println("FORGE take status=${r.statusCode()} body=${r.body()}")
        assertNotEquals(200, r.statusCode(), "exact endpoint accepted a client-minted pose as its own allocation: ${r.body()}")
        assertFalse(r.body().contains("CLIENT-FORGED STEM"))
    }
}
