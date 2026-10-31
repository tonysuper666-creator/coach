package com.coach.store

import kotlinx.serialization.json.JsonObject

/** Claim metadata includes both immutable input hashes and a restart-safe fencing token. */
data class GradeWork(val id: String, val kind: String, val subject: JsonObject, val fingerprint: String,
    val answerHash: String, val attempt: Int, val token: Fence? = null)
