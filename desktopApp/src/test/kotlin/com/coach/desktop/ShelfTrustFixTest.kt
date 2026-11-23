package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Verification for TON-1822 (#4A & #8A):
 * - #4A: Shelf "继续" card subtitle is plain human language ("书名 · 章名 · 第 x 页"),
 *   shows "上次问：问题前 12 个字…" after asking a question, and never leaks internal IDs or hex hashes.
 * - #8A: Home shows "还没有要复习的。做错的题会在明天出现。" when no reviews are due (never "今日复习已完成"),
 *   and shows "今天 N 道，约 M 分钟" when reviews are due, opening the review list on click.
 */
@OptIn(ExperimentalTestApi::class)
class ShelfTrustFixTest {
    private val hexTokenRe = Regex("[0-9a-fA-F]{10,}")

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = Path.of("build/screens/ton-1822")
        Files.createDirectories(dir)
        assertTrue(
            ImageIO.write(
                onNodeWithTag("windows-host").captureToImage().toAwtImage(),
                "png",
                dir.resolve("$name.png").toFile(),
            ),
        )
    }

    private fun ComposeUiTest.nodeText(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }.orEmpty()

    @Test fun hexIdentifiersNeverLeakIntoContinueCardOrMasteryMeta() {
        val root = Files.createTempDirectory("coach-ton1822-hex-")
        try {
            val md = root.resolve("econ-notes.md").also {
                Files.writeString(
                    it,
                    "# 第一章 需求与供给\n\n价格机制在分散决策中协调供给与需求。\n\n# 第二章 弹性分析\n\n价格弹性刻画需求量对价格变动的敏感程度。\n",
                )
            }
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)

            // Simulate page dwell with a raw 18-char hex tocNodeId (the exact bug reported by P4/P5).
            library.recordPageDwell(
                bookId = book.id,
                tocNodeId = "c0dd3b3faf610216d6",
                page = 1,
                pagesTotal = book.pages.size,
                dwellMs = 15_000L,
            )

            // Also simulate a legacy DB row that already stored a hex string in concept_mastery_ledger.canonical_name.
            val dbUrl = "jdbc:sqlite:${library.directory.resolve("library.sqlite").toAbsolutePath()}"
            DriverManager.getConnection(dbUrl).use { db ->
                db.prepareStatement(
                    "UPDATE concept_mastery_ledger SET canonical_name = ? WHERE book_id = ?",
                ).use { s ->
                    s.setString(1, "c0dd3b3faf610216d6")
                    s.setString(2, book.id)
                    assertTrue(s.executeUpdate() >= 1)
                }
            }

            val rec = library.topHomeRecommendation(book.id)
            if (rec != null) {
                assertFalse(hexTokenRe.containsMatchIn(rec.metaLine), "Recommendation meta must not leak hex: ${rec.metaLine}")
                assertFalse("待巩固" in rec.metaLine, "Normal dwell meta should not show old 待巩固 template: ${rec.metaLine}")
            }

            val c = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.initialized && it.screen == BookScreen.HOME }

                val meta = nodeText("home-focus-meta")
                assertFalse(hexTokenRe.containsMatchIn(meta), "Home focus meta must not leak hex: $meta")
                assertTrue(book.title in meta, "Expected book title in meta: $meta")
                assertTrue("第 1 页" in meta, "Expected page number in meta: $meta")
                assertTrue("第一章 需求与供给" in meta, "Expected chapter title in meta: $meta")

                val emptyReview = nodeText("home-review-done")
                assertEquals("还没有要复习的。做错的题会在明天出现。", emptyReview)
                onAllNodesWithText("今日复习已完成", substring = true).assertCountEquals(0)
                capture("01-home-clean-and-honest-review")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun askedQuestionAppearsAsTwelveCharExcerptOnContinueCardAfterReturningToShelf() {
        val root = Files.createTempDirectory("coach-ton1822-ask-")
        try {
            val md = root.resolve("health-study.md").also {
                Files.writeString(
                    it,
                    "# 第一章 医疗市场\n\n信息不对称会导致逆向选择与道德风险，使保险市场偏离完全竞争均衡。\n",
                )
            }
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val block = book.sourceBlocks().first { it.isBodyParagraph }

            // Ask through BookExplainer.explainSelection (unpaired offline call still records the asked question locally).
            val explainer = BookExplainer(
                settings = DesktopConnectionSettings(home = root.resolve("home"), environment = { null }),
                library = library,
            )
            val longQuestion = "为什么医疗保险会带来道德风险以及逆向选择问题？"
            runBlocking {
                explainer.explainSelection(kind = "ask", chosen = block, question = longQuestion)
            }

            assertEquals(longQuestion, library.lastQuestion(book.id))

            // Reopen fresh controller (simulating return to shelf / app restart).
            val reopened = TextbookController(TextbookLibrary(library.directory))
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(reopened) { null } }
                ready(reopened) { it.initialized && it.screen == BookScreen.HOME }

                val meta = nodeText("home-focus-meta")
                assertTrue(meta.startsWith("上次问：为什么医疗保险会带来道德…"), "Unexpected ask meta: $meta")
                assertFalse(hexTokenRe.containsMatchIn(meta), "Meta must not leak hex: $meta")
                capture("02-home-last-question-excerpt")
            }

            // Short question (<= 12 chars) should not append an ellipsis.
            assertEquals("上次问：什么是道德风险？", MasteryEngine.formatQuestionExcerpt("什么是道德风险？"))
            assertNull(MasteryEngine.formatQuestionExcerpt("c0dd3b3faf610216d6"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun dueReviewsShowCountAndMinutesAndClickingMetaOpensReviewList() {
        val root = Files.createTempDirectory("coach-ton1822-due-")
        try {
            val source = syntheticTextbook(root.resolve("synthetic"))
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(source)
            val lessons = book.lessons.filter { it.text.isNotBlank() }.take(3)
            assertTrue(lessons.size >= 2, "Expected at least 2 lessons in synthetic textbook")
            for (l in lessons) {
                library.saveAnswer(book.id, l.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Initial", true, false))
            }

            val dueCount = lessons.size
            val expectedMeta = MasteryEngine.formatHomeReviewMeta(dueCount)
            assertEquals("今天 3 道，约 4 分钟", MasteryEngine.formatHomeReviewMeta(3))
            assertEquals("今天 1 道，约 2 分钟", MasteryEngine.formatHomeReviewMeta(1))

            val c = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.initialized && it.screen == BookScreen.HOME && it.reviews.size == dueCount }

                onNodeWithTag("home-review-done").assertDoesNotExist()
                onNodeWithTag("home-review-meta").assertTextEquals(expectedMeta)
                capture("03-home-due-reviews")

                // Clicking the review summary text itself opens the review list.
                onNodeWithTag("home-review-meta").performClick()
                ready(c) { it.screen == BookScreen.REVIEWS }
                onNodeWithTag("local-reviews").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
