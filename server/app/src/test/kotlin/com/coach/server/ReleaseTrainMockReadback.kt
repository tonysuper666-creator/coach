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
        // A rollback build that already serves mock exams must answer; an older one must fail as a client-visible 404.
        if (input["expectSupported"]?.jsonPrimitive?.booleanOrNull == true) {
            require(reply.body != null && reply.error == null) { "Rollback mocks route did not answer" }
            println("{\"status\":200,\"clientError\":false,\"completed\":true}")
        } else {
            require(reply.body == null && reply.error?.contains("404") == true) { "Missing mocks route did not produce a client error" }
            println("{\"status\":404,\"clientError\":true,\"completed\":true}")
        }
    } finally { http.close() }
}
