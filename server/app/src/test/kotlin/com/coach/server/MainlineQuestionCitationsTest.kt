package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
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

/** Current page-citation contract; no dependency on frozen block-layout work. Entirely invented sources. */
class MainlineQuestionCitationsTest {
    private val text = "A synthetic basket has two pears. Count pears once."
    private val page = SourcePage("synthetic-book", 1, text)
    private val sourceVersion = "a".repeat(64)
    private val later = "A synthetic basket has two pears. SYNTHETIC_UNREAD_SECRET."
    private fun payload(id: String) = json("paragraphs" to JsonArray(listOf(json("text" to "每个梨只计一次。", "anchors" to listOf(id))))).toString()

    private inner class Harness : AutoCloseable {
        val dir = Files.createTempDirectory("synthetic-question-http-")
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createDirectories(dir.resolve("db")))
        val stub = StubTutor()
        init {
            val root = Files.createDirectories(dir.resolve("papers/mcq-mainline"))
            val candidate = json("contract_version" to "1.0.0", "id" to "fruit1", "course_id" to "SYNTHETIC", "week" to 1,
                "kp_id" to "fruit", "section_id" to "orchard", "variant_group_id" to "fruit-group",
                "kind" to "concept", "difficulty" to 0, "key_state" to "VERIFIED",
                "utility" to json("rationale" to "Counting is needed before combining baskets.", "evidence" to listOf("synthetic-handbook-1")),
                "sources" to JsonArray(listOf(json("kind" to "textbook", "document_id" to "synthetic-book", "locator" to "p.1", "anchor" to "Count pears once."))),
                "priority" to json("star" to 3, "star_source" to "Synthetic handbook 1", "lecture_emphasis" to "verified", "lecture_emphasis_source" to "Synthetic lecture 1"),
                "prerequisite_kp_ids" to emptyList<String>(), "stem" to "A synthetic basket has two pears. How many pears?",
                "options" to listOf("One", "Two", "Three"), "answer" to 1, "remember" to "Count every pear once.",
                "explanation" to json("correct" to "Two pears are present.", "background" to "Each pear contributes one.", "source" to "Synthetic book p.1",
                    "wrong" to JsonArray(listOf(json("option" to 0, "reason" to "One omitted."), json("option" to 2, "reason" to "One counted twice.")))))
            val learning = JsonObject(candidate + json("runtime_approval" to json("screening_decision" to "keep", "isolation_state" to "CLEARED",
                "release_state" to "APPROVED", "independent_key_evidence" to "synthetic-key-review", "utility_review_evidence" to "synthetic-utility-review",
                "originality_review_evidence" to "synthetic-originality-review", "candidate_sha256" to sha256(canonicalJson(candidate).encodeToByteArray()))))
            val item = json("id" to "fruit1", "type" to "mcq", "kp" to "fruit", "kp_week" to 1, "marks" to 1,
                "tier" to "detail", "key_state" to "VERIFIED", "stem" to candidate.str("stem"), "options" to candidate.getValue("options"),
                "answer" to 1, "source" to "Synthetic book p.1", "fact_ids" to listOf("F1"), "explanation" to "正确：Two.", "learning" to learning)
            Files.writeString(root.resolve("W01.json"), json("id" to "synthetic-week", "course" to "SYNTHETIC", "kind" to "pack", "minutes" to 10,
                "sections" to JsonArray(listOf(json("title" to "Synthetic orchard", "items" to JsonArray(listOf(item)))))).toString())
            Files.writeString(root.resolve("W01-facts.md"), """```jsonl
{"fact_id":"F1","fact":"Count pears.","type":"definition","source":"Synthetic lecture 1","kp":"fruit"}
{"fact_id":"F2","fact":"Count apples.","type":"detail","source":"Synthetic lecture 1","kp":"later"}
```""")
            Files.writeString(root.resolve("W01-notes.json"), json("schema_version" to 1, "week" to 1, "sections" to JsonArray(listOf(
                json("section_id" to "orchard", "title" to "Fruit", "body" to "Count pears.", "fact_ids" to listOf("F1"), "sources" to listOf("Synthetic lecture 1")),
                json("section_id" to "later", "title" to "Later", "body" to "Count apples.", "fact_ids" to listOf("F2"), "sources" to listOf("Synthetic lecture 1"))))).toString())
            store.enroll("phone", "synthetic-private-token-32-characters")
            store.append("SYNTHETIC", "outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "fruit", "title" to "Fruit", "week" to 1)))))
            store.registerSourcePage("SYNTHETIC", page.docId, "Synthetic book", 1, text)
            store.registerSourcePage("SYNTHETIC", page.docId, "Synthetic book", 9, later)
            store.registerSourcePage("SYNTHETIC", "foreign-book", "Synthetic foreign", 1, "A synthetic basket has two pears. SYNTHETIC_FOREIGN_SECRET.")
            store.registerSourcePage("SYNTHETIC", "hidden-book", "Synthetic hidden", 1, "A synthetic basket has two pears. SYNTHETIC_HIDDEN_SECRET.", learnerVisible = false)
            update("UPDATE source_docs SET blob_sha256='$sourceVersion'")
            DriverManager.getConnection("jdbc:sqlite:${store.directory.resolve("coach.sqlite")}").use { db ->
                db.prepareStatement("INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id) VALUES(?,'SYNTHETIC','m',?,'x','x','[]','[]',?,0,NULL)").use {
                    for ((kp, n) in listOf("fruit" to 1, "later" to 9)) {
                        it.setString(1, kp); it.setInt(2, n); it.setString(3, """[{"docId":"synthetic-book","pdfPage":$n}]"""); it.executeUpdate()
                    }
                }
            }
        }
        val actor = LedgerActor(jobs, store)
        val mainline = Mainline(MockLibrary(dir.resolve("papers")))
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            val pushes = MutableSharedFlow<String>(extraBufferCapacity = 16)
            ledgerRoutes(actor, pushes)
            // Exercise the HTTP/model boundary on Windows; the production OS sandbox remains the default.
            mainlineRoutes(actor, pushes, mainline, ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), readSources = { pack, _ -> pack }))
        }.also { it.start(false) }
        val client = HttpClient.newHttpClient()
        var serial = 0
        fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${store.directory.resolve("coach.sqlite")}").use { db -> db.createStatement().use { it.executeUpdate(sql) } }
        suspend fun request(fields: JsonObject = json()): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
            val input = JsonObject(json("requestId" to (++serial).toString(16), "kind" to "explain", "itemId" to "fruit1", "question" to "How many pears?") + fields)
            val port = engine.engine.resolvedConnectors().single().port
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/courses/SYNTHETIC/mainline/1/explain"))
                .header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                .POST(HttpRequest.BodyPublishers.ofString(input.toString())).build()
            val response = client.send(req, HttpResponse.BodyHandlers.ofString())
            response.statusCode() to LedgerJson.parseToJsonElement(response.body()).jsonObject
        }
        fun reply(id: String = "S1", delayMs: Long = 1) { stub.stream(delayMs, payload(id), StubTutor.terminal("complete"), StubTutor.DONE) }
        fun assertSecretsAbsent(body: JsonObject) {
            for (secret in listOf("SYNTHETIC_UNREAD_SECRET", "SYNTHETIC_FOREIGN_SECRET", "SYNTHETIC_HIDDEN_SECRET")) {
                assertFalse(stub.requestBodies.last().contains(secret), "Forbidden page reached bridge: $secret")
                assertFalse(body.toString().contains(secret))
            }
        }
        override fun close() { engine.stop(100, 1000); runBlocking { actor.close() }; jobs.cancel(); stub.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun questionWithoutSectionReturnsOnlyAnAuthorizedPageCard() = runBlocking {
        Harness().use { h ->
            h.reply()
            val (status, body) = h.request()
            assertEquals(200, status)
            val card = body.getValue("sources").jsonArray.single().jsonObject
            assertEquals(page.docId, card.str("docId")); assertEquals(1, card.num("pdfPage"))
            assertEquals("S1", card.str("anchor"))
            assertEquals(setOf("anchor", "docId", "pdfPage"), card.keys, "No invented block identity, version, offsets or raw snapshot")
            assertTrue(body.str("text").contains("每个梨只计一次。"))
            assertEquals(1, h.stub.requests)
            h.assertSecretsAbsent(body)
        }
    }

    @Test fun unrelatedClientSectionAndPointIdsCannotExpandTheQuestionScope() = runBlocking {
        Harness().use { h ->
            for (section in listOf("later", "unknown", "orchard")) {
                h.reply()
                val (status, body) = h.request(json("sectionId" to section, "currentKps" to listOf("later"), "kpId" to "later", "quote" to later))
                assertEquals(200, status)
                h.assertSecretsAbsent(body)
                assertEquals(1, body.getValue("sources").jsonArray.single().jsonObject.num("pdfPage"))
            }
        }
    }

    @Test fun invalidQuestionAndStaleNoteSelectionAreRejectedBeforeBridge() = runBlocking {
        Harness().use { h ->
            for (fields in listOf(json("itemId" to "unknown", "sectionId" to "later"),
                json("itemId" to "", "sectionId" to "orchard", "version" to "stale", "start" to 0, "end" to 12, "quote" to "Count pears."))) {
                assertEquals(409, h.request(fields).first)
            }
            assertEquals(0, h.stub.requests)
        }
    }

    @Test fun invalidBlockIdentityAndUnknownPageAreRejectedWithoutSalvage() = runBlocking {
        Harness().use { h ->
            // A block-shaped anchor, foreign identity or stale revision grants no fallback page.
            for (id in listOf("S1Bmissing", "foreign-book", sourceVersion, "b".repeat(64), "S0", "S2", "S999999999999Bmissing")) {
                h.reply(id)
                val (status, body) = h.request(json("sectionId" to "orchard"))
                assertEquals(200, status); assertTrue(body.getValue("sources").jsonArray.isEmpty())
                assertTrue(body.str("text").contains("无法确认")); h.assertSecretsAbsent(body)
            }
        }
    }

    @Test fun versionOrVisibilityChangeDuringHttpGenerationWithholdsSnapshots() = runBlocking {
        for (change in listOf("UPDATE source_docs SET blob_sha256='${"b".repeat(64)}'", "UPDATE source_docs SET learner_visible=0",
            "UPDATE source_docs SET tutor_visible=0", "UPDATE source_pages SET text='Replaced synthetic page.' WHERE pdf_page=1",
            "UPDATE outline_kp SET anchors='[]'")) Harness().use { h ->
            h.reply(delayMs = 150)
            val response = async(Dispatchers.Default) { h.request(json("sectionId" to "orchard")) }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            h.actor.execute { h.update(change) }
            val (status, body) = withTimeout(5000) { response.await() }
            assertEquals(200, status); assertTrue(body.getValue("sources").jsonArray.isEmpty())
            assertTrue(body.str("text").contains("当前不可见")); assertFalse(body.toString().contains(text))
        }
    }
}
