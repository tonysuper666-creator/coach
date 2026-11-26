package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class LiteratureGuideServiceTest {
    @Test fun pendingAndCompletedPollUseOneHaikuJobWithFriendAccounting() = runBlocking {
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        val models = CopyOnWriteArrayList<String>()
        val prompts = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            headers += ex.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
            val request = Json.parseToJsonElement(ex.requestBody.reader().readText()).jsonObject
            models += request.getValue("model").jsonPrimitive.content
            val content = request.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
            prompts += Json.parseToJsonElement(content).jsonObject
            val pending = headers.size == 1
            val body = if (pending) """{"x_coach_batch":"pending","retry_after_ms":60000}"""
                else """{"choices":[{"message":{"content":"{\"question\":\"Synthetic?\"}"},"finish_reason":"stop"}],
                    "x_coach":{"status":"complete","served_by":"claude-haiku-5-5","physical_calls":1,"finish_reason":"stop","fallback":false}}"""
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(if (pending) 202 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val service = LiteratureGuideService(BridgeClient(BridgeConfig("http://127.0.0.1:${server.address.port}", testTraffic = true)))
            val input = buildJsonObject {
                put("questions", buildJsonArray { add("Synthetic?") })
                put("documents", buildJsonArray { repeat(3) { add(buildJsonObject {
                    put("bookId", "book-" + it); put("author", "Author " + it); put("year", "1997")
                }) } })
                put("blocks", buildJsonArray { repeat(3) { add(buildJsonObject { put("bookId", "book-$it") }) } })
            }
            assertEquals(true, service.generate(input, "f", "synthetic", "device")["pending"]?.jsonPrimitive?.boolean)
            assertEquals("Synthetic?", service.generate(input, "f", "synthetic", "device")["question"]?.jsonPrimitive?.content)
            assertEquals(listOf(BridgeClient.HAIKU, BridgeClient.HAIKU), models)
            assertTrue(prompts.all { it["documents"] == input["documents"] })
            assertEquals(1, headers.map { it["x-coach-batch-key"] }.distinct().size)
            assertTrue(headers.all { it["x-coach-batch-key"]?.length == 64 && it["x-coach-background"] == "1" &&
                it["x-coach-task"] == "literature_guide" && it["x-coach-friend"] == friendId("synthetic") &&
                it["x-coach-friend-device"] == "device" })
        } finally { server.stop(0) }
    }
}
