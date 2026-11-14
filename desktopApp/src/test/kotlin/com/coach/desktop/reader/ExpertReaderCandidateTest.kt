package com.coach.desktop.reader

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import com.coach.desktop.windowsLibraryDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ExpertReaderCandidateTest {
    companion object {
        val evidence: Path = (System.getProperty("review.root")?.let(Path::of) ?: Files.createTempDirectory("reader-repair-evidence-")).also { Files.createDirectories(it) }
        val fixture: Path by lazy { syntheticTextbook(evidence.resolve("synthetic-only")) }
    }
    private fun library() = TextbookLibrary(Files.createTempDirectory("expert-reader-library-"))
    private fun ComposeUiTest.ready(c: TextbookController, p: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && p(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    @Test fun workspaceSourceShowsActualHighlightAndKeepsDraftAndBookmark() {
        val lib = library()
        val c = TextbookController(lib)
        runComposeUiTest {
            setContent { TextbookWorkspace(c) { fixture } }
            ready(c) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(c) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("read-only").performClick()
            ready(c) { it.prompt == BookPrompt.AFTER_READING }
            onNodeWithTag("read-only").performClick()
            ready(c) { it.prompt == null }
            repeat(2) {
                onNodeWithTag("next-page").performClick()
                ready(c) { state -> state.page == it + 2 }
            }
            onNodeWithText("返回书桌").performClick()
            ready(c) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-learn").performClick()
            ready(c) { it.prompt == BookPrompt.START }
            onNodeWithTag("question-mode-SHORT_ANSWER").performClick()
            ready(c) { it.book?.questionMode == QuestionMode.SHORT_ANSWER }
            onNodeWithTag("enable-learning").performClick()
            ready(c) { it.screen == BookScreen.LEARNING }
            onNodeWithTag("short-answer").performScrollTo().performTextInput("Unsubmitted synthesis")
            ready(c) { it.answer.text == "Unsubmitted synthesis" && !it.answer.revealed }
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            ready(c) { it.screen == BookScreen.READER && it.source != null }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-image").fetchSemanticsNodes().size == 1 }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size == 1 }
            waitForIdle()
            ImageIO.write(onNodeWithTag("windows-textbooks").captureToImage().toAwtImage(), "png", evidence.resolve("workspace-source.png").toFile())
            onNodeWithTag("remote-highlight").assertContentDescriptionEquals("原句：Photosynthesis is the process by which plants use light to make sugars.")
            val highlights = onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size
            onNodeWithText("返回学习").performClick()
            ready(c) { it.screen == BookScreen.LEARNING }
            assertEquals(3, lib.book(c.state.value.book!!.id).readingPage)
            assertEquals("Unsubmitted synthesis", c.state.value.answer.text)
            assertFalse(c.state.value.answer.revealed)
            val reopened = TextbookController(lib)
            runBlocking { reopened.load(); reopened.learn() }
            assertEquals("Unsubmitted synthesis", reopened.state.value.answer.text)
            println("Workspace source page=1; bookmark=3; draft retained including reload; highlight count=$highlights")
            assertEquals(1, highlights, "Real bookshelf PDF source must draw the matched sentence")
        }
    }

    @Test fun sameRealPdfFirstLastReopenReadyAndHighlight() {
        val lib = TextbookLibrary(evidence.resolve("native-library"))
        val book = lib.importBook(fixture)
        val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture)).joinToString("") { "%02x".format(it) }
        assertEquals(sha, book.id)
        assertContentEquals(Files.readAllBytes(fixture), Files.readAllBytes(lib.directory.resolve("${book.id}.pdf")))
        val source = LocalBooksReaderSource(lib)
        val remote = ReaderRemote(source)
        val session = ReaderSession(ReaderFixture("Synthetic PDF", emptyList(), emptyList()), 1200, remote)
        var t = 0L
        val scene = ImageComposeScene(1200, 900, Density(1f)) { ReaderWindow(session) }
        fun frames() { repeat(18) { t += 16_000_000L; scene.render(t).close(); Thread.sleep(4) } }
        try {
            frames()
            runBlocking { remote.loadCatalog(); session.openRemoteAt(book.id, 1, "Photosynthesis is the process by which plants use light to make sugars.") }
            frames()
            assertEquals(1, assertIs<PageState.Ready>(remote.page).view.pdfPage)
            assertEquals("已在页上标出原句", session.uiHighlight)
            runBlocking { session.openRemoteAt(book.id, 3, "Respiration is the process that releases energy from stored sugars.") }
            frames()
            assertEquals(3, assertIs<PageState.Ready>(remote.page).view.pdfPage)
            assertEquals("已在页上标出原句", session.uiHighlight)
            runBlocking { session.openRemoteAt(book.id, 3, "Respiration is the process that releases energy from stored sugars.") }
            frames()
            assertIs<PageState.Ready>(remote.page)
            assertEquals("已在页上标出原句", session.uiHighlight)
            t += 16_000_000L
            scene.render(t).use { img -> img.encodeToData()!!.use { Files.write(evidence.resolve("reader-last-reopen.png"), it.bytes) } }
            println("PDF SHA=$sha; local version=${remote.current!!.version}; native resolver=" + ReaderSourceResolver.resolveInternal(env = { null }, prop = { null }, libraryDirectory = lib.directory)::class.simpleName)
            Files.writeString(evidence.resolve("document-id.txt"), sha)
            println("Real PDF first/last/same-page reopen PageState.Ready; raw phrase highlighted; study=${remote.study}")
            assertIs<StudyState.Ready>(remote.study)
        } finally { scene.close() }
    }

    @Test fun readerResolverMustRespectDevelopmentLibraryGuard() {
        val home = Files.createTempDirectory("expert-synthetic-home-")
        val lib = TextbookLibrary(home.resolve("Coach/books")); lib.importBook(fixture)
        assertFailsWith<IllegalArgumentException> { windowsLibraryDirectory({ null }, { null }, home) }
        val resolved = ReaderSourceResolver.resolveInternal(env = { null }, prop = { if (it == "user.home") home.toString() else null })
        println("Synthetic home only: normal books entry rejects unconfigured development launch; reader resolver returns ${resolved::class.simpleName}")
        assertFalse(resolved is LocalBooksReaderSource, "Unconfigured development reader must not open default learner library")
    }

    @Test fun staleVersionMustBeRejectedWithoutChangingBookmark() = runBlocking {
        val lib = library(); val book = lib.importBook(fixture)
        val source = LocalBooksReaderSource(lib)
        val image = source.image(book.id, 1, "stale-version")
        val boxes = source.boxes(book.id, 1, "stale-version")
        val saved = source.saveProgress(book.id, "stale-version", 3, 1, "stale-write")
        println("stale version: image=${image?.size}; boxes=${boxes.status}; echoedVersion=${boxes.body?.get("version")}; save=${saved.status}; bookmark=${lib.book(book.id).readingPage}")
        assertNull(image, "Wrong version image must be refused")
        assertFalse(boxes.ok)
        assertFalse(saved.ok)
        assertEquals(1, lib.book(book.id).readingPage)
    }

    @Test fun noteSuccessMustSurviveReopen() = runBlocking {
        val lib = library(); val book = lib.importBook(fixture)
        val source = LocalBooksReaderSource(lib)
        val body = buildJsonObject { put("id", "synthetic-note"); put("version", "1"); put("pdfPage", 1); put("note", "Synthetic note"); put("mutationId", "note-mut"); put("baseRevision", 0) }
        val reply = source.saveNote(book.id, body)
        val notes = source.state(book.id).body!!["notes"] as JsonArray
        println("note save status=${reply.status}; reopened notes=${notes.size}")
        assertTrue(!reply.ok || notes.any { it.jsonObject["id"]?.jsonPrimitive?.content == "synthetic-note" }, "Successful note save must persist, or explicitly reject unsupported operation")
    }

    @Test fun invalidPageMustNotCorruptLocalBookmark() = runBlocking {
        val lib = library(); val book = lib.importBook(fixture)
        val source = LocalBooksReaderSource(lib)
        val reply = source.saveProgress(book.id, "1", 0, 1, "page-zero")
        println("invalid page save=${reply.status}; persisted readingPage=${lib.book(book.id).readingPage}")
        assertFalse(reply.ok, "PDF page 0 must be refused before writing library")
        assertEquals(1, lib.book(book.id).readingPage)
    }

    @Test fun genericOpenIsNotTheQuestionOwnerAndCannotOverwriteLocalBookmark() {
        val lib = library(); val imported = lib.importBook(fixture); lib.save(imported.copy(readingPage = 3))
        val remote = ReaderRemote(LocalBooksReaderSource(lib))
        val s = ReaderSession(ReaderFixture("Synthetic", emptyList(), emptyList()), 1200, remote)
        var t = 0L
        val scene = ImageComposeScene(1200, 900, Density(1f)) { ReaderWindow(s) }
        fun frames() { repeat(18) { t += 16_000_000L; scene.render(t).close(); Thread.sleep(4) } }
        try {
            frames()
            s.answers[2] = 1
            runBlocking { remote.loadCatalog(); s.openRemoteAt(imported.id, 1, "Photosynthesis") }
            frames()
            assertTrue(s.answers.isEmpty())
            assertEquals(0, s.selectedQuestion)
            assertEquals(3, lib.book(imported.id).readingPage)
            println("Generic openAt still clears its own answers (must not replace question owner); source refuses autosave, bookmark remains 3")
        } finally { scene.close() }
    }
}
