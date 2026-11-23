package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * End-to-end verification for TON-1790:
 * - Single-column home hub (no 3-tab rail, top bar LearningCoach + Search/+/Settings)
 * - Empty state 6 zero-dead-end controls & no duplicate "书架还是空的"
 * - Empty library one-step booklist & course creation via multi-file modal
 * - Multi-file drag & drop single-line inquiry bar (建课程 / 建书单 / 分开单放)
 * - Single-file import skips cover/copyright/TOC pages straight to Chapter 1 in <3s
 * - Single-card review self-rating (看答案 / 没记住 / 记住了)
 */
@OptIn(ExperimentalTestApi::class)
class SingleColumnHomeAndImportTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun createFrontMatterBookPdf(path: Path): Path {
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            fun addPage(lines: List<String>) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.setLeading(16f)
                    cs.newLineAtOffset(50f, 720f)
                    for (line in lines) {
                        cs.showText(line)
                        cs.newLine()
                    }
                    cs.endText()
                }
            }
            // Page 1: Cover
            addPage(listOf("Microeconomics Essentials", "Third Edition"))
            // Page 2: Copyright
            addPage(listOf("Copyright 2026 Academic Press", "ISBN 978-0-123456-78-9", "All rights reserved."))
            // Page 3: Contents
            addPage(listOf("Contents", "Chapter 1 Demand and Supply ... 1", "Chapter 2 Market Equilibrium ... 12"))
            // Page 4: Chapter 1 Body
            addPage(listOf(
                "Chapter 1 Demand and Supply",
                "Markets coordinate decentralized decisions through price signals.",
                "When scarcity rises, prices adjust to balance quantity demanded and quantity supplied.",
            ))
            doc.save(path.toFile())
        }
        return path
    }

    @Test fun emptyStateHasSixZeroDeadEndControlsAndCreatesBooklistInOneStep() {
        val root = Files.createTempDirectory("coach-empty-six-")
        try {
            val mdA = root.resolve("book-a.md").also { Files.writeString(it, "# Chapter 1\n\nInformation asymmetry changes contract pricing.") }
            val mdB = root.resolve("book-b.md").also { Files.writeString(it, "# Chapter 1\n\nIncentives shape decisions under uncertainty.") }
            val library = TextbookLibrary(root.resolve("library"))
            val c = TextbookController(library)

            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent {
                    WindowsTextbookHost(
                        controller = c,
                        chooseBooks = { listOf(mdA, mdB) },
                        chooseBook = { mdA },
                    )
                }
                ready(c) { it.initialized }

                // No 3-tab navigation bar, no duplicate "书架还是空的", no search button when empty.
                onNodeWithTag("host-navigation").assertDoesNotExist()
                onNodeWithTag("host-search").assertDoesNotExist()
                onAllNodesWithText("书架还是空的", substring = true).assertCountEquals(0)

                // Exact 6 clickable controls on empty state: ＋ / 设置 / 导入文件 / 3 guide cards.
                onNodeWithTag("host-brand").assertTextContains("LearningCoach")
                onNodeWithTag("host-add").assertIsDisplayed()
                onNodeWithTag("open-settings").assertIsDisplayed()
                onNodeWithTag("upload-textbook").assertIsDisplayed().assertTextContains("导入文件")
                onNodeWithTag("empty-route-course").assertIsDisplayed()
                onNodeWithTag("empty-route-single").assertIsDisplayed()
                onNodeWithTag("empty-route-booklist").assertIsDisplayed()

                // Click "读一组书" from empty library -> pick 2 files inside modal -> create booklist in 1 step.
                onNodeWithTag("empty-route-booklist").performClick()
                waitForIdle()
                onNodeWithTag("create-collection-dialog").assertIsDisplayed()
                onNodeWithTag("collection-pick-files").performClick()
                waitForIdle()
                onNodeWithTag("collection-staged-file-0").assertExists()
                onNodeWithTag("collection-staged-file-1").assertExists()
                onNodeWithTag("collection-title-input").performTextClearance()
                onNodeWithTag("collection-title-input").performTextInput("信息与激励")
                onNodeWithTag("collection-confirm").assertIsEnabled().performClick()

                ready(c) { it.collections.size == 1 && it.catalog.size == 2 }
                // Now on daily home: Search appears, focus card has 유일 PrimaryButton("继续"), 0 reviews -> single line.
                onNodeWithTag("host-search").assertIsDisplayed()
                onNodeWithTag("home-focus-card").assertIsDisplayed()
                onNodeWithTag("home-read").assertTextContains("继续")
                onNodeWithTag("home-review-done").assertTextContains("还没有要复习的。做错的题会在明天出现。")
                onNodeWithText("信息与激励").assertExists()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun singleFileImportSkipsCoverAndCopyrightPagesUnderThreeSeconds() {
        val root = Files.createTempDirectory("coach-skip-front-")
        try {
            val pdf = createFrontMatterBookPdf(root.resolve("micro.pdf"))
            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            val c = TextbookController(library)

            val startedMs = System.currentTimeMillis()
            runBlocking { c.launchImport(pdf).join() }
            val wallMs = System.currentTimeMillis() - startedMs
            val ui = c.state.value

            assertNull(ui.error)
            assertNull(ui.prompt, "No blocking BookPrompt dialog on import")
            assertEquals(BookScreen.READER, ui.screen)
            assertEquals(4, ui.page, "Should skip cover(1), copyright(2), TOC(3) and open Chapter 1 body on page 4")
            val recordedMs = assertNotNull(ui.lastImportDurationMs)
            assertTrue(recordedMs < 3_000L, "Import to chapter 1 took ${recordedMs}ms (>= 3000ms)")
            assertTrue(wallMs < 3_000L, "Wall time took ${wallMs}ms (>= 3000ms)")
            println("SINGLE_FILE_IMPORT_TIMING recordedMs=$recordedMs wallMs=$wallMs landedPage=${ui.page}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun multiFileDropShowsSingleLineInquiryBarAndCreatesCourse() {
        val root = Files.createTempDirectory("coach-multi-drop-")
        try {
            val f1 = root.resolve("week1-slides.md").also { Files.writeString(it, "# Week 1\n\nIntroduction to demand elasticity.") }
            val f2 = root.resolve("week2-notes.md").also { Files.writeString(it, "# Week 2\n\nAdverse selection in insurance markets.") }
            val library = TextbookLibrary(root.resolve("library"))
            val c = TextbookController(library)

            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.initialized }

                c.launchDroppedBooks(listOf(f1, f2))
                waitForIdle()
                onNodeWithTag("multi-drop-bar").assertIsDisplayed()
                onNodeWithTag("multi-drop-course").assertIsDisplayed().assertTextContains("建课程")
                onNodeWithTag("multi-drop-booklist").assertIsDisplayed().assertTextContains("建书单")
                onNodeWithTag("multi-drop-separate").assertIsDisplayed().assertTextContains("分开单放")

                onNodeWithTag("multi-drop-course").performClick()
                ready(c) { it.collections.any { col -> col.record.kind == CollectionKind.COURSE } && it.catalog.size == 2 }
                onNodeWithTag("multi-drop-bar").assertDoesNotExist()
                onNodeWithTag("home-section-courses").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun singleCardReviewSupportsShowAnswerAndAgainGoodSelfRating() {
        val root = Files.createTempDirectory("coach-self-rate-")
        try {
            val clock = ReviewTestClock()
            val library = TextbookLibrary(root.resolve("library"), clock)
            val c = TextbookController(library)
            runBlocking {
                c.importBook(syntheticTextbook(root.resolve("synthetic")))
                c.back()
            }
            val book = c.state.value.book!!
            val lessons = book.lessons.filter { it.text.isNotBlank() }.take(2)
            assertEquals(2, lessons.size)
            for (l in lessons) {
                library.saveAnswer(book.id, l.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Initial", true, false))
            }
            runBlocking { c.showReviews() }

            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.screen == BookScreen.REVIEWS }
                onNodeWithTag("local-review-start").performClick()
                ready(c) { it.screen == BookScreen.REVIEWING }
                val firstLesson = c.state.value.review!!.lesson

                onNodeWithTag("local-review-position").assertTextContains("1 / 2")
                onNodeWithTag("review-show-answer").performScrollTo().assertTextContains("看答案").performClick()
                waitForIdle()
                onNodeWithTag("review-reference-card").performScrollTo().assertIsDisplayed()
                onNodeWithTag("review-rate-again").performScrollTo().assertTextContains("没记住")
                onNodeWithTag("review-rate-good").performScrollTo().assertTextContains("记住了")

                // Click 没记住 -> records AGAIN (not completed) and advances to 2 / 2.
                onNodeWithTag("review-rate-again").performClick()
                ready(c) { it.screen == BookScreen.REVIEWING && it.review?.lesson != firstLesson }
                assertFalse(library.review(book.id, firstLesson, QuestionMode.SHORT_ANSWER).completed)
                onNodeWithTag("local-review-position").assertTextContains("2 / 2")

                // Click 看答案 -> 记住了 -> records GOOD (completed) and finishes queue back to REVIEWS.
                val secondLesson = c.state.value.review!!.lesson
                onNodeWithTag("review-show-answer").performScrollTo().performClick()
                waitForIdle()
                onNodeWithTag("review-rate-good").performScrollTo().performClick()
                ready(c) { it.screen == BookScreen.REVIEWS }
                assertTrue(library.review(book.id, secondLesson, QuestionMode.SHORT_ANSWER).completed)
                assertEquals(1, library.reviews().size, "First card rated AGAIN remains due; second card rated GOOD is completed")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
