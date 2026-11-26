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
 * - #8A: Home shows "还没有要复习的。做错的题会在这里出现。" when no reviews are due (never "今日复习已完成"),
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

    private fun saveUnfinished(library: TextbookLibrary, book: Textbook, block: SourceBlock) {
        library.saveAskDraft(book.id, BookAskDraft(book.id, block.page, checkNotNull(library.lastQuestion(book.id)),
            null, block.id, block.start, block.end, null))
    }

    private fun ComposeUiTest.assertAsk(text: String, legacyExcerpt: String) {
        if (windowsToday) {
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("today-question").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("today-question").assertTextContains(text, substring = true)
        } else onNodeWithTag("home-focus-meta").assertTextContains(legacyExcerpt, substring = true)
    }

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

                if (windowsToday) onNodeWithTag("home-review-card").assertDoesNotExist()
                else assertEquals("还没有要复习的。做错的题会在这里出现。", nodeText("home-review-done"))
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
            saveUnfinished(library, book, block)

            // Reopen fresh controller (simulating return to shelf / app restart).
            val reopened = TextbookController(TextbookLibrary(library.directory))
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(reopened) { null } }
                ready(reopened) { it.initialized && it.screen == BookScreen.HOME }

                val meta = nodeText("home-focus-meta")
                assertAsk(longQuestion, "上次问：为什么医疗保险会带来道德…")
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

    @Test fun emptyLibraryHomeShowsHonestZeroReviewNotice() {
        val root = Files.createTempDirectory("coach-ton1822-empty-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val c = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.initialized && it.screen == BookScreen.HOME }

                onNodeWithTag("upload-textbook").assertIsDisplayed().assertTextContains("导入文件")
                onNodeWithTag("home-review-done").assertIsDisplayed().assertTextEquals("还没有要复习的。做错的题会在这里出现。")
                onAllNodesWithText("今日复习已完成", substring = true).assertCountEquals(0)
                capture("00-empty-home-honest-review")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun switchingFocusBookViaSeparateMultiDropDoesNotLeakPreviousBooksQuestion() {
        val root = Files.createTempDirectory("coach-ton1822-switch-")
        try {
            val bookAFile = root.resolve("book-alpha.md").also {
                Files.writeString(it, "# 第一章 导论\n\n这是甲书的正文段落，用于提问测试。\n")
            }
            val bookBFile = root.resolve("book-beta.md").also {
                Files.writeString(it, "# 第一章 市场\n\n这是乙书的正文段落。\n")
            }
            val bookCFile = root.resolve("book-gamma.md").also {
                Files.writeString(it, "# 第一章 均衡\n\n这是丙书的正文段落。\n")
            }
            val library = TextbookLibrary(root.resolve("library"))
            val bookA = library.importBook(bookAFile)
            val blockA = bookA.sourceBlocks().first { it.isBodyParagraph }
            val explainer = BookExplainer(
                settings = DesktopConnectionSettings(home = root.resolve("home"), environment = { null }),
                library = library,
            )
            runBlocking {
                explainer.explainSelection(kind = "ask", chosen = blockA, question = "这个例子说明什么？")
            }
            saveUnfinished(library, bookA, blockA)

            val c = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.initialized && it.screen == BookScreen.HOME }

                // Initially focus is bookA with its asked question.
                assertAsk("这个例子说明什么？", "上次问：这个例子说明什么？")

                // Multi-drop two new books and choose "分开单放".
                c.launchDroppedBooks(listOf(bookBFile, bookCFile))
                waitForIdle()
                onNodeWithTag("multi-drop-separate").performClick()
                ready(c) { it.catalog.size == 3 && it.book?.id != bookA.id }

                val newBookMeta = nodeText("home-focus-meta")
                assertFalse("上次问" in newBookMeta, "New focus book must not inherit old book's question: $newBookMeta")
                assertFalse("这个例子说明什么" in newBookMeta, "Old question leaked onto new book card: $newBookMeta")
                assertTrue("第 1 页" in newBookMeta, "Expected clean location meta on new book: $newBookMeta")

                // Switching back to bookA still displays bookA's own question.
                runBlocking { c.select(bookA.id) }
                ready(c) { it.book?.id == bookA.id }
                assertAsk("这个例子说明什么？", "上次问：这个例子说明什么？")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun sameBookWithPracticeDraftAndQuestionShowsBothAndPreservesDraftResume() {
        val root = Files.createTempDirectory("coach-ton1822-draft-ask-")
        try {
            val md = root.resolve("sample-draft.md").also {
                Files.writeString(
                    it,
                    "# 合成章节\n\n第一页讨论边际效用与消费者选择。\n\n# 第二节\n\n第二页讨论无差异曲线与预算约束。\n",
                )
            }
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val firstLesson = book.lessons.first()
            val draft = ChapterPracticeDraft(
                bookId = book.id,
                tocNodeId = firstLesson.id,
                page = firstLesson.firstPage,
                companionOpen = true,
                companionTab = "EXPLAIN",
                activeModule = CompanionModuleTab.PRACTICE.name,
                activeQuestionId = "q-1",
                practiceTextDrafts = mapOf("q-1" to "Step 1: MRS = Px / Py"),
                updatedAt = System.currentTimeMillis(),
            )
            library.saveChapterPracticeDraft(book.id, firstLesson.id, book.pages.size, draft)

            val block = book.sourceBlocks().first { it.isBodyParagraph }
            val explainer = BookExplainer(
                settings = DesktopConnectionSettings(home = root.resolve("home"), environment = { null }),
                library = library,
            )
            runBlocking {
                explainer.explainSelection(kind = "ask", chosen = block, question = "为什么边际替代率递减会导致凸性？")
            }
            saveUnfinished(library, book, block)

            // Reopen fresh controller from SQLite (cold restart).
            val reopened = TextbookController(TextbookLibrary(library.directory))
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(reopened) { null } }
                ready(reopened) { it.initialized && it.screen == BookScreen.HOME }

                val meta = nodeText("home-focus-meta")
                assertAsk("为什么边际替代率递减会导致凸性？", "上次问：为什么边际替代率递减会导…")
                assertTrue(meta.contains("练习草稿已保存"), "Expected saved practice draft indicator: $meta")
                assertTrue(reopened.state.value.activePracticeDraft?.hasMeaningfulDraft() == true)
                capture("04-home-draft-and-question")

                // Clicking "继续" still opens reader with draft preserved.
                onNodeWithTag("home-read").performClick()
                ready(reopened) { it.screen == BookScreen.READER }
                assertEquals(firstLesson.firstPage, reopened.state.value.page)
                assertEquals("Step 1: MRS = Px / Py", reopened.state.value.activePracticeDraft?.practiceTextDrafts?.get("q-1"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
