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

/** Sage's independent synthetic regression: rubric already covers all marks. */
class SageFullRubricProbeTest {
    @Test fun finalValueReachesGradingWhenRubricCoversAllMarks() {
        val dir = Files.createTempDirectory("sage-probe")
        val store = LedgerStore(Files.createDirectories(dir.resolve("db")))
        try {
            Files.writeString(dir.resolve("paper.json"), """{"id":"probe","title":"Probe","course":"SYNTH","kind":"paper","sections":[{"items":[
              {"id":"A","type":"analytical","stem":"Synthetic arithmetic.","marks":4,"parts":[
              {"label":"a","prompt":"Calculate a value.","marks":4,"answer":"1.00","tolerance":0.005,
               "rubric":[{"criterion":"Correct final value","marks":4,"look_for":"1.00"}]}]}]}]}""")
            val mocks = MockEngine(MockLibrary(dir))
            val id = mocks.start(store, "SYNTH", json("paperId" to "probe")).str("mockId")
            val result = mocks.submit(store, "SYNTH", id, json("A#a" to json("text" to "Working only", "final" to "7.77")), false)
            val part = result.getValue("items").jsonArray.first().jsonObject
            println("PROBE submit pending=${result.num("pending")} part=$part")
            runBlocking {
                val actor = LedgerActor(this, store)
                val requests = CopyOnWriteArrayList<JsonObject>()
                val bridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                bridge.createContext("/v1/chat/completions") { exchange ->
                    val request = LedgerJson.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    requests.add(request)
                    val model = request.str("model")
                    val grade = if (requests.size == 1) json("items" to JsonArray(listOf(json("id" to "R1", "points" to 0,
                        "reason" to "No final value", "evidence" to "Working only"))), "total" to 4, "awarded" to 0)
                        else json("verdict" to "CONFIRM", "note" to "ok")
                    val response = json("model" to model, "choices" to JsonArray(listOf(json("index" to 0,
                        "message" to json("role" to "assistant", "content" to grade.toString())))),
                        "x_coach" to json("status" to "complete", "finish_reason" to "stop", "served_by" to model,
                            "fallback" to false, "physical_calls" to 1, "request_id" to "probe", "queue_ms" to 1, "ttft_ms" to 1, "elapsed_ms" to 1)).toString().toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                bridge.start()
                try {
                    val worker = ConstructedGradeWorker(actor, BridgeClient(BridgeConfig(baseUrl = "http://127.0.0.1:${bridge.address.port}")))
                    assertTrue(worker.runOne())
                    val sent = requests[0].toString()
                    println("PROBE grader-request contains final 7.77: ${"7.77" in sent}; contains working: ${"Working only" in sent}")
                    val graded = actor.execute { mocks.get(this, "SYNTH", id) }.getValue("items").jsonArray.first().jsonObject
                    println("PROBE numericFinal present after grading: ${"numericFinal" in graded}")
                    assertTrue("7.77" in sent || "numericFinal" in graded,
                        "the student's separate final value was neither shown to the grader nor checked by the server")
                } finally { bridge.stop(0); actor.close() }
            }
        } finally { runCatching { store.close() }; dir.toFile().deleteRecursively() }
    }
}
