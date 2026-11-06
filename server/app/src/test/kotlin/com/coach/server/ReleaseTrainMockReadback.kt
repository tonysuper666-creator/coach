package com.coach.server

import com.coach.client.CourseHttp
import com.coach.domain.LedgerJson
import com.coach.domain.str
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Candidate HTTP client against the actual rollback binary. Credentials only arrive on private stdin. */
fun main() = runBlocking {
    val input = LedgerJson.parseToJsonElement(System.`in`.bufferedReader().readText()).jsonObject
    val http = CourseHttp(input.str("baseUrl"), "desktop", input.str("token"), input.str("course"))
    try {
        val reply = withTimeout(5000) { http.mocks() }
        require(reply.body == null && reply.error?.contains("404") == true) { "Missing mocks route did not produce a client error" }
        println("{\"status\":404,\"clientError\":true,\"completed\":true}")
    } finally { http.close() }
}
