package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class BookIllustrationModelTest {
    private val samplePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    private val anotherPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 5, 6, 7, 8)
    private val bookId = "a".repeat(64)

    @Test fun contentHashDeduplicatesImagesPerBookAndRejectsUnsafePaths() {
        val root = Files.createTempDirectory("coach-illustration-assets-")
        try {
            val dup1 = ExtractedIllustration(1, 0, samplePng, "PNG", 640, 480, "  图 1-1 示意图  ")
            val dup2 = ExtractedIllustration(2, 10, samplePng.copyOf(), "png", 640, 480, "")
            val second = ExtractedIllustration(
                page = 2,
                anchorOffset = 20,
                bytes = anotherPng,
                extension = "jpeg",
                width = 320,
                height = 240,
                caption = null,
                kind = PageVisualKind.TABLE,
                sourceRegion = ReaderRect(0.1, 0.2, 0.8, 0.3),
            )
            assertEquals(dup1.contentSha256, dup2.contentSha256)
            assertEquals("图 1-1 示意图", dup1.caption)
            assertNull(dup2.caption)
            assertEquals("jpg", second.extension)
            assertEquals(PageVisualKind.TABLE, second.kind)

            val written = BookIllustrationAssets.writeUnique(root, bookId, listOf(dup1, dup2, second))
            assertEquals(2, written.size)
            val bookDir = BookIllustrationAssets.bookDirectory(root, bookId)
            assertTrue(written.all { it.startsWith(bookDir) })
            assertContentEquals(samplePng, Files.readAllBytes(BookIllustrationAssets.imageFile(root, bookId, dup1.contentSha256, "png")))
            assertContentEquals(anotherPng, Files.readAllBytes(BookIllustrationAssets.imageFile(root, bookId, second.contentSha256, "jpg")))

            // Re-writing the same image content does not duplicate or rewrite on disk.
            assertTrue(BookIllustrationAssets.writeUnique(root, bookId, listOf(dup1)).isEmpty())

            assertFailsWith<IllegalArgumentException> { BookIllustrationAssets.bookDirectory(root, "../escape") }
            assertFailsWith<IllegalArgumentException> { BookIllustrationAssets.imageFile(root, bookId, "bad-hash", "png") }
            assertFailsWith<IllegalArgumentException> { ExtractedIllustration(1, 0, samplePng, "svg", 100, 100) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun utf16AnchorsNeverSplitSurrogatePairsOrAlterPageTextAndCitations() {
        val root = Files.createTempDirectory("coach-illustration-utf16-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val text = "第1章 光合作用\n\n叶绿体 😀 吸收光能并合成有机物。\n\n表格原始扁平文字：A|B。\n\n小结：光反应发生在类囊体膜上。"
            val emojiHigh = text.indexOf('\uD83D')
            val emojiLow = emojiHigh + 1
            assertTrue(Character.isHighSurrogate(text[emojiHigh]))
            assertTrue(Character.isLowSurrogate(text[emojiLow]))

            assertFalse(BookIllustrationAnchors.isValidAnchor(text, emojiLow))
            assertEquals(emojiHigh, BookIllustrationAnchors.snapToValidUtf16Boundary(text, emojiLow))
            assertTrue(BookIllustrationAnchors.isValidAnchor(text, 0))
            assertTrue(BookIllustrationAnchors.isValidAnchor(text, emojiHigh))
            assertTrue(BookIllustrationAnchors.isValidAnchor(text, emojiHigh + 2))
            assertTrue(BookIllustrationAnchors.isValidAnchor(text, text.length))

            assertFailsWith<IllegalArgumentException> {
                BookIllustrationAnchors.requireValidForPages(
                    mapOf(1 to text),
                    listOf(ExtractedIllustration(1, emojiLow, samplePng, "png", 400, 300)),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                BookIllustrationAnchors.requireValidForPages(
                    mapOf(1 to text),
                    listOf(
                        ExtractedIllustration(
                            page = 1,
                            anchorOffset = 0,
                            bytes = samplePng,
                            extension = "png",
                            width = 400,
                            height = 300,
                            kind = PageVisualKind.CALLOUT,
                            sourceStart = emojiLow,
                            sourceEnd = emojiLow + 2,
                        ),
                    ),
                )
            }

            // Persist the text verbatim as a real book and verify StudyCoachCourse + BookSourceResolver offsets stay unchanged.
            val mdFile = root.resolve("chapter.md")
            Files.writeString(mdFile, text)
            val book = library.importBook(mdFile)
            val exactPageText = book.pages.single().text
            assertEquals(text, exactPageText)

            val blocksBefore = StudyCoachCourse.blocks(book.id, book.pages)
            val targetBlock = blocksBefore.last { !it.heading }
            val tableStart = text.indexOf("表格原始扁平文字")
            val tableEnd = text.indexOf("小结") - 2
            val cropRegion = ReaderRect(0.1, 0.45, 0.8, 0.2)

            val boxEmoji = PageTextBox(emojiHigh, emojiHigh + 2, ReaderRect(0.25, 0.20, 0.05, 0.04))
            val boxTable = PageTextBox(tableStart, tableEnd, cropRegion)
            val boxSummary = PageTextBox(targetBlock.start, targetBlock.end, ReaderRect(0.1, 0.72, 0.8, 0.08))
            val textLayout = PageTextLayout(
                page = 1,
                pageWidth = 595.0,
                pageHeight = 842.0,
                boxes = listOf(boxEmoji, boxTable, boxSummary),
            )

            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                db.autoCommit = false
                val saved = BookPageModelStore.saveInTransaction(
                    db = db,
                    libraryDirectory = library.directory,
                    bookId = book.id,
                    pageTexts = book.pages.associate { it.number to it.text },
                    illustrations = listOf(
                        ExtractedIllustration(1, emojiHigh, samplePng, "png", 800, 500, "图 1-1 叶绿体结构"),
                        ExtractedIllustration(
                            page = 1,
                            anchorOffset = tableStart,
                            bytes = anotherPng,
                            extension = "jpg",
                            width = 600,
                            height = 200,
                            caption = "表 1-1 对比",
                            kind = PageVisualKind.TABLE,
                            sourceStart = tableStart,
                            sourceEnd = tableEnd,
                            sourceRegion = cropRegion,
                        ),
                    ),
                    textLayouts = listOf(textLayout),
                )
                db.commit()
                assertEquals(2, saved.size)
                assertEquals(listOf(0, 1), saved.map { it.order })
                assertEquals(PageVisualKind.ILLUSTRATION, saved[0].kind)
                assertEquals(PageVisualKind.TABLE, saved[1].kind)
                assertEquals(tableStart until tableEnd, saved[1].replacedSourceRange)
                assertEquals(cropRegion, saved[1].sourceRegion)

                val extras = BookPageModelStore.loadExtrasByPage(db, book.id, includeTextLayouts = true)[1]!!
                assertEquals(saved, extras.illustrations)
                assertTrue(Files.exists(BookIllustrationAssets.imageFile(library.directory, book.id, extras.illustrations[0])))
                assertTrue(Files.exists(BookIllustrationAssets.imageFile(library.directory, book.id, extras.illustrations[1])))

                val loadedLayout = BookPageTextBoxStore.loadPage(db, book.id, 1)!!
                assertEquals(595.0, loadedLayout.pageWidth)
                assertEquals(842.0, loadedLayout.pageHeight)
                assertEquals(3, loadedLayout.boxes.size)
                assertEquals(boxEmoji.startOffset, loadedLayout.boxes[0].startOffset)
                assertEquals(boxEmoji.endOffset, loadedLayout.boxes[0].endOffset)
                assertEquals(boxEmoji.bounds.x, loadedLayout.boxes[0].bounds.x, 1e-6)
                assertEquals(boxEmoji.bounds.y, loadedLayout.boxes[0].bounds.y, 1e-6)
                // Forward & reverse mapping for TON-1670.
                assertEquals(listOf(boxSummary.startOffset), loadedLayout.boxesForRange(targetBlock.start, targetBlock.end).map { it.startOffset })
                val dragged = loadedLayout.rangeBetweenPoints(0.26, 0.21, 0.5, 0.75)!!
                assertEquals(emojiHigh until targetBlock.end, dragged)
                assertEquals(text.substring(dragged.first, dragged.last + 1), exactPageText.substring(dragged.first, dragged.last + 1))
            }

            // Page text, blocks, and UTF-16 source resolution remain bit-for-bit identical.
            val reopened = TextbookLibrary(library.directory).book(book.id)
            assertEquals(exactPageText, reopened.pages.single().text)
            assertEquals(blocksBefore, StudyCoachCourse.blocks(reopened.id, reopened.pages))
            val resolved = assertIs<BookSourceResolution.Resolved>(
                BookSourceResolver(library).resolve(
                    OwnedBookCitation(reopened.id, targetBlock.page, targetBlock.start, targetBlock.end),
                    targetBlock.text,
                ),
            ).target
            assertEquals(targetBlock.text, reopened.page(1)!!.text.substring(resolved.start, resolved.end))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun legacyDatabasesWithoutTablesOrNewColumnsOpenNormally() {
        val root = Files.createTempDirectory("coach-illustration-legacy-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(syntheticTextbook(root.resolve("source")))
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                // 1. Pre-illustration database lacking both tables entirely.
                db.createStatement().use {
                    it.execute("DROP TABLE IF EXISTS book_illustrations")
                    it.execute("DROP TABLE IF EXISTS book_page_text_boxes")
                }
                assertFalse(BookIllustrationStore.tableExists(db))
                assertFalse(BookPageTextBoxStore.tableExists(db))
                assertEquals(emptyMap(), BookIllustrationStore.loadByPage(db, imported.id))
                assertNull(BookPageTextBoxStore.loadPage(db, imported.id, 1))
                assertEquals(emptyMap(), BookPageModelStore.loadExtrasByPage(db, imported.id, includeTextLayouts = true))

                // 2. Simulate an older 9-column book_illustrations table before TON-1669 columns existed.
                db.createStatement().use {
                    it.execute(
                        """CREATE TABLE book_illustrations(
                            book TEXT NOT NULL,page INTEGER NOT NULL,ordinal INTEGER NOT NULL,
                            anchor_offset INTEGER NOT NULL,image_sha256 TEXT NOT NULL,extension TEXT NOT NULL,
                            width INTEGER NOT NULL,height INTEGER NOT NULL,caption TEXT,
                            PRIMARY KEY(book,page,ordinal))""".trimIndent(),
                    )
                    it.execute(
                        "INSERT INTO book_illustrations VALUES('${imported.id}',1,0,0,'${"b".repeat(64)}','png',200,100,'旧图')",
                    )
                }
                val legacyLoaded = BookIllustrationStore.loadByPage(db, imported.id)[1].orEmpty()
                assertEquals(1, legacyLoaded.size)
                assertEquals(PageVisualKind.ILLUSTRATION, legacyLoaded.single().kind)
                assertNull(legacyLoaded.single().sourceStart)
                assertNull(legacyLoaded.single().sourceRegion)

                // 3. Migrating upgrades the 9-column table in-place without losing legacy rows.
                BookPageModelStore.migrate(db)
                assertTrue(BookIllustrationStore.tableExists(db))
                assertTrue(BookPageTextBoxStore.tableExists(db))
                assertEquals(legacyLoaded, BookIllustrationStore.loadByPage(db, imported.id)[1])
            }
            val reopened = TextbookLibrary(library.directory).book(imported.id)
            assertTrue(reopened.pages.all { it.textLayout == null }, "A legacy library opens before any text-layer backfill")
            assertEquals(imported.pages.map { page ->
                val legacy = page.copy(textLayout = null)
                if (page.number == 1) legacy.copy(illustrations = listOf(BookIllustration(1, 0, "b".repeat(64), "png", 200, 100, "旧图"))) else legacy
            }, reopened.pages)
            val layer = assertNotNull(library.pdfTextLayer(reopened, reopened.pages.first()))
            assertEquals(imported.pages.first().text, layer.text)
            assertEquals(imported.pages.first().textLayout, library.pageTextLayout(imported.id, 1))
            assertNull(library.pageTextLayout(imported.id, 2), "Only the page actually opened is backfilled")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun importPersistsPageModelsAndKeepsUtf16CitationsAfterReopen() {
        val root = Files.createTempDirectory("coach-page-model-import-")
        try {
            val text = "第1章 测试\n\n前段 😀 内容。\n\n后段原文保留。"
            val anchor = text.indexOf("后段")
            val layout = PageTextLayout(1, 600.0, 800.0, listOf(
                PageTextBox(anchor, text.length, ReaderRect(0.1, 0.2, 0.4, 0.1)),
            ))
            val visual = ExtractedIllustration(1, anchor, samplePng, "png", 100, 80, "图 1")
            val source = root.resolve("book.txt")
            Files.writeString(source, "source")
            val library = TextbookLibrary(root.resolve("library"), extractBook = { _, _, _, _, _ ->
                ExtractedBook("测试", listOf(BookPage(1, text, textLayout = layout)), listOf(visual))
            })
            val imported = library.importBook(source)
            val page = TextbookLibrary(library.directory).book(imported.id).pages.single()
            assertEquals(text, page.text)
            assertEquals(anchor, page.illustrations.single().anchorOffset)
            assertEquals(visual.contentSha256, page.illustrations.single().imageSha256)
            assertContentEquals(samplePng, Files.readAllBytes(BookIllustrationAssets.imageFile(library.directory, imported.id, page.illustrations.single())))
            assertEquals(layout.boxes.map { it.startOffset to it.endOffset }, page.textLayout!!.boxes.map { it.startOffset to it.endOffset })
            assertEquals(page.textLayout, library.pageTextLayout(imported.id, 1))
            val block = StudyCoachCourse.blocks(imported.id, listOf(BookPage(1, text))).last()
            val resolved = assertIs<BookSourceResolution.Resolved>(BookSourceResolver(library).resolve(
                OwnedBookCitation(imported.id, 1, block.start, block.end), block.text,
            )).target
            assertEquals(block.text, page.text.substring(resolved.start, resolved.end))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun failedImportRollsBackPageModelsAndNewImageFiles() {
        val root = Files.createTempDirectory("coach-page-model-rollback-")
        try {
            val source = root.resolve("book.txt")
            Files.writeString(source, "source")
            val invalid = PageTextLayout(1, 600.0, 800.0, listOf(
                PageTextBox(0, 100, ReaderRect(0.1, 0.2, 0.4, 0.1)),
            ))
            val library = TextbookLibrary(root.resolve("library"), extractBook = { _, _, _, _, _ ->
                ExtractedBook("测试", listOf(BookPage(1, "短文", textLayout = invalid)),
                    listOf(ExtractedIllustration(1, 0, samplePng, "png", 100, 80)))
            })
            assertFailsWith<IllegalArgumentException> { library.importBook(source) }
            assertTrue(library.catalog().isEmpty())
            Files.walk(library.directory).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".png") || it.fileName.toString().endsWith(".txt") })
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun segmentingPageAroundIllustrationsAndCroppedBlocksPreservesExactOffsets() {
        val text = "首段 😀 说明。\n\n表格扁平文字。\n\n末段。"
        val hash = BookIllustrationAssets.sha256(samplePng)
        val midEmoji = text.indexOf('\uD83D') + 1
        val tableStart = text.indexOf("表格扁平文字")
        val tableEnd = tableStart + "表格扁平文字".length
        val items = listOf(
            BookIllustration(1, text.length, hash, "png", 300, 200, "文末图", order = 3),
            BookIllustration(1, 0, hash, "png", 300, 200, "文首图", order = 0),
            BookIllustration(
                page = 1,
                anchorOffset = tableStart,
                imageSha256 = hash,
                extension = "png",
                width = 400,
                height = 180,
                caption = "表 1-1",
                order = 2,
                kind = PageVisualKind.TABLE,
                sourceStart = tableStart,
                sourceEnd = tableEnd,
                sourceRegion = ReaderRect(0.1, 0.3, 0.8, 0.2),
            ),
            BookIllustration(1, midEmoji, hash, "png", 300, 200, "代理对安全吸附", order = 1),
        )
        // Verbatim mode keeps all text intact.
        val verbatim = BookIllustrationLayout.segment(text, items, hideReplacedSourceText = false)
        assertEquals(text, verbatim.filterIsInstance<BookPageSegment.Text>().joinToString("") { it.text })
        for (segment in verbatim.filterIsInstance<BookPageSegment.Text>()) {
            assertEquals(text.substring(segment.startOffset, segment.endOffset), segment.text)
        }
        val orderedCaptions = verbatim.filterIsInstance<BookPageSegment.Illustration>().map { it.illustration.caption }
        assertEquals(listOf("文首图", "代理对安全吸附", "表 1-1", "文末图"), orderedCaptions)

        // Reflow mode with TON-1669 crop blocks hides only the replaced flattened table text while keeping exact offsets.
        val reflow = BookIllustrationLayout.segment(text, items, hideReplacedSourceText = true)
        val reflowJoined = reflow.filterIsInstance<BookPageSegment.Text>().joinToString("") { it.text }
        assertFalse(reflowJoined.contains("表格扁平文字"))
        assertTrue(reflowJoined.contains("末段。"))
        for (segment in reflow.filterIsInstance<BookPageSegment.Text>()) {
            assertEquals(text.substring(segment.startOffset, segment.endOffset), segment.text)
        }
    }
}
