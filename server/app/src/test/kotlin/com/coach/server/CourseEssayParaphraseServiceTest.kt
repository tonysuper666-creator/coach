package com.coach.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourseEssayParaphraseServiceTest {
    @Test
    fun evidenceOnlyAnswerReachesBothBlindGradersAndKeepsItsSource() = runBlocking {
        val sentence = "The workshop budget framework limits the combinations of goods that a team can buy."
        val answer = "Our funds cover the drill or the saw, but not the pair."
        val tasks = mutableListOf<String>()
        val transport = object : BatchAiTransport {
            override suspend fun generateSection(
                systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int,
            ): BatchTransportReply {
                tasks += task
                val payload = Json.parseToJsonElement(userPrompt).jsonObject
                check(payload.getValue("points").jsonArray.isEmpty())
                check(payload.getValue("findEvidence").jsonArray.size == 1)
                return BatchTransportReply(
                    text = """{"items":[],"evidence":[{"index":0,"quote":"$answer","correct":true,"gap":"和材料一致"}]}""",
                    routeChannel = CompanionRouteChannel.HAIKU_REALTIME,
                    model = if (task == PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE) "claude-haiku-5-5" else "gpt-6.2-astra",
                    inputTokens = 1, outputTokens = 1, ttftMs = 1, elapsedMs = 1,
                )
            }
        }
        val request = PracticeGradeAndDiagnoseRequest(
            mode = "COURSE_ESSAY", bookId = "manual", topicTitle = "Workshop budget", questionStem = "Explain the trade-off.",
            studentAnswer = answer, generateVariant = false,
            sourcePages = listOf(PracticeSourcePageInput(pdfPage = 19, text = sentence, bookId = "manual")),
        )
        val result = PracticeGradeAndDiagnoseService(transport).process(request)
        val point = result.rubricItems.single()
        assertEquals(listOf(PracticeGradeLimits.TASK_COURSE_ESSAY_NOTE, PracticeGradeLimits.TASK_COURSE_ESSAY_CHECK), tasks)
        assertEquals("USED", point.usage)
        assertEquals(PracticeRubricVerdict.FULL, point.verdict)
        assertTrue(point.studentQuoteVerified)
        assertEquals(answer, point.studentQuote)
        assertEquals("manual", point.bookId)
        assertEquals(19, point.pdfPage)
        assertEquals(sentence, point.quoteAnchor)
    }
}
