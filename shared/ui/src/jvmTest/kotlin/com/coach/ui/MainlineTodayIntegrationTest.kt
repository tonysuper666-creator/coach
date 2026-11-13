package com.coach.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.server.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/** The actual Today navigation and question UI over an explicitly injected synthetic server. */
@OptIn(ExperimentalTestApi::class)
class MainlineTodayIntegrationTest {
    @Test fun phoneTodayOpensQuestionAndRestoresAnswerFromSqlite() = walk(412, 915)
    @Test fun wideTodayOpensQuestionAndRestoresAnswerFromSqlite() = walk(933, 704)

    private fun walk(width: Int, height: Int) {
        val root = Files.createTempDirectory("synthetic-today")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createDirectories(root.resolve("ledger")))
        val token = "synthetic-today-token-32-characters"
        store.enroll("phone", token)
        val actor = LedgerActor(scope, store)
        val raw = LedgerJson.parseToJsonElement(javaClass.getResource("/question-ladder/synthetic-runtime-identity-v1.json")!!.readText()).jsonObject
        val runtime = SyntheticLadderRuntime.parse(raw)
        val mainline = Mainline(MockLibrary(Files.createDirectories(root.resolve("papers"))),
            syntheticCapabilities = setOf("SYN-add"), syntheticRuntimeProvider = { course -> runtime.takeIf { it.courseId == course } })
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, pushes); mainlineRoutes(actor, pushes, mainline) }
        server.start(false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        val http = CourseHttp("http://127.0.0.1:$port", "phone", token, "SYN-COURSE-A")
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger sync in this test")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger sync")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger sync")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger sync")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger sync")
        }
        val file = root.resolve("client.sqlite")
        var driver = JdbcSqliteDriver("jdbc:sqlite:$file")
        val now = 1793188800000L
        val sha: (ByteArray) -> String = { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        fun controller() = CoachController("SYN-COURSE-A", SyncEngine(ClientDatabase(driver), "phone", transport,
            listOf("SYN-COURSE-A"), { "id" }, sha), TutorClient("http://127.0.0.1:1", "phone", token, TutorModel({ now })),
            formatClock = { "12:00" }, nowMillis = { now }, course = http)
        var c: CoachController? = null
        try {
            ClientDatabase.Schema.create(driver)
            val event = Event(1, "synthetic-outline", "SYN-COURSE-A", "outline_revised", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                    json("id" to "SYN-K1", "title" to "Synthetic counters", "week" to 1)))))
            ClientDatabase(driver).ledgerQueries.putEvent(event.seq, event.courseId, LedgerJson.encodeToString(Event.serializer(), event))
            c = controller()
            val first = c
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                setContent { CoachApp(first, dark = false) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("today-continue").fetchSemanticsNodes().isNotEmpty() }
                captureLessonEvidence("today-real-entry-${width}x$height", width, height)
                onNodeWithTag("today-continue").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-submit").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("How many objects are one and one?").assertExists()
                onNodeWithTag("mainline-option-0").performScrollTo().performClick()
                onNodeWithTag("mainline-submit").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                captureLessonEvidence("today-real-feedback-${width}x$height", width, height)
                val beforeAsk = first.sync.mainlineState("SYN-COURSE-A", "week:1")["result"]
                onNodeWithTag("mainline-ask").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("ladder-question").performTextInput("Why are two counters a pair?")
                onNodeWithText("发送").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("课程依据服务暂不可用，无法确认。").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("回到这道题").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isEmpty() }
                assertEquals(beforeAsk, first.sync.mainlineState("SYN-COURSE-A", "week:1")["result"])
            }
            val saved = first.sync.mainlineState("SYN-COURSE-A", "week:1")
            assertEquals("SYN-Q1@v2", saved.str("item"))
            assertTrue((saved["result"] as JsonObject).flag("syntheticOnly"))
            assertFalse((saved["result"] as JsonObject).flag("independentCorrect"))
            first.tutor.close()
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            c = controller()
            val reopened = c
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                setContent { CoachApp(reopened, dark = false) }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("How many objects are one and one?").assertExists()
                assertEquals(saved["result"], reopened.sync.mainlineState("SYN-COURSE-A", "week:1")["result"])
                captureLessonEvidence("today-real-restored-${width}x$height", width, height)
            }
            assertTrue(runBlocking { actor.execute { events("SYN-COURSE-A").isEmpty() } })
        } finally {
            c?.tutor?.close(); driver.close(); http.close(); server.stop(0, 500); runBlocking { actor.close() }; scope.cancel(); root.toFile().deleteRecursively()
        }
    }
}
