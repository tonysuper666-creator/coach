package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.CoachTheme
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudyModeInteractionRegressionTest {

    private open class FakeStudyExplainer(
        home: Path,
        library: TextbookLibrary,
        private val onModeFetch: suspend (
            bookId: String,
            mode: String,
            input: JsonObject,
            onProgress: (chars: Int, firstTokenMs: Long?, delta: String) -> Unit,
        ) -> CourseServerCallResult = { _, _, _, _ -> CourseServerCallResult(null, "offline") },
    ) : BookExplainer(DesktopConnectionSettings(home = home, environment = { null }), library) {
        init {
            identity = DesktopIdentity("http://127.0.0.1:1", PairedCredentials("dev-test", "tok-test"))
            loaded = true
            revoked = false
        }

        override suspend fun load() {
            loaded = true
        }

        override suspend fun fetchChapterCompanion(
            requestJson: JsonObject,
            blocks: List<SourceBlock>,
        ): ChapterCompanionFetchResult = ChapterCompanionFetchResult.Failed("skip legacy", ExplainTrouble.Other)

        override suspend fun fetchStudyModePack(
            bookId: String,
            mode: String,
            input: JsonObject,
            onProgress: (chars: Int, firstTokenMs: Long?, delta: String) -> Unit,
        ): CourseServerCallResult = onModeFetch(bookId, mode, input, onProgress)
    }

    private fun seed24ChapterLibrary(root: Path): Triple<TextbookLibrary, Textbook, ChapterSectionGroup> {
        val libDir = Files.createDirectories(root.resolve("lib"))
        val library = TextbookLibrary(libDir)
        library.catalog()
        val pages = (1..620).map { p ->
            BookPage(number = p, text = "Page $p synthetic health economics adverse selection and risk pooling.")
        }
        val lessons = buildList {
            for (ch in 1..24) {
                val p = 10 + (ch - 1) * 24
                add(
                    BookLesson(
                        id = "ch-$ch-1",
                        title = "$ch.1 Core Model $ch",
                        blocks = listOf(
                            SourceBlock("c-$ch-1h", p, 0, 14, true, "$ch.1 Core Model $ch"),
                            SourceBlock("c-$ch-1b", p + 2, 15, 70, false, "Adverse selection model chapter $ch section 1."),
                        ),
                    ),
                )
                add(
                    BookLesson(
                        id = "ch-$ch-2",
                        title = "$ch.2 Empirical Evidence $ch",
                        blocks = listOf(
                            SourceBlock("c-$ch-2h", p + 11, 0, 18, true, "$ch.2 Empirical Evidence $ch"),
                            SourceBlock("c-$ch-2b", p + 12, 19, 80, false, "Empirical insurance demand elasticity in chapter $ch."),
                        ),
                    ),
                )
            }
        }
        DriverManager.getConnection("jdbc:sqlite:${libDir.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { st ->
                st.execute("INSERT OR REPLACE INTO books(id, title, format, reading_page, touched) VALUES('bk-24', 'Health Economics', 'pdf', 178, 1000)")
            }
        }
        val chapters = (1..24).map { chapter ->
            val start = 10 + (chapter - 1) * 24
            BookTocNode("ch-$chapter", null, chapter - 1, 1, TocNodeKind.CHAPTER, chapter.toString(),
                "Chapter $chapter", start, if (chapter == 24) pages.last().number else start + 23,
                source = HeadingSource.PDF_OUTLINE)
        }
        val structure = BookStructure(pageRoles = emptyMap(), sections = emptyList(), source = "PDF_OUTLINE", tocNodes = chapters).apply {
            resolvedLessons = lessons
        }
        val book = Textbook(
            id = "bk-24",
            title = "Health Economics",
            format = "pdf",
            pages = pages,
            readingPage = 178,
            structure = structure,
        )
        val ch8FirstLessonIdx = lessons.indexOfFirst { it.id == "ch-8-1" }
        val ch8Group = ChapterCompanionGrouping.groupForSection(book, ch8FirstLessonIdx)!!
        return Triple(library, book, ch8Group)
    }

    @Test
    fun originalOnlySuppressesAutomaticRequestsOnOpenAndAfterChangingDetail() {
        val root = Files.createTempDirectory("original-only-companion-")
        try {
            val (library, book, group) = seed24ChapterLibrary(root)
            val calls = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val explainer = object : FakeStudyExplainer(root, library, { _, _, _, _ ->
                calls.add("mode")
                CourseServerCallResult(null, "offline")
            }) {
                init { loaded = false }
                override suspend fun load() { calls.add("load"); super.load() }
                override suspend fun explainLesson(blocks: List<SourceBlock>, bookPages: Int, requestId: String): ExplainResult {
                    calls.add("page")
                    return super.explainLesson(blocks, bookPages, requestId)
                }
                override suspend fun fetchChapterCompanion(requestJson: JsonObject, blocks: List<SourceBlock>): ChapterCompanionFetchResult {
                    calls.add("chapter")
                    return super.fetchChapterCompanion(requestJson, blocks)
                }
            }
            val section = book.lessons.indexOfFirst { it.id == group.sections.first().id }
            var pref by mutableStateOf(BookStudyPreference(book.id, BookStudyMode.BOOK_MAP, detailLevel = ExplainDetailLevel.ORIGINAL_ONLY))
            var open by mutableStateOf(true)
            runSkikoComposeUiTest(size = Size(820f, 1050f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true) {
                            if (open) SingleBookCompanionPanel(
                                book = book, currentPage = group.firstPage, currentSectionIndex = section,
                                explainer = explainer, library = library, studyPreference = pref,
                                draft = ChapterPracticeDraft(bookId = book.id, tocNodeId = group.chapterId, page = group.firstPage),
                                onDraftChange = {}, onOpenAnchor = {}, onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {}, onOpenLogin = {},
                            )
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag("mode-surface-BOOK_MAP").assertExists()
                assertTrue(calls.isEmpty(), calls.toString())
                pref = pref.copy(detailLevel = ExplainDetailLevel.BRIEF)
                waitUntil(timeoutMillis = 5_000) { "mode" in calls && "page" in calls && "load" in calls }
                onNodeWithTag("page-explanation-error").assertTextContains("段落位置无法核对", substring = true)
                val before = calls.toList()
                pref = pref.copy(detailLevel = ExplainDetailLevel.ORIGINAL_ONLY)
                waitForIdle()
                open = false
                waitForIdle()
                pref = pref.copy(mode = BookStudyMode.READ_THROUGH)
                open = true
                waitForIdle()
                onNodeWithText("只看原文 · 可以在学法中调整讲解详略。").assertExists()
                assertEquals(before, calls.toList())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun questionDrivenDoesNotTriggerRequestPerKeystrokeOnlyOnSubmit() {
        val root = Files.createTempDirectory("qdriven-debounce-")
        try {
            val (library, book, _) = seed24ChapterLibrary(root)
            val ch8FirstIdx = book.lessons.indexOfFirst { it.id == "ch-8-1" }
            val requests = mutableListOf<String>()
            val explainer = FakeStudyExplainer(root, library) { _, mode, input, _ ->
                if (mode == "QUESTION_DRIVEN") {
                    val q = input["inquiryQuestion"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    requests += q
                    val packJson = """{"questionFocus":"$q","directConclusion":"Direct evidence for $q","evidenceHits":[{"hitId":"h1","bodyJumpPage":178,"quoteAnchor":"Adverse selection model chapter 8 section 1","howItAnswersQuestion":"Shows mechanism","limitOrCounterpoint":"Static setting"}],"argumentationScaffold":["Point 1"],"antiGhostwriteNotice":"Outline only"}"""
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(packJson) as JsonObject
                    CourseServerCallResult(obj, null)
                } else {
                    CourseServerCallResult(buildJsonObject { }, null)
                }
            }

            runSkikoComposeUiTest(size = Size(820f, 1050f)) {
                var pref by mutableStateOf(
                    BookStudyPreference(
                        bookId = book.id,
                        mode = BookStudyMode.QUESTION_DRIVEN,
                        userLocked = true,
                        detailLevel = ExplainDetailLevel.DETAILED,
                        explainLang = ExplainLanguage.BILINGUAL,
                        inquiryQuestion = "",
                    ),
                )
                var draft by mutableStateOf(ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-8-1", page = 178))

                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            SingleBookCompanionPanel(
                                book = book,
                                currentPage = 178,
                                currentSectionIndex = ch8FirstIdx,
                                explainer = explainer,
                                library = library,
                                draft = draft,
                                onDraftChange = { draft = it },
                                onOpenAnchor = {},
                                studyPreference = pref,
                                onUpdateStudyPreference = { next, _ -> pref = next },
                                onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {},
                                onOpenLogin = {},
                            )
                        }
                    }
                }
                waitForIdle()
                assertTrue(requests.isEmpty())

                onNodeWithTag("question-driven-input").performClick()
                for (ch in listOf("a", "b", "c", "d", "e")) {
                    onNodeWithTag("question-driven-input").performTextInput(ch)
                    waitForIdle()
                }
                assertEquals(emptyList(), requests, "Typing abc/abcd/abcde must not fire online requests per keystroke")
                onNodeWithTag("question-driven-pending-hint").assertIsDisplayed()

                onNodeWithTag("question-driven-submit").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { requests.size == 1 }
                waitForIdle()
                assertEquals(listOf("abcde"), requests)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bookMapBatchSectionClickJumpsToRealChapterPageNeverPageOne() {
        val root = Files.createTempDirectory("batch-sec-page-")
        try {
            val (library, book, _) = seed24ChapterLibrary(root)
            val ch8FirstIdx = book.lessons.indexOfFirst { it.id == "ch-8-1" }
            val legacyBatchChapterJson = """{"chapterNum":8,"chapterTitle":"8.1 Core Model 8","oneLiner":"第八章核心结论","coreClaims":[{"claim":"核心论断","importance":"CORE","pdfPage":178,"quoteAnchor":"Adverse selection model chapter 8 section 1"}],"sections":[{"sectionTitle":"8.1 Core Model 8","summary":"基础定义","skipUnlessExam":false},{"sectionTitle":"8.2 Empirical Evidence 8","summary":"实证检验","skipUnlessExam":false,"pdfPage":1}]}"""
            StudyModeStore.saveModePackJson(
                library = library,
                bookId = book.id,
                chapterNum = 8,
                mode = BookStudyMode.BOOK_MAP,
                detailLevel = ExplainDetailLevel.BRIEF,
                explainLang = ExplainLanguage.BILINGUAL,
                inquiryQuestion = "",
                packJson = legacyBatchChapterJson,
            )

            runSkikoComposeUiTest(size = Size(820f, 1050f)) {
                var pref by mutableStateOf(
                    BookStudyPreference(
                        bookId = book.id,
                        mode = BookStudyMode.BOOK_MAP,
                        userLocked = true,
                        detailLevel = ExplainDetailLevel.BRIEF,
                        explainLang = ExplainLanguage.BILINGUAL,
                    ),
                )
                var jumpedPage by mutableStateOf(-1)
                val explainer = FakeStudyExplainer(root, library)

                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            SingleBookCompanionPanel(
                                book = book,
                                currentPage = 178,
                                currentSectionIndex = ch8FirstIdx,
                                explainer = explainer,
                                library = library,
                                draft = ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-8-1", page = 178),
                                onDraftChange = {},
                                onOpenAnchor = { jumpedPage = it.pdfPage },
                                studyPreference = pref,
                                onUpdateStudyPreference = { next, _ -> pref = next },
                                onJumpPage = { jumpedPage = it },
                                onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {},
                                onOpenLogin = {},
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("book-map-section-0").assertExists() }.isSuccess }
                onNodeWithTag("book-map-section-0").performScrollTo().performClick()
                assertEquals(178, jumpedPage, "Missing pdfPage in batch section must resolve to section p.178, never p.1")

                onNodeWithTag("book-map-section-1").performScrollTo().performClick()
                assertEquals(189, jumpedPage, "Section 8.2 must jump to its real start page p.189, never p.1")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun streamingDeltaRendersProgressivelyBeforePackCompletes() {
        val root = Files.createTempDirectory("stream-delta-ui-")
        try {
            val (library, book, _) = seed24ChapterLibrary(root)
            val ch8FirstIdx = book.lessons.indexOfFirst { it.id == "ch-8-1" }
            val finishGate = CompletableDeferred<CourseServerCallResult>()
            val explainer = FakeStudyExplainer(root, library) { _, mode, _, onProgress ->
                assertEquals("READ_THROUGH", mode)
                onProgress(48, 120L, """{"learningArc":"本章主线：逆向选择引发市场失灵"""")
                onProgress(160, null, ""","sections":[{"sectionId":"ch-8-1","title":"8.1 柠檬市场","pdfPage":178,"plainExplanation":"好车逐层退出市场"""")
                finishGate.await()
            }

            runSkikoComposeUiTest(size = Size(820f, 1050f)) {
                val pref = BookStudyPreference(
                    bookId = book.id,
                    mode = BookStudyMode.READ_THROUGH,
                    userLocked = true,
                    detailLevel = ExplainDetailLevel.DETAILED,
                    explainLang = ExplainLanguage.BILINGUAL,
                )
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            SingleBookCompanionPanel(
                                book = book,
                                currentPage = 178,
                                currentSectionIndex = ch8FirstIdx,
                                explainer = explainer,
                                library = library,
                                draft = ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-8-1", page = 178),
                                onDraftChange = {},
                                onOpenAnchor = {},
                                studyPreference = pref,
                                onUpdateStudyPreference = { _, _ -> },
                                onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {},
                                onOpenLogin = {},
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching {
                        onNodeWithTag("mode-streaming-preview").assertTextContains("好车逐层退出市场", substring = true)
                    }.isSuccess
                }
                onNodeWithTag("mode-streaming-preview").assertTextContains("本章主线：逆向选择引发市场失灵", substring = true)

                val finalJson = """{"learningArc":"本章主线：逆向选择引发市场失灵","sections":[{"sectionId":"ch-8-1","title":"8.1 柠檬市场","pdfPage":178,"quoteAnchor":"Adverse selection model chapter 8 section 1","keyConcept":"逆向选择","plainExplanation":"好车逐层退出市场导致均衡萎缩"}]}"""
                finishGate.complete(CourseServerCallResult(kotlinx.serialization.json.Json.parseToJsonElement(finalJson) as JsonObject, null))
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag("live-read-through-card").assertIsDisplayed() }.isSuccess
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun onlineDiagnosticSkipAndProblemDrivenPersistAcrossModeSwitchAndRestart() {
        val root = Files.createTempDirectory("online-state-persist-")
        try {
            val (library, book, ch8Group) = seed24ChapterLibrary(root)
            val ch8FirstIdx = book.lessons.indexOfFirst { it.id == "ch-8-1" }
            val diagJson = """{"skipPolicy":"Pass gate to skip section 8.1","diagnosticGates":[{"gateId":"g1","sectionTitle":"8.1 Core Model 8","targetConcept":"Adverse selection","questionType":"CONCEPT_CHECK","questionPrompt":"Who exits first?","options":["Low-risk buyers","High-risk buyers"],"correctIndex":0,"passSkipNote":"Skip 8.1","missRemediation":"Review p.178","missJumpPdfPage":178,"quoteAnchor":"Adverse selection model chapter 8 section 1"}]}"""
            val probJson = """{"strategyNote":"Attempt Ex 8.1 first","problems":[{"problemId":"p1","title":"Ex 8.1","exercisePdfPage":189,"exerciseQuoteAnchor":"Empirical insurance demand elasticity in chapter 8","attemptPrompt":"Set up indifference condition","scaffoldingHints":["Step 1: Compute pooled premium","Step 2: Compare willingness to pay"],"keyDerivationCheck":"E[L] = q*L_H + (1-q)*L_L","bodyPdfPage":178,"quoteAnchor":"Adverse selection model chapter 8 section 1","answerOutline":["Compare actuarially fair rates"]}]}"""
            val mapJson = """{"oneLiner":"Chapter 8 map","sections":[]}"""
            StudyModeStore.saveModePackJson(library, book.id, 8, BookStudyMode.DIAGNOSTIC_SKIP, ExplainDetailLevel.DETAILED, ExplainLanguage.BILINGUAL, "", diagJson)
            StudyModeStore.saveModePackJson(library, book.id, 8, BookStudyMode.PROBLEM_DRIVEN, ExplainDetailLevel.DETAILED, ExplainLanguage.BILINGUAL, "", probJson)
            StudyModeStore.saveModePackJson(library, book.id, 8, BookStudyMode.BOOK_MAP, ExplainDetailLevel.DETAILED, ExplainLanguage.BILINGUAL, "", mapJson)

            val explainer = FakeStudyExplainer(root, library)

            runSkikoComposeUiTest(size = Size(820f, 1050f)) {
                var showPanel by mutableStateOf(true)
                var pref by mutableStateOf(
                    BookStudyPreference(
                        bookId = book.id,
                        mode = BookStudyMode.DIAGNOSTIC_SKIP,
                        userLocked = true,
                        detailLevel = ExplainDetailLevel.DETAILED,
                        explainLang = ExplainLanguage.BILINGUAL,
                    ),
                )
                var draft by mutableStateOf(ChapterPracticeDraft(bookId = book.id, tocNodeId = ch8Group.chapterId, page = 178))

                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            if (showPanel) {
                                SingleBookCompanionPanel(
                                    book = book,
                                    currentPage = 178,
                                    currentSectionIndex = ch8FirstIdx,
                                    explainer = explainer,
                                    library = library,
                                    draft = draft,
                                    onDraftChange = { updated ->
                                        draft = updated
                                        library.saveChapterPracticeDraft(book.id, ch8Group.chapterId, book.pages.size, updated)
                                    },
                                    onOpenAnchor = {},
                                    studyPreference = pref,
                                    onUpdateStudyPreference = { next, _ -> pref = next },
                                    onCommitQuizAnswer = { _, _, _, _ -> },
                                    onAskAboutText = {},
                                    onOpenLogin = {},
                                )
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("diagnostic-live-skip-progress").assertExists() }.isSuccess }
                onNodeWithTag("diagnostic-live-skip-progress").assertTextContains("0 / 1", substring = true)
                onNodeWithTag("diagnostic-live-opt-g1-0").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("diagnostic-live-skip-progress").assertExists() }.isSuccess }
                onNodeWithTag("diagnostic-live-skip-progress").assertTextContains("1 / 1", substring = true)
                assertTrue("skip-ch-8-1" in draft.pretestSubmitted, "Passing gate must record skip-ch-8-1 progress")
                assertTrue("diag-live-8-g1" in draft.pretestSubmitted)

                // Switch to PROBLEM_DRIVEN and reveal Step 1 hint
                pref = pref.copy(mode = BookStudyMode.PROBLEM_DRIVEN)
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("problem-live-hint-p1").assertExists() }.isSuccess }
                onNodeWithTag("problem-live-hint-p1").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("提示：Step 1: Compute pooled premium").assertExists() }.isSuccess }
                onNodeWithText("提示：Step 1: Compute pooled premium").assertIsDisplayed()
                assertEquals(1, draft.exerciseHintLevels["prob-live-8-p1"])

                // Simulate mode switch to BOOK_MAP + full restart reloading draft from SQLite
                pref = pref.copy(mode = BookStudyMode.BOOK_MAP)
                waitForIdle()
                showPanel = false
                waitForIdle()

                draft = library.loadChapterPracticeDraft(book.id, ch8Group.chapterId)!!
                pref = pref.copy(mode = BookStudyMode.DIAGNOSTIC_SKIP)
                showPanel = true
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("diagnostic-live-skip-progress").assertExists() }.isSuccess }

                onNodeWithTag("diagnostic-live-skip-progress").assertTextContains("1 / 1", substring = true)
                onNodeWithText("✓ 已掌握（已标为跳过「8.1 Core Model 8」）：Skip 8.1").assertIsDisplayed()

                pref = pref.copy(mode = BookStudyMode.PROBLEM_DRIVEN)
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("提示：Step 1: Compute pooled premium").assertExists() }.isSuccess }
                onNodeWithText("提示：Step 1: Compute pooled premium").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
