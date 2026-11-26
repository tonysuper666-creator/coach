package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent spec for TON-1663 phase 1. Fixtures are original sentences from
 * `books/ton1663-phase1/author.py`. Assertions follow the phase-1 page, citation,
 * and error rules, not the shape of an earlier implementation test.
 *
 * A format the current build cannot import fails here with the refusal text, so the
 * gap is visible before the implementation card changes production code.
 */
class Ton1663Phase1SpecTest {
    private val zhTitle1 = "第1章 窗台"
    private val zhBody1 = "窗台上有一只白瓷杯。杯里是凉掉的茶。杯沿有一枚😀和𠮷。"
    private val zhTitle2 = "第2章 石阶"
    private val zhBody2 = "石阶被雨打湿。鞋底留下浅印。"
    private val big5Title1 = "第1章 窗臺"
    private val big5Body1 = "窗臺上有一隻茶杯。茶已經涼了。"
    private val big5Title2 = "第2章 石階"
    private val big5Body2 = "石階被雨打濕。鞋底留下淺印。"
    private val cup = "窗台上有一只白瓷杯。"
    private val step = "石阶被雨打湿。"
    private val footnote = "杯是自己买的。"

    @Test fun utf8WithoutBomSplitsOnChineseChapters() = chapters(
        "utf8-nobom.txt", "txt",
        zhTitle1 to zhBody1,
        zhTitle2 to zhBody2,
    ) { book ->
        val phrase = "😀和𠮷"
        val at = book.pages[0].text.indexOf(phrase)
        assertTrue(at >= 0, "UTF-8 页里应保留补充平面字符")
        assertEquals(5, phrase.length, "😀、和、𠮷 按 UTF-16 是 2+1+2 个码元")
        assertEquals(3, phrase.codePointCount(0, phrase.length), "这三个字是三个码位")
        assertEquals(phrase, book.pages[0].text.substring(at, at + phrase.length))
    }

    @Test fun gb18030RoundTripsChapterText() = chapters(
        "gb18030.txt", "txt",
        zhTitle1 to zhBody1,
        zhTitle2 to zhBody2,
    )

    @Test fun utf16LeWithBomRoundTripsChapterText() = chapters(
        "utf16le-bom.txt", "txt",
        zhTitle1 to zhBody1,
        zhTitle2 to zhBody2,
    )

    @Test fun big5RoundTripsTraditionalChapters() = chapters(
        "big5.txt", "txt",
        big5Title1 to big5Body1,
        big5Title2 to big5Body2,
    )

    @Test fun englishChapterHeadingSplitsPages() = chapters(
        "chapters-en.txt", "txt",
        "Chapter 1 Shore" to "The tide left a thin line of shells.",
        "Chapter 2 Dune" to "Grass held the loose sand.",
    )

    @Test fun markdownExtensionSplitsOnHeadings() {
        imported("window.markdown").use { opened ->
            val book = opened.book
            assertEquals("md", book.format, "后缀 .markdown 应按 Markdown 导入")
            assertEquals(2, book.pages.size, "Markdown 按标题分章，实际 ${preview(book)}")
            assertTrue(book.pages[0].text.contains(cup) && !book.pages[0].text.contains(step), preview(book))
            assertTrue(book.pages[1].text.contains(step) && !book.pages[1].text.contains(cup), preview(book))
            assertEquals("第 1 页", book.pageLabel(1))
            assertEquals("第 2 页", book.pageLabel(2))
            opened.assertCitations()
        }
    }

    @Test fun docxUsesPageBreaksTableAndFootnote() {
        imported("window.docx").use { opened ->
            val book = opened.book
            assertEquals("docx", book.format)
            assertEquals(2, book.pages.size, "分页符应把同一章的两段正文切成两页。样例没有第二章标题，删掉分页符就不能再凑出第二页，实际 ${preview(book)}")
            val first = book.pages[0].text
            val second = book.pages[1].text
            assertTrue(first.contains(zhTitle1), "章名应留在分页符前，实际：$first")
            assertTrue(first.contains(zhBody1), "分页符前的那段正文应在第 1 页，实际：$first")
            assertFalse(first.contains(step), "分页符后的那段正文不应留在第 1 页，实际：$first")
            assertOrdered(first, listOf("物品", "状态"))
            assertOrdered(first, listOf("茶", "已经凉了"))
            val lastCell = first.lastIndexOf("已经凉了")
            val noteAt = first.indexOf(footnote)
            assertTrue(lastCell >= 0 && noteAt > lastCell, "脚注应在该页最后一个表格单元格之后，实际：$first")
            assertTrue(second.contains(step), "分页符后的那段正文应单独成页，实际：$second")
            assertFalse(second.contains(zhBody1), "分页符前的正文不应跑到第 2 页，实际：$second")
            assertFalse(second.contains(zhTitle1), "第 2 页不应再靠章名切开，实际：$second")
            assertFalse(second.contains("已经凉了"), "表格应留在分页符前，实际：$second")
            assertFalse(second.contains(footnote), "脚注不应跑到下一页：$second")
            assertEquals("第 1 页", book.pageLabel(1))
            assertEquals("第 2 页", book.pageLabel(2))
            opened.assertCitations()
        }
    }

    @Test fun pptxIsOneSlidePerPageWithNotes() {
        imported("window.pptx").use { opened ->
            val book = opened.book
            assertEquals("pptx", book.format)
            assertEquals(2, book.pages.size, "一张幻灯片一页，实际 ${preview(book)}")
            assertOnSlide(book, 0, cup, "先看杯沿的水印。")
            assertOnSlide(book, 1, step, "记下鞋印的方向。")
            assertEquals("第 1 张幻灯片", book.pageLabel(1))
            assertEquals("第 2 张幻灯片", book.pageLabel(2))
            opened.assertCitations()
        }
    }

    @Test fun htmlDropsNavigationAndScript() = htmlLike("window.html")

    @Test fun txtSuffixThatIsHtmlImportsAsHtml() = htmlLike("html-as.txt")

    @Test fun mobiWithoutDrmSplitsOnChapters() {
        imported("window.mobi").use { opened ->
            val book = opened.book
            assertEquals("mobi", book.format)
            assertEquals(2, book.pages.size, "无 DRM 的 Kindle 应按章切页，实际 ${preview(book)}")
            assertTrue(book.pages[0].text.contains(cup) && !book.pages[0].text.contains(step), preview(book))
            assertTrue(book.pages[1].text.contains(step), preview(book))
            assertTrue(book.pageLabel(1).contains("第 1 页") && book.pageLabel(1).contains(zhTitle1), book.pageLabel(1))
            assertTrue(book.pageLabel(2).contains("第 2 页") && book.pageLabel(2).contains(zhTitle2), book.pageLabel(2))
            opened.assertCitations()
        }
    }

    @Test fun rtfSplitsOnHeadings() {
        imported("window.rtf").use { opened ->
            val book = opened.book
            assertEquals("rtf", book.format)
            assertEquals(2, book.pages.size, "RTF 应按标题切页，实际 ${preview(book)}")
            assertTrue(book.pages[0].text.contains(cup) && !book.pages[0].text.contains(step), preview(book))
            assertTrue(book.pages[1].text.contains(step), preview(book))
            assertEquals("第 1 页", book.pageLabel(1))
            assertEquals("第 2 页", book.pageLabel(2))
            opened.assertCitations()
        }
    }

    @Test fun odtSplitsOnHeadingsAndKeepsTable() {
        imported("window.odt").use { opened ->
            val book = opened.book
            assertEquals("odt", book.format)
            assertEquals(2, book.pages.size, "ODT 应按标题切页，实际 ${preview(book)}")
            assertTrue(book.pages[0].text.contains(cup), preview(book))
            assertOrdered(book.pages[0].text, listOf("物品", "状态"))
            assertTrue(book.pages[1].text.contains(step) && !book.pages[1].text.contains("物品"), preview(book))
            assertEquals("第 1 页", book.pageLabel(1))
            assertEquals("第 2 页", book.pageLabel(2))
            opened.assertCitations()
        }
    }

    @Test fun epubKeepsShortDocumentText() {
        imported("window.epub").use { opened ->
            val book = opened.book
            assertEquals("epub", book.format)
            assertEquals(1, book.pages.size, "短的单 spine EPUB 应恰好一页，不按章再拆，实际 ${preview(book)}")
            val only = book.pages[0].text
            assertTrue(only.contains(cup), "第 1 页应含第一句原文，实际：$only")
            assertTrue(only.contains(step), "第 1 页应含第二句原文，实际：$only")
            assertEquals("第 1 页", book.pageLabel(1))
            opened.assertCitations()
        }
    }

    @Test fun pdfKeepsOneSentencePerPage() {
        imported("window.pdf").use { opened ->
            val book = opened.book
            assertEquals("pdf", book.format)
            assertEquals(2, book.pages.size, "PDF 按原页切，实际 ${preview(book)}")
            assertTrue(book.pages[0].text.contains("The white cup sits on the sill."), preview(book))
            assertTrue(book.pages[1].text.contains("Rain darkened the stone step."), preview(book))
            assertEquals("第 1 页", book.pageLabel(1))
            assertEquals("第 2 页", book.pageLabel(2))
            opened.assertCitations()
        }
    }

    @Test fun encryptedPdfSaysPasswordProtected() {
        val root = Files.createTempDirectory("ton1663-pdf-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val encrypted = root.resolve("encrypted.pdf")
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                pdf.protect(StandardProtectionPolicy("owner-password", "reader-password", AccessPermission()).apply {
                    encryptionKeyLength = 128
                })
                pdf.save(encrypted.toFile())
            }
            val error = assertIs<IllegalArgumentException>(runCatching { library.importBook(encrypted) }.exceptionOrNull())
            assertHuman(error.message.orEmpty(), "密码")
            assertTrue(library.catalog().isEmpty(), "加密 PDF 不应留下半本")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun drmKindleSaysCopyrightLock() {
        val message = rejected("window-drm.mobi")
        assertHuman(message, "版权保护", "打不开")
        assertTrue(message.contains("Kindle"), "应说明是 Kindle 版权保护，实际：$message")
        assertFalse(message.contains("格式不支持"), message)
    }

    @Test fun truncatedArchiveSaysDamaged() {
        val message = rejected("truncated-archive.docx")
        assertHuman(message)
        assertTrue(message.contains("损坏") || message.contains("不完整"), "截断压缩包应说明文件坏了，实际：$message")
    }

    @Test fun zipPathTraversalDoesNotEscape() {
        val root = Files.createTempDirectory("ton1663-slip-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val error = assertIs<IllegalArgumentException>(
                runCatching { library.importBook(copyFixture(root, "traversal.docx")) }.exceptionOrNull(),
            )
            assertHuman(error.message.orEmpty(), "文件位置")
            assertTrue(library.catalog().isEmpty())
            assertFalse(Files.exists(root.resolve("ton1877-escaped.txt")), "路径穿越不应把条目写到压缩包外面")
            assertFalse(Files.exists(library.directory.resolve("ton1877-escaped.txt")), "路径穿越不应把条目写进书库目录")
            assertFalse(treeContains(library.directory, "must-not-escape"), "穿越条目的内容不应写入书库")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun docxExternalEntityDoesNotReadOutside() {
        val root = Files.createTempDirectory("ton1663-xxe-")
        try {
            val secret = "TON1877-ENTITY-SECRET"
            val secretFile = root.resolve("secret.txt")
            Files.writeString(secretFile, secret)
            val docx = root.resolve("runtime-xxe.docx")
            writeXxe(docx, secretFile.toUri().toString())
            val library = TextbookLibrary(root.resolve("library"))
            val outcome = runCatching { library.importBook(docx) }
            outcome.getOrNull()?.pages?.forEach { page ->
                assertFalse(page.text.contains(secret), "外部实体不应进正文")
            }
            if (outcome.isFailure) {
                val error = assertIs<IllegalArgumentException>(outcome.exceptionOrNull())
                assertHuman(error.message.orEmpty())
                assertFalse(error.message.orEmpty().contains(secret))
                assertTrue(library.catalog().isEmpty(), "拒绝外部实体后不应留下半本")
            }
            assertFalse(treeContains(library.directory, secret), "外部实体的内容不应写入书库")
            val bundled = runCatching { library.importBook(copyFixture(root, "xxe.docx")) }
            bundled.getOrNull()?.pages?.forEach { page ->
                assertFalse(page.text.contains("ton1877-do-not-read"))
            }
            if (bundled.isFailure) {
                assertHuman(assertIs<IllegalArgumentException>(bundled.exceptionOrNull()).message.orEmpty())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun chapters(
        name: String,
        format: String,
        vararg parts: Pair<String, String>,
        extra: (Textbook) -> Unit = {},
    ) {
        imported(name).use { opened ->
            val book = opened.book
            assertEquals(format, book.format, name)
            assertEquals(parts.size, book.pages.size, "$name 应按章切成 ${parts.size} 页，实际 ${preview(book)}")
            parts.forEachIndexed { index, (title, body) ->
                val text = book.pages[index].text
                assertTrue(text.contains(title), "$name 第 ${index + 1} 页应含「$title」，实际：$text")
                assertTrue(text.contains(body), "$name 第 ${index + 1} 页应逐字含正文，实际：$text")
                val at = text.indexOf(body)
                assertEquals(body, text.substring(at, at + body.length), "$name 出处偏移应按 UTF-16 下标取到同一段文字")
                parts.forEachIndexed { other, (_, otherBody) ->
                    if (other != index) assertFalse(text.contains(otherBody), "$name 第 ${index + 1} 页混进了另一章")
                }
                val label = book.pageLabel(index + 1)
                assertTrue(label.contains("第 ${index + 1} 页") && label.contains(title), "$name 出处应为「第 N 页（章名）」，实际：$label")
            }
            extra(book)
            opened.assertCitations()
        }
    }

    private fun htmlLike(name: String) {
        imported(name).use { opened ->
            val book = opened.book
            assertEquals("html", book.format, "$name 应按内容认成网页，而不是按后缀")
            val joined = book.pages.joinToString("\n") { it.text }
            assertFalse(joined.contains("不要带上这段导航"), joined)
            assertFalse(joined.contains("不要带上这段脚本"), joined)
            assertFalse(joined.contains("不要带上这段页脚"), joined)
            assertEquals(2, book.pages.size, "网页去掉导航和脚本后按标题分章，实际 ${preview(book)}")
            assertTrue(book.pages[0].text.contains(cup) && !book.pages[0].text.contains(step), preview(book))
            assertTrue(book.pages[1].text.contains(step), preview(book))
            assertEquals("第 1 页", book.pageLabel(1))
            assertEquals("第 2 页", book.pageLabel(2))
            opened.assertCitations()
        }
    }

    private fun assertOnSlide(book: Textbook, index: Int, body: String, note: String) {
        val text = book.pages[index].text
        assertTrue(text.contains(body), "第 ${index + 1} 张应含幻灯片正文，实际：$text")
        assertTrue(text.contains(note), "备注应接在幻灯片后面，实际：$text")
        assertTrue(text.indexOf(note) > text.indexOf(body), "备注应在正文之后：$text")
    }

    private fun assertOrdered(text: String, cells: List<String>) {
        var from = 0
        for (cell in cells) {
            val at = text.indexOf(cell, from)
            assertTrue(at >= from, "文字表应按原顺序含「$cell」，实际：$text")
            from = at + cell.length
        }
    }

    private fun Imported.assertCitations() {
        val blocks = StudyCoachCourse.blocks(book.id, book.pages).filter { it.text.isNotBlank() }
        assertTrue(blocks.isNotEmpty(), "${book.format} 应能从页文字取出出处")
        for (block in blocks) {
            val page = checkNotNull(book.page(block.page)) { "${book.format} 出处页 ${block.page} 不存在" }
            assertTrue(block.start in 0..page.text.length && block.end in block.start..page.text.length, block.toString())
            assertEquals(block.text, page.text.substring(block.start, block.end), "${book.format} 出处偏移不是该页 UTF-16 下标")
            if (block.start > 0 && block.start < page.text.length) {
                val splitPair = Character.isHighSurrogate(page.text[block.start - 1]) && Character.isLowSurrogate(page.text[block.start])
                assertFalse(splitPair, "${book.format} 出处切进了代理对")
            }
        }
        val reopened = TextbookLibrary(library.directory).book(book.id)
        assertEquals(book.pages.map { it.number to it.text }, reopened.pages.map { it.number to it.text }, "导入后再打开，页文字应逐字相同")
    }

    private fun rejected(name: String): String {
        val root = Files.createTempDirectory("ton1663-bad-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val error = assertIs<IllegalArgumentException>(
                runCatching { library.importBook(copyFixture(root, name)) }.exceptionOrNull(),
            )
            assertTrue(library.catalog().isEmpty(), "$name 失败后书库应是空的")
            return error.message.orEmpty()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun imported(name: String): Imported {
        val root = Files.createTempDirectory("ton1663-")
        val library = TextbookLibrary(root.resolve("library"))
        val source = copyFixture(root, name)
        val book = try {
            library.importBook(source)
        } catch (error: IllegalArgumentException) {
            root.toFile().deleteRecursively()
            throw AssertionError("《$name》按 TON-1663 应能导入，实际被拒绝：${error.message}", error)
        }
        return Imported(root, library, book)
    }

    private fun copyFixture(root: Path, name: String): Path {
        val source = root.resolve(name)
        val stream = checkNotNull(javaClass.getResourceAsStream("/books/ton1663-phase1/bytes/$name")) { "缺少样例 $name" }
        stream.use { Files.copy(it, source) }
        return source
    }

    private fun assertHuman(message: String, vararg needles: String) {
        assertTrue(message.isNotBlank(), "出错时应有人话，不能是空消息")
        assertTrue(message.any { it.code > 127 }, "提示应是中文，实际：$message")
        assertFalse(message.contains("格式不支持"), "不应只说格式不支持，实际：$message")
        assertFalse(message.contains("unsupported", ignoreCase = true), message)
        for (needle in needles) assertTrue(message.contains(needle), "提示应说明「$needle」，实际：$message")
    }

    private fun writeXxe(docx: Path, systemId: String) {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE w:document [<!ENTITY leak SYSTEM "$systemId">]>
            <w:document xmlns:w="$w"><w:body><w:p><w:r><w:t>&leak;</w:t></w:r></w:p>
            <w:p><w:r><w:t>$cup</w:t></w:r></w:p></w:body></w:document>
        """.trimIndent()
        ZipOutputStream(Files.newOutputStream(docx)).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun treeContains(dir: Path, needle: String): Boolean {
        if (!Files.exists(dir)) return false
        Files.walk(dir).use { paths ->
            return paths.anyMatch { path ->
                Files.isRegularFile(path) && Files.size(path) < 2_000_000 &&
                    runCatching { String(Files.readAllBytes(path), Charsets.UTF_8).contains(needle) }.getOrDefault(false)
            }
        }
    }

    private fun preview(book: Textbook) = book.pages.joinToString(" | ") { it.text.replace("\n", " ").take(48) }

    private class Imported(val root: Path, val library: TextbookLibrary, val book: Textbook) : AutoCloseable {
        override fun close() {
            root.toFile().deleteRecursively()
        }
    }
}
