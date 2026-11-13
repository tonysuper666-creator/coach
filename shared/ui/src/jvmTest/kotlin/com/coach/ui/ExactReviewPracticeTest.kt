package com.coach.ui

import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.domain.reviewqueue.*
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import com.coach.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ExactReviewPracticeTest {
    private class Mac : AutoCloseable {
        val dir = Files.createTempDirectory("synthetic-exact-review")
        val token = "synthetic-private-token-32-characters"
        var clock = 1_794_052_800_000L - 86_400_000
        val offline = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir.resolve("server").also { Files.createDirectories(it) }) { clock }
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            intercept(ApplicationCallPipeline.Call) { if (offline.get()) { call.respond(HttpStatusCode.ServiceUnavailable); finish() } }
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
        }
        val base: String
        init {
            runBlocking { actor.execute {
                enroll("phone", token); enroll("other", "other-synthetic-private-token-32chars")
                append("C", "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "K", "title" to "Synthetic review")))))
                val options = listOf(Option("o1", "Synthetic one"), Option("o2", "Synthetic two"))
                registerQuestion("original", "C", "", "SINGLE_CHOICE", "Synthetic original", "VERIFIED", "o1", options, listOf("K"))
                val pose = poseChoiceGroup("C", "original-group", listOf(json("attemptId" to "original-attempt", "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1, "mainline" to true, "posedAt" to clock))).single()
                append("C", "answer_submitted", json("attemptId" to "original-attempt", "answer" to "A"))
                append("C", "attempt_graded", json("attemptId" to "original-attempt", "score" to 0, "max" to 1, "status" to "EXACT"))
                // Opposite database/selector order proves the request cannot just take the next KP card.
                registerQuestion("q-z", "C", "", "SINGLE_CHOICE", "Synthetic arbitrary fallback", "VERIFIED", "o1", options, listOf("K"))
                registerQuestion("q-a", "C", "", "SINGLE_CHOICE", "Synthetic selected exact card", "VERIFIED", "o1", options, listOf("K"))
                registerQuestion("q-unchecked", "C", "", "SINGLE_CHOICE", "Synthetic unchecked", "UNREVIEWED", "o1", options, listOf("K"))
            } }
            clock += 86_400_000
            server.start(false)
            base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
        }
        fun target(): JsonObject = runBlocking { actor.execute {
            val day = logicalDay(clock, "Europe/London")
            val queue = ledgerQueueCandidate("C", events("C"), day).allocateVariants(VariantCandidatePort { c, _, kp -> reviewVariantCandidates(c, kp) }).queue
            val entry = queue.dueEntries(day).first { it.currentAttemptId != null }
            val variant = entry.variants.single { it.attemptId == entry.currentAttemptId }
            json("courseId" to "C", "deviceId" to "phone", "agentRunId" to null, "kpId" to "K", "entryId" to entry.id,
                "attemptId" to variant.attemptId, "question" to json("id" to variant.question.id, "version" to variant.question.version, "fingerprint" to variant.question.fingerprint))
        } }
        fun post(target: JsonObject, device: String = "phone", credential: String = token, course: String = "C"): HttpResponse<String> =
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("$base/v1/courses/$course/review-practice"))
                .header("Authorization", "Bearer $credential").header("X-Coach-Device", device).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(target.toString())).build(), HttpResponse.BodyHandlers.ofString())
        override fun close() { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }
    }

    private class Device(val mac: Mac, create: Boolean = true, connect: Boolean = true, loseFirstAck: Boolean = false) : AutoCloseable {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${mac.dir.resolve("client.sqlite")}")
        init { if (create) ClientDatabase.Schema.create(driver) }
        val transport = HttpLedgerTransport(mac.base, "phone", mac.token)
        /** Set once the first command really reached the server, before its acknowledgement was dropped. */
        val firstSendCommitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val ledger = if (!loseFirstAck) transport else object : LedgerTransport by transport {
            var lost = false
            override suspend fun command(command: com.coach.protocol.WireCommand): com.coach.protocol.Receipt {
                val receipt = transport.command(command)
                if (!lost) {
                    lost = true
                    firstSendCommitted.set(true)
                    throw java.io.IOException("Synthetic lost acknowledgement after real commit")
                }
                return receipt
            }
        }
        val sync = SyncEngine(ClientDatabase(driver), "phone", ledger, listOf("C"), { UUID.randomUUID().toString() }, { bytes -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
        val course = CourseHttp(mac.base, "phone", mac.token, "C")
        val tutor = TutorClient(mac.base, "phone", mac.token, TutorModel({ mac.clock }))
        val controller = CoachController("C", sync, tutor, formatClock = { "12:00" }, nowMillis = { mac.clock }, course = course)
        init { if (connect) runBlocking { sync.syncOnce() } }
        override fun close() { controller.background.cancel(); course.close(); tutor.close(); transport.close(); driver.close() }
    }

    @Test fun typedReviewEntryLoadsSelectedQuestionSubmitsAcknowledgesAndRestartsOnSameCard() = Mac().use { mac ->
        var attempt = ""
        Device(mac).use { device ->
            runComposeUiTest {
                var practice by mutableStateOf(false)
                val facts = device.sync.localEvents("C")
                val view = CourseView(facts, device.sync.projection("C"))
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    if (practice) ReviewPracticeScreen(device.controller) { practice = false }
                    else ReviewScreen(device.controller, view, { _, _ -> fail("No generic KP fallback") }, onBack = null,
                        onOpenPractice = { practice = true })
                } } } }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("已备 1 道变式", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("开始").onFirst().performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) {
                    attempt = reviewCursorAttempt(device.sync, "C").orEmpty()
                    attempt.isNotEmpty() && onAllNodesWithTag("study-stem-$attempt").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Synthetic selected exact card").assertIsDisplayed()
                onNodeWithText("Synthetic arbitrary fallback").assertDoesNotExist()
                onNodeWithTag("study-option-$attempt-A").performClick()
                onNodeWithTag("review-submit").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("作答已确认。").fetchSemanticsNodes().isNotEmpty() }
            }
            val facts = runBlocking { mac.actor.execute { events("C") } }
            assertEquals("q-a", facts.single { it.type == "question_posed" && it.payload.str("attemptId") == attempt }.payload.str("questionId"))
            assertEquals(1, facts.count { it.type == "answer_submitted" && it.payload.str("attemptId") == attempt })
            assertEquals(1, facts.count { it.type == "attempt_graded" && it.payload.str("attemptId") == attempt })
        }
        mac.offline.set(true)
        Device(mac, create = false, connect = false).use { restarted ->
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    ReviewPracticeScreen(restarted.controller) { }
                } } } }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("study-stem-$attempt").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Synthetic selected exact card").assertIsDisplayed()
                onNodeWithText("作答已确认。").assertIsDisplayed()
                onNodeWithTag("review-submit").assertDoesNotExist()
            }
            assertEquals(attempt, reviewCursorAttempt(restarted.sync, "C"))
        }
    }

    @Test fun exactAllocationIsAtomicIdempotentAndDoesNotReturnPrivateAnswers() = Mac().use { mac ->
        val request = mac.target()
        val first = mac.post(JsonObject(request + json("stem" to "client-forged-stem", "correctOptionId" to "client-forged-key")))
        assertEquals(200, first.statusCode())
        val event = LedgerJson.decodeFromJsonElement(Event.serializer(), Json.parseToJsonElement(first.body()).jsonObject.getValue("events").jsonArray.single())
        assertEquals("q-a", event.payload.str("questionId"))
        assertEquals("Synthetic selected exact card", event.payload.str("stem"))
        assertFalse(first.body().contains("correctOptionId"))
        assertFalse(first.body().contains("client-forged"))
        assertEquals(first.body(), mac.post(request).body())
        assertEquals(1, runBlocking { mac.actor.execute { events("C").count { it.type == "question_posed" && it.payload.str("attemptId") == request.str("attemptId") } } })
        assertEquals(409, mac.post(JsonObject(request + json("entryId" to "different-entry"))).statusCode())
        assertEquals(403, mac.post(request, "other", "other-synthetic-private-token-32chars").statusCode())
        assertEquals(401, mac.post(request, credential = "wrong-token").statusCode())
    }

    @Test fun lostAckThenOfflineDatabaseRestartReplaysOneImmutableAnswerAndGetsDuplicateAck() = Mac().use { mac ->
        var attempt = ""
        var command = ""
        Device(mac, loseFirstAck = true).use { device ->
            val facts = device.sync.localEvents("C")
            val view = CourseView(facts, device.sync.projection("C"))
            runComposeUiTest {
                var practice by mutableStateOf(false)
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 880.dp)) {
                    if (practice) ReviewPracticeScreen(device.controller) { practice = false }
                    else ReviewScreen(device.controller,view,{_,_-> fail("No KP fallback")},null,onOpenPractice={practice=true})
                } } } }
                waitUntil(timeoutMillis=15_000) { onAllNodesWithText("已备 1 道变式",substring=true).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText("开始").onFirst().performScrollTo().performClick()
                waitUntil(timeoutMillis=15_000) {
                    attempt=reviewCursorAttempt(device.sync,"C").orEmpty()
                    attempt.isNotEmpty() && onAllNodesWithTag("study-stem-$attempt").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("study-option-$attempt-A").performClick()
                onNodeWithTag("review-submit").performClick()
                waitUntil(timeoutMillis=15_000) { onAllNodesWithText("重试同步").fetchSemanticsNodes().isNotEmpty() }
                // 重试入口只证明本机已留下命令；首次发送可能还在路上。等到服务端真的落库，
                // 重启后的重放才必然落在同一条 command id 上，而不是一次全新的提交。
                waitUntil(timeoutMillis=15_000) { device.firstSendCommitted.get() }
            }
            command=device.sync.mainlineState("C","review-answer:$attempt").str("commandId")
            assertTrue(command.isNotBlank())
            assertNull(device.sync.receipt(command))
            mac.offline.set(true)
        }
        Device(mac,create=false,connect=false).use { restarted ->
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(420.dp,880.dp)) { ReviewPracticeScreen(restarted.controller) {} } } } }
                waitUntil(timeoutMillis=15_000) { onAllNodesWithText("重试同步").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Synthetic selected exact card").assertIsDisplayed()
                onNodeWithTag("review-submit").assertDoesNotExist()
                mac.offline.set(false)
                onNodeWithText("重试同步").performClick()
                waitUntil(timeoutMillis=15_000) { onAllNodesWithText("作答已确认。").fetchSemanticsNodes().isNotEmpty() }
            }
            assertEquals(command,restarted.sync.mainlineState("C","review-answer:$attempt").str("commandId"))
            assertEquals("DUPLICATE",restarted.sync.receipt(command)?.status)
            assertEquals(1,restarted.sync.retainedCommands().size)
        }
        val facts=runBlocking { mac.actor.execute { events("C") } }
        assertEquals(1,facts.count { it.type=="question_posed" && it.payload.str("attemptId")==attempt })
        assertEquals(1,facts.count { it.type=="answer_submitted" && it.payload.str("attemptId")==attempt })
        assertEquals(1,facts.count { it.type=="attempt_graded" && it.payload.str("attemptId")==attempt })
        assertTrue(facts.none { it.type=="answer_orphaned" })
    }

    @Test fun unavailableChangedUnverifiedAndCrossScopeRequestsNeverPoseAnyQuestion() = Mac().use { mac ->
        val request = mac.target()
        val before = runBlocking { mac.actor.execute { events("C").size } }
        val q = request.getValue("question").jsonObject
        for (changed in listOf(JsonObject(q + json("id" to "missing")), JsonObject(q + json("id" to "q-unchecked")),
            JsonObject(q + json("fingerprint" to "changed")), JsonObject(q + json("version" to "invented-version")))) {
            assertEquals(409, mac.post(JsonObject(request + json("question" to changed))).statusCode())
        }
        assertEquals(409, mac.post(JsonObject(request + json("kpId" to "other-kp"))).statusCode())
        assertEquals(403, mac.post(JsonObject(request + json("agentRunId" to "foreign-run"))).statusCode())
        assertEquals(403, mac.post(request, course = "other-course").statusCode())
        runBlocking { mac.actor.execute { append("C", "question_key_changed", json("questionId" to "q-a", "keyState" to "REJECTED")) } }
        assertEquals(409, mac.post(request).statusCode())
        assertEquals(before + 1, runBlocking { mac.actor.execute { events("C").size } }, "Only the explicit withdrawal fact was appended")
    }

    @Test fun concurrentRequestsCannotConsumeTheSameQuestionTwice() = Mac().use { mac ->
        val target = mac.target()
        val http = HttpClient.newHttpClient()
        fun send(body: JsonObject) = http.sendAsync(HttpRequest.newBuilder(URI("${mac.base}/v1/courses/C/review-practice"))
            .header("Authorization","Bearer ${mac.token}").header("X-Coach-Device","phone").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString())
        val first=send(target)
        val second=send(JsonObject(target + json("attemptId" to "another-racing-attempt")))
        assertEquals(listOf(200,409),listOf(first.get().statusCode(),second.get().statusCode()).sorted())
        assertEquals(1,runBlocking { mac.actor.execute { events("C").count { it.type=="question_posed" && it.payload.str("questionId")=="q-a" } } })
    }

    @Test fun exactAnswerRejectsOtherDevicesRunsTamperedSnapshotsAndWithdrawnKeys() = Mac().use { mac ->
        val target=mac.target()
        val response=mac.post(target)
        assertEquals(200,response.statusCode())
        val pose=LedgerJson.decodeFromJsonElement(Event.serializer(),Json.parseToJsonElement(response.body()).jsonObject.getValue("events").jsonArray.single())
        fun submit(device:String="phone",run:String?=null,snapshot:JsonObject=pose.payload):Receipt = runBlocking {
            val body=CommandBody("submit_answer","C",mac.clock,json("attemptId" to target.str("attemptId"),"answer" to "A","posedSnapshot" to snapshot,"agentRunId" to run))
            val bytes=canonicalJson(LedgerJson.encodeToJsonElement(CommandBody.serializer(),body))
            mac.actor.execute { command(WireCommand(device,UUID.randomUUID().toString(),bytes,com.coach.store.sha256(bytes.toByteArray()))) }
        }
        assertEquals("REJECTED",submit(device="other").status)
        assertEquals("REJECTED",submit(run="foreign-run").status)
        assertEquals("REJECTED",submit(snapshot=JsonObject(pose.payload + json("stem" to "tampered"))).status)
        runBlocking { mac.actor.execute { append("C","question_key_changed",json("questionId" to "q-a","keyState" to "REJECTED")) } }
        assertEquals("REJECTED",submit().status)
        val facts=runBlocking { mac.actor.execute { events("C") } }
        assertTrue(facts.none { it.type in setOf("answer_submitted","attempt_graded") && it.payload.str("attemptId")==target.str("attemptId") })
    }
}
