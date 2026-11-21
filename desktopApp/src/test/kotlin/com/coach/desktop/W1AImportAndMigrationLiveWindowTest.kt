package com.coach.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.coach.desktop.books.BookOcr
import com.coach.desktop.books.BookOutlineItem
import com.coach.desktop.books.BookPage
import com.coach.desktop.books.BookPageRole
import com.coach.desktop.books.BookPdfLoader
import com.coach.desktop.books.BookSchemaMigration
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.BookPrompt
import com.coach.desktop.books.BookStructurePlanner
import com.coach.desktop.books.BookTocNode
import com.coach.desktop.books.CollectionKind
import com.coach.desktop.books.CollectionMaterialRecord
import com.coach.desktop.books.HeadingSource
import com.coach.desktop.books.MaterialCollectionRecord
import com.coach.desktop.books.PageLineTypography
import com.coach.desktop.books.PdfPageTextExtraction
import com.coach.desktop.books.PdfWarmup
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.TocNodeKind
import com.coach.desktop.books.extractBookPdfOutline
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.pdfbox.Loader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class W1AImportAndMigrationLiveWindowTest {
    @Test
    fun benchmarkThreeSampleToc() {
        assumeTrue(java.lang.Boolean.getBoolean("coach.w1aTocBench"))
        val sample1Path = Path.of(requireNotNull(System.getProperty("coach.w1aSample1")) { "Missing -Dcoach.w1aSample1" })
        val gtPath = Path.of(requireNotNull(System.getProperty("coach.w1aGroundTruth")) { "Missing -Dcoach.w1aGroundTruth" })
        val paperPath = System.getProperty("coach.w1aSample3Paper")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val slidesPath = System.getProperty("coach.w1aSample3Slides")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val outDir = System.getProperty("coach.w1aOutDir")?.takeIf { it.isNotBlank() }?.let(Path::of)

        val (expectedChapters, expectedSections) = parseGroundTruthIds(gtPath)
        val bookId1 = sha256File(sample1Path)
        val (pages1, typo1, outline1) = extractPdfAll(sample1Path)

        // Sample 1: Stage A (with PDF outline)
        val t1 = System.nanoTime()
        val struct1 = BookStructurePlanner.plan(bookId1, "Sample1", "pdf", pages1, outline1, typo1)
        val s1Ms = (System.nanoTime() - t1) / 1_000_000L
        val eval1 = evaluateAgainstTruth(struct1.tocNodes, struct1.pageRoles, expectedChapters, expectedSections, bodyRange = 32..579)

        // Sample 2: Stage B (PDF outline stripped)
        val strippedFile = Files.createTempFile("coach-stripped-outline-", ".pdf")
        try {
            Loader.loadPDF(sample1Path.toFile()).use { doc ->
                doc.documentCatalog.documentOutline = null
                doc.save(strippedFile.toFile())
            }
            val (_, typo2, outline2) = extractPdfAll(strippedFile)
            assertTrue("Expected empty outline in stripped PDF", outline2.isEmpty())
            val t2 = System.nanoTime()
            val struct2 = BookStructurePlanner.plan("d".repeat(64), "Sample2", "pdf", pages1, outline2, typo2)
            val s2Ms = (System.nanoTime() - t2) / 1_000_000L
            val eval2 = evaluateAgainstTruth(struct2.tocNodes, struct2.pageRoles, expectedChapters, expectedSections, bodyRange = 32..579)

            // Sample 3: No-outline, no-TOC paper + slides
            val paperSummary = paperPath?.let { p ->
                val (pPages, pTypo, pOut) = extractPdfAll(p)
                val s = BookStructurePlanner.plan("e".repeat(64), "Sample3Paper", "pdf", pPages, pOut, pTypo)
                evaluateNoTocSample("paper", s.tocNodes, s.source, s.confidence)
            }
            val slidesSummary = slidesPath?.let { p ->
                val (sPages, sTypo, sOut) = extractPdfAll(p)
                val s = BookStructurePlanner.plan("f".repeat(64), "Sample3Slides", "pdf", sPages, sOut, sTypo)
                evaluateNoTocSample("slides", s.tocNodes, s.source, s.confidence)
            }

            val report = buildJsonObject {
                put("sample1_stageA", buildJsonObject {
                    put("elapsedMs", s1Ms)
                    put("source", struct1.source)
                    put("chaptersMatched", eval1.chaptersMatched)
                    put("chaptersExpected", expectedChapters.size)
                    put("sectionsMatched", eval1.sectionsMatched)
                    put("sectionsExpected", expectedSections.size)
                    put("fragmentCount", eval1.fragmentCount)
                    put("partHierarchyErrors", eval1.partErrors)
                    put("interiorCopyrightErrorPages", buildJsonArray { eval1.interiorCopyrightPages.forEach { add(it) } })
                    put("p277Role", struct1.pageRole(277).name)
                    put("p496Role", struct1.pageRole(496).name)
                    put("missingChapterIds", buildJsonArray { eval1.missingChapterIds.forEach { add(it) } })
                    put("missingSectionIds", buildJsonArray { eval1.missingSectionIds.forEach { add(it) } })
                    put("extraSectionIds", buildJsonArray { eval1.extraSectionIds.forEach { add(it) } })
                })
                put("sample2_stageB_stripped", buildJsonObject {
                    put("elapsedMs", s2Ms)
                    put("source", struct2.source)
                    put("chaptersMatched", eval2.chaptersMatched)
                    put("chaptersExpected", expectedChapters.size)
                    put("chapterRecall", eval2.chapterRecall)
                    put("sectionsMatched", eval2.sectionsMatched)
                    put("sectionsExpected", expectedSections.size)
                    put("sectionRecall", eval2.sectionRecall)
                    put("fragmentCount", eval2.fragmentCount)
                    put("missingChapterIds", buildJsonArray { eval2.missingChapterIds.forEach { add(it) } })
                    put("missingSectionIds", buildJsonArray { eval2.missingSectionIds.forEach { add(it) } })
                })
                paperSummary?.let { put("sample3_paper", it) }
                slidesSummary?.let { put("sample3_slides", it) }
            }
            println("W1A_TOC_BENCH=$report")
            outDir?.let {
                Files.createDirectories(it)
                Files.writeString(it.resolve("toc-diff.json"), report.toString())
            }

            assertEquals("Stage A chapters must match 24/24", expectedChapters.size, eval1.chaptersMatched)
            assertEquals("Stage A sections must match 173/173 (missing=${eval1.missingSectionIds})", expectedSections.size, eval1.sectionsMatched)
            assertEquals("Stage A must emit 0 (k/n) fragments in tocNodes", 0, eval1.fragmentCount)
            assertEquals("Stage A must have 0 Part V hierarchy errors", 0, eval1.partErrors)
            assertEquals("Interior body pages p.32-579 must never be COPYRIGHT (${eval1.interiorCopyrightPages})", 0, eval1.interiorCopyrightPages.size)
            assertTrue("Stage B chapter recall must be >= 0.95 (got ${eval2.chapterRecall})", eval2.chapterRecall >= 0.95)
            assertTrue("Stage B section recall must be >= 0.95 (got ${eval2.sectionRecall})", eval2.sectionRecall >= 0.95)
            paperSummary?.let {
                assertTrue("Paper segmentation >= 4 nodes", it["nodeCount"]!!.jsonPrimitive.int >= 4)
                assertEquals("Paper must have 0 false formula/crossref titles", 0, it["falsePositiveCount"]!!.jsonPrimitive.int)
            }
            slidesSummary?.let {
                assertTrue("Slides segmentation >= 4 nodes", it["nodeCount"]!!.jsonPrimitive.int >= 4)
                assertEquals("Slides must have 0 false formula/crossref titles", 0, it["falsePositiveCount"]!!.jsonPrimitive.int)
            }
        } finally {
            Files.deleteIfExists(strippedFile)
        }
    }

    @Test
    fun winboxRealWindowVerification() {
        assumeTrue(java.lang.Boolean.getBoolean("coach.w1aLiveWindow"))
        assumeTrue(!GraphicsEnvironment.isHeadless())

        val bookPath = Path.of(requireNotNull(System.getProperty("coach.w1aBookPath")) { "Missing -Dcoach.w1aBookPath" })
        val outDir = Path.of(requireNotNull(System.getProperty("coach.w1aOutDir")) { "Missing -Dcoach.w1aOutDir" })
        val searchTerm = System.getProperty("coach.w1aSearchTerm")?.takeIf { it.isNotBlank() } ?: "Chapter"
        Files.createDirectories(outDir)

        val workRoot = Files.createTempDirectory("coach-w1a-live-win-")
        val libDir = workRoot.resolve("library")
        try {
            // 1. Live cold import + Tier 1 searchable + page 173 persist + reopen 0-flash/0-click check
            val emptyShelfFlashes = AtomicInteger(0)
            val library1 = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val controller1 = TextbookController(library1)
            runBlocking { controller1.load() }

            val window1 = edt {
                ComposeWindow().apply {
                    title = "Coach W1-A Cold Import Verification"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1440, screen.width - 40), minOf(920, screen.height - 80))
                    setContent { WindowsTextbookHost(controller1) { null } }
                    isVisible = true
                }
            }

            val importStartNs: Long
            val tier0ReadyNs: Long
            val page1PaintedNs: Long
            val searchableReadyNs: Long
            val searchHitCount: Int
            var coldPage1Outcome = "none"
            var coldPage1RenderMs = -1.0
            try {
                waitFor("initial shelf") { node(window1, "upload-textbook") != null }
                importStartNs = System.nanoTime()
                controller1.launchImport(bookPath)

                waitFor("Tier 0 reader opened", pollMs = 5L) {
                    controller1.state.value.let { !it.busy && it.screen == BookScreen.READER && it.book != null }
                }
                tier0ReadyNs = System.nanoTime()
                waitFor("Page 1 painted in reader", pollMs = 5L) {
                    val b = controller1.state.value.book ?: return@waitFor false
                    val target = "${b.title} ${b.pageLabel(1)}"
                    edt {
                        val all = nodes(window1)
                        all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "textbook-reader" } &&
                            all.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == target } == true }
                    }
                }
                edt { window1.renderImmediately() }
                page1PaintedNs = System.nanoTime()
                library1.renderTraces().lastOrNull { it.page == 1 }?.let {
                    coldPage1Outcome = it.outcome
                    coldPage1RenderMs = it.totalMs
                }
                shot(window1, outDir.resolve("01-cold-import-page1.png"))

                waitFor("Tier 1 full-book index READY", timeoutSeconds = 60, pollMs = 20L) {
                    controller1.state.value.book?.indexState == "READY"
                }
                val openBook = checkNotNull(controller1.state.value.book)
                val searchRes = library1.searchBook(openBook.id, searchTerm)
                searchHitCount = searchRes.hits.size
                searchableReadyNs = System.nanoTime()
                assertTrue("Expected full-book search hits after Tier 1", searchHitCount > 0)
                shot(window1, outDir.resolve("02-tier1-ready.png"))

                // Jump to page 173, persist session and read spot
                runBlocking { controller1.page(173) }
                waitFor("page 173 active", pollMs = 10L) {
                    controller1.state.value.page == 173 &&
                        hasContentDesc(window1, "${openBook.title} ${openBook.pageLabel(173)}")
                }
                library1.saveOpenSession(openBook.id, "READER")
                library1.saveReadSpot(openBook.id, 173, 0)
                shot(window1, outDir.resolve("03-page173-before-close.png"))
            } finally {
                edt { window1.dispose() }
            }

            // Window 2: Clear in-memory PDF cache so reopen reflects true cold-restart timing,
            // while keeping persistent disk bitmap cache and single-connection SQLite snapshot.
            BookPdfLoader.clearCache()
            val reopenStartNs = System.nanoTime()
            val library2 = TextbookLibrary(libDir, streamPdfExtras = true, lazyPdfExtras = true)
            val initLibDoneNs = System.nanoTime()
            val controller2 = TextbookController(library2)
            var loadStartNs = 0L
            val window2 = edt {
                ComposeWindow().apply {
                    title = "Coach W1-A Reopen Verification"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1440, screen.width - 40), minOf(920, screen.height - 80))
                    loadStartNs = System.nanoTime()
                    setContent { WindowsTextbookHost(controller2) { null } }
                    isVisible = true
                }
            }
            val windowCreateDoneNs = System.nanoTime()
            var controllerLoadedNs = 0L
            var firstReaderTagNs = 0L
            val reopenPaintedNs: Long
            val reopenedPage: Int
            var reopenPage173Outcome = "none"
            var reopenPage173RenderMs = -1.0
            try {
                val deadline = System.nanoTime() + 30_000_000_000L
                while (true) {
                    val snap = edt {
                        val all = nodes(window2)
                        val emptyFlash = all.any {
                            it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("书架还是空的") } == true
                        }
                        val hasAsk = all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "textbook-reader" }
                        val st = controller2.state.value
                        val b2 = st.book
                        val targetDesc = if (b2 != null) "${b2.title} ${b2.pageLabel(173)}" else null
                        val hasTarget = targetDesc != null && all.any {
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == targetDesc } == true
                        }
                        Triple(emptyFlash, st to hasAsk, hasAsk && hasTarget)
                    }
                    if (snap.first) emptyShelfFlashes.incrementAndGet()
                    val (st, hasAsk) = snap.second
                    if (st.initialized && controllerLoadedNs == 0L) {
                        controllerLoadedNs = System.nanoTime()
                    }
                    if (hasAsk && firstReaderTagNs == 0L) {
                        firstReaderTagNs = System.nanoTime()
                    }
                    val ready = st.initialized && st.screen == BookScreen.READER && st.page == 173 && st.book != null && snap.third
                    if (ready) break
                    check(System.nanoTime() < deadline) { "Timed out waiting for reopen to page 173" }
                    Thread.sleep(5)
                }
                edt { window2.renderImmediately() }
                reopenPaintedNs = System.nanoTime()
                reopenedPage = controller2.state.value.page
                library2.renderTraces().lastOrNull { it.page == 173 }?.let {
                    reopenPage173Outcome = it.outcome
                    reopenPage173RenderMs = it.totalMs
                }
                shot(window2, outDir.resolve("04-reopen-page173.png"))
            } finally {
                edt { window2.dispose() }
            }

            val tier0Ms = (tier0ReadyNs - importStartNs) / 1_000_000.0
            val page1PaintedMs = (page1PaintedNs - importStartNs) / 1_000_000.0
            val searchableMs = (searchableReadyNs - importStartNs) / 1_000_000.0
            val reopenMs = (reopenPaintedNs - reopenStartNs) / 1_000_000.0
            val loadToPage173PaintedMs = (reopenPaintedNs - loadStartNs) / 1_000_000.0
            val reopenInitLibMs = (initLibDoneNs - reopenStartNs) / 1_000_000.0
            val reopenWindowCreateMs = (windowCreateDoneNs - initLibDoneNs) / 1_000_000.0
            val reopenControllerLoadedMs = if (controllerLoadedNs > 0L) (controllerLoadedNs - loadStartNs) / 1_000_000.0 else -1.0
            val reopenFirstReaderTagMs = if (firstReaderTagNs > 0L) (firstReaderTagNs - loadStartNs) / 1_000_000.0 else -1.0
            val pdfWarmupMs = if (PdfWarmup.done.isDone) runCatching { PdfWarmup.done.get() }.getOrDefault(-1L) else -1L

            val timingsJson = buildJsonObject {
                put("tier0Ms", tier0Ms)
                put("page1PaintedMs", page1PaintedMs)
                put("searchableMs", searchableMs)
                put("searchHitCount", searchHitCount)
                put("reopenMs", reopenMs)
                put("loadToPage173PaintedMs", loadToPage173PaintedMs)
                put("reopenInitLibMs", reopenInitLibMs)
                put("reopenWindowCreateMs", reopenWindowCreateMs)
                put("reopenControllerLoadedMs", reopenControllerLoadedMs)
                put("reopenFirstReaderTagMs", reopenFirstReaderTagMs)
                put("coldPage1Outcome", coldPage1Outcome)
                put("coldPage1RenderMs", coldPage1RenderMs)
                put("reopenPage173Outcome", reopenPage173Outcome)
                put("reopenPage173RenderMs", reopenPage173RenderMs)
                put("pdfWarmupMs", pdfWarmupMs)
                put("emptyShelfFlashCount", emptyShelfFlashes.get())
                put("reopenClicks", 0)
                put("reopenPage", reopenedPage)
            }
            Files.writeString(outDir.resolve("timings.json"), timingsJson.toString())

            // 2. Also capture TOC diff (Stage A + stripped Stage B) on the winbox PDF (no text excerpts)
            val (pages, typo, outline) = extractPdfAll(bookPath)
            val structA = BookStructurePlanner.plan(sha256File(bookPath), "Sample1", "pdf", pages, outline, typo)
            val strippedTmp = Files.createTempFile("coach-w1a-stripped-", ".pdf")
            try {
                Loader.loadPDF(bookPath.toFile()).use { doc ->
                    doc.documentCatalog.documentOutline = null
                    doc.save(strippedTmp.toFile())
                }
                val (_, typoB, outlineB) = extractPdfAll(strippedTmp)
                val structB = BookStructurePlanner.plan("b".repeat(64), "Sample2", "pdf", pages, outlineB, typoB)
                val chA = structA.tocNodes.count { it.kind == TocNodeKind.CHAPTER }
                val secA = structA.tocNodes.count { it.kind == TocNodeKind.SECTION }
                val chB = structB.tocNodes.count { it.kind == TocNodeKind.CHAPTER }
                val secB = structB.tocNodes.count { it.kind == TocNodeKind.SECTION }
                val fragA = structA.tocNodes.count { Regex("""（\d+/\d+）$""").containsMatchIn(it.title) }
                val interiorCopyright = (32..579).filter { structA.pageRole(it) == BookPageRole.COPYRIGHT }
                val tocDiffJson = buildJsonObject {
                    put("stageA", buildJsonObject {
                        put("chapters", chA); put("sections", secA); put("fragments", fragA)
                        put("p277Role", structA.pageRole(277).name); put("p496Role", structA.pageRole(496).name)
                        put("interiorCopyrightPages", buildJsonArray { interiorCopyright.forEach { add(it) } })
                    })
                    put("stageB_stripped", buildJsonObject {
                        put("chapters", chB); put("sections", secB)
                    })
                }
                Files.writeString(outDir.resolve("toc-diff.json"), tocDiffJson.toString())
            } finally {
                Files.deleteIfExists(strippedTmp)
            }

            // 3. Record 1.1.1 -> v2 SQLite migration SHA-256 verification
            val migJson = runMigrationShaVerification(workRoot.resolve("mig-check"))
            Files.writeString(outDir.resolve("migration-sha256.json"), migJson.toString())

            println("W1A_LIVE_TIMINGS=$timingsJson")
            assertEquals("Expected 0 empty-shelf flashes on reopen", 0, emptyShelfFlashes.get())
            assertEquals("Expected reopen directly to page 173", 173, reopenedPage)
            assertTrue("Expected page1PaintedMs <= 3000ms (got $page1PaintedMs)", page1PaintedMs <= 3000.0)
            assertTrue("Expected searchableMs <= 15000ms (got $searchableMs)", searchableMs <= 15000.0)
            assertTrue("Expected reopenMs <= 500ms (got $reopenMs)", reopenMs <= 500.0)
        } finally {
            workRoot.toFile().deleteRecursively()
        }
    }

    private fun runMigrationShaVerification(dir: Path): JsonObject {
        Files.createDirectories(dir)
        val db = dir.resolve("library.sqlite")
        val examsDb = dir.resolve("exams.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$examsDb").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE exams(id TEXT PRIMARY KEY,course TEXT NOT NULL,score INTEGER NOT NULL)")
                s.execute("INSERT INTO exams VALUES('ex-1','COURSE-101',88)")
            }
        }
        val examsBeforeSha = sha256File(examsDb)
        val id = "a".repeat(64)
        val id2 = "b".repeat(64)
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { s ->
                s.execute("PRAGMA user_version = 1")
                s.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
                s.execute("INSERT INTO books VALUES('$id','V1 Material','pdf',173,2,1,1,'CHOICE',1700000000)")
                s.execute("CREATE TABLE pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,ocr INTEGER NOT NULL DEFAULT 0,ocr_pending INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,page))")
                s.execute("INSERT INTO pages VALUES('$id',1,'First page text',0,0)")
                s.execute("INSERT INTO pages VALUES('$id',173,'Target page text for verification',0,0)")
                s.execute("CREATE TABLE answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                s.execute("INSERT INTO answers VALUES('$id','les-1','CHOICE','A',1,NULL,0)")
                s.execute("CREATE TABLE book_reviews(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,completed INTEGER NOT NULL DEFAULT 0,guessed INTEGER NOT NULL DEFAULT 0,sources INTEGER NOT NULL DEFAULT 1,due_day INTEGER,streak INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                s.execute("INSERT INTO book_reviews VALUES('$id','les-1','CHOICE','V1 Material','Chapter 1','Q?','Ref','B','[\"A\",\"B\"]','A','B',1,NULL,1,0,1,20000,1)")
                s.execute("CREATE TABLE book_review_history(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL,answered_day INTEGER)")
                s.execute("INSERT INTO book_review_history VALUES('$id','les-1','CHOICE','LESSON','B',1,NULL,0,20000)")
                s.execute("CREATE TABLE book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)")
                s.execute("INSERT INTO book_review_position VALUES(1,'$id','les-1','CHOICE')")
                s.execute("CREATE TABLE book_open_session(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,screen TEXT NOT NULL CHECK(screen IN ('READER','LEARNING')))")
                s.execute("INSERT INTO book_open_session VALUES(1,'$id','READER')")
                s.execute("CREATE TABLE book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))")
                s.execute("INSERT INTO book_learning_paths VALUES('$id','READ_AND_LEARN')")
                s.execute("CREATE TABLE book_ladders(book TEXT PRIMARY KEY,session TEXT NOT NULL)")
                s.execute("INSERT INTO book_ladders VALUES('$id','{}')")
                s.execute("CREATE TABLE book_reader_views(book TEXT PRIMARY KEY,text_view INTEGER NOT NULL)")
                s.execute("INSERT INTO book_reader_views VALUES('$id',0)")
                s.execute("CREATE TABLE book_pdf_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                s.execute("INSERT INTO book_pdf_modes VALUES('$id',0)")
                s.execute("CREATE TABLE book_text_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                s.execute("INSERT INTO book_text_modes VALUES('$id',1)")
                s.execute("CREATE TABLE book_read_spots(book TEXT PRIMARY KEY,page INTEGER NOT NULL,offset_utf16 INTEGER NOT NULL)")
                s.execute("INSERT INTO book_read_spots VALUES('$id',173,42)")
                s.execute("CREATE TABLE book_highlights(book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,PRIMARY KEY(book,page,start,end))")
                s.execute("INSERT INTO book_highlights VALUES('$id',173,0,6,'Target')")
                s.execute("CREATE TABLE book_notes(book TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,note TEXT NOT NULL,text_sha256 TEXT NOT NULL,revision INTEGER NOT NULL,last_mutation_id TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,id))")
                s.execute("INSERT INTO book_notes VALUES('$id','n1','v1',173,0,6,'Target','Saved note','$id2',1,'m1',0)")
                s.execute("CREATE TABLE reading_settings(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
                s.execute("INSERT INTO reading_settings VALUES('font','SERIF')")
            }
        }
        fun tableHash(table: String, cols: String): Pair<Int, String> {
            DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
                var cnt = 0
                val text = buildString {
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT $cols FROM $table ORDER BY $cols").use { rs ->
                            val md = rs.metaData.columnCount
                            while (rs.next()) {
                                cnt++
                                for (i in 1..md) append(rs.getObject(i)?.toString() ?: "<NULL>").append('|')
                                append('\n')
                            }
                        }
                    }
                }
                return cnt to MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        }
        val tables = linkedMapOf(
            "books" to "id,title,format,reading_page,lesson_index,learning,prompt_seen,mode,touched",
            "pages" to "book,page,text",
            "answers" to "book,lesson,mode,text,revealed,understood,guessed",
            "book_reviews" to "book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,answer_text,revealed,understood,completed,guessed,sources,due_day,streak",
            "book_review_history" to "book,lesson,mode,kind,text,revealed,understood,guessed,answered_day",
            "book_review_position" to "slot,book,lesson,mode",
            "book_open_session" to "slot,book,screen",
            "book_learning_paths" to "book,path",
            "book_ladders" to "book,session",
            "book_reader_views" to "book,text_view",
            "book_pdf_modes" to "book,scrolling",
            "book_text_modes" to "book,scrolling",
            "book_read_spots" to "book,page,offset_utf16",
            "book_highlights" to "book,page,start,end,quote",
            "book_notes" to "book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted",
            "reading_settings" to "name,value",
        )
        val before = tables.mapValues { (t, cols) -> tableHash(t, cols) }
        TextbookLibrary(dir, streamPdfExtras = true, lazyPdfExtras = true)
        val after = tables.mapValues { (t, cols) -> tableHash(t, cols) }
        val backupOk = Files.isRegularFile(dir.resolve(BookSchemaMigration.V1_BACKUP_FILENAME)) &&
            BookSchemaMigration.isHealthySqlite(dir.resolve(BookSchemaMigration.V1_BACKUP_FILENAME))
        val examsUntouched = sha256File(examsDb) == examsBeforeSha
        return buildJsonObject {
            put("backupValid", backupOk)
            put("examsUntouched", examsUntouched)
            put("allShaMatch", before.all { (k, v) -> after[k] == v })
            put("tables", buildJsonObject {
                before.forEach { (t, v) ->
                    put(t, buildJsonObject {
                        put("countBefore", v.first); put("countAfter", after[t]!!.first)
                        put("sha256Before", v.second); put("sha256After", after[t]!!.second)
                    })
                }
            })
        }
    }

    private fun extractPdfAll(path: Path): Triple<List<BookPage>, Map<Int, List<PageLineTypography>>, List<BookOutlineItem>> {
        return BookPdfLoader.read(path.toFile()) { pdf ->
            val outline = extractBookPdfOutline(pdf)
            val pages = ArrayList<BookPage>(pdf.numberOfPages)
            val typo = LinkedHashMap<Int, List<PageLineTypography>>()
            for (n in 1..pdf.numberOfPages) {
                val fast = PdfPageTextExtraction.extractPlainTextAndTypography(pdf, n)
                pages += BookPage(n, fast.text)
                if (fast.lines.isNotEmpty()) typo[n] = fast.lines
            }
            Triple(pages, typo, outline)
        }
    }

    private fun parseGroundTruthIds(gtPath: Path): Pair<List<String>, Set<String>> {
        val arr = Json.parseToJsonElement(Files.readString(gtPath)).jsonArray
        val chapters = mutableListOf<String>()
        val sections = linkedSetOf<String>()
        for (el in arr) {
            val obj = el.jsonObject
            chapters += obj.getValue("chapter").jsonPrimitive.int.toString()
            for (sec in obj.getValue("sections").jsonArray) {
                sections += sec.jsonObject.getValue("id").jsonPrimitive.content
            }
        }
        // 18.3 wraps across 2 lines in the printed TOC; both PDF outline & printed TOC contain it.
        sections += "18.3"
        return chapters to sections
    }

    private data class TocEval(
        val chaptersMatched: Int,
        val chapterRecall: Double,
        val sectionsMatched: Int,
        val sectionRecall: Double,
        val fragmentCount: Int,
        val partErrors: Int,
        val interiorCopyrightPages: List<Int>,
        val missingChapterIds: List<String>,
        val missingSectionIds: List<String>,
        val extraSectionIds: List<String>,
    )

    private fun evaluateAgainstTruth(
        nodes: List<BookTocNode>,
        pageRoles: Map<Int, BookPageRole>,
        expectedChapters: List<String>,
        expectedSections: Set<String>,
        bodyRange: IntRange,
    ): TocEval {
        val actualChLabels = nodes.filter { it.kind == TocNodeKind.CHAPTER }.mapNotNull { it.numberLabel }.toSet()
        val actualSecLabels = nodes.filter { it.kind == TocNodeKind.SECTION }.mapNotNull { it.numberLabel }.toSet()
        val missingCh = expectedChapters.filter { it !in actualChLabels }
        val missingSec = expectedSections.filter { it !in actualSecLabels }
        val extraSec = actualSecLabels.filter { it !in expectedSections }
        val frags = nodes.count { Regex("""（\d+/\d+）$""").containsMatchIn(it.title) }
        val partErrors = nodes.filter { it.kind == TocNodeKind.PART }.count { it.level != 1 || it.parentId != null } +
            nodes.filter { it.kind == TocNodeKind.BACK_MATTER }.count { it.level != 1 || it.parentId != null }
        val badCopy = bodyRange.filter { pageRoles[it] == BookPageRole.COPYRIGHT }
        val chMatched = expectedChapters.size - missingCh.size
        val secMatched = expectedSections.size - missingSec.size
        return TocEval(
            chaptersMatched = chMatched,
            chapterRecall = chMatched.toDouble() / expectedChapters.size,
            sectionsMatched = secMatched,
            sectionRecall = secMatched.toDouble() / expectedSections.size,
            fragmentCount = frags,
            partErrors = partErrors,
            interiorCopyrightPages = badCopy,
            missingChapterIds = missingCh,
            missingSectionIds = missingSec,
            extraSectionIds = extraSec,
        )
    }

    private fun evaluateNoTocSample(kind: String, nodes: List<BookTocNode>, source: String, confidence: Double): JsonObject {
        val fakePattern = Regex("""^(?:0\.\d+|[=<>]|.*https?://|.*@|See\s+(?:Section|Chapter|Table|Figure)\b)""", RegexOption.IGNORE_CASE)
        val fp = nodes.count { fakePattern.containsMatchIn(it.title) }
        return buildJsonObject {
            put("kind", kind)
            put("source", source)
            put("confidence", confidence)
            put("nodeCount", nodes.size)
            put("falsePositiveCount", fp)
        }
    }

    private fun sha256File(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> =
        window.ownedWindows.filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }

    private fun node(window: ComposeWindow, tag: String) =
        edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }

    private fun hasContentDesc(window: ComposeWindow, exact: String) = edt {
        nodes(window).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == exact } == true }
    }

    private fun click(window: ComposeWindow, tag: String) {
        waitFor(tag) { node(window, tag) != null }
        edt { assertTrue(tag, node(window, tag)!!.config[SemanticsActions.OnClick].action!!.invoke()) }
        Thread.sleep(20)
    }

    private fun shot(window: ComposeWindow, target: Path) {
        Thread.sleep(200)
        edt {
            window.renderImmediately()
            VerifyHooks.capture(window, target.toFile())
        }
    }

    private fun waitFor(label: String, timeoutSeconds: Long = 30, pollMs: Long = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $label" }
            Thread.sleep(pollMs)
        }
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
