package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.coach.client.PairedCredentials
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.AndroidBookConnection
import com.coach.desktop.books.AndroidBookIdentity
import com.coach.desktop.books.BookExplainPanel
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.ExplainResult
import com.coach.desktop.books.ExplainTrouble
import com.coach.desktop.books.LocalBookRelogin
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidSableLoginTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = ConcurrentHashMap<String, InMemoryStore>()
    private val bgExecutor = Executors.newSingleThreadExecutor()

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        AndroidBookConnection.resetForTest()
        stores.clear()
    }

    @After fun tearDown() {
        AndroidBookConnection.resetForTest()
        stores.clear()
        bgExecutor.shutdownNow()
    }

    @Test fun firstOpenShowsSableLoginAndBackupCodeWithoutLeakingServerUrl() {
        FakeFriendBackend().use { friend ->
            val (library, book) = sampleLibrary()
            bindConnection(friend.url, friend.issuerUrl)
            val explainer = BookExplainer(library)
            var reloginClicked = false

            compose.setContent {
                CompositionLocalProvider(LocalBookRelogin provides { reloginClicked = true }) {
                    CoachTheme(darkTheme = false) {
                        BookExplainPanel(book, 1, ExplainConsent(library.directory), explainer)
                    }
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-connect-hint").fetchSemanticsNodes().isNotEmpty() }

            // 2.0 removes inline pairing form from reader; unauthenticated panel directs user to full-screen login.
            compose.onNodeWithTag("explain-pairing").assertDoesNotExist()
            compose.onNodeWithTag("explain-code").assertDoesNotExist()
            compose.onNodeWithTag("explain-relogin").assertIsDisplayed().performClick()
            assertTrue(reloginClicked)
            assertTrue(compose.onAllNodesWithText(friend.url, substring = true).fetchSemanticsNodes().isEmpty())

            // Backup 6-digit pairing code still works via BookExplainer.pair against built-in friend URL.
            runBlocking { assertTrue(explainer.pair("", "123456")) }
            assertEquals(1, friend.pairs.get())
        }
    }

    @Test fun sableDeviceCodeLoginPersistsAcrossColdStartAndExplainsWithParagraphCitation() {
        FakeFriendBackend().use { friend ->
            val (library, book) = sampleLibrary()
            bindConnection(friend.url, friend.issuerUrl)
            val explainer = BookExplainer(library)
            val openedUris = mutableListOf<String>()
            val http = HttpClient.newHttpClient()

            runBlocking {
                explainer.sable { uri, _ ->
                    openedUris += uri
                    http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                }
                explainer.load()
            }
            assertEquals(1, openedUris.size)
            assertEquals("${friend.issuerUrl}/link?code=BCDF-GHJK", openedUris.single())
            assertTrue("slow_down should widen poll interval", friend.tokenPolls.get() >= 3)

            val savedIdentity = requireNotNull(explainer.identity)
            assertEquals("friend", savedIdentity.credentials.role)
            assertFalse(savedIdentity.toString().contains(friend.url))
            assertFalse(savedIdentity.toString().contains(savedIdentity.credentials.token))

            val friendStore = requireNotNull(stores["paired-friend"])
            val stored = requireNotNull(friendStore.load())
            assertEquals("friend", stored.role)
            assertFalse(stored.token.contains(friend.upstreamGrant))

            val connPrefs = ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSharedPreferences("coach-book-connection", android.content.Context.MODE_PRIVATE).all
            assertFalse(connPrefs.values.joinToString().contains(friend.url))

            val coldExplainer = BookExplainer(library)
            runBlocking { coldExplainer.load() }
            assertNotNull(coldExplainer.identity)
            assertFalse(coldExplainer.revoked)
            assertEquals("", coldExplainer.defaultAddress)
            assertTrue(friend.statusCalls.get() >= 1)

            friend.status503.set(true)
            assertTrue(runBlocking { AndroidBookConnection.stillAuthorized(savedIdentity, startup = true) })
            friend.status503.set(false)
            val deadPort = ServerSocket(0).use { it.localPort }
            assertTrue(runBlocking {
                AndroidBookConnection.stillAuthorized(
                    AndroidBookIdentity("http://127.0.0.1:$deadPort", stored),
                    startup = true,
                )
            })

            // 2.0 removes per-book consent dialog: clicking explain trigger goes straight to explanation.
            compose.setContent {
                CoachTheme(darkTheme = false) {
                    BookExplainPanel(book, 1, ExplainConsent(library.directory), explainer)
                }
            }
            compose.onNodeWithTag("explain-section-trigger").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("explain-text").assertTextContains("Plants convert light energy", substring = true)
            compose.onNodeWithTag("explain-citation").assertTextContains("第 1 段", substring = true)
            assertEquals(1, friend.explains.get())
        }
    }

    @Test fun revocation401ClearsCredentialPromptsReloginAndPropagatesAcrossExplainers() {
        FakeFriendBackend().use { friend ->
            val (library, book) = sampleLibrary()
            bindConnection(friend.url, friend.issuerUrl)
            val http = HttpClient.newHttpClient()

            val firstExplainer = BookExplainer(library)
            runBlocking {
                firstExplainer.sable { uri, _ ->
                    http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                }
                firstExplainer.load()
            }
            val firstCreds = requireNotNull(stores["paired-friend"]?.load())
            assertNotNull(firstExplainer.identity)
            assertTrue(firstExplainer.loaded)

            friend.revokedTokens += firstCreds.token
            val consent = ExplainConsent(library.directory)
            var reloginReason: String? = null

            compose.setContent {
                CompositionLocalProvider(LocalBookRelogin provides { reloginReason = it }) {
                    CoachTheme(darkTheme = true) {
                        BookExplainPanel(book, 1, consent, firstExplainer)
                    }
                }
            }
            compose.waitUntil(15_000) { firstExplainer.loaded }
            compose.onNodeWithTag("explain-section-trigger").performClick()

            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-revoked").fetchSemanticsNodes().isNotEmpty() }
            assertNull("Credential store must be cleared after 401", stores["paired-friend"]?.load())
            assertNull(firstExplainer.identity)
            assertTrue(firstExplainer.revoked)
            compose.onNodeWithTag("explain-revoked").assertTextContains("授权已撤销，请重新登录", substring = true)
            compose.onNodeWithTag("section-guide-failed").assertTextEquals(ExplainTrouble.REVOKED_LOGIN)
            compose.onNodeWithTag("explain-relogin").assertIsDisplayed().performClick()
            assertNotNull(reloginReason)

            val secondExplainer = BookExplainer(library)
            runBlocking { secondExplainer.load() }
            assertNull(secondExplainer.identity)
            assertTrue(secondExplainer.revoked)

            // Re-login via Sable clears revoked state and restores explanation.
            friend.nextFriendToken = "c".repeat(43)
            runBlocking {
                firstExplainer.sable { uri, _ ->
                    http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                }
            }
            assertFalse(firstExplainer.revoked)
            assertNotNull(stores["paired-friend"]?.load())

            compose.onNodeWithTag("explain-section-trigger").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }

            val newToken = requireNotNull(stores["paired-friend"]?.load()).token
            friend.revokedTokens += newToken
            val savedIdentity = requireNotNull(firstExplainer.identity)
            assertFalse(runBlocking { AndroidBookConnection.stillAuthorized(savedIdentity, startup = true) })
            assertTrue(friend.sawStartupHeader.get())
            assertNull(stores["paired-friend"]?.load())
        }
    }

    @Test fun lateRevocation403DoesNotInvalidateNewLogin() {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val explainHit = AtomicBoolean(false)
        val pairs = AtomicInteger(0)
        val oldToken = "x".repeat(43)
        val newToken = "y".repeat(43)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val (status, body) = when (exchange.requestURI.path) {
                "/v1/pair" -> {
                    val idx = pairs.incrementAndGet()
                    val tok = if (idx == 1) oldToken else newToken
                    200 to """{"deviceId":"dev-$idx","token":"$tok","role":"friend"}"""
                }
                "/v1/public/status" -> {
                    if (auth == "Bearer $oldToken" && explainHit.get()) {
                        checking.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        401 to "{}"
                    } else {
                        200 to "{}"
                    }
                }
                "/v1/library/explain" -> {
                    explainHit.set(true)
                    403 to "{}"
                }
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val (library, book) = sampleLibrary()
            ExplainConsent(library.directory).allow(book.id)
            val url = "http://127.0.0.1:${server.address.port}"
            bindConnection(url, url)

            val explainer = BookExplainer(library)
            runBlocking { assertTrue(explainer.pair("", "111111")) }
            val block = com.coach.desktop.books.StudyCoachCourse.blocks(book.id, book.pages).first { !it.heading }
            runBlocking {
                val job = async(kotlinx.coroutines.Dispatchers.IO) { explainer.explain(block, listOf(block)) }
                assertTrue(checking.await(10, TimeUnit.SECONDS))
                assertTrue(explainer.pair("", "222222"))
                release.countDown()
                val result = job.await()
                assertTrue(result is ExplainResult.Refused)
                assertEquals("dev-2", explainer.identity?.credentials?.deviceId)
                assertNotNull(stores["paired-friend"]?.load())
                assertFalse(explainer.revoked)
            }
        } finally {
            release.countDown()
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test fun pairingGateShowsSableLoginAndSharesCredentialWithBookExplainer() {
        FakeFriendBackend().use { friend ->
            val (library, _) = sampleLibrary()
            bindConnection(friend.url, friend.issuerUrl)
            val ownerStore = InMemoryStore()
            val friendStore = stores.getOrPut("paired-friend") { InMemoryStore() }
            val http = HttpClient.newHttpClient()

            compose.setContent {
                val handler = object : UriHandler {
                    override fun openUri(uri: String) {
                        bgExecutor.submit {
                            http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                        }
                    }
                }
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    CoachTheme(darkTheme = false) {
                        PairingGate(
                            store = ownerStore,
                            serverUrl = "http://127.0.0.1:18799",
                            friendUrl = friend.url,
                            sableIssuer = friend.issuerUrl,
                            friendStore = friendStore,
                        ) {
                            Text("owner-content")
                        }
                    }
                }
            }

            compose.onNodeWithText("用 Sable 账号登录").assertIsDisplayed()
            compose.onNodeWithText("没有 Sable 账号？用配对码").assertIsDisplayed()
            compose.onNodeWithText("用 Sable 账号登录").performClick()

            compose.waitUntil(25_000) { compose.onAllNodesWithText("owner-content").fetchSemanticsNodes().isNotEmpty() }
            val gateCreds = requireNotNull(friendStore.load())
            assertEquals("friend", gateCreds.role)

            val explainer = BookExplainer(library)
            runBlocking { explainer.load() }
            assertEquals(gateCreds.deviceId, explainer.identity?.credentials?.deviceId)
            assertEquals(gateCreds.token, explainer.identity?.credentials?.token)
        }
    }

    @Test fun androidTextbookGateRequiresLoginFirstThenEntersShelfResumesAndHandlesRevocationAndOffline() {
        FakeFriendBackend().use { friend ->
            val (library, book) = sampleLibrary()
            bindConnection(friend.url, friend.issuerUrl)
            val http = HttpClient.newHttpClient()
            var generation by mutableStateOf(0)
            var activeController by mutableStateOf(TextbookController(library))

            compose.setContent {
                val handler = object : UriHandler {
                    override fun openUri(uri: String) {
                        bgExecutor.submit {
                            http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                        }
                    }
                }
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    key(generation) {
                        AndroidTextbookGate(darkTheme = false) {
                            WindowsTextbookHost(activeController) { null }
                        }
                    }
                }
            }

            // 1. Fresh launch shows full-screen login page with Sable primary and backup code tucked behind link.
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("用 Sable 账号登录").assertIsDisplayed()
            compose.onNodeWithTag("login-pairing-toggle").assertIsDisplayed()
            compose.onNodeWithTag("login-pairing-form").assertDoesNotExist()
            assertTrue(compose.onAllNodesWithText("朋友服务", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText(friend.url, substring = true).fetchSemanticsNodes().isEmpty())

            // 2. Login via Sable enters bookshelf.
            compose.onNodeWithText("用 Sable 账号登录").performClick()
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("windows-host").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { activeController.state.value.book?.id == book.id && !activeController.state.value.busy }

            // Open reader and record open session, then simulate cold reopen -> zero clicks straight back to reader!
            runBlocking { activeController.read(); activeController.dismissPrompt() }
            compose.waitUntil(15_000) { activeController.state.value.screen == BookScreen.READER }
            compose.runOnIdle {
                activeController = TextbookController(TextbookLibrary(library.directory))
                generation++
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty() &&
                    activeController.state.value.screen == BookScreen.READER
            }
            compose.onNodeWithTag("textbook-login-screen").assertDoesNotExist()

            // 3. Revocation (401) returns to full-screen login screen with clear reason.
            val storedToken = requireNotNull(stores["paired-friend"]?.load()).token
            friend.revokedTokens += storedToken
            compose.runOnIdle { generation++ }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("login-revoked-reason").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("login-revoked-reason").assertTextContains("授权已撤销", substring = true)
            assertNull(stores["paired-friend"]?.load())

            // 4. Offline (503) with a saved credential still opens reader without blocking on login.
            friend.nextFriendToken = "d".repeat(43)
            compose.onNodeWithText("用 Sable 账号登录").performClick()
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty() }
            friend.status503.set(true)
            compose.runOnIdle {
                activeController = TextbookController(TextbookLibrary(library.directory))
                generation++
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("textbook-login-screen").assertDoesNotExist()
        }
    }

    @Test fun androidGatedExplainPanelHidesOldFormAndOffersOnlyReloginOnRevocation() {
        FakeFriendBackend().use { friend ->
            val (library, book) = sampleLibrary()
            bindConnection(friend.url, friend.issuerUrl)
            val http = HttpClient.newHttpClient()
            val explainer = BookExplainer(library)
            runBlocking {
                explainer.sable { uri, _ ->
                    http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                }
                explainer.load()
            }
            var reloginReason by mutableStateOf<String?>(null)

            compose.setContent {
                CoachTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalBookRelogin provides { reason -> reloginReason = reason }) {
                        BookExplainPanel(book, 1, ExplainConsent(library.directory), explainer)
                    }
                }
            }
            compose.waitUntil(15_000) { explainer.loaded }
            compose.onNodeWithTag("explain-pairing").assertDoesNotExist()
            compose.onNodeWithTag("explain-code").assertDoesNotExist()
            compose.onNodeWithTag("explain-relogin").assertDoesNotExist()

            compose.runOnIdle { explainer.markRevoked() }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-relogin").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("explain-pairing").assertDoesNotExist()
            compose.onNodeWithTag("explain-code").assertDoesNotExist()
            compose.onNodeWithTag("explain-relogin").performClick()
            assertEquals("授权已撤销，请重新登录。", reloginReason)
        }
    }

    private fun bindConnection(friendUrl: String, issuerUrl: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("coach-book-connection", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        AndroidBookConnection.bind(
            context = context,
            url = "http://127.0.0.1:18799",
            store = stores.getOrPut("paired-device") { InMemoryStore() },
            friendUrl = friendUrl,
            sableIssuer = issuerUrl,
            credentialFactory = { _, _, name -> stores.getOrPut(name) { InMemoryStore() } },
        )
    }

    private fun sampleLibrary(): Pair<TextbookLibrary, Textbook> {
        val root = Files.createTempDirectory("android-sable-test-")
        val library = TextbookLibrary(root.resolve("library"))
        val txt = root.resolve("sample-biology.txt")
        Files.writeString(
            txt,
            "Chapter 1 Light\n\n" +
                "Photosynthesis is the process by which plants convert light energy into chemical energy.\n\n" +
                "Chlorophyll molecules in plant cells absorb sunlight for this reaction.",
        )
        val book = library.importBook(txt)
        return library to book
    }

    private class InMemoryStore : CoachCredentialStore {
        @Volatile private var value: PairedCredentials? = null
        @Volatile private var revokedFlag = false
        override fun save(credentials: PairedCredentials) {
            value = credentials
            revokedFlag = false
        }
        override fun load(): PairedCredentials? = value
        override fun bookPairing(): Pair<PairedCredentials?, Boolean> = value to revokedFlag
        override fun revokeIfCurrent(credentials: PairedCredentials) {
            val saved = value
            if (saved != null && saved.deviceId == credentials.deviceId && saved.token == credentials.token) {
                value = null
                revokedFlag = true
            }
        }
        override fun clear() {
            value = null
            revokedFlag = false
        }
    }

    private class FakeFriendBackend : AutoCloseable {
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
        }
        val url: String = "http://127.0.0.1:${server.address.port}"
        val issuerUrl: String = url
        val upstreamGrant = "synthetic-sable-access-token"
        val approved = AtomicBoolean(false)
        val tokenPolls = AtomicInteger(0)
        val pairs = AtomicInteger(0)
        val explains = AtomicInteger(0)
        val statusCalls = AtomicInteger(0)
        val status503 = AtomicBoolean(false)
        val sawStartupHeader = AtomicBoolean(false)
        val revokedTokens = ConcurrentHashMap.newKeySet<String>()
        @Volatile var nextFriendToken = "a".repeat(43)

        init {
            server.createContext("/") { exchange ->
                val rawBody = exchange.requestBody.use { it.readBytes() }.decodeToString()
                val path = exchange.requestURI.path
                val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
                val token = auth.removePrefix("Bearer ").takeIf { auth.startsWith("Bearer ") }.orEmpty()

                val (status, json) = when (path) {
                    "/api/device/code" -> {
                        approved.set(false)
                        tokenPolls.set(0)
                        200 to """{"device_code":"dev-code-1","user_code":"BCDF-GHJK","expires_in":120,"interval":1,"verification_uri":"$issuerUrl/link","verification_uri_complete":"$issuerUrl/link?code=BCDF-GHJK"}"""
                    }
                    "/link" -> {
                        approved.set(exchange.requestURI.query == "code=BCDF-GHJK")
                        200 to "{}"
                    }
                    "/api/device/token" -> {
                        val n = tokenPolls.incrementAndGet()
                        when {
                            !approved.get() -> 400 to """{"error":"authorization_pending"}"""
                            n == 1 -> 400 to """{"error":"authorization_pending"}"""
                            n == 2 -> 400 to """{"error":"slow_down"}"""
                            else -> 200 to """{"access_token":"$upstreamGrant"}"""
                        }
                    }
                    "/v1/public/sable" -> {
                        if (token == upstreamGrant) {
                            val assigned = nextFriendToken
                            200 to """{"deviceId":"friend-android-${assigned.take(1)}","token":"$assigned","role":"friend"}"""
                        } else 401 to """{"error":"unauthorized"}"""
                    }
                    "/v1/pair" -> {
                        pairs.incrementAndGet()
                        200 to """{"deviceId":"friend-code-1","token":"${"b".repeat(43)}","role":"friend"}"""
                    }
                    "/v1/public/status" -> {
                        statusCalls.incrementAndGet()
                        if (exchange.requestHeaders.getFirst("X-Coach-Startup") == "1") sawStartupHeader.set(true)
                        when {
                            token in revokedTokens -> 401 to """{"error":"unauthorized"}"""
                            status503.get() -> 503 to """{"error":"unavailable"}"""
                            else -> 200 to """{"online":true,"todayCalls":0}"""
                        }
                    }
                    "/v1/library/explain" -> {
                        explains.incrementAndGet()
                        if (token in revokedTokens) {
                            401 to """{"error":"unauthorized"}"""
                        } else {
                            val payload = Json.parseToJsonElement(rawBody).jsonObject
                            val firstBlock = payload.getValue("blocks").jsonArray.first().jsonObject
                            val id = firstBlock.getValue("id").jsonPrimitive.content
                            val page = Regex(":p(\\d+):").find(id)!!.groupValues[1]
                            200 to """{"text":"Plants convert light energy into chemical energy.【S1】","sources":[{"docId":"$id","pdfPage":$page,"anchor":"S1"}],"elapsedMs":12}"""
                        }
                    }
                    else -> 404 to "{}"
                }

                val bytes = json.toByteArray()
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
