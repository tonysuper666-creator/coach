package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopPairingGate
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * TON-1760：生产 `/link` 还没放行，Windows 1.1 默认不放「用 Sable 账号登录」，
 * 首次连接和连接停用后的重新配对都直接给配对码表单；候选构建时传了 `coach.sableLogin=true` 才放出入口。
 * Mac 换成本机回环上的假服务，只答配对和握手，从不碰生产端口。
 */
@OptIn(ExperimentalTestApi::class)
class SableLoginEntryTest {
    @Test fun firstConnectionOffersOnlyThePairingCodeAndItConnects() {
        PairingMac().use { mac ->
            val (library, book) = sample()
            val settings = settings(Files.createTempDirectory("sable-hidden-first-"))
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent { CoachTheme(darkTheme = false) { BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), BookExplainer(settings, library)) } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isNotEmpty() }
                assertNoSable()
                onNodeWithTag("explain-connect-hint").assertTextContains("配对码", substring = true)
                pair(mac.url, "123456")
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
                onNodeWithText("已连上 Mac", substring = true).assertExists()
            }
            assertEquals(1, mac.pairs.get())
        }
    }

    @Test fun revokedConnectionGoesStraightBackToThePairingCode() {
        PairingMac().use { mac ->
            val (library, book) = sample()
            val settings = settings(Files.createTempDirectory("sable-hidden-revoked-"))
            val explainer = BookExplainer(settings, library)
            assertTrue(kotlinx.coroutines.runBlocking { explainer.pair(mac.url, "123456") })
            explainer.markRevoked()
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent { CoachTheme(darkTheme = true) { BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), explainer) } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-revoked").fetchSemanticsNodes().isNotEmpty() }
                assertNoSable()
                onNodeWithTag("explain-revoked").assertTextContains("新的配对码", substring = true)
                pair(mac.url, "654321")
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isEmpty() }
                onNodeWithText("已连上 Mac", substring = true).assertExists()
            }
            assertEquals(2, mac.pairs.get())
            assertFalse(ExplainTrouble.REVOKED.contains("登录"), "停用那一句不能再叫人去登录")
        }
    }

    @Test fun existingPairingStillOpensConnectedWithoutAForm() {
        PairingMac().use { mac ->
            val (library, book) = sample()
            val settings = settings(Files.createTempDirectory("sable-hidden-existing-"))
            assertTrue(kotlinx.coroutines.runBlocking { BookExplainer(settings, library).pair(mac.url, "123456") })
            // 重开程序：新的 explainer 从磁盘读回同一份配对，直接是「已连上」。
            runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
                setContent { CoachTheme(darkTheme = false) { BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), BookExplainer(settings, library)) } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("已连上 Mac", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("explain-pairing").assertDoesNotExist()
            }
            assertEquals(1, mac.pairs.get())
        }
    }

    @Test fun candidateBuildThatTurnsItOnShowsTheSableEntryAgain() {
        val (library, book) = sample()
        val settings = settings(Files.createTempDirectory("sable-shown-"), sableLogin = true)
        assertTrue(settings.sableLoginShown())
        runSkikoComposeUiTest(size = Size(1100f, 850f), density = Density(1f)) {
            setContent { CoachTheme(darkTheme = false) { BookExplainPanel(book, book.lessons.first().firstPage, ExplainConsent(library.directory), BookExplainer(settings, library)) } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-pairing").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("用 Sable 账号登录").assertExists()
            onNodeWithText("没有 Sable 账号？用配对码").assertExists()
            onNodeWithTag("explain-code").assertExists()
        }
    }

    @Test fun onlyTheExactWordTrueTurnsItOn() {
        val home = Files.createTempDirectory("sable-flag-")
        for (value in listOf(null, "", "false", "1", "yes")) {
            val settings = DesktopConnectionSettings(home, { null }, mac = false, property = { if (it == "coach.sableLogin") value else null })
            assertFalse(settings.sableLoginShown(), "coach.sableLogin=$value")
        }
        assertTrue(DesktopConnectionSettings(home, { null }, mac = false, property = { if (it == "coach.sableLogin") " true " else null }).sableLoginShown())
    }

    @Test fun pairingGateFollowsTheSameBuildDecision() {
        for (shown in listOf(false, true)) {
            val settings = settings(Files.createTempDirectory("sable-gate-"), sableLogin = shown)
            runComposeUiTest {
                setContent { Box(Modifier.size(840.dp, 720.dp)) { DesktopPairingGate(settings) { Text("已进入学习", Modifier.testTag("desktop-paired")) } } }
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("连接你的 Mac").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("配对") and hasClickAction()).assertExists()
                if (shown) onNodeWithText("用 Sable 账号登录").assertExists() else assertNoSableText()
            }
        }
    }

    /** 隐藏模式：讲解页的配对表单里没有 Sable 字样，地址和配对码两格都在。 */
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

    private fun settings(home: Path, sableLogin: Boolean = false) = DesktopConnectionSettings(home, { null }, mac = false, property = {
        when (it) {
            "jpackage.app-path" -> "/synthetic/LearningCoach.exe"
            "coach.sableLogin" -> if (sableLogin) "true" else null
            else -> null
        }
    })

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
}
