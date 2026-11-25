package com.coach.desktop.books

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * 朋友那台电脑连李政的 Mac 讲解时，没讲成的几种情况（TON-1687）：
 * 连不上、服务暂时不可用、连接被停用。教材是自造的英文短文。
 */
@OptIn(ExperimentalTestApi::class)
class ExplainTroubleTest {
    @Test fun lateRevocationDoesNotInvalidateTheNewPairing() = runBlocking {
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val status = if (exchange.requestURI.path == "/v1/public/status") {
                checking.countDown(); check(release.await(10, TimeUnit.SECONDS)); 401
            } else 403
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val mac = TestMac("http://127.0.0.1:${server.address.port}")
            mac.load()
            val request = async(Dispatchers.IO) { mac.explain(block, listOf(block)) }
            assertTrue(checking.await(10, TimeUnit.SECONDS))
            val replacement = DesktopIdentity("http://127.0.0.1:${server.address.port}", PairedCredentials("new-device", "new-token", "friend"))
            mac.switchTo(replacement)
            release.countDown()
            assertIs<ExplainResult.Refused>(request.await())
            assertSame(replacement, mac.identity)
            assertFalse(mac.revoked)
        } finally { release.countDown(); server.stop(0) }
    }

    @Composable
    private fun WithLogin(explainer: BookExplainer, address: String, content: @Composable () -> Unit) {
        var reason by remember { mutableStateOf<String?>(null) }
        if (reason != null) {
            TextbookLoginScreen(explainer.sableLogin, address, false, reason,
                onSableLogin = { error("This test uses the local pairing server") },
                onPair = { url, code -> explainer.pair(url, code).also { if (it) reason = null } })
        } else CompositionLocalProvider(LocalBookRelogin provides { reason = it ?: "请重新登录。" }) {
            content()
        }
    }

    @Test fun forbiddenExplanationChecksTheSameIdentityWithoutResendingThePassage() {
        for (status in listOf(401, 200, 0)) {
            val (library, book, chosen) = sample()
            val savedPage = book.readingPage
            val explains = AtomicInteger()
            val checks = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val bytes = exchange.requestBody.use { it.readBytes() }
                assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
                assertEquals("test-device", exchange.requestHeaders.getFirst("X-Coach-Device"))
                when (exchange.requestURI.path) {
                    "/v1/library/explain" -> {
                        explains.incrementAndGet()
                        val body = """{"error":"这段原文没有访问权限。"}""".toByteArray()
                        exchange.sendResponseHeaders(403, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    "/v1/public/status" -> {
                        checks.incrementAndGet()
                        assertEquals("GET", exchange.requestMethod)
                        assertEquals(0, bytes.size)
                        if (status == 0) exchange.close() else {
                            val body = "{}".toByteArray()
                            exchange.sendResponseHeaders(status, body.size.toLong())
                            exchange.responseBody.use { it.write(body) }
                        }
                    }
                    else -> error("unexpected request")
                }
            }
            server.start()
            try {
                val mac = TestMac("http://127.0.0.1:${server.address.port}")
                val consent = ExplainConsent(library.directory)
                runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                    setContent { CoachTheme(darkTheme = false) {
                        WithLogin(mac, "") { BookExplainPanel(book, chosen.page, consent, mac, selection = chosen) }
                    } }
                    waitUntil(timeoutMillis = 20_000) { mac.loaded }
                    assertTrue(consent.allowed(book.id))
                    onNodeWithTag("explain-button").performClick()
                    onNodeWithTag("explain-consent-allow").assertDoesNotExist()
                    waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("explain-failed").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(1, explains.get())
                    assertTrue(checks.get() >= 1)
                    if (status == 401) {
                        onNodeWithTag("explain-failed").assertTextEquals(ExplainTrouble.REVOKED)
                        onNodeWithTag("explain-repair").performScrollTo().performClick()
                        onNodeWithTag("login-pairing-form").assertExists()
                        onNodeWithTag("login-address").assertExists()
                        onNodeWithTag("login-code").assertExists()
                        assertNull(mac.identity)
                    } else {
                        onNodeWithTag("explain-failed").assertTextEquals("这段原文没有访问权限。")
                        onNodeWithTag("explain-retry").assertExists()
                        onNodeWithTag("explain-repair").assertDoesNotExist()
                        assertNotNull(mac.identity)
                    }
                    assertEquals(savedPage, library.book(book.id).readingPage)
                    assertTrue(library.catalog().any { it.id == book.id })
                    assertEquals(1, explains.get(), "状态查询不能重发原文")
                }
            } finally { server.stop(0) }
        }
    }
    @Test fun eachReplyKindGetsItsOwnNextStep() {
        assertEquals(ExplainTrouble.Unreachable, ExplainTrouble.of(null, null))
        assertEquals(ExplainTrouble.Revoked, ExplainTrouble.of(401, null))
        assertEquals(ExplainTrouble.Revoked, ExplainTrouble.of(401, "daily_quota"))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(403, "unavailable"))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(429, null))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(503, "unavailable"))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(500, null))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(408, null))
        assertEquals(ExplainTrouble.Other, ExplainTrouble.of(400, "source_mismatch"))
        assertFalse(ExplainTrouble.Revoked.retry)
        assertTrue(ExplainTrouble.Unreachable.retry && ExplainTrouble.Busy.retry && ExplainTrouble.Other.retry)
        // 连不上和停用用固定的话，不管服务端或底层说了什么。
        assertEquals(ExplainTrouble.UNREACHABLE, ExplainTrouble.message(ExplainTrouble.Unreachable, "java.net.ConnectException: refused"))
        assertEquals(ExplainTrouble.REVOKED, ExplainTrouble.message(ExplainTrouble.Revoked, "HTTP 401"))
    }

    @Test fun serverSentenceReachesThePersonWithoutCodeOrStatus() = runBlocking {
        val quota = "今天的讲解额度用完了，明天再来。"
        val busy = "讲解这会儿排满了，过一会儿再试。"
        val cases = listOf(
            Reply(429, """{"error":"$quota","code":"daily_quota"}""") to (ExplainTrouble.Busy to BUSY),
            Reply(403, """{"error":"这个月的讲解额度用完了。","code":"monthly_quota"}""") to (ExplainTrouble.Busy to BUSY),
            Reply(429, """{"error":"$quota"}""") to (ExplainTrouble.Busy to BUSY),
            Reply(503, """{"error":"$busy","code":"unavailable"}""") to (ExplainTrouble.Busy to busy),
            // Funnel 或代理回的 HTML 错误页：不是 Mac 说的话，按连不上处理。
            Reply(502, "<html><body>502 Bad Gateway</body></html>", "text/html") to (ExplainTrouble.Unreachable to ExplainTrouble.UNREACHABLE),
            Reply(401, """{"error":"unauthorized","code":"revoked"}""") to (ExplainTrouble.Revoked to ExplainTrouble.REVOKED),
        )
        for ((reply, expected) in cases) withMac(reply) { url ->
            val mac = TestMac(url)
            mac.load()
            val refused = assertIs<ExplainResult.Refused>(mac.explain(block, listOf(block)))
            assertEquals(expected.first, refused.trouble, "HTTP ${reply.status}")
            assertEquals(expected.second, refused.message)
            for (leak in listOf("${reply.status}", "quota", "unavailable", "revoked", "Exception", "HTTP", "html"))
                assertFalse(leak in refused.message, "给人看的话里不该有「$leak」：${refused.message}")
            if (expected.first == ExplainTrouble.Revoked) {
                assertNull(mac.identity, "停用后讲解面板回到配对")
                assertTrue(mac.revoked)
            } else assertNotNull(mac.identity)
        }
    }

    @Test fun macThatIsAsleepOrGoneIsUnreachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val mac = TestMac("http://127.0.0.1:$port")
        mac.load()
        val refused = assertIs<ExplainResult.Refused>(mac.explain(block, listOf(block)))
        assertEquals(ExplainTrouble.Unreachable, refused.trouble)
        assertEquals(ExplainTrouble.UNREACHABLE, refused.message)
        assertNotNull(mac.identity, "连不上不等于停用，配对留着，Mac 回来就能再试")
        Unit
    }

    @Test fun pairedQuestionKeepsTheServerSentenceAndItsKind() = runBlocking {
        val (library, book, chosen) = sample()
        val support = PairedBookLearningSupport(library) { _, _ -> ExplainResult.Refused(BUSY, ExplainTrouble.Busy) }
        support.allow(book.id)
        val reply = assertIs<BookLearningReply.Unavailable>(support.ask(BookPassageQuestion(BookPassage.from(book, chosen), "Why light?")))
        assertEquals(BUSY, reply.message)
        assertEquals(ExplainTrouble.Busy, reply.trouble)
    }

    @Test fun retryAfterTheMacIsBackKeepsPassageAndQuestion() {
        val (library, book, chosen) = sample()
        val replies = ArrayDeque(listOf<ExplainResult>(
            ExplainResult.Refused(ExplainTrouble.UNREACHABLE, ExplainTrouble.Unreachable),
            ExplainResult.Done(Explanation("Light supplies energy for the plant.【S1】", listOf(Citation("S1", chosen.id, chosen.page)), 1)),
        ))
        var calls = 0
        val support = PairedBookLearningSupport(library) { sent, question ->
            calls++
            assertEquals(chosen, sent)
            assertEquals("Why does the plant need light?", question)
            replies.removeFirst()
        }
        support.allow(book.id)
        runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
            setContent { CoachTheme(darkTheme = false) { BookPassageAsk(book, book.pages.first(), support) {} } }
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why does the plant need light?")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("explain-trouble").assertTextEquals(ExplainTrouble.UNREACHABLE)
            onNodeWithTag("book-selected-passage").assertTextEquals(chosen.text)
            onNodeWithTag("book-passage-question").assertTextContains("Why does the plant need light?")
            onNodeWithTag("explain-trouble-retry").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-reply").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-unavailable").assertDoesNotExist()
            assertEquals(2, calls)
        }
    }

    @Test fun revokedLeadsBackToPairing() {
        val (library, book, _) = sample()
        val refused = ExplainResult.Refused(ExplainTrouble.REVOKED, ExplainTrouble.Revoked)
        val support = PairedBookLearningSupport(library) { _, _ -> refused }
        support.allow(book.id)
        var repaired = 0
        runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
            setContent { CoachTheme(darkTheme = true) { BookPassageAsk(book, book.pages.first(), support, onRepair = { repaired++ }) {} } }
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why light?")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("explain-trouble").assertTextEquals(refused.message)
            onNodeWithTag("explain-trouble-retry").assertDoesNotExist()
            onNodeWithTag("explain-trouble-repair").performScrollTo().performClick()
            assertEquals(1, repaired)
        }
    }

    @Test fun serviceUnavailableAllowsResendingWithTheSamePassageAndQuestion() {
        val (library, book, chosen) = sample()
        var calls = 0
        val support = PairedBookLearningSupport(library) { _, _ -> calls++; ExplainResult.Refused(BUSY, ExplainTrouble.Busy) }
        support.allow(book.id)
        runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
            setContent { CoachTheme(darkTheme = false) { BookPassageAsk(book, book.pages.first(), support) {} } }
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why does the plant need light?")
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, calls)
            onNodeWithTag("book-passage-send").performScrollTo().assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-trouble-retry").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, calls)
            onNodeWithTag("book-selected-passage").assertTextEquals(chosen.text)
            onNodeWithTag("book-passage-question").assertTextContains("Why does the plant need light?")
            onNodeWithTag("explain-trouble-repair").assertDoesNotExist()
        }
    }

    /**
     * 做题学答完 → 讲解被 401 停用 → 去登录页重新配对 → 回到同一节：这一节重新去取讲解，不再停在旧的「已停用」上。
     * 走真的配对文件和真的 HTTP，只是 Mac 换成本机一个假服务：第一次配对的凭据讲解时回 401，第二次的正常讲。
     */
    @Test fun answerExplainComesBackAfterPairingOnTheLoginScreen() {
        val (library, book, _) = sample()
        val lesson = book.lessons.first()
        val practice = assertNotNull(com.coach.desktop.books.testPracticeFixture(book, lesson, QuestionMode.CHOICE))
        FakeMac().use { mac ->
            val home = Files.createTempDirectory("explain-repair-home-").toRealPath()
            val settings = DesktopConnectionSettings(home, { null }, mac = false,
                property = { when (it) { "jpackage.app-path" -> "/synthetic/Coach.exe"; "coach.sableLogin" -> "false"; else -> null } })
            assertTrue(runBlocking { BookExplainer(settings, library).pair(mac.url, "123456") })
            val consent = ExplainConsent(library.directory).also { it.allow(book.id) }
            // 和 Windows 主机一样：登录页完成后，学习页重新加载已保存的配对。
            val client = BookExplainer(settings, library)
            val reply = mutableStateOf<BookLearningReply?>(null)
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = true) {
                        WithLogin(client, mac.url) {
                            val gate = remember { answerExplainWith(BookExplainer(settings, library), consent) }
                            LessonAnswerExplanation(book, lesson, practice, gate, reply, sources = {}, onOpenSourcePage = {})
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("answer-explain-unpaired").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("answer-explain-unpaired-text").assertTextEquals(ExplainTrouble.REVOKED)
                assertEquals(1, mac.explains.get())
                onNodeWithTag("answer-explain-pair").performClick()
                // 撤销后进入全屏登录页，配对成功后回到原来的答后讲解。
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("login-revoked-reason").assertExists()
                onNodeWithTag("login-address").performTextClearance()
                onNodeWithTag("login-address").performTextInput(mac.url)
                onNodeWithTag("login-code").performTextInput("654321")
                onNodeWithTag("login-pair").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isEmpty() }
                assertEquals(2, mac.pairs.get())
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-explanation-text").assertTextContains("Light gives the plant its energy.", substring = true)
                onNodeWithTag("book-explanation-unavailable").assertDoesNotExist()
                assertEquals(2, mac.explains.get())
            }
        }
    }

    /**
     * 放出了 Sable 登录的构建（TON-1760 显示模式）：答后讲解碰到 401，那一句要把登录也说上；
     * 登录页的撤销提示和账号登录按钮同样在，配对码仍可填。
     */
    @Test fun answerExplain401PointsToLoginWhenTheSableEntryIsShown() {
        val (library, book, _) = sample()
        val lesson = book.lessons.first()
        val practice = assertNotNull(com.coach.desktop.books.testPracticeFixture(book, lesson, QuestionMode.CHOICE))
        FakeMac().use { mac ->
            val settings = DesktopConnectionSettings(Files.createTempDirectory("explain-sable-401-").toRealPath(), { null }, mac = false,
                property = { when (it) { "jpackage.app-path" -> "/synthetic/Coach.exe"; "coach.sableLogin" -> "true"; else -> null } })
            assertTrue(runBlocking { BookExplainer(settings, library).pair(mac.url, "123456") })
            val consent = ExplainConsent(library.directory).also { it.allow(book.id) }
            val client = BookExplainer(settings, library)
            val reply = mutableStateOf<BookLearningReply?>(null)
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = false) {
                        WithLogin(client, mac.url) {
                            val gate = remember { answerExplainWith(BookExplainer(settings, library), consent) }
                            LessonAnswerExplanation(book, lesson, practice, gate, reply, sources = {}, onOpenSourcePage = {})
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("answer-explain-unpaired").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("answer-explain-unpaired-text").assertTextEquals(ExplainTrouble.REVOKED_LOGIN)
                assertTrue(ExplainTrouble.REVOKED_LOGIN.contains("登录"))
                onNodeWithTag("answer-explain-pair").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("textbook-login-screen").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("login-revoked-reason").assertTextContains("登录", substring = true)
                onNodeWithText("用 Sable 账号登录").assertExists()
                onNodeWithTag("login-pairing-toggle").performClick()
                onNodeWithTag("login-code").assertExists()
            }
            assertEquals(1, mac.explains.get())
        }
    }

    /** 本机回环上的假 Mac：配对第 n 次发第 n 份凭据；第一份讲解时回 401，之后的照常讲，引用发来的第一段。 */
    private class FakeMac : AutoCloseable {
        val pairs = AtomicInteger()
        val explains = AtomicInteger()
        private val tokens = listOf("a".repeat(43), "b".repeat(43))
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String

        init {
            server.createContext("/") { exchange ->
                val sent = exchange.requestBody.use { it.readBytes() }.decodeToString()
                val (status, body) = when (exchange.requestURI.path) {
                    "/v1/pair" -> 200 to """{"deviceId":"friend-pc","token":"${tokens[pairs.getAndIncrement().coerceAtMost(1)]}"}"""
                    "/v1/public/status" -> 200 to "{}"
                    "/v1/hello" -> 200 to """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                    "/v1/library/explain" -> {
                        explains.incrementAndGet()
                        if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${tokens[0]}") 401 to """{"error":"unauthorized"}"""
                        else {
                            val id = Json.parseToJsonElement(sent).jsonObject.getValue("blocks").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
                            val page = Regex(":p(\\d+):").find(id)!!.groupValues[1]
                            200 to """{"text":"Light gives the plant its energy.【S1】","sources":[{"docId":"$id","pdfPage":$page,"anchor":"S1"}]}"""
                        }
                    }
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

    private data class Reply(val status: Int, val body: String, val type: String = "application/json")

    private class TestMac(private val url: String) :
        BookExplainer(DesktopConnectionSettings(
            home = Files.createTempDirectory("explain-trouble-home-").toRealPath(),
            environment = { null },
            mac = false,
            property = { if (it == "coach.sableLogin") "false" else null },
        )) {
        override suspend fun load() { identity = DesktopIdentity(url, PairedCredentials("test-device", "test-token", "friend")); loaded = true }
        fun switchTo(value: DesktopIdentity) { identity = value }
    }

    private val block = SourceBlock("synthetic:p1:0", 1, 0, 30, heading = false, text = "Plants use light to make food.")
    private val BUSY = "讲解服务暂时不可用，请稍后再试。"

    private fun sample(): Triple<TextbookLibrary, Textbook, SourceBlock> {
        val root = Files.createTempDirectory("explain-trouble-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        return Triple(library, book, book.lessons.first().blocks.first { !it.heading })
    }

    private suspend fun withMac(reply: Reply, body: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = reply.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "${reply.type}; charset=utf-8")
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { body("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
}
