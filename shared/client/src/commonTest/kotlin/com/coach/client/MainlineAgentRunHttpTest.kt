package com.coach.client

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MainlineAgentRunHttpTest {
    @Test fun optionalRunPreservesLegacyBodyAndScopesRequests() = runBlocking {
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val client = CourseHttp("http://127.0.0.1:1", "phone", "synthetic-token", "C1", { MockEngine { request ->
            urls += request.url.toString()
            (request.body as? TextContent)?.let { bodies += it.text }
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        } })
        try {
            client.answerMainline(1, "Q1", 0, false, "s1", null)
            client.answerMainline(1, "Q1", 0, false, "s1", null, agentRunId = "run-A")
            assertEquals("{\"itemId\":\"Q1\",\"choice\":0,\"guessed\":false,\"submissionId\":\"s1\",\"dontKnow\":false,\"learning\":false,\"assisted\":false}", bodies[0])
            assertEquals(bodies[0].dropLast(1) + ",\"agentRunId\":\"run-A\"}", bodies[1])
            client.mainline("run-A")
            client.mainlineWeek(1, "run-A")
            assertTrue(urls[2].endsWith("/mainline?agentRunId=run-A"))
            assertTrue(urls[3].endsWith("/mainline/1?layout=shuffled-v1&learning=v1&agentRunId=run-A"))
        } finally { client.close() }
    }
}
