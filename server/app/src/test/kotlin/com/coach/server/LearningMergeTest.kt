package com.coach.server

import com.coach.protocol.learning.learningTables
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LearningMergeTest {
    @Test fun divergentBaselinesAndConcurrentFieldsAreIndependentOfArrivalOrder() {
        val table = learningTables.single { it.kind == "book_progress" }
        val base = buildJsonObject {
            for (field in table.columns) put(field,0)
            put("id","a".repeat(64)); put("title","old"); put("format","TXT")
        }
        val id = table.legacyId(base)
        val left = "10000000-0000-4000-8000-000000000000"
        val right = "20000000-0000-4000-8000-000000000000"
        val otherBase = JsonObject(base + ("reading_page" to JsonPrimitive(8)))
        val leftEdit = JsonObject(base + mapOf("reading_page" to JsonPrimitive(10),"title" to JsonPrimitive("saved")))
        val rightEdit = JsonObject(otherBase + ("reading_page" to JsonPrimitive(20)))
        val account = UnifiedBookScope("sable-" + "f".repeat(64),true)
        for (order in listOf(listOf(left,right),listOf(right,left))) {
            val directory = Files.createTempDirectory("merge-order")
            try {
                val store = LearningSyncStore(directory)
                val epoch = store.profile(account).getValue("epoch")
                fun submit(stream: String, counter: Int, before: JsonElement, after: JsonElement) {
                    val result = store.push(account,"device-$stream",buildJsonObject {
                        put("kind",table.kind); put("id",id); put("epoch",epoch); put("protocol",1)
                        put("stream",stream); put("seq",counter); put("clock",counter); put("time",0)
                        put("round",1); put("before",before); put("after",after)
                    })
                    assertEquals(JsonPrimitive(counter),result["seq"])
                }
                for (stream in order) submit(stream,1,JsonNull,if (stream == left) base else otherBase)
                for (stream in order.reversed()) {
                    if (stream == left) submit(stream,2,base,leftEdit) else submit(stream,2,otherBase,rightEdit)
                }
                assertEquals(JsonObject(rightEdit + ("title" to JsonPrimitive("saved"))),
                    store.lookup(account,table.kind,id)["body"])
            } finally { directory.toFile().deleteRecursively() }
        }
    }
}
