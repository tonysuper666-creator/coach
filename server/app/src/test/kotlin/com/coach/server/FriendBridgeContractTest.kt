package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TON-1943: the friend requests the server really sends must match `tools/bridge/tests/fixtures/friend-study-requests.json`.
 * `tools/bridge/tests/friend_study_contract.py` runs the same rows through the real `subscription_bridge.build_req`.
 */
class FriendBridgeContractTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.exists(it.resolve("settings.gradle.kts")) && it.resolve("tools/bridge").isDirectory() }
        ?: fail("repository root not found")
    private val fixture = Json.parseToJsonElement(Files.readString(repo.resolve("tools/bridge/tests/fixtures/friend-study-requests.json")))
        .jsonObject.getValue("requests").jsonArray.associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }

    private class Capture(private val reply: (task: String) -> String) : AutoCloseable {
        val seen = CopyOnWriteArrayList<Pair<Map<String, String>, JsonObject>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val headers = ex.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
                seen += headers to Json.parseToJsonElement(ex.requestBody.reader().readText()).jsonObject
                val served = reply(headers["x-coach-task"].orEmpty())
                val bytes = served.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val bridge = BridgeClient(BridgeConfig("http://127.0.0.1:${server.address.port}", testTraffic = true))
        override fun close() = server.stop(0)
    }

    private fun complete(content: String, servedBy: String) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("message", buildJsonObject { put("content", content) }); put("finish_reason", "stop")
        }) })
        put("x_coach", buildJsonObject {
            put("status", "complete"); put("served_by", servedBy); put("physical_calls", 1)
            put("finish_reason", "stop"); put("fallback", false)
        })
    }.toString()

    /** Reduces a captured request to the fields the Bridge's friend admission looks at. */
    private fun shape(headers: Map<String, String>, body: JsonObject) = buildJsonObject {
        put("task", headers["x-coach-task"].orEmpty())
        put("model", body.getValue("model").jsonPrimitive.content)
        put("stream", body["stream"]?.jsonPrimitive?.booleanOrNull ?: false)
        put("background", headers["x-coach-background"] == "1")
        put("batch_key", headers["x-coach-batch-key"]?.matches(Regex("[a-f0-9]{64}")) == true)
        put("format_attempt", headers["x-coach-format-attempt"].orEmpty())
    }

    private fun expected(name: String) = buildJsonObject {
        val row = fixture.getValue(name).jsonObject
        for (key in listOf("task", "model", "stream", "background", "batch_key", "format_attempt")) put(key, row.getValue(key))
    }

    private fun assertFriendHeaders(headers: Map<String, String>) {
        assertEquals(friendId("甲"), headers["x-coach-friend"])
        assertEquals("friend-pc", headers["x-coach-friend-device"])
    }

    @Test fun friendConceptSayingsSendsTheAdmittedBatchShapeAndReturnsBooks() = runBlocking {
        Capture { complete("""{"books":[{"bookId":"book-a","keep":true,"statement":"Alpha raises it.","stance":"SAME"}]}""", BridgeClient.HAIKU) }.use { c ->
            val input = buildJsonObject {
                put("conceptId", "c1"); put("conceptName", "synthetic concept")
                put("books", buildJsonArray {
                    for (id in listOf("book-a", "book-b")) add(buildJsonObject {
                        put("bookId", id); put("bookTitle", id); put("page", 1)
                        put("quote", "$id quote stays on the page"); put("claim", "$id claim")
                    })
                })
            }
            val reply = ConceptSayingService(c.bridge).generate(input, "甲", "friend-pc")
            assertEquals("book-a", (reply.getValue("books") as JsonArray).first().jsonObject.getValue("bookId").jsonPrimitive.content)
            val (headers, body) = c.seen.single()
            assertFriendHeaders(headers)
            assertEquals(expected("concept_sayings"), shape(headers, body))
        }
    }

    @Test fun friendCourseEssayVotesUseTheAdmittedReaderSelectionShape() = runBlocking {
        Capture { _ -> complete("{}", BridgeClient.HAIKU) }.use { c ->
            val transport = HaikuPinnedBridgeTransport(c.bridge, backgroundBatch = true)
            for (task in listOf(PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE, PracticeGradeLimits.TASK_COURSE_ESSAY_CHECK)) {
                transport.generateSectionForFriend("s", "u", task, "k", 400, "甲", "friend-pc")
            }
            assertEquals(2, c.seen.size)
            c.seen.forEach { (headers, _) -> assertFriendHeaders(headers) }
            assertEquals(expected("course_essay_note"), shape(c.seen[0].first, c.seen[0].second))
            assertEquals(expected("course_essay_check"), shape(c.seen[1].first, c.seen[1].second))
        }
    }

    @Test fun friendEssaySecondVoteAcceptsSonnetButOwnerStillNeedsGpt() {
        assertTrue(courseEssayCheckModel("claude-sonnet-5-5", friend = true))
        assertTrue(!courseEssayCheckModel("claude-haiku-5-5", friend = true))
        assertTrue(!courseEssayCheckModel("claude-sonnet-5-5", friend = false))
        assertTrue(courseEssayCheckModel("gpt-6.2-astra", friend = false))
        assertTrue(fixture.values.all { it.jsonObject.getValue("stream").jsonPrimitive.boolean.not() })
    }
}
