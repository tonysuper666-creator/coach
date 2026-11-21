package com.coach.desktop.books

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

internal enum class CollectionKind {
    COURSE,
    BOOKLIST,
    CUSTOM,
}

internal data class MaterialCollectionRecord(
    val id: String,
    val kind: CollectionKind,
    val title: String,
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

internal data class CollectionMaterialRecord(
    val collectionId: String,
    val materialId: String,
    val role: String = "PRIMARY",
    val ordinal: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val metadataJson: String = "{}",
)

/**
 * Lossless 1.1.x -> 2.0 schema migration for `library.sqlite`:
 * - Creates a verified pre-migration snapshot `library.sqlite.v1.bak` when upgrading an existing v1 DB.
 * - Executes all DDL and additive column upgrades inside a single `BEGIN IMMEDIATE ... COMMIT` transaction.
 * - Never drops or overwrites legacy 1.1.x rows (`books`, `book_notes`, `book_highlights`, `answers`,
 *   `book_reviews`, `book_review_history`, `book_read_spots`, `book_learning_paths`, `book_ladders`, etc.).
 * - Restores `library.sqlite` from `library.sqlite.v1.bak` if an upgrade fails mid-flight and leaves the DB unhealthy.
 */
internal object BookSchemaMigration {
    const val V2_SCHEMA_VERSION = 2
    const val V1_BACKUP_FILENAME = "library.sqlite.v1.bak"

    private val CORE_V1_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,PRIMARY KEY(book,page))",
        "CREATE TABLE IF NOT EXISTS answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,PRIMARY KEY(book,lesson,mode))",
        """CREATE TABLE IF NOT EXISTS book_reviews(
            book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,book_title TEXT NOT NULL,lesson_title TEXT NOT NULL,
            prompt TEXT NOT NULL,reference TEXT NOT NULL,solution TEXT NOT NULL,options TEXT NOT NULL,wrong_text TEXT NOT NULL,
            answer_text TEXT NOT NULL DEFAULT '',revealed INTEGER NOT NULL DEFAULT 0,understood INTEGER,
            completed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,lesson,mode))""".trimIndent(),
        "CREATE TABLE IF NOT EXISTS book_review_position(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS book_open_session(slot INTEGER PRIMARY KEY CHECK(slot=1),book TEXT NOT NULL,screen TEXT NOT NULL CHECK(screen IN ('READER','LEARNING')))",
        "CREATE TABLE IF NOT EXISTS book_learning_paths(book TEXT PRIMARY KEY,path TEXT NOT NULL CHECK(path IN ('READ','READ_AND_LEARN','QUESTION_FIRST')))",
        "CREATE TABLE IF NOT EXISTS book_ladders(book TEXT PRIMARY KEY,session TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS book_reader_views(book TEXT PRIMARY KEY,text_view INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS book_pdf_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS book_text_modes(book TEXT PRIMARY KEY,scrolling INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS book_read_spots(book TEXT PRIMARY KEY,page INTEGER NOT NULL,offset_utf16 INTEGER NOT NULL)",
        """CREATE TABLE IF NOT EXISTS book_highlights(
            book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,
            PRIMARY KEY(book,page,start,end))""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS book_notes(
            book TEXT NOT NULL,id TEXT NOT NULL,version TEXT NOT NULL,page INTEGER NOT NULL,
            start INTEGER NOT NULL,end INTEGER NOT NULL,quote TEXT NOT NULL,note TEXT NOT NULL,
            text_sha256 TEXT NOT NULL,revision INTEGER NOT NULL,last_mutation_id TEXT NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(book,id))""".trimIndent(),
        "CREATE TABLE IF NOT EXISTS reading_settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)",
    )

    private val V2_TABLES = listOf(
        """CREATE TABLE IF NOT EXISTS collections(
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL CHECK(kind IN ('COURSE','BOOKLIST','CUSTOM')),
            title TEXT NOT NULL,
            metadata_json TEXT NOT NULL DEFAULT '{}',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS collection_materials(
            collection_id TEXT NOT NULL,
            material_id TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'PRIMARY',
            ordinal INTEGER NOT NULL DEFAULT 0,
            added_at INTEGER NOT NULL,
            metadata_json TEXT NOT NULL DEFAULT '{}',
            PRIMARY KEY(collection_id, material_id),
            FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE,
            FOREIGN KEY(material_id) REFERENCES books(id) ON DELETE CASCADE
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS book_toc_nodes(
            book TEXT NOT NULL,
            id TEXT NOT NULL,
            parent_id TEXT,
            ordinal INTEGER NOT NULL,
            level INTEGER NOT NULL,
            kind TEXT NOT NULL,
            number_label TEXT,
            title TEXT NOT NULL,
            start_page INTEGER NOT NULL,
            end_page INTEGER NOT NULL,
            printed_page TEXT,
            source TEXT NOT NULL,
            confidence REAL NOT NULL DEFAULT 1.0,
            PRIMARY KEY(book, id)
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_book_toc_nodes_order ON book_toc_nodes(book, ordinal)",
        """CREATE TABLE IF NOT EXISTS book_study_chunks(
            book TEXT NOT NULL,
            id TEXT NOT NULL,
            toc_node_id TEXT,
            ordinal INTEGER NOT NULL,
            title TEXT NOT NULL,
            breadcrumb TEXT NOT NULL,
            level INTEGER NOT NULL,
            start_page INTEGER NOT NULL,
            end_page INTEGER NOT NULL,
            char_count INTEGER NOT NULL,
            block_ids_json TEXT NOT NULL,
            PRIMARY KEY(book, id)
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_book_study_chunks_order ON book_study_chunks(book, ordinal)",
        """CREATE TABLE IF NOT EXISTS book_bookmarks(
            book TEXT NOT NULL,
            page INTEGER NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            PRIMARY KEY(book, page)
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS book_page_aspects(
            book TEXT NOT NULL,
            page INTEGER NOT NULL,
            width REAL NOT NULL,
            height REAL NOT NULL,
            rotation INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(book, page)
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS book_visual_pages(
            book TEXT NOT NULL,
            page INTEGER NOT NULL,
            extracted_at INTEGER NOT NULL,
            PRIMARY KEY(book, page)
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_pages_book_pending ON pages(book, ocr_pending, page)",
    )

    private val REQUIRED_V2_TABLES = setOf(
        "books", "pages", "answers", "book_reviews", "book_review_position",
        "book_open_session", "book_learning_paths", "book_ladders", "book_reader_views",
        "book_pdf_modes", "book_text_modes", "book_read_spots", "book_highlights",
        "book_notes", "reading_settings",
        "book_review_history", "book_illustrations", "book_page_text_boxes",
        "book_native_sources", "book_structures", "book_section_cache",
        "collections", "collection_materials", "book_toc_nodes", "book_study_chunks",
        "book_bookmarks", "book_page_aspects", "book_visual_pages", "pages_fts_meta",
    )

    fun upgradeAndMigrate(
        directory: Path,
        openConnection: () -> Connection,
        onInTransaction: (Connection) -> Unit,
        onPostCommit: (Connection?) -> Unit = {},
    ) {
        val dbFile = directory.resolve("library.sqlite")
        val backupFile = directory.resolve(V1_BACKUP_FILENAME)
        var upgradingFromV1 = false
        var currentVersion = 0
        var schemaComplete = false

        if (Files.isRegularFile(dbFile) && Files.size(dbFile) > 0L) {
            openConnection().use { probe ->
                currentVersion = readUserVersion(probe)
                val existingTables = buildSet {
                    probe.createStatement().use { sql ->
                        sql.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rows ->
                            while (rows.next()) add(rows.getString(1))
                        }
                    }
                }
                val hadBooks = "books" in existingTables
                upgradingFromV1 = hadBooks && currentVersion < V2_SCHEMA_VERSION
                if (upgradingFromV1) {
                    runCatching { probe.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
                }
                schemaComplete = currentVersion >= V2_SCHEMA_VERSION &&
                    REQUIRED_V2_TABLES.all { it in existingTables } &&
                    "index_state" in tableColumns(probe, "books") &&
                    "text_pending" in tableColumns(probe, "pages") &&
                    "due_day" in tableColumns(probe, "book_reviews") &&
                    "guessed" in tableColumns(probe, "answers")
                if (schemaComplete) {
                    onPostCommit(probe)
                    return
                }
            }
            if (upgradingFromV1 && !Files.exists(backupFile)) {
                createVerifiedBackup(dbFile, backupFile)
            }
        }

        try {
            openConnection().use { db ->
                db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    db.createStatement().use { sql ->
                        for (ddl in CORE_V1_TABLES) sql.execute(ddl)
                    }
                    val addedIndexState = ensureColumn(db, "books", "index_state", "TEXT NOT NULL DEFAULT 'READY'")
                    ensureColumn(db, "books", "material_kind", "TEXT NOT NULL DEFAULT 'BOOK'")
                    ensureColumn(db, "books", "source_filename", "TEXT")
                    ensureColumn(db, "books", "file_bytes", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "books", "sync_epoch", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "pages", "ocr", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "pages", "ocr_pending", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "pages", "text_pending", "INTEGER NOT NULL DEFAULT 0")

                    db.createStatement().use { sql ->
                        for (ddl in V2_TABLES) sql.execute(ddl)
                    }
                    BookFtsStore.migrate(db)
                    onInTransaction(db)
                    if (upgradingFromV1 || addedIndexState) {
                        db.createStatement().use { sql ->
                            sql.executeUpdate(
                                """UPDATE books SET index_state='PENDING_TOC'
                                   WHERE format='pdf'
                                     AND index_state='READY'
                                     AND NOT EXISTS (SELECT 1 FROM book_toc_nodes t WHERE t.book = books.id)""".trimIndent(),
                            )
                        }
                    }
                    db.createStatement().use { it.execute("PRAGMA user_version = $V2_SCHEMA_VERSION") }
                    db.createStatement().use { it.execute("COMMIT") }
                } catch (failure: Throwable) {
                    runCatching { db.createStatement().use { it.execute("ROLLBACK") } }
                    throw failure
                }
                onPostCommit(db)
            }
        } catch (failure: Throwable) {
            if (upgradingFromV1 && Files.isRegularFile(backupFile) && !isHealthySqlite(dbFile)) {
                runCatching {
                    Files.copy(backupFile, dbFile, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            throw failure
        }
    }

    internal fun createVerifiedBackup(sourceDb: Path, backupTarget: Path) {
        val temp = backupTarget.resolveSibling(".${backupTarget.fileName}.${System.nanoTime()}.tmp")
        try {
            Files.copy(sourceDb, temp, StandardCopyOption.REPLACE_EXISTING)
            val srcSize = Files.size(sourceDb)
            val copySize = Files.size(temp)
            check(copySize > 0L && copySize == srcSize) { "SQLite v1 backup size mismatch ($copySize vs $srcSize)" }
            check(isHealthySqlite(temp)) { "SQLite v1 backup failed integrity quick_check" }
            try {
                Files.move(temp, backupTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, backupTarget, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    internal fun isHealthySqlite(path: Path): Boolean {
        if (!Files.isRegularFile(path) || Files.size(path) <= 0L) return false
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
                conn.createStatement().use { sql ->
                    sql.executeQuery("PRAGMA quick_check").use { row ->
                        row.next() && row.getString(1).equals("ok", ignoreCase = true)
                    }
                }
            }
        }.getOrDefault(false)
    }

    internal fun readUserVersion(db: Connection): Int =
        db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA user_version").use { row -> if (row.next()) row.getInt(1) else 0 }
        }

    internal fun tableExists(db: Connection, table: String): Boolean =
        db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { sql ->
            sql.setString(1, table)
            sql.executeQuery().use { it.next() }
        }

    internal fun tableColumns(db: Connection, table: String): Set<String> = buildSet {
        db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info($table)").use { rows ->
                while (rows.next()) add(rows.getString("name"))
            }
        }
    }

    internal fun ensureColumn(db: Connection, table: String, name: String, declaration: String): Boolean {
        val cols = tableColumns(db, table)
        if (name in cols) return false
        db.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $name $declaration") }
        return true
    }
}

/**
 * FTS5 full-text index over `pages(book, page, text)` using `trigram` (with `unicode61` fallback).
 * Returns exact matching candidate page numbers in milliseconds for `searchBookPages` offset refinement.
 */
internal object BookFtsStore {
    private const val TABLE = "pages_fts"
    private const val META_TABLE = "pages_fts_meta"

    fun migrate(db: Connection) {
        val exists = BookSchemaMigration.tableExists(db, TABLE)
        if (!exists) {
            val created = runCatching {
                db.createStatement().use {
                    it.execute("CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts5(book UNINDEXED, page UNINDEXED, text, tokenize='trigram')")
                }
                "trigram"
            }.getOrElse {
                runCatching {
                    db.createStatement().use {
                        it.execute("CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts5(book UNINDEXED, page UNINDEXED, text, tokenize='unicode61')")
                    }
                    "unicode61"
                }.getOrNull()
            } ?: return
            db.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS $META_TABLE(slot INTEGER PRIMARY KEY CHECK(slot=1), tokenizer TEXT NOT NULL)")
                it.execute("INSERT OR REPLACE INTO $META_TABLE(slot, tokenizer) VALUES(1, '$created')")
            }
            if (BookSchemaMigration.tableExists(db, "pages")) {
                db.createStatement().use {
                    it.execute("INSERT INTO $TABLE(book, page, text) SELECT book, page, text FROM pages WHERE text != ''")
                }
            }
        } else if (!BookSchemaMigration.tableExists(db, META_TABLE)) {
            db.createStatement().use {
                it.execute("CREATE TABLE IF NOT EXISTS $META_TABLE(slot INTEGER PRIMARY KEY CHECK(slot=1), tokenizer TEXT NOT NULL)")
                it.execute("INSERT OR REPLACE INTO $META_TABLE(slot, tokenizer) VALUES(1, 'unicode61')")
            }
        }
    }

    fun deleteBook(db: Connection, bookId: String) {
        if (!BookSchemaMigration.tableExists(db, TABLE)) return
        db.prepareStatement("DELETE FROM $TABLE WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeUpdate()
        }
    }

    fun rebuildBook(db: Connection, bookId: String, pages: List<BookPage>) {
        if (!BookSchemaMigration.tableExists(db, TABLE)) return
        deleteBook(db, bookId)
        db.prepareStatement("INSERT INTO $TABLE(book, page, text) VALUES(?,?,?)").use { sql ->
            for (page in pages) {
                if (page.text.isEmpty()) continue
                sql.setString(1, bookId)
                sql.setInt(2, page.number)
                sql.setString(3, page.text)
                sql.addBatch()
            }
            sql.executeBatch()
        }
    }

    fun upsertPage(db: Connection, bookId: String, pageNumber: Int, text: String) {
        if (!BookSchemaMigration.tableExists(db, TABLE)) return
        db.prepareStatement("DELETE FROM $TABLE WHERE book=? AND CAST(page AS INTEGER)=?").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, pageNumber)
            sql.executeUpdate()
        }
        if (text.isEmpty()) return
        db.prepareStatement("INSERT INTO $TABLE(book, page, text) VALUES(?,?,?)").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, pageNumber)
            sql.setString(3, text)
            sql.executeUpdate()
        }
    }

    private fun tokenizerKind(db: Connection): String? {
        if (!BookSchemaMigration.tableExists(db, TABLE)) return null
        if (!BookSchemaMigration.tableExists(db, META_TABLE)) return "unicode61"
        return db.createStatement().use { sql ->
            sql.executeQuery("SELECT tokenizer FROM $META_TABLE WHERE slot=1").use { row ->
                if (row.next()) row.getString(1) else "unicode61"
            }
        }
    }

    fun queryCandidatePages(db: Connection, bookId: String, rawQuery: String): List<Int>? {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val tokenizer = tokenizerKind(db)
        if (tokenizer == "trigram") {
            // Pick the longest >=3-char token without whitespace as an exact FTS5 trigram superset filter.
            val anchorToken = query.split(Regex("\\s+")).filter { it.length >= 3 }.maxByOrNull { it.length }
            if (anchorToken != null) {
                val escaped = "\"" + anchorToken.replace("\"", "\"\"") + "\""
                val result = runCatching {
                    db.prepareStatement("SELECT CAST(page AS INTEGER) FROM $TABLE WHERE book=? AND text MATCH ? ORDER BY CAST(page AS INTEGER)").use { sql ->
                        sql.setString(1, bookId)
                        sql.setString(2, escaped)
                        sql.executeQuery().use { rows ->
                            buildList { while (rows.next()) add(rows.getInt(1)) }.distinct()
                        }
                    }
                }.getOrNull()
                if (result != null) return result
            }
        }
        // Fallback for 1-2 char queries, unicode61 non-prefix substrings, or special punctuation: SQLite instr/lower scan.
        return runCatching {
            db.prepareStatement("SELECT page FROM pages WHERE book=? AND instr(lower(text), lower(?)) > 0 ORDER BY page").use { sql ->
                sql.setString(1, bookId)
                sql.setString(2, query)
                sql.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getInt(1)) }
                }
            }
        }.getOrNull()
    }
}

internal object BookTocStore {
    private val KIND_BY_NAME = TocNodeKind.entries.associateBy { it.name }
    private val SOURCE_BY_NAME = HeadingSource.entries.associateBy { it.name }

    fun saveInTransaction(
        db: Connection,
        bookId: String,
        tocNodes: List<BookTocNode>,
        studyChunks: List<BookStudyChunk>,
    ) {
        if (BookSchemaMigration.tableExists(db, "book_toc_nodes")) {
            db.prepareStatement("DELETE FROM book_toc_nodes WHERE book=?").use {
                it.setString(1, bookId)
                it.executeUpdate()
            }
            if (tocNodes.isNotEmpty()) {
                db.prepareStatement(
                    """INSERT INTO book_toc_nodes(
                        book, id, parent_id, ordinal, level, kind, number_label, title,
                        start_page, end_page, printed_page, source, confidence
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                ).use { sql ->
                    tocNodes.forEachIndexed { index, node ->
                        sql.setString(1, bookId)
                        sql.setString(2, node.id)
                        if (node.parentId == null) sql.setNull(3, java.sql.Types.VARCHAR) else sql.setString(3, node.parentId)
                        sql.setInt(4, index)
                        sql.setInt(5, node.level)
                        sql.setString(6, node.kind.name)
                        if (node.numberLabel == null) sql.setNull(7, java.sql.Types.VARCHAR) else sql.setString(7, node.numberLabel)
                        sql.setString(8, node.title)
                        sql.setInt(9, node.startPage)
                        sql.setInt(10, node.endPage)
                        if (node.printedPage == null) sql.setNull(11, java.sql.Types.VARCHAR) else sql.setString(11, node.printedPage)
                        sql.setString(12, node.source.name)
                        sql.setDouble(13, node.confidence)
                        sql.addBatch()
                    }
                    sql.executeBatch()
                }
            }
        }

        if (BookSchemaMigration.tableExists(db, "book_study_chunks")) {
            db.prepareStatement("DELETE FROM book_study_chunks WHERE book=?").use {
                it.setString(1, bookId)
                it.executeUpdate()
            }
            if (studyChunks.isNotEmpty()) {
                db.prepareStatement(
                    """INSERT INTO book_study_chunks(
                        book, id, toc_node_id, ordinal, title, breadcrumb, level,
                        start_page, end_page, char_count, block_ids_json
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                ).use { sql ->
                    studyChunks.forEachIndexed { index, chunk ->
                        sql.setString(1, bookId)
                        sql.setString(2, chunk.id)
                        if (chunk.tocNodeId == null) sql.setNull(3, java.sql.Types.VARCHAR) else sql.setString(3, chunk.tocNodeId)
                        sql.setInt(4, index)
                        sql.setString(5, chunk.title)
                        sql.setString(6, chunk.breadcrumb)
                        sql.setInt(7, chunk.level)
                        sql.setInt(8, chunk.startPage)
                        sql.setInt(9, chunk.endPage)
                        sql.setInt(10, chunk.charCount)
                        sql.setString(11, Json.encodeToString(chunk.blockIds))
                        sql.addBatch()
                    }
                    sql.executeBatch()
                }
            }
        }
    }

    fun loadTocNodes(db: Connection, bookId: String): List<BookTocNode> {
        if (!BookSchemaMigration.tableExists(db, "book_toc_nodes")) return emptyList()
        return db.prepareStatement(
            """SELECT id, parent_id, ordinal, level, kind, number_label, title,
                      start_page, end_page, printed_page, source, confidence
               FROM book_toc_nodes WHERE book=? ORDER BY ordinal""".trimIndent(),
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val kind = rows.getString(5)?.let { KIND_BY_NAME[it] } ?: TocNodeKind.SECTION
                        val source = rows.getString(11)?.let { SOURCE_BY_NAME[it] } ?: HeadingSource.HEADING_RULE
                        add(
                            BookTocNode(
                                id = rows.getString(1),
                                parentId = rows.getString(2),
                                ordinal = rows.getInt(3),
                                level = rows.getInt(4),
                                kind = kind,
                                numberLabel = rows.getString(6),
                                title = rows.getString(7),
                                startPage = rows.getInt(8),
                                endPage = rows.getInt(9),
                                printedPage = rows.getString(10),
                                source = source,
                                confidence = rows.getDouble(12),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun parseBlockIdsFast(raw: String): List<String> {
        val s = raw.trim()
        if (s == "[]") return emptyList()
        if (s.startsWith('[') && s.endsWith(']') && '\\' !in s) {
            val result = ArrayList<String>()
            var i = 1
            val end = s.length - 1
            while (i < end) {
                while (i < end && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
                if (i >= end) break
                if (s[i] != '"') return Json.decodeFromString(raw)
                i++
                val start = i
                val close = s.indexOf('"', i)
                if (close < 0 || close >= end) return Json.decodeFromString(raw)
                result += s.substring(start, close)
                i = close + 1
            }
            return result
        }
        return Json.decodeFromString(raw)
    }

    fun loadStudyChunks(db: Connection, bookId: String): List<BookStudyChunk> {
        if (!BookSchemaMigration.tableExists(db, "book_study_chunks")) return emptyList()
        return db.prepareStatement(
            """SELECT id, toc_node_id, ordinal, title, breadcrumb, level,
                      start_page, end_page, char_count, block_ids_json
               FROM book_study_chunks WHERE book=? ORDER BY ordinal""".trimIndent(),
        ).use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val blockIds = runCatching {
                            parseBlockIdsFast(rows.getString(10))
                        }.getOrDefault(emptyList())
                        add(
                            BookStudyChunk(
                                id = rows.getString(1),
                                tocNodeId = rows.getString(2),
                                ordinal = rows.getInt(3),
                                title = rows.getString(4),
                                breadcrumb = rows.getString(5),
                                level = rows.getInt(6),
                                startPage = rows.getInt(7),
                                endPage = rows.getInt(8),
                                charCount = rows.getInt(9),
                                blockIds = blockIds,
                            ),
                        )
                    }
                }
            }
        }
    }
}

internal object BookAspectStore {
    fun saveInTransaction(db: Connection, bookId: String, boxes: List<Triple<Int, Pair<Double, Double>, Int>>) {
        if (!BookSchemaMigration.tableExists(db, "book_page_aspects")) return
        db.prepareStatement("DELETE FROM book_page_aspects WHERE book=?").use {
            it.setString(1, bookId)
            it.executeUpdate()
        }
        db.prepareStatement("INSERT INTO book_page_aspects(book, page, width, height, rotation) VALUES(?,?,?,?,?)").use { sql ->
            for ((page, dims, rot) in boxes) {
                sql.setString(1, bookId)
                sql.setInt(2, page)
                sql.setDouble(3, dims.first)
                sql.setDouble(4, dims.second)
                sql.setInt(5, rot)
                sql.addBatch()
            }
            sql.executeBatch()
        }
    }

    fun loadAspects(db: Connection, bookId: String): Map<Int, Float> {
        if (!BookSchemaMigration.tableExists(db, "book_page_aspects")) return emptyMap()
        return db.prepareStatement("SELECT page, width, height, rotation FROM book_page_aspects WHERE book=? ORDER BY page").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val page = rows.getInt("page")
                        val w = rows.getDouble("width").toFloat()
                        val h = rows.getDouble("height").toFloat()
                        val rot = rows.getInt("rotation")
                        val aspect = if (rot == 90 || rot == 270) h / w else w / h
                        if (aspect.isFinite() && aspect > 0f) put(page, aspect)
                    }
                }
            }
        }
    }
}

internal object BookVisualPageStore {
    fun extractedPages(db: Connection, bookId: String): Set<Int> {
        if (!BookSchemaMigration.tableExists(db, "book_visual_pages")) return emptySet()
        return db.prepareStatement("SELECT page FROM book_visual_pages WHERE book=?").use { sql ->
            sql.setString(1, bookId)
            sql.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getInt(1)) }
            }
        }
    }

    fun markExtracted(db: Connection, bookId: String, pages: Collection<Int>, extractedAt: Long = System.currentTimeMillis()) {
        if (pages.isEmpty() || !BookSchemaMigration.tableExists(db, "book_visual_pages")) return
        db.prepareStatement("INSERT OR REPLACE INTO book_visual_pages(book, page, extracted_at) VALUES(?,?,?)").use { sql ->
            for (p in pages) {
                sql.setString(1, bookId)
                sql.setInt(2, p)
                sql.setLong(3, extractedAt)
                sql.addBatch()
            }
            sql.executeBatch()
        }
    }
}

internal object MaterialCollectionStore {
    fun upsertCollection(db: Connection, record: MaterialCollectionRecord) {
        db.prepareStatement(
            """INSERT INTO collections(id, kind, title, metadata_json, created_at, updated_at)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, title=excluded.title,
               metadata_json=excluded.metadata_json, updated_at=excluded.updated_at""".trimIndent(),
        ).use { sql ->
            sql.setString(1, record.id)
            sql.setString(2, record.kind.name)
            sql.setString(3, record.title)
            sql.setString(4, record.metadataJson)
            sql.setLong(5, record.createdAt)
            sql.setLong(6, record.updatedAt)
            sql.executeUpdate()
        }
    }

    fun attachMaterial(db: Connection, link: CollectionMaterialRecord) {
        db.prepareStatement(
            """INSERT INTO collection_materials(collection_id, material_id, role, ordinal, added_at, metadata_json)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(collection_id, material_id) DO UPDATE SET role=excluded.role,
               ordinal=excluded.ordinal, metadata_json=excluded.metadata_json""".trimIndent(),
        ).use { sql ->
            sql.setString(1, link.collectionId)
            sql.setString(2, link.materialId)
            sql.setString(3, link.role)
            sql.setInt(4, link.ordinal)
            sql.setLong(5, link.addedAt)
            sql.setString(6, link.metadataJson)
            sql.executeUpdate()
        }
    }

    fun collections(db: Connection): List<MaterialCollectionRecord> =
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT id, kind, title, metadata_json, created_at, updated_at FROM collections ORDER BY updated_at DESC, id").use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            MaterialCollectionRecord(
                                id = rows.getString("id"),
                                kind = CollectionKind.valueOf(rows.getString("kind")),
                                title = rows.getString("title"),
                                metadataJson = rows.getString("metadata_json"),
                                createdAt = rows.getLong("created_at"),
                                updatedAt = rows.getLong("updated_at"),
                            ),
                        )
                    }
                }
            }
        }

    fun materials(db: Connection, collectionId: String): List<CollectionMaterialRecord> =
        db.prepareStatement(
            "SELECT collection_id, material_id, role, ordinal, added_at, metadata_json FROM collection_materials WHERE collection_id=? ORDER BY ordinal, added_at",
        ).use { sql ->
            sql.setString(1, collectionId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            CollectionMaterialRecord(
                                collectionId = rows.getString("collection_id"),
                                materialId = rows.getString("material_id"),
                                role = rows.getString("role"),
                                ordinal = rows.getInt("ordinal"),
                                addedAt = rows.getLong("added_at"),
                                metadataJson = rows.getString("metadata_json"),
                            ),
                        )
                    }
                }
            }
        }
}
