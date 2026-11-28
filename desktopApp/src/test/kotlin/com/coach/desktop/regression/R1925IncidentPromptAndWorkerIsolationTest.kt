package com.coach.desktop.regression

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopCredentials
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.WindowsTextbookGate
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.Citation
import com.coach.desktop.books.CoachConnectivityRegistry
import com.coach.desktop.books.ExplainResult
import com.coach.desktop.books.ExplainSectionContext
import com.coach.desktop.books.Explanation
import com.coach.desktop.books.FollowUpTurn
import com.coach.desktop.books.SourceBlock
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.incident.IncidentLog
import com.coach.desktop.incident.IncidentStore
import com.coach.desktop.incident.incidentDirectory
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TON-1925：
 * 1) WindowsTextbookGate 的 IncidentExitPrompt 在首页可用（查看/发送/不发），进入阅读器后不覆盖顶部选中浮条与底栏，
 *    保持待处理状态回到首页后仍可处理；
 * 2) friend-path-worker.ps1 按轮设置独立 COACH_INCIDENT_DIR，后一轮读不到前一轮的异常退出标记。
 */
@OptIn(ExperimentalTestApi::class)
class R1925IncidentPromptAndWorkerIsolationTest {
    @Test
    fun incidentPromptShowsOnHomeAndNeverCoversReaderSelectionOrBottomHud() {
        CoachConnectivityRegistry.resetForTests()
        val root = Files.createTempDirectory("coach-r1925-gate-").toRealPath()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path.orEmpty()
                val (code, body) = when (path) {
                    "/v1/public/status" -> 200 to "{}"
                    "/v1/incidents" -> 204 to ""
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, if (code == 204) -1L else bytes.size.toLong())
                if (code != 204) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val incidentDir = Files.createDirectories(root.resolve("incidents"))
            // Simulate an unclean previous process exit with a local incident record.
            val prior = IncidentStore(incidentDir, pid = { 999_999L })
            assertNull(prior.beginSession())
            prior.recordImportFailure("synthetic prior failure")

            IncidentLog.startSession(home) { key ->
                if (key == "COACH_INCIDENT_DIR") incidentDir.toString() else null
            }
            assertNotNull(IncidentLog.currentOffer(), "Expected pending incident offer after unclean exit")

            val serverUrl = "http://127.0.0.1:${server.address.port}"
            val settings = DesktopConnectionSettings(
                home = home,
                environment = { null },
                mac = false,
                property = { if (it == "jpackage.app-path") home.resolve("LearningCoach.exe").toString() else null },
            )
            val creds = DesktopCredentials(settings.clientDirectory)
            creds.saveAddress(serverUrl)
            creds.save(serverUrl, PairedCredentials("r1925-pc", "t".repeat(43), "friend"))

            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            val quote = "Marginal utility diminishes steadily along a synthetic curve."
            val bookFile = root.resolve("sample.md")
            Files.writeString(
                bookFile,
                """
                # Chapter 1: Synthetic Choice

                $quote

                More text keeps the reading view populated on the first page.
                """.trimIndent(),
            )
            library.importBook(bookFile)

            val explainer = StubSelectionExplainer(home, library, serverUrl)
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings, darkTheme = false) {
                        WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                    }
                }

                // 1. On Home: 顶栏角落的小徽标在，点开才有查看/发送/不发，且不压顶栏、不盖首页主按钮。
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy &&
                        controller.state.value.screen == BookScreen.HOME &&
                        onAllNodesWithTag("home-read").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-exit-prompt").assertIsDisplayed()

                val hostBarBounds = onNodeWithTag("host-bar").fetchSemanticsNode().boundsInRoot
                val badgeBounds = onNodeWithTag("incident-exit-prompt").fetchSemanticsNode().boundsInRoot
                val homeReadBounds = onNodeWithTag("home-read").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    badgeBounds.top >= hostBarBounds.top - 1f && badgeBounds.bottom <= hostBarBounds.bottom + 1f,
                    "Badge must sit inside HostTopBar: badge=$badgeBounds hostBar=$hostBarBounds",
                )
                assertTrue(
                    badgeBounds.height < hostBarBounds.height,
                    "Badge must be smaller than the bar height: badge=$badgeBounds hostBar=$hostBarBounds",
                )
                assertTrue(
                    badgeBounds.bottom <= homeReadBounds.top + 1f || badgeBounds.top >= homeReadBounds.bottom - 1f,
                    "Badge overlapped home-read button: badge=$badgeBounds homeRead=$homeReadBounds",
                )

                // Actions stay behind the badge, not on the page.
                assertTrue(onAllNodesWithTag("incident-send").fetchSemanticsNodes().isEmpty())
                onNodeWithTag("incident-exit-prompt").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-send").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-view").assertIsDisplayed()
                onNodeWithTag("incident-send").assertIsDisplayed()
                onNodeWithTag("incident-decline").assertIsDisplayed()
                onNodeWithTag("incident-never-ask").assertIsDisplayed()

                // Preview dialog works on Home, then closes while keeping the offer pending.
                onNodeWithTag("incident-view").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-preview").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-preview-close").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-preview").fetchSemanticsNodes().isEmpty()
                }
                assertNotNull(IncidentLog.currentOffer())

                // 2. Keep pending and enter Reader: prompt must stay hidden and must not cover selection pill or bottom bar.
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.screen == BookScreen.READER &&
                        onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                assertTrue(
                    onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty(),
                    "IncidentExitPrompt must not appear inside the reader",
                )

                // Verify top bar and bottom scrubber are both unobstructed by IncidentExitPrompt.
                onNodeWithTag("textbook-reader").performMouseInput {
                    moveTo(Offset(320f, 590f))
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("immersive-bottom-hud").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("immersive-back").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("immersive-bottom-hud").assertIsDisplayed()
                onNodeWithTag("immersive-back").assertIsDisplayed()
                assertTrue(onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty())

                val textNode = onNode(hasTestTag("reader-text") and hasText(quote, substring = true))
                val layouts = mutableListOf<TextLayoutResult>()
                textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val start = layout.layoutInput.text.text.indexOf(quote)
                val firstBox = layout.getBoundingBox(start)
                val lastBox = layout.getBoundingBox(start + quote.lastIndex)
                textNode.performMouseInput {
                    moveTo(Offset(firstBox.left + 1f, firstBox.center.y))
                    press()
                    moveTo(Offset(lastBox.right - 1f, lastBox.center.y))
                    release()
                }

                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("selection-explain-toolbar").assertIsDisplayed()
                onNodeWithTag("selection-action-explain").assertIsDisplayed()
                assertTrue(
                    onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty(),
                    "IncidentExitPrompt appeared while selection toolbar was shown",
                )

                // Click 讲讲 on the floating toolbar and confirm explanation returns.
                onNodeWithTag("selection-action-explain").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("explain-consent-allow").fetchSemanticsNodes().isNotEmpty() ||
                        onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty()
                }
                if (onAllNodesWithTag("explain-consent-allow").fetchSemanticsNodes().isNotEmpty()) {
                    onNodeWithTag("explain-consent-allow").performClick()
                }
                waitUntil(timeoutMillis = 15_000) {
                    explainer.selections.isNotEmpty() &&
                        onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("inline-explain-turn-0").assertIsDisplayed()

                // 3. Return to Home: pending prompt is visible again and decline keeps local files intact.
                onNodeWithTag("textbook-reader").performMouseInput {
                    moveTo(Offset(320f, 10f))
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("immersive-back").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.screen == BookScreen.HOME &&
                        onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-exit-prompt").assertIsDisplayed()
                onNodeWithTag("incident-exit-prompt").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-decline").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-decline").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty()
                }
                assertNull(IncidentLog.currentOffer())
                assertTrue(prior.incidentFiles().isNotEmpty(), "Declining must keep local incident records on disk")
            }
        } finally {
            server.stop(0)
            CoachConnectivityRegistry.resetForTests()
            IncidentLog.resetForTest()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun homeIncidentPromptSendsOfferToServerWhenClicked() {
        CoachConnectivityRegistry.resetForTests()
        val root = Files.createTempDirectory("coach-r1925-send-").toRealPath()
        val uploaded = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path.orEmpty()
                val reqBody = ex.requestBody.use { it.readBytes() }.decodeToString()
                val (code, body) = when (path) {
                    "/v1/public/status" -> 200 to "{}"
                    "/v1/incidents" -> {
                        uploaded += reqBody
                        204 to ""
                    }
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, if (code == 204) -1L else bytes.size.toLong())
                if (code != 204) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val incidentDir = Files.createDirectories(root.resolve("incidents"))
            val prior = IncidentStore(incidentDir, pid = { 888_888L })
            assertNull(prior.beginSession())
            prior.recordImportFailure("synthetic uploadable failure")

            IncidentLog.startSession(home) { key ->
                if (key == "COACH_INCIDENT_DIR") incidentDir.toString() else null
            }
            assertNotNull(IncidentLog.currentOffer())

            val serverUrl = "http://127.0.0.1:${server.address.port}"
            val settings = DesktopConnectionSettings(
                home = home,
                environment = { null },
                mac = false,
                property = { if (it == "jpackage.app-path") home.resolve("LearningCoach.exe").toString() else null },
            )
            val creds = DesktopCredentials(settings.clientDirectory)
            creds.saveAddress(serverUrl)
            creds.save(serverUrl, PairedCredentials("r1925-send-pc", "t".repeat(43), "friend"))

            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings, darkTheme = false) {
                        WindowsTextbookHost(controller) { null }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("windows-host").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-exit-prompt").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-send").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-send").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    uploaded.size == 1 &&
                        onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(uploaded.single().contains("kind: import_failure"))
                assertNull(IncidentLog.currentOffer())
            }
        } finally {
            server.stop(0)
            CoachConnectivityRegistry.resetForTests()
            IncidentLog.resetForTest()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sentReportSurvivesImmediateNavigationToReaderAndStaysResolvedOnReturn() {
        CoachConnectivityRegistry.resetForTests()
        val root = Files.createTempDirectory("coach-r1925-nav-").toRealPath()
        val requestSeen = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val responseSent = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val path = ex.requestURI.path.orEmpty()
                val (code, body) = when (path) {
                    "/v1/public/status" -> 200 to "{}"
                    "/v1/incidents" -> {
                        ex.requestBody.use { it.readBytes() }
                        requestSeen.countDown()
                        check(releaseResponse.await(10, TimeUnit.SECONDS))
                        204 to ""
                    }
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, if (code == 204) -1L else bytes.size.toLong())
                if (code != 204) ex.responseBody.use { it.write(bytes) } else ex.close()
                if (path == "/v1/incidents") responseSent.countDown()
            }
            start()
        }
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val incidentDir = Files.createDirectories(root.resolve("incidents"))
            val prior = IncidentStore(incidentDir, pid = { 777_777L })
            assertNull(prior.beginSession())
            prior.recordImportFailure("synthetic in-flight failure")

            IncidentLog.startSession(home) { key ->
                if (key == "COACH_INCIDENT_DIR") incidentDir.toString() else null
            }
            assertNotNull(IncidentLog.currentOffer())

            val serverUrl = "http://127.0.0.1:${server.address.port}"
            val settings = DesktopConnectionSettings(
                home = home,
                environment = { null },
                mac = false,
                property = { if (it == "jpackage.app-path") home.resolve("LearningCoach.exe").toString() else null },
            )
            val creds = DesktopCredentials(settings.clientDirectory)
            creds.saveAddress(serverUrl)
            creds.save(serverUrl, PairedCredentials("r1925-nav-pc", "t".repeat(43), "friend"))

            val library = TextbookLibrary(home.resolve("books"), todayCardEnabled = false)
            val bookFile = root.resolve("sample.md")
            Files.writeString(bookFile, "# Chapter 1\n\nSynthetic text for navigation test.")
            library.importBook(bookFile)

            val explainer = StubSelectionExplainer(home, library, serverUrl)
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings, darkTheme = false) {
                        WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy &&
                        controller.state.value.screen == BookScreen.HOME &&
                        onAllNodesWithTag("home-read").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-exit-prompt").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("incident-send").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("incident-send").performClick()
                waitUntil(timeoutMillis = 6_000) { requestSeen.count == 0L }

                // Navigate immediately to Reader while the upload is still in flight.
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.screen == BookScreen.READER &&
                        onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty())

                releaseResponse.countDown()
                waitUntil(timeoutMillis = 6_000) { responseSent.count == 0L }
                waitUntil(timeoutMillis = 6_000) { IncidentLog.currentOffer() == null }

                // Return to Home: the already-sent prompt must stay resolved.
                onNodeWithTag("textbook-reader").performMouseInput {
                    moveTo(Offset(320f, 10f))
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("immersive-back").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.screen == BookScreen.HOME &&
                        onAllNodesWithTag("home-read").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                assertTrue(
                    onAllNodesWithTag("incident-exit-prompt").fetchSemanticsNodes().isEmpty(),
                    "Completed send must stay resolved after returning to Home",
                )
            }
        } finally {
            releaseResponse.countDown()
            server.stop(0)
            CoachConnectivityRegistry.resetForTests()
            IncidentLog.resetForTest()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun friendPathWorkerIsolatesCoachIncidentDirAcrossRuns() {
        val repoRoot = Path.of("").toAbsolutePath().normalize().let { cwd ->
            if (Files.exists(cwd.resolve("tools/smoke/friend-path-worker.ps1"))) cwd else cwd.parent
        }
        val worker = repoRoot.resolve("tools/smoke/friend-path-worker.ps1")
        assertTrue(Files.isRegularFile(worker), "Missing $worker")
        val script = Files.readString(worker)
        val envLineIndex = script.indexOf("\$env:COACH_INCIDENT_DIR")
        val startIndex = script.indexOf("Mark-ExistingClients")
        assertTrue(envLineIndex > 0, "friend-path-worker.ps1 must set COACH_INCIDENT_DIR")
        assertTrue(envLineIndex < startIndex, "COACH_INCIDENT_DIR must be set before launching the client")
        assertTrue(
            Regex("""Join-Path\s+\${'$'}OutDir\s+'incidents'""").containsMatchIn(script),
            "COACH_INCIDENT_DIR must live inside each run's OutDir",
        )

        val root = Files.createTempDirectory("coach-r1925-worker-")
        try {
            val home = root.resolve("shared-home")
            val run1IncidentDir = root.resolve("run1/incidents")
            val run2IncidentDir = root.resolve("run2/incidents")
            assertNotEquals(
                incidentDirectory(home) { if (it == "COACH_INCIDENT_DIR") run1IncidentDir.toString() else null },
                incidentDirectory(home) { if (it == "COACH_INCIDENT_DIR") run2IncidentDir.toString() else null },
            )
            // Unclean exit left in run1 must not be visible in run2.
            val store1 = IncidentStore(incidentDirectory(home) { if (it == "COACH_INCIDENT_DIR") run1IncidentDir.toString() else null }, pid = { 101L })
            assertNull(store1.beginSession())
            store1.recordImportFailure("run1 failure")

            val store2 = IncidentStore(incidentDirectory(home) { if (it == "COACH_INCIDENT_DIR") run2IncidentDir.toString() else null }, pid = { 102L })
            assertNull(store2.beginSession(), "Run 2 must not observe run 1's unclean exit marker")
            assertTrue(store2.incidentFiles().isEmpty())
            assertFalse(Files.exists(home.resolve("Coach/incidents")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class StubSelectionExplainer(
        home: Path,
        library: TextbookLibrary,
        serverUrl: String,
    ) : BookExplainer(DesktopConnectionSettings(home = home, environment = { null }), library) {
        val selections = mutableListOf<SourceBlock>()

        init {
            identity = DesktopIdentity(serverUrl, PairedCredentials("r1925-pc", "t".repeat(43), "friend"))
            loaded = true
        }

        override suspend fun load() {
            loaded = true
        }

        override suspend fun explainSelection(
            kind: String,
            chosen: SourceBlock,
            context: ExplainSectionContext?,
            question: String?,
            history: List<FollowUpTurn>,
            requestId: String,
        ): ExplainResult {
            selections += chosen
            return ExplainResult.Done(
                Explanation(
                    text = "合成讲解，第 ${chosen.page} 页。",
                    citations = listOf(Citation("S1", chosen.id, chosen.page)),
                    elapsedMs = 1L,
                ),
            )
        }
    }
}
