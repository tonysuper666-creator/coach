package com.coach.desktop.books

import com.coach.protocol.learning.LearningTable
import com.coach.protocol.learning.draftVersion
import com.coach.protocol.learning.learningTables
import com.coach.desktop.books.exam.LocalExamStore

import kotlinx.serialization.json.*
import java.sql.Connection
import java.util.UUID

internal object LearningJournal {
    val enabled get() = System.getenv("COACH_LEARNING_SYNC")?.let { it == "1" }
        ?: java.lang.Boolean.getBoolean("coach.learningSync")

    fun installProfile(library: TextbookLibrary) {
        installLibrary(library)
        val exams = LocalExamStore(library)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${exams.databasePath}").use { db ->
            db.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            install(db, learningTables.filter { it.exam })
        }
    }

    fun installLibrary(library: TextbookLibrary) = library.withCourseDatabase { db ->
        db.createStatement().use { it.execute(StudyModeStore.PREFERENCES_DDL.trimIndent()) }
        install(db, learningTables.filterNot { it.exam } + draftVersion)
    }

    /** Called after source schema migration. SQLite's writer lock covers both seeding and installing triggers. */
    fun install(db: Connection, tables: List<LearningTable>) {
        check(db.autoCommit)
        db.createStatement().use { sql ->
            sql.execute("BEGIN IMMEDIATE")
            try {
                if (BookSchemaMigration.tableExists(db,"local_learning_retired")) {
                    sql.execute("COMMIT")
                    return
                }
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_sources(
                    kind TEXT PRIMARY KEY, stream_id TEXT NOT NULL)""")
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_events(
                    kind TEXT NOT NULL, local_id INTEGER NOT NULL, record_id TEXT NOT NULL,
                    PRIMARY KEY(kind,local_id), UNIQUE(kind,record_id))""")
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_outbox(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, event_id TEXT,
                    before_json TEXT, after_json TEXT, baseline INTEGER NOT NULL DEFAULT 0,
                    clock INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL DEFAULT (unixepoch('subsec')*1000))""")
                sql.execute("CREATE TABLE IF NOT EXISTS learning_sync_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                BookSchemaMigration.ensureColumn(db,"learning_sync_outbox","fact","TEXT")
                BookSchemaMigration.ensureColumn(db,"learning_sync_outbox","acknowledged","INTEGER NOT NULL DEFAULT 0")
                sql.execute("CREATE TABLE IF NOT EXISTS learning_sync_fact_context(kind TEXT PRIMARY KEY, fact TEXT NOT NULL)")
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_stage(seq INTEGER PRIMARY KEY,item TEXT NOT NULL)""")
                sql.execute("""CREATE TRIGGER IF NOT EXISTS learning_sync_capture_fact AFTER INSERT ON learning_sync_outbox
                    WHEN EXISTS(SELECT 1 FROM learning_sync_fact_context WHERE kind=NEW.kind) BEGIN
                    UPDATE learning_sync_outbox SET fact=(SELECT fact FROM learning_sync_fact_context WHERE kind=NEW.kind)
                    WHERE seq=NEW.seq; END""")
                sql.execute("""CREATE TRIGGER IF NOT EXISTS learning_sync_tick AFTER INSERT ON learning_sync_outbox BEGIN
                    INSERT INTO learning_sync_meta VALUES('clock','1') ON CONFLICT(key) DO UPDATE SET value=CAST(value AS INTEGER)+1;
                    UPDATE learning_sync_outbox SET clock=(SELECT value FROM learning_sync_meta WHERE key='clock') WHERE seq=NEW.seq;
                    END""")
                sql.execute("CREATE TABLE IF NOT EXISTS learning_sync_prior(kind TEXT PRIMARY KEY,body TEXT NOT NULL)")
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_inbox(kind TEXT NOT NULL,id TEXT NOT NULL,
                    item TEXT NOT NULL,PRIMARY KEY(kind,id))""")
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_pending_books(book TEXT PRIMARY KEY,
                    record_id TEXT NOT NULL UNIQUE,body TEXT NOT NULL)""")
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_versions(kind TEXT NOT NULL,id TEXT NOT NULL,
                    round INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(kind,id))""")
                sql.execute("""CREATE TABLE IF NOT EXISTS learning_sync_keys(kind TEXT NOT NULL,id TEXT NOT NULL,
                    keys_json TEXT NOT NULL,PRIMARY KEY(kind,id))""")
                var migrated = false
                for (table in tables) {
                    if (table == draftVersion) {
                        sql.execute("""CREATE TABLE IF NOT EXISTS learning_draft_versions(
                            parent TEXT NOT NULL,generation INTEGER NOT NULL,writer TEXT NOT NULL,slot TEXT NOT NULL,
                            source TEXT NOT NULL,book TEXT,page INTEGER,location TEXT NOT NULL,text TEXT NOT NULL,
                            PRIMARY KEY(parent,generation,writer,slot))""")
                        sql.execute("CREATE INDEX IF NOT EXISTS draft_versions_book ON learning_draft_versions(book)")
                        sql.execute("""CREATE TRIGGER IF NOT EXISTS learning_sync_remove_book_drafts AFTER DELETE ON books
                            BEGIN DELETE FROM learning_draft_versions WHERE book=OLD.id; END""")
                        sql.execute("""CREATE TRIGGER IF NOT EXISTS learning_sync_remove_topic_drafts AFTER DELETE ON course_topics
                            BEGIN DELETE FROM learning_draft_versions WHERE source='course_topic' AND location=OLD.topic_id; END""")
                    }
                    val installed = db.prepareStatement("SELECT 1 FROM learning_sync_sources WHERE kind=?").use {
                        it.setString(1, table.kind)
                        it.executeQuery().use { rows -> rows.next() }
                    }
                    if (!installed) {
                        installSource(db, table)
                        if (table == draftVersion) {
                            // An older client may have advanced its cursor past an unknown recovery kind.
                            sql.execute("""INSERT OR REPLACE INTO learning_sync_meta SELECT 'restore_required','1'
                                WHERE EXISTS(SELECT 1 FROM learning_sync_meta WHERE key IN ('cursor','stage_token'))""")
                            sql.execute("DELETE FROM learning_sync_stage")
                            sql.execute("DELETE FROM learning_sync_meta WHERE key IN ('stage_token','stage_after','stage_count','stage_hash')")
                        }
                        migrated = true
                    }
                    if (table.kind == "mastery") {
                        fun payload(prefix: String) = "json_object(" + table.columns.joinToString(",") { "'$it',$prefix.$it" } + ")"
                        val before = payload("OLD")
                        val after = payload("NEW")
                        sql.execute("""CREATE TRIGGER IF NOT EXISTS learning_sync_equal_mastery AFTER UPDATE ON ${table.name}
                            WHEN EXISTS(SELECT 1 FROM learning_sync_fact_context WHERE kind='mastery') AND $before=$after BEGIN
                            INSERT INTO learning_sync_outbox(kind,before_json,after_json) VALUES('mastery',$before,$after); END""")
                    }
                }
                if (migrated) {
                    check(sql.executeQuery("PRAGMA foreign_key_check").use { !it.next() }) { "Learning migration has a missing reference" }
                    check(sql.executeQuery("PRAGMA integrity_check").use { it.next() && it.getString(1) == "ok" && !it.next() })
                }
                sql.execute("COMMIT")
            } catch (failure: Throwable) {
                sql.execute("ROLLBACK")
                throw failure
            }
        }
    }

    private fun installSource(db: Connection, table: LearningTable) {
        val present = db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info(${table.name})").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
        }
        // Preferences and bookmarks can be created lazily by their feature stores.
        check(present.containsAll(table.columns)) { "Incomplete learning schema for ${table.name}" }
        val columns = table.columns.filter { it in present }
        val kind = "'${table.kind}'"
        fun payload(prefix: String, fields: List<String> = columns): String =
            "json_object(" + fields.joinToString(",") { "'$it',$prefix.$it" } + ")"
        fun visible(prefix: String) = if (table.kind == "exam_attempt") "$prefix.owner='learner'" else "1"
        val maps = "learning_sync_events"
        fun event(prefix: String) = if (table.event) {
            "(SELECT record_id FROM $maps WHERE kind=$kind AND local_id=$prefix.id)"
        } else "NULL"
        val out = "INSERT INTO learning_sync_outbox(kind,event_id,before_json,after_json)"
        val localWrite = "NOT EXISTS(SELECT 1 FROM learning_sync_meta WHERE key='applying')"
        val source = table.name
        db.createStatement().use { sql ->
            if (table.event) {
                val fields = if ("write_id" in present) columns + "write_id" else columns
                db.prepareStatement("INSERT INTO $maps(kind,local_id,record_id) VALUES(?,?,?)").use { insert ->
                    sql.executeQuery("SELECT id,${payload(source, fields)} FROM $source ORDER BY id").use { rows ->
                        while (rows.next()) {
                            insert.setString(1, table.kind)
                            insert.setLong(2, rows.getLong(1))
                            insert.setString(3, table.legacyId(Json.parseToJsonElement(rows.getString(2)).jsonObject))
                            insert.executeUpdate()
                        }
                    }
                }
            }
            sql.executeUpdate("""INSERT INTO learning_sync_outbox(kind,event_id,after_json,baseline)
                SELECT $kind,${event(source)},${payload(source)},1 FROM $source WHERE ${visible(source)}""")
            val expected = "SELECT ${payload(source)} AS body,COUNT(*) AS n FROM $source WHERE ${visible(source)} GROUP BY body"
            val actual = "SELECT after_json AS body,COUNT(*) AS n FROM learning_sync_outbox WHERE kind=$kind AND baseline=1 GROUP BY body"
            check(sql.executeQuery("$expected EXCEPT $actual").use { !it.next() } &&
                sql.executeQuery("$actual EXCEPT $expected").use { !it.next() }) { "Learning migration mismatch for $source" }
            // SQL-only triggers also work on connections owned by the reader, test drivers and exam writer.
            val randomId = """lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||substr(hex(randomblob(2)),2)
                ||'-'||substr('89ab',1+(random() & 3),1)||substr(hex(randomblob(2)),2)||'-'||hex(randomblob(6)))"""
            val allocate = if (table.event) {
                "INSERT OR IGNORE INTO $maps(kind,local_id,record_id) VALUES($kind,NEW.id,$randomId);"
            } else ""
            val primaryKey = sql.executeQuery("PRAGMA table_info($source)").use { r ->
                buildList { while (r.next()) if (r.getInt("pk") > 0) add(r.getString("name")) }
            }
            val sameRow = primaryKey.joinToString(" AND ") { "$source.$it=NEW.$it" }
            // INSERT OR REPLACE is used by several domain stores. Capture the preimage before SQLite removes it.
            sql.execute("""CREATE TRIGGER learning_sync_${source}_before_insert BEFORE INSERT ON $source
                WHEN $localWrite AND ${visible("NEW")} BEGIN
                DELETE FROM learning_sync_prior WHERE kind=$kind;
                INSERT INTO learning_sync_prior SELECT $kind,${payload(source)} FROM $source
                WHERE $sameRow AND ${visible(source)}; END""")
            val prior = "(SELECT body FROM learning_sync_prior WHERE kind=$kind)"
            val normalInsert = if (table.kind == "book_progress") {
                val fields = columns.filterNot { it == "id" }.joinToString(",") {
                    "$it=json_extract((SELECT body FROM learning_sync_pending_books WHERE book=NEW.id),'$.$it')"
                }
                // Do both branches in one trigger: SQLite does not guarantee the order of AFTER triggers.
                """$out SELECT $kind,NULL,$prior,${payload("NEW")}
                    WHERE NOT EXISTS(SELECT 1 FROM learning_sync_pending_books WHERE book=NEW.id);
                    INSERT INTO learning_sync_meta VALUES('applying','1');
                    UPDATE books SET $fields WHERE id=NEW.id AND EXISTS(SELECT 1 FROM learning_sync_pending_books WHERE book=NEW.id);
                    DELETE FROM learning_sync_meta WHERE key='applying';
                    DELETE FROM learning_sync_pending_books WHERE book=NEW.id;"""
            } else "$out VALUES($kind,${event("NEW")},$prior,${payload("NEW")});"
            sql.execute("""CREATE TRIGGER learning_sync_${source}_insert AFTER INSERT ON $source
                WHEN $localWrite AND ${visible("NEW")} BEGIN $allocate
                $normalInsert DELETE FROM learning_sync_prior WHERE kind=$kind; END""")
            sql.execute("""CREATE TRIGGER learning_sync_${source}_update AFTER UPDATE ON $source
                WHEN $localWrite AND (${visible("OLD")} OR ${visible("NEW")}) AND
                (${payload("OLD")} != ${payload("NEW")}) BEGIN
                $out VALUES($kind,${event("OLD")},
                    CASE WHEN ${visible("OLD")} THEN ${payload("OLD")} END,
                    CASE WHEN ${visible("NEW")} THEN ${payload("NEW")} END); END""")
            val forget = if (table.event) "DELETE FROM $maps WHERE kind=$kind AND local_id=OLD.id;" else ""
            sql.execute("""CREATE TRIGGER learning_sync_${source}_delete AFTER DELETE ON $source
                WHEN $localWrite AND ${visible("OLD")} BEGIN
                $out VALUES($kind,${event("OLD")},${payload("OLD")},NULL); $forget END""")
        }
        db.prepareStatement("INSERT INTO learning_sync_sources(kind,stream_id) VALUES(?,?)").use {
            it.setString(1, table.kind)
            // A source database has one stream, even when another table is installed later.
            val stream = db.createStatement().use { sql ->
                sql.executeQuery("SELECT stream_id FROM learning_sync_sources LIMIT 1").use { r ->
                    if (r.next()) r.getString(1) else UUID.randomUUID().toString()
                }
            }
            it.setString(2, stream)
            it.executeUpdate()
        }
    }
}
