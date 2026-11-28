package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.CoachTheme
import com.coach.ui.v7.DeskTypeControl
import com.coach.ui.v7.LocalDeskTypeControl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NonIntrusiveReaderTest {

    private fun multiSectionMarkdown(prefix: String = "Principles"): String = buildString {
        for (sec in 1..5) {
            if (sec > 1) appendLine("\u000c")
            appendLine("# Chapter $sec $prefix Topic")
            appendLine()
            repeat(6) { p ->
                appendLine("Paragraph $p of unit $sec explains marginal incentives, opportunity cost, and equilibrium adjustment clearly.")
                appendLine()
            }
        }
    }

    private fun selectPhraseOnReaderText(
        scope: SkikoComposeUiTest,
        phrase: String,
    ): Rect {
        val textNode = scope.onNode(hasTestTag("reader-text") and hasText(phrase, substring = true))
        val layouts = mutableListOf<TextLayoutResult>()
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val idx = layout.layoutInput.text.text.indexOf(phrase)
        assertTrue(idx >= 0, "Expected phrase '$phrase' in reader-text")
        val firstBox = layout.getBoundingBox(idx)
        val lastBox = layout.getBoundingBox(idx + phrase.lastIndex)
        val coords = textNode.fetchSemanticsNode().layoutInfo.coordinates
        val startRoot = coords.localToRoot(Offset(firstBox.left + 2f, firstBox.center.y))
        val endRoot = coords.localToRoot(Offset(lastBox.right - 2f, lastBox.center.y))
        scope.onRoot().performMouseInput {
            moveTo(startRoot)
            press()
            moveTo((startRoot + endRoot) / 2f)
            moveTo(endRoot)
            release()
        }
        scope.waitUntil(timeoutMillis = 10_000) {
            scope.onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty()
        }
        val tl = coords.localToRoot(Offset(firstBox.left, firstBox.top))
        val br = coords.localToRoot(Offset(lastBox.right, lastBox.bottom))
        return Rect(minOf(tl.x, br.x), minOf(tl.y, br.y), maxOf(tl.x, br.x), maxOf(tl.y, br.y))
    }

    @Test
    fun selectionPillPositionsNearSelectionAndDismissesAfterAction() {
        // Pure positioning contract: above by default, falls back below near top edge.
        val above = calculateSelectionPillOffset(
            selectionRect = Rect(300f, 300f, 500f, 324f),
            pillSize = IntSize(260, 36),
            containerSize = IntSize(1000, 700),
            topSafePx = 12,
            bottomSafePx = 12,
            gapPx = 8,
            horizontalPaddingPx = 12,
        )
        assertEquals(256, above.y, "Pill should sit above selection when space permits")

        val below = calculateSelectionPillOffset(
            selectionRect = Rect(300f, 20f, 500f, 44f),
            pillSize = IntSize(260, 36),
            containerSize = IntSize(1000, 700),
            topSafePx = 12,
            bottomSafePx = 12,
            gapPx = 8,
            horizontalPaddingPx = 12,
        )
        assertEquals(52, below.y, "Pill should fall back below selection near top edge")

        val root = Files.createTempDirectory("coach-ton1984-pill-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val mdFile = root.resolve("book.md").also { file ->
                Files.writeString(
                    file,
                    """
                    # Chapter 1 Opening
                    First line token AlphaZero explains initial trade equilibrium cleanly across markets.

                    Paragraph two discusses supply elasticity and household consumption across time.

                    Paragraph three discusses capital accumulation and saving rates cleanly.

                    Paragraph four discusses BetaBottom equilibrium dynamics and marginal efficiency.
                    """.trimIndent(),
                )
            }
            val book = library.importBook(mdFile)
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(book.id, 1)
            }
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                // 1. Selecting near top falls back below the selected line (never occludes first line).
                val topSelectionRect = selectPhraseOnReaderText(this, "AlphaZero explains")
                val topPillRect = onNodeWithTag("selection-explain-toolbar").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    topPillRect.top >= topSelectionRect.bottom - 2f || topPillRect.bottom <= topSelectionRect.top + 2f,
                    "Pill $topPillRect must not overlap top selection $topSelectionRect",
                )

                // Clicking 讲讲 auto-dismisses the selection pill.
                onNodeWithTag("selection-action-explain").performClick()
                waitForIdle()
                onNodeWithTag("selection-explain-toolbar").assertDoesNotExist()
                onNodeWithTag("companion-close").performClick()
                waitForIdle()

                // 2. Selecting lower paragraph places pill above selection, and 笔记 dismisses pill too.
                val lowerRect = selectPhraseOnReaderText(this, "BetaBottom equilibrium")
                val lowerPillRect = onNodeWithTag("selection-explain-toolbar").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    lowerPillRect.bottom <= lowerRect.top + 4f,
                    "Pill $lowerPillRect should sit above lower selection $lowerRect",
                )
                onNodeWithTag("selection-action-note").performClick()
                waitForIdle()
                onNodeWithTag("selection-explain-toolbar").assertDoesNotExist()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun companionDrawerDefaultClosedAndNarrowDockedAndBottomSheetCollapsedToHandle() {
        val root = Files.createTempDirectory("coach-ton1984-drawer-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val book = library.importBook(root.resolve("book.md").also { Files.writeString(it, multiSectionMarkdown()) })
            // Non-READ_THROUGH mode must NOT auto-open companion drawer.
            StudyModeStore.save(
                library,
                BookStudyPreference(book.id, BookStudyMode.BOOK_MAP, userLocked = true),
            )
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(book.id, 1)
            }

            // Narrow desktop 1120dp (1000..1279dp): default closed, opens docked side-by-side, toggleable to overlay.
            runSkikoComposeUiTest(size = Size(1120f, 640f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertDoesNotExist()

                onNodeWithTag("textbook-reader").performKeyInput { pressKey(Key.L) }
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertIsDisplayed()
                val pageBounds = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                val drawerBounds = onNodeWithTag("immersive-companion-drawer").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    pageBounds.right <= drawerBounds.left + 1f,
                    "Narrow desktop companion must dock side-by-side without overlapping body: page.right=${pageBounds.right}, drawer.left=${drawerBounds.left}",
                )
                onNodeWithTag("companion-dock-toggle").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithTag("companion-dock-toggle").performClick()
                waitForIdle()
                val reDockedPage = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                val reDockedDrawer = onNodeWithTag("immersive-companion-drawer").fetchSemanticsNode().boundsInRoot
                assertTrue(reDockedPage.right <= reDockedDrawer.left + 1f)
            }

            // Small window < 1000dp (906x546): collapses to bottom sheet handle; clicking handle opens drawer.
            runSkikoComposeUiTest(size = Size(906f, 546f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertDoesNotExist()
                onNodeWithTag("companion-bottom-sheet-handle").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertIsDisplayed()
                onNodeWithTag("companion-bottom-sheet-handle").assertDoesNotExist()
            }

            // Course entry without unsent draft stays closed; unsent draft opens drawer.
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                val courseNoDraft = CourseReaderContext(
                    courseId = "c1",
                    courseTitle = "Econ 101",
                    topicId = "t1",
                    topicTitle = "Topic 1",
                    weekLabel = CourseExamPoints.LABEL,
                    authorityNotice = "",
                    examProfile = CourseExamProfile(examEnabled = true),
                    orderedSources = emptyList(),
                    pack = null,
                    resumeDraft = null,
                )
                setContent {
                    CoachTheme {
                        TextbookReader(
                            ui = LibraryUi(book = book, screen = BookScreen.READER, page = 1),
                            library = library,
                            support = UnconnectedBookLearningSupport,
                            practiceSource = null,
                            pageFocus = remember { FocusRequester() },
                            onNoteSession = {},
                            onPage = {},
                            onBack = {},
                            onLearn = {},
                            courseContext = courseNoDraft,
                        )
                    }
                }
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertDoesNotExist()
            }

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                val unsentDraft = ChapterPracticeDraft(
                    bookId = book.id,
                    tocNodeId = book.lessons.first().id,
                    companionOpen = true,
                    practiceTextDrafts = mapOf("q1" to "unfinished answer"),
                )
                setContent {
                    CoachTheme {
                        TextbookReader(
                            ui = LibraryUi(book = book, screen = BookScreen.READER, page = 1),
                            library = library,
                            support = UnconnectedBookLearningSupport,
                            practiceSource = null,
                            pageFocus = remember { FocusRequester() },
                            onNoteSession = {},
                            onPage = {},
                            onBack = {},
                            onLearn = {},
                            resumeDraft = unsentDraft,
                        )
                    }
                }
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun revokedConnectionDoesNotForceOpenDrawerShowsDismissibleNoticeAndPromptsOnAiAction() {
        val root = Files.createTempDirectory("coach-ton1984-revoked-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val book = library.importBook(root.resolve("book.md").also { Files.writeString(it, multiSectionMarkdown()) })
            val explainer = object : BookExplainer(DesktopConnectionSettings(root.resolve("conn"), { null }), library) {
                init {
                    identity = null
                    loaded = true
                    revoked = true
                }

                override suspend fun load() {
                    loaded = true
                }
            }
            var reloginCount = 0
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    CoachTheme {
                        CompositionLocalProvider(LocalBookRelogin provides { _ -> reloginCount++ }) {
                            TextbookReader(
                                ui = LibraryUi(book = book, screen = BookScreen.READER, page = 1, source = book.lessons.first()),
                                library = library,
                                support = UnconnectedBookLearningSupport,
                                practiceSource = null,
                                pageFocus = remember { FocusRequester() },
                                onNoteSession = {},
                                onPage = {},
                                onBack = {},
                                onLearn = {},
                                explainProvider = explainer,
                                openPairing = false,
                            )
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertDoesNotExist()
                onNodeWithTag("reader-connection-status-dot").assertExists()
                onNodeWithTag("reader-revoked-notice").assertIsDisplayed()
                onNodeWithTag("revoked-notice-dismiss").performClick()
                waitForIdle()
                onNodeWithTag("reader-revoked-notice").assertDoesNotExist()

                selectPhraseOnReaderText(this, "marginal incentives")
                onNodeWithTag("selection-action-explain").performClick()
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertIsDisplayed()
                onNodeWithTag("inline-explain-error").assertTextContains("重新登录", substring = true)
                onNodeWithText("重新登录").performClick()
                assertEquals(1, reloginCount)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun studyModePopoverIsCompactAndWithinHalfWindowHeight() {
        val root = Files.createTempDirectory("coach-ton1984-studymode-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val book = library.importBook(root.resolve("book.md").also { Files.writeString(it, multiSectionMarkdown()) })
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(book.id, 1)
            }
            runSkikoComposeUiTest(size = Size(906f, 546f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()
                onNodeWithTag("textbook-reader").performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                onNodeWithTag("immersive-mode-trigger").performClick()
                waitForIdle()
                val bounds = onNodeWithTag("study-mode-popover").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(
                    bounds.height <= 546f / 2f + 1f,
                    "Study mode popover height ${bounds.height} must be <= half window height ${546f / 2f}",
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun hudGloballyAutoHidesAcrossNewBooksAndPersistsGlobalPinPreference() {
        val root = Files.createTempDirectory("coach-ton1984-hud-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val bookA = library.importBook(root.resolve("a.md").also { Files.writeString(it, multiSectionMarkdown("Alpha")) })
            val bookB = library.importBook(root.resolve("b.md").also { Files.writeString(it, multiSectionMarkdown("Beta")) })
            // Legacy per-book file is ignored.
            Files.writeString(library.directory.resolve("reader-hud-dismissed.txt"), "${bookA.id}\n")
            assertFalse(BookHudPreferenceStore(library.directory).isPinned())

            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(bookA.id, 1)
            }
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.book?.id == bookA.id }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertDoesNotExist()
                onNodeWithTag("immersive-progress-hairline").assertExists()

                runBlocking { controller.openBook(bookB.id, 1) }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.book?.id == bookB.id }
                waitForIdle()
                onNodeWithTag("immersive-top-hud").assertDoesNotExist()

                onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                onNodeWithTag("reader-page").performKeyInput { pressKey(Key.Tab) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("immersive-top-hud").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                onNodeWithTag("immersive-more-menu").performClick()
                waitForIdle()
                onNodeWithTag("immersive-pin-hud").performClick()
                waitForIdle()
                assertTrue(BookHudPreferenceStore(library.directory).isPinned())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun topNoticesShareSingleDismissibleQueueAndDamagedPagesLeavesTopBarBadge() {
        assertNull(
            selectActiveReaderTopNotice(
                workspaceError = "读取失败",
                bookErrorDismissed = false,
                sourceNotice = "无法确认原句",
                dismissedSourceNotice = null,
                damagedPages = listOf(2),
                damagedPagesDismissed = false,
                connectionRevoked = true,
                revokedNoticeDismissed = false,
                selectionPillVisible = true,
            ),
            "Notice queue must yield when selection pill is active",
        )

        val root = Files.createTempDirectory("coach-ton1984-notices-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val imported = library.importBook(root.resolve("book.md").also { Files.writeString(it, multiSectionMarkdown()) })
            val book = imported.copy(damagedPages = listOf(2, 5))
            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
                setContent {
                    CoachTheme {
                        TextbookReader(
                            ui = LibraryUi(book = book, screen = BookScreen.READER, page = 1),
                            library = library,
                            support = UnconnectedBookLearningSupport,
                            practiceSource = null,
                            pageFocus = remember { FocusRequester() },
                            onNoteSession = {},
                            onPage = {},
                            onBack = {},
                            onLearn = {},
                            workspaceError = "暂时打不开，请稍后再试一次。",
                            onRetryWorkspaceError = {},
                        )
                    }
                }
                waitForIdle()
                // Only highest priority notice shows at once.
                onNodeWithTag("book-error-banner").assertIsDisplayed()
                onNodeWithTag("damaged-pages").assertDoesNotExist()

                // Dismissing error reveals damaged-pages notice.
                onNodeWithTag("book-error-dismiss").performClick()
                waitForIdle()
                onNodeWithTag("book-error-banner").assertDoesNotExist()
                onNodeWithTag("damaged-pages").assertIsDisplayed()

                // Dismissing damaged-pages leaves a compact badge in the top HUD.
                onNodeWithTag("damaged-pages-dismiss").performClick()
                waitForIdle()
                onNodeWithTag("damaged-pages").assertDoesNotExist()
                // Move pointer to reading center so HUD is deterministically hidden on both macOS and Windows,
                // then restore focus to reader-page and reveal HUD via Tab.
                onRoot().performMouseInput { moveTo(Offset(640f, 400f)) }
                waitForIdle()
                onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                onNodeWithTag("reader-page").performKeyInput { pressKey(Key.Tab) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("immersive-top-hud").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                onNodeWithTag("damaged-pages-badge").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithTag("damaged-pages").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun proactiveWhisperAndCheckpointNeverShowAgainPersistsAndKeepsCompanionOnDemand() {
        val root = Files.createTempDirectory("coach-ton1984-proactive-")
        try {
            val library = TextbookLibrary(root.resolve("lib"))
            val mdFile = root.resolve("econ.md").also { file ->
                Files.writeString(file, multiSectionMarkdown("Econ"))
            }
            val book = library.importBook(mdFile)
            assertTrue(book.lessons.size >= 5)
            val sec1 = book.lessons[0]
            val sec2 = book.lessons[1]
            val group1 = ChapterCompanionGrouping.groupForSection(book, 0)
            if (group1 != null) {
                library.saveChapterCompanion(
                    book.id,
                    group1.chapterId,
                    BookCompanionPack(
                        bookId = book.id,
                        chapterId = group1.chapterId,
                        chapterTitle = group1.chapterTitle,
                        coreQuestion = "Marginal equilibrium",
                        keyPoints = listOf(BookCompanionKeyPoint("kp-1", sec1.id, "Marginal incentives and opportunity cost.")),
                        optionalPractice = listOf(
                            BookCompanionQuiz(
                                id = "cp-q1",
                                sectionId = sec1.id,
                                stem = "What stabilizes equilibrium adjustment?",
                                options = listOf("Marginal incentives", "Fixed noise"),
                                correctOption = "Marginal incentives",
                                explanation = "Marginal incentives drive equilibrium.",
                            ),
                        ),
                    ),
                )
            }
            library.recordMasteryEvidence(
                bookId = book.id,
                tocNodeId = sec2.id,
                page = sec2.firstPage,
                canonicalName = sec2.title,
                signal = LearnerSignalType.MISCONCEPTION_DIAG,
                errorClass = MisconceptionErrorClass.GRAPH_SHIFT_OR_ROTATION,
                syllabusWeight = 1.6,
            )

            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(book.id, sec1.firstPage)
            }
            runSkikoComposeUiTest(size = Size(1040f, 800f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalChapterDwellMs provides 20L) {
                        Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.READER }
                waitForIdle()

                // Dwell on sec1 then advance into sec2 -> triggers EndOfSectionCheckpoint + MarginWhisperStrip.
                onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                repeat(3) {
                    Thread.sleep(30)
                    mainClock.advanceTimeBy(40)
                    waitForIdle()
                }
                while (controller.state.value.page < sec2.firstPage) {
                    onNodeWithTag("reader-page").performKeyInput { pressKey(Key.DirectionRight) }
                    waitForIdle()
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("margin-whisper-strip").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("end-of-section-checkpoint").fetchSemanticsNodes().isNotEmpty()
                }

                onNodeWithTag("margin-whisper-never-again").performClick()
                waitForIdle()
                onRoot().performMouseInput { moveTo(Offset(520f, 400f)) }
                waitForIdle()
                onNodeWithTag("checkpoint-never-again").performClick()
                waitForIdle()

                onNodeWithTag("margin-whisper-strip").assertDoesNotExist()
                onNodeWithTag("end-of-section-checkpoint").assertDoesNotExist()
                val saved = ReadingSettings(library.directory)
                assertFalse(saved.proactiveWhispers)
                assertFalse(saved.endOfSectionCheckpoint)

                // Advancing across 3 more sections never triggers whisper or checkpoint again.
                repeat(3) {
                    repeat(2) {
                        Thread.sleep(30)
                        mainClock.advanceTimeBy(40)
                        waitForIdle()
                    }
                    onNodeWithTag("reader-page").performKeyInput { pressKey(Key.DirectionRight) }
                    waitForIdle()
                    onNodeWithTag("margin-whisper-strip").assertDoesNotExist()
                    onNodeWithTag("end-of-section-checkpoint").assertDoesNotExist()
                }

                // On-demand companion drawer entry + settings switches remain available.
                onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                onNodeWithTag("reader-page").performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                onNodeWithTag("immersive-companion-toggle").performClick()
                waitForIdle()
                onNodeWithTag("single-book-companion").assertIsDisplayed()
                onNodeWithTag("companion-mod-practice").assertIsDisplayed()
                onNodeWithTag("companion-tab-ask").assertIsDisplayed()

                onNodeWithTag("immersive-more-menu").performClick()
                waitForIdle()
                onNodeWithTag("v7-type-menu").performClick()
                waitForIdle()
                onNodeWithTag("reading-proactiveWhispers-OFF").assertIsSelected()
                onNodeWithTag("reading-endOfSectionCheckpoint-OFF").assertIsSelected()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
