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
            assertTrue(h.reader.explain("d", "c", request, "Synthetic", listOf(private)).str("text").contains("无法确认"))
            assertEquals(0, h.stub.requests)
            h.stub.stream(1, "“Synthetic pears”是课件中的例子。【S1】", StubTutor.terminal("complete"), StubTutor.DONE)
            val answer = h.reader.explain("d", "c", request, "Synthetic pears", pack.pages + private)
            assertTrue(answer.str("text").contains("Synthetic pears"))
            assertEquals(4, answer["sources"]!!.jsonArray.single().jsonObject.num("pdfPage"))
            assertEquals(1, h.stub.requests)
            assertFalse(h.stub.requestBodies.single().contains("confidential"))
            h.stub.httpError(503)
            assertTrue(h.reader.explain("d", "c", json("requestId" to "ab", "kind" to "example"), "Synthetic", pack.pages).str("text").contains("无法确认"))
            h.stub.stream(1, "恢复后返回的解释。【S1】", StubTutor.terminal("complete"), StubTutor.DONE)
            assertTrue(h.reader.explain("d", "c", json("requestId" to "ac", "kind" to "example"), "Synthetic", pack.pages).str("text").contains("恢复后"))
            assertTrue(h.actor.execute { events("c") }.isEmpty(), "AI must not change quiz or mastery state")
        }
    }
    @Test fun cancellationIsDeviceScopedAndAlsoWorksBeforeTheRequestArrives() = runBlocking {
        Harness().use { h ->
            h.reader.cancel("d", "c", "aa")
            assertEquals("追问已取消。", h.reader.explain("d", "c", json("requestId" to "aa", "kind" to "explain"), "Synthetic", pack.pages).str("text"))
            assertEquals(0, h.stub.requests)
            h.stub.stream(200, *Array(30) { "Partial synthetic paragraph " }, StubTutor.terminal("complete"), StubTutor.DONE)
            val pending = async(Dispatchers.Default) {
                h.reader.explain("d", "c", json("requestId" to "ab", "kind" to "explain"), "Synthetic", pack.pages)
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
