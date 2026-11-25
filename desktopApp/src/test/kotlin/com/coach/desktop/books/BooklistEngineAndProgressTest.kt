package com.coach.desktop.books

import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Unit & integration verification for TON-1794 (2.0-W2-D):
 * - Real chapter read detection (`>=70%` pages dwelled `>=8s`, manual override, TOC jump never marks skipped chapters READ)
 * - Per-chapter concept extraction (6–10 core concepts, generic waste words filtered) + UTF-16 `quote_anchor` hard verification
 * - Two-stage FTS5 pre-screening + semantic alignment (rejects homonyms, preserves muted & shared_note on re-alignment)
 * - Four zero-distraction iron laws (only READ chapters linked, <= 2 cards per section, theme-level mute has 0 leakage)
 */
class BooklistEngineAndProgressTest {

    private fun writeBook(dir: Path, filename: String, content: String): Path =
        dir.resolve(filename).also { Files.writeString(it, content) }

    @Test fun chapterReadProgressRequiresSeventyPercentAtEightSecondsAndProtectsDrafts() {
        val root = Files.createTempDirectory("coach-w2d-progress-")
        try {
            val md = writeBook(
                root,
                "multi-page-book.md",
                buildString {
                    appendLine("# Chapter 1 Asymmetric Information")
                    appendLine()
                    appendLine("Adverse selection occurs when insured households hold private health information.")
                    appendLine("\u000c")
                    appendLine("Risk pooling lowers variance across heterogeneous enrollees when participation is broad.")
                    appendLine("\u000c")
                    appendLine("Actuarial fairness sets premiums equal to expected claim payouts.")
                    appendLine("\u000c")
                    appendLine("Compulsory insurance prevents low-risk consumers from exiting the risk pool.")
                    appendLine("\u000c")
                    appendLine("Separating equilibrium relies on deductible menus to screen hidden risk types.")
                    appendLine()
                    appendLine("# Chapter 2 Moral Hazard")
                    appendLine()
                    appendLine("Ex post moral hazard increases medical utilization when coinsurance drops to zero.")
                    appendLine()
                    appendLine("# Chapter 3 Innovation Incentives")
                    appendLine()
                    appendLine("Patent protection compensates pharmaceutical developers for clinical trial risk.")
                },
            )
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val chapters = BookChapterResolver.chapters(book)
            assertTrue(chapters.size >= 3, "Expected >= 3 chapters, got ${chapters.size}")
            val ch1 = chapters[0]
            val ch2 = chapters[1]
            val ch3 = chapters[2]

            // Persist a practice draft on ch1 first; dwell updates must NEVER wipe practice_draft_json.
            val draft = ChapterPracticeDraft(
                bookId = book.id,
                tocNodeId = ch1.tocNodeId,
                page = ch1.firstPage,
                companionOpen = true,
                activeModule = CompanionModuleTab.PRACTICE.name,
                practiceTextDrafts = mapOf("q1" to "My calculation draft"),
            )
            library.saveChapterPracticeDraft(book.id, ch1.tocNodeId, ch1.pagesTotal, draft)

            // Dwell < 8s does NOT count.
            val tooFast = library.recordPageDwell(book.id, ch1.tocNodeId, ch1.firstPage, 5, dwellMs = 2_500L)
            assertEquals(ChapterReadState.UNREAD, tooFast?.readState ?: ChapterReadState.UNREAD)
            assertEquals(0, tooFast?.pagesDwelled ?: 0)

            // Dwell >= 8s on 3 of 5 pages (60% < 70%) -> PARTIAL.
            library.recordPageDwell(book.id, ch1.tocNodeId, ch1.firstPage, 5, dwellMs = 8_000L)
            library.recordPageDwell(book.id, ch1.tocNodeId, ch1.firstPage + 1, 5, dwellMs = 9_000L)
            val partial = library.recordPageDwell(book.id, ch1.tocNodeId, ch1.firstPage + 2, 5, dwellMs = 8_500L)
            assertEquals(ChapterReadState.PARTIAL, partial?.readState)
            assertEquals(3, partial?.pagesDwelled)

            // Duplicate dwell on the same page still counts as 3/5 (60%) -> PARTIAL.
            val dup = library.recordPageDwell(book.id, ch1.tocNodeId, ch1.firstPage, 5, dwellMs = 10_000L)
            assertEquals(ChapterReadState.PARTIAL, dup?.readState)
            assertEquals(3, dup?.pagesDwelled)

            // 4th distinct page (4/5 = 80% >= 70%) -> READ!
            val read = library.recordPageDwell(book.id, ch1.tocNodeId, ch1.firstPage + 3, 5, dwellMs = 8_000L)
            assertEquals(ChapterReadState.READ, read?.readState)
            assertEquals(4, read?.pagesDwelled)

            // Practice draft is completely intact.
            assertEquals(
                "My calculation draft",
                library.loadChapterPracticeDraft(book.id, ch1.tocNodeId)?.practiceTextDrafts?.get("q1"),
            )

            // Manual override: marking ch3 READ works immediately; marking ch1 UNREAD stays UNREAD even after more dwells.
            val manualCh3 = library.setChapterManualReadState(book.id, ch3.tocNodeId, ch3.pagesTotal, read = true)
            assertEquals(ChapterReadState.READ, manualCh3?.readState)
            assertTrue(manualCh3!!.manualOverride)
            assertEquals(ChapterReadState.UNREAD, library.chapterProgressMap(book.id)[ch2.tocNodeId]?.readState ?: ChapterReadState.UNREAD)

            val manualUnreadCh1 = library.setChapterManualReadState(book.id, ch1.tocNodeId, 5, read = false)
            assertEquals(ChapterReadState.UNREAD, manualUnreadCh1?.readState)
            val afterMoreDwell = library.recordPageDwell(book.id, ch1.tocNodeId, ch1.firstPage + 4, 5, dwellMs = 9_000L)
            assertEquals(ChapterReadState.UNREAD, afterMoreDwell?.readState, "Manual UNREAD override must not be overwritten by dwell")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun twoStageAlignmentEnforcesUtf16VerificationHomonymRejectionReadGateCapAndThemeMute() = runBlocking {
        val root = Files.createTempDirectory("coach-w2d-align-")
        try {
            val mdA = writeBook(
                root,
                "book-a-theory.md",
                """
                # Chapter 1 Asymmetric Information and Screening

                Adverse selection arises in insurance contracts when high-risk individuals conceal private morbidity signals from insurers.
                Actuarial fairness requires pooled premiums to reflect average expected claims across the entire risk pool.
                Rothschild-Stiglitz screening uses deductible contracts to separate low-risk and high-risk consumers in equilibrium.
                Moral hazard increases healthcare utilization once coinsurance drops below marginal cost.
                Compulsory participation stabilizes risk pooling by preventing healthy enrollees from exiting.
                The river bank eroded rapidly after spring flooding altered the channel geometry.

                # Chapter 2 Clinical Trial Pipelines

                Pharmaceutical innovation depends on clinical trials and patent exclusivity to recoup sunk research expenditure.
                Orphan drug incentives target rare diseases with small patient populations and high development costs.
                """.trimIndent(),
            )
            val mdB = writeBook(
                root,
                "book-b-empirical.md",
                """
                # Chapter 1 Empirical Insurance Evidence

                Contrary to simple adverse selection theory, empirical evidence from Medigap finds advantageous selection driven by cognitive ability.
                Actuarial fairness breaks down in employer plans when adverse selection triggers a death spiral among healthy employees.
                Moral hazard estimates from the Oregon Medicaid experiment show substantial outpatient utilization increases after coverage expansion.
                Compulsory participation mandates in Massachusetts reduced uncompensated hospital care and lowered pooled premiums.
                Rothschild-Stiglitz screening models predict partial coverage for low-risk types, whereas empirical plans show clustering.
                The commercial bank expanded mortgage lending after regulatory reserve requirements fell.

                # Chapter 2 Drug Pricing Reforms

                Pharmaceutical innovation responds strongly to expected market size and patent exclusivity windows across therapeutic classes.
                """.trimIndent(),
            )
            val mdC = writeBook(
                root,
                "book-c-policy.md",
                """
                # Chapter 1 Universal Coverage Architecture

                Compulsory participation combined with risk adjustment transfers mitigates adverse selection across competing insurers.
                Moral hazard can be moderated through tiered coinsurance schedules that preserve financial risk protection.
                Actuarial fairness conflicts with community rating policies designed to subsidize chronically ill households.
                """.trimIndent(),
            )

            val library = TextbookLibrary(root.resolve("library"))
            val bookA = library.importBook(mdA)
            val bookB = library.importBook(mdB)
            val bookC = library.importBook(mdC)

            val colId = "booklist-econ-3"
            library.upsertCollection(MaterialCollectionRecord(colId, CollectionKind.BOOKLIST, "健康与激励书单"))
            library.attachMaterial(CollectionMaterialRecord(colId, bookA.id, role = "LIST_BOOK", ordinal = 0))
            library.attachMaterial(CollectionMaterialRecord(colId, bookB.id, role = "LIST_BOOK", ordinal = 1))
            library.attachMaterial(CollectionMaterialRecord(colId, bookC.id, role = "LIST_BOOK", ordinal = 2))

            // Run full 2-stage alignment (Stage 1 FTS5 + Stage 2 judge)
            val report = BooklistAlignmentEngine.alignBooklist(library, colId, HeuristicConceptSemanticJudge)
            assertEquals(3, report.booksProcessed)
            assertTrue(report.extractedMentions >= 12, "Expected >=12 verified mentions, got ${report.extractedMentions}")
            assertTrue(report.ftsCandidatePairs >= 3, "Expected >=3 FTS5 candidate pairs, got ${report.ftsCandidatePairs}")
            assertTrue(report.alignedConcepts >= 3, "Expected >=3 aligned cross-book concepts, got ${report.alignedConcepts}")

            // Verify every persisted quote_anchor passes exact UTF-16 substring verification against stored pages.
            val snapshot = library.booklistConcepts(
                colId,
                mapOf(bookA.id to bookA.title, bookB.id to bookB.title, bookC.id to bookC.title),
                emptyMap(),
            )
            val allMentions = snapshot.active.flatMap { it.mentions }
            assertTrue(allMentions.isNotEmpty())
            val booksById = mapOf(bookA.id to bookA, bookB.id to bookB, bookC.id to bookC)
            for (cv in snapshot.active) {
                assertFalse(
                    ChapterConceptExtractor.isGenericStopConcept(cv.entity.canonicalName),
                    "Generic stop concept leaked: ${cv.entity.canonicalName}",
                )
            }
            for (mv in allMentions) {
                val pageText = booksById[mv.mention.bookId]!!.page(mv.mention.page)!!.text
                val slice = pageText.substring(mv.mention.offsetUtf16, mv.mention.offsetUtf16 + mv.mention.quoteAnchor.length)
                assertEquals(mv.mention.quoteAnchor, slice, "UTF-16 quote_anchor mismatch on ${mv.mention.mentionId}")
            }

            // Homonym "bank" (river bank vs commercial bank) must NOT be aligned.
            assertTrue(
                snapshot.active.none { it.entity.canonicalName.equals("bank", ignoreCase = true) },
                "Homonym 'bank' must be rejected",
            )

            // Verify forged quote_anchor is rejected by ConceptStore even if directly submitted.
            val badConceptId = "concept-forged-test"
            val forgedMention = ConceptMentionRecord(
                mentionId = "m-forged",
                conceptId = badConceptId,
                bookId = bookA.id,
                tocNodeId = BookChapterResolver.chapters(bookA).first().tocNodeId,
                page = 1,
                offsetUtf16 = 0,
                quoteAnchor = "NONEXISTENT_PHRASE_THAT_NEVER_APPEARS_IN_BOOK_A",
                claimSummary = "forged",
            )
            assertNull(ConceptAnchorVerifier.verify(bookA.pages.first().text, forgedMention.quoteAnchor, 0))

            // Iron Law 2 (TOC skip / UNREAD gate):
            // Suppose user jumps directly via TOC to Book A Chapter 2 (leaving Chapter 1 UNREAD).
            val chA1 = BookChapterResolver.chapters(bookA)[0]
            val chA2 = BookChapterResolver.chapters(bookA)[1]
            val chB1 = BookChapterResolver.chapters(bookB)[0]
            val chC1 = BookChapterResolver.chapters(bookC)[0]
            library.setChapterManualReadState(bookA.id, chA2.tocNodeId, chA2.pagesTotal, read = true)

            // Reading Book B Chapter 1 must trigger ZERO cross-book cards from Book A Chapter 1 (skipped / UNREAD)!
            val cardsWhenSkipped = library.companionCrossBookCards(bookB.id, chB1.tocNodeId, chB1.firstPage..chB1.lastPage, colId)
            assertEquals(0, cardsWhenSkipped.size, "Skipped/UNREAD chapters must trigger 0 cross-book cards")

            // Now mark Book A Chapter 1 and Book C Chapter 1 as genuinely READ.
            library.setChapterManualReadState(bookA.id, chA1.tocNodeId, chA1.pagesTotal, read = true)
            library.setChapterManualReadState(bookC.id, chC1.tocNodeId, chC1.pagesTotal, read = true)

            // Iron Law 3: High-value restraint -> <= 2 cards per section even though >= 3 concepts exist!
            val cardsWhenRead = library.companionCrossBookCards(bookB.id, chB1.tocNodeId, chB1.firstPage..chB1.lastPage, colId)
            assertTrue(cardsWhenRead.size in 1..2, "Expected 1..2 cards when chapters READ, got ${cardsWhenRead.size}")

            // Iron Law 4: Theme-level permanent muting (`concept_entities.muted = 1`) -> 0 leakage across ALL books/chapters!
            val mutedConceptId = cardsWhenRead.first().conceptId
            assertTrue(library.setConceptMuted(mutedConceptId, true))

            // Check Book B Ch 1, Book A Ch 1, and Book C Ch 1 -> ZERO occurrences of mutedConceptId anywhere!
            library.setChapterManualReadState(bookB.id, chB1.tocNodeId, chB1.pagesTotal, read = true)
            val afterMuteB = library.companionCrossBookCards(bookB.id, chB1.tocNodeId, chB1.firstPage..chB1.lastPage, colId)
            val afterMuteA = library.companionCrossBookCards(bookA.id, chA1.tocNodeId, chA1.firstPage..chA1.lastPage, colId)
            val afterMuteC = library.companionCrossBookCards(bookC.id, chC1.tocNodeId, chC1.firstPage..chC1.lastPage, colId)
            assertTrue(afterMuteB.none { it.conceptId == mutedConceptId }, "Muted concept leaked into Book B")
            assertTrue(afterMuteA.none { it.conceptId == mutedConceptId }, "Muted concept leaked into Book A")
            assertTrue(afterMuteC.none { it.conceptId == mutedConceptId }, "Muted concept leaked into Book C")

            // Shared note & muted state must survive re-alignment!
            val secondConceptId = snapshot.active.first { it.entity.conceptId != mutedConceptId }.entity.conceptId
            assertTrue(library.saveConceptSharedNote(secondConceptId, "两书在实证与理论边界上互补。"))
            BooklistAlignmentEngine.alignBooklist(library, colId, HeuristicConceptSemanticJudge)
            val afterRealign = library.booklistConcepts(
                colId,
                mapOf(bookA.id to bookA.title, bookB.id to bookB.title, bookC.id to bookC.title),
                emptyMap(),
            )
            assertTrue(afterRealign.muted.any { it.entity.conceptId == mutedConceptId }, "Re-alignment must preserve muted=1")
            assertEquals(
                "两书在实证与理论边界上互补。",
                afterRealign.active.first { it.entity.conceptId == secondConceptId }.entity.sharedNote,
                "Re-alignment must preserve shared_note",
            )

            // Restoring from muted list restores the concept to active list.
            assertTrue(library.setConceptMuted(mutedConceptId, false))
            val afterRestore = library.booklistConcepts(
                colId,
                mapOf(bookA.id to bookA.title, bookB.id to bookB.title, bookC.id to bookC.title),
                emptyMap(),
            )
            assertTrue(afterRestore.muted.none { it.entity.conceptId == mutedConceptId })
            assertTrue(afterRestore.active.any { it.entity.conceptId == mutedConceptId })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun aiExplainDisabledStrictlyUsesLocalAlignmentOnAutoCreateAddAndManualRealign() = runBlocking {
        val root = Files.createTempDirectory("coach-w2d-privacy-")
        try {
            val mdA = writeBook(
                root,
                "privacy-a.md",
                """
                # Chapter 1 Asymmetric Information

                Adverse selection arises in insurance contracts when high-risk individuals conceal private morbidity signals from insurers.
                Compulsory participation stabilizes risk pooling by preventing healthy enrollees from exiting.
                Moral hazard increases healthcare utilization once coinsurance drops below marginal cost.
                """.trimIndent(),
            )
            val mdB = writeBook(
                root,
                "privacy-b.md",
                """
                # Chapter 1 Empirical Insurance Evidence

                Contrary to simple adverse selection theory, empirical evidence finds advantageous selection driven by cognitive ability.
                Compulsory participation mandates reduced uncompensated hospital care and lowered pooled premiums.
                Moral hazard estimates from field experiments show rising outpatient utilization after coverage expansion.
                """.trimIndent(),
            )
            val mdC = writeBook(
                root,
                "privacy-c.md",
                """
                # Chapter 1 Risk Adjustment Policies

                Compulsory participation combined with risk adjustment mitigates adverse selection across competing insurers.
                Moral hazard can be moderated through tiered coinsurance schedules that preserve risk protection.
                """.trimIndent(),
            )

            val libDir = root.resolve("library")
            val library = TextbookLibrary(libDir)
            val bookA = library.importBook(mdA)
            val bookB = library.importBook(mdB)
            val bookC = library.importBook(mdC)

            // Turn OFF "伴读讲解与提问" ("已关闭，仅本地阅读不发送正文。")
            val settings = ReadingSettings(libDir)
            settings.chooseAiExplain(false)
            assertFalse(library.isReadingAiExplainEnabled())

            val loadCalls = AtomicInteger(0)
            val alignCalls = AtomicInteger(0)
            val spyExplainer = object : BookExplainer(DesktopConnectionSettings(root.resolve("conn")), library) {
                init {
                    identity = DesktopIdentity("http://127.0.0.1:18791", PairedCredentials("dev-test", "tok-test"))
                    loaded = true
                }

                override suspend fun load() {
                    loadCalls.incrementAndGet()
                    loaded = true
                }

                override suspend fun alignBooklistConcepts(requestJson: JsonObject): JsonObject {
                    alignCalls.incrementAndGet()
                    val pairs = (requestJson["candidates"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    return buildJsonObject {
                        put("decisions", buildJsonArray {
                            for (p in pairs) {
                                val pid = (p["pairId"] as? JsonPrimitive)?.content ?: continue
                                add(buildJsonObject {
                                    put("pairId", pid)
                                    put("keep", true)
                                    put("canonicalName", "Adverse Selection")
                                    put("aliases", buildJsonArray { add(JsonPrimitive("逆向选择")) })
                                    put("relationType", "OPPOSING_VIEW")
                                    put("diffSummary", "远端返回摘要。")
                                    put("confidence", 0.94)
                                    put("rejectReason", "")
                                })
                            }
                        })
                    }
                }
            }

            // 1. Direct RemoteBooklistSemanticJudge with allowRemote={false} must NEVER call remote explainer.
            val blockedJudge = RemoteBooklistSemanticJudge(spyExplainer, allowRemote = { false })
            val colDirect = "booklist-privacy-direct"
            library.upsertCollection(MaterialCollectionRecord(colDirect, CollectionKind.BOOKLIST, "隐私测试直调"))
            library.attachMaterial(CollectionMaterialRecord(colDirect, bookA.id, role = "LIST_BOOK", ordinal = 0))
            library.attachMaterial(CollectionMaterialRecord(colDirect, bookB.id, role = "LIST_BOOK", ordinal = 1))
            val directReport = BooklistAlignmentEngine.alignBooklist(library, colDirect, blockedJudge)
            assertFalse(directReport.usedRemoteJudge)
            assertFalse(blockedJudge.lastUsedRemote)
            assertTrue(directReport.alignedConcepts >= 2, "Local fallback must still align concepts")
            assertEquals(0, alignCalls.get(), "Remote align endpoint must not be called when allowRemote=false")

            // 2. Controller automatic alignment on createCollection + addBooksToBooklist AND manual launchBooklistAlignment.
            val controller = TextbookController(library)
            controller.conceptSemanticJudge = RemoteBooklistSemanticJudge(spyExplainer, allowRemote = { true })
            controller.load()

            // Auto path A: createCollection(BOOKLIST) with >= 2 books triggers automatic alignment.
            controller.createCollection(
                kind = CollectionKind.BOOKLIST,
                title = "仅本地书单",
                existingBookIds = listOf(bookA.id, bookB.id),
                newFiles = emptyList(),
            ).join()
            controller.awaitBooklistAlignment()
            val createdId = controller.state.value.collections.first { it.record.title == "仅本地书单" }.record.id
            assertEquals(0, alignCalls.get(), "Auto alignment on createCollection must stay strictly local when aiExplain=false")
            assertEquals(0, loadCalls.get())
            assertTrue(
                library.booklistConcepts(createdId, mapOf(bookA.id to bookA.title, bookB.id to bookB.title), emptyMap()).active.size >= 2,
                "Local alignment on createCollection must still produce concepts",
            )

            // Auto path B: addBooksToBooklist triggers automatic alignment.
            controller.addBooksToBooklist(
                collectionId = createdId,
                existingBookIds = listOf(bookC.id),
                newFiles = emptyList(),
            ).join()
            controller.awaitBooklistAlignment()
            assertEquals(0, alignCalls.get(), "Auto alignment on addBooksToBooklist must stay strictly local when aiExplain=false")

            // Manual path: explicit launchBooklistAlignment ("重新整理共同主题").
            controller.launchBooklistAlignment(createdId).join()
            assertEquals(0, alignCalls.get(), "Manual realign must stay strictly local when aiExplain=false")

            // 3. Re-enabling aiExplain restores remote adjudication cleanly.
            settings.chooseAiExplain(true)
            assertTrue(library.isReadingAiExplainEnabled())
            controller.launchBooklistAlignment(createdId).join()
            assertEquals(1, alignCalls.get(), "Remote alignment should resume once aiExplain=true")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun sequentialScreenReadingMarksChapterReadWhileJumpsAndRapidMashingNeverMarkSkippedChapters() {
        val root = Files.createTempDirectory("coach-ton1847-seq-dwell-")
        try {
            val md = writeBook(
                root,
                "seq-chapters.md",
                buildString {
                    appendLine("# Chapter 1 Foundations")
                    appendLine("Page one of chapter one.")
                    appendLine("\u000c")
                    appendLine("Page two of chapter one.")
                    appendLine("# Chapter 2 Wages and Profits")
                    appendLine("Wages of labour and profits of stock on page three.")
                    appendLine("\u000c")
                    appendLine("Page four of chapter two.")
                    appendLine("\u000c")
                    appendLine("Page five of chapter two.")
                    appendLine("\u000c")
                    appendLine("Page six of chapter two.")
                    appendLine("\u000c")
                    appendLine("Page seven of chapter two.")
                    appendLine("# Chapter 3 Natural Price")
                    appendLine("Natural and market price on page eight.")
                },
            )
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(md)
            val chapters = BookChapterResolver.chapters(book)
            assertEquals(3, chapters.size)
            val ch1 = chapters[0]
            val ch2 = chapters[1]
            val ch3 = chapters[2]

            // 1. Jump directly via TOC to Chapter 2 start (skipping Chapter 1), then read sequentially ~4s/page into Chapter 3.
            val tracker = ChapterDwellTracker(minPageDwellMs = 8_000L)
            val committed = mutableListOf<Pair<BookChapterSpan, Int>>()
            committed += tracker.onLocationChanged(ch2, setOf(ch2.firstPage), isJump = true)
            assertTrue(committed.isEmpty(), "TOC jump must not mark skipped Chapter 1 or Chapter 2 yet")

            for (p in ch2.firstPage..ch2.lastPage) {
                if (p > ch2.firstPage) {
                    committed += tracker.onLocationChanged(ch2, setOf(p), isJump = false)
                }
                repeat(8) { // 8 * 500ms = 4s/page (< 8s single-page, 20s total across 5 pages >= 2s/page)
                    committed += tracker.onTick(ch2, setOf(p), 500L)
                }
            }
            // Entering Chapter 3 sequentially after finishing Chapter 2 commits Chapter 2's visited pages!
            committed += tracker.onLocationChanged(ch3, setOf(ch3.firstPage), isJump = false)
            for ((span, page) in committed) {
                library.recordPageDwell(book.id, span.tocNodeId, page, span.pagesTotal, ChapterReadPolicy.MIN_DWELL_MS)
            }
            val progressAfterCh2 = library.chapterProgressMap(book.id)
            assertEquals(ChapterReadState.UNREAD, progressAfterCh2[ch1.tocNodeId]?.readState ?: ChapterReadState.UNREAD)
            assertEquals(ChapterReadState.READ, progressAfterCh2[ch2.tocNodeId]?.readState)

            // 2. Pressing End / jumping mid-chapter or rapid 200ms/page mashing on Chapter 3 must NOT mark it READ.
            val antiSkipTracker = ChapterDwellTracker(minPageDwellMs = 8_000L)
            val skippedCommits = mutableListOf<Pair<BookChapterSpan, Int>>()
            skippedCommits += antiSkipTracker.onLocationChanged(ch1, setOf(ch1.firstPage), isJump = false)
            skippedCommits += antiSkipTracker.onTick(ch1, setOf(ch1.firstPage), 200L)
            skippedCommits += antiSkipTracker.onLocationChanged(ch1, setOf(ch1.lastPage), isJump = false)
            skippedCommits += antiSkipTracker.onTick(ch1, setOf(ch1.lastPage), 200L)
            // End jump straight to Chapter 3
            skippedCommits += antiSkipTracker.onLocationChanged(ch3, setOf(ch3.lastPage), isJump = true)
            skippedCommits += antiSkipTracker.onTick(ch3, setOf(ch3.lastPage), 500L)
            assertTrue(skippedCommits.isEmpty(), "Rapid mashing and End jump must record 0 dwell pages")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
