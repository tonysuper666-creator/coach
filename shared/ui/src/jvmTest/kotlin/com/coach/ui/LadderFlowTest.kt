package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
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

/** The production screen and CourseHttp, invented material, and a real close/reopen of SQLite. Run on Mac. */
@OptIn(ExperimentalTestApi::class)
class LadderFlowTest {
    @Test fun directExplanationThenAdaptiveQuestionsAndRecapSurviveDatabaseReopen() {
        val answers = CopyOnWriteArrayList<JsonObject>()
        val questions = (0..8).map { n -> LadderQuestion("q$n", "K", "S", if (n == 0) "introduction" else "concept",
            if (n == 0) 2 else 0, "fruit", "fingerprint$n", eligible = true) }
        val items = questions.map { q -> json("id" to q.id, "tier" to "detail", "stem" to "Synthetic basket ${q.id}: count two pears.",
            "options" to listOf("One", "Two", "Three"), "factIds" to listOf("F1"), "keyState" to "VERIFIED", "status" to "new",
            "ladder" to LedgerJson.encodeToJsonElement(q)) }
        val body = json("week" to 1, "learning" to true, "items" to JsonArray(items), "notes" to JsonArray(listOf(
            json("section_id" to "S", "title" to "Synthetic baskets", "body" to "Count each pear once.", "fact_ids" to listOf("F1"), "sources" to listOf("Synthetic book p.1")))))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/C/mainline") { exchange ->
                val reply = if (exchange.requestURI.path.endsWith("/answer")) {
                    val wire = LedgerJson.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    answers += wire
                    json("itemId" to wire.str("itemId"), "choice" to wire.num("choice"), "answer" to 1, "keyState" to "VERIFIED", "kpId" to "K",
                        "dontKnow" to wire.flag("dontKnow"), "guessed" to wire.flag("guessed"),
                        "status" to if (wire.flag("dontKnow")) "missed" else "mastered",
                        "independentCorrect" to !wire.flag("dontKnow"), "remember" to "Synthetic memory ${wire.str("itemId")}",
                        "explanation" to json("correct" to "Two pears are counted once.", "background" to "Synthetic counting only.", "options" to json("0" to "One pear was omitted.", "2" to "One pear was counted twice.")))
                } else body
                val bytes = reply.toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "synthetic", "synthetic", "C")
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ 1793188800000L }))
        val file = File.createTempFile("ladder-flow-", ".sqlite")
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        fun controller(driver: JdbcSqliteDriver) = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { 1793188800000L }, course = http)
        val view = CourseView(emptyList(), project(emptyList()))
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            ClientDatabase.Schema.create(driver)
            var c = controller(driver)
            c.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
            runSkikoComposeUiTest(size = Size(933f, 704f), density = Density(1f)) {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-dont-know").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(0, answers.size)
                onNodeWithText("Synthetic basket q0: count two pears.").assertExists()
                onNodeWithTag("mainline-dont-know").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(answers.single().flag("dontKnow")); assertEquals(-1, answers.single().num("choice").toInt())
                val feedback = c.sync.mainlineState("C", "week:1")["result"]
                onNodeWithText("回到要点").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-notes").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-skip").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(feedback, c.sync.mainlineState("C", "week:1")["result"])
                onNodeWithTag("mainline-next").performScrollTo().performClick()
                for (n in 1..5) {
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic basket q$n: count two pears.").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                    onNodeWithTag("mainline-submit").performScrollTo().performClick()
                    waitUntil(timeoutMillis = 10_000) { answers.size == n + 1 && onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-next").performScrollTo().performClick()
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-checkpoint").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("刚学会的").assertExists()
                val screens = File("build/screens").also { it.mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(screens, "ladder-recap-933x704.png"))
            }
            val saved = c.sync.mainlineState("C", "week:1")
            assertTrue(saved.flag("recapVisible"))
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            c = controller(driver)
            assertEquals(saved, c.sync.mainlineState("C", "week:1"))
            runSkikoComposeUiTest(size = Size(933f, 704f), density = Density(1f)) {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-recap-continue").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("ladder-recap-continue").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic basket q6: count two pears.").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(6, answers.size)
            }
        } finally { driver.close(); tutor.close(); http.close(); server.stop(0); file.delete() }
    }
}
