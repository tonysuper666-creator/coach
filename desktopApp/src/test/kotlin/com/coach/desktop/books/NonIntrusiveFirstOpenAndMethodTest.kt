package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.CoachTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NonIntrusiveFirstOpenAndMethodTest {

    @Test
    fun unconsentedBookReadsFreelyAndOneClickAllowExplainsInPlace() {
        val root = Files.createTempDirectory("coach-consent-in-place-")
        try {
            val md = root.resolve("econ.md")
            Files.writeString(md, "# Chapter 1\n\nUNCONSENTED_BOOK_BODY_VISIBLE market equilibrium.\n")
            val library = TextbookLibrary(root.resolve("library"))
            val settings = ReadingSettings(library.directory)
            settings.chooseAiExplain(false)
            assertFalse(ExplainConsent(library.directory).allowed("a".repeat(64)))

            val controller = TextbookController(library)
            val companionCalls = AtomicInteger(0)
            val explainCalls = AtomicInteger(0)
            val explainer = object : BookExplainer(library = library) {
                override suspend fun load() {
                    identity = com.coach.desktop.DesktopIdentity(
                        serverUrl = "http://127.0.0.1:1",
                        credentials = com.coach.client.PairedCredentials("dev", "tok"),
                    )
                    loaded = true
                }

                override suspend fun fetchChapterCompanion(
                    requestJson: JsonObject,
                    blocks: List<SourceBlock>,
                ): ChapterCompanionFetchResult {
                    if (!ExplainConsent(library.directory).allowed(blocks.first().id.substringBefore(":p"))) {
                        return ChapterCompanionFetchResult.Failed(
                            "这本书还没允许讲解",
                            ExplainTrouble.ConsentRequired,
                        )
                    }
                    companionCalls.incrementAndGet()
                    return ChapterCompanionFetchResult.Ready(
                        BookCompanionPack(
                            bookId = blocks.first().id.substringBefore(":p"),
                            chapterId = "ch1",
                            chapterTitle = "Chapter 1",
                            pretest = listOf(
                                BookCompanionQuiz(
                                    id = "q1",
                                    sectionId = "s1",
                                    stem = "What clears the market?",
                                    options = listOf("Price", "Nothing"),
                                    correctOption = "Price",
                                    explanation = "Price adjusts until demand equals supply.",
                                ),
                            ),
                        ),
                    )
                }

                override suspend fun explain(
                    chosen: SourceBlock,
                    blocks: List<SourceBlock>,
                    requestId: String,
                ): ExplainResult {
                    explainCalls.incrementAndGet()
                    return ExplainResult.Done(
                        Explanation("讲解完成", listOf(Citation("S1", chosen.id, chosen.page)), 10L),
                    )
                }
            }

            runSkikoComposeUiTest(size = Size(1280f, 800f), density = Density(1f)) {
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
                    onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty()
                }
                // Reader text is immediately visible without any "请先打开个人导入的书并允许讲解" blocker.
                onNode(hasTestTag("reader-text") and hasText("UNCONSENTED_BOOK_BODY_VISIBLE", substring = true)).assertIsDisplayed()
                assertEquals(0, companionCalls.get())
                onAllNodesWithTag("first-taste").assertCountEquals(0)

                // User actively clicks "来一道": sees one-click "允许这本书讲解", clicks it, and gets the question!
                onNodeWithTag("first-taste-try").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste-allow-explain").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(0, companionCalls.get())
                onNodeWithTag("first-taste-allow-explain").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste-stem").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("first-taste-stem").assertIsDisplayed()
                assertTrue(companionCalls.get() >= 1)
                val book = library.catalog().single()
                assertTrue(ExplainConsent(library.directory).allowed(book.id))
            }

            // Also verify BookExplainPanel in-place allow button when LocalReadingAiExplain is false.
            settings.chooseAiExplain(false)
            val book = library.book(library.catalog().single().id)
            runSkikoComposeUiTest(size = Size(900f, 700f), density = Density(1f)) {
                setContent {
                    var allowed by mutableStateOf(false)
                    CoachTheme(false) {
                        CompositionLocalProvider(
                            LocalReadingAiExplain provides allowed,
                            LocalAllowBookExplain provides { id ->
                                ExplainConsent(library.directory).allow(id)
                                allowed = true
                            },
                        ) {
                            BookExplainPanel(
                                book = book,
                                page = 1,
                                consent = ExplainConsent(library.directory),
                                explainer = explainer,
                                selection = book.sourceBlocks().first { !it.heading },
                                library = library,
                            )
                        }
                    }
                }
                onNodeWithTag("explain-button").assertIsDisplayed().performClick()
                onNodeWithTag("explain-allow-book").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(explainCalls.get() >= 1)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun readingMethodButtonsAreAlwaysEnabledAndPanelCollapsesToOneLineWithoutRecordingBlankMistakes() {
        val root = Files.createTempDirectory("coach-method-non-intrusive-")
        try {
            val md = root.resolve("textbook.md")
            Files.writeString(
                md,
                """
                # Chapter 1 Supply
                
                ## 1.1 Law of Supply
                Prices signal producers to expand output.
                
                ## 1.2 Exercises
                1. Why does supply slope upward?
                2. How do costs affect supply?
                """.trimIndent(),
            )
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val group = ChapterCompanionGrouping.groupForSection(book, 0)!!
            val committed = AtomicInteger(0)

            runSkikoComposeUiTest(size = Size(700f, 600f), density = Density(1f)) {
                var draft by mutableStateOf(
                    ChapterPracticeDraft(
                        bookId = book.id,
                        tocNodeId = group.chapterId,
                        readingMethodJson = ReadingMethodBookState(
                            active = ReadingMethod.BEFORE,
                            userLocked = true,
                            reason = "test",
                            alreadyRead = false,
                            forReview = false,
                        ).encode(),
                    ),
                )
                setContent {
                    CoachTheme(false) {
                        ReadingMethodPanel(
                            book = book,
                            chapter = group,
                            draft = draft,
                            draftReady = true,
                            library = library,
                            onDraftChange = { draft = it },
                            onJumpPage = {},
                            onCommitChoice = { _, _, _, _ -> committed.incrementAndGet() },
                        )
                    }
                }

                // 0. Collapsed by default into a single-line entry; click 展开 to open the flow.
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("reading-method-expand").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("reading-method-collapsed").assertIsDisplayed()
                onNodeWithTag("reading-method-expand").performClick()

                // 1. BEFORE: "做完了，去读" is enabled with 0 answers and advances to READ.
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("reading-method-next").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("reading-method-next").performScrollTo().assertIsEnabled()
                onNodeWithText("做完了，去读").assertIsDisplayed()
                onNodeWithTag("reading-method-next").performClick()
                waitForIdle()
                onNodeWithText("读完了，对照").assertIsDisplayed()

                // 2. Switch to DURING: advance to PAUSE, then "下一节" / "这一章走完了" is enabled without typing.
                onNodeWithTag("reading-method-switch").performClick()
                waitForIdle()
                onNodeWithText("这一节读完了").assertIsDisplayed()
                onNodeWithTag("reading-method-next").performScrollTo().assertIsEnabled().performClick()
                waitForIdle()
                onNodeWithTag("reading-method-next").performScrollTo().assertIsEnabled().performClick()
                waitForIdle()

                // 3. Switch to AFTER: "去做题" in RECALL and "对一下" in QUIZ are enabled without typing,
                //    no mistake is recorded and no mastery evidence is written.
                onNodeWithTag("reading-method-switch").performClick()
                waitForIdle()
                onNodeWithText("去做题").assertIsDisplayed()
                onNodeWithTag("reading-method-next").performScrollTo().assertIsEnabled().performClick()
                waitForIdle()
                onNodeWithTag("reading-method-next").performScrollTo().assertIsEnabled()
                onNodeWithText("对一下").assertIsDisplayed()
                onNodeWithTag("reading-method-next").performClick()
                waitForIdle()

                val decoded = ReadingMethodBookState.decode(draft.readingMethodJson)!!
                val afterProgress = decoded.progress(group.chapterId, ReadingMethod.AFTER)
                assertEquals("DONE", afterProgress.step)
                assertTrue(afterProgress.wrongIds.isEmpty())
                assertEquals(0, committed.get())
                assertTrue(library.conceptMasteryForBook(book.id).isEmpty())

                // 4. Collapse and re-expand leaves a single-line entry.
                onNodeWithTag("reading-method-collapse").performClick()
                waitForIdle()
                onNodeWithTag("reading-method-collapsed").assertIsDisplayed()
                onNodeWithTag("reading-method-expand").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithTag("reading-method-collapse").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
