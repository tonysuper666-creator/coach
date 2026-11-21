package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.assertFailsWith

class BookMigrationV2Test {
    private val shaBookA = "a".repeat(64)
    private val shaBookB = "b".repeat(64)

    @Test
    fun upgradesV111LibraryLosslesslyWithBackupExamsUntouchedAndCollectionSharing() {
        val dir = Files.createTempDirectory("coach-v111-upgrade-")
        try {
            val libraryDb = dir.resolve("library.sqlite")
            val examsDb = dir.resolve("exams.sqlite")
            seedFullV111Database(libraryDb)
            seedExamsDatabase(examsDb)

            val preTables = listOf(
                "books" to listOf("id", "title", "format", "reading_page", "lesson_index", "learning", "prompt_seen", "mode", "touched"),
                "pages" to listOf("book", "page", "text"),
                "answers" to listOf("book", "lesson", "mode", "text", "revealed", "understood", "guessed"),
                "book_reviews" to listOf("book", "lesson", "mode", "book_title", "lesson_title", "prompt", "reference", "solution", "options", "wrong_text", "answer_text", "revealed", "understood", "completed", "guessed", "sources", "due_day", "streak"),
                "book_review_history" to listOf("book", "lesson", "mode", "kind", "text", "revealed", "understood", "guessed", "answered_day"),
                "book_review_position" to listOf("slot", "book", "lesson", "mode"),
                "book_open_session" to listOf("slot", "book", "screen"),
                "book_learning_paths" to listOf("book", "path"),
                "book_ladders" to listOf("book", "session"),
                "book_reader_views" to listOf("book", "text_view"),
                "book_pdf_modes" to listOf("book", "scrolling"),
                "book_text_modes" to listOf("book", "scrolling"),
                "book_read_spots" to listOf("book", "page", "offset_utf16"),
                "book_highlights" to listOf("book", "page", "start", "end", "quote"),
                "book_notes" to listOf("book", "id", "version", "page", "start", "end", "quote", "note", "text_sha256", "revision", "last_mutation_id", "deleted"),
                "reading_settings" to listOf("name", "value"),
                "book_illustrations" to listOf("book", "page", "ordinal", "anchor_offset", "image_sha256", "extension", "width", "height", "caption", "kind", "source_start", "source_end", "region_x", "region_y", "region_w", "region_h", "table_rows"),
                "book_page_text_boxes" to listOf("book", "page", "page_width", "page_height", "boxes"),
                "book_native_sources" to listOf("book", "payload"),
                "book_structures" to listOf("book", "version", "payload"),
                "book_section_cache" to listOf("book", "section_id", "kind", "payload", "updated_at"),
            )

            val beforeDigests = snapshotTables(libraryDb, preTables)
            val beforeExamsSha = sha256(Files.readAllBytes(examsDb))

            val library = TextbookLibrary(dir, streamPdfExtras = true, lazyPdfExtras = true)

            val backupFile = dir.resolve(BookSchemaMigration.V1_BACKUP_FILENAME)
            assertTrue("Backup library.sqlite.v1.bak should exist", Files.isRegularFile(backupFile))
            assertTrue("Backup should pass PRAGMA quick_check", BookSchemaMigration.isHealthySqlite(backupFile))
            DriverManager.getConnection("jdbc:sqlite:$libraryDb").use { conn ->
                assertEquals(BookSchemaMigration.CURRENT_SCHEMA_VERSION, BookSchemaMigration.readUserVersion(conn))
                for (v2Table in listOf("collections", "collection_materials", "book_toc_nodes", "book_study_chunks", "book_bookmarks", "book_page_aspects", "book_visual_pages", "pages_fts")) {
                    assertTrue("Missing v2 table $v2Table", BookSchemaMigration.tableExists(conn, v2Table))
                }
            }

            val afterDigests = snapshotTables(libraryDb, preTables)
            for ((table, before) in beforeDigests) {
                val after = afterDigests[table]
                assertNotNull("Missing table $table after migration", after)
                assertEquals("Row count mismatch on $table", before.first, after!!.first)
                assertEquals("Content SHA-256 mismatch on $table", before.second, after.second)
            }
            assertEquals("companion exams.sqlite must be untouched", beforeExamsSha, sha256(Files.readAllBytes(examsDb)))

            val loadedBook = library.book(shaBookA)
            assertEquals(173, loadedBook.readingPage)
            assertEquals("Existing Note", library.allNotes(shaBookA).single().note)
            assertEquals(1, library.allHighlights(shaBookA).size)
            assertEquals(173 to 42, library.savedReadSpot(shaBookA))
            assertEquals(shaBookA to "READER", library.openSession())
            assertFalse(library.pdfScrollMode(shaBookA))
            assertTrue(library.textScrollMode(shaBookA))

            // FTS5 trigram search immediately finds migrated v1 pages.
            val searchHits = library.searchBook(shaBookA, "marginal utility")
            assertEquals(1, searchHits.hits.size)
            assertEquals(173, searchHits.hits.single().page)

            // Single material referenced by multiple collections (Course + Booklist) for 2.0-R readiness.
            library.upsertCollection(MaterialCollectionRecord("course-econ-101", CollectionKind.COURSE, "Applied Microeconomics Course"))
            library.upsertCollection(MaterialCollectionRecord("booklist-autumn", CollectionKind.BOOKLIST, "Autumn Reading List"))
            library.attachMaterial(CollectionMaterialRecord("course-econ-101", shaBookA, role = "TEXTBOOK", ordinal = 0))
            library.attachMaterial(CollectionMaterialRecord("booklist-autumn", shaBookA, role = "REFERENCE", ordinal = 0))
            assertEquals(2, library.collections().size)
            assertEquals(shaBookA, library.collectionMaterials("course-econ-101").single().materialId)
            assertEquals(shaBookA, library.collectionMaterials("booklist-autumn").single().materialId)
            assertEquals(1, library.catalog().size)

            // Bookmarks table schema matches PR #570 BookBookmarkStore contract cleanly.
            DriverManager.getConnection("jdbc:sqlite:$libraryDb").use { conn ->
                conn.prepareStatement("INSERT INTO book_bookmarks(book,page,note,created_at) VALUES(?,?,?,?)").use { sql ->
                    sql.setString(1, shaBookA); sql.setInt(2, 173); sql.setString(3, "Key Theorem"); sql.setLong(4, 1700000001L)
                    sql.executeUpdate()
                }
                val note = conn.prepareStatement("SELECT note FROM book_bookmarks WHERE book=? AND page=?").use { sql ->
                    sql.setString(1, shaBookA); sql.setInt(2, 173)
                    sql.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
                assertEquals("Key Theorem", note)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rollbackLeavesHealthyDatabaseAndPreservesBackupOnMigrationError() {
        val dir = Files.createTempDirectory("coach-v111-rollback-")
        try {
            val libraryDb = dir.resolve("library.sqlite")
            seedFullV111Database(libraryDb)
            val preCount = countRows(libraryDb, "books")

            assertFailsWith<IllegalStateException> {
                BookSchemaMigration.upgradeAndMigrate(
                    directory = dir,
                    openConnection = { DriverManager.getConnection("jdbc:sqlite:$libraryDb") },
                    onInTransaction = { db ->
                        db.createStatement().use { it.execute("DELETE FROM books") }
                        throw IllegalStateException("Simulated migration failure")
                    },
                )
            }

            assertTrue(Files.isRegularFile(dir.resolve(BookSchemaMigration.V1_BACKUP_FILENAME)))
            assertTrue(BookSchemaMigration.isHealthySqlite(libraryDb))
            assertEquals(preCount, countRows(libraryDb, "books"))
            DriverManager.getConnection("jdbc:sqlite:$libraryDb").use { conn ->
                assertEquals(1, BookSchemaMigration.readUserVersion(conn))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun v1PdfLibraryUpgradeRebuildsCanonicalTocInBackgroundOnLoad() {
        val dir = Files.createTempDirectory("coach-v111-toc-rebuild-")
        try {
            val shaBookC = "c".repeat(64)
            writeSyntheticMultiChapterPdf(dir.resolve("$shaBookA.pdf"), title = "Synthetic Outline V1 Book A", includeOutline = true)
            writeSyntheticMultiChapterPdf(dir.resolve("$shaBookB.pdf"), title = "Synthetic Outline V1 Book B", includeOutline = true)

            val libraryDb = dir.resolve("library.sqlite")
            val expectedPage4Text = "Equilibrium occurs when marginal benefit equals marginal cost on page 4."
            DriverManager.getConnection("jdbc:sqlite:$libraryDb").use { conn ->
                conn.createStatement().use { sql ->
                    sql.execute("PRAGMA user_version = 1")
                    sql.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
                    sql.execute("INSERT INTO books VALUES('$shaBookA','Synthetic Outline V1 Book A','pdf',4,0,0,0,'CHOICE',1700000300)")
                    sql.execute("INSERT INTO books VALUES('$shaBookB','Synthetic Outline V1 Book B','pdf',2,0,0,0,'CHOICE',1700000200)")
                    sql.execute("INSERT INTO books VALUES('$shaBookC','Synthetic Missing Pdf Book C','pdf',1,0,0,0,'CHOICE',1700000100)")

                    sql.execute("CREATE TABLE pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,ocr INTEGER NOT NULL DEFAULT 0,ocr_pending INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,page))")
                    for (p in 1..6) {
                        val txtA = if (p == 4) expectedPage4Text else "Synthetic body prose on page $p for outline book A."
                        sql.execute("INSERT INTO pages VALUES('$shaBookA',$p,'$txtA',0,0)")
                        sql.execute("INSERT INTO pages VALUES('$shaBookB',$p,'Synthetic body prose on page $p for outline book B.',0,0)")
                    }
                    sql.execute("INSERT INTO pages VALUES('$shaBookC',1,'Chapter 1 Foundations\n\nScarcity forces rational agents to weigh marginal benefit against opportunity cost across periods.',0,0)")
                    sql.execute("INSERT INTO pages VALUES('$shaBookC',2,'Chapter 2 Incentives\n\nPrices coordinate decentralized allocation across competitive markets and production chains.',0,0)")
                    sql.execute("INSERT INTO pages VALUES('$shaBookC',3,'Chapter 3 Equilibrium\n\nMarket clearing occurs where quantity demanded equals quantity supplied at stable prices.',0,0)")

                    sql.execute("CREATE TABLE answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                    sql.execute("CREATE TABLE book_reviews(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,completed INTEGER NOT NULL DEFAULT 0,guessed INTEGER NOT NULL DEFAULT 0,sources INTEGER NOT NULL DEFAULT 1,due_day INTEGER,streak INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                    sql.execute("CREATE TABLE book_review_history(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL,answered_day INTEGER)")
                    sql.execute("CREATE TABLE book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)")
                    sql.execute("CREATE TABLE book_open_session(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,screen TEXT NOT NULL CHECK(screen IN ('READER','LEARNING')))")
                    sql.execute("CREATE TABLE book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))")
                    sql.execute("CREATE TABLE book_ladders(book TEXT PRIMARY KEY,session TEXT NOT NULL)")
                    sql.execute("CREATE TABLE book_reader_views(book TEXT PRIMARY KEY,text_view INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_pdf_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_text_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_read_spots(book TEXT PRIMARY KEY,page INTEGER NOT NULL,offset_utf16 INTEGER NOT NULL)")
                    sql.execute("INSERT INTO book_read_spots VALUES('$shaBookA',4,12)")
                    sql.execute("CREATE TABLE book_highlights(book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,PRIMARY KEY(book,page,start,end))")
                    sql.execute("INSERT INTO book_highlights VALUES('$shaBookA',4,0,11,'Equilibrium')")
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,note TEXT NOT NULL,text_sha256 TEXT NOT NULL,revision INTEGER NOT NULL,last_mutation_id TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,id))")
                    sql.execute("INSERT INTO book_notes VALUES('$shaBookA','note-v1','v1',4,0,11,'Equilibrium','Preserve UTF-16 anchor','$shaBookB',1,'mut-1',0)")
                    sql.execute("CREATE TABLE reading_settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    sql.execute("CREATE TABLE book_structures(book TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)")
                    sql.execute("INSERT INTO book_structures VALUES('$shaBookA',1,'{\"version\":1,\"source\":\"HEADING_RULE\",\"modelAttempted\":false,\"pageRoles\":{},\"sections\":[]}')")
                }
            }

            val library = TextbookLibrary(dir, streamPdfExtras = true, lazyPdfExtras = true)
            assertTrue(Files.isRegularFile(dir.resolve(BookSchemaMigration.V1_BACKUP_FILENAME)))
            assertEquals(listOf("PENDING_TOC", "PENDING_TOC", "PENDING_TOC"), library.catalog().map { it.indexState })

            val controller = TextbookController(library)
            runBlocking { controller.load() }

            val deadline = System.nanoTime() + 15_000_000_000L
            while (controller.state.value.book?.indexState != "READY" ||
                controller.state.value.catalog.any { it.indexState != "READY" }
            ) {
                assertTrue("Timed out waiting for legacy v1 PDFs to finish background TOC rebuild", System.nanoTime() < deadline)
                Thread.sleep(30)
            }

            val openBook = controller.state.value.book!!
            assertEquals(shaBookA, openBook.id)
            assertEquals("READY", openBook.indexState)
            assertEquals(4, openBook.readingPage)
            assertTrue("Expected 3 chapters + 2 subsections for v1 PDF", openBook.tocNodes.size >= 5)
            assertFalse("Canonical TOC must contain zero (k/n) fragments", openBook.tocNodes.any { Regex("""\(\d+/\d+\)""").containsMatchIn(it.title) })
            assertEquals(listOf("1", "2", "3"), openBook.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }.mapNotNull { it.numberLabel })

            assertEquals(listOf(shaBookA, shaBookB, shaBookC), library.catalog().map { it.id })
            assertEquals(expectedPage4Text, library.book(shaBookA).page(4)!!.text)
            assertEquals("Equilibrium", library.allHighlights(shaBookA).single().quote)
            assertEquals("Preserve UTF-16 anchor", library.allNotes(shaBookA).single().note)
            assertEquals(4 to 12, library.savedReadSpot(shaBookA))

            val bookB = library.book(shaBookB)
            assertEquals("READY", bookB.indexState)
            assertTrue(bookB.tocNodes.size >= 5)

            val bookC = library.book(shaBookC)
            assertEquals("READY", bookC.indexState)
            assertTrue("Missing-PDF v1 fallback should still build canonical tocNodes", bookC.tocNodes.isNotEmpty())

            DriverManager.getConnection("jdbc:sqlite:$libraryDb").use { conn ->
                assertTrue(countBookRows(conn, "book_toc_nodes", shaBookA) >= 5)
                assertTrue(countBookRows(conn, "book_study_chunks", shaBookA) > 0)
                assertEquals(6, countBookRows(conn, "book_page_aspects", shaBookA))
                assertTrue(countBookRows(conn, "book_toc_nodes", shaBookB) >= 5)
                assertTrue(countBookRows(conn, "book_toc_nodes", shaBookC) > 0)
            }

            // Re-opening fresh TextbookLibrary loads rebuilt TOC directly from SQLite.
            val reopened = TextbookLibrary(dir, streamPdfExtras = true, lazyPdfExtras = true).book(shaBookA)
            assertEquals("READY", reopened.indexState)
            assertTrue(reopened.tocNodes.size >= 5)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun v1NoOutlinePdfUpgradeRebuildsCanonicalTocUsingTypographyWithoutMutatingPageText() {
        val dir = Files.createTempDirectory("coach-v111-no-outline-rebuild-")
        try {
            writeSyntheticMultiChapterPdf(dir.resolve("$shaBookA.pdf"), title = "Synthetic Typography V1 Book", includeOutline = false)
            val libraryDb = dir.resolve("library.sqlite")
            val customPages = mapOf(
                1 to "1 Foundations\nSENTINEL_V1_OFFSET_PRESERVED_P1\nScarcity forces clear tradeoffs among competing uses.",
                2 to "SENTINEL_V1_OFFSET_PRESERVED_P2\nOpportunity cost measures the next best foregone alternative.",
                3 to "2 Incentives\nSENTINEL_V1_OFFSET_PRESERVED_P3\nMarginal incentives guide rational household and firm choices.",
                4 to "SENTINEL_V1_OFFSET_PRESERVED_P4\nSupply shifts reflect input price changes and technology.",
                5 to "3 Equilibrium\nSENTINEL_V1_OFFSET_PRESERVED_P5\nMarket clearing balances quantity supplied and demanded.",
                6 to "SENTINEL_V1_OFFSET_PRESERVED_P6\nConsumer and producer surplus measure total gains from trade.",
            )
            DriverManager.getConnection("jdbc:sqlite:$libraryDb").use { conn ->
                conn.createStatement().use { sql ->
                    sql.execute("PRAGMA user_version = 1")
                    sql.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
                    sql.execute("INSERT INTO books VALUES('$shaBookA','Synthetic Typography V1 Book','pdf',3,0,0,0,'CHOICE',1700000500)")
                    sql.execute("CREATE TABLE pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,ocr INTEGER NOT NULL DEFAULT 0,ocr_pending INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,page))")
                }
                conn.prepareStatement("INSERT INTO pages VALUES(?,?,?,0,0)").use { sql ->
                    for ((p, txt) in customPages) {
                        sql.setString(1, shaBookA); sql.setInt(2, p); sql.setString(3, txt)
                        sql.executeUpdate()
                    }
                }
                conn.createStatement().use { sql ->
                    sql.execute("CREATE TABLE answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                    sql.execute("CREATE TABLE book_reviews(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,completed INTEGER NOT NULL DEFAULT 0,guessed INTEGER NOT NULL DEFAULT 0,sources INTEGER NOT NULL DEFAULT 1,due_day INTEGER,streak INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                    sql.execute("CREATE TABLE book_review_history(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL,answered_day INTEGER)")
                    sql.execute("CREATE TABLE book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)")
                    sql.execute("CREATE TABLE book_open_session(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,screen TEXT NOT NULL CHECK(screen IN ('READER','LEARNING')))")
                    sql.execute("CREATE TABLE book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))")
                    sql.execute("CREATE TABLE book_ladders(book TEXT PRIMARY KEY,session TEXT NOT NULL)")
                    sql.execute("CREATE TABLE book_reader_views(book TEXT PRIMARY KEY,text_view INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_pdf_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_text_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_read_spots(book TEXT PRIMARY KEY,page INTEGER NOT NULL,offset_utf16 INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE book_highlights(book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,PRIMARY KEY(book,page,start,end))")
                    sql.execute("INSERT INTO book_highlights VALUES('$shaBookA',3,13,44,'SENTINEL_V1_OFFSET_PRESERVED_P3')")
                    sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,note TEXT NOT NULL,text_sha256 TEXT NOT NULL,revision INTEGER NOT NULL,last_mutation_id TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,id))")
                    sql.execute("CREATE TABLE reading_settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                }
            }

            val library = TextbookLibrary(dir, streamPdfExtras = true, lazyPdfExtras = true)
            assertEquals("PENDING_TOC", library.catalog().single().indexState)

            val controller = TextbookController(library)
            runBlocking { controller.load() }

            val deadline = System.nanoTime() + 15_000_000_000L
            while (controller.state.value.book?.indexState != "READY") {
                assertTrue("Timed out rebuilding no-outline v1 PDF", System.nanoTime() < deadline)
                Thread.sleep(30)
            }

            val rebuilt = controller.state.value.book!!
            assertTrue("Expected >= 3 chapter/section nodes from typography + headings", rebuilt.tocNodes.size >= 3)
            for ((p, expectedText) in customPages) {
                assertEquals("Legacy page $p text must stay byte-for-byte identical", expectedText, rebuilt.page(p)!!.text)
            }
            DriverManager.getConnection("jdbc:sqlite:$libraryDb").use { conn ->
                assertTrue(countBookRows(conn, "book_toc_nodes", shaBookA) >= 3)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun countBookRows(conn: java.sql.Connection, table: String, bookId: String): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { row -> if (row.next()) row.getInt(1) else 0 }
        }

    private fun writeSyntheticMultiChapterPdf(target: Path, title: String, includeOutline: Boolean) {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = title
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val outline = if (includeOutline) PDDocumentOutline().also { pdf.documentCatalog.documentOutline = it } else null

            val chapterStarts = mapOf(
                1 to "1 Foundations",
                3 to "2 Incentives",
                5 to "3 Equilibrium",
            )
            val chapterItems = LinkedHashMap<Int, PDOutlineItem>()
            for (p in 1..6) {
                val page = PDPage()
                pdf.addPage(page)
                chapterStarts[p]?.let { chTitle ->
                    if (outline != null) {
                        val dest = PDPageFitDestination().apply { this.page = page }
                        val item = PDOutlineItem().apply { this.title = chTitle; destination = dest }
                        outline.addLast(item)
                        chapterItems[p] = item
                    }
                }
                if (outline != null) {
                    if (p == 2) {
                        val dest = PDPageFitDestination().apply { this.page = page }
                        chapterItems[1]?.addLast(PDOutlineItem().apply { this.title = "1.1 Core Concepts"; destination = dest })
                    } else if (p == 4) {
                        val dest = PDPageFitDestination().apply { this.page = page }
                        chapterItems[3]?.addLast(PDOutlineItem().apply { this.title = "2.1 Decision Margin"; destination = dest })
                    }
                }
                PDPageContentStream(pdf, page).use { out ->
                    val heading = chapterStarts[p]
                    if (heading != null) {
                        out.beginText()
                        out.setFont(bold, 18f)
                        out.newLineAtOffset(54f, 740f)
                        out.showText(heading)
                        out.endText()
                    }
                    out.beginText()
                    out.setFont(font, 11f)
                    out.newLineAtOffset(54f, 690f)
                    out.showText("Synthetic body paragraph one on page $p explaining foundational analytical principles clearly.")
                    out.newLineAtOffset(0f, -18f)
                    out.showText("Synthetic body paragraph two on page $p illustrating quantitative tradeoffs and market behavior.")
                    out.newLineAtOffset(0f, -18f)
                    out.showText("Synthetic body paragraph three on page $p summarizing intuition without copyrighted material.")
                    out.endText()
                }
            }
            pdf.save(target.toFile())
        }
    }

    private fun countRows(dbFile: Path, table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.createStatement().use { sql ->
                sql.executeQuery("SELECT COUNT(*) FROM $table").use { row -> if (row.next()) row.getInt(1) else 0 }
            }
        }

    private fun snapshotTables(dbFile: Path, tables: List<Pair<String, List<String>>>): Map<String, Pair<Int, String>> {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            return tables.associate { (table, cols) ->
                val selectCols = cols.joinToString(",")
                val orderCols = cols.joinToString(",")
                val rowsText = buildString {
                    var count = 0
                    conn.createStatement().use { sql ->
                        sql.executeQuery("SELECT $selectCols FROM $table ORDER BY $orderCols").use { rs ->
                            while (rs.next()) {
                                count++
                                for (i in 1..cols.size) {
                                    val obj = rs.getObject(i)
                                    append(
                                        when (obj) {
                                            null -> "<NULL>"
                                            is ByteArray -> "BYTES:" + sha256(obj)
                                            else -> obj.toString()
                                        },
                                    )
                                    append('|')
                                }
                                append('\n')
                            }
                        }
                    }
                    append("COUNT=$count")
                }
                val rowCount = countRows(dbFile, table)
                table to (rowCount to sha256(rowsText.toByteArray()))
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun seedExamsDatabase(examsDb: Path) {
        DriverManager.getConnection("jdbc:sqlite:$examsDb").use { conn ->
            conn.createStatement().use { sql ->
                sql.execute("CREATE TABLE exams(id TEXT PRIMARY KEY, course TEXT NOT NULL, score INTEGER NOT NULL)")
                sql.execute("INSERT INTO exams VALUES('ex-1','COURSE-101',88)")
            }
        }
    }

    private fun seedFullV111Database(dbFile: Path) {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.createStatement().use { sql ->
                sql.execute("PRAGMA user_version = 1")
                sql.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
                sql.execute("INSERT INTO books VALUES('$shaBookA','Synthetic V1 Book','pdf',173,2,1,1,'CHOICE',1700000000)")

                sql.execute("CREATE TABLE pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,ocr INTEGER NOT NULL DEFAULT 0,ocr_pending INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,page))")
                sql.execute("INSERT INTO pages VALUES('$shaBookA',1,'Chapter 1 Introduction to concepts.',0,0)")
                sql.execute("INSERT INTO pages VALUES('$shaBookA',173,'Diminishing marginal utility explains demand curves clearly.',0,0)")

                sql.execute("CREATE TABLE answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                sql.execute("INSERT INTO answers VALUES('$shaBookA','les-1','CHOICE','A',1,NULL,0)")

                sql.execute("CREATE TABLE book_reviews(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,completed INTEGER NOT NULL DEFAULT 0,guessed INTEGER NOT NULL DEFAULT 0,sources INTEGER NOT NULL DEFAULT 1,due_day INTEGER,streak INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))")
                sql.execute("INSERT INTO book_reviews VALUES('$shaBookA','les-1','CHOICE','Synthetic V1 Book','Chapter 1','Q?','Ref','B','[\"A\",\"B\"]','A','B',1,NULL,1,0,1,20000,1)")

                sql.execute("CREATE TABLE book_review_history(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,kind TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,guessed INTEGER NOT NULL,answered_day INTEGER)")
                sql.execute("INSERT INTO book_review_history VALUES('$shaBookA','les-1','CHOICE','LESSON','B',1,NULL,0,20000)")

                sql.execute("CREATE TABLE book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)")
                sql.execute("INSERT INTO book_review_position VALUES(1,'$shaBookA','les-1','CHOICE')")

                sql.execute("CREATE TABLE book_open_session(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,screen TEXT NOT NULL CHECK(screen IN ('READER','LEARNING')))")
                sql.execute("INSERT INTO book_open_session VALUES(1,'$shaBookA','READER')")

                sql.execute("CREATE TABLE book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))")
                sql.execute("INSERT INTO book_learning_paths VALUES('$shaBookA','READ_AND_LEARN')")

                sql.execute("CREATE TABLE book_ladders(book TEXT PRIMARY KEY,session TEXT NOT NULL)")
                sql.execute("INSERT INTO book_ladders VALUES('$shaBookA','{}')")

                sql.execute("CREATE TABLE book_reader_views(book TEXT PRIMARY KEY,text_view INTEGER NOT NULL)")
                sql.execute("INSERT INTO book_reader_views VALUES('$shaBookA',0)")

                sql.execute("CREATE TABLE book_pdf_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                sql.execute("INSERT INTO book_pdf_modes VALUES('$shaBookA',0)")

                sql.execute("CREATE TABLE book_text_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)")
                sql.execute("INSERT INTO book_text_modes VALUES('$shaBookA',1)")

                sql.execute("CREATE TABLE book_read_spots(book TEXT PRIMARY KEY,page INTEGER NOT NULL,offset_utf16 INTEGER NOT NULL)")
                sql.execute("INSERT INTO book_read_spots VALUES('$shaBookA',173,42)")

                sql.execute("CREATE TABLE book_highlights(book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,PRIMARY KEY(book,page,start,end))")
                sql.execute("INSERT INTO book_highlights VALUES('$shaBookA',173,0,10,'Diminishing')")

                sql.execute("CREATE TABLE book_notes(book TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,note TEXT NOT NULL,text_sha256 TEXT NOT NULL,revision INTEGER NOT NULL,last_mutation_id TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,id))")
                sql.execute("INSERT INTO book_notes VALUES('$shaBookA','note-1','v1',173,0,10,'Diminishing','Existing Note','${shaBookB}',3,'mut-3',0)")

                sql.execute("CREATE TABLE reading_settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                sql.execute("INSERT INTO reading_settings VALUES('font','SERIF')")

                sql.execute("CREATE TABLE book_illustrations(book TEXT NOT NULL,page INTEGER NOT NULL,ordinal INTEGER NOT NULL,anchor_offset INTEGER NOT NULL,image_sha256 TEXT NOT NULL,extension TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,caption TEXT,kind TEXT NOT NULL DEFAULT 'ILLUSTRATION',source_start INTEGER,source_end INTEGER,region_x REAL,region_y REAL,region_w REAL,region_h REAL,table_rows BLOB,PRIMARY KEY(book,page,ordinal))")
                sql.execute("INSERT INTO book_illustrations VALUES('$shaBookA',1,0,5,'${shaBookB}','png',100,80,'Fig 1','ILLUSTRATION',0,5,0.1,0.1,0.5,0.4,NULL)")

                sql.execute("CREATE TABLE book_page_text_boxes(book TEXT NOT NULL,page INTEGER NOT NULL,page_width REAL NOT NULL,page_height REAL NOT NULL,boxes BLOB NOT NULL,PRIMARY KEY(book,page))")
                sql.execute("INSERT INTO book_page_text_boxes VALUES('$shaBookA',1,595.0,842.0,X'00000000')")

                sql.execute("CREATE TABLE book_native_sources(book TEXT PRIMARY KEY,payload BLOB NOT NULL)")
                sql.execute("INSERT INTO book_native_sources VALUES('$shaBookA',X'7B7D')")

                sql.execute("CREATE TABLE book_structures(book TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)")
                sql.execute("INSERT INTO book_structures VALUES('$shaBookA',1,'{\"version\":1,\"source\":\"HEADING_RULE\",\"modelAttempted\":false,\"pageRoles\":{},\"sections\":[]}')")

                sql.execute("CREATE TABLE book_section_cache(book TEXT NOT NULL, section_id TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(book, section_id, kind))")
                sql.execute("INSERT INTO book_section_cache VALUES('$shaBookA','sec-1','guide','{\"version\":2}',1700000000)")
            }
        }
    }
}
