package com.coach.desktop.books

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.core.LedgerActor
import com.coach.desktop.DesktopIdentity
import com.coach.domain.LedgerJson
import com.coach.domain.str
import com.coach.server.FriendAccess
import com.coach.server.SableSessions
import com.coach.server.UnifiedBookPipeline
import com.coach.server.ledgerRoutes
import com.coach.server.unifiedBookRoutes
import com.coach.store.LedgerStore
import com.coach.ui.CoachTheme
import com.sun.net.httpserver.HttpServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedBookUploadQueueTest {
    private class FakeRemoteClient(
        var accountKey: String? = "sable-alpha",
        var capability: UnifiedBookCapability = UnifiedBookCapability.Supported("sable-alpha"),
        var nextUploadOutcome: UnifiedBookUploadOutcome = UnifiedBookUploadOutcome.Accepted(
            shelfBookId = "sb_0123456789abcdef01234567",
            sha256 = "",
            jobState = BookUploadState.QUEUED,
            progressPct = 0,
            stageLabel = "已排队，等待整理",
        ),
        var nextStatusOutcome: UnifiedBookStatusOutcome = UnifiedBookStatusOutcome.Ready(
            jobState = BookUploadState.QUEUED,
            progressPct = 0,
            stageLabel = "已排队，等待整理",
        ),
    ) : UnifiedBookRemoteClient {
        var uploadCalls = 0
        var statusCalls = 0
        var deleteCalls = 0
        var lastUploadedFile: Path? = null
        val deletedShelfBookIds = mutableListOf<String>()
        var nextDeleteOutcome: UnifiedBookDeleteOutcome = UnifiedBookDeleteOutcome.Deleted

        override suspend fun activeAccountKey(): String? = accountKey
        override suspend fun checkCapability(): UnifiedBookCapability = capability
        override suspend fun uploadBook(
            file: Path,
            format: String,
            title: String,
            sha256: String,
            onProgress: (Int) -> Unit,
        ): UnifiedBookUploadOutcome {
            uploadCalls++
            lastUploadedFile = file
            onProgress(50)
            onProgress(100)
            return when (val out = nextUploadOutcome) {
                is UnifiedBookUploadOutcome.Accepted -> out.copy(sha256 = sha256)
                else -> out
            }
        }

        override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome {
            statusCalls++
            return nextStatusOutcome
        }

        override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
            deleteCalls++
            deletedShelfBookIds += shelfBookId
            return nextDeleteOutcome
        }
    }

    private fun createSampleTxt(dir: Path, name: String, content: String): Path {
        val file = dir.resolve(name)
        Files.writeString(file, content)
        return file
    }

    @Test
    fun offlineImportOpensLocallyPersistsQueueAndResumesAcrossRestart() = runBlocking {
        val root = Files.createTempDirectory("coach-upload-offline-")
        try {
            val libDir = root.resolve("library")
            val source = createSampleTxt(root, "chapter.txt", "Chapter 1\nLocal reading works completely offline.")
            val clock = Clock.fixed(Instant.parse("2026-11-23T11:00:00Z"), ZoneOffset.UTC)
            val lib = TextbookLibrary(libDir, clock)
            val remote = FakeRemoteClient(capability = UnifiedBookCapability.Offline)
            val controller = TextbookController(lib).apply { unifiedUploadClient = remote }

            controller.importBook(source)
            // Instant local open without waiting for server.
            assertEquals(BookScreen.READER, controller.state.value.screen)
            val book = assertNotNull(controller.state.value.book)
            assertTrue(book.pages.first().text.contains("Local reading works"))

            controller.uploadCoordinator.enqueueBook(book.id, book.id, book.format, book.title)
            controller.uploadCoordinator.syncOnce()
            assertEquals(0, remote.uploadCalls)

            val offlineEntry = assertNotNull(lib.uploadEntriesForAccount("sable-alpha")[book.id])
            assertEquals(BookUploadState.WAITING_NETWORK, offlineEntry.state)
            assertEquals("等待联网", offlineEntry.cardStatusLine())
            assertNull(offlineEntry.serverShelfBookId)

            // Simulate restart with a fresh TextbookLibrary instance; queue survives and uploads once online.
            val restartedLib = TextbookLibrary(libDir, clock)
            remote.capability = UnifiedBookCapability.Supported("sable-alpha")
            val coordinator = UnifiedBookUploadCoordinator(restartedLib) { remote }
            coordinator.syncOnce()

            assertEquals(1, remote.uploadCalls)
            val uploaded = assertNotNull(restartedLib.uploadEntriesForAccount("sable-alpha")[book.id])
            assertEquals(book.id, uploaded.localBookId)
            assertEquals(book.id, uploaded.fileSha256)
            assertEquals("sb_0123456789abcdef01234567", uploaded.serverShelfBookId)
            assertEquals(BookUploadState.QUEUED, uploaded.state)
            assertEquals("已排队，等待整理", uploaded.cardStatusLine())

            // Re-importing the exact same file preserves existing reading progress & notes.
            restartedLib.save(restartedLib.book(book.id).copy(readingPage = 1, learning = true))
            val reimported = restartedLib.stageBookImport(source)
            assertTrue(reimported.alreadyExisted)
            assertTrue(reimported.book.learning)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rolledBackOldServerShowsWaitingServerNeverFallsBackAndRecoversWhenEnabled() = runBlocking {
        val root = Files.createTempDirectory("coach-upload-old-server-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val source = createSampleTxt(root, "book.txt", "Chapter 1\nOld server rollback test content.")
            val book = lib.importBook(source)
            val remote = FakeRemoteClient(capability = UnifiedBookCapability.Unsupported("sable-alpha"))
            val coordinator = UnifiedBookUploadCoordinator(lib) { remote }

            coordinator.enqueueBook(book.id, book.id, book.format, book.title)
            coordinator.syncOnce()

            assertEquals(0, remote.uploadCalls)
            val waiting = assertNotNull(lib.uploadEntriesForAccount("sable-alpha")[book.id])
            assertEquals(BookUploadState.WAITING_SERVER, waiting.state)
            assertEquals("等待服务支持", waiting.cardStatusLine())
            assertFalse(waiting.canRetry)
            // Local book stays readable.
            assertEquals(book.id, lib.book(book.id).id)

            // Once server upgrades / flag turns on, queue uploads cleanly.
            remote.capability = UnifiedBookCapability.Supported("sable-alpha")
            coordinator.syncOnce()
            assertEquals(1, remote.uploadCalls)
            assertEquals(BookUploadState.QUEUED, lib.uploadEntriesForAccount("sable-alpha")[book.id]?.state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedUploadShowsServerMessageAllowsRetryAndAccountSwitchPausesPreviousQueue() = runBlocking {
        val root = Files.createTempDirectory("coach-upload-retry-switch-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val source = createSampleTxt(root, "retry.txt", "Chapter 1\nRetry and account switch test.")
            val book = lib.importBook(source)
            val remote = FakeRemoteClient(
                accountKey = "sable-alpha",
                capability = UnifiedBookCapability.Supported("sable-alpha"),
                nextUploadOutcome = UnifiedBookUploadOutcome.Failed("存储空间不足，请稍后再试", retryable = true),
            )
            val coordinator = UnifiedBookUploadCoordinator(lib) { remote }
            coordinator.enqueueBook(book.id, book.id, book.format, book.title)
            coordinator.syncOnce()

            val failed = assertNotNull(lib.uploadEntriesForAccount("sable-alpha")[book.id])
            assertEquals(BookUploadState.FAILED, failed.state)
            assertEquals("存储空间不足，请稍后再试", failed.cardStatusLine())
            assertTrue(failed.canRetry)

            // Switching to account B pauses account A's queue and does not upload A's entries.
            remote.accountKey = "sable-beta"
            remote.capability = UnifiedBookCapability.Supported("sable-beta")
            remote.nextUploadOutcome = UnifiedBookUploadOutcome.Accepted(
                shelfBookId = "sb_abcdef0123456789abcdef01",
                sha256 = book.id,
                jobState = BookUploadState.QUEUED,
                progressPct = 0,
                stageLabel = "已排队，等待整理",
            )
            coordinator.syncOnce()
            assertEquals(1, remote.uploadCalls)
            assertTrue(lib.allUploadEntries().single { it.accountKey == "sable-alpha" }.paused)
            assertTrue(coordinator.statuses.value.isEmpty())

            // Switching back to account A resumes and retry succeeds.
            remote.accountKey = "sable-alpha"
            remote.capability = UnifiedBookCapability.Supported("sable-alpha")
            coordinator.syncOnce()
            coordinator.retryBook(book.id)
            assertEquals(2, remote.uploadCalls)
            val retried = assertNotNull(lib.uploadEntriesForAccount("sable-alpha")[book.id])
            assertEquals(BookUploadState.QUEUED, retried.state)
            assertEquals("sb_abcdef0123456789abcdef01", retried.serverShelfBookId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun homeBookCardShowsSingleLineUploadStatusAndRetryWithoutBlockingRead() {
        val books = listOf(
            BookSummary("b-offline", "微观经济学导论", 240, 240, 12, learning = false),
            BookSummary("b-old", "计量方法讲义", 180, 180, 5, learning = false),
            BookSummary("b-queued", "公共政策分析", 320, 320, 44, learning = true),
            BookSummary("b-failed", "大部头参考书", 620, 620, 1, learning = false),
        )
        val statuses = mapOf(
            "b-offline" to BookUploadEntry("sable-a", "b-offline", "a".repeat(64), null, "pdf", books[0].title, BookUploadState.WAITING_NETWORK),
            "b-old" to BookUploadEntry("sable-a", "b-old", "b".repeat(64), null, "pdf", books[1].title, BookUploadState.WAITING_SERVER),
            "b-queued" to BookUploadEntry("sable-a", "b-queued", "c".repeat(64), "sb_111111111111111111111111", "pdf", books[2].title, BookUploadState.QUEUED),
            "b-failed" to BookUploadEntry("sable-a", "b-failed", "d".repeat(64), null, "pdf", books[3].title, BookUploadState.FAILED, errorMessage = "存储空间不足，请稍后再试"),
        )
        val opened = mutableListOf<String>()
        val retried = mutableListOf<String>()
        runSkikoComposeUiTest(size = Size(1280f, 1180f), density = Density(1.5f)) {
            setContent {
                CoachTheme(false) {
                    Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                        LibraryHome(
                            ui = LibraryUi(catalog = books, initialized = true, uploadStatuses = statuses),
                            onImport = {},
                            onSelect = {},
                            onRead = {},
                            onLearn = {},
                            onPath = {},
                            onOpenBook = { opened += it },
                            onRetryUpload = { retried += it },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("等待联网").performScrollTo().assertIsDisplayed()
            onNodeWithText("等待服务支持").performScrollTo().assertIsDisplayed()
            onNodeWithText("已排队，等待整理").performScrollTo().assertIsDisplayed()
            onNodeWithText("存储空间不足，请稍后再试").performScrollTo().assertIsDisplayed()
            onNodeWithTag("book-upload-retry-b-failed").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(listOf("b-failed"), retried)
            // Book remains immediately openable while queued or waiting.
            onNodeWithTag("book-b-queued").performScrollTo().performClick()
            assertEquals(listOf("b-queued"), opened)

            val shotPath = Path.of("build/screens/ton1823/book-card-upload-status.png")
            Files.createDirectories(shotPath.parent)
            assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", shotPath.toFile()))
        }
    }

    @Test
    fun midSyncIdentitySwitchCancelsOldBatchPreventsCrossAccountUploadAndProtectsUi() = runBlocking {
        val root = Files.createTempDirectory("coach-upload-mid-switch-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val book1 = lib.importBook(createSampleTxt(root, "a1.txt", "Chapter 1\nAccount A book one."))
            val book2 = lib.importBook(createSampleTxt(root, "a2.txt", "Chapter 1\nAccount A book two."))
            val uploadStarted = CompletableDeferred<Unit>()
            val allowUploadFinish = CompletableDeferred<Unit>()
            val uploadedPairs = mutableListOf<Pair<String, String>>()
            var currentIdentity = "dev-alpha"

            val remote = object : UnifiedBookRemoteClient {
                override suspend fun activeAccountKey(): String = currentIdentity
                override suspend fun checkCapability(): UnifiedBookCapability = UnifiedBookCapability.Supported(currentIdentity)
                override suspend fun uploadBook(
                    file: Path,
                    format: String,
                    title: String,
                    sha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookUploadOutcome = error("openSession must be used")

                override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome = UnifiedBookStatusOutcome.Offline

                override suspend fun openSession(): UnifiedBookRemoteSession {
                    val pinned = currentIdentity
                    return object : UnifiedBookRemoteSession {
                        override val sessionId: String = pinned
                        override val fallbackAccountKey: String = pinned
                        override suspend fun checkCapability(): UnifiedBookCapability = UnifiedBookCapability.Supported(pinned)
                        override suspend fun uploadBook(
                            file: Path,
                            format: String,
                            title: String,
                            sha256: String,
                            onProgress: (Int) -> Unit,
                        ): UnifiedBookUploadOutcome {
                            uploadedPairs += pinned to sha256
                            if (pinned == "dev-alpha" && !uploadStarted.isCompleted) {
                                uploadStarted.complete(Unit)
                                allowUploadFinish.await()
                            }
                            return UnifiedBookUploadOutcome.Accepted(
                                shelfBookId = "sb_${sha256.take(24)}",
                                sha256 = sha256,
                                jobState = BookUploadState.QUEUED,
                                progressPct = 0,
                                stageLabel = "已排队，等待整理",
                            )
                        }

                        override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome = UnifiedBookStatusOutcome.Offline
                    }
                }
            }

            val coordinator = UnifiedBookUploadCoordinator(lib) { remote }
            coordinator.enqueueBook(book1.id, book1.id, book1.format, book1.title)
            coordinator.enqueueBook(book2.id, book2.id, book2.format, book2.title)

            val alphaJob = launch(Dispatchers.IO) {
                runCatching { coordinator.syncOnce() }
            }
            uploadStarted.await()

            // Switch identity mid-upload: old batch must be cancelled and must NOT upload book2 under dev-beta or pollute beta UI.
            currentIdentity = "dev-beta"
            coordinator.onSessionChanged("dev-beta")
            allowUploadFinish.complete(Unit)
            alphaJob.join()

            coordinator.syncOnce()
            assertTrue(uploadedPairs.none { it.first == "dev-beta" }, "Account A books must never be uploaded under Account B: $uploadedPairs")
            assertEquals(1, uploadedPairs.size, "Second book in cancelled Account A batch must not start after switch")
            assertTrue(coordinator.statuses.value.isEmpty(), "Account B UI must not receive stale statuses from Account A")
            assertTrue(lib.allUploadEntries().filter { it.accountKey == "dev-alpha" }.all { it.paused })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelImportDuringAccountSwitchWithoutActiveAccountPreservesAllAccountsRemoteRecords() = runBlocking {
        val root = Files.createTempDirectory("coach-upload-cancel-switch-")
        try {
            val lib = TextbookLibrary(
                root.resolve("library"),
                ocr = BookOcr { _, control ->
                    while (!control.isCancelled()) Thread.sleep(10)
                    control.check()
                    ""
                },
            )
            val sharedBook = lib.importBook(createSampleTxt(root, "shared.txt", "Chapter 1\nShared remote shelf textbook."))
            val secondBook = lib.importBook(createSampleTxt(root, "second.txt", "Chapter 1\nSecond remote shelf textbook."))
            val unsyncedBook = lib.importBook(createSampleTxt(root, "unsynced.txt", "Chapter 1\nLocal unsynced draft."))
            val scannedDraft = createSampleTxt(root, "pending-scan.png", "") // handled below via tiny image
            Files.deleteIfExists(scannedDraft)
            val imageFile = root.resolve("pending-scan.png")
            val raster = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            ImageIO.write(raster, "png", imageFile.toFile())
            raster.flush()

            val remoteShelfRecords = linkedMapOf(
                ("sable-alpha" to "sb_aaaaaaaaaaaaaaaaaaaaaaaa") to sharedBook.id,
                ("sable-beta" to "sb_bbbbbbbbbbbbbbbbbbbbbbbb") to sharedBook.id,
                ("sable-beta" to "sb_cccccccccccccccccccccccc") to secondBook.id,
            )
            var activeKey: String? = "sable-alpha"
            val remote = object : UnifiedBookRemoteClient {
                override suspend fun activeAccountKey(): String? = activeKey
                override suspend fun checkCapability(): UnifiedBookCapability =
                    activeKey?.let { UnifiedBookCapability.Supported(it) } ?: UnifiedBookCapability.Unauthorized

                override suspend fun uploadBook(
                    file: Path,
                    format: String,
                    title: String,
                    sha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookUploadOutcome {
                    val account = requireNotNull(activeKey)
                    val shelfId = remoteShelfRecords.entries
                        .firstOrNull { it.key.first == account && it.value == sha256 }
                        ?.key?.second
                        ?: "sb_${account.takeLast(4).padStart(4, '0')}_${sha256.take(19)}".also {
                            remoteShelfRecords[account to it] = sha256
                        }
                    return UnifiedBookUploadOutcome.Accepted(
                        shelfBookId = shelfId,
                        sha256 = sha256,
                        jobState = BookUploadState.QUEUED,
                        progressPct = 0,
                        stageLabel = "已排队，等待整理",
                    )
                }

                override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome {
                    val account = activeKey ?: return UnifiedBookStatusOutcome.Unauthorized
                    return if ((account to shelfIdKey(shelfBookId)) in remoteShelfRecords) {
                        UnifiedBookStatusOutcome.Ready(BookUploadState.QUEUED, 0, "已排队，等待整理")
                    } else {
                        UnifiedBookStatusOutcome.NotFound
                    }
                }

                private fun shelfIdKey(id: String) = id

                override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
                    val account = activeKey ?: return UnifiedBookDeleteOutcome.Unauthorized
                    val removed = remoteShelfRecords.remove(account to shelfBookId) != null
                    return if (removed) UnifiedBookDeleteOutcome.Deleted else UnifiedBookDeleteOutcome.NotFound
                }
            }
            val controller = TextbookController(lib).apply { unifiedUploadClient = remote }
            val coordinator = controller.uploadCoordinator

            // Upload sharedBook under Account A, and both sharedBook + secondBook under Account B.
            coordinator.enqueueBook(sharedBook.id, sharedBook.id, sharedBook.format, sharedBook.title)
            coordinator.syncOnce()

            activeKey = "sable-beta"
            controller.onUploadIdentityChanged("sable-beta")
            coordinator.enqueueBook(sharedBook.id, sharedBook.id, sharedBook.format, sharedBook.title)
            coordinator.enqueueBook(secondBook.id, secondBook.id, secondBook.format, secondBook.title)
            coordinator.syncOnce()

            // Also leave unsynced local queue rows for both accounts.
            lib.enqueueBookUpload("sable-alpha", unsyncedBook.id, unsyncedBook.id, unsyncedBook.format, unsyncedBook.title)
            lib.enqueueBookUpload("sable-beta", unsyncedBook.id, unsyncedBook.id, unsyncedBook.format, unsyncedBook.title)

            // Simulate account switch where active account cannot be resolved (currentAccountKey() == null).
            activeKey = null
            controller.onUploadIdentityChanged(null)?.join()
            assertNull(coordinator.currentAccountKey())

            // Cancelling an import started during account switch (no active account) must NOT create remote unbinds.
            val importJob = launch(Dispatchers.Default) {
                controller.importBook(imageFile)
            }
            while (controller.state.value.importing?.recognizing != true) {
                kotlinx.coroutines.delay(10)
            }
            controller.cancelImport()
            importJob.join()

            // Local deletes when initiating account is unknown (null) also preserve all remote records and unsynced originals.
            lib.deleteBook(sharedBook.id, initiatingAccountKey = null)
            lib.deleteBook(secondBook.id, initiatingAccountKey = null)
            lib.deleteBook(unsyncedBook.id, initiatingAccountKey = null)

            assertTrue(lib.catalog().isEmpty(), "Local books should be deleted from local catalog")
            assertNotNull(lib.originalFileIfPresent(unsyncedBook.id, unsyncedBook.format), "Other accounts' unsynced local original must be preserved")

            val allAfterDelete = lib.allUploadEntries()
            assertTrue(allAfterDelete.none { it.state == BookUploadState.PENDING_DELETE })
            assertEquals("sb_aaaaaaaaaaaaaaaaaaaaaaaa", allAfterDelete.single { it.accountKey == "sable-alpha" && it.localBookId == sharedBook.id }.serverShelfBookId)
            assertEquals("sb_bbbbbbbbbbbbbbbbbbbbbbbb", allAfterDelete.single { it.accountKey == "sable-beta" && it.localBookId == sharedBook.id }.serverShelfBookId)
            assertEquals("sb_cccccccccccccccccccccccc", allAfterDelete.single { it.accountKey == "sable-beta" && it.localBookId == secondBook.id }.serverShelfBookId)
            assertEquals(2, allAfterDelete.count { it.localBookId == unsyncedBook.id })

            // Resuming sync on Account A and Account B keeps every remote shelf record intact (one each + unsyncedBook uploaded).
            activeKey = "sable-alpha"
            controller.onUploadIdentityChanged("sable-alpha")?.join()
            coordinator.syncOnce()
            activeKey = "sable-beta"
            controller.onUploadIdentityChanged("sable-beta")?.join()
            coordinator.syncOnce()

            assertTrue(("sable-alpha" to "sb_aaaaaaaaaaaaaaaaaaaaaaaa") in remoteShelfRecords)
            assertTrue(("sable-beta" to "sb_bbbbbbbbbbbbbbbbbbbbbbbb") in remoteShelfRecords)
            assertTrue(("sable-beta" to "sb_cccccccccccccccccccccccc") in remoteShelfRecords)
            assertTrue(remoteShelfRecords.values.count { it == unsyncedBook.id } == 2, "Preserved local original allowed both accounts to upload")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelImportAfterAccountSwitchUnbindsOnlyInitiatingAccountAndPreservesOtherAccountPendingOriginal() = runBlocking {
        val root = Files.createTempDirectory("coach-upload-initiating-account-")
        try {
            val libDir = root.resolve("library")
            val imageFile = root.resolve("scan-book.png")
            val raster = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            ImageIO.write(raster, "png", imageFile.toFile())
            raster.flush()

            val remoteShelfRecords = linkedMapOf<Pair<String, String>, String>()
            val deletedOnServer = mutableListOf<Pair<String, String>>()
            var activeKey: String? = "sable-alpha"
            var serverOnline = true
            val remote = object : UnifiedBookRemoteClient {
                override suspend fun activeAccountKey(): String? = activeKey
                override suspend fun checkCapability(): UnifiedBookCapability = when {
                    activeKey == null -> UnifiedBookCapability.Unauthorized
                    !serverOnline -> UnifiedBookCapability.Offline
                    else -> UnifiedBookCapability.Supported(activeKey!!)
                }

                override suspend fun uploadBook(
                    file: Path,
                    format: String,
                    title: String,
                    sha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookUploadOutcome {
                    val account = requireNotNull(activeKey)
                    val shelfId = "sb_${if (account == "sable-alpha") "1111" else "2222"}_${sha256.take(19)}"
                    remoteShelfRecords[account to shelfId] = sha256
                    return UnifiedBookUploadOutcome.Accepted(
                        shelfBookId = shelfId,
                        sha256 = sha256,
                        jobState = BookUploadState.QUEUED,
                        progressPct = 0,
                        stageLabel = "已排队，等待整理",
                    )
                }

                override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome =
                    UnifiedBookStatusOutcome.Ready(BookUploadState.QUEUED, 0, "已排队，等待整理")

                override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
                    val account = activeKey ?: return UnifiedBookDeleteOutcome.Unauthorized
                    deletedOnServer += account to shelfIdOr(shelfBookId)
                    val removed = remoteShelfRecords.remove(account to shelfBookId) != null
                    return if (removed) UnifiedBookDeleteOutcome.Deleted else UnifiedBookDeleteOutcome.NotFound
                }

                private fun shelfIdOr(shelfBookId: String): String = shelfBookId
            }

            val setupLib = TextbookLibrary(libDir)
            val firstController = TextbookController(setupLib).apply { unifiedUploadClient = remote }
            firstController.onUploadIdentityChanged("sable-alpha")?.join()
            val importedId = setupLib.stageBookImport(imageFile).book.id
            setupLib.saveImportAccountKey(importedId, "sable-alpha")
            setupLib.enqueueBookUpload("sable-alpha", importedId, importedId, "png", "scan-book")
            firstController.syncBookUploads().join()
            val alphaShelfId = setupLib.readUploadEntry("sable-alpha", importedId)!!.serverShelfBookId!!

            val ocrStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val lib = TextbookLibrary(
                libDir,
                ocr = BookOcr { _, control ->
                    ocrStarted.complete(Unit)
                    while (!control.isCancelled()) Thread.sleep(10)
                    control.check()
                    ""
                },
            )
            val controller = TextbookController(lib).apply { unifiedUploadClient = remote }
            controller.onUploadIdentityChanged("sable-alpha")?.join()
            controller.load()
            kotlinx.coroutines.withTimeout(15000) { ocrStarted.await() }

            // Switch to Account B (sable-beta) while offline so beta's copy is still waiting to upload, then cancel Account A's import!
            lib.enqueueBookUpload("sable-beta", importedId, importedId, "png", "scan-book")
            serverOnline = false
            activeKey = "sable-beta"
            controller.onUploadIdentityChanged("sable-beta")?.join()

            controller.cancelImport()
            kotlinx.coroutines.withTimeout(15000) {
                while (controller.state.value.importing != null) kotlinx.coroutines.delay(10)
            }

            // Only Account A is marked PENDING_DELETE; Account B keeps its pending upload AND local original file!
            val alphaAfterCancel = assertNotNull(lib.readUploadEntry("sable-alpha", importedId))
            assertEquals(BookUploadState.PENDING_DELETE, alphaAfterCancel.state)
            assertEquals(alphaShelfId, alphaAfterCancel.serverShelfBookId)
            val betaAfterCancel = assertNotNull(lib.readUploadEntry("sable-beta", importedId))
            assertNull(betaAfterCancel.serverShelfBookId)
            assertNotNull(lib.originalFileIfPresent(importedId, "png"))

            // Account B goes back online, syncs cleanly, and uploads its copy using the preserved local original.
            serverOnline = true
            controller.uploadCoordinator.syncOnce()
            assertEquals(1, remoteShelfRecords.keys.count { it.first == "sable-beta" })
            assertEquals(BookUploadState.QUEUED, lib.readUploadEntry("sable-beta", importedId)?.state)

            // Restart library & switch back to Account A -> PENDING_DELETE unbinds only Account A's shelfId.
            val restartedLib = TextbookLibrary(lib.directory)
            activeKey = "sable-alpha"
            val restartedCoordinator = UnifiedBookUploadCoordinator(restartedLib) { remote }
            restartedCoordinator.syncOnce()

            assertEquals(listOf("sable-alpha" to alphaShelfId), deletedOnServer)
            assertNull(restartedLib.readUploadEntry("sable-alpha", importedId))
            assertEquals(1, remoteShelfRecords.keys.count { it.first == "sable-beta" })
            assertNull(restartedLib.originalFileIfPresent(importedId, "png"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun schemaMigrationV5ToV6PreservesExistingQueueAndSupportsPendingDelete() {
        val root = Files.createTempDirectory("coach-v5-to-v6-")
        try {
            val dir = Files.createDirectories(root.resolve("library"))
            val dbFile = dir.resolve("library.sqlite")
            // Build a real V5 database with the older CHECK constraint (no PENDING_DELETE).
            java.sql.DriverManager.getConnection("jdbc:sqlite:$dbFile").use { db ->
                db.createStatement().use { sql ->
                    sql.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,PRIMARY KEY(book,page))")
                    sql.execute("CREATE TABLE answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,PRIMARY KEY(book,lesson,mode))")
                    sql.execute("CREATE TABLE book_reviews(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,completed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                    sql.execute("CREATE TABLE book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)")
                    sql.execute("CREATE TABLE book_open_session(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,screen TEXT NOT NULL CHECK(screen IN ('READER','LEARNING')))")
                    sql.execute("CREATE TABLE book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))")
                    sql.execute("CREATE TABLE book_ladders(book TEXT PRIMARY KEY,session TEXT NOT NULL)")
                    sql.execute("CREATE TABLE book_reader_views(book TEXT PRIMARY KEY,text_view INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_pdf_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_text_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_read_spots(book TEXT PRIMARY KEY,page INTEGER NOT NULL,offset_utf16 INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_highlights(book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,PRIMARY KEY(book,page,start,end))")
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,note TEXT NOT NULL,text_sha256 TEXT NOT NULL,revision INTEGER NOT NULL,last_mutation_id TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,id))")
                    sql.execute("CREATE TABLE reading_settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    sql.execute(
                        """CREATE TABLE book_upload_queue(
                            account_key TEXT NOT NULL,
                            local_book_id TEXT NOT NULL,
                            file_sha256 TEXT NOT NULL,
                            server_shelf_book_id TEXT,
                            format TEXT NOT NULL,
                            title TEXT NOT NULL,
                            state TEXT NOT NULL CHECK(state IN (
                                'WAITING_NETWORK','WAITING_SERVER','UPLOADING','RECEIVED','QUEUED','PROCESSING','READY','FAILED','PAUSED'
                            )),
                            progress_pct INTEGER NOT NULL DEFAULT 0,
                            stage_label TEXT NOT NULL DEFAULT '',
                            error_message TEXT,
                            paused INTEGER NOT NULL DEFAULT 0,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(account_key, local_book_id)
                        )""".trimIndent(),
                    )
                    sql.execute(
                        "INSERT INTO books(id,title,format,touched) VALUES('${"a".repeat(64)}','Legacy V5 Book','txt',1)",
                    )
                    sql.execute(
                        """INSERT INTO book_upload_queue(account_key,local_book_id,file_sha256,server_shelf_book_id,format,title,state,updated_at)
                           VALUES('sable-a','${"a".repeat(64)}','${"a".repeat(64)}','sb_000000000000000000000001','txt','Legacy V5 Book','QUEUED',1)""".trimIndent(),
                    )
                    sql.execute("PRAGMA user_version = 5")
                }
            }

            val lib = TextbookLibrary(dir)
            lib.deleteBook("a".repeat(64), initiatingAccountKey = "sable-a")
            val entry = assertNotNull(lib.readUploadEntry("sable-a", "a".repeat(64)))
            assertEquals(BookUploadState.PENDING_DELETE, entry.state)
            assertEquals("sb_000000000000000000000001", entry.serverShelfBookId)
            assertTrue(Files.isRegularFile(dir.resolve(BookSchemaMigration.V5_BACKUP_FILENAME)))
            assertEquals(1, lib.syncTasksForAccount("sable-a").count { it.state == BookUploadState.PENDING_DELETE })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun offlineDeleteThenReimportOrdersUnbindBeforeUploadAndPersistsOcrInitiatingOwnerAcrossRestart() = runBlocking {
        val root = Files.createTempDirectory("coach-offline-order-owner-")
        try {
            val dir = root.resolve("library")
            val events = mutableListOf<String>()
            var failDelete = true
            var activeKey = "sable-alpha"
            val remote = object : UnifiedBookRemoteClient {
                override suspend fun activeAccountKey(): String = activeKey
                override suspend fun checkCapability(): UnifiedBookCapability = UnifiedBookCapability.Supported(activeKey)
                override suspend fun uploadBook(
                    file: Path,
                    format: String,
                    title: String,
                    sha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookUploadOutcome {
                    events += "upload:$activeKey"
                    return UnifiedBookUploadOutcome.Accepted("sb_new_1234567890abcdef01", sha256, BookUploadState.QUEUED, 0, "")
                }

                override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome =
                    UnifiedBookStatusOutcome.Ready(BookUploadState.QUEUED, 0, "")

                override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome {
                    events += "delete:$activeKey:$shelfBookId:${if (failDelete) "fail" else "ok"}"
                    return if (failDelete) UnifiedBookDeleteOutcome.Offline else UnifiedBookDeleteOutcome.Deleted
                }
            }
            val lib = TextbookLibrary(dir)
            val source = createSampleTxt(root, "reimport.txt", "Chapter 1\nOffline delete then reimport order test.")
            val book = lib.importBook(source)
            lib.enqueueBookUpload("sable-alpha", book.id, book.id, book.format, book.title)
            lib.updateBookUploadState("sable-alpha", book.id, BookUploadState.QUEUED, serverShelfBookId = "sb_old_1234567890abcdef01")
            // Offline delete, then offline re-import of the exact same file.
            lib.deleteBook(book.id, "sable-alpha")
            val reimported = lib.importBook(source)
            lib.enqueueBookUpload("sable-alpha", reimported.id, reimported.id, reimported.format, reimported.title)

            val coordinator = UnifiedBookUploadCoordinator(lib) { remote }
            // When delete fails transiently, re-import upload must NOT run ahead of unbinding sb_old.
            coordinator.syncOnce()
            assertEquals(listOf("delete:sable-alpha:sb_old_1234567890abcdef01:fail"), events)
            assertNull(lib.readUploadEntry("sable-alpha", reimported.id)?.serverShelfBookId)

            // Once delete succeeds, sb_old unbinds BEFORE the new upload runs.
            failDelete = false
            coordinator.syncOnce()
            assertEquals(
                listOf(
                    "delete:sable-alpha:sb_old_1234567890abcdef01:fail",
                    "delete:sable-alpha:sb_old_1234567890abcdef01:ok",
                    "upload:sable-alpha",
                ),
                events,
            )
            assertEquals("sb_new_1234567890abcdef01", lib.readUploadEntry("sable-alpha", reimported.id)?.serverShelfBookId)

            // Persisted initiating account survives restart on scanned OCR resume even after switching to Account B.
            val scanDir = root.resolve("scan-library")
            val scanImage = root.resolve("scan-owner.png")
            ImageIO.write(java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_BYTE_GRAY), "png", scanImage.toFile())
            val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val firstLib = TextbookLibrary(
                scanDir,
                ocr = BookOcr { _, control ->
                    firstStarted.complete(Unit)
                    while (!control.isCancelled()) Thread.sleep(5)
                    control.check()
                    ""
                },
            )
            val firstController = TextbookController(firstLib).apply { unifiedUploadClient = remote }
            firstController.onUploadIdentityChanged("sable-alpha")?.join()
            val firstImport = launch(Dispatchers.Default) { firstController.importBook(scanImage) }
            firstStarted.await()
            val scanBookId = firstController.state.value.importing!!.bookId!!
            assertEquals("sable-alpha", firstLib.importAccountKey(scanBookId))
            // Wait for initiating account key persistence, then finish cancel cleanly before setting up restart state.
            firstController.cancelImport()
            firstImport.join()
            while (firstController.state.value.importing != null) kotlinx.coroutines.delay(10)
            val betaPre = TextbookLibrary(scanDir)
            val reStaged = betaPre.stageBookImport(scanImage).book
            betaPre.saveImportAccountKey(reStaged.id, "sable-alpha")
            betaPre.enqueueBookUpload("sable-alpha", reStaged.id, reStaged.id, "png", "scan-owner")
            betaPre.updateBookUploadState("sable-alpha", reStaged.id, BookUploadState.QUEUED, serverShelfBookId = "sb_scan_alpha_000000000001")
            betaPre.enqueueBookUpload("sable-beta", reStaged.id, reStaged.id, "png", "scan-owner")
            betaPre.updateBookUploadState("sable-beta", reStaged.id, BookUploadState.QUEUED, serverShelfBookId = "sb_scan_beta_0000000000002")

            val resumeStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resumedLib = TextbookLibrary(
                scanDir,
                ocr = BookOcr { _, control ->
                    resumeStarted.complete(Unit)
                    while (!control.isCancelled()) Thread.sleep(5)
                    control.check()
                    ""
                },
            )
            activeKey = "sable-beta"
            val resumedController = TextbookController(resumedLib).apply { unifiedUploadClient = remote }
            resumedController.onUploadIdentityChanged("sable-beta")?.join()
            resumedController.load()
            resumeStarted.await()
            resumedController.cancelImport()
            while (resumedController.state.value.importing != null) kotlinx.coroutines.delay(10)

            assertEquals(BookUploadState.PENDING_DELETE, resumedLib.readUploadEntry("sable-alpha", reStaged.id)?.state)
            assertEquals(BookUploadState.QUEUED, resumedLib.readUploadEntry("sable-beta", reStaged.id)?.state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun freshComputerLoginShowsServerShelfPullsBackOriginalWithExactShaAndOpensReader() {
        val root = Files.createTempDirectory("coach-fresh-pc-pullback-").toRealPath()
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serverDir = root.resolve("server")
        val store = LedgerStore(serverDir).also { it.enroll("desktop", "owner-token-fresh-pc-test-123456") }
        val actor = LedgerActor(serverScope, store)
        val tokAlpha1 = "fresh-pc-sable-token-alpha-01"
        val tokAlpha2 = "fresh-pc-sable-token-alpha-02"
        val tokBeta1 = "fresh-pc-sable-token-beta-001"
        val fakeSable = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/coach/whoami") { ex ->
                ex.requestBody.use { it.readAllBytes() }
                val bearer = ex.requestHeaders.getFirst("Authorization")
                val (code, body) = when (bearer) {
                    "Bearer $tokAlpha1" -> 200 to """{"sub":"sub-pullback-alpha","username":"alice","display_name":"Alice"}"""
                    "Bearer $tokAlpha2" -> 200 to """{"sub":"sub-pullback-alpha","username":"alice-pc2","display_name":"Alice"}"""
                    "Bearer $tokBeta1" -> 200 to """{"sub":"sub-pullback-beta","username":"bob","display_name":"Bob"}"""
                    else -> 401 to "{}"
                }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val sableOrigin = URI("http://127.0.0.1:${fakeSable.address.port}")
        val friends = FriendAccess(
            serverDir.resolve("friends"),
            serverDir.resolve("state"),
            serverDir.resolve("friends.json"),
            sableSessions = SableSessions(actor, serverDir.resolve("keys"), sableOrigin),
        )
        val unified = UnifiedBookPipeline(serverDir.resolve("unified-books"), enabled = true, autoProcess = true)
        val server = serverScope.embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = true, unifiedBooks = unified)
            unifiedBookRoutes(actor, unified, friends)
        }.apply { start(false) }

        fun sha256Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(Files.readAllBytes(path))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build()

        suspend fun exchange(port: Int, sableToken: String): DesktopIdentity = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val res = httpClient.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/public/sable"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization", "Bearer $sableToken")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, res.statusCode(), res.body())
            val obj = LedgerJson.parseToJsonElement(res.body()).jsonObject
            DesktopIdentity(
                serverUrl = "http://127.0.0.1:$port",
                credentials = PairedCredentials(obj.str("deviceId"), obj.str("token"), obj.str("role")),
            )
        }

        fun pinnedExplainer(id: DesktopIdentity, lib: TextbookLibrary): BookExplainer =
            object : BookExplainer(library = lib) {
                init {
                    identity = id
                    loaded = true
                }
                override suspend fun load() {
                    loaded = true
                }
            }

        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val (alphaPc1, alphaPc2, betaPc) = runBlocking {
                Triple(exchange(port, tokAlpha1), exchange(port, tokAlpha2), exchange(port, tokBeta1))
            }

            // Computer 1: import a synthetic book locally and upload to server.
            val sourceFile = createSampleTxt(
                root,
                "micro-notes.md",
                "# Chapter 1 Incentives\n\nSynthetic textbook content for fresh computer pullback verification.\n",
            )
            val originalSha = sha256Of(sourceFile)
            val lib1 = TextbookLibrary(root.resolve("pc1-library"))
            val explainer1 = pinnedExplainer(alphaPc1, lib1)
            val controller1 = TextbookController(lib1).apply { unifiedUploadClient = explainer1.unifiedUploadClient() }
            val shelfBookId = runBlocking {
                controller1.importBook(sourceFile)
                val book1 = assertNotNull(controller1.state.value.book)
                assertEquals(originalSha, book1.id)
                controller1.uploadCoordinator.enqueueBook(book1.id, book1.id, book1.format, book1.title)
                controller1.uploadCoordinator.syncOnce()

                // Wait for server background worker to finish processing once.
                var serverReady = false
                repeat(80) {
                    controller1.uploadCoordinator.syncOnce()
                    val entry = controller1.uploadCoordinator.statuses.value[originalSha]
                    if (entry?.state == BookUploadState.READY) {
                        serverReady = true
                        return@repeat
                    }
                    delay(25)
                }
                assertTrue(serverReady, "Expected server worker to mark uploaded book READY")
                assertEquals(1, unified.processCountForSha(originalSha))
                assertNotNull(controller1.uploadCoordinator.statuses.value[originalSha]?.serverShelfBookId)
            }

            // Computer 2: brand-new empty data directory logged into the SAME Sable account (different deviceId).
            val lib2 = TextbookLibrary(root.resolve("pc2-library"))
            assertTrue(lib2.catalog().isEmpty())
            val explainer2 = pinnedExplainer(alphaPc2, lib2)
            val controller2 = TextbookController(lib2).apply { unifiedUploadClient = explainer2.unifiedUploadClient() }
            runBlocking {
                controller2.load()
                controller2.uploadCoordinator.syncOnce()
            }

            val remoteList = controller2.uploadCoordinator.remoteShelfBooks.value
            assertEquals(1, remoteList.size, "Fresh computer must see the account's server shelf books")
            assertEquals(shelfBookId, remoteList.single().shelfBookId)
            assertEquals(originalSha, remoteList.single().sha256)

            // Shelf UI shows the cloud book (not empty state), and clicking it pulls the original back and opens it.
            val openedOnPc2 = CompletableDeferred<String>()
            runSkikoComposeUiTest(Size(1280f, 900f), Density(1f)) {
                setContent {
                    CoachTheme {
                        Surface(Modifier.fillMaxSize()) {
                            LibraryHome(
                                ui = controller2.state.value,
                                onImport = {},
                                onSelect = {},
                                onRead = {},
                                onOpenBook = { id ->
                                    openedOnPc2.complete(id)
                                },
                                library = lib2,
                            )
                        }
                    }
                }
                onNodeWithTag("cloud-book-$shelfBookId").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 15_000L) { openedOnPc2.isCompleted }
            }
            runBlocking {
                val pulledBookId = openedOnPc2.await()
                assertEquals(originalSha, pulledBookId)
                controller2.openBook(pulledBookId)
            }

            // Pulled-back original file exists locally on Computer 2 with exact matching SHA-256.
            val restoredOriginal = assertNotNull(lib2.originalFileIfPresent(originalSha, "md"))
            assertEquals(originalSha, sha256Of(restoredOriginal))
            assertEquals(BookScreen.READER, controller2.state.value.screen)
            assertEquals(originalSha, controller2.state.value.book?.id)
            assertTrue(controller2.uploadCoordinator.remoteShelfBooks.value.isEmpty())

            // Re-syncing on Computer 2 reuses the existing server binding and never reprocesses.
            runBlocking {
                controller2.uploadCoordinator.syncOnce()
            }
            assertEquals(shelfBookId, controller2.uploadCoordinator.statuses.value[originalSha]?.serverShelfBookId)
            assertEquals(1, unified.processCountForSha(originalSha))

            // Computer 3 (different Sable account Beta): sees zero books from Alpha.
            val lib3 = TextbookLibrary(root.resolve("pc3-library"))
            val explainer3 = pinnedExplainer(betaPc, lib3)
            val controller3 = TextbookController(lib3).apply { unifiedUploadClient = explainer3.unifiedUploadClient() }
            runBlocking {
                controller3.uploadCoordinator.syncOnce()
            }
            assertTrue(controller3.uploadCoordinator.remoteShelfBooks.value.isEmpty())
        } finally {
            runBlocking {
                server.stopSuspend(0, 500)
                actor.close()
            }
            fakeSable.stop(0)
            serverScope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellingRestoreMidDownloadDeletesTempFileAndResetsCloudCardState() = runBlocking {
        val root = Files.createTempDirectory("coach-restore-cancel-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val fullBytes = "# Chapter 1\nSynthetic book content for cancellation cleanup verification.\n".toByteArray()
            val expectedSha = MessageDigest.getInstance("SHA-256").digest(fullBytes).joinToString("") { "%02x".format(it) }
            val remoteBook = RemoteShelfBook(
                shelfBookId = "sb_cancel_00000000000001",
                sha256 = expectedSha,
                title = "Cancel Cleanup Book",
                format = "md",
                sizeBytes = fullBytes.size.toLong(),
                createdAt = 1L,
                updatedAt = 2L,
                jobState = BookUploadState.READY,
                progressPct = 100,
                stageLabel = "整理完成",
            )
            var attempt = 0
            val wrotePartial = CompletableDeferred<Path>()
            val hangForever = CompletableDeferred<Unit>()
            val remote = object : UnifiedBookRemoteClient {
                override suspend fun activeAccountKey(): String = "sable-alpha"
                override suspend fun checkCapability(): UnifiedBookCapability = UnifiedBookCapability.Supported("sable-alpha")
                override suspend fun uploadBook(
                    file: Path,
                    format: String,
                    title: String,
                    sha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookUploadOutcome = UnifiedBookUploadOutcome.Failed("unused")

                override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome =
                    UnifiedBookStatusOutcome.Ready(BookUploadState.READY, 100, "整理完成")

                override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome =
                    UnifiedBookDeleteOutcome.Deleted

                override suspend fun listRemoteBooks(): UnifiedBookListOutcome =
                    UnifiedBookListOutcome.Ready(listOf(remoteBook))

                override suspend fun downloadBookOriginal(
                    shelfBookId: String,
                    destination: Path,
                    expectedSha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookDownloadOutcome {
                    attempt++
                    return if (attempt == 1) {
                        Files.write(destination, ByteArray(1024) { 0x41 })
                        onProgress(25)
                        wrotePartial.complete(destination)
                        hangForever.await()
                        UnifiedBookDownloadOutcome.Offline
                    } else {
                        Files.write(destination, fullBytes)
                        onProgress(100)
                        UnifiedBookDownloadOutcome.Downloaded(expectedSha, fullBytes.size.toLong())
                    }
                }
            }
            val coordinator = UnifiedBookUploadCoordinator(lib) { remote }
            coordinator.syncOnce()
            assertEquals(1, coordinator.remoteShelfBooks.value.size)

            // Launch restore in a shelf-like coroutine and cancel it after 1,024 bytes are written.
            val shelfJob = launch(Dispatchers.Default) {
                coordinator.downloadAndImport(remoteBook)
            }
            val tempFile = wrotePartial.await()
            assertEquals(1024L, Files.size(tempFile))
            assertEquals(remoteBook.shelfBookId, coordinator.restoringShelfBookId.value)

            shelfJob.cancel()
            shelfJob.join()

            assertFalse(Files.exists(tempFile), "Cancelled restore must delete partial temp file")
            assertFalse(Files.exists(tempFile.parent), "Cancelled restore must delete temp directory")
            assertNull(coordinator.restoringShelfBookId.value, "Cancelled restore must reset restoringShelfBookId")
            assertEquals(0, coordinator.restoreProgressPct.value)

            // Cloud card is clickable again and succeeds on retry.
            val importedId = coordinator.downloadAndImport(remoteBook)
            assertEquals(expectedSha, importedId)
            assertNull(coordinator.restoringShelfBookId.value)
            assertTrue(coordinator.remoteShelfBooks.value.isEmpty())
            assertEquals(remoteBook.shelfBookId, lib.readUploadEntry("sable-alpha", expectedSha)?.serverShelfBookId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellingRestoreTheMomentCardTurnsBusyStillResetsCloudCard() = runBlocking {
        val root = Files.createTempDirectory("coach-restore-cancel-early-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val fullBytes = "# Chapter 1\nSynthetic book for immediate restore cancellation.\n".toByteArray()
            val expectedSha = MessageDigest.getInstance("SHA-256").digest(fullBytes).joinToString("") { "%02x".format(it) }
            val remoteBook = RemoteShelfBook(
                shelfBookId = "sb_cancel_00000000000002",
                sha256 = expectedSha,
                title = "Early Cancel Book",
                format = "md",
                sizeBytes = fullBytes.size.toLong(),
                createdAt = 1L,
                updatedAt = 2L,
                jobState = BookUploadState.READY,
                progressPct = 100,
                stageLabel = "整理完成",
            )
            val downloadCalls = AtomicInteger(0)
            val remote = object : UnifiedBookRemoteClient {
                override suspend fun activeAccountKey(): String = "sable-alpha"
                override suspend fun checkCapability(): UnifiedBookCapability = UnifiedBookCapability.Supported("sable-alpha")
                override suspend fun uploadBook(
                    file: Path,
                    format: String,
                    title: String,
                    sha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookUploadOutcome = UnifiedBookUploadOutcome.Failed("unused")

                override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome =
                    UnifiedBookStatusOutcome.Ready(BookUploadState.READY, 100, "整理完成")

                override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome =
                    UnifiedBookDeleteOutcome.Deleted

                override suspend fun listRemoteBooks(): UnifiedBookListOutcome =
                    UnifiedBookListOutcome.Ready(listOf(remoteBook))

                override suspend fun downloadBookOriginal(
                    shelfBookId: String,
                    destination: Path,
                    expectedSha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookDownloadOutcome {
                    downloadCalls.incrementAndGet()
                    Files.write(destination, fullBytes)
                    onProgress(100)
                    return UnifiedBookDownloadOutcome.Downloaded(expectedSha, fullBytes.size.toLong())
                }
            }
            val coordinator = UnifiedBookUploadCoordinator(lib) { remote }
            coordinator.syncOnce()

            // Leave the shelf the instant the card turns busy: before the temp directory exists or any download starts.
            val shelfJob = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                coordinator.downloadAndImport(remoteBook)
            }
            val watcher = launch(Dispatchers.Unconfined) {
                coordinator.restoringShelfBookId.first { it == remoteBook.shelfBookId }
                shelfJob.cancel()
            }
            shelfJob.start()
            shelfJob.join()
            watcher.cancel()

            assertEquals(0, downloadCalls.get(), "Cancel must land before the download starts")
            assertNull(coordinator.restoringShelfBookId.value, "Card must not stay busy after an immediate cancel")
            assertEquals(0, coordinator.restoreProgressPct.value)

            // The card is clickable again and the retry succeeds.
            assertEquals(expectedSha, coordinator.downloadAndImport(remoteBook))
            assertEquals(1, downloadCalls.get())
            assertNull(coordinator.restoringShelfBookId.value)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun switchingAccountDuringRestoreCancelsAndNeverWritesBindingToNewAccount() = runBlocking {
        val root = Files.createTempDirectory("coach-restore-account-switch-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val bookBytesA = "# Chapter 1\nAccount Alpha book pulled back during account switch.\n".toByteArray()
            val shaA = MessageDigest.getInstance("SHA-256").digest(bookBytesA).joinToString("") { "%02x".format(it) }
            val remoteBookA = RemoteShelfBook(
                shelfBookId = "sb_alpha_00000000000001",
                sha256 = shaA,
                title = "Alpha Pullback Book",
                format = "md",
                sizeBytes = bookBytesA.size.toLong(),
                createdAt = 1L,
                updatedAt = 2L,
                jobState = BookUploadState.READY,
                progressPct = 100,
                stageLabel = "整理完成",
            )
            var activeKey = "sable-alpha"
            var pauseDownload = true
            val downloadStarted = CompletableDeferred<Path>()
            val downloadGate = CompletableDeferred<Unit>()
            val remote = object : UnifiedBookRemoteClient {
                override suspend fun activeAccountKey(): String = activeKey
                override suspend fun checkCapability(): UnifiedBookCapability = UnifiedBookCapability.Supported(activeKey)
                override suspend fun uploadBook(
                    file: Path,
                    format: String,
                    title: String,
                    sha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookUploadOutcome = UnifiedBookUploadOutcome.Failed("unused")

                override suspend fun fetchStatus(shelfBookId: String): UnifiedBookStatusOutcome =
                    UnifiedBookStatusOutcome.Ready(BookUploadState.READY, 100, "整理完成")

                override suspend fun deleteBook(shelfBookId: String): UnifiedBookDeleteOutcome =
                    UnifiedBookDeleteOutcome.Deleted

                override suspend fun listRemoteBooks(): UnifiedBookListOutcome =
                    UnifiedBookListOutcome.Ready(if (activeKey == "sable-alpha") listOf(remoteBookA) else emptyList())

                override suspend fun downloadBookOriginal(
                    shelfBookId: String,
                    destination: Path,
                    expectedSha256: String,
                    onProgress: (Int) -> Unit,
                ): UnifiedBookDownloadOutcome {
                    Files.write(destination, bookBytesA)
                    if (pauseDownload) {
                        downloadStarted.complete(destination)
                        downloadGate.await()
                    }
                    return UnifiedBookDownloadOutcome.Downloaded(shaA, bookBytesA.size.toLong())
                }
            }

            val coordinator = UnifiedBookUploadCoordinator(lib) { remote }
            coordinator.syncOnce()
            assertEquals("sable-alpha", coordinator.currentAccountKey())

            // Case 1: Account switches mid-download -> active restore is cancelled, temp cleaned, zero bindings written.
            val midDownloadJob = launch(Dispatchers.Default) {
                coordinator.downloadAndImport(remoteBookA)
            }
            val midTempFile = downloadStarted.await()
            activeKey = "sable-beta"
            coordinator.onSessionChanged("sable-beta")
            midDownloadJob.join()
            assertFalse(Files.exists(midTempFile))
            assertTrue(lib.catalog().isEmpty())
            assertNull(lib.readUploadEntry("sable-alpha", shaA))
            assertNull(lib.readUploadEntry("sable-beta", shaA))
            assertNull(coordinator.restoringShelfBookId.value)

            // Case 2: Account switches AFTER download & SHA verification, right before local import/queue commit ->
            // commit must abort and never write Alpha's shelf binding under Beta's account.
            activeKey = "sable-alpha"
            pauseDownload = false
            coordinator.onSessionChanged("sable-alpha")
            coordinator.syncOnce()

            val reachedCommit = CompletableDeferred<String>()
            val releaseCommit = CompletableDeferred<Unit>()
            coordinator.beforeRestoreCommitForTest = { _, pinnedAccount ->
                reachedCommit.complete(pinnedAccount)
                releaseCommit.await()
            }

            val preCommitJob = launch(Dispatchers.Default) {
                coordinator.downloadAndImport(remoteBookA)
            }
            assertEquals("sable-alpha", reachedCommit.await())

            activeKey = "sable-beta"
            coordinator.onSessionChanged("sable-beta")
            coordinator.syncOnce()
            assertEquals("sable-beta", coordinator.currentAccountKey())

            releaseCommit.complete(Unit)
            preCommitJob.join()

            assertTrue(lib.catalog().isEmpty(), "Stale restore must not import after account switch")
            assertNull(lib.readUploadEntry("sable-beta", shaA), "Must never bind Alpha's book to Beta")
            assertNull(lib.readUploadEntry("sable-alpha", shaA))
            assertNull(coordinator.restoringShelfBookId.value)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
