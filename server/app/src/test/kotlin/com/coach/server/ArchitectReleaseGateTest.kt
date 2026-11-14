package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.server.reader.v2.*
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

/** Integration fixture for main + PR142 + frozen PR148. Entirely invented sources. */
class ArchitectReleaseGateTest {
    private val text = "A synthetic basket has two pears. Count pears once."
    private val page = SourcePage("synthetic-book", 1, text, sourceSha256 = "a".repeat(64))
    private val later = "A synthetic basket has two pears. SYNTHETIC_UNREAD_SECRET."
    private fun layout(p: SourcePage) = ReaderCitationPage(LayoutPage(p.pdfPage, "ok", 1, "single_column", null, 0,
        listOf(600.0, 800.0), listOf(LayoutBlock("b1", "text", listOf(20.0, 20.0, 500.0, 60.0), 0, 0, "ok",
            p.text.substringBefore(" Count pears"), true, null))), "f".repeat(64))
    private fun token(p: SourcePage = page) = Regex("【(S1B[a-f0-9]+)】").find(
        ReaderBlockCitations(SourcePack(listOf(p)), ::layout).render())!!.groupValues[1]
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
            update("UPDATE source_docs SET blob_sha256='${page.sourceSha256}'")
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
            mainlineRoutes(actor, pushes, mainline, ReaderExplanation(actor, BridgeClient(BridgeConfig(stub.baseUrl)), citationLayout = ::layout))
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
        fun reply(id: String = token(), delayMs: Long = 1) { stub.stream(delayMs, payload(id), StubTutor.terminal("complete"), StubTutor.DONE) }
        fun assertSecretsAbsent(body: JsonObject) {
            for (secret in listOf("SYNTHETIC_UNREAD_SECRET", "SYNTHETIC_FOREIGN_SECRET", "SYNTHETIC_HIDDEN_SECRET")) {
                assertFalse(stub.requestBodies.last().contains(secret), "Forbidden page reached bridge: $secret")
                assertFalse(body.toString().contains(secret))
            }
        }
        override fun close() { engine.stop(100, 1000); runBlocking { actor.close() }; jobs.cancel(); stub.close(); dir.toFile().deleteRecursively() }
    }

    private fun retractedWeek(root: java.nio.file.Path) {
        val f = root.resolve("papers/mcq-mainline/W01.json")
        Files.writeString(f, Files.readString(f).replace("\"key_state\":\"VERIFIED\"", "\"key_state\":\"DRAFT\""))
    }

    /** A1: question identity retracted while the model is generating. Expect no snapshot release. */
    @Test fun a1_itemRetractedDuringGenerationWithholdsSnapshots() = runBlocking {
        Harness().use { h ->
            h.reply(delayMs = 150)
            val response = async(Dispatchers.Default) { h.request() }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            h.actor.execute { retractedWeek(h.dir) }
            val (status, body) = withTimeout(5000) { response.await() }
            // control: a fresh request after the retraction is refused at entry
            val (after, _) = h.request()
            assertEquals(409, after, "control: retracted item must be refused at entry")
            assertEquals(200, status)
            assertTrue(body.getValue("sources").jsonArray.isEmpty(), "released after identity change: $body")
        }
    }

    /** A2: the trusted ceiling narrows (point retired) during generation. Expect no snapshot release. */
    @Test fun a2_scopeNarrowedDuringGenerationWithholdsSnapshots() = runBlocking {
        Harness().use { h ->
            h.reply(delayMs = 150)
            val response = async(Dispatchers.Default) { h.request() }
            withTimeout(5000) { while (h.stub.framesWritten == 0) delay(5) }
            h.actor.execute { h.update("UPDATE outline_kp SET retired=1 WHERE kp_id='fruit'") }
            val (status, body) = withTimeout(5000) { response.await() }
            val now = h.actor.execute { learnedThrough("SYNTHETIC", com.coach.domain.scope.VisibleScope.learner(), setOf("fruit")) }
            assertEquals(com.coach.domain.scope.VisibleScope.NONE, now, "control: new ceiling is closed")
            assertEquals(200, status)
            assertTrue(body.getValue("sources").jsonArray.isEmpty(), "released outside current ceiling: $body")
        }
    }

    /** B: section path. Forged sectionId with another section's quote/version is refused; a real later selection is the ceiling by design. */
    @Test fun b_sectionPathForgeryRefusedBeforeBridge() = runBlocking {
        Harness().use { h ->
            val pears = notesVersion("Count pears."); val apples = notesVersion("Count apples.")
            for (fields in listOf(
                json("itemId" to "", "sectionId" to "later", "version" to pears, "start" to 0, "end" to 12, "quote" to "Count pears."),
                json("itemId" to "", "sectionId" to "orchard", "version" to apples, "start" to 0, "end" to 13, "quote" to "Count apples."),
                json("itemId" to "", "sectionId" to "orchard", "version" to pears, "start" to 0, "end" to 12, "quote" to "SYNTHETIC_UNREAD_SECRET"),
                json("itemId" to "", "sectionId" to "ghost", "version" to pears, "start" to 0, "end" to 12, "quote" to "Count pears."))) {
                assertEquals(409, h.request(fields).first, "forged section accepted: $fields")
            }
            assertEquals(0, h.stub.requests)
        }
    }

    /** C: wrong week / wrong course on the question path never reaches the bridge. */
    @Test fun c_wrongWeekOrCourseRefused() = runBlocking {
        Harness().use { h ->
            val port = h.engine.engine.resolvedConnectors().single().port
            for (path in listOf("/v1/courses/SYNTHETIC/mainline/2/explain", "/v1/courses/OTHER/mainline/1/explain")) {
                val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                    .header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                    .POST(HttpRequest.BodyPublishers.ofString(json("requestId" to "ab", "kind" to "explain", "itemId" to "fruit1", "question" to "How many pears?").toString())).build()
                val code = h.client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
                assertTrue(code in setOf(403, 404, 409), "$path -> $code")
            }
            assertEquals(0, h.stub.requests)
        }
    }

    /** D: learner question text naming unread content does not widen retrieval. */
    @Test fun d_questionTextCannotPullUnreadPages() = runBlocking {
        Harness().use { h ->
            h.reply()
            val (status, body) = h.request(json("question" to "UNREAD FOREIGN HIDDEN SECRET basket pears?"))
            assertEquals(200, status); h.assertSecretsAbsent(body)
            body.getValue("sources").jsonArray.forEach { assertEquals(1, it.jsonObject.num("pdfPage")); assertEquals(page.docId, it.jsonObject.str("docId")) }
        }
    }

    /** E: legitimate block plus a bogus same-page token: block card kept, page fallback card, nothing foreign. */
    @Test fun e_mixedValidAndBogusAnchors() = runBlocking {
        Harness().use { h ->
            h.stub.stream(1, json("paragraphs" to JsonArray(listOf(json("text" to "每个梨只计一次。", "anchors" to listOf(token(), "S1Bbogus"))))).toString(),
                StubTutor.terminal("complete"), StubTutor.DONE)
            val (status, body) = h.request()
            assertEquals(200, status); h.assertSecretsAbsent(body)
            val modes = body.getValue("sources").jsonArray.map { it.jsonObject.str("mode") to it.jsonObject.num("pdfPage") }
            assertTrue(modes.all { it.second == 1L }, "$modes")
            println("E modes=$modes text=${body.str("text")}")
        }
    }
}
