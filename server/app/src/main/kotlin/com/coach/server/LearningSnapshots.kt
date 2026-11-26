package com.coach.server

import com.coach.protocol.learning.snapshotDigest
import kotlinx.serialization.json.*
import java.sql.Connection
import java.util.UUID

internal object LearningSnapshots {
    private const val DAY = 86_400_000L

    fun install(db: Connection) = db.createStatement().use { sql ->
        sql.execute("""CREATE TABLE IF NOT EXISTS sync_snapshots(token TEXT PRIMARY KEY,water INTEGER NOT NULL,
            count INTEGER NOT NULL,digest TEXT NOT NULL,expires INTEGER NOT NULL)""")
        sql.execute("""CREATE TABLE IF NOT EXISTS sync_snapshot_rows(token TEXT,seq INTEGER,item TEXT NOT NULL,
            PRIMARY KEY(token,seq))""")
    }

    fun expire(db: Connection, now: Long) {
        db.prepareStatement("DELETE FROM sync_snapshots WHERE expires<=?").use { it.setLong(1,now); it.executeUpdate() }
        db.createStatement().use { it.executeUpdate("DELETE FROM sync_snapshot_rows WHERE token NOT IN (SELECT token FROM sync_snapshots)") }
    }

    fun page(db: Connection, scope: String, epoch: String, token: String?, after: Long, now: Long): JsonObject {
        require(after >= 0 && (token != null || after == 0L))
        expire(db,now)
        val key = token ?: freeze(db,now)
        return db.prepareStatement("SELECT water,count,digest FROM sync_snapshots WHERE token=?").use { q ->
            q.setString(1,key)
            q.executeQuery().use { r ->
                if (!r.next()) throw LearningSnapshotExpired()
                val water = r.getLong(1)
                val count = r.getLong(2)
                val digest = r.getString(3)
                var last = after
                var done = true
                val items = buildJsonArray {
                    db.prepareStatement("SELECT seq,item FROM sync_snapshot_rows WHERE token=? AND seq>? ORDER BY seq LIMIT 65").use { read ->
                        read.setString(1,key); read.setLong(2,after)
                        read.executeQuery().use { page ->
                            var bytes = 0
                            var emitted = 0
                            while (page.next()) {
                                val item = page.getString(2)
                                bytes += item.encodeToByteArray().size
                                if (emitted == 64 || (emitted > 0 && bytes > 1_000_000)) { done = false; break }
                                add(Json.parseToJsonElement(item))
                                emitted++
                                last = page.getLong(1)
                            }
                        }
                    }
                }
                buildJsonObject {
                    put("scope",scope); put("epoch",epoch); put("token",key); put("water",water); put("count",count)
                    put("digest",digest); put("items",items); put("next",last); put("done",done)
                }
            }
        }
    }

    fun freeze(db: Connection, now: Long): String {
        val water = db.createStatement().use { it.executeQuery("SELECT COALESCE(MAX(seq),0) FROM sync_records").use { r -> r.next(); r.getLong(1) } }
        db.prepareStatement("SELECT token FROM sync_snapshots WHERE water=? LIMIT 1").use { q ->
            q.setLong(1,water)
            q.executeQuery().use { if (it.next()) return it.getString(1) }
        }
        val token = UUID.randomUUID().toString()
        var count = 0L
        var digest = ""
        db.prepareStatement("INSERT INTO sync_snapshot_rows VALUES(?,?,?)").use { insert ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT seq,kind,id,body,round,writer FROM sync_records ORDER BY seq").use { r ->
                    while (r.next()) {
                        val item = buildJsonObject {
                            put("seq",r.getLong(1)); put("kind",r.getString(2)); put("id",r.getString(3))
                            put("body",r.getString(4)?.let(Json::parseToJsonElement) ?: JsonNull)
                            put("round",r.getLong(5)); put("clock",r.getString(6).substringBefore('/').toLong())
                        }.toString()
                        insert.setString(1,token); insert.setLong(2,r.getLong(1)); insert.setString(3,item); insert.executeUpdate()
                        count++
                        digest = snapshotDigest(digest,item)
                    }
                }
            }
        }
        db.prepareStatement("INSERT INTO sync_snapshots VALUES(?,?,?,?,?)").use { q ->
            q.setString(1,token); q.setLong(2,water); q.setLong(3,count); q.setString(4,digest); q.setLong(5,now+DAY); q.executeUpdate()
        }
        return token
    }
}

internal class LearningSnapshotExpired : Exception()
