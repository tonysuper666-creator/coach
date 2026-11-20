package com.coach.server

import com.coach.ai.*
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
import java.sql.DriverManager
import kotlin.test.*

/** Real loopback listeners and routes, synthetic books and HTTP/SSE bridge only. */
class FriendAccessTest {
    private class Harness : AutoCloseable {
        val root = Files.createTempDirectory("coach-friends-")
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val admin = "isolated-owner-token-32-characters"
        val store = LedgerStore(root).also { it.enroll("desktop", admin) }
        val actor = LedgerActor(jobs, store)
        val stub = StubTutor()
        val friends = FriendAccess(root.resolve("friends"), root.resolve("state"), root.resolve("friends.json"))
        val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), readSources = { pack, _ -> pack })
        fun engine(public: Boolean) = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = public)
            libraryExplainRoutes(actor, reader, friends = friends)
        }.apply { start(false) }
        val owner = engine(false)
        val public = engine(true)
        val client = HttpClient.newHttpClient()
        fun request(path: String, method: String = "GET", credential: JsonObject? = null, body: String = "", private: Boolean = false, startup: Boolean = false): HttpResponse<String> {
            val port = runBlocking { (if (private) owner else public).engine.resolvedConnectors().single().port }
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(java.time.Duration.ofSeconds(10))
            if (startup) request.header("X-Coach-Startup", "1")
            if (credential != null) request.header("X-Coach-Device", credential.str("deviceId")).header("Authorization", "Bearer ${credential.str("token")}")
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        }
        val ownerCredential get() = json("deviceId" to "desktop", "token" to admin)
        fun pair(name: String): JsonObject {
            val code = request("/v1/pairing-code", "POST", ownerCredential, json("friend" to name).toString(), true)
            assertEquals(200, code.statusCode())
            val result = request("/v1/pair", "POST", body = json("code" to parse(code).str("code"), "name" to "$name PC").toString())
            assertEquals(200, result.statusCode())
            return parse(result).also { assertEquals("friend", it.str("role")) }
        }
        fun explain(credential: JsonObject, text: String = "Synthetic passage."): HttpResponse<String> {
            val book = "a".repeat(64)
            val input = json("requestId" to "ab12", "kind" to "explain", "selection" to text,
                "blocks" to JsonArray(listOf(json("id" to "$book:p1:0", "text" to text))),
                "localSource" to json("schema" to 1, "book" to book, "format" to "txt", "title" to "Self-authored book", "consented" to true,
                    "pages" to JsonArray(listOf(json("number" to 1, "length" to 200, "ocr" to false, "sha256" to "b".repeat(64))))))
            return request("/v1/library/explain", "POST", credential, input.toString())
        }
        fun lesson(credential: JsonObject, requestId: String, blocks: Int = 8, kind: String = "lesson"): HttpResponse<String> {
            val book = "c".repeat(64)
            val input = json("requestId" to requestId, "kind" to kind, "bookPages" to 8,
                "blocks" to JsonArray((1..blocks).map { n -> json("id" to "$book:p$n:0", "text" to "Self-authored lesson block $n.") }),
                "localSource" to json("schema" to 1, "book" to book, "format" to "txt", "title" to "Self-authored lesson",
                    "consented" to true, "pages" to JsonArray((1..blocks).map { n -> json("number" to n, "length" to 200,
                        "ocr" to false, "sha256" to "d".repeat(64)) })))
            return request("/v1/library/explain", "POST", credential, input.toString())
        }
        fun reply() = stub.stream(1, """{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", StubTutor.terminal("complete"), StubTutor.DONE)
        override fun close() {
            runBlocking { owner.stopSuspend(0, 500); public.stopSuspend(0, 500); actor.close() }
            jobs.cancel(); stub.close(); root.toFile().deleteRecursively()
        }
    }
    private companion object {
        fun parse(response: HttpResponse<String>) = LedgerJson.parseToJsonElement(response.body()).jsonObject
    }

    @Test fun sableCredentialUsesFriendQuotaAndChecksRevocationOnStartupAndAfterTenMinutes() = Harness().use { h ->
        var time = 100L
        var active = true
        var offline = false
        val other = "second-synthetic-sable-grant"
        val token = "synthetic-sable-token-for-friend"
        fun sessions() = SableSessions(h.actor, SableVault(h.root.resolve("vault")), { value ->
            if (offline) throw SableUnavailable()
            if ((active && value == token) || value == other) SableIdentity("stable-sub", "demo") else null
        }, { time })
        h.friends.sable = sessions()
        fun exchange() = h.request("/v1/public/sable", "POST", json("deviceId" to "", "token" to token))
        val first = parse(exchange())
        val independent = parse(h.request("/v1/public/sable", "POST", json("deviceId" to "", "token" to other)))
        h.friends.sable = sessions()
        assertEquals(200, h.request("/v1/public/status", credential = first).statusCode())
        offline = true
        time += 10 * 60_000
        assertEquals(200, h.request("/v1/public/status", credential = first).statusCode())
        time += 5 * 60_000
        assertEquals(503, h.request("/v1/public/status", credential = first).statusCode())
        offline = false
        assertEquals(200, h.request("/v1/public/status", credential = first).statusCode())
        assertEquals("friend", first.str("role"))
        h.stub.stream(1, StubTutor.terminal("failed", "daily_quota"), StubTutor.DONE)
        assertEquals(429, h.lesson(first, "ab13").statusCode())
        active = false
        time += 10 * 60_000
        assertEquals(401, h.request("/v1/public/status", credential = first).statusCode())
        assertEquals(200, h.request("/v1/public/status", credential = independent).statusCode())
        active = true
        val second = parse(exchange())
        active = false
        assertEquals(401, h.request("/v1/public/status", credential = second, startup = true).statusCode())
        assertEquals(200, h.request("/v1/public/status", credential = h.pair("manual")).statusCode())
        active = true
        assertEquals(204, h.request("/v1/sable/disable", "POST", h.ownerCredential, """{"username":"demo"}""", true).statusCode())
        assertEquals(401, exchange().statusCode())
    }

    @Test fun twentyForbiddenPathsHaveEmpty404EvenWithoutAuthenticationAndFriendsCannotUseOwnerPort() = Harness().use { h ->
        val a = h.pair("甲")
        val paths = listOf("/v1/ws", "/v1/hello", "/v1/commands", "/v1/devices", "/v1/pairing-code", "/v1/usage",
            "/v1/courses/x/snapshot", "/v1/courses/x/today", "/v1/courses/x/sources", "/v1/courses/x/pool",
            "/v1/courses/x/essays", "/v1/prewarm", "/v1/reader", "/v1/library", "/v1/health", "/health",
            "/v1/%64evices", "/v1/%70airing-code", "/v1/library/explain/", "/v1/public/status/../devices")
        for (path in paths) {
            val method = if (path.contains("commands") || path.contains("pairing-code") || path.contains("prewarm")) "POST" else "GET"
            for (credential in listOf(null, a, h.ownerCredential)) {
                val response = h.request(path, method, credential)
                assertEquals(404, response.statusCode(), path); assertEquals("", response.body(), path)
            }
            assertEquals(404, h.request(path, method, a, private = true).statusCode(), path)
        }
        assertEquals(404, h.request("/v1/public/status", credential = h.ownerCredential).statusCode())
        assertEquals(200, h.request("/v1/hello", credential = h.ownerCredential, private = true).statusCode())
        println("FRIEND_BOUNDARY=20 paths x 3 public identities + owner listener; all denied")
    }

    @Test fun friendsHaveSeparateImportsAnswersAuditsAndNoOwnerLedgerCallsAndRevocationIsImmediate() = runBlocking {
        Harness().use { h ->
            val a = h.pair("甲"); val a2 = h.pair("甲"); val b = h.pair("乙")
            h.reply(); assertEquals(200, h.explain(a).statusCode())
            // Same book/hash/page with different text must not conflict across friends.
            h.reply(); assertEquals(200, h.explain(b, "Different synthetic passage.").statusCode())
            assertEquals(0L, h.actor.execute { supplyCallCount() })
            assertNull(h.actor.execute { learnerSourcePage("a".repeat(64), 1) })
            for (name in listOf("甲", "乙")) {
                val dir = h.root.resolve("friends").resolve(friendId(name))
                assertTrue(Files.exists(dir.resolve("imports/sources.sqlite")))
                assertTrue(Files.exists(dir.resolve("explanations.sqlite")))
                assertFalse(Files.readString(dir.resolve("requests.log")).contains("passage"))
            }
            val devices = LedgerJson.parseToJsonElement(h.request("/v1/devices", credential = h.ownerCredential, private = true).body()).jsonArray
            assertTrue(devices.any { it.jsonObject.str("friend") == "甲" && it.jsonObject["lastUsedAt"] != JsonNull })
            assertEquals(204, h.request("/v1/friends/revoke", "POST", h.ownerCredential, json("friend" to "甲").toString(), true).statusCode())
            for (pc in listOf(a, a2)) {
                assertEquals(401, h.request("/v1/public/status", credential = pc).statusCode())
                assertEquals(401, h.request("/v1/public/status", credential = pc, private = true).statusCode())
            }
            assertEquals(200, h.request("/v1/public/status", credential = b).statusCode())
            assertEquals(204, h.request("/v1/devices/${b.str("deviceId")}", "DELETE", h.ownerCredential, private = true).statusCode())
            assertEquals(401, h.request("/v1/public/status", credential = b).statusCode())
            println("FRIEND_ISOLATION=2 friends; owner AI calls=0; revoke A=401 B=200")
        }
    }

    @Test fun ownerCodesNeverPairPubliclyAndFixedQuotaUnavailableErrorsReachHttpUnchanged() = Harness().use { h ->
        val code = parse(h.request("/v1/pairing-code", "POST", h.ownerCredential, private = true)).str("code")
        assertEquals(401, h.request("/v1/pair", "POST", body = json("code" to code, "name" to "owner").toString()).statusCode())
        assertEquals(200, h.request("/v1/pair", "POST", body = json("code" to code, "name" to "owner").toString(), private = true).statusCode())
        val b = h.pair("乙")
        assertEquals(403, h.request("/v1/library/explain", "POST", b, json("requestId" to "ab12", "kind" to "explain", "selection" to "x",
            "blocks" to JsonArray(listOf(json("id" to "owner:p1:0", "text" to "x")))).toString()).statusCode())
        for ((code, status) in listOf("daily_quota" to 429, "monthly_quota" to 429, "unavailable" to 503)) {
            h.stub.stream(1, StubTutor.terminal("failed", code), StubTutor.DONE)
            val response = h.explain(b)
            assertEquals(status, response.statusCode())
            assertEquals(FriendServiceError(code).body, parse(response))
        }
    }

    @Test fun entireLessonUsesOneFriendCallAndOnlyStoresConsentedBlocks() = Harness().use { h ->
        val friend = h.pair("整节测试")
        h.stub.stream(1, """{"paragraphs":[{"text":"这一节的开头和结尾相互关联。","anchors":["S1","S8"]}]}""",
            StubTutor.terminal("complete"), StubTutor.DONE)
        val response = h.lesson(friend, "ab12")
        assertEquals(200, response.statusCode())
        assertEquals(listOf(1L, 8L), parse(response)["sources"]!!.jsonArray.map { it.jsonObject.num("pdfPage") })
        assertEquals(1, h.stub.requests)
        assertEquals(0L, runBlocking { h.actor.execute { supplyCallCount() } })
        val privateDir = h.root.resolve("friends").resolve(friendId("整节测试"))
        DriverManager.getConnection("jdbc:sqlite:${privateDir.resolve("imports/sources.sqlite")}").use { db ->
            db.createStatement().use { sql -> sql.executeQuery("SELECT count(*) FROM blocks").use { rows ->
                rows.next(); assertEquals(8, rows.getInt(1))
            } }
        }
        val audit = LedgerJson.parseToJsonElement(Files.readString(privateDir.resolve("requests.log"))).jsonObject
        assertEquals("ab12", audit.str("request"))
        assertEquals(8L, audit.num("sentAnchors"))
        assertEquals(2L, audit.num("citedAnchors"))
        assertEquals(JsonNull, audit["formatReason"])
        assertFalse(audit.toString().contains("Self-authored"))
        h.stub.stream(1, StubTutor.terminal("failed", "daily_quota"), StubTutor.DONE)
        val limited = h.lesson(friend, "ab13")
        assertEquals(429, limited.statusCode())
        assertEquals(FriendServiceError("daily_quota").body, parse(limited))
        assertEquals(2, h.stub.requests, "No retry after the second manual request hits quota")
    }

    @Test fun lessonAuditDistinguishesFormatRefusalsFromBridgeFailuresByRequest() = Harness().use { h ->
        val name = "诊断测试"
        val friend = h.pair(name)
        h.stub.stream(1, """{"paragraphs":[{"text":"PRIVATE_MODEL_TEXT","anchors":["S99"]}]}""",
            StubTutor.terminal("complete"), StubTutor.DONE)
        h.stub.stream(1, "PRIVATE_BAD_STREAM", StubTutor.terminal("complete"), StubTutor.DONE)
        h.stub.httpError(503)
        h.stub.stream(1, StubTutor.terminal("failed", "PRIVATE_BRIDGE_REASON"), StubTutor.DONE)

        for ((id, count) in listOf("ca01" to 8, "ca02" to 3)) {
            val response = h.lesson(friend, id, count)
            assertEquals(200, response.statusCode())
            val body = parse(response)
            assertEquals("回答缺少可核对的页码依据，暂时无法确认。", body.str("text"))
            assertTrue(body.getValue("sources").jsonArray.isEmpty())
        }
        for ((id, count) in listOf("ca03" to 5, "ca04" to 4)) {
            val response = h.lesson(friend, id, count)
            assertEquals(503, response.statusCode())
            assertEquals(FriendServiceError().body, parse(response))
        }
        assertEquals(4, h.stub.requests)

        val log = Files.readString(h.root.resolve("friends").resolve(friendId(name)).resolve("requests.log"))
        val rows = log.lineSequence().filter(String::isNotEmpty).map { LedgerJson.parseToJsonElement(it).jsonObject }.toList()
        assertEquals(listOf("ca01", "ca02", "ca03", "ca04"), rows.map { it.str("request") })
        assertEquals(listOf("complete", "complete", "unavailable", "unavailable"), rows.map { it.str("status") })
        assertEquals(listOf(8L, 3L, 5L, 4L), rows.map { it.num("sentAnchors") })
        assertEquals(listOf("ANCHORS", "STREAM_JSON", null, null), rows.map { it.getValue("formatReason").jsonPrimitive.contentOrNull })
        assertEquals(listOf(null, null, "HTTP_RESPONSE", "TERMINAL"), rows.map { it.getValue("bridgeFailureStage").jsonPrimitive.contentOrNull })
        for (row in rows) {
            assertEquals(friend.str("deviceId"), row.str("device"))
            assertEquals(0L, row.num("citedAnchors"))
            assertEquals(JsonNull, row["timeoutStage"])
        }
        for (secret in listOf("Self-authored", "PRIVATE_", "rid-stub", friend.str("token"))) {
            assertFalse(secret in log)
        }
    }

    @Test fun statusReportsOwnDeviceCallsAndOnlyExplicitFriendLimits() = Harness().use { h ->
        val b = h.pair("乙"); val b2 = h.pair("乙")
        Files.createDirectories(h.root.resolve("state"))
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        Files.writeString(h.root.resolve("state/api-credit-ledger.json"), json("friends" to json(friendId("乙") to json(day to json(
            "calls" to 1, "input_tokens" to 100, "output_tokens" to 20, "devices" to json(b.str("deviceId") to json("calls" to 1)))))).toString())
        val first = parse(h.request("/v1/public/status", credential = b))
        val second = parse(h.request("/v1/public/status", credential = b2))
        assertEquals(1L, first.num("calls")); assertEquals(0L, second.num("calls"))
        for (status in listOf(first, second)) {
            assertFalse("callsRemaining" in status)
            assertFalse("tokensRemaining" in status)
        }
        val config = h.root.resolve("friends.json")
        Files.writeString(config, json("defaults" to json("daily_calls" to 1)).toString())
        for (device in listOf(b, b2)) {
            val limited = parse(h.request("/v1/public/status", credential = device))
            assertEquals(0L, limited.getValue("callsRemaining").jsonPrimitive.long)
            assertFalse("tokensRemaining" in limited)
        }
        Files.writeString(config, json("friends" to json(friendId("乙") to json("daily_tokens" to 130))).toString())
        val tokenLimited = parse(h.request("/v1/public/status", credential = b2))
        assertFalse("callsRemaining" in tokenLimited)
        assertEquals(10L, tokenLimited.num("tokensRemaining"))
        assertFalse(first.toString().contains(friendId("乙")))
    }

    @Test fun friendChannelSupportsUnstructuredBookSectioningOnceAndNeverLeaksText() = Harness().use { h ->
        val friend = h.pair("分节朋友")
        h.stub.stream(
            1,
            """{"sections":[{"startBlockId":"S1","title":"第一部分"},{"startBlockId":"S5","title":"第二部分"}]}""",
            StubTutor.terminal("complete"),
            StubTutor.DONE,
        )
        val res = h.lesson(friend, "f001", blocks = 8, kind = "structure")
        assertEquals(200, res.statusCode())
        val parsed = parse(res)
        assertEquals(true, parsed["valid"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(listOf(1L, 5L), parsed["sections"]!!.jsonArray.map { it.jsonObject.num("pdfPage") })
        assertEquals(1, h.stub.requests)
        assertEquals(0L, runBlocking { h.actor.execute { supplyCallCount() } })
        val log = Files.readString(h.root.resolve("friends").resolve(friendId("分节朋友")).resolve("requests.log"))
        assertFalse("Self-authored" in log)
    }
}
