package com.coach.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual MainlineScreen/CourseHttp events against invented local service responses, no desktop window. */
@OptIn(ExperimentalTestApi::class)
class MainlineAskFlowTest {
    @Test fun todayEntersInOneClickWithoutReadingAndReturnsToTheSameAnswer() {
        val now = 1_794_052_800_000L
        MainlineScreenTest.FakeMac(logicalDay(now, "Europe/London")).use { mac ->
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                ClientDatabase.Schema.create(driver)
                val db = ClientDatabase(driver)
                val outline = Event(1, "synthetic-outline", "C", "outline_revised", recordedAt = now, occurredAt = now,
                    deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                        json("id" to "K", "title" to "Synthetic orchard", "week" to 1)))))
                db.ledgerQueries.putEvent(1, "C", LedgerJson.encodeToString(Event.serializer(), outline))
                val transport = object : LedgerTransport {
                    override suspend fun hello(epoch: String?): Hello = error("No ledger network")
                    override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
                    override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
                    override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
                    override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
                }
                val tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
                val c = CoachController("C", SyncEngine(db, "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
                    tutor, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
                try {
                    runSkikoComposeUiTest(size = Size(412f, 915f), density = Density(1f)) {
                        setContent { CoachTheme(false) { Surface(Modifier.fillMaxSize()) { CoachApp(c, dark = false, settings = {}) } } }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("today-continue").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("today-continue").assertIsDisplayed().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question S1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("mainline-notes").assertDoesNotExist()
                        assertEquals(0, mac.answers.size)
                        onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                        onNodeWithTag("mainline-submit").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                        val answer = c.sync.mainlineState("C", "week:1")["result"]
                        onNodeWithText("暂离", substring = true).performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("today-continue").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("today-continue").performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                        assertEquals(answer, c.sync.mainlineState("C", "week:1")["result"])
                        onNodeWithTag("mainline-option-1").assertIsSelected()
                        assertEquals(1, mac.answers.size)
                        captureLessonEvidence("today-one-tap-restored-412", 412, 915)
                        println("LADDER_TRACE today:one-click -> S1:no-reading -> submitted:choice1 -> today -> one-click-resume:S1/same-answer")
                    }
                } finally { tutor.close() }
            }
        }
    }

    @Test fun phoneDrawerClosesToTheOriginalAnswerAndRejectsLateReply() = walk(412, 915, false)
    @Test fun mediumRailClosesToTheOriginalAnswerAndRejectsLateReply() = walk(933, 704, true)
    @Test fun mediumCollapsedRailOpensDrawerAndReturnsToTheSameAnswer() = walk(933, 704, true, "v7-margin-toggle")
    @Test fun mediumFocusedReadingOpensDrawerAndReturnsToTheSameAnswer() = walk(933, 704, true, "v7-focus")
    @Test fun innerCollapsedRailOpensDrawerAndReturnsToTheSameAnswer() = walk(1280, 800, false, "v7-margin-toggle")
    @Test fun collapsingTheMediumRailKeepsTheDraftAndInFlightRequest() = transferOpenAsk(933, 704, "v7-margin-toggle")
    @Test fun focusingInnerReadingKeepsTheDraftAndInFlightRequest() = transferOpenAsk(1280, 800, "v7-focus")

    @Test fun twoFirstTriesLeadToTheirActualReferenceAndSurviveScreenReentry() {
        val now = 1_794_052_800_000L
        MainlineScreenTest.FakeMac(logicalDay(now, "Europe/London"), primers = true).use { mac ->
            SyntheticLocalLedger("C").use { local ->
                val tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
                val c = CoachController("C", local.sync, tutor, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
                local.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
                try {
                    var mounted by mutableStateOf(true)
                    runSkikoComposeUiTest(size = Size(412f, 915f), density = Density(1f)) {
                        setContent { CoachTheme(false) { Surface(Modifier.fillMaxSize()) { if (mounted)
                            MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {})
                        } } }
                        for (id in listOf("P1", "P2", "S1")) {
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question $id:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                            onNodeWithTag("mainline-dont-know").performScrollTo().performClick()
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                            if (id != "S1") onNodeWithTag("mainline-next").performScrollTo().performClick()
                        }
                        val reference = "开头『先试试』的第2题说的就是这个。"
                        onNodeWithText(reference).performScrollTo().assertIsDisplayed()
                        onNodeWithText("null 错在哪").assertDoesNotExist()
                        for (reason in listOf("sold out", "ripen after", "imported")) onNodeWithText(reason, substring = true).assertExists()
                        val saved = local.sync.mainlineState("C", "week:1")
                        val session = LedgerJson.decodeFromJsonElement<LadderSession>(saved["ladderSession"]!!)
                        assertEquals(listOf("P1", "P2", "S1"), session.responses.map { it.id })
                        assertEquals(listOf(true, true, false), session.responses.map { it.tryFirst })
                        assertTrue(session.responses.none { it.independentCorrect })
                        captureLessonEvidence("first-try-reference-412", 412, 915)
                        runOnIdle { mounted = false }; waitForIdle()
                        runOnIdle { mounted = true }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(reference).fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText(reference).performScrollTo().assertIsDisplayed()
                        onNodeWithText("null 错在哪").assertDoesNotExist()
                        val restored = local.sync.mainlineState("C", "week:1")
                        assertEquals(saved["item"], restored["item"])
                        assertEquals(saved["result"], restored["result"])
                        assertEquals(saved["ladderSession"], restored["ladderSession"])
                        assertEquals(3, mac.answers.size)
                        captureLessonEvidence("first-try-reference-restored-412", 412, 915)
                        println("LADDER_TRACE P1:try-first -> P2:try-first -> S1:reference-P2/ordinal2 -> composition-reentry:S1; no-independent-correct")
                    }
                } finally { tutor.close() }
            }
        }
    }

    private fun walk(width: Int, height: Int, dark: Boolean, hideBeforeAsk: String? = null) {
        val evidence = "ask-$width" + (hideBeforeAsk?.let { "-$it" } ?: "")
        val now = 1_794_052_800_000L
        val release = CountDownLatch(1)
        val held = CountDownLatch(1)
        MainlineScreenTest.FakeMac(logicalDay(now, "Europe/London"), explain = { wire ->
            if (wire.str("question").startsWith("Hold")) {
                held.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                json("text" to "Synthetic late response for S1", "sources" to emptyList<String>())
            } else json("text" to "Synthetic pears are counted once.",
                "sources" to JsonArray(listOf(json("docId" to "SYN-BOOK", "pdfPage" to 4))))
        }).use { mac ->
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                ClientDatabase.Schema.create(driver)
                val transport = object : LedgerTransport {
                    override suspend fun hello(epoch: String?): Hello = error("No ledger network")
                    override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
                    override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
                    override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
                    override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
                }
                val tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
                val c = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
                    tutor, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
                c.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
                try {
                    runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                        setContent { CoachTheme(dark) { Surface(Modifier.fillMaxSize()) {
                            MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {})
                        } } }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-1").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                        onNodeWithTag("mainline-submit").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                        val original = c.sync.mainlineState("C", "week:1")
                        hideBeforeAsk?.let {
                            onNodeWithTag(it).performClick()
                            onNodeWithTag("v7-margin").assertDoesNotExist()
                        }
                        onNodeWithTag("mainline-ask").performScrollTo()
                        waitForIdle()
                        val offset = c.sync.mainlineState("C", "week:1").num("quizScroll")
                        onNodeWithTag("mainline-ask").performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().size == 1 }
                        captureLessonEvidence("$evidence-open", width, height,
                            captureRoot = onNode(isRoot() and hasAnyDescendant(hasTestTag("ladder-question"))))
                        onNodeWithTag("ladder-question").performTextInput("Explain synthetic pears")
                        onNodeWithText("发送").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic pears are counted once.").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("SYN-BOOK · PDF 第 4 页").performScrollTo().assertIsDisplayed()
                        assertEquals("S1", mac.explains.single().str("itemId"))
                        captureLessonEvidence("$evidence-reply", width, height,
                            captureRoot = onNode(isRoot() and hasAnyDescendant(hasTestTag("ladder-question"))))
                        onNodeWithText("回到这道题").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isEmpty() }
                        val returned = c.sync.mainlineState("C", "week:1")
                        for (key in listOf("item", "choice", "result", "ladderSession")) assertEquals(original[key], returned[key], key)
                        assertEquals(offset, returned.num("quizScroll"))
                        onNodeWithTag("mainline-option-1").assertIsSelected()
                        captureLessonEvidence("$evidence-returned", width, height)
                        onNodeWithTag("mainline-ask").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("ladder-question").performTextInput("Hold synthetic response")
                        onNodeWithText("发送").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { held.count == 0L }
                        val oldRequest = mac.explains.last().str("requestId")
                        onNodeWithText("回到这道题").performScrollTo().performClick()
                        onNodeWithTag("mainline-next").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                        release.countDown()
                        waitUntil(timeoutMillis = 10_000) { oldRequest in mac.cancellations }
                        onNodeWithTag("mainline-ask").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isNotEmpty() }
                        assertEquals("", onNodeWithTag("ladder-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                        onNodeWithText("Synthetic late response for S1").assertDoesNotExist()
                        assertEquals("D1", c.sync.mainlineState("C", "week:1").str("item"))
                        assertFalse(c.sync.mainlineState("C", "week:1")["result"] is kotlinx.serialization.json.JsonObject)
                        captureLessonEvidence("$evidence-next-after-late", width, height,
                            captureRoot = onNode(isRoot() and hasAnyDescendant(hasTestTag("ladder-question"))))
                        println("LADDER_TRACE viewport=${width}x$height layout=${hideBeforeAsk ?: "expanded"} S1:submitted -> ask:S1 -> close:S1/choice1/result-preserved -> delayed:S1 -> D1:unanswered; late-reply-hidden")
                    }
                } finally { release.countDown(); tutor.close() }
            }
        }
    }

    private fun transferOpenAsk(width: Int, height: Int, hideControl: String) {
        val now = 1_794_052_800_000L
        val release = CountDownLatch(1)
        val held = CountDownLatch(1)
        val draft = "Hold synthetic draft across layout"
        val reply = "Synthetic answer after changing layout"
        MainlineScreenTest.FakeMac(logicalDay(now, "Europe/London"), explain = {
            held.countDown()
            check(release.await(20, TimeUnit.SECONDS))
            json("text" to reply, "sources" to emptyList<String>())
        }).use { mac ->
            SyntheticLocalLedger("C").use { local ->
                val tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
                val c = CoachController("C", local.sync, tutor, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
                local.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
                try {
                    runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                        setContent { CoachTheme(false) { Surface(Modifier.fillMaxSize()) {
                            MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {})
                        } } }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-1").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                        onNodeWithTag("mainline-submit").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }

                        // Move an unsent draft, then a live HTTP request, from the rail to a sheet.
                        for (send in listOf(false, true)) {
                            onNodeWithTag("v7-margin").assertExists()
                            onNodeWithTag("mainline-ask").performScrollTo().performClick()
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().size == 1 }
                            onNodeWithTag("ladder-question").performTextInput(draft)
                            if (send) {
                                onNodeWithText("发送").performScrollTo().performClick()
                                waitUntil(timeoutMillis = 10_000) { held.count == 0L }
                            }
                            waitForIdle()
                            val original = local.sync.mainlineState("C", "week:1")
                            val requestId = mac.explains.singleOrNull()?.str("requestId")
                            onNodeWithTag(hideControl).performClick()
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().size == 1 }
                            onNodeWithTag("v7-margin").assertDoesNotExist()
                            assertEquals(draft, onNodeWithTag("ladder-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                            assertEquals(if (send) 1 else 0, mac.explains.size)
                            assertFalse(requestId in mac.cancellations, "A layout change must not cancel the live request")
                            for (key in listOf("item", "choice", "result", "ladderSession", "quizScroll"))
                                assertEquals(original[key], local.sync.mainlineState("C", "week:1")[key], key)
                            if (send) {
                                onNodeWithText("正在查课件和教材依据…").assertExists()
                                release.countDown()
                                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(reply).fetchSemanticsNodes().isNotEmpty() }
                                onNodeWithText(reply).performScrollTo().assertIsDisplayed()
                                assertEquals(1, mac.explains.size, "Moving the presenter must not restart HTTP")
                                assertEquals("S1", mac.explains.single().str("itemId"))
                            }
                            captureLessonEvidence("ask-$width-$hideControl-transfer-${if (send) "reply" else "draft"}", width, height,
                                captureRoot = onNode(isRoot() and hasAnyDescendant(hasTestTag("ladder-question"))))
                            onNodeWithText("回到这道题").performScrollTo().performClick()
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isEmpty() }
                            for (key in listOf("item", "choice", "result", "ladderSession", "quizScroll"))
                                assertEquals(original[key], local.sync.mainlineState("C", "week:1")[key], key)
                            onNodeWithTag("mainline-option-1").assertIsSelected()
                            onNodeWithTag(hideControl).performClick()
                        }
                        waitUntil(timeoutMillis = 10_000) { mac.explains.single().str("requestId") in mac.cancellations }
                        onNodeWithTag("mainline-ask").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().size == 1 }
                        assertEquals("", onNodeWithTag("ladder-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                        onNodeWithText(reply).assertDoesNotExist()
                        assertEquals(1, mac.answers.size)
                        println("LADDER_TRACE viewport=${width}x$height control=$hideControl S1:submitted -> rail:draft -> sheet:same-draft -> close -> rail:one-request -> sheet:same-request/reply -> close/reopen:empty; question/choice/result/ladder/scroll-preserved")
                    }
                } finally { release.countDown(); tutor.close() }
            }
        }
    }
}
