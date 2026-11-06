package com.coach.server

import com.coach.ai.GradeGate
import com.coach.ai.TaskParsing
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Invented content only: no course material, real exam text or generated papers belong in the repository. */
private val PAPER = """
{"id":"synthetic-paper","title":"Synthetic mock","kind":"paper","course":"C1","minutes":120,"key_state":"VERIFIED","sections":[
 {"title":"Part I","instructions":"Answer all.","choose":null,"items":[
  {"id":"Q1","type":"mcq","kp_week":1,"kp":"orchard pricing","marks":4,"stem":"A synthetic orchard sells apples. Which option is correct?",
   "options":["Alpha choice","Beta choice","Gamma choice","Delta choice"],"answer":1,"explanation":"SECRET-EXPLANATION-Q1","source":"synthetic"},
  {"id":"Q2","type":"mcq","kp_week":2,"kp":"lantern supply","marks":4,"stem":"A synthetic lantern maker raises output. Which follows?",
   "options":["First lantern","Second lantern","Third lantern","Fourth lantern"],"answer":3,"explanation":"SECRET-EXPLANATION-Q2","source":"synthetic"}]},
 {"title":"Part II","instructions":"Answer ONE.","choose":1,"items":[
  {"id":"E1","type":"essay","kp_week":3,"kp":"canal policy","marks":20,"word_target":300,"stem":"Discuss a synthetic canal policy.",
   "rubric":[{"criterion":"States the mechanism","marks":10,"look_for":"SECRET-LOOKFOR"},{"criterion":"Uses an example","marks":10,"look_for":"any example"}],
   "model_answer":"SECRET-MODEL-E1","source":"synthetic"},
  {"id":"E2","type":"essay","kp_week":3,"kp":"canal policy","marks":20,"stem":"Evaluate a synthetic bridge toll.",
   "rubric":[{"criterion":"Defines the toll","marks":8,"look_for":"definition"},{"criterion":"Weighs costs","marks":12,"look_for":"trade-off"}],
   "model_answer":"SECRET-MODEL-E2","source":"synthetic"}]},
 {"title":"Part III","instructions":"Answer ONE.","choose":1,"items":[
  {"id":"X","type":"analytical","kp_week":4,"kp":"kite market","marks":10,"stem":"A synthetic kite market has two kinds of kites.","figure_svg":"figs/x.svg",
   "parts":[{"label":"a","prompt":"Compute the average price.","marks":4,"answer":"2.50","tolerance":0.005,"rubric":[]},
            {"label":"b","prompt":"Explain and compute the share.","marks":6,"answer":"1.25","tolerance":0.005,
             "rubric":[{"criterion":"Sets up the condition","marks":3,"look_for":"SECRET-PART-B"}]}]},
  {"id":"Y","type":"analytical","kp_week":4,"kp":"kite market","marks":10,"stem":"A synthetic boat market.",
   "parts":[{"label":"a","prompt":"Compute it.","marks":10,"answer":"7","tolerance":0.5,"rubric":[]}]}]}]}
""".trimIndent()

private fun pack() = json("id" to "synthetic-pack", "kind" to "pack", "course" to "C1", "sections" to JsonArray(listOf(json("title" to "Pack",
    "items" to JsonArray((1..8).map { i -> json("id" to "P$i", "type" to "mcq", "kp_week" to (if (i <= 3) 1 else 2), "kp" to "drill topic",
        "marks" to 4, "stem" to "Synthetic drill question number $i?", "options" to JsonArray(listOf("Yes $i", "No $i", "Maybe $i").map(::JsonPrimitive)),
        "answer" to 0, "explanation" to "Synthetic explanation $i") })))))

class MockExamTest {
    private fun library(dir: Path): MockLibrary {
        Files.createDirectories(dir.resolve("figs"))
        Files.writeString(dir.resolve("paper.json"), PAPER)
        Files.writeString(dir.resolve("figs/x.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>")
        Files.writeString(dir.resolve("pack.json"), pack().toString())
        Files.writeString(dir.resolve("broken.json"), json("id" to "broken", "kind" to "paper", "course" to "C1", "sections" to JsonArray(listOf(json("items" to
            JsonArray(listOf(json("id" to "B1", "type" to "mcq", "marks" to 4, "stem" to "Synthetic?", "options" to JsonArray(listOf(JsonPrimitive("Only"))), "answer" to 0))))))).toString())
        Files.writeString(dir.resolve("other-course.json"), PAPER.replace("\"C1\"", "\"OTHER\""))
        return MockLibrary(dir)
    }

    @Test fun fullMockHidesKeysResumesGradesAndRecordsFullMockEvidence() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mock-exam")
        val now = AtomicLong(1_800_000_000_000)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { now.get() }.also { it.enroll("phone", "synthetic-private-token-32-characters") }
        val actor = LedgerActor(scope, store)
        val mocks = MockEngine(library(dir.resolve("papers")), clock = { now.get() })
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, pushes); mockRoutes(actor, pushes, mocks) }
        engine.start(false)
        try {
            val port = engine.engine.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            suspend fun request(path: String, body: JsonObject? = null, authenticated: Boolean = true): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
                val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/courses/C1$path")).timeout(java.time.Duration.ofSeconds(5))
                if (authenticated) b.header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
                r.statusCode() to (runCatching { LedgerJson.parseToJsonElement(r.body()).jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
            }

            assertEquals(401, request("/mocks", authenticated = false).first)
            val home = request("/mocks").second
            val paper = home.getValue("papers").jsonArray.single().jsonObject
            assertEquals("synthetic-paper", paper.str("id"))
            assertEquals(38.0, paper.getValue("max").jsonPrimitive.double, "choose sections count only the chosen number of questions")
            assertTrue(home.getValue("invalid").jsonArray.any { it.jsonObject.str("file") == "broken.json" }, "a malformed paper is reported, not offered")

            val (status, started) = request("/mocks", json("paperId" to "synthetic-paper"))
            assertEquals(200, status)
            val id = started.str("mockId")
            assertEquals(now.get() + 120 * 60_000, started.num("deadline"))
            val sent = started.toString()
            for (secret in listOf("SECRET-EXPLANATION-Q1", "SECRET-MODEL-E1", "SECRET-LOOKFOR", "SECRET-PART-B", "\"answer\":1", "1.25", "rubric", "tolerance"))
                assertFalse(secret in sent, "the exam view must not leak $secret before submission")
            assertTrue("<svg" in sent, "figures travel with the exam")
            assertEquals(409, request("/mocks", json("paperId" to "synthetic-paper")).first, "one exam at a time")

            // Leave mid-exam, then come back: the saved answers return with the remaining time.
            val answers = json("Q1" to json("choice" to 1), "Q2" to json("choice" to 0), "E2" to json("text" to "A bridge toll prices congestion; costs fall on commuters."),
                "X#a" to json("text" to "average", "final" to "2.5"), "X#b" to json("text" to "Indifference holds where values meet.", "final" to "1.30"))
            assertEquals(200, request("/mocks/$id/answers", json("answers" to answers)).first)
            now.addAndGet(30 * 60_000)
            val resumed = request("/mocks/$id").second
            assertEquals("active", resumed.str("status"))
            assertEquals(answers, resumed.getValue("answers"))

            val result = request("/mocks/$id/submit", json("answers" to answers)).second
            assertEquals("submitted", result.str("status"))
            val items = result.getValue("items").jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
            assertEquals(4.0, items.getValue("Q1").getValue("score").jsonPrimitive.double)
            assertEquals(0.0, items.getValue("Q2").getValue("score").jsonPrimitive.double)
            assertTrue("SECRET-EXPLANATION-Q2" in items.getValue("Q2").str("feedback"), "after submission the explanation is shown")
            assertEquals(4.0, items.getValue("X#a").getValue("score").jsonPrimitive.double, "2.5 matches 2.50 within tolerance")
            assertEquals("SKIPPED", items.getValue("E1").str("status"), "the unanswered essay is not the chosen one")
            assertEquals("SKIPPED", items.getValue("Y#a").str("status"))
            assertEquals("PENDING", items.getValue("E2").str("status"))
            assertEquals("SECRET-MODEL-E2", items.getValue("E2").str("reference"))
            assertEquals(8.0, result.getValue("score").jsonPrimitive.double)
            assertEquals(38.0, result.getValue("max").jsonPrimitive.double)
            assertEquals(2, result.num("pending"))

            val events = actor.execute { events("C1") }
            val submitted = events.single { it.type == "mock_submitted" }
            assertEquals("FULL_MOCK", submitted.payload.str("purpose"))
            assertEquals(5, events.count { it.type == "question_posed" && it.payload.str("purpose") == "FULL_MOCK" && it.payload.str("mockId") == id })
            assertEquals(1, events.count { it.type == "mock_started" })

            // Constructed answers go to the existing grading chain and must pass its frozen-rubric and answer checks.
            val claimed = actor.execute { generateSequence { claimGradeWork() }.take(5).toList() }
            assertEquals(setOf("$id-4", "$id-6"), claimed.map { it.subject.str("attempt") }.toSet())
            for (work in claimed) {
                val q = TaskParsing.constructed(JsonArray(listOf(work.subject.getValue("question"))).toString()).single()
                assertEquals(work.fingerprint, GradeGate.rubricFingerprint(q.rubric))
                assertEquals(work.answerHash, sha256(json("answer" to work.subject.str("answer"), "finalAnswer" to work.subject.str("finalAnswer")).toString().toByteArray()))
            }
            val partB = claimed.single { it.subject.str("attempt") == "$id-6" }
            assertEquals("1.25", partB.subject.getValue("question").jsonObject.getValue("numeric_final").jsonObject.str("expected"))
            val essay = claimed.single { it.subject.str("attempt") == "$id-4" }
            assertTrue(actor.execute { persistGradeDraft(essay, json("status" to "AI_UNREVIEWED", "score" to 1400, "max" to 2000,
                "items" to JsonArray(listOf(json("id" to "R1", "points" to 6, "reason" to "Defines it briefly."), json("id" to "R2", "points" to 8, "reason" to "Weighs one cost.")))),
                "claude-opus-5-5") })

            val again = request("/mocks/$id/submit", json("answers" to JsonObject(emptyMap()))).second
            val e2 = again.getValue("items").jsonArray.map { it.jsonObject }.single { it.str("key") == "E2" }
            assertEquals("DRAFT", e2.str("status"))
            assertEquals(listOf("Defines the toll", "Weighs costs"), e2.getValue("reasons").jsonArray.map { it.jsonObject.str("criterion") })
            assertEquals(22.0, again.getValue("score").jsonPrimitive.double)
            assertEquals(1, actor.execute { events("C1") }.count { it.type == "mock_submitted" }, "a second submit cannot grade twice")
            assertTrue(again.getValue("lostByKp").jsonArray.any { it.jsonObject.str("kp") == "lantern supply" })
            assertTrue(request("/mocks").second.getValue("history").jsonArray.any { it.jsonObject.str("mockId") == id })
        } finally { engine.stopSuspend(0, 500); actor.close(); scope.cancel(); dir.toFile().deleteRecursively() }
    }

    @Test fun drillDrawsSevenChoicesAndClosesItselfAtTheDeadline() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mock-drill")
        val now = AtomicLong(1_800_000_000_000)
        val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { now.get() }
        val mocks = MockEngine(library(dir.resolve("papers")), clock = { now.get() })
        try {
            val byWeek = mocks.start(store, "C1", json("drill" to json("week" to 1)))
            val weekItems = byWeek.getValue("paper").jsonObject.getValue("sections").jsonArray.single().jsonObject.getValue("items").jsonArray
            assertEquals(3, weekItems.size, "a week with three choices gives three, never repeats")
            assertEquals(14 * 60_000L, byWeek.num("deadline") - now.get())
            mocks.submit(store, "C1", byWeek.str("mockId"), JsonObject(emptyMap()), false)

            val weighted = mocks.start(store, "C1", json("drill" to JsonObject(emptyMap())))
            val keys = weighted.getValue("paper").jsonObject.getValue("sections").jsonArray.single().jsonObject.getValue("items").jsonArray.map { it.jsonObject.str("id") }
            assertEquals(7, keys.distinct().size)
            val id = weighted.str("mockId")
            mocks.save(store, "C1", id, json(keys.first() to json("choice" to 0)))
            now.addAndGet(15 * 60_000)
            assertEquals(setOf("C1"), mocks.sweep(store))
            val result = mocks.get(store, "C1", id)
            assertEquals("submitted", result.str("status"))
            assertTrue(result.flag("auto"))
            assertEquals(4.0, result.getValue("score").jsonPrimitive.double, "the one saved answer still counts after the automatic close")
            assertEquals(28.0, result.getValue("max").jsonPrimitive.double)
            assertEquals("TIMED", store.events("C1").last { it.type == "mock_submitted" }.payload.str("purpose"))
        } finally { store.close(); dir.toFile().deleteRecursively() }
    }
}
