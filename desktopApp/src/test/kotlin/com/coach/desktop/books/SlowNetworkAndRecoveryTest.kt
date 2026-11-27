package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopCredentials
import com.coach.desktop.WindowsTextbookGate
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TON-1944 unit and UI tests for slow/flaky network tolerance and automatic offline recovery:
 * 1. First request takes 12 seconds to return -> never misclassified as offline; first question succeeds.
 * 2. First two requests time out and third succeeds -> progressive retry intervals, never shows offline;
 *    only when all retries fail does it mark offline.
 * 3. Offline for 30 seconds then recovers -> background probe detects recovery, clears offline prompts,
 *    and automatically resumes failed first question (首题) and explanation (讲解) without user clicks.
 */
@OptIn(ExperimentalTestApi::class)
class SlowNetworkAndRecoveryTest {

    @Before
    fun resetBefore() {
        CoachConnectivityRegistry.resetForTests()
    }

    @After
    fun resetAfter() {
        CoachConnectivityRegistry.resetForTests()
    }

    private fun isolatedSettings(home: Path, serverUrl: String): DesktopConnectionSettings {
        val settings = DesktopConnectionSettings(
            home = home,
            environment = { null },
            mac = false,
            property = { if (it == "jpackage.app-path") home.resolve("LearningCoach.exe").toString() else null },
        )
        val creds = DesktopCredentials(settings.clientDirectory)
        creds.saveAddress(serverUrl)
        creds.save(serverUrl, PairedCredentials("slow-net-pc", "s".repeat(43), "friend"))
        return settings
    }

    private fun writeSyntheticBook(dir: Path): Path {
        val file = dir.resolve("synthetic-microeconomics.md")
        Files.writeString(
            file,
            buildString {
                appendLine("# Chapter 1: Market Equilibrium")
                appendLine()
                appendLine("Prices adjust when scarcity rises in a competitive market until supply equals demand.")
                appendLine()
                appendLine("Consumer surplus measures the net gain from buying at the market clearing price.")
            },
        )
        return file
    }

    private fun companionPackResponseJson(bodyStr: String): String {
        val req = runCatching { Json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
        val bookId = req?.get("bookId")?.jsonPrimitive?.content ?: "book-1"
        val chapterId = req?.get("chapterId")?.jsonPrimitive?.content ?: "ch-1"
        val sectionId = req?.get("sections")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: "s-1"
        return buildJsonObject {
            put("bookId", bookId)
            put("chapterId", chapterId)
            put("chapterTitle", "Chapter 1: Market Equilibrium")
            put("coreQuestion", "How do prices clear a competitive market?")
            put(
                "keyPoints",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("sectionId", sectionId)
                            put("summary", "竞争市场通过价格调整消除超额需求。")
                            put(
                                "anchor",
                                buildJsonObject {
                                    put("pdfPage", 1)
                                    put("blockId", "$bookId:p1:0")
                                    put("startUtf16", 0)
                                    put("endUtf16", 20)
                                    put("quoteAnchor", "Prices adjust when scarcity rises")
                                },
                            )
                        },
                    )
                },
            )
            put(
                "pretest",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "q-open-1")
                            put("sectionId", sectionId)
                            put("stem", "当竞争市场出现稀缺时，价格会如何变化？")
                            put(
                                "options",
                                buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("价格调整直至供求平衡"))
                                    add(kotlinx.serialization.json.JsonPrimitive("价格保持固定不变"))
                                },
                            )
                            put("correctOption", "价格调整直至供求平衡")
                            put("explanation", "稀缺推动价格调整，直到供给与需求重新相等。")
                        },
                    )
                },
            )
        }.toString()
    }

    private fun explainResponseJson(bodyStr: String): String {
        val req = runCatching { Json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
        val blockId = req?.get("blocks")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: "b:p1:0"
        val page = Regex(":p(\\d+):").find(blockId)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return """{"text":"这段原文说明稀缺会推动市场价格调整直至出清。【S1】","sources":[{"docId":"$blockId","pdfPage":$page,"anchor":"S1"}]}"""
    }

    @Test
    fun scenario1_firstRequestReturnsAfter12SecondsWithoutFalseOfflineAndLoadsFirstQuestion() {
        val root = Files.createTempDirectory("coach-ton1944-12s-").toRealPath()
        val serverPool = Executors.newCachedThreadPool()
        val firstWaveGateMs = 12_200L
        val startedAtNs = AtomicLong(0L)
        val firstResponseElapsedMs = AtomicLong(0L)
        val totalCalls = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverPool
            createContext("/") { exchange ->
                val reqBody = exchange.requestBody.use { it.readBytes() }.decodeToString()
                totalCalls.incrementAndGet()
                val startNs = startedAtNs.compareAndSet(0L, System.nanoTime()).let { startedAtNs.get() }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val remainingSlowMs = firstWaveGateMs - elapsedMs
                if (remainingSlowMs > 0L) {
                    Thread.sleep(remainingSlowMs)
                }
                firstResponseElapsedMs.compareAndSet(0L, (System.nanoTime() - startNs) / 1_000_000L)
                val path = exchange.requestURI.path
                val (status, resp) = when {
                    path == "/v1/public/status" -> 200 to """{"online":true,"unifiedBooks":true,"accountScope":"sable-slow-12s"}"""
                    path == "/v1/books/upload" -> 200 to """{"shelfBookId":"sb_12s","sha256":"${exchange.requestHeaders.getFirst("X-Coach-Book-Sha256").orEmpty()}","job":{"state":"ready","progressPct":100,"stageLabel":"已就绪"}}"""
                    path.startsWith("/v1/books/") && path.endsWith("/status") -> 200 to """{"job":{"state":"ready","progressPct":100,"stageLabel":"已就绪"}}"""
                    path == "/v1/library/companion/chapter" -> 200 to companionPackResponseJson(reqBody)
                    path == "/v1/library/explain" -> 200 to explainResponseJson(reqBody)
                    else -> 200 to "{}"
                }
                val bytes = resp.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val url = "http://127.0.0.1:${server.address.port}"
        val observedStatuses = CopyOnWriteArrayList<CoachConnectivityStatus>()
        val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        monitorScope.launch {
            CoachConnectivityRegistry.flowFor(url).collect { observedStatuses += it }
        }

        try {
            val home = Files.createDirectories(root.resolve("home"))
            val settings = isolatedSettings(home, url)
            val bookFile = writeSyntheticBook(root)
            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            val explainer = BookExplainer(settings, library)
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(1200f, 800f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings) {
                        WindowsTextbookHost(
                            controller = controller,
                            bookExplainer = explainer,
                            chooseBook = { bookFile },
                            firstOpenGuide = true,
                        )
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    controller.state.value.initialized &&
                        onAllNodesWithTag("upload-textbook").fetchSemanticsNodes().isNotEmpty()
                }
                // Trigger first-book import while the 12s slow first request window is active.
                onNodeWithTag("upload-textbook").performClick()

                // Throughout the 12-second window, neither HostOfflineBadge nor "再试一次" may appear.
                waitUntil(timeoutMillis = 25_000) {
                    assertEquals(
                        0,
                        onAllNodesWithTag("host-offline-badge").fetchSemanticsNodes().size,
                        "Slow 12s first response must never trigger host-offline-badge",
                    )
                    assertFalse(
                        CoachConnectivityStatus.OFFLINE in observedStatuses,
                        "Slow 12s first response must never mark connectivity OFFLINE: $observedStatuses",
                    )
                    onAllNodesWithTag("first-taste-stem").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("first-taste-stem").assertIsDisplayed()
            }

            assertTrue(
                firstResponseElapsedMs.get() >= 12_000L,
                "Expected first request to take >= 12s, actual=${firstResponseElapsedMs.get()}ms",
            )
            assertFalse(CoachConnectivityStatus.OFFLINE in observedStatuses, "Statuses: $observedStatuses")
            assertEquals(CoachConnectivityStatus.ONLINE, CoachConnectivityRegistry.statusFor(url))
            assertTrue(totalCalls.get() >= 1)
        } finally {
            monitorScope.cancel()
            server.stop(0)
            serverPool.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun scenario2_firstTwoRequestsTimeOutAndThirdSucceedsWithProgressiveBackoffWithoutShowingOffline() = runBlocking {
        val root = Files.createTempDirectory("coach-ton1944-retry3-").toRealPath()
        val serverPool = Executors.newCachedThreadPool()
        CoachConnectivityRegistry.probePolicy = ConnectivityProbePolicy(
            maxAttempts = 3,
            attemptTimeoutMs = 900L,
            retryDelaysMs = listOf(350L, 950L),
        )

        val statusCalls = AtomicInteger(0)
        val companionCalls = AtomicInteger(0)
        val forceAllTimeout = AtomicBoolean(false)
        val statusArrivalMs = CopyOnWriteArrayList<Long>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverPool
            createContext("/") { exchange ->
                val reqBody = exchange.requestBody.use { it.readBytes() }.decodeToString()
                val path = exchange.requestURI.path
                when (path) {
                    "/v1/public/status" -> {
                        statusArrivalMs += System.nanoTime() / 1_000_000L
                        val n = statusCalls.incrementAndGet()
                        if (forceAllTimeout.get() || n <= 2) {
                            // Exceeds attemptTimeoutMs (900ms) -> triggers real HttpRequestTimeoutException on client.
                            Thread.sleep(1_800L)
                            runCatching { exchange.close() }
                            return@createContext
                        }
                        val bytes = """{"online":true,"unifiedBooks":true,"accountScope":"sable-retry3"}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    "/v1/library/companion/chapter" -> {
                        val n = companionCalls.incrementAndGet()
                        if (n <= 2) {
                            // Do not answer within socket/connect timeout? Companion has 180s request timeout unless connection drops/stalls;
                            // close without response headers after 500ms to simulate dropped/timed-out upstream twice before 3rd succeeds.
                            Thread.sleep(500L)
                            runCatching { exchange.close() }
                            return@createContext
                        }
                        val bytes = companionPackResponseJson(reqBody).toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    else -> {
                        val bytes = "{}".toByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
            }
            start()
        }

        val url = "http://127.0.0.1:${server.address.port}"
        val observedStatuses = CopyOnWriteArrayList<CoachConnectivityStatus>()
        val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        monitorScope.launch {
            CoachConnectivityRegistry.flowFor(url).collect { observedStatuses += it }
        }

        try {
            val home = Files.createDirectories(root.resolve("home"))
            val settings = isolatedSettings(home, url)
            val bookFile = writeSyntheticBook(root)
            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            val book = library.importBook(bookFile)
            ExplainConsent(library.directory).allow(book.id)

            // 2A. Probe: first two attempts time out (> 900ms), third succeeds; progressive interval & zero OFFLINE.
            val explainer = BookExplainer(settings, library)
            val client = checkNotNull(explainer.unifiedUploadClient())
            val resultStatus = client.probeReachability()
            assertEquals(CoachConnectivityStatus.ONLINE, resultStatus)
            // load() also ran stillAuthorized() (which succeeded once statusCalls passed 2) or probe ran 3 attempts:
            assertTrue(statusArrivalMs.size >= 3, "Expected >= 3 probe attempts, got ${statusArrivalMs.size}")
            val gap1 = statusArrivalMs[1] - statusArrivalMs[0]
            val gap2 = statusArrivalMs[2] - statusArrivalMs[1]
            assertTrue(
                gap2 > gap1,
                "Second retry interval ($gap2 ms) must be longer than first retry interval ($gap1 ms)",
            )
            assertFalse(
                CoachConnectivityStatus.OFFLINE in observedStatuses,
                "Two timeouts followed by 3rd success must never emit OFFLINE: $observedStatuses",
            )

            // 2B. FirstTastePanel: first two companion fetches fail transiently, third succeeds without showing Retry/Offline.
            CoachConnectivityRegistry.resetForTests()
            CoachConnectivityRegistry.probePolicy = ConnectivityProbePolicy(
                maxAttempts = 3,
                attemptTimeoutMs = 900L,
                retryDelaysMs = listOf(250L, 650L),
            )
            observedStatuses.clear()
            runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = false) {
                        FirstTastePanel(
                            book = book,
                            explainer = explainer,
                            connectivity = CoachConnectivityStatus.UNKNOWN,
                            onReadBook = {},
                            onDone = {},
                        )
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    assertEquals(
                        0,
                        onAllNodesWithTag("first-taste-primary").fetchSemanticsNodes().size.let { count ->
                            if (onAllNodesWithTag("first-taste-stem").fetchSemanticsNodes().isEmpty()) count else 0
                        },
                        "FirstTastePanel must not flash 再试一次 before all 3 attempts fail",
                    )
                    onAllNodesWithTag("first-taste-stem").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(3, companionCalls.get())
                assertFalse(CoachConnectivityStatus.OFFLINE in observedStatuses)
            }

            // 2C. Control check: when all 3 attempts time out, status DOES transition to OFFLINE.
            CoachConnectivityRegistry.resetForTests()
            CoachConnectivityRegistry.probePolicy = ConnectivityProbePolicy(
                maxAttempts = 3,
                attemptTimeoutMs = 600L,
                retryDelaysMs = listOf(150L, 350L),
            )
            statusCalls.set(0)
            forceAllTimeout.set(true)
            val finalStatus = client.probeReachability()
            assertEquals(3, statusCalls.get())
            assertEquals(CoachConnectivityStatus.OFFLINE, finalStatus)
            assertEquals(CoachConnectivityStatus.OFFLINE, CoachConnectivityRegistry.statusFor(url))
        } finally {
            monitorScope.cancel()
            server.stop(0)
            serverPool.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun scenario3_offlineFor30SecondsThenRecoversAndAutomaticallyResumesFirstQuestionAndExplanation() {
        val root = Files.createTempDirectory("coach-ton1944-30s-recover-").toRealPath()
        val serverPool = Executors.newCachedThreadPool()
        CoachConnectivityRegistry.probePolicy = ConnectivityProbePolicy(
            maxAttempts = 3,
            attemptTimeoutMs = 1_000L,
            retryDelaysMs = listOf(150L, 350L),
        )

        val online = AtomicBoolean(false)
        val companionCalls = AtomicInteger(0)
        val explainCalls = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverPool
            createContext("/") { exchange ->
                val reqBody = exchange.requestBody.use { it.readBytes() }.decodeToString()
                if (!online.get()) {
                    exchange.close()
                    return@createContext
                }
                val path = exchange.requestURI.path
                val (status, resp) = when {
                    path == "/v1/public/status" -> 200 to """{"online":true,"unifiedBooks":true,"accountScope":"sable-30s-recover"}"""
                    path == "/v1/books/upload" -> 200 to """{"shelfBookId":"sb_30s","sha256":"${exchange.requestHeaders.getFirst("X-Coach-Book-Sha256").orEmpty()}","job":{"state":"ready","progressPct":100,"stageLabel":"已就绪"}}"""
                    path.startsWith("/v1/books/") && path.endsWith("/status") -> 200 to """{"job":{"state":"ready","progressPct":100,"stageLabel":"已就绪"}}"""
                    path == "/v1/library/companion/chapter" -> {
                        companionCalls.incrementAndGet()
                        200 to companionPackResponseJson(reqBody)
                    }
                    path == "/v1/library/explain" -> {
                        explainCalls.incrementAndGet()
                        200 to explainResponseJson(reqBody)
                    }
                    else -> 200 to "{}"
                }
                val bytes = resp.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val url = "http://127.0.0.1:${server.address.port}"
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val settings = isolatedSettings(home, url)
            val bookFile = writeSyntheticBook(root)
            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            val explainer = BookExplainer(settings, library)
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalConnectivityPollMs provides 600L) {
                        WindowsTextbookGate(settings) {
                            WindowsTextbookHost(
                                controller = controller,
                                bookExplainer = explainer,
                                chooseBook = { bookFile },
                                firstOpenGuide = true,
                            )
                        }
                    }
                }

                // Wait for all 3 initial probe attempts to fail and show the offline badge.
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.initialized &&
                        onAllNodesWithTag("host-offline-badge").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("upload-textbook").fetchSemanticsNodes().isNotEmpty()
                }
                val outageStartedNs = System.nanoTime()

                // User imports the first book while offline -> FirstTastePanel holds on offline prompt + "再试一次".
                onNodeWithTag("upload-textbook").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("first-taste-primary").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("first-taste-status").assertTextContains("现在没联网", substring = true)

                // Keep network down for a full 30 seconds while background probes run.
                while ((System.nanoTime() - outageStartedNs) / 1_000_000L < 30_000L) {
                    waitForIdle()
                    Thread.sleep(250L)
                }
                val offlineDurationMs = (System.nanoTime() - outageStartedNs) / 1_000_000L
                assertTrue(offlineDurationMs >= 30_000L, "Expected >= 30s outage, got ${offlineDurationMs}ms")
                // Still holding offline right before recovery:
                onNodeWithTag("first-taste-status").assertTextContains("现在没联网", substring = true)

                // Part A: Network recovers -> WITHOUT clicking "再试一次", FirstTastePanel automatically loads the first question!
                online.set(true)
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("first-taste-stem").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("first-taste-stem").assertIsDisplayed()
                assertTrue(companionCalls.get() >= 1)

                // Answer the first question and enter the reader.
                val importedBook = library.catalog().single()
                ExplainConsent(library.directory).allow(importedBook.id)
                onNodeWithTag("first-taste-option-0").performClick()
                onNodeWithTag("first-taste-primary").performClick()
                onNodeWithTag("first-taste-explanation").assertIsDisplayed()
                onNodeWithTag("first-taste-primary").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste").fetchSemanticsNodes().isEmpty() &&
                        controller.state.value.screen == BookScreen.READER
                }

                // Part B: Drop network again, trigger inline "讲讲" explanation while offline -> shows offline notice;
                // then restore network -> WITHOUT clicking "重试", offline notice disappears and explanation loads automatically!
                online.set(false)
                waitUntil(timeoutMillis = 10_000) {
                    CoachConnectivityRegistry.isOffline(url)
                }
                waitForIdle()

                val targetQuote = "Prices adjust when scarcity rises"
                val textNode = onNode(hasTestTag("reader-text") and hasText(targetQuote, substring = true))
                val layouts = mutableListOf<TextLayoutResult>()
                textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val qStart = layout.layoutInput.text.text.indexOf(targetQuote)
                val leftBox = layout.getBoundingBox(qStart)
                val rightBox = layout.getBoundingBox(qStart + targetQuote.length - 1)
                textNode.performMouseInput {
                    moveTo(Offset(leftBox.left + 0.5f, leftBox.center.y))
                    press()
                    moveTo(Offset(rightBox.right - 0.5f, rightBox.center.y))
                    release()
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("selection-action-explain").fetchSemanticsNodes().isNotEmpty()
                }
                val explainsBefore = explainCalls.get()
                onNodeWithTag("selection-action-explain").performClick()
                waitUntil(timeoutMillis = 8_000) {
                    onAllNodesWithTag("inline-explain-error").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("companion-ask-offline-notice").fetchSemanticsNodes().isNotEmpty()
                }

                // Restore network -> zero clicks! Offline notice & error vanish, and inline-explain-turn-0 renders!
                online.set(true)
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("inline-explain-error").fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithTag("companion-ask-offline-notice").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(explainCalls.get() > explainsBefore)

                // Return to Home and confirm top-bar offline badge also disappeared automatically.
                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.HOME }
                onAllNodesWithTag("host-offline-badge").assertCountEquals(0)
                onNodeWithText(OfflineCopy.HOST_BADGE_OFFLINE).assertDoesNotExist()
            }
        } finally {
            server.stop(0)
            serverPool.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }
}
