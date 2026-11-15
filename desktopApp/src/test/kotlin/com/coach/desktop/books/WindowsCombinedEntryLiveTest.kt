package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.WindowsTextbooksContent
import java.nio.file.Files
import java.nio.file.Path
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Opt-in live CLI integration: only the fictional PDF generated below is authorized. */
@OptIn(ExperimentalTestApi::class)
class WindowsCombinedEntryLiveTest {
    @Test fun ordinaryEntryKeepsPairingAndLiveSourceReturnAndRevocation() {
        assumeTrue("Requires the opt-in synthetic live CLI receipt", System.getenv("COACH_TEST_CODEX_CALLS") != null)
        val calls = Path.of(requireNotNull(System.getenv("COACH_TEST_CODEX_CALLS")))
        val root = Files.createTempDirectory("combined-entry-synthetic-")
        val pdfPath = root.resolve("fictional.pdf")
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val cmap = """/CIDInit /ProcSet findresource begin
                12 dict begin begincmap /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
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
                    for (line in listOf("Chapter ${index + 1} Fictional gravity",
                        "This fictional repeated sentence appears on both pages.",
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
        library.save(book.copy(readingPage = 2))
        val blocks = StudyCoachCourse.blocks(book.id, listOf(book.pages[1])).filterNot { it.heading }
        val paragraph = blocks.indexOfFirst { "😀" in it.text }
        assertTrue(paragraph >= 0)
        val store = OwnedBookConsentStore(library)
        store.grant(store.offer(book.id, OwnedBookConsentIdentity()), setOf(OwnedBookAction.ASK))
        val settings = DesktopConnectionSettings(root, environment = { null }, mac = false, property = { null })
        fun executions() = if (Files.exists(calls)) Files.readAllLines(calls).count { it == "exec" } else 0
        val before = executions()
        val question = "Why do the fictional bodies move together?"
        runSkikoComposeUiTest(size = Size(1180f, 1100f), density = Density(1f)) {
            setContent { WindowsTextbooksContent(library.directory, settings) { null } }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-read").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("open-settings").performClick()
            onNodeWithTag("book-practice-course").assertExists()
            onNodeWithTag("close-settings").performClick()
            onNodeWithTag("home-read").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("ask-book-passage").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("reader-page-number").assertTextEquals("2 / 2")
            onNodeWithTag("ask-book-passage").performClick()
            onNodeWithTag("ask-paragraph-$paragraph").performScrollTo().performClick()
            onNodeWithTag("book-passage-question").performScrollTo().performTextInput(question)
            onNodeWithTag("book-passage-send").performScrollTo().performClick()
            waitUntil(timeoutMillis = 180_000) {
                onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            val answer = onNodeWithTag("book-passage-answer").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            assertEquals(before + 1, executions())
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("return-to-passage-question").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("reader-page-number").assertTextEquals("2 / 2")
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("return-to-passage-question").performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").assertIsFocused()
            onNodeWithTag("book-passage-question").assertTextContains(question)
            onNodeWithTag("book-passage-answer").assertTextEquals(answer)
            assertEquals(before + 1, executions())
            assertEquals(2, library.book(book.id).readingPage)
            onNodeWithTag("book-consent-revoke").performScrollTo().performClick()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("book-passage-answer").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("book-source-0").assertDoesNotExist()
            onNodeWithTag("book-passage-question").assertTextContains(question)
            assertEquals(before + 1, executions())
            assertNull(store.read(book.id))
            println("COMBINED_ENTRY pairingSettings=true sourcePage=2 nonBMP=true ask=1 returnedSame=true revokeNoOutbound=true")
        }
    }
}
