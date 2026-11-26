package com.coach.server

import com.coach.store.sha256
import com.coach.protocol.learning.learningTables
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.json.*

/** All storage paths are obtained from the authenticated Sable scope. Cursors belong to one database. */
internal class LearningSyncStore(private val accounts: Path) {
    private val tables = learningTables.associateBy { it.kind }
    private fun <T> transaction(scope: UnifiedBookScope, body: (Connection, String) -> T): T {
        require(scope.isSableAccount)
        val directory = privateFiles.directory(accounts.resolve(scope.scopeKey))
        val file = privateFiles.file(directory.resolve("learning.sqlite"))
        return DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA busy_timeout=5000")
                sql.execute("BEGIN IMMEDIATE")
                try {
                    sql.execute("CREATE TABLE IF NOT EXISTS sync_meta(epoch TEXT NOT NULL)")
                    sql.execute("""CREATE TABLE IF NOT EXISTS sync_receipts(
                        device TEXT NOT NULL, stream TEXT NOT NULL, counter INTEGER NOT NULL,
                        digest TEXT NOT NULL, PRIMARY KEY(stream,counter))""")
                    sql.execute("""CREATE TABLE IF NOT EXISTS sync_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind TEXT NOT NULL,id TEXT NOT NULL,body TEXT,round INTEGER NOT NULL,writer TEXT NOT NULL)""")
                    sql.execute("""CREATE TABLE IF NOT EXISTS sync_records(kind TEXT NOT NULL,id TEXT NOT NULL,
                        seq INTEGER NOT NULL,body TEXT,round INTEGER NOT NULL,writer TEXT NOT NULL,PRIMARY KEY(kind,id))""")
                    val epoch = sql.executeQuery("SELECT epoch FROM sync_meta").use { rows ->
                        if (rows.next()) rows.getString(1) else null
                    } ?: UUID.randomUUID().toString().also { epoch ->
                        db.prepareStatement("INSERT INTO sync_meta VALUES(?)").use { it.setString(1, epoch); it.executeUpdate() }
                    }
                    val result = body(db, epoch)
                    sql.execute("COMMIT")
                    result
                } catch (failure: Throwable) {
                    sql.execute("ROLLBACK")
                    throw failure
                }
            }
        }
    }

    fun profile(scope: UnifiedBookScope) = transaction(scope) { _, epoch ->
        buildJsonObject { put("scope",scope.scopeKey); put("epoch",epoch) }
    }

    fun changes(scope: UnifiedBookScope, expectedEpoch: String?, after: Long): JsonObject = transaction(scope) { db, epoch ->
        if ((after != 0L || expectedEpoch != null) && expectedEpoch != epoch) throw LearningConflict()
        val changes = buildJsonArray {
            db.prepareStatement("SELECT seq,kind,id,body,round,writer FROM sync_changes WHERE seq>? ORDER BY seq LIMIT 64").use { q ->
                q.setLong(1, after)
                q.executeQuery().use { r ->
                    var bytes = 0
                    var emitted = 0
                    while (r.next()) {
                        val body = r.getString(4)
                        bytes += body?.length ?: 0
                        if (emitted > 0 && bytes > 1_000_000) break
                        emitted++
                        add(buildJsonObject {
                            put("seq",r.getLong(1)); put("kind",r.getString(2)); put("id",r.getString(3))
                            put("body",body?.let(Json::parseToJsonElement) ?: JsonNull); put("round",r.getLong(5))
                            put("clock",r.getString(6).substringBefore('/').toLong())
                        })
                    }
                }
            }
        }
        buildJsonObject { put("scope",scope.scopeKey); put("epoch",epoch); put("changes",changes) }
    }

    fun lookup(scope: UnifiedBookScope, kind: String, id: String) = transaction(scope) { db, epoch ->
        require(kind in tables && UUID.fromString(id).toString() == id)
        db.prepareStatement("SELECT seq,body,round FROM sync_records WHERE kind=? AND id=?").use { q ->
            q.setString(1,kind)
            q.setString(2,id)
            q.executeQuery().use { r ->
                if (!r.next()) throw LearningRecordMissing()
                buildJsonObject {
                    put("scope",scope.scopeKey)
                    put("epoch",epoch)
                    put("seq",r.getLong(1))
                    put("body",r.getString(2)?.let(Json::parseToJsonElement) ?: JsonNull)
                    put("round",r.getLong(3))
                }
            }
        }
    }

    fun push(scope: UnifiedBookScope, device: String, request: JsonObject) = transaction(scope) { db, epoch ->
        if (request["epoch"]?.jsonPrimitive?.content != epoch) throw LearningConflict()
        require(request.getValue("protocol").jsonPrimitive.int == 1)
        val clock = request.getValue("clock").jsonPrimitive.long.also { require(it in 1 until Long.MAX_VALUE) }
        val stream = UUID.fromString(request.getValue("stream").jsonPrimitive.content).toString()
        val counter = request.getValue("seq").jsonPrimitive.long.also { require(it > 0) }
        val id = UUID.fromString(request.getValue("id").jsonPrimitive.content).toString()
        val kind = request.getValue("kind").jsonPrimitive.content
        val table = requireNotNull(tables[kind])
        val round = request.getValue("round").jsonPrimitive.long.also { require(it > 0) }
        fun payload(key: String): JsonObject? {
            val value = request.getValue(key)
            if (value == JsonNull) return null
            val row = value.jsonObject
            val fields = if (table.event) table.columns - "id" else table.columns
            require(row.keys == fields.toSet() && row.values.all { it is JsonPrimitive })
            require(table.event || table.legacyId(row) == id)
            require(kind != "exam_attempt" || row["owner"]?.jsonPrimitive?.content == "learner")
            return row
        }
        val before = payload("before")
        val after = payload("after")
        val digest = sha256(request.toString().toByteArray(Charsets.UTF_8))
        val receipt = db.prepareStatement("SELECT digest FROM sync_receipts WHERE stream=? AND counter=?").use { q ->
            q.setString(1,stream); q.setLong(2,counter)
            q.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        if (receipt != null && receipt != digest) throw LearningConflict()
        if (receipt == null) {
            val previous = db.prepareStatement("SELECT coalesce(max(counter),0) FROM sync_receipts WHERE stream=?").use { q ->
                q.setString(1,stream)
                q.executeQuery().use { it.next(); it.getLong(1) }
            }
            if (counter != previous + 1) throw LearningConflict()
            val current = db.prepareStatement("SELECT round,body,writer FROM sync_records WHERE kind=? AND id=?").use { q ->
                q.setString(1,kind); q.setString(2,id)
                q.executeQuery().use { r -> if (r.next()) Triple(r.getLong(1),r.getString(2)?.let { Json.parseToJsonElement(it).jsonObject },r.getString(3)) else null }
            }
            val stored = current?.second
            val currentRound = current?.first ?: 1L
            val recreating = current != null && stored == null && before == null && after != null && round == currentRound + 1
            if (round > currentRound && !recreating) throw LearningConflict()
            val writer = "${clock.toString().padStart(19,'0')}/$stream/${counter.toString().padStart(19,'0')}"
            fun recoverNote(body: JsonObject, author: String) {
                if (body["note"] == before?.get("note")) return
                val copyId = UUID.nameUUIDFromBytes("$id/$round/$author".encodeToByteArray()).toString()
                val copy = JsonObject(body + ("id" to JsonPrimitive(copyId)))
                record(db,kind,table.legacyId(copy),copy,1,author)
            }
            when {
                recreating || current == null -> record(db,kind,id,after,round,writer)
                round < currentRound || stored == null -> {
                    if (kind == "note" && after != null) recoverNote(after,writer)
                }
                stored == after -> Unit
                stored == before -> record(db,kind,id,after,round,writer)
                after == null -> {
                    if (kind == "note") recoverNote(stored,current.third)
                    record(db,kind,id,null,round,writer)
                }
                kind == "note" -> {
                    val incomingWins = writer > current.third
                    if (stored["note"] != after["note"] && stored["note"] != before?.get("note") && after["note"] != before?.get("note")) {
                        if (incomingWins) recoverNote(stored,current.third) else recoverNote(after,writer)
                    }
                    val merged = JsonObject(after.mapValues { (field,value) ->
                        when {
                            value == before?.get(field) -> stored.getValue(field)
                            stored[field] == before?.get(field) || incomingWins -> value
                            else -> stored.getValue(field)
                        }
                    })
                    record(db,kind,id,merged,round,maxOf(writer,current.third))
                }
                kind == "read_spot" -> if (writer > current.third) record(db,kind,id,after,round,writer)
                else -> throw LearningConflict()
            }
            db.prepareStatement("INSERT INTO sync_receipts VALUES(?,?,?,?)").use { q ->
                q.setString(1,device); q.setString(2,stream); q.setLong(3,counter); q.setString(4,digest); q.executeUpdate()
            }
        }
        buildJsonObject { put("scope",scope.scopeKey); put("epoch",epoch); put("seq",counter) }
    }

    private fun record(db: Connection, kind: String, id: String, row: JsonObject?, round: Long, writer: String) {
        db.prepareStatement("INSERT INTO sync_changes(kind,id,body,round,writer) VALUES(?,?,?,?,?)").use { q ->
            q.setString(1,kind)
            q.setString(2,id)
            q.setString(3,row?.toString())
            q.setLong(4,round)
            q.setString(5,writer)
            q.executeUpdate()
        }
        db.prepareStatement("""INSERT INTO sync_records(kind,id,seq,body,round,writer) VALUES(?,?,last_insert_rowid(),?,?,?)
            ON CONFLICT(kind,id) DO UPDATE SET seq=excluded.seq,body=excluded.body,round=excluded.round,writer=excluded.writer""").use { q ->
            q.setString(1,kind)
            q.setString(2,id)
            q.setString(3,row?.toString())
            q.setLong(4,round)
            q.setString(5,writer)
            q.executeUpdate()
        }
    }
}

internal class LearningConflict : Exception()
internal class LearningRecordMissing : Exception()
