package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.server.Mainline
import com.coach.server.MockLibrary
import com.coach.store.LedgerStore
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

/** Same producer JSON -> real Mainline -> CourseHttp -> selector -> production Compose screen. Mac only. */
@OptIn(ExperimentalTestApi::class)
class LadderRuntimeAdapterTest {
    @Test fun essayAssociationShowsPendingEndpointWithoutDisplayingIdsOrInventingFullText() {
        for ((width, height) in listOf(412 to 915, 933 to 704)) {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                setContent { CoachTheme(false) { Surface(Modifier.padding(24.dp)) {
                    LadderEssayFeedback(json("essay" to json("model_answer_id" to "SYN-E1", "rubric_point_ids" to listOf("SYN-R1"))))
                } } }
                onNodeWithTag("ladder-essay-status").assertIsDisplayed()
                onNodeWithText("已关联论述示范与评分点；全文读取尚未接通，暂不能查看。").assertIsDisplayed()
                onNodeWithText("SYN-E1", substring = true).assertDoesNotExist()
                val bounds = onNodeWithTag("ladder-essay-status").fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height)
                captureLessonEvidence("runtime-essay-pending-${width}x$height", width, height)
            }
        }
    }

    @Test fun syntheticContractAndPendingScopeAtPhoneAndDesktop() {
        for ((width, height) in listOf(412 to 915, 933 to 704)) walk(width, height)
    }

    private fun walk(width: Int, height: Int) {
        val raw = LedgerJson.parseToJsonElement(javaClass.getResource("/question-ladder/synthetic-runtime-v1.json")!!.readText()).jsonObject
        val runtime = SyntheticLadderRuntime.parse(raw)
        val directory = Files.createTempDirectory("runtime-adapter")
        val store = LedgerStore(Files.createDirectories(directory.resolve("ledger")))
        val mainline = Mainline(MockLibrary(Files.createDirectories(directory.resolve("papers"))), syntheticRuntime = runtime)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/SYNTHETIC/mainline") { exchange ->
                val response = if (exchange.requestURI.path.endsWith("/answer")) {
                    val wire = LedgerJson.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    mainline.answer(store, "SYNTHETIC", 1, wire)
                } else mainline.week(store, "SYNTHETIC", 1, learning = true)
                val bytes = response.toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            val http = CourseHttp("http://127.0.0.1:${server.address.port}", "synthetic", "synthetic", "SYNTHETIC")
            val sync = SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("SYNTHETIC"), { "id" }, { "hash" })
            val c = CoachController("SYNTHETIC", sync,
                TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ 1793188800000L })),
                formatClock = { "12:00" }, nowMillis = { 1793188800000L }, course = http)
            sync.saveMainlineState("SYNTHETIC", json("week" to 1, "entry" to "quiz"))
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-submit").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("How many objects are one and one?").assertExists()
                onNodeWithText("What is one plus one?").assertDoesNotExist()
                onNodeWithText("Two objects form a pair.").assertDoesNotExist()
                onNodeWithTag("ladder-synthetic-status").assertIsDisplayed()
                onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                onNodeWithTag("mainline-submit").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                val result = sync.mainlineState("SYNTHETIC", "week:1")["result"]!!.jsonObject
                assertFalse(result.flag("independentCorrect")); assertTrue(result.flag("syntheticOnly"))
                onNodeWithText("合成示例：已查看参考答案，不计掌握。").performScrollTo().assertIsDisplayed()
                captureLessonEvidence("runtime-answer-${width}x$height", width, height)
                onNodeWithTag("mainline-next").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-checkpoint").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("ladder-scope-status").assertIsDisplayed()
                onNodeWithText("合成示例，不计掌握；正式学习范围仍待核。").assertIsDisplayed()
                captureLessonEvidence("runtime-scope-${width}x$height", width, height)
            }
            assertTrue(store.events("SYNTHETIC").isEmpty())
        } finally {
            driver.close(); server.stop(0); store.close(); directory.toFile().deleteRecursively()
        }
    }
}
