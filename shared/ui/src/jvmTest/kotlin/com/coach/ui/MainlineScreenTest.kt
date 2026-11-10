package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
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
    private val longCorrect = "Synthetic pears grow in the orchard; this explanation remains available in full. ".repeat(40)

    private class FakeMac(today: Long, private val loseFirstReply: Boolean = false, private val includeNotes: Boolean = false, private var initialFailures: Int = 0, private val anchor: String = "", private val setupEmphasis: Boolean = false,
                          source: String = "Synthetic lecture 1, slide 4", private val strictResolve: Boolean = false,
                          correct: String = "Synthetic pears are the only fruit grown in the synthetic orchard, so this statement matches the lecture example.") : AutoCloseable {
        @Volatile var offline = false
        val answers = CopyOnWriteArrayList<JsonObject>()
        /** Every reader request the cited-source component makes through the real CourseHttp. */
        val readerRequests = CopyOnWriteArrayList<String>()
        private val recorded = mutableMapOf<String, JsonObject>()
        private fun cov(mastered: Int, answered: Int) = """{"facts":3,"factsWithQuestions":3,"factsMastered":$mastered,"questions":3,"answered":$answered,"mastered":$mastered,"keysPending":0}"""
        private val explanation = """{"correct":${JsonPrimitive(correct)},
            "options":{"0":"Synthetic apples were sold out before the season started, so they cannot be the answer.",
                       "2":"Synthetic plums ripen after the period described in the question.",
                       "3":"Synthetic figs are imported, not grown in the orchard."},
            "background":"The synthetic orchard story sets up the supply example used later in the week.","source":"$source"}"""
        private val items = listOf("S1" to "setup", "D1" to "detail", "B1" to "bridge")
        private fun weeklyBody(): JsonObject {
            val questions = items.map { (id, tier) ->
                val metadata = LadderQuestion(id, "K", if (id == "B1") "W01-bridge" else "W01-basics",
                    when (tier) { "setup" -> "introduction"; "bridge" -> "application"; else -> "concept" },
                    if (id == "S1") 2 else 0, "synthetic-orchard", "synthetic-$id", eligible = true)
                json("id" to id, "tier" to tier, "stem" to "Synthetic orchard question $id: which statement about the orchard holds?",
                    "options" to listOf("Apples are grown there", "Pears are grown there", "Plums ripen early", "Figs are grown there"),
                    "status" to "new", "keyState" to "VERIFIED", "factIds" to listOf("F-$id"), "ladder" to LedgerJson.encodeToJsonElement(metadata))
            }
            val body = json("week" to 1, "learning" to true, "title" to "Synthetic orchard", "coverage" to LedgerJson.parseToJsonElement(cov(0, 0)), "items" to JsonArray(questions))
            if (!includeNotes) return body
            return JsonObject(body + mapOf(
                "notes" to JsonArray(listOf(
                    json("section_id" to "W01-basics", "title" to "Synthetic basics", "body" to "Synthetic apples are sold out. ", "fact_ids" to listOf("F-S1", "F-D1"), "sources" to listOf("Synthetic lecture 1")),
                    json("section_id" to "W01-bridge", "title" to "Synthetic bridge notes", "body" to "Synthetic pears connect the orchard to the next topic.", "fact_ids" to listOf("F-B1"), "sources" to listOf("Synthetic lecture 1")))),
                "emphasis" to JsonArray(listOf(json("fact_id" to if (setupEmphasis) "F-S1" else "F-B1", "week" to 1,
                    "recording_id" to "T1", "recording_kind" to "tutorial", "timestamp" to "02:01:03", "summary" to "Synthetic pears were emphasized.")))))
        }
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
                        val right = a["choice"]!!.jsonPrimitive.int == 1 && !a.flag("dontKnow")
                        val status = if (!right) "missed" else if (guessed) "guessed" else "mastered"
                        val i = items.indexOfFirst { it.first == id }
                        val next = items.getOrNull(i + 1)?.first?.let { "\"$it\"" } ?: "null"
                        val queued = status != "mastered"
                        reply(ex, """{"itemId":"$id","answer":1,"choice":${a["choice"]},"guessed":$guessed,"keyState":"VERIFIED","status":"$status","tier":"${items[i].second}",
                            "independentCorrect":${right && !guessed},"remember":"Synthetic memory for $id.","dontKnow":${a.flag("dontKnow")},
                            "explanation":$explanation,"source":"Synthetic lecture 1","anchor":"$anchor","kpId":"K","dueDay":${if (queued) today + 1 else "null"},
                            "mistakeWeek":${if (queued) 1 else "null"},"next":$next,"coverage":${cov(if (status == "mastered") 1 else 0, i + 1)}}""")
                    }
                    path.endsWith("/mainline/1") && initialFailures-- > 0 -> reply(ex, """{"error":"Synthetic Mac temporarily unavailable"}""", 503)
                    path.endsWith("/mainline/1") -> reply(ex, weeklyBody().toString())
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
                    // Strict mode mirrors the server: one exact registered citation resolves; the slides of week 1 are not registered.
                    path.endsWith("/resolve") && strictResolve -> {
                        if (java.net.URLDecoder.decode(ex.requestURI.rawQuery, "UTF-8") == "source=教材 p.8") """{"documentId":"doc","version":"v1","pdfPage":39,"anchorStatus":"page_only"}"""
                        else { reply(ex, "{}", 422); return@createContext }
                    }
                    path.endsWith("/resolve") -> """{"documentId":"doc","version":"v1","pdfPage":2,"anchorStatus":"page_only"}"""
                    path.endsWith("/boxes") -> """{"version":"v1","pdfPage":2,"width":300,"height":400,"words":[
                        {"x0":30,"y0":40,"x1":80,"y1":55,"text":"Alpha"},{"x0":85,"y0":40,"x1":120,"y1":55,"text":"beta"}]}"""
                    path.endsWith("/progress") -> """{"revision":1}"""
                    path.endsWith("/study") -> """{"facts":[],"questions":[],"unlocated":0}"""
                    path.endsWith("/state") -> """{"document":{"version":"v1"},"progress":null,"notes":[]}"""
                    else -> """{"documents":[{"id":"doc","title":"Synthetic reading","version":"v1","pages":${if (strictResolve) 60 else 4}}]}"""
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
                onNodeWithText("考点 3 · 有题 3 · 会了 0").assertIsDisplayed()
                capture("mainline-weeks-$width")
                onNodeWithTag("mainline-week-1").performClick()
                awaitQuestion("S1")
                onNodeWithTag("mainline-notes").assertDoesNotExist()

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
                    val outcome = if (retryChoice == 0) "答错。" else if (firstGuessed) "猜对：不算会。" else "答对。"
                    onNodeWithText(outcome).performScrollTo().assertIsDisplayed()
                    assertFeedbackTextReadable(outcome)
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
                onNodeWithText("答错。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已排进复习：明天再看一次。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已进第 1 周错题本", substring = true).performScrollTo().assertIsDisplayed()
                // The source is now the reader citation button (label "出处：" + source), so the exact label is asserted on that node.
                onNodeWithTag("private-citation").performScrollTo().assertTextEquals("出处：Synthetic lecture 1, slide 4")
                capture("mainline-explained-bottom-$width")
                onNodeWithTag("mainline-next").performScrollTo().performClick()

                // Right but marked as a guess: still not mastered, still queued.
                awaitQuestion("D1")
                onNodeWithTag("mainline-option-1").performClick()
                onNodeWithTag("mainline-guess").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("猜对：不算会。").performScrollTo().assertIsDisplayed()
                onNodeWithText("已排进复习：明天再看一次。").performScrollTo().assertIsDisplayed()
                assertEquals(listOf(false, true), mac.answers.map { it["guessed"]!!.jsonPrimitive.boolean })
                capture("mainline-guessed-$width")
                onNodeWithTag("mainline-next").performScrollTo().performClick()

                // The bridge question leads on to step grading and the timed essay.
                awaitQuestion("B1")
                onNodeWithTag("mainline-option-1").performClick()
                onNodeWithTag("mainline-submit").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("答对。").performScrollTo().assertIsDisplayed()
                onNodeWithText("考点 3 · 有题 3 · 会了 1").assertExists()
                onNodeWithTag("mainline-essay").performScrollTo().assertIsDisplayed()
                onNodeWithTag("mainline-steps").performClick()
                assertTrue(steps, "the bridge question opens step grading")
                onNodeWithTag("mainline-next").performScrollTo().performClick()
                onNodeWithText("本节脉络").assertExists()
                assertEquals(listOf("S1", "D1", "B1"), mac.answers.map { it.str("itemId") })
                capture("mainline-bridge-$width")
            }
        } finally { tutor.close(); driver.close() }
    }

    /** Whole-week practice belongs to its week and starts the real question screen in one click. */
    @Test fun weekPracticeStartsTheDisplayedWeekWithoutReadingOrAnExtraStart() = FakeMac(today).use { mac ->
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
        val events = listOf(Event(1, "synthetic-week", "C", "outline_revised", recordedAt = now, occurredAt = now,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(json("id" to "K", "title" to "Synthetic orchard", "week" to 1))))))
        val view = CourseView(events, project(events))
        var opened by androidx.compose.runtime.mutableStateOf<Int?>(null)
        var showReview by androidx.compose.runtime.mutableStateOf(true)
        try {
            runComposeUiTest {
                setContent {
                    CoachTheme(false) { Surface { Box(Modifier.size(420.dp, 900.dp)) {
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        val actions = androidx.compose.runtime.remember(c) { WeekActions(c, scope) }
                        actions.onPractice = { number ->
                            c.sync.saveMainlineState("C", json("week" to number, "entry" to "quiz"))
                            opened = number
                        }
                        when {
                            showReview -> ReviewScreen(c, view, { _, _ -> }, {}, onOpenMainline = { _, _ -> error("Obsolete review entry must not dispatch") })
                            opened == null -> com.coach.ui.v7.WeekPage(c, view, TutorUiState(), actions, { _, _ -> }, 1)
                            else -> MainlineScreen(c, view, {}, {}, {}, {}, directEntry = true, initialWeek = opened)
                        }
                    } } }
                }
                onNodeWithTag("open-mainline").assertDoesNotExist()
                runOnIdle { showReview = false }
                onNodeWithTag("week-practice").assertIsEnabled()
                assertTrue(mac.answers.isEmpty(), "Browsing a week must not answer a question")
                onNodeWithTag("week-practice").performClick()
                awaitQuestion("S1")
                assertEquals(1, opened)
                onNodeWithTag("mainline-notes").assertDoesNotExist()
                onNodeWithTag("mainline-open-notes").assertExists()
                capture("week-practice-direct-entry")
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
                awaitQuestion("S1")
                onNodeWithTag("mainline-open-notes").performScrollTo().performClick()
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
                awaitQuestion("B1")
                onNodeWithText("第 1 题").assertExists()
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
                onNodeWithText("猜对：不算会。").performScrollTo().assertIsDisplayed()
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
                awaitQuestion("B1")
                onNodeWithText("第 1 题").performScrollTo().assertIsDisplayed()
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
        captureLessonEvidence(name)
    }

    private fun ComposeUiTest.awaitQuestion(id: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question $id:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("mainline-option-0").assertExists()
    }

    /** Geometry alone cannot detect an overlay that fades otherwise visible text. */
    private fun ComposeUiTest.assertFeedbackTextReadable(text: String, matchIndex: Int = 0) {
        val interaction = onAllNodesWithText(text)[matchIndex]
        val node = interaction.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val viewport = onNodeWithTag("mainline-scroll-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(node.size.height.toFloat(), bounds.height, "all lines must be unclipped: $text")
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom, "text stays above the action area: $bounds, $viewport")
        val layouts = mutableListOf<TextLayoutResult>()
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty(), "text layout is available")
        val pixels = onRoot().captureToImage().toAwtImage()
        val layout = layouts.single()
        for (line in 0 until layout.lineCount) {
            val top = kotlin.math.floor(bounds.top + layout.getLineTop(line)).toInt().coerceIn(0, pixels.height - 1)
            val bottom = kotlin.math.ceil(bounds.top + layout.getLineBottom(line)).toInt().coerceIn(top + 1, pixels.height)
            val left = kotlin.math.floor(bounds.left).toInt().coerceIn(0, pixels.width - 1)
            val right = kotlin.math.ceil(bounds.right).toInt().coerceIn(left + 1, pixels.width)
            val darkest = (top until bottom).minOf { y -> (left until right).minOf { x ->
                val rgb = pixels.getRGB(x, y)
                (((rgb shr 16) and 255) + ((rgb shr 8) and 255) + (rgb and 255)) / 3
            } }
            // These fixtures use the light theme's dark body ink. Fully faded lines in the
            // reported screenshots have minima 175..208; allow antialiasing, not washed-out ink.
            assertTrue(darkest <= 96, "line ${line + 1} is faded (darkest=$darkest): $text")
        }
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
                    // Preserve the original 40dp citation clearance above the action area.
                    explanation.isDisplayed() && citation.isDisplayed() && source.top >= viewport.top && source.bottom <= viewport.bottom - 80f
                }
                explanation.assertIsDisplayed()
                citation.assertIsDisplayed()
                assertFeedbackTextReadable("Synthetic apples were sold out before the season started, so they cannot be the answer.")
                capture("outer-feedback-1972x1248")
            } catch (failure: Throwable) {
                capture("outer-feedback-1972x1248-failure")
                throw failure
            }
            onAllNodesWithText("The synthetic orchard story sets up the supply example used later in the week.")[0].performScrollTo().assertIsDisplayed()
            assertFeedbackTextReadable("Synthetic apples were sold out before the season started, so they cannot be the answer.")
            assertFeedbackTextReadable("The synthetic orchard story sets up the supply example used later in the week.")
            capture("outer-feedback-details-1972x1248")
            // Once the reader scrolls further down, returning from notes must retain that position.
            onNodeWithText("答错。").performScrollTo().assertIsDisplayed()
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
    @Test fun longExplanationKeepsCitationAboveFadeAndFullFeedbackScrollable() = FakeMac(today, includeNotes = true, setupEmphasis = true, correct = longCorrect).use { mac ->
        val c = controller(mac)
        c.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
        val view = CourseView(emptyList(), project(emptyList()))
        runSkikoComposeUiTest(size = Size(1972f, 1248f), density = Density(2f)) {
            setContent { CoachTheme(false) { Surface { Box(Modifier.fillMaxSize().padding(top = 73.dp, bottom = 24.dp)) {
                MainlineScreen(c, view, {}, {}, {}, {})
            } } } }
            awaitQuestion("S1")
            onNodeWithTag("mainline-option-0").performClick()
            onNodeWithTag("mainline-submit").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            val scroller = onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("mainline-heading")))
            waitUntil(timeoutMillis = 15_000) {
                val viewport = scroller.fetchSemanticsNode().boundsInRoot
                val citation = onNodeWithTag("private-citation").fetchSemanticsNode().boundsInRoot
                citation.top >= viewport.top && citation.bottom <= viewport.bottom - 80f
            }
            assertFeedbackTextReadable("Synthetic apples were sold out before the season started, so they cannot be the answer.")
            capture("outer-long-feedback-1972x1248")
            onNodeWithText(longCorrect).assertExists()
            onNodeWithText("为什么对", substring = true).performScrollTo().assertIsDisplayed()
            onNodeWithText("Synthetic apples were sold out before the season started, so they cannot be the answer.").performScrollTo().assertIsDisplayed()
            onNodeWithText("Synthetic plums ripen after the period described in the question.").performScrollTo().assertIsDisplayed()
            onAllNodesWithText("The synthetic orchard story sets up the supply example used later in the week.")[0].performScrollTo().assertIsDisplayed()
            assertFeedbackTextReadable("The synthetic orchard story sets up the supply example used later in the week.")
            capture("outer-long-feedback-details-1972x1248")
            val before = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            onNodeWithText("回到要点").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-notes").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-skip").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertEquals(before, scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
            onNodeWithTag("mainline-option-0").assertIsSelected()
        }
    }

    @Test fun innerSubmissionShowsCompleteInlineActionsAndCitationWithoutManualScroll() = FakeMac(today, includeNotes = true, setupEmphasis = true).use { mac ->
        val c = controller(mac)
        c.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
        val view = CourseView(emptyList(), project(emptyList()))
        // QA's inner capture: top system/banner 146px, bottom navigation 64px, paper y=266..1680.
        runSkikoComposeUiTest(size = Size(2448f, 1848f), density = Density(2f)) {
            setContent { CoachTheme(false) { Surface { Box(Modifier.fillMaxSize().padding(top = 73.dp, bottom = 32.dp)) {
                MainlineScreen(c, view, {}, {}, {}, {})
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("v7-directory").assertIsDisplayed()
            onNodeWithTag("mainline-option-0").performClick()
            onNodeWithTag("mainline-submit").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 15_000) { onNodeWithText("为什么对", substring = true).isDisplayed() }
            // Capture at the first settled answer, before any performScrollTo or notes navigation.
            capture("inner-first-answer-2448x1848")
            val viewport = onNodeWithTag("mainline-scroll-viewport").fetchSemanticsNode().boundsInRoot
            val next = onNodeWithTag("mainline-next").fetchSemanticsNode()
            val nextText = onNode(
                hasText("下一题  ›") and hasAnyAncestor(hasTestTag("mainline-next")),
                useUnmergedTree = true
            ).fetchSemanticsNode()
            val back = onNodeWithText("回到要点").fetchSemanticsNode()
            val backText = onNodeWithText("回到要点", useUnmergedTree = true).fetchSemanticsNode()
            val citation = onNodeWithTag("private-citation").fetchSemanticsNode()
            val heading = onNodeWithText("为什么对", substring = true).fetchSemanticsNode()
            assertEquals(next.size.height.toFloat(), next.boundsInRoot.height, "the whole next action must fit on the paper")
            assertEquals(nextText.size.height.toFloat(), nextText.boundsInRoot.height, "next-action glyphs must not be clipped")
            assertEquals(back.size.height.toFloat(), back.boundsInRoot.height, "the whole return action must fit on the paper")
            assertEquals(backText.size.height.toFloat(), backText.boundsInRoot.height, "return-action glyphs must not be clipped")
            assertEquals(citation.size.height.toFloat(), citation.boundsInRoot.height, "the whole citation must fit on the paper")
            assertEquals(heading.size.height.toFloat(), heading.boundsInRoot.height, "the explanation heading must remain fully visible")
            onNodeWithText("回到要点").assertIsDisplayed()
            onNodeWithTag("private-citation").assertIsDisplayed()
            assertTrue(next.boundsInRoot.bottom <= viewport.bottom, "actions must be inside the paper: ${next.boundsInRoot}, $viewport")
            assertTrue(citation.boundsInRoot.top >= viewport.top && citation.boundsInRoot.bottom <= next.boundsInRoot.top,
                "the complete citation stays above the inline actions: ${citation.boundsInRoot}")
            // A later deliberate scroll and return to notes must not trigger the submit-only reveal again.
            val scroller = onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("mainline-heading")))
            onNodeWithText("答错。").performScrollTo()
            val before = scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            onNodeWithText("回到要点").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-notes").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-skip").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, scroller.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
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
            awaitQuestion("S1")
            onNodeWithTag("mainline-option-0").performClick()
            onNodeWithTag("mainline-submit").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-citation").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("private-highlight-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-label").assertTextContains("第 2 页", substring = true)
            onNodeWithTag("private-highlight-status").assertTextContains("已在页上标出", substring = true)
            assertTrue(ImageIO.write(onNodeWithTag("private-page-label").onParent().captureToImage().toAwtImage(), "png",
                File(System.getProperty("coach.testScreens"), "mainline-v7-cited-page.png").also { it.parentFile.mkdirs() }))
            onNodeWithTag("private-document-back").performClick()
            onNodeWithTag("private-citation").assertExists()
            onNodeWithTag("v7-desk").assertExists()
            onNodeWithTag("mainline-option-0").assertIsSelected()
            capture("mainline-v7-answer-after-reader-return")
        }
        assertTrue(mac.readerRequests.any { it.contains("/resolve?source=") }, "the cited source went through the reader resolve route")
        assertTrue(mac.readerRequests.any { it.contains("/doc/pages/2/boxes") && it.contains("version=v1") }, "coordinates were requested for the resolved document, page and version: ${mac.readerRequests}")
    }

    @Test fun compositeSourceOffersEachCitationAndOnlyTheRegisteredTextbookPageOpens() =
        FakeMac(today, false, source = "W1 课件 p.26；教材 p.8。", strictResolve = true).use { mac ->
        val c = controller(mac)
        val view = CourseView(emptyList(), project(emptyList()))
        runComposeUiTest {
            setContent { CoachTheme(false) { Surface { Box(Modifier.size(900.dp, 700.dp)) {
                MainlineScreen(c, view, onBack = {}, onOpenSteps = {}, onOpenEssay = {}, onReviewWeek = {})
            } } } }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-week-1").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("mainline-week-1").performClick()
            awaitQuestion("S1")
            onNodeWithTag("mainline-option-0").performClick()
            onNodeWithTag("mainline-submit").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("private-citation").assertCountEquals(0)
            onNodeWithTag("private-citation-0").performScrollTo().assertTextEquals("W1 课件 p.26")
            onNodeWithTag("private-citation-1").assertTextEquals("教材 p.8")
            capture("mainline-composite-citation-900x700")
            // The unregistered slides say so and never open a guessed page.
            onNodeWithTag("private-citation-0").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("private-citation-failed").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag("private-page-label").assertCountEquals(0)
            onNodeWithText("返回题目").performClick()
            onNodeWithTag("mainline-option-0").assertIsSelected()
            onNodeWithTag("private-citation-1").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("private-page-label").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("private-page-label").assertTextContains("PDF 第 39 页", substring = true)
            onNodeWithTag("private-document-back").performClick()
            onNodeWithTag("private-citation-1").assertExists()
            onNodeWithTag("mainline-option-0").assertIsSelected()
            onNodeWithTag("mainline-outcome").assertExists()
        }
        val resolved = mac.readerRequests.filter { it.contains("/resolve?") }.map { java.net.URLDecoder.decode(it.substringAfter("source="), "UTF-8") }
        assertEquals(listOf("W1 课件 p.26", "教材 p.8"), resolved.distinct(), "each citation resolves on its own, never the composite string")
        assertTrue(mac.readerRequests.any { it.contains("/doc/pages/39/") }, "the registered textbook opened its own PDF page: ${mac.readerRequests}")
    }
}
