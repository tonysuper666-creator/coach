package com.coach.server

import com.coach.domain.*
import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.ai.ConstructedGradeWorker
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Synthetic questions only. Exercise the same adapter that serves a timed full mock. */
class AnalyticalMockTest {
    private fun checkPart(expected: String, tolerance: String, submitted: String, status: String, points: Double) =
        withPaper(expected, tolerance) { mocks, store, id, _ ->
            val result = mocks.submit(store, "SYNTH", id, json("A#a" to json("text" to "Synthetic working", "final" to submitted)), false)
            val part = result.getValue("items").jsonArray.first().jsonObject
            assertEquals(points, part.getValue("score").jsonPrimitive.double)
            assertEquals(status, part.getValue("numericFinal").jsonObject.str("status"))
            assertEquals(tolerance, part.getValue("numericFinal").jsonObject.getValue("absTolerance").jsonPrimitive.content)
            assertTrue(part.str("feedback").isNotBlank())
        }

    @Test fun exactInclusiveBoundaryAbove() = checkPart("1.005", "0.005", "1.00", "CORRECT", 4.0)
    @Test fun exactInclusiveBoundaryBelow() = checkPart("0.995", "0.005", "1.00", "CORRECT", 4.0)
    @Test fun justBeyondBoundary() = checkPart("1.005000000000000000001", "0.005", "1.00", "INCORRECT", 0.0)
    @Test fun zeroToleranceIsNotInflated() = checkPart("1.000000001", "0", "1.00", "INCORRECT", 0.0)
    @Test fun oneDecimalPlaceIsRejected() = checkPart("1.00", "0.005", "1.0", "WRONG_DECIMAL_PLACES", 0.0)
    @Test fun nonNumberIsRejected() = checkPart("1.00", "0.005", "NaN", "NOT_NUMERIC", 0.0)
    @Test fun missingFinalDoesNotFallBackToWorking() = checkPart("1.00", "0.005", "", "MISSING", 0.0)

    @Test fun standardFigureOnlyAppearsAfterSubmissionAndSurvivesReopening() = withPaper("1.00", "0.005") { mocks, store, id, dir ->
        assertFalse("<svg" in mocks.get(store, "SYNTH", id).toString())
        // The diagram and key are frozen at start; subsequent file edits must not alter the result.
        Files.writeString(dir.resolve("fig.svg"), "<svg>changed</svg>")
        val answers = json("A#a" to json("final" to "1.00"))
        val result = mocks.submit(store, "SYNTH", id, answers, false)
        val parts = result.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(4.0, result.getValue("score").jsonPrimitive.double)
        assertEquals(10.0, result.getValue("max").jsonPrimitive.double)
        assertEquals(0.0, parts[1].getValue("score").jsonPrimitive.double, "blank part cannot erase the answered part")
        assertEquals("MISSING", parts[1].getValue("numericFinal").jsonObject.str("status"))
        assertTrue("frozen-standard" in parts[0].str("standardFigure"))
        store.close()
        LedgerStore(dir.resolve("db")).use { reopened ->
            assertEquals(result, MockEngine(MockLibrary(dir)).get(reopened, "SYNTH", id))
            val events = reopened.events("SYNTH")
            assertEquals(1, events.count { it.type == "mock_submitted" })
            assertEquals(2, events.count { it.type == "attempt_graded" })
            assertEquals(400L, events.filter { it.type == "attempt_graded" }.sumOf { it.payload.num("score") })
            assertTrue(events.filter { it.type == "question_posed" }.all { it.payload.str("purpose") == "FULL_MOCK" })
        }
    }

    @Test fun constructedPartUsesExistingWorkerAndPreservesExactKey() = withPaper("1.005000000000000000001", "0.005000000000000000000") { mocks, store, id, _ ->
        val result = mocks.submit(store, "SYNTH", id, json("A#b" to json("text" to "Sets up the synthetic equality", "final" to "1.0")), false)
        assertEquals(1L, result.num("pending"))
        // Run the real worker and cross-vendor review through local HTTP; do not inject grade facts.
        runBlocking {
            val actor = LedgerActor(this, store)
            val requests = CopyOnWriteArrayList<JsonObject>()
            val bridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            bridge.createContext("/v1/chat/completions") { exchange ->
                val request = LedgerJson.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                requests.add(request)
                val model = request.str("model")
                val grade = if (requests.size == 1) json("items" to JsonArray(listOf(json("id" to "R1", "points" to 3,
                    "reason" to "Correct synthetic equality", "evidence" to "Sets up the synthetic equality"))), "total" to 3, "awarded" to 3)
                    else json("verdict" to "CONFIRM", "note" to "Synthetic method checked")
                val response = json("model" to model, "choices" to JsonArray(listOf(json("index" to 0,
                    "message" to json("role" to "assistant", "content" to grade.toString())))),
                    "x_coach" to json("status" to "complete", "finish_reason" to "stop", "served_by" to model,
                        "fallback" to false, "physical_calls" to 1, "request_id" to "synthetic", "queue_ms" to 1, "ttft_ms" to 1, "elapsed_ms" to 1)).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            bridge.start()
            try {
                val worker = ConstructedGradeWorker(actor, BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${bridge.address.port}")))
                assertTrue(worker.runOne())
                val draft = actor.execute { mocks.get(this, "SYNTH", id) }
                assertEquals(3.0, draft.getValue("score").jsonPrimitive.double)
                assertEquals("DRAFT", draft.getValue("items").jsonArray[1].jsonObject.str("status"))
                assertTrue(worker.runOne())
                assertFalse(worker.runOne())
                val resumed = actor.execute { mocks.get(this, "SYNTH", id) }
                val part = resumed.getValue("items").jsonArray[1].jsonObject
                assertEquals(3.0, resumed.getValue("score").jsonPrimitive.double)
                assertEquals("REVIEWED", part.str("status"))
                val numeric = part.getValue("numericFinal").jsonObject
                assertEquals(2, numeric.getValue("decimalPlaces").jsonPrimitive.int)
                assertEquals("1.005000000000000000001", numeric.str("expected"))
                assertEquals("0.005000000000000000000", numeric.getValue("absTolerance").jsonPrimitive.content)
                assertEquals("WRONG_DECIMAL_PLACES", numeric.str("status"))
                assertTrue("两位小数" in part.getValue("reasons").jsonArray[1].jsonObject.str("reason"))
                assertNotEquals(requests[0].str("model"), requests[1].str("model"))
                val facts = actor.execute { events("SYNTH") }
                assertEquals(300L, facts.filter { it.type == "attempt_graded" }.sumOf { it.payload.num("score") })
                assertEquals(1, facts.count { it.type == "grade_reviewed" })
            } finally { bridge.stop(0); actor.close() }
        }
    }

    private fun withPaper(expected: String, tolerance: String, block: (MockEngine, LedgerStore, String, java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("analytical-mock")
        val store = LedgerStore(Files.createDirectories(dir.resolve("db")))
        try {
            Files.writeString(dir.resolve("fig.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\"><text x=\"5\" y=\"20\">frozen-standard</text></svg>")
            Files.writeString(dir.resolve("paper.json"), """{"id":"synthetic-steps","title":"Synthetic steps","course":"SYNTH","kind":"paper","sections":[{"items":[
              {"id":"A","type":"analytical","stem":"Synthetic orchard arithmetic.","marks":10,"figure_svg":"fig.svg","parts":[
              {"label":"a","prompt":"Calculate a value.","marks":4,"answer":"$expected","tolerance":$tolerance,"rubric":[]},
              {"label":"b","prompt":"Explain the equality and calculate.","marks":6,"answer":"$expected","tolerance":$tolerance,
               "rubric":[{"criterion":"Set up equality","marks":3,"look_for":"Shows both sides"}]}]}]}]}""")
            val mocks = MockEngine(MockLibrary(dir))
            val started = mocks.start(store, "SYNTH", json("paperId" to "synthetic-steps"))
            block(mocks, store, started.str("mockId"), dir)
        } finally { runCatching { store.close() }; dir.toFile().deleteRecursively() }
    }
}
