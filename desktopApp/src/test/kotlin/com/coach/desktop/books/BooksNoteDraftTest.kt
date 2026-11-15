package com.coach.desktop.books

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import com.coach.desktop.reader.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Real books entry and production LocalBooksReaderSource; only the PDF and user text are synthetic. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class BooksNoteDraftTest {
    private fun ComposeUiTest.ready(c: TextbookController, check: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && check(c.state.value) }
    }
    private fun ComposeUiTest.uploadAndRead(c: TextbookController) {
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("upload-textbook").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("upload-textbook").performClick()
        ready(c) { it.prompt == BookPrompt.IMPORTED }
        onNodeWithTag("read-only").performClick()
        ready(c) { it.screen == BookScreen.READER }
        if (c.state.value.prompt != null) {
            onNodeWithTag("read-only").performClick()
            ready(c) { it.prompt == null }
        }
    }
    private fun ComposeUiTest.notes() {
        onNodeWithTag("books-notes-toggle").assertExists()
        waitUntil(timeoutMillis = 20_000) { onAllNodes(hasTestTag("books-notes-toggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("books-notes-toggle").performClick()
    }
    private fun ComposeUiTest.refused(text: String) {
        onNodeWithTag("books-note-new").performScrollTo().performClick()
        onNodeWithTag("books-note-line-1").performScrollTo().performClick()
        onNodeWithTag("note-input").performScrollTo().performTextInput(text)
        onNodeWithTag("books-note-save").performScrollTo().performClick()
        waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("note-failure-reason").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("note-failure-reason").assertTextContains("本地阅读器暂不支持保存批注", substring = true)
        onNodeWithTag("note-failure-reason").assertTextContains("这条草稿已留在本机", substring = true)
        onNodeWithTag("note-status").assertTextEquals("没有保存")
    }
    private fun store(library: TextbookLibrary) = assertNotNull(ReaderDraftScope.forSource(LocalBooksReaderSource(library), library.directory))
    private fun ComposeUiTest.shot(name: String) {
        val dir = File("build/screens/books-drafts").also { it.mkdirs() }
        ImageIO.write(onNodeWithTag("windows-textbooks").captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    @Test fun bookshelfPdfRefusalSurvivesClosingBookAndNewWorkspaceWithoutChangingAnswerOrBookmark() {
        val root = Files.createTempDirectory("synthetic-books-note-")
        val pdf = wordedChapterPdf(root)
        val directory = root.resolve("library")
        val first = TextbookController(TextbookLibrary(directory))
        val answer = "Synthetic unsubmitted answer"
        val note = "Synthetic note kept after 501"
        var id = ""; var lesson = ""; var noteId = ""
        runComposeUiTest {
            setContent { TextbookWorkspace(first) { pdf } }
            uploadAndRead(first)
            repeat(2) { onNodeWithTag("next-page").performClick(); ready(first) { ui -> ui.page == it + 2 } }
            onNodeWithText("返回书架").performClick(); ready(first) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").performClick(); ready(first) { it.prompt == BookPrompt.START }
            onNodeWithTag("question-mode-SHORT_ANSWER").performClick(); ready(first) { it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            onNodeWithTag("enable-learning").performClick(); ready(first) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").performScrollTo().performTextInput(answer)
            id = first.state.value.book!!.id; lesson = first.state.value.book!!.lessons[0].id
            onNodeWithTag("open-practice-source").performScrollTo().performClick()
            ready(first) { it.screen == BookScreen.READER && it.source != null }
            notes(); refused(note)
            val saved = store(first.library).listFor(id, "1", 1).single()
            noteId = saved.noteId
            assertEquals(note, saved.note); assertEquals(0L, saved.baseRevision)
            shot("01-refused")
            repeat(2) { onNodeWithTag("books-notes-toggle").performClick() }
            onNodeWithTag("local-draft-0").performScrollTo().performClick()
            onNodeWithTag("note-input").assertTextEquals(note)
            onNodeWithTag("note-status").assertTextEquals("有未保存的修改")
            onNodeWithTag("note-failure-reason").assertDoesNotExist()
            // Esc closes this editor, not the source route. No direct controller/state mutation.
            onNodeWithTag("note-input").performClick().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("note-input").fetchSemanticsNodes().isEmpty() }
            assertEquals(BookScreen.READER, first.state.value.screen)
            onNodeWithTag("local-draft-0").assertExists()
            onNodeWithText("返回学习").performClick(); ready(first) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(answer)))
            assertFalse(first.state.value.answer.revealed)
            onNodeWithText("返回书架").performClick(); ready(first) { it.screen == BookScreen.HOME }
        }
        val second = TextbookController(TextbookLibrary(directory))
        runComposeUiTest {
            setContent { TextbookWorkspace(second) { null } }
            ready(second) { it.book != null }
            onNodeWithTag("home-learn").performClick(); ready(second) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(answer)))
            onNodeWithTag("open-practice-source").performScrollTo().performClick(); ready(second) { it.screen == BookScreen.READER }
            notes()
            onNodeWithTag("local-draft-0").performScrollTo().performClick()
            onNodeWithTag("note-input").assertTextEquals(note)
            onNodeWithTag("note-status").assertTextEquals("有未保存的修改")
            onNodeWithTag("note-failure-reason").assertDoesNotExist() // no automatic send to the real 501 source
            assertEquals(noteId, store(second.library).listFor(id, "1", 1).single().noteId)
            // Hiding/showing the panel must not resend the previous save tick.
            repeat(2) { onNodeWithTag("books-notes-toggle").performClick() }
            onNodeWithTag("local-draft-0").performScrollTo().performClick()
            onNodeWithTag("note-input").assertTextEquals(note)
            onNodeWithTag("note-failure-reason").assertDoesNotExist()
            shot("02-new-session-restored")
            // Close the book with the editor still open; input has already reached real disk.
            onNodeWithText("返回学习").performClick(); ready(second) { it.screen == BookScreen.LEARNING }
        }
        val reread = TextbookLibrary(directory)
        assertEquals(3, reread.book(id).readingPage)
        assertEquals(3, reread.catalog().single().readingPage)
        assertEquals(PracticeAnswer(answer), reread.answer(id, lesson, QuestionMode.SHORT_ANSWER))
        assertEquals(note, store(reread).listFor(id, "1", 1).single().note)
    }

    @Test fun actualOtherBookAndOtherLibraryNeverOfferTheOriginalDraft() {
        val root = Files.createTempDirectory("synthetic-books-isolation-")
        val pdf = wordedChapterPdf(root)
        val other = root.resolve("other.pdf")
        Loader.loadPDF(pdf.toFile()).use { it.documentInformation.title = "Another synthetic book"; it.save(other.toFile()) }
        val library = TextbookLibrary(root.resolve("a"))
        val first = TextbookController(library)
        var selected = pdf
        var id = ""
        runComposeUiTest {
            setContent { TextbookWorkspace(first) { selected } }
            uploadAndRead(first); id = first.state.value.book!!.id
            notes(); refused("Only library A and original PDF")
            onNodeWithText("返回书架").performClick(); ready(first) { it.screen == BookScreen.HOME }
            selected = other
            uploadAndRead(first)
            assertNotEquals(id, first.state.value.book!!.id)
            notes(); onNodeWithTag("local-draft-0").assertDoesNotExist()
            onNodeWithText("返回书架").performClick(); ready(first) { it.screen == BookScreen.HOME }
            onNodeWithTag("book-$id").performClick(); ready(first) { it.book?.id == id }
            onNodeWithTag("home-read").performClick(); ready(first) { it.screen == BookScreen.READER }
            notes(); onNodeWithTag("local-draft-0").assertExists()
        }
        val elsewhere = TextbookController(TextbookLibrary(root.resolve("b")))
        runComposeUiTest {
            setContent { TextbookWorkspace(elsewhere) { pdf } }
            uploadAndRead(elsewhere)
            assertEquals(id, elsewhere.state.value.book!!.id, "Same immutable bytes, different actual library")
            notes(); onNodeWithTag("local-draft-0").assertDoesNotExist()
            onNodeWithTag("note-input").assertDoesNotExist()
        }
        assertEquals(1, store(library).listFor(id, "1", 1).size)
    }

    @Test fun foreignVersionAndUnknownDocumentStayOnDiskWithoutBeingRestoredOrSent() {
        val root = Files.createTempDirectory("synthetic-books-version-")
        val pdf = wordedChapterPdf(root)
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(pdf)
        val remote = ReaderRemote(LocalBooksReaderSource(library))
        runBlocking { remote.loadCatalog(); remote.open(book.id); remote.show(1) }
        val view = (remote.page as PageState.Ready).view
        val text = view.text.lineSequence().first { it.isNotBlank() }
        val drafts = store(library)
        val identity = DraftIdentity(drafts.scope, book.id, "1", 1, 0, text.length, view.textSha256)
        val valid = StoredDraft(identity, "original-id", text, "Same version draft", 0L)
        val foreign = valid.copy(identity = identity.copy(version = "2"), noteId = "other-version", note = "Foreign version")
        val unknown = valid.copy(identity = identity.copy(doc = "f".repeat(64)), noteId = "unknown-doc", note = "Unknown document")
        assertTrue(drafts.put(valid)); assertTrue(drafts.put(foreign)); assertTrue(drafts.put(unknown))
        val controller = TextbookController(TextbookLibrary(library.directory))
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            ready(controller) { it.book != null }
            onNodeWithTag("home-read").performClick(); ready(controller) { it.screen == BookScreen.READER }
            if (controller.state.value.prompt != null) { onNodeWithTag("read-only").performClick(); ready(controller) { it.prompt == null } }
            notes()
            onAllNodesWithTag("local-draft-0").assertCountEquals(1)
            onNodeWithTag("local-draft-1").assertDoesNotExist()
            onNodeWithTag("local-draft-0").performScrollTo().performClick()
            onNodeWithTag("note-input").assertTextEquals(valid.note)
            onNodeWithTag("note-status").assertTextEquals("有未保存的修改")
            onNodeWithTag("note-failure-reason").assertDoesNotExist()
        }
        assertEquals(foreign, drafts.get(foreign.identity))
        assertEquals(unknown, drafts.get(unknown.identity))
        assertEquals(valid, drafts.get(valid.identity))
    }
}
