package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ImportedBookWireTest {
    @Test fun consentAndOriginalAndSavedUtf16RangeAreRequiredWithoutSendingRestOfPage() = runBlocking {
        val root = Files.createTempDirectory("synthetic-import-receipt-")
        try {
            val source = root.resolve("self-authored.txt")
            Files.writeString(source, "自写🌱前言。\n\nSynthetic selected text.\n\nUNSENT_PRIVATE_PAGE_SUFFIX.")
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(source)
            val page = book.pages.first()
            val text = "Synthetic selected text."
            val start = page.text.indexOf(text)
            val block = SourceBlock("${book.id}:p1:$start", 1, start, start + text.length, false, text)
            ReadingSettings(library.directory).chooseAiExplain(false)
            val wire = ImportedBookWire(library)
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block)) }
            ExplainConsent(library.directory).allow(book.id)
            val snapshot = wire.snapshot(listOf(block))
            assertEquals(page.text.length, snapshot.getValue("pages").jsonArray.single().jsonObject.getValue("length").jsonPrimitive.int)
            assertFalse(snapshot.toString().contains("UNSENT_PRIVATE_PAGE_SUFFIX"))
            assertEquals(snapshot, ImportedBookWire(TextbookLibrary(library.directory)).snapshot(listOf(block)))
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block.copy(start = start - 1))) }
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block.copy(text = "Forged selected text."))) }
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block.copy(page = 2))) }
            Files.writeString(library.directory.resolve("${book.id}.${book.format}"), "Changed synthetic original")
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block)) }
            Unit
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun explainerResetsServerPersonalImportOncePerDigestAndClearsSyncStateOnReimport() = runBlocking {
        val root = Files.createTempDirectory("synthetic-import-sync-").toRealPath()
        val seenRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val source = root.resolve("sync-book.txt")
        Files.writeString(source, "第一章\n\nSynthetic paragraph for reset flow verification.")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source)
        ExplainConsent(library.directory).allow(book.id)
        val page = book.pages.first()
        val text = "Synthetic paragraph for reset flow verification."
        val start = page.text.indexOf(text)
        val block = SourceBlock("${book.id}:p1:$start", 1, start, start + text.length, false, text)

        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seenRequests += "${ex.requestMethod} ${ex.requestURI.path}"
            val body = when {
                ex.requestMethod == "DELETE" && ex.requestURI.path.startsWith("/v1/library/personal-imports/") ->
                    """{"ok":true,"cleared":1}"""
                ex.requestMethod == "POST" && ex.requestURI.path == "/v1/library/explain" ->
                    """{"text":"已核对原文。","sources":[{"docId":"${block.id}","pdfPage":${block.page},"anchor":"S1"}],"elapsedMs":5}"""
                else -> """{"error":"unexpected"}"""
            }.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val settings = com.coach.desktop.DesktopConnectionSettings(home = root.resolve("home"), environment = { null })
            val explainer = object : BookExplainer(settings, library) {
                init {
                    identity = com.coach.desktop.DesktopIdentity(
                        baseUrl,
                        com.coach.client.PairedCredentials("dev-sync", "tok-sync-32-characters-00000000"),
                    )
                    loaded = true
                }
            }

            assertTrue(explainer.explain(block, book.sourceBlocks()) is ExplainResult.Done)
            assertEquals(
                listOf("DELETE /v1/library/personal-imports/${book.id}", "POST /v1/library/explain"),
                seenRequests.toList(),
            )
            val digest = library.localPagesDigest(book.id)
            assertNotNull(digest)
            assertEquals(digest, library.syncedPersonalImportDigest("$baseUrl|dev-sync", book.id))

            // Second call with unchanged pages must not hit DELETE again.
            seenRequests.clear()
            assertTrue(explainer.explain(block, book.sourceBlocks()) is ExplainResult.Done)
            assertEquals(listOf("POST /v1/library/explain"), seenRequests.toList())

            // Re-importing (even identical file) invalidates sync state so the next explain reconciles once more.
            library.importBook(source)
            assertNull(library.syncedPersonalImportDigest("$baseUrl|dev-sync", book.id))
            seenRequests.clear()
            assertTrue(explainer.explain(block, book.sourceBlocks()) is ExplainResult.Done)
            assertEquals(
                listOf("DELETE /v1/library/personal-imports/${book.id}", "POST /v1/library/explain"),
                seenRequests.toList(),
            )
        } finally {
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }
}
