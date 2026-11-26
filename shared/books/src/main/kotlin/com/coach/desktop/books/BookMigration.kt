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
    /** One book read on its own; the collection only carries order, anchor and lock state. */
    SINGLE_BOOK,
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
    val sortOrder: Int = 0,
    val examDate: String? = null,
    val lastActiveAnchorJson: String? = null,
    val sourceHash: String? = null,
    val userLocked: Boolean = false,
    val archived: Boolean = false,
)

internal data class CollectionMaterialRecord(
    val collectionId: String,
    val materialId: String,
    val role: String = "PRIMARY",
    val ordinal: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val metadataJson: String = "{}",
    val weekNum: Int? = null,
    val userLocked: Boolean = false,
    val authorityRank: Int = 2,
    val sourceHash: String? = null,
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

    /** 2.0-W2: single book, course and book list share collections, chapter progress and FSRS cards. */
    const val V3_SCHEMA_VERSION = 3

    /** 2.0-AI-1 (Stage 4): concept mastery ledger, learner evidence stream, 4-point learning gain cohorts & whisper audit. */
    const val V4_SCHEMA_VERSION = 4

    /** TON-1823 PR A: persistent per-account unified book upload queue & local-to-server shelf mapping. */
    const val V5_SCHEMA_VERSION = 5

    /** TON-1848: transactional SQLite persistence for companion Q&A threads (`book_ask_turns`). */
    const val V6_SCHEMA_VERSION = 6

    /** TON-1833: persistent per-account remote unbind (`PENDING_DELETE`), `book_pending_deletes`, and `books.import_account_key`. */
    const val V7_SCHEMA_VERSION = 7
    const val V8_SCHEMA_VERSION = 8
    const val CURRENT_SCHEMA_VERSION = 9
    const val V1_BACKUP_FILENAME = "library.sqlite.v1.bak"
    const val V2_BACKUP_FILENAME = "library.sqlite.v2.bak"
    const val V3_BACKUP_FILENAME = "library.sqlite.v3.bak"
    const val V4_BACKUP_FILENAME = "library.sqlite.v4.bak"
    const val V5_BACKUP_FILENAME = "library.sqlite.v5.bak"
    const val V6_BACKUP_FILENAME = "library.sqlite.v6.bak"

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

    private val COLLECTIONS_DDL = """CREATE TABLE IF NOT EXISTS collections(
            id TEXT PRIMARY KEY,
            kind TEXT NOT NULL CHECK(kind IN ('SINGLE_BOOK','COURSE','BOOKLIST','CUSTOM')),
            title TEXT NOT NULL,
            metadata_json TEXT NOT NULL DEFAULT '{}',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0,
            exam_date TEXT,
            last_active_anchor_json TEXT,
            source_hash TEXT,
            user_locked INTEGER NOT NULL DEFAULT 0,
            archived INTEGER NOT NULL DEFAULT 0
        )""".trimIndent()

    private val COLLECTION_MATERIALS_DDL = """CREATE TABLE IF NOT EXISTS collection_materials(
            collection_id TEXT NOT NULL,
            material_id TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'PRIMARY',
            ordinal INTEGER NOT NULL DEFAULT 0,
            added_at INTEGER NOT NULL,
            metadata_json TEXT NOT NULL DEFAULT '{}',
            week_num INTEGER,
            user_locked INTEGER NOT NULL DEFAULT 0,
            authority_rank INTEGER NOT NULL DEFAULT 2,
            source_hash TEXT,
            PRIMARY KEY(collection_id, material_id),
            FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE,
            FOREIGN KEY(material_id) REFERENCES books(id) ON DELETE CASCADE
        )""".trimIndent()

    /** FSRS-5 memory state per review card; `book_reviews` keeps the question snapshot and the history. */
    val BOOK_FSRS_CARDS_DDL = """CREATE TABLE IF NOT EXISTS book_fsrs_cards(
            book TEXT NOT NULL,
            lesson TEXT NOT NULL,
            mode TEXT NOT NULL,
            stability REAL,
            difficulty REAL,
            last_review_day INTEGER,
            due_day INTEGER NOT NULL,
            reps INTEGER NOT NULL DEFAULT 0,
            lapses INTEGER NOT NULL DEFAULT 0,
            origin TEXT NOT NULL DEFAULT 'NEW',
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(book, lesson, mode)
        )""".trimIndent()

    private val V3_TABLES = listOf(
        """CREATE TABLE IF NOT EXISTS book_chapter_progress(
            book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            toc_node_id TEXT NOT NULL,
            pages_total INTEGER NOT NULL,
            pages_dwelled INTEGER NOT NULL DEFAULT 0,
            dwelled_pages_json TEXT NOT NULL DEFAULT '[]',
            read_state TEXT NOT NULL DEFAULT 'UNREAD' CHECK(read_state IN ('UNREAD','PARTIAL','READ')),
            manual_override INTEGER NOT NULL DEFAULT 0,
            practice_draft_json TEXT NOT NULL DEFAULT '{}',
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(book_id, toc_node_id)
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS course_topics(
            topic_id TEXT PRIMARY KEY,
            collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
            week_label TEXT NOT NULL,
            ord INTEGER NOT NULL,
            title TEXT NOT NULL,
            exam_status TEXT NOT NULL DEFAULT 'EXAMINABLE' CHECK(exam_status IN ('EXAMINABLE','EXCLUDED','PENDING_MATERIALS')),
            user_locked INTEGER NOT NULL DEFAULT 0,
            exam_weight REAL NOT NULL DEFAULT 1.0,
            pack_payload_json TEXT,
            pack_sources_hash TEXT,
            practice_draft_json TEXT NOT NULL DEFAULT '{}',
            mastery_state TEXT NOT NULL DEFAULT 'UNSEEN',
            updated_at INTEGER NOT NULL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_course_topics_order ON course_topics(collection_id, ord)",
        """CREATE TABLE IF NOT EXISTS course_topic_sources(
            topic_id TEXT NOT NULL REFERENCES course_topics(topic_id) ON DELETE CASCADE,
            book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            toc_node_id TEXT,
            start_page INTEGER NOT NULL,
            end_page INTEGER NOT NULL,
            source_role TEXT NOT NULL,
            user_locked INTEGER NOT NULL DEFAULT 0,
            scope_note TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(topic_id, book_id, start_page, end_page)
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS concept_entities(
            concept_id TEXT PRIMARY KEY,
            collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
            canonical_name TEXT NOT NULL,
            aliases_json TEXT NOT NULL DEFAULT '[]',
            muted INTEGER NOT NULL DEFAULT 0,
            muted_at INTEGER,
            shared_note TEXT NOT NULL DEFAULT ''
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS concept_mentions(
            mention_id TEXT PRIMARY KEY,
            concept_id TEXT NOT NULL REFERENCES concept_entities(concept_id) ON DELETE CASCADE,
            book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            toc_node_id TEXT,
            chunk_id TEXT,
            page INTEGER NOT NULL,
            offset_utf16 INTEGER NOT NULL DEFAULT 0,
            quote_anchor TEXT NOT NULL,
            mention_role TEXT NOT NULL CHECK(mention_role IN ('PRIMARY_DEF','FORMAL_MODEL','EMPIRICAL_TEST','WORKED_PROBLEM','COUNTER_VIEW')),
            claim_summary TEXT NOT NULL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_concept_mentions_book ON concept_mentions(book_id, page)",
        """CREATE TABLE IF NOT EXISTS concept_links(
            link_id TEXT PRIMARY KEY,
            collection_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
            concept_id TEXT NOT NULL REFERENCES concept_entities(concept_id) ON DELETE CASCADE,
            from_mention_id TEXT NOT NULL REFERENCES concept_mentions(mention_id) ON DELETE CASCADE,
            to_mention_id TEXT NOT NULL REFERENCES concept_mentions(mention_id) ON DELETE CASCADE,
            relation_type TEXT NOT NULL CHECK(relation_type IN ('CONSISTENT_DEEPENING','NOTATION_OR_BOUNDARY_DIFF','EMPIRICAL_CHALLENGE','OPPOSING_VIEW','PREREQUISITE')),
            diff_summary TEXT NOT NULL,
            confidence REAL NOT NULL,
            dismissed INTEGER NOT NULL DEFAULT 0
        )""".trimIndent(),
        BOOK_FSRS_CARDS_DDL,
        "CREATE INDEX IF NOT EXISTS idx_book_fsrs_cards_due ON book_fsrs_cards(due_day)",
    )

    private val V4_TABLES = listOf(
        """CREATE TABLE IF NOT EXISTS concept_mastery_ledger(
            concept_key TEXT PRIMARY KEY,
            collection_id TEXT REFERENCES collections(id) ON DELETE SET NULL,
            book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            toc_node_id TEXT NOT NULL,
            canonical_name TEXT NOT NULL,
            syllabus_weight REAL NOT NULL DEFAULT 1.0,
            p_mastery REAL NOT NULL DEFAULT 0.15,
            has_active_test INTEGER NOT NULL DEFAULT 0,
            fsrs_stability REAL NOT NULL DEFAULT 1.0,
            dominant_error_tag TEXT,
            reread_count INTEGER NOT NULL DEFAULT 0,
            dwell_ms_total INTEGER NOT NULL DEFAULT 0,
            ask_count INTEGER NOT NULL DEFAULT 0,
            highlight_count INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_concept_mastery_book ON concept_mastery_ledger(book_id, toc_node_id)",
        """CREATE TABLE IF NOT EXISTS learner_evidence_events(
            event_id TEXT PRIMARY KEY,
            concept_key TEXT NOT NULL REFERENCES concept_mastery_ledger(concept_key) ON DELETE CASCADE,
            book_id TEXT NOT NULL,
            page INTEGER NOT NULL,
            signal_type TEXT NOT NULL CHECK(signal_type IN (
                'PRETEST_T0', 'POST_QUIZ_T1', 'DELAYED_TRANSFER_T2', 'RUBRIC_GRADE',
                'MISCONCEPTION_DIAG', 'PAGE_DWELL', 'REREAD_BACKTRACK', 'SELECTION_ASK', 'HIGHLIGHT_NOTE'
            )),
            score_ratio REAL,
            guessed INTEGER NOT NULL DEFAULT 0,
            error_class TEXT,
            duration_ms INTEGER NOT NULL DEFAULT 0,
            detail_json TEXT NOT NULL DEFAULT '{}',
            created_at INTEGER NOT NULL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_learner_evidence_concept ON learner_evidence_events(concept_key, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_learner_evidence_book_page ON learner_evidence_events(book_id, page, created_at)",
        """CREATE TABLE IF NOT EXISTS learning_gain_cohorts(
            cohort_id TEXT PRIMARY KEY,
            collection_id TEXT,
            book_id TEXT NOT NULL,
            chapter_id TEXT NOT NULL,
            concept_key TEXT NOT NULL,
            t0_at INTEGER,
            t0_score REAL,
            t0_guessed INTEGER NOT NULL DEFAULT 0,
            read_completed_at INTEGER,
            read_dwell_sec INTEGER NOT NULL DEFAULT 0,
            t1_at INTEGER,
            t1_score REAL,
            t2_eligible_after INTEGER,
            t2_at INTEGER,
            t2_variant_id TEXT,
            t2_score REAL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_learning_gain_t2_due ON learning_gain_cohorts(t2_eligible_after, t2_at)",
        """CREATE TABLE IF NOT EXISTS margin_whisper_audit(
            whisper_id TEXT PRIMARY KEY,
            book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            toc_node_id TEXT NOT NULL,
            page INTEGER NOT NULL,
            rule TEXT NOT NULL,
            collapsed_text TEXT NOT NULL,
            expanded_text TEXT NOT NULL,
            pdf_page_anchor INTEGER NOT NULL,
            quote_anchor TEXT NOT NULL DEFAULT '',
            trigger_evidence_json TEXT NOT NULL,
            dismissed INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_margin_whisper_book_sec ON margin_whisper_audit(book_id, toc_node_id, created_at)",
    )

    private val BOOK_UPLOAD_QUEUE_DDL = """CREATE TABLE IF NOT EXISTS book_upload_queue(
            account_key TEXT NOT NULL,
            local_book_id TEXT NOT NULL,
            file_sha256 TEXT NOT NULL,
            server_shelf_book_id TEXT,
            format TEXT NOT NULL,
            title TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN (
                'WAITING_NETWORK','WAITING_SERVER','UPLOADING','RECEIVED','QUEUED','PROCESSING','READY','FAILED','PAUSED','PENDING_DELETE'
            )),
            progress_pct INTEGER NOT NULL DEFAULT 0,
            stage_label TEXT NOT NULL DEFAULT '',
            error_message TEXT,
            paused INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(account_key, local_book_id)
        )""".trimIndent()

    private val V5_TABLES = listOf(
        BOOK_UPLOAD_QUEUE_DDL,
        "CREATE INDEX IF NOT EXISTS idx_book_upload_queue_account ON book_upload_queue(account_key, paused, state)",
        """CREATE TABLE IF NOT EXISTS book_upload_account_bindings(
            session_key TEXT PRIMARY KEY,
            account_key TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )""".trimIndent(),
    )

    private val V6_TABLES = listOf(
        """CREATE TABLE IF NOT EXISTS book_ask_turns(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            book TEXT NOT NULL,
            thread_id TEXT NOT NULL,
            selection_id TEXT NOT NULL,
            selection_page INTEGER NOT NULL,
            selection_start INTEGER NOT NULL,
            selection_end INTEGER NOT NULL,
            selection_text TEXT NOT NULL,
            section_location TEXT NOT NULL DEFAULT '',
            action_kind TEXT NOT NULL DEFAULT 'ask',
            label TEXT NOT NULL,
            question_text TEXT,
            answer_text TEXT NOT NULL,
            citations_json TEXT NOT NULL DEFAULT '[]',
            sent_blocks_json TEXT NOT NULL DEFAULT '[]',
            elapsed_ms INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_book_ask_turns_book_thread ON book_ask_turns(book, thread_id, id)",
        "CREATE INDEX IF NOT EXISTS idx_book_ask_turns_book_id ON book_ask_turns(book, id)",
    )

    private val V7_TABLES = listOf(
        """CREATE TABLE IF NOT EXISTS book_pending_deletes(
            account_key TEXT NOT NULL,
            server_shelf_book_id TEXT NOT NULL,
            local_book_id TEXT NOT NULL,
            file_sha256 TEXT NOT NULL,
            format TEXT NOT NULL,
            title TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(account_key, server_shelf_book_id)
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_book_pending_deletes_account ON book_pending_deletes(account_key, created_at)",
    )

    private val V2_TABLES = listOf(
        COLLECTIONS_DDL,
        COLLECTION_MATERIALS_DDL,
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
        "book_native_sources", "book_structures", "book_section_cache", "book_body_start",
        "collections", "collection_materials", "book_toc_nodes", "book_study_chunks",
        "book_bookmarks", "book_page_aspects", "book_visual_pages", "pages_fts_meta",
    )

    private val REQUIRED_V3_TABLES = REQUIRED_V2_TABLES + setOf(
        "book_chapter_progress", "course_topics", "course_topic_sources",
        "concept_entities", "concept_mentions", "concept_links", "book_fsrs_cards",
    )

    private val REQUIRED_V4_TABLES = REQUIRED_V3_TABLES + setOf(
        "concept_mastery_ledger", "learner_evidence_events", "learning_gain_cohorts", "margin_whisper_audit",
    )

    private val REQUIRED_V5_TABLES = REQUIRED_V4_TABLES + setOf(
        "book_upload_queue", "book_upload_account_bindings",
    )

    private val REQUIRED_V6_TABLES = REQUIRED_V5_TABLES + setOf(
        "book_ask_turns",
    )

    private val REQUIRED_V7_TABLES = REQUIRED_V6_TABLES + setOf(
        "book_pending_deletes",
    )

    fun upgradeAndMigrate(
        directory: Path,
        openConnection: () -> Connection,
        onInTransaction: (Connection) -> Unit,
        onPostCommit: (Connection?) -> Unit = {},
    ) {
        val dbFile = directory.resolve("library.sqlite")
        var backupFile = directory.resolve(V1_BACKUP_FILENAME)
        var upgradingFromV1 = false
        var upgradingExisting = false
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
                upgradingExisting = hadBooks && currentVersion < CURRENT_SCHEMA_VERSION
                backupFile = when {
                    upgradingFromV1 -> directory.resolve(V1_BACKUP_FILENAME)
                    currentVersion < V3_SCHEMA_VERSION -> directory.resolve(V2_BACKUP_FILENAME)
                    currentVersion < V4_SCHEMA_VERSION -> directory.resolve(V3_BACKUP_FILENAME)
                    currentVersion < V5_SCHEMA_VERSION -> directory.resolve(V4_BACKUP_FILENAME)
                    currentVersion < V6_SCHEMA_VERSION -> directory.resolve(V5_BACKUP_FILENAME)
                    currentVersion < V7_SCHEMA_VERSION -> directory.resolve(V6_BACKUP_FILENAME)
                    currentVersion < V8_SCHEMA_VERSION -> directory.resolve("library.sqlite.v7.bak")
                    else -> directory.resolve("library.sqlite.v8.bak")
                }
                if (upgradingExisting) {
                    runCatching { probe.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
                }
                schemaComplete = currentVersion >= CURRENT_SCHEMA_VERSION &&
                    REQUIRED_V7_TABLES.all { it in existingTables } &&
                    "book_ask_drafts" in existingTables &&
                    tableColumns(probe, "book_ask_turns").containsAll(listOf("answer_status", "write_id")) &&
                    "show_standalone" in tableColumns(probe, "books") &&
                    "import_account_key" in tableColumns(probe, "books") &&
                    "week_num" in tableColumns(probe, "collection_materials") &&
                    "index_state" in tableColumns(probe, "books") &&
                    "text_pending" in tableColumns(probe, "pages") &&
                    "source_page" in tableColumns(probe, "book_reviews") &&
                    "due_day" in tableColumns(probe, "book_reviews") &&
                    "guessed" in tableColumns(probe, "answers")
                if (schemaComplete) {
                    onPostCommit(probe)
                    return
                }
            }
            if (upgradingExisting && !Files.exists(backupFile)) {
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
                    ensureColumn(db, "books", "show_standalone", "INTEGER NOT NULL DEFAULT 1")
                    ensureColumn(db, "books", "source_filename", "TEXT")
                    ensureColumn(db, "books", "file_bytes", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "books", "sync_epoch", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "books", "import_account_key", "TEXT")
                    ensureColumn(db, "pages", "ocr", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "pages", "ocr_pending", "INTEGER NOT NULL DEFAULT 0")
                    ensureColumn(db, "pages", "text_pending", "INTEGER NOT NULL DEFAULT 0")

                    db.createStatement().use { sql ->
                        for (ddl in V2_TABLES) sql.execute(ddl)
                    }
                    upgradeCollectionsToV3(db)
                    db.createStatement().use { sql ->
                        for (ddl in V3_TABLES) sql.execute(ddl)
                    }
                    db.createStatement().use { sql ->
                        for (ddl in V4_TABLES) sql.execute(ddl)
                    }
                    db.createStatement().use { sql ->
                        for (ddl in V5_TABLES) sql.execute(ddl)
                    }
                    db.createStatement().use { sql ->
                        for (ddl in V6_TABLES) sql.execute(ddl)
                        sql.execute("""CREATE TABLE IF NOT EXISTS book_ask_drafts(
                            book TEXT PRIMARY KEY, page INTEGER NOT NULL, question TEXT NOT NULL,
                            thread_id TEXT, block_id TEXT, block_start INTEGER, block_end INTEGER,
                            collection_id TEXT, updated_at INTEGER NOT NULL)""")
                    }
                    ensureColumn(db, "book_ask_turns", "answer_status",
                        "TEXT NOT NULL DEFAULT 'complete' CHECK(answer_status IN ('pending','failed','complete'))")
                    ensureColumn(db, "book_ask_turns", "write_id", "TEXT")
                    upgradeUploadQueueToV6(db)
                    db.createStatement().use { sql ->
                        for (ddl in V7_TABLES) sql.execute(ddl)
                        sql.executeUpdate(
                            """INSERT OR IGNORE INTO book_pending_deletes(
                                   account_key, server_shelf_book_id, local_book_id, file_sha256, format, title, created_at
                               ) SELECT account_key, server_shelf_book_id, local_book_id, file_sha256, format, title, updated_at
                               FROM book_upload_queue
                               WHERE state='PENDING_DELETE' AND server_shelf_book_id IS NOT NULL""".trimIndent(),
                        )
                    }
                    ensureColumn(db, "book_reviews", "source_page", "INTEGER")
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
                    db.createStatement().use { it.execute("PRAGMA user_version = $CURRENT_SCHEMA_VERSION") }
                    db.createStatement().use { it.execute("COMMIT") }
                } catch (failure: Throwable) {
                    runCatching { db.createStatement().use { it.execute("ROLLBACK") } }
                    throw failure
                }
                onPostCommit(db)
            }
        } catch (failure: Throwable) {
            if (upgradingExisting && Files.isRegularFile(backupFile) && !isHealthySqlite(dbFile)) {
                runCatching {
                    Files.copy(backupFile, dbFile, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            throw failure
        }
    }

    /**
     * v2 `collections` only allowed COURSE/BOOKLIST/CUSTOM. SQLite cannot widen a CHECK in place, so both
     * collection tables are rebuilt once: rename aside, recreate, copy every shared column, then drop the old copies.
     * Child rows are copied before either old table is dropped, so nothing is lost even with foreign keys on.
     */
    private fun upgradeCollectionsToV3(db: Connection) {
        val collectionsSql = db.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='collections'",
        ).use { sql -> sql.executeQuery().use { if (it.next()) it.getString(1).orEmpty() else "" } }
        if ("SINGLE_BOOK" !in collectionsSql) {
            db.createStatement().use { sql ->
                sql.execute("DROP TABLE IF EXISTS collection_materials_v2_old")
                sql.execute("DROP TABLE IF EXISTS collections_v2_old")
                sql.execute("ALTER TABLE collection_materials RENAME TO collection_materials_v2_old")
                sql.execute("ALTER TABLE collections RENAME TO collections_v2_old")
                sql.execute(COLLECTIONS_DDL)
                sql.execute(COLLECTION_MATERIALS_DDL)
            }
            copySharedColumns(db, "collections_v2_old", "collections")
            copySharedColumns(db, "collection_materials_v2_old", "collection_materials")
            db.createStatement().use { sql ->
                sql.execute("DROP TABLE collection_materials_v2_old")
                sql.execute("DROP TABLE collections_v2_old")
            }
        }
        ensureColumn(db, "collection_materials", "week_num", "INTEGER")
        ensureColumn(db, "collection_materials", "user_locked", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "collection_materials", "authority_rank", "INTEGER NOT NULL DEFAULT 2")
        ensureColumn(db, "collection_materials", "source_hash", "TEXT")
    }

    private fun upgradeUploadQueueToV6(db: Connection) {
        val queueSql = db.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='book_upload_queue'",
        ).use { sql -> sql.executeQuery().use { if (it.next()) it.getString(1).orEmpty() else "" } }
        if (queueSql.isNotEmpty() && "PENDING_DELETE" !in queueSql) {
            db.createStatement().use { sql ->
                sql.execute("DROP TABLE IF EXISTS book_upload_queue_v5_old")
                sql.execute("ALTER TABLE book_upload_queue RENAME TO book_upload_queue_v5_old")
                sql.execute(BOOK_UPLOAD_QUEUE_DDL)
            }
            copySharedColumns(db, "book_upload_queue_v5_old", "book_upload_queue")
            db.createStatement().use { sql ->
                sql.execute("DROP TABLE book_upload_queue_v5_old")
                sql.execute("CREATE INDEX IF NOT EXISTS idx_book_upload_queue_account ON book_upload_queue(account_key, paused, state)")
            }
        }
    }

    private fun copySharedColumns(db: Connection, from: String, to: String) {
        val target = tableColumns(db, to)
        val shared = tableColumns(db, from).filter { it in target }.joinToString(",")
        if (shared.isEmpty()) return
        db.createStatement().use { it.executeUpdate("INSERT INTO $to($shared) SELECT $shared FROM $from") }
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
            """INSERT INTO collections(id, kind, title, metadata_json, created_at, updated_at,
                 sort_order, exam_date, last_active_anchor_json, source_hash, user_locked, archived)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, title=excluded.title,
               metadata_json=excluded.metadata_json, updated_at=excluded.updated_at,
               sort_order=excluded.sort_order, exam_date=excluded.exam_date,
               last_active_anchor_json=excluded.last_active_anchor_json, source_hash=excluded.source_hash,
               user_locked=excluded.user_locked, archived=excluded.archived""".trimIndent(),
        ).use { sql ->
            sql.setString(1, record.id)
            sql.setString(2, record.kind.name)
            sql.setString(3, record.title)
            sql.setString(4, record.metadataJson)
            sql.setLong(5, record.createdAt)
            sql.setLong(6, record.updatedAt)
            sql.setInt(7, record.sortOrder)
            sql.setString(8, record.examDate)
            sql.setString(9, record.lastActiveAnchorJson)
            sql.setString(10, record.sourceHash)
            sql.setInt(11, if (record.userLocked) 1 else 0)
            sql.setInt(12, if (record.archived) 1 else 0)
            sql.executeUpdate()
        }
    }

    fun attachMaterial(db: Connection, link: CollectionMaterialRecord) {
        db.prepareStatement(
            """INSERT INTO collection_materials(collection_id, material_id, role, ordinal, added_at, metadata_json,
                 week_num, user_locked, authority_rank, source_hash)
               VALUES(?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT(collection_id, material_id) DO UPDATE SET role=excluded.role,
               ordinal=excluded.ordinal, metadata_json=excluded.metadata_json, week_num=excluded.week_num,
               user_locked=excluded.user_locked, authority_rank=excluded.authority_rank,
               source_hash=excluded.source_hash""".trimIndent(),
        ).use { sql ->
            sql.setString(1, link.collectionId)
            sql.setString(2, link.materialId)
            sql.setString(3, link.role)
            sql.setInt(4, link.ordinal)
            sql.setLong(5, link.addedAt)
            sql.setString(6, link.metadataJson)
            if (link.weekNum == null) sql.setNull(7, java.sql.Types.INTEGER) else sql.setInt(7, link.weekNum)
            sql.setInt(8, if (link.userLocked) 1 else 0)
            sql.setInt(9, link.authorityRank)
            sql.setString(10, link.sourceHash)
            sql.executeUpdate()
        }
    }

    fun collections(db: Connection): List<MaterialCollectionRecord> =
        db.createStatement().use { sql ->
            sql.executeQuery(
                """SELECT id, kind, title, metadata_json, created_at, updated_at, sort_order, exam_date,
                     last_active_anchor_json, source_hash, user_locked, archived
                   FROM collections ORDER BY updated_at DESC, id""".trimIndent(),
            ).use { rows ->
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
                                sortOrder = rows.getInt("sort_order"),
                                examDate = rows.getString("exam_date"),
                                lastActiveAnchorJson = rows.getString("last_active_anchor_json"),
                                sourceHash = rows.getString("source_hash"),
                                userLocked = rows.getInt("user_locked") != 0,
                                archived = rows.getInt("archived") != 0,
                            ),
                        )
                    }
                }
            }
        }

    fun materials(db: Connection, collectionId: String): List<CollectionMaterialRecord> =
        db.prepareStatement(
            """SELECT collection_id, material_id, role, ordinal, added_at, metadata_json,
                 week_num, user_locked, authority_rank, source_hash
               FROM collection_materials WHERE collection_id=? ORDER BY ordinal, added_at""".trimIndent(),
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
                                weekNum = rows.getInt("week_num").takeUnless { rows.wasNull() },
                                userLocked = rows.getInt("user_locked") != 0,
                                authorityRank = rows.getInt("authority_rank"),
                                sourceHash = rows.getString("source_hash"),
                            ),
                        )
                    }
                }
            }
        }
}
