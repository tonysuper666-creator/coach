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
import com.coach.ui.CoachTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
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
            val lib = TextbookLibrary(
                root.resolve("library"),
                ocr = BookOcr { _, control ->
                    while (!control.isCancelled()) Thread.sleep(10)
                    control.check()
                    ""
                },
            )
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
                    deletedOnServer += account to shelfBookId
                    val removed = remoteShelfRecords.remove(account to shelfBookId) != null
                    return if (removed) UnifiedBookDeleteOutcome.Deleted else UnifiedBookDeleteOutcome.NotFound
                }
            }

            val controller = TextbookController(lib).apply { unifiedUploadClient = remote }
            controller.onUploadIdentityChanged("sable-alpha")?.join()

            // Start import as Account A (sable-alpha) and wait until upload has produced a server shelfBookId.
            val importJob = launch(Dispatchers.Default) {
                controller.importBook(imageFile)
            }
            while (controller.state.value.importing?.recognizing != true) {
                kotlinx.coroutines.delay(10)
            }
            val importedId = controller.state.value.importing!!.bookId!!
            while (lib.readUploadEntry("sable-alpha", importedId)?.serverShelfBookId == null) {
                kotlinx.coroutines.delay(10)
            }
            val alphaShelfId = lib.readUploadEntry("sable-alpha", importedId)!!.serverShelfBookId!!

            // Switch to Account B (sable-beta) while offline so beta's copy is still waiting to upload, then cancel Account A's import!
            lib.enqueueBookUpload("sable-beta", importedId, importedId, "png", "scan-book")
            serverOnline = false
            activeKey = "sable-beta"
            controller.onUploadIdentityChanged("sable-beta")?.join()

            controller.cancelImport()
            importJob.join()

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
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
