package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.HttpLedgerTransport
import com.coach.client.PairedCredentials
import com.coach.client.SyncEngine
import com.coach.client.db.ClientDatabase
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopCredentials
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.HostSearchDialog
import com.coach.desktop.WindowsTextbookGate
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.reader.LocalBooksReaderSource
import com.coach.domain.LedgerJson
import com.coach.domain.Projection
import com.coach.domain.json
import com.coach.domain.reader.ReaderTextRange
import com.coach.protocol.CommandBody
import com.coach.protocol.Hello
import com.coach.protocol.Receipt
import com.coach.protocol.Snapshot
import com.coach.protocol.WireCommand
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TON-1888: 6 automated acceptance tests covering offline reading, notes/highlights,
 * FSRS due review, explicit "需要联网" prompts for AI features with preserved input,
 * offline -> online auto-recovery without restart, and crash-free navigation.
 */
@OptIn(ExperimentalTestApi::class)
class OfflineModeAcceptanceTest {

    @Before
    fun clearConnectivityBefore() {
        CoachConnectivityRegistry.resetForTests()
    }

    @After
    fun clearConnectivityAfter() {
        CoachConnectivityRegistry.resetForTests()
    }

    private fun closedLoopbackUrl(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    private fun isolatedSettings(home: Path, serverUrl: String): DesktopConnectionSettings {
        val settings = DesktopConnectionSettings(
            home = home,
            environment = { null },
            mac = false,
            property = { if (it == "jpackage.app-path") home.resolve("LearningCoach.exe").toString() else null },
        )
        val creds = DesktopCredentials(settings.clientDirectory)
        creds.saveAddress(serverUrl)
        creds.save(serverUrl, PairedCredentials("offline-pc", "t".repeat(43), "friend"))
        return settings
    }

    private fun writeMultiChapterMarkdown(dir: Path, name: String = "synthetic-economics.md"): Path {
        val content = buildString {
            appendLine("# Chapter 1: Foundations of Choice")
            appendLine()
            appendLine("Consumer preferences satisfy completeness and transitivity across bundles.")
            appendLine()
            appendLine("Marginal utility diminishes as consumption increases along the budget line.")
            appendLine()
            appendLine("# Chapter 2: Asymmetric Information")
            appendLine()
            appendLine("Adverse selection occurs when hidden types distort equilibrium quality in insurance.")
            appendLine()
            appendLine("Moral hazard arises when hidden actions alter risk after a contract is signed.")
            appendLine()
            appendLine("# Chapter 3: Empirical Demand")
            appendLine()
            appendLine("Arc elasticity measures responsiveness across discrete coinsurance changes.")
            appendLine()
            appendLine("Randomized health insurance experiments isolate causal utilization effects.")
        }
        val file = dir.resolve(name)
        Files.writeString(file, content)
        return file
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class MutableTestClock(initial: Instant) : Clock() {
        private var current: Instant = initial
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = current
        fun advanceDays(days: Long) {
            current = current.plusSeconds(days * 86_400L)
        }
    }

    private fun ComposeUiTest.assertOfflineNoticeContains(tag: String, needle: String = "需要联网") {
        onNodeWithTag(tag).assertIsDisplayed()
        onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(needle, substring = true)).assertIsDisplayed()
    }

    private class ControllableCoachServer : AutoCloseable {
        val online = AtomicBoolean(false)
        val explainCalls = AtomicInteger(0)
        val collectionAskCalls = AtomicInteger(0)
        val bookMapBatchCalls = AtomicInteger(0)
        val uploadCalls = AtomicInteger(0)
        val receivedCommands = CopyOnWriteArrayList<String>()
        private val uploadedBooks = java.util.concurrent.ConcurrentHashMap<String, String>()

        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", ::handle)
            start()
        }
        val url: String = "http://127.0.0.1:${server.address.port}"

        private fun handle(exchange: HttpExchange) {
            val reqBytes = exchange.requestBody.use { it.readBytes() }
            if (!online.get()) {
                exchange.close()
                return
            }
            val path = exchange.requestURI.path
            val bodyStr = reqBytes.decodeToString()
            val (status, resp) = when {
                path == "/v1/public/status" -> 200 to """{"online":true,"unifiedBooks":true,"accountScope":"sable-offline-test"}"""
                path == "/v1/hello" -> 200 to LedgerJson.encodeToString(
                    Hello(epoch = "offline-test-epoch", lastSeq = 0, backedUpThroughSeq = 0, lastSeqBefore = 0, unifiedBooksSupported = true),
                )
                path == "/v1/courses/C1/snapshot" -> 200 to LedgerJson.encodeToString(
                    Snapshot(
                        hello = Hello(epoch = "offline-test-epoch", lastSeq = 0, backedUpThroughSeq = 0, lastSeqBefore = 0),
                        courseId = "C1",
                        events = emptyList(),
                        projection = Projection(lastSeq = 0),
                    ),
                )
                path == "/v1/courses/C1/events" -> 200 to """{"epoch":"offline-test-epoch","events":[],"throughSeq":0,"hasMore":false}"""
                path == "/v1/commands" -> {
                    val cmd = LedgerJson.decodeFromString<WireCommand>(bodyStr)
                    receivedCommands += cmd.commandId
                    200 to LedgerJson.encodeToString(
                        Receipt(
                            commandId = cmd.commandId,
                            status = "APPLIED",
                            eventSeqs = listOf(1L),
                            epoch = "offline-test-epoch",
                            backedUpThroughSeq = 1L,
                        ),
                    )
                }
                path == "/v1/books/upload" -> {
                    uploadCalls.incrementAndGet()
                    val shelfId = "sb_offline_test_${uploadedBooks.size + 1}"
                    uploadedBooks[shelfId] = "READY"
                    200 to """{"shelfBookId":"$shelfId","sha256":"${exchange.requestHeaders.getFirst("X-Coach-Book-Sha256").orEmpty()}","job":{"state":"ready","progressPct":100,"stageLabel":"已就绪"}}"""
                }
                path.startsWith("/v1/books/") && path.endsWith("/status") -> {
                    200 to """{"job":{"state":"ready","progressPct":100,"stageLabel":"已就绪"}}"""
                }
                path == "/v1/library/explain" -> {
                    explainCalls.incrementAndGet()
                    val req = Json.parseToJsonElement(bodyStr).jsonObject
                    val blockId = req["blocks"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: "b:p1:0"
                    val page = Regex(":p(\\d+):").find(blockId)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    200 to """{"text":"这段原文说明了偏好完备性与递减边际效用。【S1】","sources":[{"docId":"$blockId","pdfPage":$page,"anchor":"S1"}]}"""
                }
                path == "/v1/library/collection/ask" -> {
                    collectionAskCalls.incrementAndGet()
                    val req = Json.parseToJsonElement(bodyStr).jsonObject
                    val first = req["blocks"]?.jsonArray?.firstOrNull()?.jsonObject
                    val bookId = first?.get("bookId")?.jsonPrimitive?.content ?: "book-1"
                    val page = first?.get("page")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                    val docLabel = first?.get("docLabel")?.jsonPrimitive?.content ?: "Synthetic Book"
                    val rawText = first?.get("text")?.jsonPrimitive?.content ?: "Adverse selection"
                    val anchor = rawText.trim().split(Regex("\\s+")).take(4).joinToString(" ")
                    val respObj = buildJsonObject {
                        put("sentences", buildJsonArray {
                            add(buildJsonObject {
                                put("claim", "跨材料核对：在第 $page 页中，原文说明了逆向选择与均衡变化。")
                                put("bookId", bookId)
                                put("docLabel", docLabel)
                                put("page", page)
                                put("quoteAnchor", anchor)
                            })
                        })
                        put("hasMaterialConflict", false)
                        put("conflictSummaryZh", "")
                        put("conflictCards", buildJsonArray { })
                    }
                    200 to respObj.toString()
                }
                path.startsWith("/api/books/") && path.endsWith("/book-map/batch") -> {
                    bookMapBatchCalls.incrementAndGet()
                    val bid = path.removePrefix("/api/books/").substringBefore("/book-map/batch")
                    200 to """{"bookId":"$bid","bookTitle":"Synthetic","chapterCount":3,"chapters":[{"chapterNum":1,"chapterTitle":"Chapter 1","oneLiner":"Core choice","coreClaims":[{"claim":"Completeness","importance":"CORE","pdfPage":1,"quoteAnchor":"Consumer preferences satisfy"}]}],"thematicClusters":[{"clusterId":"c1","title":"Choice","themeSummary":"Preferences","chapterNums":[1,2]}],"dependencyEdges":[],"fastSkimOrder40Min":[{"chapterNum":1,"minutes":5,"focus":"Preferences"}],"masterGlossary":[{"en":"Adverse selection","zh":"逆向选择","chapterNum":2}],"costStats":{"estimatedCostUsd":0.01}}"""
                }
                else -> 404 to "{}"
            }
            val bytes = resp.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }

    @Test
    fun item1_coldStartOfflineOpensShelfBookTurnsPagesJumpsChapterAndSearchesText() {
        val root = Files.createTempDirectory("coach-offline-item1-").toRealPath()
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val settings = isolatedSettings(home, closedLoopbackUrl())
            val libDir = home.resolve("books")
            val library = TextbookLibrary(libDir, todayCardEnabled = false)
            val book = library.importBook(writeMultiChapterMarkdown(root))
            assertTrue(book.pages.size >= 3, "Multi-chapter markdown should produce >= 3 pages")

            val explainer = BookExplainer(settings, library)
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings) {
                        WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.screen == BookScreen.HOME &&
                        onAllNodesWithTag("home-read").fetchSemanticsNodes().isNotEmpty()
                }
                // Opens shelf immediately offline without blocking on login
                onNodeWithTag("book-${book.id}").assertIsDisplayed()
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()
                assertEquals(1, controller.state.value.page)

                // Turn page forward
                onNodeWithTag("next-page").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.page > 1 }

                // Jump to Chapter 2 via TOC drawer
                onNodeWithTag("v7-directory-toggle").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("toc-item-1").fetchSemanticsNodes().isNotEmpty() }
                val ch2TargetPage = book.tocNodes.first { "Chapter 2" in it.title }.startPage
                onNodeWithTag("toc-item-1").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.page == ch2TargetPage }

                // Search in-book text for Chapter 3 term and jump to match
                onNodeWithTag("immersive-more-menu").performClick()
                waitForIdle()
                onNodeWithTag("book-search-toggle").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("book-search-input").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-search-input").performTextInput("Arc elasticity")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("book-search-hit-0").fetchSemanticsNodes().isNotEmpty() }
                val ch3Page = book.pages.first { "Arc elasticity" in it.text }.number
                onNodeWithTag("book-search-hit-0").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.page == ch3Page }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun item2_offlineHighlightsAndNotesPersistWithoutLossOrDuplicatesAfterReconnect() = runBlocking {
        val root = Files.createTempDirectory("coach-offline-item2-").toRealPath()
        try {
            ControllableCoachServer().use { server ->
                val home = Files.createDirectories(root.resolve("home"))
                val settings = isolatedSettings(home, server.url)
                val libDir = home.resolve("books")
                val library = TextbookLibrary(libDir, todayCardEnabled = false)
                val book = library.importBook(writeMultiChapterMarkdown(root))
                val page1 = checkNotNull(book.page(1))
                val quote = "Consumer preferences satisfy completeness"
                val start = page1.text.indexOf(quote)
                assertTrue(start >= 0)
                val end = start + quote.length

                // Offline: add highlight and note
                assertFalse(server.online.get())
                library.setHighlight(BookPassage(book.id, 1, start, end, quote), true)
                val readerSource = LocalBooksReaderSource(library, loadPageImages = false)
                val noteBody = buildJsonObject {
                    put("id", "note-offline-1")
                    put("version", "1")
                    put("pdfPage", 1)
                    put("start", start)
                    put("end", end)
                    put("quote", quote)
                    put("note", "Offline saved marginal note")
                    put("textSha256", sha256Hex(page1.text))
                    put("baseRevision", 0L)
                    put("mutationId", "mut-offline-1")
                    put("deleted", false)
                }
                assertEquals(200, readerSource.saveNote(book.id, noteBody).status)
                // Idempotent replay with same mutationId must not duplicate
                assertEquals(200, readerSource.saveNote(book.id, noteBody).status)

                assertEquals(1, library.highlights(book.id, page1).size)
                assertEquals(1, library.allNotes(book.id).size)

                // Reconnect and sync queued book upload
                val explainer = BookExplainer(settings, library)
                explainer.load()
                val uploadClient = checkNotNull(explainer.unifiedUploadClient())
                val controller = TextbookController(library).apply { unifiedUploadClient = uploadClient }
                controller.onUploadIdentityChanged(uploadClient.activeAccountKey())?.join()
                controller.uploadCoordinator.enqueueBook(book.id, book.id, book.format, book.title)
                server.online.set(true)
                controller.syncBookUploads().join()

                // Verify persisted highlights and notes on disk with zero loss and zero duplicates
                val reloaded = TextbookLibrary(libDir, todayCardEnabled = false)
                val reloadedPage1 = checkNotNull(reloaded.book(book.id).page(1))
                val highlights = reloaded.highlights(book.id, reloadedPage1)
                val notes = reloaded.allNotes(book.id)
                assertEquals(1, highlights.size, "Highlight must not be lost or duplicated after reconnect")
                assertEquals(ReaderTextRange(start, end), highlights.single())
                assertEquals(1, notes.size, "Note must not be lost or duplicated after reconnect")
                assertEquals("Offline saved marginal note", notes.single().note)
                assertEquals(quote, notes.single().quote)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun item3_offlineDueFsrsReviewCompletesRoundAndPersistsScheduleLocally() {
        val root = Files.createTempDirectory("coach-offline-item3-").toRealPath()
        try {
            val clock = MutableTestClock(Instant.parse("2026-11-26T09:00:00Z"))
            val libDir = root.resolve("books")
            val library = TextbookLibrary(libDir, clock = clock, todayCardEnabled = false)
            val book = library.importBook(writeMultiChapterMarkdown(root))
            val lesson = book.lessons.first()
            val practice = Practice(
                prompt = "Which property ensures any two bundles can be compared?",
                reference = "Consumer preferences satisfy completeness and transitivity across bundles.",
                answer = "Completeness",
                options = listOf("Completeness", "Satiation"),
                sourcePage = 1,
            )
            library.saveSectionCheckAnswer(book.id, lesson, 0, practice, PracticeAnswer("Satiation", revealed = true))
            clock.advanceDays(1)
            val controller = TextbookController(library)
            val todayEpochDay = LocalDate.now(clock).toEpochDay()
            assertEquals(1, library.reviewQueue().cards.size)

            val settings = isolatedSettings(Files.createDirectories(root.resolve("conn")), closedLoopbackUrl())
            val offlineExplainer = BookExplainer(settings, library).apply {
                runBlocking { load() }
            }

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalCoachConnectivity provides CoachConnectivityStatus.OFFLINE) {
                        WindowsTextbookHost(controller, bookExplainer = offlineExplainer) { null }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.screen == BookScreen.HOME &&
                        onAllNodesWithTag("home-review-start").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("home-review-start").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.REVIEWS }
                onNodeWithTag("local-review-start").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.REVIEWING }

                // Reveal answer and rate GOOD while offline
                onNodeWithTag("review-show-answer").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("review-rate-good").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("review-rate-good").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    !controller.state.value.busy && controller.state.value.screen == BookScreen.REVIEWS
                }
                // AI auto-diagnosis must not spin offline
                onNodeWithText("正在分析错因与生成同类变式…").assertDoesNotExist()
                onNodeWithTag("local-reviews").assertIsDisplayed()
                onNodeWithTag("local-review-start").assertDoesNotExist()
                assertTrue(controller.state.value.todayQueue.cards.isEmpty())
            }

            // Verify FSRS schedule written directly into library.sqlite
            DriverManager.getConnection("jdbc:sqlite:${libDir.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { st ->
                    st.executeQuery("SELECT due_day, stability, reps FROM book_fsrs_cards WHERE book='${book.id}'").use { rs ->
                        assertTrue(rs.next(), "FSRS card row must be persisted locally")
                        val dueDay = rs.getLong(1)
                        val stability = rs.getDouble(2)
                        val reps = rs.getInt(3)
                        assertTrue(dueDay > todayEpochDay, "dueDay ($dueDay) should advance past today ($todayEpochDay)")
                        assertTrue(stability > 0.0, "stability should be updated")
                        assertTrue(reps >= 1, "reps should increment")
                    }
                    st.executeQuery("SELECT completed FROM book_reviews WHERE book='${book.id}'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun item4_aiFeaturesShowNeedsNetworkWithoutSpinningAndPreserveQuestionForOneClickSendAfterReconnect() {
        val root = Files.createTempDirectory("coach-offline-item4-").toRealPath()
        try {
            ControllableCoachServer().use { server ->
                val home = Files.createDirectories(root.resolve("home"))
                val settings = isolatedSettings(home, server.url)
                val libDir = home.resolve("books")
                val library = TextbookLibrary(libDir, todayCardEnabled = false)
                val book = library.importBook(writeMultiChapterMarkdown(root))
                ExplainConsent(libDir).allow(book.id)

                val explainer = BookExplainer(settings, library)
                runBlocking { explainer.load() }
                val block = book.sourceBlocks().first { it.isBodyParagraph }

                // 4A. 讲解 (Explanation): offline reports "需要联网" without technical errors
                assertFalse(server.online.get())
                val explainRes = runBlocking { explainer.explain(block, listOf(block)) }
                assertTrue(explainRes is ExplainResult.Refused)
                assertEquals(ExplainTrouble.Unreachable, explainRes.trouble)
                assertTrue("需要联网" in explainRes.message, "Explain error should contain 需要联网: ${explainRes.message}")
                assertFalse("Exception" in explainRes.message || "HTTP" in explainRes.message)

                // 4B. 书内问答 (BookPassageAsk): shows 需要联网, preserves question, 1-click sends after online
                val support = PairedBookLearningSupport(
                    library,
                    sendLesson = { blocks, pages -> explainer.explainLesson(blocks, pages) },
                    send = { blk, q -> explainer.ask(blk, q) },
                )
                val page1 = checkNotNull(book.page(1))
                runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
                    setContent {
                        CoachTheme(darkTheme = false) {
                            CompositionLocalProvider(LocalCoachConnectivity provides CoachConnectivityStatus.OFFLINE) {
                                BookPassageAsk(book = book, page = page1, support = support, onClose = {})
                            }
                        }
                    }
                    assertOfflineNoticeContains("book-passage-offline-notice")
                    onNodeWithTag("book-passage-send").assertTextContains("需要联网", substring = true)
                    onNodeWithTag("ask-paragraph-0").performClick()
                    onNodeWithTag("book-passage-question").performTextInput("这段核心结论是什么？")
                    onNodeWithTag("book-passage-send").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithText("正在读这段问题…").assertDoesNotExist()
                    onNodeWithTag("explain-trouble").assertTextContains("需要联网", substring = true)
                    onNodeWithTag("book-passage-question").assertTextContains("这段核心结论是什么？", substring = true)

                    // Reconnect and 1-click retry
                    server.online.set(true)
                    onNodeWithTag("explain-trouble-retry").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("book-passage-reply").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(server.explainCalls.get() >= 1)
                }

                // 4C. 跨材料提问 (HostSearchDialog): shows 需要联网, no spinner, preserves query, 1-click retry succeeds
                server.online.set(false)
                val courseRecord = MaterialCollectionRecord("course-offline-qa", CollectionKind.COURSE, "Offline Econ")
                library.upsertCollection(courseRecord)
                library.attachMaterial(CollectionMaterialRecord(courseRecord.id, book.id, role = "TEXTBOOK", ordinal = 0))
                val courseSummary = CollectionSummary(record = courseRecord, materialIds = listOf(book.id))
                val summary = library.catalog().single()
                runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
                    setContent {
                        CoachTheme(darkTheme = false) {
                            CompositionLocalProvider(LocalCoachConnectivity provides CoachConnectivityStatus.OFFLINE) {
                                HostSearchDialog(
                                    library = library,
                                    catalog = listOf(summary),
                                    collections = listOf(courseSummary),
                                    onOpenBook = { _, _ -> },
                                    onOpenCollection = {},
                                    onClose = {},
                                    explainer = explainer,
                                    preferredCollectionId = courseRecord.id,
                                )
                            }
                        }
                    }
                    assertOfflineNoticeContains("host-search-offline-notice")
                    onNodeWithTag("host-search-ask-collection").assertTextContains("需要联网", substring = true)
                    onNodeWithTag("host-search-input").performTextInput("Adverse selection")
                    onNodeWithTag("host-search-ask-collection").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("host-search-qa-error").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("host-search-qa-loading").assertDoesNotExist()
                    onNodeWithTag("host-search-qa-error").assertTextContains("需要联网", substring = true)
                    onNodeWithTag("host-search-input").assertTextContains("Adverse selection", substring = true)

                    // Reconnect and 1-click retry
                    server.online.set(true)
                    onNodeWithTag("host-search-qa-retry").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("host-search-qa-turn-0").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertTrue(server.collectionAskCalls.get() >= 1)
                }

                // 4D. 书的地图生成 (BookStudyMode.BOOK_MAP): shows 需要联网 offline, 1-click generates after online
                server.online.set(false)
                val chGroup = ChapterSectionGroup(
                    chapterId = "ch-1",
                    chapterTitle = "Chapter 1",
                    sections = book.lessons.take(1),
                    firstPage = 1,
                    lastPage = book.pages.size,
                )
                val pref = BookStudyPreference(bookId = book.id, mode = BookStudyMode.BOOK_MAP)
                runSkikoComposeUiTest(size = Size(700f, 800f), density = Density(1f)) {
                    setContent {
                        CoachTheme(darkTheme = false) {
                            CompositionLocalProvider(LocalCoachConnectivity provides CoachConnectivityStatus.OFFLINE) {
                                val scope = androidx.compose.runtime.rememberCoroutineScope()
                                ModeDistinctCompanionSurface(
                                    book = book,
                                    library = library,
                                    currentPage = 1,
                                    preference = pref,
                                    chapterGroup = chGroup,
                                    currentSectionIndex = 0,
                                    pack = null,
                                    draft = ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-1", page = 1),
                                    onUpdateDraft = {},
                                    onUpdateInquiry = {},
                                    onJumpPage = {},
                                    onOpenAnchor = {},
                                    modeLoading = true,
                                    onRequestBookMapBatch = {
                                        scope.launch {
                                            val wire = StudyModeWireBuilder.buildBookMapBatchRequestJson(library, book, pref)
                                            val res = explainer.fetchBookMapBatch(book.id, wire)
                                            res.body?.let { StudyModeStore.saveBookMapBatchResult(library, book.id, it) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    onNodeWithTag("mode-streaming-status").assertDoesNotExist()
                    assertOfflineNoticeContains("mode-offline-banner")
                    assertOfflineNoticeContains("book-map-offline-notice")
                    onNodeWithTag("book-map-batch-button").assertTextContains("需要联网", substring = true)

                    // Reconnect and 1-click generate book map
                    server.online.set(true)
                    onNodeWithTag("book-map-batch-button").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        StudyModeStore.loadBookMapMeta(library, book.id) != null
                    }
                    assertTrue(server.bookMapBatchCalls.get() >= 1)
                }

                // 4E. 阅读器真实入口 (TextbookReader + CompanionAskPane + 选段「讲讲」):
                //     已判离线时立即显示「需要联网」，不闪出「正在整理…」，保留草稿并在联网后一键发送成功
                server.online.set(false)
                val callsBeforeReader = server.explainCalls.get()
                val readerController = TextbookController(library)
                runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                    setContent {
                        CompositionLocalProvider(LocalConnectivityPollMs provides 150L) {
                            WindowsTextbookGate(settings) {
                                WindowsTextbookHost(readerController, bookExplainer = explainer) { null }
                            }
                        }
                    }
                    waitUntil(timeoutMillis = 15_000) {
                        !readerController.state.value.busy && readerController.state.value.screen == BookScreen.HOME &&
                            onAllNodesWithTag("host-offline-badge").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("home-read").performClick()
                    waitUntil(timeoutMillis = 10_000) { readerController.state.value.screen == BookScreen.READER }
                    waitForIdle()

                    // Drag-select passage on page 1 and click "讲讲" (selection-action-explain)
                    val targetQuote = "Consumer preferences satisfy"
                    val textNode = onNode(hasTestTag("reader-text") and hasText(targetQuote, substring = true))
                    val layouts = mutableListOf<TextLayoutResult>()
                    textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    val qStart = layout.layoutInput.text.text.indexOf(targetQuote)
                    val leftBox = layout.getBoundingBox(qStart)
                    val rightBox = layout.getBoundingBox(qStart + targetQuote.length - 1)
                    textNode.performMouseInput {
                        moveTo(Offset(leftBox.left + 0.5f, leftBox.center.y))
                        press()
                        moveTo(Offset(rightBox.right - 0.5f, rightBox.center.y))
                        release()
                    }
                    waitUntil(timeoutMillis = 5_000) {
                        onAllNodesWithTag("selection-action-explain").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("selection-action-explain").performClick()
                    waitUntil(timeoutMillis = 5_000) {
                        onAllNodesWithTag("companion-ask-pane").fetchSemanticsNodes().isNotEmpty() &&
                            onAllNodesWithTag("inline-explain-error").fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("inline-explain-loading").assertDoesNotExist()
                    onNodeWithTag("inline-explain-error").assertTextContains("需要联网才能使用", substring = true)
                    assertOfflineNoticeContains("companion-ask-offline-notice")

                    // Type follow-up question in CompanionAskPane and click "发送" while offline
                    onNodeWithTag("inline-followup-input").performTextInput("这段核心假设是什么？")
                    onNodeWithTag("inline-followup-send").performClick()
                    waitForIdle()
                    onNodeWithTag("inline-explain-loading").assertDoesNotExist()
                    onNodeWithTag("inline-explain-error").assertTextContains("需要联网才能提问", substring = true)
                    onNodeWithTag("inline-followup-input").assertTextContains("这段核心假设是什么？", substring = true)

                    // Bring server online and 1-click send immediately from CompanionAskPane
                    server.online.set(true)
                    onNodeWithTag("inline-followup-send").performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty() &&
                            onAllNodesWithTag("inline-explain-error").fetchSemanticsNodes().isEmpty()
                    }
                    assertTrue(server.explainCalls.get() > callsBeforeReader)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun item5_offlineToOnlineRecoversWithin30sAndCompletesQueuedUploadsAndLedgerSyncWithoutRestart() {
        val root = Files.createTempDirectory("coach-offline-item5-").toRealPath()
        try {
            ControllableCoachServer().use { server ->
                val home = Files.createDirectories(root.resolve("home"))
                val settings = isolatedSettings(home, server.url)
                val libDir = home.resolve("books")
                val library = TextbookLibrary(libDir, todayCardEnabled = false)
                val book = library.importBook(writeMultiChapterMarkdown(root))
                val explainer = BookExplainer(settings, library)
                runBlocking { explainer.load() }
                val controller = TextbookController(library)

                // Pre-seed client ledger.sqlite with 1 offline queued command and close handle before mounting UI;
                // WindowsTextbookGate (RememberDesktopLedgerRecovery) must own and drain SyncEngine without test sync.start().
                val ledgerPath = settings.clientDirectory.resolve("ledger.sqlite")
                Files.createDirectories(settings.clientDirectory)
                val queuedCmd = run {
                    val setupDriver = JdbcSqliteDriver("jdbc:sqlite:${ledgerPath.toAbsolutePath()}", Properties(), ClientDatabase.Schema)
                    val setupTransport = HttpLedgerTransport(server.url, "offline-pc", "t".repeat(43))
                    try {
                        val setupSync = SyncEngine(
                            db = ClientDatabase(setupDriver),
                            deviceId = "offline-pc",
                            transport = setupTransport,
                            courses = listOf("C1"),
                            newId = { UUID.randomUUID().toString() },
                            hash = { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
                        )
                        setupSync.enqueue(CommandBody("mark_read", "C1", 1_700_000_000_000L, json("week" to 1)))
                    } finally {
                        setupTransport.close()
                        setupDriver.close()
                    }
                }

                runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                    setContent {
                        CompositionLocalProvider(LocalConnectivityPollMs provides 200L) {
                            WindowsTextbookGate(settings) {
                                WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                            }
                        }
                    }
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy &&
                            controller.state.value.screen == BookScreen.HOME &&
                            controller.unifiedUploadClient != null &&
                            onAllNodesWithTag("windows-host").fetchSemanticsNodes().isNotEmpty()
                    }
                    // Enqueue book upload while offline
                    runBlocking {
                        controller.uploadCoordinator.enqueueBook(book.id, book.id, book.format, book.title)
                        controller.uploadCoordinator.syncOnce()
                    }
                    controller.syncBookUploads()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("host-offline-badge").fetchSemanticsNodes().isNotEmpty() &&
                            controller.state.value.uploadStatuses[book.id]?.state == BookUploadState.WAITING_NETWORK
                    }
                    assertTrue(server.receivedCommands.isEmpty())

                    // Bring server online WITHOUT restarting host; app must drain both uploads and ledger outbox within 30s
                    server.online.set(true)
                    waitUntil(timeoutMillis = 25_000) {
                        onAllNodesWithTag("host-offline-badge").fetchSemanticsNodes().isEmpty() &&
                            controller.state.value.uploadStatuses[book.id]?.state == BookUploadState.READY &&
                            queuedCmd.commandId in server.receivedCommands
                    }
                    assertTrue(server.uploadCalls.get() >= 1)
                }

                DriverManager.getConnection("jdbc:sqlite:${ledgerPath.toAbsolutePath()}").use { conn ->
                    conn.prepareStatement("SELECT ack_seq, receipt_status FROM outbox WHERE command_id=?").use { st ->
                        st.setString(1, queuedCmd.commandId)
                        st.executeQuery().use { rs ->
                            assertTrue(rs.next(), "Queued ledger command row must exist")
                            assertTrue(rs.getLong(1) > 0L, "ack_seq must be persisted")
                            assertEquals("APPLIED", rs.getString(2))
                        }
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun item6_offlineNavigationNeverCrashesOrShowsBlankPage() {
        val root = Files.createTempDirectory("coach-offline-item6-").toRealPath()
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val settings = isolatedSettings(home, closedLoopbackUrl())
            val libDir = home.resolve("books")
            val clock = MutableTestClock(Instant.parse("2026-11-26T09:00:00Z"))
            val library = TextbookLibrary(libDir, clock = clock, todayCardEnabled = false)
            val book = library.importBook(writeMultiChapterMarkdown(root))
            val lesson = book.lessons.first()
            val practice = Practice("Sample?", "Consumer preferences satisfy completeness.", "Yes", listOf("Yes", "No"), 1)
            val explainer = BookExplainer(settings, library)
            library.saveSectionCheckAnswer(book.id, lesson, 0, practice, PracticeAnswer("No", revealed = true))
            clock.advanceDays(1)
            assertEquals(1, library.reviewQueue().cards.size)
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    WindowsTextbookGate(settings) {
                        WindowsTextbookHost(controller, bookExplainer = explainer) { null }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.screen == BookScreen.HOME &&
                        onAllNodesWithTag("home-focus-card").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("home-focus-card").assertIsDisplayed()

                // Navigate Home -> Reader
                onNodeWithTag("home-read").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    controller.state.value.screen == BookScreen.READER &&
                        onAllNodesWithTag("textbook-reader").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("textbook-reader").assertIsDisplayed()

                // Open TOC & Companion drawer (Explain -> Ask -> Notes)
                onNodeWithTag("v7-directory-toggle").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("v7-directory-drawer").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("immersive-companion-toggle").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("immersive-companion-drawer").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("companion-tab-ask").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("companion-ask-pane").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("companion-tab-notes").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("books-notes").fetchSemanticsNodes().isNotEmpty() }

                // Back to Home -> Settings -> Reviews -> Home
                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.HOME }
                onNodeWithTag("home-focus-card").assertIsDisplayed()

                onNodeWithTag("open-settings").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("settings-page").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("close-settings").performClick()
                waitForIdle()

                onNodeWithTag("home-review-start").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    controller.state.value.screen == BookScreen.REVIEWS &&
                        onAllNodesWithTag("local-reviews").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("local-reviews").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
