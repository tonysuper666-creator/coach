package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class MainlineEssayRoutesTest {
    @Test fun authenticatedReaderBindsCourseSubmissionVersionAndResourcesAndRejectsRevocation() = runBlocking<Unit> {
        val root = Files.createTempDirectory("synthetic-essay-http")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createDirectories(root.resolve("db")))
        val token = "synthetic-essay-device-token-32-characters"
        store.enroll("phone", token)
        val actor = LedgerActor(scope, store)
        val raw = LedgerJson.parseToJsonElement(javaClass.getResource("/question-ladder/synthetic-runtime-identity-v1.json")!!.readText()).jsonObject
        val runtimes = ConcurrentHashMap<String, SyntheticLadderRuntime>()
        runtimes["SYN-COURSE-A"] = SyntheticLadderRuntime.parse(raw)
        val second = JsonObject(raw + ("course_id" to JsonPrimitive("SYN-COURSE-B")))
        runtimes["SYN-COURSE-B"] = SyntheticLadderRuntime.parse(second)
        val mainline = Mainline(MockLibrary(Files.createDirectories(root.resolve("papers"))),
            syntheticCapabilities = setOf("SYN-add"), syntheticRuntimeProvider = runtimes::get)
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, pushes); mainlineRoutes(actor, pushes, mainline) }
        engine.start(false)
        try {
            val base = "http://127.0.0.1:${engine.engine.resolvedConnectors().single().port}"
            val client = HttpClient.newHttpClient()
            suspend fun request(course: String, action: String, input: JsonObject? = null, auth: Boolean = true): HttpResponse<String> = withContext(Dispatchers.IO) {
                val builder = HttpRequest.newBuilder(URI("$base/v1/courses/$course/mainline/1$action"))
                    .timeout(java.time.Duration.ofSeconds(5))
                if (auth) builder.header("X-Coach-Device", "phone").header("Authorization", "Bearer $token")
                if (input != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(input.toString()))
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }
            val identity = MainlineEssayIdentity("SYN-Q1@v2", "synthetic-submitted", "SYN-ANSWER-2", listOf("SYN-RUBRIC-2A", "SYN-RUBRIC-2B"))
            fun body(value: MainlineEssayIdentity = identity) = LedgerJson.encodeToJsonElement(value).jsonObject
            assertEquals(401, request("SYN-COURSE-A", "/essay", body(), auth = false).statusCode())
            assertEquals(401, request("SYN-COURSE-A", "/essay?token=synthetic", body()).statusCode())
            assertEquals(409, request("SYN-COURSE-A", "/essay", JsonObject(body() + ("synthetic_submitted" to JsonPrimitive(true)))).statusCode())
            val feed = request("SYN-COURSE-A", "?learning=v1")
            assertEquals(200, feed.statusCode()); assertFalse(feed.body().contains("Two objects form a pair."))
            val context = LedgerJson.decodeFromJsonElement<LadderRuntimeContext>(LedgerJson.parseToJsonElement(feed.body()).jsonObject["runtime"]!!)
            assertEquals(4, context.scopePoints!!.size); assertEquals(5, context.capabilityScope!!.size)
            assertEquals("candidate_missing", context.scopePoints!!.last().availability)
            val wire = json("itemId" to "SYN-Q1@v2", "choice" to 1, "submissionId" to "submitted", "learning" to true)
            val receipt = request("SYN-COURSE-A", "/answer", wire)
            assertEquals(200, receipt.statusCode())
            val answer = LedgerJson.parseToJsonElement(receipt.body()).jsonObject
            assertEquals(identity.attemptId, answer.str("attemptId")); assertFalse(answer.flag("independentCorrect"))
            assertEquals(listOf("SYN-Q1@v1"), answer["essay"]!!.jsonObject.strings("recorded_linked_question_refs"))
            assertEquals(listOf("SYN-Q1@v2"), answer["essay"]!!.jsonObject.strings("resolved_linked_question_refs"))
            val replay = request("SYN-COURSE-A", "/answer", JsonObject(wire + ("choice" to JsonPrimitive(0))))
            assertEquals(answer, LedgerJson.parseToJsonElement(replay.body()).jsonObject)
            val shown = request("SYN-COURSE-A", "/essay", body())
            assertEquals(200, shown.statusCode()); assertEquals("no-store", shown.headers().firstValue("cache-control").orElse(""))
            val document = LedgerJson.decodeFromString<MainlineEssayDocument>(shown.body())
            assertTrue(document.matches("SYN-COURSE-A", identity)); assertTrue(document.syntheticOnly); assertFalse(document.masteryEvidence)
            assertEquals("Two objects form a pair.", document.modelAnswer)
            assertEquals(listOf("States the number.", "Explains the grouping."), document.rubricPoints.map { it.text })
            for (wrong in listOf(identity.copy(itemId = "SYN-Q1@v1"), identity.copy(attemptId = "synthetic-forged"),
                identity.copy(modelAnswerId = "another-resource"), identity.copy(rubricPointIds = identity.rubricPointIds.reversed()))) {
                val denied = request("SYN-COURSE-A", "/essay", body(wrong))
                assertEquals(409, denied.statusCode()); assertFalse(denied.body().contains(document.modelAnswer))
            }
            assertEquals(409, request("SYN-COURSE-B", "/essay", body()).statusCode())
            assertEquals(409, request("unrelated-course", "/essay", body()).statusCode())
            assertEquals(200, request("SYN-COURSE-B", "/answer", wire).statusCode())
            val other = LedgerJson.decodeFromString<MainlineEssayDocument>(request("SYN-COURSE-B", "/essay", body()).body())
            assertTrue(other.matches("SYN-COURSE-B", identity)); assertFalse(other.matches("SYN-COURSE-A", identity))
            fun changedModel(value: JsonElement): SyntheticLadderRuntime {
                val rows = raw["questions"]!!.jsonArray.map { q -> if (q.jsonObject.str("id") == "SYN-Q2") JsonObject(q.jsonObject + ("model_answer" to value)) else q }
                return SyntheticLadderRuntime.parse(JsonObject(raw + ("questions" to JsonArray(rows))))
            }
            runtimes["SYN-COURSE-A"] = changedModel(JsonNull)
            assertEquals(503, request("SYN-COURSE-A", "/essay", body()).statusCode())
            runtimes["SYN-COURSE-A"] = changedModel(JsonPrimitive("An edited answer under the same version."))
            assertEquals(409, request("SYN-COURSE-A", "/essay", body()).statusCode())
            runtimes.remove("SYN-COURSE-A")
            assertEquals(403, request("SYN-COURSE-A", "/essay", body()).statusCode())
            runtimes["SYN-COURSE-A"] = SyntheticLadderRuntime.parse(raw)
            actor.execute { revokeDevice("phone") }
            assertEquals(401, request("SYN-COURSE-A", "/essay", body()).statusCode())
            assertTrue(actor.execute { events("SYN-COURSE-A").isEmpty() && events("SYN-COURSE-B").isEmpty() })
        } finally {
            engine.stop(0, 500); actor.close(); scope.cancel(); root.toFile().deleteRecursively()
        }
    }
}
