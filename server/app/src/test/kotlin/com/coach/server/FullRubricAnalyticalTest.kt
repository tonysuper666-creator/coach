package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.ai.ConstructedGradeWorker
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Full rubrics must retain both submitted fields without inventing extra marks. */
class FullRubricAnalyticalTest {
    @Test fun correctFinalOnlyCanEarnRubricCredit() = check("", "1.00", "CORRECT", 4)
    @Test fun wrongFinalKeepsMethodCredit() = check("Working only", "7.77", "INCORRECT", 2)
    @Test fun invalidFinalKeepsMethodCredit() = check("Working only", "abc", "NOT_NUMERIC", 2)
    @Test fun wrongPrecisionKeepsMethodCredit() = check("Working only", "1.0", "WRONG_DECIMAL_PLACES", 2)
    @Test fun missingFinalKeepsMethodCredit() = check("Working only", "", "MISSING", 2)
    @Test fun inclusiveToleranceOnFullRubric() = check("", "1.00", "CORRECT", 4, "1.005")
    @Test fun aboveToleranceOnFullRubric() = check("Working only", "1.00", "INCORRECT", 2, "1.005000000000000000001")
    @Test fun nullToleranceUsesAnalyticalDefault() = check("", "1.00", "CORRECT", 4, "1.005", "null")
    @Test fun entirelyBlankStillRecordsMissingFinal() = check("", "", "MISSING", 0)
    @Test fun reviewReconcilesFullCreditWithWrongFinal() = check("Working only", "7.77", "INCORRECT", 2, draftPoints = 4)
    @Test fun confirmedFullCreditWithWrongFinalRemainsFlagged() = check("Working only", "7.77", "INCORRECT", 4)
    @Test fun fullRubricExplicitZeroRemainsStrictDuringReview() = check("Working only", "1.00", "INCORRECT", 2,
        expected = "1.000000000000000001", tolerance = "0")

    private fun check(working: String, final: String, status: String, points: Int,
        expected: String = "1.00", tolerance: String = "0.005", draftPoints: Int = points) = runBlocking {
        val dir = Files.createTempDirectory("full-rubric")
        val store = LedgerStore(Files.createDirectories(dir.resolve("db")))
        val requests = CopyOnWriteArrayList<JsonObject>()
        val bridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val evidence = if (working.isBlank()) final else working
        bridge.createContext("/v1/chat/completions") { exchange ->
            val request = LedgerJson.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            requests.add(request)
            fun grade(awarded: Int) = json("items" to JsonArray(listOf(json("id" to "R1", "points" to awarded,
                "reason" to "Synthetic rubric judgment", "evidence" to evidence))), "total" to 4, "awarded" to awarded)
            val grade = if (requests.size == 1) grade(draftPoints)
                else if(draftPoints != points) json("verdict" to "REVISE", "note" to "Method credit retained; final criterion not met",
                    "revised_grade" to grade(points))
                else json("verdict" to "CONFIRM", "note" to "Synthetic independent review")
            val response = json("model" to request.str("model"), "choices" to JsonArray(listOf(json("index" to 0,
                "message" to json("role" to "assistant", "content" to grade.toString())))),
                "x_coach" to json("status" to "complete", "finish_reason" to "stop", "served_by" to request.str("model"),
                    "fallback" to false, "physical_calls" to 1, "request_id" to "synthetic-full", "queue_ms" to 1,
                    "ttft_ms" to 1, "elapsed_ms" to 1)).toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        bridge.start()
        val actor = LedgerActor(this, store)
        try {
            Files.writeString(dir.resolve("paper.json"), """{"id":"full","title":"Synthetic full rubric","course":"SYNTH","kind":"paper","sections":[{"items":[
              {"id":"A","type":"analytical","stem":"Synthetic arithmetic.","marks":4,"parts":[
              {"label":"a","prompt":"Calculate and explain.","marks":4,"answer":"$expected","tolerance":$tolerance,
               "rubric":[{"criterion":"Method and final value","marks":4,"look_for":"Synthetic method and correct final"}]}]}]}]}""")
            val mocks = MockEngine(MockLibrary(dir))
            val id = actor.execute { mocks.start(this, "SYNTH", json("paperId" to "full")).str("mockId") }
            actor.execute { mocks.submit(this, "SYNTH", id, json("A#a" to json("text" to working, "final" to final)), false) }
            val worker = ConstructedGradeWorker(actor, BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${bridge.address.port}")))
            if (working.isNotBlank() || final.isNotBlank()) {
                assertTrue(worker.runOne())
                assertTrue(worker.runOne())
                assertEquals(2, requests.size)
                requests.forEach { assertTrue("Submitted final value (verbatim, server-owned): <<<$final>>>" in it.toString()) }
                requests.forEach { request ->
                    val prompt = request.getValue("messages").jsonArray.joinToString("\n") { it.jsonObject.str("content") }
                    assertTrue("Server numeric verdict (authoritative):" in prompt)
                    assertTrue("\"status\":\"$status\"" in prompt)
                    assertTrue("\"correct\":${status == "CORRECT"}" in prompt)
                }
                if (draftPoints == 4 && status != "CORRECT") {
                    val reviewPrompt = requests[1].getValue("messages").jsonArray.joinToString("\n") { it.jsonObject.str("content") }
                    assertTrue("FULL_CREDIT_WITH_NONMATCHING_FINAL" in reviewPrompt)
                }
                assertNotEquals(requests[0].str("model"), requests[1].str("model"))
            }
            assertFalse(worker.runOne())
            val result = actor.execute { mocks.get(this, "SYNTH", id) }
            val part = result.getValue("items").jsonArray.single().jsonObject
            assertEquals(points.toDouble(), part.getValue("score").jsonPrimitive.double)
            assertEquals(4.0, result.getValue("max").jsonPrimitive.double)
            assertEquals(status, part.getValue("numericFinal").jsonObject.str("status"))
            assertEquals(if (points == 4 && status != "CORRECT") "FULL_CREDIT_WITH_NONMATCHING_FINAL" else "",
                part.getValue("numericFinal").jsonObject.str("rubricReviewWarning"))
            if (points == 4 && status != "CORRECT") assertTrue("细则获满分" in part.str("feedback"))
            assertTrue(part.str("feedback").isNotBlank())
            assertEquals(1, part.getValue("reasons").jsonArray.size, "no invented numeric points")
            val events = actor.execute { events("SYNTH") }
            val fact = events.single { it.type == "attempt_graded" }.payload
            assertEquals(draftPoints * 100L, fact.num("score"))
            if (draftPoints != points) {
                val review = events.single { it.type == "grade_reviewed" }.payload
                assertEquals("REVISE", review.str("decision"))
                assertEquals(points * 100L, review.getValue("revised").jsonObject.num("score"))
                assertEquals("FULL_CREDIT_WITH_NONMATCHING_FINAL", fact.getValue("numericFinal").jsonObject.str("rubricReviewWarning"))
            }
            if (working.isBlank() && final.isNotBlank()) {
                val span = fact.getValue("evidence").jsonArray.first { it.jsonObject.str("rubricItemId") == "R1" }.jsonObject
                assertEquals("finalAnswer", span.str("field"))
                assertEquals(evidence, final.substring(span.num("start").toInt(), span.num("end").toInt()))
            }
            actor.close()
            LedgerStore(dir.resolve("db")).use { reopened -> assertEquals(result, mocks.get(reopened, "SYNTH", id)) }
        } finally {
            bridge.stop(0)
            actor.close()
            dir.toFile().deleteRecursively()
        }
    }
}
