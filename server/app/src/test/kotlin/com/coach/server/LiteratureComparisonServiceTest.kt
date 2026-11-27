package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiteratureComparisonServiceTest {
    @Test fun pendingAndCompletedPollUseOneHaikuBatch() = runBlocking {
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        val models = CopyOnWriteArrayList<String>()
        val systems = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            headers += exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
            val payload = Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
            models += payload.getValue("model").jsonPrimitive.content
            systems += payload.getValue("messages").jsonArray.first().jsonObject.getValue("content").jsonPrimitive.content
            val pending = headers.size == 1
            val body = if (pending) """{"x_coach_batch":"pending","retry_after_ms":60000}"""
            else """{"choices":[{"message":{"content":"{\"rows\":[]}"},"finish_reason":"stop"}],
                "x_coach":{"status":"complete","served_by":"claude-haiku-5-5","physical_calls":1,"finish_reason":"stop","fallback":false}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (pending) 202 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val service = LiteratureComparisonService(
                BridgeClient(BridgeConfig("http://127.0.0.1:${server.address.port}", testTraffic = true)),
            )
            val input = buildJsonObject {
                put("blocks", buildJsonArray {
                    repeat(3) { add(buildJsonObject { put("bookId", "book-$it") }) }
                })
            }
            assertEquals(true, service.generate(input, "owner", "synthetic", "desktop")["pending"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(Json.Default.parseToJsonElement("""{"rows":[]}"""), service.generate(input, "owner", "synthetic", "desktop"))
            assertEquals(listOf(BridgeClient.HAIKU, BridgeClient.HAIKU), models)
            assertEquals(1, headers.map { it["x-coach-batch-key"] }.distinct().size)
            assertTrue(headers.all {
                it["x-coach-background"] == "1" && it["x-coach-task"] == "literature_comparison" &&
                    it["x-coach-friend"] == friendId("synthetic")
            })
            assertTrue(systems.all { system ->
                "本行这一篇" in system && "反对者自己" in system && "不要一边写成支持" in system
            })
        } finally {
            server.stop(0)
        }
    }
}
