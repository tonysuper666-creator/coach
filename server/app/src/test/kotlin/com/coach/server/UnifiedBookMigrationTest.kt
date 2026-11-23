package com.coach.server

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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedBookMigrationTest {
    private class Harness(
        val config: UnifiedBookConfig = UnifiedBookConfig(enabled = true),
    ) : AutoCloseable {
        val root: Path = Files.createTempDirectory("coach-book-migration-")
        val dataDir: Path = Files.createDirectories(root.resolve("data"))
        val readerDir: Path = Files.createDirectories(root.resolve("reader-catalog"))
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adminToken = "isolated-owner-token-32-characters"
        val store = LedgerStore(dataDir).also { it.enroll("desktop", adminToken) }
        val actor = LedgerActor(jobs, store)
        val friends = FriendAccess(dataDir.resolve("friends"), root.resolve("state"), root.resolve("friends.json"))
        val unifiedDir: Path = dataDir.resolve("unified-books")
        val unified = UnifiedBookPipeline(unifiedDir, config)
        val migration = UnifiedBookMigration(dataDir, readerDir, unified, adminDevice = "desktop") {
            actor.execute { pairedDevices() }
        }

        var sableLookup: (String) -> SableIdentity? = { token ->
            when (token) {
                TOK_ALICE_1 -> SableIdentity("sub-alice-mig", "alice-secret-user")
                TOK_ALICE_2 -> SableIdentity("sub-alice-mig", "alice-renamed-user")
                TOK_BOB_1 -> SableIdentity("sub-bob-mig", "bob-secret-user")
                else -> null
            }
        }

        init {
            friends.sable = SableSessions(actor, SableVault(root.resolve("vault")), { token -> sableLookup(token) })
        }

        val owner = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), adminDevice = "desktop", friends = friends, publicOnly = false, unifiedBooks = unified)
            unifiedBookRoutes(actor, unified, friends)
            unifiedBookMigrationRoutes(migration, unified, adminDevice = "desktop")
        }.apply { start(false) }

        val public = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), adminDevice = "desktop", friends = friends, publicOnly = true, unifiedBooks = unified)
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
            private: Boolean = true,
        ): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(private)}$path"))
                .timeout(java.time.Duration.ofSeconds(15))
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

        fun pairDevice(name: String, friend: String? = null): JsonObject {
            val codeBody = if (friend == null) "" else json("friend" to friend).toString()
            val code = request("/v1/pairing-code", "POST", ownerCredential, codeBody, private = true)
            assertEquals(200, code.statusCode(), code.body())
            val res = request(
                "/v1/pair",
                "POST",
                body = json("code" to parse(code).str("code"), "name" to name).toString(),
                private = friend == null,
            )
            assertEquals(200, res.statusCode(), res.body())
            return parse(res)
        }

        fun upload(
            credential: JsonObject,
            bytes: ByteArray,
            format: String = "txt",
            title: String = "Synthetic Upload",
            private: Boolean = false,
        ): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(private)}/v1/books/upload"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("X-Coach-Book-Format", format)
                .header("X-Coach-Book-Title", URLEncoder.encode(title, StandardCharsets.UTF_8))
                .header("X-Coach-Book-Sha256", sha256Hex(bytes))
                .header("X-Coach-Device", credential.str("deviceId"))
                .header("Authorization", "Bearer ${credential.str("token")}")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
            return client.send(req, HttpResponse.BodyHandlers.ofString())
        }

        fun writeLegacyReceiptRow(
            sourcesDir: Path,
            deviceId: String,
            bookSha: String,
            page: Int,
            start: Int,
            text: String,
            pageLength: Int = start + text.length + 10,
            ocr: Boolean = false,
            overrideSnapshot: String? = null,
        ) {
            Files.createDirectories(sourcesDir)
            val sqlite = sourcesDir.resolve("sources.sqlite")
            val snap = overrideSnapshot ?: json(
                "sha256" to sha256Hex("page-$page-$text".toByteArray()),
                "length" to pageLength,
                "ocr" to ocr,
            ).toString()
            DriverManager.getConnection("jdbc:sqlite:$sqlite").use { db ->
                db.createStatement().use { sql ->
                    sql.execute("CREATE TABLE IF NOT EXISTS blocks(device TEXT NOT NULL,book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,text TEXT NOT NULL,snapshot TEXT NOT NULL,PRIMARY KEY(device,book,page,start,end))")
                }
                db.prepareStatement("INSERT OR REPLACE INTO blocks VALUES(?,?,?,?,?,?,?)").use { sql ->
                    sql.setString(1, deviceId)
                    sql.setString(2, bookSha)
                    sql.setInt(3, page)
                    sql.setInt(4, start)
                    sql.setInt(5, start + text.length)
                    sql.setString(6, text)
                    sql.setString(7, snap)
                    sql.executeUpdate()
                }
            }
        }

        override fun close() {
            runBlocking {
                owner.stopSuspend(0, 500)
                public.stopSuspend(0, 500)
                actor.close()
            }
            jobs.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val TOK_ALICE_1 = "synthetic-sable-token-alice-mig-01"
        const val TOK_ALICE_2 = "synthetic-sable-token-alice-mig-02"
        const val TOK_BOB_1 = "synthetic-sable-token-bob-mig-0001"
        fun parse(res: HttpResponse<String>): JsonObject = LedgerJson.parseToJsonElement(res.body()).jsonObject
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun inventoryIsStrictlyReadOnlyOmitsAllTitlesAndProseAndRespectsAdminPermissionBoundaries() = runBlocking {
        Harness(UnifiedBookConfig(enabled = false)).use { h ->
            val alice = h.exchangeSable(TOK_ALICE_1)
            val friendName = h.actor.execute { friendName(alice.str("deviceId")) }!!
            val ownerIpad = h.pairDevice("Owner iPad")
            val revokedOwner = h.pairDevice("Revoked Owner Laptop")
            h.actor.execute { revokeDevice(revokedOwner.str("deviceId")) }

            // Write synthetic catalog with 1 valid book + 1 missing file + 1 tampered SHA.
            val validBytes = "SECRET_PROSE_CATALOG_ALPHA_999".toByteArray()
            val validSha = sha256Hex(validBytes)
            Files.write(h.readerDir.resolve("secret-catalog-file.txt"), validBytes)
            Files.write(h.readerDir.resolve("secret-tampered-file.txt"), "TAMPERED_SECRET_BYTES".toByteArray())
            val catalogJson = json(
                "schema" to 1,
                "documents" to JsonArray(
                    listOf(
                        json("id" to "secret-cat-ok", "course" to "SECRET_COURSE_101", "title" to "SECRET_TITLE_VALID", "file" to "secret-catalog-file.txt", "sha256" to validSha, "pages" to 5, "kind" to "textbook"),
                        json("id" to "secret-cat-miss", "course" to "SECRET_COURSE_101", "title" to "SECRET_TITLE_MISSING", "file" to "does-not-exist.txt", "sha256" to "1".repeat(64), "pages" to 3, "kind" to "reading"),
                        json("id" to "secret-cat-bad", "course" to "SECRET_COURSE_101", "title" to "SECRET_TITLE_TAMPERED", "file" to "secret-tampered-file.txt", "sha256" to "2".repeat(64), "pages" to 2, "kind" to "reading"),
                    ),
                ),
            )
            Files.writeString(h.readerDir.resolve("catalog.json"), catalogJson.toString())

            // Write legacy personal-imports & friend-imports receipts containing secret passages & IDs.
            val ownerImportSha = "a".repeat(64)
            h.writeLegacyReceiptRow(h.dataDir.resolve("personal-imports"), "desktop", ownerImportSha, 1, 0, "SECRET_OWNER_FRAGMENT_XYZ")
            h.writeLegacyReceiptRow(h.dataDir.resolve("personal-imports"), "secret-unknown-device", "b".repeat(64), 1, 0, "SECRET_UNKNOWN_FRAGMENT")
            h.writeLegacyReceiptRow(h.dataDir.resolve("personal-imports"), revokedOwner.str("deviceId"), "c".repeat(64), 1, 0, "SECRET_REVOKED_FRAGMENT")

            val aliceImportDir = h.dataDir.resolve("friends").resolve(friendId(friendName)).resolve("imports")
            h.writeLegacyReceiptRow(aliceImportDir, alice.str("deviceId"), "d".repeat(64), 2, 10, "SECRET_FRIEND_PROSE_777")

            // Permission boundary checks on private & public ports.
            for (path in listOf("/v1/admin/books/migration/inventory", "/v1/admin/books/migration/verify")) {
                assertEquals(401, h.request(path, "GET", credential = null, private = true).statusCode())
                assertEquals(404, h.request(path, "GET", credential = null, private = false).statusCode())
                assertEquals(404, h.request(path, "GET", credential = alice, private = false).statusCode())
                assertEquals(404, h.request(path, "GET", credential = alice, private = true).statusCode())
                assertEquals(403, h.request(path, "GET", credential = ownerIpad, private = true).statusCode())
            }
            assertEquals(401, h.request("/v1/admin/books/migration/run", "POST", credential = null, private = true).statusCode())
            assertEquals(404, h.request("/v1/admin/books/migration/run", "POST", credential = alice, private = false).statusCode())
            assertEquals(404, h.request("/v1/admin/books/migration/run", "POST", credential = alice, private = true).statusCode())
            assertEquals(403, h.request("/v1/admin/books/migration/run", "POST", credential = ownerIpad, private = true).statusCode())
            // Migration run fails closed with 409 when COACH_UNIFIED_BOOKS is disabled.
            assertEquals(409, h.request("/v1/admin/books/migration/run", "POST", credential = h.ownerCredential, private = true).statusCode())
            assertFalse(Files.exists(h.unifiedDir), "Disabled migrate must not create unified-books directory")

            // Read-only inventory works even when COACH_UNIFIED_BOOKS=0 and creates ZERO files/dirs.
            val invRes = h.request("/v1/admin/books/migration/inventory", "GET", credential = h.ownerCredential, private = true)
            assertEquals(200, invRes.statusCode(), invRes.body())
            assertFalse(Files.exists(h.unifiedDir), "Read-only inventory must never create unified-books directory")

            val rawBody = invRes.body()
            val forbiddenLeaks = listOf(
                "SECRET_PROSE", "SECRET_TITLE", "SECRET_COURSE", "SECRET_OWNER_FRAGMENT",
                "SECRET_UNKNOWN_FRAGMENT", "SECRET_REVOKED_FRAGMENT", "SECRET_FRIEND_PROSE",
                "secret-catalog-file", "secret-tampered-file", "secret-cat-ok", "secret-cat-miss",
                "secret-unknown-device", "alice-secret-user", alice.str("deviceId"), revokedOwner.str("deviceId"),
            )
            for (secret in forbiddenLeaks) {
                assertFalse(rawBody.contains(secret), "Inventory report leaked sensitive string '$secret': $rawBody")
            }

            val report = parse(invRes)
            val counts = report["counts"]!!.jsonObject
            assertEquals(3, counts["catalogTotal"]!!.jsonPrimitive.int)
            assertEquals(1, counts["catalogReady"]!!.jsonPrimitive.int)
            assertEquals(2, counts["catalogUnmatched"]!!.jsonPrimitive.int)
            assertEquals(3, counts["ownerReceiptsTotal"]!!.jsonPrimitive.int)
            assertEquals(1, counts["ownerReceiptsAwaitingFullUpload"]!!.jsonPrimitive.int)
            assertEquals(2, counts["ownerReceiptsUnmatched"]!!.jsonPrimitive.int)
            assertEquals(1, counts["friendReceiptsTotal"]!!.jsonPrimitive.int)
            assertEquals(1, counts["friendReceiptsAwaitingFullUpload"]!!.jsonPrimitive.int)

            val unmatched = report["unmatched"]!!.jsonArray.map { it.jsonObject }
            val reasons = unmatched.map { it.str("sourceKind") to it.str("reason") }.toSet()
            assertTrue(("catalog" to "missing_file") in reasons)
            assertTrue(("catalog" to "sha256_mismatch") in reasons)
            assertTrue(("owner_import_receipt" to "awaiting_full_upload") in reasons)
            assertTrue(("owner_import_receipt" to "owner_unverified") in reasons)
            assertTrue(("owner_import_receipt" to "revoked_device") in reasons)
            assertTrue(("friend_import_receipt" to "awaiting_full_upload") in reasons)
            assertTrue(unmatched.all { Regex("(cat|lb|pi|fi):[0-9a-f]{16}").matches(it.str("recordId")) })
        }
    }

    @Test
    fun idempotentMigrationPreservesLegacyStoresIsolatesAccountsAlignsPostUploadReceiptsAndVerifiesIntegrity() = runBlocking {
        Harness(UnifiedBookConfig(enabled = true)).use { h ->
            val alice1 = h.exchangeSable(TOK_ALICE_1)
            val alice2 = h.exchangeSable(TOK_ALICE_2)
            val bob = h.exchangeSable(TOK_BOB_1)
            val manualFriend = h.pairDevice("Manual PC", friend = "manual-friend-secret")
            val aliceFriendName = h.actor.execute { friendName(alice1.str("deviceId")) }!!
            val manualFriendName = h.actor.execute { friendName(manualFriend.str("deviceId")) }!!

            // 1. Legacy catalog book + local-books binding (both owner-only) + owner fragment receipt on the catalog book.
            val catalogBytes = "Synthetic economics chapter 1 original content for PR B migration.".toByteArray()
            val catalogSha = sha256Hex(catalogBytes)
            Files.write(h.readerDir.resolve("econ-ch1.txt"), catalogBytes)
            val localBindingBytes = "Synthetic local-binding textbook payload for PR B.".toByteArray()
            val localBindingSha = sha256Hex(localBindingBytes)
            Files.write(h.readerDir.resolve("local-bound.txt"), localBindingBytes)
            val pagesSnapshot = json(
                "schema" to 1,
                "book" to localBindingSha,
                "pages" to JsonArray(listOf(json("number" to 1, "text" to "Synthetic local-binding textbook payload for PR B."))),
            ).toString().toByteArray()
            val pagesSnapshotSha = sha256Hex(pagesSnapshot)
            Files.write(h.readerDir.resolve("local-bound-pages.json"), pagesSnapshot)

            Files.writeString(
                h.readerDir.resolve("catalog.json"),
                json(
                    "schema" to 1,
                    "documents" to JsonArray(
                        listOf(
                            json(
                                "id" to "econ-doc-1",
                                "course" to "ECON101",
                                "title" to "SECRET_MIG_CATALOG_TITLE",
                                "file" to "econ-ch1.txt",
                                "sha256" to catalogSha,
                                "pages" to 12,
                                "printedPageOffset" to 2,
                                "kind" to "textbook",
                                "explainSourceId" to "src:econ:1",
                            ),
                        ),
                    ),
                ).toString(),
            )
            Files.writeString(
                h.readerDir.resolve("local-books.json"),
                json(
                    "schema" to 1,
                    "books" to JsonArray(
                        listOf(
                            json(
                                "id" to "lb-doc-1",
                                "course" to "ECON101",
                                "title" to "SECRET_MIG_LOCAL_BINDING_TITLE",
                                "file" to "local-bound.txt",
                                "sha256" to localBindingSha,
                                "pagesFile" to "local-bound-pages.json",
                                "pagesSha256" to pagesSnapshotSha,
                                "explainSourceId" to "src:local:1",
                            ),
                        ),
                    ),
                ).toString(),
            )

            // Owner receipt matching catalogSha -> will align automatically right after catalog migration!
            h.writeLegacyReceiptRow(h.dataDir.resolve("personal-imports"), "desktop", catalogSha, 1, 0, "Synthetic economics")

            // Shared friend book: Bob uploads it FIRST, while Alice and manualFriend only have legacy fragment receipts.
            val sharedFriendBookBytes = "Shared friend book original bytes uploaded by Bob first, Alice later.".toByteArray()
            val sharedFriendSha = sha256Hex(sharedFriendBookBytes)
            assertEquals(200, h.upload(bob, sharedFriendBookBytes, title = "Bob Copy").statusCode())

            val aliceImportDir = h.dataDir.resolve("friends").resolve(friendId(aliceFriendName)).resolve("imports")
            val manualImportDir = h.dataDir.resolve("friends").resolve(friendId(manualFriendName)).resolve("imports")
            h.writeLegacyReceiptRow(aliceImportDir, alice1.str("deviceId"), sharedFriendSha, 1, 0, "Shared friend book")
            h.writeLegacyReceiptRow(manualImportDir, manualFriend.str("deviceId"), sharedFriendSha, 1, 0, "Shared friend book")
            // Also inject a spoofed receipt (Alice's device placed inside manualFriend's directory) -> must fail scope_mismatch!
            h.writeLegacyReceiptRow(manualImportDir, alice1.str("deviceId"), sharedFriendSha, 2, 0, "Spoofed cross-dir block")

            // Run 1 of migration.
            val run1Res = h.request("/v1/admin/books/migration/run", "POST", credential = h.ownerCredential, private = true)
            assertEquals(200, run1Res.statusCode(), run1Res.body())
            assertFalse(run1Res.body().contains("SECRET_MIG_"), "Migration report must not leak titles")
            val run1 = parse(run1Res)
            assertEquals(2, run1["migratedBooks"]!!.jsonPrimitive.int)
            assertEquals(1, run1["alignedReceipts"]!!.jsonPrimitive.int)
            assertEquals(1, run1["alignedBlocks"]!!.jsonPrimitive.int)
            assertEquals(0, run1["deduplicatedSkipped"]!!.jsonPrimitive.int)
            assertTrue(run1["verification"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)

            // Legacy files remain untouched on disk for rollback.
            assertTrue(Files.isRegularFile(h.readerDir.resolve("catalog.json")))
            assertTrue(Files.isRegularFile(h.readerDir.resolve("local-books.json")))
            assertTrue(Files.isRegularFile(h.dataDir.resolve("personal-imports/sources.sqlite")))
            assertTrue(Files.isRegularFile(aliceImportDir.resolve("sources.sqlite")))

            // Cross-account isolation: Alice and manualFriend still CANNOT see owner's books OR Bob's book!
            assertEquals(0, parse(h.request("/v1/books", "GET", credential = alice1, private = false))["books"]!!.jsonArray.size)
            assertEquals(0, parse(h.request("/v1/books", "GET", credential = manualFriend, private = false))["books"]!!.jsonArray.size)
            assertEquals(2, parse(h.request("/v1/books", "GET", credential = h.ownerCredential, private = true))["books"]!!.jsonArray.size)

            // Alice & manualFriend fragment receipts remain awaiting_full_upload (Bob's upload does NOT grant them access!).
            val run1Unmatched = run1["unmatched"]!!.jsonArray.map { it.jsonObject.str("reason") }
            assertEquals(2, run1Unmatched.count { it == "awaiting_full_upload" })
            assertEquals(1, run1Unmatched.count { it == "scope_mismatch" })

            // Run 2 (idempotent re-run): zero new books or receipts, 3 deduplicated skips (2 books + 1 owner receipt).
            val run2 = parse(h.request("/v1/admin/books/migration/run", "POST", credential = h.ownerCredential, private = true))
            assertEquals(0, run2["migratedBooks"]!!.jsonPrimitive.int)
            assertEquals(0, run2["alignedReceipts"]!!.jsonPrimitive.int)
            assertEquals(3, run2["deduplicatedSkipped"]!!.jsonPrimitive.int)
            assertTrue(run2["verification"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)

            // Reordering catalog.json (e.g. prepending an unmatched entry) must keep legacy_record_id stable and deduplicated.
            Files.writeString(
                h.readerDir.resolve("catalog.json"),
                json(
                    "schema" to 1,
                    "documents" to JsonArray(
                        listOf(
                            json("id" to "prepended-missing", "course" to "ECON101", "title" to "Missing Prepended", "file" to "missing.txt", "sha256" to "9".repeat(64), "pages" to 1, "kind" to "reading"),
                            json(
                                "id" to "econ-doc-1",
                                "course" to "ECON101",
                                "title" to "SECRET_MIG_CATALOG_TITLE",
                                "file" to "econ-ch1.txt",
                                "sha256" to catalogSha,
                                "pages" to 12,
                                "printedPageOffset" to 2,
                                "kind" to "textbook",
                                "explainSourceId" to "src:econ:1",
                            ),
                        ),
                    ),
                ).toString(),
            )
            val runReordered = parse(h.request("/v1/admin/books/migration/run", "POST", credential = h.ownerCredential, private = true))
            assertEquals(0, runReordered["migratedBooks"]!!.jsonPrimitive.int)
            assertEquals(3, runReordered["deduplicatedSkipped"]!!.jsonPrimitive.int)
            // Restore original catalog.json before subsequent assertions.
            Files.writeString(
                h.readerDir.resolve("catalog.json"),
                json(
                    "schema" to 1,
                    "documents" to JsonArray(
                        listOf(
                            json(
                                "id" to "econ-doc-1",
                                "course" to "ECON101",
                                "title" to "SECRET_MIG_CATALOG_TITLE",
                                "file" to "econ-ch1.txt",
                                "sha256" to catalogSha,
                                "pages" to 12,
                                "printedPageOffset" to 2,
                                "kind" to "textbook",
                                "explainSourceId" to "src:econ:1",
                            ),
                        ),
                    ),
                ).toString(),
            )

            // Now Alice uploads the full file from her SECOND device (same Sable sub, rotated deviceId/username).
            val aliceUpload = h.upload(alice2, sharedFriendBookBytes, title = "Alice Copy")
            assertEquals(200, aliceUpload.statusCode(), aliceUpload.body())
            assertEquals(2, h.unified.sharedRefCount(sharedFriendSha))

            // Run 3: Alice's old fragment receipt from device 1 now aligns cleanly to Alice's Sable scope shelf book!
            val run3 = parse(h.request("/v1/admin/books/migration/run", "POST", credential = h.ownerCredential, private = true))
            assertEquals(0, run3["migratedBooks"]!!.jsonPrimitive.int)
            assertEquals(1, run3["alignedReceipts"]!!.jsonPrimitive.int)
            assertEquals(1, run3["alignedBlocks"]!!.jsonPrimitive.int)
            assertEquals(3, run3["deduplicatedSkipped"]!!.jsonPrimitive.int)
            val run3Unmatched = run3["unmatched"]!!.jsonArray.map { it.jsonObject.str("reason") }
            // Only manualFriend's receipt still awaits full upload (+ 1 spoofed scope_mismatch).
            assertEquals(1, run3Unmatched.count { it == "awaiting_full_upload" })
            assertEquals(1, run3Unmatched.count { it == "scope_mismatch" })

            // Per-account verification checks books, legacy mappings, aligned receipts, refs, and blob hashes.
            val verify1 = parse(h.request("/v1/admin/books/migration/verify", "GET", credential = h.ownerCredential, private = true))
            assertTrue(verify1["ok"]!!.jsonPrimitive.boolean)
            assertEquals(3, verify1["globalSharedBlobs"]!!.jsonPrimitive.int) // catalog + localBinding + sharedFriend
            assertEquals(4, verify1["globalSharedRefs"]!!.jsonPrimitive.int)  // owner(2) + bob(1) + alice(1)
            val accounts = verify1["accounts"]!!.jsonArray.map { it.jsonObject }
            assertEquals(3, accounts.size)
            val ownerScopeKey = UnifiedBookScope.fromCall("desktop", null).scopeKey
            val aliceScopeKey = UnifiedBookScope.fromCall(alice1.str("deviceId"), aliceFriendName).scopeKey
            val ownerAcct = accounts.single { it.str("accountRef") == ownerScopeKey }
            val aliceAcct = accounts.single { it.str("accountRef") == aliceScopeKey }
            assertEquals(2, ownerAcct["shelfBooks"]!!.jsonPrimitive.int)
            assertEquals(2, ownerAcct["legacyMappings"]!!.jsonPrimitive.int)
            assertEquals(1, ownerAcct["alignedReceipts"]!!.jsonPrimitive.int)
            assertEquals(1, aliceAcct["shelfBooks"]!!.jsonPrimitive.int)
            assertEquals(1, aliceAcct["alignedReceipts"]!!.jsonPrimitive.int)

            // Negative integrity check: corrupting a shared blob on disk causes verify to flag corruptedBlobs and ok=false.
            Files.write(h.unified.blobPathForVerify(catalogSha), "corrupted-bytes".toByteArray())
            val verifyBroken = parse(h.request("/v1/admin/books/migration/verify", "GET", credential = h.ownerCredential, private = true))
            assertFalse(verifyBroken["ok"]!!.jsonPrimitive.boolean)
            val brokenOwner = verifyBroken["accounts"]!!.jsonArray.map { it.jsonObject }.single { it.str("accountRef") == ownerScopeKey }
            assertEquals(1, brokenOwner["corruptedBlobs"]!!.jsonPrimitive.int)
            assertFalse(brokenOwner["ok"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun migrationHandlesQuotaAndDiskLimitsCleanlyAndVerifyChecksNotesQaOverridesAndOrphans() = runBlocking {
        var fakeFreeSpace = 10_000L
        val cfg = UnifiedBookConfig(
            enabled = true,
            maxFileBytes = 500L,
            accountQuotaBytes = 600L,
            minFreeDiskBytes = 1_000L,
            usableSpaceProvider = { fakeFreeSpace },
        )
        Harness(cfg).use { h ->
            val b1 = ByteArray(400) { 'a'.code.toByte() }
            val b2 = ByteArray(400) { 'b'.code.toByte() }
            val sha1 = sha256Hex(b1)
            val sha2 = sha256Hex(b2)
            Files.write(h.readerDir.resolve("book1.txt"), b1)
            Files.write(h.readerDir.resolve("book2.txt"), b2)
            Files.writeString(
                h.readerDir.resolve("catalog.json"),
                json(
                    "schema" to 1,
                    "documents" to JsonArray(
                        listOf(
                            json("id" to "q-book-1", "course" to "Q101", "title" to "Quota Book One", "file" to "book1.txt", "sha256" to sha1, "pages" to 2, "kind" to "textbook"),
                            json("id" to "q-book-2", "course" to "Q101", "title" to "Quota Book Two", "file" to "book2.txt", "sha256" to sha2, "pages" to 2, "kind" to "textbook"),
                        ),
                    ),
                ).toString(),
            )

            // First book succeeds (400B <= 600B); second exceeds account quota -> unmatched with quota_exceeded and zero temp files left.
            val run = parse(h.request("/v1/admin/books/migration/run", "POST", credential = h.ownerCredential, private = true))
            assertEquals(1, run["migratedBooks"]!!.jsonPrimitive.int)
            assertEquals(listOf("quota_exceeded"), run["unmatched"]!!.jsonArray.map { it.jsonObject.str("reason") })
            assertTrue(run["verification"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
            val tmpDir = h.unifiedDir.resolve("shared/tmp")
            val leftovers = if (Files.exists(tmpDir)) Files.list(tmpDir).use { it.toList() } else emptyList()
            assertTrue(leftovers.isEmpty(), "Expected no leftover migration temp files, found $leftovers")

            // Populate account-owned notes, Q&A, overrides on the migrated shelf book and verify counts pass.
            val ownerScope = UnifiedBookScope.fromCall("desktop", null)
            val shelfId = h.unified.existingShelfBookIdByShaReadOnly(ownerScope, sha1)!!
            DriverManager.getConnection("jdbc:sqlite:${h.unified.accountShelfFile(ownerScope)}").use { db ->
                db.prepareStatement("INSERT INTO shelf_book_notes VALUES(?,?,?,?)").use {
                    it.setString(1, shelfId); it.setString(2, "n1"); it.setString(3, "{}"); it.setLong(4, 1L); it.executeUpdate()
                }
                db.prepareStatement("INSERT INTO shelf_book_qa VALUES(?,?,?,?)").use {
                    it.setString(1, shelfId); it.setString(2, "q1"); it.setString(3, "{}"); it.setLong(4, 1L); it.executeUpdate()
                }
                db.prepareStatement("INSERT INTO shelf_book_overrides VALUES(?,?,?,?)").use {
                    it.setString(1, shelfId); it.setString(2, "{}"); it.setString(3, "{}"); it.setLong(4, 1L); it.executeUpdate()
                }
            }
            val verifyClean = parse(h.request("/v1/admin/books/migration/verify", "GET", credential = h.ownerCredential, private = true))
            assertTrue(verifyClean["ok"]!!.jsonPrimitive.boolean)
            val acctClean = verifyClean["accounts"]!!.jsonArray.single().jsonObject
            assertEquals(1, acctClean["notes"]!!.jsonPrimitive.int)
            assertEquals(1, acctClean["qa"]!!.jsonPrimitive.int)
            assertEquals(1, acctClean["overrides"]!!.jsonPrimitive.int)
            assertEquals(0, acctClean["orphanAccountRecords"]!!.jsonPrimitive.int)

            // Inject an orphan note referencing a nonexistent shelf_book_id -> verify catches it.
            DriverManager.getConnection("jdbc:sqlite:${h.unified.accountShelfFile(ownerScope)}").use { db ->
                db.prepareStatement("INSERT INTO shelf_book_notes VALUES(?,?,?,?)").use {
                    it.setString(1, "sb_999999999999999999999999"); it.setString(2, "orphan"); it.setString(3, "{}"); it.setLong(4, 1L); it.executeUpdate()
                }
            }
            val verifyOrphan = parse(h.request("/v1/admin/books/migration/verify", "GET", credential = h.ownerCredential, private = true))
            assertFalse(verifyOrphan["ok"]!!.jsonPrimitive.boolean)
            val acctOrphan = verifyOrphan["accounts"]!!.jsonArray.single().jsonObject
            assertEquals(1, acctOrphan["orphanAccountRecords"]!!.jsonPrimitive.int)
            assertFalse(acctOrphan["ok"]!!.jsonPrimitive.boolean)
        }
    }
}
