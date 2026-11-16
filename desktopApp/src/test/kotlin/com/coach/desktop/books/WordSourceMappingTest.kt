package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class WordSourceMappingTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val math = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private fun document(body: String) = "<w:document xmlns:w='$word' xmlns:m='$math'><w:body>$body</w:body></w:document>"
    private fun paragraph(text: String) = "<w:p><w:r><w:t xml:space='preserve'>$text</w:t></w:r></w:p>"
    private fun docx(file: Path, xml: String, notes: String? = null, styles: String? = null) {
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            val members = linkedMapOf("word/document.xml" to xml)
            if (notes != null) members["word/footnotes.xml"] = notes
            if (styles != null) members["word/styles.xml"] = styles
            members.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
    }
    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-word-source-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun text(source: WordElementSource, pages: List<BookPage>) = source.ranges.joinToString("") { range ->
        val page = pages.single { it.number == range.page }
        assertTrue(range.start in 0 until range.end && range.end <= page.text.length)
        for (offset in listOf(range.start, range.end)) {
            assertFalse(offset > 0 && offset < page.text.length && Character.isHighSurrogate(page.text[offset - 1]) && Character.isLowSurrogate(page.text[offset]))
        }
        page.text.substring(range.start, range.end)
    }
    private fun mapped(file: Path, pages: List<BookPage>): WordSourceMapping {
        val result = StructuredBookExtraction.wordSourceMapping(file, pages)
        assertEquals(WordSourceMapping.Status.MAPPED, result.status)
        result.elements.forEach { text(it, pages) }
        return result
    }

    @Test fun repeatedParagraphsTableGridAndMathKeepExactSourceIdentity() = temporary { root ->
        val xml = document(paragraph("same😀") + "\n<!--ignored-->" + paragraph("same😀") +
            "<w:tbl><w:tblPr/><w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:tcPr><w:gridSpan w:val='2'/></w:tcPr>${paragraph("A")}</w:tc>" +
            "<w:tc>${paragraph("B")}</w:tc></w:tr><w:tr><w:tc><w:tcPr><w:vMerge w:val='restart'/></w:tcPr>${paragraph("C")}</w:tc><w:tc>${paragraph("D")}</w:tc></w:tr></w:tbl>" +
            "<w:p><m:oMath><m:f><m:num><m:r><m:t>x</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den></m:f></m:oMath></w:p>")
        val file = root.resolve("book.docx"); docx(file, xml)
        val pages = TextbookExtraction.extract(file, "docx", "fallback").pages
        assertEquals(listOf("same😀\n\n\n\nsame😀\n\n\n\nA | B\n\n\n\nC | D\n\n\n\nx2"), pages.map { it.text })
        val result = mapped(file, pages)
        val paragraphs = result.elements.filter { it.localName == "p" && it.nodePath.size == 2 }.take(2)
        assertEquals(listOf(listOf(0, 0), listOf(0, 1)), paragraphs.map { it.nodePath })
        assertEquals(listOf(0, 10), paragraphs.map { it.ranges.single().start })
        assertEquals(listOf("same😀", "same😀"), paragraphs.map { text(it, pages) })
        assertEquals(listOf("A | B", "C | D"), result.elements.filter { it.localName == "tr" }.map { text(it, pages) })
        assertEquals(listOf("A", "B", "C", "D"), result.elements.filter { it.localName == "tc" }.map { text(it, pages) })
        assertEquals("x2", text(result.elements.single { it.localName == "f" }, pages))
        assertEquals(math, result.elements.single { it.localName == "oMath" }.namespace)
        assertTrue(result.elements.filter { it.localName in setOf("tblPr", "tblHeader", "gridSpan", "vMerge") }.all { it.ranges.isEmpty() })
        val dom = StructuredBookExtraction.xml(xml.toByteArray())
        val hash = MessageDigest.getInstance("SHA-256").digest(xml.toByteArray()).joinToString("") { "%02x".format(it) }
        for (source in result.elements) {
            var node = dom
            for (index in source.nodePath) node = (0 until node.childNodes.length).mapNotNull { node.childNodes.item(it) as? org.w3c.dom.Element }[index]
            assertEquals(node.localName, source.localName)
            assertEquals(node.namespaceURI, source.namespace)
            assertEquals(hash, source.memberSha256)
            assertEquals("word/document.xml", source.member)
        }
    }

    @Test fun chapterStylesAndLongParagraphsPreserveSlicesAndUnicodeBoundaries() = temporary { root ->
        val content = "a".repeat(3999) + "😀" + "b".repeat(4001)
        val heading = "<w:p><w:pPr><w:pStyle w:val='Lesson'/></w:pPr><w:r><w:t>Second</w:t></w:r></w:p>"
        val file = root.resolve("book.docx")
        docx(file, document(paragraph("First") + heading + paragraph(content)), styles =
            "<w:styles xmlns:w='$word'><w:style w:styleId='Lesson'><w:name w:val='Heading 2'/></w:style></w:styles>")
        val pages = TextbookExtraction.extract(file, "docx", "fallback").pages
        assertEquals("First", pages.first().text)
        assertEquals("# Second\n\n\n\n$content", pages.drop(1).joinToString("") { it.text })
        val result = mapped(file, pages)
        val large = result.elements.single { it.localName == "t" && it.nodePath.take(2) == listOf(0, 2) }
        assertEquals(3, large.ranges.size)
        assertEquals(content, text(large, pages))
        assertEquals("# Second", text(result.elements.single { it.localName == "p" && it.nodePath == listOf(0, 1) }, pages))
    }

    @Test fun explicitBreaksAndEmptyPagesUseExistingTrimWithoutInventingAnchors() = temporary { root ->
        val file = root.resolve("book.docx")
        docx(file, document(paragraph("  A  ") + "<w:p><w:r><w:br w:type='page'/><w:br w:type='page'/><w:t> B </w:t></w:r></w:p>" +
            "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>C</w:t></w:r></w:p>" +
            "<w:sectPr><w:type w:val='nextPage'/></w:sectPr>" + paragraph(" D ")))
        val pages = TextbookExtraction.extract(file, "docx", "fallback").pages
        assertEquals(listOf("A", "", "B", "C", "D"), pages.map { it.text })
        val result = mapped(file, pages)
        assertEquals(listOf(1, 3, 4, 5), result.elements.single { it.localName == "body" }.ranges.map { it.page })
        assertTrue(result.elements.filter { it.localName in setOf("br", "sectPr", "pageBreakBefore") }.all { it.ranges.isEmpty() })
        assertEquals(listOf("A", "B", "C", "D"), result.elements.filter { it.localName == "t" }.map { text(it, pages) })
    }

    @Test fun carriageReturnNormalizationMatchesBothExistingWordPaginationPaths() = temporary { root ->
        for (explicit in listOf(false, true)) {
            val file = root.resolve("book-$explicit.docx")
            docx(file, document(paragraph(" A&#13;&#10;B&#13;C ") + if (explicit) "<w:p><w:r><w:br w:type='page'/><w:t>Tail</w:t></w:r></w:p>" else ""))
            val pages = TextbookExtraction.extract(file, "docx", "fallback").pages
            assertEquals(if (explicit) listOf("A\r\nB\rC", "Tail") else listOf("A\nB\nC"), pages.map { it.text })
            val source = mapped(file, pages).elements.first { it.localName == "t" }
            assertEquals(pages.first().text, text(source, pages))
        }
    }

    @Test fun repeatedFootnotesMapEachAppendedCopyToItsOwnMember() = temporary { root ->
        val reference = "<w:footnoteReference w:id='1'/>"
        val file = root.resolve("book.docx")
        val noteXml = "<w:footnotes xmlns:w='$word'><w:footnote w:id='-1'>${paragraph("separator")}</w:footnote>" +
            "<w:footnote w:id='1'>${paragraph("Note😀")}</w:footnote><w:footnote w:id='2'>${paragraph("unused")}</w:footnote></w:footnotes>"
        docx(file, document("<w:p><w:r><w:t>Body</w:t>$reference$reference<w:br w:type='page'/><w:t>Body</w:t>$reference<w:footnoteReference w:id='9'/></w:r></w:p>"), noteXml)
        val pages = TextbookExtraction.extract(file, "docx", "fallback").pages
        assertEquals(listOf("Body【脚注 1】【脚注 1】\n\n脚注 1：Note😀", "Body【脚注 1】【脚注 9】\n\n脚注 1：Note😀\n\n脚注 9：（原文件缺少这条脚注）"), pages.map { it.text })
        val result = mapped(file, pages)
        val notes = result.elements.filter { it.member == "word/footnotes.xml" }
        val source = notes.single { it.localName == "t" && it.nodePath.first() == 1 }
        assertEquals(listOf(1, 2), source.ranges.map { it.page })
        assertEquals("Note😀Note😀", text(source, pages))
        assertEquals(listOf(23, 23), source.ranges.map { it.start })
        assertTrue(notes.filter { it.nodePath.firstOrNull() in setOf(0, 2) }.all { it.ranges.isEmpty() })
        val hash = MessageDigest.getInstance("SHA-256").digest(noteXml.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(notes.all { it.memberSha256 == hash })
        assertEquals("Body【脚注 1】【脚注 1】Body【脚注 1】【脚注 9】", text(result.elements.single { it.member == "word/document.xml" && it.localName == "body" }, pages))
    }

    @Test fun omittedContentAndNestedTablesHaveNoFalseCanonicalRanges() = temporary { root ->
        val file = root.resolve("book.docx")
        docx(file, document("<w:p><w:pPr/><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:cr/><w:t>C</w:t><w:instrText>FIELD</w:instrText></w:r>" +
            "<w:del><w:r><w:t>Deleted</w:t></w:r></w:del><w:r><w:t/></w:r></w:p>" +
            "<w:tbl><w:tr><w:tc>${paragraph("Cell")}<w:tbl><w:tr><w:tc>${paragraph("Nested")}</w:tc></w:tr></w:tbl></w:tc></w:tr></w:tbl>"))
        val pages = TextbookExtraction.extract(file, "docx", "fallback").pages
        assertEquals(listOf("A\tB\nC\n\n\n\nCell"), pages.map { it.text })
        val result = mapped(file, pages)
        assertEquals("\t", text(result.elements.single { it.localName == "tab" }, pages))
        assertEquals("\n", text(result.elements.single { it.localName == "cr" }, pages))
        assertTrue(result.elements.filter { it.localName in setOf("del", "instrText", "pPr") || it.nodePath.take(5) == listOf(0, 1, 0, 0, 1) }.all { it.ranges.isEmpty() })
        assertEquals(listOf("A", "B", "C", "", "", "Cell", ""), result.elements.filter { it.localName == "t" }.map { text(it, pages) })
    }

    @Test fun missingChangedOrRenumberedCanonicalPagesRejectAllSources() = temporary { root ->
        val file = root.resolve("book.docx"); docx(file, document(paragraph("Repeat😀") + paragraph("Repeat😀")))
        val pages = TextbookExtraction.extract(file, "docx", "fallback").pages
        for (stale in listOf(emptyList(), listOf(pages.single().copy(text = "changed")), listOf(pages.single().copy(number = 2)), pages + pages)) {
            val result = StructuredBookExtraction.wordSourceMapping(file, stale)
            assertEquals(WordSourceMapping.Status.STALE_TEXT, result.status)
            assertTrue(result.elements.isEmpty())
        }
    }

    @Test fun mappingSavedWordSourceAfterReopenPreservesBlocksAndLegalCitations() = temporary { root ->
        val file = root.resolve("book.docx")
        docx(file, document("<w:p><w:pPr><w:pStyle w:val='Heading1'/></w:pPr><w:r><w:t>Lesson</w:t></w:r></w:p>" +
            paragraph("A cell is a living unit😀.") + "<w:tbl><w:tr><w:tc>${paragraph("A cell is a living unit😀.")}</w:tc><w:tc>${paragraph("Cell wall")}</w:tc></w:tr></w:tbl>" +
            "<w:p><w:r><w:t>More details.</w:t><w:footnoteReference w:id='1'/></w:r></w:p>"),
            "<w:footnotes xmlns:w='$word'><w:footnote w:id='1'>${paragraph("A note.")}</w:footnote></w:footnotes>")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        val before = StudyCoachCourse.blocks(book.id, book.pages)
        Files.delete(file)
        val reopenedLibrary = TextbookLibrary(library.directory)
        val reopened = reopenedLibrary.book(book.id)
        mapped(library.directory.resolve("${book.id}.docx"), reopened.pages)
        assertEquals(book.pages, reopened.pages)
        assertEquals(before, StudyCoachCourse.blocks(book.id, reopened.pages))
        val resolver = BookSourceResolver(reopenedLibrary)
        for (block in before.filterNot { it.heading }) {
            val target = assertIs<BookSourceResolution.Resolved>(resolver.resolve(OwnedBookCitation(book.id, block.page, block.start, block.end), block.text)).target
            assertEquals(block.text, reopened.page(target.pdfPage)!!.text.substring(target.start, target.end))
        }
        assertEquals(book.pages, reopenedLibrary.book(book.id).pages)
    }
}
