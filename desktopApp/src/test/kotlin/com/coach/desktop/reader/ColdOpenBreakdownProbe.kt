package com.coach.desktop.reader

import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 自有旁路观测（只读归因，不是功能测试）：用自造的 244 页有字 PDF，在全新测试 JVM 里按 LaunchedEffect 的真实顺序
 * （loadCatalog → open → show(2)）驱动生产 ReaderRemote + LocalBooksReaderSource，给每个 ReaderSource 调用打钟点和线程，
 * 并与"同书第二次进入"对比。全部无窗口、不取输入锁；结果打印为 COLD_OPEN_PROBE 行。只有环境变量 COACH_COLDOPEN_PROBE=1 才跑。
 */
class ColdOpenBreakdownProbe {
    private class Timed(private val inner: ReaderSource, val log: MutableList<String>) : ReaderSource by inner {
        private suspend inline fun <T> t(name: String, block: () -> T): T {
            val t0 = System.nanoTime(); val r = block()
            log += "%-8s %9.2f ms  thread=%s".format(name, (System.nanoTime() - t0) / 1e6, Thread.currentThread().name); return r
        }
        override suspend fun catalog() = t("catalog") { inner.catalog() }
        override suspend fun state(document: String) = t("state") { inner.state(document) }
        override suspend fun page(document: String, pdfPage: Int) = t("page") { inner.page(document, pdfPage) }
        override suspend fun study(document: String, pdfPage: Int) = t("study") { inner.study(document, pdfPage) }
        override suspend fun image(document: String, pdfPage: Int, version: String?) = t("image") { inner.image(document, pdfPage, version) }
        override suspend fun boxes(document: String, pdfPage: Int, version: String) = t("boxes") { inner.boxes(document, pdfPage, version) }
    }

    @Test fun coldOpenBreakdown() {
        if (System.getenv("COACH_COLDOPEN_PROBE") != "1") return
        val root = Files.createTempDirectory("coach-coldopen-")
        val pdf = root.resolve("book.pdf")
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN)
            for (p in 1..244) {
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(font, 10f); cs.setLeading(12f); cs.newLineAtOffset(40f, 740f)
                    repeat(55) { line -> cs.showText("Page $p line $line the quick brown fox jumps over the lazy dog while reading chapter text number $line"); cs.newLine() }
                    cs.endText()
                }
            }
            doc.save(pdf.toFile())
        }
        val imported = TextbookLibrary(root.resolve("lib")).importBook(pdf)
        println("COLD_OPEN_PROBE book pages=${imported.pages.size} textChars=${imported.pages.sumOf { it.text.length }}")
        // 全新库实例，模拟应用启动后第一次打开（预热在库构造时已启动，与应用一致）
        val library = TextbookLibrary(root.resolve("lib"))
        val log = CopyOnWriteArrayList<String>()
        val remote = ReaderRemote(Timed(LocalBooksReaderSource(library), log))
        fun entry(label: String) {
            log += "--- $label"
            val t0 = System.nanoTime()
            runBlocking { remote.loadCatalog(); remote.open(imported.id); remote.show(2) }
            log += "%-8s %9.2f ms".format("TOTAL", (System.nanoTime() - t0) / 1e6)
        }
        entry("first entry"); entry("second entry")
        log.forEach { println("COLD_OPEN_PROBE $it") }
        assertTrue(remote.page is PageState.Ready)
    }
}
