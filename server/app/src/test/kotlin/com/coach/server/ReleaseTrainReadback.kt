package com.coach.server

import com.coach.client.CourseView
import com.coach.domain.Event
import com.coach.domain.str
import com.coach.domain.LedgerJson
import com.coach.domain.project
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Private stdin only. Run with the selected distribution's client jars, not a replica algorithm. */
fun main() {
    val input = LedgerJson.parseToJsonElement(System.`in`.bufferedReader().readText()).jsonObject
    val events = LedgerJson.decodeFromJsonElement(ListSerializer(Event.serializer()), input.getValue("events"))
    val view = CourseView(events, project(events))
    val cards = events.mapNotNull { it.threadId }.distinct().flatMap { view.quizGroups(it) }.flatMap { it.cards }
    val expected = input.str("expectedMistake")
    val kp = input.str("routingKp")
    val misroutes = if (kp.isBlank()) 0 else {
        val selected = view.tutorThreadId(kp)
        val learning = input.str("learningThread")
        val review = input.str("reviewThread")
        if (input["expectLegacyMisroute"]?.jsonPrimitive?.booleanOrNull == true) {
            require(selected == review && selected != learning) { "Legacy routing limitation changed; reassess rollback contract" }
            1
        } else {
            require(selected == learning && selected != review) { "Continue learning selected the review thread" }
            0
        }
    }
    if (expected.isNotBlank()) {
        val card = requireNotNull(cards.singleOrNull { it.attemptId == expected }) { "Candidate card missing" }
        require(card.answer != null && card.grade?.correct == false) { "Candidate wrong answer missing" }
        val retry = input.getValue("retryAttempts").jsonArray.map { it.jsonPrimitive.content }.toSet()
        require(cards.filter { it.attemptId in retry }.size == 3 && cards.filter { it.attemptId in retry }.all { it.answer == null })
    }
    println(buildJsonObject {
        put("wrongCards", cards.count { it.grade?.correct == false })
        put("continueLearningMisroutes", misroutes)
        put("routingChecked", kp.isNotBlank())
    })
}
