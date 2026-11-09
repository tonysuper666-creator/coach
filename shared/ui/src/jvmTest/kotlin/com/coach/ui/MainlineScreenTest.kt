package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
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

    private class FakeMac(today: Long, private val loseFirstReply: Boolean = false, private val includeNotes: Boolean = false, private var initialFailures: Int = 0, private val anchor: String = "", private val setupEmphasis: Boolean = false) : AutoCloseable {
        @Volatile var offline = false
        val answers = CopyOnWriteArrayList<JsonObject>()
        /** Every reader request the cited-source component makes through the real CourseHttp. */
        val readerRequests = CopyOnWriteArrayList<String>()
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
                if (offline) {
                    reply(ex, """{"error":"Synthetic offline"}""", 503)
                    return@createContext
                }
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
                            "explanation":$explanation,"source":"Synthetic lecture 1","anchor":"$anchor","kpId":"K","dueDay":${if (queued) today + 1 else "null"},
                            "mistakeWeek":${if (queued) 1 else "null"},"next":$next,"coverage":${cov(if (status == "mastered") 1 else 0, i + 1)}}""")
                    }
                    path.endsWith("/mainline/1") && initialFailures-- > 0 -> reply(ex, """{"error":"Synthetic Mac temporarily unavailable"}""", 503)
                    path.endsWith("/mainline/1") -> reply(ex, """{"week":1,"title":"Synthetic orchard","coverage":${cov(0, 0)},"items":[""" +
                        items.joinToString(",") { (id, tier) -> """{"id":"$id","tier":"$tier","stem":"Synthetic orchard question $id: which statement about the orchard holds?",
                            "options":["Apples are grown there","Pears are grown there","Plums ripen early","Figs are grown there"],"status":"new","keyState":"VERIFIED","factIds":["F-$id"]}""" } + "]" + (if (includeNotes) ""","notes":[{"section_id":"W01-basics","title":"Synthetic basics","body":"Synthetic apples are sold out. " ,"fact_ids":["F-S1","F-D1"],"sources":["Synthetic lecture 1"]},{"section_id":"W01-bridge","title":"Synthetic bridge notes","body":"Synthetic pears connect the orchard to the next topic.","fact_ids":["F-B1"],"sources":["Synthetic lecture 1"]}],"emphasis":[{"fact_id":"${if (setupEmphasis) "F-S1" else "F-B1"}","week":1,"recording_id":"T1","recording_kind":"tutorial","timestamp":"02:01:03","summary":"Synthetic pears were emphasized."}]""" else "") + "}")
                    else -> reply(ex, """{"weeks":[{"week":1,"title":"Synthetic orchard","coverage":${cov(0, 0)}}],"invalid":[]}""")
                }
            }
            createContext("/v1/courses/C/reader") { ex ->
                val path = ex.requestURI.path
                readerRequests += ex.requestURI.toString()
                if (path.endsWith("/image")) {
                    val png = syntheticReaderPage("Orchard")
                    ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) }
                    return@createContext
                }
                ex.requestBody.readBytes()
                reply(ex, when {
                    path.endsWith("/resolve") -> """{"documentId":"doc","version":"v1","pdfPage":2,"anchorStatus":"page_only"}"""
                    path.endsWith("/boxes") -> """{"version":"v1","pdfPage":2,"width":300,"height":400,"words":[
                        {"x0":30,"y0":40,"x1":80,"y1":55,"text":"Alpha"},{"x0":85,"y0":40,"x1":120,"y1":55,"text":"beta"}]}"""
                    path.endsWith("/progress") -> """{"revision":1}"""
                    path.endsWith("/study") -> """{"facts":[],"questions":[],"unlocated":0}"""
                    path.endsWith("/state") -> """{"document":{"version":"v1"},"progress":null,"notes":[]}"""
                    else -> """{"documents":[{"id":"doc","title":"Synthetic reading","version":"v1","pages":4}]}"""
                })
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
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-notes").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-skip").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("铺垫题", substring = true).fetchSemanticsNodes().isNotEmpty() }

                if (retryChoice != null) {
                    onNodeWithTag("mainline-option-$retryChoice").performClick()
                    onNodeWithTag(if (firstGuessed) "mainline-guess" else "mainline-submit").performClick()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic response lost after commit", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("mainline-submit").assertIsNotEnabled()
                    onNodeWithTag("mainline-guess").assertIsNotEnabled()
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(2, mac.answers.size)
                    assertEquals(mac.answers[0]["submissionId"], mac.answers[1]["submissionId"])
                    assertEquals(retryChoice, mac.answers[0].getValue("choice").jsonPrimitive.int)
                    assertEquals(retryChoice, mac.answers[1].getValue("choice").jsonPrimitive.int)
                    assertEquals(firstGuessed, mac.answers[0].getValue("guessed").jsonPrimitive.boolean)
                    assertEquals(firstGuessed, mac.answers[1].getValue("guessed").jsonPrimitive.boolean)
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
                try {
                    onNodeWithText("为什么对", substring = true).assertIsDisplayed()
                } catch (failure: AssertionError) {
                    runCatching {
                        capture("mainline-explanation-failure-${width}x$height")
                        File("build/screens/mainline-explanation-failure-${width}x$height.txt").writeText(
                            onRoot(useUnmergedTree = true).printToString(maxDepth = 50))
                    }.onFailure { failure.addSuppressed(it) }
                    throw failure
                }
                for (reason in listOf("sold out", "ripen after", "imported")) onNodeWithText(reason, substring = true).assertExists()
                capture("mainline-explained-$width")
                onNodeWithText("答错了。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已排进复习：明天再看一次。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已进第 1 周错题本", substring = true).performScrollTo().assertIsDisplayed()
                // The source is now the reader citation button (label "出处：" + source), so the exact label is asserted on that node.
                onNodeWithTag("private-citation").performScrollTo().assertTextEquals("出处：Synthetic lecture 1, slide 4")
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

    /**
     * 复习页的主线入口：课程回答一到，入口就要显示当前周并能点进去。设备烟测在像素已经画出「继续第 1 周」时
     * 读到的仍是「正在连接…」，所以这个标签必须来自回答到达后才出现的节点，而不是把一个禁用按钮原地改名。
     */
    @Test fun reviewEntryShowsTheCurrentWeekOnceTheCourseAnswers() = FakeMac(today).use { mac ->
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
        var opened: Pair<Int?, Boolean>? = null
        try {
            runComposeUiTest {
                setContent {
                    CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 900.dp)) {
                        ReviewScreen(c, view, { _, _ -> }, {}, onOpenMainline = { number, start -> opened = number to start })
                    } } }
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("继续第 1 周").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("正在连接…").assertDoesNotExist()
                onNodeWithTag("mainline-continue").assertIsEnabled().performClick()
                assertEquals(1 to true, opened)
                onNodeWithText("看本周要点").assertExists()
                capture("review-mainline-entry")
            }
        } finally { tutor.close(); driver.close() }
    }

    @Test fun notesFilterAndExactAnsweredQuestionSurviveAnActualDatabaseReopen() = FakeMac(today, includeNotes = true, initialFailures = 1).use { mac ->
        val file = File.createTempFile("mainline-resume-", ".sqlite")
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        val tutor = TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now }))
        fun controller(driver: JdbcSqliteDriver) = CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            tutor, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
        val view = CourseView(emptyList(), project(emptyList()))
        var driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            ClientDatabase.Schema.create(driver)
            var c = controller(driver)
            var saved: JsonObject? = null
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-week-1").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-week-1").performClick()
                // The first GET fails; retry must recover without another tap.
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic Mac temporarily unavailable").fetchSemanticsNodes().isNotEmpty() }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-notes").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("○ Synthetic bridge notes").performScrollTo().performClick()
                onNodeWithTag("mainline-emphasis").performScrollTo()
                waitForIdle()
                val readingBeforeStar = c.sync.mainlineState("C", "week:1")
                onNodeWithTag("mainline-emphasis").performClick()
                onNodeWithText("Synthetic pears were emphasized.").assertIsDisplayed()
                onNodeWithText("02:01:03", substring = true).assertExists()
                onNodeWithTag("mainline-emphasis-close").performClick()
                waitForIdle()
                assertEquals(readingBeforeStar, c.sync.mainlineState("C", "week:1"))
                onNodeWithTag("mainline-section-quiz").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("桥接题 · 第 1/1 题").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                onNodeWithTag("mainline-emphasis").performScrollTo()
                waitForIdle()
                val quizBeforeStar = c.sync.mainlineState("C", "week:1")
                onNodeWithTag("mainline-emphasis").performClick()
                onNodeWithText("Synthetic pears were emphasized.").assertIsDisplayed()
                onNodeWithTag("mainline-emphasis-close").performClick()
                waitForIdle()
                assertEquals(quizBeforeStar, c.sync.mainlineState("C", "week:1"))
                onNodeWithTag("mainline-option-1").assertIsSelected()
                onNodeWithTag("mainline-guess").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("答对了，但标了猜的：不算掌握。").performScrollTo().assertIsDisplayed()
                saved = c.sync.mainlineState("C", "week:1")
                assertEquals("B1", saved!!.str("item"))
                assertEquals("W01-bridge", saved!!.str("filter"))
                assertNotNull(saved!!["result"])
            }
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            c = controller(driver)
            assertEquals(saved, c.sync.mainlineState("C", "week:1"))
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}) } } }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("桥接题 · 第 1/1 题").performScrollTo().assertIsDisplayed()
                onNodeWithTag("mainline-option-1").assertIsSelected()
                onNodeWithTag("mainline-return-section").performScrollTo().performClick()
                onNodeWithText("Synthetic pears connect", substring = true).performScrollTo().assertIsDisplayed()
                waitForIdle()
                assertEquals("notes", c.sync.mainlineState("C", "week:1").str("mode"))
                assertEquals("W01-bridge", c.sync.mainlineState("C", "week:1").str("section"))
            }
            driver.close()
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            c = controller(driver)
            mac.offline = true
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { MainlineScreen(c, view, {}, {}, {}, {}) } } }
                onNodeWithTag("mainline-notes").performScrollTo().assertIsDisplayed()
                onNodeWithText("Synthetic pears connect", substring = true).performScrollTo().assertIsDisplayed()
                onNodeWithTag("mainline-emphasis").performScrollTo().performClick()
                onNodeWithText("Synthetic pears were emphasized.").assertIsDisplayed()
                onNodeWithTag("mainline-emphasis-close").performClick()
            }
        } finally { driver.close(); tutor.close(); file.delete() }
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = File("build/screens").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        // Keep successful geometry too: a visible heading can coexist with the body below the viewport.
        File(dir, "$name.txt").writeText(onRoot(useUnmergedTree = true).printToString(maxDepth = 50))
    }

    @Test fun phoneWalksOneWeekWithExplanationsOnTheSamePage() = walk(412, 915)
    @Test fun desktopWalksOneWeekWithExplanationsOnTheSamePage() = walk(1000, 800)
    // 1972×1248 pixels at Android density 320 is 986×624 dp, before system insets.
    // No performScrollTo before the explanation assertion: submitting must expose it automatically.
    @Test fun outerLandscapeShowsExplanationAutomaticallyAtDensity320() = walk(986, 624)
    @Test fun outerLandscapeSubmissionRevealsExplanationAndCitationAtDensity320() = FakeMac(today, includeNotes = true, setupEmphasis = true).use { mac ->
        val c = controller(mac)
        c.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
        val events = listOf(Event(1, "synthetic-outline", "C", "outline_revised", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(
                json("id" to "K", "title" to "Synthetic orchard point a", "week" to 1))))))
        val view = CourseView(events, project(events))
        // Match QA's 1972x1248 capture at density 320: the system bars plus connection banner
        // leave the desk at y=146..1200px. The full 986x624dp walk above omits those insets.
        runSkikoComposeUiTest(size = Size(1972f, 1248f), density = Density(2f)) {
            setContent { CoachTheme(false) { Surface { Box(Modifier.fillMaxSize().padding(top = 73.dp, bottom = 24.dp)) {
                CompositionLocalProvider(com.coach.ui.v7.LocalDeskNavigation provides { vertical -> DeskNavigation(AppTab.REVIEW, vertical) {} }) {
                    MainlineScreen(c, view, {}, {}, {}, {})
                }
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-emphasis").assertIsDisplayed()
            onNodeWithTag("mainline-option-0").assertIsDisplayed().performClick()
            onNodeWithTag("mainline-submit").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            val explanation = onNodeWithText("为什么对", substring = true)
            val citation = onNodeWithTag("private-citation")
            val scroll = onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("mainline-heading")))
            try {
                waitUntil(timeoutMillis = 15_000) {
                    val viewport = scroll.fetchSemanticsNode().boundsInRoot
                    val source = citation.fetchSemanticsNode().boundsInRoot
                    // The last 40dp of the scroller is covered by the fixed action bar's fade.
                    explanation.isDisplayed() && citation.isDisplayed() && source.top >= viewport.top && source.bottom <= viewport.bottom - 80f
                }
                explanation.assertIsDisplayed()
                citation.assertIsDisplayed()
                capture("outer-feedback-1972x1248")
            } catch (failure: Throwable) {
                capture("outer-feedback-1972x1248-failure")
                throw failure
            }
            // Once the reader scrolls further down, returning from notes must retain that position.
            onNodeWithText("答错了。").performScrollTo().assertIsDisplayed()
            val beforeNotes = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            onNodeWithText("回到要点").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-notes").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-skip").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertEquals(beforeNotes, scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
                "returning to a completed answer must preserve the reader's scroll")
            onNodeWithTag("mainline-option-0").assertIsSelected()
        }
    }
    @Test fun lostWrongReplyAutomaticallyResendsImmutableChoice() = walk(412, 915, 0, false)
    @Test fun lostGuessedReplyAutomaticallyResendsRecordedGuess() = walk(1000, 800, 1, true)
    @Test fun lostCorrectReplyAutomaticallyResendsRecordedPass() = walk(412, 915, 1, false)
    @Test fun lostWrongReplyMarksTheRecordedChoiceAfterChangingBothChoiceAndGuess() = walk(412, 915, 0, false)
    @Test fun lostGuessedReplyKeepsTheRecordedGuessAfterAnUnguessedRetry() = walk(1000, 800, 1, true)
    @Test fun lostCorrectReplyKeepsTheRecordedPassAfterAGuessedRetry() = walk(412, 915, 1, false)

    private fun controller(mac: FakeMac): CoachController {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network in this test")
            override suspend fun snapshot(course: String): Snapshot = error("No network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No network")
            override suspend fun command(command: WireCommand): Receipt = error("No network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No network")
        }
        return CoachController("C", SyncEngine(ClientDatabase(driver), "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
            TutorClient("http://127.0.0.1:19291", "synthetic", "synthetic", TutorModel({ now })), formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
    }

    @Test fun pagePracticeShowsOnlyThePagesQuestionsAndReturns() = FakeMac(today, false).use { mac ->
        val c = controller(mac)
        val original = buildJsonObject { put("mode", "quiz"); put("item", "S1"); put("choice", 0); put("quizScroll", 451) }
        c.sync.saveMainlineState("C", original, "week:1")
        val view = CourseView(emptyList(), project(emptyList()))
        var closed = 0
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PagePracticeBody(c, view, 1, setOf("D1"), onClose = { closed++ }) } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("question D1", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithText("question S1", substring = true).assertCountEquals(0)
            onAllNodesWithText("question B1", substring = true).assertCountEquals(0)
            onNodeWithTag("mainline-option-2").performClick()
            onNodeWithText("← 回到阅读").performClick()
            assertEquals(1, closed)
            assertEquals(original, c.sync.mainlineState("C", "week:1"), "page practice must preserve the underlying question, choice and scroll")
        }
    }

    @Test fun pagePracticeNeverPretendsWhenQuestionsAreMissingOrNoLocalRecord() = FakeMac(today, false).use { mac ->
        val c = controller(mac)
        val view = CourseView(emptyList(), project(emptyList()))
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PagePracticeBody(c, view, 1, setOf("GONE"), onClose = {}) } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("在这一周的选择题里找不到", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PagePracticeBody(c, null, 1, setOf("D1"), onClose = {}) } } } }
            onNodeWithText("暂时不能开始练习", substring = true).assertExists()
        }
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 800.dp)) { PagePracticeBody(c, view, 1, emptySet(), onClose = {}) } } } }
            onNodeWithText("本页没有登记的题", substring = true).assertExists()
        }
    }

    @Test fun answerAnchorTravelsThroughMainlineScreenToTheCitedPageHighlight() = FakeMac(today, false, anchor = "Alpha beta").use { mac ->
        val c = controller(mac)
        val view = CourseView(emptyList(), project(emptyList()))
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(412.dp, 915.dp)) {
                MainlineScreen(c, view, onBack = {}, onOpenSteps = {}, onOpenEssay = {}, onReviewWeek = {})
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-week-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-week-1").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-notes").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-skip").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("铺垫题", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-option-0").performClick()
            onNodeWithTag("mainline-submit").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-citation").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("private-highlight-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-label").assertTextContains("第 2 页", substring = true)
            onNodeWithTag("private-highlight-status").assertTextContains("已在页上标出", substring = true)
            ImageIO.write(onNodeWithTag("private-page-label").onParent().captureToImage().toAwtImage(), "png", File("build/screens/mainline-v7-cited-page.png").also { it.parentFile.mkdirs() })
            onNodeWithTag("private-document-back").performClick()
            onNodeWithTag("private-citation").assertExists()
            onNodeWithTag("v7-desk").assertExists()
            onNodeWithTag("mainline-option-0").assertIsSelected()
            capture("mainline-v7-answer-after-reader-return")
        }
        assertTrue(mac.readerRequests.any { it.contains("/resolve?source=") }, "the cited source went through the reader resolve route")
        assertTrue(mac.readerRequests.any { it.contains("/doc/pages/2/boxes") && it.contains("version=v1") }, "coordinates were requested for the resolved document, page and version: ${mac.readerRequests}")
    }
}
