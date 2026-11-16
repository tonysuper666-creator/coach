package com.coach.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.ui.CoachController
import com.coach.ui.CoachTheme
import com.coach.ui.MainlineScreen
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** All course data and HTTP traffic are local, disposable fixtures. */
@OptIn(ExperimentalTestApi::class)
class WindowsMainlineEmptyTest {
    private fun exercise(week: Int, withQuestion: Boolean) {
        val ladder = LadderQuestion("synthetic-q", "K", "S", "introduction", 0, "synthetic", "synthetic", eligible = true)
        val question = json("id" to "synthetic-q", "stem" to "Synthetic available question", "options" to listOf("A", "B"),
            "status" to "new", "keyState" to "VERIFIED", "factIds" to listOf("F"), "ladder" to LedgerJson.encodeToJsonElement(ladder))
        val body = json("week" to week, "learning" to true, "items" to JsonArray(if (withQuestion) listOf(question) else emptyList()))
        val answers = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val response = if (exchange.requestURI.path.endsWith("/answer")) {
                    answers.incrementAndGet()
                    """{"itemId":"synthetic-q","answer":1,"choice":1,"keyState":"VERIFIED","status":"mastered","independentCorrect":true,"remember":"Synthetic memory","explanation":{"correct":"Synthetic answer"}}"""
                } else body.toString()
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "test", "synthetic", "C")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val now = 1_794_052_800_000L
        val c = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now })),
            formatClock = { "12:00" }, nowMillis = { now }, course = http)
        var returned = false
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 800.dp)) {
                    MainlineScreen(c, CourseView(emptyList(), project(emptyList())), { returned = true }, {}, {}, {}, directEntry = true, initialWeek = week)
                } } } }
                if (withQuestion) {
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic available question", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-option-1").performClick()
                    onNodeWithTag("mainline-submit").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(1, answers.get())
                } else {
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("这一周还没有可用题目", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    val message = onNodeWithText("这一周还没有可用题目", substring = true).fetchSemanticsNode().boundsInRoot
                    val back = onNodeWithText("← 第 $week 周").fetchSemanticsNode().boundsInRoot
                    assertTrue(back.bottom <= message.top, "The return control must not overlap the empty-state message")
                    onNodeWithText("← 第 $week 周").performTouchInput { click() }
                    runOnIdle { assertTrue(returned) }
                    onAllNodesWithTag("mainline-option-0").assertCountEquals(0)
                }
            }
        } finally { http.close(); server.stop(0); driver.close() }
    }

    @Test fun emptyWeekOneShowsMessageAndCanReturn() = exercise(1, false)
    @Test fun emptyWeekTwoShowsMessageAndCanReturn() = exercise(2, false)
    @Test fun availableWeekOneQuestionOpens() = exercise(1, true)
    @Test fun availableWeekTwoQuestionOpens() = exercise(2, true)
}
