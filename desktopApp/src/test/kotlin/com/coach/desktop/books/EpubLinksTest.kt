package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class EpubLinksTest {
    private val xhtml = "http://www.w3.org/1999/xhtml"
    private fun document(body: String) = "<html xmlns='$xhtml'><head><title>T</title></head><body>$body</body></html>"

    private fun epub(path: Path, documents: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>")
            val manifest = documents.keys.mapIndexed { i, name -> "<item id='d$i' href='$name'/>" }.joinToString("")
            val spine = documents.keys.mapIndexed { i, _ -> "<itemref idref='d$i'/>" }.joinToString("")
            entry("OPS/package.opf", "<package><metadata><title>Notes</title></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>")
            documents.forEach { (name, content) -> entry("OPS/$name", content) }
        }
    }

    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-epub-links-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    private fun sample(root: Path): Path {
        val file = root.resolve("book.epub")
        epub(file, linkedMapOf(
            "cover.xhtml" to document("<div></div>"),
            "chapter.xhtml" to document(
                "<h1>One</h1><p id='p1'>Alpha beta<a id='ref-1' href='notes.xhtml#note-1'>1</a> gamma.</p>" +
                    "<p>Delta<a id='ref-2' href='notes.xhtml#note-2'>2</a>.</p>",
            ),
            "notes.xhtml" to document(
                "<h1>Notes</h1><ol><li id='note-1'><p>First note <a href='chapter.xhtml#ref-1'>\u21a9</a></p></li>" +
                    "<li id='note-2'><p>Second note <a href='chapter.xhtml#ref-2'>\u21a9</a> <a href='https://example.com/x'>web</a></p></li></ol>",
            ),
        ))
        return file
    }

    @Test fun footnoteLinksResolveForwardAndBack() = temporary { root ->
        val file = sample(root)
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        val links = assertNotNull(TextbookExtraction.epubLinks(file, pages))
        assertEquals(4, links.count, "只有书内链接，外部网址不算")
        fun linkTexts(page: Int) = links.onPage(page).map { pages[page - 1].text.substring(it.start, it.end) }
        val bodyPage = pages.indexOfFirst { it.text.contains("Alpha beta") } + 1
        val notesPage = pages.indexOfFirst { it.text.contains("First note") } + 1
        assertEquals(listOf("1", "2"), linkTexts(bodyPage))
        val forward = links.onPage(bodyPage).first()
        assertEquals(notesPage, forward.target.importPage)
        // 目标落在「First note」这一条的开头，而不是页首。
        assertTrue(pages[notesPage - 1].text.startsWith("First note", forward.target.offsetUtf16) ||
            pages[notesPage - 1].text.substring(forward.target.offsetUtf16).trimStart().startsWith("First note"))
        val back = links.onPage(notesPage).first { pages[notesPage - 1].text.substring(it.start, it.end) == "\u21a9" }
        assertEquals(bodyPage, back.target.importPage)
        assertTrue(pages[bodyPage - 1].text.substring(back.target.offsetUtf16).startsWith("1"))
        assertSame(forward, links.at(bodyPage, forward.start))
        assertNull(links.at(bodyPage, forward.end))
    }

    @Test fun staleOrChangedPagesGiveNoLinks() = temporary { root ->
        val file = sample(root)
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        assertNull(TextbookExtraction.epubLinks(file, pages.map { it.copy(text = it.text + "!") }))
    }

    @Test fun textlessSpineMembersDoNotBecomeBlankPages() = temporary { root ->
        val file = sample(root)
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        assertTrue(pages.none { it.text.isBlank() }, "没有文字也没有图的章节不能成为空白页")
        assertEquals(pages.indices.map { it + 1 }, pages.map { it.number })
    }

    @Test fun imageOnlyMembersKeepTheirPage() = temporary { root ->
        val file = root.resolve("cover.epub")
        epub(file, linkedMapOf(
            "cover.xhtml" to document("<div><img src='cover.png'/></div>"),
            "text.xhtml" to document("<p>Hello</p>"),
        ))
        val pages = TextbookExtraction.extract(file, "epub", "fallback").pages
        assertEquals(2, pages.size)
    }

    @Test fun publicSenecaEndnotesRoundTrip() {
        val file = Path.of("D:/agent/materials/public-corpus/nonfiction/seneca-dialogues/seneca-dialogues.epub")
        assumeCorpus(file)
        val pages = TextbookExtraction.extract(file, "epub", "x").pages
        val links = assertNotNull(TextbookExtraction.epubLinks(file, pages))
        val noteRefs = pages.indices.flatMap { index ->
            links.onPage(index + 1).filter { pages[index].text.substring(it.start, it.end).all(Char::isDigit) }
        }
        assertTrue(noteRefs.size >= 150, "正向尾注链接应有 158 条，实得 ${noteRefs.size}")
        val backArrows = pages.indices.flatMap { index ->
            links.onPage(index + 1).filter { pages[index].text.substring(it.start, it.end).contains('\u21a9') }
        }
        assertTrue(backArrows.size >= 150, "返回箭头应有 156 条，实得 ${backArrows.size}")
    }

    @Test fun publicMeditationsHasNoBlankPage() {
        val file = Path.of("D:/agent/materials/public-corpus/nonfiction/meditations/meditations.epub")
        assumeCorpus(file)
        val book = TextbookExtraction.extract(file, "epub", "x")
        val pages = book.pages
        assertEquals(0, pages.count { it.text.isBlank() && book.illustrations.none { image -> image.page == it.number } })
        assertEquals(138, pages.size, "封面外壳页不进阅读顺序，原 139 页变 138 页")
    }

    @Test fun meditationsTitlePageLinesShareOneSheetWithTheNextHeading() {
        val file = Path.of("D:/agent/materials/public-corpus/nonfiction/meditations/meditations.epub")
        assumeCorpus(file)
        val book = TextbookExtraction.extract(file, "epub", "x")
        val nav = TextbookExtraction.epubNavigation(file, book.pages)!!
        val structure = BookStructurePlanner.plan("id", "Meditations", "epub", book.pages, nav.outline, emptyMap(), nav.nativeBodyStart)
        val plan = planBookChapters(book.pages, tocNodes = structure.tocNodes, bodyStart = BookBoundaries.bodyStart(book.pages, structure, null))
        val head = plan.chapters.take(6).map { it.title to it.end - it.start }
        assertTrue(plan.chapters.none { it.title == "MEDITATIONS" }, "书名行不能单独成屏：$head")
        assertTrue(plan.chapters.none { it.title != null && it.end - it.start < 200 && plan.chapters.indexOf(it) < 4 }, "前置短章应并入后一章：$head")
        println("PROBE $head")
    }

    @Test fun mergeKeepsRealChaptersAndOnlyFoldsLeadingTitleLines() {
        val source = "# TITLE\nBy Someone\n\n# Part\n\n" + "Body sentence. ".repeat(100) + "\n# Short\nTiny.\n# Next\n" + "More text. ".repeat(200)
        val at = { text: String -> source.indexOf(text) }
        val chapters = listOf(Triple(at("# TITLE"), at("TITLE"), "TITLE"), Triple(at("# Part"), at("Part"), "Part"),
            Triple(at("# Short"), at("Short"), "Short"), Triple(at("# Next"), at("Next"), "Next"))
        val merged = mergeTitlePageChapters(source, chapters)
        assertEquals(listOf("Part", "Short", "Next"), merged.map { it.third })
        assertEquals(at("# TITLE"), merged.first().first)
        assertEquals(chapters.drop(1).map { it.first }.drop(1), merged.drop(1).map { it.first })
    }

    private fun assumeCorpus(file: Path) = org.junit.Assume.assumeTrue("缺公开书库：$file", Files.isRegularFile(file))
}
