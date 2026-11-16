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
                    assertTrue(lessonIndex >= 0, name)
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
}
