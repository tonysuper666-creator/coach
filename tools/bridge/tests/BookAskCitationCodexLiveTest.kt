package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import kotlin.test.*

/** Opt-in only: a fictional PDF, the unchanged local consent/CLI bridge, and the real workspace. */
@OptIn(ExperimentalTestApi::class)
class BookAskCitationCodexLiveTest {
    @Test fun sameLiveAskHasTypedSecondPageSourceReturnsOnceAndRevokes() {
        val output = java.io.File("build/reports/book-ask-citation-live").absoluteFile.also { it.mkdirs() }
        val root = Files.createTempDirectory("synthetic-live-ask-citation-")
        val pdfPath = root.resolve("Public fictional gravity.pdf")
        val repeated = "This fictional repeated sentence appears on both pages."
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
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
            repeat(2) { index ->
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText(); stream.setFont(font, 11f); stream.newLineAtOffset(45f, 720f)
                    for (line in listOf("Chapter ${index + 1} Fictional gravity", repeated,
                        "Gravity X pulls fictional bodies together across distance.")) {
                        stream.showText(line); stream.newLineAtOffset(0f, -25f)
                    }
                    stream.endText()
                }
            }
            pdf.save(pdfPath.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(pdfPath)
        assertTrue(book.pages.all { repeated in it.text })
        assertTrue("😀" in book.pages[1].text)
        val paragraphs = StudyCoachCourse.blocks(book.id, listOf(book.pages[1])).filterNot { it.heading }
        val paragraphIndex = paragraphs.indexOfFirst { "😀" in it.text }
        assertTrue(paragraphIndex >= 0, "The selected imported paragraph must include the supplementary character")
        library.save(book.copy(learning = true, promptSeen = true, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN); controller.source(); controller.page(2) }
        val bookmark = library.book(book.id).readingPage
        assertEquals(1, bookmark, "Source reading must not replace the independent bookshelf bookmark")
        val receipts = CopyOnWriteArrayList<JsonObject>()
        val outbound = AtomicInteger()
        val dispatches = AtomicInteger()
        val capturedRequest = AtomicReference<String>()
        val capturedRaw = AtomicReference<String>()
        val capturedReply = AtomicReference<BookLearningReply.Text>()
        val transport = LocalCodexBookTransport(assertNotNull(discoverLocalCodex()), receipt = { argv, exit ->
            val record = buildJsonObject { put("argv", JsonArray(argv.map(::JsonPrimitive))); put("exit", exit) }
            receipts += record
            println("SYNTHETIC_ASK_CLI $record")
        })
        val store = OwnedBookConsentStore(library)
        val actual = ConsentedBookLearningSupport(library, store, OwnedBookCliTransport { request ->
            outbound.incrementAndGet(); capturedRequest.set(request)
            transport.complete(request).also { capturedRaw.set(it) }
        })
        val support = object : ConsentingBookLearningSupport by actual {
            override suspend fun ask(request: BookPassageQuestion): BookLearningReply {
                dispatches.incrementAndGet()
                return actual.ask(request).also { if (it is BookLearningReply.Text) capturedReply.set(it) }
            }
        }
        // Fixture authorization is bound to this generated book, model, provider and notice only.
        // The existing real consent-button probe is separate; this probe adds the missing same-ask source path.
        runBlocking { actual.grant(actual.consentOffer(book.id), setOf(OwnedBookAction.ASK)) }
        val question = "Why do the fictional bodies move together?"
        runSkikoComposeUiTest(size = Size(1180f, 1100f), density = Density(1f)) {
            fun capture(name: String, tag: String) {
                assertTrue(ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", output.resolve("$name.png")))
            }
            setContent { WindowsTextbookHost(controller, support = support) { null } }
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-$paragraphIndex").performScrollTo().performClick()
            onNodeWithTag("book-passage-question").performScrollTo().performTextInput(question)
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 180_000) {
                onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("book-passage-answer").assertExists()
            val reply = assertNotNull(capturedReply.get())
            val source = reply.sources.single()
            assertEquals(book.id, source.book)
            assertEquals(2, source.page)
            assertEquals(source.text, book.pages[1].text.substring(source.start, source.end))
            assertTrue(source.text.length > source.text.codePointCount(0, source.text.length))
            assertEquals(1, outbound.get())
            onNodeWithTag("book-source-0").performScrollTo()
            capture("answer-with-source", "book-passage-ask")
            onNodeWithTag("book-source-0").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("return-to-passage-question").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("reader-page-number").assertTextEquals("2 / 2")
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-image").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("${book.title} 第 2 页").assertExists()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().isNotEmpty() }
            capture("actual-pdf-page-two", "windows-host")
            onNodeWithTag("return-to-passage-question").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-question").assertTextContains(question)
            onNodeWithTag("book-passage-answer").assertTextEquals(reply.text)
            onNodeWithTag("book-source-0").assertIsFocused()
            waitForIdle()
            assertEquals(1, dispatches.get(), "Returning must not ask the real provider a second time")
            assertEquals(1, outbound.get())
            assertEquals(bookmark, library.book(book.id).readingPage)
            capture("returned-same-answer", "book-passage-ask")
            assertIs<BookSourceState.WrongBook>(bookSourceState(source.copy(book = "0".repeat(64)), book, BookSourceResolver(library)))
            onNodeWithTag("book-source-0").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("return-to-passage-question").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { actual.revoke(book.id) }
            onNodeWithTag("return-to-passage-question").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            onNodeWithTag("book-source-0").assertDoesNotExist()
            onNodeWithTag("book-passage-question").assertTextContains(question)
            assertNull(store.read(book.id))
            assertEquals(1, outbound.get(), "Revoked reply must not cause another real request")
            assertEquals(bookmark, library.book(book.id).readingPage)
            capture("revoked-with-question-kept", "book-passage-ask")
            val evidence = buildJsonObject {
                put("book", book.id); put("question", question); put("answer", reply.text)
                put("raw_cli_reply", capturedRaw.get()); put("request", Json.parseToJsonElement(capturedRequest.get()))
                put("source", buildJsonObject { put("book", source.book); put("pdfPage", source.page)
                    put("startUtf16", source.start); put("endUtf16", source.end); put("quote", source.text) })
                put("argv_and_exits", JsonArray(receipts.toList()))
                put("authorized_ask_outbound_count", outbound.get()); put("ask_dispatches_before_revoke", 1)
                put("bookmark_before", bookmark); put("bookmark_after", library.book(book.id).readingPage)
                put("source_return_same_question_answer_focus", true); put("wrong_book_mapper_refused", true)
                put("revoked_cached_answer_hidden", true); put("mode", "Windows JVM headless SOFTWARE_FAST; no native input lease")
            }
            Files.writeString(output.resolve("same-call-receipt.json").toPath(), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), evidence))
            Files.copy(pdfPath, output.resolve("synthetic-two-pages.pdf").toPath())
            println("SYNTHETIC_ASK_SOURCE_RETURN outbound=1 page=2 range=${source.start}..${source.end} bookmark=$bookmark")
        }
    }
}
