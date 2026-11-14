package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.coach.client.ReaderReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderExplanationNavigationTest {
    private class Rig(val session: DesktopExplanationSession) : AutoCloseable {
        private var time = 0L
        val scene = ImageComposeScene(1200, 900, Density(1f)) { ReaderExplanationWorkspace(session) }
        fun frames() { repeat(2) { scene.render(time.also { time += 16_000_000 }).close(); Thread.sleep(5) } }
        fun until(label: String, test: () -> Boolean) {
            val end = System.nanoTime() + 20_000_000_000
            do { frames(); if (test()) return } while (System.nanoTime() < end)
            error("Timed out: $label; ${session.error}")
        }
        fun node(tag: String): SemanticsNode? {
            fun find(n: SemanticsNode): SemanticsNode? = if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n else n.children.firstNotNullOfOrNull(::find)
            return scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
        }
        fun click(tag: String) { assertTrue(checkNotNull(node(tag)?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "Missing click $tag" }.invoke()); frames() }
        override fun close() = scene.close()
    }

    @Test fun actualHttpCitationOpensExistingReaderAndReturnsSameOwnerDraft() {
        SyntheticCitationServer().use { h ->
            val s = h.session()
            Rig(s).use { r ->
                r.until("HTTP question") { s.question != null }
                r.click("explanation-choice-1")
                val input = checkNotNull(r.node("explanation-draft")?.config?.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)
                assertTrue(input(AnnotatedString("Why two? unsubmitted draft")))
                r.frames(); r.click("explanation-ask")
                r.until("HTTP explanation") { s.citations.isNotEmpty() }
                val c = s.citations.single()
                assertEquals("synthetic-book", c.document); assertEquals("a".repeat(64), c.sourceVersion)
                assertEquals(h.pdfHash, c.pdfVersion); assertTrue(c.isBlock); assertEquals(1, c.pdfPage)
                r.click("explanation-source-0")
                r.until("existing ReaderWindow ready") { s.opened?.remote?.page is PageState.Ready && r.node("reader-back") != null }
                val reader = s.opened!!
                val view = (reader.remote!!.page as PageState.Ready).view
                assertEquals(1, view.pdfPage); assertEquals("synthetic-book", view.document)
                assertTrue(view.text.contains(h.blockText)); assertNotNull(view.image, "actual PDF rendering")
                r.until("visible block highlight") { r.node("remote-highlight") != null }
                assertEquals("已在页上标出原句", reader.uiHighlight)
                assertEquals(h.blockText, reader.state.flashAnchor)
                assertTrue(reader.readOnly)
                r.click("reader-back")
                r.until("same owner") { s.opened == null && r.node("explanation-draft") != null }
                assertEquals(ExplanationOwner("SYNTHETIC", 1, "fruit1"), s.owner)
                assertEquals(1, s.choice); assertEquals("Why two? unsubmitted draft", s.draft)
                assertEquals(1, h.bridgeRequests.get())
            }
        }
    }

    @Test fun actualHttpInvalidBlockFallsBackOnlyToAuthorizedPageSnapshot(): Unit = runBlocking {
        SyntheticCitationServer().use { h ->
            h.fallback = true
            val s = h.session(); s.load(); s.edit("Why two pears?"); s.explain(); assertNull(s.error)
            val c = s.citations.single(); assertFalse(c.isBlock)
            s.open(c); assertNull(s.error)
            val view = (s.opened!!.remote!!.page as PageState.Ready).view
            assertEquals(h.text, view.text); assertEquals(1, view.pdfPage); assertNull(view.image)
            val restricted = CitationReaderSource.create(c, h.reader)
            assertEquals(403, restricted.page(c.document, 2).status)
            assertEquals(403, restricted.page("foreign-book", 1).status)
            assertEquals(403, restricted.saveProgress(c.document, c.sourceVersion, 1, 0, "synthetic").status)
        }
    }

    @Test fun wrongBookVersionPageAndAmbiguousSelectorNeverGuess(): Unit = runBlocking {
        SyntheticCitationServer().use { h ->
            val s = h.session(); s.load(); s.edit("Why two pears?"); s.explain(); assertNull(s.error)
            val c = s.citations.single()
            val original = h.reader.catalog().body!!
            fun altered(change: (JsonObject) -> JsonObject) = object : ReaderSource by h.reader {
                override suspend fun catalog(): ReaderReply {
                    val docs = original.getValue("documents").jsonArray.map { change(it.jsonObject) }
                    return ReaderReply(200, JsonObject(original + ("documents" to JsonArray(docs))))
                }
            }
            suspend fun rejected(reader: ReaderSource) {
                assertFails { CitationReaderSource.create(c, reader) }
            }
            rejected(altered { JsonObject(it + ("id" to JsonPrimitive("wrong-book"))) })
            rejected(altered { JsonObject(it + ("version" to JsonPrimitive("b".repeat(64)))) })
            rejected(altered { JsonObject(it + ("pages" to JsonPrimitive(0))) })
            rejected(object : ReaderSource by h.reader {
                override suspend fun page(document: String, pdfPage: Int) = ReaderReply(200,
                    JsonObject(h.reader.page(document, pdfPage).body!! + ("text" to JsonPrimitive("${h.blockText} ${h.blockText}"))))
            })
            val source = CitationReaderSource.create(c, h.reader)
            assertEquals(403, source.page(c.document, 2).status)
            assertNull(source.image(c.document, 2, c.pdfVersion))
            assertEquals(403, source.saveNote(c.document, buildJsonObject {}).status)
        }
    }

    @Test fun actualHttpPdfChangedAfterExplanationIsRejected(): Unit = runBlocking {
        SyntheticCitationServer().use { h ->
            val s = h.session(); s.load(); s.edit("Why two pears?"); s.explain(); assertNull(s.error)
            val citation = s.citations.single()
            java.nio.file.Files.writeString(h.dir.resolve("library/synthetic.pdf"), "\nSYNTHETIC VERSION CHANGE", java.nio.file.StandardOpenOption.APPEND)
            s.open(citation)
            assertNull(s.opened)
            assertNotNull(s.error, "HTTP reader must reject a changed PDF rather than open a guessed edition")
        }
    }

    @Test fun missingIdentityAndUnavailablePagesFailExplicitly(): Unit = runBlocking {
        SyntheticCitationServer().use { h ->
            val s = h.session(); s.load(); s.edit("Why two pears?"); s.explain(); assertNull(s.error)
            val c = s.citations.single()
            val raw = buildJsonObject {
                put("docId", c.document); put("version", c.sourceVersion); put("pdfPage", 1)
                put("mode", "block"); put("text", c.text); put("blockId", c.blockId)
                put("startUtf16", 0); put("endUtf16", c.text.length)
            }
            assertFailsWith<IllegalStateException> { ExplanationCitation.parse(raw) }
            assertFailsWith<IllegalArgumentException> { CitationReaderSource.create(c, object : ReaderSource by h.reader {
                override suspend fun page(document: String, pdfPage: Int) = ReaderReply(403, null)
            }) }
        }
    }
}
