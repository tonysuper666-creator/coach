package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopPairing
import com.coach.desktop.DesktopPairingGate
import com.coach.desktop.WindowsTextbookGate
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * TON-1782：1.1.2 起 Windows 和 Mac 默认开启「用 Sable 账号登录」，显式传 `-Pcoach.sableLogin=false` 时才收起；
 * Windows 教材入口启动即全屏登录页，配对码收在小链接里，登录进书架、重开 0 次点击回原位、401 回登录页、断网照常可读。
 * 假服务只跑在本机随机回环端口，从不碰生产端口。
 */
@OptIn(ExperimentalTestApi::class)
class SableLoginEntryTest {
    @Test fun explicitFalseOffersOnlyThePairingCodeAndItConnects() {
        PairingMac().use { mac ->
            val (library, book) = sample()
            val settings = settings(Files.createTempDirectory("sable-hidden-first-"), sableLogin = false)
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings, darkTheme = false) {
                        val explainer = remember { BookExplainer(settings, library) }
                        CoachTheme(darkTheme = false) { BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), explainer) }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("login-pairing-form").fetchSemanticsNodes().isNotEmpty() }
                assertNoSableText()
                onNodeWithTag("login-pairing-toggle").assertDoesNotExist()
                onNodeWithTag("login-address").assertExists().performTextClearance()
                onNodeWithTag("login-address").performTextInput(mac.url)
                onNodeWithTag("login-code").assertExists().performTextInput("123456")
                onNodeWithTag("login-pair").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithTag("explain-section-trigger").fetchSemanticsNodes()
                            .any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
                }
                onNodeWithTag("explain-section-trigger").assertIsEnabled()
                assertTrue(onAllNodesWithText("已连上", substring = true).fetchSemanticsNodes().isEmpty(), "连接正常时不显示连接提示文案")
            }
            assertEquals(1, mac.pairs.get())
        }
    }

    @Test fun revokedConnectionGoesStraightBackToThePairingCodeWhenSableDisabled() {
        PairingMac().use { mac ->
            val (library, book) = sample()
            val settings = settings(Files.createTempDirectory("sable-hidden-revoked-"), sableLogin = false)
            val explainer = BookExplainer(settings, library)
            assertTrue(kotlinx.coroutines.runBlocking { explainer.pair(mac.url, "123456") })
            var showRevoked = true
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings, darkTheme = true) {
                        val activeExplainer = remember {
                            if (showRevoked) { showRevoked = false; explainer } else BookExplainer(settings, library)
                        }
                        CoachTheme(darkTheme = true) { BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), activeExplainer) }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { explainer.loaded }
                runOnIdle { explainer.markRevoked() }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-revoked").fetchSemanticsNodes().isNotEmpty() }
                assertNoSableText()
                onNodeWithTag("explain-relogin").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("login-pairing-form").fetchSemanticsNodes().isNotEmpty() }
                assertNoSableText()
                onNodeWithTag("login-pairing-toggle").assertDoesNotExist()
                onNodeWithTag("login-address").assertExists().performTextClearance()
                onNodeWithTag("login-address").performTextInput(mac.url)
                onNodeWithTag("login-code").assertExists().performTextInput("654321")
                onNodeWithTag("login-pair").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithTag("explain-section-trigger").fetchSemanticsNodes()
                            .any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
                }
                onNodeWithTag("explain-section-trigger").assertIsEnabled()
                assertTrue(onAllNodesWithText("已连上", substring = true).fetchSemanticsNodes().isEmpty(), "重新配对后不显示连接提示文案")
            }
            assertEquals(2, mac.pairs.get())
            assertFalse(ExplainTrouble.REVOKED.contains("登录"), "停用那一句不能再叫人去登录")
        }
    }

    @Test fun existingPairingStillOpensConnectedWithoutAForm() {
        PairingMac().use { mac ->
            val (library, book) = sample()
            val settings = settings(Files.createTempDirectory("sable-hidden-existing-"), sableLogin = true)
            assertTrue(kotlinx.coroutines.runBlocking { BookExplainer(settings, library).pair(mac.url, "123456") })
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent { CoachTheme(darkTheme = false) { BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), BookExplainer(settings, library)) } }
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("explain-section-trigger").fetchSemanticsNodes()
                        .any { it.config.getOrNull(SemanticsProperties.Disabled) == null }
                }
                onNodeWithTag("explain-pairing").assertDoesNotExist()
                assertTrue(onAllNodesWithText("已连上", substring = true).fetchSemanticsNodes().isEmpty())
            }
            assertEquals(1, mac.pairs.get())
        }
    }

    @Test fun windowsAndMacDefaultToShowingSableUnlessExplicitlyTurnedOff() {
        val home = Files.createTempDirectory("sable-flag-")
        for (mac in listOf(false, true)) {
            for ((value, shown) in listOf(null to true, "" to true, "true" to true, " true " to true, "false" to false, " false " to false)) {
                val settings = DesktopConnectionSettings(home, { null }, mac = mac, property = { if (it == "coach.sableLogin") value else null })
                assertEquals(shown, settings.sableLoginShown(), "mac=$mac, coach.sableLogin=$value")
            }
        }
    }

    @Test fun pairingGateFollowsTheSameBuildDecision() {
        for (shown in listOf(false, true)) {
            val settings = settings(Files.createTempDirectory("sable-gate-"), sableLogin = shown)
            runComposeUiTest {
                setContent { Box(Modifier.size(840.dp, 720.dp)) { DesktopPairingGate(settings) { Text("已进入学习", Modifier.testTag("desktop-paired")) } } }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("连接服务").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("配对") and hasClickAction()).assertExists()
                if (shown) onNodeWithText("用 Sable 账号登录").assertExists() else assertNoSableText()
            }
        }
    }

    @Test fun startupGateShowsSableFirstHidesPairingBehindLinkAndAllowsBackupCode() {
        PairingMac().use { mac ->
            val settings = settings(Files.createTempDirectory("startup-gate-backup-"), sableLogin = true)
            runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings, darkTheme = false) {
                        Text("书架已打开", Modifier.testTag("shelf-entered"))
                    }
                }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("用 Sable 账号登录").assertIsDisplayed()
                onNodeWithTag("login-subtitle").assertIsDisplayed()
                assertTrue(onAllNodesWithText("朋友服务", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                onNodeWithTag("login-pairing-toggle").assertIsDisplayed()
                onNodeWithTag("login-pairing-form").assertDoesNotExist()

                // Click subtle link to expand backup pairing code form and connect.
                onNodeWithTag("login-pairing-toggle").performClick()
                onNodeWithTag("login-pairing-form").assertIsDisplayed()
                onNodeWithTag("login-address").performTextClearance()
                onNodeWithTag("login-address").performTextInput(mac.url)
                onNodeWithTag("login-code").performTextInput("123456")
                onNodeWithTag("login-pair").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("shelf-entered").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("textbook-login-screen").assertDoesNotExist()
            }
            assertEquals(1, mac.pairs.get())
        }
    }

    @Test fun startupGateSableLoginEntersShelfAndReopenResumesLastPageWithZeroClicks() {
        SableTestServer().use { server ->
            val home = Files.createTempDirectory("startup-gate-sable-").toRealPath()
            val libDir = home.resolve("library")
            val source = syntheticTextbook(home.resolve("fixture"))
            val settings = sableSettings(home, server.url)
            val http = HttpClient.newHttpClient()
            val bg = Executors.newSingleThreadExecutor()
            var generation by mutableStateOf(0)
            var activeController by mutableStateOf(TextbookController(TextbookLibrary(libDir)))
            try {
                runSkikoComposeUiTest(size = Size(1200f, 850f), density = Density(1f)) {
                    setContent {
                        val handler = object : UriHandler {
                            override fun openUri(uri: String) {
                                bg.submit {
                                    http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                                }
                            }
                        }
                        CompositionLocalProvider(LocalUriHandler provides handler) {
                            key(generation) {
                                WindowsTextbookGate(settings, sableIssuer = server.url, darkTheme = false) {
                                    WindowsTextbookHost(activeController) { source }
                                }
                            }
                        }
                    }
                    // 1. Fresh launch -> full-screen login screen.
                    waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("用 Sable 账号登录").performClick()

                    // 2. After Sable login -> enters bookshelf.
                    waitUntil(timeoutMillis = 25_000) { onAllNodesWithTag("windows-host").fetchSemanticsNodes().isNotEmpty() }
                    waitUntil(timeoutMillis = 20_000) { !activeController.state.value.busy }
                    onNodeWithTag("upload-textbook").performClick()
                    waitUntil(timeoutMillis = 20_000) {
                        onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty() &&
                            activeController.state.value.screen == BookScreen.READER &&
                            !activeController.state.value.busy
                    }
                    assertNull(activeController.state.value.prompt)
                    val beforePage = activeController.state.value.page
                    if (onAllNodesWithTag("next-page").fetchSemanticsNodes().isEmpty()) {
                        onNodeWithTag("textbook-reader").performKeyInput { pressKey(Key.Tab) }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("next-page").fetchSemanticsNodes().isNotEmpty() }
                    }
                    onNodeWithTag("next-page").assertIsEnabled().performClick()
                    waitUntil(timeoutMillis = 20_000) { activeController.state.value.page != beforePage && !activeController.state.value.busy }
                    val targetPage = activeController.state.value.page

                    // 3. Close and reopen -> zero clicks, lands directly back on the last page!
                    runOnIdle {
                        activeController = TextbookController(TextbookLibrary(libDir))
                        generation++
                    }
                    waitUntil(timeoutMillis = 20_000) {
                        onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty() &&
                            activeController.state.value.page == targetPage && !activeController.state.value.busy
                    }
                    onNodeWithTag("textbook-login-screen").assertDoesNotExist()
                    if (onAllNodesWithTag("reader-page-number").fetchSemanticsNodes().isEmpty()) {
                        onNodeWithTag("textbook-reader").performKeyInput { pressKey(Key.Tab) }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("reader-page-number").fetchSemanticsNodes().isNotEmpty() }
                    }
                    onNodeWithTag("reader-page-number").assertTextContains("第 $targetPage 页 / 3", substring = true)
                }
            } finally {
                bg.shutdownNow()
            }
        }
    }

    @Test fun startupGateRevocation401ReturnsToLoginWithClearReason() {
        SableTestServer().use { server ->
            val home = Files.createTempDirectory("startup-gate-revoke-").toRealPath()
            val settings = sableSettings(home, server.url)
            val http = HttpClient.newHttpClient()
            val signedIn = kotlinx.coroutines.runBlocking {
                DesktopPairing(settings).sable({ uri, _ ->
                    http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                }, server.url)
            }
            server.revokedTokens += signedIn.credentials.token

            runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings, sableIssuer = server.url, darkTheme = false) {
                        Text("已进入", Modifier.testTag("shelf-entered"))
                    }
                }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("login-revoked-reason").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("login-revoked-reason").assertTextContains("授权已撤销", substring = true)
                onNodeWithText("用 Sable 账号登录").assertIsDisplayed()
                assertNull(DesktopPairing(settings).load())
            }
        }
    }

    @Test fun startupGateOfflineWithSavedIdentityOpensShelfAndReadsNormally() {
        val home = Files.createTempDirectory("startup-gate-offline-").toRealPath()
        val deadPort = ServerSocket(0).use { it.localPort }
        val settings = sableSettings(home, "http://127.0.0.1:$deadPort")
        val libDir = home.resolve("library")
        val library = TextbookLibrary(libDir)
        val book = library.importBook(syntheticTextbook(home.resolve("fixture")))
        library.save(book.copy(readingPage = 2))
        library.saveOpenSession(book.id, "READER")
        // Seed a valid stored credential pointing to the unreachable server (simulating offline).
        com.coach.desktop.DesktopCredentials(settings.clientDirectory).save(
            "http://127.0.0.1:$deadPort",
            com.coach.client.PairedCredentials("offline-device", "a".repeat(43), "friend"),
        )
        com.coach.desktop.DesktopCredentials(settings.clientDirectory).saveAddress("http://127.0.0.1:$deadPort")

        val controller = TextbookController(TextbookLibrary(libDir))
        runSkikoComposeUiTest(size = Size(1200f, 850f), density = Density(1f)) {
            setContent {
                WindowsTextbookGate(settings, darkTheme = false) {
                    WindowsTextbookHost(controller) { null }
                }
            }
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty() &&
                    controller.state.value.page == 2 && !controller.state.value.busy
            }
            onNodeWithTag("textbook-login-screen").assertDoesNotExist()
            if (onAllNodesWithTag("reader-page-number").fetchSemanticsNodes().isEmpty()) {
                onNodeWithTag("textbook-reader").performKeyInput { pressKey(Key.Tab) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("reader-page-number").fetchSemanticsNodes().isNotEmpty() }
            }
            onNodeWithTag("reader-page-number").assertTextContains("第 2 页 / 3", substring = true)
        }
    }

    @Test fun explainPanelInsideGateHidesOldFormAndOffersOnlyReloginOnRevocation() {
        PairingMac().use { mac ->
            val (library, book) = sample()
            val settings = settings(Files.createTempDirectory("explain-gated-"), sableLogin = true)
            val explainer = BookExplainer(settings, library)
            assertTrue(kotlinx.coroutines.runBlocking { explainer.pair(mac.url, "123456") })
            var reloginReason by mutableStateOf<String?>(null)

            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = false) {
                        CompositionLocalProvider(LocalBookRelogin provides { reason -> reloginReason = reason }) {
                            BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), explainer)
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { explainer.loaded }
                // Logged in: no connection form at all.
                onNodeWithTag("explain-pairing").assertDoesNotExist()
                onNodeWithTag("explain-address").assertDoesNotExist()
                onNodeWithTag("explain-code").assertDoesNotExist()
                onNodeWithTag("explain-relogin").assertDoesNotExist()

                // Revoked: still no old connection form; only a single "重新登录" button.
                runOnIdle { explainer.markRevoked() }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("explain-relogin").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("explain-pairing").assertDoesNotExist()
                onNodeWithTag("explain-address").assertDoesNotExist()
                onNodeWithTag("explain-code").assertDoesNotExist()
                onNodeWithTag("explain-relogin").assertTextEquals("重新登录").performClick()
                assertEquals("授权已撤销，请重新登录。", reloginReason)
            }
        }
    }

    private fun ComposeUiTest.assertNoSable() {
        assertNoSableText()
        onNodeWithTag("explain-address").assertExists()
        onNodeWithTag("explain-code").assertExists()
    }

    private fun ComposeUiTest.assertNoSableText() =
        assertTrue(onAllNodesWithText("Sable", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "隐藏模式下不该出现 Sable 字样")

    private fun ComposeUiTest.pair(url: String, code: String) {
        onNodeWithTag("explain-address").performTextClearance()
        onNodeWithTag("explain-address").performTextInput(url)
        onNodeWithTag("explain-code").performTextInput(code)
        onNodeWithTag("explain-pair").performClick()
    }

    private fun settings(home: Path, sableLogin: Boolean = true) = DesktopConnectionSettings(home.toRealPath(), { null }, mac = false, property = {
        when (it) {
            "jpackage.app-path" -> "/synthetic/LearningCoach.exe"
            "coach.sableLogin" -> if (sableLogin) "true" else "false"
            else -> null
        }
    })

    private fun sableSettings(home: Path, friendUrl: String) = DesktopConnectionSettings(
        home.toRealPath(),
        { key -> if (key == "COACH_FRIEND_URL") friendUrl else null },
        mac = false,
        property = { if (it == "jpackage.app-path") "/synthetic/LearningCoach.exe" else null },
    )

    private fun sample(): Pair<TextbookLibrary, Textbook> {
        val root = Files.createTempDirectory("sable-entry-")
        val library = TextbookLibrary(root.resolve("library"))
        return library to library.importBook(syntheticTextbook(root.resolve("synthetic")))
    }

    /** 回环上的假 Mac：只答配对和握手，第 n 次配对发第 n 份凭据。 */
    private class PairingMac : AutoCloseable {
        val pairs = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String

        init {
            server.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val (status, body) = when (exchange.requestURI.path) {
                    "/v1/pair" -> 200 to """{"deviceId":"friend-pc","token":"${('a' + pairs.getAndIncrement()).toString().repeat(43)}"}"""
                    "/v1/hello" -> 200 to """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            check(server.address.port != 18791) { "never the production port" }
            url = "http://127.0.0.1:${server.address.port}"
        }

        override fun close() = server.stop(0)
    }

    /** 回环上的假 Sable + Coach 服务：只答 device code 登录、status 和 401 撤销。 */
    private class SableTestServer : AutoCloseable {
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = pool }
        val url: String = "http://127.0.0.1:${server.address.port}"
        val approved = AtomicBoolean(false)
        val revokedTokens = ConcurrentHashMap.newKeySet<String>()
        private val nextIndex = AtomicInteger(0)

        init {
            check(server.address.port != 18791) { "never the production port" }
            server.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                val token = auth.removePrefix("Bearer ").takeIf { auth.startsWith("Bearer ") }.orEmpty()
                val (status, body) = when (exchange.requestURI.path) {
                    "/api/device/code" -> {
                        approved.set(false)
                        200 to """{"device_code":"dev-1","user_code":"BCDF-GHJK","expires_in":120,"interval":1,"verification_uri":"$url/link","verification_uri_complete":"$url/link?code=BCDF-GHJK"}"""
                    }
                    "/link" -> {
                        approved.set(exchange.requestURI.query == "code=BCDF-GHJK")
                        200 to "{}"
                    }
                    "/api/device/token" -> {
                        if (approved.get()) 200 to """{"access_token":"synthetic-grant"}"""
                        else 400 to """{"error":"authorization_pending"}"""
                    }
                    "/v1/public/sable" -> {
                        if (token == "synthetic-grant") {
                            val n = nextIndex.getAndIncrement()
                            val tok = ('a' + (n % 20)).toString().repeat(43)
                            200 to """{"deviceId":"sable-pc-$n","token":"$tok","role":"friend"}"""
                        } else 401 to """{"error":"unauthorized"}"""
                    }
                    "/v1/public/status" -> {
                        if (token in revokedTokens) 401 to """{"error":"unauthorized"}"""
                        else 200 to """{"online":true}"""
                    }
                    else -> 404 to "{}"
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }
}
