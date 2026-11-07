package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

/** 一周选择题从选周走到讲解：讲解答完就在同页，猜对的不算掌握。题目、讲解都是合成的。 */
@OptIn(ExperimentalTestApi::class)
class MainlineScreenTest {
    private val now = 1_794_052_800_000L
    private val today = logicalDay(now, "Europe/London")

    private class FakeMac(today: Long, private val loseFirstReply: Boolean = false) : AutoCloseable {
        val answers = CopyOnWriteArrayList<JsonObject>()
        private val recorded = mutableMapOf<String, JsonObject>()
        private fun cov(mastered: Int, answered: Int) = """{"facts":3,"factsWithQuestions":3,"factsMastered":$mastered,"questions":3,"answered":$answered,"mastered":$mastered,"keysPending":0}"""
        private val explanation = """{"correct":"Synthetic pears are the only fruit grown in the synthetic orchard, so this statement matches the lecture example.",
            "options":{"0":"Synthetic apples were sold out before the season started, so they cannot be the answer.",
                       "2":"Synthetic plums ripen after the period described in the question.",
                       "3":"Synthetic figs are imported, not grown in the orchard."},
            "background":"The synthetic orchard story sets up the supply example used later in the week.","source":"Synthetic lecture 1, slide 4"}"""
        private val items = listOf("S1" to "setup", "D1" to "detail", "B1" to "bridge")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            fun reply(ex: com.sun.net.httpserver.HttpExchange, body: String, status: Int = 200) {
                val bytes = body.toByteArray(); ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            createContext("/v1/courses/C/mainline") { ex ->
                val path = ex.requestURI.path
                when {
                    path.endsWith("/answer") -> {
                        val wire = Json.parseToJsonElement(ex.requestBody.bufferedReader().readText()).jsonObject
                        answers += wire
                        val a = recorded.getOrPut(wire.getValue("submissionId").jsonPrimitive.content) { wire }
                        if (loseFirstReply && answers.size == 1) {
                            reply(ex, """{"error":"Synthetic response lost after commit"}""", 503)
                            return@createContext
                        }
                        val id = a["itemId"]!!.jsonPrimitive.content
                        val guessed = a["guessed"]!!.jsonPrimitive.boolean
                        val right = a["choice"]!!.jsonPrimitive.int == 1
                        val status = if (!right) "missed" else if (guessed) "guessed" else "mastered"
                        val i = items.indexOfFirst { it.first == id }
                        val next = items.getOrNull(i + 1)?.first?.let { "\"$it\"" } ?: "null"
                        val queued = status != "mastered"
                        reply(ex, """{"itemId":"$id","answer":1,"choice":${a["choice"]},"guessed":$guessed,"keyState":"VERIFIED","status":"$status","tier":"${items[i].second}",
                            "explanation":$explanation,"source":"Synthetic lecture 1","kpId":"K","dueDay":${if (queued) today + 1 else "null"},
                            "mistakeWeek":${if (queued) 1 else "null"},"next":$next,"coverage":${cov(if (status == "mastered") 1 else 0, i + 1)}}""")
                    }
                    path.endsWith("/mainline/1") -> reply(ex, """{"week":1,"title":"Synthetic orchard","coverage":${cov(0, 0)},"items":[""" +
                        items.joinToString(",") { (id, tier) -> """{"id":"$id","tier":"$tier","stem":"Synthetic orchard question $id: which statement about the orchard holds?",
                            "options":["Apples are grown there","Pears are grown there","Plums ripen early","Figs are grown there"],"status":"new","keyState":"VERIFIED"}""" } + "]}")
                    else -> reply(ex, """{"weeks":[{"week":1,"title":"Synthetic orchard","coverage":${cov(0, 0)}}],"invalid":[]}""")
                }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test", "synthetic", "C")
        override fun close() { http.close(); server.stop(0) }
    }

    private fun walk(width: Int, height: Int, retryChoice: Int? = null, firstGuessed: Boolean = false) = FakeMac(today, retryChoice != null).use { mac ->
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
        var steps = false
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(width.dp, height.dp)) {
                    MainlineScreen(c, view, onBack = {}, onOpenSteps = { steps = true }, onOpenEssay = {}, onReviewWeek = {})
                } } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-week-1").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("知识点 3 · 有题 3 · 已掌握 0").assertIsDisplayed()
                capture("mainline-weeks-$width")
                onNodeWithTag("mainline-week-1").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("铺垫题", substring = true).fetchSemanticsNodes().isNotEmpty() }

                if (retryChoice != null) {
                    onNodeWithTag("mainline-option-$retryChoice").performClick()
                    onNodeWithTag(if (firstGuessed) "mainline-guess" else "mainline-submit").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic response lost after commit").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-2").performClick()
                    onNodeWithTag(if (firstGuessed) "mainline-submit" else "mainline-guess").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(2, mac.answers.size)
                    assertEquals(mac.answers[0]["submissionId"], mac.answers[1]["submissionId"])
                    assertEquals(retryChoice, mac.answers[0].getValue("choice").jsonPrimitive.int)
                    assertEquals(2, mac.answers[1].getValue("choice").jsonPrimitive.int)
                    assertEquals(firstGuessed, mac.answers[0].getValue("guessed").jsonPrimitive.boolean)
                    assertEquals(!firstGuessed, mac.answers[1].getValue("guessed").jsonPrimitive.boolean)
                    onNodeWithTag("mainline-option-$retryChoice").assertIsSelected()
                    onNodeWithTag("mainline-option-2").assertIsNotSelected()
                    val wrongMark = hasText("✗") and hasAnyAncestor(hasTestTag("mainline-option-$retryChoice"))
                    if (retryChoice == 0) onNode(wrongMark, useUnmergedTree = true).assertExists()
                    else onNodeWithText("✗", useUnmergedTree = true).assertDoesNotExist()
                    val outcome = if (retryChoice == 0) "答错了。" else if (firstGuessed) "答对了，但标了猜的：不算掌握。" else "答对了，算掌握。"
                    onNodeWithText(outcome).performScrollTo().assertIsDisplayed()
                    capture("mainline-retry-$retryChoice-$firstGuessed-$width")
                    return@runComposeUiTest
                }

                // Wrong on the setup question: every option carries its reason right away, no extra tap.
                onNodeWithTag("mainline-option-0").performClick()
                onNodeWithTag("mainline-submit").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("为什么对", substring = true).assertIsDisplayed()
                for (reason in listOf("sold out", "ripen after", "imported")) onNodeWithText(reason, substring = true).assertExists()
                capture("mainline-explained-$width")
                onNodeWithText("答错了。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已排进复习：明天再看一次。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已进第 1 周错题本", substring = true).performScrollTo().assertIsDisplayed()
                onNodeWithText("Synthetic lecture 1, slide 4").performScrollTo().assertIsDisplayed()
                capture("mainline-explained-bottom-$width")
                onNodeWithTag("mainline-next").performScrollTo().performClick()

                // Right but marked as a guess: still not mastered, still queued.
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("细节题", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-option-1").performClick()
                onNodeWithTag("mainline-guess").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("答对了，但标了猜的：不算掌握。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已排进复习：明天再看一次。").performScrollTo().assertIsDisplayed()
                assertEquals(listOf(false, true), mac.answers.map { it["guessed"]!!.jsonPrimitive.boolean })
                capture("mainline-guessed-$width")
                onNodeWithTag("mainline-next").performScrollTo().performClick()

                // The bridge question leads on to step grading and the timed essay.
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("桥接题", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-option-1").performClick()
                onNodeWithTag("mainline-submit").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("答对了，算掌握。").performScrollTo().assertIsDisplayed()
                onNodeWithText("知识点 3 · 有题 3 · 已掌握 1").assertExists()
                onNodeWithTag("mainline-essay").performScrollTo().assertIsDisplayed()
                onNodeWithTag("mainline-steps").performClick()
                assertTrue(steps, "the bridge question opens step grading")
                onNodeWithText("这一周做完了").assertExists()
                capture("mainline-bridge-$width")
            }
        } finally { tutor.close(); driver.close() }
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = File("build/screens").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    @Test fun phoneWalksOneWeekWithExplanationsOnTheSamePage() = walk(412, 915)
    @Test fun desktopWalksOneWeekWithExplanationsOnTheSamePage() = walk(1000, 800)
    @Test fun lostWrongReplyMarksTheRecordedChoiceAfterChangingBothChoiceAndGuess() = walk(412, 915, 0, false)
    @Test fun lostGuessedReplyKeepsTheRecordedGuessAfterAnUnguessedRetry() = walk(1000, 800, 1, true)
    @Test fun lostCorrectReplyKeepsTheRecordedPassAfterAGuessedRetry() = walk(412, 915, 1, false)
}
