package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Types
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 挂在物理页上的视觉块类型，统一承载：
 * - TON-1665：随文插图（[ILLUSTRATION]）
 * - TON-1669：重排时从原页裁出的非纯文本块（[TABLE]、[FORMULA]、[CALLOUT]、[SIDEBAR]、[DIAGRAM]、[COMPLEX_REGION]）
 */
internal enum class PageVisualKind {
    ILLUSTRATION,
    TABLE,
    FORMULA,
    CALLOUT,
    SIDEBAR,
    DIAGRAM,
    COMPLEX_REGION,
}

/**
 * 随文插图与裁图块的统一持久化模型，挂在物理页上，不修改页正文字符串，也不改变 UTF-16 出处偏移。
 *
 * - TON-1665：使用 [page]、[anchorOffset]、[imageSha256]、[extension]、[width]、[height]、[caption]、[order]，
 *   [kind] 为 [PageVisualKind.ILLUSTRATION]，[sourceStart]/[sourceEnd] 为 null。
 * - TON-1669：额外使用 [kind]、[sourceStart]/[sourceEnd]（原页正文里被该裁图视觉替代的 UTF-16 区间，正文与出处偏移仍不变）
 *   以及 [sourceRegion]（原页未旋转 CropBox 内的归一化裁图框）。
 */
internal data class BookIllustration(
    val page: Int,
    val anchorOffset: Int,
    val imageSha256: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val caption: String? = null,
    val order: Int = 0,
    val kind: PageVisualKind = PageVisualKind.ILLUSTRATION,
    val sourceStart: Int? = null,
    val sourceEnd: Int? = null,
    val sourceRegion: ReaderRect? = null,
) {
    val fileName: String get() = "$imageSha256.$extension"
    val replacedSourceRange: IntRange?
        get() = if (sourceStart != null && sourceEnd != null) sourceStart until sourceEnd else null
}

/**
 * 导入提取阶段产出的原始视觉块载荷（TON-1665 插图与 TON-1669 裁图块共用）；
 * 在 [TextbookLibrary] 入库时按内容哈希落盘并转为 [BookIllustration]。
 */
internal class ExtractedIllustration(
    val page: Int,
    val anchorOffset: Int,
    val bytes: ByteArray,
    extension: String,
    val width: Int,
    val height: Int,
    caption: String? = null,
    val kind: PageVisualKind = PageVisualKind.ILLUSTRATION,
    val sourceStart: Int? = null,
    val sourceEnd: Int? = null,
    val sourceRegion: ReaderRect? = null,
) {
    val extension: String = BookIllustrationAssets.normalizeExtension(extension)
    val caption: String? = caption?.trim()?.takeIf { it.isNotEmpty() }
    val contentSha256: String by lazy(LazyThreadSafetyMode.NONE) { BookIllustrationAssets.sha256(bytes) }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ExtractedIllustration &&
            page == other.page &&
            anchorOffset == other.anchorOffset &&
            extension == other.extension &&
            width == other.width &&
            height == other.height &&
            caption == other.caption &&
            kind == other.kind &&
            sourceStart == other.sourceStart &&
            sourceEnd == other.sourceEnd &&
            sourceRegion == other.sourceRegion &&
            bytes.contentEquals(other.bytes))

    override fun hashCode(): Int {
        var result = page
        result = 31 * result + anchorOffset
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + extension.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (caption?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + (sourceStart ?: 0)
        result = 31 * result + (sourceEnd ?: 0)
        result = 31 * result + (sourceRegion?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ExtractedIllustration(page=$page, kind=$kind, anchorOffset=$anchorOffset, bytes=${bytes.size}B, extension=$extension, width=$width, height=$height, caption=$caption, sourceStart=$sourceStart, sourceEnd=$sourceEnd, sourceRegion=$sourceRegion)"
}

/**
 * TON-1670 预留字段：页面正文 UTF-16 区间 [[startOffset], [endOffset]) 映射到原页归一化位置框 [bounds]
 * （未旋转 CropBox，左上角原点，坐标范围 0..1）。用于原版视图的「拖选区域 -> UTF-16 偏移」与「出处偏移 -> 原页高亮」。
 */
internal data class PageTextBox(
    val startOffset: Int,
    val endOffset: Int,
    val bounds: ReaderRect,
) {
    init {
        require(startOffset >= 0 && endOffset > startOffset) {
            "字位置框的 UTF-16 区间非法：[$startOffset, $endOffset)"
        }
    }
}

/**
 * TON-1670 预留的整页原版位置层：保留原页逻辑尺寸及按 UTF-16 下标排序的字/词位置框。
 * 页文字本身不改变，仅通过 UTF-16 下标与原页坐标建立双向映射。
 */
internal data class PageTextLayout(
    val page: Int,
    val pageWidth: Double,
    val pageHeight: Double,
    val boxes: List<PageTextBox> = emptyList(),
) {
    init {
        require(page >= 1) { "页码必须从 1 开始：$page" }
        require(pageWidth.isFinite() && pageWidth > 0.0 && pageHeight.isFinite() && pageHeight > 0.0) {
            "页面尺寸必须为正有限数：${pageWidth}x$pageHeight"
        }
    }

    /** 正向：给定页内 UTF-16 偏移区间，返回用于原页高亮的字位置框。 */
    fun boxesForRange(startOffset: Int, endOffset: Int): List<PageTextBox> {
        if (endOffset <= startOffset || boxes.isEmpty()) return emptyList()
        return boxes.filter { it.endOffset > startOffset && it.startOffset < endOffset }
    }

    /** 反向（框选矩形）：返回与归一化矩形相交的全部位置框覆盖的 UTF-16 偏移区间。 */
    fun rangeForRect(rect: ReaderRect): IntRange? {
        val right = rect.x + rect.width
        val bottom = rect.y + rect.height
        val hit = boxes.filter { box ->
            val b = box.bounds
            b.x < right && b.x + b.width > rect.x && b.y < bottom && b.y + b.height > rect.y
        }
        if (hit.isEmpty()) return null
        return hit.minOf { it.startOffset } until hit.maxOf { it.endOffset }
    }

    /** 反向（起止点拖选）：按阅读顺序从起止归一化坐标定位起止框，返回完整 UTF-16 偏移区间。 */
    fun rangeBetweenPoints(x0: Double, y0: Double, x1: Double, y1: Double): IntRange? {
        if (boxes.isEmpty()) return null
        val startIndex = hitIndex(x0, y0)
        val endIndex = hitIndex(x1, y1)
        val lo = minOf(startIndex, endIndex)
        val hi = maxOf(startIndex, endIndex)
        return boxes[lo].startOffset until boxes[hi].endOffset
    }

    private fun hitIndex(x: Double, y: Double): Int {
        val direct = boxes.indexOfFirst { box ->
            val b = box.bounds
            x in b.x..(b.x + b.width) && y in b.y..(b.y + b.height)
        }
        if (direct >= 0) return direct
        var bestIdx = 0
        var bestDist = Double.POSITIVE_INFINITY
        for (i in boxes.indices) {
            val b = boxes[i].bounds
            val dx = when {
                x < b.x -> b.x - x
                x > b.x + b.width -> x - (b.x + b.width)
                else -> 0.0
            }
            val dy = when {
                y < b.y -> b.y - y
                y > b.y + b.height -> y - (b.y + b.height)
                else -> 0.0
            }
            val dist = dy * 4.0 + dx
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }
}

/**
 * 同一物理页上三张卡共享的扩展挂载数据（页文字 [BookPage.text] 独立且保持不变）：
 * - [illustrations]：TON-1665 插图与 TON-1669 裁图块
 * - [textLayout]：TON-1670 字位置框
 */
internal data class BookPageExtras(
    val page: Int,
    val illustrations: List<BookIllustration> = emptyList(),
    val textLayout: PageTextLayout? = null,
)

/** UTF-16 锚点、替代区间与字位置框的边界校验。 */
internal object BookIllustrationAnchors {
    fun splitsSurrogate(text: String, offset: Int): Boolean =
        offset in 1 until text.length &&
            Character.isHighSurrogate(text[offset - 1]) &&
            Character.isLowSurrogate(text[offset])

    fun isValidAnchor(pageText: String, offset: Int): Boolean =
        offset in 0..pageText.length && !splitsSurrogate(pageText, offset)

    /** 将原始 UTF-16 偏移收敛到合法边界，绝不落在代理对中间。 */
    fun snapToValidUtf16Boundary(pageText: String, rawOffset: Int): Int {
        val clamped = rawOffset.coerceIn(0, pageText.length)
        return if (splitsSurrogate(pageText, clamped)) clamped - 1 else clamped
    }

    fun requireValidForPages(pageTexts: Map<Int, String>, illustrations: List<ExtractedIllustration>) {
        for (item in illustrations) {
            require(item.page >= 1) { "插图页码必须从 1 开始：${item.page}" }
            val pageText = requireNotNull(pageTexts[item.page]) { "插图指向了不存在的页码：${item.page}" }
            require(isValidAnchor(pageText, item.anchorOffset)) {
                "插图锚点 ${item.anchorOffset} 超出第 ${item.page} 页范围（0..${pageText.length}）或劈开了 UTF-16 代理对。"
            }
            if (item.sourceStart != null || item.sourceEnd != null) {
                val start = requireNotNull(item.sourceStart) { "裁图替换区间缺少起始偏移。" }
                val end = requireNotNull(item.sourceEnd) { "裁图替换区间缺少结束偏移。" }
                require(start in 0 until end && end <= pageText.length) {
                    "裁图替换区间 [$start, $end) 超出第 ${item.page} 页范围（0..${pageText.length}）。"
                }
                require(!splitsSurrogate(pageText, start) && !splitsSurrogate(pageText, end)) {
                    "裁图替换区间 [$start, $end) 劈开了 UTF-16 代理对。"
                }
            }
            require(item.width > 0 && item.height > 0) { "插图宽高必须大于 0：${item.width}x${item.height}" }
            require(item.bytes.isNotEmpty()) { "插图内容不能为空。" }
            require(item.bytes.size <= BookIllustrationAssets.MAX_IMAGE_BYTES) {
                "单张插图过大（超过 ${BookIllustrationAssets.MAX_IMAGE_BYTES / (1024 * 1024)} MB）。"
            }
        }
    }

    fun requireValidTextLayouts(pageTexts: Map<Int, String>, layouts: List<PageTextLayout>) {
        for (layout in layouts) {
            val pageText = requireNotNull(pageTexts[layout.page]) { "字位置框指向了不存在的页码：${layout.page}" }
            var previousStart = 0
            for (box in layout.boxes) {
                require(box.endOffset <= pageText.length) {
                    "字位置框结束偏移 ${box.endOffset} 超出第 ${layout.page} 页长度 ${pageText.length}。"
                }
                require(!splitsSurrogate(pageText, box.startOffset) && !splitsSurrogate(pageText, box.endOffset)) {
                    "字位置框 [${box.startOffset}, ${box.endOffset}) 劈开了 UTF-16 代理对。"
                }
                require(box.startOffset >= previousStart) {
                    "字位置框必须按 UTF-16 起始偏移非降序排列：${box.startOffset} < $previousStart"
                }
                previousStart = box.startOffset
            }
        }
    }
}

/** 按内容 SHA-256 将插图与裁图安全地存入对应教材的目录内，避免路径穿越和重复写入。 */
internal object BookIllustrationAssets {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    val ALLOWED_EXTENSIONS = setOf("png", "jpg", "webp", "gif")
    private val sha256Pattern = Regex("[a-f0-9]{64}")

    fun isValidSha256(value: String): Boolean = sha256Pattern.matches(value)

    fun normalizeExtension(raw: String): String {
        val normalized = raw.trim().lowercase(Locale.ROOT).let { if (it == "jpeg") "jpg" else it }
        require(normalized in ALLOWED_EXTENSIONS) { "不支持的插图格式：$raw" }
        return normalized
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun bookDirectory(libraryDirectory: Path, bookId: String): Path {
        require(isValidSha256(bookId)) { "非法的教材编号。" }
        return libraryDirectory.resolve(bookId).resolve("illustrations")
    }

    fun imageFile(libraryDirectory: Path, bookId: String, imageSha256: String, extension: String): Path {
        require(isValidSha256(imageSha256)) { "非法的插图哈希。" }
        val safeExt = normalizeExtension(extension)
        return bookDirectory(libraryDirectory, bookId).resolve("$imageSha256.$safeExt")
    }

    fun imageFile(libraryDirectory: Path, bookId: String, illustration: BookIllustration): Path =
        imageFile(libraryDirectory, bookId, illustration.imageSha256, illustration.extension)

    /**
     * 按内容哈希去重写入插图/裁图文件，返回本次新写入的文件路径（已存在的同哈希文件不重复落盘）。
     * 任一文件写入失败时回滚本次新建文件。
     */
    fun writeUnique(
        libraryDirectory: Path,
        bookId: String,
        illustrations: List<ExtractedIllustration>,
    ): List<Path> {
        if (illustrations.isEmpty()) return emptyList()
        val dir = bookDirectory(libraryDirectory, bookId)
        Files.createDirectories(dir)
        val created = mutableListOf<Path>()
        val unique = linkedMapOf<Pair<String, String>, ByteArray>()
        for (item in illustrations) {
            unique.putIfAbsent(item.contentSha256 to item.extension, item.bytes)
        }
        try {
            for ((key, bytes) in unique) {
                val (hash, ext) = key
                val target = imageFile(libraryDirectory, bookId, hash, ext)
                if (Files.exists(target)) continue
                val temp = dir.resolve(".tmp-img-${UUID.randomUUID()}")
                try {
                    Files.write(temp, bytes)
                    try {
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    created.add(target)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
            return created
        } catch (error: Exception) {
            created.forEach { runCatching { Files.deleteIfExists(it) } }
            throw error
        }
    }
}

/** 随文插图与裁图块的 SQLite 存储与旧库兼容读取：缺表、缺列或缺行时均按空数据照常打开。 */
internal object BookIllustrationStore {
    private const val TABLE = "book_illustrations"

    fun migrate(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute(
                """CREATE TABLE IF NOT EXISTS $TABLE(
                    book TEXT NOT NULL,
                    page INTEGER NOT NULL,
                    ordinal INTEGER NOT NULL,
                    anchor_offset INTEGER NOT NULL,
                    image_sha256 TEXT NOT NULL,
                    extension TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    caption TEXT,
                    kind TEXT NOT NULL DEFAULT 'ILLUSTRATION',
                    source_start INTEGER,
                    source_end INTEGER,
                    region_x REAL,
                    region_y REAL,
                    region_w REAL,
                    region_h REAL,
                    PRIMARY KEY(book, page, ordinal)
                )""".trimIndent(),
            )
        }
        val columns = tableColumns(db)
        fun ensureColumn(name: String, declaration: String) {
            if (name !in columns) {
                db.createStatement().use { it.execute("ALTER TABLE $TABLE ADD COLUMN $name $declaration") }
            }
        }
        ensureColumn("kind", "TEXT NOT NULL DEFAULT 'ILLUSTRATION'")
        ensureColumn("source_start", "INTEGER")
        ensureColumn("source_end", "INTEGER")
        ensureColumn("region_x", "REAL")
        ensureColumn("region_y", "REAL")
        ensureColumn("region_w", "REAL")
        ensureColumn("region_h", "REAL")
    }

    fun tableExists(db: Connection): Boolean =
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$TABLE'").use { it.next() }
        }

    private fun tableColumns(db: Connection): Set<String> = buildSet {
        db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info($TABLE)").use { rows ->
                while (rows.next()) add(rows.getString("name"))
            }
        }
    }

    fun saveInTransaction(
        db: Connection,
        libraryDirectory: Path,
        bookId: String,
        pageTexts: Map<Int, String>,
        illustrations: List<ExtractedIllustration>,
    ): List<BookIllustration> {
        require(BookIllustrationAssets.isValidSha256(bookId)) { "非法的教材编号。" }
        if (illustrations.isEmpty()) return emptyList()
        BookIllustrationAnchors.requireValidForPages(pageTexts, illustrations)
        migrate(db)
        val createdFiles = BookIllustrationAssets.writeUnique(libraryDirectory, bookId, illustrations)
        val pageOrdinals = HashMap<Int, Int>()
        val persisted = mutableListOf<BookIllustration>()
        try {
            db.prepareStatement(
                """INSERT INTO $TABLE(
                    book,page,ordinal,anchor_offset,image_sha256,extension,width,height,caption,
                    kind,source_start,source_end,region_x,region_y,region_w,region_h
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            ).use { sql ->
                for (item in illustrations) {
                    val ordinal = pageOrdinals.getOrDefault(item.page, 0)
                    pageOrdinals[item.page] = ordinal + 1
                    val hash = item.contentSha256
                    sql.setString(1, bookId)
                    sql.setInt(2, item.page)
                    sql.setInt(3, ordinal)
                    sql.setInt(4, item.anchorOffset)
                    sql.setString(5, hash)
                    sql.setString(6, item.extension)
                    sql.setInt(7, item.width)
                    sql.setInt(8, item.height)
                    if (item.caption == null) sql.setNull(9, Types.VARCHAR) else sql.setString(9, item.caption)
                    sql.setString(10, item.kind.name)
                    if (item.sourceStart == null) sql.setNull(11, Types.INTEGER) else sql.setInt(11, item.sourceStart)
                    if (item.sourceEnd == null) sql.setNull(12, Types.INTEGER) else sql.setInt(12, item.sourceEnd)
                    val region = item.sourceRegion
                    if (region == null) {
                        sql.setNull(13, Types.REAL); sql.setNull(14, Types.REAL)
                        sql.setNull(15, Types.REAL); sql.setNull(16, Types.REAL)
                    } else {
                        sql.setDouble(13, region.x); sql.setDouble(14, region.y)
                        sql.setDouble(15, region.width); sql.setDouble(16, region.height)
                    }
                    sql.addBatch()
                    persisted.add(
                        BookIllustration(
                            page = item.page,
                            anchorOffset = item.anchorOffset,
                            imageSha256 = hash,
                            extension = item.extension,
                            width = item.width,
                            height = item.height,
                            caption = item.caption,
                            order = ordinal,
                            kind = item.kind,
                            sourceStart = item.sourceStart,
                            sourceEnd = item.sourceEnd,
                            sourceRegion = region,
                        ),
                    )
                }
                sql.executeBatch()
            }
            return persisted
        } catch (error: Exception) {
            createdFiles.forEach { runCatching { Files.deleteIfExists(it) } }
            throw error
        }
    }

    fun loadByPage(db: Connection, bookId: String): Map<Int, List<BookIllustration>> {
        if (!BookIllustrationAssets.isValidSha256(bookId) || !tableExists(db)) return emptyMap()
        val columns = tableColumns(db)
        val hasExtended = setOf("kind", "source_start", "source_end", "region_x", "region_y", "region_w", "region_h")
            .all { it in columns }
        val selectCols = if (hasExtended) {
            "page,ordinal,anchor_offset,image_sha256,extension,width,height,caption,kind,source_start,source_end,region_x,region_y,region_w,region_h"
        } else {
            "page,ordinal,anchor_offset,image_sha256,extension,width,height,caption"
        }
        val grouped = LinkedHashMap<Int, MutableList<BookIllustration>>()
        db.prepareStatement("SELECT $selectCols FROM $TABLE WHERE book=? ORDER BY page,ordinal").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                while (rows.next()) {
                    val hash = rows.getString("image_sha256")
                    val ext = runCatching { BookIllustrationAssets.normalizeExtension(rows.getString("extension")) }.getOrNull()
                    val width = rows.getInt("width")
                    val height = rows.getInt("height")
                    val page = rows.getInt("page")
                    val anchor = rows.getInt("anchor_offset")
                    if (!BookIllustrationAssets.isValidSha256(hash) || ext == null || width <= 0 || height <= 0 || page < 1 || anchor < 0) {
                        continue
                    }
                    val kind = if (hasExtended) {
                        rows.getString("kind")?.let { raw -> runCatching { PageVisualKind.valueOf(raw) }.getOrNull() }
                            ?: PageVisualKind.ILLUSTRATION
                    } else {
                        PageVisualKind.ILLUSTRATION
                    }
                    val sourceStart = if (hasExtended) rows.getInt("source_start").let { if (rows.wasNull()) null else it } else null
                    val sourceEnd = if (hasExtended) rows.getInt("source_end").let { if (rows.wasNull()) null else it } else null
                    val region = if (hasExtended) {
                        val rx = rows.getDouble("region_x").let { if (rows.wasNull()) null else it }
                        val ry = rows.getDouble("region_y").let { if (rows.wasNull()) null else it }
                        val rw = rows.getDouble("region_w").let { if (rows.wasNull()) null else it }
                        val rh = rows.getDouble("region_h").let { if (rows.wasNull()) null else it }
                        if (rx != null && ry != null && rw != null && rh != null) {
                            runCatching { ReaderRect(rx, ry, rw, rh) }.getOrNull()
                        } else null
                    } else null
                    val illustration = BookIllustration(
                        page = page,
                        anchorOffset = anchor,
                        imageSha256 = hash,
                        extension = ext,
                        width = width,
                        height = height,
                        caption = rows.getString("caption")?.trim()?.takeIf { it.isNotEmpty() },
                        order = rows.getInt("ordinal"),
                        kind = kind,
                        sourceStart = sourceStart,
                        sourceEnd = sourceEnd,
                        sourceRegion = region,
                    )
                    grouped.getOrPut(page) { mutableListOf() }.add(illustration)
                }
            }
        }
        return grouped
    }
}

/**
 * TON-1670 字位置框的按页压缩二进制存储：每页一行 BLOB，兼顾 300 页教材的落盘体积与按页按需读取性能。
 * 旧库无该表或旧书无记录时返回 null / emptyMap，不影响照常打开。
 */
internal object BookPageTextBoxStore {
    private const val TABLE = "book_page_text_boxes"
    private const val BLOB_VERSION = 1

    fun migrate(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute(
                """CREATE TABLE IF NOT EXISTS $TABLE(
                    book TEXT NOT NULL,
                    page INTEGER NOT NULL,
                    page_width REAL NOT NULL,
                    page_height REAL NOT NULL,
                    boxes BLOB NOT NULL,
                    PRIMARY KEY(book, page)
                )""".trimIndent(),
            )
        }
    }

    fun tableExists(db: Connection): Boolean =
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$TABLE'").use { it.next() }
        }

    fun saveInTransaction(
        db: Connection,
        bookId: String,
        pageTexts: Map<Int, String>,
        layouts: List<PageTextLayout>,
    ) {
        require(BookIllustrationAssets.isValidSha256(bookId)) { "非法的教材编号。" }
        if (layouts.isEmpty()) return
        BookIllustrationAnchors.requireValidTextLayouts(pageTexts, layouts)
        migrate(db)
        db.prepareStatement(
            "INSERT OR REPLACE INTO $TABLE(book,page,page_width,page_height,boxes) VALUES(?,?,?,?,?)",
        ).use { sql ->
            for (layout in layouts) {
                sql.setString(1, bookId)
                sql.setInt(2, layout.page)
                sql.setDouble(3, layout.pageWidth)
                sql.setDouble(4, layout.pageHeight)
                sql.setBytes(5, encodeBoxes(layout.boxes))
                sql.addBatch()
            }
            sql.executeBatch()
        }
    }

    fun loadPage(db: Connection, bookId: String, page: Int): PageTextLayout? {
        if (!BookIllustrationAssets.isValidSha256(bookId) || page < 1 || !tableExists(db)) return null
        db.prepareStatement("SELECT page_width,page_height,boxes FROM $TABLE WHERE book=? AND page=?").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.executeQuery().use { rows ->
                if (!rows.next()) return null
                return decodeRow(page, rows.getDouble("page_width"), rows.getDouble("page_height"), rows.getBytes("boxes"))
            }
        }
    }

    fun loadAllByPage(db: Connection, bookId: String): Map<Int, PageTextLayout> {
        if (!BookIllustrationAssets.isValidSha256(bookId) || !tableExists(db)) return emptyMap()
        val result = LinkedHashMap<Int, PageTextLayout>()
        db.prepareStatement("SELECT page,page_width,page_height,boxes FROM $TABLE WHERE book=? ORDER BY page").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                while (rows.next()) {
                    val page = rows.getInt("page")
                    val layout = decodeRow(page, rows.getDouble("page_width"), rows.getDouble("page_height"), rows.getBytes("boxes"))
                    if (layout != null) result[page] = layout
                }
            }
        }
        return result
    }

    internal fun encodeBoxes(boxes: List<PageTextBox>): ByteArray {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gzip ->
            DataOutputStream(gzip).use { out ->
                out.writeByte(BLOB_VERSION)
                out.writeInt(boxes.size)
                for (box in boxes) {
                    out.writeInt(box.startOffset)
                    out.writeInt(box.endOffset)
                    out.writeFloat(box.bounds.x.toFloat())
                    out.writeFloat(box.bounds.y.toFloat())
                    out.writeFloat(box.bounds.width.toFloat())
                    out.writeFloat(box.bounds.height.toFloat())
                }
            }
        }
        return raw.toByteArray()
    }

    internal fun decodeBoxes(blob: ByteArray): List<PageTextBox>? = runCatching {
        GZIPInputStream(ByteArrayInputStream(blob)).use { gzip ->
            DataInputStream(gzip).use { input ->
                val version = input.readUnsignedByte()
                if (version != BLOB_VERSION) return null
                val count = input.readInt()
                if (count < 0) return null
                buildList(count) {
                    repeat(count) {
                        val start = input.readInt()
                        val end = input.readInt()
                        val x = input.readFloat().toDouble()
                        val y = input.readFloat().toDouble()
                        val w = input.readFloat().toDouble()
                        val h = input.readFloat().toDouble()
                        add(PageTextBox(start, end, ReaderRect(x, y, w, h)))
                    }
                }
            }
        }
    }.getOrNull()

    private fun decodeRow(page: Int, width: Double, height: Double, blob: ByteArray?): PageTextLayout? {
        if (blob == null || page < 1 || !width.isFinite() || width <= 0.0 || !height.isFinite() || height <= 0.0) return null
        val boxes = decodeBoxes(blob) ?: return null
        return PageTextLayout(page, width, height, boxes)
    }
}

/** 统一迁移、写入与读取单页扩展数据（插图/裁图 + 字位置框）。 */
internal object BookPageModelStore {
    fun migrate(db: Connection) {
        BookIllustrationStore.migrate(db)
        BookPageTextBoxStore.migrate(db)
    }

    fun saveInTransaction(
        db: Connection,
        libraryDirectory: Path,
        bookId: String,
        pageTexts: Map<Int, String>,
        illustrations: List<ExtractedIllustration> = emptyList(),
        textLayouts: List<PageTextLayout> = emptyList(),
    ): List<BookIllustration> {
        val persisted = BookIllustrationStore.saveInTransaction(db, libraryDirectory, bookId, pageTexts, illustrations)
        BookPageTextBoxStore.saveInTransaction(db, bookId, pageTexts, textLayouts)
        return persisted
    }

    fun loadExtrasByPage(
        db: Connection,
        bookId: String,
        includeTextLayouts: Boolean = false,
    ): Map<Int, BookPageExtras> {
        val illustrations = BookIllustrationStore.loadByPage(db, bookId)
        val layouts = if (includeTextLayouts) BookPageTextBoxStore.loadAllByPage(db, bookId) else emptyMap()
        val pages = (illustrations.keys + layouts.keys).toSortedSet()
        return pages.associateWith { page ->
            BookPageExtras(
                page = page,
                illustrations = illustrations[page].orEmpty(),
                textLayout = layouts[page],
            )
        }
    }
}

internal sealed interface BookPageSegment {
    data class Text(val startOffset: Int, val endOffset: Int, val text: String) : BookPageSegment
    data class Illustration(val illustration: BookIllustration) : BookPageSegment
}

/**
 * 按 UTF-16 锚点将一页切分为交错的正文片段与视觉块（插图或裁图块）：
 * - [hideReplacedSourceText] 为 false（默认）时，拼接全部正文片段与原页文字逐字一致；
 * - [hideReplacedSourceText] 为 true 时，带 [BookIllustration.sourceStart]/[sourceEnd] 的裁图块
 *   只隐藏其覆盖的原页扁平文字，其余正文片段的 UTF-16 偏移仍然逐位对应原文。
 */
internal object BookIllustrationLayout {
    fun segment(
        pageText: String,
        illustrations: List<BookIllustration>,
        hideReplacedSourceText: Boolean = false,
    ): List<BookPageSegment> {
        if (illustrations.isEmpty()) {
            return if (pageText.isEmpty()) emptyList() else listOf(BookPageSegment.Text(0, pageText.length, pageText))
        }
        val ordered = illustrations.sortedWith(compareBy<BookIllustration> { it.anchorOffset }.thenBy { it.order })
        val result = mutableListOf<BookPageSegment>()
        var cursor = 0
        for (item in ordered) {
            val anchor = BookIllustrationAnchors.snapToValidUtf16Boundary(pageText, item.anchorOffset)
            if (anchor > cursor) {
                result.add(BookPageSegment.Text(cursor, anchor, pageText.substring(cursor, anchor)))
                cursor = anchor
            }
            result.add(BookPageSegment.Illustration(item))
            if (hideReplacedSourceText) {
                val replaced = item.replacedSourceRange
                if (replaced != null && replaced.first >= cursor && replaced.last + 1 <= pageText.length) {
                    cursor = maxOf(cursor, replaced.last + 1)
                }
            }
        }
        if (cursor < pageText.length) {
            result.add(BookPageSegment.Text(cursor, pageText.length, pageText.substring(cursor)))
        }
        return result
    }
}
