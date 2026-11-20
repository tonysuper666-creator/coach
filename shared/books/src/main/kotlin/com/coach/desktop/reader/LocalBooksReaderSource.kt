package com.coach.desktop.reader

import com.coach.client.ReaderReply
import com.coach.desktop.books.BookNoteRecord
import com.coach.desktop.books.BookNoteWriteResult
import com.coach.desktop.books.BookPdfLoader
import com.coach.desktop.books.BookPdfStripper
import com.coach.desktop.books.BookPdfTextPosition
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.StudyCoachCourse
import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * 将本地 Windows TextbookLibrary 适配为 ReaderSource。
 *
 * 仅识别经书架显式导入的内容 SHA-256 ID；本地不可变副本的版本为 1。
 * HTTP 的 opaque ID 与页文本哈希都不是本地文件身份。读取不会隐式导入文件。
 * 书签仍由书架控制器保存；批注经 SQLite `book_notes` 提供 CAS 与幂等持久化。
 */
internal class LocalBooksReaderSource(val library: TextbookLibrary, private val loadPageImages: Boolean = true) : ReaderSource {
    // 书库（SQLite）、PDFBox、PNG 编码都是阻塞工作。调用方在 EDT 上时挪到 IO 线程执行，EDT 只挂起等待、结果由调用方的调度器接回，
    // 状态提交仍在调用方；调用方本来就不在 EDT（后台线程、离屏测试）时原地执行，行为与以前完全一致。
    private suspend inline fun <T> offEdt(crossinline block: () -> T): T =
        if (isBookUiThread()) withContext(Dispatchers.IO) { block() } else block()

    private val localId = Regex("[0-9a-f]{64}")

    private fun findBook(document: String): Textbook? =
        if (localId.matches(document)) runCatching { library.book(document) }.getOrNull() else null

    private fun refused(status: Int, message: String) =
        ReaderReply(status, buildJsonObject { put("error", message) })

    private fun locationFailure(book: Textbook, pdfPage: Int, version: String = "1"): ReaderReply? = when {
        version != "1" -> refused(409, "资料版本已改变，请重新定位")
        book.page(pdfPage) == null -> refused(409, "页码超出了这本教材的范围")
        else -> null
    }

    override suspend fun catalog(): ReaderReply = offEdt { catalogBlocking() }

    private fun catalogBlocking(): ReaderReply {
        val summaries = library.catalog()
        val array = buildJsonArray {
            for (s in summaries) {
                add(buildJsonObject {
                    put("id", s.id)
                    put("title", s.title)
                    put("version", "1")
                    put("pages", s.lastPage)
                })
            }
        }
        return ReaderReply(200, buildJsonObject { put("documents", array) })
    }

    private fun pageTextSha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override suspend fun state(document: String): ReaderReply = offEdt { stateBlocking(document) }

    private fun stateBlocking(document: String): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        val notesArray = buildJsonArray {
            for (n in library.allNotes(book.id)) {
                val p = book.page(n.pdfPage)
                val stale = n.version != "1" ||
                    p == null ||
                    n.textSha256 != pageTextSha256(p.text) ||
                    n.start !in 0 until n.end ||
                    n.end > p.text.length ||
                    p.text.substring(n.start, n.end) != n.quote
                add(buildJsonObject {
                    put("id", n.id)
                    put("version", n.version)
                    put("pdfPage", n.pdfPage)
                    put("start", n.start)
                    put("end", n.end)
                    put("quote", n.quote)
                    put("note", n.note)
                    put("textSha256", n.textSha256)
                    put("revision", n.revision)
                    put("stale", stale)
                })
            }
        }
        val body = buildJsonObject {
            put("document", buildJsonObject {
                put("id", book.id)
                put("title", book.title)
                put("version", "1")
                put("pages", book.pages.maxOf { it.number })
            })
            put("progress", buildJsonObject {
                put("pdfPage", book.readingPage)
                put("version", "1")
                put("revision", 0L) // 只读投影；不宣称提供 CAS 修订号。
                put("stale", false)
            })
            put("notes", notesArray)
        }
        return ReaderReply(200, body)
    }

    override suspend fun page(document: String, pdfPage: Int): ReaderReply = offEdt { pageBlocking(document, pdfPage) }

    private fun pageBlocking(document: String, pdfPage: Int): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        locationFailure(book, pdfPage)?.let { return it }
        val p = checkNotNull(book.page(pdfPage))
        val sha = pageTextSha256(p.text)
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

    override suspend fun image(document: String, pdfPage: Int, version: String?): ByteArray? =
        if (loadPageImages) offEdt { imageBlocking(document, pdfPage, version) } else null

    private fun imageBlocking(document: String, pdfPage: Int, version: String?): ByteArray? {
        val book = findBook(document) ?: return null
        if (version != "1" || book.page(pdfPage) == null) return null
        val bufferedImage = if (book.format == "pdf" || book.format in com.coach.desktop.books.BookFormats.images) {
            runCatching { library.renderOriginal(book, pdfPage) }.getOrNull()
        } else {
            renderFallbackImage(book, pdfPage)
        } ?: return null

        return encodeReaderPng(bufferedImage)
    }

    override suspend fun study(document: String, pdfPage: Int): ReaderReply = offEdt { studyBlocking(document, pdfPage) }

    private fun studyBlocking(document: String, pdfPage: Int): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        locationFailure(book, pdfPage)?.let { return it }
        val lessons = book.lessons.filter { pdfPage in it.firstPage..it.lastPage }
        val questions = buildJsonArray {
            for (lesson in lessons) {
                val anchor = StudyCoachCourse.sourceQuote(lesson, pdfPage).orEmpty()
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

    override suspend fun boxes(document: String, pdfPage: Int, version: String): ReaderReply = offEdt { boxesBlocking(document, pdfPage, version) }

    private fun boxesBlocking(document: String, pdfPage: Int, version: String): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        locationFailure(book, pdfPage, version)?.let { return it }
        if (book.format != "pdf" || book.page(pdfPage)?.ocr == true) {
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

        val (w, h, words) = BookPdfLoader.read(pdfFile) { pdf ->
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

    override suspend fun saveNote(document: String, body: JsonObject): ReaderReply = offEdt { saveNoteBlocking(document, body) }

    private fun saveNoteBlocking(document: String, body: JsonObject): ReaderReply {
        val book = findBook(document) ?: return ReaderReply(404, null)
        val version = (body["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val pdfPage = (body["pdfPage"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return refused(409, "批注缺少可确认的页码")
        locationFailure(book, pdfPage, version)?.let { return it }

        val id = (body["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: return refused(400, "批注锚点或字段不完整")
        val mutationId = (body["mutationId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: return refused(400, "批注锚点或字段不完整")
        val baseRevision = (body["baseRevision"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0L }
            ?: return refused(400, "批注锚点或字段不完整")
        val start = (body["start"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val end = (body["end"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (start == null || end == null || start !in 0 until end || end - start > 4000) {
            return refused(400, "批注锚点或字段不完整")
        }
        val quote = (body["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return refused(400, "批注锚点或字段不完整")
        val noteText = (body["note"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 8000 }
            ?: return refused(400, "批注锚点或字段不完整")
        val textSha256 = (body["textSha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return refused(400, "批注锚点或字段不完整")
        val deleted = (body["deleted"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

        val page = checkNotNull(book.page(pdfPage))
        if (textSha256 != pageTextSha256(page.text)) {
            return refused(409, "页文字已变化，请重新定位后再保存")
        }
        if (end > page.text.length || page.text.substring(start, end) != quote) {
            return refused(409, "批注引文与当前页文字不符")
        }
        return when (val result = library.saveNote(
            bookId = book.id,
            record = BookNoteRecord(id, version, pdfPage, start, end, quote, noteText, textSha256, baseRevision, deleted),
            baseRevision = baseRevision,
            mutationId = mutationId,
        )) {
            is BookNoteWriteResult.Saved -> ReaderReply(200, buildJsonObject { put("revision", result.revision) })
            BookNoteWriteResult.Conflict -> refused(409, "批注已被更新，请刷新后处理冲突")
        }
    }
}

internal class PdfBoxWordStripper(
    targetPage: Int,
    private val pageWidth: Double,
    private val pageHeight: Double,
) : BookPdfStripper() {
    init {
        startPage = targetPage
        endPage = targetPage
        sortByPosition = true
    }

    val words = mutableListOf<WordBox>()

    override fun writeString(text: String, textPositions: List<BookPdfTextPosition>) {
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
