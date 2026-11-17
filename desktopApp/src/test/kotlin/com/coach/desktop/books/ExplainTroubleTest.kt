package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.*

/**
 * 朋友那台电脑连李政的 Mac 讲解时，没讲成的几种情况（TON-1687）：
 * 连不上、额度用完、连接被停用，以及服务端回的那句话怎么原样到人眼前。教材是自造的英文短文。
 */
@OptIn(ExperimentalTestApi::class)
class ExplainTroubleTest {
    @Test fun eachReplyKindGetsItsOwnNextStep() {
        assertEquals(ExplainTrouble.Unreachable, ExplainTrouble.of(null, null))
        assertEquals(ExplainTrouble.Revoked, ExplainTrouble.of(401, null))
        assertEquals(ExplainTrouble.Revoked, ExplainTrouble.of(401, "daily_quota"))
        assertEquals(ExplainTrouble.Quota, ExplainTrouble.of(429, "daily_quota"))
        assertEquals(ExplainTrouble.Quota, ExplainTrouble.of(403, "monthly_quota"))
        assertEquals(ExplainTrouble.Quota, ExplainTrouble.of(429, null))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(503, "unavailable"))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(500, null))
        assertEquals(ExplainTrouble.Busy, ExplainTrouble.of(408, null))
        assertEquals(ExplainTrouble.Other, ExplainTrouble.of(400, "source_mismatch"))
        assertFalse(ExplainTrouble.Quota.retry)
        assertFalse(ExplainTrouble.Revoked.retry)
        assertTrue(ExplainTrouble.Unreachable.retry && ExplainTrouble.Busy.retry && ExplainTrouble.Other.retry)
        // 连不上和停用用固定的话，不管服务端或底层说了什么。
        assertEquals(ExplainTrouble.UNREACHABLE, ExplainTrouble.message(ExplainTrouble.Unreachable, "java.net.ConnectException: refused"))
        assertEquals(ExplainTrouble.REVOKED, ExplainTrouble.message(ExplainTrouble.Revoked, "HTTP 401"))
        assertEquals("讲解额度用完了。", ExplainTrouble.message(ExplainTrouble.Quota, "  "))
    }

    @Test fun serverSentenceReachesThePersonWithoutCodeOrStatus() = runBlocking {
        val quota = "今天的讲解额度用完了，明天再来。"
        val busy = "讲解这会儿排满了，过一会儿再试。"
        val cases = listOf(
            Reply(429, """{"error":"$quota","code":"daily_quota"}""") to (ExplainTrouble.Quota to quota),
            Reply(403, """{"error":"这个月的讲解额度用完了。","code":"monthly_quota"}""") to (ExplainTrouble.Quota to "这个月的讲解额度用完了。"),
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
        val support = PairedBookLearningSupport(library) { _, _ -> ExplainResult.Refused("今天的讲解额度用完了，明天再来。", ExplainTrouble.Quota) }
        support.allow(book.id)
        val reply = assertIs<BookLearningReply.Unavailable>(support.ask(BookPassageQuestion(BookPassage.from(book, chosen), "Why light?")))
        assertEquals("今天的讲解额度用完了，明天再来。", reply.message)
        assertEquals(ExplainTrouble.Quota, reply.trouble)
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

    @Test fun quotaHasNoRetryAndRevokedLeadsBackToPairing() {
        val (library, book, _) = sample()
        for (refused in listOf(ExplainResult.Refused("今天的讲解额度用完了，明天再来。", ExplainTrouble.Quota),
            ExplainResult.Refused(ExplainTrouble.REVOKED, ExplainTrouble.Revoked))) {
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
                if (refused.trouble == ExplainTrouble.Revoked) {
                    onNodeWithTag("explain-trouble-repair").performScrollTo().performClick()
                    assertEquals(1, repaired)
                } else onNodeWithTag("explain-trouble-repair").assertDoesNotExist()
            }
        }
    }

    private data class Reply(val status: Int, val body: String, val type: String = "application/json")

    private class TestMac(private val url: String) :
        BookExplainer(DesktopConnectionSettings(home = Files.createTempDirectory("explain-trouble-home-"), environment = { null })) {
        override suspend fun load() { identity = DesktopIdentity(url, PairedCredentials("test-device", "test-token")); loaded = true }
    }

    private val block = SourceBlock("synthetic:p1:0", 1, 0, 30, heading = false, text = "Plants use light to make food.")

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
