package com.coach.client

import com.coach.domain.*
import com.coach.domain.reader.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ClaimSourceReaderTest {
    private class FakeLibrary : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val authorization = CopyOnWriteArrayList<String>()
        val reads get() = requests.filter { "/pages/" in it }
        @Volatile var catalogStatus = 200
        @Volatile var pageStatus = 200
        @Volatile var catalogOverride: JsonArray? = null
        @Volatile var rewrite: (JsonObject) -> JsonObject = { it }
        private fun doc(id: String, kind: String, pages: Int) =
            json("id" to id, "title" to "Invented " + id, "version" to "edition-" + id, "pages" to pages, "kind" to kind)
        private val docs = mapOf("A" to listOf(doc("book-A", "textbook", 12), doc("slides-A", "slides", 8)),
            "B" to listOf(doc("book-B", "reading", 5), doc("book-A", "textbook", 12)))
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/courses/") { ex ->
                val path = ex.requestURI.path
                requests += ex.requestMethod + " " + path
                authorization += ex.requestHeaders.getFirst("Authorization").orEmpty()
                val course = path.substringAfter("/v1/courses/").substringBefore("/")
                val relative = path.substringAfter("/reader", "").trim('/')
                var status = if (relative.isEmpty()) catalogStatus else pageStatus
                val body = if (relative.isEmpty()) json("documents" to (catalogOverride ?: JsonArray(docs[course].orEmpty())))
                else {
                    val id = relative.substringBefore("/")
                    val page = relative.substringAfterLast("/").toIntOrNull()
                    val doc = docs[course].orEmpty().singleOrNull { it["id"] == JsonPrimitive(id) }
                    if (doc == null || page == null) { status = 404; json("error" to "Unavailable") }
                    else rewrite(json("documentId" to id, "version" to doc.getValue("version"),
                        "pdfPage" to page, "text" to ("Invented page " + course + "/" + id + "/" + page)))
                }
                val bytes = body.toString().encodeToByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val a = CourseHttp("http://127.0.0.1:" + server.address.port, "synthetic-device", "synthetic-token", "A")
        val b = CourseHttp("http://127.0.0.1:" + server.address.port, "synthetic-device", "synthetic-token", "B")
        override fun close() { a.close(); b.close(); server.stop(0) }
    }

    private val definition = ClaimSourceAnchor("book-A", 4, KnowledgeSourceKind.TEXTBOOK, claimRef = "definition")
    private val comparison = ClaimSourceAnchor("slides-A", 6, KnowledgeSourceKind.SLIDES, 8, "comparison", "Compare the invented cases")
    private fun row(vararg anchors: ClaimSourceAnchor, course: String = "A", point: JsonObject? = null): KpRow {
        val payload = point ?: writeKnowledgeSources(json("id" to "K", "name" to "Invented topic"), anchors.toList())
        val events = listOf(Event(1, "outline", course, "outline_revised", recordedAt = 1, occurredAt = 1,
            deviceId = "synthetic-device", actor = "SYSTEM", payload = json("knowledgePoints" to JsonArray(listOf(payload)))))
        return CourseView(events, project(events)).kp("K")!!
    }
    private fun failure(result: ClaimSourceRead, reason: ClaimSourceFailure) =
        assertEquals(reason, assertIs<ClaimSourceRead.Unconfirmed>(result).reason)

    @Test fun courseViewClaimSelectionConsumesThePairedReaderForTwoCourses() = runBlocking {
        FakeLibrary().use { fake ->
            val kp = row(definition, comparison)
            val first = assertIs<ClaimSourceRead.Opened>(fake.a.reader.readClaimSource(kp, "definition"))
            assertEquals("book-A", first.source.docId)
            assertEquals(4, first.pdfPage)
            assertEquals("Invented page A/book-A/4", first.text)
            val pages = assertIs<ClaimSourceRead.ChoosePage>(fake.a.reader.readClaimSource(kp, "comparison"))
            assertEquals(6..8, pages.source.pdfPage..pages.source.pdfPageEnd)
            assertEquals(1, fake.reads.size, "a range must not issue a request for its first page")
            val comparisonPage = assertIs<ClaimSourceRead.Opened>(
                fake.a.reader.readClaimSource(kp, supportedSubpoint = comparison.supportedSubpoint, pdfPage = 8))
            assertEquals("slides-A", comparisonPage.source.docId)
            assertEquals(8, comparisonPage.pdfPage)
            val other = ClaimSourceAnchor("book-B", 3, KnowledgeSourceKind.READING, claimRef = "other-case")
            val otherPage = assertIs<ClaimSourceRead.Opened>(fake.b.reader.readClaimSource(row(other, course = "B"), "other-case"))
            assertEquals("Invented page B/book-B/3", otherPage.text)
            assertEquals(listOf("GET /v1/courses/A/reader/book-A/pages/4", "GET /v1/courses/A/reader/slides-A/pages/8",
                "GET /v1/courses/B/reader/book-B/pages/3"), fake.reads)
            assertTrue(fake.authorization.all { "synthetic-token" in it })
            assertTrue(fake.requests.all { it.startsWith("GET ") }, "source reading must not mutate progress, mastery or AI state")
        }
    }

    @Test fun multipleDocumentsAndDisjointPagesRequireSelectionInsteadOfTheFirstSource() = runBlocking {
        FakeLibrary().use { fake ->
            val later = definition.copy(pdfPage = 10, pdfPageEnd = 12)
            val slides = comparison.copy(claimRef = "definition")
            val kp = row(later, slides, definition)
            val choices = assertIs<ClaimSourceRead.ChooseSource>(fake.a.reader.readClaimSource(kp, "definition"))
            assertEquals(listOf(later, slides, definition), choices.sources)
            assertTrue(fake.reads.isEmpty())
            assertIs<ClaimSourceRead.ChoosePage>(fake.a.reader.readClaimSource(kp, "definition", source = slides))
            val actual = assertIs<ClaimSourceRead.Opened>(fake.a.reader.readClaimSource(kp, "definition", source = slides, pdfPage = 7))
            assertEquals(slides, actual.source)
            assertEquals(7, actual.pdfPage)
            failure(fake.a.reader.readClaimSource(kp, "definition", source = definition, pdfPage = 5), ClaimSourceFailure.PAGE_OUTSIDE_SOURCE)
            failure(fake.a.reader.readClaimSource(kp, "definition", source = comparison), ClaimSourceFailure.SOURCE_NOT_SELECTED)
            assertEquals(listOf("GET /v1/courses/A/reader/slides-A/pages/7"), fake.reads)
        }
    }

    @Test fun unregisteredForeignWrongKindAndOutOfBoundsSourcesNeverRequestAPage() = runBlocking {
        FakeLibrary().use { fake ->
            for ((anchor, expected) in listOf(
                definition.copy(docId = "not-registered") to ClaimSourceFailure.DOCUMENT_NOT_REGISTERED,
                definition.copy(docId = "../book-A") to ClaimSourceFailure.DOCUMENT_NOT_REGISTERED,
                definition.copy(docId = "book-B", sourceKind = KnowledgeSourceKind.READING) to ClaimSourceFailure.DOCUMENT_NOT_REGISTERED,
                definition.copy(sourceKind = KnowledgeSourceKind.SLIDES) to ClaimSourceFailure.KIND_MISMATCH,
                definition.copy(pdfPageEnd = 13) to ClaimSourceFailure.PAGE_OUTSIDE_DOCUMENT,
            )) failure(fake.a.reader.readClaimSource(row(anchor), "definition"), expected)
            // A missing second support source cannot disappear while the first is shown as complete.
            val invalidSecond = definition.copy(docId = "unregistered-slides")
            failure(fake.a.reader.readClaimSource(row(definition, invalidSecond), "definition", source = definition),
                ClaimSourceFailure.DOCUMENT_NOT_REGISTERED)
            assertTrue(fake.reads.isEmpty())
        }
    }

    @Test fun missingClaimEvidenceAndLegacyChaptersRemainUnconfirmedWithoutAnyNetworkRead() = runBlocking {
        FakeLibrary().use { fake ->
            failure(fake.a.reader.readClaimSource(row(definition), "unknown"), ClaimSourceFailure.SOURCE_CONTRACT)
            failure(fake.a.reader.readClaimSource(row(definition)), ClaimSourceFailure.SOURCE_CONTRACT)
            val old = json("id" to "K", "name" to "Old topic", "sourceAnchor" to json("docId" to "book-A", "pdfPage" to 1))
            val legacy = row(point = old)
            assertEquals("book-A", legacy.anchorDoc)
            assertEquals(KnowledgeSourceProblem.LEGACY_PAGE_ONLY,
                assertIs<ClaimSourceRead.Unconfirmed>(fake.a.reader.readClaimSource(legacy, "definition")).contractProblem)
            val missing = JsonObject(old + ("sourceAnchors" to JsonArray(listOf(
                json("docId" to "book-A", "pdfPage" to 4, "sourceKind" to "textbook")))))
            failure(fake.a.reader.readClaimSource(row(point = missing), "definition"), ClaimSourceFailure.SOURCE_CONTRACT)
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test fun changedEditionWrongReplyPageAndEmptyTextNeverProduceALocatedSource() = runBlocking {
        FakeLibrary().use { fake ->
            val kp = row(definition)
            fake.pageStatus = 409
            failure(fake.a.reader.readClaimSource(kp, "definition"), ClaimSourceFailure.DOCUMENT_CHANGED)
            fake.pageStatus = 200
            for (bad in listOf(
                "documentId" to JsonPrimitive("another-book"),
                "version" to JsonPrimitive("republished-edition"),
                "pdfPage" to JsonPrimitive(3),
                "pdfPage" to JsonPrimitive("4"),
            )) {
                fake.rewrite = { JsonObject(it + bad) }
                failure(fake.a.reader.readClaimSource(kp, "definition"), ClaimSourceFailure.PAGE_REPLY_MISMATCH)
            }
            fake.rewrite = { JsonObject(it + ("text" to JsonPrimitive(""))) }
            failure(fake.a.reader.readClaimSource(kp, "definition"), ClaimSourceFailure.NO_TEXT)
        }
    }

    @Test fun deniedMalformedOrDuplicateCatalogCannotGrantASource() = runBlocking {
        FakeLibrary().use { fake ->
            val kp = row(definition)
            fake.catalogStatus = 403
            failure(fake.a.reader.readClaimSource(kp, "definition"), ClaimSourceFailure.CATALOG_UNAVAILABLE)
            fake.catalogStatus = 200
            fake.catalogOverride = JsonArray(listOf(json("id" to "book-A")))
            failure(fake.a.reader.readClaimSource(kp, "definition"), ClaimSourceFailure.CATALOG_UNAVAILABLE)
            val doc = json("id" to "book-A", "version" to "v", "pages" to 12, "kind" to "textbook")
            fake.catalogOverride = JsonArray(listOf(doc, doc))
            failure(fake.a.reader.readClaimSource(kp, "definition"), ClaimSourceFailure.DOCUMENT_NOT_REGISTERED)
            assertTrue(fake.reads.isEmpty())
        }
    }

    @Test fun aDocumentIdRegisteredInTwoCoursesCannotMoveAClaimAcrossCourses() = runBlocking {
        FakeLibrary().use { fake ->
            failure(fake.b.reader.readClaimSource(row(definition), "definition"), ClaimSourceFailure.COURSE_MISMATCH)
            assertTrue(fake.requests.isEmpty(), "a mismatched outline must be rejected even when both catalogs register that docId")
            val correct = assertIs<ClaimSourceRead.Opened>(
                fake.b.reader.readClaimSource(row(definition, course = "B"), "definition"))
            assertEquals("Invented page B/book-A/4", correct.text)
        }
    }

    @Test fun sourceAndPageChoicesRemainBoundToTheCatalogEditionTheUserSaw() = runBlocking {
        FakeLibrary().use { fake ->
            val kp = row(definition, comparison.copy(claimRef = "definition"))
            val choice = assertIs<ClaimSourceRead.ChooseSource>(fake.a.reader.readClaimSource(kp, "definition"))
            assertEquals("edition-book-A", choice.versions[definition.docId])
            val range = assertIs<ClaimSourceRead.ChoosePage>(fake.a.reader.readClaimSource(row(comparison), "comparison"))
            fake.catalogOverride = JsonArray(listOf(
                json("id" to "book-A", "title" to "Changed book", "version" to "new-book", "pages" to 12, "kind" to "textbook"),
                json("id" to "slides-A", "title" to "Changed slides", "version" to "new-slides", "pages" to 8, "kind" to "slides")))
            failure(fake.a.reader.readClaimSource(kp, "definition", source = definition,
                expectedVersion = choice.versions[definition.docId]), ClaimSourceFailure.DOCUMENT_CHANGED)
            failure(fake.a.reader.readClaimSource(row(comparison), "comparison", source = comparison, pdfPage = 8,
                expectedVersion = range.version), ClaimSourceFailure.DOCUMENT_CHANGED)
            assertTrue(fake.reads.isEmpty(), "neither a source nor a page choice may transfer to another edition")
        }
    }
}
