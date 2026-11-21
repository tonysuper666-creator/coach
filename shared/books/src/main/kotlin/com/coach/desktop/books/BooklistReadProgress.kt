package com.coach.desktop.books

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * 2.0-W2-D 真实章节已读状态（对应 `book_chapter_progress.read_state`）：
 * - `UNREAD`：未读（含目录跳读越过的中间章节，绝不自动标已读）。
 * - `PARTIAL`：已停留部分页面，但尚未达到 `>= 70%` 阈值。
 * - `READ`：真实已读（`>= 70%` 页面单页停留 `>= 8s`，或用户手动标为已读）。
 */
internal enum class ChapterReadState {
    UNREAD,
    PARTIAL,
    READ,
    ;

    companion object {
        fun fromWire(raw: String?): ChapterReadState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNREAD
    }
}

internal object ChapterReadPolicy {
    /** 单页真实停留门槛：8 秒；快速翻过/滑过（<8s）不计入已停留页。 */
    const val MIN_DWELL_MS: Long = 8_000L

    /** 章节自动转为 READ 的不重复停留页比例门槛：70%。 */
    const val READ_RATIO_THRESHOLD: Double = 0.70

    fun computeReadState(dwelledCount: Int, pagesTotal: Int): ChapterReadState {
        val total = pagesTotal.coerceAtLeast(1)
        val dwelled = dwelledCount.coerceIn(0, total)
        if (dwelled <= 0) return ChapterReadState.UNREAD
        return if (dwelled.toDouble() / total.toDouble() >= READ_RATIO_THRESHOLD) {
            ChapterReadState.READ
        } else {
            ChapterReadState.PARTIAL
        }
    }
}

/** 测试可通过 CompositionLocal 缩短等待时间；生产默认严格为 8 秒（8000ms）。 */
internal val LocalChapterDwellMs = staticCompositionLocalOf { ChapterReadPolicy.MIN_DWELL_MS }

internal data class BookChapterSpan(
    val tocNodeId: String,
    val title: String,
    val firstPage: Int,
    val lastPage: Int,
    val sectionIndices: List<Int>,
) {
    val pagesTotal: Int get() = (lastPage - firstPage + 1).coerceAtLeast(1)
}

internal data class ChapterProgressRecord(
    val bookId: String,
    val tocNodeId: String,
    val pagesTotal: Int,
    val dwelledPages: Set<Int>,
    val readState: ChapterReadState,
    val manualOverride: Boolean,
    val updatedAt: Long,
) {
    val pagesDwelled: Int get() = dwelledPages.size
    val ratio: Double get() = if (pagesTotal <= 0) 0.0 else pagesDwelled.toDouble() / pagesTotal.toDouble()
}

/**
 * 统一解析一本书的章节 `toc_node_id` 与页码跨度，使 `book_chapter_progress`、
 * `concept_mentions.toc_node_id`、目录抽屉与伴读抽屉共用同一章节主键。
 */
internal object BookChapterResolver {
    private val SUBSECTION_NUM_RE = Regex("""^(\d+)\.\d+""")

    fun chapters(book: Textbook): List<BookChapterSpan> {
        if (book.lessons.isEmpty()) {
            val first = book.pages.firstOrNull()?.number ?: 1
            val last = book.pages.lastOrNull()?.number ?: first
            return listOf(BookChapterSpan("ch-all", book.title, first, last, emptyList()))
        }
        val grouped = LinkedHashMap<String, MutableList<Pair<Int, BookLesson>>>()
        val titles = LinkedHashMap<String, String>()
        book.lessons.forEachIndexed { idx, lesson ->
            val parts = lesson.breadcrumb.split('›').map(String::trim).filter(String::isNotEmpty)
            val subNum = SUBSECTION_NUM_RE.find(lesson.title.trim())?.groupValues?.get(1)
            val id = when {
                parts.size >= 3 -> parts.dropLast(1).joinToString(" › ")
                subNum != null -> "ch-$subNum"
                else -> lesson.id
            }
            val title = when {
                parts.size >= 3 -> parts[parts.lastIndex - 1]
                else -> lesson.title
            }
            grouped.getOrPut(id) { mutableListOf() }.add(idx to lesson)
            titles.putIfAbsent(id, title)
        }
        return grouped.entries.map { (id, items) ->
            BookChapterSpan(
                tocNodeId = id,
                title = titles[id].orEmpty().ifBlank { items.first().second.title },
                firstPage = items.minOf { it.second.firstPage },
                lastPage = items.maxOf { it.second.lastPage },
                sectionIndices = items.map { it.first },
            )
        }
    }

    fun chapterForLesson(book: Textbook, sectionIndex: Int): BookChapterSpan? {
        val all = chapters(book)
        return all.firstOrNull { sectionIndex in it.sectionIndices }
            ?: book.lessons.getOrNull(sectionIndex)?.let { lesson ->
                all.firstOrNull { lesson.firstPage in it.firstPage..it.lastPage }
            }
    }

    fun chapterForPage(book: Textbook, page: Int): BookChapterSpan? {
        val all = chapters(book)
        return all.firstOrNull { page in it.firstPage..it.lastPage }
            ?: all.lastOrNull { it.firstPage <= page }
            ?: all.firstOrNull()
    }
}

/**
 * 真实已读判定持久化引擎（直接读写 SQLite v3 `book_chapter_progress` 表）。
 * - 更新停留页与已读状态时完整保留 `practice_draft_json`（不干扰 W2-B 练习草稿续接）。
 * - 严格遵守防跳读契约：只记录真实停留 >= 8 秒的当前页，绝不顺带标记跳过的中间章。
 */
internal object BooklistProgressStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun decodePages(raw: String?): Set<Int> {
        if (raw.isNullOrBlank() || raw == "[]") return emptySet()
        return runCatching { json.decodeFromString<List<Int>>(raw).toSet() }.getOrDefault(emptySet())
    }

    private fun encodePages(pages: Set<Int>): String =
        json.encodeToString(pages.sorted())

    /**
     * 记录用户在 [page] 单页停留达标（[dwellMs] >= [ChapterReadPolicy.MIN_DWELL_MS]）。
     * 若停留不足阈值则直接返回当前状态且不写库。
     * 若该章已被用户手动标为已读/未读（`manual_override = 1`），仅累计停留页数，不覆盖用户的手动状态。
     */
    fun recordPageDwell(
        db: Connection,
        bookId: String,
        tocNodeId: String,
        page: Int,
        pagesTotal: Int,
        dwellMs: Long = ChapterReadPolicy.MIN_DWELL_MS,
        now: Long = System.currentTimeMillis(),
    ): ChapterProgressRecord? {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return null
        val existing = loadChapter(db, bookId, tocNodeId)
        if (dwellMs < ChapterReadPolicy.MIN_DWELL_MS) return existing

        val total = maxOf(pagesTotal.coerceAtLeast(1), existing?.pagesTotal ?: 1)
        val updatedPages = (existing?.dwelledPages.orEmpty() + page)
        val manual = existing?.manualOverride == true
        val nextState = if (existing != null && existing.manualOverride) {
            existing.readState
        } else {
            ChapterReadPolicy.computeReadState(updatedPages.size, total)
        }
        val encoded = encodePages(updatedPages)
        db.prepareStatement(
            """INSERT INTO book_chapter_progress(
                book_id, toc_node_id, pages_total, pages_dwelled, dwelled_pages_json,
                read_state, manual_override, practice_draft_json, updated_at
            ) VALUES(?,?,?,?,?,?,?,'{}',?)
            ON CONFLICT(book_id, toc_node_id) DO UPDATE SET
                pages_total=MAX(book_chapter_progress.pages_total, excluded.pages_total),
                pages_dwelled=excluded.pages_dwelled,
                dwelled_pages_json=excluded.dwelled_pages_json,
                read_state=excluded.read_state,
                updated_at=excluded.updated_at""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.setInt(3, total)
            s.setInt(4, updatedPages.size)
            s.setString(5, encoded)
            s.setString(6, nextState.name)
            s.setInt(7, if (manual) 1 else 0)
            s.setLong(8, now)
            s.executeUpdate()
        }
        return ChapterProgressRecord(
            bookId = bookId,
            tocNodeId = tocNodeId,
            pagesTotal = total,
            dwelledPages = updatedPages,
            readState = nextState,
            manualOverride = manual,
            updatedAt = now,
        )
    }

    /** 用户在目录主动点击「标为已读 / 标为未读」，写入 `manual_override = 1` 优先于停留推导。 */
    fun setManualReadState(
        db: Connection,
        bookId: String,
        tocNodeId: String,
        pagesTotal: Int,
        read: Boolean,
        now: Long = System.currentTimeMillis(),
    ): ChapterProgressRecord? {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return null
        val existing = loadChapter(db, bookId, tocNodeId)
        val total = maxOf(pagesTotal.coerceAtLeast(1), existing?.pagesTotal ?: 1)
        val dwelled = existing?.dwelledPages.orEmpty()
        val nextState = if (read) ChapterReadState.READ else ChapterReadState.UNREAD
        db.prepareStatement(
            """INSERT INTO book_chapter_progress(
                book_id, toc_node_id, pages_total, pages_dwelled, dwelled_pages_json,
                read_state, manual_override, practice_draft_json, updated_at
            ) VALUES(?,?,?,?,?,?,1,'{}',?)
            ON CONFLICT(book_id, toc_node_id) DO UPDATE SET
                pages_total=MAX(book_chapter_progress.pages_total, excluded.pages_total),
                read_state=excluded.read_state,
                manual_override=1,
                updated_at=excluded.updated_at""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.setInt(3, total)
            s.setInt(4, dwelled.size)
            s.setString(5, encodePages(dwelled))
            s.setString(6, nextState.name)
            s.setLong(7, now)
            s.executeUpdate()
        }
        return ChapterProgressRecord(
            bookId = bookId,
            tocNodeId = tocNodeId,
            pagesTotal = total,
            dwelledPages = dwelled,
            readState = nextState,
            manualOverride = true,
            updatedAt = now,
        )
    }

    fun loadChapter(db: Connection, bookId: String, tocNodeId: String): ChapterProgressRecord? {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return null
        return db.prepareStatement(
            """SELECT pages_total, dwelled_pages_json, read_state, manual_override, updated_at
               FROM book_chapter_progress WHERE book_id=? AND toc_node_id=?""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.executeQuery().use { r ->
                if (!r.next()) null else ChapterProgressRecord(
                    bookId = bookId,
                    tocNodeId = tocNodeId,
                    pagesTotal = r.getInt(1),
                    dwelledPages = decodePages(r.getString(2)),
                    readState = ChapterReadState.fromWire(r.getString(3)),
                    manualOverride = r.getInt(4) != 0,
                    updatedAt = r.getLong(5),
                )
            }
        }
    }

    fun loadForBook(db: Connection, bookId: String): Map<String, ChapterProgressRecord> {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return emptyMap()
        return db.prepareStatement(
            """SELECT toc_node_id, pages_total, dwelled_pages_json, read_state, manual_override, updated_at
               FROM book_chapter_progress WHERE book_id=?""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.executeQuery().use { r ->
                val out = LinkedHashMap<String, ChapterProgressRecord>()
                while (r.next()) {
                    val id = r.getString(1)
                    out[id] = ChapterProgressRecord(
                        bookId = bookId,
                        tocNodeId = id,
                        pagesTotal = r.getInt(2),
                        dwelledPages = decodePages(r.getString(3)),
                        readState = ChapterReadState.fromWire(r.getString(4)),
                        manualOverride = r.getInt(5) != 0,
                        updatedAt = r.getLong(6),
                    )
                }
                out
            }
        }
    }

    fun readChapterIds(db: Connection, bookId: String): Set<String> {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return emptySet()
        return db.prepareStatement(
            "SELECT toc_node_id FROM book_chapter_progress WHERE book_id=? AND read_state='READ'",
        ).use { s ->
            s.setString(1, bookId)
            s.executeQuery().use { r ->
                buildSet { while (r.next()) add(r.getString(1)) }
            }
        }
    }
}
