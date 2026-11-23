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
        var lastUploadedFile: Path? = null

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

        override suspend fun deleteBook(shelfBookId: String): Boolean = true
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
}
