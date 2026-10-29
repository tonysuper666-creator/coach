package com.coach.ai

import com.coach.domain.Option
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Replay TON-1041's private planted corpus and recorded independent verdicts through the new HTTP contract. */
fun main() {
    val corpus=Path.of(System.getenv("COACH_CORPUS_DIR") ?: "${System.getProperty("user.home")}/Coach/corpus/gradegen")
    val input=JsonText.objectOf(Files.readString(corpus.resolve("inputs/planted.json")))
    val previous=JsonText.objectOf(Files.readString(corpus.resolve("runs/planted.json")))
    val items=input.getValue("items").jsonArray
    val rows=previous.getValue("rows").jsonArray
    require(items.size == rows.size)
    val questions=items.mapIndexed { i,value ->
        val q=value.jsonObject;val raw=q.getValue("options").jsonObject
        val options=raw.entries.mapIndexed { j,entry -> Option("replay-$i-$j",entry.value.jsonPrimitive.content) }
        val key=raw.keys.indexOf(q.getValue("key").jsonPrimitive.content)
        Mcq(q.getValue("stem").jsonPrimitive.content,options,options[key].optionId,"","")
    }
    val reply=buildJsonArray {
        rows.forEachIndexed { i,value ->
            val row=value.jsonObject
            val answer=row.getValue("reviewer_answer").jsonPrimitive.content
            val text=items[i].jsonObject.getValue("options").jsonObject[answer]?.jsonPrimitive?.content ?: "NONE"
            add(buildJsonObject {
                put("id",i);put("answer_text",text);put("defects",row.getValue("defects"));put("note","")
            })
        }
    }
    StubBridge().use { bridge ->
        bridge.enqueue(200,StubBridge.completion(reply.toString()))
        val pool=QuestionPool(BridgeClient(stubConfig(bridge)))
        val review=pool.reviewGroup(listOf(QuestionPool.ReviewEntry("replay",input.getValue("excerpt").jsonPrimitive.content,questions,0)))
        var caught=0;var total=0;var cleanFlagged=0;var cleanTotal=0
        questions.forEachIndexed { i,question ->
            val row=rows[i].jsonObject
            val verdict=review.verdicts[i]
            // Original planted experiment tested the blind-review gate alone, not source-quote generation.
            val flagged=verdict == null || verdict.answer != question.correctOptionId || verdict.defects.isNotEmpty()
            check(flagged == row.getValue("flagged").jsonPrimitive.boolean) { "Planted conclusion changed at row $i" }
            if(row.getValue("plant").jsonPrimitive.content == "clean") { cleanTotal++;if(flagged) cleanFlagged++ }
            else { total++;if(flagged) caught++ }
        }
        check(caught == 5 && total == 5 && cleanFlagged == 0 && cleanTotal == 1)
        println("TON1041_REPLAY plantedCaught=$caught/$total cleanFlagged=$cleanFlagged/$cleanTotal conclusionsUnchanged=true paidCalls=0 stubHttpRequests=${bridge.requests.size}")
    }
}
