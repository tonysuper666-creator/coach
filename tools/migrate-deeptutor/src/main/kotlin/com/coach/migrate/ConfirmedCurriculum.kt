package com.coach.migrate

import com.coach.domain.Event
import com.coach.domain.LedgerJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.sql.Connection

/** A present-day acceptance of the reviewed outline, distinct from a historical source
 * correction. Later learner outline edits retain their ledger order and are never overwritten.
 */
internal fun writeConfirmedCurriculum(db:Connection,plan:JsonObject,seed:JsonObject,now:Long,snapshotAt:Long):Int {
    val course=plan.str("course_id")
    val events=db.query("SELECT event_json FROM events WHERE course_id=? AND type='outline_revised' ORDER BY seq",course)
        .map { LedgerJson.decodeFromString<Event>(it.str("event_json")) }
    val previous=events.lastOrNull { it.deviceId=="deeptutor-migration" && it.payload.str("sourceId")=="confirmation:curriculum" }
    val curriculum=confirmedCurriculum(plan)
    require(curriculum!=null || previous==null) { "Previously accepted curriculum requires an explicit current confirmation" }
    if(curriculum==null) return 0
    // Re-importing the accepted decision is inert, including after a learner edits the outline.
    if(previous?.payload?.get("curriculum")==curriculum) return 0
    val concepts=mutableMapOf<String,JsonArray>();val prereqs=mutableMapOf<String,JsonArray>()
    for(event in events) {
        for(value in event.payload.array("knowledgePoints")) {
            val kp=value.jsonObject;val id=kp.str("id")
            (kp["conceptIds"] as? JsonArray)?.let { concepts[id]=it }
            for(key in listOf("prereqs","prerequisites","prerequisiteIds")) (kp[key] as? JsonArray)?.let { prereqs[id]=it }
        }
        for(value in event.payload.array("ops")) {
            val op=value.jsonObject;val id=op.str("kpId").ifBlank { op.str("id") }
            (op["conceptIds"] as? JsonArray)?.let { concepts[id]=it }
            (op["prereqs"] as? JsonArray)?.let { prereqs[id]=it }
        }
    }
    val expected=previous?.payload?.obj("curriculum")?.array("knowledge_points")?.map { it.jsonObject }?.associateBy { it.str("id") }
    for(value in seed.array("knowledge_points")) {
        val kp=value.jsonObject;val id=kp.str("id")
        val old=expected?.getValue(id)
        val expectedConcepts=JsonArray(old?.array("concept_ids") ?: listOf(JsonPrimitive(id)))
        val expectedPrereqs=JsonArray(old?.array("prerequisite_ids") ?: kp.array("prerequisites"))
        require(concepts[id]==expectedConcepts && prereqs[id]==expectedPrereqs) { "Cannot overwrite externally changed curriculum: $id" }
        val sql=db.query("SELECT prereqs FROM outline_kp WHERE course_id=? AND kp_id=?",course,id).single()
        require(parseText(sql.str("prereqs"))==expectedPrereqs) { "Outline SQL differs from its ledger: $id" }
    }
    val ops=JsonArray(curriculum.array("knowledge_points").map { value ->
        val kp=value.jsonObject
        db.exec("UPDATE outline_kp SET prereqs=? WHERE course_id=? AND kp_id=?",canonical(JsonArray(kp.array("prerequisite_ids"))),course,kp.str("id"))
        buildJsonObject { put("op","setPrereq");put("kpId",kp.str("id"));put("conceptIds",JsonArray(kp.array("concept_ids")));put("prereqs",JsonArray(kp.array("prerequisite_ids"))) }
    })
    val payload=buildJsonObject {
        put("sourceId","confirmation:curriculum");put("curriculum",curriculum);put("ops",ops)
        put("decision",plan.getValue("human_confirmation"))
    }
    val id="migration:curriculum:"+sha(course+canonical(payload))
    require(db.query("SELECT id FROM events WHERE id=?",id).isEmpty()) { "Cannot replay a superseded curriculum decision" }
    val seq=db.query("SELECT coalesce(max(seq),0)+1 AS n FROM events").single().getValue("n").jsonPrimitive.long
    val event=Event(seq,id,course,"outline_revised",recordedAt=now,occurredAt=snapshotAt,deviceId="deeptutor-migration",actor="SYSTEM",payload=payload)
    db.exec("INSERT INTO events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload_sha256,payload,event_json) VALUES(?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
        seq,id,course,event.type,1,now,snapshotAt,event.deviceId,event.actor,id,sha(canonical(payload)),canonical(payload),LedgerJson.encodeToString(event))
    return 1
}
