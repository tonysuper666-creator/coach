package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7ReducedMotion
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BookMapWhatAboutTest {
    private fun makeMultiChapterBook(
        bookId: String,
        title: String,
        chapterCount: Int,
        includeFrontMatterPages: Int = 2,
        chinese: Boolean = false,
        messyWhitespace: Boolean = false,
        correctedBodyPage: Int? = null,
    ): Textbook {
        val pages = mutableListOf<BookPage>()
        var pageNum = 1
        for (f in 1..includeFrontMatterPages) {
            pages += BookPage(pageNum++, "Copyright Notice and Front Matter Preface page $f for $title. All rights reserved.")
        }
        val tocNodes = mutableListOf<BookTocNode>()
        for (c in 1..chapterCount) {
            val chStart = pageNum
            val chId = "ch-$c"
            val chTitle = if (chinese) "第 $c 章 核心专题 $c" else "$c Core Topic Chapter $c"
            for (pOffset in 0..2) {
                val p = pageNum++
                val text = if (chinese) {
                    "第 $c 章第 ${pOffset + 1} 页正文：核心制度安排决定了长期激励机制与行为均衡结果。" +
                        "当外部约束发生变化时，微观主体会重新权衡边际收益与边际成本以达成新的稳定状态。" +
                        "实证数据表明第 $c 章第 ${pOffset + 1} 节的政策干预显著改变了样本群体的选择分布。"
                } else if (messyWhitespace) {
                    "Chapter $c section ${pOffset + 1} explains how   co\u00ADinsurance\n" +
                        "rates shape marginal healthcare demand across households in sample $bookId. " +
                        "Empirical evidence in chapter $c page $p shows that lower cost sharing increases outpatient utilization substantially. " +
                        "Policy makers must balance financial risk protection against moral hazard welfare losses in chapter $c."
                } else {
                    "Chapter $c section ${pOffset + 1} establishes that economic incentives govern equilibrium allocation in $bookId. " +
                        "Empirical estimates on page $p confirm that price elasticity varies significantly across income groups in chapter $c. " +
                        "Institutional design in chapter $c section ${pOffset + 1} mitigates adverse selection through risk pooling mechanisms."
                }
                pages += BookPage(p, text)
            }
            val chEnd = pageNum - 1
            tocNodes += BookTocNode(
                id = chId,
                parentId = null,
                ordinal = c,
                level = 1,
                kind = TocNodeKind.CHAPTER,
                numberLabel = c.toString(),
                title = chTitle,
                startPage = chStart,
                endPage = chEnd,
                source = HeadingSource.PDF_OUTLINE,
            )
        }
        val structure = BookStructure(
            pageRoles = pages.associate { it.number to if (it.number <= includeFrontMatterPages) BookPageRole.FRONT_MATTER else BookPageRole.BODY },
            sections = emptyList(),
            tocNodes = tocNodes,
            source = HeadingSource.PDF_OUTLINE.name,
            nativeBodyStart = BookPageAnchor(includeFrontMatterPages + 1, 0),
        )
        return Textbook(
            id = bookId,
            title = title,
            format = "pdf",
            pages = pages,
            structure = structure,
            correctedBodyPage = correctedBodyPage,
        )
    }

    private fun makeSamePageEpubBook(): Textbook {
        val ch1Text = "First chapter opening explains how early markets coordinate decentralized production across regions. " +
            "Specialization raises output whenever transaction costs remain low enough for regular trade. " +
            "Historical records show guild rules gradually gave way to open merchant contracts."
        val ch2Text = "Second chapter examines how central banking stabilizes liquidity during sudden credit contractions. " +
            "Lender of last resort policy prevents solvent institutions from collapsing under panic withdrawals. " +
            "Modern monetary frameworks anchor inflation expectations through transparent rate targets."
        val fullPage = "$ch1Text\n$ch2Text"
        val splitOffset = ch1Text.length + 1
        val page = BookPage(1, fullPage)
        val toc = listOf(
            BookTocNode("ep-ch-1", null, 1, 1, TocNodeKind.CHAPTER, "1", "Chapter 1 Markets", 1, 1, source = HeadingSource.EPUB_TOC, startOffset = 0),
            BookTocNode("ep-ch-2", null, 2, 1, TocNodeKind.CHAPTER, "2", "Chapter 2 Banking", 1, 1, source = HeadingSource.EPUB_TOC, startOffset = splitOffset),
        )
        return Textbook(
            id = "epub-same-page",
            title = "Same-Page EPUB",
            format = "epub",
            pages = listOf(page),
            structure = BookStructure(
                pageRoles = mapOf(1 to BookPageRole.BODY),
                sections = emptyList(),
                tocNodes = toc,
                source = HeadingSource.EPUB_TOC.name,
                nativeBodyStart = BookPageAnchor(1, 0),
            ),
        )
    }

    /** Simulates a Haiku 5.5 Batch response producing 4–5 grounded points per chapter from the request's numbered refs. */
    private fun fakeBatchResponseFor(request: JsonObject): JsonObject {
        val chaptersArr = request["chapters"]!!.jsonArray
        return buildJsonObject {
            put("formatVersion", 2)
            put("pending", false)
            put("model", "claude-haiku-5-5")
            put(
                "chapters",
                buildJsonArray {
                    chaptersArr.forEach { el ->
                        val ch = el.jsonObject
                        val refs = ch["refs"]!!.jsonArray
                        val pickedCount = minOf(5, refs.size)
                        add(
                            buildJsonObject {
                                put("chapterId", ch["chapterId"]!!.jsonPrimitive.content)
                                put("chapterNum", ch["chapterNum"]!!.jsonPrimitive.intOrNull ?: 1)
                                put("oneLiner", "概括：${ch["chapterTitle"]!!.jsonPrimitive.content}的主干逻辑")
                                put(
                                    "points",
                                    buildJsonArray {
                                        for (i in 0 until pickedCount) {
                                            val r = refs[i].jsonObject
                                            val refId = r["ref"]!!.jsonPrimitive.intOrNull!!
                                            add(
                                                buildJsonObject {
                                                    put("summary", "要点 ${i + 1}：说明了第 ${ch["chapterNum"]!!.jsonPrimitive.intOrNull} 章的关键论断 #$refId。")
                                                    put("refs", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(refId)) })
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    @Test
    fun tenBooksRandomTenPointsEachJumpAndHighlightAccuracyAtLeast95Percent() {
        val books = (1..10).map { idx ->
            makeMultiChapterBook(
                bookId = "bench-book-$idx",
                title = if (idx % 3 == 0) "中文教材第 $idx 本" else "Benchmark Textbook #$idx",
                chapterCount = 4 + (idx % 3),
                includeFrontMatterPages = idx % 4,
                chinese = idx % 3 == 0,
                messyWhitespace = idx % 2 == 0,
            )
        }

        val rng = Random(1889L)
        var totalSampled = 0
        var exactHighlightHits = 0

        for (book in books) {
            val built = BookMapWire.buildBatchRequest(book)
            val response = fakeBatchResponseFor(built.payload)
            val parsed = BookMapWire.parseBatchResponse(book, response, built.refMap)
            assertTrue(parsed is BookMapBatchParseResult.Ready, "Map generation for ${book.id} must succeed")
            val doc = parsed.document

            // Goal 1: every chapter has 3–7 points, each with >= 1 anchor.
            for (ch in doc.chapters) {
                assertTrue(ch.points.size in 3..7, "${book.id} ${ch.chapterId} expected 3..7 points, got ${ch.points.size}")
                for (pt in ch.points) {
                    assertTrue(pt.anchors.isNotEmpty(), "Every point must have at least one anchor")
                }
            }

            val allPoints = doc.chapters.flatMap { ch -> ch.points.map { ch to it } }
            val sample = allPoints.shuffled(rng).take(10)
            assertEquals(10, sample.size, "${book.id} should have at least 10 points to sample")

            for ((ch, pt) in sample) {
                totalSampled += 1
                val rawAnchor = pt.primaryAnchor!!
                val companionAnchor = BookMapAnchorResolver.toCompanionAnchor(book, rawAnchor)
                val targetPage = book.page(companionAnchor.pdfPage)
                // Exact TextbookReader.kt:1697-1701 highlight contract:
                val exactHighlight = targetPage != null &&
                    companionAnchor.pdfPage in ch.firstPage..ch.lastPage &&
                    companionAnchor.startUtf16 in 0 until companionAnchor.endUtf16 &&
                    companionAnchor.endUtf16 <= targetPage.text.length &&
                    targetPage.text.substring(companionAnchor.startUtf16, companionAnchor.endUtf16) == companionAnchor.quoteAnchor
                if (exactHighlight) exactHighlightHits += 1
            }
        }

        val accuracy = exactHighlightHits.toDouble() / totalSampled.toDouble()
        assertEquals(100, totalSampled)
        assertTrue(accuracy >= 0.95, "Expected >=95% jump+highlight accuracy across 10x10 points, got $exactHighlightHits/$totalSampled")
        assertEquals(100, exactHighlightHits)
    }

    @Test
    fun anchorResolverMapsMessyWhitespaceAndSoftHyphenQuotesToExactUtf16Span() {
        val book = makeMultiChapterBook("messy-1", "Messy PDF", chapterCount = 2, messyWhitespace = true)
        val page3 = book.page(3)!!
        // Model-style normalized quote without soft hyphen or newline:
        val normalizedQuote = "coinsurance rates shape marginal healthcare demand across households"
        val resolved = BookMapAnchorResolver.toCompanionAnchor(book, BookMapAnchor(pdfPage = 3, startUtf16 = 0, endUtf16 = 0, quote = normalizedQuote))
        assertEquals(3, resolved.pdfPage)
        assertTrue(resolved.startUtf16 in 0 until resolved.endUtf16)
        assertEquals(resolved.quoteAnchor, page3.text.substring(resolved.startUtf16, resolved.endUtf16))
        assertTrue(resolved.quoteAnchor.contains("demand across households"))
    }

    @Test
    fun skeletonEditsRearrangeSavedMapForRenameMergeSplitAndBodyStart() {
        val root = Files.createTempDirectory("book-map-skeleton-")
        try {
            val library = TextbookLibrary(root)
            val original = makeMultiChapterBook("skel-book", "Skeleton Book", chapterCount = 3, includeFrontMatterPages = 2)
            val built = BookMapWire.buildBatchRequest(original)
            val parsed = BookMapWire.parseBatchResponse(original, fakeBatchResponseFor(built.payload), built.refMap) as BookMapBatchParseResult.Ready
            BookMapStore.save(library, parsed.document)

            // 1. Rename Chapter 2 -> title updates, all points stay intact.
            val renamedToc = original.tocNodes.map { if (it.id == "ch-2") it.copy(title = "2 Renamed Incentives") else it }
            val renamedBook = original.copy(structure = original.structure!!.copy(tocNodes = renamedToc))
            val afterRename = BookMapStore.load(library, renamedBook)
            assertNotNull(afterRename)
            assertEquals("2 Renamed Incentives", afterRename.chapters[1].title)
            assertEquals(parsed.document.chapters[1].points.size, afterRename.chapters[1].points.size)
            assertTrue(afterRename.reconciledFromSkeleton)
            assertNotEquals(parsed.document.skeletonHash, afterRename.skeletonHash)

            // 2. Merge Chapter 2 into Chapter 1 -> points combine in page order.
            val mergedToc = listOf(
                renamedToc[0].copy(endPage = renamedToc[1].endPage),
                renamedToc[2],
            )
            val mergedBook = renamedBook.copy(structure = renamedBook.structure!!.copy(tocNodes = mergedToc))
            val afterMerge = BookMapStore.load(library, mergedBook)!!
            assertEquals(2, afterMerge.chapters.size)
            assertEquals(
                parsed.document.chapters[0].points.size + parsed.document.chapters[1].points.size,
                afterMerge.chapters[0].points.size,
            )

            // 3. Split merged Chapter 1 back at page 6 -> points partition accurately across the split boundary.
            val splitToc = listOf(
                BookTocNode("ch-1", null, 1, 1, TocNodeKind.CHAPTER, "1", "1 First Half", 3, 5, source = HeadingSource.PDF_OUTLINE),
                BookTocNode("ch-split", null, 2, 1, TocNodeKind.CHAPTER, "2", "2 Split Second Half", 6, 8, source = HeadingSource.PDF_OUTLINE),
                renamedToc[2],
            )
            val splitBook = mergedBook.copy(structure = mergedBook.structure!!.copy(tocNodes = splitToc))
            val afterSplit = BookMapStore.load(library, splitBook)!!
            assertEquals(3, afterSplit.chapters.size)
            assertTrue(afterSplit.chapters[0].points.all { it.primaryAnchor!!.pdfPage in 3..5 })
            assertTrue(afterSplit.chapters[1].points.all { it.primaryAnchor!!.pdfPage in 6..8 })

            // 4. Move bodyStartPage forward to page 6 -> Chapter 1 (pages 3..5) excluded from map.
            val shiftedBook = splitBook.copy(correctedBodyPage = 6)
            val afterShift = BookMapStore.load(library, shiftedBook)!!
            assertEquals(2, afterShift.chapters.size)
            assertEquals(6, afterShift.bodyStartPage)
            assertTrue(afterShift.chapters.all { it.firstPage >= 6 })

            // 5. Same-page EPUB split partitions by UTF-16 offset on page 1.
            val epub = makeSamePageEpubBook()
            val epubReq = BookMapWire.buildBatchRequest(epub)
            val epubDoc = (BookMapWire.parseBatchResponse(epub, fakeBatchResponseFor(epubReq.payload), epubReq.refMap) as BookMapBatchParseResult.Ready).document
            assertEquals(2, epubDoc.chapters.size)
            val ch2StartOffset = epub.tocNodes[1].startOffset
            assertTrue(epubDoc.chapters[0].points.all { it.primaryAnchor!!.startUtf16 < ch2StartOffset })
            assertTrue(epubDoc.chapters[1].points.all { it.primaryAnchor!!.startUtf16 >= ch2StartOffset })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun uiPanelGeneratesViaPendingBatchSavesLocallySpendsZeroOnReopenShowsReadStateAndHighlightsOnClick() {
        val root = Files.createTempDirectory("book-map-ui-")
        try {
            val library = TextbookLibrary(root)
            val book = makeMultiChapterBook("ui-book", "Health Economics Demo", chapterCount = 2, includeFrontMatterPages = 1)
            val batchCalls = AtomicInteger(0)

            // Mark Chapter 1 page 2 as dwelled (partial read) in book_chapter_progress.
            library.recordPageDwell(book.id, tocNodeId = "ch-1", page = 2, pagesTotal = 3, dwellMs = ChapterReadPolicy.MIN_DWELL_MS)

            var clickedAnchor: BookCompanionAnchor? = null
            runSkikoComposeUiTest(size = Size(820f, 960f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            BookWhatAboutMapPanel(
                                book = book,
                                library = library,
                                currentPage = 2,
                                aiAvailable = true,
                                pollDelayOverrideMs = 20L,
                                fetchBatch = { req ->
                                    val n = batchCalls.incrementAndGet()
                                    if (n == 1) {
                                        CourseServerCallResult(
                                            body = buildJsonObject {
                                                put("formatVersion", 2)
                                                put("pending", true)
                                                put("jobKey", "job-ui-1")
                                                put("retryAfterMs", 20L)
                                                put("completedChunks", 0)
                                                put("totalChunks", 1)
                                            },
                                            error = null,
                                        )
                                    } else {
                                        CourseServerCallResult(body = fakeBatchResponseFor(req), error = null)
                                    }
                                },
                                onOpenAnchor = { clickedAnchor = it },
                            )
                        }
                    }
                }

                onNodeWithTag("book-map-v2-generate").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag("book-map-v2-point-0-0").assertExists() }.isSuccess
                }
                assertEquals(2, batchCalls.get(), "Expected 1 pending poll + 1 completed fetch")

                // Read/unread labels are shown and clickable quote highlights exact UTF-16 slice.
                onNodeWithTag("book-map-v2-point-0-0-read-state", useUnmergedTree = true).assertExists()
                onNodeWithTag("book-map-v2-point-0-0-anchor-0", useUnmergedTree = true).performClick()
                val anchor = assertNotNull(clickedAnchor)
                val targetPage = assertNotNull(book.page(anchor.pdfPage))
                assertTrue(anchor.startUtf16 in 0 until anchor.endUtf16)
                assertEquals(anchor.quoteAnchor, targetPage.text.substring(anchor.startUtf16, anchor.endUtf16))

                // Filter to 未读 still works cleanly.
                onNodeWithTag("book-map-filter-unread").performClick()
                onNodeWithTag("book-map-filter-all").performClick()
            }

            // Reopen in a fresh UI session (dark theme) -> must load from local SQLite with ZERO extra API calls!
            val reopenCalls = AtomicInteger(0)
            runSkikoComposeUiTest(size = Size(820f, 960f)) {
                setContent {
                    CoachTheme(true) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            BookWhatAboutMapPanel(
                                book = book,
                                library = library,
                                currentPage = 2,
                                aiAvailable = true,
                                fetchBatch = {
                                    reopenCalls.incrementAndGet()
                                    error("Reopening a saved book map must NOT call the server")
                                },
                                onOpenAnchor = {},
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag("book-map-v2-point-0-0").assertExists() }.isSuccess
                }
                assertEquals(0, reopenCalls.get(), "Reopening saved map must spend zero quota")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun uiPanelShowsHumanReadableOfflineAndErrorStates() {
        val root = Files.createTempDirectory("book-map-offline-")
        try {
            val library = TextbookLibrary(root)
            val book = makeMultiChapterBook("offline-book", "Offline Demo", chapterCount = 2)

            runSkikoComposeUiTest(size = Size(820f, 900f)) {
                var failOffline by mutableStateOf(true)
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            BookWhatAboutMapPanel(
                                book = book,
                                library = library,
                                currentPage = 3,
                                aiAvailable = true,
                                fetchBatch = {
                                    if (failOffline) {
                                        CourseServerCallResult(null, "连不上学习服务", ExplainTrouble.Unreachable)
                                    } else {
                                        CourseServerCallResult(null, "本月生成额度已达上限", ExplainTrouble.Busy)
                                    }
                                },
                                onOpenAnchor = {},
                            )
                        }
                    }
                }
                onNodeWithTag("book-map-v2-generate").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag("book-map-v2-offline").assertExists() }.isSuccess
                }
                onNodeWithText("现在连不上网络，联网后再生成地图；生成一次会存在本机，以后离线也能看。").assertIsDisplayed()

                failOffline = false
                onNodeWithTag("book-map-v2-retry").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag("book-map-v2-error").assertExists() }.isSuccess
                }
                onNodeWithText("当前生成额度紧张或已到上限，请稍后再试；已生成的地图不受影响。").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun realTon1876StructureEditRearrangesSavedBookMapAndPersistsAcrossReload() {
        val root = Files.createTempDirectory("book-map-ton1876-")
        try {
            val library = TextbookLibrary(root)
            val original = makeMultiChapterBook("ton1876-book", "Real Skeleton Book", chapterCount = 3, includeFrontMatterPages = 2)
            val built = BookMapWire.buildBatchRequest(original)
            val doc = (BookMapWire.parseBatchResponse(original, fakeBatchResponseFor(built.payload), built.refMap) as BookMapBatchParseResult.Ready).document
            BookMapStore.save(library, doc)

            // 1. Real TON-1876 Rename via applyStructureEdit
            val renamed = original.applyStructureEdit(StructureEdit.Rename("ch-2", "2 Moral Hazard Updated"))
            val afterRename = BookMapStore.load(library, renamed)!!
            assertEquals("2 Moral Hazard Updated", afterRename.chapters[1].title)
            assertEquals(doc.chapters[1].points.size, afterRename.chapters[1].points.size)
            assertTrue(afterRename.reconciledFromSkeleton)

            // 2. Real TON-1876 MergeWithPrevious via applyStructureEdit
            val merged = renamed.applyStructureEdit(StructureEdit.MergeWithPrevious("ch-2"))
            val afterMerge = BookMapStore.load(library, merged)!!
            assertEquals(2, afterMerge.chapters.size)
            assertEquals(doc.chapters[0].points.size + doc.chapters[1].points.size, afterMerge.chapters[0].points.size)

            // 3. Real TON-1876 Split via applyStructureEdit (split merged ch-1 at page 6)
            val split = merged.applyStructureEdit(StructureEdit.Split("ch-1", page = 6, title = "2 Split Out Chapter"))
            val afterSplit = BookMapStore.load(library, split)!!
            assertEquals(3, afterSplit.chapters.size)
            assertEquals("2 Split Out Chapter", afterSplit.chapters[1].title)
            assertTrue(afterSplit.chapters[0].points.all { it.primaryAnchor!!.pdfPage in 3..5 })
            assertTrue(afterSplit.chapters[1].points.all { it.primaryAnchor!!.pdfPage in 6..8 })

            // 4. Real TON-1876 BodyStart via applyStructureEdit (move body start to page 6)
            val bodyShifted = split.applyStructureEdit(StructureEdit.BodyStart(6))
            val afterBodyShift = BookMapStore.load(library, bodyShifted)!!
            assertEquals(6, afterBodyShift.bodyStartPage)
            assertEquals(2, afterBodyShift.chapters.size)
            assertTrue(afterBodyShift.chapters.all { it.firstPage >= 6 })

            // Reloading from SQLite with same edited book returns persisted reconciled map unchanged
            val reloaded = BookMapStore.load(library, bodyShifted)!!
            assertEquals(afterBodyShift.skeletonHash, reloaded.skeletonHash)
            assertEquals(afterBodyShift.chapters.size, reloaded.chapters.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun singleBookCompanionPanelWiresWhatAboutMapInBookMapMode() {
        val root = Files.createTempDirectory("book-map-companion-wire-")
        try {
            val library = TextbookLibrary(root)
            val book = makeMultiChapterBook("wire-book", "Wired Companion Book", chapterCount = 2, includeFrontMatterPages = 1)
            val built = BookMapWire.buildBatchRequest(book)
            val savedDoc = (BookMapWire.parseBatchResponse(book, fakeBatchResponseFor(built.payload), built.refMap) as BookMapBatchParseResult.Ready).document
            BookMapStore.save(library, savedDoc)

            val fakeExplainer = object : BookExplainer(DesktopConnectionSettings(home = root, environment = { null }), library) {
                init {
                    identity = DesktopIdentity("http://127.0.0.1:1", PairedCredentials("dev-test", "tok-test"))
                    loaded = true
                    revoked = false
                }

                override suspend fun load() {
                    loaded = true
                }

                override suspend fun fetchChapterCompanion(
                    requestJson: JsonObject,
                    blocks: List<SourceBlock>,
                ): ChapterCompanionFetchResult = ChapterCompanionFetchResult.Failed("skip", ExplainTrouble.Other)

                override suspend fun fetchStudyModePack(
                    bookId: String,
                    mode: String,
                    input: JsonObject,
                    onProgress: (Int, Long?, String) -> Unit,
                ): CourseServerCallResult = error("BOOK_MAP must not trigger streaming pack fetch")

                override suspend fun fetchBookMapBatch(bookId: String, input: JsonObject): CourseServerCallResult =
                    error("Saved map must load from local SQLite with zero server calls")
            }

            var openedAnchor: BookCompanionAnchor? = null
            runSkikoComposeUiTest(size = Size(820f, 1000f)) {
                var pref by mutableStateOf(
                    BookStudyPreference(
                        bookId = book.id,
                        mode = BookStudyMode.BOOK_MAP,
                        userLocked = true,
                        detailLevel = ExplainDetailLevel.BRIEF,
                        explainLang = ExplainLanguage.BILINGUAL,
                    ),
                )
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalReadingAiExplain provides true, LocalV7ReducedMotion provides true) {
                            SingleBookCompanionPanel(
                                book = book,
                                currentPage = 2,
                                currentSectionIndex = 0,
                                explainer = fakeExplainer,
                                library = library,
                                draft = ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-1", page = 2),
                                onDraftChange = {},
                                onOpenAnchor = { openedAnchor = it },
                                studyPreference = pref,
                                onUpdateStudyPreference = { next, _ -> pref = next },
                                onJumpPage = {},
                                onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {},
                                onOpenLogin = {},
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag("book-what-about-map").assertExists() }.isSuccess &&
                        runCatching { onNodeWithTag("book-map-v2-point-0-0-anchor-0", useUnmergedTree = true).assertExists() }.isSuccess
                }
                onNodeWithTag("book-map-v2-point-0-0-anchor-0", useUnmergedTree = true).performClick()
                val jumped = assertNotNull(openedAnchor)
                val targetPage = assertNotNull(book.page(jumped.pdfPage))
                assertTrue(jumped.startUtf16 in 0 until jumped.endUtf16)
                assertEquals(jumped.quoteAnchor, targetPage.text.substring(jumped.startUtf16, jumped.endUtf16))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
