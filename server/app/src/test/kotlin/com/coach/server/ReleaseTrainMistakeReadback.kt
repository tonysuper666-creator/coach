package com.coach.server

import com.coach.client.mistakeReview
import com.coach.domain.Event
import com.coach.domain.LedgerJson
import com.coach.domain.str
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** The train client projects responses returned by the old compatible server. */
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
