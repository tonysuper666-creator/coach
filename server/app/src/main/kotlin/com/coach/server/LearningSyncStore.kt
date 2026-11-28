package com.coach.server

import com.coach.store.sha256
import com.coach.protocol.learning.learningTables
import com.coach.protocol.learning.draftVersion
import java.nio.file.Path
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.json.*

/** All storage paths are obtained from the authenticated account scope. Cursors belong to one database. */
internal class LearningSyncStore(
    private val accounts: Path,
    private val backup: LearningBackup? = null,
    private val allowOwner: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val tables = (learningTables + draftVersion).associateBy { it.kind }
    private fun <T> transaction(scope: UnifiedBookScope, upload: Boolean = false, body: (Connection, String) -> T): T {
        require(scope.isSableAccount || allowOwner)
        val directory = privateFiles.directory(accounts.resolve(scope.scopeKey))
        val file = directory.resolve("learning.sqlite")
        backup?.recoverMissing(file,scope.scopeKey)
        privateFiles.file(file)
        return DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA busy_timeout=5000")
                backup?.attach(db)
                val parts = directory.resolve("learning-parts.sqlite")
                val hasParts = upload || Files.exists(parts)
                if (hasParts) {
                    privateFiles.file(parts)
                    db.prepareStatement("ATTACH DATABASE ? AS parts").use { it.setString(1,parts.toString()); it.execute() }
                }
                sql.execute("BEGIN IMMEDIATE")
                try {
                    if (hasParts) LearningChunks.install(db,now())
                    sql.execute("CREATE TABLE IF NOT EXISTS sync_meta(epoch TEXT NOT NULL)")
                    sql.execute("""CREATE TABLE IF NOT EXISTS sync_receipts(
                        device TEXT NOT NULL, stream TEXT NOT NULL, counter INTEGER NOT NULL,
                        digest TEXT NOT NULL, PRIMARY KEY(stream,counter))""")
                    sql.execute("""CREATE TABLE IF NOT EXISTS sync_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind TEXT NOT NULL,id TEXT NOT NULL,body TEXT,round INTEGER NOT NULL,writer TEXT NOT NULL)""")
                    sql.execute("""CREATE TABLE IF NOT EXISTS sync_records(kind TEXT NOT NULL,id TEXT NOT NULL,
                        seq INTEGER NOT NULL,body TEXT,round INTEGER NOT NULL,writer TEXT NOT NULL,PRIMARY KEY(kind,id))""")
                    sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS sync_records_seq ON sync_records(seq)")
                    LearningFactReplay.install(db)
                    LearningSnapshots.install(db)
                    LearningRetention.install(db,now())
                    sql.execute("""CREATE TABLE IF NOT EXISTS sync_field_writers(kind TEXT,id TEXT,round INTEGER,
                        field TEXT,writer TEXT NOT NULL,PRIMARY KEY(kind,id,round,field))""")
                    val epoch = sql.executeQuery("SELECT epoch FROM sync_meta").use { rows ->
                        if (rows.next()) rows.getString(1) else null
                    } ?: UUID.randomUUID().toString().also { epoch ->
                        db.prepareStatement("INSERT INTO sync_meta VALUES(?)").use { it.setString(1, epoch); it.executeUpdate() }
                    }
                    sql.execute("CREATE TABLE IF NOT EXISTS sync_transport(singleton INTEGER PRIMARY KEY,epoch TEXT NOT NULL)")
                    sql.execute("INSERT OR IGNORE INTO sync_transport SELECT 1,epoch FROM sync_meta")
                    backup?.verify(db,scope.scopeKey)
                    val result = body(db, epoch)
                    backup?.advance(db,scope.scopeKey)
                    sql.execute("COMMIT")
                    result
                } catch (failure: Throwable) {
                    sql.execute("ROLLBACK")
                    throw failure
                }
            }
        }
    }

    private fun transport(db: Connection) = db.createStatement().use { q ->
        q.executeQuery("SELECT epoch FROM sync_transport WHERE singleton=1").use { r -> check(r.next()); r.getString(1) }
    }

    fun profile(scope: UnifiedBookScope) = transaction(scope) { db, epoch ->
        buildJsonObject { put("scope",scope.scopeKey); put("epoch",epoch); put("transportEpoch",transport(db)) }
    }

    fun snapshot(scope: UnifiedBookScope, expected: String, token: String?, after: Long, chunks: Boolean = false) = transaction(scope) { db, epoch ->
        if (expected != epoch) throw LearningConflict()
        JsonObject(LearningSnapshots.page(db,scope.scopeKey,epoch,token,after,now(),chunks) + ("transportEpoch" to JsonPrimitive(transport(db))))
    }

    fun changes(scope: UnifiedBookScope, expectedEpoch: String?, after: Long, device: String, stream: String, chunks: Boolean = false): JsonObject = transaction(scope) { db, epoch ->
        if ((after != 0L || expectedEpoch != null) && expectedEpoch != epoch) throw LearningConflict()
        require(UUID.fromString(stream).toString() == stream)
        val high = db.createStatement().use { it.executeQuery("SELECT coalesce(max(seq),0) FROM sync_records").use { r -> r.next(); r.getLong(1) } }
        require(after in 0..high)
        val floor = LearningRetention.observe(db,device,stream,after,now())
        val received = db.prepareStatement("SELECT coalesce(max(counter),0) FROM sync_receipts WHERE stream=?").use { q ->
            q.setString(1,stream)
            q.executeQuery().use { r -> r.next(); r.getLong(1) }
        }
        if (after < floor) return@transaction buildJsonObject {
            put("scope",scope.scopeKey); put("epoch",epoch); put("backedThrough",received)
            put("transportEpoch",transport(db))
            put("changes",JsonArray(emptyList())); put("snapshotRequired",true)
        }
        val changes = buildJsonArray {
            db.prepareStatement("SELECT seq,kind,id,body,round,writer FROM sync_changes WHERE seq>? ORDER BY seq LIMIT 64").use { q ->
                q.setLong(1, after)
                q.executeQuery().use { r ->
                    var bytes = 0
                    var emitted = 0
                    while (r.next()) {
                        val body = r.getString(4)
                        val item = buildJsonObject {
                            put("seq",r.getLong(1)); put("kind",r.getString(2)); put("id",r.getString(3))
                            put("body",body?.let(Json::parseToJsonElement) ?: JsonNull); put("round",r.getLong(5))
                            put("clock",r.getString(6).substringBefore('/').toLong())
                        }
                        bytes += item.toString().encodeToByteArray().size
                        if (emitted > 0 && bytes > 1_000_000) break
                        add(if (chunks) LearningChunks.manifest(item) else item)
                        emitted++
                    }
                }
            }
        }
        buildJsonObject { put("scope",scope.scopeKey); put("epoch",epoch); put("changes",changes); put("backedThrough",received); put("transportEpoch",transport(db)) }
    }.also { backup?.publish(accounts.resolve(scope.scopeKey).resolve("learning.sqlite"),scope.scopeKey) }

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

    fun part(scope: UnifiedBookScope, expectedEpoch: String, expectedTransport: String, token: String?, seq: Long, offset: Int) =
        transaction(scope) { db, epoch ->
            if (expectedEpoch != epoch || expectedTransport != transport(db)) throw LearningConflict()
            LearningChunks.read(db,token,seq,offset,now())
        }

    fun push(scope: UnifiedBookScope, device: String, request: JsonObject): JsonObject =
        transaction(scope, upload = "chunk" in request) { db, epoch ->
            if ("chunk" !in request) return@transaction applyPush(db,epoch,scope,device,request)
            require(request["chunk"] == JsonPrimitive(1))
            if (request["epoch"] != JsonPrimitive(epoch) || request["transportEpoch"] != JsonPrimitive(transport(db))) throw LearningConflict()
            LearningChunks.receive(db,device,scope.scopeKey,request,now()) { op -> applyPush(db,epoch,scope,device,op) }
        }

    private fun applyPush(db: Connection, epoch: String, scope: UnifiedBookScope, device: String, request: JsonObject): JsonObject {
        if (request["epoch"]?.jsonPrimitive?.content != epoch) throw LearningConflict()
        if ((request["transportEpoch"]?.jsonPrimitive?.content ?: epoch) != transport(db)) throw LearningConflict()
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
        // Retransmission after exact-water recovery keeps the original operation identity and digest.
        val digest = sha256(JsonObject(request - "transportEpoch").toString().toByteArray(Charsets.UTF_8))
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
                kind in setOf("card","mastery") && request["fact"] != null && after != null &&
                    (current == null || recreating || (round == currentRound && stored != null)) -> {
                    val display = if (stored != null && current.third > writer) stored else after
                    val replayed = LearningFactReplay.apply(db,kind,id,round,writer,stored ?: before,display,
                        after.getValue("updated_at").jsonPrimitive.long,request.getValue("fact").jsonObject)
                    record(db,kind,id,replayed,round,maxOf(writer,current?.third.orEmpty()))
                }
                recreating || current == null -> record(db,kind,id,after,round,writer)
                round < currentRound || stored == null -> {
                    if (kind == "note" && after != null) recoverNote(after,writer)
                    if (after != null) recoverDraft(db,kind,id,round,writer,after,before,null)
                }
                kind != "note" && after != null -> {
                    val merged = mergeFields(db,kind,id,round,stored,before,after,current.third,writer)
                    if (stored != after || writer > current.third) {
                        record(db,kind,id,merged,round,maxOf(current.third,writer))
                    }
                }
                stored == after -> Unit
                stored == before -> record(db,kind,id,after,round,writer)
                after == null -> {
                    if (kind == "note") recoverNote(stored,current.third)
                    val field = LearningDraftRecovery.field(kind)
                    if (field != null) {
                        val author = db.prepareStatement("SELECT writer FROM sync_field_writers WHERE kind=? AND id=? AND round=? AND field=?").use { q ->
                            q.setString(1,kind); q.setString(2,id); q.setLong(3,round); q.setString(4,field)
                            q.executeQuery().use { if (it.next()) it.getString(1) else current.third }
                        }
                        recoverDraft(db,kind,id,round,author,stored,before,null)
                    }
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
            }
            db.prepareStatement("INSERT INTO sync_receipts VALUES(?,?,?,?)").use { q ->
                q.setString(1,device); q.setString(2,stream); q.setLong(3,counter); q.setString(4,digest); q.executeUpdate()
            }
        }
        return buildJsonObject { put("scope",scope.scopeKey); put("epoch",epoch); put("seq",counter) }
    }

    private fun recoverDraft(db: Connection, kind: String, id: String, round: Long, author: String,
                             edited: JsonObject, before: JsonObject?, visible: JsonObject?) {
        for (copy in LearningDraftRecovery.copies(kind,id,round,author,edited,before,visible)) {
            val copyId = draftVersion.legacyId(copy)
            val known = db.prepareStatement("SELECT 1 FROM sync_records WHERE kind=? AND id=?").use { q ->
                q.setString(1,draftVersion.kind); q.setString(2,copyId); q.executeQuery().use { it.next() }
            }
            if (!known) record(db,draftVersion.kind,copyId,copy,1,author)
        }
    }

    private fun mergeFields(db: Connection, kind: String, id: String, round: Long, stored: JsonObject,
                            before: JsonObject?, after: JsonObject, previousWriter: String, writer: String): JsonObject {
        // Keep coupled coordinates, frozen papers and derived states internally consistent.
        val groups = when (kind) {
            "card","mastery","read_spot","exam_paper","exam_attempt","ask_turn","review_event","evidence","exam_daily" -> listOf(after.keys)
            else -> after.keys.map { setOf(it) }
        }
        val stamps = db.prepareStatement("SELECT field,writer FROM sync_field_writers WHERE kind=? AND id=? AND round=?").use { q ->
            q.setString(1,kind); q.setString(2,id); q.setLong(3,round)
            q.executeQuery().use { r -> buildMap { while (r.next()) put(r.getString(1),r.getString(2)) } }
        }
        val merged = stored.toMutableMap()
        db.prepareStatement("INSERT OR REPLACE INTO sync_field_writers VALUES(?,?,?,?,?)").use { q ->
            for (group in groups) {
                val field = if (group.size == 1) group.single() else "@row"
                val previous = stamps[field] ?: previousWriter
                val changed = before == null || group.any { before[it] != after[it] }
                val wins = changed && writer > previous
                if (field == LearningDraftRecovery.field(kind) && stored[field] != after[field]) {
                    if (wins) recoverDraft(db,kind,id,round,previous,stored,before,after)
                    else recoverDraft(db,kind,id,round,writer,after,before,stored)
                }
                if (wins) group.forEach { merged[it] = after.getValue(it) }
                if (wins || field !in stamps) {
                    q.setString(1,kind); q.setString(2,id); q.setLong(3,round); q.setString(4,field)
                    q.setString(5,if (wins) writer else previous)
                    q.addBatch()
                }
            }
            q.executeBatch()
        }
        return JsonObject(merged)
    }

    private fun record(db: Connection, kind: String, id: String, row: JsonObject?, round: Long, writer: String) {
        db.prepareStatement("INSERT INTO sync_changes(kind,id,body,round,writer,committed_at) VALUES(?,?,?,?,?,?)").use { q ->
            q.setString(1,kind)
            q.setString(2,id)
            q.setString(3,row?.toString())
            q.setLong(4,round)
            q.setString(5,writer)
            q.setLong(6,now())
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
