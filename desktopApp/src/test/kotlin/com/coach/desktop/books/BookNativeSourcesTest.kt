package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BookNativeSourcesTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun zip(path: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { out -> entries.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry()
        } }
    }
    private fun epub(path: Path) = zip(path, linkedMapOf(
        "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>",
        "OPS/package.opf" to "<package><manifest><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='b' linear='no'/><itemref idref='a'/></spine></package>",
        "OPS/a.xhtml" to "<!DOCTYPE html><html xmlns='http://www.w3.org/1999/xhtml'><body><p>same😀</p><p>same😀</p><aside>Box</aside><math xmlns='http://www.w3.org/1998/Math/MathML'><mi>x</mi></math><img src='a.png'/></body></html>",
        "OPS/b.xhtml" to "<html><body><p>Not linear</p></body></html>",
    ))
    private fun docx(path: Path) = zip(path, linkedMapOf(
        "word/document.xml" to "<w:document xmlns:w='$word'><w:body><w:p><w:pPr/><w:r><w:t>same😀</w:t><w:footnoteReference w:id='1'/><w:br w:type='page'/><w:t>same😀</w:t><w:footnoteReference w:id='1'/></w:r></w:p></w:body></w:document>",
        "word/footnotes.xml" to "<w:footnotes xmlns:w='$word'><w:footnote w:id='1'><w:p><w:r><w:t>Note😀</w:t></w:r></w:p></w:footnote></w:footnotes>",
    ))
    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-native-store-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun sql(library: TextbookLibrary, statement: String) =
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { it.execute(statement) }
        }
    private fun write(library: TextbookLibrary, book: String, bytes: ByteArray) {
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            BookNativeSourceStore.saveInTransaction(db, book, bytes)
        }
    }
    private fun slices(element: BookNativeElement, pages: List<BookPage>) = element.ranges.joinToString("") { r ->
        pages.single { it.number == r.page }.text.substring(r.start, r.end)
    }

    @Test fun epubImportReopensWithoutInputAndKeepsRepeatedSpineAndEmptyNodes() = temporary { root ->
        val file = root.resolve("input.epub"); epub(file)
        val expected = TextbookExtraction.extract(file, "epub", "input").pages
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        val sources = assertNotNull(library.nativeSources(book.id))
        assertEquals(expected.map { it.text }, book.pages.map { it.text })
        val paragraphs = sources.elements.filter { it.localName == "p" }
        assertEquals(listOf(0, 0, 2, 2), paragraphs.map { it.spineIndex })
        assertEquals(listOf(1, 1, 2, 2), paragraphs.map { it.ranges.single().page })
        assertTrue(paragraphs.all { slices(it, book.pages).trim() == "same😀" })
        assertTrue(sources.elements.filter { it.localName == "img" }.all { it.ranges.isEmpty() })
        assertEquals(2, sources.elements.count { it.localName == "aside" })
        assertEquals(2, sources.elements.count { it.localName == "math" })
        Files.delete(file)
        assertEquals(sources, TextbookLibrary(library.directory).nativeSources(book.id))
        assertEquals(StudyCoachCourse.blocks(book.id, book.pages),
            StudyCoachCourse.blocks(book.id, TextbookLibrary(library.directory).book(book.id).pages))
    }

    @Test fun wordReopenKeepsSeparateFootnoteMemberAndNestedOverlappingRanges() = temporary { root ->
        val file = root.resolve("input.docx"); docx(file)
        val expected = TextbookExtraction.extract(file, "docx", "input").pages
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        val sources = assertNotNull(TextbookLibrary(library.directory).nativeSources(book.id))
        assertEquals("docx", sources.format)
        assertEquals(expected.map { it.text }, book.pages.map { it.text })
        assertTrue(sources.elements.all { it.spineIndex == null })
        assertEquals(2, sources.elements.map { it.member }.distinct().size)
        val note = sources.elements.single { it.member == "word/footnotes.xml" && it.localName == "t" }
        assertEquals(listOf(1, 2), note.ranges.map { it.page })
        assertEquals("Note😀Note😀", slices(note, book.pages))
        assertTrue(sources.elements.single { it.localName == "pPr" }.ranges.isEmpty())
        assertEquals(sources.elements.single { it.localName == "body" }.ranges,
            sources.elements.single { it.member == "word/document.xml" && it.localName == "p" }.ranges)
    }

    @Test fun oldLibraryMigrationAndExplicitBackfillLeaveBookAndProgressUnchanged() = temporary { root ->
        val file = root.resolve("input.epub"); epub(file)
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        val expected = assertNotNull(library.nativeSources(book.id))
        library.save(book.copy(readingPage = 2))
        sql(library, "DROP TABLE book_native_sources")
        val reopened = TextbookLibrary(library.directory)
        assertNull(reopened.nativeSources(book.id))
        assertEquals(expected, reopened.retainNativeSources(book.id))
        assertEquals(2, reopened.book(book.id).readingPage)
        assertEquals(book.pages, reopened.book(book.id).pages)
        // A valid saved index can be read without re-opening the archive. It is provenance only.
        Files.delete(reopened.directory.resolve("${book.id}.epub"))
        assertEquals(expected, TextbookLibrary(library.directory).retainNativeSources(book.id))
    }

    @Test fun modifiedOriginalRefusesBackfillAndDoesNotWriteAnIndex() = temporary { root ->
        val file = root.resolve("input.docx"); docx(file)
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        sql(library, "DELETE FROM book_native_sources")
        Files.writeString(library.directory.resolve("${book.id}.docx"), "changed")
        assertFailsWith<IllegalArgumentException> { library.retainNativeSources(book.id) }
        assertNull(library.nativeSources(book.id))
        assertEquals(book.pages, library.book(book.id).pages)
    }

    @Test fun optionalMappingMismatchKeepsOrdinaryImportAvailable() = temporary { root ->
        val file = root.resolve("input.epub"); epub(file)
        val library = TextbookLibrary(root.resolve("library"), extractBook = { _, _, _, _, _ ->
            ExtractedBook("Existing extraction", listOf(BookPage(1, "Existing canonical text")))
        })
        val book = library.importBook(file)
        assertEquals("Existing canonical text", book.pages.single().text)
        assertNull(library.nativeSources(book.id))
        assertNull(library.retainNativeSources(book.id))
        assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
    }

    @Test fun indexWriteFailureRollsBackBookPagesAndManagedOriginal() = temporary { root ->
        val file = root.resolve("input.docx"); docx(file)
        val library = TextbookLibrary(root.resolve("library"))
        sql(library, "CREATE TRIGGER reject_native BEFORE INSERT ON book_native_sources BEGIN SELECT RAISE(ABORT,'native write failed'); END")
        assertFailsWith<java.sql.SQLException> { library.importBook(file) }
        assertTrue(library.catalog().isEmpty())
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            for (table in listOf("books", "pages", "book_native_sources")) db.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { row -> assertTrue(row.next()); assertEquals(0, row.getInt(1)) }
            }
        }
        Files.list(library.directory).use { files -> assertFalse(files.anyMatch { it.fileName.toString().endsWith(".docx") || it.fileName.toString().startsWith(".import-") }) }
    }

    @Test fun failedAndCancelledBackfillKeepPreviouslyReadableBook() = temporary { root ->
        val file = root.resolve("input.docx"); docx(file)
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        sql(library, "DELETE FROM book_native_sources")
        sql(library, "CREATE TRIGGER reject_native BEFORE INSERT ON book_native_sources BEGIN SELECT RAISE(ABORT,'native write failed'); END")
        assertFailsWith<java.sql.SQLException> { library.retainNativeSources(book.id) }
        assertNull(library.nativeSources(book.id))
        assertFailsWith<java.util.concurrent.CancellationException> { library.retainNativeSources(book.id, BookImportControl().apply { cancel() }) }
        assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
        assertNull(library.nativeSources(book.id))
    }

    @Test fun stalePagesAndCorruptOrFutureRecordsAreUnavailableRatherThanRelocated() = temporary { root ->
        val file = root.resolve("input.epub"); epub(file)
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        val sources = assertNotNull(library.nativeSources(book.id))
        val payload = BookNativeSourceStore.encode(book.id, book.pages, sources)
        val changed = book.pages.map { if (it.number == 1) it.copy(text = it.text.replace("Box", "box")) else it }
        for (pages in listOf(changed, book.pages.reversed(), book.pages.drop(1), book.pages.map { it.copy(number = it.number + 10) })) {
            assertFailsWith<IllegalArgumentException> { BookNativeSourceStore.decode(payload, book.id, "epub", pages) }
        }
        for (bad in listOf(payload.copyOf(5), payload + byteArrayOf(0), payload.copyOf().apply { this[3] = 2 }, byteArrayOf(0, 0, 0, 1))) {
            write(library, book.id, bad)
            assertNull(TextbookLibrary(library.directory).nativeSources(book.id))
        }
        write(library, book.id, payload)
        sql(library, "UPDATE pages SET text='changed' WHERE page=1")
        assertNull(library.nativeSources(book.id))
        assertNull(library.retainNativeSources(book.id))
        assertEquals("changed", library.book(book.id).pages.first().text)
    }

    @Test fun sourceIdentityAndUtf16ValidationRejectMalformedPayloadsWithoutRejectingNesting() {
        val id = "a".repeat(64); val pages = listOf(BookPage(1, "A😀B\uD800"))
        val element = BookNativeElement(0, "OPS/a.xhtml", "b".repeat(64), listOf(0), "xhtml", "p", listOf(BookNativeRange(1, 0, 4)))
        val source = BookNativeSources("epub", listOf(element))
        val bytes = BookNativeSourceStore.encode(id, pages, source)
        assertEquals(source, BookNativeSourceStore.decode(bytes, id, "epub", pages))
        assertFailsWith<IllegalArgumentException> { BookNativeSourceStore.decode(bytes, id, "epub", listOf(BookPage(1, "A😀B\uD801"))) }
        assertFailsWith<IllegalArgumentException> { BookNativeSourceStore.decode(bytes, "c".repeat(64), "epub", pages) }
        assertFailsWith<IllegalArgumentException> { BookNativeSourceStore.decode(bytes, id, "docx", pages) }
        for (bad in listOf(element.copy(member = "../a"), element.copy(memberSha256 = "bad"), element.copy(spineIndex = null),
            element.copy(ranges = listOf(BookNativeRange(1, 0, 2))), element.copy(ranges = listOf(BookNativeRange(9, 0, 1))))) {
            assertFailsWith<IllegalArgumentException> { BookNativeSourceStore.encode(id, pages, source.copy(elements = listOf(bad))) }
        }
        assertFailsWith<IllegalArgumentException> { BookNativeSourceStore.encode(id, pages, source.copy(elements = listOf(element, element))) }
        val nested = source.copy(elements = listOf(element, element.copy(nodePath = listOf(0, 0))))
        assertEquals(nested, BookNativeSourceStore.decode(BookNativeSourceStore.encode(id, pages, nested), id, "epub", pages))
    }
}
