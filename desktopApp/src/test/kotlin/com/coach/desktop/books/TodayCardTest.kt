package com.coach.desktop.books

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import com.coach.ui.CoachTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class TodayCardTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun singleBookUnsureSurvivesOvernight() = overnight(booklist = false)
    @Test fun booklistWrongAnswerSurvivesOvernight() = overnight(booklist = true)

    private fun overnight(booklist: Boolean) {
        val root = folder.root.toPath()
        val clock = ReviewTestClock()
        val library = TextbookLibrary(root.resolve("library"), clock, todayCardEnabled = true)
        val pages = (1..7).joinToString("\n") { n ->
            "<!-- PDF 第 $n 页 · 书第 $n 页 -->\n# Section $n\n\nSample text on page $n."
        }
        val book = library.importBook(Files.writeString(root.resolve("Reader.md"), pages))
        val controller = TextbookController(library)
        if (booklist) {
            val other = library.importBook(Files.writeString(root.resolve("other.txt"), "Another local book."))
            library.upsertCollection(MaterialCollectionRecord("pair", CollectionKind.BOOKLIST, "两本书"))
            listOf(book, other).forEachIndexed { index, b ->
                library.attachMaterial(CollectionMaterialRecord("pair", b.id, ordinal = index))
            }
        }
        runBlocking {
            controller.load()
            if (booklist) controller.openBooklist("pair")
            controller.openBook(book.id, 7)
        }
        val section = book.lessons.first { 4 in it.firstPage..it.lastPage }
        val group = ChapterSectionGroup("ch", "Test chapter", 1, 7, listOf(section))
        val q = BookCompanionQuiz("q1", section.id, "Which?", listOf("First", "Second"), "First",
            anchor = BookCompanionAnchor(4, "block", 0, 0, ""))
        val pack = BookCompanionPack(bookId = book.id, chapterId = "ch", chapterTitle = "Test", pretest = listOf(q))
        val live = """{"diagnosticGates":[{"gateId":"g1","sectionTitle":"Test","questionPrompt":"Which?","options":["First","Second"],"correctIndex":0,"missJumpPdfPage":4,"quoteAnchor":"test"}]}"""
        runSkikoComposeUiTest(size = Size(1024f, 768f)) {
            var draft by mutableStateOf(ChapterPracticeDraft(book.id, "ch", page = 7))
            setContent {
                CoachTheme(false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ModeDistinctCompanionSurface(book, library, 7, BookStudyPreference(book.id, BookStudyMode.DIAGNOSTIC_SKIP),
                            group, 0, pack, draft, onUpdateDraft = { draft = it(draft) }, onUpdateInquiry = {},
                            onJumpPage = {}, onOpenAnchor = { runBlocking { controller.page(it.pdfPage) } }, modePackJson = if (booklist) live else null,
                            onDiagnosticAnswer = { lesson, index, practice, answer ->
                                runBlocking { controller.recordSectionQuizAnswer(lesson, index, practice, answer, isPretest = true) }
                            })
                    }
                }
            }
            onNodeWithTag(if (booklist) "diagnostic-live-opt-g1-1" else "diagnostic-unsure-q1").performScrollTo().performClick()
            onNodeWithText(if (booklist) "直达 p.4" else "直达第 4 页", substring = true).performScrollTo().performClick()
            assertEquals(4, controller.state.value.page)
            library.saveChapterPracticeDraft(book.id, "ch", 7, draft)
        }
        runBlocking {
            controller.back()
            if (booklist) controller.back()
        }
        clock.day++
        val next = TextbookController(TextbookLibrary(library.directory, clock, todayCardEnabled = true))
        runBlocking { next.load() }
        val ui = next.state.value
        val entry = ui.todayQueue.cards.single()
        assertContains(entry.sources, if (booklist) LocalReviewSource.WRONG else LocalReviewSource.GUESSED)
        assertEquals(4, entry.practice.sourcePage)
        assertEquals(4, ui.catalog.single { it.id == book.id }.readingPage)
        runSkikoComposeUiTest(size = Size(1440f, 960f)) {
            setContent { CoachTheme(false) { TextbookWorkspace(next, loadOnMount = false) { null } } }
            onNodeWithTag("home-focus-meta").assertTextContains("第 4 页", substring = true)
            onNodeWithTag("book-${book.id}").assertTextContains("读到第 4 页", substring = true)
            onNodeWithTag("home-read").performClick()
            waitUntil(timeoutMillis = 10_000) { next.state.value.screen == BookScreen.READER && !next.state.value.busy }
            assertEquals(4, next.state.value.page)
            runBlocking { next.back() }
            onNodeWithTag("home-review-start").performClick()
            waitUntil(timeoutMillis = 10_000) { next.state.value.screen == BookScreen.REVIEWS && !next.state.value.busy }
            onNodeWithTag("local-review-item-0").performClick()
            waitUntil(timeoutMillis = 10_000) { next.state.value.screen == BookScreen.REVIEWING && !next.state.value.busy }
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000) { next.state.value.source != null && next.state.value.page == 4 }
            assertEquals(book.id, next.state.value.book?.id)
        }
    }

    @Test fun upgradingVersionSevenRecoversTheCachedGateOnce() {
        val root = folder.root.toPath()
        val clock = ReviewTestClock()
        val library = TextbookLibrary(root.resolve("library"), clock, todayCardEnabled = true)
        val book = library.importBook(syntheticTextbook(root.resolve("fixture")))
        val group = ChapterCompanionGrouping.groupForSection(book, 0)!!
        val page = group.firstPage
        val chapter = StudyModeChapterNumbering.resolve(book, page, page).chapterNumber
        val gate = "diag-live-$chapter-old"
        StudyModeStore.saveModePackJson(library, book.id, chapter, BookStudyMode.DIAGNOSTIC_SKIP,
            """{"diagnosticGates":[{"gateId":"old","questionPrompt":"Which?","options":["First","Second"],"correctIndex":0,"missJumpPdfPage":$page}]}""")
        library.saveChapterPracticeDraft(book.id, group.chapterId, group.pageCount,
            ChapterPracticeDraft(book.id, group.chapterId, page, pretestSubmitted = setOf(gate), pretestGuessed = mapOf(gate to true)))
        library.withCourseDatabase { db -> db.createStatement().use { it.execute("PRAGMA user_version=7") } }
        clock.day++
        repeat(2) {
            val restored = TextbookLibrary(library.directory, clock, todayCardEnabled = true).reviewQueue().cards.single()
            assertEquals(page, restored.practice.sourcePage)
            assertEquals(1, restored.history.size)
            assertContains(restored.sources, LocalReviewSource.GUESSED)
        }
    }

    @Test fun unfinishedQuestionOpensItsPassageAndClearingItRemovesTheRow() {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = true)
        val book = library.importBook(syntheticTextbook(root.resolve("fixture")))
        val block = book.lessons.last().blocks.last { !it.heading }
        val question = "How does that follow?"
        val saved = BookAskDraft(book.id, block.page, question, "first", block.id, block.start, block.end, null)
        for (thread in listOf("first", "second")) {
            library.saveAskTurn(book.id, thread, block, "Chapter", "ASK", "Previous question", "Previous question",
                Explanation("Answer in $thread", emptyList(), 1), listOf(block))
        }
        library.saveAskDraft(book.id, saved)
        val c = TextbookController(TextbookLibrary(library.directory, todayCardEnabled = true))
        runBlocking { c.load() }
        runSkikoComposeUiTest(size = Size(1440f, 960f)) {
            setContent { CoachTheme(false) { TextbookWorkspace(c, loadOnMount = false) { null } } }
            onNodeWithTag("today-question").performClick()
            waitUntil(timeoutMillis = 10_000) { c.state.value.screen == BookScreen.READER }
            assertEquals(block.page, c.state.value.page)
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(question).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Answer in first", substring = true).assertExists()
            onNodeWithTag("inline-followup-input").performTextClearance()
            runBlocking { c.back() }
            waitUntil(timeoutMillis = 10_000) { library.askDraft() == null }
            onNodeWithTag("today-question").assertDoesNotExist()
        }
    }
}
