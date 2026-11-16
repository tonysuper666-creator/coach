package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BookNativeDocumentTest {
    private val html = "http://www.w3.org/1999/xhtml"
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val math = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private fun zip(path: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { out -> entries.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry()
        } }
    }
    private fun epub(body: String) = linkedMapOf(
        "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>",
        "OPS/package.opf" to "<package><manifest><item id='a' href='a.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='a'/></spine></package>",
        "OPS/a.xhtml" to "<!DOCTYPE html><html xmlns='$html' xml:lang='en'><head><style>p {color:red}</style><link rel='stylesheet' href='local.css'/></head><body dir='rtl'>$body</body></html>",
    )
    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-native-document-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun BookXmlElement.attr(local: String) = attributes.single { it.name.localName == local }.value
    private fun BookXmlElement.all(): List<BookXmlElement> = listOf(this) + children.flatMap { it.all() }

    @Test fun epubRetainsMixedContentTableMathAndAncestorContextAcrossReopen() = temporary { root ->
        val file = root.resolve("input.epub")
        zip(file, epub("<p style='font-weight:bold'>a <em>b</em> c<![CDATA[<d>]]><img src='a.png'/></p>" +
            "<table><tr><th colspan='2'>Title</th></tr><tr><td>A</td><td>B</td></tr></table>" +
            "<math xmlns='http://www.w3.org/1998/Math/MathML'><mfrac><mi>x</mi><mn>2</mn></mfrac></math>"))
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        val sources = assertNotNull(library.nativeSources(book.id))
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val document = assertNotNull(library.nativeDocument(book.id, "OPS/a.xhtml"))
        val pSources = document.sources.filter { it.localName == "p" }
        assertEquals(listOf(0, 1), pSources.map { it.spineIndex })
        val paragraph = assertNotNull(document.resolve(pSources.first()))
        assertSame(paragraph, document.resolve(pSources.last()))
        assertEquals(listOf(BookXmlText("a "), paragraph.children[0], BookXmlText(" c"),
            BookXmlText("<d>"), paragraph.children[1]), paragraph.content)
        assertEquals("font-weight:bold", paragraph.attr("style"))
        assertEquals("en", document.root.attr("lang"))
        assertEquals("rtl", document.root.children.single { it.name.localName == "body" }.attr("dir"))
        assertEquals("local.css", document.root.all().single { it.name.localName == "link" }.attr("href"))
        assertEquals("2", document.root.all().single { it.name.localName == "th" }.attr("colspan"))
        val fraction = document.root.all().single { it.name.localName == "mfrac" }
        assertEquals(listOf("mi", "mn"), fraction.children.map { it.name.localName })
        assertEquals("http://www.w3.org/1998/Math/MathML", fraction.name.namespace)
        assertTrue(document.sources.all { document.resolve(it) != null })
        assertNull(document.resolve(pSources.first().copy(localName = "foreign")))
        Files.delete(file)
        val reopened = TextbookLibrary(library.directory)
        assertEquals(document.root, assertNotNull(reopened.nativeDocument(book.id, document.member)).root)
        assertEquals(sources, reopened.nativeSources(book.id))
        assertEquals(book.pages, reopened.book(book.id).pages)
        assertEquals(blocks, StudyCoachCourse.blocks(book.id, reopened.book(book.id).pages))
    }

    @Test fun wordKeepsRunParagraphTablePropertiesOmmlAndSeparateFootnotes() = temporary { root ->
        val file = root.resolve("input.docx")
        zip(file, mapOf(
            "word/document.xml" to "<w:document xmlns:w='$word' xmlns:m='$math'><w:body>" +
                "<w:p><w:pPr><w:pStyle w:val='Heading1'/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t xml:space='preserve'>A </w:t><w:footnoteReference w:id='1'/></w:r>" +
                "<m:oMath><m:f><m:num><m:r><m:t>x</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den></m:f></m:oMath></w:p>" +
                "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val='2'/></w:tcPr><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>",
            "word/footnotes.xml" to "<w:footnotes xmlns:w='$word'><w:footnote w:id='1'><w:p><w:r><w:t>Note</w:t></w:r></w:p></w:footnote></w:footnotes>",
        ))
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(file)
        val doc = assertNotNull(library.nativeDocument(book.id, "word/document.xml"))
        assertEquals("Heading1", doc.root.all().single { it.name.localName == "pStyle" }.attr("val"))
        val span = doc.root.all().single { it.name.localName == "gridSpan" }
        assertEquals(word, span.attributes.single().name.namespace)
        assertEquals("w", span.attributes.single().name.prefix)
        assertEquals("2", span.attr("val"))
        assertEquals(listOf("num", "den"), doc.root.all().single { it.name.localName == "f" }.children.map { it.name.localName })
        assertEquals(math, doc.root.all().single { it.name.localName == "f" }.name.namespace)
        val note = assertNotNull(library.nativeDocument(book.id, "word/footnotes.xml"))
        assertTrue(note.sources.all { note.resolve(it) != null && doc.resolve(it) == null })
        assertEquals("1", note.root.all().single { it.name.localName == "footnote" }.attr("id"))
        assertEquals(book.pages, library.book(book.id).pages)
    }

    @Test fun changedMemberWithIdenticalCanonicalTextIsRefusedWithoutChangingSavedIndex() = temporary { root ->
        val input = root.resolve("input.epub"); val entries = epub("<p style='color:red'>same</p>"); zip(input, entries)
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(input)
        val index = assertNotNull(library.nativeSources(book.id))
        val original = library.directory.resolve("${book.id}.epub")
        zip(original, entries.mapValues { (_, value) -> value.replace("color:red", "color:tan") })
        assertEquals(book.pages.map { it.text }, TextbookExtraction.extract(original, "epub", "input").pages.map { it.text })
        assertNull(library.nativeDocument(book.id, "OPS/a.xhtml"))
        assertEquals(index, library.nativeSources(book.id))
        assertEquals(book.pages, library.book(book.id).pages)
    }

    @Test fun missingUnindexedAndStaleCanonicalSourcesNeverProduceStructure() = temporary { root ->
        val input = root.resolve("input.epub"); zip(input, epub("<p>same</p>"))
        val library = TextbookLibrary(root.resolve("library")); val book = library.importBook(input)
        assertNull(library.nativeDocument(book.id, "OPS/package.opf"))
        assertNull(library.nativeDocument(book.id, "../a.xhtml"))
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { it.execute("UPDATE pages SET text='stale' WHERE page=1") }
        }
        assertNull(library.nativeDocument(book.id, "OPS/a.xhtml"))
        Files.delete(library.directory.resolve("${book.id}.epub"))
        assertNull(library.nativeDocument(book.id, "OPS/a.xhtml"))
    }

    private fun read(root: Path, xml: String, format: String = "epub", path: List<Int> = emptyList(),
                     namespace: String = "", name: String = "html", control: BookImportControl = BookImportControl()): BookNativeDocument? {
        val file = root.resolve("input.zip"); zip(file, mapOf("member.xml" to xml))
        val source = BookNativeElement(if (format == "epub") 0 else null, "member.xml",
            BookIllustrationAssets.sha256(xml.toByteArray()), path, namespace, name, emptyList())
        return BookNativeDocument.read(file, BookNativeSources(format, listOf(source)), "member.xml", control)
    }

    @Test fun checkedDigestStillRequiresExactNodePathNamespaceAndName() = temporary { root ->
        assertNull(read(root, "<html><p/></html>", path = listOf(1), name = "p"))
        assertNull(read(root, "<html><p/></html>", path = listOf(0), name = "span"))
        assertNull(read(root, "<html><p/></html>", path = listOf(0), name = "p", namespace = html))
    }

    @Test fun xmlNeverLoadsEntitiesAndPreservesInertLinksAsData() = temporary { root ->
        assertNull(read(root, "<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///not-readable'>]><html>&x;</html>"))
        assertNull(read(root, "<!DOCTYPE html SYSTEM 'https://example.invalid/custom.dtd'><html/>", "docx"))
        val doc = assertNotNull(read(root, "<!DOCTYPE html><html><a href='https://example.invalid/'>link</a><script>text</script></html>"))
        assertEquals("https://example.invalid/", doc.root.children.first().attr("href"))
        assertEquals(listOf(BookXmlText("text")), doc.root.children.last().content)
    }

    @Test fun deepAndOversizedTreesAreRefusedAndCancellationPropagates(): Unit = temporary { root ->
        assertNull(read(root, "<html>" + "<p>".repeat(257) + "x" + "</p>".repeat(257) + "</html>"))
        assertNull(read(root, "<html>" + "<p/>".repeat(200_001) + "</html>"))
        var checks = 0
        val control = BookImportControl(cancelledExternally = { ++checks >= 7 })
        assertFailsWith<BookImportCancelled> { read(root, "<html><p>A</p><p>B</p></html>", control = control) }
    }
}
