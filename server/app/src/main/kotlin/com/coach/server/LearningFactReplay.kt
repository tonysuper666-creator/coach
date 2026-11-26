package com.coach.server

import com.coach.domain.fsrs.Fsrs5
import com.coach.domain.fsrs.FsrsRating
import com.coach.domain.fsrs.FsrsState
import com.coach.domain.mastery.ConceptMasteryRecord
import com.coach.domain.mastery.LearnerSignalType
import com.coach.domain.mastery.MasteryReplay
import com.coach.domain.mastery.MisconceptionErrorClass
import kotlinx.serialization.json.*
import java.sql.Connection

/** Events arriving out of order are folded against the same immutable, pre-review state. */
internal object LearningFactReplay {
    fun install(db: Connection) = db.createStatement().use { sql ->
        sql.execute("""CREATE TABLE IF NOT EXISTS sync_fact_bases(kind TEXT,id TEXT,round INTEGER,body TEXT,
            PRIMARY KEY(kind,id,round))""")
        sql.execute("""CREATE TABLE IF NOT EXISTS sync_facts(kind TEXT,id TEXT,round INTEGER,event TEXT,
            day INTEGER NOT NULL,writer TEXT NOT NULL,fact TEXT NOT NULL,updated_at INTEGER NOT NULL,
            PRIMARY KEY(kind,id,round,event))""")
        sql.execute("""CREATE INDEX IF NOT EXISTS sync_facts_order ON sync_facts(kind,id,round,day,writer)""")
    }

    fun apply(db: Connection, kind: String, id: String, round: Long, writer: String, base: JsonObject?,
              row: JsonObject, updatedAt: Long, fact: JsonObject): JsonObject {
        val card = kind == "card"
        require(card || kind == "mastery")
        require(fact.string("algorithm") == if (card) "fsrs5-default-v1" else "mastery-v1")
        val day = if (card) fact.number("day") else Math.floorDiv(fact.number("time"),86_400_000L)
        val event = if (card) writer else fact.string("event")
        db.prepareStatement("INSERT OR IGNORE INTO sync_fact_bases VALUES(?,?,?,?)").use { q ->
            q.setString(1,kind); q.setString(2,id); q.setLong(3,round); q.setString(4,base?.toString())
            q.executeUpdate()
        }
        db.prepareStatement("INSERT OR IGNORE INTO sync_facts VALUES(?,?,?,?,?,?,?,?)").use { q ->
            q.setString(1,kind); q.setString(2,id); q.setLong(3,round); q.setString(4,event)
            q.setLong(5,day); q.setString(6,writer); q.setString(7,fact.toString()); q.setLong(8,updatedAt)
            q.executeUpdate()
        }
        val initial = db.prepareStatement("SELECT body FROM sync_fact_bases WHERE kind=? AND id=? AND round=?").use { q ->
            q.setString(1,kind); q.setString(2,id); q.setLong(3,round)
            q.executeQuery().use { r -> check(r.next()); r.getString(1)?.let { Json.parseToJsonElement(it).jsonObject } }
        }
        var fsrs = if (initial != null && card) FsrsState(initial.number("due_day"),initial.decimal("stability"),
            initial.decimal("difficulty"),initial["last_review_day"]?.jsonPrimitive?.longOrNull,
            initial.number("reps").toInt(),initial.number("lapses").toInt()) else Fsrs5.newCard(day)
        var mastery = if (card) null else ConceptMasteryRecord(
            conceptKey=row.string("concept_key"),bookId=row.string("book_id"),tocNodeId=row.string("toc_node_id"),
            canonicalName=row.string("canonical_name"),pMastery=initial?.decimal("p_mastery") ?: MasteryReplay.DEFAULT_P_MASTERY,
            hasActiveTest=initial?.number("has_active_test") == 1L,fsrsStability=initial?.decimal("fsrs_stability") ?: 1.0,
            dominantErrorTag=initial?.get("dominant_error_tag")?.jsonPrimitive?.contentOrNull,
            rereadCount=initial?.number("reread_count")?.toInt() ?: 0, dwellMsTotal=initial?.number("dwell_ms_total") ?: 0,
            askCount=initial?.number("ask_count")?.toInt() ?: 0, highlightCount=initial?.number("highlight_count")?.toInt() ?: 0,
        )
        var updated = initial?.number("updated_at") ?: 0L
        db.prepareStatement("""SELECT day,fact,updated_at FROM sync_facts WHERE kind=? AND id=? AND round=?
            ORDER BY day,writer,event""").use { q ->
            q.setString(1,kind); q.setString(2,id); q.setLong(3,round)
            q.executeQuery().use { r ->
                while (r.next()) {
                    val input = Json.parseToJsonElement(r.getString(2)).jsonObject
                    if (card) {
                        fsrs = Fsrs5.review(fsrs,FsrsRating.of(input.number("rating").toInt()),r.getLong(1))
                    } else {
                        mastery = MasteryReplay.applySignal(checkNotNull(mastery),LearnerSignalType.valueOf(input.string("signal")),
                            input.decimal("score"),input.getValue("guessed").jsonPrimitive.boolean,
                            input["error"]?.let { MisconceptionErrorClass.valueOf(it.jsonPrimitive.content) },
                            input.number("duration"),input.decimal("stability"),input.number("time"))
                    }
                    updated = maxOf(updated,r.getLong(3))
                }
            }
        }
        return buildJsonObject {
            row.forEach { (k,v) -> put(k,v) }
            put("updated_at",updated)
            if (card) {
                put("due_day",fsrs.dueDay); put("last_review_day",fsrs.lastReviewDay)
                put("stability",fsrs.stability); put("difficulty",fsrs.difficulty)
                put("reps",fsrs.reps); put("lapses",fsrs.lapses)
            } else {
                val state = checkNotNull(mastery)
                put("p_mastery",state.pMastery); put("has_active_test",if (state.hasActiveTest) 1 else 0)
                put("fsrs_stability",state.fsrsStability); put("dominant_error_tag",state.dominantErrorTag)
                put("reread_count",state.rereadCount); put("dwell_ms_total",state.dwellMsTotal)
                put("ask_count",state.askCount); put("highlight_count",state.highlightCount)
            }
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.decimal(key: String) = get(key)?.jsonPrimitive?.doubleOrNull
}
