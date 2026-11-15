package com.coach.server

import com.coach.domain.*
import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class LibraryExplainTest {
    private fun blocks(vararg entries: Pair<String, String>) = JsonArray(entries.map { json("id" to it.first, "text" to it.second) })
    private fun request(selection: String, blocks: JsonArray) = json("requestId" to "ab12", "kind" to "explain", "selection" to selection, "blocks" to blocks)
    private val id = "0123abcd:p12:340"

    @Test fun blocksBecomeAnchorablePagesCarryingTheirIds() = Harness().use { h ->
        val untrusted = libraryExplainRequest(request("price elasticity", blocks(id to "Synthetic price   elasticity is a ratio.", "0123abcd:p12:900" to "Next synthetic block.")))
        assertFalse(untrusted.scope.permits(id, 12, true), "Parsing client text cannot mint a trusted scope")
        val parsed = untrusted.authorize(h.store)
        assertEquals(listOf(id), parsed.pages.map { it.docId })
        assertEquals("price elasticity", parsed.pages.single().text)
        assertTrue(parsed.scope.permits(id, 12, true))
        assertFalse(parsed.scope.permits(id, 13, true))
        assertFalse(parsed.scope.permits("0123abcd:p12:900", 12, true))
        assertFalse(parsed.scope.permits(id, 12, false))
        assertEquals(12, parsed.pages.first().pdfPage)
        assertEquals("price elasticity", parsed.quote)
    }

    @Test fun malformedRequestsAreRefused() {
        val ok = blocks(id to "Synthetic text here.")
        for (bad in listOf(
            request("", ok), request("not in the blocks", ok), request("Synthetic", blocks("not-an-id" to "Synthetic")),
            request("Synthetic", blocks(id to "Synthetic", id to "Synthetic")), request("Synthetic", JsonArray(emptyList())),
            request("Synthetic", blocks(*Array(5) { "0123abcd:p1:$it" to "Synthetic" })),
            request("Synthetic", blocks(id to "x".repeat(8_001))),
            request("Synthetic", blocks("0123abcd:p0:0" to "Synthetic")),
            request("Synthetic", blocks(id to "Synthetic", "other:p1:0" to "Synthetic")),
            request("Synthetic", blocks("sealed:p1:0" to "Synthetic")),
            json("selection" to "Synthetic"),
        )) assertEquals(HttpStatusCodeBad, assertFailsWith<MockError> { libraryExplainRequest(bad) }.status.value)
    }
    @Test fun callerVisibilityAndScopeCannotPromoteNeighboursOrOtherPages() = Harness().use { h ->
        val input = request("Synthetic selected", blocks(
            id to "Unread prefix. Synthetic selected. Unread suffix.",
            "0123abcd:p13:0" to "Synthetic not yet read.",
        )) + mapOf("scope" to json("allowsInternalReference" to true), "learnerVisible" to JsonPrimitive(true))
        val parsed = libraryExplainRequest(JsonObject(input)).authorize(h.store)
        assertEquals("Synthetic selected", parsed.pages.single().text)
        assertFalse(parsed.scope.permits("0123abcd:p13:0", 13, true))
        val pack = SourcePack.prepare("", parsed.pages)
        assertTrue(pack.containsSelection(parsed.quote))
        assertFalse(pack.render().contains("Unread"))
        assertFalse(pack.render().contains("not yet read"))
        assertNotNull(checkedReaderPayload("""{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", pack))
        Unit
    }

    private inner class Harness : AutoCloseable {
        val dir = Files.createTempDirectory("synthetic-library-scope-")
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir)
        val stub = StubTutor()
        fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { it.executeUpdate(sql) }
        }
        init {
            store.enroll("phone", "synthetic-private-token-32-characters")
            store.registerSourcePage("SYNTHETIC", "0123abcd", "Synthetic book", 12,
                "Synthetic price elasticity is a ratio. Synthetic selected. SYNTHETIC_NEIGHBOUR_SECRET.")
            store.registerSourcePage("SYNTHETIC", "0123abcd", "Synthetic book", 13, "SYNTHETIC_UNREAD_SECRET.")
            store.registerSourcePage("SYNTHETIC", "harmless-id", "Synthetic answers", 12, "SYNTHETIC_ANSWER_SECRET.")
            store.registerSourcePage("SYNTHETIC", "ordinary-id", "Synthetic sealed material", 12, "SYNTHETIC_HELDOUT_SECRET.", learnerVisible = false)
            for ((kp, page) in listOf("k1" to 12, "k2" to 13)) update(
                """INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id)
                    VALUES('$kp','SYNTHETIC','m',$page,'x','x','[]','[]','[{"docId":"0123abcd","pdfPage":$page}]',0,NULL)""")
            store.append("SYNTHETIC", "kp_opened", json("kpId" to "k1"))
        }
        val actor = LedgerActor(jobs, store)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 16))
            libraryExplainRoutes(actor, ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), readSources = { pack, _ -> pack }))
        }.also { it.start(false) }
        val client = HttpClient.newHttpClient()
        suspend fun post(input: JsonObject): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
            val port = engine.engine.resolvedConnectors().single().port
            val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/library/explain"))
                .header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(), HttpResponse.BodyHandlers.ofString())
            response.statusCode() to LedgerJson.parseToJsonElement(response.body()).jsonObject
        }
        fun reply(delayMs: Long = 1) = stub.stream(delayMs,
            """{"paragraphs":[{"text":"有依据的解释。","anchors":["S1"]}]}""", StubTutor.terminal("complete"), StubTutor.DONE)
        override fun close() { engine.stop(100, 1000); runBlocking { actor.close() }; jobs.cancel(); stub.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun registeredLearnedSelectionReachesModelWithoutClientOrServerNeighbours() = runBlocking {
        Harness().use { h ->
            h.reply()
            val (status, body) = h.post(request("Synthetic selected", blocks(
                id to "SYNTHETIC_CLIENT_PREFIX. Synthetic selected. SYNTHETIC_CLIENT_SUFFIX.",
                "0123abcd:p13:0" to "SYNTHETIC_UNREAD_SECRET.")))
            assertEquals(200, status)
            assertTrue(body.str("text").contains("有依据的解释。"))
            assertEquals(id, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertEquals(1, h.stub.requests)
            val wire = h.stub.requestBodies.single()
            assertTrue(wire.contains("Synthetic selected"))
            for (secret in listOf("SYNTHETIC_CLIENT_PREFIX", "SYNTHETIC_CLIENT_SUFFIX", "SYNTHETIC_NEIGHBOUR_SECRET",
                "SYNTHETIC_UNREAD_SECRET", "SYNTHETIC_ANSWER_SECRET", "SYNTHETIC_HELDOUT_SECRET")) {
                assertFalse(wire.contains(secret)); assertFalse(body.toString().contains(secret))
            }
        }
    }

    @Test fun unregisteredUnlearnedProtectedAndForgedTextAreRejectedBeforeModel() = runBlocking {
        Harness().use { h ->
            for ((block, quote) in listOf("unknown:p12:0" to "Synthetic selected", "0123abcd:p13:0" to "SYNTHETIC_UNREAD_SECRET",
                "harmless-id:p12:0" to "SYNTHETIC_ANSWER_SECRET", "ordinary-id:p12:0" to "SYNTHETIC_HELDOUT_SECRET",
                id to "SYNTHETIC_FORGED_TEXT")) {
                val forged = JsonObject(request(quote, blocks(block to quote)) + json("course" to "SYNTHETIC", "currentKps" to listOf("k2"),
                    "learnerVisible" to true, "scope" to json("allowsInternalReference" to true)))
                assertEquals(409, h.post(forged).first, block)
            }
            h.actor.execute { h.update("UPDATE outline_kp SET anchors='[]'") }
            assertEquals(409, h.post(request("Synthetic selected", blocks(id to "Synthetic selected"))).first)
            assertEquals(0, h.stub.requests, "No model request, retry or fallback may promote a client claim")
        }
    }

    @Test fun registeredSelectionRevokedDuringGenerationWithholdsAnswerAndCitations() = runBlocking {
        for (change in listOf("UPDATE source_docs SET learner_visible=0", "UPDATE source_docs SET blob_sha256='new-synthetic-version'",
            "UPDATE source_docs SET kind='sealed'", "UPDATE outline_kp SET anchors='[]'")) Harness().use { h ->
            h.reply(delayMs = 150)
            val response = async { h.post(request("Synthetic selected", blocks(id to "Synthetic selected"))) }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            h.actor.execute { h.update(change) }
            val (status, body) = withTimeout(5000) { response.await() }
            assertEquals(200, status); assertTrue(body.getValue("sources").jsonArray.isEmpty())
            assertTrue(body.str("text").contains("当前不可见")); assertFalse(body.toString().contains("有依据的解释。"))
        }
    }

    private val HttpStatusCodeBad = 400
}
