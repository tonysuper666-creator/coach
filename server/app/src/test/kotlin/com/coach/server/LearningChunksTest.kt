package com.coach.server

import com.coach.protocol.learning.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class LearningChunksTest {
    @Test fun expiredOrDamagedPrefixCannotPublishAndCanBeRetried() {
        val root = Files.createTempDirectory("learning-parts")
        try {
            var now = 1L
            val store = LearningSyncStore(root,now={now})
            val account = UnifiedBookScope("sable-" + "f".repeat(64),true)
            val profile = store.profile(account)
            val stream = UUID.randomUUID().toString()
            val table = learningTables.single { it.kind=="ask_draft" }
            val row = buildJsonObject {
                table.columns.forEach { put(it,JsonNull) }
                put("book","b".repeat(64)); put("question","q".repeat(LEARNING_CHUNK_BYTES))
            }
            val id = table.legacyId(row)
            val op = buildJsonObject {
                put("stream",stream); put("seq",1); put("clock",1); put("time",0); put("protocol",1)
                put("round",1); put("id",id); put("kind",table.kind); put("epoch",profile.getValue("epoch"))
                put("before",JsonNull); put("after",row)
            }
            val bytes = op.toString().encodeToByteArray()
            val header = buildJsonObject {
                listOf("stream","seq","epoch").forEach { put(it,op.getValue(it)) }
                put("transportEpoch",profile.getValue("transportEpoch")); put("chunk",1)
                put("size",bytes.size); put("digest",learningDigest(bytes))
            }
            fun part(offset: Int, damaged: Boolean = false): JsonObject {
                val data = bytes.copyOfRange(offset,minOf(bytes.size,offset+LEARNING_CHUNK_BYTES))
                if (damaged) data[0] = 0
                return JsonObject(header + mapOf("offset" to JsonPrimitive(offset),
                    "data" to JsonPrimitive(Base64.getEncoder().encodeToString(data))))
            }
            fun send(request: JsonObject) = store.push(account,"A",request)
            assertEquals(JsonPrimitive(LEARNING_CHUNK_BYTES),send(part(0))["next"])
            assertFailsWith<LearningConflict> { store.push(account,"B",header) }
            assertFailsWith<LearningConflict> { send(part(0,damaged=true)) }
            now += LEARNING_CHUNK_TTL_MS+1
            assertEquals(JsonPrimitive(0),send(header)["next"])
            send(part(0,damaged=true))
            assertEquals(JsonPrimitive(0),send(part(LEARNING_CHUNK_BYTES))["next"])
            assertFailsWith<LearningRecordMissing> { store.lookup(account,table.kind,id) }
            send(part(0))
            val receipt = send(part(LEARNING_CHUNK_BYTES))
            assertEquals(JsonPrimitive(1),receipt["seq"])
            assertEquals(receipt,send(header))
            assertEquals(row,store.lookup(account,table.kind,id)["body"])
            val other = UnifiedBookScope("sable-" + "e".repeat(64),true)
            val otherProfile = store.profile(other)
            assertFailsWith<LearningRecordMissing> {
                store.part(other,otherProfile.getValue("epoch").jsonPrimitive.content,
                    otherProfile.getValue("transportEpoch").jsonPrimitive.content,null,1,0)
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
