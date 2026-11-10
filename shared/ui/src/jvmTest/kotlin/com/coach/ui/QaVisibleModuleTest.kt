package com.coach.ui

import androidx.compose.foundation.layout.*
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
import kotlinx.serialization.json.JsonArray
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Render actual screens using a synthetic course with the real seed's numbered-module shape. */
@OptIn(ExperimentalTestApi::class)
class QaVisibleModuleTest {
    private fun checkPage(page: String) {
        val now = 1_794_225_600_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val events = listOf(Event(1, "qa1", "SYNTHETIC", "outline_revised", recordedAt = now,
            occurredAt = now, deviceId = "qa", actor = "SYSTEM", payload = json(
                "modules" to JsonArray(listOf(json("id" to "m3", "title" to "03 合成课程"))),
                "knowledgePoints" to JsonArray(listOf(json("id" to "A", "moduleId" to "m3", "title" to "【W3·slides】合成甲"))))))
        events.forEach { db.ledgerQueries.putEvent(it.seq, it.courseId, LedgerJson.encodeToString(Event.serializer(), it)) }
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No network")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        val c = CoachController("SYNTHETIC", SyncEngine(db, "qa", transport, listOf("SYNTHETIC"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { now })
        val view = CourseView(events, project(events))
        try {
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(1000.dp, 900.dp)) {
                    when (page) {
                        "today" -> TodayScreen(c, view, TutorUiState(), null, {}, { _, _ -> })
                        "route" -> RouteScreen(c, view, null, { _, _ -> })
                        else -> StudyScreen(c, view.kp("A")!!, view, TutorUiState(), autoStart = false, onBack = {})
                    }
                } } } }
                waitForIdle()
                val out = File("build/qa-copy").also { it.mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$page.png"))
                File(out, "$page-semantics.txt").writeText(onRoot(useUnmergedTree = true).printToString())
                onNodeWithText("03 合成课程", substring = true, useUnmergedTree = true).assertDoesNotExist()
            }
        } finally { tutor.close(); driver.close() }
    }
    @Test fun todayOmitsTheInternalModuleNumber() = checkPage("today")
    @Test fun routeOmitsTheInternalModuleNumber() = checkPage("route")
    @Test fun studyOmitsTheInternalModuleNumber() = checkPage("study")
}
