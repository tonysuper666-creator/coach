package com.coach.migrate

import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class HistoryWriterTest {
    private val seed=Json.parseToJsonElement("""{"course":{"id":"course"},"knowledge_points":[{"id":"kp","name":"Synthetic","module_id":"module"}],"exam_weights":{"kp":10}}""").jsonObject
    private val choice=Json.parseToJsonElement("""{"question_type":"mcq","question":"Synthetic choice","options":{"A":"first","B":"second","C":"third","D":"fourth","E":"fifth"},"correct_answer":"B","explanation":"B 正确，A、C、D、E 不对。"}""").jsonObject
    private fun fixture():Pair<JsonObject,JsonObject> {
        val raws=listOf(
            "legacy_question" to choice,
            "legacy_attempt" to json("created_at" to 1700000000,"user_answer" to "I think B"),
            "legacy_passed" to JsonPrimitive(1.0),
            "legacy_schedule" to json("kp_id" to "kp"),
            "notes" to json("created_at" to 1700000000,"output" to "historical note"),
            "legacy_message" to json("session_id" to "session","role" to "user","content" to "body only","created_at" to 1700000000),
            "legacy_event_archive" to json("type" to "attempt_graded","payload" to "never execute this old event"),
            "legacy_journal" to json("text" to "old journal")
        )
        val sources=raws.map { (kind,raw) -> json("id" to "legacy:$kind","source_record" to raw,"content_hash" to sha(canonical(raw))) }
        val payloads=mapOf(
            "legacy_question" to json("question" to mapChoiceQuestion(choice),"kp_ids" to emptyList<String>(),"association_confirmed" to false,"keyState" to "UNREVIEWED"),
            "legacy_attempt" to json("created_at" to 1700000000,"origin" to "LEGACY","kp_id" to "kp","question" to mapChoiceQuestion(choice),"answer" to null,"answer_unresolved" to true,"unresolved_answer_text" to "I think B","reset" to true,"reset_reason" to "old reset","window_eligible" to false),
            "legacy_passed" to json("kp_id" to "kp","passed" to true,"solid" to false),
            "legacy_schedule" to json("kp_id" to "kp","dueAt" to 1700086400,"queue_eligible" to true,"original_state" to json("lapse_count" to 2,"stability" to 3.5)),
            "legacy_journal" to json("day" to "2023-11-14","entry" to raws.last().second)
        )
        val records=raws.zip(sources).map { (pair,source) -> plannedRecord(source,pair.first,payloads[pair.first] ?: pair.second.jsonObject) }
        val plan=json("course_id" to "course","records" to JsonArray(records),"unknown_types" to JsonArray(listOf(json("id" to "kp","type" to null,"current_source_type" to "memory"))),"human_confirmation" to "pending")
        return plan to json("sources" to JsonArray(sources))
    }
    private fun withDatabase(setup:(LedgerStore)->Unit={},block:(java.sql.Connection)->Unit) {
        val dir=Files.createTempDirectory("history-writer-test")
        try {
            LedgerStore(dir).use { store ->
                store.append("course","outline_revised",json("ops" to JsonArray(listOf(json("op" to "add","kpId" to "kp","title" to "Synthetic")))))
                store.append("course","exam_weights_set",json("weights" to json("kp" to 10)))
                setup(store)
            }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
                db.exec("PRAGMA foreign_keys=ON")
                db.exec("INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired) VALUES('kp','course','module',0,'Synthetic',NULL,'[]','[]','[]',0)")
                block(db)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `real database repeat is inert and legacy content stays quarantined`() = withDatabase { db ->
        val (plan,mapping)=fixture()
        val before=databaseState(db,"course")
        assertTrue(writeHistory(db,plan,seed,mapping).getValue("events_added").jsonPrimitive.int>0)
        val first=databaseState(db,"course")
        val again=writeHistory(db,plan,seed,mapping)
        assertEquals(JsonPrimitive(0),again["events_added"]);assertEquals(JsonPrimitive(0),again["records_inserted"])
        assertEquals(first,databaseState(db,"course"))
        assertNotEquals(before,first)
        assertTrue(db.query("SELECT * FROM question_kp").isEmpty());assertTrue(db.query("SELECT * FROM answer_keys").isEmpty())
        val question=db.query("SELECT * FROM questions").single()
        assertEquals("UNREVIEWED",question.str("key_state"));assertEquals(5,parseText(question.str("options")).jsonArray.size)
        assertEquals("body only",db.query("SELECT content_md FROM messages").single().str("content_md"))
        assertEquals(JsonPrimitive(1.0),parseText(db.query("SELECT source_json FROM migration_records WHERE kind='legacy_passed'").single().str("source_json")))
        val projection=first.obj("projection")
        assertTrue(projection.obj("position").obj("pending").isEmpty())
        val mastery=projection.obj("mastery").obj("kp")
        assertEquals(JsonPrimitive(true),mastery["passed"]);assertEquals(JsonPrimitive(false),mastery["solid"])
        assertTrue(mastery.array("window").isEmpty());assertTrue(mastery.array("support").isEmpty())
        assertEquals(JsonPrimitive(2),projection.obj("review").obj("kp")["lapses"])
        assertTrue(db.query("SELECT * FROM events WHERE type='attempt_graded'").isEmpty())
        assertEquals(1,projection.obj("journal").values.sumOf { it.jsonObject.getValue("notes").jsonPrimitive.int })
        assertEquals(1,projection.obj("journal").values.sumOf { it.jsonObject.array("legacyLogs").size })
        val oldDay=logicalDay(1700000000000,"Europe/London").toString()
        assertEquals(JsonPrimitive(1),projection.obj("journal").obj(oldDay)["answers"])
        // A subsequently reviewed question must still offer the learner a native first answer.
        val imported=db.query("SELECT event_json FROM events ORDER BY seq").map {
            LedgerJson.decodeFromString<Event>(it.str("event_json"))
        }
        val now=System.currentTimeMillis()
        val native=listOf(
            "question_posed" to json("attemptId" to "native-first","questionId" to question.str("question_id"),"kpIds" to listOf("kp"),
                "fingerprint" to mapChoiceQuestion(choice).str("fingerprint"),"format" to "SINGLE_CHOICE","origin" to "GENERATED","purpose" to "PRACTICE","keyState" to "VERIFIED","posedAt" to now),
            "answer_submitted" to json("attemptId" to "native-first","answer" to choiceAnswer(choice,"B")),
            "attempt_graded" to json("attemptId" to "native-first","score" to 100,"max" to 100,"grader" to "EXACT")
        ).mapIndexed { i,(type,payload) -> Event(imported.last().seq+i+1,"native-$i","course",type,recordedAt=now,occurredAt=now,deviceId="native",actor="LEARNER",payload=payload) }
        assertEquals(listOf("native-first"),project(imported+native).mastery.getValue("kp").window)
    }

    @Test fun `existing Coach review and pending answer survive migration`() = withDatabase(setup={ store ->
        store.append("course","legacy_passed",json("kpId" to "kp"))
        store.append("course","legacy_schedule",json("kpId" to "kp","dueDay" to 21000,"interval" to 7,"lapses" to 1))
        store.append("course","question_posed",json("attemptId" to "native-pending","questionId" to "native-question","kpIds" to listOf("kp"),"fingerprint" to "native-fingerprint"))
    }) { db ->
        val before=databaseState(db,"course").obj("projection")
        val (plan,mapping)=fixture();writeHistory(db,plan,seed,mapping)
        val after=databaseState(db,"course").obj("projection")
        assertEquals(before.obj("review"),after.obj("review"))
        assertEquals(before.obj("position").obj("pending"),after.obj("position").obj("pending"))
        assertEquals(1,db.query("SELECT * FROM events WHERE type='legacy_schedule'").size)
    }

    @Test fun `source conflict rolls back earlier new rows and mismatched seed cannot reseed`() = withDatabase { db ->
        val (plan,mapping)=fixture();writeHistory(db,plan,seed,mapping)
        val before=databaseState(db,"course")
        val fresh=json("id" to "legacy:000-fresh","content_hash" to "fresh","source_record" to json("output" to "new note"))
        val altered=plan.array("records").map { v -> val row=v.jsonObject
            if(row.str("kind")=="legacy_schedule") JsonObject(row+mapOf("source_hash" to JsonPrimitive("changed"))) else row
        }+plannedRecord(fresh,"notes",fresh.obj("source_record"))
        assertFailsWith<IllegalArgumentException> {
            writeHistory(db,JsonObject(plan+mapOf("records" to JsonArray(altered))),seed,JsonObject(mapping+mapOf("extra" to fresh)))
        }
        assertEquals(before,databaseState(db,"course"))
        assertFailsWith<IllegalArgumentException> { writeHistory(db,plan,JsonObject(seed+mapOf("exam_weights" to json("kp" to 9))),mapping) }
        assertEquals(before,databaseState(db,"course"))
    }

    @Test fun `five options retain their key and explanations lose display letter references`() {
        val q=mapChoiceQuestion(choice)
        assertEquals(choiceAnswer(choice,"B"),q["correctOptionId"])
        assertEquals(5,q.array("options").size);assertEquals("UNREVIEWED",q.str("keyState"))
        assertFalse(Regex("[A-E]").containsMatchIn(q.str("explanation")))
        assertTrue(q.str("explanation").contains("「second」"));assertFalse(q.containsKey("importIssue"))
        assertEquals(JsonNull,choiceAnswer(choice,"c"));assertEquals(JsonNull,choiceAnswer(choice,"I think B"))
    }

    @Test fun `read only online backup includes committed WAL and restores normalized state`() {
        val root=Files.createTempDirectory("history-backup-test")
        try {
            val source=root.resolve("source.sqlite");val copy=root.resolve("copy.sqlite")
            DriverManager.getConnection("jdbc:sqlite:$source").use { db ->
                db.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                db.exec("CREATE TABLE facts(id TEXT PRIMARY KEY)");db.exec("INSERT INTO facts VALUES('committed-in-wal')")
                backupDatabase(source,copy)
                openReadOnly(copy).use { assertEquals(db.query("SELECT * FROM facts"),it.query("SELECT * FROM facts")) }
                openReadOnly(source).use { assertFails { it.exec("INSERT INTO facts VALUES('forbidden')") } }
                assertFails { backupDatabase(source,copy) }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
