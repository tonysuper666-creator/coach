package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CollectionQaEngineTest {

    @Test
    fun fullPageRetrievalPreservesPageBottomAbove1500CharsAndJoinsOfflineDiscrepanciesWithoutFalsePositives() = runBlocking {
        val root = Files.createTempDirectory("coach-ai3-course-test-")
        try {
            val lib = TextbookLibrary(root.resolve("lib"))
            val filler = "This introductory paragraph explains basic utility notation and budget constraints in healthcare consumption. ".repeat(24)
            assertTrue(filler.length > 2200)
            val textbookFile = root.resolve("Core-Textbook.md").also {
                Files.writeString(
                    it,
                    """
                    # Chapter 5 Insurance Equilibrium

                    $filler
                    Bottom page conclusion: In competitive separating pools, robust individuals choose partial insurance labeled Omega_3 while risky types buy full cover.
                    Furthermore, any pooling equilibrium fails because an insurer can always cream-skim healthy buyers using single-crossing indifference curves.
                    """.trimIndent(),
                )
            }
            val slidesFile = root.resolve("Week5-Lecture-Slides.md").also {
                Files.writeString(
                    it,
                    """
                    # Week 5 Adverse Selection
                    On our lecture diagram, the separating contract for robust households is marked as Omega_2 rather than Omega_3.
                    """.trimIndent(),
                )
            }
            val bookText = lib.importBook(textbookFile)
            val bookSlides = lib.importBook(slidesFile)

            val courseId = lib.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db,
                    title = "Applied Health Economics",
                    materialBookIds = listOf(bookText.id, bookSlides.id),
                )
                CourseAutoAligner.alignLocally(db, cid)
                val detail = CourseStore.loadCourseDetail(db, cid)
                val topic = detail.allTopics.first()
                val textTargetPage = bookText.pages.first { "Omega_3" in it.text }.number
                val pack = CourseTopicPack(
                    topicId = topic.topicId,
                    summary = "Separating equilibrium notation comparison",
                    authorityNotice = "L1 slides precede L2 textbook notation.",
                    discrepancies = listOf(
                        CourseDiscrepancyItem(
                            point = "分离均衡部分保险合同记号 Ω3 与 Ω2 差异",
                            authoritativeView = "第5周讲义把健壮人群买到的部分保险合同记为 Omega_2",
                            secondaryView = "课本把同一分离均衡部分保险合同记为 Omega_3",
                            advice = "两份材料指代同一分离均衡合同，只是符号下标编号对调",
                            citations = listOf(
                                CourseCitationRef(
                                    bookId = bookSlides.id,
                                    bookTitle = bookSlides.title,
                                    role = CourseMaterialRole.LECTURE_SLIDES,
                                    page = 1,
                                    startUtf16 = 0,
                                    endUtf16 = 20,
                                    quote = "marked as Omega_2 rather than Omega_3",
                                ),
                                CourseCitationRef(
                                    bookId = bookText.id,
                                    bookTitle = bookText.title,
                                    role = CourseMaterialRole.CORE_TEXTBOOK,
                                    page = textTargetPage,
                                    startUtf16 = 0,
                                    endUtf16 = 20,
                                    quote = "robust individuals choose partial insurance labeled Omega_3",
                                ),
                            ),
                        ),
                    ),
                )
                CourseStore.saveTopicPack(db, topic.topicId, pack, topic.packSourcesHash.orEmpty())
                cid
            }

            // 1. Discrepancy question co-retrieves full bottom-of-page (> 2200 chars) + offline discrepancy
            val conflictBundle = CollectionQaRetriever.retrieve(
                lib,
                courseId,
                "在 Rothschild-Stiglitz 分离均衡里，健壮人群买到的部分保险合同是 Omega_2 还是 Omega_3？",
            )
            assertTrue(conflictBundle.blocks.any { it.bookId == bookText.id && "Omega_3" in it.text }, "Page bottom (>2200 chars) must NOT be truncated")
            assertTrue(conflictBundle.blocks.any { it.bookId == bookSlides.id && "Omega_2" in it.text })
            assertEquals(1, conflictBundle.matchedDiscrepancies.count { it.directAspectMatch })

            // 2. Non-conflict question on the SAME Rothschild-Stiglitz model must have ZERO false-positive discrepancy matches!
            val concordantBundle = CollectionQaRetriever.retrieve(
                lib,
                courseId,
                "在 Rothschild-Stiglitz 模型里，为什么混同均衡永远不可能存在？",
            )
            assertEquals(0, concordantBundle.matchedDiscrepancies.count { it.directAspectMatch })
            assertTrue(concordantBundle.blocks.any { "cream-skim" in it.text })

            // 3. End-to-end engine strips fabricated citations & maps valid UTF-16 passage offsets
            val targetBlock = conflictBundle.blocks.first { it.bookId == bookText.id }
            val turn = CollectionQaEngine.ask(
                library = lib,
                collectionId = courseId,
                question = "在 Rothschild-Stiglitz 分离均衡里，健壮人群买到的部分保险合同是 Omega_2 还是 Omega_3？",
                remoteAsk = { _ ->
                    CourseServerCallResult(
                        body = buildJsonObject {
                            put("sentences", buildJsonArray {
                                add(buildJsonObject {
                                    put("claim", "课本把健壮人群买到的部分保险合同记为 Omega_3。")
                                    put("blockId", targetBlock.blockId)
                                    put("quoteAnchor", "robust individuals choose partial insurance labeled Omega_3")
                                })
                                add(buildJsonObject {
                                    put("claim", "未核验的虚构引文必须当场剥离。")
                                    put("blockId", targetBlock.blockId)
                                    put("quoteAnchor", "hallucinated non-existent quote from nowhere")
                                })
                            })
                            put("hasMaterialConflict", false)
                            put("conflictSummaryZh", "")
                        },
                        error = null,
                    )
                },
            ).getOrThrow()

            assertEquals(1, turn.sentences.size)
            assertTrue(turn.droppedUnverifiedCount >= 1)
            assertTrue(turn.hasMaterialConflict)
            assertEquals(1, turn.conflictCards.size)
            assertEquals(2, turn.conflictCards.first().citations.size)
            val cit = turn.sentences.first().citation
            val pageObj = bookText.page(cit.page)!!
            val passage = SentenceCitationGate.locatePassageInPage(bookText.id, pageObj, cit.quoteAnchor, cit.timestampLabel)
            assertNotNull(passage)
            assertTrue(pageObj.text.substring(passage.start, passage.end).contains("Omega_3"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun booklistEnforcesHardAntiSpoilerReadGateAcrossPagesAndConceptLinks() = runBlocking {
        val root = Files.createTempDirectory("coach-ai3-booklist-antisp-")
        try {
            val lib = TextbookLibrary(root.resolve("lib"))
            val bookAFile = root.resolve("Book-A.md").also {
                Files.writeString(
                    it,
                    """
                    # 1.1 First Read Topic
                    Public goods underprovision is analyzed through Lindahl pricing mechanisms in Chapter 1.

                    # 2.1 Unread Spoiler Chapter
                    Secret ending twist: hidden Pareto inefficiency in Chapter 2 resolves via voucher taxation.
                    """.trimIndent(),
                )
            }
            val bookBFile = root.resolve("Book-B.md").also {
                Files.writeString(
                    it,
                    """
                    # 1.1 Completely Unread Book
                    Book B discusses Lindahl equilibrium breakdown and voucher taxation spoilers in full detail.
                    """.trimIndent(),
                )
            }
            val bookA = lib.importBook(bookAFile)
            val bookB = lib.importBook(bookBFile)

            val booklistId = "booklist-antisp-1"
            lib.upsertCollection(
                MaterialCollectionRecord(
                    id = booklistId,
                    kind = CollectionKind.BOOKLIST,
                    title = "制度经济学书单",
                ),
            )
            lib.attachMaterial(CollectionMaterialRecord(booklistId, bookA.id, ordinal = 0))
            lib.attachMaterial(CollectionMaterialRecord(booklistId, bookB.id, ordinal = 1))

            val ch1 = BookChapterResolver.chapters(bookA).first()
            val ch2 = BookChapterResolver.chapters(bookA).last()
            assertFalse(ch1.tocNodeId == ch2.tocNodeId)

            // Mark ONLY Book A Ch 1 as READ; Book A Ch 2 and Book B remain UNREAD
            lib.setChapterManualReadState(bookA.id, ch1.tocNodeId, ch1.pagesTotal, read = true)

            // Seed a concept_link pointing from Book A Ch1 to UNREAD Book B Ch1
            val pageA1 = bookA.page(ch1.firstPage)!!.text
            val pageB1 = bookB.page(1)!!.text
            val anchorA = "Lindahl pricing mechanisms"
            val anchorB = "Lindahl equilibrium breakdown"
            lib.withCourseDatabase { db ->
                ConceptStore.saveAlignedConcepts(
                    db = db,
                    collectionId = booklistId,
                    entities = listOf(ConceptEntityRecord("c-lindahl", booklistId, "林达尔定价")),
                    mentions = listOf(
                        ConceptMentionRecord(
                            mentionId = "m-a",
                            conceptId = "c-lindahl",
                            bookId = bookA.id,
                            tocNodeId = ch1.tocNodeId,
                            page = ch1.firstPage,
                            offsetUtf16 = pageA1.indexOf(anchorA),
                            quoteAnchor = anchorA,
                            claimSummary = "A书第1章讨论林达尔机制",
                        ),
                        ConceptMentionRecord(
                            mentionId = "m-b",
                            conceptId = "c-lindahl",
                            bookId = bookB.id,
                            tocNodeId = BookChapterResolver.chapters(bookB).first().tocNodeId,
                            page = 1,
                            offsetUtf16 = pageB1.indexOf(anchorB),
                            quoteAnchor = anchorB,
                            claimSummary = "B书未读第1章的反例剧透",
                        ),
                    ),
                    links = listOf(
                        ConceptLinkRecord(
                            linkId = "l-1",
                            collectionId = booklistId,
                            conceptId = "c-lindahl",
                            fromMentionId = "m-a",
                            toMentionId = "m-b",
                            relationType = ConceptRelationType.OPPOSING_VIEW,
                            diffSummary = "林达尔机制崩溃剧透",
                            confidence = 0.95,
                        ),
                    ),
                )
            }

            // Ask about unread spoiler keywords ("voucher taxation" / "Secret ending twist" / "Book B")
            val bundle = CollectionQaRetriever.retrieve(
                lib,
                booklistId,
                "Lindahl pricing and voucher taxation twist across Book A and Book B?",
            )
            assertTrue(bundle.unreadPagesFilteredOut >= 1)
            assertTrue(bundle.blocks.all { it.bookId == bookA.id && it.page in ch1.firstPage..ch1.lastPage })
            assertFalse(bundle.blocks.any { "Secret ending twist" in it.text || "Book B discusses" in it.text })
            assertEquals(0, bundle.matchedDiscrepancies.size, "Concept links touching unread Book B must be blocked")

            // When NO chapters are READ in a booklist, return clean message without calling remote server
            lib.setChapterManualReadState(bookA.id, ch1.tocNodeId, ch1.pagesTotal, read = false)
            var remoteCalled = false
            val emptyReadTurn = CollectionQaEngine.ask(
                library = lib,
                collectionId = booklistId,
                question = "Lindahl pricing",
                remoteAsk = {
                    remoteCalled = true
                    CourseServerCallResult(null, "should not be called")
                },
            ).getOrThrow()
            assertFalse(remoteCalled)
            assertTrue(emptyReadTurn.sentences.isEmpty())
            assertTrue(emptyReadTurn.fallbackMessage?.contains("还没有已读章节") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
