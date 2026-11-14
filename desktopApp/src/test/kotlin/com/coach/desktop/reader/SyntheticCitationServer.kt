package com.coach.desktop.reader

import com.coach.ai.*
import com.coach.client.CourseHttp
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.server.*
import com.coach.server.reader.v2.*
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

/** Synthetic-only real product HTTP fixture. Pack/outline adapted from MainlineQuestionCitationsTest.
 * The bridge emits deterministic SSE; no model process or production configuration is consulted. */
internal class SyntheticCitationServer : AutoCloseable {
    val dir = Files.createTempDirectory("synthetic-citation-window-")
    val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = LedgerStore(Files.createDirectories(dir.resolve("db")))
    val text = "A synthetic basket has two pears. Count pears once."
    val blockText = text.substringBefore(" Count pears")
    private val later = "A synthetic basket has two pears. SYNTHETIC_UNREAD_SECRET."
    private val page = SourcePage("synthetic-book", 1, text, sourceSha256 = "a".repeat(64))
    val pdfHash: String
    private val bridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val bridgeExecutor = java.util.concurrent.Executors.newCachedThreadPool()
    var fallback = false
    val bridgeRequests = java.util.concurrent.atomic.AtomicInteger()
    init {
        val library = Files.createDirectories(dir.resolve("library"))
        val file = library.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            for (line in listOf(text, "SYNTHETIC SECOND PAGE: never authorized by this citation.", "SYNTHETIC THIRD PAGE.")) {
                val p = PDPage(); pdf.addPage(p)
                PDPageContentStream(pdf, p).use { out -> out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f); out.newLineAtOffset(40f, 700f); out.showText(line); out.endText() }
            }
            pdf.save(file.toFile())
        }
        pdfHash = sha256(Files.readAllBytes(file))
        Files.writeString(library.resolve("catalog.json"), json("schema" to 1, "documents" to JsonArray(listOf(json(
            "id" to page.docId, "course" to "SYNTHETIC", "title" to "Synthetic pears", "file" to "synthetic.pdf",
            "sha256" to pdfHash, "pages" to 3, "kind" to "textbook", "printedPageOffset" to 0)))).toString())
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
        bridge.executor = bridgeExecutor
        bridge.createContext("/v1/chat/completions") { e ->
            e.requestBody.readBytes(); bridgeRequests.incrementAndGet()
            val id = if (fallback) "S1Bmissing" else token()
            val answer = json("paragraphs" to JsonArray(listOf(json("text" to "每个梨只计一次。", "anchors" to listOf(id))))).toString()
            val delta = json("model" to "claude-sonnet-5-5", "choices" to JsonArray(listOf(json("index" to 0, "delta" to json("content" to answer)))))
            val end = json("choices" to JsonArray(emptyList()), "model" to "claude-sonnet-5-5", "x_coach" to json("status" to "complete", "reason" to null, "finish_reason" to "stop", "served_by" to "claude-sonnet-5-5", "fallback" to false, "physical_calls" to 1, "request_id" to "synthetic", "queue_ms" to 0, "ttft_ms" to 1, "elapsed_ms" to 1))
            val bytes = "data: $delta\n\ndata: $end\n\ndata: [DONE]\n\n".toByteArray()
            e.responseHeaders.add("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        bridge.start()
    }
    private fun layout(p: SourcePage) = ReaderCitationPage(LayoutPage(p.pdfPage, "ok", 1, "single_column", null, 0,
        listOf(600.0, 800.0), listOf(LayoutBlock("b1", "text", listOf(20.0, 20.0, 500.0, 60.0), 0, 0, "ok", blockText, true, null))), pdfHash)
    private fun token(): String {
        val digest = sha256(listOf(page.docId, page.sourceSha256!!, pdfHash, "1", "b1", "0", blockText.length.toString(), text).joinToString("\u0000").toByteArray()).take(32)
        return "S1B$digest"
    }
    val actor = LedgerActor(jobs, store)
    val mainline = Mainline(MockLibrary(dir.resolve("papers")))
    // ReaderLibrary's constructor is internal to server:app. Reflection is confined to this test
    // fixture, so the production configuration/API need not be widened for a synthetic library.
    val library = ReaderLibrary::class.java.getDeclaredConstructor(Path::class.java, String::class.java)
        .newInstance(dir.resolve("library"), System.getProperty("coach.pdf.bin") ?: "")
    val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 16)
        ledgerRoutes(actor, pushes)
        mainlineRoutes(actor, pushes, mainline, ReaderExplanation(actor, BridgeClient(BridgeConfig("http://127.0.0.1:${bridge.address.port}")), citationLayout = ::layout))
        readerRoutes(actor, pushes, library, mainline)
    }.also { it.start(false) }
    val base: String get() = runBlocking { "http://127.0.0.1:${engine.engine.resolvedConnectors().single().port}" }
    val http = CourseHttp(base, "phone", "synthetic-private-token-32-characters", "SYNTHETIC")
    val reader = ReaderHttpSource(http.reader)
    fun session() = DesktopExplanationSession(ExplanationOwner("SYNTHETIC", 1, "fruit1"), CourseExplanationTransport(http), reader)
    fun update(sql: String) = DriverManager.getConnection("jdbc:sqlite:${store.directory.resolve("coach.sqlite")}").use { db -> db.createStatement().use { it.executeUpdate(sql) } }
    override fun close() { http.close(); engine.stop(100, 1000); runBlocking { actor.close() }; jobs.cancel(); bridge.stop(0); bridgeExecutor.shutdownNow(); dir.toFile().deleteRecursively() }
}
