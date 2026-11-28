package com.coach.server

import com.coach.core.LedgerActor
import com.coach.protocol.learning.learningTables
import com.coach.store.LedgerStore
import com.coach.store.sha256
import com.sun.net.httpserver.HttpServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerLearningSyncTest {
    private fun withServer(
        allowOwnerLearningSync: Boolean,
        block: suspend (Path, String, String, String, String, String, String) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("owner-learning-sync-")
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("service"))
        val actor = LedgerActor(job, store)
        val owner1Device = "qa-owner-1"
        val owner1Token = "a".repeat(43)
        val owner2Device = "qa-owner-2"
        val owner2Token = "b".repeat(43)
        store.enroll(owner1Device, owner1Token)
        store.enroll(owner2Device, owner2Token)
        val friendCode = store.createPairingCode("manual-friend").code
        val manualFriend = checkNotNull(store.pair(friendCode, "friend-pc"))
        val sableToken = "synthetic-sable-owner-sync-test"
        val issuer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/coach/whoami") { ex ->
                val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $sableToken"
                val body = """{"sub":"sable-user-1","username":"sable1","display_name":"Sable"}""".toByteArray()
                ex.sendResponseHeaders(if (ok) 200 else 401, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val friends = FriendAccess(
            root.resolve("friends"),
            root.resolve("state"),
            root.resolve("config"),
            SableSessions(actor, root.resolve("vault"), URI("http://127.0.0.1:${issuer.address.port}")),
        )
        val books = UnifiedBookPipeline(root.resolve("books"), enabled = true, autoProcess = false)
        val server = job.embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = false, unifiedBooks = books)
            unifiedBookRoutes(actor, books, friends, learningSync = true, allowOwnerLearningSync = allowOwnerLearningSync)
        }.apply { start(false) }
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            val sableCred = checkNotNull(friends.sable?.exchange(sableToken, books))
            block(
                root,
                base,
                "$owner1Device:$owner1Token",
                "$owner2Device:$owner2Token",
                "${manualFriend.deviceId}:${manualFriend.token}",
                "${sableCred.deviceId}:${sableCred.token}",
                sableCred.deviceId,
            )
        } finally {
            server.stop(0, 1000)
            issuer.stop(0)
            actor.close()
            job.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private fun request(base: String, path: String, cred: String, body: String? = null): HttpResponse<String> {
        val (device, token) = cred.split(':', limit = 2)
        val builder = HttpRequest.newBuilder(URI("$base$path"))
            .header("Authorization", "Bearer $token")
            .header("X-Coach-Device", device)
        if (body == null) builder.GET()
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun productionDetectionRequiresManagedUserPort18791AndDefaultDataDir() {
        val prodData = Path.of(System.getProperty("user.home"), "Coach/data")
        assertTrue(isProductionCoachServer(emptyMap(), managed = true, data = prodData, port = 18791))
        assertTrue(isProductionCoachServer(mapOf("COACH_TRAFFIC" to "user"), managed = true, data = prodData, port = 18791))
        assertFalse(isProductionCoachServer(emptyMap(), managed = false, data = prodData, port = 18791))
        assertFalse(isProductionCoachServer(mapOf("COACH_TRAFFIC" to "test"), managed = true, data = prodData, port = 18791))
        assertFalse(isProductionCoachServer(emptyMap(), managed = true, data = prodData, port = 28791))
        assertFalse(isProductionCoachServer(emptyMap(), managed = true, data = prodData.resolveSibling("staging-data"), port = 18791))
    }

    @Test fun productionServerRejectsOwnerAndManualFriendWhileKeepingSableSyncUnchanged() {
        withServer(allowOwnerLearningSync = false) { root, base, owner1, _, manualFriend, sable, _ ->
            for (cred in listOf(owner1, manualFriend)) {
                assertEquals(403, request(base, "/learning-sync/profile", cred).statusCode())
                assertEquals(403, request(base, "/learning-sync/changes?after=0&stream=${UUID.randomUUID()}", cred).statusCode())
                assertEquals(403, request(base, "/learning-sync/push", cred, "{}").statusCode())
            }
            val sableProfile = request(base, "/learning-sync/profile", sable)
            assertEquals(200, sableProfile.statusCode())
            val scope = Json.parseToJsonElement(sableProfile.body()).jsonObject.getValue("scope").jsonPrimitive.content
            assertTrue(scope.startsWith("sable-"))

            val store = LearningSyncStore(root.resolve("store-check"), allowOwner = false)
            assertFailsWith<IllegalArgumentException> {
                store.profile(UnifiedBookScope("device-" + "a".repeat(64), isSableAccount = false))
            }
        }
    }

    @Test fun stagingServerAcceptsOwnerIsolatesAccountsAndStillRejectsManualFriend() {
        withServer(allowOwnerLearningSync = true) { _, base, owner1, owner2, manualFriend, sable, _ ->
            assertEquals(403, request(base, "/learning-sync/profile", manualFriend).statusCode())

            val p1Res = request(base, "/learning-sync/profile", owner1)
            assertEquals(200, p1Res.statusCode())
            val p1 = Json.parseToJsonElement(p1Res.body()).jsonObject
            val owner1Scope = p1.getValue("scope").jsonPrimitive.content
            val owner1Epoch = p1.getValue("epoch").jsonPrimitive.content
            assertEquals("device-${sha256("qa-owner-1".toByteArray(Charsets.UTF_8))}", owner1Scope)

            val p2Res = request(base, "/learning-sync/profile", owner2)
            assertEquals(200, p2Res.statusCode())
            val p2 = Json.parseToJsonElement(p2Res.body()).jsonObject
            val owner2Scope = p2.getValue("scope").jsonPrimitive.content
            val owner2Epoch = p2.getValue("epoch").jsonPrimitive.content
            assertEquals("device-${sha256("qa-owner-2".toByteArray(Charsets.UTF_8))}", owner2Scope)
            assertTrue(owner1Scope != owner2Scope)

            val table = learningTables.single { it.kind == "mastery" }
            val row = buildJsonObject {
                put("concept_key", "cmap:${"a".repeat(64)}:0123456789abcdef")
                put("collection_id", JsonNull)
                put("book_id", "a".repeat(64))
                put("toc_node_id", "ch-1")
                put("canonical_name", "Operant Conditioning")
                put("syllabus_weight", 1.0)
                put("p_mastery", 0.72)
                put("has_active_test", 1)
                put("fsrs_stability", 1.0)
                put("dominant_error_tag", JsonNull)
                put("reread_count", 0)
                put("dwell_ms_total", 0)
                put("ask_count", 0)
                put("highlight_count", 0)
                put("updated_at", 100L)
            }
            val recordId = table.legacyId(row)
            val stream1 = UUID.randomUUID().toString()
            val pushBody = buildJsonObject {
                put("kind", table.kind)
                put("id", recordId)
                put("epoch", owner1Epoch)
                put("protocol", 1)
                put("stream", stream1)
                put("seq", 1)
                put("clock", 1)
                put("time", 100L)
                put("round", 1)
                put("before", JsonNull)
                put("after", row)
            }.toString()
            val pushRes = request(base, "/learning-sync/push", owner1, pushBody)
            assertEquals(200, pushRes.statusCode())
            assertEquals(owner1Scope, Json.parseToJsonElement(pushRes.body()).jsonObject.getValue("scope").jsonPrimitive.content)

            // Same owner identity on a second directory/stream receives the record.
            val sameOwnerChanges = request(base, "/learning-sync/changes?after=0&epoch=$owner1Epoch&stream=${UUID.randomUUID()}", owner1)
            assertEquals(200, sameOwnerChanges.statusCode())
            val changes1 = Json.parseToJsonElement(sameOwnerChanges.body()).jsonObject.getValue("changes").jsonArray
            assertEquals(1, changes1.size)
            assertEquals(recordId, changes1.single().jsonObject.getValue("id").jsonPrimitive.content)

            // Different owner and Sable accounts are strictly isolated.
            assertEquals(404, request(base, "/learning-sync/record?kind=${table.kind}&id=$recordId", owner2).statusCode())
            assertEquals(404, request(base, "/learning-sync/record?kind=${table.kind}&id=$recordId", sable).statusCode())
            val owner2Changes = Json.parseToJsonElement(
                request(base, "/learning-sync/changes?after=0&epoch=$owner2Epoch&stream=${UUID.randomUUID()}", owner2).body()
            ).jsonObject.getValue("changes").jsonArray
            assertTrue(owner2Changes.isEmpty())
            val owner2Snapshot = Json.parseToJsonElement(
                request(base, "/learning-sync/snapshot?epoch=$owner2Epoch&after=0", owner2).body()
            ).jsonObject.getValue("items").jsonArray
            assertTrue(owner2Snapshot.isEmpty())

            // Bad token is rejected with 401.
            assertEquals(401, request(base, "/learning-sync/profile", "qa-owner-1:${"z".repeat(43)}").statusCode())
        }
    }
}
