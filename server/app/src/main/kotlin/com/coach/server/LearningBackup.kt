package com.coach.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.DigestInputStream
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat

/** Called inside the account lock. The book registry is independent of the account files being restored. */
internal class LearningBackup(private val registry: Path) {
    private data class Position(val epoch: String, val seq: Long, val receipts: Long)
    private data class Fence(val current: Position, val digest: String?)

    private fun position(db: Connection): Position = db.createStatement().use { q ->
        q.executeQuery("""SELECT epoch,(SELECT coalesce(max(seq),0) FROM sync_records),
            (SELECT coalesce(max(rowid),0) FROM sync_receipts) FROM sync_meta""").use { r ->
            if (!r.next()) throw LearningRecoveryRequired()
            Position(r.getString(1),r.getLong(2),r.getLong(3))
        }
    }

    private fun fence(db: Connection, scope: String): Fence? = db.prepareStatement(
        "SELECT epoch,seq,receipts,digest FROM learning_checkpoints WHERE scope_key=?"
    ).use { q ->
        q.setString(1,scope)
        q.executeQuery().use { r -> if (r.next()) Fence(Position(r.getString(1),r.getLong(2),r.getLong(3)),r.getString(4)) else null }
    }

    fun attach(db: Connection) {
        db.prepareStatement("ATTACH DATABASE ? AS registry").use { it.setString(1,registry.toString()); it.execute() }
    }

    fun verify(db: Connection, scope: String) {
        val generation = db.prepareStatement("SELECT deletion_epoch FROM registry.account_deletions WHERE scope_key=?").use { q ->
            q.setString(1,scope)
            q.executeQuery().use { r -> if (r.next()) r.getLong(1) else 0L }
        }
        db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS sync_identity(scope_key TEXT PRIMARY KEY,generation INTEGER NOT NULL)") }
        val identity = db.createStatement().use { it.executeQuery("SELECT scope_key,generation FROM sync_identity").use { r ->
            if (r.next()) r.getString(1) to r.getLong(2) else null
        } }
        if (identity == null) {
            if (generation > 0 && position(db).receipts != 0L) throw LearningRecoveryRequired()
            db.prepareStatement("INSERT INTO sync_identity VALUES(?,?)").use { q -> q.setString(1,scope); q.setLong(2,generation); q.executeUpdate() }
        } else if (identity != (scope to generation)) throw LearningRecoveryRequired()
        val expected = fence(db,scope) ?: return
        if (position(db) != expected.current) throw LearningRecoveryRequired()
    }

    fun advance(db: Connection, scope: String) {
        val state = position(db)
        db.prepareStatement("""INSERT INTO registry.learning_checkpoints(scope_key,epoch,seq,receipts) VALUES(?,?,?,?)
            ON CONFLICT(scope_key) DO UPDATE SET epoch=excluded.epoch,seq=excluded.seq,receipts=excluded.receipts,digest=NULL
            WHERE epoch<>excluded.epoch OR seq<>excluded.seq OR receipts<>excluded.receipts""").use { q ->
            q.setString(1,scope); q.setString(2,state.epoch); q.setLong(3,state.seq); q.setLong(4,state.receipts); q.executeUpdate()
        }
    }

    private fun digest(file: Path): String {
        val sha = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file),sha).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        return HexFormat.of().formatHex(sha.digest())
    }

    private fun inspect(file: Path, expected: Position) {
        DriverManager.getConnection("jdbc:sqlite:${file.toUri()}?mode=ro").use { db ->
            val intact = db.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { r -> r.next() && r.getString(1) == "ok" && !r.next() } }
            if (!intact || position(db) != expected) throw LearningRecoveryRequired()
        }
    }

    private fun latest(live: Path) = live.resolveSibling("learning-backups").resolve("latest.sqlite")

    fun publish(live: Path, scope: String) {
        DriverManager.getConnection("jdbc:sqlite:$registry").use { index ->
            val expected = fence(index,scope) ?: throw LearningRecoveryRequired()
            val latest = latest(live)
            if (expected.digest != null && Files.isRegularFile(latest)) return
            val staged = privateFiles.tempFile(privateFiles.directory(latest.parent),"pending-",".sqlite")
            try {
                DriverManager.getConnection("jdbc:sqlite:$live").use { source ->
                    source.prepareStatement("VACUUM INTO ?").use { it.setString(1,staged.toString()); it.execute() }
                }
                inspect(staged,expected.current)
                val hash = digest(staged)
                Files.move(staged,latest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                index.prepareStatement("UPDATE learning_checkpoints SET digest=? WHERE scope_key=? AND epoch=? AND seq=? AND receipts=?").use { q ->
                    q.setString(1,hash); q.setString(2,scope); q.setString(3,expected.current.epoch)
                    q.setLong(4,expected.current.seq); q.setLong(5,expected.current.receipts)
                    if (q.executeUpdate() != 1) throw LearningRecoveryRequired()
                }
            } finally { Files.deleteIfExists(staged) }
        }
    }

    fun recoverMissing(live: Path, scope: String) {
        if (Files.exists(live)) return
        val expected = DriverManager.getConnection("jdbc:sqlite:$registry").use { fence(it,scope) } ?: return
        val backup = latest(live)
        if (expected.digest == null || !Files.isRegularFile(backup) || digest(backup) != expected.digest) throw LearningRecoveryRequired()
        val staged = privateFiles.tempFile(live.parent,"restore-",".sqlite")
        try {
            Files.newInputStream(backup).use { input -> Files.newOutputStream(staged).use { output -> input.copyTo(output) } }
            inspect(staged,expected.current)
            if (digest(staged) != expected.digest) throw LearningRecoveryRequired()
            DriverManager.getConnection("jdbc:sqlite:$staged").use { recovered ->
                recovered.autoCommit = false
                recovered.createStatement().use { q ->
                    q.executeUpdate("DELETE FROM sync_snapshot_rows")
                    q.executeUpdate("DELETE FROM sync_snapshots")
                    q.executeUpdate("DELETE FROM sync_cursors")
                }
                recovered.prepareStatement("UPDATE sync_transport SET epoch=?").use { q ->
                    q.setString(1,java.util.UUID.randomUUID().toString()); check(q.executeUpdate() == 1)
                }
                recovered.commit()
            }
            Files.move(staged,live,StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(staged) }
    }
}

internal class LearningRecoveryRequired : Exception()
