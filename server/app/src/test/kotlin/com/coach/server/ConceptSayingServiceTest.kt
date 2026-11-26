package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConceptSayingServiceTest {
    @Test fun pendingAndCompletedPollUseOneHaikuBatch() = runBlocking {
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        val models = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            headers += ex.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
            models += Json.parseToJsonElement(ex.requestBody.reader().readText()).jsonObject.getValue("model").jsonPrimitive.content
            val pending = headers.size == 1
            val body = if (pending) {
                """{"x_coach_batch":"pending","retry_after_ms":60000}"""
            } else {
                """{"choices":[{"message":{"content":"{\"books\":[{\"bookId\":\"book-a\",\"keep\":true,\"statement\":\"Alpha raises the premium.\",\"stance\":\"SAME\"}]}"},"finish_reason":"stop"}],
                    "x_coach":{"status":"complete","served_by":"claude-haiku-5-5","physical_calls":1,"finish_reason":"stop","fallback":false}}"""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(if (pending) 202 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val service = ConceptSayingService(BridgeClient(BridgeConfig("http://127.0.0.1:${server.address.port}", testTraffic = true)))
            val first = service.generate(input(), "synthetic", "device")
            assertEquals(true, first["pending"]?.jsonPrimitive?.booleanOrNull)
            val second = service.generate(input(), "synthetic", "device")
            val books = second.getValue("books") as JsonArray
            assertEquals("book-a", books.first().jsonObject.getValue("bookId").jsonPrimitive.content)
            assertEquals("SAME", books.first().jsonObject.getValue("stance").jsonPrimitive.content)
            assertEquals(listOf(BridgeClient.HAIKU, BridgeClient.HAIKU), models)
            assertEquals(1, headers.map { it["x-coach-batch-key"] }.distinct().size)
            assertTrue(headers.all {
                it["x-coach-batch-key"]?.length == 64 && it["x-coach-background"] == "1" &&
                    it["x-coach-task"] == "concept_sayings" && it["x-coach-friend"] == friendId("synthetic") &&
                    it["x-coach-friend-device"] == "device"
            })
        } finally {
            server.stop(0)
        }
    }

    @Test fun nonHaikuServedByIsRejected() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val body = """{"choices":[{"message":{"content":"{\"books\":[]}"},"finish_reason":"stop"}],
                "x_coach":{"status":"complete","served_by":"claude-opus-5-5","physical_calls":1,"finish_reason":"stop","fallback":false}}"""
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val service = ConceptSayingService(BridgeClient(BridgeConfig("http://127.0.0.1:${server.address.port}", testTraffic = true)))
            assertFailsWith<IllegalStateException> { service.generate(input(), null, "device") }
            Unit
        } finally {
            server.stop(0)
        }
    }

    @Test fun oneBookNeverReachesTheBridge() = runBlocking {
        var hits = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits++
            ex.sendResponseHeaders(500, -1)
            ex.close()
        }
        server.start()
        try {
            val service = ConceptSayingService(BridgeClient(BridgeConfig("http://127.0.0.1:${server.address.port}", testTraffic = true)))
            val single = buildJsonObject {
                put("conceptName", "synthetic concept")
                put("books", buildJsonArray {
                    add(buildJsonObject {
                        put("bookId", "book-a")
                        put("quote", "alpha theory stays on the page")
                        put("claim", "alpha raises a premium")
                    })
                })
            }
            assertFailsWith<IllegalArgumentException> { service.generate(single, null, "device") }
            assertEquals(0, hits)
        } finally {
            server.stop(0)
        }
    }

    private fun input() = buildJsonObject {
        put("conceptId", "c-synthetic")
        put("conceptName", "synthetic concept")
        put("books", buildJsonArray {
            add(buildJsonObject {
                put("bookId", "book-a")
                put("bookTitle", "Book A")
                put("page", 1)
                put("quote", "alpha theory stays on the page")
                put("claim", "alpha raises a premium")
            })
            add(buildJsonObject {
                put("bookId", "book-b")
                put("bookTitle", "Book B")
                put("page", 1)
                put("quote", "beta evidence stays on the page")
                put("claim", "beta lowers a premium")
            })
        })
    }
}
