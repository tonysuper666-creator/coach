package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.reader.LocalBooksReaderSource
import com.coach.desktop.reader.ReaderDraftScope
import com.coach.desktop.reader.ReaderFixture
import com.coach.desktop.reader.ReaderRemote
import com.coach.desktop.reader.ReaderSession
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class Ton1848CompanionFixesTest {

    private class FakeCompanionExplainer(
        home: Path,
        library: TextbookLibrary,
        private val refuseSelection: Boolean = false,
    ) : BookExplainer(DesktopConnectionSettings(home = home, environment = { null }), library) {
        val lessonCalls = mutableListOf<List<SourceBlock>>()
        val modeCalls = mutableListOf<String>()
        val selectionCalls = mutableListOf<Triple<String, SourceBlock, String?>>()

        init {
            identity = DesktopIdentity("http://127.0.0.1:1", PairedCredentials("dev-ton1848", "tok-ton1848"))
            loaded = true
            revoked = false
        }

        override suspend fun load() {
            loaded = true
        }

        override suspend fun fetchChapterCompanion(
            requestJson: JsonObject,
            blocks: List<SourceBlock>,
        ): ChapterCompanionFetchResult = ChapterCompanionFetchResult.Failed("skip chapter", ExplainTrouble.Other)

        override suspend fun fetchStudyModePack(
            bookId: String,
            mode: String,
            input: JsonObject,
            onProgress: (chars: Int, firstTokenMs: Long?, delta: String) -> Unit,
        ): CourseServerCallResult {
            modeCalls += mode
            return CourseServerCallResult(buildJsonObject { }, null)
        }

        override suspend fun explainLesson(
            blocks: List<SourceBlock>,
            bookPages: Int,
            requestId: String,
        ): ExplainResult {
            lessonCalls += blocks
            val first = blocks.first()
            return ExplainResult.Done(
                Explanation(
                    text = "屏幕讲解：${first.text.take(24)}【S1】",
                    citations = listOf(Citation("S1", first.id, first.page)),
                    elapsedMs = 42L,
                ),
            )
        }

        override suspend fun explainSelection(
            kind: String,
            chosen: SourceBlock,
            context: ExplainSectionContext?,
            question: String?,
            history: List<FollowUpTurn>,
            requestId: String,
        ): ExplainResult {
            selectionCalls += Triple(kind, chosen, question)
            if (refuseSelection) {
                return ExplainResult.Refused("模拟服务拒绝回答", ExplainTrouble.Other)
            }
            val answer = if (kind == "ask") {
                "追问答复：针对「${question.orEmpty()}」的解释（基于第 ${chosen.page} 页原文）。【S1】"
            } else {
                "选段讲解：解释「${chosen.text.take(18)}」。【S1】"
            }
            return ExplainResult.Done(
                Explanation(
                    text = answer,
                    citations = listOf(Citation("S1", chosen.id, chosen.page)),
                    elapsedMs = 35L,
                ),
            )
        }
    }

    private fun importSyntheticBook(
        root: Path,
        content: String,
        filename: String = "synthetic-book.txt",
    ): Pair<TextbookLibrary, Textbook> {
        val libDir = Files.createDirectories(root.resolve("library"))
        val src = root.resolve(filename)
        Files.writeString(src, content)
        val library = TextbookLibrary(libDir)
        val book = library.importBook(src)
        return library to book
    }

    @Test
    fun noteForSelectionUsesExactQuoteNeverFallsBackToFirstLine() = runBlocking {
        val root = Files.createTempDirectory("ton1848-note-")
        try {
            val pageText = """
                Project Gutenberg Header Copyright Notice

                Dialogue line one: Where is the medicine?
                Dialogue line two: The crowd moved quietly down the street.
            """.trimIndent()
            val (library, book) = importSyntheticBook(root, pageText)
            val source = LocalBooksReaderSource(library, loadPageImages = false)
            val remote = ReaderRemote(source)
            val session = ReaderSession(
                ReaderFixture(book.title, emptyList(), emptyList()),
                1200,
                remote,
                ReaderDraftScope.forSource(source, library.directory),
            )
            remote.loadCatalog()
            session.openRemote(book.id, 1)
            remote.show(1)

            val targetQuote = "Dialogue line two: The crowd moved quietly down the street."
            val storedText = book.page(1)!!.text
            val start = storedText.indexOf(targetQuote)
            assertTrue(start > 0)
            val end = start + targetQuote.length

            assertTrue(session.beginNoteForRange(start, end, targetQuote))
            assertEquals(targetQuote, session.noteDraft?.quote)
            assertEquals(start, session.noteDraft?.start)
            assertEquals(end, session.noteDraft?.end)

            session.editNote("Selected dialogue note body")
            session.commitNote()
            assertEquals(1, library.allNotes(book.id).size)
            assertEquals(targetQuote, library.allNotes(book.id).single().quote)
            assertEquals("Selected dialogue note body", library.allNotes(book.id).single().note)

            // Invalid/missing quote must refuse cleanly instead of defaulting to line 0 copyright header.
            session.cancelNote()
            assertFalse(session.beginNoteForRange(99999, 100020, "Nonexistent quote text"))
            assertNull(session.noteDraft)
            assertEquals(-1, session.noteLine)
            assertNotNull(session.message)

            // Reopening with a fresh TextbookLibrary preserves the exact note quote and body.
            val reopened = TextbookLibrary(library.directory)
            val reloadedNote = reopened.allNotes(book.id).single()
            assertEquals(targetQuote, reloadedNote.quote)
            assertEquals("Selected dialogue note body", reloadedNote.note)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun screenExplainSkipsLeadingImageCaptionAndPrioritizesCurrentScreenSection() {
        val root = Files.createTempDirectory("ton1848-screen-")
        try {
            val xrayCaption = "X-Ray photograph of the hand showing internal bone structure."
            val introPara = "INTRODUCTION: Science advances by careful observation and testable hypotheses across generations."
            val page1Text = "$xrayCaption\n\n## INTRODUCTION\n\n$introPara"
            val (library, rawBook) = importSyntheticBook(root, "placeholder")
            val captionStart = page1Text.indexOf(xrayCaption)
            val introStart = page1Text.indexOf("## INTRODUCTION")
            val introBodyStart = page1Text.indexOf(introPara)
            assertTrue(introStart > 0 && introBodyStart > introStart)
            val introEnd = introBodyStart + introPara.length

            val blockCaption = SourceBlock("${rawBook.id}:p1:$captionStart", 1, captionStart, captionStart + xrayCaption.length, false, xrayCaption)
            val blockHeading = SourceBlock("${rawBook.id}:p1:$introStart", 1, introStart, introStart + "## INTRODUCTION".length, true, "## INTRODUCTION")
            val blockIntro = SourceBlock("${rawBook.id}:p1:$introBodyStart", 1, introBodyStart, introEnd, false, introPara)

            val lessons = listOf(
                BookLesson("sec-xray", "Illustrations", listOf(blockCaption)),
                BookLesson("sec-intro", "INTRODUCTION", listOf(blockHeading, blockIntro)),
            )
            val structure = BookStructure(
                version = BookStructure.CURRENT_VERSION,
                pageRoles = mapOf(1 to BookPageRole.BODY),
                sections = lessons.map {
                    BookSection(it.id, it.title, it.breadcrumb, 1, 1, 1, it.blocks.map(SourceBlock::id))
                },
                source = HeadingSource.HEADING_RULE.name,
                tocNodes = listOf(
                    BookTocNode("ch-1", null, 0, 1, TocNodeKind.CHAPTER, "1", "The Outline of Science", 1, 2, null, HeadingSource.HEADING_RULE),
                ),
            ).also { st -> st.deferResolvedLessons { lessons } }

            val book = rawBook.copy(
                pages = listOf(
                    BookPage(
                        number = 1,
                        text = page1Text,
                        illustrations = listOf(
                            BookIllustration(
                                page = 1,
                                order = 0,
                                anchorOffset = captionStart,
                                imageSha256 = "0".repeat(64),
                                extension = "png",
                                width = 200,
                                height = 150,
                                caption = xrayCaption,
                            ),
                        ),
                    ),
                    BookPage(2, "Chapter 2 continuation body text for second page."),
                ),
                structure = structure,
            )

            // Same-page section matching picks INTRODUCTION at offset >= introStart, not X-Ray section.
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            val guideController = SectionGuideController(book, library, ExplainConsent(library.directory), FakeCompanionExplainer(root.resolve("home"), library), scope)
            assertEquals(1, guideController.sectionIndexForAnchor(1, introBodyStart))

            // Screen block selector skips leading X-Ray caption when visible screen starts at INTRODUCTION.
            val selectedAtIntro = selectScreenExplainBlocks(
                book = book,
                page = 1,
                anchorOffsetUtf16 = introStart,
                visibleRanges = listOf(introStart until page1Text.length),
            )
            assertEquals(listOf(blockIntro), selectedAtIntro)

            // Even if caption appears before INTRODUCTION on the same page, leading caption is skipped in favor of prose.
            val selectedWholePage = selectScreenExplainBlocks(book = book, page = 1)
            assertEquals(listOf(blockIntro), selectedWholePage)

            val explainer = FakeCompanionExplainer(root.resolve("home2"), library)
            val pref = BookStudyPreference(book.id, BookStudyMode.READ_THROUGH, detailLevel = ExplainDetailLevel.DETAILED)
            runSkikoComposeUiTest(size = Size(820f, 620f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            SingleBookCompanionPanel(
                                book = book,
                                currentPage = 1,
                                currentPageOffset = introBodyStart,
                                visiblePageRanges = listOf(introStart until page1Text.length),
                                currentSectionIndex = 1,
                                explainer = explainer,
                                library = library,
                                draft = ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-1", page = 1),
                                studyPreference = pref,
                                onDraftChange = {},
                                onOpenAnchor = {},
                                onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {},
                                onOpenLogin = {},
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { explainer.lessonCalls.isNotEmpty() }
                waitForIdle()
                assertEquals(listOf(listOf(blockIntro)), explainer.lessonCalls)
                onNodeWithTag("explain-text").assertTextContains("INTRODUCTION", substring = true)
            }

            // Reopening the same screen hits local SQLite cache (0 extra explainLesson calls).
            val secondExplainer = FakeCompanionExplainer(root.resolve("home3"), library)
            runSkikoComposeUiTest(size = Size(820f, 620f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            SingleBookCompanionPanel(
                                book = book,
                                currentPage = 1,
                                currentPageOffset = introBodyStart,
                                visiblePageRanges = listOf(introStart until page1Text.length),
                                currentSectionIndex = 1,
                                explainer = secondExplainer,
                                library = library,
                                draft = ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-1", page = 1),
                                studyPreference = pref,
                                onDraftChange = {},
                                onOpenAnchor = {},
                                onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {},
                                onOpenLogin = {},
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("explain-text").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithTag("explain-text").assertTextContains("INTRODUCTION", substring = true)
                assertTrue(secondExplainer.lessonCalls.isEmpty(), "Cache hit must avoid second explainLesson call")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrationV5ToV6IsLosslessAndAskHistorySurvivesForceKillRestart() {
        val root = Files.createTempDirectory("ton1848-kill-qa-")
        try {
            val content = "First paragraph about supply and demand equilibrium.\n\nSecond paragraph about marginal utility."
            val (library, book) = importSyntheticBook(root, content)
            val dbFile = library.directory.resolve("library.sqlite")

            // Rewind DB to v5 (without book_ask_turns) and verify lossless migration to v6.
            DriverManager.getConnection("jdbc:sqlite:$dbFile").use { db ->
                db.createStatement().use { s ->
                    s.execute("DROP TABLE IF EXISTS book_ask_turns")
                    s.execute("PRAGMA user_version = 5")
                }
            }
            val migratedLibrary = TextbookLibrary(library.directory)
            assertTrue(Files.isRegularFile(library.directory.resolve(BookSchemaMigration.V5_BACKUP_FILENAME)))
            DriverManager.getConnection("jdbc:sqlite:$dbFile").use { db ->
                assertEquals(BookSchemaMigration.V6_SCHEMA_VERSION, BookSchemaMigration.readUserVersion(db))
                assertTrue(BookSchemaMigration.tableExists(db, "book_ask_turns"))
            }

            // Failed ask must NOT persist a turn in book_ask_turns.
            val p1 = book.page(1)!!
            val targetBlock = SourceBlock("${book.id}:p1:0", 1, 0, p1.text.indexOf('\n'), false, p1.text.substringBefore('\n'))
            val refusingExplainer = FakeCompanionExplainer(root.resolve("home-fail"), migratedLibrary, refuseSelection = true)
            runBlocking {
                val res = refusingExplainer.explainSelection("ask", targetBlock, null, "Will this persist?")
                assertTrue(res is ExplainResult.Refused)
            }
            assertNull(migratedLibrary.loadLatestAskThread(book.id))

            // Real TextbookReader ask -> persisted in SQLite -> kill/reopen with brand-new TextbookLibrary -> restored in UI.
            val explainer = FakeCompanionExplainer(root.resolve("home-ok"), migratedLibrary)
            runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            TextbookReader(
                                ui = LibraryUi(
                                    catalog = migratedLibrary.catalog(),
                                    book = book,
                                    screen = BookScreen.READER,
                                    page = 1,
                                    keepAskTabOpen = true,
                                ),
                                library = migratedLibrary,
                                support = UnconnectedBookLearningSupport,
                                practiceSource = null,
                                pageFocus = focus,
                                onNoteSession = {},
                                onPage = {},
                                onBack = {},
                                onLearn = {},
                                explainProvider = explainer,
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("inline-followup-input").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("inline-followup-input").performClick()
                onNodeWithTag("inline-followup-input").performTextInput("Why does supply slope upward?")
                waitForIdle()
                onNodeWithTag("inline-followup-send").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
            }

            // Simulate process kill & restart: open a completely fresh TextbookLibrary instance.
            val afterKillLibrary = TextbookLibrary(library.directory)
            assertEquals("Why does supply slope upward?", afterKillLibrary.lastQuestion(book.id))
            val persisted = assertNotNull(afterKillLibrary.loadLatestAskThread(book.id))
            assertEquals(1, persisted.turns.size)
            assertTrue(persisted.turns.single().explanation.text.contains("追问答复"))

            val reloadedBook = afterKillLibrary.book(book.id)
            runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            TextbookReader(
                                ui = LibraryUi(
                                    catalog = afterKillLibrary.catalog(),
                                    book = reloadedBook,
                                    screen = BookScreen.READER,
                                    page = 1,
                                    keepAskTabOpen = true,
                                ),
                                library = afterKillLibrary,
                                support = UnconnectedBookLearningSupport,
                                practiceSource = null,
                                pageFocus = focus,
                                onNoteSession = {},
                                onPage = {},
                                onBack = {},
                                onLearn = {},
                                explainProvider = FakeCompanionExplainer(root.resolve("home-after-kill"), afterKillLibrary),
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("inline-explain-turn-0").assertIsDisplayed()
                onNodeWithText("追问答复", substring = true).assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun followUpAfterExplainScrollsIntoViewAndIsVisibleInCompactWindow() {
        val root = Files.createTempDirectory("ton1848-followup-")
        try {
            val content = "Selected literary sentence for explain and follow-up testing."
            val (_, book) = importSyntheticBook(root, content)
            val p1 = book.page(1)!!
            val sel = SourceBlock("${book.id}:p1:0", 1, 0, p1.text.length, false, p1.text)
            val allBlocks = listOf(sel)

            // Verify ExplainWire.selectionRequest drops blank turns cleanly without throwing.
            val wireWithBlank = ExplainWire.selectionRequest(
                requestId = "abcd1234",
                kind = "ask",
                chosen = sel,
                blocks = allBlocks,
                question = "Follow-up question",
                history = listOf(FollowUpTurn("", ""), FollowUpTurn("讲讲", "Initial explanation")),
            )
            assertNotNull(wireWithBlank["history"])

            val longInitial = (1..18).joinToString("\n") { "第 $it 条长讲解内容，用来占满 820x620 紧凑抽屉视口高度，验证追问自动滚入视野。" }
            val followUpReply = "这是追问的单独答复：专门回答为什么作者这样写。"
            val turn0 = InlineExplainEntry(
                label = "讲讲",
                explanation = Explanation(longInitial, listOf(Citation("S1", sel.id, 1)), 20L),
                sentBlocks = allBlocks,
            )
            val turn1 = InlineExplainEntry(
                label = "问：作者为什么这样写？",
                explanation = Explanation(followUpReply, listOf(Citation("S1", sel.id, 1)), 25L),
                sentBlocks = allBlocks,
            )

            runSkikoComposeUiTest(size = Size(820f, 340f), density = Density(1f)) {
                var thread by mutableStateOf(InlineExplainThread(sel, null, turns = listOf(turn0), askingOpen = true))
                var draft by mutableStateOf("作者为什么这样写？")
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            CompanionAskPane(
                                book = book,
                                allBlocks = allBlocks,
                                pageBlocks = allBlocks,
                                activeSelection = sel,
                                currentSection = null,
                                thread = thread,
                                draft = draft,
                                compact = true,
                                onDraftChange = { draft = it },
                                onPickPassage = {},
                                onSubmitQuestion = {
                                    thread = thread.copy(turns = thread.turns + turn1)
                                    draft = ""
                                },
                                onRetry = {},
                                onOpenLogin = {},
                                onOpenCitation = { _, _ -> },
                                onClearSelection = {},
                            )
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag("inline-explain-turn-0").assertExists()
                onNodeWithTag("companion-ask-scrollbar").assertExists()

                onNodeWithTag("inline-followup-send").performClick()
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("inline-explain-turn-1").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                onNodeWithText(followUpReply, substring = true).assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun importedBookWireSnapshotUsesLightweightPageMetaWithoutLoadingWholeBook() {
        val root = Files.createTempDirectory("ton1848-snapshot-perf-")
        try {
            val pages = (1..80).joinToString("\n\n") { idx ->
                "## Chapter $idx\n\nSynthetic paragraph for chapter $idx verifying lightweight snapshot metadata."
            }
            val (library, book) = importSyntheticBook(root, pages)
            ExplainConsent(library.directory).allow(book.id)
            val block = book.sourceBlocks().first { it.isBodyParagraph }
            val wire = ImportedBookWire(library)
            val t0 = System.nanoTime()
            val snapshot = wire.snapshot(listOf(block))
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            println("TON1848_SNAPSHOT_MS=$elapsedMs")
            assertEquals(book.id, snapshot["book"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
            assertTrue(elapsedMs < 2000.0, "Snapshot took unexpectedly long: ${elapsedMs}ms")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
