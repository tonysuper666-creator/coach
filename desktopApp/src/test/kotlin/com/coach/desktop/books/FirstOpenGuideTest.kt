package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalTestApi::class)
class FirstOpenGuideTest {
    @Test fun openingRequestUsesOnlyTheStartOfTheBook() {
        val root = Files.createTempDirectory("coach-first-open-plan-")
        try {
            val later = "LATER_CHAPTER_MARKER_1927"
            val md = root.resolve("econ.md")
            val opening = "Prices adjust when scarcity rises in a competitive market."
            val padding = " Supply and demand clear the market. ".repeat(400)
            Files.writeString(md, "# Chapter 1\n\n$opening\n\n$padding\n\n# Chapter 2\n\n$later\n")
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val group = checkNotNull(FirstOpenPlanner.openingGroup(book))
            val wire = ChapterCompanionGrouping.buildWireRequest(book, group)
            val texts = wire.sectionTexts()
            assertTrue(texts.any { opening in it }, "opening sentence missing: $texts")
            assertTrue(texts.none { later in it }, "later chapter was included: $texts")
            assertTrue(texts.sumOf { it.length } <= FirstOpenPlanner.OPENING_CHARS + 800, "sent ${texts.sumOf { it.length }} chars")
            assertEquals(1, wire["sections"]!!.jsonArray.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun tier0BookWithEmptyChapterOpenerPicksExtractedSectionInsteadOfReturningNull() {
        val bookId = "a".repeat(64)
        val bodyText = "Health care markets exhibit uncertainty and asymmetric information."
        val pages = (1..30).map { p -> BookPage(p, if (p == 19) bodyText else "") }
        val outline = listOf(
            BookOutlineItem("Chapter 1 Introduction", level = 0, page = 17, source = HeadingSource.PDF_OUTLINE),
            BookOutlineItem("1.1 Why Health Economics?", level = 1, page = 19, source = HeadingSource.PDF_OUTLINE),
            BookOutlineItem("1.2 Demand for Health", level = 1, page = 25, source = HeadingSource.PDF_OUTLINE),
        )
        val structure = BookTocWaterfall.buildTier0Structure(bookId, "Health Economics", 30, outline)
            .copy(nativeBodyStart = BookPageAnchor(19)).also {
                it.deferResolvedLessons { it.toLessons(bookId, pages) }
            }
        val tier0Book = Textbook(
            id = bookId,
            title = "Health Economics",
            format = "pdf",
            pages = pages,
            readingPage = 19,
            structure = structure,
            indexState = "TIER0",
        )
        val group = checkNotNull(FirstOpenPlanner.openingGroup(tier0Book)) {
            "Tier 0 book with an empty chapter opener before extracted 1.1 must still resolve openingGroup"
        }
        assertEquals(1, group.sections.size)
        assertTrue(group.sections.single().blocks.any { bodyText in it.text })
    }

    @Test fun troubleCopyStaysHuman() {
        assertEquals(FirstOpenBlock.OFFLINE, FirstOpenPlanner.blockFor(ExplainTrouble.Unreachable, CoachConnectivityStatus.ONLINE))
        assertEquals(FirstOpenBlock.BUSY, FirstOpenPlanner.blockFor(ExplainTrouble.Busy, CoachConnectivityStatus.ONLINE))
        assertEquals(FirstOpenBlock.OFFLINE, FirstOpenPlanner.blockFor(ExplainTrouble.Other, CoachConnectivityStatus.OFFLINE))
        assertEquals(
            FirstOpenCopy.BUSY,
            FirstOpenPlanner.statusText(FirstOpenBlock.BUSY, "HTTP 429", bookInHand = true),
        )
        assertFalse("HTTP" in FirstOpenPlanner.statusText(FirstOpenBlock.OTHER, "java.io.IOException: HTTP 503", bookInHand = true))
        val failed = FirstOpenPlanner.statusText(FirstOpenBlock.IMPORT, "这份教材不完整或已损坏，请重新保存或下载后再导入。", bookInHand = false)
        assertTrue(FirstOpenCopy.IMPORT_NEXT in failed)
    }

    @Test fun emptyFirstOpenHasOneButtonAndSaysWhatFailed() {
        runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
            setContent {
                FirstOpenInvite(
                    ui = LibraryUi(initialized = true, error = "这份教材不完整或已损坏，请重新保存或下载后再导入。"),
                    onImport = {},
                )
            }
            onNodeWithTag("upload-textbook").assertIsDisplayed().assertIsEnabled()
            onNodeWithTag("first-open-status").assertIsDisplayed()
            onAllNodesWithTag("empty-route-course").assertCountEquals(0)
            onAllNodesWithTag("empty-route-single").assertCountEquals(0)
            onAllNodesWithTag("empty-route-booklist").assertCountEquals(0)
            onAllNodesWithTag("upload-textbook").assertCountEquals(1)
        }
        runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
            setContent {
                FirstOpenInvite(ui = LibraryUi(initialized = true, busy = true), onImport = {})
            }
            onNodeWithTag("first-open-status").assertIsDisplayed()
            onNodeWithTag("upload-textbook").assertIsNotEnabled()
        }
    }

    @Test fun offlineAndBusyDoNotLeaveABlankPage() {
        val root = Files.createTempDirectory("coach-first-open-hold-")
        try {
            val md = root.resolve("econ.md")
            Files.writeString(md, "# Chapter 1\n\nPrices adjust when scarcity rises.\n")
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val boom = object : BookExplainer(library = library) {
                override suspend fun fetchChapterCompanion(requestJson: JsonObject, blocks: List<SourceBlock>): ChapterCompanionFetchResult {
                    error("offline must not ask the server")
                }
            }
            runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
                setContent {
                    FirstTastePanel(
                        book = book,
                        explainer = boom,
                        connectivity = CoachConnectivityStatus.OFFLINE,
                        onReadBook = {},
                        onDone = {},
                    )
                }
                onNodeWithTag("first-taste-status").assertIsDisplayed()
                onNodeWithTag("first-taste-primary").assertIsDisplayed()
                onNodeWithTag("first-taste-read").assertIsDisplayed()
            }
            val busy = object : BookExplainer(library = library) {
                override suspend fun fetchChapterCompanion(requestJson: JsonObject, blocks: List<SourceBlock>): ChapterCompanionFetchResult =
                    ChapterCompanionFetchResult.Failed("HTTP 503", ExplainTrouble.Busy)
            }
            runSkikoComposeUiTest(size = Size(1100f, 760f), density = Density(1f)) {
                setContent {
                    FirstTastePanel(
                        book = book,
                        explainer = busy,
                        connectivity = CoachConnectivityStatus.ONLINE,
                        onReadBook = {},
                        onDone = {},
                    )
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste-status").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("first-taste-status").assertTextEquals(FirstOpenCopy.BUSY)
                onNodeWithTag("first-taste-primary").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun firstQuestionShowsTheExplanationThenRemembersItIsDone() {
        val root = Files.createTempDirectory("coach-first-open-flow-")
        try {
            val md = root.resolve("econ.md")
            Files.writeString(md, "# Chapter 1\n\nPrices adjust when scarcity rises.\n")
            val library = TextbookLibrary(root.resolve("library"))
            val controller = TextbookController(library)
            val explainer = object : BookExplainer(library = library) {
                override suspend fun fetchChapterCompanion(requestJson: JsonObject, blocks: List<SourceBlock>): ChapterCompanionFetchResult =
                    ChapterCompanionFetchResult.Ready(samplePack())
            }
            assertFalse(FirstOpenStore.isDone(library.directory))
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
                onNodeWithTag("upload-textbook").assertIsDisplayed()
                onAllNodesWithTag("empty-route-course").assertCountEquals(0)
                onNodeWithTag("upload-textbook").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("first-taste-stem").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("first-taste-option-0").performClick()
                onNodeWithTag("first-taste-primary").performClick()
                onNodeWithTag("first-taste-explanation").assertIsDisplayed()
                onNodeWithTag("first-taste-primary").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("first-taste").fetchSemanticsNodes().isEmpty()
                }
            }
            assertTrue(FirstOpenStore.isDone(library.directory))
        } finally {
            root.toFile().deleteRecursively()
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
                explanation = "Scarcity moves the price until the market clears.",
            ),
        ),
    )

    private fun JsonObject.sectionTexts(): List<String> {
        val sections = this["sections"]?.jsonArray ?: return emptyList()
        return sections.flatMap { section ->
            section.jsonObject["blocks"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        }
    }
}
