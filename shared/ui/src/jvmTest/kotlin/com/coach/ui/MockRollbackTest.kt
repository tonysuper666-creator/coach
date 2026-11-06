package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.project
import com.coach.protocol.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/** The candidate UI must finish loading and offer retry when the installed old server has no mocks route. */
@OptIn(ExperimentalTestApi::class)
class MockRollbackTest {
    @Test fun oldServer404IsVisibleAndRetryable() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/C/mocks") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ 0 }))
        val http = CourseHttp("http://127.0.0.1:${server.address.port}", "synthetic", "synthetic", "C")
        val controller = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport,
            listOf("C"), { "id" }, { "hash" }), tutor, formatClock = { "" }, nowMillis = { 0 }, course = http)
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MockScreen(controller, CourseView(emptyList(), project(emptyList())), {}) } } }
                waitUntil(timeoutMillis = 5000) { onAllNodesWithText("404", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("404", substring = true).assertIsDisplayed()
                onNodeWithText("正在问 Mac…").assertDoesNotExist()
                onNodeWithText("重试").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5000) { requests.get() >= 2 }
                onNodeWithText("404", substring = true).assertIsDisplayed()
            }
        } finally { http.close(); tutor.close(); driver.close(); server.stop(0) }
    }
}
