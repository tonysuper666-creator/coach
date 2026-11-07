package com.coach.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.domain.math.MathDelimiters
import com.coach.domain.*
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.protocol.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WeekMistakePreviewTest {
    @Test fun imageSourcesAreReplacedAndLongStemsAreBoundedWithoutSplittingFormula() {
        val data = "data:image/png;base64," + "A".repeat(1200)
        assertEquals("合成图 ［图片］ 后文", mistakePreview("合成图 ![图]($data) 后文"))
        assertEquals("［图片］", mistakePreview(data))
        val formula = "\$\\frac{\\Delta Q}{\\Delta P}\$"
        val preview = mistakePreview("合成题 $formula " + "长题".repeat(300))
        assertTrue(preview.length <= 181)
        assertTrue(preview.endsWith("…"))
        assertEquals("\\frac{\\Delta Q}{\\Delta P}", MathDelimiters.tokenize(preview).segments.single { it.math != null }.math)
        assertFalse(mistakePreview("a".repeat(175) + formula).contains("\\frac"))
    }

    @Test fun previewsAreScrollableAndOpenTheSelectedOriginalAttempt() = runComposeUiTest {
        val data = "data:image/png;base64," + "A".repeat(1200)
        val stems = listOf("合成公式 \$x^2\$", "合成图片 $data", "合成长题 " + "题干".repeat(300), "合成后续 S4", "合成后续 S5")
        var selected: String? = null
        val renderedMath = CopyOnWriteArrayList<String>()
        val math = MathAssets { tex, _ -> renderedMath.add(tex); null }
        setContent {
            CoachTheme(false) {
                Surface {
                    LazyColumn(Modifier.size(360.dp, 420.dp).testTag("mistakes")) {
                        items(stems.indices.toList()) { index ->
                            WeekMistakeItem(stems[index], "attempt-$index", math, true) { selected = it }
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { renderedMath.contains("x^2") }
        onNodeWithText("合成图片 ［图片］", substring = true).assertExists()
        onNodeWithTag("week-mistake-attempt-1").assertIsDisplayed()
        onNodeWithText("data:image", substring = true).assertDoesNotExist()
        onNodeWithText("点按查看完整题目").assertExists()
        onNodeWithTag("mistakes").performScrollToIndex(4)
        onNodeWithTag("week-mistake-attempt-4").assertIsDisplayed()
        onNodeWithText("合成后续 S5").assertIsDisplayed().performClick()
        assertEquals("attempt-4", selected)
        onNodeWithTag("mistakes").performScrollToIndex(1)
        onNodeWithText("合成图片 ［图片］", substring = true).performClick()
        assertEquals("attempt-1", selected)
    }

    @Test fun screenSendsSelectedAttemptAndShowsTheFullRetriedQuestion() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val db = ClientDatabase(driver)
        val stem = "合成完整原题 " + "题干".repeat(150)
        fun event(seq: Long, type: String, payload: kotlinx.serialization.json.JsonObject, thread: String? = null) =
            Event(seq, "e$seq", "C", type, threadId = thread, recordedAt = 1, occurredAt = 1, deviceId = "synthetic", actor = "SYSTEM", payload = payload)
        val posed = json("attemptId" to "original", "questionId" to "q1", "kpIds" to listOf("k1"), "stem" to stem,
            "format" to "SINGLE_CHOICE", "keyState" to "VERIFIED", "groupId" to "old")
        val facts = listOf(
            event(1, "outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "k1", "title" to "【W1】合成要点"))))),
            event(2, "question_posed", posed), event(3, "answer_submitted", json("attemptId" to "original", "answer" to "A")),
            event(4, "attempt_graded", json("attemptId" to "original", "score" to 0, "max" to 1)))
        val retry = event(5, "question_posed", json("attemptId" to "retry", "questionId" to "q1", "stem" to stem,
            "format" to "SINGLE_CHOICE", "groupId" to "retry-group"), "retry-thread")
        val hello = Hello("synthetic", 4, 0, 0)
        val sent = AtomicReference<CommandBody?>()
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?) = hello
            override suspend fun snapshot(course: String) = Snapshot(hello, course, facts, project(facts))
            override suspend fun events(course: String, after: Long) = EventsPage("synthetic", emptyList(), 4)
            override suspend fun command(command: WireCommand): Receipt {
                sent.set(LedgerJson.decodeFromString(CommandBody.serializer(), command.payloadBytes))
                return Receipt(command.commandId, "OK", result = json("threadId" to "retry-thread"), epoch = "synthetic", backedUpThroughSeq = 0)
            }
            override suspend fun watch(onLedger: suspend () -> Unit) = error("not used")
        }
        val tutor = TutorClient("http://127.0.0.1:18941", "synthetic", "synthetic", TutorModel({ 1L }))
        val controller = CoachController("C", SyncEngine(db, "synthetic", transport, listOf("C"), { "command-1" }, { "hash" }), tutor, formatClock = { "" })
        try {
            runComposeUiTest {
                var view by mutableStateOf(CourseView(facts, project(facts)))
                setContent { CoachTheme(false) { WeekMistakesScreen(controller, view, 1, onBack = {}) } }
                onNodeWithText("合成完整原题", substring = true).performClick()
                waitUntil(timeoutMillis = 10_000) { sent.get() != null }
                assertEquals("retry_week_mistake", sent.get()!!.type)
                assertEquals("original", sent.get()!!.payload.str("attemptId"))
                assertEquals(1, sent.get()!!.payload.num("week").toInt())
                view = CourseView(facts + retry, project(facts + retry))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(stem).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText(stem).assertExists()
            }
        } finally { tutor.close(); driver.close() }
    }
}
