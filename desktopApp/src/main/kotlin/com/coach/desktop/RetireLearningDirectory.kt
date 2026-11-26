package com.coach.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** Same SQLite transaction as retiring the profile binding. Called only on a verified change of account generation. */
internal fun retireLearningDirectory(db: Connection, directory: Path, scope: String, generation: String) {
    for ((schema,file) in listOf("retired_library" to "library.sqlite","retired_exams" to "exams.sqlite")) {
        val path = directory.resolve(file)
        if (!Files.isRegularFile(path)) continue
        db.prepareStatement("ATTACH DATABASE ? AS $schema").use { q -> q.setString(1,path.toString()); q.execute() }
        db.createStatement().use { q ->
            val tables = q.executeQuery("SELECT name FROM $schema.sqlite_master WHERE type='table' AND name GLOB 'learning_sync_*'").use { r ->
                buildList { while (r.next()) add(r.getString(1)) }
            }
            if ("learning_sync_meta" !in tables) return@use
            val binding = q.executeQuery("SELECT key,value FROM $schema.learning_sync_meta WHERE key IN ('owner','epoch')").use { r ->
                buildMap { while (r.next()) put(r.getString(1),r.getString(2)) }
            }
            check(binding["owner"] in setOf(null,scope) && binding["epoch"] in setOf(null,generation))
            // Saved business rows are already current. Preserve earlier, unacknowledged authored drafts too.
            q.execute("""CREATE TABLE IF NOT EXISTS $schema.local_saved_writing(kind TEXT NOT NULL,body TEXT NOT NULL,
                PRIMARY KEY(kind,body))""")
            val hasAck = q.executeQuery("PRAGMA $schema.table_info(learning_sync_outbox)").use { r ->
                generateSequence { if (r.next()) r.getString("name") else null }.any { it == "acknowledged" }
            }
            val pending = if (hasAck) " AND acknowledged=0" else ""
            q.executeUpdate("""INSERT OR IGNORE INTO $schema.local_saved_writing SELECT kind,after_json
                FROM $schema.learning_sync_outbox WHERE baseline=0 $pending AND after_json IS NOT NULL AND
                kind IN ('note','ask_draft','answer','bookmark','exam_attempt')""")
            val triggers = q.executeQuery("SELECT name FROM $schema.sqlite_master WHERE type='trigger' AND name GLOB 'learning_sync_*'").use { r ->
                buildList { while (r.next()) add(r.getString(1)) }
            }
            for ((type,names) in listOf("TRIGGER" to triggers,"TABLE" to tables)) {
                for (name in names) {
                    check(name.matches(Regex("learning_sync_[a-z0-9_]+")))
                    q.execute("DROP $type $schema.$name")
                }
            }
            q.execute("CREATE TABLE IF NOT EXISTS $schema.local_learning_retired(singleton INTEGER PRIMARY KEY)")
            q.executeUpdate("INSERT OR IGNORE INTO $schema.local_learning_retired VALUES(1)")
        }
    }
}
