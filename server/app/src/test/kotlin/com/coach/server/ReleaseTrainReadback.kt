package com.coach.server

import com.coach.client.mistakeReview
import com.coach.domain.Event
import com.coach.domain.str
import com.coach.domain.LedgerJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Private stdin only. Run with the selected distribution's client jars, not a replica algorithm. */
fun main() {
    val input = LedgerJson.parseToJsonElement(System.`in`.bufferedReader().readText()).jsonObject
    val events = LedgerJson.decodeFromJsonElement(ListSerializer(Event.serializer()), input.getValue("events"))
    val review = mistakeReview(events)
    val expected = input.str("expectedMistake")
    if (expected.isNotBlank()) {
        val mistake = requireNotNull(review.items.singleOrNull { it.attemptId == expected }) { "Candidate mistake missing" }
        require(mistake.correctAnswer.isNotBlank() && mistake.reason.isNotBlank()) { "Candidate explanation missing" }
    }
    println(buildJsonObject { put("mistakes", review.items.size) })
}
