package com.coach.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.coach.client.PairedCredentials
import com.coach.desktop.books.AndroidBookConnection
import com.coach.desktop.books.AndroidBookIdentity
import com.coach.desktop.books.BookExplainPanel
import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.ExplainResult
import com.coach.desktop.books.ExplainTrouble
import com.coach.desktop.books.Textbook
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

            compose.setContent {
                CoachTheme(darkTheme = false) {
                    BookExplainPanel(book, 1, ExplainConsent(library.directory), explainer)
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithText("用 Sable 账号登录").assertIsDisplayed()
            compose.onNodeWithText("没有 Sable 账号？用配对码").assertIsDisplayed()
            compose.onNodeWithTag("explain-connect-hint").assertTextContains("朋友服务", substring = true)
            compose.onNodeWithTag("explain-code").assertExists()
            compose.onNodeWithTag("explain-pair").assertExists()

            // Must not pre-fill or render the secret friend URL on screen.
            val addressNode = compose.onNodeWithTag("explain-address").fetchSemanticsNode()
            val editable = addressNode.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            assertEquals("", editable)
            assertTrue(compose.onAllNodesWithText(friend.url, substring = true).fetchSemanticsNodes().isEmpty())

            // Backup 6-digit pairing code works with blank address against built-in friend URL.
            compose.onNodeWithTag("explain-code").performTextInput("123456")
            compose.onNodeWithTag("explain-pair").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("已连上 Mac", substring = true).assertIsDisplayed()
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

            compose.setContent {
                val handler = object : UriHandler {
                    override fun openUri(uri: String) {
                        openedUris += uri
                        bgExecutor.submit {
                            http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                        }
                    }
                }
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    CoachTheme(darkTheme = false) {
                        BookExplainPanel(book, 1, ExplainConsent(library.directory), explainer)
                    }
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("用 Sable 账号登录").performClick()

            compose.waitUntil(25_000) { compose.onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("已连上 Mac", substring = true).assertIsDisplayed()
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

            // SharedPreferences must not leak the built-in friendUrl.
            val connPrefs = ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSharedPreferences("coach-book-connection", android.content.Context.MODE_PRIVATE).all
            assertFalse(connPrefs.values.joinToString().contains(friend.url))

            // Cold start simulation: brand new BookExplainer loads the persisted credential and verifies status=200.
            val coldExplainer = BookExplainer(library)
            runBlocking { coldExplainer.load() }
            assertNotNull(coldExplainer.identity)
            assertFalse(coldExplainer.revoked)
            assertEquals("", coldExplainer.defaultAddress)
            assertTrue(friend.statusCalls.get() >= 1)

            // Offline status (503 / closed port) must NOT drop valid pairing.
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

            // Request explanation for paragraph 1 and verify HTTP 200 + paragraph citation.
            compose.onAllNodesWithTag("explain-button")[0].performClick()
            compose.onNodeWithTag("explain-consent-allow").performClick()
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

            // First sign in cleanly via Sable and confirm loaded state is connected.
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

            // Server revokes the credential -> next explain gets 401 -> clears store and prompts re-login.
            friend.revokedTokens += firstCreds.token
            val consent = ExplainConsent(library.directory).also { it.allow(book.id) }

            compose.setContent {
                val handler = object : UriHandler {
                    override fun openUri(uri: String) {
                        bgExecutor.submit {
                            http.send(HttpRequest.newBuilder(URI(uri)).GET().build(), HttpResponse.BodyHandlers.discarding())
                        }
                    }
                }
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    CoachTheme(darkTheme = true) {
                        BookExplainPanel(book, 1, consent, firstExplainer)
                    }
                }
            }
            compose.waitUntil(15_000) { firstExplainer.loaded }
            compose.onAllNodesWithTag("explain-button")[0].performClick()

            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-revoked").fetchSemanticsNodes().isNotEmpty() }
            assertNull("Credential store must be cleared after 401", stores["paired-friend"]?.load())
            assertNull(firstExplainer.identity)
            assertTrue(firstExplainer.revoked)
            compose.onNodeWithTag("explain-revoked").assertTextEquals("授权已撤销，请重新登录。读书和做题照常。")
            compose.onNodeWithTag("explain-failed").assertTextEquals(ExplainTrouble.REVOKED_LOGIN)
            compose.onNodeWithText("用 Sable 账号登录").assertIsDisplayed()
            compose.onNodeWithTag("explain-code").assertExists()

            // A freshly created explainer (like TextbookReader opening after AnswerExplain 401) also sees revoked=true.
            val secondExplainer = BookExplainer(library)
            runBlocking { secondExplainer.load() }
            assertNull(secondExplainer.identity)
            assertTrue(secondExplainer.revoked)

            // Re-login via Sable clears revoked state and restores explanation.
            friend.nextFriendToken = "c".repeat(43)
            compose.onNodeWithText("用 Sable 账号登录").performClick()
            compose.waitUntil(25_000) { compose.onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
            assertFalse(firstExplainer.revoked)
            assertNotNull(stores["paired-friend"]?.load())

            compose.onAllNodesWithTag("explain-button")[0].performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }

            // Startup check sends X-Coach-Startup: 1 and clears store on 401.
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

            compose.waitUntil(25_000) { compose.onAllNodesWithText("选段提问").fetchSemanticsNodes().isNotEmpty() }
            val gateCreds = requireNotNull(friendStore.load())
            assertEquals("friend", gateCreds.role)

            val explainer = BookExplainer(library)
            runBlocking { explainer.load() }
            assertEquals(gateCreds.deviceId, explainer.identity?.credentials?.deviceId)
            assertEquals(gateCreds.token, explainer.identity?.credentials?.token)
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
