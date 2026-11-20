package com.coach.desktop.books

import android.content.Context
import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.coach.android.CoachCredentialStore
import com.coach.client.PairedCredentials
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.reader.LocalBooksReaderSource
import com.coach.desktop.reader.ReaderDraftScope
import com.coach.ui.theme.CoachTheme
import com.sun.net.httpserver.HttpServer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@Implements(Magnifier::class)
class NoOpShadowMagnifier {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi", shadows = [NoOpShadowMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextReaderParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun phoneTextReaderTouchTurnAaReflowModeMemoryNotesHighlightsAndReopen() {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "android-text-parity-")
        val libDir = root.resolve("library")
        val sourceFile = root.resolve("Arithmetic.md").also { file ->
            val body = (1..60).joinToString("\n\n") { n ->
                "Paragraph $n. 中文与 English 混合正文，用于验证安卓文字版阅读器的翻页、滚动、字号重排与读位保存。"
            }
            Files.writeString(file, "# 第一章 阅读与证据\n\n$body")
        }
        val library1 = TextbookLibrary(libDir)
        val book = library1.importBook(sourceFile)
        library1.save(book.copy(promptSeen = true))
        val targetPhrase = "Paragraph 2. 中文与 English 混合正文"
        val hlStart = book.pages.first().text.indexOf(targetPhrase)
        assertTrue(hlStart >= 0)
        library1.setHighlight(BookPassage(book.id, 1, hlStart, hlStart + targetPhrase.length, targetPhrase), true)

        val controller1 = TextbookController(library1)
        runBlocking {
            controller1.load()
            controller1.learn(LearningPath.READ)
        }
        val activeController = mutableStateOf<TextbookController?>(controller1)
        compose.setContent {
            activeController.value?.let { controller ->
                Box(Modifier.fillMaxSize()) {
                    WindowsTextbookHost(controller) { null }
                }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }

        fun visibleText(): String = compose.onNodeWithTag("reader-text")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text

        fun bodyFontSizeSp(): Float {
            val out = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            return out.single().layoutInput.style.fontSize.value
        }

        // 1. Initial paged view renders book text and highlight; touch swipe left turns page.
        val page0Text = visibleText()
        compose.onNodeWithTag("book-page-turn").performTouchInput {
            down(Offset(width * 0.6f, height * 0.5f))
            moveTo(Offset(width * 0.35f, height * 0.5f))
            moveTo(Offset(width * 0.1f, height * 0.5f))
            up()
        }
        compose.waitUntil(10_000) { visibleText() != page0Text }
        val page1Text = visibleText()
        assertNotEquals(page0Text, page1Text)
        compose.waitUntil(10_000) {
            val saved = library1.savedReadSpot(book.id)
            saved != null && saved.first == 1 && saved.second > 0
        }
        val spotAfterPage1 = library1.savedReadSpot(book.id)!!

        // 2. Tap right edge advances another page; spot persists to SQLite.
        compose.onNodeWithTag("book-page-turn").performTouchInput {
            down(Offset(width * 0.88f, height * 0.5f))
            up()
        }
        compose.waitUntil(10_000) { visibleText() != page1Text }
        val page2Text = visibleText()
        assertNotEquals(page1Text, page2Text)
        compose.waitUntil(10_000) {
            val saved = library1.savedReadSpot(book.id)
            saved != null && saved.first == 1 && saved.second > spotAfterPage1.second
        }
        val savedSpot = library1.savedReadSpot(book.id)!!

        // 3. Aa settings: font size LARGE immediately reflows typography without losing spot, then back to MEDIUM; KAI + DARK persist.
        val mediumFontPx = bodyFontSizeSp()
        compose.onNodeWithTag("v7-type-menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reading-size-LARGE").performClick()
        compose.waitUntil(10_000) { bodyFontSizeSp() > mediumFontPx + 1f }
        assertEquals(ReadingSize.LARGE, ReadingSettings(libDir).size)
        compose.onNodeWithTag("reading-font-KAI").performClick()
        compose.onNodeWithTag("reading-paper-DARK").performClick()
        compose.onNodeWithTag("reading-size-MEDIUM").performClick()
        compose.waitUntil(10_000) { kotlin.math.abs(bodyFontSizeSp() - mediumFontPx) < 0.5f }
        assertEquals(page2Text, visibleText())
        assertEquals(savedSpot, library1.savedReadSpot(book.id))

        // Close Aa popup by clicking back on reader text area? Simply toggle read mode next (dismisses popup or re-click).
        compose.onNodeWithTag("v7-type-menu").performClick()
        compose.waitForIdle()

        // 4. Switch to Scroll mode: remembered per book, survives reopen!
        compose.onNodeWithTag("read-mode").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book-scroll-read").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(library1.textScrollMode(book.id))

        // 5. Notes draft persists across reopen (matching Windows 1.1.0 LocalBooksReaderSource contract).
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("books-notes-toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("books-notes-toggle").performClick()
        compose.onNodeWithTag("books-note-new").performScrollTo().performClick()
        compose.onNodeWithTag("books-note-line-0").performScrollTo().performClick()
        compose.onNodeWithTag("note-input").performScrollTo().performTextInput("Android note draft check")
        compose.onNodeWithTag("books-note-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-failure-reason").fetchSemanticsNodes().isNotEmpty() }

        // 6. Cold start with fresh TextbookLibrary + TextbookController: restores scroll mode, read spot, Aa settings, highlight, and note draft.
        compose.runOnIdle { activeController.value = null }
        compose.waitForIdle()
        val library2 = TextbookLibrary(libDir)
        assertTrue(library2.textScrollMode(book.id))
        assertEquals(savedSpot, library2.savedReadSpot(book.id))
        assertEquals(ReadingFont.KAI, ReadingSettings(libDir).font)
        assertEquals(PaperColor.DARK, ReadingSettings(libDir).paper)
        assertEquals(1, library2.highlights(book.id, book.pages.first()).size)
        val draftStore = ReaderDraftScope.forSource(LocalBooksReaderSource(library2), libDir)!!
        assertEquals("Android note draft check", draftStore.listFor(book.id, "1", 1).single().note)

        val controller2 = TextbookController(library2)
        runBlocking {
            controller2.load()
            controller2.learn(LearningPath.READ)
        }
        compose.runOnIdle { activeController.value = controller2 }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("book-scroll-read").fetchSemanticsNodes().isNotEmpty() }
        // Switch back to paged mode and verify we are still on the exact saved subpage!
        compose.onNodeWithTag("read-mode").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book-page-turn").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(page2Text, visibleText())
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("books-notes-toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("books-notes-toggle").performClick()
        compose.onNodeWithTag("local-draft-0").assertExists()
    }

    @Test fun foldUnfoldSpreadPreservesNewPageAfterManualTurn() {
        val raw = "# Chapter 1\n\n" + (1..80).joinToString("\n\n") { n ->
            "Paragraph $n. Folding and unfolding must preserve the reader's new page after turning."
        }
        val book = Textbook("fold-spot", "Foldable Book", "epub", listOf(BookPage(1, raw)))
        val state = BookPagingState(BookPageAnchor(1))
        val width = mutableStateOf(900.dp) // unfolded wide -> spread
        compose.setContent {
            com.coach.ui.CoachTheme {
                Box(Modifier.width(width.value).fillMaxSize()) {
                    BookPageView(book, state, emptyMap(), onTurn = { state.move(it) },
                        mode = BookPageMode.Auto, turnStyle = com.coach.ui.pageturn.PageTurnStyle.Flip)
                }
            }
        }
        compose.waitUntil(15_000) { state.pages.size >= 4 }
        // Turn once in wide/unfolded state.
        val moved = compose.runOnIdle { state.move(ReaderTurn.Next) }!!
        compose.waitForIdle()
        assertEquals(moved, state.anchor)
        // Narrow (fold to outer screen), then widen (unfold back to inner screen).
        compose.runOnIdle { width.value = 360.dp }
        compose.waitForIdle()
        assertEquals(moved, state.anchor)
        assertTrue(state.pages[state.index].sources.any {
            it.importPage == moved.importPage && moved.offsetUtf16 in it.startUtf16 until it.endUtf16
        })
        compose.runOnIdle { width.value = 900.dp }
        compose.waitForIdle()
        assertEquals(moved, state.anchor)
        assertTrue(state.pages[state.index].sources.any {
            it.importPage == moved.importPage && moved.offsetUtf16 in it.startUtf16 until it.endUtf16
        })
    }

    @Test fun androidLongPressShowsExplainToolbarAnswersWithSectionContextAndPreservesDefaultTextToolbar() {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        Class.forName("org.sqlite.JDBC")
        AndroidBookConnection.resetForTest()
        context.getSharedPreferences("coach-book-connection", Context.MODE_PRIVATE).edit().clear().commit()

        val root = Files.createTempDirectory(context.cacheDir.toPath(), "android-longpress-explain-")
        val pool = Executors.newCachedThreadPool()
        val explainRequests = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                val rawBody = exchange.requestBody.use { it.readBytes() }.decodeToString()
                val (status, json) = when (exchange.requestURI.path) {
                    "/v1/public/status" -> 200 to """{"online":true,"todayCalls":0}"""
                    "/v1/library/explain" -> {
                        val payload = Json.parseToJsonElement(rawBody).jsonObject
                        explainRequests += payload
                        val firstBlock = payload.getValue("blocks").jsonArray.first().jsonObject
                        val id = firstBlock.getValue("id").jsonPrimitive.content
                        val page = Regex(":p(\\d+):").find(id)!!.groupValues[1]
                        val kind = payload["kind"]?.jsonPrimitive?.content ?: "explain"
                        val text = if (kind == "ask") {
                            "这一问承接本节任务继续展开说明。【S1】"
                        } else {
                            "这段原文说明核心概念与本节任务的承接关系。【S1】"
                        }
                        200 to """{"text":"$text","sources":[{"docId":"$id","pdfPage":$page,"anchor":"S1"}],"elapsedMs":15}"""
                    }
                    else -> 404 to "{}"
                }
                val bytes = json.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val serverUrl = "http://127.0.0.1:${server.address.port}"
            val stores = ConcurrentHashMap<String, CoachCredentialStore>()
            fun memoryStore(name: String): CoachCredentialStore = stores.getOrPut(name) {
                object : CoachCredentialStore {
                    @Volatile private var value: PairedCredentials? = null
                    @Volatile private var revoked = false
                    override fun save(credentials: PairedCredentials) {
                        value = credentials
                        revoked = false
                    }
                    override fun load(): PairedCredentials? = value
                    override fun bookPairing(): Pair<PairedCredentials?, Boolean> = value to revoked
                    override fun revokeIfCurrent(credentials: PairedCredentials) {
                        if (value == credentials) {
                            value = null
                            revoked = true
                        }
                    }
                    override fun clear() {
                        value = null
                        revoked = false
                    }
                }
            }

            val creds = PairedCredentials("friend-android-test", "a".repeat(43), "friend")
            memoryStore("paired-friend").save(creds)
            AndroidBookConnection.bind(
                context = context,
                url = "http://127.0.0.1:18799",
                store = memoryStore("paired-device"),
                friendUrl = serverUrl,
                sableIssuer = serverUrl,
                credentialFactory = { _, _, name -> memoryStore(name) },
            )
            AndroidBookConnection.syncFriendSaved(serverUrl, creds, context)

            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir)
            val sec1Body = (1..16).joinToString("\n\n") { i ->
                "核心概念说明（${i}）：生物学课程立足学生真实探究经验，帮助理解生命系统的基本规律与科学思维方式。Biology inquiry builds concepts."
            }
            val sec2Body = (1..8).joinToString("\n\n") { i ->
                "基本任务说明（${i}）：光合作用在叶绿体中把太阳能转化为稳定的化学能，为生态系统提供物质和能量基础。Photosynthesis stores energy."
            }
            val sourceFile = root.resolve("BiologyGuide.md").also { file ->
                Files.writeString(
                    file,
                    "# 第一章 课程性质\n\n$sec1Body\n\n## （二）基本任务\n\n$sec2Body",
                )
            }
            val book = library.importBook(sourceFile)
            ExplainConsent(libDir).allow(book.id)
            val explainer = BookExplainer(library)
            runBlocking { explainer.load() }
            assertNotNull(explainer.identity)

            val expectedSection = book.lessons.first { 1 in it.firstPage..it.lastPage }
            var observedDefaultCopyMenu = 0

            compose.setContent {
                var activePage by remember { mutableStateOf(1) }
                val focus = remember { FocusRequester() }
                val platformContextMenu = LocalTextContextMenuToolbarProvider.current
                val recordingContextMenu = remember(platformContextMenu) {
                    object : TextContextMenuProvider {
                        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
                            if (dataProvider.data().components.any { it.key == TextContextMenuKeys.CopyKey }) {
                                observedDefaultCopyMenu++
                            }
                            platformContextMenu?.showTextContextMenu(dataProvider)
                        }
                    }
                }
                CoachTheme(darkTheme = false) {
                    CompositionLocalProvider(
                        LocalReadingAutoGuide provides false,
                        LocalTextContextMenuToolbarProvider provides recordingContextMenu,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            TextbookReader(
                                ui = LibraryUi(library.catalog(), book, screen = BookScreen.READER, page = activePage),
                                library = library,
                                support = UnconnectedBookLearningSupport,
                                practiceSource = null,
                                pageFocus = focus,
                                onNoteSession = {},
                                onPage = { activePage = it },
                                onBack = {},
                                onLearn = {},
                                explainProvider = explainer,
                            )
                        }
                    }
                }
            }

            compose.waitUntil(20_000) { compose.onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()

            val initialText = compose.onNodeWithTag("reader-text")
                .fetchSemanticsNode().config[SemanticsProperties.Text].single().text

            // 1. Real touch long-press near the left edge of reader-text (inside PageTurnView's 28% edge zone):
            // must NOT accidentally turn page, must preserve default Copy menu, and must open selection-explain-toolbar.
            compose.onNodeWithTag("reader-text").performTouchInput {
                longClick(Offset(24f, height * 0.35f))
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(initialText, compose.onNodeWithTag("reader-text")
                .fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
            compose.waitUntil(10_000) { observedDefaultCopyMenu > 0 }
            assertTrue("Default Android Copy context menu must remain intact", observedDefaultCopyMenu > 0)

            compose.onNodeWithTag("selection-action-explain").assertTextEquals("讲讲")
            compose.onNodeWithTag("selection-action-example").assertTextEquals("举个例子")
            compose.onNodeWithTag("selection-action-rephrase").assertTextEquals("换个说法")
            compose.onNodeWithTag("selection-action-ask").assertTextEquals("问…")

            // 2. Tap「讲讲」-> inline answer renders with sectionContext and chosen text.
            compose.onNodeWithTag("selection-action-explain").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, explainRequests.size)
            val firstReq = explainRequests[0]
            assertEquals("explain", firstReq["kind"]?.jsonPrimitive?.content)
            val chosenText = firstReq["selection"]?.jsonPrimitive?.content.orEmpty()
            assertTrue("Chosen text must be non-blank and come from book page: '$chosenText'",
                chosenText.isNotBlank() && chosenText in book.pages.first().text)
            assertEquals(expectedSection.breadcrumb,
                firstReq["sectionContext"]?.jsonObject?.get("location")?.jsonPrimitive?.content)

            // 3. Follow-up question shares sectionContext and prior turn history.
            compose.onNodeWithTag("inline-followup-input").performTextInput("它和前面有什么联系？")
            compose.onNodeWithTag("inline-followup-send").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("inline-explain-turn-1").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, explainRequests.size)
            val followReq = explainRequests[1]
            assertEquals("ask", followReq["kind"]?.jsonPrimitive?.content)
            assertEquals(1, followReq["history"]?.jsonArray?.size)
            assertEquals(expectedSection.breadcrumb,
                followReq["sectionContext"]?.jsonObject?.get("location")?.jsonPrimitive?.content)

            // 4. Close inline card -> card and toolbar dismiss cleanly.
            compose.onNodeWithTag("inline-explain-close").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("inline-explain-card").assertDoesNotExist()
            compose.onNodeWithTag("selection-explain-toolbar").assertDoesNotExist()

            // 5. Scroll mode: touch long-press also triggers selection-explain-toolbar.
            compose.onNodeWithTag("read-mode").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("book-scroll-read").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithTag("reader-text")[0].performTouchInput {
                longClick(Offset(width * 0.45f, height * 0.35f))
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            AndroidBookConnection.resetForTest()
            context.getSharedPreferences("coach-book-connection", Context.MODE_PRIVATE).edit().clear().commit()
            server.stop(0)
            pool.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }
}
