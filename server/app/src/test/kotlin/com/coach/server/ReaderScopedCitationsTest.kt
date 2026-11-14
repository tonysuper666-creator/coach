package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.domain.scope.VisibleScope
import com.coach.server.reader.v2.*
import com.coach.store.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

/** Real reader, actor, store and bridge protocol; all source text is synthetic. */
class ReaderScopedCitationsTest {
    private val page = SourcePage("synthetic-book", 2, "Synthetic pears grow here. Extra context.", sourceSha256 = "a".repeat(64))
    private val unread = page.copy(pdfPage = 50, text = "Synthetic unread chapter secret.")
    private val foreign = page.copy(docId = "other-book", text = "Synthetic foreign document secret.")
    private val hidden = page.copy(docId = "private-book", text = "Synthetic private secret.", learnerVisible = false)
    private val ceiling = VisibleScope.learner().narrowToPages(page.docId, 1..2)
    private fun payload(id: String) = json("paragraphs" to JsonArray(listOf(json("text" to "有依据的解释。", "anchors" to listOf(id))))).toString()
    private fun layout(p: SourcePage) = ReaderCitationPage(LayoutPage(p.pdfPage, "ok", 1, "single_column", null, 0,
        listOf(600.0, 800.0), listOf(LayoutBlock("b1", "text", listOf(20.0, 20.0, 500.0, 60.0), 0, 0, "ok",
            p.text.substringBefore(" Extra context."), true, null))), "f".repeat(64))
    private fun token(p: SourcePage = page) = Regex("【(S1B[a-f0-9]+)】").find(
        ReaderBlockCitations(SourcePack(listOf(p)), ::layout).render())!!.groupValues[1]

    private inner class Harness : AutoCloseable {
        val dir = Files.createTempDirectory("synthetic-scoped-citations-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir)
        init {
            for (p in listOf(page, unread, foreign, hidden))
                store.registerSourcePage("c", p.docId, "Synthetic source", p.pdfPage, p.text, learnerVisible = p.learnerVisible)
            update("UPDATE source_docs SET blob_sha256='${page.sourceSha256}'")
        }
        val actor = LedgerActor(scope, store)
        val stub = StubTutor()
        val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), citationLayout = ::layout)
        fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { it.executeUpdate(sql) }
        }
        suspend fun answer(id: String, sources: List<SourcePage> = listOf(page, unread, foreign, hidden),
            visible: VisibleScope = ceiling, request: String = "aa"): JsonObject {
            stub.stream(1, payload(id), StubTutor.terminal("complete"), StubTutor.DONE)
            return reader.explain("d", "c", json("requestId" to request, "kind" to "explain"), "Synthetic pears", sources, visible)
        }
        override fun close() { runBlocking { actor.close() }; scope.cancel(); stub.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun narrowScopeProtectsBothBlockAndExplicitPageFallbackAndCannotBeWidened() = runBlocking {
        Harness().use { h ->
            val forged = ceiling.narrow(VisibleScope.tutorInternal().narrowToPages(page.docId, 1..999))
            for ((i, id) in listOf(token(), "S1Bmissing", token(foreign), token(page.copy(sourceSha256 = "b".repeat(64)))).withIndex()) {
                val result = h.answer(id, visible = forged, request = "a$i")
                val card = result["sources"]!!.jsonArray.single().jsonObject
                assertEquals(page.docId, card.str("docId")); assertEquals(2, card.num("pdfPage"))
                assertEquals(page.sourceSha256, card.str("version"))
                if (i == 0) {
                    assertEquals("block", card.str("mode")); assertEquals("Synthetic pears grow here.", card.str("text"))
                } else {
                    assertEquals("page", card.str("mode")); assertEquals(page.text, card.str("text"))
                    assertTrue(card.str("notice").contains("整页")); assertEquals(JsonNull, card["blockId"])
                }
                for (denied in listOf(unread, foreign, hidden)) {
                    assertFalse(h.stub.requestBodies.last().contains(denied.text))
                    assertFalse(result.toString().contains(denied.text))
                }
            }
        }
    }

    @Test fun noneForeignUnreadAndHiddenSourcesCannotStartTheBridge() = runBlocking {
        Harness().use { h ->
            for ((i, case) in listOf(listOf(page) to VisibleScope.NONE, listOf(unread) to ceiling,
                listOf(foreign) to ceiling, listOf(hidden) to VisibleScope.tutorInternal()).withIndex()) {
                val result = h.reader.explain("d", "c", json("requestId" to "b$i", "kind" to "explain"), "Synthetic", case.first, case.second)
                assertTrue(result["sources"]!!.jsonArray.isEmpty()); assertTrue(result.str("text").contains("无法确认"))
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun invalidPageNumbersCannotFallBackToAnUnauthorizedPage() = runBlocking {
        Harness().use { h ->
            for ((i, id) in listOf("S0", "S2", "S2Bmissing", "S999999999999999999999999999Bmissing").withIndex()) {
                val result = h.answer(id, request = "c$i")
                assertTrue(result["sources"]!!.jsonArray.isEmpty()); assertFalse(result.toString().contains(page.text))
            }
        }
    }

    @Test fun revokingPermissionOrReplacingVersionDuringGenerationWithholdsBlockAndPageSnapshots() = runBlocking {
        for (id in listOf(token(), "S1Bmissing")) for (change in listOf(
            "UPDATE source_docs SET learner_visible=0",
            "UPDATE source_docs SET blob_sha256='${"b".repeat(64)}'",
            "UPDATE source_pages SET text='Synthetic replaced text.'")) Harness().use { h ->
            h.stub.stream(60, payload(id), StubTutor.terminal("complete"), StubTutor.DONE)
            val pending = async(Dispatchers.Default) {
                h.reader.explain("d", "c", json("requestId" to "dd", "kind" to "explain"), "Synthetic pears", listOf(page), ceiling)
            }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            h.actor.execute { h.update(change) }
            val result = withTimeout(5000) { pending.await() }
            assertTrue(result["sources"]!!.jsonArray.isEmpty()); assertTrue(result.str("text").contains("当前不可见"))
            assertFalse(result.toString().contains(page.text))
        }
    }
}
