package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderLocalContractTest {
    private fun fixture(): Pair<TextbookLibrary, Textbook> {
        val root = Files.createTempDirectory("reader-contract-")
        val library = TextbookLibrary(root.resolve("library"))
        return library to library.importBook(syntheticTextbook(root.resolve("synthetic")))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun disk(library: TextbookLibrary): Map<String, String> = Files.walk(library.directory).use { files ->
        files.filter { Files.isRegularFile(it) }.toList().associate {
            library.directory.relativize(it).toString() to sha(Files.readAllBytes(it))
        }
    }
    private fun note(version: String = "1", page: Int = 1) = buildJsonObject {
        put("id", "synthetic-note"); put("version", version); put("pdfPage", page)
        put("note", "Synthetic unsaved note"); put("baseRevision", 0); put("mutationId", "synthetic-mutation")
    }

    @Test fun unknownIdsAndPathsNeverImportOrWrite() = runBlocking {
        val (library, book) = fixture()
        val source = LocalBooksReaderSource(library)
        val before = disk(library)
        for (id in listOf("unknown-opaque-id", "0".repeat(64), library.directory.resolve("${book.id}.pdf").toString(), "../book.pdf", "\u0000")) {
            assertEquals(404, source.state(id).status)
            assertEquals(404, source.page(id, 1).status)
            assertEquals(404, source.study(id, 1).status)
            assertEquals(404, source.boxes(id, 1, "1").status)
            assertNull(source.image(id, 1, "1"))
            assertEquals(404, source.saveProgress(id, "1", 1, 0, "unknown").status)
            assertEquals(404, source.saveNote(id, note()).status)
            assertEquals(before, disk(library), "Unknown document must not create or change library files")
        }
        println("unknown IDs / absolute paths / invalid paths: state,page,study,boxes,progress,note=404; image=null; disk unchanged")
    }

    @Test fun wrongVersionsAndOutOfRangePagesAreExplicitFailuresWithUnchangedDisk() = runBlocking {
        val (library, book) = fixture()
        val source = LocalBooksReaderSource(library)
        val before = disk(library)
        for (version in listOf("stale-version", "", "2")) {
            assertNull(source.image(book.id, 1, version))
            assertEquals(409, source.boxes(book.id, 1, version).status)
            assertEquals(409, source.saveProgress(book.id, version, 1, 0, "stale").status)
            assertEquals(409, source.saveNote(book.id, note(version)).status)
            assertEquals(before, disk(library))
        }
        assertNull(source.image(book.id, 1, null))
        for (page in listOf(-1, 0, book.pages.size + 1, Int.MAX_VALUE)) {
            assertEquals(409, source.page(book.id, page).status)
            assertEquals(409, source.study(book.id, page).status)
            assertNull(source.image(book.id, page, "1"))
            assertEquals(409, source.boxes(book.id, page, "1").status)
            assertEquals(409, source.saveProgress(book.id, "1", page, 0, "invalid-page").status)
            assertEquals(409, source.saveNote(book.id, note(page = page)).status)
            assertEquals(before, disk(library))
        }
        println("wrong version / invalid page: 409 (image=null), no escaped exception; every library file SHA unchanged: $before")
    }

    @Test fun unsupportedWritesCannotAcknowledgeOrOverwriteLearningState() = runBlocking {
        val (library, original) = fixture()
        val book = original.copy(readingPage = 3, lessonIndex = 1, learning = true, questionMode = QuestionMode.SHORT_ANSWER)
        library.save(book)
        val lesson = book.lessons[1]
        val answer = PracticeAnswer("Synthetic pending answer", revealed = false)
        library.saveAnswer(book.id, lesson.id, book.questionMode, answer)
        val source = LocalBooksReaderSource(library)
        val before = disk(library)
        for (revision in listOf(-1L, 0L, 1L, 999L)) {
            repeat(2) {
                val reply = source.saveProgress(book.id, "1", 1, revision, "repeated-mutation")
                assertEquals(501, reply.status)
                assertNotNull(reply.body?.get("error"))
            }
        }
        assertEquals(501, source.saveNote(book.id, note()).status)
        assertEquals(before, disk(library))
        val reopened = TextbookLibrary(library.directory)
        assertEquals(book, reopened.book(book.id))
        assertEquals(answer, reopened.answer(book.id, lesson.id, book.questionMode))
        assertEquals(0, (source.state(book.id).body!!["notes"] as JsonArray).size)
        println("valid progress/notes=501, repeated IDs and stale revisions never acknowledge; bookmark=3, lesson=1, learning/mode/answer and disk unchanged")
    }

    @Test fun resolverUsesBooksGuardForDefaultAndAliasLibraries() {
        val home = Files.createTempDirectory("reader-guard-home-")
        val learner = home.resolve("Coach/books")
        TextbookLibrary(learner)
        val property: (String) -> String? = { if (it == "user.home") home.toString() else null }
        assertIs<SyntheticReaderSource>(ReaderSourceResolver.resolveInternal({ null }, property))
        for (target in listOf(learner, learner.resolve("../books").normalize())) {
            assertFailsWith<IllegalArgumentException> {
                ReaderSourceResolver.resolveInternal({ if (it == "COACH_LIBRARY_DIR") target.toString() else null }, property)
            }
            assertFailsWith<IllegalArgumentException> {
                ReaderSourceResolver.resolveInternal({ null }, property, target)
            }
        }
        val alias = home.resolve("alias")
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", alias.toString(), learner.toString()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        } else Files.createSymbolicLink(alias, learner)
        assertTrue(Files.isSameFile(alias, learner))
        assertFailsWith<IllegalArgumentException> {
            ReaderSourceResolver.resolveInternal({ null }, property, alias)
        }
        assertFailsWith<IllegalArgumentException> {
            ReaderSourceResolver.resolveInternal({ null }, { if (it == "coach.library.dir") alias.toString() else property(it) })
        }
        val isolated = home.resolve("isolated")
        assertIs<LocalBooksReaderSource>(ReaderSourceResolver.resolveInternal({ null }, property, isolated))
        val packaged: (String) -> String? = { if (it == "jpackage.app-path") "synthetic.exe" else property(it) }
        assertIs<LocalBooksReaderSource>(ReaderSourceResolver.resolveInternal({ null }, packaged))
        println("synthetic home only: no-config development uses fixture; explicit default and real directory alias refused; isolated and packaged defaults allowed")
    }

    @Test fun identityTracksImmutableBytesAndFirstLastImagesAreDifferent() = runBlocking {
        val (library, book) = fixture()
        val original = Files.readAllBytes(library.directory.resolve("${book.id}.pdf"))
        assertEquals(sha(original), book.id)
        val changed = library.directory.parent.resolve("revised.pdf")
        Files.write(changed, original)
        Files.writeString(changed, "\n% Synthetic revision\n", StandardOpenOption.APPEND)
        val revised = library.importBook(changed)
        assertNotEquals(book.id, revised.id)
        assertContentEquals(original, Files.readAllBytes(library.directory.resolve("${book.id}.pdf")))
        val source = LocalBooksReaderSource(library)
        val remote = ReaderRemote(source)
        remote.loadCatalog()
        remote.open(book.id)
        remote.show(1)
        val first = assertIs<PageState.Ready>(remote.page).view
        assertEquals("Photosynthesis is the process by which plants use light to make sugars.", assertIs<StudyState.Ready>(remote.study).view.questions.single().anchor)
        assertEquals(book.id, first.document)
        assertEquals(1, first.pdfPage)
        remote.show(book.pages.size)
        val last = assertIs<PageState.Ready>(remote.page).view
        assertIs<StudyState.Ready>(remote.study)
        assertEquals(book.pages.size, last.pdfPage)
        val firstImage = assertNotNull(first.image)
        val lastImage = assertNotNull(last.image)
        assertFalse(firstImage.contentEquals(lastImage), "Actual first/last PDF pixels must differ")
        assertEquals(source.page(book.id, 1).body!!["textSha256"], source.page(revised.id, 1).body!!["textSha256"])
        println("changed PDF bytes -> new ID (same page text hash); firstImageSHA=${sha(firstImage)}, lastImageSHA=${sha(lastImage)}")
    }

    @Test fun rejectedNoteKeepsActuallyTypedDraft() {
        val (library, book) = fixture()
        val before = disk(library)
        val remote = ReaderRemote(LocalBooksReaderSource(library))
        val session = ReaderSession(ReaderFixture("Synthetic", emptyList(), emptyList()), 1200, remote)
        runComposeUiTest {
            setContent { ReaderWindow(session) }
            runBlocking { remote.loadCatalog(); session.openRemoteAt(book.id, 1) }
            waitUntil(timeoutMillis = 20_000) { remote.page is PageState.Ready }
            onNodeWithTag("bar-Body").performClick()
            onNodeWithTag("reader-root").performKeyInput { pressKey(Key.N); pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 20_000) { session.noteDraft != null }
            onNodeWithTag("note-input").performTextInput("Synthetic unsaved note")
            val id = session.noteDraft!!.id
            onNodeWithTag("note-input").performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft)
            }
            waitUntil(timeoutMillis = 20_000) { remote.noteSave is NoteSaveState.Failed }
            onNodeWithTag("note-input").assertTextEquals("Synthetic unsaved note")
            onNodeWithTag("note-status", useUnmergedTree = true).assertTextEquals("没有保存")
            assertEquals(id, session.noteDraft?.id)
            assertEquals("Synthetic unsaved note", session.noteText)
            assertEquals(before, disk(library))
            val evidence = System.getProperty("review.root")?.let(Path::of)
            if (evidence != null) ImageIO.write(onNodeWithTag("reader-root").captureToImage().toAwtImage(), "png", evidence.resolve("rejected-note.png").toFile())
        }
        println("real Compose N/Enter + text input + Ctrl/Enter: rejection displayed; original draft ID/text and disk retained")
    }

    @Test fun choiceResultReturnsToSameControllerWithoutChangingBookmark() {
        val (library, book) = fixture()
        library.save(book.copy(readingPage = 3, learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        runComposeUiTest {
            setContent { TextbookWorkspace(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING }
            onNodeWithTag("choice-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.revealed }
            val answer = controller.state.value.answer
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().size == 1 }
            onNodeWithText("返回学习").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            assertEquals(answer, controller.state.value.answer)
            assertEquals(3, library.book(book.id).readingPage)
            val reopened = TextbookController(library)
            runBlocking { reopened.load(); reopened.learn() }
            assertEquals(answer, reopened.state.value.answer)
        }
        println("real choice click/source/return/reload: same controller answer/result preserved; independent bookmark=3")
    }
}