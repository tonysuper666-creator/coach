package com.coach.desktop.books

import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

/**
 * 跨书的「我的库」。
 * 只读已经落盘的批注、高亮、问答、错题、对比表、综述提纲和概念并排。
 * 不把页正文和讲解多送出去的原文放进库。核对出处时只看这一句还在不在原页上。
 * 记录 ID 在本地和云的格式规范合入前走 [libraryRecordIds]（现为 legacy-v1）。换规范时只换这个对象。
 */
internal interface LibraryRecordIdScheme {
    val name: String
    fun book(bookId: String): String
    fun entry(kind: LibraryKind, rawId: String): String
}

internal object LegacyLibraryRecordIds : LibraryRecordIdScheme {
    override val name: String = "legacy-v1"
    override fun book(bookId: String): String = "$name:book:$bookId"
    override fun entry(kind: LibraryKind, rawId: String): String = "$name:${kind.name.lowercase()}:$rawId"
}

internal var libraryRecordIds: LibraryRecordIdScheme = LegacyLibraryRecordIds

internal enum class LibraryKind(val label: String) {
    ANNOTATION("批注"),
    HIGHLIGHT("高亮"),
    QUESTION("问答"),
    MISTAKE("错题"),
    COMPARISON("对比"),
    OUTLINE("提纲"),
    SAYING("说法"),
}

internal data class LibraryRecord(
    val key: String,
    val scheme: String,
    val kind: LibraryKind,
    val bookId: String,
    val bookTitle: String,
    val author: String,
    val page: Int?,
    val startUtf16: Int?,
    val endUtf16: Int?,
    val primary: String,
    val quote: String,
    val detail: String,
    val body: String,
    val atMs: Long?,
)

internal data class LibraryBookHead(val id: String, val title: String, val author: String)

internal data class LibraryQuery(
    val text: String = "",
    val bookId: String? = null,
    val bookTitleContains: String? = null,
    val kind: LibraryKind? = null,
    val sinceMs: Long? = null,
    val limit: Int = 80,
)

internal data class LibrarySync(val count: Int, val rebuilt: Boolean)

internal fun openLibraryCorpus(directory: Path): Connection =
    DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

/** 把阅读位置写到这本书的原页，好让随后打开的阅读器落在那一句附近。页不在书里则不动。原句还在才记下高亮。 */
internal fun stageLibraryJump(library: TextbookLibrary, record: LibraryRecord): Boolean =
    stagePassageJump(library, record.bookId, record.page, record.startUtf16, record.endUtf16, record.quote)

internal fun stagePassageJump(
    library: TextbookLibrary,
    bookId: String,
    page: Int?,
    startUtf16: Int?,
    endUtf16: Int?,
    quote: String,
): Boolean {
    val resolved = page?.takeIf { it > 0 } ?: run {
        library.consumeQuoteJump(bookId)
        return false
    }
    val offset = startUtf16?.takeIf { it >= 0 } ?: 0
    val moved = openLibraryCorpus(library.directory).use { db ->
        db.prepareStatement(
            "UPDATE books SET reading_page=? WHERE id=? AND EXISTS (SELECT 1 FROM pages WHERE book=? AND page=?)",
        ).use { sql ->
            sql.setInt(1, resolved)
            sql.setString(2, bookId)
            sql.setString(3, bookId)
            sql.setInt(4, resolved)
            sql.executeUpdate() == 1
        }
    }
    if (!moved) {
        library.consumeQuoteJump(bookId)
        return false
    }
    library.saveReadSpot(bookId, resolved, offset)
    val end = endUtf16
    if (end != null && end > offset && quote.isNotBlank()) {
        library.stageQuoteJump(BookPassage(bookId, resolved, offset, end, quote))
    } else {
        library.consumeQuoteJump(bookId)
    }
    return true
}

internal fun syncLibraryCorpus(db: Connection): LibrarySync {
    ensureCorpusTable(db)
    val desired = collectRecords(db)
    val existing = HashMap<String, String>(desired.size.coerceAtLeast(16))
    db.createStatement().use { sql ->
        sql.executeQuery("SELECT record_key, body_hash FROM library_corpus").use { rows ->
            while (rows.next()) existing[rows.getString(1)] = rows.getString(2)
        }
    }
    val same = existing.size == desired.size && desired.all { existing[it.key] == it.hash }
    if (same) return LibrarySync(desired.size, rebuilt = false)
    val auto = db.autoCommit
    if (auto) db.autoCommit = false
    try {
        db.createStatement().use { it.execute("DELETE FROM library_corpus") }
        db.prepareStatement(
            """INSERT INTO library_corpus(
                record_key, id_scheme, kind, book_id, book_title, author, page, start_utf16, end_utf16,
                primary_text, quote, detail, body, at_ms, body_hash
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { sql ->
            for (row in desired) {
                val record = row.record
                sql.setString(1, record.key)
                sql.setString(2, record.scheme)
                sql.setString(3, record.kind.name)
                sql.setString(4, record.bookId)
                sql.setString(5, record.bookTitle)
                sql.setString(6, record.author)
                bindInt(sql, 7, record.page)
                bindInt(sql, 8, record.startUtf16)
                bindInt(sql, 9, record.endUtf16)
                sql.setString(10, record.primary)
                sql.setString(11, record.quote)
                sql.setString(12, record.detail)
                sql.setString(13, record.body)
                bindLong(sql, 14, record.atMs)
                sql.setString(15, row.hash)
                sql.addBatch()
            }
            sql.executeBatch()
        }
        if (ensureFts(db)) {
            val rebuilt = runCatching {
                db.createStatement().use {
                    it.execute("INSERT INTO library_corpus_fts(library_corpus_fts) VALUES('rebuild')")
                }
            }
            if (rebuilt.isFailure) {
                runCatching { db.createStatement().use { it.execute("DROP TABLE IF EXISTS library_corpus_fts") } }
            }
        }
        if (auto) db.commit()
    } catch (error: Exception) {
        if (auto) db.rollback()
        throw error
    } finally {
        if (auto) db.autoCommit = true
    }
    return LibrarySync(desired.size, rebuilt = true)
}

internal fun searchLibrary(db: Connection, query: LibraryQuery): List<LibraryRecord> {
    ensureCorpusTable(db)
    val text = query.text.trim()
    val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    val match = if (tokens.isNotEmpty() && BookSchemaMigration.tableExists(db, "library_corpus_fts")) trigramMatch(tokens) else null
    val limit = query.limit.coerceIn(1, 500_000)
    val rows = if (match != null) {
        runCatching { queryRecords(db, query, match, tokens, limit) }.getOrElse {
            queryRecords(db, query, match = null, tokens, limit)
        }
    } else {
        queryRecords(db, query, match = null, tokens, limit)
    }
    if (tokens.isEmpty()) return rows
    return rows.filter { record -> tokens.all { token -> record.body.contains(token, ignoreCase = true) } }
}

/** 新写入的批注和高亮记下时间。已有行的新列保持空，时间仍算未知。 */
internal fun installLibraryRecordTimes(db: Connection) {
    stampCreatedAt(db, "book_notes", "book_notes_stamp_created", "book = NEW.book AND id = NEW.id")
    stampCreatedAt(
        db,
        "book_highlights",
        "book_highlights_stamp_created",
        "book = NEW.book AND page = NEW.page AND start = NEW.start AND end = NEW.end",
    )
}

private fun stampCreatedAt(db: Connection, table: String, trigger: String, identity: String) {
    if (!BookSchemaMigration.tableExists(db, table)) return
    BookSchemaMigration.ensureColumn(db, table, "created_at", "INTEGER")
    db.createStatement().use {
        it.execute(
            """CREATE TRIGGER IF NOT EXISTS $trigger
               AFTER INSERT ON $table
               FOR EACH ROW
               WHEN NEW.created_at IS NULL
               BEGIN
                 UPDATE $table SET created_at = CAST(strftime('%s','now') AS INTEGER) * 1000
                 WHERE $identity AND created_at IS NULL;
               END""",
        )
    }
}

internal fun listLibraryRecords(db: Connection): List<LibraryRecord> =
    searchLibrary(db, LibraryQuery(limit = 500_000))

internal fun listLibraryBooks(db: Connection): List<LibraryBookHead> {
    if (!BookSchemaMigration.tableExists(db, "books")) return emptyList()
    val authorCol = authorColumn(db)
    val sql = if (authorCol == null) "SELECT id, title FROM books ORDER BY title, id"
    else "SELECT id, title, $authorCol FROM books ORDER BY title, id"
    return db.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            buildList {
                while (rows.next()) {
                    val author = if (authorCol == null) "" else rows.getString(3).orEmpty()
                    val title = visibleBookTitle(rows.getString(2).orEmpty()).ifBlank { "未命名的书" }
                    add(LibraryBookHead(rows.getString(1), title, author.trim()))
                }
            }
        }
    }
}

private data class DesiredRecord(val record: LibraryRecord, val hash: String, val key: String = record.key)

private fun collectRecords(db: Connection): List<DesiredRecord> {
    val ids = libraryRecordIds
    val books = listLibraryBooks(db).associateBy { it.id }
    if (books.isEmpty()) return emptyList()
    val toc = tocPages(db)
    val times = mistakeTimes(db)
    val out = ArrayList<DesiredRecord>()
    fun add(record: LibraryRecord) {
        val hash = corpusHash(record)
        out += DesiredRecord(record, hash)
    }
    loadNotes(db, books, ids, ::add)
    loadHighlights(db, books, ids, ::add)
    loadQuestions(db, books, ids, ::add)
    loadMistakes(db, books, toc, times, ids, ::add)
    collectStudyRecords(db, books, ids).forEach(::add)
    return out
}

private fun loadNotes(
    db: Connection,
    books: Map<String, LibraryBookHead>,
    ids: LibraryRecordIdScheme,
    add: (LibraryRecord) -> Unit,
) {
    if (!BookSchemaMigration.tableExists(db, "book_notes")) return
    val created = if ("created_at" in BookSchemaMigration.tableColumns(db, "book_notes")) "created_at" else "NULL"
    db.prepareStatement(
        "SELECT book, id, page, start, end, quote, note, $created FROM book_notes WHERE deleted=0",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                val book = books[rows.getString(1)] ?: continue
                val note = notesOneLine(rows.getString(7).orEmpty())
                if (note.isEmpty()) continue
                val quote = notesOneLine(rows.getString(6).orEmpty())
                val page = rows.getInt(3).takeIf { it > 0 }
                val detail = bullet("我写的" to note, "选中的原句" to quote)
                val at = rows.longOrNull(8)?.takeIf { it > 0L }
                add(record(ids, LibraryKind.ANNOTATION, rows.getString(2), book, page, rows.intOrNull(4), rows.intOrNull(5), note, quote, detail, at))
            }
        }
    }
}

private fun loadHighlights(
    db: Connection,
    books: Map<String, LibraryBookHead>,
    ids: LibraryRecordIdScheme,
    add: (LibraryRecord) -> Unit,
) {
    if (!BookSchemaMigration.tableExists(db, "book_highlights")) return
    val created = if ("created_at" in BookSchemaMigration.tableColumns(db, "book_highlights")) "created_at" else "NULL"
    db.prepareStatement("SELECT book, page, start, end, quote, $created FROM book_highlights").use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                val book = books[rows.getString(1)] ?: continue
                val quote = notesOneLine(rows.getString(5).orEmpty())
                if (quote.isEmpty()) continue
                val page = rows.getInt(2)
                val start = rows.getInt(3)
                val end = rows.getInt(4)
                val detail = bullet("选中的原句" to quote)
                val raw = "${book.id}:$page:$start:$end"
                val at = rows.longOrNull(6)?.takeIf { it > 0L }
                add(record(ids, LibraryKind.HIGHLIGHT, raw, book, page.takeIf { it > 0 }, start, end, quote, quote, detail, at))
            }
        }
    }
}

private fun loadQuestions(
    db: Connection,
    books: Map<String, LibraryBookHead>,
    ids: LibraryRecordIdScheme,
    add: (LibraryRecord) -> Unit,
) {
    if (!BookSchemaMigration.tableExists(db, "book_ask_turns")) return
    val cols = BookSchemaMigration.tableColumns(db, "book_ask_turns")
    if ("question_text" !in cols) return
    val created = if ("created_at" in cols) "created_at" else "NULL"
    db.prepareStatement(
        """SELECT id, book, selection_page, selection_start, selection_end, selection_text, question_text, answer_text, $created
           FROM book_ask_turns
           WHERE question_text IS NOT NULL AND length(trim(question_text))>0""",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                val book = books[rows.getString(2)] ?: continue
                val question = notesOneLine(rows.getString(7).orEmpty())
                if (question.isEmpty()) continue
                val answer = notesAnswerSummary(rows.getString(8).orEmpty()).ifBlank { "（没有记下回答）" }
                val quote = notesOneLine(rows.getString(6).orEmpty())
                val at = rows.longOrNull(9)?.takeIf { it > 0L }
                val detail = bullet("问题" to question, "回答摘要" to answer, "选中的原句" to quote)
                add(
                    record(
                        ids, LibraryKind.QUESTION, rows.getLong(1).toString(), book,
                        rows.getInt(3).takeIf { it > 0 }, rows.intOrNull(4), rows.intOrNull(5),
                        question, quote, detail, at,
                    ),
                )
            }
        }
    }
}

private fun loadMistakes(
    db: Connection,
    books: Map<String, LibraryBookHead>,
    toc: Map<String, Map<String, Int>>,
    times: Map<String, Long>,
    ids: LibraryRecordIdScheme,
    add: (LibraryRecord) -> Unit,
) {
    if (!BookSchemaMigration.tableExists(db, "book_reviews")) return
    val cols = BookSchemaMigration.tableColumns(db, "book_reviews")
    if ("prompt" !in cols || "lesson" !in cols || "mode" !in cols) return
    val guessed = if ("guessed" in cols) "r.guessed" else "0"
    val sources = if ("sources" in cols) "r.sources" else "0"
    val completed = if ("completed" in cols) "r.completed" else "0"
    val understood = if ("understood" in cols) "r.understood" else "NULL"
    val revealed = if ("revealed" in cols) "r.revealed" else "0"
    val history = BookSchemaMigration.tableExists(db, "book_review_history")
    val historySql = if (history) {
        """,
        (SELECT h.understood FROM book_review_history h
          WHERE h.book=r.book AND h.lesson=r.lesson AND h.mode=r.mode ORDER BY h.rowid LIMIT 1) AS first_understood,
        (SELECT h.guessed FROM book_review_history h
          WHERE h.book=r.book AND h.lesson=r.lesson AND h.mode=r.mode ORDER BY h.rowid LIMIT 1) AS first_guessed"""
    } else ""
    val sqlText = """
        SELECT r.book, r.lesson, r.mode, r.prompt, r.solution, r.wrong_text, r.answer_text,
               $revealed AS revealed, $understood AS understood, $guessed AS guessed,
               $completed AS completed, $sources AS sources
               $historySql
        FROM book_reviews r
    """.trimIndent()
    db.prepareStatement(sqlText).use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                val book = books[rows.getString(1)] ?: continue
                val lesson = rows.getString(2)
                val mode = rows.getString(3)
                val understoodRaw = rows.getObject("understood")
                val firstUnderstoodRaw = if (history) rows.getObject("first_understood") else null
                val firstGuessedRaw = if (history) rows.getObject("first_guessed") else null
                val stored = StoredMistakeRow(
                    lesson = lesson,
                    mode = mode,
                    prompt = rows.getString(4).orEmpty(),
                    solution = rows.getString(5).orEmpty(),
                    wrongText = rows.getString(6).orEmpty(),
                    answerText = rows.getString(7).orEmpty(),
                    revealed = rows.getInt("revealed") != 0,
                    understood = if (understoodRaw == null) null else rows.getInt("understood") != 0,
                    guessed = rows.getInt("guessed") != 0,
                    completed = rows.getInt("completed") != 0,
                    sources = rows.getInt("sources"),
                    firstUnderstood = if (firstUnderstoodRaw == null) null else rows.getInt("first_understood") != 0,
                    firstGuessed = firstGuessedRaw != null && rows.getInt("first_guessed") != 0,
                )
                if (!isWrongOrUncertain(stored)) continue
                val mine = notesOneLine(stored.answerText).ifBlank { notesOneLine(stored.wrongText) }.ifBlank { "（没有记下我的答案）" }
                val correct = notesOneLine(stored.solution).ifBlank { "（没有单独记下正解）" }
                val stem = notesOneLine(stored.prompt).ifBlank { "（题干未记下）" }
                val uncertain = stored.guessed || stored.firstGuessed ||
                    stored.understood == false || stored.firstUnderstood == false ||
                    (stored.sources and LocalReviewSource.GUESSED.bit) != 0 ||
                    (stored.sources and LocalReviewSource.SELF_CHECK.bit) != 0
                val state = if (uncertain) "不确定" else "错题"
                val page = sourcePageForLesson(lesson, emptyMap(), toc[book.id].orEmpty())
                val detail = bullet("题干" to stem, "我的答案" to mine, "正解" to correct, "状态" to state)
                val at = times["${book.id}|$lesson|$mode"]
                add(record(ids, LibraryKind.MISTAKE, "${book.id}:$lesson:$mode", book, page, null, null, stem, "", detail, at))
            }
        }
    }
}

internal fun record(
    ids: LibraryRecordIdScheme,
    kind: LibraryKind,
    rawId: String,
    book: LibraryBookHead,
    page: Int?,
    start: Int?,
    end: Int?,
    primary: String,
    quote: String,
    detail: String,
    atMs: Long?,
): LibraryRecord {
    val body = listOf(book.title, primary, quote, detailText(detail)).filter { it.isNotBlank() }.joinToString("\n")
    return LibraryRecord(
        key = ids.entry(kind, rawId),
        scheme = ids.name,
        kind = kind,
        bookId = book.id,
        bookTitle = notesOneLine(book.title).ifBlank { "未命名的书" },
        author = book.author,
        page = page,
        startUtf16 = start,
        endUtf16 = end,
        primary = primary,
        quote = quote,
        detail = detail,
        body = body,
        atMs = atMs,
    )
}

internal fun bullet(vararg pairs: Pair<String, String>): String = buildString {
    for ((label, text) in pairs) {
        val line = notesOneLine(text)
        if (line.isEmpty()) continue
        append(label).append('\t').append(line).append('\n')
    }
}

private fun detailText(detail: String): String = detail.lineSequence().mapNotNull { line ->
    val tab = line.indexOf('\t')
    if (tab < 0) null else line.substring(tab + 1)
}.joinToString("\n")

private fun tocPages(db: Connection): Map<String, Map<String, Int>> {
    if (!BookSchemaMigration.tableExists(db, "book_toc_nodes")) return emptyMap()
    val out = HashMap<String, MutableMap<String, Int>>()
    db.createStatement().use { sql ->
        sql.executeQuery("SELECT book, id, start_page FROM book_toc_nodes").use { rows ->
            while (rows.next()) {
                out.getOrPut(rows.getString(1)) { HashMap() }[rows.getString(2)] = rows.getInt(3)
            }
        }
    }
    return out
}

private fun mistakeTimes(db: Connection): Map<String, Long> {
    val out = HashMap<String, Long>()
    if (BookSchemaMigration.tableExists(db, "book_fsrs_cards")) {
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT book, lesson, mode, updated_at FROM book_fsrs_cards").use { rows ->
                while (rows.next()) {
                    val at = rows.getLong(4)
                    if (!rows.wasNull() && at > 0L) out["${rows.getString(1)}|${rows.getString(2)}|${rows.getString(3)}"] = at
                }
            }
        }
    }
    if (BookSchemaMigration.tableExists(db, "book_review_history")) {
        db.createStatement().use { sql ->
            sql.executeQuery(
                """SELECT book, lesson, mode, MAX(answered_day) FROM book_review_history
                   WHERE answered_day IS NOT NULL GROUP BY book, lesson, mode""",
            ).use { rows ->
                while (rows.next()) {
                    val key = "${rows.getString(1)}|${rows.getString(2)}|${rows.getString(3)}"
                    if (key in out) continue
                    val day = rows.getLong(4)
                    if (!rows.wasNull() && day > 0L) out[key] = day * 86_400_000L
                }
            }
        }
    }
    return out
}

private fun authorColumn(db: Connection): String? {
    val cols = BookSchemaMigration.tableColumns(db, "books")
    return listOf("author", "creator", "book_author").firstOrNull { it in cols }
}

private fun ensureCorpusTable(db: Connection) {
    db.createStatement().use {
        it.execute(
            """CREATE TABLE IF NOT EXISTS library_corpus(
                record_key TEXT PRIMARY KEY,
                id_scheme TEXT NOT NULL,
                kind TEXT NOT NULL,
                book_id TEXT NOT NULL,
                book_title TEXT NOT NULL,
                author TEXT NOT NULL DEFAULT '',
                page INTEGER,
                start_utf16 INTEGER,
                end_utf16 INTEGER,
                primary_text TEXT NOT NULL,
                quote TEXT NOT NULL DEFAULT '',
                detail TEXT NOT NULL,
                body TEXT NOT NULL,
                at_ms INTEGER,
                body_hash TEXT NOT NULL
            )""",
        )
        it.execute("CREATE INDEX IF NOT EXISTS idx_library_corpus_filter ON library_corpus(book_id, kind, at_ms)")
    }
}

private fun ensureFts(db: Connection): Boolean {
    if (BookSchemaMigration.tableExists(db, "library_corpus_fts")) return true
    return runCatching {
        db.createStatement().use {
            it.execute(
                """CREATE VIRTUAL TABLE library_corpus_fts USING fts5(
                    body,
                    content='library_corpus',
                    content_rowid='rowid',
                    tokenize='trigram'
                )""",
            )
        }
        true
    }.getOrDefault(false)
}

private fun trigramMatch(tokens: List<String>): String? {
    val parts = tokens.mapNotNull { token ->
        val cleaned = token.replace("\"", "").replace("*", "").trim()
        if (cleaned.length < 3) null else "\"" + cleaned.replace("\"", "\"\"") + "\""
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(" AND ")
}

private fun queryRecords(db: Connection, query: LibraryQuery, match: String?, tokens: List<String>, fetch: Int): List<LibraryRecord> {
    val sql = buildString {
        append(
            """SELECT c.record_key, c.id_scheme, c.kind, c.book_id, c.book_title, c.author, c.page,
                      c.start_utf16, c.end_utf16, c.primary_text, c.quote, c.detail, c.body, c.at_ms
               FROM library_corpus c """,
        )
        if (match != null) append("JOIN library_corpus_fts ON library_corpus_fts.rowid = c.rowid ")
        append("WHERE 1=1 ")
        if (match != null) append("AND library_corpus_fts MATCH ? ")
        repeat(tokens.size) { append("AND instr(lower(c.body), lower(?)) > 0 ") }
        if (query.bookId != null) append("AND c.book_id = ? ")
        if (!query.bookTitleContains.isNullOrBlank()) append("AND c.book_title LIKE ? ESCAPE '\\' ")
        if (query.kind != null) append("AND c.kind = ? ")
        if (query.sinceMs != null) append("AND c.at_ms IS NOT NULL AND c.at_ms >= ? ")
        append("ORDER BY CASE WHEN c.at_ms IS NULL THEN 1 ELSE 0 END, c.at_ms DESC, c.book_title, COALESCE(c.page, 2147483647), c.record_key LIMIT ?")
    }
    return db.prepareStatement(sql).use { statement ->
        var index = 1
        if (match != null) statement.setString(index++, match)
        for (token in tokens) statement.setString(index++, token)
        if (query.bookId != null) statement.setString(index++, query.bookId)
        if (!query.bookTitleContains.isNullOrBlank()) statement.setString(index++, likeContains(query.bookTitleContains))
        if (query.kind != null) statement.setString(index++, query.kind.name)
        if (query.sinceMs != null) statement.setLong(index++, query.sinceMs)
        statement.setInt(index, fetch)
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) add(readRecord(rows))
            }
        }
    }
}

private fun likeContains(raw: String): String {
    val escaped = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$escaped%"
}

private fun readRecord(rows: ResultSet): LibraryRecord {
    val kind = runCatching { LibraryKind.valueOf(rows.getString("kind")) }.getOrDefault(LibraryKind.ANNOTATION)
    return LibraryRecord(
        key = rows.getString("record_key"),
        scheme = rows.getString("id_scheme"),
        kind = kind,
        bookId = rows.getString("book_id"),
        bookTitle = visibleBookTitle(rows.getString("book_title").orEmpty()),
        author = rows.getString("author").orEmpty(),
        page = rows.intOrNull("page"),
        startUtf16 = rows.intOrNull("start_utf16"),
        endUtf16 = rows.intOrNull("end_utf16"),
        primary = rows.getString("primary_text").orEmpty(),
        quote = rows.getString("quote").orEmpty(),
        detail = rows.getString("detail").orEmpty(),
        body = rows.getString("body").orEmpty(),
        atMs = rows.longOrNull("at_ms"),
    )
}

private fun corpusHash(record: LibraryRecord): String {
    val raw = listOf(
        record.key, record.kind.name, record.bookId, record.bookTitle, record.author,
        record.page?.toString().orEmpty(), record.startUtf16?.toString().orEmpty(), record.endUtf16?.toString().orEmpty(),
        record.primary, record.quote, record.detail, record.body, record.atMs?.toString().orEmpty(),
    ).joinToString("\u0000")
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

private fun bindInt(sql: java.sql.PreparedStatement, index: Int, value: Int?) {
    if (value == null) sql.setNull(index, Types.INTEGER) else sql.setInt(index, value)
}

private fun bindLong(sql: java.sql.PreparedStatement, index: Int, value: Long?) {
    if (value == null) sql.setNull(index, Types.INTEGER) else sql.setLong(index, value)
}

private fun ResultSet.intOrNull(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun ResultSet.intOrNull(index: Int): Int? {
    val value = getInt(index)
    return if (wasNull()) null else value
}

private fun ResultSet.longOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun ResultSet.longOrNull(index: Int): Long? {
    val value = getLong(index)
    return if (wasNull()) null else value
}
