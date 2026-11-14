package com.coach.server

import com.coach.domain.*
import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import com.coach.store.SourcePage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ReaderExplanationTest {
    private val pack = SourcePack(listOf(SourcePage("synthetic-slides", 4, "Synthetic pears grow in the orchard.")))
    private fun payload(text: String, vararg anchors: String = arrayOf("S1")) = json(
        "paragraphs" to JsonArray(listOf(json("text" to text, "anchors" to JsonArray(anchors.map(::JsonPrimitive)))))
    ).toString()

    @Test fun rawTabMatchesFinalValidationWithoutCountingAsBodyProgress() {
        val prefix = "{\"paragraphs\":[{\"text\":\""
        val body = "的解释。"
        val suffix = "\",\"anchors\":[\"S1\"]}]}"
        val raw = prefix + "有依据\t" + body + suffix
        assertEquals("有依据\t${body}【S1】", assertNotNull(checkedReaderPayload(raw, pack)).first)
        val progress = ReaderBodyProgress()
        assertFalse(progress.push(prefix))
        assertTrue(progress.push("有依据"))
        assertFalse(progress.push("\t"), "A raw tab must not renew the content deadline")
        assertFalse(progress.invalid, "The final validator accepts this raw tab")
        assertTrue(progress.push(body))
        assertFalse(progress.push(suffix))
        assertFalse(progress.invalid)
    }

    @Test fun structuredOutputRequiresDeclaredCitationsAndPreservesTheWholeAnswerGate() {
        val valid = payload("“Synthetic pears”是这里的例子。")
        assertEquals("“Synthetic pears”是这里的例子。【S1】", checkedReaderPayload(valid, pack)!!.first)
        assertEquals(4, checkedReaderPayload(valid, pack)!!.second.single().jsonObject.num("pdfPage"))
        for (invalid in listOf(
            payload("“合成梨”是这里的例子。"), // The observed paraphrase-in-quotes failure must still refuse.
            payload("“synthetic pears”是这里的例子。"),
            payload("不存在的来源。", "S9"), payload("未声明来源。", *emptyArray()),
            payload("自行嵌入依据。【S1】"), payload("PDF page 99 says this."),
            payload("有依据。\n\nWait, I should correct the formatting."),
            "{\"paragraphs\":[{\"text\":\"有依据。\",\"anchors\":[\"S1\"]},{\"text\":\"漏引。\",\"anchors\":[]}]}",
            "{\"paragraphs\":[{\"text\":\"有依据。\",\"anchors\":[\"S1\"]}],\"correction\":\"uncited\"}",
            valid + "\nWait, I should correct the formatting.",
            "有依据。【S1】\n\nWait, I should correct the formatting."
        )) assertNull(checkedReaderPayload(invalid, pack), invalid)
    }
    @Test fun citationsAndVerbatimQuotesMustAllBelongToVisiblePages() {
        val result = checkedReaderAnswer("“Synthetic pears”是这里的例子。【S1】", pack)!!
        assertEquals(4, result.second.single().jsonObject.num("pdfPage"))
        for (invalid in listOf("没有依据的段落。", "编造页。【S9】", "“Synthetic apples”并不在原文。【S1】",
            "好的一段。【S1】\n\n没有依据的第二段。", "PDF page 99 says this.【S1】"))
            assertNull(checkedReaderAnswer(invalid, pack), invalid)
    }
    @Test fun onlyUsedAnchorsAreReturnedAndSourceVersionsChangeWithText() {
        val two = SourcePack(pack.pages + SourcePage("synthetic-book", 8, "Another synthetic page."))
        assertEquals("synthetic-book", checkedReaderAnswer("另一种说法。【S2】", two)!!.second.single().jsonObject.str("docId"))
        assertNotEquals(notesVersion("Synthetic A"), notesVersion("Synthetic B"))
        assertEquals(notesVersion("Synthetic A"), notesVersion("Synthetic A"))
    }
    private class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createTempDirectory("reader-synthetic-"))
        val actor = LedgerActor(scope, store)
        val stub = StubTutor()
        val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)))
        override fun close() { runBlocking { actor.close() }; scope.cancel(); stub.close() }
    }
    @Test fun explicitSelectionUsesVisibleCoursePagesAndNeverStartsWithoutEvidence() = runBlocking {
        Harness().use { h ->
            val request = json("requestId" to "aa", "kind" to "explain")
            val private = SourcePage("sealed", 1, "Synthetic confidential key", learnerVisible = false)
            assertTrue(h.reader.explain("d", "c", request, "Synthetic", listOf(private), com.coach.domain.scope.VisibleScope.learner()).str("text").contains("无法确认"))
            assertEquals(0, h.stub.requests)
            // A page outside the (narrowed) scope is refused even though the page itself is learner-visible.
            val outside = com.coach.domain.scope.VisibleScope.learner().narrowToPages(pack.pages.first().docId, 900..999)
            assertTrue(h.reader.explain("d", "c", request, "Synthetic", pack.pages, outside).str("text").contains("无法确认"))
            assertEquals(0, h.stub.requests)
            h.stub.stream(1, payload("“Synthetic pears”是课件中的例子。"), StubTutor.terminal("complete"), StubTutor.DONE)
            val answer = h.reader.explain("d", "c", request, "Synthetic pears", pack.pages + private, com.coach.domain.scope.VisibleScope.learner())
            assertTrue(answer.str("text").contains("Synthetic pears"))
            assertEquals(4, answer["sources"]!!.jsonArray.single().jsonObject.num("pdfPage"))
            assertEquals(1, h.stub.requests)
            assertFalse(h.stub.requestBodies.single().contains("confidential"))
            h.stub.httpError(503)
            assertTrue(h.reader.explain("d", "c", json("requestId" to "ab", "kind" to "example"), "Synthetic", pack.pages, com.coach.domain.scope.VisibleScope.learner()).str("text").contains("无法确认"))
            h.stub.stream(1, payload("恢复后返回的解释。"), StubTutor.terminal("complete"), StubTutor.DONE)
            assertTrue(h.reader.explain("d", "c", json("requestId" to "ac", "kind" to "example"), "Synthetic", pack.pages, com.coach.domain.scope.VisibleScope.learner()).str("text").contains("恢复后"))
            assertTrue(h.actor.execute { events("c") }.isEmpty(), "AI must not change quiz or mastery state")
        }
    }
    @Test fun cancellationIsDeviceScopedAndAlsoWorksBeforeTheRequestArrives() = runBlocking {
        Harness().use { h ->
            h.reader.cancel("d", "c", "aa")
            assertEquals("追问已取消。", h.reader.explain("d", "c", json("requestId" to "aa", "kind" to "explain"), "Synthetic", pack.pages, com.coach.domain.scope.VisibleScope.learner()).str("text"))
            assertEquals(0, h.stub.requests)
            h.stub.stream(200, "{\"paragraphs\":[{\"text\":\"", *Array(30) { "Partial synthetic paragraph " }, StubTutor.terminal("complete"), StubTutor.DONE)
            val pending = async(Dispatchers.Default) {
                h.reader.explain("d", "c", json("requestId" to "ab", "kind" to "explain"), "Synthetic", pack.pages, com.coach.domain.scope.VisibleScope.learner())
            }
            withTimeout(5_000) { while (h.stub.framesWritten == 0) delay(10) }
            h.reader.cancel("other-device", "c", "ab")
            assertTrue(pending.isActive)
            h.reader.cancel("d", "c", "ab")
            assertEquals("追问已取消。", withTimeout(5_000) { pending.await() }.str("text"))
            withTimeout(5_000) { while (!h.stub.aborted) delay(10) }
        }
    }
}
