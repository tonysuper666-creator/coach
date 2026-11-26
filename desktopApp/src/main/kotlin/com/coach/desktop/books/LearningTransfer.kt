package com.coach.desktop.books

import com.coach.protocol.learning.LearningTable
import com.coach.protocol.learning.learningTables

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID

internal interface LearningRemote {
    suspend fun changes(epoch: String?, after: Long): JsonObject?
    suspend fun push(change: JsonObject): JsonObject?
}

/** Upload queued Windows learning records and commit incoming pages with the source database's cursor. */
internal class LearningTransfer(private val path: Path, private val exam: Boolean) {
    private val sources = learningTables.filter { it.exam == exam }.associateBy { it.kind }

    private fun <T> write(body: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA busy_timeout=5000")
                sql.execute("PRAGMA foreign_keys=ON")
                sql.execute("BEGIN IMMEDIATE")
                try {
                    val result = body(db)
                    sql.execute("COMMIT")
                    result
                } catch (failure: Throwable) {
                    sql.execute("ROLLBACK")
                    throw failure
                }
            }
        }

    private fun meta(db: Connection, key: String) = db.prepareStatement("SELECT value FROM learning_sync_meta WHERE key=?").use { q ->
        q.setString(1,key)
        q.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
    }

    private fun save(db: Connection, key: String, value: String) {
        db.prepareStatement("INSERT OR REPLACE INTO learning_sync_meta VALUES(?,?)").use { q ->
            q.setString(1,key); q.setString(2,value); q.executeUpdate()
        }
    }

    private fun version(db: Connection, kind: String, id: String) =
        db.prepareStatement("SELECT round,deleted FROM learning_sync_versions WHERE kind=? AND id=?").use { q ->
            q.setString(1,kind); q.setString(2,id)
            q.executeQuery().use { r -> if (r.next()) r.getLong(1) to (r.getInt(2) == 1) else 1L to false }
        }

    private fun setVersion(db: Connection, kind: String, id: String, round: Long, deleted: Boolean) {
        db.prepareStatement("INSERT OR REPLACE INTO learning_sync_versions VALUES(?,?,?,?)").use { q ->
            q.setString(1,kind); q.setString(2,id); q.setLong(3,round); q.setInt(4,if (deleted) 1 else 0); q.executeUpdate()
        }
    }

    private fun next(db: Connection, epoch: String): JsonObject? = db.createStatement().use { sql ->
        sql.executeQuery("""SELECT o.*,s.stream_id FROM learning_sync_outbox o JOIN learning_sync_sources s USING(kind)
            ORDER BY seq LIMIT 1""").use { r ->
            if (!r.next()) return null
            val table = sources.getValue(r.getString("kind"))
            val old = r.getString("before_json")?.let { Json.parseToJsonElement(it).jsonObject }
            val new = r.getString("after_json")?.let { Json.parseToJsonElement(it).jsonObject }
            val id = r.getString("event_id") ?: table.legacyId(new ?: checkNotNull(old))
            val (previousRound, deleted) = version(db,table.kind,id)
            fun wire(row: JsonObject?): JsonElement {
                if (row == null || (table.kind == "note" && row["deleted"]?.jsonPrimitive?.intOrNull == 1)) return JsonNull
                return if (table.event) JsonObject(row - "id") else row
            }
            val after = wire(new)
            buildJsonObject {
                put("kind",table.kind); put("id",id); put("stream",r.getString("stream_id"))
                put("epoch",epoch); put("seq",r.getLong("seq"))
                put("clock",r.getLong("clock")); put("time",r.getLong("created_at")); put("protocol",1)
                put("before",wire(old)); put("after",after)
                put("round",if (deleted && wire(old) == JsonNull && after != JsonNull) previousRound + 1 else previousRound)
            }
        }
    }

    suspend fun sync(scope: String, remote: LearningRemote, profileEpoch: String? = null, current: () -> Boolean) {
        require(scope.matches(Regex("sable-[0-9a-f]{64}")))
        if (!current()) return
        val state = write { db ->
            val owner = meta(db,"owner")
            if (owner != null && owner != scope) return@write null
            save(db,"owner",scope)
            meta(db,"epoch") to (meta(db,"cursor")?.toLong() ?: 0)
        } ?: return
        if (profileEpoch != null && state.first != null && profileEpoch != state.first) return
        val first = remote.changes(state.first ?: profileEpoch,state.second) ?: return
        if (!current() || first["scope"]?.jsonPrimitive?.content != scope) return
        val epoch = first.getValue("epoch").jsonPrimitive.content
        if ((state.first != null && state.first != epoch) || (profileEpoch != null && profileEpoch != epoch)) return
        val bound = write { db ->
            val existing = meta(db,"epoch")
            if (existing != null && existing != epoch) return@write false
            save(db,"epoch",epoch)
            true
        }
        if (!bound) return
        while (current()) {
            val operation = write { db -> next(db,epoch) } ?: break
            val ack = remote.push(operation) ?: return
            if (!current() || ack["scope"]?.jsonPrimitive?.content != scope || ack["epoch"]?.jsonPrimitive?.content != epoch ||
                ack["seq"] != operation["seq"]) return
            write { db ->
                db.prepareStatement("DELETE FROM learning_sync_outbox WHERE seq=?").use { q ->
                    q.setLong(1,operation.getValue("seq").jsonPrimitive.long)
                    if (q.executeUpdate() == 1) {
                        setVersion(db,operation.getValue("kind").jsonPrimitive.content,operation.getValue("id").jsonPrimitive.content,
                            operation.getValue("round").jsonPrimitive.long,operation["after"] == JsonNull)
                    }
                }
                save(db,"epoch",epoch)
            }
        }
        var cursor = state.second
        var page = first
        while (current()) {
            if (page["scope"]?.jsonPrimitive?.content != scope || page["epoch"]?.jsonPrimitive?.content != epoch) return
            val changes = page.getValue("changes").jsonArray
            val applied = write { db ->
                if (!current() || (meta(db,"cursor")?.toLong() ?: 0L) != cursor) return@write false
                db.createStatement().use { sql ->
                    sql.executeQuery("SELECT 1 FROM learning_sync_outbox LIMIT 1").use { if (it.next()) return@write false }
                }
                save(db,"applying","1")
                var clock = meta(db,"clock")?.toLong() ?: 0L
                for (change in changes) {
                    val item = change.jsonObject
                    val seq = item.getValue("seq").jsonPrimitive.long
                    check(seq == cursor + 1)
                    clock = maxOf(clock,item.getValue("clock").jsonPrimitive.long)
                    val table = sources[item.getValue("kind").jsonPrimitive.content]
                    if (table != null) db.prepareStatement("INSERT OR REPLACE INTO learning_sync_inbox VALUES(?,?,?)").use { q ->
                        q.setString(1,table.kind)
                        q.setString(2,item.getValue("id").jsonPrimitive.content)
                        q.setString(3,item.toString())
                        q.executeUpdate()
                    }
                    cursor = seq
                }
                drainInbox(db)
                save(db,"clock",clock.toString())
                db.createStatement().use { it.executeUpdate("DELETE FROM learning_sync_meta WHERE key='applying'") }
                save(db,"cursor",cursor.toString()); save(db,"epoch",epoch)
                true
            }
            if (!applied || changes.isEmpty()) return
            page = remote.changes(epoch,cursor) ?: return
        }
    }

    /** A course material or concept may arrive before its collection or the local book original. */
    private fun drainInbox(db: Connection) {
        do {
            val pending = db.createStatement().use { sql ->
                sql.executeQuery("SELECT item FROM learning_sync_inbox ORDER BY rowid").use { r ->
                    buildList { while (r.next()) add(Json.parseToJsonElement(r.getString(1)).jsonObject) }
                }
            }
            var applied = 0
            for (item in pending) {
                val table = sources.getValue(item.getValue("kind").jsonPrimitive.content)
                try {
                    apply(db,table,item)
                } catch (failure: org.sqlite.SQLiteException) {
                    if (failure.resultCode != org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY) throw failure
                    continue
                }
                db.prepareStatement("DELETE FROM learning_sync_inbox WHERE kind=? AND id=?").use { q ->
                    q.setString(1,table.kind)
                    q.setString(2,item.getValue("id").jsonPrimitive.content)
                    q.executeUpdate()
                }
                applied++
            }
        } while (applied > 0 && applied < pending.size)
    }

    private fun apply(db: Connection, table: LearningTable, item: JsonObject) {
        val id = item.getValue("id").jsonPrimitive.content
        val row = item["body"] as? JsonObject
        val round = item.getValue("round").jsonPrimitive.long
        if (table.kind == "book_progress") {
            if (row == null) {
                db.prepareStatement("DELETE FROM learning_sync_pending_books WHERE record_id=?").use { q ->
                    q.setString(1,id)
                    q.executeUpdate()
                }
            } else {
                require(table.legacyId(row) == id && row.keys == table.columns.toSet())
                val book = row.getValue("id").jsonPrimitive.content
                val staged = db.prepareStatement("""INSERT OR REPLACE INTO learning_sync_pending_books
                    SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM books WHERE id=?)""").use { q ->
                    q.setString(1,book)
                    q.setString(2,id)
                    q.setString(3,row.toString())
                    q.setString(4,book)
                    q.executeUpdate() == 1
                }
                if (staged) {
                    rememberKeys(db,table.kind,id,JsonArray(listOf(JsonPrimitive(book))))
                    setVersion(db,table.kind,id,round,false)
                    return
                }
            }
        }
        val pk = db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info(${table.name})").use { r ->
                buildList { while (r.next()) if (r.getInt("pk") > 0) add(r.getInt("pk") to r.getString("name")) }
                    .sortedBy { it.first }.map { it.second }
            }
        }
        require(pk.isNotEmpty())
        val known = db.prepareStatement("SELECT keys_json FROM learning_sync_keys WHERE kind=? AND id=?").use { q ->
            q.setString(1,table.kind); q.setString(2,id)
            q.executeQuery().use { r -> if (r.next()) Json.parseToJsonElement(r.getString(1)).jsonArray else null }
        }
            ?: if (table.event) db.prepareStatement("SELECT local_id FROM learning_sync_events WHERE kind=? AND record_id=?").use { q ->
                q.setString(1,table.kind); q.setString(2,id)
                q.executeQuery().use { r -> if (r.next()) JsonArray(listOf(JsonPrimitive(r.getLong(1)))) else null }
            } else null
        if (row == null) {
            if (known != null) db.prepareStatement("DELETE FROM ${table.name} WHERE " + pk.joinToString(" AND ") { "$it=?" }).use { q ->
                known.forEachIndexed { index, value -> bind(q,index+1,value) }; q.executeUpdate()
            }
            for (statement in listOf("DELETE FROM learning_sync_keys WHERE kind=? AND id=?",
                "DELETE FROM learning_sync_events WHERE kind=? AND record_id=?")) {
                db.prepareStatement(statement).use { q ->
                    q.setString(1,table.kind)
                    q.setString(2,id)
                    q.executeUpdate()
                }
            }
        } else {
            require(row.keys.all { it in table.columns } && (table.event || table.legacyId(row) == id))
            if (table.kind == "exam_attempt") require(row["owner"]?.jsonPrimitive?.content == "learner")
            val fields = row.toMutableMap()
            if (table.event && known != null) fields["id"] = known.single()
            if (table.kind == "ask_turn" && fields["answer_status"]?.jsonPrimitive?.content in setOf("failed","pending")) {
                fields["write_id"] = JsonPrimitive(UUID.randomUUID().toString())
            }
            val update = fields.keys.filterNot { it in pk }.joinToString(",") { "$it=excluded.$it" }
            val statement = "INSERT INTO ${table.name}(${fields.keys.joinToString(",")}) VALUES(" +
                fields.keys.joinToString(",") { "?" } + ") ON CONFLICT(${pk.joinToString(",")}) DO UPDATE SET $update"
            db.prepareStatement(statement).use { q ->
                fields.values.forEachIndexed { index, value -> bind(q,index+1,value) }
                q.executeUpdate()
            }
            val keys = if (table.event && known == null) {
                val localId = db.createStatement().use { sql -> sql.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
                db.prepareStatement("INSERT OR REPLACE INTO learning_sync_events VALUES(?,?,?)").use { q ->
                    q.setString(1,table.kind); q.setLong(2,localId); q.setString(3,id); q.executeUpdate()
                }
                JsonArray(listOf(JsonPrimitive(localId)))
            } else JsonArray(pk.map { fields.getValue(it) })
            rememberKeys(db,table.kind,id,keys)
        }
        setVersion(db,table.kind,id,round,row == null)
    }

    private fun bind(q: PreparedStatement, index: Int, value: JsonElement) {
        require(value is JsonPrimitive)
        if (value == JsonNull) q.setNull(index,java.sql.Types.NULL) else q.setString(index,value.content)
    }

    private fun rememberKeys(db: Connection, kind: String, id: String, keys: JsonArray) {
        db.prepareStatement("INSERT OR REPLACE INTO learning_sync_keys VALUES(?,?,?)").use { q ->
            q.setString(1,kind); q.setString(2,id); q.setString(3,keys.toString()); q.executeUpdate()
        }
    }
}
