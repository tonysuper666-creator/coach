package com.coach.desktop.books

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Types
import java.util.Locale
import java.util.UUID

/**
 * 随文插图的持久化模型，挂在物理页上，不修改页正文字符串，也不改变 UTF-16 出处偏移。
 * [anchorOffset] 为插图应当显示在该页 [BookPage.text] 哪个 UTF-16 下标之前（范围 0..text.length）。
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
) {
    val fileName: String get() = "$imageSha256.$extension"
}

/**
 * 导入提取阶段产出的原始插图载荷；在 [TextbookLibrary] 入库时按内容哈希落盘并转为 [BookIllustration]。
 */
internal class ExtractedIllustration(
    val page: Int,
    val anchorOffset: Int,
    val bytes: ByteArray,
    extension: String,
    val width: Int,
    val height: Int,
    caption: String? = null,
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
            bytes.contentEquals(other.bytes))

    override fun hashCode(): Int {
        var result = page
        result = 31 * result + anchorOffset
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + extension.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (caption?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ExtractedIllustration(page=$page, anchorOffset=$anchorOffset, bytes=${bytes.size}B, extension=$extension, width=$width, height=$height, caption=$caption)"
}

/** UTF-16 锚点边界校验与对代理对安全的下标吸附。 */
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
            require(item.width > 0 && item.height > 0) { "插图宽高必须大于 0：${item.width}x${item.height}" }
            require(item.bytes.isNotEmpty()) { "插图内容不能为空。" }
            require(item.bytes.size <= BookIllustrationAssets.MAX_IMAGE_BYTES) {
                "单张插图过大（超过 ${BookIllustrationAssets.MAX_IMAGE_BYTES / (1024 * 1024)} MB）。"
            }
        }
    }
}

/** 按内容 SHA-256 将插图安全地存入对应教材的目录内，避免路径穿越和重复写入。 */
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
     * 按内容哈希去重写入插图文件，返回本次新写入的文件路径（已存在的同哈希文件不重复落盘）。
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

/** 随文插图的 SQLite 存储与旧库兼容读取：缺表或缺行时按无插图照常打开。 */
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
                    PRIMARY KEY(book, page, ordinal)
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
                "INSERT INTO $TABLE(book,page,ordinal,anchor_offset,image_sha256,extension,width,height,caption) VALUES(?,?,?,?,?,?,?,?,?)",
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
                    sql.addBatch()
                    persisted += BookIllustration(
                        page = item.page,
                        anchorOffset = item.anchorOffset,
                        imageSha256 = hash,
                        extension = item.extension,
                        width = item.width,
                        height = item.height,
                        caption = item.caption,
                        order = ordinal,
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
        val grouped = LinkedHashMap<Int, MutableList<BookIllustration>>()
        db.prepareStatement(
            "SELECT page,ordinal,anchor_offset,image_sha256,extension,width,height,caption FROM $TABLE WHERE book=? ORDER BY page,ordinal",
        ).use { sql ->
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
                    val illustration = BookIllustration(
                        page = page,
                        anchorOffset = anchor,
                        imageSha256 = hash,
                        extension = ext,
                        width = width,
                        height = height,
                        caption = rows.getString("caption")?.trim()?.takeIf { it.isNotEmpty() },
                        order = rows.getInt("ordinal"),
                    )
                    grouped.getOrPut(page) { mutableListOf() }.add(illustration)
                }
            }
        }
        return grouped
    }
}

internal sealed interface BookPageSegment {
    data class Text(val startOffset: Int, val endOffset: Int, val text: String) : BookPageSegment
    data class Illustration(val illustration: BookIllustration) : BookPageSegment
}

/** 按 UTF-16 锚点将一页切分为交错的正文片段与插图块；拼接全部正文片段与原页文字逐字一致。 */
internal object BookIllustrationLayout {
    fun segment(pageText: String, illustrations: List<BookIllustration>): List<BookPageSegment> {
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
        }
        if (cursor < pageText.length) {
            result.add(BookPageSegment.Text(cursor, pageText.length, pageText.substring(cursor)))
        }
        return result
    }
}
