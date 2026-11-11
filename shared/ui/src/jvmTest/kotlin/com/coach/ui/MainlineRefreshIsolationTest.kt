package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Synthetic refresh behaviour for the weekly notes screen.
 * Events go through MainlineScreen and the real controller: a late answer payload,
 * an explicit reread while the Mac is offline, and an older week response released
 * after a newer reread. Nothing here is a copied cache.
 */
@OptIn(ExperimentalTestApi::class)
class MainlineRefreshIsolationTest {
    private val now = 1_794_052_800_000L
    private val current = "Synthetic apples are sold out."
    private val bridge = "Synthetic pears connect the orchard to the next topic."
    private val late = "LATE OLD RESPONSE covers the current section."
    private val fresh = "FRESH SECTION DRAFT after reconnect."
    private val note = "Keep this synthetic note"

    private class RefreshMac : AutoCloseable {
        @Volatile var offline = false
        @Volatile var gate = false
        @Volatile var weekBody = week(currentBasics = "Synthetic apples are sold out.", version = "v1")
        @Volatile var answerNotes: String? = null
        val gates = CopyOnWriteArrayList<CompletableFuture<String>>()
        private val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            fun reply(ex: com.sun.net.httpserver.HttpExchange, body: String, status: Int = 200) {
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            createContext("/v1/courses/C/mainline") { ex ->
                val path = ex.requestURI.path
                if (path.endsWith("/answer")) {
                    ex.requestBody.readBytes()
                    val notes = answerNotes?.let { ""","notes":$it""" }.orEmpty()
                    reply(ex, """{"itemId":"S1","answer":1,"choice":0,"guessed":false,"keyState":"VERIFIED","status":"missed","tier":"setup",
                        "explanation":{"correct":"Synthetic pears are the only fruit grown in the synthetic orchard.","options":{"0":"Synthetic apples were sold out."},"background":"Synthetic background.","source":"Synthetic lecture 1"},
                        "source":"Synthetic lecture 1","kpId":"K","dueDay":null,"mistakeWeek":1,"next":null,"coverage":${cov(0, 1)}$notes}""")
                    return@createContext
                }
                if (offline && path.endsWith("/mainline/1")) {
                    reply(ex, """{"error":"Synthetic offline"}""", 503)
                    return@createContext
                }
                if (gate && path.endsWith("/mainline/1")) {
                    val pending = CompletableFuture<String>()
                    gates += pending
                    val body = runCatching { pending.get(20, TimeUnit.SECONDS) }.getOrElse { weekBody }
                    runCatching { reply(ex, body) }
                    return@createContext
                }
                reply(ex, when {
                    path.endsWith("/mainline/1") -> weekBody
                    else -> """{"weeks":[{"week":1,"title":"Synthetic orchard","coverage":${cov(0, 0)}}],"invalid":[]}"""
                })
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test", "synthetic", "C")
        override fun close() {
            gates.forEach { it.completeExceptionally(IllegalStateException("closed")) }
            http.close(); server.stop(0); pool.shutdownNow()
        }
    }

    @Test fun lateAnswerCannotReplaceTheOpenSection() = RefreshMac().use { mac ->
        mac.answerNotes = """[{"section_id":"W01-basics","title":"Synthetic basics","body":${q(late)},"content_version":"old","fact_ids":["F-S1"],"sources":["Synthetic lecture 1"]}]"""
        harness(mac) { c ->
            openNotes()
            onNodeWithText("○ Synthetic bridge notes").assertExists()
            answerCurrentSectionWrong()
            onNodeWithText("回到要点").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(current, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(late, substring = true).assertDoesNotExist()
            onNodeWithText("○ Synthetic bridge notes").performScrollTo().performClick()
            onNodeWithText(bridge, substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText(late, substring = true).assertDoesNotExist()
            val state = c.sync.mainlineState("C", "week:1")
            assertEquals("missed", (state["result"] as? JsonObject)?.str("status"), "answering must keep the result when a late notes payload arrives")
            assertEquals("S1", state.str("item"), "a late notes payload must not move the saved question")
            assertEquals("W01-bridge", state.str("section"))
        }
    }

    @Test fun offlineRereadKeepsTheAnswerAndAnnotation() = RefreshMac().use { mac ->
        harness(mac) { c ->
            openNotes()
            annotateCurrentSection()
            onNodeWithText("○ Synthetic bridge notes").performScrollTo().performClick()
            onNodeWithText("我的一句话：$note").assertDoesNotExist()
            onNodeWithText("原文已更新", substring = true).assertDoesNotExist()
            onNodeWithText("○ Synthetic basics").performScrollTo().performClick()
            onNodeWithText("我的一句话：$note").performScrollTo().assertIsDisplayed()
            answerCurrentSectionWrong()
            onNodeWithText("回到要点").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("我的一句话：$note").fetchSemanticsNodes().isNotEmpty() }
            mac.offline = true
            onNodeWithText("重新读取本周内容").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText(current, substring = true).assertIsDisplayed()
            onNodeWithText("我的一句话：$note").performScrollTo().assertIsDisplayed()
            onNodeWithTag("mainline-section-quiz").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("答错。").performScrollTo().assertIsDisplayed()
            onNodeWithTag("mainline-option-0").assertIsSelected()
            val state = c.sync.mainlineState("C", "week:1")
            assertEquals("missed", (state["result"] as? JsonObject)?.str("status"), "an offline reread must keep the saved result")
            assertEquals(0, (state["choice"] as? JsonPrimitive)?.intOrNull, "an offline reread must keep the saved choice")
            val marks = (mac.http.readerState(c.sync, 1)["marks"] as? JsonArray).orEmpty().map { it.jsonObject }
            assertEquals(listOf("W01-basics"), marks.map { it.str("section") })
            assertEquals(note, marks.single().str("note"))
        }
    }

    @Test fun lateWeekResponseCannotCoverTheCurrentSection() = RefreshMac().use { mac ->
        harness(mac) { c ->
            openNotes()
            annotateCurrentSection()
            answerCurrentSectionWrong()
            onNodeWithText("回到要点").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(current).fetchSemanticsNodes().isNotEmpty() }
            mac.gate = true
            onNodeWithText("重新读取本周内容").performScrollTo().performClick()
            pumpUntil(mac, 1)
            onNodeWithText(current, substring = true).assertIsDisplayed()
            onNodeWithText("重新读取本周内容").performScrollTo().performClick()
            pumpUntil(mac, 2)
            mac.gates[1].complete(week(fresh, "v2"))
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(fresh, substring = true).fetchSemanticsNodes().isNotEmpty() }
            mac.gates[0].complete(week(late, "old"))
            waitForIdle()
            mainClock.advanceTimeBy(200)
            waitForIdle()
            Thread.sleep(400)
            waitForIdle()
            onNodeWithText(fresh, substring = true).assertIsDisplayed()
            onNodeWithText(late, substring = true).assertDoesNotExist()
            onNodeWithText("原文已更新，划线位置待重新确认。保留的是当时的原句和批注。").performScrollTo().assertIsDisplayed()
            onNodeWithText("我的一句话：$note").performScrollTo().assertIsDisplayed()
            onNodeWithText("○ Synthetic bridge notes").performScrollTo().performClick()
            onNodeWithText(bridge, substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText("我的一句话：$note").assertDoesNotExist()
            onNodeWithText("原文已更新", substring = true).assertDoesNotExist()
            onNodeWithText(late, substring = true).assertDoesNotExist()
            onNodeWithText("○ Synthetic basics").performScrollTo().performClick()
            onNodeWithTag("mainline-section-quiz").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("答错。").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-option-0").assertIsSelected()
            assertEquals("missed", (c.sync.mainlineState("C", "week:1")["result"] as? JsonObject)?.str("status"))
        }
    }

    private fun harness(mac: RefreshMac, block: ComposeUiTest.(CoachController) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network in this test")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val c = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
        val view = CourseView(emptyList(), project(emptyList()))
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 900.dp)) {
                    MainlineScreen(c, view, onBack = {}, onOpenSteps = {}, onOpenEssay = {}, onReviewWeek = {})
                } } } }
                block(c)
            }
        } finally { tutor.close(); driver.close() }
    }

    private fun ComposeUiTest.openNotes() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-week-1").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("mainline-week-1").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(current).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.annotateCurrentSection() {
        onNodeWithTag("reader-text").performScrollTo().performSemanticsAction(SemanticsActions.SetSelection) { it(0, 16, false) }
        onNodeWithText("一句批注").performScrollTo().performClick()
        onNodeWithTag("reader-note").performTextInput(note)
        onNodeWithText("返回阅读").performClick()
        onNodeWithText("我的一句话：$note").performScrollTo().assertIsDisplayed()
    }

    private fun ComposeUiTest.answerCurrentSectionWrong() {
        onNodeWithTag("mainline-section-quiz").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("mainline-option-0").performScrollTo().performClick()
        onNodeWithTag("mainline-submit").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("答错。").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.pumpUntil(mac: RefreshMac, count: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (mac.gates.size < count) {
            if (System.nanoTime() > deadline) fail("expected $count in-flight week reads, saw ${mac.gates.size}")
            mainClock.advanceTimeByFrame()
            Thread.sleep(30)
        }
    }
}

private fun cov(mastered: Int, answered: Int) =
    """{"facts":2,"factsWithQuestions":2,"factsMastered":$mastered,"questions":2,"answered":$answered,"mastered":$mastered,"keysPending":0}"""

private fun q(text: String) = JsonPrimitive(text).toString()

private fun week(currentBasics: String, version: String) = """{"week":1,"title":"Synthetic orchard","coverage":${cov(0, 0)},"items":[
    {"id":"S1","tier":"setup","stem":"Synthetic orchard question S1: which statement about the orchard holds?","options":["Apples are grown there","Pears are grown there","Plums ripen early","Figs are grown there"],"status":"new","keyState":"VERIFIED","factIds":["F-S1"]},
    {"id":"B1","tier":"bridge","stem":"Synthetic orchard question B1: which statement about the bridge holds?","options":["Apples are grown there","Pears are grown there","Plums ripen early","Figs are grown there"],"status":"new","keyState":"VERIFIED","factIds":["F-B1"]}
],"notes":[
    {"section_id":"W01-basics","title":"Synthetic basics","body":${q(currentBasics)},"content_version":"$version","fact_ids":["F-S1"],"sources":["Synthetic lecture 1"]},
    {"section_id":"W01-bridge","title":"Synthetic bridge notes","body":"Synthetic pears connect the orchard to the next topic.","content_version":"bridge-v1","fact_ids":["F-B1"],"sources":["Synthetic lecture 1"]}
],"emphasis":[]}"""
