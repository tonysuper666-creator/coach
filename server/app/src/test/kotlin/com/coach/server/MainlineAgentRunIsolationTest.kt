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
import java.net.http.*
import java.nio.file.Files
import kotlin.test.*

class MainlineAgentRunIsolationTest {
    @Test fun isolatedAnswersSurviveRestartAndRejectInvalidOrigins() = runBlocking {
        val dir = Files.createTempDirectory("mainline-run-isolation")
        val root = Files.createDirectories(dir.resolve("papers/mcq-mainline"))
        val question = json("id" to "Q1", "type" to "mcq", "kp_week" to 1, "kp" to "K1", "key_state" to "VERIFIED",
            "fact_ids" to listOf("F1"), "tier" to "setup", "marks" to 1, "stem" to "Synthetic question?",
            "options" to listOf("Wrong", "Right"), "answer" to 1, "explanation" to "正确：Right", "source" to "Synthetic")
        Files.writeString(root.resolve("W01.json"), json("id" to "mainline-W01", "kind" to "pack", "course" to "C1", "minutes" to 10,
            "sections" to JsonArray(listOf(json("title" to "Week 1", "instructions" to "", "choose" to null, "items" to JsonArray(listOf(question)))))).toString())
        val db = Files.createDirectories(dir.resolve("db"))
        val now = 1_800_000_000_000L
        var expectedHome: JsonObject? = null
        var expectedWeek: JsonObject? = null
        var expectedProjection: Projection? = null
        try {
            repeat(2) { restart ->
                val store = LedgerStore(db) { now }
                if (restart == 0) {
                    store.enroll("phone", "synthetic-private-token-32-characters")
                    store.append("C1", "outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "K1", "title" to "K1", "week" to 1)))))
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val actor = LedgerActor(scope, store)
                val mainline = Mainline(MockLibrary(dir.resolve("papers")), clock = { now })
                val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { mainlineRoutes(actor, MutableSharedFlow(extraBufferCapacity = 64), mainline) }
                engine.start(false)
                try {
                    val port = engine.engine.resolvedConnectors().single().port
                    val http = HttpClient.newHttpClient()
                    suspend fun request(path: String, body: JsonObject? = null): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
                        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/courses/C1$path"))
                            .header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                        if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
                        r.statusCode() to LedgerJson.parseToJsonElement(r.body()).jsonObject
                    }
                    val answer = json("itemId" to "Q1", "choice" to 0, "submissionId" to "agent-1", "agentRunId" to "run-A")
                    if (restart == 0) {
                        expectedHome = request("/mainline").second
                        expectedWeek = request("/mainline/1?layout=shuffled-v1").second
                        expectedProjection = store.snapshot("C1").projection
                        assertEquals(200, request("/mainline/1/answer", answer).first)
                    }
                    assertEquals(expectedHome, request("/mainline").second, "learner home after restart=$restart")
                    assertEquals(expectedWeek, request("/mainline/1?layout=shuffled-v1").second, "learner week after restart=$restart")
                    val projection = store.snapshot("C1").projection
                    assertTrue(projection.lastSeq > expectedProjection!!.lastSeq, "audit cursor includes agent events")
                    assertEquals(expectedProjection!!.copy(lastSeq = 0), projection.copy(lastSeq = 0), "learner progress and due list after restart=$restart")
                    assertEquals(1L, request("/mainline/1?agentRunId=run-A").second.getValue("coverage").jsonObject.num("answered"))
                    assertEquals(0L, request("/mainline/1?agentRunId=run-B").second.getValue("coverage").jsonObject.num("answered"))
                    val before = store.events("C1")
                    assertEquals(409, request("/mainline/1/answer", JsonObject(answer - "agentRunId")).first)
                    assertEquals(400, request("/mainline/1/answer", JsonObject(answer + ("agentRunId" to JsonPrimitive("bad run")))).first)
                    assertEquals(before, store.events("C1"))
                } finally { engine.stopSuspend(0, 500); actor.close(); scope.cancel() }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
