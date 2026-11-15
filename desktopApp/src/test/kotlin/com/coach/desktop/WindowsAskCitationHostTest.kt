package com.coach.desktop

import androidx.compose.ui.test.*
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowsAskCitationHostTest {
    @Test fun pdfSecondPageSupplementaryCharacterSourceOpens() {
        val root = Files.createTempDirectory("ask-pdf-citation-")
        val path = root.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            // Map the synthetic visible X glyph to one supplementary code point in the PDF text layer.
            val cmap = """/CIDInit /ProcSet findresource begin
                12 dict begin begincmap
                /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                /CMapName /SyntheticUnicode def /CMapType 2 def
                1 begincodespacerange <00> <FF> endcodespacerange
                2 beginbfrange <20> <57> <0020> <59> <7E> <0059> endbfrange
                1 beginbfchar <58> <D83DDE00> endbfchar
                endcmap CMapName currentdict /CMap defineresource pop end end""".trimIndent()
            val unicode = PDStream(pdf)
            unicode.createOutputStream().use { it.write(cmap.toByteArray(Charsets.US_ASCII)) }
            font.cosObject.setItem(COSName.TO_UNICODE, unicode)
            repeat(2) { i ->
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(font, 11f); out.newLineAtOffset(50f, 710f)
                    out.showText(if (i == 0) "Plants use sunlight to grow." else "Gravity X pulls bodies together across distance.")
                    out.endText()
                }
            }
            pdf.save(path.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(path)
        assertTrue(book.pages[1].text.contains("😀"), book.pages[1].text)
        library.save(book.copy(readingPage = 2, promptSeen = true))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.read() }
        var excerpt: OwnedBookExcerpt? = null
        var calls = 0
        val support = windowsBookLearningSupport(library, OwnedBookCliTransport {
            calls++; """{"answer":"Synthetic answer.","citations":[0]}"""
        }, OwnedBookOutboundPolicy {
            excerpt = it.excerpts.single()
            it.action == OwnedBookAction.ASK && it.excerpts.single().text.contains("😀")
        })
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("return-to-passage-question").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("reader-page-number").assertTextEquals("2 / 2")
            onNodeWithTag("return-to-passage-question").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-question").assertTextContains("Why?")
            onNodeWithText("Synthetic answer.").assertExists()
            assertEquals(1, calls)
            assertEquals(2, library.book(book.id).readingPage)
            val captured = assertNotNull(excerpt)
            assertEquals(2, captured.citation.page)
            assertEquals(captured.text, book.pages[1].text.substring(captured.citation.start, captured.citation.end))
            assertTrue(captured.text.length > captured.text.codePointCount(0, captured.text.length))
        }
    }

    @Test fun passageAnswerOpensAndReturnsWithoutAskingAgain() {
        val root = Files.createTempDirectory("ask-citation-host-")
        val path = root.resolve("synthetic.txt")
        Files.writeString(path, "Chapter 1 Plants\n\nPlants use sunlight to grow.\u000cChapter 2 Roots\n\nGravity 😀𝒜 pulls bodies together across distance.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(path)
        library.save(book.copy(readingPage = 2, promptSeen = true))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.read() }
        val current = controller.state.value.book!!
        var calls = 0
        var observed: OwnedBookExcerpt? = null
        val support = windowsBookLearningSupport(library, OwnedBookCliTransport {
            calls++
            """{"answer":"Synthetic answer about gravity.","citations":[0]}"""
        }, OwnedBookOutboundPolicy {
            observed = it.excerpts.single()
            it.action == OwnedBookAction.ASK && it.excerpts.single().text.contains("😀𝒜")
        })
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why does it move?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("return-to-passage-question").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("reader-page-number").assertTextEquals("2 / 2")
            onNodeWithTag("return-to-passage-question").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-question").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-question").assertTextContains("Why does it move?")
            onNodeWithText("Synthetic answer about gravity.").assertExists()
            onNodeWithTag("book-source-0").assertIsFocused()
            assertEquals(1, calls)
            assertEquals(2, library.book(current.id).readingPage)
            val excerpt = assertNotNull(observed)
            assertEquals(2, excerpt.citation.page)
            assertEquals(excerpt.text, current.pages[1].text.substring(excerpt.citation.start, excerpt.citation.end))
            assertTrue(excerpt.text.length > excerpt.text.codePointCount(0, excerpt.text.length))
            Files.write(library.directory.resolve("${current.id}.${current.format}"), byteArrayOf(10), StandardOpenOption.APPEND)
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-stale-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-question").assertTextContains("Why does it move?")
            onNodeWithTag("return-to-passage-question").assertDoesNotExist()
            assertEquals(1, calls)
        }
    }

    @Test fun wrongBookSourceIsNotAnOpenButton() {
        val root = Files.createTempDirectory("ask-wrong-book-")
        val path = root.resolve("synthetic.txt")
        Files.writeString(path, "Chapter 1\n\nGravity pulls bodies together across distance.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(path)
        library.save(book.copy(promptSeen = true))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.read() }
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Unavailable("not used")
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Text("Synthetic answer",
                listOf(request.passage.copy(book = "0".repeat(64))))
        }
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Why?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-wrong-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").assertDoesNotExist()
            onNodeWithTag("return-to-passage-question").assertDoesNotExist()
            onNodeWithTag("book-passage-question").assertTextContains("Why?")
        }
    }

    @Test fun lateReplyCannotReplaceNewQuestion() {
        val root = Files.createTempDirectory("ask-late-reply-")
        val path = root.resolve("synthetic.txt")
        Files.writeString(path, "Chapter 1\n\nGravity pulls bodies together across distance.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(path)
        library.save(book.copy(promptSeen = true))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.read() }
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val oldReturned = AtomicBoolean()
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Unavailable("not used")
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                calls.incrementAndGet()
                if (request.question == "Old?") {
                    withContext(NonCancellable) { gate.await() }
                    oldReturned.set(true)
                    return BookLearningReply.Text("OLD ANSWER", listOf(request.passage))
                }
                return BookLearningReply.Text("NEW ANSWER", listOf(request.passage))
            }
        }
        runComposeUiTest {
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("Old?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { calls.get() == 1 }
            onNodeWithText("回到阅读").performClick()
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-0").performClick()
            onNodeWithTag("book-passage-question").performTextInput("New?")
            onNodeWithTag("book-passage-send").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithText("NEW ANSWER").fetchSemanticsNodes().isNotEmpty() }
            gate.complete(Unit)
            waitUntil(timeoutMillis = 20_000) { oldReturned.get() }
            waitForIdle()
            onNodeWithText("OLD ANSWER").assertDoesNotExist()
            onNodeWithTag("book-passage-question").assertTextContains("New?")
            assertEquals(2, calls.get())
        }
    }
}
