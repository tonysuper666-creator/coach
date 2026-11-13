package com.coach.desktop.reader

import com.coach.client.ReaderReply
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TextbookLibrary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * 将本地 Windows TextbookLibrary 适配为 ReaderSource。
 *
 * 仅识别经书架显式导入的内容 SHA-256 ID；本地不可变副本的版本为 1。
 * HTTP 的 opaque ID 与页文本哈希都不是本地文件身份。读取不会隐式导入文件。
 * 书签仍由书架控制器保存；没有持久 CAS/幂等与批注存储时，拒绝 ReaderSource 写入。
 */
internal class LocalBooksReaderSource(val library: TextbookLibrary) : ReaderSource {

    private val localId = Regex("[0-9a-f]{64}")

    private fun findBook(document: String): Textbook? =
        if (localId.matches(document)) runCatching { library.book(document) }.getOrNull() else null

    private fun refused(status: Int, message: String) =
        ReaderReply(status, buildJsonObject { put("error", message) })

    private fun locationFailure(book: Textbook, pdfPage: Int, version: String = "1"): ReaderReply? = when {
        version != "1" -> refused(409, "资料版本已改变，请重新定位")
        pdfPage !in 1..book.pages.size -> refused(409, "页码超出了这本教材的范围")
        else -> null
    }

    override suspend fun catalog(): ReaderReply {
        val summaries = library.catalog()
        val array = buildJsonArray {
            for (s in summaries) {
                add(buildJsonObject {
                    put("id", s.id)
                    put("title", s.title)
                    put("version", "1")
                    put("pages", s.pageCount)
                })
            }
        }
        return ReaderReply(200, buildJsonObject { put("documents", array) })
    }

    override suspend fun state(document: String): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        val body = buildJsonObject {
            put("document", buildJsonObject {
                put("id", book.id)
                put("title", book.title)
                put("version", "1")
                put("pages", book.pages.size)
            })
            put("progress", buildJsonObject {
                put("pdfPage", book.readingPage)
                put("version", "1")
                put("revision", 0L) // 只读投影；不宣称提供 CAS 修订号。
                put("stale", false)
            })
            put("notes", JsonArray(emptyList()))
        }
        return ReaderReply(200, body)
    }

    override suspend fun page(document: String, pdfPage: Int): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        locationFailure(book, pdfPage)?.let { return it }
        val p = book.pages[pdfPage - 1]
        val sha = MessageDigest.getInstance("SHA-256").digest(p.text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val body = buildJsonObject {
            put("documentId", book.id)
            put("pdfPage", pdfPage)
            put("printedPage", pdfPage)
            put("version", "1")
            put("text", p.text)
            put("textSha256", sha)
        }
        return ReaderReply(200, body)
    }

    override suspend fun image(document: String, pdfPage: Int, version: String?): ByteArray? {
        val book = findBook(document) ?: return null
        if (version != "1" || pdfPage !in 1..book.pages.size) return null
        val bufferedImage = if (book.format == "pdf") {
            runCatching { library.renderPdf(book, pdfPage) }.getOrNull()
        } else {
            renderFallbackImage(book, pdfPage)
        } ?: return null

        val baos = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "png", baos)
        return baos.toByteArray()
    }

    private fun renderFallbackImage(book: Textbook, pdfPage: Int): BufferedImage {
        val img = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = java.awt.Color(0xFA, 0xF8, 0xF3)
            g.fillRect(0, 0, 1200, 1600)
            g.color = java.awt.Color(0x22, 0x22, 0x22)
            g.drawString("${book.title} · 第 $pdfPage 页", 50, 80)
            val p = book.pages.getOrNull(pdfPage - 1)
            var y = 140
            for (line in p?.text?.lines().orEmpty().take(40)) {
                g.drawString(line, 50, y)
                y += 30
            }
        } finally {
            g.dispose()
        }
        return img
    }

    override suspend fun study(document: String, pdfPage: Int): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        locationFailure(book, pdfPage)?.let { return it }
        val lessons = book.lessons.filter { pdfPage in it.firstPage..it.lastPage }
        val questions = buildJsonArray {
            for (lesson in lessons) {
                val anchor = lesson.blocks.firstOrNull { it.page == pdfPage && !it.heading }?.text ?: lesson.text
                add(buildJsonObject {
                    put("id", lesson.id)
                    put("stem", lesson.title)
                    put("status", "active")
                    put("anchor", anchor)
                })
            }
        }
        val body = buildJsonObject {
            put("documentId", book.id)
            put("pdfPage", pdfPage)
            put("version", "1")
            put("questions", questions)
            put("unlocated", 0)
            put("available", lessons.isNotEmpty())
        }
        return ReaderReply(200, body)
    }

    override suspend fun boxes(document: String, pdfPage: Int, version: String): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        locationFailure(book, pdfPage, version)?.let { return it }
        if (book.format != "pdf") {
            return ReaderReply(200, buildJsonObject {
                put("version", version)
                put("pdfPage", pdfPage)
                put("width", 1200.0)
                put("height", 1600.0)
                put("words", JsonArray(emptyList()))
            })
        }
        val pdfFile = library.directory.resolve("${book.id}.pdf").toFile()
        if (!pdfFile.isFile) return ReaderReply(404, null)

        val (w, h, words) = Loader.loadPDF(pdfFile).use { pdf ->
            val page = pdf.getPage(pdfPage - 1)
            val bounds = page.cropBox ?: page.mediaBox
            val pw = bounds.width.toDouble().coerceAtLeast(1.0)
            val ph = bounds.height.toDouble().coerceAtLeast(1.0)
            val stripper = PdfBoxWordStripper(pdfPage, pw, ph)
            stripper.getText(pdf)
            Triple(pw, ph, stripper.words)
        }

        val body = buildJsonObject {
            put("version", version)
            put("pdfPage", pdfPage)
            put("width", w)
            put("height", h)
            put("words", buildJsonArray {
                for (wb in words) {
                    add(buildJsonObject {
                        put("x0", wb.x0)
                        put("y0", wb.y0)
                        put("x1", wb.x1)
                        put("y1", wb.y1)
                        put("text", wb.text)
                    })
                }
            })
        }
        return ReaderReply(200, body)
    }

    override suspend fun saveProgress(document: String, version: String, pdfPage: Int, baseRevision: Long, mutationId: String): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        locationFailure(book, pdfPage, version)?.let { return it }
        return refused(501, "本地阅读进度由书架保存，暂不支持此写入方式")
    }

    override suspend fun saveNote(document: String, body: JsonObject): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        val version = (body["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val pdfPage = (body["pdfPage"] as? JsonPrimitive)?.intOrNull
            ?: return refused(409, "批注缺少可确认的页码")
        locationFailure(book, pdfPage, version)?.let { return it }
        return refused(501, "本地阅读器暂不支持保存批注，草稿仍保留")
    }
}

internal class PdfBoxWordStripper(
    targetPage: Int,
    private val pageWidth: Double,
    private val pageHeight: Double,
) : PDFTextStripper() {
    init {
        startPage = targetPage
        endPage = targetPage
        sortByPosition = true
    }

    val words = mutableListOf<WordBox>()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        var currentToken = StringBuilder()
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = 0.0
        var maxY = 0.0

        fun flush() {
            if (currentToken.isNotEmpty()) {
                val token = currentToken.toString().trim()
                if (token.isNotEmpty()) {
                    val x0 = minX.coerceIn(0.0, pageWidth)
                    val y0 = minY.coerceIn(0.0, pageHeight)
                    val x1 = maxX.coerceIn(0.0, pageWidth)
                    val y1 = maxY.coerceIn(0.0, pageHeight)
                    if (x1 > x0 && y1 > y0) {
                        words += WordBox(x0, y0, x1, y1, token)
                    }
                }
                currentToken.clear()
                minX = Double.MAX_VALUE
                minY = Double.MAX_VALUE
                maxX = 0.0
                maxY = 0.0
            }
        }

        for (tp in textPositions) {
            val u = tp.unicode
            if (u.isNullOrBlank()) {
                flush()
                continue
            }
            currentToken.append(u)
            val glyphX0 = tp.xDirAdj.toDouble()
            val glyphY0 = (tp.yDirAdj - tp.heightDir).toDouble()
            val glyphX1 = (tp.xDirAdj + tp.widthDirAdj).toDouble()
            val glyphY1 = tp.yDirAdj.toDouble()

            if (glyphX0 < minX) minX = glyphX0
            if (glyphY0 < minY) minY = glyphY0
            if (glyphX1 > maxX) maxX = glyphX1
            if (glyphY1 > maxY) maxY = glyphY1
        }
        flush()
    }
}
