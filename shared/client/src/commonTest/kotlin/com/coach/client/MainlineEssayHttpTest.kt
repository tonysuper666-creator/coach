package com.coach.client

import com.coach.domain.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class MainlineEssayHttpTest {
    private val identity = MainlineEssayIdentity("SYN-Q1@v2", "synthetic-answer", "SYN-A", listOf("SYN-R"))
    private val document = MainlineEssayDocument("SYN-C", identity, "v1", "Two objects make a pair.",
        listOf(MainlineRubricPoint("SYN-R", "Explains the grouping.")), syntheticOnly = true)

    @Test fun pairedClientRejectsWrongIdentityAndDoesNotReuseBodiesAfterRevocation() = runBlocking {
        var status = HttpStatusCode.OK
        var body = LedgerJson.encodeToJsonElement(document).toString()
        val client = CourseHttp("http://127.0.0.1:1", "phone", "synthetic-token", "SYN-C", { MockEngine { request ->
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            assertEquals("phone", request.headers["X-Coach-Device"])
            assertEquals("/v1/courses/SYN-C/mainline/1/essay", request.url.encodedPath)
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        } })
        try {
            assertEquals(document, client.mainlineEssay(1, identity).document)
            body = LedgerJson.encodeToJsonElement(document.copy(course = "SYN-other")).toString()
            assertNull(client.mainlineEssay(1, identity).document)
            body = LedgerJson.encodeToJsonElement(document.copy(identity = identity.copy(attemptId = "another-answer"))).toString()
            assertNull(client.mainlineEssay(1, identity).document)
            body = LedgerJson.encodeToJsonElement(document.copy(rubricPoints = emptyList())).toString()
            assertNull(client.mainlineEssay(1, identity).document)
            status = HttpStatusCode.Unauthorized; body = "{}"
            val denied = client.mainlineEssay(1, identity)
            assertNull(denied.document); assertTrue("无权" in denied.error.orEmpty())
            status = HttpStatusCode.NotImplemented
            assertTrue("尚未接通" in client.mainlineEssay(1, identity).error.orEmpty())
        } finally { client.close() }
    }
}
