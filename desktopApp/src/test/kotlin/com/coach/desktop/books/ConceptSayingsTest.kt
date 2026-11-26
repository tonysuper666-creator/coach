package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 同一概念并排：只留下真的同一概念，说法留在本机，对不上的原文不高亮。 */
class ConceptSayingsTest {
    @Test fun parseDropsHomonymUnknownBooksAndCopiedQuotes() {
        val candidates = listOf(candidate("book-a", "m-a", "alpha theory stays on the page", anchor = true), candidate("book-b", "m-b", "beta evidence stays on the page"))
        val homonym = ConceptSayingLogic.parse(
            buildJsonObject {
                put("books", buildJsonArray {
                    add(decision("book-a", true, "甲书用信息差解释保费上升。", "SAME"))
                    add(decision("book-b", false, "", "CONFLICT"))
                    add(decision("book-z", true, "这本不在候选里。", "SAME"))
                })
            },
            candidates,
        )
        assertNotNull(homonym)
        assertEquals(listOf("book-a", "book-b"), homonym.map { it.bookId })
        assertTrue(homonym.first { it.bookId == "book-a" }.keep)
        assertFalse(homonym.first { it.bookId == "book-b" }.keep)

        val copied = ConceptSayingLogic.parse(
            buildJsonObject {
                put("books", buildJsonArray {
                    add(decision("book-b", true, "beta evidence stays on the page", "SAME"))
                })
            },
            candidates,
        )
        assertNotNull(copied)
        assertFalse(copied.single().keep)

        assertNull(ConceptSayingLogic.parse(buildJsonObject { put("note", "missing") }, candidates))
        assertNull(
            ConceptSayingLogic.parse(
                buildJsonObject { put("books", buildJsonArray { add(decision("book-z", true, "另一件事。", "ANGLE")) }) },
                candidates,
            ),
        )
        assertEquals(emptyList(), ConceptSayingLogic.parse(buildJsonObject { put("books", buildJsonArray {}) }, candidates))
    }

    @Test fun hashIgnoresOrderAndTitleWhileMatchAndJumpStayExact() {
        val first = candidate("book-a", "m-a", "alpha theory stays on the page", title = "甲", anchor = true)
        val second = candidate("book-b", "m-b", "beta evidence stays on the page", title = "乙")
        val renamed = second.copy(bookTitle = "乙书新名字")
        assertEquals(
            ConceptSayingLogic.inputHash("concept-1", listOf(first, second)),
            ConceptSayingLogic.inputHash("concept-1", listOf(renamed, first)),
        )
        val names = listOf(ConceptNameRef("concept-1", "Adverse Selection", listOf("hidden information")))
        assertEquals("concept-1", ConceptSayingLogic.matchConcept("  adverse   selection ", names))
        assertEquals("concept-1", ConceptSayingLogic.matchConcept("Hidden Information", names))
        assertNull(ConceptSayingLogic.matchConcept("a", names))
        assertNull(ConceptSayingLogic.matchConcept("adverse selection appears inside a much longer paragraph", names))

        val page = "before alpha theory stays on the page after"
        val hit = ConceptSayingLogic.passageIfExact("book-a", 3, page, 0, "alpha theory stays on the page")
        assertNotNull(hit)
        assertEquals(page.indexOf("alpha theory stays on the page"), hit.start)
        assertEquals("alpha theory stays on the page", page.substring(hit.start, hit.end))
        assertNull(ConceptSayingLogic.passageIfExact("book-a", 3, page, 0, "a sentence that is not on this page"))
    }

    @Test fun twoBooksPreviewThenCacheAndARejectedBookStaysRejected() {
        val root = Files.createTempDirectory("coach-sayings-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val bookA = library.importBook(write(root, "book-a.md", chapter("Alpha theory says private signals raise the premium.")))
            val bookB = library.importBook(write(root, "book-b.md", chapter("Beta evidence says informed buyers push the premium down.")))
            val conceptId = "concept-premium"
            val quoteA = "Alpha theory says private signals raise the premium."
            val quoteB = "Beta evidence says informed buyers push the premium down."
            val chapterA = BookChapterResolver.chapters(bookA).first()
            val chapterB = BookChapterResolver.chapters(bookB).first()
            library.upsertCollection(MaterialCollectionRecord("list-1", CollectionKind.BOOKLIST, "synthetic list"))
            library.saveAlignedConcepts(
                "list-1",
                listOf(ConceptEntityRecord(conceptId, "list-1", "premium signal", aliases = listOf("private signal"))),
                listOf(
                    mention(bookA, conceptId, "m-a", chapterA.tocNodeId, quoteA, "甲书认为私有信号抬高保费。"),
                    mention(bookB, conceptId, "m-b", chapterB.tocNodeId, quoteB, "乙书认为知情买方压低保费。"),
                ),
                listOf(link("list-1", conceptId, "m-a", "m-b", ConceptRelationType.OPPOSING_VIEW, 0.92)),
            )

            library.setChapterManualReadState(bookA.id, chapterA.tocNodeId, chapterA.pagesTotal, true)
            val onlyAnchor = library.conceptSayings(conceptId, bookA.id)
            assertNotNull(onlyAnchor)
            assertTrue(onlyAnchor.onlyThisBook)
            assertFalse(onlyAnchor.pending)
            assertEquals(ConceptSayingsBoard.ONLY_THIS_BOOK, "库里只有这一本讲到")
            assertEquals(1, onlyAnchor.candidates.size)

            library.setChapterManualReadState(bookB.id, chapterB.tocNodeId, chapterB.pagesTotal, true)
            val preview = library.conceptSayings(conceptId, bookA.id)
            assertNotNull(preview)
            assertTrue(preview.pending)
            assertEquals(2, preview.columns.size)
            assertEquals(bookA.id, preview.columns.first().bookId)
            assertEquals("有冲突", preview.columns.first { it.bookId == bookB.id }.stance.label)
            assertEquals(quoteB, preview.columns.first { it.bookId == bookB.id }.quoteAnchor)

            val parsed = listOf(
                ParsedBookSaying(bookA.id, true, "甲书认为私有信号会抬高保费。", SayingStance.SAME),
                ParsedBookSaying(bookB.id, true, "乙书认为买方知情后保费下降。", SayingStance.SAME),
            )
            library.saveConceptSayings(preview, parsed)
            val cached = library.conceptSayings(conceptId, bookA.id)
            assertNotNull(cached)
            assertFalse(cached.pending)
            assertEquals(preview.inputHash, cached.inputHash)
            assertEquals("说法一致", cached.columns.first { it.bookId == bookB.id }.stance.label)
            assertEquals("乙书认为买方知情后保费下降。", cached.columns.first { it.bookId == bookB.id }.statement)

            library.saveConceptSayings(
                cached,
                listOf(
                    ParsedBookSaying(bookA.id, true, "甲书认为私有信号会抬高保费。", SayingStance.SAME),
                    ParsedBookSaying(bookB.id, false, "", SayingStance.CONFLICT),
                ),
            )
            val dropped = library.conceptSayings(conceptId, bookA.id)
            assertNotNull(dropped)
            assertFalse(dropped.pending)
            assertTrue(dropped.onlyThisBook)
            assertEquals(listOf(bookA.id), dropped.columns.map { it.bookId })
            assertEquals(2, sayingRows(root.resolve("library"), conceptId))

            library.setConceptMuted(conceptId, true)
            assertNull(library.conceptSayings(conceptId, bookA.id))
            assertTrue(library.conceptNamesForBook(bookA.id).none { it.conceptId == conceptId })

            library.setConceptMuted(conceptId, false)
            library.saveAlignedConcepts(
                "list-1",
                listOf(ConceptEntityRecord("concept-other", "list-1", "other topic")),
                emptyList(),
                emptyList(),
            )
            assertEquals(0, sayingRows(root.resolve("library"), conceptId))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun lowConfidenceLinkIsNotShownBesideTheAnchor() {
        val root = Files.createTempDirectory("coach-sayings-low-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val bookA = library.importBook(write(root, "book-a.md", chapter("Alpha theory says private signals raise the premium.")))
            val bookB = library.importBook(write(root, "book-b.md", chapter("Beta evidence says informed buyers push the premium down.")))
            val conceptId = "concept-low"
            val chapterA = BookChapterResolver.chapters(bookA).first()
            val chapterB = BookChapterResolver.chapters(bookB).first()
            library.setChapterManualReadState(bookA.id, chapterA.tocNodeId, chapterA.pagesTotal, true)
            library.setChapterManualReadState(bookB.id, chapterB.tocNodeId, chapterB.pagesTotal, true)
            library.upsertCollection(MaterialCollectionRecord("list-low", CollectionKind.BOOKLIST, "synthetic low"))
            library.saveAlignedConcepts(
                "list-low",
                listOf(ConceptEntityRecord(conceptId, "list-low", "premium signal")),
                listOf(
                    mention(bookA, conceptId, "m-a", chapterA.tocNodeId, "Alpha theory says private signals raise the premium.", "甲书抬高保费。"),
                    mention(bookB, conceptId, "m-b", chapterB.tocNodeId, "Beta evidence says informed buyers push the premium down.", "乙书压低保费。"),
                ),
                listOf(link("list-low", conceptId, "m-a", "m-b", ConceptRelationType.CONSISTENT_DEEPENING, 0.5)),
            )
            val board = library.conceptSayings(conceptId, bookA.id)
            assertNotNull(board)
            assertTrue(board.onlyThisBook)
            assertFalse(board.pending)
            assertEquals(1, board.candidates.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun settleLeavesThePreviewUncachedWhenTheBatchFailsOrStillWaits() = runBlocking {
        val board = ConceptSayingsBoard(
            conceptId = "concept-1",
            conceptName = "premium signal",
            anchorBookId = "book-a",
            inputHash = "abc",
            columns = emptyList(),
            pending = true,
            candidates = listOf(
                candidate("book-a", "m-a", "alpha theory stays on the page", anchor = true),
                candidate("book-b", "m-b", "beta evidence stays on the page"),
            ),
        )
        var saves = 0
        val failed = settleConceptSayings(board, fetch = { null }, save = { _, _ -> saves++ }, reload = { board.copy(pending = false) }, delayMillis = {})
        assertEquals(0, saves)
        assertTrue(failed.pending)

        var calls = 0
        val settled = settleConceptSayings(
            board,
            fetch = {
                calls++
                if (calls == 1) buildJsonObject { put("pending", true); put("retryAfterMs", 1_000) }
                else buildJsonObject {
                    put("books", buildJsonArray {
                        add(decision("book-a", true, "甲书用信息差解释保费。", "SAME"))
                        add(decision("book-b", true, "乙书看到保费下降。", "ANGLE"))
                    })
                }
            },
            save = { _, parsed ->
                saves++
                assertEquals(2, parsed.size)
                assertTrue(parsed.all { it.keep })
            },
            reload = { board.copy(pending = false) },
            delayMillis = { waited -> assertTrue(waited in 1_000L..20_000L) },
        )
        assertEquals(2, calls)
        assertEquals(1, saves)
        assertFalse(settled.pending)
    }

    private fun candidate(
        bookId: String,
        mentionId: String,
        quote: String,
        title: String = bookId,
        anchor: Boolean = false,
    ) = SayingCandidate(
        bookId = bookId,
        bookTitle = title,
        mentionId = mentionId,
        page = 1,
        offsetUtf16 = 0,
        quoteAnchor = quote,
        claimSummary = "local claim for $bookId",
        stance = SayingStance.ANGLE,
        anchor = anchor,
    )

    private fun decision(bookId: String, keep: Boolean, statement: String, stance: String): JsonObject = buildJsonObject {
        put("bookId", bookId)
        put("keep", keep)
        put("statement", statement)
        put("stance", stance)
    }

    private fun write(dir: Path, name: String, text: String): Path =
        dir.resolve(name).also { Files.writeString(it, text) }

    private fun chapter(sentence: String) = """
        # Chapter 1 Synthetic

        $sentence
    """.trimIndent()

    private fun mention(
        book: Textbook,
        conceptId: String,
        mentionId: String,
        tocNodeId: String,
        quote: String,
        claim: String,
    ): ConceptMentionRecord {
        val page = book.pages.first { quote in it.text }
        return ConceptMentionRecord(
            mentionId = mentionId,
            conceptId = conceptId,
            bookId = book.id,
            tocNodeId = tocNodeId,
            page = page.number,
            offsetUtf16 = page.text.indexOf(quote),
            quoteAnchor = quote,
            claimSummary = claim,
        )
    }

    private fun link(
        collectionId: String,
        conceptId: String,
        fromId: String,
        toId: String,
        relation: ConceptRelationType,
        confidence: Double,
    ) = ConceptLinkRecord(
        linkId = "link-$fromId-$toId",
        collectionId = collectionId,
        conceptId = conceptId,
        fromMentionId = fromId,
        toMentionId = toId,
        relationType = relation,
        diffSummary = "synthetic difference",
        confidence = confidence,
    )

    private fun sayingRows(dir: Path, conceptId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("library.sqlite")}").use { db ->
            db.prepareStatement("SELECT COUNT(*) FROM concept_sayings WHERE concept_id=?").use { s ->
                s.setString(1, conceptId)
                s.executeQuery().use { r ->
                    check(r.next())
                    r.getInt(1)
                }
            }
        }
}
