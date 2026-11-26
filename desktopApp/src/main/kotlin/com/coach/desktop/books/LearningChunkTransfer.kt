package com.coach.desktop.books

import com.coach.protocol.learning.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64

internal object LearningChunkTransfer {
    suspend fun push(op: JsonObject, scope: String, transport: String, remote: LearningRemote, current: () -> Boolean): JsonObject? {
        val bytes = op.toString().encodeToByteArray()
        if (bytes.size <= LEARNING_CHUNK_BYTES) return remote.push(JsonObject(op + ("transportEpoch" to JsonPrimitive(transport))))
        require(bytes.size <= LEARNING_UPLOAD_LIMIT)
        val header = buildJsonObject {
            put("chunk",1); put("epoch",op.getValue("epoch")); put("transportEpoch",transport)
            put("stream",op.getValue("stream")); put("seq",op.getValue("seq"))
            put("size",bytes.size); put("digest",learningDigest(bytes))
        }
        var reply = remote.push(header) ?: return null
        var last = -1
        while (current()) {
            if (reply["epoch"] != op["epoch"] || reply["scope"] != JsonPrimitive(scope)) return null
            if (reply["seq"] == op["seq"]) return reply
            val offset = reply.getValue("next").jsonPrimitive.int
            if (offset <= last || offset !in bytes.indices || offset % LEARNING_CHUNK_BYTES != 0) return null
            val part = bytes.copyOfRange(offset,minOf(bytes.size,offset+LEARNING_CHUNK_BYTES))
            last = offset
            reply = remote.push(JsonObject(header + mapOf("offset" to JsonPrimitive(offset),
                "data" to JsonPrimitive(Base64.getEncoder().encodeToString(part))))) ?: return null
        }
        return null
    }

    suspend fun readPage(path: Path, items: JsonArray, epoch: String, transport: String, snapshot: String?,
                         remote: LearningRemote, current: () -> Boolean): JsonArray? {
        if (items.none { "parts" in it.jsonObject }) return items
        return DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            buildJsonArray {
                for (element in items) {
                    val item = element.jsonObject
                    val manifest = item["parts"]?.jsonObject
                    if (manifest == null) { add(item); continue }
                    val digest = manifest.getValue("digest").jsonPrimitive.content
                    val size = manifest.getValue("bytes").jsonPrimitive.int
                    require(size > LEARNING_CHUNK_BYTES && digest.matches(Regex("[0-9a-f]{64}")))
                    val bytes = ByteArray(size)
                    val seq = item.getValue("seq").jsonPrimitive.long
                    for (offset in bytes.indices step LEARNING_CHUNK_BYTES) {
                        if (!current()) return null
                        val data = db.prepareStatement("SELECT data FROM learning_sync_parts WHERE digest=? AND offset=?").use {
                            it.setString(1,digest); it.setInt(2,offset)
                            it.executeQuery().use { r -> if (r.next()) r.getBytes(1) else null }
                        } ?: run {
                            val reply = remote.part(epoch,transport,snapshot,seq,offset) ?: return null
                            if (!current() || reply["offset"] != JsonPrimitive(offset)) return null
                            val chunk = Base64.getDecoder().decode(reply.getValue("data").jsonPrimitive.content)
                            require(chunk.size == minOf(LEARNING_CHUNK_BYTES,size-offset))
                            db.prepareStatement("INSERT OR IGNORE INTO learning_sync_parts VALUES(?,?,?,?)").use {
                                it.setString(1,digest); it.setInt(2,offset); it.setBytes(3,chunk)
                                it.setLong(4,System.currentTimeMillis()); it.executeUpdate()
                            }
                            chunk
                        }
                        if (data.size != minOf(LEARNING_CHUNK_BYTES,size-offset)) {
                            forget(db,digest)
                            return null
                        }
                        data.copyInto(bytes,offset)
                    }
                    if (learningDigest(bytes) != digest) {
                        forget(db,digest)
                        return null
                    }
                    val decoded = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence=true)).jsonObject
                    if (snapshot == null) add(JsonObject((item - "parts") + ("body" to decoded))) else {
                        require(decoded["seq"] == item["seq"])
                        add(decoded)
                    }
                }
            }
        }
    }

    fun applied(db: Connection, items: JsonArray) {
        for (item in items) item.jsonObject["parts"]?.jsonObject?.let { forget(db,it.getValue("digest").jsonPrimitive.content) }
    }

    private fun forget(db: Connection, digest: String) {
        db.prepareStatement("DELETE FROM learning_sync_parts WHERE digest=?").use { it.setString(1,digest); it.executeUpdate() }
    }
}
