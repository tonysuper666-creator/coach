package com.coach.store

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.sql.DriverManager

/** Application-owned LedgerStore.backup and rawBackup copies. Older backups may predate friend pairing. */
internal fun eraseBackupCredentials(root: Path, friend: String) {
    require(isSableFriend(friend))
    if (!Files.exists(root,NOFOLLOW_LINKS)) return
    check(Files.isDirectory(root,NOFOLLOW_LINKS))
    Files.list(root).use { entries ->
        entries.forEach { directory ->
            check(!Files.isSymbolicLink(directory))
            if (!Files.isDirectory(directory,NOFOLLOW_LINKS)) return@forEach
            val file = directory.resolve("coach.sqlite")
            if (!Files.exists(file,NOFOLLOW_LINKS)) return@forEach
            check(Files.isRegularFile(file,NOFOLLOW_LINKS))
            DriverManager.getConnection("jdbc:sqlite:${file.toUri()}?mode=rw").use { db ->
                db.createStatement().use { q ->
                    q.execute("PRAGMA secure_delete=ON")
                    q.execute("PRAGMA foreign_keys=ON")
                    fun hasColumn(table: String, column: String) = q.executeQuery("PRAGMA table_info($table)").use { r ->
                        generateSequence { if (r.next()) r.getString("name") else null }.any { it == column }
                    }
                    db.autoCommit = false
                    var changed = 0
                    fun erase(statement: String) = db.prepareStatement(statement).use { delete ->
                        delete.setString(1,friend)
                        changed += delete.executeUpdate()
                    }
                    if (hasColumn("devices","friend_name") && hasColumn("sable_grants","device_id")) {
                        erase("DELETE FROM sable_grants WHERE device_id IN (SELECT device_id FROM devices WHERE friend_name=?)")
                    }
                    for (table in listOf("pairing_code","devices","sable_friends")) {
                        if (hasColumn(table,"friend_name")) erase("DELETE FROM $table WHERE friend_name=?")
                    }
                    db.commit()
                    db.autoCommit = true
                    if (changed > 0) {
                        q.execute("VACUUM")
                        check(q.executeQuery("PRAGMA integrity_check").use { r -> r.next() && r.getString(1) == "ok" && !r.next() })
                    }
                }
            }
        }
    }
}
