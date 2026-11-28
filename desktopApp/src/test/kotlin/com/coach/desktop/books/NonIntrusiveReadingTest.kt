package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalTestApi::class)
class NonIntrusiveReadingTest {
    @Test fun newBookOpensOnThePageAndMuteStaysMuted() {
        val root = Files.createTempDirectory("coach-taste-strip-")
        try {
            val md = root.resolve("econ.md")
            Files.writeString(md, "# Chapter 1\n\nPrices adjust when scarcity rises.\n")
            val other = root.resolve("other.md")
            Files.writeString(other, "# Chapter 1\n\nAnother book starts here.\n")
            val library = TextbookLibrary(root.resolve("library"))
            val explainer = object : BookExplainer(library = library) {
                override suspend fun fetchChapterCompanion(requestJson: JsonObject, blocks: List<SourceBlock>): ChapterCompanionFetchResult =
                    ChapterCompanionFetchResult.Ready(samplePack())
            }
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(
                        controller = controller,
                        bookExplainer = explainer,
                        chooseBook = { md },
                        firstOpenGuide = true,
                    )
                }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.initialized }
                onNodeWithTag("upload-textbook").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag("first-taste").fetchSemanticsNodes().isNotEmpty()
                }
                val page = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                val strip = onNodeWithTag("first-taste").fetchSemanticsNode().boundsInRoot
                assertTrue(page.height > strip.height, "page ${page.height} strip ${strip.height}")
                assertTrue(strip.top > page.top + 80f, "strip top ${strip.top} page top ${page.top}")
                onNodeWithTag("reader-text").assertIsDisplayed()
                onNodeWithTag("first-taste-mute").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste").fetchSemanticsNodes().isEmpty()
                }
            }
            assertTrue(FirstOpenStore.isMuted(library.directory))
            assertFalse(FirstOpenStore.isDone(library.directory))
            val secondBook = library.importBook(other)
            val second = TextbookController(library)
            val secondId = secondBook.id
            runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(
                        controller = second,
                        bookExplainer = explainer,
                        chooseBook = { other },
                        firstOpenGuide = true,
                    )
                }
                waitUntil(timeoutMillis = 20_000) { second.state.value.initialized }
                // A new controller restores the last reader. The shelf card is not on that screen.
                assertEquals(0, onAllNodesWithTag("first-taste").fetchSemanticsNodes().size)
                second.openBook(secondId)
                waitUntil(timeoutMillis = 20_000) {
                    second.state.value.screen == BookScreen.READER && second.state.value.book?.id == secondId
                }
                waitForIdle()
                assertEquals(0, onAllNodesWithTag("first-taste").fetchSemanticsNodes().size)
                onNodeWithTag("reader-text").assertIsDisplayed()
            }
            assertTrue(FirstOpenStore.isMuted(library.directory))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun unansweredMethodButtonsAdvanceWithoutWritingAMiss() {
        val plan = ReadingChapterPlan(
            "ch",
            "章",
            listOf(ReadingSectionRef("s1", "第一节", 2), ReadingSectionRef("s2", "第二节", 3)),
        )
        val commits = AtomicInteger()
        val seen = mutableListOf<ChapterMethodProgress>()
        runSkikoComposeUiTest(size = Size(800f, 1400f), density = Density(1f)) {
            setContent {
                BeforeFlow(plan, emptyList(), ChapterMethodProgress("PRETEST")) { seen += it }
            }
            onNodeWithTag("reading-method-next").assertIsEnabled().performClick()
            waitForIdle()
        }
        runSkikoComposeUiTest(size = Size(800f, 700f), density = Density(1f)) {
            setContent {
                DuringFlow(
                    plan,
                    emptyList(),
                    ChapterMethodProgress("PAUSE"),
                    onProgress = { seen += it },
                    onCommitChoice = { _, _, _, _ -> commits.incrementAndGet() },
                    chapter = null,
                )
            }
            onNodeWithTag("reading-method-next").assertIsEnabled().performClick()
            waitForIdle()
        }
        runSkikoComposeUiTest(size = Size(800f, 700f), density = Density(1f)) {
            setContent {
                AfterFlow(
                    plan,
                    emptyList(),
                    ChapterMethodProgress("RECALL"),
                    onProgress = { seen += it },
                    onJumpPage = {},
                    onCommitChoice = { _, _, _, _ -> commits.incrementAndGet() },
                    chapter = null,
                )
            }
            onNodeWithTag("reading-method-next").assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(listOf("READ", "READ", "QUIZ"), seen.map { it.step })
        assertTrue(seen[0].answers.isEmpty())
        assertTrue(seen[0].wrongIds.isEmpty())
        assertEquals(1, seen[1].sectionIndex)
        assertTrue(seen[1].answers.isEmpty())
        assertTrue(seen[1].wrongIds.isEmpty())
        assertEquals("", seen[2].recall)
        assertTrue(seen[2].wrongIds.isEmpty())
        assertEquals(0, commits.get())
    }

    @Test fun consentGateStaysOffThePageUntilTheReaderAsks() {
        val root = Files.createTempDirectory("coach-taste-consent-")
        try {
            val md = root.resolve("econ.md")
            Files.writeString(md, "# Chapter 1\n\nPrices adjust when scarcity rises.\n")
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val allowed = AtomicInteger()
            val explainer = object : BookExplainer(library = library) {
                override suspend fun fetchChapterCompanion(requestJson: JsonObject, blocks: List<SourceBlock>): ChapterCompanionFetchResult {
                    if (allowed.get() == 0) {
                        return ChapterCompanionFetchResult.Failed("请先打开个人导入的书并允许讲解。")
                    }
                    return ChapterCompanionFetchResult.Ready(samplePack())
                }
            }
            runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
                setContent {
                    FirstTastePanel(
                        book = book,
                        explainer = explainer,
                        connectivity = CoachConnectivityStatus.ONLINE,
                        onReadBook = {},
                        onDone = {},
                        compact = true,
                        onAllowExplain = { allowed.incrementAndGet() },
                    )
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste-offer").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(0, onAllNodesWithText("请先打开个人导入的书并允许讲解", substring = true).fetchSemanticsNodes().size)
                assertEquals(0, onAllNodesWithText("再试一次", substring = true).fetchSemanticsNodes().size)
                onNodeWithTag("first-taste-ask").performClick()
                onNodeWithTag("first-taste-allow").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste-stem").fetchSemanticsNodes().isNotEmpty()
                }
            }
            assertEquals(1, allowed.get())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun companionCanAskForAQuestionAfterMute() {
        val root = Files.createTempDirectory("coach-taste-ask-")
        try {
            val md = root.resolve("econ.md")
            Files.writeString(md, "# Chapter 1\n\nPrices adjust when scarcity rises.\n")
            val library = TextbookLibrary(root.resolve("library"))
            FirstOpenStore.mute(library.directory)
            val book = library.importBook(md)
            val asked = AtomicInteger()
            runSkikoComposeUiTest(size = Size(800f, 600f), density = Density(1f)) {
                setContent {
                    val ask = remember { FirstTasteAsk() }
                    ask.onAsk = { asked.incrementAndGet() }
                    CompositionLocalProvider(LocalFirstTasteAsk provides ask) {
                        ReadingMethodPanel(
                            book = book,
                            chapter = ChapterCompanionGrouping.groupForSection(book, 0),
                            draft = ChapterPracticeDraft(),
                            draftReady = true,
                            library = library,
                            onDraftChange = {},
                            onJumpPage = {},
                            onCommitChoice = { _, _, _, _ -> },
                        )
                    }
                }
                onNodeWithTag("companion-taste-ask").assertIsDisplayed().performClick()
                waitForIdle()
            }
            assertEquals(1, asked.get())
            assertTrue(FirstOpenStore.isMuted(library.directory))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun importToastClosesWithoutFillingTheWindow() {
        runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
            var notice by mutableStateOf<BookImportNotice?>(BookImportNotice(7L, "已放进书架"))
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BookImportToast(
                        notice,
                        onDismiss = { notice = null },
                        modifier = Modifier.align(Alignment.BottomEnd),
                        clearTop = false,
                    )
                }
            }
            onNodeWithTag("book-import-toast").assertIsDisplayed()
            val toast = onNodeWithTag("book-import-toast").fetchSemanticsNode().boundsInRoot
            assertTrue(toast.width < 900f, "toast width ${toast.width}")
            onNodeWithTag("book-import-toast-close").performClick()
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("book-import-toast").fetchSemanticsNodes().size)
        }
    }

    private fun samplePack() = BookCompanionPack(
        bookId = "book",
        chapterId = "ch1",
        chapterTitle = "Chapter 1",
        pretest = listOf(
            BookCompanionQuiz(
                id = "q1",
                sectionId = "s1",
                stem = "What happens when scarcity rises?",
                options = listOf("Prices adjust", "Prices stay fixed"),
                correctOption = "Prices adjust",
                explanation = "Scarcity moves the price.",
            ),
        ),
    )
}
