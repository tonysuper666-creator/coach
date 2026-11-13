package com.coach.server

import com.coach.domain.*
import com.coach.server.reader.v2.*
import com.coach.store.SourcePage
import com.coach.store.LedgerStore
import com.coach.core.LedgerActor
import com.coach.ai.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ReaderBlockCitationsTest {
    private val page = SourcePage("synthetic-book", 2, "Intro. Synthetic  pears\ngrow here. Other text.", sourceSha256 = "a".repeat(64))
    private fun layout(text: String = "Synthetic pears grow here.") = LayoutPage(2, "ok", 1, "single_column", null, 0,
        listOf(600.0, 800.0), listOf(LayoutBlock("b1", "text", listOf(20.0, 20.0, 500.0, 60.0), 0, 0, "ok", text, true, null)))
    private fun payload(id: String, text: String = "这是一种说法。") = json("paragraphs" to JsonArray(listOf(
        json("text" to text, "anchors" to listOf(id))))).toString()
    private fun token(c: ReaderBlockCitations) = Regex("【(S1B[a-f0-9]+)】").find(c.render())!!.groupValues[1]
    private fun bound(l: LayoutPage = layout(), hash: String = page.sourceSha256!!) = ReaderCitationPage(l, hash)

    @Test fun diskLayoutAndExplicitImportBindingReachTheProductionAnswerOrFallBackToTheAuthorizedPage() = runBlocking {
        val dir = Files.createTempDirectory("synthetic-reader-cache-answer-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, LedgerStore(dir))
        fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val imported = page.copy(sourceSha256 = hash(page.text))
        val pdfHash = hash("Synthetic original document fixture")
        val root = Files.createDirectories(dir.resolve("layout"))
        val version = Files.createDirectories(root.resolve("unrelated-cache-name/v1/pages")).parent
        try { StubTutor().use { stub ->
            Files.writeString(version.parent.resolve("latest.json"), json("version" to "v1").toString())
            Files.writeString(version.resolve("manifest.json"), json("schema_version" to 1,
                "doc_sha256" to pdfHash, "pages_total" to 2).toString())
            Files.writeString(version.resolve("pages/00002.json"), json("schema_version" to 1,
                "doc_sha256" to pdfHash, "page_number" to 2, "page_index" to 1, "page_base" to 1,
                "status" to "ok", "reading_order_status" to "single_column", "columns" to 1,
                "size" to listOf(600, 800), "blocks" to JsonArray(listOf(json("id" to "b1", "kind" to "text",
                    "bbox" to listOf(20, 20, 500, 60), "reading_order" to 0, "column" to 0,
                    "status" to "ok", "in_reading_flow" to true, "text" to "Synthetic pears grow here.")))).toString())
            fun bind(sourceHash: String) = Files.writeString(root.resolve("source-bindings.json"),
                json("schema_version" to 1, "bindings" to JsonArray(listOf(json("source_doc_id" to imported.docId,
                    "source_sha256" to sourceHash, "pdf_sha256" to pdfHash)))).toString())
            bind(imported.sourceSha256!!)
            actor.execute { registerSourcePage("c", imported.docId, "Synthetic book", imported.pdfPage, imported.text) }
            java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
                db.prepareStatement("UPDATE source_docs SET blob_sha256=?").use {
                    it.setString(1, imported.sourceSha256); it.executeUpdate()
                }
            }
            val cache = ReaderCitationLayout(root)
            val id = token(ReaderBlockCitations(SourcePack(listOf(imported)), cache::page))
            val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), citationLayout = cache::page)
            suspend fun answer(request: String): JsonObject {
                stub.stream(1, payload(id), StubTutor.terminal("complete"), StubTutor.DONE)
                return reader.explain("d", "c", json("requestId" to request, "kind" to "explain"),
                    "Synthetic pears", listOf(imported), com.coach.domain.scope.VisibleScope.learner())
            }
            val block = answer("a1")["sources"]!!.jsonArray.single().jsonObject
            assertEquals("block", block.str("mode"))
            assertEquals("Synthetic  pears\ngrow here.", block.str("text"))
            assertEquals(imported.sourceSha256, block.str("version"))
            assertEquals(pdfHash, block.str("pdfVersion"))
            assertEquals("b1", block.str("blockId"))
            assertEquals(block.str("text"), imported.text.substring(block.num("startUtf16").toInt(), block.num("endUtf16").toInt()))
            assertFalse(stub.requestBodies.last().contains("Other text."))
            // The same cached block and old model ID cannot establish a different import's provenance.
            bind(hash("Synthetic replaced import"))
            val fallback = answer("a2")["sources"]!!.jsonArray.single().jsonObject
            assertEquals("page", fallback.str("mode"))
            assertEquals(imported.text, fallback.str("text"))
            assertEquals(imported.docId, fallback.str("docId"))
            assertEquals(2, fallback.num("pdfPage"))
            assertEquals(JsonNull, fallback["blockId"])
            assertTrue(fallback.str("notice").contains("整页"))
        } } finally { actor.close(); scope.cancel(); dir.toFile().deleteRecursively() }
    }

    @Test fun slowLayoutIsInterruptedByTheExistingFirstContentDeadline() = runBlocking {
        val dir = Files.createTempDirectory("synthetic-reader-deadline-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, LedgerStore(dir))
        try { StubTutor().use { stub ->
            val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)),
                ReaderTimeouts(firstMs = 250, gapMs = 250, totalMs = 2000), citationLayout = {
                    Thread.sleep(5000); bound()
                })
            val start = System.nanoTime()
            val result = reader.explain("d", "c", json("requestId" to "aa", "kind" to "explain"), "Synthetic", listOf(page), com.coach.domain.scope.VisibleScope.learner())
            assertTrue(result.str("text").contains("正文迟迟未开始"))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500, "layout must not retain the timed-out request")
            assertEquals(0, stub.requests)
            assertTrue(result["sources"]!!.jsonArray.isEmpty())
        } } finally { actor.close(); scope.cancel(); dir.toFile().deleteRecursively() }
    }

    @Test fun productionExplanationProvidesOnlyBoundIdsAndWithholdsRevokedSnapshots() = runBlocking {
        val dir = Files.createTempDirectory("synthetic-reader-block-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, LedgerStore(dir))
        try { StubTutor().use { stub ->
            actor.execute { registerSourcePage("c", page.docId, "Synthetic book", page.pdfPage, page.text) }
            java.sql.DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
                db.createStatement().use { it.executeUpdate("UPDATE source_docs SET blob_sha256='${page.sourceSha256}'") }
            }
            val reader = ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), citationLayout = { bound() })
            val id = token(ReaderBlockCitations(SourcePack(listOf(page))) { bound() })
            stub.stream(1, payload(id), StubTutor.terminal("complete"), StubTutor.DONE)
            val result = reader.explain("d", "c", json("requestId" to "aa", "kind" to "explain"), "Synthetic pears", listOf(page), com.coach.domain.scope.VisibleScope.learner())
            assertEquals("block", result["sources"]!!.jsonArray.single().jsonObject.str("mode"))
            assertTrue(result.str("text").contains("【S1B1】"))
            assertTrue(stub.requestBodies.single().contains(id))
            assertFalse(stub.requestBodies.single().contains("Other text.")) // outside the provided block
            actor.execute { registerSourcePage("c", page.docId, "Synthetic book", page.pdfPage, page.text, learnerVisible = false) }
            stub.stream(1, payload(id), StubTutor.terminal("complete"), StubTutor.DONE)
            val revoked = reader.explain("d", "c", json("requestId" to "ab", "kind" to "explain"), "Synthetic pears", listOf(page), com.coach.domain.scope.VisibleScope.learner())
            assertTrue(revoked["sources"]!!.jsonArray.isEmpty())
            assertTrue(revoked.str("text").contains("当前不可见"))
            assertFalse(revoked.toString().contains(page.text))
        } } finally { actor.close(); scope.cancel(); dir.toFile().deleteRecursively() }
    }

    @Test fun verifiedBlockUsesAuthorizedIndexOffsetsAndCannotQuoteOutsideItsRange() {
        val c = ReaderBlockCitations(SourcePack(listOf(page))) { bound() }
        val id = token(c)
        val result = c.check(payload(id, "“Synthetic  pears”是例子。"))!!
        val card = result.second.single().jsonObject
        assertEquals("block", card.str("mode"))
        assertEquals("Synthetic  pears\ngrow here.", card.str("text"))
        assertEquals(card.str("text"), page.text.substring(card.num("startUtf16").toInt(), card.num("endUtf16").toInt()))
        assertEquals(page.sourceSha256, card.str("version"))
        assertEquals("b1", card.str("blockId"))
        assertNull(c.check(payload(id, "“Other text”不属于这块。")))
        assertNull(c.check(payload(id, "“合成梨”不是逐字引文。")))
        assertNull(c.check(payload("S9Bunknown")))
        assertNull(c.check(payload(id, "PDF page 500 says this.")))
        assertNull(c.check(payload(id) + " trailing correction"))
    }

    @Test fun unknownForeignAndOldVersionIdsAreExplicitWholePageFallbacks() {
        val current = ReaderBlockCitations(SourcePack(listOf(page))) { bound() }
        val foreign = ReaderBlockCitations(SourcePack(listOf(page.copy(docId = "other")))) { bound() }
        val changed = ReaderBlockCitations(SourcePack(listOf(page.copy(sourceSha256 = "b".repeat(64))))) { bound() }
        val changedPdf = ReaderBlockCitations(SourcePack(listOf(page))) { bound(hash = "c".repeat(64)) }
        for (id in listOf("S1", "S1Bmissing", token(foreign), token(changed), token(changedPdf))) {
            assertNotEquals(token(current), id)
            val card = current.check(payload(id))!!.second.single().jsonObject
            assertEquals("page", card.str("mode"))
            assertEquals(page.docId, card.str("docId"))
            assertEquals(page.text, card.str("text"))
            assertTrue(card.str("notice").contains("整页"))
            assertTrue(card["blockId"] is JsonNull)
        }
    }

    @Test fun uncertainVersionOrderAmbiguityAndInvisibleSourcesNeverProduceBlockEvidence() {
        for (source in listOf(page.copy(sourceSha256 = null), page.copy(text = page.text + " " + page.text))) {
            val c = ReaderBlockCitations(SourcePack(listOf(source))) { bound() }
            assertFalse(c.render().contains("S1B"))
            assertEquals("page", c.check(payload("S1"))!!.second.single().jsonObject.str("mode"))
        }
        for (l in listOf(layout().copy(readingOrderStatus = "uncertain"), layout().copy(pageNumber = 1),
            layout("not in the source"), layout().copy(blocks = layout().blocks.map { it.copy(status = "unverified") }),
            layout().copy(blocks = layout().blocks + layout().blocks.single().copy(id = "duplicate", readingOrder = 1)))) {
            val c = ReaderBlockCitations(SourcePack(listOf(page))) { bound(l) }
            assertFalse(c.render().contains("S1B"))
        }
        val private = ReaderBlockCitations(SourcePack(listOf(page.copy(learnerVisible = false)))) { error("must not read layout") }
        assertEquals("", private.render())
        assertNull(private.check(payload("S1Bmissing")))
        assertNull(private.check(payload("S1")))
        assertNull(uniqueReaderRange("aaaa", "aaa"))
    }

    @Test fun completeCacheMustMatchFullHashAndPhysicalPageAndBeUnique() {
        val root = Files.createTempDirectory("synthetic-citation-layout-")
        try {
            fun write(doc: String = "different-filename", hash: String = page.sourceSha256!!, number: Int = 2) {
                val dir = Files.createDirectories(root.resolve(doc).resolve("v1"))
                Files.writeString(dir.parent.resolve("latest.json"), json("version" to "v1").toString())
                Files.writeString(dir.resolve("manifest.json"), json("schema_version" to 1, "doc_sha256" to hash, "pages_total" to 2).toString())
                Files.createDirectories(dir.resolve("pages"))
                Files.writeString(dir.resolve("pages/00002.json"), json("schema_version" to 1, "doc_sha256" to hash,
                    "page_number" to number, "page_index" to 1, "page_base" to 1, "status" to "ok",
                    "reading_order_status" to "single_column", "columns" to 1, "size" to listOf(600, 800),
                    "blocks" to JsonArray(listOf(json("id" to "b1", "kind" to "text", "bbox" to listOf(20,20,500,60),
                        "reading_order" to 0, "column" to 0, "status" to "ok", "in_reading_flow" to true, "text" to "Synthetic pears grow here.")))).toString())
            }
            val cache = ReaderCitationLayout(root)
            assertNull(cache.page(page))
            write(hash = "b".repeat(64)); assertNull(cache.page(page))
            write(number = 1); assertNull(cache.page(page))
            write(); assertNotNull(cache.page(page))
            val actual = ReaderBlockCitations(SourcePack(listOf(page)), cache::page)
            assertEquals("block", actual.check(payload(token(actual)))!!.second.single().jsonObject.str("mode"))
            write("competing-cache"); assertNull(cache.page(page))
            Files.writeString(root.resolve("competing-cache/latest.json"), "bad json")
            assertNotNull(cache.page(page))
            // Transformed Markdown requires explicit exact-version provenance, never names or fuzzy text.
            val imported = page.copy(sourceSha256 = "d".repeat(64))
            assertNull(cache.page(imported))
            fun binding(doc: String = imported.docId, sourceHash: String = imported.sourceSha256!!, copies: Int = 1) {
                Files.writeString(root.resolve("source-bindings.json"), json("schema_version" to 1, "bindings" to JsonArray(
                    List(copies) { json("source_doc_id" to doc, "source_sha256" to sourceHash, "pdf_sha256" to page.sourceSha256) })).toString())
            }
            binding(doc = "other"); assertNull(cache.page(imported))
            binding(sourceHash = "e".repeat(64)); assertNull(cache.page(imported))
            binding(copies = 2); assertNull(cache.page(imported))
            binding()
            assertEquals(page.sourceSha256, cache.page(imported)!!.pdfSha256)
            val derived = ReaderBlockCitations(SourcePack(listOf(imported)), cache::page)
            val card = derived.check(payload(token(derived)))!!.second.single().jsonObject
            assertEquals("block", card.str("mode"))
            assertEquals(imported.sourceSha256, card.str("version"))
            assertEquals(page.sourceSha256, card.str("pdfVersion"))
        } finally { root.toFile().deleteRecursively() }
    }
}
