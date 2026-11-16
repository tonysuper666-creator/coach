package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class EpubSourceMappingTest {
    private val xhtml = "http://www.w3.org/1999/xhtml"
    private fun document(body: String) = "<html xmlns='$xhtml'><head><title>Ignored</title><style>Ignored</style></head><body>$body</body></html>"

    private fun epub(path: Path, documents: Map<String, String>, order: List<String> = documents.keys.toList(), nonLinear: Set<Int> = emptySet()) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>")
            val manifest = documents.keys.mapIndexed { i, name -> "<item id='d$i' href='$name'/>" }.joinToString("")
            val spine = order.mapIndexed { index, name -> "<itemref idref='d${documents.keys.indexOf(name)}' linear='${if (index in nonLinear) "no" else "yes"}'/>" }.joinToString("")
            entry("OPS/package.opf", "<package><metadata><title>Native</title></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>")
            documents.forEach { (name, content) -> entry("OPS/$name", content) }
        }
    }

    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-epub-source-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    private fun text(source: EpubElementSource, pages: List<BookPage>) = source.ranges.joinToString("") { range ->
        val page = pages.single { it.number == range.page }
        assertTrue(range.start in 0 until range.end && range.end <= page.text.length)
        for (offset in listOf(range.start, range.end)) {
            assertFalse(offset > 0 && offset < page.text.length && Character.isHighSurrogate(page.text[offset - 1]) && Character.isLowSurrogate(page.text[offset]))
        }
        page.text.substring(range.start, range.end)
    }

    @Test fun repeatedTextUsesTreePositionsAndPreservesTableMathAndAsideIdentity() = temporary { root ->
        val xml = document("<p>same😀</p><p>same😀</p><table><tr><th>A</th><td colspan='2'>B</td></tr><tr><td>C</td><td>D</td></tr></table>" +
            "<aside>Box<em>same😀</em></aside><math xmlns='http://www.w3.org/1998/Math/MathML'><mfrac><mi>x</mi><mn>2</mn></mfrac></math>" +
            "<figure><img src='image.png'/><figcaption>Caption</figcaption></figure><script>Ignored</script>")
        val file = root.resolve("book.epub")
        epub(file, mapOf("a.xhtml" to xml))
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        assertEquals(listOf("same😀\n\n\n\nsame😀\n\n\n\nAB\n\n\n\nCD\n\nBoxsame😀x2Caption"), pages.map { it.text })
        val result = TextbookExtraction.epubSourceMapping(file, pages)
        assertEquals(EpubSourceMapping.Status.MAPPED, result.status)
        val paragraphs = result.elements.filter { it.localName == "p" }
        assertEquals(listOf(listOf(1, 0), listOf(1, 1)), paragraphs.map { it.nodePath })
        assertEquals(listOf(0, 10), paragraphs.map { it.ranges.single().start })
        assertEquals(listOf("same😀", "same😀"), paragraphs.map { text(it, pages).trim() })
        assertEquals("AB\n\n\n\nCD", text(result.elements.single { it.localName == "table" }, pages).trim())
        assertEquals("Boxsame😀", text(result.elements.single { it.localName == "aside" }, pages))
        assertEquals("x2", text(result.elements.single { it.localName == "mfrac" }, pages))
        assertEquals("http://www.w3.org/1998/Math/MathML", result.elements.single { it.localName == "math" }.namespace)
        assertTrue(result.elements.single { it.localName == "img" }.ranges.isEmpty())
        assertFalse(result.elements.any { it.localName in setOf("head", "title", "script", "style") })

        // Resolve the same path in the original XML, including omitted head and whitespace nodes.
        val dom = StructuredBookExtraction.xml(xml.toByteArray())
        val hash = MessageDigest.getInstance("SHA-256").digest(xml.toByteArray()).joinToString("") { "%02x".format(it) }
        for (source in result.elements) {
            var node = dom
            for (index in source.nodePath) node = (0 until node.childNodes.length).mapNotNull {
                node.childNodes.item(it) as? org.w3c.dom.Element
            }[index]
            assertEquals(node.localName, source.localName)
            assertEquals(hash, source.memberSha256)
            assertEquals("OPS/a.xhtml", source.member)
            text(source, pages)
        }
    }

    @Test fun identicalSpineDocumentsAndRepeatedSpineEntriesHaveSeparatePageRanges() = temporary { root ->
        val file = root.resolve("book.epub")
        epub(file, linkedMapOf("a.xhtml" to document("<p>Repeat😀</p>"), "b.xhtml" to document("<p>Repeat😀</p>")), listOf("b.xhtml", "a.xhtml", "b.xhtml"))
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        val paragraphs = TextbookExtraction.epubSourceMapping(file, pages).elements.filter { it.localName == "p" }
        assertEquals(listOf(0, 1, 2), paragraphs.map { it.spineIndex })
        assertEquals(listOf("OPS/b.xhtml", "OPS/a.xhtml", "OPS/b.xhtml"), paragraphs.map { it.member })
        assertEquals(listOf(1, 2, 3), paragraphs.map { it.ranges.single().page })
        assertEquals(listOf("Repeat😀", "Repeat😀", "Repeat😀"), paragraphs.map { text(it, pages) })
    }

    @Test fun longElementSpansPagesWithoutSplittingEmojiOrLosingWhitespace() = temporary { root ->
        val content = "a".repeat(3999) + "😀" + "b".repeat(4001)
        val file = root.resolve("book.epub")
        epub(file, mapOf("a.xhtml" to document("<p>$content</p><p>Tail</p>")))
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        val mapped = TextbookExtraction.epubSourceMapping(file, pages)
        val paragraph = mapped.elements.single { it.localName == "p" && it.nodePath.last() == 0 }
        assertEquals(3, paragraph.ranges.size)
        assertEquals(content, text(paragraph, pages).trimEnd())
        assertEquals(3999, pages.first().text.length)
        mapped.elements.forEach { text(it, pages) }
    }

    @Test fun skippedSpineEntriesKeepOriginalIndexAndWhitespaceDoesNotChangeTreePaths() = temporary { root ->
        val file = root.resolve("book.epub")
        val xml = "<html xmlns='$xhtml'>\n<head><title>Ignored</title></head>\n<body>\n<!--comment--><p>Body</p>\n</body></html>"
        epub(file, linkedMapOf("skip.xhtml" to document("<p>Skip</p>"), "body.xhtml" to xml), listOf("skip.xhtml", "body.xhtml"), setOf(0))
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        val mapped = TextbookExtraction.epubSourceMapping(file, pages)
        assertTrue(mapped.elements.all { it.spineIndex == 1 && it.member == "OPS/body.xhtml" })
        val paragraph = mapped.elements.single { it.localName == "p" }
        assertEquals(listOf(1, 0), paragraph.nodePath)
        assertEquals("Body", text(paragraph, pages))
        assertFalse(pages.any { "Skip" in it.text })
    }

    @Test fun normalizationAndEmptyFormFeedSectionsUseExactConsumedSlices() {
        val raw = " \r\n\u0000A😀\r\nB\u000c\u000cC \r"
        val trace = EpubTextTrace()
        trace.record(emptyList(), xhtml, "body", 0, raw.length)
        trace.record(listOf(0), xhtml, "em", raw.indexOf("😀"), raw.indexOf("😀") + 2)
        val pages = TextbookExtraction.paginate(TextbookExtraction.clean(raw).trim())
        val sources = trace.project(raw, pages, 0, "a.xhtml", byteArrayOf(1), 0)
        assertEquals(listOf("A😀\nB", "", "C"), pages.map { it.text })
        assertEquals(listOf(1, 3), sources.first().ranges.map { it.page })
        assertEquals("A😀\nBC", text(sources.first(), pages))
        assertEquals("😀", text(sources.last(), pages))
    }

    @Test fun whitespaceOnlyChapterAndEmptyElementsHaveNoInventedTextAnchor() = temporary { root ->
        val file = root.resolve("book.epub")
        epub(file, linkedMapOf("blank.xhtml" to document("<p> </p><img/>"), "body.xhtml" to document("<p>Body</p>")))
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        assertEquals(listOf("", "Body"), pages.map { it.text })
        val sources = TextbookExtraction.epubSourceMapping(file, pages).elements
        assertTrue(sources.filter { it.spineIndex == 0 }.all { it.ranges.isEmpty() })
        assertEquals(2, sources.single { it.spineIndex == 1 && it.localName == "p" }.ranges.single().page)
    }

    @Test fun staleTextMissingPagesOrRenumberingRejectsAllMappings() = temporary { root ->
        val file = root.resolve("book.epub")
        epub(file, mapOf("a.xhtml" to document("<p>Body😀</p>")))
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        for (stale in listOf(emptyList(), listOf(pages.single().copy(text = "changed")), listOf(pages.single().copy(number = 2)), pages + pages)) {
            val result = TextbookExtraction.epubSourceMapping(file, stale)
            assertEquals(EpubSourceMapping.Status.STALE_TEXT, result.status)
            assertTrue(result.elements.isEmpty())
        }
    }

    @Test fun mappingSavedSourceAfterReopenPreservesCanonicalTextBlocksAndCitations() = temporary { root ->
        val file = root.resolve("book.epub")
        epub(file, mapOf("a.xhtml" to document("<h1>Lesson</h1><p>A cell is a living unit😀.</p><aside>A cell is a living unit😀.</aside>")))
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        val before = StudyCoachCourse.blocks(book.id, book.pages)
        Files.delete(file)
        val reopenedLibrary = TextbookLibrary(library.directory)
        val reopened = reopenedLibrary.book(book.id)
        val source = library.directory.resolve("${book.id}.epub")
        val mapped = TextbookExtraction.epubSourceMapping(source, reopened.pages)
        assertEquals(EpubSourceMapping.Status.MAPPED, mapped.status)
        assertEquals("A cell is a living unit😀.", text(mapped.elements.single { it.localName == "aside" }, reopened.pages))
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
