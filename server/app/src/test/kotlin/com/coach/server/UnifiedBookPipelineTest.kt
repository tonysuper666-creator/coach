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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UnifiedBookPipelineTest {
    private class Harness(
        val config: UnifiedBookConfig = UnifiedBookConfig(enabled = true),
        val withCatalog: Boolean = false,
        processor: UnifiedBookProcessor = DefaultUnifiedBookProcessor(),
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
        val unified = UnifiedBookPipeline(unifiedDir, config, processor)
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

        fun requestBytes(
            path: String,
            credential: JsonObject? = null,
            private: Boolean = false,
        ): HttpResponse<ByteArray> {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(private)}$path"))
                .timeout(java.time.Duration.ofSeconds(15))
            if (credential != null) {
                req.header("X-Coach-Device", credential.str("deviceId"))
                    .header("Authorization", "Bearer ${credential.str("token")}")
            }
            return client.send(req.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        }

        fun exchangeResponse(sableToken: String): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(false)}/v1/public/sable"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer $sableToken")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            return client.send(req, HttpResponse.BodyHandlers.ofString())
        }

        fun exchangeSable(sableToken: String): JsonObject {
            val res = exchangeResponse(sableToken)
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
            assertEquals(401, h.request("/v1/books/sb_000000000000000000000001", "DELETE").statusCode())
            assertEquals(401, h.request("/v1/books", private = true).statusCode())

            // Capability flags advertise false when disabled.
            val hello = parse(h.request("/v1/hello", credential = h.ownerCredential, private = true))
            assertFalse(hello["unifiedBooksSupported"]?.jsonPrimitive?.boolean == true)
            val status = parse(h.request("/v1/public/status", credential = alice))
            assertFalse(status["unifiedBooks"]?.jsonPrimitive?.boolean == true)

            // Authenticated unified routes fail closed with 404 and create no filesystem artifacts.
            assertEquals(404, h.request("/v1/books", credential = alice).statusCode())
            assertEquals(404, h.upload(alice, "hello".toByteArray()).statusCode())
            assertEquals(404, h.request("/v1/books/sb_000000000000000000000001", "DELETE", credential = alice).statusCode())
            assertFalse(Files.exists(h.unifiedDir))
        }

    @Test
    fun twoAccountsConcurrentUploadDeduplicatesBlobAndJobWhileIsolatingShelvesAndDevices() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true, autoProcess = false)).use { h ->
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

            // Cross-account isolation on delete: unauthenticated -> 401; Bob/Carol/pairedOnly cannot delete Alice's shelfBookId.
            assertEquals(401, h.request("/v1/books/$shelfA", "DELETE").statusCode())
            assertEquals(404, h.request("/v1/books/$shelfA", "DELETE", credential = bob).statusCode())
            assertEquals(404, h.request("/v1/books/$shelfA", "DELETE", credential = carol).statusCode())
            assertEquals(404, h.request("/v1/books/$shelfA", "DELETE", credential = pairedOnly).statusCode())
            assertEquals(2, h.unified.sharedRefCount(expectedSha))

            // Alice deletes shelfA: Alice's shelf record is gone, Bob's shelfB and shared blob remain active.
            val delA = h.request("/v1/books/$shelfA", "DELETE", credential = alice1)
            assertEquals(200, delA.statusCode(), delA.body())
            assertEquals(404, h.request("/v1/books/$shelfA/status", credential = alice2).statusCode())
            assertEquals(0, parse(h.request("/v1/books", credential = alice1))["books"]!!.jsonArray.size)
            assertEquals(200, h.request("/v1/books/$shelfB/status", credential = bob).statusCode())
            assertEquals(1, h.unified.sharedRefCount(expectedSha))
            assertFalse(h.unified.isTrashed(expectedSha))

            // Alice re-imports -> gets a new shelfBookId; replaying old DELETE /v1/books/$shelfA leaves the new import intact.
            val reimportA = h.upload(alice2, bookBytes, title = "Alice Reimported")
            assertEquals(200, reimportA.statusCode())
            val shelfA2 = parse(reimportA).str("shelfBookId")
            assertNotEquals(shelfA, shelfA2)
            assertEquals(2, h.unified.sharedRefCount(expectedSha))
            repeat(2) {
                assertEquals(404, h.request("/v1/books/$shelfA", "DELETE", credential = alice1).statusCode())
            }
            assertEquals(200, h.request("/v1/books/$shelfA2/status", credential = alice1).statusCode())
            assertEquals(2, h.unified.sharedRefCount(expectedSha))

            // Unbinding every remaining shelf record moves the shared blob into trash.
            assertEquals(200, h.request("/v1/books/$shelfA2", "DELETE", credential = alice1).statusCode())
            assertEquals(1, h.unified.sharedRefCount(expectedSha))
            assertFalse(h.unified.isTrashed(expectedSha))
            assertEquals(200, h.request("/v1/books/$shelfB", "DELETE", credential = bob).statusCode())
            assertEquals(0, h.unified.sharedRefCount(expectedSha))
            assertTrue(h.unified.isTrashed(expectedSha))
            assertFalse(Files.exists(h.unified.blobPathForVerify(expectedSha)))
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

            // Deleting the owner's unified shelf book unbinds it cleanly and restores legacy 409 check.
            assertEquals(200, h.request("/v1/books/$shelfBookId", "DELETE", h.ownerCredential, private = true).statusCode())
            assertEquals(409, h.request("/v1/library/explain", "POST", h.ownerCredential, explainBody, private = true).statusCode())
        }
    }

    @Test
    fun crashRecoveryAndTrashPurgeRespectRetentionAndReupload() = runBlocking {
        var fakeNow = 1_000_000L
        val retentionMs = 7L * 24L * 60L * 60L * 1000L
        val root = Files.createTempDirectory("coach-unified-purge-")
        try {
            val cfg = UnifiedBookConfig(enabled = true, trashRetentionMs = retentionMs, clock = { fakeNow })
            val alice = UnifiedBookScope.fromCall("alice-device", "sable:${"a".repeat(64)}")
            val bob = UnifiedBookScope.fromCall("bob-device", "sable:${"b".repeat(64)}")
            val bytes = "Synthetic crash recovery and trash purge chapter.".toByteArray()

            val p1 = UnifiedBookPipeline(root, cfg)
            val a1 = p1.upload(alice, "txt", "Book", null, bytes.size.toLong(), io.ktor.utils.io.ByteReadChannel(bytes))
            val b1 = p1.upload(bob, "txt", "Book", null, bytes.size.toLong(), io.ktor.utils.io.ByteReadChannel(bytes))

            // Simulate crash right after recording pending delete & deleting Alice's shelf row.
            p1.recordPendingDeleteForTest(alice, a1.shelfBookId, a1.sha256)
            p1.deleteOnlyShelfRowForTest(alice, a1.shelfBookId)

            val p2 = UnifiedBookPipeline(root, cfg)
            assertEquals(1, p2.reconcilePendingDeletes())
            assertEquals(1, p2.sharedRefCount(a1.sha256))
            assertFalse(p2.isTrashed(a1.sha256))

            // Unbind Bob -> last ref gone -> moves to trash.
            assertTrue(p2.deleteShelfBook(bob, b1.shelfBookId))
            assertEquals(0, p2.sharedRefCount(b1.sha256))
            assertTrue(p2.isTrashed(b1.sha256))

            // Before retention expires -> purgeExpiredTrash keeps it.
            fakeNow += retentionMs - 1L
            assertEquals(0, p2.purgeExpiredTrash())
            assertTrue(p2.isTrashed(b1.sha256))

            // Once retention expires -> purgeExpiredTrash deletes trash file and shared metadata.
            fakeNow += 2L
            assertEquals(1, p2.purgeExpiredTrash())
            assertFalse(p2.isTrashed(b1.sha256))
            assertEquals(0, p2.sharedBlobCount())
            assertEquals(0, p2.sharedJobCount(b1.sha256))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun buildSyntheticBookmarkedPdf(): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val pageTexts = listOf(
                "Copyright 2026 Synthetic Press. ISBN 0-000-00000-0. All rights reserved.",
                "Chapter 1 Foundations of Supply and Demand\nMarkets coordinate incentives across buyers and sellers.",
                "Chapter 2 Welfare Analysis\nConsumer surplus measures gains from trade above market price.",
            )
            val pages = pageTexts.map { text ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(50f, 700f)
                    for (line in text.lines()) {
                        cs.showText(line)
                        cs.newLineAtOffset(0f, -18f)
                    }
                    cs.endText()
                }
                page
            }
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            fun addBookmark(title: String, pageIdx: Int) {
                val dest = PDPageFitDestination().apply { page = pages[pageIdx] }
                val item = PDOutlineItem().apply {
                    this.title = title
                    this.destination = dest
                }
                outline.addLast(item)
            }
            addBookmark("Copyright", 0)
            addBookmark("Chapter 1 Foundations of Supply and Demand", 1)
            addBookmark("Chapter 2 Welfare Analysis", 2)
            doc.save(out)
        }
        return out.toByteArray()
    }

    @Test
    fun serverProcessesSameBookOnlyOnceAcrossTwoAccountsAndExposesProgressAndSkeletonArtifact() = runBlocking {
        val invocations = AtomicInteger(0)
        val reached40 = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()
        val delegate = DefaultUnifiedBookProcessor()
        val gatedProcessor = UnifiedBookProcessor { blob, format, title, sha, parserVersion, report ->
            invocations.incrementAndGet()
            delegate.process(blob, format, title, sha, parserVersion) { pct, label ->
                report(pct, label)
                if (pct == 40) {
                    reached40.complete(Unit)
                    runBlocking { allowFinish.await() }
                }
            }
        }

        Harness(UnifiedBookConfig(enabled = true, autoProcess = true), processor = gatedProcessor).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            val bob = h.exchangeSable(TOK_BETA_1)
            val pdfBytes = buildSyntheticBookmarkedPdf()
            val expectedSha = sha256Hex(pdfBytes)

            val uploadA = h.upload(alice, pdfBytes, format = "pdf", title = "Synthetic Economics", declaredSha = expectedSha)
            assertEquals(200, uploadA.statusCode(), uploadA.body())
            val shelfA = parse(uploadA).str("shelfBookId")

            // While processing is paused at 40%, status reports exact factual progress without blocking reads.
            reached40.await()
            val midStatus = parse(h.request("/v1/books/$shelfA/status", credential = alice))
            val midJob = midStatus["job"]!!.jsonObject
            assertEquals("processing", midJob.str("state"))
            assertEquals(40, midJob["progressPct"]!!.jsonPrimitive.int)
            assertEquals(1, midJob["processCount"]!!.jsonPrimitive.int)
            assertEquals(409, h.request("/v1/books/$shelfA/artifact", credential = alice).statusCode())

            // Allow worker to complete skeleton artifact.
            allowFinish.complete(Unit)
            var ready = false
            repeat(80) {
                val st = parse(h.request("/v1/books/$shelfA/status", credential = alice))["job"]!!.jsonObject
                if (st.str("state") == "ready") {
                    ready = true
                    return@repeat
                }
                delay(25)
            }
            assertTrue(ready, "Expected processing job to reach ready")
            assertEquals(1, invocations.get())
            assertEquals(1, h.unified.processCount(expectedSha))

            // Artifact reflects TON-1821 PDF outline & skips copyright page to bodyStartPage = 2.
            val artifactResA = h.request("/v1/books/$shelfA/artifact", credential = alice)
            assertEquals(200, artifactResA.statusCode(), artifactResA.body())
            val artifactJsonA = parse(artifactResA)["artifact"]!!.jsonObject
            assertEquals("PDF_OUTLINE", artifactJsonA.str("source"))
            assertEquals(3, artifactJsonA["pageCount"]!!.jsonPrimitive.int)
            assertEquals(2, artifactJsonA["bodyStartPage"]!!.jsonPrimitive.int)
            assertEquals(3, artifactJsonA["chapters"]!!.jsonArray.size)

            // Account B uploads the exact same PDF: server reuses processed artifact without reprocessing (processCount stays 1).
            val uploadB = h.upload(bob, pdfBytes, format = "pdf", title = "Bob Economics", declaredSha = expectedSha)
            assertEquals(200, uploadB.statusCode(), uploadB.body())
            val bodyB = parse(uploadB)
            val shelfB = bodyB.str("shelfBookId")
            assertNotEquals(shelfA, shelfB)
            assertTrue(bodyB["deduplicated"]!!.jsonPrimitive.boolean)
            val jobB = bodyB["job"]!!.jsonObject
            assertEquals("ready", jobB.str("state"))
            assertEquals(1, jobB["processCount"]!!.jsonPrimitive.int)
            delay(150)
            assertEquals(1, invocations.get(), "Second account upload must reuse processed artifact without reprocessing")
            assertEquals(1, h.unified.processCount(expectedSha))

            val artifactResB = h.request("/v1/books/$shelfB/artifact", credential = bob)
            assertEquals(200, artifactResB.statusCode(), artifactResB.body())
            assertEquals(1, parse(artifactResB)["job"]!!.jsonObject["processCount"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun accountIsolationBlocksCrossAccountAndForgedIdsOnOriginalAndArtifact() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true, autoProcess = false)).use { h ->
            val alice1 = h.exchangeSable(TOK_ALPHA_1)
            val alice2 = h.exchangeSable(TOK_ALPHA_2)
            val bob = h.exchangeSable(TOK_BETA_1)
            val carol = h.exchangeSable(TOK_GAMMA_1)
            val pairedOnly = h.pairFriend("manual-friend-iso")

            val bookBytes = "# Chapter 1\nSynthetic isolated book bytes for pullback and isolation checks.".toByteArray()
            val expectedSha = sha256Hex(bookBytes)
            val shelfA = parse(h.upload(alice1, bookBytes, format = "md", title = "Alice Secret")).str("shelfBookId")
            assertTrue(h.unified.processNextJob("worker-iso"))

            // Alice's second device (fresh deviceId, same Sable sub) pulls back exact bytes & artifact.
            val origA2 = h.requestBytes("/v1/books/$shelfA/original", credential = alice2)
            assertEquals(200, origA2.statusCode())
            assertEquals(expectedSha, sha256Hex(origA2.body()))
            assertEquals(expectedSha, origA2.headers().firstValue("X-Coach-Book-Sha256").orElse(""))
            assertEquals(200, h.request("/v1/books/$shelfA/artifact", credential = alice2).statusCode())

            // Unauthenticated -> 401.
            assertEquals(401, h.requestBytes("/v1/books/$shelfA/original").statusCode())
            assertEquals(401, h.request("/v1/books/$shelfA/artifact").statusCode())
            assertEquals(401, h.request("/v1/account", "DELETE").statusCode())

            // Bob, Carol, pairing-only device, and forged IDs cannot read Alice's original or artifact.
            val forgedId = "sb_" + "f".repeat(24)
            for (other in listOf(bob, carol, pairedOnly)) {
                assertEquals(404, h.requestBytes("/v1/books/$shelfA/original", credential = other).statusCode())
                assertEquals(404, h.request("/v1/books/$shelfA/artifact", credential = other).statusCode())
                assertEquals(404, h.requestBytes("/v1/books/$forgedId/original", credential = other).statusCode())
                assertEquals(404, h.requestBytes("/v1/books/$expectedSha/original", credential = other).statusCode())
                assertEquals(404, h.requestBytes("/v1/books/../shared/blobs/$expectedSha.blob/original", credential = other).statusCode())
            }

            // Even after Bob uploads the exact same bytes, Bob STILL cannot use Alice's shelfBookId.
            val shelfB = parse(h.upload(bob, bookBytes, format = "md", title = "Bob Copy")).str("shelfBookId")
            assertEquals(404, h.requestBytes("/v1/books/$shelfA/original", credential = bob).statusCode())
            assertEquals(404, h.request("/v1/books/$shelfA/artifact", credential = bob).statusCode())
            assertEquals(200, h.requestBytes("/v1/books/$shelfB/original", credential = bob).statusCode())
        }
    }

    @Test
    fun accountDeletionCleansAccountRecordsAndOnlyTrashesUnreferencedSharedBlobsAndArtifacts() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true, autoProcess = false)).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            val bob = h.exchangeSable(TOK_BETA_1)

            val sharedBytes = "# Chapter 1\nShared textbook bytes.".toByteArray()
            val sharedSha = sha256Hex(sharedBytes)
            val aliceOnlyBytes = "# Chapter 1\nAlice private monograph bytes.".toByteArray()
            val aliceOnlySha = sha256Hex(aliceOnlyBytes)
            val bobOnlyBytes = "# Chapter 1\nBob private notes bytes.".toByteArray()
            val bobOnlySha = sha256Hex(bobOnlyBytes)

            h.upload(alice, sharedBytes, format = "md", title = "Shared Book")
            h.upload(alice, aliceOnlyBytes, format = "md", title = "Alice Private")
            val shelfBShared = parse(h.upload(bob, sharedBytes, format = "md", title = "Bob Shared")).str("shelfBookId")
            val shelfBOnly = parse(h.upload(bob, bobOnlyBytes, format = "md", title = "Bob Private")).str("shelfBookId")

            while (h.unified.processNextJob("worker-del")) { /* drain all 3 jobs */ }
            assertTrue(Files.isRegularFile(h.unified.artifactFilePath(sharedSha)))
            assertTrue(Files.isRegularFile(h.unified.artifactFilePath(aliceOnlySha)))
            assertTrue(Files.isRegularFile(h.unified.artifactFilePath(bobOnlySha)))

            // Alice deletes her account: her directory & devices are wiped; Alice-only blob+artifact move to trash;
            // Shared Book stays ACTIVE for Bob!
            val delAlice = h.request("/v1/account", "DELETE", credential = alice)
            assertEquals(200, delAlice.statusCode(), delAlice.body())
            val delAliceBody = parse(delAlice)
            assertTrue(delAliceBody["deleted"]!!.jsonPrimitive.boolean)
            assertEquals(2, delAliceBody["removedBooks"]!!.jsonPrimitive.int)
            assertEquals(1, delAliceBody["trashedSharedBlobs"]!!.jsonPrimitive.int)

            // Alice's token is revoked immediately.
            assertEquals(401, h.request("/v1/books", credential = alice).statusCode())
            // Alice-only blob & processed artifact are trashed; shared blob & artifact remain active for Bob.
            assertTrue(h.unified.isTrashed(aliceOnlySha))
            assertFalse(Files.exists(h.unified.blobPathForVerify(aliceOnlySha)))
            assertFalse(Files.exists(h.unified.artifactFilePath(aliceOnlySha)))
            assertFalse(h.unified.isTrashed(sharedSha))
            assertEquals(1, h.unified.sharedRefCount(sharedSha))
            assertTrue(Files.isRegularFile(h.unified.artifactFilePath(sharedSha)))
            assertEquals(200, h.requestBytes("/v1/books/$shelfBShared/original", credential = bob).statusCode())
            assertEquals(200, h.request("/v1/books/$shelfBShared/artifact", credential = bob).statusCode())
            assertEquals(200, h.requestBytes("/v1/books/$shelfBOnly/original", credential = bob).statusCode())

            // Now Bob deletes his account: remaining refs hit 0 -> shared + bob-only blobs & artifacts move to trash.
            val delBob = h.request("/v1/account", "DELETE", credential = bob)
            assertEquals(200, delBob.statusCode(), delBob.body())
            assertEquals(2, parse(delBob)["trashedSharedBlobs"]!!.jsonPrimitive.int)
            assertTrue(h.unified.isTrashed(sharedSha))
            assertTrue(h.unified.isTrashed(bobOnlySha))
            assertFalse(Files.exists(h.unified.artifactFilePath(sharedSha)))
            assertFalse(Files.exists(h.unified.artifactFilePath(bobOnlySha)))

            // Purging expired trash removes all blobs, artifacts, jobs, and registry rows completely.
            assertEquals(3, h.unified.purgeExpiredTrash(0L))
            assertEquals(0, h.unified.sharedBlobCount())
            assertEquals(0, h.unified.sharedJobCount(sharedSha))
            assertEquals(0, h.unified.sharedJobCount(aliceOnlySha))
            assertEquals(0, h.unified.sharedJobCount(bobOnlySha))
        }
    }

    @Test
    fun reuploadWithinTrashRetentionRestoresArtifactCleanly() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true, autoProcess = false)).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            val bob = h.exchangeSable(TOK_BETA_1)
            val bytes = "# Chapter 1\nReupload after trash test.".toByteArray()
            val sha = sha256Hex(bytes)

            val shelfA = parse(h.upload(alice, bytes, format = "md", title = "Reupload Book")).str("shelfBookId")
            assertTrue(h.unified.processNextJob("worker-reup"))
            assertEquals(200, h.request("/v1/books/$shelfA/artifact", credential = alice).statusCode())

            // Delete last ref -> trashed.
            assertEquals(200, h.request("/v1/books/$shelfA", "DELETE", credential = alice).statusCode())
            assertTrue(h.unified.isTrashed(sha))
            assertFalse(Files.exists(h.unified.artifactFilePath(sha)))

            // Re-upload by Bob within retention window restores active blob & artifact cleanly.
            val shelfB = parse(h.upload(bob, bytes, format = "md", title = "Bob Reupload")).str("shelfBookId")
            assertFalse(h.unified.isTrashed(sha))
            assertEquals(200, h.request("/v1/books/$shelfB/artifact", credential = bob).statusCode())
            assertEquals(1, h.unified.processCount(sha))
        }
    }

    @Test
    fun accountDeletionBlocksInFlightUploadCommitAndRetriesFailedDirCleanup() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true, autoProcess = false)).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            val bob = h.exchangeSable(TOK_BETA_1)
            val carol = h.exchangeSable(TOK_GAMMA_1)

            val sharedBytes = "# Chapter 1\nShared book kept for Bob after Alice deletes account.".toByteArray()
            val lateBytes = "# Chapter 1\nLate in-flight upload that must never commit after delete.".toByteArray()
            val lateSha = sha256Hex(lateBytes)
            val carolBytes = "# Chapter 1\nCarol book for failed directory cleanup retry.".toByteArray()
            val carolSha = sha256Hex(carolBytes)

            assertEquals(200, h.upload(alice, sharedBytes, format = "md", title = "Shared A").statusCode())
            val shelfBob = parse(h.upload(bob, sharedBytes, format = "md", title = "Shared B")).str("shelfBookId")

            val aliceDeviceId = alice.str("deviceId")
            val carolDeviceId = carol.str("deviceId")
            val aliceScope = UnifiedBookScope.fromCall(aliceDeviceId, h.actor.execute { friendName(aliceDeviceId) })
            val carolScope = UnifiedBookScope.fromCall(carolDeviceId, h.actor.execute { friendName(carolDeviceId) })

            val reachedCommit = CompletableDeferred<Unit>()
            val allowCommit = CompletableDeferred<Unit>()
            h.unified.beforeUploadCommitForTest = { _, sha ->
                if (sha == lateSha) {
                    reachedCommit.complete(Unit)
                    allowCommit.await()
                }
            }

            // Start an upload for Alice that pauses right after streaming, before committing refs/blob.
            val inFlightUpload = async(Dispatchers.IO) {
                h.upload(alice, lateBytes, format = "md", title = "Late Upload", declaredSha = lateSha)
            }
            reachedCommit.await()

            // Delete Alice's account while the upload is still in flight.
            val delAlice = h.request("/v1/account", "DELETE", credential = alice)
            assertEquals(200, delAlice.statusCode(), delAlice.body())
            assertTrue(parse(delAlice)["deleted"]!!.jsonPrimitive.boolean)
            assertFalse(Files.exists(h.unified.accountShelfFile(aliceScope).parent))

            // Release the paused upload: commit must be rejected with 401 and leave zero refs/blobs/account dir.
            allowCommit.complete(Unit)
            val lateRes = inFlightUpload.await()
            assertEquals(401, lateRes.statusCode(), lateRes.body())
            assertEquals(0, h.unified.sharedRefCount(lateSha))
            assertFalse(Files.exists(h.unified.blobPathForVerify(lateSha)))
            assertFalse(Files.exists(h.unified.accountShelfFile(aliceScope).parent))
            assertEquals(1, h.unified.sharedBlobCount())
            val tmpEntries = Files.list(h.root.resolve("unified-books/shared/tmp")).use { it.toList() }
            assertTrue(tmpEntries.isEmpty(), "Expected no residual upload temp files, found $tmpEntries")
            assertEquals(200, h.requestBytes("/v1/books/$shelfBob/original", credential = bob).statusCode())

            // Simulate directory cleanup failure on Carol's account deletion:
            // must return 503 (NOT 200), remain DELETING (blocking uploads), and finish cleanly on retry.
            h.unified.beforeUploadCommitForTest = null
            assertEquals(200, h.upload(carol, carolBytes, format = "md", title = "Carol Book").statusCode())
            h.unified.accountDirDeleterForTest = { false }
            val failedDelete = h.request("/v1/account", "DELETE", credential = carol)
            assertEquals(503, failedDelete.statusCode(), failedDelete.body())
            assertEquals("account_dir_cleanup_failed", parse(failedDelete).str("code"))
            assertEquals("DELETING", h.unified.accountDeletionStateForTest(carolScope))
            assertTrue(Files.exists(h.unified.accountShelfFile(carolScope).parent))
            assertTrue(h.unified.isTrashed(carolSha))

            // While stuck in DELETING, new uploads for Carol are blocked with 401.
            val blockedUpload = h.upload(carol, lateBytes, format = "md", title = "Blocked While Deleting")
            assertEquals(401, blockedUpload.statusCode(), blockedUpload.body())
            assertEquals("account_deleted", parse(blockedUpload).str("code"))
            assertEquals(0, h.unified.sharedRefCount(lateSha))

            // Once the directory can be removed, retry DELETE /v1/account succeeds and revokes Carol's session.
            h.unified.accountDirDeleterForTest = null
            val retryDelete = h.request("/v1/account", "DELETE", credential = carol)
            assertEquals(200, retryDelete.statusCode(), retryDelete.body())
            assertTrue(parse(retryDelete)["deleted"]!!.jsonPrimitive.boolean)
            assertEquals("DELETED", h.unified.accountDeletionStateForTest(carolScope))
            assertFalse(Files.exists(h.unified.accountShelfFile(carolScope).parent))
            assertEquals(401, h.request("/v1/books", credential = carol).statusCode())
        }
    }

    @Test
    fun accountDeletionNeverReportsSuccessWhilePrivateDataRemainsAndRecoveryUsesSameTail() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true, autoProcess = false)).use { h ->
            val dave = h.exchangeSable(TOK_GAMMA_1)
            val daveDevice = dave.str("deviceId")
            val daveFriend = checkNotNull(h.actor.execute { friendName(daveDevice) })
            val otherDevice = h.exchangeSable(TOK_ALPHA_1).str("deviceId")
            val mixedBackup = h.actor.execute { backup() }.resolve("coach.sqlite")
            val daveScope = UnifiedBookScope.fromCall(daveDevice, daveFriend)
            assertEquals(200, h.upload(dave, "# Chapter 1\nDave private book.".toByteArray(), format = "md", title = "Dave").statusCode())

            // A private record (explain / import history) lives under the friend's own directory.
            val privateRoot = h.root.resolve("friends").resolve(friendId(daveFriend))
            val record = Files.createDirectories(privateRoot.resolve("explain")).resolve("record.json")
            Files.writeString(record, "{}")

            // 1) Private-data cleanup fails: must be 503, stay DELETING, keep the record and the credential.
            val realFinalizer = checkNotNull(h.unified.accountDataFinalizer)
            val epoch = h.unified.beginAccountDeletion(daveScope,daveFriend)
            val windows = System.getProperty("os.name").lowercase().contains("win")
            val held = if (windows) java.io.FileInputStream(record.toFile()) else null // java.io opens without FILE_SHARE_DELETE
            if (!windows) h.unified.accountDataFinalizer = { throw java.io.IOException("simulated locked file") }
            try {
                val failed = h.request("/v1/account", "DELETE", credential = dave)
                assertEquals(503, failed.statusCode(), failed.body())
                assertEquals("account_data_cleanup_failed", parse(failed).str("code"))
                assertEquals("DELETING", h.unified.accountDeletionStateForTest(daveScope))
                assertEquals(409,h.exchangeResponse(TOK_GAMMA_1).statusCode())
                assertTrue(Files.exists(record))
            } finally {
                held?.close()
                h.unified.accountDataFinalizer = realFinalizer
            }

            // 2) Startup / hourly recovery runs the same tail: private data wiped, credential revoked, then DELETED.
            h.unified.reconcilePendingDeletes()
            assertEquals("DELETED", h.unified.accountDeletionStateForTest(daveScope))
            assertFalse(Files.exists(privateRoot))
            assertFalse(h.actor.execute { pairedDevices().any { it.id == daveDevice } })
            java.sql.DriverManager.getConnection("jdbc:sqlite:$mixedBackup").use { saved ->
                saved.prepareStatement("SELECT count(*) FROM devices JOIN sable_grants USING(device_id) WHERE device_id=?").use { q ->
                    for ((id,expected) in listOf(daveDevice to 0,otherDevice to 1)) {
                        q.setString(1,id)
                        q.executeQuery().use { r -> assertTrue(r.next()); assertEquals(expected,r.getInt(1)) }
                    }
                }
            }
            assertEquals(401, h.request("/v1/books", credential = dave).statusCode())
            val registeredAgain = h.exchangeSable(TOK_GAMMA_1)
            val newBook = h.upload(registeredAgain, "New synthetic edition.".toByteArray(), format="md", title="New edition")
            assertEquals(200,newBook.statusCode())
            val shelfId = parse(newBook).str("shelfBookId")
            assertEquals(0,h.unified.deleteAccount(daveScope,expectedDeletionEpoch=epoch).removedBooks)
            val shelf = h.request("/v1/books",credential=registeredAgain)
            assertEquals(200,shelf.statusCode())
            assertTrue(shelf.body().contains(shelfId))
        }
    }

    @Test
    fun openingAReadyBookRecordedBeforeBadPagesBackfillsSkippedPagesWithoutReupload() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true, autoProcess = true)).use { h ->
            val alice = h.exchangeSable(TOK_ALPHA_1)
            val pdf = damagedPagePdfBytes(damagedIndex = 3)
            val sha = sha256Hex(pdf)
            val upload = h.upload(alice, pdf, format = "pdf", title = "Torn Synthetic", declaredSha = sha)
            assertEquals(200, upload.statusCode(), upload.body())
            val shelf = parse(upload).str("shelfBookId")
            var ready = false
            repeat(100) {
                val state = parse(h.request("/v1/books/$shelf/status", credential = alice))["job"]!!.jsonObject.str("state")
                if (state == "ready") {
                    ready = true
                    return@repeat
                }
                delay(50)
            }
            assertTrue(ready, "processing did not finish")
            assertEquals(1, h.unified.processCount(sha))
            val current = h.request("/v1/books/$shelf/artifact", credential = alice)
            assertEquals(200, current.statusCode(), current.body())
            assertEquals(1, parse(current)["job"]!!.jsonObject["processCount"]!!.jsonPrimitive.int)
            assertTrue(parse(current)["artifact"]!!.jsonObject["skippedPages"]!!.jsonArray.isNotEmpty())

            Files.writeString(
                h.unified.artifactFilePath(sha),
                """{"schema":2,"sha256":"$sha","parserVersion":"v2-skeleton","format":"pdf","pageCount":4,"source":"PDF_OUTLINE"}""",
            )
            val opened = h.request("/v1/books/$shelf/artifact", credential = alice)
            assertEquals(200, opened.statusCode(), opened.body())
            val body = parse(opened)
            val artifact = body["artifact"]!!.jsonObject
            assertEquals(3, artifact["schema"]!!.jsonPrimitive.int)
            assertEquals(listOf(4), artifact["skippedPages"]!!.jsonArray.map { it.jsonPrimitive.int })
            assertEquals(2, body["job"]!!.jsonObject["processCount"]!!.jsonPrimitive.int)
            assertEquals(2, h.unified.processCount(sha))

            val again = h.request("/v1/books/$shelf/artifact", credential = alice)
            assertEquals(200, again.statusCode(), again.body())
            assertEquals(2, parse(again)["job"]!!.jsonObject["processCount"]!!.jsonPrimitive.int)
            assertEquals(listOf(4), parse(again)["artifact"]!!.jsonObject["skippedPages"]!!.jsonArray.map { it.jsonPrimitive.int })
        }
    }

    private fun damagedPagePdfBytes(damagedIndex: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            repeat(4) { index ->
                val page = PDPage()
                pdf.addPage(page)
                if (index == 0) {
                    val outline = PDDocumentOutline()
                    pdf.documentCatalog.documentOutline = outline
                    val dest = PDPageFitDestination().apply { this.page = page }
                    outline.addLast(PDOutlineItem().apply { title = "1 Body"; destination = dest })
                }
                if (index == damagedIndex) return@repeat
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("GOOD-PAGE-${index + 1}")
                    stream.endText()
                }
            }
            val broken = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.TYPE0)
                setItem(COSName.BASE_FONT, COSName.getPDFName("BrokenPage"))
            }
            val fonts = COSDictionary()
            fonts.setItem(COSName.getPDFName("F1"), broken)
            val resources = PDResources()
            resources.cosObject.setItem(COSName.FONT, fonts)
            val torn = pdf.getPage(damagedIndex)
            torn.resources = resources
            val contents = PDStream(pdf)
            contents.createOutputStream().use { stream ->
                stream.write("BT /F1 12 Tf 72 700 Td (GOOD-PAGE-${damagedIndex + 1}) Tj ET\n".toByteArray(Charsets.US_ASCII))
            }
            torn.setContents(contents)
            pdf.save(out)
        }
        return out.toByteArray()
    }
}
