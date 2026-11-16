package com.coach.desktop.books

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
            val second = ExtractedIllustration(2, 20, anotherPng, "jpeg", 320, 240, null)
            assertEquals(dup1.contentSha256, dup2.contentSha256)
            assertEquals("图 1-1 示意图", dup1.caption)
            assertNull(dup2.caption)
            assertEquals("jpg", second.extension)

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
            val text = "第1章 光合作用\n\n叶绿体 😀 吸收光能并合成有机物。\n\n小结：光反应发生在类囊体膜上。"
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

            // Persist the text verbatim as a real book and verify StudyCoachCourse + BookSourceResolver offsets stay unchanged.
            val mdFile = root.resolve("chapter.md")
            Files.writeString(mdFile, text)
            val book = library.importBook(mdFile)
            val exactPageText = book.pages.single().text
            assertEquals(text, exactPageText)

            val blocksBefore = StudyCoachCourse.blocks(book.id, book.pages)
            val targetBlock = blocksBefore.last { !it.heading }
            val anchorBetweenParagraphs = targetBlock.start

            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                db.autoCommit = false
                val saved = BookIllustrationStore.saveInTransaction(
                    db = db,
                    libraryDirectory = library.directory,
                    bookId = book.id,
                    pageTexts = book.pages.associate { it.number to it.text },
                    illustrations = listOf(
                        ExtractedIllustration(1, emojiHigh, samplePng, "png", 800, 500, "图 1-1 叶绿体结构"),
                        ExtractedIllustration(1, anchorBetweenParagraphs, anotherPng, "jpg", 600, 400, null),
                    ),
                )
                db.commit()
                assertEquals(2, saved.size)
                assertEquals(listOf(0, 1), saved.map { it.order })

                val loaded = BookIllustrationStore.loadByPage(db, book.id)[1].orEmpty()
                assertEquals(saved, loaded)
                assertTrue(Files.exists(BookIllustrationAssets.imageFile(library.directory, book.id, loaded[0])))
                assertTrue(Files.exists(BookIllustrationAssets.imageFile(library.directory, book.id, loaded[1])))
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

    @Test fun legacyDatabasesWithoutIllustrationTableOrRowsOpenNormally() {
        val root = Files.createTempDirectory("coach-illustration-legacy-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(syntheticTextbook(root.resolve("source")))
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                // Simulate a pre-illustration database that lacks the book_illustrations table entirely.
                db.createStatement().use { it.execute("DROP TABLE IF EXISTS book_illustrations") }
                assertFalse(BookIllustrationStore.tableExists(db))
                assertEquals(emptyMap(), BookIllustrationStore.loadByPage(db, imported.id))

                // After migration, existing books without illustration rows still return emptyMap.
                BookIllustrationStore.migrate(db)
                assertTrue(BookIllustrationStore.tableExists(db))
                assertEquals(emptyMap(), BookIllustrationStore.loadByPage(db, imported.id))
            }
            val reopened = TextbookLibrary(library.directory).book(imported.id)
            assertEquals(imported.pages, reopened.pages)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun segmentingPageAroundAnchorsPreservesVerbatimTextAndExactOffsets() {
        val text = "首段 😀 说明。\n\n第二段正文。\n\n末段。"
        val hash = BookIllustrationAssets.sha256(samplePng)
        val midEmoji = text.indexOf('\uD83D') + 1
        val secondPara = text.indexOf("第二段")
        val items = listOf(
            BookIllustration(1, text.length, hash, "png", 300, 200, "文末图", order = 3),
            BookIllustration(1, 0, hash, "png", 300, 200, "文首图", order = 0),
            BookIllustration(1, secondPara, hash, "png", 300, 200, "第二张同锚点", order = 2),
            BookIllustration(1, midEmoji, hash, "png", 300, 200, "代理对安全吸附", order = 1),
        )
        val segments = BookIllustrationLayout.segment(text, items)
        val joined = segments.filterIsInstance<BookPageSegment.Text>().joinToString("") { it.text }
        assertEquals(text, joined)
        for (segment in segments.filterIsInstance<BookPageSegment.Text>()) {
            assertEquals(text.substring(segment.startOffset, segment.endOffset), segment.text)
        }
        val orderedCaptions = segments.filterIsInstance<BookPageSegment.Illustration>().map { it.illustration.caption }
        assertEquals(listOf("文首图", "代理对安全吸附", "第二张同锚点", "文末图"), orderedCaptions)
    }
}
