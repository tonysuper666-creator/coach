package com.coach.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** TON-1943: services opened to signed-in friends use the friend's Bridge route and leave the owner's AI-call table alone. */
class FriendRoutedStudyServicesTest {
    private fun reply(text: String) = BatchTransportReply(
        text = text, routeChannel = CompanionRouteChannel.HAIKU_REALTIME, model = "claude-haiku-test",
        inputTokens = 10, outputTokens = 5, ttftMs = 1L, elapsedMs = 2L,
    )

    @Test fun booklistAlignForFriendGoesThroughFriendRouteAndSkipsOwnerLedger() = runBlocking {
        val dir = Files.createTempDirectory("coach-friend-booklist-")
        try {
            val ledger = BookTokenLedger(dir)
            val seen = mutableListOf<Pair<String?, String?>>()
            val transport = object : BatchAiTransport {
                override suspend fun generateSection(systemPrompt: String, userPrompt: String, task: String, batchKey: String, maxTokens: Int) =
                    reply("""{"decisions":[]}""").also { seen += null to null }
                override suspend fun generateSectionForFriend(systemPrompt: String, userPrompt: String, task: String, batchKey: String,
                    maxTokens: Int, friend: String?, device: String?) = reply("""{"decisions":[]}""").also { seen += friend to device }
            }
            val input = buildJsonObject {
                put("collectionTitle", "同一书单")
                put("candidates", buildJsonArray { add(buildJsonObject { put("pairId", "p1") }) })
            }
            val book = "booklist:${com.coach.store.sha256("同一书单".toByteArray(Charsets.UTF_8)).take(12)}"
            BooklistSemanticAligner(transport, ledger).align(input, friend = "甲", device = "friend-pc")
            assertEquals(listOf<Pair<String?, String?>>("甲" to "friend-pc"), seen)
            assertTrue(ledger.entriesForBook(book).isEmpty(), "friend call reached the owner's AI-call table")
            BooklistSemanticAligner(transport, ledger).align(input)
            assertEquals(null to null, seen.last())
            assertEquals(1, ledger.entriesForBook(book).size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun courseAlignForFriendUsesFriendTransportAndOwnerOnlyTransportsRefuseFriends() = runBlocking {
        val dir = Files.createTempDirectory("coach-friend-course-align-")
        try {
            val ledger = BookTokenLedger(dir)
            var friendCalls = 0
            var ownerCalls = 0
            val answer = CourseAlignReply("""{"topics":[]}""", CompanionRouteChannel.HAIKU_REALTIME, "claude-test", 10, 5, 2L)
            val transport = object : CourseAlignTransport {
                override suspend fun alignCourse(systemPrompt: String, userPrompt: String, batchKey: String, maxTokens: Int) =
                    answer.also { ownerCalls++ }
                override suspend fun alignCourseForFriend(systemPrompt: String, userPrompt: String, batchKey: String, maxTokens: Int,
                    friend: String, device: String) = answer.also { assertEquals("甲" to "friend-pc", friend to device); friendCalls++ }
            }
            val input = buildJsonObject { put("courseId", "c1"); put("courseTitle", "课程") }
            CourseAlignmentPipeline(transport, ledger).align(input, friend = "甲", device = "friend-pc")
            assertEquals(1 to 0, friendCalls to ownerCalls)
            assertTrue(ledger.entriesForBook("c1").isEmpty(), "friend call reached the owner's AI-call table")

            val ownerOnly = object : CourseAlignTransport {
                override suspend fun alignCourse(systemPrompt: String, userPrompt: String, batchKey: String, maxTokens: Int) = answer
            }
            assertFailsWith<FriendServiceError> { CourseAlignmentPipeline(ownerOnly, ledger).align(input, friend = "甲", device = "friend-pc") }
            assertTrue(ledger.entriesForBook("c1").isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
