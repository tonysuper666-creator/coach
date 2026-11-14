package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.domain.*
import com.coach.domain.reader.*
import com.coach.protocol.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

/** Real Ktor sockets and the paired CourseHttp/ReaderHttp, with entirely invented documents and page pixels. */
@OptIn(ExperimentalTestApi::class)
class ClaimSourceConsumptionTest {
    private val book = ClaimSourceAnchor("invented-book", 4, KnowledgeSourceKind.TEXTBOOK, claimRef = "definition")
    private val slides = ClaimSourceAnchor("invented-slides", 2, KnowledgeSourceKind.SLIDES, 4, "definition")

    private fun view(vararg anchors: ClaimSourceAnchor, course: String = "C", id: String = "K"): CourseView {
        val point = writeKnowledgeSources(json("id" to id, "name" to "Synthetic claim", "module" to "Week 1"), anchors.toList())
        val events = listOf(Event(1, "synthetic-outline", course, "outline_revised", recordedAt = 1, occurredAt = 1,
            deviceId = "synthetic", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(point)))))
        return CourseView(events, project(events))
    }

    private class SyntheticService : AutoCloseable {
        val evidence = CopyOnWriteArrayList<JsonObject>()
        val pageRequests = CopyOnWriteArrayList<String>()
        @Volatile var version = "edition-1"
        @Volatile var denyCatalog = false
        @Volatile var denyPage = false
        @Volatile var wrongPage = false
        @Volatile var heldDocument: String? = null
        val pageStarted = CompletableDeferred<Unit>()
        val releasePage = CompletableDeferred<Unit>()
        val heldPageFinished = CompletableDeferred<Unit>()
        private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                suspend fun ApplicationCall.reply(body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) {
                    evidence += json("request" to "${request.httpMethod.value} ${request.uri}", "status" to status.value,
                        "documentId" to body["documentId"], "version" to body["version"], "pdfPage" to body["pdfPage"],
                        "documents" to body["documents"], "stateVersion" to (body["document"] as? JsonObject)?.get("version"),
                        "itemId" to body["itemId"], "choice" to body["choice"])
                    respondText(body.toString(), ContentType.Application.Json, status)
                }
                get("/v1/courses/{course}/reader") {
                    assertTrue(call.request.headers[HttpHeaders.Authorization].orEmpty().contains("synthetic-token"))
                    val docs = listOf("invented-book" to "textbook", "invented-slides" to "slides").map { (id, kind) ->
                        json("id" to id, "title" to "Synthetic $id", "version" to version, "pages" to 6, "kind" to kind)
                    }
                    call.reply(json("documents" to JsonArray(docs)), if (denyCatalog) HttpStatusCode.Forbidden else HttpStatusCode.OK)
                }
                get("/v1/courses/{course}/reader/{document}/pages/{page}") {
                    val doc = call.parameters["document"]!!
                    val page = call.parameters["page"]!!.toInt()
                    pageRequests += "$doc/$page"
                    if (doc == heldDocument) {
                        pageStarted.complete(Unit)
                        releasePage.await()
                    }
                    try {
                        call.reply(json("documentId" to doc, "version" to version, "pdfPage" to if (wrongPage) page + 1 else page,
                            "text" to "Synthetic $doc page $page"), if (denyPage) HttpStatusCode.Forbidden else HttpStatusCode.OK)
                    } finally {
                        if (doc == heldDocument) heldPageFinished.complete(Unit)
                    }
                }
                get("/v1/courses/{course}/reader/{document}/pages/{page}/image") {
                    val doc = call.parameters["document"]!!
                    val page = call.parameters["page"]!!
                    val changed = call.request.queryParameters["version"] != version
                    evidence += json("request" to "GET ${call.request.uri}", "status" to if (changed) 409 else 200,
                        "documentId" to doc, "version" to version, "pdfPage" to page.toInt())
                    if (changed) call.respond(HttpStatusCode.Conflict)
                    else call.respondBytes(syntheticReaderPage("$doc p$page"), ContentType.Image.PNG)
                }
                get("/v1/courses/{course}/reader/{document}/state") {
                    call.reply(json("document" to json("version" to version), "progress" to JsonNull, "notes" to JsonArray(emptyList())))
                }
                post("/v1/courses/{course}/reader/{document}/progress") {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    call.reply(json("revision" to 1, "documentId" to call.parameters["document"],
                        "version" to body["version"], "pdfPage" to body["pdfPage"]))
                }
                get("/v1/courses/{course}/reader/{document}/pages/{page}/study") {
                    call.reply(json("facts" to JsonArray(emptyList()), "questions" to JsonArray(emptyList()), "unlocated" to 0))
                }
                get("/v1/courses/C/sources") { call.reply(json("documents" to JsonArray(emptyList()))) }
                get("/v1/courses/C/mainline") {
                    call.reply(json("weeks" to JsonArray(listOf(json("week" to 1, "title" to "Synthetic week", "coverage" to json("questions" to 2))))))
                }
                get("/v1/courses/C/mainline/1") {
                    call.reply(json("week" to 1, "items" to JsonArray(listOf("Q1", "Q2").map { id ->
                        json("id" to id, "tier" to "setup", "stem" to "Synthetic question $id", "options" to listOf("First", "Second"), "factIds" to listOf("F"))
                    })))
                }
                post("/v1/courses/C/mainline/1/answer") {
                    val answer = Json.parseToJsonElement(call.receiveText()).jsonObject
                    call.reply(json("itemId" to answer["itemId"], "kpId" to "K", "answer" to 1, "choice" to answer["choice"],
                        "status" to "missed", "tier" to "setup", "explanation" to json("correct" to "Synthetic explanation")))
                }
            }
        }.start(false)
        private val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
        val http = CourseHttp(base, "synthetic-device", "synthetic-token", "C")
        fun receipt(name: String) {
            File("build/source-consumption/$name.json").also { it.parentFile.mkdirs() }
                .writeText(json("fixture" to "Ktor synthetic documents and pixels", "requests" to JsonArray(evidence)).toString())
        }
        override fun close() {
            releasePage.complete(Unit)
            http.close()
            runBlocking { server.stopSuspend(0, 500) }
        }
    }

    private class Controller(http: CourseHttp, events: List<Event> = emptyList()) : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("No ledger network")
            override suspend fun snapshot(course: String): Snapshot = error("No ledger network")
            override suspend fun events(course: String, after: Long): EventsPage = error("No ledger network")
            override suspend fun command(command: WireCommand): Receipt = error("No ledger network")
            override suspend fun watch(onLedger: suspend () -> Unit) = error("No ledger network")
        }
        private val tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ 1L }))
        val value: CoachController
        init {
            ClientDatabase.Schema.create(driver)
            val db = ClientDatabase(driver)
            events.forEach { event -> db.ledgerQueries.putEvent(event.seq, event.courseId, LedgerJson.encodeToString(event)) }
            value = CoachController("C", SyncEngine(db, "synthetic", transport, listOf("C"), { "id" }, { "hash" }),
                tutor, formatClock = { "12:00" }, nowMillis = { 1_794_052_800_000L }, course = http)
        }
        override fun close() { tutor.close(); driver.close() }
    }

    private fun ComposeUiTest.waitTag(tag: String) = waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun ComposeUiTest.capture(name: String) {
        val file = File("build/screens/$name.png").also { it.parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("claim-source-dialog").captureToImage().toAwtImage(), "png", file)
    }

    @Test fun mainlineEntrySelectsTheSourceAndRangePageThenReturnsToTheSameAnswer() {
        SyntheticService().use { service -> Controller(service.http).use { controller ->
            val view = view(book, slides)
            runComposeUiTest {
                setContent { CoachTheme(false) { Surface { Box(Modifier.size(700.dp, 1000.dp)) {
                    MainlineScreen(controller.value, view, {}, {}, {}, {})
                } } } }
                waitTag("mainline-week-1")
                onNodeWithTag("mainline-direct-1").performClick()
                waitTag("mainline-option-0")
                onNodeWithTag("mainline-option-0").performClick()
                onNodeWithTag("mainline-submit").performClick()
                waitTag("claim-source-open")
                onNodeWithTag("claim-source-open").performScrollTo()
                waitForIdle()
                val before = controller.value.sync.mainlineState("C", "week:1")
                onNodeWithTag("claim-source-open").performClick()
                waitTag("claim-source-choice-1")
                assertTrue(service.pageRequests.isEmpty())
                capture("claim-source-multiple")
                onNodeWithTag("claim-source-close").performClick()
                assertEquals(before, controller.value.sync.mainlineState("C", "week:1"))
                service.evidence += json("observation" to "selection-cancelled", "questionStateRetained" to true,
                    "itemId" to before["item"], "choice" to before["choice"])
                onNodeWithTag("claim-source-open").performClick()
                waitTag("claim-source-choice-1")
                onNodeWithTag("claim-source-choice-1").performClick()
                waitTag("claim-source-page-4")
                assertTrue(service.pageRequests.isEmpty())
                capture("claim-source-range")
                onNodeWithTag("claim-source-page-4").performClick()
                waitTag("private-page-label")
                onNodeWithTag("private-page-label").assertTextEquals("PDF 第 4 页 / 6")
                waitTag("private-page-4")
                assertEquals(listOf("invented-slides/4"), service.pageRequests.toList())
                capture("claim-source-opened")
                onNodeWithTag("claim-source-close").performClick()
                onNodeWithTag("mainline-option-0").assertIsSelected()
                assertEquals(before, controller.value.sync.mainlineState("C", "week:1"))
                service.evidence += json("observation" to "reader-returned", "questionStateRetained" to true,
                    "itemId" to before["item"], "choice" to before["choice"])
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/screens/claim-source-answer-restored.png"))
                onNodeWithTag("mainline-next").performClick()
                onNodeWithText("Synthetic question Q2").assertExists()
                onNodeWithTag("claim-source-dialog").assertDoesNotExist()
            }
            service.receipt("mainline-range-return")
        } }
    }

    @Test fun studyAppSourceActionConsumesStructuredReferencesWithNoLegacyJump() {
        SyntheticService().use { service ->
            val course = view(book)
            assertTrue(course.kp("K")!!.anchorDoc.isEmpty())
            Controller(service.http, course.events).use { controller ->
                runComposeUiTest {
                    setContent { Box(Modifier.size(900.dp, 1000.dp)) { CoachApp(controller.value, initialKp = "K", dark = false) } }
                    waitTag("open-source")
                    val navigation = controller.value.sync.mainlineState("C", "navigation")
                    onNodeWithTag("open-source").performClick()
                    waitTag("private-page-label")
                    assertEquals(listOf("invented-book/4"), service.pageRequests.toList())
                    onNodeWithTag("claim-source-close").performClick()
                    onNodeWithTag("open-source").assertExists()
                    assertEquals(navigation, controller.value.sync.mainlineState("C", "navigation"))
                }
            }
            service.receipt("study-app-entry-return")
        }
    }

    @Test fun claimSelectionOpensOnlyTheChosenSinglePage() {
        SyntheticService().use { service ->
            val kp = view(book, slides.copy(claimRef = "comparison")).kp("K")!!
            runComposeUiTest {
                setContent { CoachTheme(false) { ClaimSourceReferences(service.http.reader, kp, "") } }
                onNodeWithTag("claim-source-open").performClick()
                waitTag("claim-source-claim-0")
                assertTrue(service.evidence.isEmpty())
                onNodeWithTag("claim-source-claim-0").performClick()
                waitTag("private-page-label")
                onNodeWithTag("private-page-label").assertTextEquals("PDF 第 4 页 / 6")
                assertEquals(listOf("invented-book/4"), service.pageRequests.toList())
            }
            service.receipt("claim-single-page")
        }
    }

    @Test fun wrongCourseAndUnregisteredSourcesStayUnconfirmed() {
        SyntheticService().use { service ->
            for ((name, kp) in listOf("wrong-course" to view(book, course = "other").kp("K")!!,
                "unregistered" to view(book.copy(docId = "missing")).kp("K")!!)) {
                runComposeUiTest {
                    setContent { CoachTheme(false) { ClaimSourceReferences(service.http.reader, kp, "legacy chapter") } }
                    onNodeWithTag("claim-source-open").performClick()
                    waitTag("claim-source-unconfirmed")
                    onNodeWithTag("private-page-label").assertDoesNotExist()
                    capture("claim-source-$name")
                }
                assertTrue(service.pageRequests.isEmpty())
            }
            service.receipt("wrong-course-and-registration")
        }
    }

    @Test fun changedEditionOrRevokedAccessBetweenChoosingAndOpeningNeverShowsAPage() {
        for (change in listOf("edition", "access")) SyntheticService().use { service ->
            val kp = view(slides).kp("K")!!
            runComposeUiTest {
                setContent { CoachTheme(false) { ClaimSourceReferences(service.http.reader, kp, "") } }
                onNodeWithTag("claim-source-open").performClick()
                waitTag("claim-source-page-4")
                if (change == "edition") service.version = "edition-2" else service.denyCatalog = true
                onNodeWithTag("claim-source-page-4").performClick()
                waitTag("claim-source-unconfirmed")
                onNodeWithTag("private-page-label").assertDoesNotExist()
                assertTrue(service.pageRequests.isEmpty())
                capture("claim-source-$change")
            }
            service.receipt("changed-$change")
        }
    }

    @Test fun deniedOrMismatchedPageReplyNeverOpensTheReader() {
        for (failure in listOf("denied", "wrong-page")) SyntheticService().use { service ->
            service.denyPage = failure == "denied"
            service.wrongPage = failure == "wrong-page"
            runComposeUiTest {
                setContent { CoachTheme(false) { ClaimSourceReferences(service.http.reader, view(book).kp("K")!!, "") } }
                onNodeWithTag("claim-source-open").performClick()
                waitTag("claim-source-unconfirmed")
                onNodeWithTag("private-page-label").assertDoesNotExist()
            }
            service.receipt(failure)
        }
    }

    @Test fun lateReplyCannotOpenForAnotherQuestionOrReplaceItsPage() {
        SyntheticService().use { service ->
            service.heldDocument = book.docId
            var kp by mutableStateOf(view(book).kp("K")!!)
            var owner by mutableStateOf("question-1")
            runComposeUiTest {
                setContent { CoachTheme(false) { ClaimSourceReferences(service.http.reader, kp, "", ownerKey = owner) } }
                onNodeWithTag("claim-source-open").performClick()
                waitUntil(timeoutMillis = 15_000) { service.pageStarted.isCompleted }
                runOnIdle { kp = view(slides.copy(pdfPage = 3, pdfPageEnd = 3), id = "K2").kp("K2")!!; owner = "question-2" }
                onNodeWithTag("claim-source-dialog").assertDoesNotExist()
                onNodeWithTag("claim-source-open").performClick()
                waitTag("private-page-label")
                onNodeWithTag("private-page-label").assertTextEquals("PDF 第 3 页 / 6")
                waitTag("private-page-3")
                service.releasePage.complete(Unit)
                waitUntil(timeoutMillis = 15_000) { service.heldPageFinished.isCompleted }
                waitForIdle()
                onNodeWithTag("private-page-label").assertTextEquals("PDF 第 3 页 / 6")
                onNodeWithText("Synthetic invented-book").assertDoesNotExist()
                capture("claim-source-late-reply")
            }
            service.receipt("late-reply-question-switch")
        }
    }

    @Test fun claimPeekKeepsSavedProgressAndSystemBackBeatsTheCoveredDesk() {
        SyntheticService().use { service ->
            val covered = intArrayOf(0, 0)
            val dispatcher = AppBackDispatcher()
            val kp = view(book).kp("K")!!
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAppBack provides dispatcher) {
                        AppBackHandler(true, priority = 300) { covered[0]++ }
                        AppBackHandler(true, priority = 400) { covered[1]++ }
                        CoachTheme(false) {
                            Box(Modifier.size(800.dp, 900.dp)) { ClaimSourceReferences(service.http.reader, kp, "") }
                        }
                    }
                }
                onNodeWithTag("claim-source-open").performClick()
                waitTag("private-page-label")
                onNodeWithTag("private-page-label").assertTextEquals("PDF 第 4 页 / 6")
                onNodeWithTag("private-next").performClick()
                waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("PDF 第 5 页 / 6").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("private-page-label").assertTextEquals("PDF 第 5 页 / 6")
                onNodeWithTag("private-focus").performClick()
                onNodeWithTag("private-focus").assertTextEquals("退出专心读")
                runOnIdle { assertTrue(dispatcher.back()) }
                waitForIdle()
                onNodeWithTag("claim-source-dialog").assertExists()
                onNodeWithTag("private-focus").assertTextEquals("专心读")
                runOnIdle { assertTrue(dispatcher.back()) }
                waitForIdle()
                onNodeWithTag("claim-source-dialog").assertDoesNotExist()
                assertEquals(0, covered[0])
                assertEquals(0, covered[1])
                val progressPosts = service.evidence.mapNotNull { it["request"]?.jsonPrimitive?.content }
                    .filter { it.startsWith("POST ") && it.contains("/progress") }
                assertEquals(emptyList(), progressPosts)
            }
        }
    }
}
