package com.coach.server

import com.coach.protocol.learning.draftVersion
import com.coach.protocol.learning.learningTables
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class LearningDraftRecoveryTest {
    @Test fun losingTextAndUnseenEditsHaveStableIdsInEitherArrivalOrder() {
        val table = learningTables.single { it.kind == "answer" }
        val base = buildJsonObject {
            put("book","b".repeat(64)); put("lesson","one"); put("mode","SHORT_ANSWER")
            put("text","base"); put("revealed",0); put("understood",JsonNull); put("guessed",0)
        }
        val id = table.legacyId(base)
        val account = UnifiedBookScope("sable-" + "c".repeat(64),true)
        val a = "10000000-0000-4000-8000-000000000000"
        val b = "20000000-0000-4000-8000-000000000000"
        for (delete in listOf(false,true)) {
            val copyIds = mutableSetOf<JsonElement>()
            for (order in listOf(listOf(a,b),listOf(b,a))) {
                val directory = Files.createTempDirectory("draft-order")
                try {
                    val store = LearningSyncStore(directory)
                    val epoch = store.profile(account).getValue("epoch").jsonPrimitive.content
                    fun push(stream: String, seq: Int, clock: Int, before: JsonElement, after: JsonElement) {
                        val op = buildJsonObject {
                            put("kind",table.kind); put("id",id); put("epoch",epoch); put("protocol",1)
                            put("stream",stream); put("seq",seq); put("clock",clock); put("time",0)
                            put("round",1); put("before",before); put("after",after)
                        }
                        val ack = store.push(account,stream,op)
                        assertEquals(ack,store.push(account,stream,op))
                    }
                    push(a,1,1,JsonNull,base)
                    for (stream in order) {
                        val after = if (delete && stream == b) JsonNull
                            else JsonObject(base + ("text" to JsonPrimitive(if (stream == a) "A" else "B")))
                        push(stream,if (stream == a) 2 else 1,2,base,after)
                    }
                    val changes = store.changes(account,epoch,0,a,a).getValue("changes").jsonArray.map { it.jsonObject }
                    val copy = changes.single { it["kind"] == JsonPrimitive(draftVersion.kind) }
                    val body = copy.getValue("body").jsonObject
                    assertEquals(JsonPrimitive("A"),body["text"])
                    assertEquals(JsonPrimitive("one"),body["location"])
                    copyIds.add(copy.getValue("id"))
                    val active = store.lookup(account,table.kind,id).getValue("body")
                    if (delete) assertEquals(JsonNull,active) else assertEquals(JsonPrimitive("B"),active.jsonObject["text"])
                } finally { directory.toFile().deleteRecursively() }
            }
            assertEquals(1,copyIds.size)
        }
    }

    @Test fun structuredPracticeDraftsRecoverOnlyChangedHandwrittenAnswers() {
        fun chapter(a: String, submitted: Boolean) = buildJsonObject {
            put("book_id","book"); put("toc_node_id","chapter")
            put("practice_draft_json",buildJsonObject {
                put("page",4); put("submitted",submitted)
                put("practiceTextDrafts",buildJsonObject { put("one",a); put("two","unchanged") })
            }.toString())
        }
        val prior = chapter("base",false)
        fun copies(kind: String, row: JsonObject, base: JsonObject?) =
            LearningDraftRecovery.copies(kind,"parent",1,"writer",row,base,null)
        assertTrue(copies("chapter_progress",chapter("base",true),prior).isEmpty())
        val chapterCopy = copies("chapter_progress",chapter("typed",false),prior).single()
        assertEquals(JsonPrimitive("one"),chapterCopy["slot"])
        assertEquals(JsonPrimitive("typed"),chapterCopy["text"])
        assertEquals(JsonPrimitive(4),chapterCopy["page"])
        val topic = buildJsonObject {
            put("topic_id","topic"); put("practice_draft_json","""{"text":"my answer","activePage":3}""")
        }
        val topicCopy = copies("course_topic",topic,null).single()
        assertEquals(JsonPrimitive("topic"),topicCopy["location"])
        assertEquals(JsonPrimitive("my answer"),topicCopy["text"])
        val choice = buildJsonObject { put("mode","CHOICE"); put("text","selected option") }
        assertTrue(copies("answer",choice,null).isEmpty())
    }
}
