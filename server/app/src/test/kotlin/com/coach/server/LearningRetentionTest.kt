package com.coach.server

import com.coach.protocol.learning.learningTables
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearningRetentionTest {
    @Test fun laggingActiveLibraryDelaysPruningAndOfflineUpdatesRespectDeletedRounds() {
        val root = Files.createTempDirectory("learning-retention-test")
        try {
            val day = 86_400_000L
            var now = day
            val store = LearningSyncStore(root) { now }
            val account = UnifiedBookScope("sable-" + "a".repeat(64),true)
            val epoch = store.profile(account).getValue("epoch").jsonPrimitive.content
            val source = UUID.randomUUID().toString()
            val waiting = UUID.randomUUID().toString()
            val book = buildJsonObject { put("book","a".repeat(64)); put("page",7); put("note","first"); put("created_at",1) }
            val id = learningTables.single { it.kind == "bookmark" }.legacyId(book)
            fun push(seq: Long, round: Long, before: JsonElement, after: JsonElement) {
                store.push(account,"device",buildJsonObject {
                    put("epoch",epoch); put("protocol",1); put("kind","bookmark"); put("id",id); put("stream",source)
                    put("seq",seq); put("clock",seq); put("time",now); put("round",round); put("before",before); put("after",after)
                })
            }
            fun read(stream: String, seq: Long) = store.changes(account,epoch,seq,"device",stream)
            read(source,0)
            push(1,1,JsonNull,book)
            push(2,1,book,JsonNull)
            now += 89 * day
            read(waiting,0)
            now += 2 * day
            read(source,2)
            assertEquals(2,read(waiting,0).getValue("changes").jsonArray.size)
            read(waiting,2)
            now += day
            read(source,2)
            val offline = UUID.randomUUID().toString()
            assertEquals(JsonPrimitive(true),read(offline,0)["snapshotRequired"])
            val tombstone = store.snapshot(account,epoch,null,0).getValue("items").jsonArray.single().jsonObject
            assertEquals(JsonNull,tombstone["body"])
            assertEquals(JsonPrimitive(1),tombstone["round"])
            push(3,1,book,book)
            assertEquals(JsonNull,store.lookup(account,"bookmark",id)["body"])
            push(4,2,JsonNull,book)
            push(5,1,book,JsonNull)
            assertEquals(book,store.lookup(account,"bookmark",id)["body"])
            assertNull(read(source,2)["snapshotRequired"])
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
