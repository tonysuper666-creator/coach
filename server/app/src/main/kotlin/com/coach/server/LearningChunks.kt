package com.coach.server

import com.coach.protocol.learning.*
import kotlinx.serialization.json.*
import java.sql.Connection
import java.util.Base64
import java.util.UUID

internal object LearningChunks {
    fun install(db: Connection, now: Long) = db.createStatement().use { q ->
        q.execute("""CREATE TABLE IF NOT EXISTS parts.upload(stream TEXT,offset INTEGER,device TEXT NOT NULL,
            counter INTEGER NOT NULL,digest TEXT NOT NULL,size INTEGER NOT NULL,transport TEXT NOT NULL,
            touched INTEGER NOT NULL,data BLOB NOT NULL,PRIMARY KEY(stream,offset))""")
        db.prepareStatement("DELETE FROM parts.upload WHERE stream IN (SELECT stream FROM parts.upload GROUP BY stream HAVING MAX(touched)<?)").use {
            it.setLong(1,now-LEARNING_CHUNK_TTL_MS); it.executeUpdate()
        }
    }

    /** Each persisted prefix is contiguous. The last part, merge and receipt share the account transaction. */
    fun receive(db: Connection, device: String, scope: String, request: JsonObject, now: Long,
                publish: (JsonObject) -> JsonObject): JsonObject {
        val stream = request.getValue("stream").jsonPrimitive.content
        require(UUID.fromString(stream).toString() == stream)
        val seq = request.getValue("seq").jsonPrimitive.long
        val size = request.getValue("size").jsonPrimitive.int
        val digest = request.getValue("digest").jsonPrimitive.content
        val transport = request.getValue("transportEpoch").jsonPrimitive.content
        require(seq > 0 && size in LEARNING_CHUNK_BYTES+1..LEARNING_UPLOAD_LIMIT && digest.matches(Regex("[0-9a-f]{64}")))
        fun response(next: Int?) = buildJsonObject {
            put("scope",scope); put("epoch",request.getValue("epoch"))
            if (next == null) put("seq",seq) else put("next",next)
        }
        db.prepareStatement("SELECT device,counter,digest FROM sync_receipts WHERE stream=? ORDER BY counter DESC LIMIT 1").use { q ->
            q.setString(1,stream)
            q.executeQuery().use { r ->
                if (r.next()) {
                    if (r.getString(1)!=device) throw LearningConflict()
                    if (r.getLong(2)>=seq) {
                        db.prepareStatement("SELECT digest FROM sync_receipts WHERE stream=? AND counter=?").use {
                            it.setString(1,stream); it.setLong(2,seq)
                            it.executeQuery().use { ack -> if (!ack.next() || ack.getString(1)!=digest) throw LearningConflict() }
                        }
                        return response(null)
                    }
                    if (seq != r.getLong(2)+1) throw LearningConflict()
                } else if (seq != 1L) throw LearningConflict()
            }
        }
        db.prepareStatement("DELETE FROM parts.upload WHERE stream=? AND (transport<>? OR counter<?)").use {
            it.setString(1,stream); it.setString(2,transport); it.setLong(3,seq); it.executeUpdate()
        }
        val next = db.prepareStatement("SELECT device,counter,digest,size,offset+length(data) FROM parts.upload WHERE stream=? ORDER BY offset DESC LIMIT 1").use { q ->
            q.setString(1,stream)
            q.executeQuery().use { r ->
                if (!r.next()) 0 else {
                    if (r.getString(1)!=device || r.getLong(2)!=seq || r.getString(3)!=digest || r.getInt(4)!=size) throw LearningConflict()
                    r.getInt(5)
                }
            }
        }
        if (request["data"] == null) return response(next)
        val offset = request.getValue("offset").jsonPrimitive.int
        require(offset in 0 until size && offset % LEARNING_CHUNK_BYTES == 0)
        if (offset > next) throw LearningConflict()
        val encoded = request.getValue("data").jsonPrimitive.content
        require(encoded.length <= 4*((LEARNING_CHUNK_BYTES+2)/3))
        val data = Base64.getDecoder().decode(encoded)
        require(data.size == minOf(LEARNING_CHUNK_BYTES,size-offset))
        if (offset < next) {
            db.prepareStatement("SELECT data FROM parts.upload WHERE stream=? AND offset=?").use {
                it.setString(1,stream); it.setInt(2,offset)
                it.executeQuery().use { r -> if (!r.next() || !r.getBytes(1).contentEquals(data)) throw LearningConflict() }
            }
            return response(next)
        }
        db.createStatement().use { q ->
            q.executeQuery("SELECT coalesce(sum(length(data)),0) FROM parts.upload").use {
                check(it.next()); require(it.getLong(1)+data.size <= 4L*LEARNING_UPLOAD_LIMIT)
            }
        }
        db.prepareStatement("INSERT INTO parts.upload VALUES(?,?,?,?,?,?,?,?,?)").use {
            it.setString(1,stream); it.setInt(2,offset); it.setString(3,device); it.setLong(4,seq); it.setString(5,digest)
            it.setInt(6,size); it.setString(7,transport); it.setLong(8,now); it.setBytes(9,data); it.executeUpdate()
        }
        if (offset+data.size < size) return response(offset+data.size)
        val bytes = ByteArray(size)
        db.prepareStatement("SELECT offset,data FROM parts.upload WHERE stream=? ORDER BY offset").use {
            it.setString(1,stream)
            it.executeQuery().use { r -> while (r.next()) r.getBytes(2).copyInto(bytes,r.getInt(1)) }
        }
        db.prepareStatement("DELETE FROM parts.upload WHERE stream=?").use { it.setString(1,stream); it.executeUpdate() }
        if (learningDigest(bytes)!=digest) return response(0)
        val op = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence=true)).jsonObject
        require(listOf("stream","seq","epoch").all { op[it] == request[it] } && "transportEpoch" !in op)
        require(learningDigest(op.toString().encodeToByteArray()) == digest)
        return publish(JsonObject(op + ("transportEpoch" to JsonPrimitive(transport))))
    }

    fun manifest(item: JsonObject): JsonObject {
        val parts = descriptor(item.getValue("body").toString()) ?: return item
        return JsonObject(item + mapOf("body" to JsonNull, "parts" to parts))
    }

    fun snapshotItem(seq: Long, frozen: String): JsonObject {
        val parts = descriptor(frozen) ?: return Json.parseToJsonElement(frozen).jsonObject
        return buildJsonObject { put("seq",seq); put("parts",parts) }
    }

    private fun descriptor(raw: String): JsonObject? {
        val bytes = raw.encodeToByteArray()
        if (bytes.size <= LEARNING_CHUNK_BYTES) return null
        return buildJsonObject { put("bytes",bytes.size); put("digest",learningDigest(bytes)) }
    }

    fun read(db: Connection, token: String?, seq: Long, offset: Int, now: Long): JsonObject {
        require(seq>0 && offset>=0 && offset % LEARNING_CHUNK_BYTES == 0)
        val query = if (token == null) "SELECT substr(CAST(body AS BLOB),?,?) FROM sync_changes WHERE seq=?"
        else """SELECT substr(CAST(r.item AS BLOB),?,?) FROM sync_snapshot_rows r
            JOIN sync_snapshots s ON s.token=r.token WHERE r.seq=? AND r.token=? AND s.expires>?"""
        return db.prepareStatement(query).use { q ->
            q.setLong(1,offset.toLong()+1); q.setInt(2,LEARNING_CHUNK_BYTES); q.setLong(3,seq)
            if (token != null) { q.setString(4,token); q.setLong(5,now) }
            q.executeQuery().use { r ->
                if (!r.next()) throw LearningRecordMissing()
                val bytes = r.getBytes(1)
                require(bytes != null && bytes.isNotEmpty())
                buildJsonObject { put("offset",offset); put("data",Base64.getEncoder().encodeToString(bytes)) }
            }
        }
    }
}
