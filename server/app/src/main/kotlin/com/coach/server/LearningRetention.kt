package com.coach.server

import java.sql.Connection

internal object LearningRetention {
    const val WINDOW_MS = 90L * 86_400_000
    fun install(db: Connection, now: Long) = db.createStatement().use { q ->
        q.execute("CREATE TABLE IF NOT EXISTS sync_cursors(device TEXT,stream TEXT,seen INTEGER NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(device,stream))")
        q.execute("CREATE TABLE IF NOT EXISTS sync_retention(singleton INTEGER PRIMARY KEY,floor INTEGER NOT NULL,checked INTEGER NOT NULL)")
        q.execute("INSERT OR IGNORE INTO sync_retention VALUES(1,0,0)")
        // Existing change logs did not retain commit time. Give them a full window from upgrade.
        if (!q.executeQuery("PRAGMA table_info(sync_changes)").use { r ->
                generateSequence { if (r.next()) r.getString("name") else null }.any { it == "committed_at" } }) {
            q.execute("ALTER TABLE sync_changes ADD COLUMN committed_at INTEGER NOT NULL DEFAULT 0")
            db.prepareStatement("UPDATE sync_changes SET committed_at=?").use { it.setLong(1,now); it.executeUpdate() }
        }
    }

    fun observe(db: Connection, device: String, stream: String, after: Long, now: Long): Long {
        db.prepareStatement("""INSERT INTO sync_cursors VALUES(?,?,?,?) ON CONFLICT(device,stream)
            DO UPDATE SET seen=excluded.seen,seq=excluded.seq""").use { q ->
            q.setString(1,device); q.setString(2,stream); q.setLong(3,now); q.setLong(4,after); q.executeUpdate()
        }
        val (floor,checked) = db.createStatement().use { it.executeQuery("SELECT floor,checked FROM sync_retention").use { r ->
            r.next(); r.getLong(1) to r.getLong(2) } }
        if (now-checked < 3_600_000L) return floor
        LearningSnapshots.expire(db,now)
        val cutoff = now-WINDOW_MS
        val eligible = db.prepareStatement("""SELECT coalesce(max(seq),0) FROM sync_changes
            WHERE seq<coalesce((SELECT min(seq) FROM sync_changes WHERE committed_at>?),9223372036854775807)
            AND seq<=(SELECT min(seq) FROM sync_cursors WHERE seen>=?)""").use { q ->
            q.setLong(1,cutoff); q.setLong(2,cutoff)
            q.executeQuery().use { it.next(); it.getLong(1) }
        }
        if (eligible > floor) {
            // The current record (including the last deleted round) survives log pruning.
            // Materialize a consistent recovery manifest in the same transaction before cutting history.
            LearningSnapshots.freeze(db,now)
            db.prepareStatement("DELETE FROM sync_changes WHERE seq<=?").use { it.setLong(1,eligible); it.executeUpdate() }
            db.createStatement().use { q ->
                for (table in listOf("sync_facts","sync_fact_bases","sync_field_writers")) {
                    q.executeUpdate("""DELETE FROM $table WHERE EXISTS(SELECT 1 FROM sync_records r
                        WHERE r.kind=$table.kind AND r.id=$table.id AND (r.round>$table.round OR
                        (r.round=$table.round AND r.body IS NULL)))""")
                }
            }
        }
        return maxOf(floor,eligible).also { next ->
            db.prepareStatement("UPDATE sync_retention SET floor=?,checked=? WHERE singleton=1").use { q ->
                q.setLong(1,next); q.setLong(2,now); q.executeUpdate()
            }
        }
    }
}
