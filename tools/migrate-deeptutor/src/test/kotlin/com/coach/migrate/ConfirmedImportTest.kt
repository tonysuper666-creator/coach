package com.coach.migrate

import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

class ConfirmedImportTest {
    private fun json(vararg pairs:Pair<String,Any?>):JsonObject {
        fun value(v:Any?):JsonElement=when(v) {
            null -> JsonNull
            is JsonElement -> v
            is Iterable<*> -> JsonArray(v.map(::value))
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
        return JsonObject(pairs.associate { (k,v) -> k to value(v) })
    }
    internal val seed=json("course" to json("id" to "course"),"knowledge_points" to listOf(
        json("id" to "k1","name" to "One","module_id" to "m","prerequisites" to emptyList<String>()),
        json("id" to "k2","name" to "Two","module_id" to "m","prerequisites" to emptyList<String>())
    ),"exam_weights" to json("k1" to 10,"k2" to 0))
    private fun source(id:String,raw:JsonObject)=json("id" to id,"source_record" to raw,"content_hash" to sourceContentHash(id,raw))
    internal fun mapping(stem:String="Synthetic choice"):JsonObject {
        val choice=json("question_type" to "choice","question" to stem,"options" to json("A" to "First","B" to "Second"),"correct_answer" to "B","explanation" to "B, not A.")
        val short=json("question_type" to "short_answer","question" to "Explain","explanation" to "Original points, no weights.")
        val qs=listOf(choice,short,choice).mapIndexed { i,raw ->
            JsonObject(source("legacy:q$i",raw)+("question" to mapChoiceQuestion(raw)))
        }
        return json("knowledge_points" to listOf(json("id" to "k1","name" to "One","module_id" to "m","content_hash" to "h1"),json("id" to "k2","name" to "Two","module_id" to "m","content_hash" to "h2")),
            "course_records" to listOf(json("source_record" to json("id" to "course"))),"exam_weights_set" to listOf(json("source_record" to json("points" to 10))),
            "questions" to qs,"legacy_attempts" to listOf("B or A", "c").mapIndexed { i,answer ->
                JsonObject(source("legacy:a$i",json("question_json" to canonical(choice),"result_json" to "{}","user_answer" to answer,"created_at" to 1700000000))+
                    mapOf("reset" to JsonPrimitive(false),"reset_reason" to JsonPrimitive("")))
            },"history_time" to json("snapshot_at" to 1700090000000L),"journal_days" to json(),
            "journal" to source("legacy:journal",json("records" to emptyList<String>())),
            "chat_archive" to json("messages" to emptyList<String>()),"legacy_attempt_groups" to emptyList<String>(),"excluded_path_archives" to emptyList<String>())
    }
    private fun proposal(mapping:JsonObject):JsonObject {
        val body=json("input_hash" to sha(canonical(proposalInput(mapping))),"proposer" to json("model" to "synthetic","vendor" to "a"),
            "concepts" to listOf(json("id" to "c1","name" to "First"),json("id" to "c2","name" to "Second")),
            "knowledge_points" to listOf(json("id" to "k1","concept_ids" to listOf("c1"),"prerequisite_ids" to emptyList<String>()),json("id" to "k2","concept_ids" to listOf("c2"),"prerequisite_ids" to listOf("k1"))),
            "questions" to (0..2).map { i -> json("id" to "legacy:q$i","kp_ids" to if(i==2) emptyList() else listOf("k${i+1}"),"reason" to "Synthetic evidence","status" to if(i==2) "unresolved" else "proposed","warning" to if(i==2) "No match" else "") })
        return JsonObject(body+("review" to json("model" to "synthetic-reviewer","vendor" to "b","status" to "reviewed","proposal_hash" to sha(canonical(body)))))
    }
    private fun decision(mapping:JsonObject,proposal:JsonObject)=json("schema_version" to 1,"decision_ref" to "synthetic-human-decision",
        "input_hash" to sha(canonical(proposalInput(mapping))),"proposal_hash" to sha(canonical(proposal)),
        "question_associations" to "accept_reviewed_proposal","curriculum" to "accept_reviewed_proposal",
        "ambiguous_answers" to "preserve_verbatim_unscored","choice_explanations" to "archive_only","passed_times" to "approximate",
        "grading" to "unreviewed_no_keys","short_answer_samples" to shortAnswerSamples(mapping))
    internal fun plan(m:JsonObject):JsonObject { val p=proposal(m);return importPlan(m,seed,p,decision(m,p)) }
    internal fun withDatabase(block:(Connection)->Unit) {
        val dir=Files.createTempDirectory("confirmed-import-test")
        try {
            LedgerStore(dir).use { store ->
                store.append("course","outline_revised",json("ops" to seed.array("knowledge_points").map { value ->
                    val k=value.jsonObject;json("op" to "add","kpId" to k.str("id"),"title" to k.str("name"),"conceptIds" to listOf(k.str("id")),"prereqs" to emptyList<String>())
                }))
                store.append("course","exam_weights_set",json("weights" to seed.obj("exam_weights")))
            }
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db ->
                db.exec("PRAGMA foreign_keys=ON")
                seed.array("knowledge_points").forEachIndexed { i,value -> val k=value.jsonObject
                    db.exec("INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,prereqs,retired) VALUES(?,'course','m',?,?, '[]',0)",k.str("id"),i,k.str("name"))
                }
                block(db)
            }
        } finally { dir.toFile().deleteRecursively() }
    }
    private fun assertAccepted(db:Connection) {
        assertEquals(listOf("legacy:q0","legacy:q1"),db.query("SELECT DISTINCT question_id FROM question_kp ORDER BY question_id").map { it.str("question_id") })
        assertTrue(db.query("SELECT * FROM questions").all { it.str("key_state")=="UNREVIEWED" })
        for(table in listOf("answer_keys","pool_details","constructed_details")) assertTrue(db.query("SELECT * FROM $table").isEmpty())
        val stored=db.query("SELECT payload_json FROM migration_records WHERE source_id='legacy:q1'").single()
        assertEquals(JsonPrimitive("Original points, no weights."),parseText(stored.str("payload_json")).jsonObject.obj("accepted_short_answer_material")["value"])
        assertEquals(listOf("B or A","c"),db.query("SELECT payload_json FROM migration_records WHERE kind='legacy_attempt' ORDER BY source_id").map { parseText(it.str("payload_json")).jsonObject.str("unresolved_answer_text") })
        assertTrue(db.query("SELECT * FROM events WHERE type='attempt_graded'").isEmpty())
        assertEquals(JsonArray(listOf(JsonPrimitive("k1"))),parseText(db.query("SELECT prereqs FROM outline_kp WHERE kp_id='k2'").single().str("prereqs")))
        val events=db.query("SELECT event_json FROM events ORDER BY seq").map { LedgerJson.decodeFromString<Event>(it.str("event_json")) }
        val accepted=events.single { it.payload.str("sourceId")=="confirmation:curriculum" }
        assertEquals(listOf(JsonPrimitive("k1")),accepted.payload.array("ops").single { it.jsonObject.str("kpId")=="k2" }.jsonObject.array("prereqs"))
        assertTrue(project(events).unsupportedTypes.isEmpty())
        assertTrue(project(events).mastery.values.all { it.window.isEmpty() && it.support.isEmpty() && !it.solid })
    }
    @Test fun `confirmed first import preserves unknown and old answers without enabling grading`() = withDatabase { db ->
        val m=mapping();val p=plan(m)
        writeHistory(db,p,seed,m);assertAccepted(db)
        val first=databaseState(db,"course")
        val repeat=writeHistory(db,p,seed,m)
        assertEquals(JsonPrimitive(0),repeat["records_revised"]);assertEquals(JsonPrimitive(0),repeat["records_inserted"]);assertEquals(JsonPrimitive(0),repeat["events_added"])
        assertEquals(first,databaseState(db,"course"))
    }
    @Test fun `decision can supplement an existing unconfirmed import and revise its source safely`() = withDatabase { db ->
        val m=mapping()
        writeHistory(db,importPlan(m,seed,proposal(m)),seed,m)
        assertEquals(JsonPrimitive(3),writeHistory(db,plan(m),seed,m)["records_revised"]);assertAccepted(db)
        val changed=mapping("Changed synthetic stem")
        writeHistory(db,plan(changed),seed,changed);assertAccepted(db)
        assertEquals("Changed synthetic stem",db.query("SELECT stem_md FROM questions WHERE question_id='legacy:q0'").single().str("stem_md"))
        val state=databaseState(db,"course");assertEquals(JsonPrimitive(0),writeHistory(db,plan(changed),seed,changed)["events_added"])
        assertEquals(state,databaseState(db,"course"))
    }
    @Test fun `stale or tampered decision cannot transfer approval or create a grading key`() {
        val m=mapping();val p=proposal(m);val d=decision(m,p)
        assertFailsWith<IllegalArgumentException> { importPlan(mapping("Changed"),seed,proposal(mapping("Changed")),d) }
        for((key,value) in listOf("proposal_hash" to JsonPrimitive("changed"),"short_answer_samples" to JsonArray(emptyList()),"grading" to JsonPrimitive("verified"))) {
            assertFailsWith<IllegalArgumentException> { importPlan(m,seed,p,JsonObject(d+(key to value))) }
        }
        withDatabase { db ->
            val forged=importPlan(m,seed,p)
            val rows=forged.array("records").map { v -> val row=v.jsonObject
                if(row.str("id")=="legacy:q0") plannedRecord(json("id" to row.str("id"),"content_hash" to row.str("source_hash")),row.str("kind"),JsonObject(row.obj("payload")+("kp_ids" to JsonArray(listOf(JsonPrimitive("k1")))))) else row
            }
            assertFailsWith<IllegalArgumentException> { writeHistory(db,JsonObject(forged+("records" to JsonArray(rows))),seed,m) }
        }
    }
    @Test fun `external associations or promoted keys stop a revised import transaction`() {
        for(promote in listOf(false,true)) withDatabase { db ->
            val m=mapping();writeHistory(db,plan(m),seed,m)
            if(promote) db.exec("UPDATE questions SET key_state='VERIFIED' WHERE question_id='legacy:q0'")
            else db.exec("UPDATE question_kp SET is_primary=0 WHERE question_id='legacy:q0'")
            val before=databaseState(db,"course");val changed=mapping("New content")
            assertFailsWith<IllegalArgumentException> { writeHistory(db,plan(changed),seed,changed) }
            assertEquals(before,databaseState(db,"course"))
        }
    }
    @Test fun `missing confirmation cannot silently undo an accepted import`() = withDatabase { db ->
        val m=mapping();writeHistory(db,plan(m),seed,m);val before=databaseState(db,"course")
        val changed=mapping("New content")
        assertFailsWith<IllegalArgumentException> { writeHistory(db,importPlan(changed,seed,null),seed,changed) }
        assertEquals(before,databaseState(db,"course"))
    }
    @Test fun `external curriculum changes are preserved when acceptance is first applied`() = withDatabase { db ->
        val m=mapping();db.exec("UPDATE outline_kp SET prereqs='[\"k2\"]' WHERE kp_id='k1'")
        val before=databaseState(db,"course")
        assertFailsWith<IllegalArgumentException> { writeHistory(db,plan(m),seed,m) }
        assertEquals(before,databaseState(db,"course"))
    }
}
