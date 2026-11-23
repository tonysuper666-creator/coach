package com.coach.server

import com.coach.ai.BridgeClient
import com.coach.ai.BridgeConfig
import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.domain.str
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UnifiedBookPipelineTest {
    private class Harness(
        val config: UnifiedBookConfig = UnifiedBookConfig(enabled = true),
        val withCatalog: Boolean = false,
    ) : AutoCloseable {
        val root: Path = Files.createTempDirectory("coach-unified-books-")
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adminToken = "isolated-owner-token-32-characters"
        val store = LedgerStore(root).also { it.enroll("desktop", adminToken) }
        val actor = LedgerActor(jobs, store)
        val stub = StubTutor()
        val imports = ImportedBookSources(root.resolve("personal-imports"))
        val friends = FriendAccess(root.resolve("friends"), root.resolve("state"), root.resolve("friends.json"))
        val unifiedDir: Path = root.resolve("unified-books")
        val unified = UnifiedBookPipeline(unifiedDir, config)
        val readerDir: Path = Files.createDirectories(root.resolve("reader-catalog")).also { dir ->
            if (withCatalog) Files.writeString(dir.resolve("catalog.json"), """{"schema":1,"documents":[]}""")
        }
        val library: ReaderLibrary? = if (withCatalog) ReaderLibrary(readerDir) else null
        val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), readSources = { pack, _ -> pack })

        var sableLookup: (String) -> SableIdentity? = { token ->
            when (token) {
                TOK_ALPHA_1 -> SableIdentity("sub-alpha", "alice")
                TOK_ALPHA_2 -> SableIdentity("sub-alpha", "alice-renamed")
                TOK_BETA_1 -> SableIdentity("sub-beta", "bob")
                TOK_GAMMA_1 -> SableIdentity("sub-gamma", "carol")
                else -> null
            }
        }

        init {
            friends.sable = SableSessions(actor, SableVault(root.resolve("vault")), { token -> sableLookup(token) })
        }

        val owner = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = false, unifiedBooks = unified)
            libraryExplainRoutes(actor, reader, library = library, imports = imports, friends = friends, unifiedBooks = unified)
            unifiedBookRoutes(actor, unified, friends)
        }.apply { start(false) }

        val public = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = true, unifiedBooks = unified)
            libraryExplainRoutes(actor, reader, library = library, imports = imports, friends = friends, unifiedBooks = unified)
            unifiedBookRoutes(actor, unified, friends)
        }.apply { start(false) }

        val client: HttpClient = HttpClient.newHttpClient()
        val ownerCredential get() = json("deviceId" to "desktop", "token" to adminToken)

        fun port(private: Boolean): Int = runBlocking {
            (if (private) owner else public).engine.resolvedConnectors().single().port
        }

        fun request(
            path: String,
            method: String = "GET",
            credential: JsonObject? = null,
            body: String = "",
            private: Boolean = false,
        ): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(private)}$path"))
                .timeout(java.time.Duration.ofSeconds(10))
            if (credential != null) {
                req.header("X-Coach-Device", credential.str("deviceId"))
                    .header("Authorization", "Bearer ${credential.str("token")}")
            }
            return client.send(
                req.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

        fun exchangeSable(sableToken: String): JsonObject {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(false)}/v1/public/sable"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer $sableToken")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, res.statusCode(), res.body())
            return parse(res)
        }

        fun pairFriend(name: String): JsonObject {
            val code = request("/v1/pairing-code", "POST", ownerCredential, json("friend" to name).toString(), private = true)
            assertEquals(200, code.statusCode())
            val res = request(
                "/v1/pair",
                "POST",
                body = json("code" to parse(code).str("code"), "name" to "$name PC").toString(),
            )
            assertEquals(200, res.statusCode())
            return parse(res)
        }

        fun upload(
            credential: JsonObject?,
            bytes: ByteArray,
            format: String = "txt",
            title: String = "Synthetic Book",
            declaredSha: String? = null,
            private: Boolean = false,
        ): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(private)}/v1/books/upload"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("X-Coach-Book-Format", format)
                .header("X-Coach-Book-Title", URLEncoder.encode(title, StandardCharsets.UTF_8))
            if (declaredSha != null) req.header("X-Coach-Book-Sha256", declaredSha)
            if (credential != null) {
                req.header("X-Coach-Device", credential.str("deviceId"))
                    .header("Authorization", "Bearer ${credential.str("token")}")
            }
            return client.send(
                req.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }

        fun writeCatalogBook(bytes: ByteArray, allowPersonalImport: Boolean = false): String {
            val sha = sha256Hex(bytes)
            val file = readerDir.resolve("course-book.txt")
            Files.write(file, bytes)
            val manifest = json(
                "schema" to 1,
                "documents" to JsonArray(
                    listOf(
                        json(
                            "id" to "synthetic-course-book",
                            "course" to "SYNTH",
                            "title" to "Synthetic Course Book",
                            "file" to "course-book.txt",
                            "sha256" to sha,
                            "pages" to 1,
                            "kind" to "textbook",
                            "allowPersonalImport" to allowPersonalImport,
                        ),
                    ),
                ),
            )
            Files.writeString(readerDir.resolve("catalog.json"), manifest.toString())
            return sha
        }

        fun reply() = stub.stream(
            1,
            """{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""",
            StubTutor.terminal("complete"),
            StubTutor.DONE,
        )

        override fun close() {
            runBlocking {
                owner.stopSuspend(0, 500)
                public.stopSuspend(0, 500)
                actor.close()
            }
            jobs.cancel()
            stub.close()
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val TOK_ALPHA_1 = "synthetic-sable-token-alpha-01"
        const val TOK_ALPHA_2 = "synthetic-sable-token-alpha-02"
        const val TOK_BETA_1 = "synthetic-sable-token-beta-001"
        const val TOK_GAMMA_1 = "synthetic-sable-token-gamma-01"
        fun parse(res: HttpResponse<String>): JsonObject = LedgerJson.parseToJsonElement(res.body()).jsonObject
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun disabledByDefaultHidesRoutesAdvertisesFalseAndUnauthenticatedStillReturns401() =
        Harness(UnifiedBookConfig(enabled = false)).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            // Unauthenticated access MUST return 401 on unified endpoints.
            assertEquals(401, h.request("/v1/books").statusCode())
            assertEquals(401, h.upload(null, "hello".toByteArray()).statusCode())
            assertEquals(401, h.request("/v1/books", private = true).statusCode())

            // Capability flags advertise false when disabled.
            val hello = parse(h.request("/v1/hello", credential = h.ownerCredential, private = true))
            assertFalse(hello["unifiedBooksSupported"]?.jsonPrimitive?.boolean == true)
            val status = parse(h.request("/v1/public/status", credential = alice))
            assertFalse(status["unifiedBooks"]?.jsonPrimitive?.boolean == true)

            // Authenticated unified routes fail closed with 404 and create no filesystem artifacts.
            assertEquals(404, h.request("/v1/books", credential = alice).statusCode())
            assertEquals(404, h.upload(alice, "hello".toByteArray()).statusCode())
            assertFalse(Files.exists(h.unifiedDir))
        }

    @Test
    fun twoAccountsConcurrentUploadDeduplicatesBlobAndJobWhileIsolatingShelvesAndDevices() = runBlocking {
        Harness().use { h ->
            val alice1 = h.exchangeSable(TOK_ALPHA_1)
            val alice2 = h.exchangeSable(TOK_ALPHA_2)
            val bob = h.exchangeSable(TOK_BETA_1)
            val carol = h.exchangeSable(TOK_GAMMA_1)
            val pairedOnly = h.pairFriend("manual-friend")
            assertNotEquals(alice1.str("deviceId"), alice2.str("deviceId"))

            // Capability advertised true when enabled.
            assertTrue(parse(h.request("/v1/hello", credential = h.ownerCredential, private = true))["unifiedBooksSupported"]!!.jsonPrimitive.boolean)
            val aliceStatus = parse(h.request("/v1/public/status", credential = alice1))
            val alice2Status = parse(h.request("/v1/public/status", credential = alice2))
            assertTrue(aliceStatus["unifiedBooks"]!!.jsonPrimitive.boolean)
            assertEquals(aliceStatus.str("accountScope"), alice2Status.str("accountScope"))

            // Unauthenticated access returns 401.
            assertEquals(401, h.request("/v1/books").statusCode())
            assertEquals(401, h.upload(null, "synthetic content".toByteArray()).statusCode())

            // Client claiming a wrong SHA without matching bytes is rejected.
            val bookBytes = "Synthetic economics chapter one text for unified pipeline testing.".toByteArray()
            val expectedSha = sha256Hex(bookBytes)
            assertEquals(400, h.upload(alice1, bookBytes, declaredSha = "0".repeat(64)).statusCode())
            assertEquals(400, h.upload(alice1, ByteArray(0)).statusCode())

            // Concurrent upload of the exact same bytes by Alice and Bob.
            val (resA, resB) = listOf(
                async(Dispatchers.IO) { h.upload(alice1, bookBytes, title = "Alice Copy", declaredSha = expectedSha) },
                async(Dispatchers.IO) { h.upload(bob, bookBytes, title = "Bob Copy", declaredSha = expectedSha) },
            ).awaitAll()
            assertEquals(200, resA.statusCode(), resA.body())
            assertEquals(200, resB.statusCode(), resB.body())

            val bodyA = parse(resA)
            val bodyB = parse(resB)
            val shelfA = bodyA.str("shelfBookId")
            val shelfB = bodyB.str("shelfBookId")
            assertNotEquals(shelfA, shelfB)
            assertEquals(expectedSha, bodyA.str("sha256"))
            assertEquals(expectedSha, bodyB.str("sha256"))

            // Only 1 shared blob and 1 queued job (never prematurely ready in PR A).
            assertEquals(1, h.unified.sharedBlobCount())
            assertEquals(1, h.unified.sharedJobCount(expectedSha))
            assertEquals(2, h.unified.sharedRefCount(expectedSha))
            assertEquals("queued", bodyA["job"]!!.jsonObject.str("state"))
            assertEquals("queued", bodyB["job"]!!.jsonObject.str("state"))

            // Alice's second device (new deviceId + renamed username, same Sable sub) sees shelfA.
            val alice2List = parse(h.request("/v1/books", credential = alice2))["books"]!!.jsonArray
            assertEquals(1, alice2List.size)
            assertEquals(shelfA, alice2List.single().jsonObject.str("shelfBookId"))
            assertEquals(200, h.request("/v1/books/$shelfA/status", credential = alice2).statusCode())

            // Cross-account isolation: Alice cannot see Bob's book, Carol sees nothing, pairing-code device cannot claim Sable books.
            assertEquals(404, h.request("/v1/books/$shelfB/status", credential = alice1).statusCode())
            assertEquals(404, h.request("/v1/books/$shelfA/status", credential = bob).statusCode())
            assertEquals(0, parse(h.request("/v1/books", credential = carol))["books"]!!.jsonArray.size)
            assertEquals(404, h.request("/v1/books/$shelfA/status", credential = carol).statusCode())
            assertEquals(0, parse(h.request("/v1/books", credential = pairedOnly))["books"]!!.jsonArray.size)
            assertEquals(404, h.request("/v1/books/$shelfA/status", credential = pairedOnly).statusCode())

            // Re-uploading by Alice reuses the same shelfBookId and keeps refCount at 2.
            val reupload = h.upload(alice2, bookBytes, title = "Alice Renamed")
            assertEquals(200, reupload.statusCode())
            assertEquals(shelfA, parse(reupload).str("shelfBookId"))
            assertEquals(2, h.unified.sharedRefCount(expectedSha))
        }
    }

    @Test
    fun deleteIsIdempotentPreservesOtherAccountQuarantinesLastRefAndRecoversOrPurgesAfterRetention() = runBlocking {
        var now = 1_000_000L
        val retentionMs = 60_000L
        val cfg = UnifiedBookConfig(enabled = true, trashRetentionMs = retentionMs, clock = { now })
        Harness(cfg).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            val bob = h.exchangeSable(TOK_BETA_1)
            val carol = h.exchangeSable(TOK_GAMMA_1)
            val bytes = "Shared textbook payload for refcount deletion test.".toByteArray()
            val sha = sha256Hex(bytes)

            val shelfA = parse(h.upload(alice, bytes, title = "Book A")).str("shelfBookId")
            val shelfB = parse(h.upload(bob, bytes, title = "Book B")).str("shelfBookId")
            val scopeA = UnifiedBookScope.fromCall(alice.str("deviceId"), h.actor.execute { friendName(alice.str("deviceId")) })
            val scopeB = UnifiedBookScope.fromCall(bob.str("deviceId"), h.actor.execute { friendName(bob.str("deviceId")) })
            h.unified.saveAccountNoteForTest(scopeA, shelfA, "n1", """{"note":"alice"}""")
            h.unified.saveAccountNoteForTest(scopeB, shelfB, "n1", """{"note":"bob"}""")

            // Alice deletes -> Alice's book & notes gone, Bob's book & notes intact, blob still ACTIVE.
            assertEquals(200, h.request("/v1/books/$shelfA", "DELETE", credential = alice).statusCode())
            assertEquals(0, h.unified.accountNoteCount(scopeA, shelfA))
            assertEquals(1, h.unified.accountNoteCount(scopeB, shelfB))
            assertEquals(1, h.unified.sharedRefCount(sha))
            assertFalse(h.unified.isTrashed(sha))
            assertEquals(200, h.request("/v1/books/$shelfB/status", credential = bob).statusCode())

            // Duplicate delete by Alice is idempotent (404) and does not decrement Bob's ref.
            assertEquals(404, h.request("/v1/books/$shelfA", "DELETE", credential = alice).statusCode())
            assertEquals(1, h.unified.sharedRefCount(sha))

            // Bob deletes -> last ref removed -> quarantined into trash, NOT immediately purged.
            assertEquals(200, h.request("/v1/books/$shelfB", "DELETE", credential = bob).statusCode())
            assertEquals(0, h.unified.accountNoteCount(scopeB, shelfB))
            assertEquals(0, h.unified.sharedRefCount(sha))
            assertEquals(0, h.unified.sharedBlobCount())
            assertTrue(h.unified.isTrashed(sha))
            assertEquals(0, h.unified.purgeExpiredTrash())

            // Carol uploads within retention window -> recovers from trash.
            now += retentionMs / 2
            val shelfC = parse(h.upload(carol, bytes, title = "Book C")).str("shelfBookId")
            assertFalse(h.unified.isTrashed(sha))
            assertEquals(1, h.unified.sharedBlobCount())
            assertEquals(1, h.unified.sharedRefCount(sha))

            // Carol deletes -> trashed again -> after retention expires, purge cleans up permanently.
            assertEquals(200, h.request("/v1/books/$shelfC", "DELETE", credential = carol).statusCode())
            assertTrue(h.unified.isTrashed(sha))
            now += retentionMs + 1
            assertEquals(1, h.unified.purgeExpiredTrash())
            assertFalse(h.unified.isTrashed(sha))
            assertEquals(0, h.unified.sharedJobCount(sha))
        }
    }

    @Test
    fun uploadEnforcesFileLimitAccountQuotaAndDiskHeadroomWithHumanReadableErrors() {
        var fakeFreeSpace = 10_000L
        val cfg = UnifiedBookConfig(
            enabled = true,
            maxFileBytes = 500L,
            accountQuotaBytes = 800L,
            minFreeDiskBytes = 1_000L,
            usableSpaceProvider = { fakeFreeSpace },
        )
        Harness(cfg).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)

            // Exceeds per-file limit -> 413 "文件太大"
            val tooLarge = h.upload(alice, ByteArray(600) { 'a'.code.toByte() }, title = "Too Large")
            assertEquals(413, tooLarge.statusCode())
            assertEquals("文件太大", parse(tooLarge).str("error"))

            // First 450-byte book succeeds; second 450-byte book exceeds 800-byte account quota -> 413
            assertEquals(200, h.upload(alice, ByteArray(450) { 'b'.code.toByte() }, title = "Book 1").statusCode())
            val overQuota = h.upload(alice, ByteArray(450) { 'c'.code.toByte() }, title = "Book 2")
            assertEquals(413, overQuota.statusCode())
            assertEquals("存储空间不足，请稍后再试", parse(overQuota).str("error"))

            // Low disk headroom -> 507
            val bob = h.exchangeSable(TOK_BETA_1)
            fakeFreeSpace = 1_100L
            val noDisk = h.upload(bob, ByteArray(200) { 'd'.code.toByte() }, title = "Disk Full")
            assertEquals(507, noDisk.statusCode())
            assertEquals("存储空间不足，请稍后再试", parse(noDisk).str("error"))

            // No temp files leaked after rejections.
            val tmpDir = h.unifiedDir.resolve("shared/tmp")
            val leftover = if (Files.exists(tmpDir)) Files.list(tmpDir).use { it.toList() } else emptyList()
            assertTrue(leftover.isEmpty(), "Expected no leftover temp files, found $leftover")
        }
    }

    @Test
    fun unifiedShelfBookEliminatesLegacyCatalog409OnExplain() {
        Harness(UnifiedBookConfig(enabled = true), withCatalog = true).use { h ->
            val content = "Synthetic health economics paragraph for 409 regression check."
            val bytes = content.toByteArray()
            val sha = h.writeCatalogBook(bytes, allowPersonalImport = false)
            val pageSha = sha256Hex(bytes)

            val explainBody = json(
                "requestId" to "ab1823",
                "kind" to "explain",
                "selection" to content,
                "blocks" to JsonArray(listOf(json("id" to "$sha:p1:0", "text" to content))),
                "localSource" to json(
                    "schema" to 1,
                    "book" to sha,
                    "format" to "txt",
                    "title" to "Synthetic Course Book",
                    "consented" to true,
                    "pages" to JsonArray(
                        listOf(json("number" to 1, "length" to content.length, "ocr" to false, "sha256" to pageSha)),
                    ),
                ),
            ).toString()

            // Before unified upload, catalog reservation still blocks with 409.
            assertEquals(409, h.request("/v1/library/explain", "POST", h.ownerCredential, explainBody, private = true).statusCode())

            // After uploading via unified endpoint, 409 is resolved for this owner scope.
            val uploadRes = h.upload(h.ownerCredential, bytes, title = "Synthetic Course Book", private = true)
            assertEquals(200, uploadRes.statusCode(), uploadRes.body())
            val shelfBookId = parse(uploadRes).str("shelfBookId")

            h.reply()
            val ok = h.request("/v1/library/explain", "POST", h.ownerCredential, explainBody, private = true)
            assertEquals(200, ok.statusCode(), ok.body())

            // After deleting from unified shelf, explain fails closed with 409 again.
            assertEquals(200, h.request("/v1/books/$shelfBookId", "DELETE", h.ownerCredential, private = true).statusCode())
            assertEquals(409, h.request("/v1/library/explain", "POST", h.ownerCredential, explainBody, private = true).statusCode())
        }
    }

    @Test
    fun interruptedDeleteAfterShelfCommitRecoversViaRetryAndStartupReconciliation() = runBlocking {
        var now = 2_000_000L
        val cfg = UnifiedBookConfig(enabled = true, trashRetentionMs = 60_000L, clock = { now })
        Harness(cfg).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            val bob = h.exchangeSable(TOK_BETA_1)
            val bytes = "Crash-recovery shared payload for pending_deletes.".toByteArray()
            val sha = sha256Hex(bytes)

            val shelfA = parse(h.upload(alice, bytes, title = "Crash Book A")).str("shelfBookId")
            val shelfB = parse(h.upload(bob, bytes, title = "Crash Book B")).str("shelfBookId")
            val scopeA = UnifiedBookScope.fromCall(alice.str("deviceId"), h.actor.execute { friendName(alice.str("deviceId")) })
            val scopeB = UnifiedBookScope.fromCall(bob.str("deviceId"), h.actor.execute { friendName(bob.str("deviceId")) })

            // Simulate crash right after writing pending_deletes + committing Alice's shelf delete, before shared_blob_refs unbind.
            h.unified.recordPendingDeleteForTest(scopeA, shelfA, sha)
            h.unified.deleteOnlyShelfRowForTest(scopeA, shelfA)
            assertEquals(2, h.unified.sharedRefCount(sha))

            // Retry DELETE HTTP call succeeds even though shelf_books row is already gone, releasing Alice's ref.
            assertEquals(200, h.request("/v1/books/$shelfA", "DELETE", credential = alice).statusCode())
            assertEquals(1, h.unified.sharedRefCount(sha))
            assertFalse(h.unified.isTrashed(sha))

            // Simulate crash for Bob's final ref as well; startup reconcilePendingDeletes finishes unbind and quarantines blob.
            h.unified.recordPendingDeleteForTest(scopeB, shelfB, sha)
            h.unified.deleteOnlyShelfRowForTest(scopeB, shelfB)
            assertEquals(1, h.unified.sharedRefCount(sha))

            assertEquals(1, h.unified.reconcilePendingDeletes())
            assertEquals(0, h.unified.sharedRefCount(sha))
            assertTrue(h.unified.isTrashed(sha))

            // After retention expires, scheduled purge removes files first and then drops DB rows.
            now += 60_001L
            assertEquals(1, h.unified.purgeExpiredTrash())
            assertFalse(h.unified.isTrashed(sha))
            assertEquals(0, h.unified.sharedJobCount(sha))
        }
    }
}
