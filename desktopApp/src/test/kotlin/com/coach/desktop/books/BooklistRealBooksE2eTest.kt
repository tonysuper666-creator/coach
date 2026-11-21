package com.coach.desktop.books

import com.coach.server.BookTokenLedger
import com.coach.server.BooklistSemanticAligner
import com.coach.server.CodeBuddyDeepSeekTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local 3-real-book end-to-end verification harness for TON-1794 (2.0-W2-D).
 * Gated by `-Dcoach.w2d.realBooksDir=<dir>` so standard CI and repo tests stay 100% self-contained
 * and zero copyrighted book titles/excerpts ever enter Git.
 *
 * Exercises:
 * 1. Importing 3 real same-domain books into a single BOOKLIST collection.
 * 2. Per-chapter concept extraction (6–10 core concepts/chapter) + UTF-16 quote_anchor verification.
 * 3. Stage 1 SQLite FTS5 candidate pre-screening + Stage 2 live DeepSeek (`deepseek-v4.1-flash`) alignment.
 * 4. Writing a 20-link random-sampled audit artifact to `-Dcoach.w2d.auditOut=<path>` (local only).
 * 5. TOC skip -> 0 unread triggers; READ chapter -> <= 2 cards; theme-level mute -> 0 leakage & clean restore.
 */
class BooklistRealBooksE2eTest {

    @Test fun threeRealBooksEndToEndWithLiveDeepSeek() = runBlocking {
        val dirProp = (System.getProperty("coach.w2d.realBooksDir") ?: System.getenv("COACH_W2D_REAL_BOOKS_DIR"))?.trim().orEmpty()
        assumeTrue("Set COACH_W2D_REAL_BOOKS_DIR or -Dcoach.w2d.realBooksDir to run real-books E2E", dirProp.isNotBlank())
        val dir = Path.of(dirProp)
        assumeTrue("Directory does not exist: $dir", Files.isDirectory(dir))

        val explicitFiles = (System.getProperty("coach.w2d.realBookFiles") ?: System.getenv("COACH_W2D_REAL_BOOK_FILES"))?.trim().orEmpty()
            .split(',').map(String::trim).filter(String::isNotEmpty)
            .map { dir.resolve(it) }
        val files = if (explicitFiles.size >= 3) {
            explicitFiles.take(3)
        } else {
            Files.list(dir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    Files.isRegularFile(p) &&
                        name.endsWith(".pdf", ignoreCase = true) &&
                        !name.contains("批注版")
                }.sorted().toList().take(3)
            }
        }
        assertTrue(files.size >= 3, "Expected at least 3 real books in $dir, found ${files.size}")

        val root = Files.createTempDirectory("coach-w2d-real3-")
        try {
            val library = TextbookLibrary(root.resolve("library"), streamPdfExtras = true, lazyPdfExtras = true)
            val books = files.map { f ->
                val staged = library.stageBookImportTier0(f)
                library.indexBookTier1(staged.book.id)
            }
            val colId = "booklist-real-3"
            library.upsertCollection(MaterialCollectionRecord(colId, CollectionKind.BOOKLIST, "真实三书单实测"))
            books.forEachIndexed { idx, b ->
                library.attachMaterial(CollectionMaterialRecord(colId, b.id, role = "LIST_BOOK", ordinal = idx))
            }

            val useLiveDeepSeek = (System.getProperty("coach.w2d.liveDeepSeek") ?: System.getenv("COACH_W2D_LIVE_DEEPSEEK") ?: "true").toBoolean()
            val ledger = BookTokenLedger(root.resolve("ledger"))
            val aligner = BooklistSemanticAligner(CodeBuddyDeepSeekTransport(timeoutSeconds = 60L), ledger)
            val judge: ConceptSemanticJudge = if (useLiveDeepSeek) {
                ConceptSemanticJudge { title, candidates ->
                    val req = RemoteBooklistSemanticJudge.buildWireRequest(title, candidates)
                    val resp = aligner.align(req)
                    RemoteBooklistSemanticJudge.parseWireResponse(resp)
                }
            } else {
                HeuristicConceptSemanticJudge
            }

            val report = BooklistAlignmentEngine.alignBooklist(library, colId, judge)
            assertEquals(3, report.booksProcessed)
            assertTrue(report.extractedMentions >= 18, "Expected >=18 mentions across 3 real books, got ${report.extractedMentions}")
            assertTrue(report.ftsCandidatePairs >= 10, "Expected >=10 FTS5 candidate pairs, got ${report.ftsCandidatePairs}")
            assertTrue(report.alignedLinks >= 1, "Expected aligned cross-book links, got ${report.alignedLinks}")

            val booksById = books.associateBy { it.id }
            val titles = books.associate { it.id to it.title }
            val chTitles = books.associate { b -> b.id to BookChapterResolver.chapters(b).associate { it.tocNodeId to it.title } }
            val snapshot = library.booklistConcepts(colId, titles, chTitles)

            // Verify 100% of stored mentions pass exact UTF-16 quote_anchor verification
            var verifiedCount = 0
            for (cv in snapshot.active) {
                for (mv in cv.mentions) {
                    val pageText = booksById[mv.mention.bookId]!!.page(mv.mention.page)!!.text
                    val slice = pageText.substring(mv.mention.offsetUtf16, mv.mention.offsetUtf16 + mv.mention.quoteAnchor.length)
                    assertEquals(mv.mention.quoteAnchor, slice)
                    verifiedCount++
                }
            }

            // Build deterministic 20-link sample for local manual blind audit (never committed to Git!)
            val mentionsById = snapshot.active.flatMap { it.mentions }.associateBy { it.mention.mentionId }
            val allLinks = snapshot.active.flatMap { cv -> cv.links.map { cv.entity to it } }
            val sampled = allLinks.shuffled(java.util.Random(20261121L)).take(20)

            // TOC skip / unread gate check on real books:
            // Mark NO chapters read on book[0], mark last chapter only -> reading book[1] ch0 gets 0 cards from book[0] ch0.
            val b0 = books[0]
            val b1 = books[1]
            val b0Chapters = BookChapterResolver.chapters(b0)
            val b1Chapters = BookChapterResolver.chapters(b1)
            val b0First = b0Chapters.first()
            val b1First = b1Chapters.first()
            if (b0Chapters.size >= 2) {
                val b0Last = b0Chapters.last()
                library.setChapterManualReadState(b0.id, b0Last.tocNodeId, b0Last.pagesTotal, read = true)
            }
            val unreadTriggerCount = library.companionCrossBookCards(b1.id, b1First.tocNodeId, b1First.firstPage..b1First.lastPage, colId)
                .count { it.otherBookId == b0.id && it.otherChapterId == b0First.tocNodeId }
            assertEquals(0, unreadTriggerCount, "TOC-skipped unread chapter must trigger 0 reminders")

            // Mark all chapters in b0 and b2 as READ, check <= 2 cards cap on b1 and 0 leakage on theme mute + restore
            for (ch in b0Chapters) library.setChapterManualReadState(b0.id, ch.tocNodeId, ch.pagesTotal, read = true)
            for (ch in BookChapterResolver.chapters(books[2])) {
                library.setChapterManualReadState(books[2].id, ch.tocNodeId, ch.pagesTotal, read = true)
            }
            val b1CardsPerChapter = b1Chapters.map { ch ->
                ch to library.companionCrossBookCards(b1.id, ch.tocNodeId, ch.firstPage..ch.lastPage, colId)
            }
            assertTrue(b1CardsPerChapter.all { it.second.size <= 2 }, "Every section must cap at <= 2 cards")

            var muteLeakage = 0
            var restoredOk = false
            val targetCard = b1CardsPerChapter.firstNotNullOfOrNull { it.second.firstOrNull() }
            if (targetCard != null) {
                assertTrue(library.setConceptMuted(targetCard.conceptId, true))
                for (b in books) {
                    for (ch in BookChapterResolver.chapters(b)) {
                        val leaked = library.companionCrossBookCards(b.id, ch.tocNodeId, ch.firstPage..ch.lastPage, colId)
                            .count { it.conceptId == targetCard.conceptId }
                        muteLeakage += leaked
                    }
                }
                assertEquals(0, muteLeakage, "Muted concept must have 0 leakage across entire booklist")
                assertTrue(library.setConceptMuted(targetCard.conceptId, false))
                val afterRestore = library.booklistConcepts(colId, titles, chTitles)
                restoredOk = afterRestore.muted.none { it.entity.conceptId == targetCard.conceptId } &&
                    afterRestore.active.any { it.entity.conceptId == targetCard.conceptId }
                assertTrue(restoredOk)
            }

            val auditOut = (System.getProperty("coach.w2d.auditOut") ?: System.getenv("COACH_W2D_AUDIT_OUT"))?.trim().orEmpty()
            if (auditOut.isNotBlank()) {
                val outPath = Path.of(auditOut)
                Files.createDirectories(outPath.parent)
                val auditJson = buildJsonObject {
                    put("booksProcessed", report.booksProcessed)
                    put("chaptersProcessed", report.chaptersProcessed)
                    put("extractedMentions", report.extractedMentions)
                    put("verifiedMentions", verifiedCount)
                    put("rejectedAnchors", report.rejectedAnchors)
                    put("ftsCandidatePairs", report.ftsCandidatePairs)
                    put("alignedConcepts", report.alignedConcepts)
                    put("alignedLinks", report.alignedLinks)
                    put("sampledCount", sampled.size)
                    put("tocSkipUnreadTriggers", unreadTriggerCount)
                    put("maxCardsInAnySection", b1CardsPerChapter.maxOfOrNull { it.second.size } ?: 0)
                    put("muteLeakage", muteLeakage)
                    put("restoredOk", restoredOk)
                    put("liveDeepSeek", useLiveDeepSeek)
                    put("sampledLinks", buildJsonArray {
                        sampled.forEachIndexed { idx, (entity, link) ->
                            val fm = mentionsById[link.fromMentionId]
                            val tm = mentionsById[link.toMentionId]
                            add(buildJsonObject {
                                put("index", idx + 1)
                                put("conceptId", entity.conceptId)
                                put("canonicalName", entity.canonicalName)
                                put("relationType", link.relationType.name)
                                put("confidence", link.confidence)
                                put("diffSummary", link.diffSummary)
                                put("fromBook", fm?.bookTitle.orEmpty())
                                put("fromPage", fm?.mention?.page ?: 0)
                                put("fromQuote", fm?.mention?.quoteAnchor.orEmpty())
                                put("toBook", tm?.bookTitle.orEmpty())
                                put("toPage", tm?.mention?.page ?: 0)
                                put("toQuote", tm?.mention?.quoteAnchor.orEmpty())
                            })
                        }
                    })
                }
                Files.writeString(outPath, auditJson.toString())
            }

            println(
                "W2D_REAL3_E2E books=${report.booksProcessed} chapters=${report.chaptersProcessed} " +
                    "mentions=${report.extractedMentions} verified=$verifiedCount ftsPairs=${report.ftsCandidatePairs} " +
                    "concepts=${report.alignedConcepts} links=${report.alignedLinks} sampled=${sampled.size} " +
                    "tocSkipTriggers=$unreadTriggerCount muteLeakage=$muteLeakage restored=$restoredOk liveDeepSeek=$useLiveDeepSeek",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
