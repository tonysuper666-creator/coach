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

    private fun personal(book: String, text: String = "自写🌱 Synthetic selected.", start: Int = 5,
        hash: String = "a".repeat(64)): JsonObject = JsonObject(request(text,
        blocks("$book:p1:$start" to text, "$book:p1:100" to "PRIVATE_NEIGHBOUR")) +
        ("localSource" to json("schema" to 1, "book" to book, "format" to "png", "title" to "Self-authored scan",
            "consented" to true, "pages" to JsonArray(listOf(json("number" to 1, "length" to 200,
                "ocr" to true, "sha256" to hash))))))

    @Test fun firstConsentedPersonalImportExplainsWithoutCourseRegistrationAndPersistsOnlySentBlocks() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            h.reply()
            val input = personal(h.version)
            val (status, body) = h.post(input)
            assertEquals(200, status)
            assertEquals("${h.version}:p1:5", body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertFalse(h.stub.requestBodies.single().contains("PRIVATE_NEIGHBOUR"))
            val receipt = h.imports!!.register("phone", input, libraryExplainRequest(input))
            assertTrue(ImportedBookSources(h.dir.resolve("personal-imports")).isCurrent(receipt))
            assertFalse(h.imports.isCurrent(receipt.copy(device = "other-device")))
            assertNull(h.store.learnerSourcePage(h.version, 1), "Personal import must not grant course authority")
            assertEquals(409, h.post(JsonObject(input - "localSource")).first)
            assertEquals(1, h.stub.requests)
        }
    }

    @Test fun personalReceiptsRejectMissingConsentImpossibleOffsetsAndConflictingSnapshotsBeforeModel() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog()
            val input = personal(h.version)
            val source = input.getValue("localSource").jsonObject
            for (bad in listOf(JsonObject(source + ("consented" to JsonPrimitive(false))),
                JsonObject(source + ("book" to JsonPrimitive("0".repeat(64)))))) {
                assertEquals(400, h.post(JsonObject(input + ("localSource" to bad))).first)
            }
            assertEquals(400, h.post(personal(h.version, start = 199)).first)
            h.imports!!.register("phone", input, libraryExplainRequest(input))
            assertEquals(409, h.post(personal(h.version, hash = "b".repeat(64))).first)
            assertEquals(409, h.post(personal(h.version, text = "不同的原文" )).first)
            assertEquals(413, h.post(JsonObject(input + ("padding" to JsonPrimitive("x".repeat(131_073))))).first)
            assertEquals(0, h.stub.requests)
            // Selecting a prefix later is legitimate; overlapping UTF-16 characters must still match.
            val prefix = personal(h.version, text = "自写🌱")
            val receipt = h.imports.register("phone", prefix, libraryExplainRequest(prefix))
            assertTrue(h.imports.isCurrent(receipt))
        }
    }

    @Test fun personalReceiptCannotReclassifyKnownCatalogBookOrRestoreRevokedCoursePermissions() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            for (entry in listOf(h.entry(source = null), h.entry(title = "Synthetic answers"), h.entry(source = "unknown"))) {
                h.catalog(entry)
                assertEquals(409, h.post(personal(h.version)).first)
            }
            Files.writeString(h.libraryRoot.resolve("catalog.json"), "broken")
            assertEquals(409, h.post(personal(h.version)).first)
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun deletingPersonalRegistrationWhileGeneratingWithholdsReplyAndCitations() = runBlocking {
        Harness(local = true, personal = true).use { h ->
            h.catalog(); h.reply(delayMs = 250)
            val pending = async { h.post(personal(h.version)) }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            DriverManager.getConnection("jdbc:sqlite:${h.dir.resolve("personal-imports/sources.sqlite")}").use { db ->
                db.createStatement().use { it.executeUpdate("DELETE FROM blocks") }
            }
            val (status, body) = pending.await()
            assertEquals(200, status)
            assertTrue(body.getValue("sources").jsonArray.isEmpty())
            assertFalse(body.str("text").contains("有依据的解释"))
        }
    }

    @Test fun savedOcrPageBindingPreservesUtf16AnchorsAndCannotGrantOtherPages() = runBlocking {
        Harness(local = true).use { h ->
            val prefix = "自写🌱\n"
            val quote = "Synthetic selected."
            val text = prefix + quote
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic scan", 12, text) }
            h.savedBinding(text)
            val block = "${h.version}:p12:${prefix.length}"
            for ((anchor, selection) in listOf(
                "${h.version}:p12:${prefix.length - 1}" to quote,
                "${h.version}:p13:0" to "SYNTHETIC_UNREAD_SECRET.",
                "${h.version}:p11:0" to quote,
                block to "Forged text.")) {
                assertEquals(409, h.post(request(selection, blocks(anchor to selection))).first)
            }
            assertEquals(0, h.stub.requests)
            h.reply()
            val (status, body) = h.post(request(quote, blocks(block to quote)))
            assertEquals(200, status)
            assertEquals(block, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertFalse(h.stub.requestBodies.single().contains(prefix))
            // The snapshot is not a second authority: changing the ledger revokes it.
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic scan", 12, "Different trusted text.") }
            assertEquals(409, h.post(request(quote, blocks(block to quote))).first)
            assertEquals(1, h.stub.requests)
        }
    }

    @Test fun savedBookBindingsFailClosedOnSnapshotTamperAmbiguityAndRevocation() = runBlocking {
        Harness(local = true).use { h ->
            val text = "Synthetic selected."
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic scan", 12, text) }
            val block = "${h.version}:p12:0"
            val input = request(text, blocks(block to text))
            val snapshot = h.savedBinding(text)
            Files.writeString(snapshot, "{}")
            assertEquals(409, h.post(input).first)
            h.savedBinding(text)
            h.catalog(h.entry()) // Same original in PDF and saved-page registries is ambiguous.
            assertEquals(409, h.post(input).first)
            h.catalog()
            h.reply(delayMs = 250)
            val pending = async { h.post(input) }
            withTimeout(5_000) { while (h.stub.requests == 0) delay(10) }
            Files.delete(h.libraryRoot.resolve("local-books.json"))
            val (_, body) = pending.await()
            assertTrue(body.getValue("sources").jsonArray.isEmpty())
            assertFalse(body.str("text").contains("有依据的解释"))
            assertEquals(409, h.post(input).first)
        }
    }

    @Test fun boundParagraphExtractionAndQuestionRetainAllAuthorizationGates() = runBlocking {
        Harness(local = true).use { h ->
            h.actor.execute { registerSourcePage("SYNTHETIC", h.sourceId, "Synthetic book", 12,
                "An _economic_ explanation of resources and a_b costs of 1-2.") }
            val paragraph = "An eco-\nnomic explanation of re-\nsources and a_b costs of 1-2."
            val question = "Why are resources relevant?"
            val valid = JsonObject(request(paragraph, blocks(h.localId to paragraph)) + json("kind" to "ask", "question" to question))
            for (invalid in listOf("", "x".repeat(501))) {
                assertEquals(400, h.post(JsonObject(valid + json("question" to invalid))).first)
            }
            for ((block, selection) in listOf(
                "${"0".repeat(64)}:p12:0" to paragraph,
                "${h.version}:p13:0" to paragraph,
                h.localId to paragraph.replace("a_b", "ab"),
                h.localId to paragraph.replace("1-2", "12"))) {
                assertEquals(409, h.post(request(selection, blocks(block to selection))).first)
            }
            assertEquals(0, h.stub.requests)
            h.reply()
            val (status, body) = h.post(valid)
            assertEquals(200, status)
            assertEquals(h.localId, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertTrue(h.stub.requestBodies.single().contains(question))
            h.actor.execute { h.update("UPDATE outline_kp SET anchors='[]'") }
            assertEquals(409, h.post(valid).first)
            assertEquals(1, h.stub.requests)
        }
    }

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

    private inner class Harness(local: Boolean = false, val sourceId: String = "0123abcd", personal: Boolean = false) : AutoCloseable {
        val dir = Files.createTempDirectory("synthetic-library-scope-")
        val libraryRoot = Files.createDirectory(dir.resolve("private-library"))
        // Binding tests need bytes and their identity only; the importer tests validate actual PDFs with Poppler.
        val bookFile = libraryRoot.resolve("synthetic.pdf").also { Files.writeString(it, "Synthetic private file identity") }
        val version = ReaderLibrary.digest(Files.readAllBytes(bookFile))
        val localId get() = "$version:p12:340"
        fun entry(course: String = "SYNTHETIC", source: String? = sourceId, title: String = "Synthetic book", pages: Int = 13) =
            JsonObject(json("id" to "synthetic", "course" to course, "title" to title, "file" to "synthetic.pdf",
                "sha256" to version, "pages" to pages, "kind" to "textbook") +
                (source?.let { mapOf("explainSourceId" to JsonPrimitive(it)) } ?: emptyMap()))
        fun catalog(vararg entries: JsonObject) = Files.writeString(libraryRoot.resolve("catalog.json"),
            json("schema" to 1, "documents" to JsonArray(entries.toList())).toString())
        fun savedBinding(text: String): java.nio.file.Path {
            catalog()
            val snapshot = libraryRoot.resolve("saved-pages.json")
            Files.writeString(snapshot, json("schema" to 1, "book" to version, "format" to "png", "pages" to JsonArray(listOf(
                json("number" to 12, "text" to text, "ocr" to true),
                json("number" to 13, "text" to "SYNTHETIC_UNREAD_SECRET.", "ocr" to true)))).toString())
            val entry = json("id" to "saved-scan", "course" to "SYNTHETIC", "title" to "Synthetic scan",
                "file" to "synthetic.pdf", "sha256" to version, "explainSourceId" to sourceId,
                "pagesFile" to "saved-pages.json", "pagesSha256" to ReaderLibrary.digest(Files.readAllBytes(snapshot)))
            Files.writeString(libraryRoot.resolve("local-books.json"), json("schema" to 1, "books" to JsonArray(listOf(entry))).toString())
            return snapshot
        }
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir)
        val stub = StubTutor()
        fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { it.executeUpdate(sql) }
        }
        init {
            catalog(entry())
            store.enroll("phone", "synthetic-private-token-32-characters")
            store.registerSourcePage("SYNTHETIC", sourceId, "Synthetic book", 12,
                "Synthetic price elasticity is a ratio. Synthetic selected. SYNTHETIC_NEIGHBOUR_SECRET.")
            store.registerSourcePage("SYNTHETIC", sourceId, "Synthetic book", 13, "SYNTHETIC_UNREAD_SECRET.")
            store.registerSourcePage("SYNTHETIC", "harmless-id", "Synthetic answers", 12, "SYNTHETIC_ANSWER_SECRET.")
            store.registerSourcePage("SYNTHETIC", "ordinary-id", "Synthetic sealed material", 12, "SYNTHETIC_HELDOUT_SECRET.", learnerVisible = false)
            for ((kp, page) in listOf("k1" to 12, "k2" to 13)) update(
                """INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id)
                    VALUES('$kp','SYNTHETIC','m',$page,'x','x','[]','[]','[{"docId":"$sourceId","pdfPage":$page}]',0,NULL)""")
            store.append("SYNTHETIC", "kp_opened", json("kpId" to "k1"))
        }
        val actor = LedgerActor(jobs, store)
        val library = if (local) ReaderLibrary(libraryRoot) else null
        val imports = if (personal) ImportedBookSources(dir.resolve("personal-imports")) else null
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 16))
            libraryExplainRoutes(actor, ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), readSources = { pack, _ -> pack }), library, imports)
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

    @Test fun explicitlyBoundLocalFileExplainsLearnedSelectionAndKeepsLocalCitationAlias() = runBlocking {
        Harness(local = true).use { h ->
            h.reply()
            val input = JsonObject(request("Synthetic selected", blocks(
                h.localId to "CLIENT_PREFIX. Synthetic selected. CLIENT_SUFFIX.",
                "${h.version}:p13:0" to "SYNTHETIC_UNREAD_SECRET")) + json(
                "course" to "CLIENT_FAKE_COURSE", "explainSourceId" to "CLIENT_FAKE_SOURCE"))
            val (status, body) = h.post(input)
            assertEquals(200, status)
            assertTrue(body.str("text").contains("有依据的解释。"))
            assertEquals(h.localId, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertEquals(1, h.stub.requests)
            val wire = h.stub.requestBodies.single()
            assertTrue(wire.contains("Synthetic selected"))
            for (secret in listOf("CLIENT_PREFIX", "CLIENT_SUFFIX", "SYNTHETIC_UNREAD_SECRET", "SYNTHETIC_NEIGHBOUR_SECRET",
                "CLIENT_FAKE_COURSE", "CLIENT_FAKE_SOURCE")) assertFalse(wire.contains(secret), secret)
        }
    }

    @Test fun boundNamespacedLearnerSourceExplainsOnlyItsLearnedPage() = runBlocking {
        Harness(local = true, sourceId = "textbook:synthetic.chapter-1").use { h ->
            h.reply()
            val (status, body) = h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected")))
            assertEquals(200, status)
            assertTrue(body.str("text").contains("有依据的解释。"))
            assertEquals(h.localId, body.getValue("sources").jsonArray.single().jsonObject.str("docId"))
            assertEquals(409, h.post(request("SYNTHETIC_UNREAD_SECRET", blocks(
                "${h.version}:p13:0" to "SYNTHETIC_UNREAD_SECRET"))).first)
            assertEquals(1, h.stub.requests)
        }
    }

    @Test fun localHashCannotBypassOptInByNamingAnExistingLedgerDocument() = runBlocking {
        Harness(local = true).use { h ->
            h.actor.execute {
                registerSourcePage("SYNTHETIC", h.version, "Synthetic hash-named book", 12, "Synthetic selected")
                h.update("""UPDATE outline_kp SET anchors='[{"docId":"${h.version}","pdfPage":12}]' WHERE kp_id='k1'""")
            }
            for (entries in listOf(emptyArray(), arrayOf(h.entry(source = null)))) {
                h.catalog(*entries)
                val forged = JsonObject(request("Synthetic selected", blocks(h.localId to "Synthetic selected")) +
                    json("explainSourceId" to h.version, "course" to "SYNTHETIC", "learnerVisible" to true))
                assertEquals(409, h.post(forged).first)
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun localBindingRejectsUnknownAmbiguousWrongCourseProtectedAndOutOfRangeMappings() = runBlocking {
        Harness(local = true).use { h ->
            val duplicate = JsonObject(h.entry() + ("id" to JsonPrimitive("second-registration")))
            for (entries in listOf(arrayOf(h.entry(source = "unknown")), arrayOf(h.entry(course = "OTHER")),
                arrayOf(h.entry(title = "Synthetic answers")), arrayOf(h.entry(pages = 11)), arrayOf(h.entry(), duplicate))) {
                h.catalog(*entries)
                assertEquals(409, h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))).first)
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun localBindingDoesNotAuthorizeUnreadProtectedOrForgedSelections() = runBlocking {
        Harness(local = true).use { h ->
            for ((source, page, quote) in listOf(Triple("0123abcd", 13, "SYNTHETIC_UNREAD_SECRET"),
                Triple("harmless-id", 12, "SYNTHETIC_ANSWER_SECRET"), Triple("ordinary-id", 12, "SYNTHETIC_HELDOUT_SECRET"),
                Triple("0123abcd", 12, "SYNTHETIC_FORGED_TEXT"))) {
                h.catalog(h.entry(source = source))
                val forged = JsonObject(request(quote, blocks("${h.version}:p$page:0" to quote)) +
                    json("currentKps" to listOf("k2"), "scope" to json("allowsInternalReference" to true), "learnerVisible" to true))
                assertEquals(409, h.post(forged).first, source)
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun changedFileAndUnreadableCatalogFailClosedBeforeModel() = runBlocking {
        Harness(local = true).use { h ->
            val original = Files.readAllBytes(h.bookFile)
            Files.writeString(h.bookFile, "Changed synthetic private file")
            assertEquals(409, h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))).first)
            Files.write(h.bookFile, original)
            Files.writeString(h.libraryRoot.resolve("catalog.json"), "invalid synthetic catalog")
            assertEquals(409, h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))).first)
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun localBindingRevokedOrFileChangedDuringGenerationWithholdsAnswerAndCitations() = runBlocking {
        for (change in listOf<(Harness) -> Unit>({ it.catalog(it.entry(source = null)); Unit },
            { it.catalog(it.entry(course = "OTHER")); Unit }, { Files.writeString(it.bookFile, "Changed synthetic file"); Unit })) {
            Harness(local = true).use { h ->
                h.reply(delayMs = 150)
                val response = async { h.post(request("Synthetic selected", blocks(h.localId to "Synthetic selected"))) }
                withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
                change(h)
                val (status, body) = withTimeout(5000) { response.await() }
                assertEquals(200, status)
                assertTrue(body.getValue("sources").jsonArray.isEmpty())
                assertTrue(body.str("text").contains("当前不可见"))
                assertFalse(body.toString().contains("有依据的解释。"))
            }
        }
    }

    private val HttpStatusCodeBad = 400
}
