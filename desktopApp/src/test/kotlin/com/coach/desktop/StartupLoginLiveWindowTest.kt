package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.coach.desktop.books.BookPrompt
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.ExplainTrouble
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.syntheticTextbook
import com.sun.net.httpserver.HttpServer
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*

/**
 * TON-1782 winbox real-window acceptance test: runs a real native [ComposeWindow] against an isolated
 * loopback Sable + Coach stub and captures real screenshots for all 5 acceptance paths:
 * 1. Fresh install -> full-screen login screen (Sable primary + backup pairing link)
 * 2. Sign in with Sable test identity -> enters bookshelf
 * 3. Read to page 2, close window, reopen -> zero clicks back to page 2
 * 4. Revoke identity (401) -> returns to login screen with clear reason
 * 5. Offline (server stopped) with valid identity -> still opens and reads normally
 *
 * Opt in with `-Pcoach.startupLoginLiveWindow=true`. Never touches production ports or user data.
 */
@OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.runtime.tooling.ComposeToolingApi::class,
)
class StartupLoginLiveWindowTest {
    @Test fun fiveStartupLoginPathsInRealWindowsNativeWindow() {
        org.junit.Assume.assumeTrue(System.getProperty("coach.startupLoginLiveWindow") == "true")
        val root = Files.createTempDirectory("coach-ton1782-live-").toRealPath()
        val outDir = File("build/screens/ton1782-live").apply { mkdirs() }
        val home = Files.createDirectories(root.resolve("home"))
        val libDir = root.resolve("library")
        val fixture = syntheticTextbook(root.resolve("fixture"))
        val savedProps = listOf("user.home", "jpackage.app-path", "coach.windows.theme").associateWith { System.getProperty(it) }
        val bg = Executors.newSingleThreadExecutor()
        val http = HttpClient.newHttpClient()

        try {
            System.setProperty("user.home", home.toString())
            System.setProperty("jpackage.app-path", home.resolve("LearningCoach.exe").toString())
            System.setProperty("coach.windows.theme", "light")

            val server = LiveStubServer()
            val settings = DesktopConnectionSettings(
                home = home,
                environment = { if (it == "COACH_FRIEND_URL") server.url else null },
                mac = false,
                property = { if (it == "jpackage.app-path") home.resolve("LearningCoach.exe").toString() else null },
            )
            val uriHandler = object : UriHandler {
                override fun openUri(uri: String) {
                    bg.submit {
                        http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                    }
                }
            }

            fun openWindow(controller: TextbookController): ComposeWindow = edt {
                ComposeWindow().apply {
                    title = "LearningCoach"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 40, screen.y + 40, minOf(1280, screen.width - 80), minOf(840, screen.height - 80))
                    setContent {
                        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                            WindowsTextbookGate(settings, sableIssuer = server.url, darkTheme = false) {
                                WindowsTextbookHost(controller) { fixture }
                            }
                        }
                    }
                    isVisible = true
                    toFront()
                }
            }

            // ---- Path 1 & 2: Fresh install -> login screen -> Sable login -> bookshelf -> open book & turn to page 2 ----
            val c1 = TextbookController(TextbookLibrary(libDir))
            val w1 = openWindow(c1)
            try {
                waitFor("fresh login screen") { node(w1, "textbook-login-screen") != null && node(w1, "login-pairing-toggle") != null }
                assertNull(node(w1, "login-pairing-form"))
                Thread.sleep(600)
                shot(w1, File(outDir, "01-fresh-login.png"))

                clickText(w1, "用 Sable 账号登录")
                waitFor("shelf entered") { node(w1, "windows-host") != null && node(w1, "upload-textbook") != null }
                Thread.sleep(600)
                shot(w1, File(outDir, "02-after-sable-login-shelf.png"))

                // Import synthetic book, start reading, turn to page 2.
                click(w1, "upload-textbook")
                waitFor("imported prompt") { c1.state.value.prompt == BookPrompt.IMPORTED }
                click(w1, "read-only")
                waitFor("after reading prompt") { c1.state.value.screen == BookScreen.READER && c1.state.value.prompt == BookPrompt.AFTER_READING }
                click(w1, "read-only")
                waitFor("reader ready") { c1.state.value.prompt == null }
                click(w1, "next-page")
                waitFor("page 2") { c1.state.value.page == 2 && !c1.state.value.busy }
                Thread.sleep(400)
            } finally {
                edt { w1.dispose() }
            }

            // ---- Path 3: Close & reopen -> zero clicks straight back to reader page 2 ----
            val c2 = TextbookController(TextbookLibrary(libDir))
            val w2 = openWindow(c2)
            try {
                waitFor("reopened reader page 2") {
                    node(w2, "textbook-reader") != null && c2.state.value.page == 2 && !c2.state.value.busy
                }
                assertNull(node(w2, "textbook-login-screen"))
                Thread.sleep(600)
                shot(w2, File(outDir, "03-reopen-resumed-page2.png"))
            } finally {
                edt { w2.dispose() }
            }

            // ---- Path 4: Revoke identity (401) -> reopen returns to login screen with reason -> sign back in ----
            val savedIdentity = checkNotNull(DesktopPairing(settings).load())
            server.revokedTokens += savedIdentity.credentials.token
            val c3 = TextbookController(TextbookLibrary(libDir))
            val w3 = openWindow(c3)
            try {
                waitFor("revoked back to login") { node(w3, "login-revoked-reason") != null }
                Thread.sleep(600)
                shot(w3, File(outDir, "04-revoked-back-to-login.png"))

                // Sign back in with fresh Sable token so Path 5 has a valid credential before going offline.
                clickText(w3, "用 Sable 账号登录")
                waitFor("re-entered after relogin") { node(w3, "textbook-reader") != null && c3.state.value.page == 2 }
                Thread.sleep(300)
            } finally {
                edt { w3.dispose() }
            }

            // ---- Path 5: Offline (stop server) -> reopen still enters reader page 2 and reads normally ----
            server.close()
            val c4 = TextbookController(TextbookLibrary(libDir))
            val w4 = openWindow(c4)
            try {
                waitFor("offline reader page 2") {
                    node(w4, "textbook-reader") != null && c4.state.value.page == 2 && !c4.state.value.busy
                }
                assertNull(node(w4, "textbook-login-screen"))
                val openBook = checkNotNull(c4.state.value.book)
                ExplainConsent(libDir).allow(openBook.id)
                // Open explain panel and request section explain offline: no old login form appears, only unreachable notice.
                click(w4, "book-explain-toggle")
                waitFor("explain panel open offline") { node(w4, "book-explain") != null }
                assertNull(node(w4, "explain-pairing"))
                click(w4, "explain-section-trigger")
                waitFor("offline explain unreachable") { node(w4, "section-guide-failed") != null }
                Thread.sleep(600)
                shot(w4, File(outDir, "05-offline-still-reads.png"))
                assertTrue(ExplainTrouble.UNREACHABLE.contains("读书和做题照常"))
            } finally {
                edt { w4.dispose() }
            }
            println("TON1782_LIVE_WINDOW_PASS screenshots=${outDir.absolutePath}")
        } finally {
            bg.shutdownNow()
            for ((k, v) in savedProps) if (v == null) System.clearProperty(k) else System.setProperty(k, v)
            root.toFile().deleteRecursively()
        }
    }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return window.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun node(window: ComposeWindow, tag: String): SemanticsNode? = edt {
        nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor("click $tag") { node(window, tag) != null }
        edt {
            val target = checkNotNull(node(window, tag))
            assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke(), tag)
        }
        Thread.sleep(250)
    }

    private fun clickText(window: ComposeWindow, label: String) {
        waitFor("clickText $label") {
            edt {
                nodes(window).any { n ->
                    n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                }
            }
        }
        edt {
            val textNode = nodes(window).first { n ->
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            }
            val clickable = generateSequence(textNode) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(clickable.config[SemanticsActions.OnClick].action!!.invoke(), label)
        }
        Thread.sleep(250)
    }

    private fun waitFor(name: String, timeoutMs: Long = 25_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(120)
        }
        error("Timed out waiting for $name")
    }

    private fun shot(window: ComposeWindow, target: File) {
        edt {
            window.renderImmediately()
            VerifyHooks.capture(window, target)
        }
        assertTrue(target.isFile && target.length() > 1000, "Failed to capture ${target.absolutePath}")
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var out: Result<T>? = null
        SwingUtilities.invokeAndWait { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    private class LiveStubServer : AutoCloseable {
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = pool }
        val url: String = "http://127.0.0.1:${server.address.port}"
        val approved = AtomicBoolean(false)
        val revokedTokens = ConcurrentHashMap.newKeySet<String>()
        private val counter = AtomicInteger(0)

        init {
            check(server.address.port != 18791) { "never production port" }
            server.createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                val token = auth.removePrefix("Bearer ").takeIf { auth.startsWith("Bearer ") }.orEmpty()
                val (status, body) = when (exchange.requestURI.path) {
                    "/api/device/code" -> {
                        approved.set(false)
                        200 to """{"device_code":"live-dev","user_code":"BCDF-GHJK","expires_in":120,"interval":1,"verification_uri":"$url/link","verification_uri_complete":"$url/link?code=BCDF-GHJK"}"""
                    }
                    "/link" -> {
                        approved.set(exchange.requestURI.query == "code=BCDF-GHJK")
                        200 to "{}"
                    }
                    "/api/device/token" -> {
                        if (approved.get()) 200 to """{"access_token":"live-sable-grant"}"""
                        else 400 to """{"error":"authorization_pending"}"""
                    }
                    "/v1/public/sable" -> {
                        if (token == "live-sable-grant") {
                            val idx = counter.getAndIncrement()
                            val tok = ('a' + (idx % 20)).toString().repeat(43)
                            200 to """{"deviceId":"live-pc-$idx","token":"$tok","role":"friend"}"""
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
