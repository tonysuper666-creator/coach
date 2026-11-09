package com.coach.server

import com.coach.client.CourseHttp
import com.coach.client.ReaderHttp
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

private const val readerToken = "synthetic-reader-token-at-least-32-characters"

/** A real, entirely invented PDF, including xref offsets. No private course fixtures. */
private fun inventedPdf(pages: Int = 34, label: String = "Synthetic pears") : ByteArray {
    val objects = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>", "", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    val kids = mutableListOf<Int>()
    repeat(pages) { index ->
        val pageId = objects.size + 1; kids += pageId
        val stream = "BT /F1 18 Tf 30 300 Td ($label PDF ${index + 1}) Tj ET"
        objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] /Resources << /Font << /F1 3 0 R >> >> /Contents ${pageId + 1} 0 R >>"
        objects += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
    }
    objects[1] = "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count $pages >>"
    val pdf = StringBuilder("%PDF-1.4\n")
    val offsets = mutableListOf(0)
    objects.forEachIndexed { i, obj -> offsets += pdf.length; pdf.append("${i + 1} 0 obj\n$obj\nendobj\n") }
    val xref = pdf.length
    pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.drop(1).forEach { pdf.append("%010d 00000 n \n".format(it)) }
    pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return pdf.toString().toByteArray(Charsets.US_ASCII)
}

private fun catalog(root: Path, pdf: ByteArray = inventedPdf(), extra: List<JsonObject> = emptyList()): ReaderLibrary {
    Files.createDirectories(root)
    Files.write(root.resolve("synthetic.pdf"), pdf)
    val doc = json("id" to "orchard", "course" to "C1", "title" to "Invented orchard textbook", "file" to "synthetic.pdf",
        "sha256" to ReaderLibrary.digest(pdf), "pages" to 34, "printedPageOffset" to 31, "kind" to "textbook")
    Files.writeString(root.resolve("catalog.json"), json("schema" to 1, "documents" to JsonArray(listOf(doc) + extra)).toString())
    return ReaderLibrary(root)
}

private class ReaderService(val base: String, val store: LedgerStore, val reader: ReaderHttp) {
    suspend fun request(path: String, body: String? = null, device: String? = "phone", token: String = readerToken): Pair<Int, String> = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$base/v1/courses/$path"))
        if (device != null) b.header("X-Coach-Device", device).header("Authorization", "Bearer $token")
        if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body))
        val r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString())
        r.statusCode() to r.body()
    }
}

private suspend fun withReader(db: Path, library: ReaderLibrary, withLedger: Boolean = true, block: suspend (ReaderService) -> Unit) {
    val store = LedgerStore(Files.createDirectories(db))
    store.enroll("phone", readerToken); store.enroll("desktop", readerToken)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val actor = LedgerActor(scope, store)
    val pushes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { if (withLedger) ledgerRoutes(actor, pushes); readerRoutes(actor, pushes, library) }
    server.start(false)
    val base = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
    val http = CourseHttp(base, "phone", readerToken, "C1")
    try { block(ReaderService(base, store, http.reader)) }
    finally { http.close(); server.stopSuspend(0, 500); actor.close(); scope.cancel() }
}

private fun noteBody(page: JsonObject, mutation: String = "save-note", revision: Long = 0, note: String = "**Personal** observation") = json(
    "id" to "note-one", "version" to page.str("version"), "pdfPage" to page.num("pdfPage"),
    "textSha256" to page.str("textSha256"), "start" to 0, "end" to 15, "quote" to page.str("text").take(15),
    "note" to note, "deleted" to false, "baseRevision" to revision, "mutationId" to mutation)

class ReaderTest {
    @Test fun registeredSlidesUseTheirOwnPdfPagesAndReadingsRequireExplicitSelection() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-sources")
        try {
            val version = ReaderLibrary.digest(inventedPdf())
            fun entry(id: String, kind: String, week: Int? = null) = json("id" to id, "course" to "C1",
                "title" to "Invented source", "file" to "synthetic.pdf", "sha256" to version,
                "pages" to 34, "kind" to kind, "week" to week)
            withReader(dir.resolve("db"), catalog(dir.resolve("library"), extra = listOf(
                entry("week-two", "slides", 2), entry("required-reading", "reading")))) { s ->
                val resolved = s.reader.resolve("W2 课件 p.3").body!!
                assertEquals("week-two", resolved.str("documentId")); assertEquals(3, resolved.num("pdfPage"))
                assertEquals(JsonNull, resolved["printedPage"])
                assertContains(s.reader.page("week-two", 3).body!!.str("text"), "PDF 3")
                assertTrue(s.reader.page("required-reading", 1).ok)
                assertEquals(404, s.reader.page("unregistered-key", 1).status)
                assertEquals(422, s.reader.resolve("W3 课件 p.3").status)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun actualPdfAndPrintedPagesTravelThroughPairedClientWithoutAnyAiService() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-pdf")
        try {
            withReader(dir.resolve("db"), catalog(dir.resolve("library"))) { s ->
                assertTrue(s.reader.catalog().ok)
                assertEquals(1, s.reader.catalog().body!!.getValue("documents").jsonArray.size)
                assertEquals("disabled_no_local_inference", s.reader.catalog().body!!.str("privateAi"))
                val resolved = s.reader.resolve("教材 p.1").body!!
                assertEquals(32, resolved.num("pdfPage")); assertEquals("page_only", resolved.str("anchorStatus"))
                val page = s.reader.page("orchard", 32).body!!
                assertEquals(1, page.num("printedPage")); assertContains(page.str("text"), "Synthetic pears PDF 32")
                assertEquals(JsonNull, s.reader.page("orchard", 31).body!!["printedPage"])
                val image = s.reader.image("orchard", 32)!!
                assertEquals(listOf(137, 80, 78, 71, 13, 10, 26, 10), image.take(8).map { it.toInt() and 255 })
                assertContentEquals(image, s.reader.image("orchard", 32))
                assertEquals(1600, java.nio.ByteBuffer.wrap(image, 16, 4).int)
                assertEquals(422, s.reader.resolve("教材 p.999").status)
                assertEquals(422, s.reader.resolve("教材 p.0").status)
                assertEquals(422, s.reader.resolve("教材 p.1&another=value").status)
                assertEquals(422, s.reader.resolve("W2 课件 p.1").status)
                assertEquals(404, s.reader.page("orchard", 0).status)
                assertEquals(404, s.request("OTHER/reader/orchard/pages/1").first)
                assertEquals(401, s.request("C1/reader", device = null).first)
                assertEquals(401, s.request("C1/reader", token = "wrong").first)
                assertEquals(401, s.request("C1/reader?token=$readerToken").first)
                assertEquals(404, s.request("C1/reader/orchard/ask", "{}").first)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun progressAndNotesSurviveRestartExportAndAnotherDeviceWithoutMasteryCredit() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-restart")
        try {
            val library = catalog(dir.resolve("library")); var revision = 0L
            withReader(dir.resolve("db"), library) { s ->
                val page = s.reader.page("orchard", 32).body!!
                assertTrue(s.reader.saveProgress("orchard", page.str("version"), 32, 0, "progress-one").ok)
                val body = noteBody(page)
                val save = s.reader.saveNote("orchard", body)
                assertTrue(save.ok); revision = save.body!!.num("revision")
                assertTrue(s.reader.saveNote("orchard", body).body!!.flag("replayed"))
                assertTrue(s.reader.saveNote("orchard", JsonObject(body.entries.reversed().associate { it.toPair() })).body!!.flag("replayed"))
                assertEquals(2, s.store.events("C1").size)
                assertTrue(s.store.snapshot("C1").projection.mastery.isEmpty())
            }
            withReader(dir.resolve("db"), library) { s ->
                val state = LedgerJson.parseToJsonElement(s.request("C1/reader/orchard/state", device = "desktop").second).jsonObject
                assertEquals(32, state.getValue("progress").jsonObject.num("pdfPage"))
                val note = state.getValue("notes").jsonArray.single().jsonObject
                assertEquals(revision, note.num("revision")); assertFalse(note.flag("stale"))
                val md = s.reader.exportMarkdown("orchard")!!
                assertContains(md, "印刷 p.1"); assertContains(md, "> Synthetic pears")
                assertContains(md, "**Personal** observation")
                val retry = s.reader.saveNote("orchard", noteBody(s.reader.page("orchard", 32).body!!))
                assertTrue(retry.body!!.flag("replayed")); assertEquals(revision, retry.body!!.num("revision"))
                assertEquals(2, s.store.events("C1").size)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun simultaneousDevicesConflictInsteadOfLosingNotesAndDeletesAreDurable() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-cas")
        try {
            val library = catalog(dir.resolve("library"))
            withReader(dir.resolve("db"), library) { s ->
                val page = s.reader.page("orchard", 32).body!!
                val results = coroutineScope {
                    listOf("phone", "desktop").map { device -> async {
                        s.request("C1/reader/orchard/notes", noteBody(page, mutation = "save-$device", note = device).toString(), device)
                    } }.awaitAll()
                }
                assertEquals(listOf(200, 409), results.map { it.first }.sorted())
                val revision = s.reader.state("orchard").body!!.getValue("notes").jsonArray.single().jsonObject.num("revision")
                assertEquals(409, s.reader.saveNote("orchard", noteBody(page, "stale", note = "stale body")).status)
                val edited = s.reader.saveNote("orchard", noteBody(page, "edit", revision, "revised"))
                assertTrue(edited.ok)
                val deleted = JsonObject(noteBody(page, "delete", edited.body!!.num("revision"), "revised") + ("deleted" to JsonPrimitive(true)))
                assertTrue(s.reader.saveNote("orchard", deleted).ok)
                assertTrue(s.reader.state("orchard").body!!.getValue("notes").jsonArray.isEmpty())
                assertFalse(s.reader.exportMarkdown("orchard")!!.contains("revised"))
                assertEquals(409, s.reader.saveNote("orchard", JsonObject(deleted + ("note" to JsonPrimitive("different")))).status)
            }
            withReader(dir.resolve("db"), library) { s -> assertTrue(s.reader.state("orchard").body!!.getValue("notes").jsonArray.isEmpty()) }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun forgedOrStaleAnchorsAndOversizedBodiesNeverAppendAnEvent() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-anchor")
        try {
            withReader(dir.resolve("db"), catalog(dir.resolve("library"))) { s ->
                val body = noteBody(s.reader.page("orchard", 32).body!!)
                for ((key, value, status) in listOf(Triple("quote", JsonPrimitive("fabricated"), 400),
                    Triple("start", JsonPrimitive(-1), 400), Triple("end", JsonPrimitive(999999), 400),
                    Triple("pdfPage", JsonPrimitive(35), 400), Triple("textSha256", JsonPrimitive("wrong"), 409),
                    Triple("version", JsonPrimitive("outdated"), 409))) {
                    assertEquals(status, s.reader.saveNote("orchard", JsonObject(body + (key to value))).status, key)
                }
                assertEquals(413, s.request("C1/reader/orchard/notes", "x".repeat(32769)).first)
                assertEquals(400, s.request("C1/reader/orchard/notes", "[]").first)
                assertTrue(s.store.events("C1").isEmpty())
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun replacingPdfCannotServeOldCachedPageOrSilentlyRebindSavedNotes() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-version")
        try {
            val root = dir.resolve("library"); val first = catalog(root)
            withReader(dir.resolve("db"), first) { s ->
                val page = s.reader.page("orchard", 32).body!!
                assertTrue(s.reader.saveNote("orchard", noteBody(page)).ok)
                Files.write(root.resolve("synthetic.pdf"), inventedPdf(label = "Synthetic apples are new"))
                assertEquals(409, s.reader.page("orchard", 32).status)
            }
            val replacement = catalog(root, inventedPdf(label = "Synthetic apples are new"))
            withReader(dir.resolve("db"), replacement) { s ->
                val state = s.reader.state("orchard").body!!
                assertTrue(state.getValue("notes").jsonArray.single().jsonObject.flag("stale"))
                assertContains(s.reader.exportMarkdown("orchard")!!, "旧资料版本")
                assertContains(s.reader.page("orchard", 32).body!!.str("text"), "Synthetic apples are new")
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun catalogCannotEscapeLibraryOrResolveAnAmbiguousTextbook() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-allowlist")
        try {
            val root = dir.resolve("library"); val first = catalog(root)
            val entry = first.document("C1", "orchard")
            val second = json("id" to "second", "course" to "C1", "title" to "Second synthetic textbook", "file" to "synthetic.pdf",
                "sha256" to entry.version, "pages" to 34, "printedPageOffset" to 31, "kind" to "textbook")
            assertFailsWith<ReaderFailure> { catalog(root, extra = listOf(second)).resolve("C1", "教材 p.1") }
            val outside = dir.resolve("outside.pdf"); Files.write(outside, inventedPdf())
            Files.delete(root.resolve("synthetic.pdf")); Files.createSymbolicLink(root.resolve("synthetic.pdf"), outside)
            assertFailsWith<IllegalArgumentException> { ReaderLibrary(root) }
            assertTrue(ReaderLibrary(null).list("C1").isEmpty())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun standaloneRoutesStillAuthenticateAndMutationScopeIncludesCourseAndOperation() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("reader-scope")
        try {
            val root = dir.resolve("library")
            val version = ReaderLibrary.digest(inventedPdf())
            val otherCourse = json("id" to "orchard", "course" to "C2", "title" to "Other synthetic course", "file" to "synthetic.pdf",
                "sha256" to version, "pages" to 34, "printedPageOffset" to 31, "kind" to "textbook")
            withReader(dir.resolve("db"), catalog(root, extra = listOf(otherCourse)), withLedger = false) { s ->
                assertEquals(401, s.request("C1/reader/orchard/pages/32", device = null).first)
                val progress = json("version" to version, "pdfPage" to 32, "baseRevision" to 0, "mutationId" to "same-id")
                assertEquals(200, s.request("C1/reader/orchard/progress", progress.toString()).first)
                assertEquals(200, s.request("C2/reader/orchard/progress", progress.toString()).first)
                // Operation identity is part of the retry hash even for the same request body.
                val page = s.reader.page("orchard", 32).body!!
                val note = noteBody(page, mutation = "op-id")
                assertTrue(s.reader.saveNote("orchard", note).ok)
                assertEquals(409, s.request("C1/reader/orchard/progress", note.toString()).first)
                assertEquals(2, s.store.events("C1").size); assertEquals(1, s.store.events("C2").size)
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
