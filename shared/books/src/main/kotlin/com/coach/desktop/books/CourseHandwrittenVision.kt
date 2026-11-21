package com.coach.desktop.books

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import kotlin.math.max

internal const val MAX_HANDWRITTEN_VISION_PAGES = 25

internal data class VisionPageInput(
    val pageNumber: Int,
    val pngBase64: String,
    val pageSha256: String,
)

internal data class VisionPageOutput(
    val pageNumber: Int,
    val text: String,
    val pageSha256: String,
    val cached: Boolean = false,
)

internal data class PreparedVisionBatch(
    val bookId: String,
    val totalPages: Int,
    val cachedHits: List<VisionPageOutput>,
    val pendingPages: List<VisionPageInput>,
)

/**
 * Local SQLite cache + 25-page guard for handwritten or scanned PDF pages recognized on demand.
 * Prevents duplicate vision requests for identical page hashes across sessions or re-imports.
 */
internal object CourseVisionCacheStore {
    private const val RENDER_MAX_SIDE = 1600f
    private const val RENDER_TARGET_DPI = 150f

    fun ensureSchema(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute(
                """CREATE TABLE IF NOT EXISTS course_vision_cache(
                    page_sha256 TEXT PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    page_num INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    vision_tag TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )""",
            )
            sql.execute("CREATE INDEX IF NOT EXISTS idx_course_vision_book ON course_vision_cache(book_id, page_num)")
        }
    }

    fun lookupBySha(db: Connection, sha256: String): String? {
        ensureSchema(db)
        return db.prepareStatement("SELECT text FROM course_vision_cache WHERE page_sha256=?").use { sql ->
            sql.setString(1, sha256)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    fun saveRecognizedPages(
        db: Connection,
        bookId: String,
        pages: List<VisionPageOutput>,
        visionTag: String = "vision-v1",
    ) {
        if (pages.isEmpty()) return
        ensureSchema(db)
        val now = System.currentTimeMillis()
        db.autoCommit = false
        try {
            db.prepareStatement(
                """INSERT INTO course_vision_cache(page_sha256, book_id, page_num, text, vision_tag, updated_at)
                   VALUES(?,?,?,?,?,?)
                   ON CONFLICT(page_sha256) DO UPDATE SET
                       text=excluded.text,
                       book_id=excluded.book_id,
                       page_num=excluded.page_num,
                       vision_tag=excluded.vision_tag,
                       updated_at=excluded.updated_at""",
            ).use { upsert ->
                for (page in pages) {
                    if (page.text.isBlank()) continue
                    upsert.setString(1, page.pageSha256)
                    upsert.setString(2, bookId)
                    upsert.setInt(3, page.pageNumber)
                    upsert.setString(4, page.text)
                    upsert.setString(5, visionTag)
                    upsert.setLong(6, now)
                    upsert.addBatch()
                }
                upsert.executeBatch()
            }
            db.prepareStatement("UPDATE pages SET text=?, ocr=1, ocr_pending=0 WHERE book=? AND page=?").use { upd ->
                for (page in pages) {
                    if (page.text.isBlank()) continue
                    upd.setString(1, page.text)
                    upd.setString(2, bookId)
                    upd.setInt(3, page.pageNumber)
                    upd.addBatch()
                }
                upd.executeBatch()
            }
            for (page in pages) {
                if (page.text.isNotBlank()) {
                    BookFtsStore.upsertPage(db, bookId, page.pageNumber, page.text)
                }
            }
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally {
            db.autoCommit = true
        }
    }

    fun prepareHandwrittenPages(
        library: TextbookLibrary,
        bookId: String,
        requestedPages: List<Int> = emptyList(),
    ): PreparedVisionBatch {
        val summary = library.book(bookId)
        require(summary.format == "pdf") { "仅支持对 PDF 格式的板书或手写材料发起视觉识别。" }
        val pdfPath = requireNotNull(library.originalFileIfExists(bookId, "pdf")) {
            "找不到原始 PDF 文件，请重新导入后重试。"
        }
        val pdfFile = pdfPath.toFile()
        val rendered = BookPdfLoader.read(pdfFile) { pdf ->
            val total = pdf.numberOfPages
            require(total in 1..MAX_HANDWRITTEN_VISION_PAGES) {
                "手写或扫描 PDF 超过 $MAX_HANDWRITTEN_VISION_PAGES 页上限（当前 $total 页），请拆分后再识别。"
            }
            val targets = if (requestedPages.isEmpty()) {
                (1..total).toList()
            } else {
                requestedPages.distinct().filter { it in 1..total }.sorted()
            }
            val pngs = targets.map { pageNum ->
                val bounds = pdf.getPage(pageNum - 1).cropBox
                require(bounds.width.isFinite() && bounds.height.isFinite() && bounds.width > 0f && bounds.height > 0f) {
                    "第 $pageNum 页 PDF 尺寸无效，无法渲染。"
                }
                val scale = minOf(RENDER_TARGET_DPI / 72f, RENDER_MAX_SIDE / max(bounds.width, bounds.height))
                val bitmap = renderBookPdfPage(pdfFile, pdf, pageNum - 1, scale)
                try {
                    val png = bookBitmapCropPng(bitmap, 0, 0, bitmap.width, bitmap.height)
                    Triple(pageNum, png, sha256Hex(png))
                } finally {
                    releaseBookBitmap(bitmap)
                }
            }
            total to pngs
        }
        val (totalPages, pngEntries) = rendered
        val cachedHits = mutableListOf<VisionPageOutput>()
        val pendingPages = mutableListOf<VisionPageInput>()
        library.withCourseDatabase { db ->
            ensureSchema(db)
            for ((pageNum, pngBytes, sha) in pngEntries) {
                val cachedText = lookupBySha(db, sha)
                if (!cachedText.isNullOrBlank()) {
                    cachedHits += VisionPageOutput(pageNum, cachedText, sha, cached = true)
                } else {
                    pendingPages += VisionPageInput(
                        pageNumber = pageNum,
                        pngBase64 = Base64.getEncoder().encodeToString(pngBytes),
                        pageSha256 = sha,
                    )
                }
            }
            if (cachedHits.isNotEmpty()) {
                saveRecognizedPages(db, bookId, cachedHits, visionTag = "local-cache")
            }
        }
        return PreparedVisionBatch(
            bookId = bookId,
            totalPages = totalPages,
            cachedHits = cachedHits,
            pendingPages = pendingPages,
        )
    }

    internal fun buildVisionRequest(prepared: PreparedVisionBatch): JsonObject = buildJsonObject {
        put("bookId", prepared.bookId)
        put("totalPages", prepared.totalPages)
        put("pages", buildJsonArray {
            for (p in prepared.pendingPages) {
                add(buildJsonObject {
                    put("pageNumber", p.pageNumber)
                    put("pageSha256", p.pageSha256)
                    put("pngBase64", p.pngBase64)
                })
            }
        })
    }

    internal fun parseVisionOutputs(body: JsonObject): List<VisionPageOutput> =
        ((body["pages"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pNum = (o["pageNumber"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val sha = (o["pageSha256"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val text = (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (sha.isBlank() || text.isBlank()) return@mapNotNull null
            VisionPageOutput(pageNumber = pNum, text = text, pageSha256 = sha, cached = false)
        }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
