package com.coach.store

import com.coach.domain.Event
import com.coach.domain.Option
import kotlinx.serialization.json.JsonObject

/** Server-only content. Keys must never be serialized to a client response. */
data class VerifiedPoolQuestion(
    val stem: String, val options: List<Option>, val correctOptionId: String,
    val explanation: String, val sourceQuote: String, val generator: String, val reviewer: String,
)
data class SupplyJob(val id: String, val kind: String, val subject: JsonObject, val packHash: String)
data class PoolTake(val events: List<Event>, val status: String, val available: Int,
    val jobs: List<Pair<String, String>>, val message: String?)
