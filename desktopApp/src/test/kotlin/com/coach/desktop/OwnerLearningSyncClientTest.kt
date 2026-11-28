package com.coach.desktop

import com.coach.client.PairedCredentials
import com.coach.core.LedgerActor
import com.coach.desktop.books.BookChapterResolver
import com.coach.desktop.books.ConceptAnswerKind
import com.coach.desktop.books.ConceptQuoteCandidate
import com.coach.desktop.books.HttpLearningRemote
import com.coach.desktop.books.LearnerSignalType
import com.coach.desktop.books.LearningJournal
import com.coach.desktop.books.LearningMigrationBackup
import com.coach.desktop.books.LearningRemote
import com.coach.desktop.books.LearningTransfer
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.syntheticTextbook
import com.coach.server.FriendAccess
import com.coach.server.SableSessions
import com.coach.server.UnifiedBookPipeline
import com.coach.server.ledgerRoutes
import com.coach.server.unifiedBookRoutes
import com.coach.store.LedgerStore
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerLearningSyncClientTest {
    @Test fun productionUrlsKeepOwnerSyncOffWhileStagingAndExplicitSwitchEnableIt() {
        val home = Files.createTempDirectory("owner-sync-settings-").toRealPath()
        try {
            val config = home.resolve("Coach/config/local.properties")
            Files.createDirectories(config.parent)
            Files.writeString(
                config,
                "coach.friendUrl=https://prod-friend.example\ncoach.desktopUrl=http://prod-desktop.example:28799\n",
            )
            val settings = DesktopConnectionSettings(home, { null }, mac = false, property = { null })
            val ownerCred = PairedCredentials("qa-owner-1", "a".repeat(43), "owner")
            val friendCred = PairedCredentials("sable-dev-1", "b".repeat(43), "friend")

            // Production addresses: port 18791/8791/8790, configured friendUrl, configured desktopUrl, malformed.
            for (url in listOf(
                "http://127.0.0.1:18791",
                "http://100.64.0.2:18791",
                "http://127.0.0.1:8791",
                "http://127.0.0.1:8790",
                "https://prod-friend.example",
                "http://prod-desktop.example:28799",
            )) {
                assertTrue(settings.isProductionServerUrl(url), "expected production URL: $url")
                assertFalse(
                    ownerLearningSyncAllowed(DesktopIdentity(url, ownerCred), settings, { null }, { null }),
                    "owner sync must be off for production URL $url",
                )
            }
            assertTrue(settings.isProductionServerUrl("not a url"))

            // Staging / non-production URLs enable owner sync automatically.
            for (url in listOf("http://100.64.0.2:28791", "http://127.0.0.1:28791", "https://staging.example:8443")) {
                assertFalse(settings.isProductionServerUrl(url), "expected non-production URL: $url")
                assertTrue(
                    ownerLearningSyncAllowed(DesktopIdentity(url, ownerCred), settings, { null }, { null }),
                    "owner sync should be allowed on staging URL $url",
                )
                assertFalse(
                    ownerLearningSyncAllowed(DesktopIdentity(url, friendCred), settings, { null }, { null }),
                    "friend identity must never use owner sync path",
                )
            }

            // Explicit test switch overrides client gating in both directions for owner only.
            val prodOwner = DesktopIdentity("http://127.0.0.1:18791", ownerCred)
            assertTrue(ownerLearningSyncAllowed(prodOwner, settings, { if (it == "COACH_OWNER_LEARNING_SYNC") "1" else null }, { null }))
            assertTrue(ownerLearningSyncAllowed(prodOwner, settings, { null }, { if (it == "coach.ownerLearningSync") "true" else null }))
            val stagingOwner = DesktopIdentity("http://100.64.0.2:28791", ownerCred)
            assertFalse(ownerLearningSyncAllowed(stagingOwner, settings, { if (it == "COACH_OWNER_LEARNING_SYNC") "0" else null }, { null }))
            assertFalse(ownerLearningSyncAllowed(stagingOwner, settings, { null }, { if (it == "coach.ownerLearningSync") "false" else null }))
            assertFalse(ownerLearningSyncAllowed(DesktopIdentity("http://100.64.0.2:28791", friendCred), settings, { "1" }, { "true" }))
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test fun learningProfilesAndTransferRejectOwnerScopeWhenAllowOwnerIsFalse(): Unit = runBlocking {
        val root = Files.createTempDirectory("owner-sync-profiles-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        val deviceScope = "device-" + "1".repeat(64)
        val sableScope = "sable-" + "2".repeat(64)
        val epoch = UUID.randomUUID().toString()
        val returnedScope = java.util.concurrent.atomic.AtomicReference(deviceScope)
        server.createContext("/learning-sync/profile") { ex ->
            calls.incrementAndGet()
            val body = """{"scope":"${returnedScope.get()}","epoch":"$epoch"}""".encodeToByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val owner = DesktopIdentity(base, PairedCredentials("qa-owner", "a".repeat(43), "owner"))
            val friend = DesktopIdentity(base, PairedCredentials("sable-dev", "b".repeat(43), "friend"))

            val prodProfiles = LearningProfiles(root.resolve("prod-owner"))
            assertNull(prodProfiles.open(owner, online = true, allowOwner = false).scope)
            assertEquals(0, calls.get(), "production owner must not query /learning-sync/profile")

            // A friend identity never accepts a device- scope.
            val friendProfiles = LearningProfiles(root.resolve("friend"))
            assertNull(friendProfiles.open(friend, online = true, allowOwner = false).scope)
            returnedScope.set(sableScope)
            assertEquals(sableScope, friendProfiles.open(friend, online = true, allowOwner = false).scope)

            // Staging owner accepts device- scope, persists it, and reopens offline with allowOwner = true,
            // while allowOwner = false refuses even cached device- scope.
            returnedScope.set(deviceScope)
            val stagingProfiles = LearningProfiles(root.resolve("staging-owner"))
            val opened = stagingProfiles.open(owner, online = true, allowOwner = true)
            assertEquals(deviceScope, opened.scope)
            assertEquals(opened, stagingProfiles.open(owner, online = false, allowOwner = true))
            assertNull(stagingProfiles.open(owner, online = false, allowOwner = false).scope)

            // LearningTransfer refuses device- scope on production path (allowOwner = false).
            val lib = root.resolve("transfer-check")
            TextbookLibrary(lib)
            LearningJournal.installProfile(TextbookLibrary(lib))
            val noopRemote = object : LearningRemote {
                override suspend fun part(epoch: String, transport: String, snapshot: String?, seq: Long, offset: Int): JsonObject? = null
                override suspend fun snapshot(epoch: String, token: String?, after: Long): JsonObject? = null
                override suspend fun changes(epoch: String?, after: Long, stream: String): JsonObject? = null
                override suspend fun push(change: JsonObject): JsonObject? = null
            }
            assertFailsWith<IllegalArgumentException> {
                LearningTransfer(lib.resolve("library.sqlite"), false).sync(deviceScope, noopRemote, epoch, allowOwner = false) { true }
            }
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun stagingOwnerSyncsConceptMasteryAcrossTwoDirectoriesWhileProductionPathStaysSableOnly(): Unit = runBlocking {
        val root = Files.createTempDirectory("owner-concept-ab-sync-")
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("service"))
        val actor = LedgerActor(job, store)
        val ownerDevice = "qa-owner-shared"
        val ownerToken = "a".repeat(43)
        val otherOwnerDevice = "qa-owner-other"
        val otherOwnerToken = "b".repeat(43)
        store.enroll(ownerDevice, ownerToken)
        store.enroll(otherOwnerDevice, otherOwnerToken)
        val sableToken = "synthetic-sable-ab-sync-test"
        val issuer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/coach/whoami") { ex ->
                val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $sableToken"
                val body = """{"sub":"sable-ab-1","username":"sableab","display_name":"Sable"}""".toByteArray()
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
        val stagingBooks = UnifiedBookPipeline(root.resolve("staging-books"), enabled = true, autoProcess = false)
        val prodBooks = UnifiedBookPipeline(root.resolve("prod-books"), enabled = true, autoProcess = false)
        val stagingServer = job.embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = false, unifiedBooks = stagingBooks)
            unifiedBookRoutes(actor, stagingBooks, friends, learningSync = true, allowOwnerLearningSync = true)
        }.apply { start(false) }
        val prodServer = job.embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = false, unifiedBooks = prodBooks)
            unifiedBookRoutes(actor, prodBooks, friends, learningSync = true, allowOwnerLearningSync = false)
        }.apply { start(false) }
        val prodPublicServer = job.embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = true, unifiedBooks = prodBooks)
            unifiedBookRoutes(actor, prodBooks, friends, learningSync = true, allowOwnerLearningSync = false)
        }.apply { start(false) }

        try {
            val stagingBase = "http://127.0.0.1:${stagingServer.engine.resolvedConnectors().single().port}"
            val prodBase = "http://127.0.0.1:${prodServer.engine.resolvedConnectors().single().port}"
            val prodPublicBase = "http://127.0.0.1:${prodPublicServer.engine.resolvedConnectors().single().port}"
            val ownerCred = PairedCredentials(ownerDevice, ownerToken, "owner")
            val otherOwnerCred = PairedCredentials(otherOwnerDevice, otherOwnerToken, "owner")
            val sableExchange = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                HttpRequest.newBuilder(URI("$prodPublicBase/v1/public/sable"))
                    .header("Authorization", "Bearer $sableToken")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, sableExchange.statusCode())
            val sableJson = Json.parseToJsonElement(sableExchange.body()).jsonObject
            val sableCred = PairedCredentials(
                sableJson.getValue("deviceId").jsonPrimitive.content,
                sableJson.getValue("token").jsonPrimitive.content,
                "friend",
            )
            val settings = DesktopConnectionSettings(root.resolve("home"), { null }, mac = false, property = { null })

            // 1. Prove production path still rejects owner and allows Sable.
            val prodOwnerResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("$prodBase/learning-sync/profile"))
                    .header("Authorization", "Bearer $ownerToken")
                    .header("X-Coach-Device", ownerDevice)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(403, prodOwnerResp.statusCode())
            val sableProfiles = LearningProfiles(root.resolve("prod-sable/books"))
            val sableProfile = sableProfiles.open(DesktopIdentity(prodPublicBase, sableCred), online = true)
            assertTrue(assertNotNull(sableProfile.scope).startsWith("sable-"))

            // 2. Staging owner A & B directories with the SAME owner identity.
            val stagingIdentity = DesktopIdentity(stagingBase, ownerCred)
            assertTrue(ownerLearningSyncAllowed(stagingIdentity, settings, { null }, { null }))

            val dirA = root.resolve("dirA/books")
            val dirB = root.resolve("dirB/books")
            val dirC = root.resolve("dirC/books")
            val profileA = LearningProfiles(dirA).open(stagingIdentity, online = true, allowOwner = true)
            val profileB = LearningProfiles(dirB).open(stagingIdentity, online = true, allowOwner = true)
            val profileC = LearningProfiles(dirC).open(DesktopIdentity(stagingBase, otherOwnerCred), online = true, allowOwner = true)
            assertNotNull(profileA.scope)
            assertEquals(profileA.scope, profileB.scope)
            assertTrue(profileA.scope != profileC.scope)

            fun openLibrary(dir: Path): TextbookLibrary {
                LearningMigrationBackup.prepare(dir)
                return TextbookLibrary(dir).also { LearningJournal.installProfile(it) }
            }

            val libA = openLibrary(profileA.directory)
            val libB = openLibrary(profileB.directory)
            val libC = openLibrary(profileC.directory)
            val bookSource = syntheticTextbook(root.resolve("book-fixture"))
            val bookA = libA.importBook(bookSource)
            val page = bookA.pages.first()
            val sentence = page.text.lineSequence().map { it.trim() }.first { it.length >= 24 }
            val chapter = assertNotNull(BookChapterResolver.chapterForPage(bookA, page.number))
            val recorded = libA.recordConceptAnswers(
                book = bookA,
                expectedChapterId = chapter.tocNodeId,
                candidates = listOf(
                    ConceptQuoteCandidate("photosynthesis", page.number, sentence.take(24), scoreRatio = 1.0),
                    ConceptQuoteCandidate("cellular respiration", page.number, sentence.takeLast(24), scoreRatio = 0.0),
                ),
                kind = ConceptAnswerKind.MCQ,
                signal = LearnerSignalType.POST_QUIZ_T1,
                guessed = false,
                questionId = "qa-sync-1",
                prompt = "Synthetic chapter question",
            )
            assertEquals(2, recorded)
            assertEquals(
                1,
                libA.recordConceptAnswers(
                    book = bookA,
                    expectedChapterId = chapter.tocNodeId,
                    candidates = listOf(
                        ConceptQuoteCandidate("photosynthesis", page.number, sentence.take(24), scoreRatio = 1.0),
                    ),
                    kind = ConceptAnswerKind.RUBRIC,
                    signal = LearnerSignalType.RUBRIC_GRADE,
                    guessed = false,
                    questionId = "qa-sync-open-1",
                ),
            )

            suspend fun syncDir(identity: DesktopIdentity, profile: com.coach.desktop.LearningProfile) {
                val http = com.coach.client.CourseHttp(identity.serverUrl, identity.credentials.deviceId, identity.credentials.token, "library")
                try {
                    val remote = HttpLearningRemote(http)
                    val scope = checkNotNull(profile.scope)
                    assertTrue(LearningTransfer(profile.directory.resolve("library.sqlite"), false).sync(scope, remote, profile.epoch, allowOwner = true) { true })
                    assertTrue(LearningTransfer(profile.directory.resolve("exams.sqlite"), true).sync(scope, remote, profile.epoch, allowOwner = true) { true })
                } finally {
                    http.close()
                }
            }

            syncDir(stagingIdentity, profileA)
            val bookB = libB.importBook(bookSource)
            val bookC = libC.importBook(bookSource)
            syncDir(stagingIdentity, profileB)
            syncDir(DesktopIdentity(stagingBase, otherOwnerCred), profileC)

            val mapA = libA.conceptMasteryMap(bookA).single { it.chapterId == chapter.tocNodeId }.concepts.associateBy { it.name }
            val mapB = libB.conceptMasteryMap(bookB).single { it.chapterId == chapter.tocNodeId }.concepts.associateBy { it.name }
            assertEquals(mapA.keys, mapB.keys)
            assertEquals(2, mapB.size)
            for ((name, nodeA) in mapA) {
                val nodeB = assertNotNull(mapB[name])
                assertEquals(nodeA.levelLabel, nodeB.levelLabel)
                assertEquals(nodeA.evidenceCount, nodeB.evidenceCount)
            }
            assertEquals(2, mapB.getValue("photosynthesis").evidenceCount)
            assertEquals(1, mapB.getValue("cellular respiration").evidenceCount)
            assertTrue(libC.conceptMasteryMap(bookC).isEmpty(), "second owner account must remain isolated")
        } finally {
            stagingServer.stop(0, 1000)
            prodServer.stop(0, 1000)
            prodPublicServer.stop(0, 1000)
            issuer.stop(0)
            actor.close()
            job.cancel()
            root.toFile().deleteRecursively()
        }
    }
}
