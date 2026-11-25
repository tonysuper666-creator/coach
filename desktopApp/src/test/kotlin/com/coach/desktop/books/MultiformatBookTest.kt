package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.*

class MultiformatBookTest {
    private fun fixture(root: Path, name: String): Path = root.resolve(name).also { path ->
        checkNotNull(javaClass.getResourceAsStream("/books/formats/$name")) { name }.use { Files.copy(it, path) }
    }
    private fun expected(name: String = "expected.txt") = checkNotNull(javaClass.getResourceAsStream("/books/formats/$name")).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun commonFormatsKeepPersistedPagesExactUtf16CitationsAndReaderJumps() = runBlocking {
        val cases = listOf(
            "utf8.txt" to "txt", "utf8-bom.text" to "txt", "gb18030.txt" to "txt", "gbk.txt" to "txt",
            "utf16le.txt" to "txt", "utf16be.txt" to "txt", "utf16le-no-bom.txt" to "txt", "utf16be-no-bom.txt" to "txt",
            "big5.txt" to "txt", "unicode.markdown" to "md", "lesson.docx" to "docx", "lesson.pptx" to "pptx",
            "article.html" to "html", "wrong-extension.txt" to "html", "wrong-extension.pdf" to "html",
            "lesson.mobi" to "mobi", "palmdoc.mobi" to "mobi", "huff.mobi" to "mobi", "lesson.azw3" to "azw3",
            "lesson.rtf" to "rtf", "lesson.odt" to "odt", "lesson.epub" to "epub",
        )
        for ((name, format) in cases) {
            val root = Files.createTempDirectory("coach-format-")
            try {
                val library = TextbookLibrary(root.resolve("library"))
                val source = fixture(root, name)
                val bytes = Files.readAllBytes(source)
                val book = library.importBook(source)
                assertEquals(format, book.format, name)
                assertTrue(book.pages.size in 1..4, "$name: ${book.pages.size} pages")
                assertTrue(book.lessons.isNotEmpty(), name)
                if (format == "txt") {
                    assertEquals(3, book.pages.size, name)
                    assertEquals(expected(if (name == "big5.txt") "expected-big5.txt" else "expected.txt").split("\n\n第").mapIndexed { i, s -> if (i == 0) s else "第$s" }, book.pages.map { it.text }, name)
                    assertTrue(book.pageLabel(2).contains("第2章"), name)
                }
                if (format == "html") {
                    assertEquals(3, book.pages.size, name)
                    assertFalse(book.pages.any { it.text.contains("不要导入") }, name)
                }
                if (format == "docx") {
                    assertEquals(2, book.pages.size)
                    assertContains(book.pages[0].text, "原料 | 二氧化碳和水")
                    assertContains(book.pages[0].text, "脚注 1：这是自制脚注")
                    assertFalse(book.pages[1].text.contains("脚注"))
                }
                if (format == "pptx") {
                    assertEquals(3, book.pages.size)
                    assertTrue(book.pages[0].text.startsWith("光合作用"))
                    assertContains(book.pages[0].text, "课堂备注")
                    assertTrue(book.pages[1].text.startsWith("细胞"))
                    assertEquals("第 2 张幻灯片", book.pageLabel(2))
                }
                if (format in setOf("mobi", "azw3")) {
                    assertEquals(3, book.pages.size, name)
                    for ((i, phrase) in listOf("光合作用", "细胞是", "呼吸作用").withIndex()) {
                        assertContains(book.pages[i].text, phrase, message = name)
                    }
                }
                Files.delete(source)
                val reopened = TextbookLibrary(library.directory).book(book.id)
                assertEquals(book.pages, reopened.pages, "$name: pages are persisted, never regenerated")
                assertContentEquals(bytes, Files.readAllBytes(library.directory.resolve("${book.id}.$format")))
                val blocks = StudyCoachCourse.blocks(book.id, book.pages).filterNot { it.heading }.shuffled(Random(1663)).take(3)
                assertEquals(3, blocks.size, name)
                val resolver = BookSourceResolver(library)
                val controller = TextbookController(library)
                controller.load(); controller.learn(LearningPath.QUESTION_FIRST); controller.enableLearning()
                for (block in blocks) {
                    val target = assertIs<BookSourceResolution.Resolved>(resolver.resolve(OwnedBookCitation(book.id, block.page, block.start, block.end), block.text), name).target
                    assertEquals(block.text, reopened.page(target.pdfPage)!!.text.substring(target.start, target.end), name)
                    assertTrue(resolver.stillValid(target), name)
                    if (format == "pptx") assertContains(target.label, "张幻灯片")
                    val lessonIndex = book.lessons.indexOfFirst { lesson -> lesson.blocks.any { it.id == block.id } }
                    assertTrue(lessonIndex >= 0, "$name: page ${block.page}, role ${book.structure?.pageRole(block.page)}, block ${block.start}-${block.end}, chapter pages ${book.lessons.map { it.firstPage to it.lastPage }}")
                    controller.lesson(lessonIndex)
                    controller.citationSource(book.id, book.lessons[lessonIndex].id, target.pdfPage)
                    assertEquals(BookScreen.READER, controller.state.value.screen, name)
                    assertEquals(block.page, controller.state.value.page, name)
                    controller.back()
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun damagedProtectedUnsafeAndOversizedFilesGiveActionableErrorsWithoutPartialImports() {
        val root = Files.createTempDirectory("coach-rejected-format-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            for ((name, message) in listOf("empty.txt" to "空", "broken.docx" to "损坏", "broken-index.azw3" to "损坏", "word-xxe.docx" to "损坏", "word-traversal.docx" to "文件位置", "word-oversize.docx" to "16 MB", "drm.mobi" to "版权保护", "drm.epub" to "版权保护")) {
                val error = assertFailsWith<IllegalArgumentException>(name) { library.importBook(fixture(root, name)) }
                assertContains(error.message.orEmpty(), message, message = name)
            }
            val encrypted = root.resolve("encrypted.pdf")
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                pdf.protect(StandardProtectionPolicy("owner-password", "reader-password", AccessPermission()).apply { encryptionKeyLength = 128 })
                pdf.save(encrypted.toFile())
            }
            assertContains(assertFailsWith<IllegalArgumentException> { library.importBook(encrypted) }.message.orEmpty(), "密码保护")
            val huge = root.resolve("oversized.txt")
            RandomAccessFile(huge.toFile(), "rw").use { it.setLength(257L * 1024 * 1024) }
            assertContains(assertFailsWith<IllegalArgumentException> { library.importBook(huge) }.message.orEmpty(), "256")
            assertTrue(library.catalog().isEmpty())
            assertTrue(Files.list(library.directory).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".import-") } })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun supplementaryCharactersNeverSplitAtPaginationOrCitationBoundaries() {
        val text = "第1章 Unicode\n\n" + "保留 😀 与 𠮷 的 UTF-16 下标。".repeat(600)
        val pages = BookText.chapters(text)
        for (page in pages) {
            assertFalse(page.text.firstOrNull()?.let(Character::isLowSurrogate) ?: false)
            assertFalse(page.text.lastOrNull()?.let(Character::isHighSurrogate) ?: false)
            for (block in StudyCoachCourse.blocks("synthetic", listOf(page))) assertEquals(block.text, page.text.substring(block.start, block.end))
        }
        assertEquals(text, pages.joinToString("") { it.text })
    }

    @Test fun largeDocxOver64MiBStreamsWithBoundedMemoryAndSupportsCancellation() {
        val root = Files.createTempDirectory("coach-large-docx-")
        try {
            val docx = root.resolve("elementary-large.docx")
            val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            val relNs = "http://schemas.openxmlformats.org/package/2006/relationships"
            val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
            java.util.zip.ZipOutputStream(Files.newOutputStream(docx)).use { zip ->
                fun entry(name: String, block: (java.io.OutputStream) -> Unit) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    block(zip)
                    zip.closeEntry()
                }
                entry("word/styles.xml") { out ->
                    out.write("""<w:styles xmlns:w="$w"><w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>""".toByteArray())
                }
                entry("word/footnotes.xml") { out ->
                    out.write("""<w:footnotes xmlns:w="$w"><w:footnote w:id="1"><w:p><w:r><w:t>大文档脚注验证</w:t></w:r></w:p></w:footnote></w:footnotes>""".toByteArray())
                }
                entry("word/_rels/document.xml.rels") { out ->
                    out.write("""<Relationships xmlns="$relNs"><Relationship Id="rId1" Type="$r/image" Target="media/eq.wmf"/></Relationships>""".toByteArray())
                }
                // >18 MiB word/document.xml (exceeds 16 MiB single-entry DOM limit) streamed without buffering.
                entry("word/document.xml") { out ->
                    out.write("""<w:document xmlns:w="$w" xmlns:r="$r" xmlns:a="$a"><w:body>""".toByteArray())
                    out.write("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第1章 代数基础</w:t></w:r></w:p>""".toByteArray())
                    out.write("""<w:p><w:r><w:t>线性方程有一个唯一解</w:t><w:footnoteReference w:id="1"/><a:blip r:embed="rId1"/></w:r></w:p>""".toByteArray())
                    val chunk = ("<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"24\"/></w:rPr><w:t>a</w:t></w:r></w:p>").toByteArray()
                    // Write ~19 MiB of valid Word XML markup while keeping extracted text concise.
                    repeat(260_000) { out.write(chunk) }
                    out.write("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""".toByteArray())
                    out.write("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第2章 尾章检查</w:t></w:r></w:p>""".toByteArray())
                    out.write("""<w:p><w:r><w:t>尾页正文完好可读。</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
                }
                // Add 4 x 14 MiB media blobs so total declared uncompressed size is ~75 MiB (>64 MiB).
                val zeros = ByteArray(64 * 1024)
                for (i in 1..4) {
                    entry("word/media/pad$i.wmf") { out -> repeat(224) { out.write(zeros) } }
                }
            }
            SafeBookArchive(docx).use { archive ->
                assertTrue(archive.size("word/document.xml")!! > 16L * 1024 * 1024)
                assertTrue(archive.declaredTotalBytes > 64L * 1024 * 1024)
            }
            val library = TextbookLibrary(root.resolve("library"))
            // Cancellation during streaming XML leaves no partial book.
            assertFailsWith<BookImportCancelled> {
                library.importBook(docx, BookImportControl(cancelledExternally = { true }))
            }
            assertTrue(library.catalog().isEmpty())
            val book = library.importBook(docx)
            assertEquals("docx", book.format)
            assertTrue(book.pages.size >= 2)
            assertContains(book.pages.first().text, "第1章 代数基础")
            assertContains(book.pages.first().text, "脚注 1：大文档脚注验证")
            assertContains(book.pages.last().text, "尾页正文完好可读")
            val reopened = TextbookLibrary(library.directory).book(book.id)
            assertEquals(book.pages, reopened.pages)
        } finally { root.toFile().deleteRecursively() }
    }
}
