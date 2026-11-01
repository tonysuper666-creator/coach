package com.coach.migrate

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class ImportPlanTest {
    private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
    private fun mapping()=obj("""{
      "knowledge_points":[{"id":"k","name":"Synthetic","module_id":"m","content_hash":"kp-hash","type":null,"current_source_type":"memory","original_type_available":false}],
      "course_records":[{"source_record":{"id":"course"}}],
      "questions":[{"id":"legacy:bank:questions:q","content_hash":"q-hash","source_record":{"question":"Synthetic question"},"question":{"question":"Synthetic question"}}],
      "exam_weights_set":[{"source_record":{"points":10}}],
      "legacy_attempts":[],"legacy_passed":[],"legacy_schedule":[],"notes":[],"events_archive":[],
      "journal_days":{},"journal":{"id":"legacy:journal:root","content_hash":"j-hash","source_record":{"records":[]}},
      "chat_archive":{"messages":[]},"legacy_attempt_groups":[],"excluded_path_archives":[]
    }""")
    private val seed=obj("""{"course":{"id":"course"},"knowledge_points":[{"id":"k","name":"Synthetic","module_id":"m"}],"exam_weights":{"k":10}}""")
    private fun proposal(m:JsonObject):JsonObject {
        val body=buildJsonObject {
            put("input_hash",sha(canonical(proposalInput(m))))
            put("proposer",obj("""{"model":"synthetic-proposer","vendor":"vendor-a"}"""))
            put("concepts",JsonArray(listOf(obj("""{"id":"concept","name":"Synthetic concept"}"""))))
            put("knowledge_points",JsonArray(listOf(obj("""{"id":"k","concept_ids":["concept"],"prerequisite_ids":[]}"""))))
            put("questions",JsonArray(listOf(obj("""{"id":"legacy:bank:questions:q","kp_ids":["k"],"reason":"Tests the synthetic concept"}"""))))
        }
        return reviewed(body)
    }
    private fun reviewed(body:JsonObject)=JsonObject(body+mapOf("review" to buildJsonObject {
        put("model","synthetic-reviewer");put("vendor","vendor-b");put("status","reviewed");put("proposal_hash",sha(canonical(body)))
    }))

    @Test fun `rerunning preserves progress and a source revision replaces one fact`() {
        val m=mapping();val plan=importPlan(m,seed,proposal(m))
        val first=simulatePlan(plan);val again=simulatePlan(plan,first.state)
        assertEquals(first.state,again.state);assertEquals(0,again.inserted);assertEquals(0,again.revised)
        val changed=JsonObject(plan+mapOf("records" to JsonArray(plan.array("records").map { v ->
            val row=v.jsonObject
            if(row.str("kind")=="legacy_question") JsonObject(row+mapOf("source_hash" to JsonPrimitive("new source revision"))) else row
        })))
        val revision=simulatePlan(changed,first.state)
        assertEquals(0,revision.inserted);assertEquals(1,revision.revised);assertEquals(first.state.size,revision.state.size)
        assertEquals("reference_existing_only",plan.str("content_action"))
        assertEquals("reuse_existing_event",plan.str("exam_weights_action"))
        val q=plan.array("records").map { it.jsonObject }.single { it.str("kind")=="legacy_question" }.obj("payload")
        assertTrue(q.array("kp_ids").isEmpty());assertEquals("UNREVIEWED",q.str("keyState"))
        assertEquals(m.array("knowledge_points"),plan.array("unknown_types"))
    }

    @Test fun `changed source or edited proposal cannot inherit an earlier review`() {
        val m=mapping();val p=proposal(m)
        validateProposal(m,p)
        assertFailsWith<IllegalArgumentException> { validateProposal(m,JsonObject(p+mapOf("input_hash" to JsonPrimitive("stale")))) }
        val edited=JsonObject(p+mapOf("concepts" to JsonArray(emptyList())))
        assertFailsWith<IllegalArgumentException> { validateProposal(m,edited) }
        val body=JsonObject(p.filterKeys { it!="review" }+mapOf("knowledge_points" to JsonArray(listOf(obj("""{"id":"k","concept_ids":["concept"],"prerequisite_ids":["k"]}""")))))
        assertFailsWith<IllegalArgumentException> { validateProposal(m,reviewed(body)) }
    }

    @Test fun `existing course mismatch stops a history plan rather than reseeding content`() {
        assertFailsWith<IllegalArgumentException> { importPlan(mapping(),JsonObject(seed+mapOf("exam_weights" to obj("""{"k":9}"""))),null) }
        assertFailsWith<IllegalArgumentException> { importPlan(mapping(),JsonObject(seed+mapOf("knowledge_points" to JsonArray(emptyList()))),null) }
    }

    @Test fun `incremental bank changes archive old review without reusing its authority`() {
        val original=mapping();val p=proposal(original)
        val changed=JsonObject(original+("questions" to JsonArray(original.array("questions").map { v -> JsonObject(v.jsonObject+("content_hash" to JsonPrimitive("changed"))) })))
        assertFailsWith<IllegalArgumentException> { importPlan(changed,seed,p) }
        val plan=incrementalPlan(changed,seed,p,original)
        assertEquals("stale_quarantined",plan.obj("proposal_review").str("status"));assertEquals(p,plan.obj("proposal_review").obj("archived_proposal"))
        assertTrue(plan.array("records").map { it.jsonObject }.filter { it.str("kind")=="legacy_question" }.all { it.obj("payload").array("kp_ids").isEmpty() })
        val edited=JsonObject(p+("concepts" to JsonArray(emptyList())))
        assertFailsWith<IllegalArgumentException> { incrementalPlan(changed,seed,edited,original) }
    }

    @Test fun `array choices and historical answers become option identities`() {
        val q=obj("""{"question_type":"choice","prompt":"Synthetic","expected_answer":"B","options":[{"label":"A","body":"Wrong"},{"label":"B","body":"Right"}]}""")
        val mapped=mapChoiceQuestion(q)
        assertEquals(choiceAnswer(q," B "),mapped["correctOptionId"])
        assertFalse(mapped.containsKey("expected_answer"));assertFalse(canonical(mapped).contains("\"label\""))
        assertEquals(JsonNull,choiceAnswer(q,"Unknown"))
        val a=record("mastery.sqlite3","mastery_interactions","a",obj("""{"question_json":${JsonPrimitive(q.toString())},"user_answer":"B","result_json":"{}"}"""))
        val row=JsonObject(a+mapOf("reset" to JsonPrimitive(false)))
        val m=JsonObject(mapping()+mapOf("legacy_attempts" to JsonArray(listOf(row))))
        val payload=importPlan(m,seed,null).array("records").map { it.jsonObject }.single { it.str("kind")=="legacy_attempt" }.obj("payload")
        assertEquals(JsonPrimitive(false),payload["window_eligible"]);assertEquals("LEGACY",payload.str("origin"))
        assertEquals(mapped["correctOptionId"],payload["answer"])
        val proseRow=JsonObject(row+mapOf("source_record" to JsonObject(row.obj("source_record")+mapOf("user_answer" to JsonPrimitive("I think B, please explain")))))
        val proseMapping=JsonObject(m+mapOf("legacy_attempts" to JsonArray(listOf(proseRow))))
        val unresolved=importPlan(proseMapping,seed,null).array("records").map { it.jsonObject }.single { it.str("kind")=="legacy_attempt" }.obj("payload")
        assertEquals(JsonNull,unresolved["answer"]);assertEquals("I think B, please explain",unresolved.str("unresolved_answer_text"))
    }

    @Test fun `curriculum gap stays explicit rather than forcing a question into the wrong KP`() {
        val m=mapping();val p=proposal(m)
        val body=JsonObject(p.filterKeys { it!="review" }+mapOf("questions" to JsonArray(listOf(obj("""{"id":"legacy:bank:questions:q","kp_ids":[],"reason":"No supported curriculum objective","status":"unresolved","warning":"Requires curriculum confirmation"}""")))))
        val plan=importPlan(m,seed,reviewed(body))
        assertEquals(JsonPrimitive(1),plan.obj("counts")["questions_without_proposals"])
        assertEquals(JsonPrimitive(0),plan.obj("counts")["confirmed_question_associations"])
        assertTrue(confirmationSummary(m,reviewed(body)).contains("1 道待解决"))
    }
}
